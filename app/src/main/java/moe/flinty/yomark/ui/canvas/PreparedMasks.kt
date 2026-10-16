package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MarkerRenderer
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.PixelMaskRenderer
import moe.flinty.yomark.render.PixelateRenderer
import moe.flinty.yomark.render.PreparedMask
import moe.flinty.yomark.render.RendererRegistry
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * 预览里读像素的码（马赛克、模糊）各自算好的样子（见 PixelMaskRenderer），跨帧留着。
 *
 * 导出在同一张位图上一块接一块地画，读像素的码读到的是画到它这一层时底下的样子：模糊盖在色块上，糊的是色块，
 * 色块挡住的字仍然看不见。预览照同一个顺序一块块地画，读像素的码贴的是照它底下那时的样子算好的小图：
 * 它要读的地方底下没有先画的码，就从原图上算；有的话，在工作图上只把那一块照导出的顺序重画一遍（原图加底下那几块），
 * 再从工作图上算。所以贴出来和导出一模一样。
 *
 * 缩放、平移、点选时画布每帧都重画，逐块现算的话十几块模糊一帧要近十毫秒；留着算好的小图，重画只是贴一下。
 * 按「画出来是什么样由什么定」来认（[Node]）：拖框、拖滑杆时变了的那块、读到它的那几块当帧重算，别的照旧用留着的。
 * 早先压着别的码时整张图从头合成一遍，拖框每帧十几毫秒。
 *
 * 画布上已经没有的码随时扔掉（见 [retain]），换了图全部作废。算好的位图不回收：上一帧的显示列表可能还引用着它，交给 GC。
 */
internal class PreparedMasks {

    /**
     * 一块码画出来是什么样：样式与参数、框、手画的那一笔、写的字，读像素的码还要看 [under]——它读的地方底下先画了哪几块
     * （各自的编号，按先后）。编号由 [ids] 发，一个样子一个编号，比的时候不用一层层往下比。
     */
    private data class Node(val look: MaskLook, val quad: Quad, val ink: InkPath?, val text: String?, val under: List<Int>)

    private var source: Bitmap? = null
    private var next = 0
    private val ids = HashMap<Node, Int>()
    private val nodes = HashMap<Int, Node>()
    private val kept = HashMap<Int, PreparedMask>()

    /** [retain] 最近一次排的码（已按 MaskOrder 排好）、各自的编号，和正在揭开的码。 */
    private var plannedFor: List<MaskItem> = emptyList()
    private var plannedIds = IntArray(0)
    private val planned = IdentityHashMap<MaskItem, Int>()
    private var leftFor: List<MaskItem> = emptyList()

    /** 重画底下那几块用的，与图一样大：渲染器按整张图的尺寸定颗粒、笔宽。跨帧复用。 */
    private var work: Bitmap? = null

    /**
     * 照这一份码（[items]，已按 MaskOrder 排好；加上正在揭开的 [leaving]）认好各块，只留它们算好的样子。
     * 每帧画之前调；码没变就什么也不做。
     */
    fun retain(image: SourceImage, items: List<MaskItem>, leaving: List<MaskItem> = emptyList()) {
        reset(image)
        if (sameItems(items, plannedFor) && sameItems(leaving, leftFor)) return
        plannedFor = items
        leftFor = leaving
        planned.clear()
        plannedIds = IntArray(items.size)
        val drawn = ArrayList<RectF>(items.size)
        items.forEachIndexed { i, item ->
            val under = if (MaskOrder.readsPixels(item.look)) {
                val reads = readBounds(image, item.quad, item.look)
                (0 until i).filter { RectF.intersects(drawn[it], reads) }.map { plannedIds[it] }
            } else emptyList()
            val id = idOf(image, item, under)
            plannedIds[i] = id
            planned[item] = id
            drawn += drawBounds(image, item.quad, item.look, item.ink)
        }
        // 揭开的码已经不在 plan 里，照它自己读原图的样子画
        val used = plannedIds.toHashSet()
        leaving.forEach { used += idOf(image, it, emptyList()) }
        ids.values.retainAll(used)
        nodes.keys.retainAll(used)
        kept.keys.retainAll(used)
    }

    /**
     * 照 [registry] 画 [item]（读像素的码），算过的直接贴。[item] 照它在 [retain] 排的那一份里的位置读底下的样子；
     * 不在那一份里、或者 [alone] 时照原图算。
     */
    fun draw(canvas: Canvas, image: SourceImage, item: MaskItem, registry: RendererRegistry, alone: Boolean = false) {
        reset(image)
        val id = planned[item]?.takeIf { !alone } ?: idOf(image, item, emptyList())
        patch(id, image, registry).draw(canvas)
    }

    /**
     * 后台合成动效那张图时顺手算好的各块（见 ComposedMasks）照收下：它们读的也是画到那一层时底下的样子，
     * 和这里算出来的一样。动效停下来的第一帧就不用再算一遍。
     */
    fun adopt(image: SourceImage, composed: ComposedMasks) {
        retain(image, composed.items, leftFor)
        composed.items.forEachIndexed { i, item ->
            if (MaskOrder.readsPixels(item.look)) kept.putIfAbsent(plannedIds[i], composed.patches[i] ?: NOTHING)
        }
    }

    private fun reset(image: SourceImage) {
        if (image.bitmap === source) return
        source = image.bitmap
        ids.clear()
        nodes.clear()
        kept.clear()
        plannedFor = emptyList()
        leftFor = emptyList()
        planned.clear()
        work = null
    }

    /** 渲染器里的绝对像素常数按分析图折算（见 MaskOptions.renderScale），与逐块现算时一样。 */
    private fun idOf(image: SourceImage, item: MaskItem, under: List<Int>): Int {
        val look = item.look.copy(options = item.look.options.copy(renderScale = image.scale))
        val node = Node(look, item.quad, item.ink, item.text, under)
        return ids.getOrPut(node) { next++.also { nodes[it] = node } }
    }

    private fun patch(id: Int, image: SourceImage, registry: RendererRegistry): PreparedMask =
        kept[id] ?: prepare(nodes.getValue(id), image, registry).also { kept[id] = it }

    private fun prepare(node: Node, image: SourceImage, registry: RendererRegistry): PreparedMask {
        val renderer = registry[node.look.style] as PixelMaskRenderer
        // 近似色在原图上取，与导出一样（见 MaskOrder.pinNearColor）：马赛克框太小退成色块时用得上
        val options = MaskOrder.pinNearColor(node.look.options, image.bitmap, node.quad)
        if (node.under.isEmpty()) return renderer.prepare(image.bitmap, node.quad, options) ?: NOTHING

        // 底下读像素的那几块先算好：算它们也可能要用工作图
        node.under.forEach { if (registry[nodes.getValue(it).look.style] is PixelMaskRenderer) patch(it, image, registry) }
        val bmp = work?.takeIf { it.width == image.width && it.height == image.height }
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { work = it }
        val area = Rect().also { readBounds(image, node.quad, node.look).roundOut(it) }
        if (area.intersect(0, 0, image.width, image.height)) {
            // 只换回要读的那一块；底下那几块整块画，不裁：裁过的路径抗锯齿出来差一点，和导出就对不上了
            val canvas = Canvas(bmp)
            canvas.drawBitmap(image.bitmap, area, area, COPY)
            node.under.forEach { drawNode(canvas, image, it, registry) }
        }
        return renderer.prepare(bmp, node.quad, options) ?: NOTHING
    }

    /** 把编号 [id] 的那块照预览的画法画到 [canvas] 上。读像素的那几块已经算好了。 */
    private fun drawNode(canvas: Canvas, image: SourceImage, id: Int, registry: RendererRegistry) {
        val node = nodes.getValue(id)
        val renderer = registry[node.look.style]
        if (renderer is PixelMaskRenderer) patch(id, image, registry).draw(canvas)
        else renderer.render(canvas, image.bitmap, node.quad, node.look.options, node.ink, node.text)
    }

    private companion object {
        /** 算过了，什么也不画（框出了图、小得放不下）：记下来，免得每帧再算一遍。 */
        val NOTHING = object : PreparedMask {
            override fun draw(canvas: Canvas) {}
        }

        /** 连 alpha 一起覆盖：透明底的 PNG 上，上一次重画的码不能透过来。 */
        val COPY = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        val PIXELATE = PixelateRenderer()

        /** 逐个比是不是同一个：每帧排出来的是新的表，里头的码没变就还是那几个。 */
        fun sameItems(a: List<MaskItem>, b: List<MaskItem>) = a.size == b.size && a.indices.all { a[it] === b[it] }

        /**
         * 读像素的码会读到的范围。模糊只读外接框；马赛克的块数向上取整，右边、下边最多多读出一块。
         * 外接框取整也会多出不到一像素，各放宽一像素。
         */
        fun readBounds(image: SourceImage, quad: Quad, look: MaskLook): RectF {
            val extra = if (look.style == MaskStyle.PIXELATE) {
                PIXELATE.blockSizeFor(min(image.width, image.height).toFloat(), look.options.pixelBlockDivisor, image.scale)
            } else 0f
            return RectF(quad.bounds()).apply {
                left -= 1f; top -= 1f
                right += 1f + extra; bottom += 1f + extra
            }
        }

        /**
         * 一块码画出来会碰到的范围，宁大勿小。打码的几种不出框，抗锯齿多出不到一像素；马克笔拉的框边是抖的，
         * 抖出框外；线（马克笔、画笔）的框比笔宽还窄时，线探出框外，按半个笔宽放宽；箭头的框被压扁了，头探出框外，按头的半宽放宽。
         */
        fun drawBounds(image: SourceImage, quad: Quad, look: MaskLook, ink: InkPath? = null): RectF {
            val shortSide = min(image.width, image.height).toFloat()
            val out = 1f + when (look.style) {
                MaskStyle.MARKER -> look.options.halfWidth(look.style, shortSide) + quad.shortEdge() * MarkerRenderer.JITTER_RATIO
                MaskStyle.PEN -> Ink.margin(look.options.halfWidth(look.style, shortSide), ink?.arrow == true)
                else -> 0f
            }
            return RectF(quad.bounds()).apply { inset(-out, -out) }
        }
    }
}
