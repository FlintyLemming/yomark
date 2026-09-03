package com.dama.app.ui

import android.net.Uri
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.MaskPlan

sealed interface EditorMessage {
    data class Exported(val uri: Uri, val downscaled: Boolean, val width: Int, val height: Int) : EditorMessage
    data class Error(val text: String) : EditorMessage
}

data class EditorUiState(
    val image: SourceImage? = null,
    val plan: MaskPlan = MaskPlan.empty(),
    val loading: Boolean = false,
    /** 识别进行中。画布此时仍可交互——用户可以先手动画框。 */
    val analyzing: Boolean = false,
    val exporting: Boolean = false,
    /** 长按手动框进入的选中态：出现四角手柄与删除按钮。 */
    val selectedManualId: String? = null,
    val pendingDialogVisible: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: EditorMessage? = null,
)
