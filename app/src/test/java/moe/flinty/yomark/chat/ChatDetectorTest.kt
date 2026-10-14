package moe.flinty.yomark.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型：PP-OCR 读出来的行加上位图，走 [ChatDetector] 这一层。
 * 版面规则在合成页上的细账在 [ChatLayoutTest] 里，这里看的是真 OCR 的框交给它也认得出。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDetectorTest {

    /** 识别一次要一两秒，整个类共用。 */
    companion object {
        private val cached = HashMap<String, Pair<SourceImage, List<TextLine>>>()
    }

    private fun read(res: String): Pair<SourceImage, List<TextLine>> = cached.getOrPut(res) {
        val bytes = ChatDetectorTest::class.java.getResourceAsStream(res)!!.readBytes()
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
        val image = SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
        image to runBlocking { PaddleTextRecognizer(ApplicationProvider.getApplicationContext()).recognize(image) }
    }

    private fun detect(res: String, masked: Boolean = true): List<Candidate> {
        val (image, lines) = read(res)
        return runBlocking { ChatDetector(masked).analyze(image, lines) }
    }

    private fun List<Candidate>.of(kind: SensitiveKind) = filter { it.kind == kind }

    /** 上架截图用的单聊页：顶栏「王小明」，左边三个橙色圆头像，右边一个蓝色的。 */
    @Test fun `the store screenshot chat gets its title and avatars masked`() {
        val found = detect("/play/chat-page.png")
        val title = found.of(SensitiveKind.CHAT_TITLE).single().quad.bounds()
        assertThat(title.left).isGreaterThan(440f)
        assertThat(title.right).isLessThan(620f)
        assertThat(title.top).isGreaterThan(120f)
        assertThat(title.bottom).isLessThan(200f)
        val avatars = found.of(SensitiveKind.CHAT_AVATAR).map { it.quad.bounds() }
        assertThat(avatars.count { it.centerX() < 200f }).isEqualTo(3)
        assertThat(avatars.count { it.centerX() > 880f }).isEqualTo(1)
        // 单聊页上没有昵称；消息里的字一个都不该被当成昵称
        assertThat(found.of(SensitiveKind.CHAT_NAME)).isEmpty()
        assertThat(found.all { it.enabledByDefault }).isTrue()
    }

    @Test fun `outlined setting only outlines`() {
        assertThat(detect("/play/chat-page.png", masked = false).none { it.enabledByDefault }).isTrue()
    }

    /** 不是聊天的页（订单页、物流页）上什么都不出。 */
    @Test fun `pages that are not chats are left alone`() {
        assertThat(detect("/readme/order-page.png")).isEmpty()
        assertThat(detect("/ppocr/logistics-page.png")).isEmpty()
    }

    /**
     * 合成的微信群聊页，缩到 540 宽（1080 宽的手机截图进识别时就是这个尺寸），字最小的昵称只有十二三个像素高：
     * 真 OCR 读这么小的字会漏、会拼行，这里只要求大头认得出。
     */
    @Test fun `a downscaled group chat still gets most avatars and names`() {
        val fx = ChatFixtures.named("chat-003-wechat-light-group")
        val found = detect("/chat/${fx.name}.png")
        assertThat(found.of(SensitiveKind.CHAT_TITLE)).hasSize(1)
        val a = ChatFixtures.Tally()
        ChatFixtures.tally(found.of(SensitiveKind.CHAT_AVATAR).map { it.box() }, fx.avatars, a)
        assertWithMessage("头像 ${a.hit}/${a.truth}").that(a.recall).isAtLeast(0.8f)
        val n = ChatFixtures.Tally()
        ChatFixtures.tally(found.of(SensitiveKind.CHAT_NAME).map { it.box() }, fx.names.map { it.box }, n)
        assertWithMessage("昵称 ${n.hit}/${n.truth}").that(n.recall).isAtLeast(0.5f)
    }

    /** 候选的框比找到的框放宽了一圈（头像 6%、字两个像素），配对时缩回去比。 */
    private fun Candidate.box(): Box {
        val r = quad.bounds()
        val pad = if (kind == SensitiveKind.CHAT_AVATAR) maxOf(2f, r.width() * 0.06f / 1.12f) else 2f
        return Box(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
    }
}
