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
 * 引导里那张样图上现成的结果：不现场识别，框是拿真识别量好写死的。样图分语言，一张一份（[ZH]、[EN]）。
 *
 * 两张图都是商店截图用的单聊页无损转成的 WebP，像素一模一样：中文的 drawable-zh-nodpi/tour_chat.webp
 * 照 docs/play/src/chat-page.html 渲染（app/src/test/resources/play/chat-page.png），其余语言用的
 * drawable-nodpi/tour_chat.webp 照 chat-page-en.html（chat-page-en.png）。人名、地址、号码全是编的，
 * 邮箱和网址是保留域名 example.com。框是用 PP-OCR 加出厂规则在图上跑出来的，单位是图上的像素；
 * 规则或识别一变，TourSampleTest 就会报不对，照它打出来的数改这里。重新渲染 PNG 的话，WebP 也要跟着重新转。
 *
 * 装进来的是哪一张由字符串 tour_sample 说了算（[of]）：它和图放在同一个语言限定符下，系统挑语言时两样一起挑。
 *
 * @param key 和 tour_sample 对得上的名字
 * @param auto 出厂设置下识别出来直接打码的几处
 * @param chat 实验室里打开「识别聊天记录」后多出来的：顶栏的聊天标题，四个头像。按读的顺序，标题在前
 * @param lines 文字识别认出的每一段字（TextLine.segments）：点字打码点的就是它们，和编辑器里一样
 * @param linkIndex 「点文字打码」让用户点的那一段：链接，出厂不打码，又是该遮的
 */
internal class TourSample private constructor(
    val key: String,
    val auto: List<MaskItem>,
    val chat: List<MaskItem>,
    val lines: List<Quad>,
    val linkIndex: Int,
) {
    val link: Quad get() = lines[linkIndex]
    val linkCenter: PointF get() = link.bounds().let { PointF(it.centerX(), it.centerY()) }

    /** 点一段字打上的码，和编辑器里点出来的一样（EditorViewModel.maskLine）。id 跟着段走，同一段只有一块。 */
    val lineItems: List<MaskItem> = lines.mapIndexed { i, quad ->
        MaskItem(lineId(i), quad, SensitiveKind.MANUAL, DetectorSource.LINE)
    }

    /**
     * 眼下图上的码：出厂打码的几处，聊天的前 [chatShown] 处（实验室开着时），
     * 去掉 [toggled] 里点掉的，再按点的先后叠上点出来的段。每一块都是上面这几张表里的同一个对象。
     *
     * @param toggled 点过、和出厂不一样的：识别出的是点掉了，「line-」开头的是点上了
     */
    fun plan(chatShown: Int, toggled: List<String>): MaskPlan {
        val defaults = defaults(chatShown)
        val off = toggled.toSet()
        val lines = toggled.mapNotNull { id -> lineItems.firstOrNull { it.candidateId == id } }
        return MaskPlan(defaults.filterNot { it.candidateId in off } + lines)
    }

    /** 识别出来的那几块：出厂的几处加上聊天的前 [chatShown] 处。 */
    fun defaults(chatShown: Int): List<MaskItem> = auto + chat.take(chatShown.coerceIn(0, chat.size))

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1680

        fun lineId(index: Int) = "line-$index"

        private fun item(id: String, kind: SensitiveKind, l: Float, t: Float, r: Float, b: Float) =
            MaskItem(id, Quad.fromRect(RectF(l, t, r, b)), kind, DetectorSource.RULE)

        /** 中文的那张，框是 2026-10-16 在中国地区下量的：地址、电话、取件码、邮箱。 */
        val ZH = TourSample(
            key = "zh",
            auto = listOf(
                item("tour-address", SensitiveKind.POSTAL_ADDRESS, 292.59494f, 469.12683f, 848.3533f, 515.1083f),
                item("tour-phone", SensitiveKind.PHONE, 180.49374f, 837.5792f, 453.1352f, 879.608f),
                item("tour-pickup", SensitiveKind.PICKUP_CODE, 671.8634f, 958.47327f, 844.49207f, 1001.52673f),
                item("tour-email", SensitiveKind.EMAIL, 176.77385f, 1395.6709f, 640.8398f, 1444.4933f),
            ),
            chat = listOf(
                item("tour-chat-title", SensitiveKind.CHAT_TITLE, 464f, 140f, 601f, 187f),
                item("tour-avatar-0", SensitiveKind.CHAT_AVATAR, 26.24f, 314.24f, 133.76f, 421.76f),
                item("tour-avatar-1", SensitiveKind.CHAT_AVATAR, 946.24f, 558.24f, 1053.76f, 665.76f),
                item("tour-avatar-2", SensitiveKind.CHAT_AVATAR, 26.24f, 680.24f, 133.76f, 787.76f),
                item("tour-avatar-3", SensitiveKind.CHAT_AVATAR, 26.24f, 1122.24f, 133.76f, 1229.76f),
            ),
            lines = listOf(
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
            ).map { Quad.fromRect(it) },
            linkIndex = 12,
        )

        /** 英文的那张，框是 2026-10-16 在美国地区下量的：地址、电话、人名、邮箱。 */
        val EN = TourSample(
            key = "en",
            auto = listOf(
                item("tour-address", SensitiveKind.POSTAL_ADDRESS, 339.11493f, 466.3193f, 785.66833f, 509.3057f),
                item("tour-phone", SensitiveKind.PHONE, 178.50555f, 832.3007f, 518.2687f, 877.2305f),
                item("tour-name", SensitiveKind.PERSON_NAME, 308.90555f, 954.1041f, 510.18857f, 997.1459f),
                item("tour-email", SensitiveKind.EMAIL, 176.40805f, 1386.912f, 680.4064f, 1434.9625f),
            ),
            chat = listOf(
                item("tour-chat-title", SensitiveKind.CHAT_TITLE, 401f, 142f, 659f, 191f),
                item("tour-avatar-0", SensitiveKind.CHAT_AVATAR, 26.24f, 310.24f, 133.76f, 417.76f),
                item("tour-avatar-1", SensitiveKind.CHAT_AVATAR, 946.24f, 554.24f, 1053.76f, 661.76f),
                item("tour-avatar-2", SensitiveKind.CHAT_AVATAR, 26.24f, 676.24f, 133.76f, 783.76f),
                item("tour-avatar-3", SensitiveKind.CHAT_AVATAR, 26.24f, 1116.24f, 133.76f, 1223.76f),
            ),
            lines = listOf(
                RectF(53.43943f, 26.343084f, 159.94766f, 69.90691f),       // 10:24
                RectF(48.671013f, 133.90335f, 85.97333f, 188.16919f),      // V（返回的「<」）
                RectF(399.17697f, 136.33754f, 664.49225f, 190.69365f),     // Jordan Ellis
                RectF(398.91888f, 253.10947f, 682.16986f, 292.67178f),     // Yesterday 9:46 PM
                RectF(177.53479f, 341.78003f, 841.459f, 391.60046f),       // Come over for dinner on Saturday?
                RectF(178.92845f, 466.3193f, 785.66833f, 509.3057f),       // We're at 2847 Oak Ave, Unit 12C
                RectF(453.4181f, 588.9547f, 903.1142f, 631.6703f),         // Sure! What time works?
                RectF(177.0795f, 708.0915f, 812.5452f, 758.63007f),        // Noon. Call me when you get here
                RectF(178.50557f, 832.3007f, 518.2687f, 877.2305f),        // +1 (202) 555-0147
                RectF(178.90091f, 954.1041f, 832.51044f, 997.1459f),       // Or ask Ms. Rivera at the front desk
                RectF(426.31836f, 1058.292f, 653.6817f, 1097.4893f),       // Today 9:12 AM
                RectF(177.05182f, 1146.9475f, 778.8111f, 1197.3495f),     // Photos from the party are here:
                RectF(178.44827f, 1206.2457f, 764.37524f, 1256.072f),      // photos.example.com/s/8fK2xQ
                RectF(176.36763f, 1327.8092f, 717.463f, 1375.9408f),       // If they won't load, email me:
                RectF(176.40805f, 1386.912f, 680.4064f, 1434.9625f),       // jordan.ellis@example.com
                RectF(921.9485f, 1589.0292f, 1021.3984f, 1634.2521f),      // Send
            ).map { Quad.fromRect(it) },
            linkIndex = 12,
        )

        val ALL = listOf(ZH, EN)

        /** 这一套资源里装的是哪一张。 */
        fun of(res: Resources): TourSample {
            val key = res.getString(R.string.tour_sample)
            return ALL.first { it.key == key }
        }

        /**
         * 解成软件位图：磨砂、模糊码都要读像素，ImageDecoder 默认给的硬件位图读不了。
         * 放在 drawable-nodpi（和 drawable-zh-nodpi）里，不按屏幕密度放大，上面的框才对得上。
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
}
