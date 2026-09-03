package com.dama.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.dama.app.core.model.SensitiveKind

/**
 * 导出拦截（spec §7.4）。pendingCount > 0 时**无例外**先弹这个框，
 * 不提供「不再提示」——它是分层默认的唯一安全网。
 *
 * M1 里 pending 只可能来自「手动框被点成仅圈出」；计划 04 接上识别后
 * 这里会换成按类型分行的完整版（「2 个网址、1 个 IP 地址」）。
 */
@Composable
fun PendingExportDialog(
    pendingByKind: Map<SensitiveKind, Int>,
    onMaskAllAndExport: () -> Unit,
    onExportAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    val total = pendingByKind.values.sum()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还有 $total 处只圈出、没打码") },
        text = { Text(pendingByKind.entries.joinToString("、") { "${it.value} 处${it.key.label()}" }) },
        confirmButton = { TextButton(onClick = onMaskAllAndExport) { Text("全部打码并导出") } },
        dismissButton = { TextButton(onClick = onExportAnyway) { Text("仍然导出") } },
    )
}

private fun SensitiveKind.label() = when (this) {
    SensitiveKind.PHONE -> "电话号码"
    SensitiveKind.EMAIL -> "邮箱"
    SensitiveKind.PAYMENT_CARD -> "银行卡号"
    SensitiveKind.IBAN -> "IBAN"
    SensitiveKind.SSN -> "证件号"
    SensitiveKind.PASSPORT -> "护照号"
    SensitiveKind.TRACKING_NO -> "运单号"
    SensitiveKind.IP_ADDR -> "IP 地址"
    SensitiveKind.MAC_ADDR -> "MAC 地址"
    SensitiveKind.URL -> "网址"
    SensitiveKind.API_KEY -> "密钥"
    SensitiveKind.POSTAL_ADDRESS -> "地址"
    SensitiveKind.PERSON_NAME -> "人名"
    SensitiveKind.ORG_NAME -> "机构名"
    SensitiveKind.FACE -> "人脸"
    SensitiveKind.BARCODE -> "条码"
    SensitiveKind.MANUAL -> "手动框"
}
