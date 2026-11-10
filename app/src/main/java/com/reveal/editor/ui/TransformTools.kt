package com.reveal.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.roundToInt

/** 拉直 / 内容识别缩放 这类只带一个数字的变换对话框 */
@Composable
internal fun TransformNumberDialog(
    title: String,
    hint: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    unitText: (Float) -> String,
    onApply: (Float) -> Unit,
    onClose: () -> Unit,
) {
    var v by remember { mutableFloatStateOf(initial) }
    Dialog(onDismissRequest = onClose) {
        Surface(color = Color(0xFFFDFDFE), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(title, color = Color(0xFF22262B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, color = Color(0xFF5F656C), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                Text(unitText(v), color = Color(0xFF22262B), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Slider(value = v, onValueChange = { v = it }, valueRange = range, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消", fontSize = 12.sp) }
                    TextButton(onClick = { onApply(v); onClose() }) { Text("确定", fontSize = 12.sp) }
                }
            }
        }
    }
}

/** 按百分比做内容识别缩放（主体等比、四周拉伸） */
internal fun applyContentScalePct(sketch: SketchView?, pct: Float) {
    val view = sketch ?: return
    val base = view.layers.firstOrNull { it.bitmap != null }?.bitmap ?: return
    val k = (pct / 100f).coerceIn(0.1f, 4f)
    view.contentAwareScale((base.width * k).roundToInt().coerceAtLeast(1), (base.height * k).roundToInt().coerceAtLeast(1))
}

/** 角度文字 */
internal fun fmtDeg(v: Float): String = String.format(Locale.US, "%.1f 度", v)

/** 百分比文字 */
internal fun fmtPct(v: Float): String = v.roundToInt().toString() + "%"
