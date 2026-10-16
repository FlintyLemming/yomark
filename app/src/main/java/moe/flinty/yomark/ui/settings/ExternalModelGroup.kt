package moe.flinty.yomark.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import moe.flinty.yomark.R
import moe.flinty.yomark.engine.genai.ExternalModelException
import moe.flinty.yomark.engine.genai.ExternalModelSettings
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * AI 页识别引擎下面的「外部模型」一组（实验室里打开了才有）：接口地址、API Key、模型，点一行弹框改，保存才写进设置；
 * 下面一行测试连接，问一句最短的话，说可用不可用、用了几秒。
 *
 * 改动在弹框里点保存才写：边打字边写的话，编辑器每收到一次就要重新探测一遍。
 * key 只说填没填，不显示出来。
 */
@Composable
internal fun ExternalModelGroup(
    settings: ExternalModelSettings,
    onChange: ((ExternalModelSettings) -> ExternalModelSettings) -> Unit,
    onTest: suspend () -> Long,
) {
    var editing by remember { mutableStateOf<ExternalField?>(null) }
    // 测试结果跟着设置走：改了哪一项，上一次的结果就不算数了
    var test by remember(settings) { mutableStateOf<TestState>(TestState.Idle) }
    val scope = rememberCoroutineScope()
    val notSet = stringResource(R.string.external_not_set)

    SectionHeader(stringResource(R.string.external_model))
    SettingsGroup {
        LinkRow(
            title = stringResource(R.string.external_base_url),
            summary = settings.baseUrl.ifBlank { notSet },
            icon = Icons.Outlined.Link,
            onClick = { editing = ExternalField.URL },
            modifier = Modifier.testTag("external-url"),
        )
        LinkRow(
            title = stringResource(R.string.external_api_key),
            summary = if (settings.apiKey.isBlank()) notSet else stringResource(R.string.external_key_set),
            icon = Icons.Outlined.Key,
            onClick = { editing = ExternalField.KEY },
            modifier = Modifier.testTag("external-key"),
        )
        LinkRow(
            title = stringResource(R.string.external_model_name),
            summary = settings.model.ifBlank { notSet },
            icon = Icons.Outlined.SmartToy,
            onClick = { editing = ExternalField.MODEL },
            modifier = Modifier.testTag("external-model-name"),
        )
        val canTest = settings.active && test != TestState.Running
        SettingsRow(
            title = stringResource(R.string.external_test),
            summary = testSummary(settings, test),
            enabled = settings.active,
            interaction = Modifier.clickable(enabled = canTest) {
                test = TestState.Running
                scope.launch {
                    test = try {
                        TestState.Ok(onTest())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ExternalModelException) {
                        TestState.Failed(e)
                    }
                }
            },
            leading = { RowIcon(Icons.Outlined.NetworkCheck) },
            modifier = Modifier.testTag("external-test"),
        )
    }
    FooterText(stringResource(R.string.external_ai_footer))

    editing?.let { field ->
        FieldDialog(
            title = stringResource(field.title),
            initial = field.of(settings),
            secret = field == ExternalField.KEY,
            placeholder = if (field == ExternalField.URL) URL_EXAMPLE else null,
            onSave = { value ->
                editing = null
                onChange { field.set(it, value) }
            },
            onDismiss = { editing = null },
        )
    }
}

/** 测试那一行的小字。地址填了却不是 https 的，先说这个：这时测不了。 */
@Composable
private fun testSummary(settings: ExternalModelSettings, test: TestState): String? = when (test) {
    TestState.Idle -> if (settings.baseUrl.isNotBlank() && settings.endpoint == null) stringResource(R.string.external_err_https) else null
    TestState.Running -> stringResource(R.string.external_testing)
    is TestState.Ok -> stringResource(R.string.external_test_ok, String.format(Locale.ROOT, "%.1f", test.ms / 1000f))
    is TestState.Failed -> stringResource(R.string.external_test_failed, failureText(test.error))
}

/** 没答上来是哪一种。HTTP 错误带上状态码和接口自己说的那句，401、404 一看就知道是 key 还是地址填错了。 */
@Composable
private fun failureText(e: ExternalModelException): String = when (e.reason) {
    ExternalModelException.Reason.HTTP -> "HTTP ${e.status}" + (e.detail?.let { " · $it" } ?: "")
    ExternalModelException.Reason.TIMEOUT -> stringResource(R.string.external_err_timeout)
    ExternalModelException.Reason.NETWORK -> stringResource(R.string.external_err_network) + (e.detail?.let { " · $it" } ?: "")
    ExternalModelException.Reason.BAD_REPLY -> stringResource(R.string.external_err_reply)
}

/** 改一项的弹框：一个输入框，保存、取消。key 按密码显示。 */
@Composable
private fun FieldDialog(
    title: String,
    initial: String,
    secret: Boolean,
    placeholder: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                placeholder = placeholder?.let { { Text(it) } },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSave(draft) }),
                modifier = Modifier.fillMaxWidth().testTag("external-field"),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 一组里能改的三项。弹框保存时交出去的是「把这一项换掉」（[set]），别的几项用存着的那份，
 * 不拿画面上的那份整个盖回去：弹框开着时设置在别处变了，也不会被冲掉。
 */
internal enum class ExternalField(val title: Int) {
    URL(R.string.external_base_url),
    KEY(R.string.external_api_key),
    MODEL(R.string.external_model_name);

    fun of(s: ExternalModelSettings): String = when (this) {
        URL -> s.baseUrl
        KEY -> s.apiKey
        MODEL -> s.model
    }

    fun set(s: ExternalModelSettings, value: String): ExternalModelSettings = when (this) {
        URL -> s.copy(baseUrl = value)
        KEY -> s.copy(apiKey = value)
        MODEL -> s.copy(model = value)
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val ms: Long) : TestState
    data class Failed(val error: ExternalModelException) : TestState
}

/** 接口地址输入框里的示例：写到 /v1 为止。 */
private const val URL_EXAMPLE = "https://api.openai.com/v1"
