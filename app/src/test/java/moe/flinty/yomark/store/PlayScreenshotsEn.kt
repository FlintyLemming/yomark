package moe.flinty.yomark.store

import android.graphics.RectF
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 英文商品详情的手机截图（docs/play/screenshots-en/）用的编辑器截图：界面是英文的，样图也是英文的。
 * 只拍上架用的四张（auto、reveal、manual、purpose），做法同 [PlayScreenshots]。不是测试，平时跳过；要重拍时：
 *
 *     YOMARK_PLAY_SHOTS_EN_DIR=$PWD/docs/play/shots-en ./gradlew -Pyomark.freeEdition=false \
 *         :app:testDebugUnitTest --tests '*.PlayScreenshotsEn'
 *     NODE_PATH="$(npm root -g)" node docs/play/src/render.cjs screenshots-en
 *     python3 docs/play/src/finalize.py
 *
 * 和中文的那套分开跑，一次 Gradle 只跑一个类：PhoneRule 的地区在一个 JVM 里只取一次，先跑的那个类的语言说了算。
 * 地区取 en-US，样图里的号码都带 +1。样图是 play/order-page-en.png 与 play/chat-page-en.png，人名、地址、号码全是编的；
 * 英文样图上没有取件码，取件码的规则只认中文字段名。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w412dp-h860dp-xxhdpi")
class PlayScreenshotsEn : PlayScreenshotsBase("YOMARK_PLAY_SHOTS_EN_DIR") {

    override val orderPage = "/play/order-page-en.png"
    override val chatPage = "/play/chat-page-en.png"
    override val purposeText = "For delivery claim use only"

    // 样图里商品那一行（缩略图 + 品名 + 规格），量自 order-page-en.png 的像素：缩略图 (80, 1292)–(209, 1421)
    override val productRow = RectF(68f, 1282f, 744f, 1432f)
}
