package moe.flinty.yomark.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Locale
import moe.flinty.yomark.R

/*
 * 反馈（关于页的「反馈」一组，2026-10-16）有两条路：
 *
 * - 邮件：只有开发者看得到，能附图。识别不准就走这条：截图和调试记录里都是用户的原文；
 * - GitHub Issues：所有人都看得到，能去重、看进度。闪退、界面不对、功能建议走这条。
 *   表单在仓库的 .github/ISSUE_TEMPLATE 里，[issueUrl] 带的参数名就是表单里那几栏的 id（GitHub 按 id 预填），
 *   两边改了都得对上，见 FeedbackTest。
 *
 * 两条都交给别的应用去发（邮件应用、浏览器），应用自己不连这两处。
 */

/** 收反馈的邮箱。issue 表单开头也写着它。 */
const val FEEDBACK_EMAIL = "yomark@flinty.moe"

/** 新建 issue 先到选模板那一页（「问题」「建议」）：预填的参数会带到选中的表单里。 */
internal const val NEW_ISSUE_URL = "https://github.com/FlintyLemming/yomark/issues/new/choose"

/** 一封反馈邮件：收件人、主题填好，正文留空给人写，下面一道线后附上版本和机型。 */
internal fun feedbackEmail(subject: String, version: String, device: String): Intent {
    val body = "\n\n——\n$version\n$device"
    val uri = Uri.parse("mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
    // 邮件应用有的读 mailto 里的，有的读 extra，两处都放
    return Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
}

/** 新建 issue 的网址，版本和机型预先填进表单。 */
internal fun issueUrl(version: String, device: String): String =
    Uri.parse(NEW_ISSUE_URL).buildUpon()
        .appendQueryParameter("version", version)
        .appendQueryParameter("device", device)
        .build()
        .toString()

/** 机型和系统版本，如「Google Pixel 10 Pro · Android 17 (API 37)」。型号里已经带着厂商名的，不再写一遍。 */
internal fun deviceSummary(
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
    release: String = Build.VERSION.RELEASE,
    sdk: Int = Build.VERSION.SDK_INT,
): String {
    val name = if (model.startsWith(manufacturer, ignoreCase = true)) {
        model
    } else {
        "${manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) }} $model"
    }
    return "$name · Android $release (API $sdk)"
}

/** 没有邮件应用时，把地址放进剪贴板。返回放没放成。 */
internal fun copyFeedbackEmail(context: Context): Boolean = runCatching {
    val clip = ClipData.newPlainText(context.getString(R.string.feedback_email), FEEDBACK_EMAIL)
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}.isSuccess
