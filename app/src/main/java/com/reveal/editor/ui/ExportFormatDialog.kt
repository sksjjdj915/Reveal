package com.reveal.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveal.editor.image.ExportFormat
import kotlin.math.roundToInt

/** 导出格式对话框。放在单独文件里，省得 EditorScreen 的 @Composable 顶到 64KB。 */
@Composable
internal fun ExportFormatDialog(
    formats: List<ExportFormat>,
    selectedFormat: ExportFormat,
    onFormat: (ExportFormat) -> Unit,
    exportScale: Int,
    onScale: (Int) -> Unit,
    exportQuality: Int,
    onQuality: (Int) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出格式", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                formats.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFormat(format) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = format == selectedFormat,
                            onClick = { onFormat(format) },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(format.displayName, fontSize = 14.sp)
                            Text(
                                text = if (format.supportsAlpha) {
                                    "保留透明通道"
                                } else {
                                    "不支持透明，导出时自动铺白底"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("输出尺寸：${exportScale}%", fontSize = 12.sp)
                ThinSlider(
                    value = exportScale.toFloat(),
                    range = 10f..100f,
                    modifier = Modifier.fillMaxWidth(),
                    onChange = { onScale(it.roundToInt().coerceIn(10, 100)) },
                )
                if (selectedFormat == ExportFormat.JPEG || selectedFormat == ExportFormat.WEBP_LOSSY) {
                    Text("质量：$exportQuality", fontSize = 12.sp)
                    ThinSlider(
                        value = exportQuality.toFloat(),
                        range = 20f..100f,
                        modifier = Modifier.fillMaxWidth(),
                        onChange = { onQuality(it.roundToInt().coerceIn(20, 100)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}