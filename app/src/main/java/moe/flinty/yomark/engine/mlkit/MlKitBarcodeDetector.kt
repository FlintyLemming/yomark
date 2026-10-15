package moe.flinty.yomark.engine.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.RegionDetector
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 条码候选的构造（2026-09-04 增补设计 §1）。
 *
 * 抽成对象是为了能单测：ML Kit 的 Barcode 造不出来，但「给定四边形与解码与否，
 * 该产出什么状态的候选」这件事本身跟 ML Kit 无关。
 */
object BarcodeCandidates {

    /**
     * 报上来的都打码。
     *
     * @param decodable 内容解得出来，或者紧挨着一个解得出的同样大小的码（[BarcodeHits.besideDecoded]）。
     *   其余解不出的（potential）只有宽松模式才报：多半只是布料印花、格纹这类有规律的纹理——真机上商品图就是这么被整块涂黑的，
     *   彩色照片在进这里之前已经被 [TwoInks] 筛掉，剩下的照样打码，宽松模式要的就是宁可多遮。置信度低一档。
     */
    fun from(index: Int, quad: Quad, decodable: Boolean) = Candidate(
        id = "barcode-$index",
        quad = quad.expand(marginFor(quad)),
        kind = SensitiveKind.BARCODE,
        source = DetectorSource.BARCODE,
        confidence = if (decodable) CONFIDENCE_DECODED else CONFIDENCE_POTENTIAL,
    )

    /**
     * cornerPoints 直接拿来打码会露边：ML Kit 给的是**整数像素下标**，右、下两边落在
     * 最后一列/行深色像素上而不是它的外沿，再加上向下取整，框的右边和下边系统性地短
     * 1–3 px（识别图坐标，模拟器上逐一比对合成二维码的真实边界量出来的；左、上两边反而偏外）。
     * 识别图是原图降采样来的，导出时按比例放大，原图上就是 2–6 px 的一条码露在色块外面。
     *
     * 固定的几个像素兜住取整；按短边再加一点，兜住 ML Kit 对二维码第四个角（没有定位图案，
     * 靠外推）的估计误差。二维码四周按规范留 4 个模块宽的静区，这点外扩落在静区里，
     * 盖不到旁边的字。
     */
    internal fun marginFor(quad: Quad): Float = MARGIN_PX + quad.shortEdge() * MARGIN_RATIO

    private const val CONFIDENCE_DECODED = 0.98f
    private const val CONFIDENCE_POTENTIAL = 0.5f
    private const val MARGIN_PX = 3f
    private const val MARGIN_RATIO = 0.02f
}

/**
 * 条码检测（bundled）：二维码与条形码。
 *
 * 用 cornerPoints 而不是 boundingBox —— 倾斜拍摄的二维码用外接矩形会盖住旁边的字。
 *
 * **绝不把 barcode.rawValue 写进日志、状态或任何持久化位置**：那是敏感内容本身。
 * 这个类只关心「哪里有一个条码」，不关心它是什么。
 */
class MlKitBarcodeDetector(
    private val strategy: BarcodeOption = BarcodeOption.LOOSE,
) : RegionDetector {

    override val id = "mlkit-barcode-${strategy.name.lowercase()}"
    override val kind = SensitiveKind.BARCODE

    /**
     * 两行配置，管的是两件相反的事，别把它们混起来看：
     *
     * `setBarcodeFormats` **收窄**格式到 [BarcodeFormats.ALLOWED]。不设置等于全格式开启，
     * 而全格式里有三种没有强制校验位，会把按钮和文字解成垃圾数字再整块涂黑（见 BarcodeFormats）。
     *
     * `enableAllPotentialBarcodes` **放宽**到连解不出内容的条码也返回。打码工具只关心
     * 「这里有一个条码」，从来不需要它的内容——默认配置下 ML Kit 只报解码成功的条码，
     * 倾斜、轻微失焦、缩放过的二维码会被静默丢掉（自造的 36 张评测图里丢了 6 张）。
     * 漏检是事故，误报只是麻烦。
     *
     * 收窄格式**之后**再放宽解码要求，两者不冲突：格式白名单决定「拿哪几种形状去匹配」，
     * potential 决定「匹配上了但校验没过要不要报」。
     *
     * 严格模式也放宽：解不出的码挨着一个解出来的码时要认（[BarcodeHits.besideDecoded]），
     * 不放宽就根本拿不到它。其余解不出的严格模式丢掉，宽松模式过一道像素判断（[TwoInks]）后也打码：
     * 放宽之后 ML Kit 会把彩色照片（真机上是一张酒店缩略图）也当疑似条码报上来。
     */
    private val client by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    BarcodeFormats.ALLOWED.first(),
                    *BarcodeFormats.ALLOWED.drop(1).toIntArray(),
                )
                .enableAllPotentialBarcodes()
                .build()
        )
    }

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val hits = scan(image.bitmap) { x, y -> PointF(x, y) } + tiles(image)
        // 疑似条码要逐像素看一遍（TwoInks），不放在主线程上
        return withContext(Dispatchers.Default) {
            val codes = BarcodeHits.dedupe(hits)
            val decoded = codes.filter { it.decoded }.map { it.quad }
            codes.mapIndexedNotNull { i, hit ->
                val real = hit.decoded || BarcodeHits.besideDecoded(hit.quad, decoded)
                if (!real) {
                    if (strategy == BarcodeOption.STRICT) return@mapIndexedNotNull null
                    // 解不出内容的，像素上不是两种墨色（彩色照片）就整个丢掉，连框都不画。
                    // 这里用的是外扩之前的框：外扩出来的那圈是静区，不该算进墨色统计。
                    // 判不了（读像素出错）就留着：这一步只许少报误报，不许让整个条码检测失败。
                    if (!runCatching { TwoInks.looksPrinted(image.bitmap, hit.quad) }.getOrDefault(true)) {
                        return@mapIndexedNotNull null
                    }
                }
                BarcodeCandidates.from(i, hit.quad, real)
            }
        }
    }

    /** 扫一张图，角点用 [toImage] 换到识别图坐标。[keep] 按图上的角点、解没解出决定留不留。 */
    private suspend fun scan(
        bitmap: Bitmap,
        keep: (corners: List<PointF>, decoded: Boolean) -> Boolean = { _, _ -> true },
        toImage: (Float, Float) -> PointF,
    ): List<BarcodeHits.Hit> =
        client.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { code ->
            val corners = code.cornerPoints?.takeIf { it.size >= 4 }?.take(4)?.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                ?: code.boundingBox?.let { Quad.fromRect(RectF(it)).points() }
                ?: return@mapNotNull null
            // rawValue 只在这里做一次「有没有」的判断，绝不读出来、更不落盘。
            val decoded = BarcodeHits.decoded(code.rawValue)
            if (!keep(corners, decoded)) return@mapNotNull null
            val p = corners.map { toImage(it.x, it.y) }
            BarcodeHits.Hit(Quad(p[0], p[1], p[2], p[3]), decoded)
        }

    /**
     * 整张扫的不是原分辨率时，回原图上分块再扫一遍：识别图是降采样过的（照片），或者长边超过 2048（截图按原图识别，长截图更长）。
     *
     * ML Kit 自己会把长边超过 2048 的图缩到 2048 再找（见 [BarcodeHits.TILE]），整张原图喂给它跟喂识别图一样；
     * 切成 2048 的块，每块按原分辨率看。真机拍的两张 4096×3072 的照片，桌上 SIM 卡贴纸的四个二维码，
     * 整张扫一张照片一个没找到、另一张解出一个；分块扫各解出一到两个，剩下的也都找到了（解不出，挨着解出来的，见
     * [BarcodeHits.besideDecoded]）。6 块在模拟器上 0.8–0.9 s，跟文字识别并行跑。
     *
     * 读不了原图（文件被换图清掉了、格式不支持分块读）就算了，整张扫的结果照用。
     */
    private suspend fun tiles(image: SourceImage): List<BarcodeHits.Hit> {
        val original = image.original ?: return emptyList()
        if (!BarcodeHits.needsTiles(image.scale, image.width, image.height)) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(original.file.path)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(original.file.path, false)
                try {
                    val sample = BarcodeHits.sampleSize(max(original.width, original.height))
                    val tile = BarcodeHits.TILE * sample
                    val overlap = BarcodeHits.OVERLAP * sample
                    val hits = ArrayList<BarcodeHits.Hit>()
                    for ((top, bottom) in BarcodeHits.spans(original.height, tile, overlap)) {
                        for ((left, right) in BarcodeHits.spans(original.width, tile, overlap)) {
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val bmp = decoder.decodeRegion(Rect(left, top, right, bottom), opts) ?: continue
                            // 块边切过的疑似码不要（框是歪的），旁边那块会整个报它；解出来的照留
                            val whole = { corners: List<PointF>, decoded: Boolean ->
                                decoded || BarcodeHits.wholeInTile(
                                    corners, bmp.width, bmp.height,
                                    cutLeft = left > 0, cutTop = top > 0, cutRight = right < original.width, cutBottom = bottom < original.height,
                                )
                            }
                            try {
                                hits += scan(bmp, whole) { x, y ->
                                    val up = original.toUpright(left + x * sample, top + y * sample)
                                    PointF(up.x * image.scale, up.y * image.scale)
                                }
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                    hits
                } finally {
                    decoder.recycle()
                }
            }.getOrDefault(emptyList())
        }
    }
}
