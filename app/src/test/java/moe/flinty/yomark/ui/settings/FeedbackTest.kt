package moe.flinty.yomark.ui.settings

import android.content.Intent
import android.net.MailTo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 反馈的两条路：邮件里带了什么，应用预填的栏和 GitHub 上的 issue 表单对不对得上。 */
@RunWith(RobolectricTestRunner::class)
class FeedbackTest {

    // 单元测试的工作目录是 app/
    private val bugForm get() = File("../.github/ISSUE_TEMPLATE/bug.yml").readText()

    @Test fun theDeviceNamesTheMakerOnce() {
        assertThat(deviceSummary("Google", "Pixel 10 Pro", "17", 37)).isEqualTo("Google Pixel 10 Pro · Android 17 (API 37)")
        assertThat(deviceSummary("samsung", "SM-F966N", "16", 36)).isEqualTo("Samsung SM-F966N · Android 16 (API 36)")
        assertThat(deviceSummary("OnePlus", "OnePlus 13", "15", 35)).isEqualTo("OnePlus 13 · Android 15 (API 35)")
    }

    /** mailto 里和 extra 里是同一封信：正文开头空着给人写，最后是版本和机型。 */
    @Test fun theEmailSaysTheSameInTheUriAndTheExtras() {
        val mail = feedbackEmail("有码反馈", "有码 0.18.0 (26) · Play 版", "Google Pixel 10 Pro · Android 17 (API 37)")
        val body = "\n\n——\n有码 0.18.0 (26) · Play 版\nGoogle Pixel 10 Pro · Android 17 (API 37)"
        assertThat(mail.action).isEqualTo(Intent.ACTION_SENDTO)
        val uri = MailTo.parse(mail.dataString!!)
        assertThat(uri.to).isEqualTo(FEEDBACK_EMAIL)
        assertThat(uri.subject).isEqualTo("有码反馈")
        assertThat(uri.body).isEqualTo(body)
        assertThat(mail.getStringArrayExtra(Intent.EXTRA_EMAIL)).asList().containsExactly(FEEDBACK_EMAIL)
        assertThat(mail.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("有码反馈")
        assertThat(mail.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(body)
    }

    /** GitHub 按栏的 id 预填：应用带的每个参数，「问题」表单里都得有同 id 的一栏，不然悄悄地就填不上了。 */
    @Test fun everyPrefilledFieldIsInTheBugForm() {
        val params = Uri.parse(issueUrl("0.18.0 (26)", "Google Pixel 10 Pro · Android 17 (API 37)")).queryParameterNames
        assertThat(params).containsExactly("version", "device")
        params.forEach { assertThat(bugForm).containsMatch("""(?m)^\s+id: $it\s*$""") }
    }

    /** 表单开头让要附截图的改发邮件，写的得是应用里这个地址。 */
    @Test fun theBugFormSendsScreenshotsToTheSameAddress() {
        assertThat(bugForm).contains("mailto:$FEEDBACK_EMAIL")
    }
}
