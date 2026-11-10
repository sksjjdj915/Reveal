package com.reveal.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp

/**
 * 用某个字体画一小段字，用来做字体预览（Compose 的 Text 吃不了运行时拿到的 Typeface，
 * 所以这里直接用底层 Canvas 画）。
 */
@Composable
fun FontSample(
    sample: String,
    typeface: android.graphics.Typeface?,
    color: Color = Color(0xFFD2D6DC),
    fontSizeSp: Float = 13f,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val paint = remember(typeface, color, fontSizeSp, density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            this.textSize = with(density) { fontSizeSp.sp.toPx() }
            this.typeface = typeface ?: android.graphics.Typeface.DEFAULT
            this.isSubpixelText = true
        }
    }
    Canvas(modifier = modifier.clipToBounds()) {
        drawIntoCanvas { c ->
            val fm = paint.fontMetrics
            val baseline = (size.height - (fm.descent - fm.ascent)) / 2f - fm.ascent
            c.nativeCanvas.drawText(sample, 0f, baseline, paint)
        }
    }
}