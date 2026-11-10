package com.reveal.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「画布大小」和「图像大小」共用的尺寸对话框：两个输入框 + 保持比例 + 一句说明。
 * 单独一个文件，避免 EditorScreen 的 @Composable 顶到 64KB。
 *
 * 三个行为（2026-11-08 用户实测后重做）：
 * 1. 「保持比例」默认开：改宽自动算高，改高自动算宽；
 * 2. 只填一边、另一边留空也能确定——按原比例补齐，不再误报「宽高至少 16 像素」；
 * 3. 报错说清楚是哪一边：宽度不能小于 16 / 高度不能小于 16 / 至少填一个。
 */
@Composable
internal fun SizeDialog(
    title: String,
    hint: String,
    initialW: String,
    initialH: String,
    /** 显示「范围：整张 / 当前层」开关（只有「图像大小」用得上） */
    showScope: Boolean = false,
    onApply: (Int, Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val baseW = initialW.toIntOrNull() ?: 0
    val baseH = initialH.toIntOrNull() ?: 0
    val ratio = if (baseW > 0 && baseH > 0) baseH.toFloat() / baseW else 0f

    var w by remember { mutableStateOf(initialW) }
    var h by remember { mutableStateOf(initialH) }
    var lock by remember { mutableStateOf(true) }
    var scopeOnly by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("宽", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = w,
                        onValueChange = { t ->
                            w = t.filter { it.isDigit() }.take(5)
                            if (lock && ratio > 0f) {
                                val v = w.toIntOrNull()
                                if (v != null && v > 0) h = max(1, (v * ratio).roundToInt()).toString()
                            }
                            error = ""
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("高", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = h,
                        onValueChange = { t ->
                            h = t.filter { it.isDigit() }.take(5)
                            if (lock && ratio > 0f) {
                                val v = h.toIntOrNull()
                                if (v != null && v > 0) w = max(1, (v / ratio).roundToInt()).toString()
                            }
                            error = ""
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (showScope) {
                    TextButton(onClick = { scopeOnly = !scopeOnly }) {
                        Text(
                            if (scopeOnly) "范围：当前图层（画布尺寸不变）"
                            else "范围：全部图层（画布一起缩放）",
                            fontSize = 11.sp,
                        )
                    }
                }
                TextButton(onClick = { lock = !lock }) {
                    Text(
                        if (lock) "保持比例：开（改一边另一边跟着变）"
                        else "保持比例：关（两边各填各的）",
                        fontSize = 11.sp,
                    )
                }
                Text(
                    hint,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var vw = w.toIntOrNull()
                var vh = h.toIntOrNull()
                if (vw == null && vh == null) {
                    error = "宽度和高度至少要填一个（另一个会按原比例自动补上）"
                    return@TextButton
                }
                // 只填一边：另一边按原比例补齐
                if (vw == null && vh != null) {
                    vw = if (ratio > 0f) max(1, (vh / ratio).roundToInt()) else vh
                }
                if (vh == null && vw != null) {
                    vh = if (ratio > 0f) max(1, (vw * ratio).roundToInt()) else vw
                }
                if (vw == null || vh == null) {
                    error = "宽度和高度至少要填一个"
                    return@TextButton
                }
                if (vw < 16) {
                    error = "宽度不能小于 16 像素（现在填的是 $vw）"
                    return@TextButton
                }
                if (vh < 16) {
                    error = "高度不能小于 16 像素（现在填的是 $vh）"
                    return@TextButton
                }
                error = ""
                onApply(vw, vh, scopeOnly)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
