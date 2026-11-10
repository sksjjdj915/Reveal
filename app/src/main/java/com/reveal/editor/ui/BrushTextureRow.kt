package com.reveal.editor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

/**
 * 笔刷纹理：用手机里的图片当笔尖底纹（桌面端的「纹理」）。
 * 存在 files/brush_tex.png，下次打开自动接着用。
 */
private fun loadSaved(ctx: android.content.Context): Bitmap? = runCatching {
    val f = File(ctx.filesDir, "brush_tex.png")
    if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
}.getOrNull()

private fun saveTo(ctx: android.content.Context, bmp: Bitmap) {
    runCatching {
        FileOutputStream(File(ctx.filesDir, "brush_tex.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

/** 一条线加一个点的细滑块（和底部参数条同一观感），值域 0~1。 */
@Composable
private fun MiniSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().height(18.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
        var trackW by remember { mutableStateOf(1f) }
        val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        val primColor = MaterialTheme.colorScheme.primary
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .pointerInput(enabled, trackW) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onChange((down.position.x / trackW).coerceIn(0f, 1f))
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            onChange((ch.position.x / trackW).coerceIn(0f, 1f))
                            ch.consume()
                        }
                    }
                }
        ) {
            trackW = size.width
            val cy = size.height / 2f
            drawLine(dimColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2f)
            val x = value.coerceIn(0f, 1f) * size.width
            drawLine(primColor, Offset(0f, cy), Offset(x, cy), strokeWidth = 2f)
            drawCircle(Color.White, radius = 4.5f, center = Offset(x, cy))
            drawCircle(primColor, radius = 3.2f, center = Offset(x, cy))
        }
        Text(
            text = "${(value * 100).toInt()}%",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
    }
}

@Composable
internal fun BrushTextureRow(sketch: SketchView?, hasDoc: Boolean) {
    val ctx = LocalContext.current
    var hasTex by remember { mutableStateOf(sketch?.brushTexImage != null) }
    LaunchedEffect(sketch) {
        val s = sketch ?: return@LaunchedEffect
        if (s.brushTexImage == null) {
            val b = loadSaved(ctx) ?: return@LaunchedEffect
            s.brushTexImage = b
            hasTex = true
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val s = sketch ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val raw = BitmapFactory.decodeStream(ins) ?: return@runCatching
                val maxSide = 256
                val sc = maxSide.toFloat() / maxOf(raw.width, raw.height)
                val b = if (sc < 1f) {
                    Bitmap.createScaledBitmap(raw, (raw.width * sc).toInt().coerceAtLeast(1), (raw.height * sc).toInt().coerceAtLeast(1), true)
                } else raw
                saveTo(ctx, b)
                s.brushTexImage = b
                hasTex = true
            }
        }
    }
    var tiltOn by remember { mutableStateOf(sketch?.brushTiltShape ?: true) }
    var tiltAmt by remember { mutableStateOf(sketch?.brushTiltAmount ?: 0.62f) }
    var tiltSpin by remember { mutableStateOf(sketch?.brushTiltSpin ?: 1f) }
    var texDepth by remember { mutableStateOf(sketch?.brushTexDepth ?: 1f) }
    var texBright by remember { mutableStateOf(sketch?.brushTexBright ?: 0f) }
    var texContrast by remember { mutableStateOf(sketch?.brushTexContrast ?: 0f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = if (hasTex) "纹理：自定义" else "纹理：内置",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = "导入",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = hasDoc) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        Text(
            text = "清除",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = hasDoc && hasTex) {
                sketch?.brushTexImage = null
                runCatching { File(ctx.filesDir, "brush_tex.png").delete() }
                hasTex = false
            },
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = if (tiltOn) "倾斜笔锋：开" else "倾斜笔锋：关",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = hasDoc) {
                sketch?.brushTiltShape = !tiltOn
                tiltOn = !tiltOn
            },
        )
    }
    if (tiltOn) {
        MiniSlider("倾斜", tiltAmt, hasDoc) {
            sketch?.brushTiltAmount = it
            tiltAmt = it
        }
        MiniSlider("旋转", tiltSpin, hasDoc) {
            sketch?.brushTiltSpin = it
            sketch?.brushTiltRotate = it > 0.02f
            tiltSpin = it
        }
    }
    if (hasTex) {
        MiniSlider("深度", texDepth, hasDoc) {
            sketch?.brushTexDepth = it
            texDepth = it
        }
        MiniSlider("亮度", (texBright + 1f) / 2f, hasDoc) {
            val v = it * 2f - 1f
            sketch?.brushTexBright = v
            texBright = v
        }
        MiniSlider("对比", (texContrast + 1f) / 2f, hasDoc) {
            val v = it * 2f - 1f
            sketch?.brushTexContrast = v
            texContrast = v
        }
    }
}