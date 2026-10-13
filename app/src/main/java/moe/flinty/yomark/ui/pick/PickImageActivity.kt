package moe.flinty.yomark.ui.pick

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import moe.flinty.yomark.export.FileProviderSink
import moe.flinty.yomark.ui.EditorActivity

/**
 * 别的应用来要图（ACTION_PICK / GET_CONTENT），比如 Telegram 附件相册页右上角的「在外部应用中打开」。
 *
 * 自己不画任何东西：先拉起系统的选图界面，选好了交给编辑器，打好码后编辑器把图交回给调用方。
 * 编辑器带着 FLAG_ACTIVITY_FORWARD_RESULT 启动，结果越过这一层直接回到调用方。选图时取消，就什么也不交，原样退回去。
 *
 * 调用方允许多选（EXTRA_ALLOW_MULTIPLE）时选图也能多选，进批量会话，导出时一起交回。
 */
class PickImageActivity : ComponentActivity() {

    private val pickOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        edit(listOfNotNull(uri))
    }

    private val pickMany = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        edit(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 选图界面开着时进程被杀、重建回来：它还在，等它的结果
        if (savedInstanceState != null) return
        // 上一次交出去的图，调用方早就读走了
        FileProviderSink(applicationContext).clear()
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) pickMany.launch(request)
        else pickOne.launch(request)
    }

    /** 没等结果的调用方（直接 startActivity 的）没有地方交图，就当从首页选的图，照常存进相册。 */
    private fun edit(images: List<Uri>) {
        if (images.isNotEmpty()) {
            startActivity(
                EditorActivity.intent(this, images, forCaller = callingActivity != null)
                    .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT),
            )
        }
        finish()
    }
}
