package moe.flinty.yomark.ui.onboarding

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.R
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.SensitiveKind

/**
 * 引导里那张样图和它上面现成的结果：不现场识别，框是拿真识别量好写死的。
 *
 * 图是 app/src/test/resources/play/chat-page.png 无损转成的 WebP，像素一模一样（商店截图用的那张单聊页，
 * 照 docs/play/src/chat-page.html 渲染：人名、地址、号码全是编的，138 0013 8000 是文档里常拿来举例的号码，
 * 邮箱和网址是保留域名 example.com）。框是 2026-10-16 用 PP-OCR 加出厂规则在这张图上跑出来的，
 * 单位是图上的像素；规则或识别一变，TourSampleTest 就会报不对，照它打出来的数改这里。
 * 重新渲染那张 PNG 的话，这张图也要跟着重新转。
 */
internal object TourSample {
    const val WIDTH = 1080
    const val HEIGHT = 1680

    private fun item(id: String, kind: SensitiveKind, l: Float, t: Float, r: Float, b: Float) =
        MaskItem(id, Quad.fromRect(RectF(l, t, r, b)), kind, DetectorSource.RULE)

    /** 出厂设置下识别出来直接打码的四处：地址、电话、取件码、邮箱。 */
    val AUTO: List<MaskItem> = listOf(
        item("tour-address", SensitiveKind.POSTAL_ADDRESS, 292.59494f, 469.12683f, 848.3533f, 515.1083f),
        item("tour-phone", SensitiveKind.PHONE, 180.49374f, 837.5792f, 453.1352f, 879.608f),
        item("tour-pickup", SensitiveKind.PICKUP_CODE, 671.8634f, 958.47327f, 844.49207f, 1001.52673f),
        item("tour-email", SensitiveKind.EMAIL, 176.77385f, 1395.6709f, 640.8398f, 1444.4933f),
    )

    /** 实验室里打开「识别聊天记录」后多出来的：顶栏的聊天标题，四个头像。按读的顺序，标题在前。 */
    val CHAT: List<MaskItem> = listOf(
        item("tour-chat-title", SensitiveKind.CHAT_TITLE, 464f, 140f, 601f, 187f),
        item("tour-avatar-0", SensitiveKind.CHAT_AVATAR, 26.24f, 314.24f, 133.76f, 421.76f),
        item("tour-avatar-1", SensitiveKind.CHAT_AVATAR, 946.24f, 558.24f, 1053.76f, 665.76f),
        item("tour-avatar-2", SensitiveKind.CHAT_AVATAR, 26.24f, 680.24f, 133.76f, 787.76f),
        item("tour-avatar-3", SensitiveKind.CHAT_AVATAR, 26.24f, 1122.24f, 133.76f, 1229.76f),
    )

    /** 文字识别认出的每一段字（TextLine.segments）：点字打码点的就是它们，和编辑器里一样。 */
    val LINES: List<Quad> = listOf(
        RectF(51.822582f, 26.90625f, 159.3871f, 72.625f),          // 10:24
        RectF(458.64362f, 133.73576f, 608.2917f, 192.20166f),      // 王小明
        RectF(47.647747f, 134.74504f, 86.904854f, 189.10982f),     // <
        RectF(459.7372f, 253.56346f, 620.26227f, 295.55518f),      // 昨天 21:46
        RectF(179.62044f, 347.79575f, 508.44397f, 392.67288f),     // 周六来我家吃饭吧
        RectF(178.27415f, 468.92325f, 848.35254f, 515.10834f),     // 地址：长江路88号阳光新村5幢302室
        RectF(528.3171f, 590.9192f, 880.4733f, 638.4558f),         // 好嘞，几点到合适？
        RectF(180.07259f, 714.65625f, 710.49194f, 757.53125f),     // 中午十二点，到了给我打电话
        RectF(180.49374f, 837.5792f, 453.1352f, 879.608f),         // 138 0013 8000
        RectF(179.98376f, 958.47327f, 844.49207f, 1001.52673f),    // 顺路帮我取个快递，取件码 5-1-0307
        RectF(462.0173f, 1062.4375f, 620.1601f, 1103.1875f),       // 今天 09:12
        RectF(182.42036f, 1156.7021f, 565.5231f, 1199.2347f),      // 上次聚会的照片在这：
        RectF(177.4594f, 1214.3567f, 709.8429f, 1264.7828f),       // pan.example.com/s/8fK2xQ
        RectF(179.6881f, 1335.5198f, 483.33597f, 1380.261f),       // 原图发你邮箱了：
        RectF(176.77612f, 1395.671f, 640.8375f, 1444.493f),        // wangxm@example.com
        RectF(934.9536f, 1586.782f, 1022.54645f, 1638.6868f),      // 发送
    ).map { Quad.fromRect(it) }

    /** 「点文字打码」让用户点的那一段：网盘链接，出厂不打码，又是该遮的。 */
    const val LINK_INDEX = 12
    val LINK: Quad get() = LINES[LINK_INDEX]
    val LINK_CENTER: PointF get() = LINK.bounds().let { PointF(it.centerX(), it.centerY()) }

    /** 点一段字打上的码，和编辑器里点出来的一样（EditorViewModel.maskLine）。id 跟着段走，同一段只有一块。 */
    val LINE_ITEMS: List<MaskItem> = LINES.mapIndexed { i, quad ->
        MaskItem(lineId(i), quad, SensitiveKind.MANUAL, DetectorSource.LINE)
    }

    fun lineId(index: Int) = "line-$index"

    /**
     * 眼下图上的码：出厂打码的四处，聊天的前 [chatShown] 处（实验室开着时），
     * 去掉 [toggled] 里点掉的，再按点的先后叠上点出来的段。每一块都是上面这几张表里的同一个对象。
     *
     * @param toggled 点过、和出厂不一样的：识别出的是点掉了，「line-」开头的是点上了
     */
    fun plan(chatShown: Int, toggled: List<String>): MaskPlan {
        val defaults = defaults(chatShown)
        val off = toggled.toSet()
        val lines = toggled.mapNotNull { id -> LINE_ITEMS.firstOrNull { it.candidateId == id } }
        return MaskPlan(defaults.filterNot { it.candidateId in off } + lines)
    }

    /** 识别出来的那几块：出厂的四处加上聊天的前 [chatShown] 处。 */
    fun defaults(chatShown: Int): List<MaskItem> = AUTO + CHAT.take(chatShown.coerceIn(0, CHAT.size))

    /**
     * 解成软件位图：磨砂、模糊码都要读像素，ImageDecoder 默认给的硬件位图读不了。
     * 放在 drawable-nodpi 里，不按屏幕密度放大，上面的框才对得上。
     */
    fun load(res: Resources): SourceImage {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeResource(res, R.drawable.tour_chat, options)
        return SourceImage(bitmap, 1f, bitmap.width, bitmap.height, "image/webp")
    }
}
