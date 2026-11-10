package com.reveal.editor.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveal.editor.image.SelectMode
import com.reveal.editor.image.SelectOp

@Composable
fun NewDocumentDialog(
    onDismiss: () -> Unit,
    onCreate: (Int, Int, Boolean) -> Unit,
    initialW: Int = 1080,
    initialH: Int = 1620,
) {
    var w by remember { mutableStateOf(initialW.coerceIn(16, 8192).toString()) }
    var h by remember { mutableStateOf(initialH.coerceIn(16, 8192).toString()) }
    var lock by remember { mutableStateOf(false) }
    var transparent by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val ratio = if (w.toIntOrNull() != null && (w.toIntOrNull() ?: 0) > 0 &&
        h.toIntOrNull() != null && (h.toIntOrNull() ?: 0) > 0
    ) (h.toIntOrNull() ?: 1).toFloat() / (w.toIntOrNull() ?: 1) else 0f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建画布", fontSize = 16.sp) },
        text = {
            Column {
                Text("常用尺寸（点一下就建）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onCreate(2048, 2048, false) }) { Text("2048² 白底", fontSize = 12.sp) }
                    TextButton(onClick = { onCreate(2048, 2048, true) }) { Text("2048² 透明", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onCreate(1080, 1920, true) }) { Text("1080×1920 透明", fontSize = 12.sp) }
                    TextButton(onClick = { onCreate(4096, 4096, true) }) { Text("4096² 透明", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Text("或者自己填（像素）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
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
                Spacer(Modifier.height(6.dp))
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
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { lock = !lock }) {
                        Text(if (lock) "保持比例：开" else "保持比例：关", fontSize = 11.sp)
                    }
                    TextButton(onClick = { transparent = !transparent }) {
                        Text(if (transparent) "背景：透明" else "背景：白底", fontSize = 11.sp)
                    }
                }
                if (error.isNotEmpty()) {
                    Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val vw = w.toIntOrNull()
                val vh = h.toIntOrNull()
                if (vw == null || vh == null) {
                    error = "宽度和高度都要填（例如 1080 / 1620）"
                    return@TextButton
                }
                if (vw < 16) { error = "宽度不能小于 16 像素（现在填的是 " + vw + "）"; return@TextButton }
                if (vh < 16) { error = "高度不能小于 16 像素（现在填的是 " + vh + "）"; return@TextButton }
                if (vw > 8000 || vh > 8000) { error = "最大 8000 像素（现在填的是 " + vw + " × " + vh + "）"; return@TextButton }
                onCreate(vw, vh, transparent)
            }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun ColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onColorChange: ((Int) -> Unit)? = null,
    onPick: (Int) -> Unit,
) {
    val hsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(initial or 0xFF000000.toInt(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceAtLeast(0.05f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.05f)) }
    val current = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)) or 0xFF000000.toInt()
    LaunchedEffect(current) { onColorChange?.invoke(current) }

    val preset = listOf(
        0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
        0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFF6D4C41, 0xFFFF80AB, 0xFF6B7178,
    ).map { it.toInt() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("取色", fontSize = 16.sp) },
        text = {
            Column {
                SvSquare(hue = hue, sat = sat, value = value) { s, v ->
                    sat = s
                    value = v
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(Color(current), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(8.dp))
                HueBar(hue = hue) { hue = it }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    preset.take(6).forEach { c -> Swatch(c) { onPick(c) } }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    preset.drop(6).forEach { c -> Swatch(c) { onPick(c) } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}


@Composable
fun ColorPickerPanel(
    initial: Int,
    onColorChange: (Int) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(initial or 0xFF000000.toInt(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceAtLeast(0.05f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.05f)) }
    val current = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value)) or 0xFF000000.toInt()
    LaunchedEffect(current) { onColorChange(current) }
    val preset = listOf(
        0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
        0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFF6D4C41, 0xFFFF80AB, 0xFF6B7178,
    ).map { it.toInt() }
    // 颜色面板：前景/背景 + 常用色板 + 最近用过
    val ctx = LocalContext.current
    var bg by remember { mutableStateOf(ColorHistory.bg) }
    var recent by remember { mutableStateOf(ColorHistory.load(ctx)) }
    fun setBg(c: Int) {
        ColorHistory.bg = c
        bg = c
    }

    /** 点色板/最近用色：必须把 HSV 指针也挪过去，否则 current 还是旧色，LaunchedEffect 立刻把它推回去 */
    fun pick(c: Int) {
        val solid = c or 0xFF000000.toInt()
        AndroidColor.colorToHSV(solid, hsv)
        hue = hsv[0]
        sat = hsv[1].coerceAtLeast(0.05f)
        value = hsv[2].coerceAtLeast(0.05f)
        onColorChange(solid)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelColorAdjust,
        contentColor = Color(0xFF22262B),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("取色", fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(Color(current), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("取消", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    onConfirm(current)
                    ColorHistory.push(ctx, current)
                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("确定", fontSize = 12.sp)
                }
            }
            SvSquare(hue = hue, sat = sat, value = value) { s, v ->
                sat = s
                value = v
            }
            Spacer(Modifier.height(8.dp))
            HueBar(hue = hue) { hue = it }
            Spacer(Modifier.height(8.dp))
            // 前景 / 背景 + 常用的色板 + 最近用过
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(current), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                )
                Text("前景", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(start = 8.dp)
                        .background(Color(bg), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                )
                Text("背景", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                ColorChip("交换") {
                    val t = bg
                    setBg(current)
                    pick(t)
                }
                ColorChip("黑白复位") {
                    setBg(0xFFFFFFFF.toInt())
                    pick(0xFF000000.toInt())
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("常用色板（点一下＝前景色）", fontSize = 10.sp, color = Color(0xFF6B7178))
            Spacer(Modifier.height(4.dp))
            SWATCHES.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color(c), RoundedCornerShape(5.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                                .clickable {
                                    pick(c)
                                    recent = ColorHistory.push(ctx, c)
                                },
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
            }
            if (recent.isNotEmpty()) {
                Text("最近用过", fontSize = 10.sp, color = Color(0xFF6B7178))
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.take(8).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color(c), RoundedCornerShape(5.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                                .clickable { pick(c) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SvSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .onSizeChanged { box = it }
            .pointerInput(hue) {
                detectDragGestures(
                    onDragStart = { p ->
                        if (box.width > 0) {
                            onChange(
                                (p.x / box.width).coerceIn(0f, 1f),
                                (1f - (p.y / box.height)).coerceIn(0f, 1f),
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        if (box.width > 0) {
                            onChange(
                                (change.position.x / box.width).coerceIn(0f, 1f),
                                (1f - (change.position.y / box.height)).coerceIn(0f, 1f),
                            )
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val base = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRect(base)
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)),
                size = size,
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                size = size,
            )
            val cx = (sat.coerceIn(0f, 1f)) * size.width
            val cy = (1f - value.coerceIn(0f, 1f)) * size.height
            drawCircle(Color.Black, radius = 8f, center = Offset(cx, cy), style = Stroke(width = 1.5f))
            drawCircle(Color.White, radius = 7f, center = Offset(cx, cy), style = Stroke(width = 1.5f))
        }
    }
}

@Composable
internal fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        if (box.width > 0) onChange((p.x / box.width).coerceIn(0f, 1f) * 360f)
                    },
                    onDrag = { change, _ ->
                        if (box.width > 0) {
                            onChange((change.position.x / box.width).coerceIn(0f, 1f) * 360f)
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val colors = (0..12).map {
                Color(AndroidColor.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, 1f)))
            }
            drawRect(brush = Brush.horizontalGradient(colors), size = Size(size.width, size.height))
            val x = (hue / 360f) * size.width
            drawRect(
                color = Color.White,
                topLeft = Offset(x - 2f, 0f),
                size = Size(4f, size.height),
            )
        }
    }
}

@Composable
internal fun Swatch(colorInt: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(Color(colorInt), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { onClick() },
    )
}

/** 抠图参数。mode: 0=绿幕/纯色底，1=色阶通道，2=混合色带 */
data class MattingParams(
    val mode: Int = 0,
    val keyColor: Int = 0xFF00A650.toInt(),
    val tolerance: Float = 0.35f,
    val spill: Boolean = true,
    val channel: Int = 3,
    val black: Float = 40f,
    val white: Float = 200f,
    val invert: Boolean = false,
    val bandLo: Int = 0,
    val bandHi: Int = 255,
    val bandInvert: Boolean = false,
    val useNextLayer: Boolean = false,
    val nextLo: Int = 0,
    val nextHi: Int = 255,
) {
    /** 绿幕模式的旧字段名，兼容老调用 */
    val greenScreen: Boolean get() = mode == 0
}

/**
 * 抠图面板：不是弹窗，贴在画布底部，调参时画布能实时看到结果。
 * [onParams] 每次参数变化都回调（用来刷预览），[onApply] 才真正写回图层。
 */
@Composable
fun MattingPanel(
    autoKeyColor: Int,
    onParams: (MattingParams) -> Unit,
    onApply: (MattingParams) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    hasLayerBelow: Boolean = false,
) {
    var mode by remember { mutableIntStateOf(0) }
    var keyColor by remember { mutableIntStateOf(autoKeyColor) }
    var tolerance by remember { mutableFloatStateOf(0.35f) }
    var spill by remember { mutableStateOf(true) }
    var channel by remember { mutableIntStateOf(3) }
    var black by remember { mutableFloatStateOf(40f) }
    var white by remember { mutableFloatStateOf(200f) }
    var invert by remember { mutableStateOf(false) }
    var bandLo by remember { mutableIntStateOf(0) }
    var bandHi by remember { mutableIntStateOf(255) }
    var bandInvert by remember { mutableStateOf(false) }
    var useNextLayer by remember { mutableStateOf(false) }
    var nextLo by remember { mutableIntStateOf(0) }
    var nextHi by remember { mutableIntStateOf(255) }

    val params = MattingParams(
        mode, keyColor, tolerance, spill, channel, black, white, invert,
        bandLo, bandHi, bandInvert, useNextLayer, nextLo, nextHi,
    )
    LaunchedEffect(params) { onParams(params) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("抠图", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消", fontSize = 12.sp) }
                TextButton(onClick = { onApply(params) }) { Text("应用", fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip("绿幕 / 纯色底", mode == 0) { mode = 0 }
                Spacer(Modifier.width(6.dp))
                TabChip("色阶通道", mode == 1) { mode = 1 }
                Spacer(Modifier.width(6.dp))
                TabChip("混合色带", mode == 2) { mode = 2 }
            }
            Spacer(Modifier.height(4.dp))
            when (mode) {
                0 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color(keyColor), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { keyColor = autoKeyColor },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "背景色（自动取四边，点色块可重置）",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ParamSlider("容差", tolerance, 0.05f..0.9f, "${(tolerance * 100).toInt()}%") { tolerance = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = spill, onCheckedChange = { spill = it })
                        Text("去绿边（抑制绿色溢色）", fontSize = 12.sp)
                    }
                }

                1 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("R", "G", "B", "亮度").forEachIndexed { index, label ->
                            TextButton(onClick = { channel = index }) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (channel == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (channel == index) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    ParamSlider("黑场", black, 0f..254f, black.toInt().toString()) { black = it }
                    ParamSlider("白场", white, 1f..255f, white.toInt().toString()) { white = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = invert, onCheckedChange = { invert = it })
                        Text("反相（抠掉亮部）", fontSize = 12.sp)
                    }
                }

                else -> {
                    Text(
                        text = "纯色背景用它最快：拖两根滑块把背景那条亮度带圈起来。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ParamSlider(
                        "当前图层 下沿",
                        bandLo.toFloat(),
                        0f..254f,
                        bandLo.toString(),
                    ) { bandLo = it.toInt().coerceAtMost(bandHi - 1) }
                    ParamSlider(
                        "当前图层 上沿",
                        bandHi.toFloat(),
                        1f..255f,
                        bandHi.toString(),
                    ) { bandHi = it.toInt().coerceAtLeast(bandLo + 1) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = bandInvert, onCheckedChange = { bandInvert = it })
                        Text("反相（保留这条亮度带）", fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = useNextLayer && hasLayerBelow,
                            onCheckedChange = { useNextLayer = it },
                            enabled = hasLayerBelow,
                        )
                        Text(
                            text = if (hasLayerBelow) "只抠下一图层同亮度的位置" else "下面没有图层，该项不可用",
                            fontSize = 12.sp,
                            color = if (hasLayerBelow) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (useNextLayer && hasLayerBelow) {
                        ParamSlider("下一图层 下沿", nextLo.toFloat(), 0f..254f, nextLo.toString()) {
                            nextLo = it.toInt().coerceAtMost(nextHi - 1)
                        }
                        ParamSlider("下一图层 上沿", nextHi.toFloat(), 1f..255f, nextHi.toString()) {
                            nextHi = it.toInt().coerceAtLeast(nextLo + 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 选区面板：点左栏「选区」后展开，一个地方管所有选区工具。
 * 上排是形状（矩形 / 椭圆 / 套索 / 多边形 / 魔棒），下排是运算和动作。
 */
@Composable
fun SelectPanel(
    mode: SelectMode,
    op: SelectOp,
    hasSelection: Boolean,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onAll: () -> Unit,
    onInvert: () -> Unit,
    onNone: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapeLabels = listOf(
        SelectMode.RECT to "矩形",
        SelectMode.ELLIPSE to "椭圆",
        SelectMode.LASSO to "套索",
        SelectMode.POLY to "多边形",
        SelectMode.WAND to "魔棒",
    )
    val opLabels = listOf(
        SelectOp.NEW to "新建",
        SelectOp.ADD to "加选",
        SelectOp.SUB to "减选",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shapeLabels.forEach { (m, label) ->
                    SelectChip(label, m == mode) { onMode(m) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                opLabels.forEach { (o, label) ->
                    SelectChip(label, o == op) { onOp(o) }
                }
                Spacer(Modifier.weight(1f))
                SelectChip("全选", false) { onAll() }
                SelectChip("反选", false, enabled = hasSelection) { onInvert() }
                SelectChip("取消", false, enabled = hasSelection) { onNone() }
                SelectChip("完成", false) { onDone() }
            }
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        fontSize = 11.sp,
        color = when {
            !enabled -> MaterialTheme.colorScheme.outline
            active -> on
            else -> off
        },
        modifier = Modifier
            .border(
                width = 0.8.dp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    active -> on
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 图层面板顶部的小图标按钮：图标代替文字，面板更清爽 */
@Composable
private fun LayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .size(17.dp),
    )
}

/** 图层面板里的小开关：阿尔法锁定 / 剪贴蒙版 */
@Composable
private fun LayerChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 9.sp,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .border(
                width = 0.8.dp,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
internal fun TabChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) Rv.accent else Rv.surfaceAlt,
        animationSpec = androidx.compose.animation.core.tween(Rv.durFast, easing = RvEase),
        label = "chipBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) Rv.accentText else Rv.textSub,
        animationSpec = androidx.compose.animation.core.tween(Rv.durFast, easing = RvEase),
        label = "chipFg",
    )
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 图层面板里的一行（由上层按"最上面一层在最前"排好） */
data class LayerRow(
    val name: String,
    val visible: Boolean,
    val index: Int,
    val active: Boolean,
    val alphaLock: Boolean = false,
    val clipped: Boolean = false,
    val blendMode: Int = 0,
    val alpha: Int = 255,
    /** 这一层有没有蒙版 */
    val hasMask: Boolean = false,
    /** 这一层是不是文字层（可以重新编辑） */
    val hasText: Boolean = false,
    /** 缩进层级（组内成员 >0） */
    val depth: Int = 0,
    /** 是不是组头 */
    val isGroup: Boolean = false,
    /** 组折叠着（成员已经不在 rows 里了） */
    val collapsed: Boolean = false,
    val lockImage: Boolean = false,
    val lockPos: Boolean = false,
    val lockAll: Boolean = false,
    val fillAlpha: Int = 100,
    val colorTag: Int = 0,
)

/** 长按图层出分类菜单（混合模式再点开才是整张列表），按住拖动可以调序 */
@Composable
fun LayerPanel(
    rows: List<LayerRow>,
    onSelect: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onAlphaLock: (Int) -> Unit,
    onClip: (Int) -> Unit,
    onAlpha: (Int, Int) -> Unit,
    onLayerStyle: (Int) -> Unit,
    onEditText: (Int) -> Unit,
    onAddMask: () -> Unit,
    onDeleteMask: () -> Unit,
    onApplyMask: () -> Unit,
    onToggleMaskEdit: () -> Unit,
    maskEditing: Boolean = false,
    onBlendMode: (Int, Int) -> Unit,
    onLock: (Int, Int) -> Unit,
    onFill: (Int, Int) -> Unit,
    onColorTag: (Int, Int) -> Unit,
    onRasterize: (Int) -> Unit,
    onSelectSimilar: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    onDuplicate: (Int) -> Unit,
    onAdd: () -> Unit,
    onAddGroup: () -> Unit,
    onPlaceImage: (() -> Unit)? = null,
    onToggleCollapse: (Int) -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMove: (Int, Int) -> Unit,
    /** 多选批量操作：动作名（delete / group / merge / show / hide / up / down）+ 选中的图层下标 */
    onBatch: (String, List<Int>) -> Unit,
    /** 多选状态的外部初值 / 回写，用来让「关掉面板再打开」还记着勾了谁 */
    multiMode0: Boolean = false,
    picked0: List<Int> = emptyList(),
    onMultiState: (Boolean, List<Int>) -> Unit = { _, _ -> },
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuFor by remember { mutableIntStateOf(-1) }
    var blendFor by remember { mutableIntStateOf(-1) }
    var renameFor by remember { mutableIntStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var layerFilter by remember { mutableStateOf("") }
    var layerKind by remember { mutableIntStateOf(0) }
    // 多选：进多选后点行是勾选，不再切活动层
    var multiMode by remember { mutableStateOf(multiMode0) }
    val picked = remember { mutableStateListOf<Int>().also { it.addAll(picked0) } }
    fun syncMulti() = onMultiState(multiMode, picked.toList())
    val rowHeightPx = with(LocalDensity.current) { 36.dp.toPx() }

    // 按名字 / 类型筛（桌面端的图层筛选思路）；组头永远显示，不然看不到组里的东西在哪
    val shownRows = if (layerFilter.isBlank() && layerKind == 0) rows else rows.filter { r ->
        val byName = layerFilter.isBlank() || r.name.contains(layerFilter, ignoreCase = true)
        val byKind = when (layerKind) {
            1 -> r.hasText
            2 -> r.hasMask
            3 -> r.isGroup
            4 -> r.lockAll || r.lockImage || r.lockPos
            else -> true
        }
        byName && byKind
    }

    val menuRow = rows.firstOrNull { it.index == menuFor }
    val blendRow = rows.firstOrNull { it.index == blendFor }

    Surface(
        modifier = modifier,
        color = PanelColorLayer,
        contentColor = Color(0xFF22262B),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图层", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (multiMode) "取消" else "多选",
                    fontSize = 11.sp,
                    color = if (multiMode) Color(0xFFFFB74D) else Color(0xFF8AB4F8),
                    modifier = Modifier
                        .clickable {
                            multiMode = !multiMode
                            picked.clear()
                            syncMulti()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                if (onPlaceImage != null) {
                    Text(
                        "置入图",
                        fontSize = 11.sp,
                        color = Color(0xFF8AB4F8),
                        modifier = Modifier
                            .clickable { onPlaceImage() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Text(
                    "组+",
                    fontSize = 11.sp,
                    color = Color(0xFF8AB4F8),
                    modifier = Modifier
                        .clickable { onAddGroup() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                LayerAction(Icons.Filled.Add, "新建图层", onAdd)
                LayerAction(Icons.Filled.DeleteOutline, "删除图层", onDelete)
                LayerAction(Icons.Filled.KeyboardArrowUp, "上移", onUp)
                LayerAction(Icons.Filled.KeyboardArrowDown, "下移", onDown)
                Spacer(Modifier.width(2.dp))
                TextButton(onClick = onClose, modifier = Modifier.height(30.dp)) {
                    Text("收起", fontSize = 11.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = layerFilter,
                    onValueChange = { layerFilter = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color(0xFF22262B), fontSize = 11.sp),
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .background(Color(0xFFF4F5F7), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    decorationBox = { inner ->
                        if (layerFilter.isEmpty()) Text("搜图层名…", color = Color(0xFF4A5057), fontSize = 11.sp)
                        inner()
                    },
                )
                Text(
                    "共 ${rows.size} / 显示 ${shownRows.size}",
                    color = Color(0xFF5F656C),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("全部", "文字", "蒙版", "组", "锁定").forEachIndexed { i, label ->
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = if (layerKind == i) Color(0xFF8AB4F8) else Color(0xFF6B7178),
                        modifier = Modifier
                            .clickable { layerKind = i }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (layerFilter.isNotEmpty() || layerKind != 0) {
                    Text(
                        "清筛选",
                        fontSize = 10.sp,
                        color = Color(0xFF8AB4F8),
                        modifier = Modifier
                            .clickable { layerFilter = ""; layerKind = 0 }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (shownRows.isEmpty()) {
                    Text("没有符合条件的图层", color = Color(0xFF5F656C), fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                }
                shownRows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .offset {
                                IntOffset(0, if (dragFrom == row.index) dragDy.roundToInt() else 0)
                            }
                            // 组里的图层整行往右缩进，缩进那段也算进底色，一眼看出它在组里；
                            // 上下各留 2dp，图层和图层之间有缝，不再糊成一片
                            .padding(
                                start = (row.depth * 18).dp,
                                top = 3.dp,
                                bottom = 3.dp,
                            )
                            .background(
                                when {
                                    multiMode && picked.contains(row.index) -> Color(0xFFE3EDFF)
                                    row.active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    // 组的底色走中性灰，别再跟"选中"的蓝色撞
                                    row.isGroup -> Color(0xFFE8EBEF)
                                    row.depth > 0 -> Color(0xFFFFFFFF)
                                    // 普通层也要有底色：没有底色，上下那 3dp 的缝就看不见，
                                    // 一屏层还是糊成一片
                                    else -> Color(0xFFFFFFFF)
                                },
                                RoundedCornerShape(6.dp),
                            )
                            .clickable {
                                if (multiMode) {
                                    if (picked.contains(row.index)) picked.remove(row.index)
                                    else picked.add(row.index)
                                    syncMulti()
                                } else {
                                    onSelect(row.index)
                                }
                            }
                            .pointerInput(row.index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragFrom = row.index
                                        dragDy = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragDy += amount.y
                                    },
                                    onDragEnd = {
                                        val steps = (dragDy / rowHeightPx).roundToInt()
                                        dragFrom = -1
                                        dragDy = 0f
                                        if (steps == 0) {
                                            menuFor = row.index
                                        } else {
                                            onMove(row.index, row.index - steps)
                                        }
                                    },
                                    onDragCancel = {
                                        dragFrom = -1
                                        dragDy = 0f
                                    },
                                )
                            }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (multiMode) {
                            Text(
                                text = if (picked.contains(row.index)) "☑" else "☐",
                                fontSize = 13.sp,
                                color = if (picked.contains(row.index)) Color(0xFF8AB4F8) else Color(0xFF6B7178),
                                modifier = Modifier.width(20.dp),
                            )
                        }
                        Text(
                            text = if (row.visible) "👁" else "—",
                            fontSize = 13.sp,
                            modifier = Modifier
                                .width(26.dp)
                                .clickable { onToggle(row.index) },
                        )
                        if (row.clipped) {
                            Text("\u21B3", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(3.dp))
                        }
                        if (row.isGroup) {
                            Text(
                                text = if (row.collapsed) "▶" else "▼",
                                fontSize = 10.sp,
                                color = Color(0xFF6B7178),
                                modifier = Modifier
                                    .width(16.dp)
                                    .clickable { onToggleCollapse(row.index) },
                            )
                        }
                        Text(
                            text = row.name,
                            fontSize = if (row.isGroup) 12.sp else 11.sp,
                            maxLines = 1,
                            color = if (row.isGroup) Color(0xFF2A2F35) else Color(0xFFC9CED6),
                        )
                        if (row.blendMode != 0) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = SketchView.BLEND_NAMES.getOrElse(row.blendMode) { "" },
                                fontSize = 9.sp,
                                color = Color(0xFF8AB4F8),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (row.hasMask) {
                            Text(
                                text = if (maskEditing && row.active) "M*" else "M",
                                fontSize = 9.sp,
                                color = if (maskEditing && row.active) MaterialTheme.colorScheme.primary else Color(0xFF8AB4F8),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        if (row.alphaLock) {
                            Text("🔒", fontSize = 10.sp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (row.active) "编辑中" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            val activeRow = rows.firstOrNull { it.active }
            if (activeRow != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "不透明度",
                        fontSize = 9.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier.width(44.dp),
                    )
                    ThinSlider(
                        value = activeRow.alpha.toFloat(),
                        range = 0f..255f,
                        modifier = Modifier.weight(1f),
                    ) { onAlpha(activeRow.index, it.roundToInt()) }
                    Text(
                        text = "${(activeRow.alpha * 100 / 255)}%",
                        fontSize = 9.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier.width(34.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (multiMode) {
                val lite = Color(0xFF8AB4F8)
                val chip: @Composable (String, () -> Unit) -> Unit = { label, action ->
                    Text(
                        label,
                        fontSize = 10.sp,
                        color = lite,
                        modifier = Modifier
                            .background(Color(0xFFE8EBEF), RoundedCornerShape(5.dp))
                            .clickable { action() }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
                fun runBatch(action: String) {
                    if (picked.isNotEmpty()) onBatch(action, picked.toList())
                    picked.clear()
                    syncMulti()
                }
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已选 ${picked.size}",
                            fontSize = 10.sp,
                            color = lite,
                            modifier = Modifier.width(50.dp),
                        )
                        chip("全选") {
                            picked.clear()
                            shownRows.forEach { picked.add(it.index) }
                            syncMulti()
                        }
                        Spacer(Modifier.width(4.dp))
                        chip("反选") {
                            val all = shownRows.map { it.index }
                            val cur = picked.toList()
                            picked.clear()
                            all.forEach { if (!cur.contains(it)) picked.add(it) }
                            syncMulti()
                        }
                        Spacer(Modifier.width(4.dp))
                        chip("删除") { runBatch("delete") }
                        Spacer(Modifier.width(4.dp))
                        chip("成组") { runBatch("group") }
                        Spacer(Modifier.width(4.dp))
                        chip("合并") { runBatch("merge") }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp),
                    ) {
                        Spacer(Modifier.width(50.dp))
                        chip("显示") { runBatch("show") }
                        Spacer(Modifier.width(4.dp))
                        chip("隐藏") { runBatch("hide") }
                        Spacer(Modifier.width(4.dp))
                        chip("上移") { runBatch("up") }
                        Spacer(Modifier.width(4.dp))
                        chip("下移") { runBatch("down") }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "退出多选",
                            fontSize = 10.sp,
                            color = Color(0xFFFFB74D),
                            modifier = Modifier
                                .clickable {
                                    multiMode = false
                                    picked.clear()
                                    syncMulti()
                                }
                                .padding(4.dp),
                        )
                    }
                }
            }
            Text(
                text = "长按出菜单 · 按住拖动调序",
                fontSize = 9.sp,
                color = Color(0xFF4A5057),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (menuRow != null) {
        AlertDialog(
            onDismissRequest = { menuFor = -1 },
            title = { Text(menuRow.name, fontSize = 13.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 文字层先把「编辑文字」放在最上面（长按后第一下就能点到）
                    if (menuRow.hasText) {
                        LayerMenuEntry("编辑文字…") {
                            onEditText(menuRow.index)
                            menuFor = -1
                        }
                    }
                    val blendName = SketchView.BLEND_NAMES.getOrElse(menuRow.blendMode) { "正常" }
                    LayerMenuEntry("混合模式：$blendName") {
                        blendFor = menuRow.index
                        menuFor = -1
                    }
                    LayerMenuEntry(if (menuRow.clipped) "剪贴蒙版：开" else "剪贴蒙版：关") {
                        onClip(menuRow.index)
                        menuFor = -1
                    }
                    LayerMenuEntry(if (menuRow.alphaLock) "阿尔法锁定：开" else "阿尔法锁定：关") {
                        onAlphaLock(menuRow.index)
                        menuFor = -1
                    }
                    LayerMenuEntry(if (menuRow.lockImage) "锁定图像：开" else "锁定图像：关") {
                        onLock(menuRow.index, 0)
                        menuFor = -1
                    }
                    LayerMenuEntry(if (menuRow.lockPos) "锁定位置：开" else "锁定位置：关") {
                        onLock(menuRow.index, 1)
                        menuFor = -1
                    }
                    LayerMenuEntry(if (menuRow.lockAll) "锁定全部：开" else "锁定全部：关") {
                        onLock(menuRow.index, 2)
                        menuFor = -1
                    }
                    LayerMenuEntry("填充不透明度：" + menuRow.fillAlpha + "%") {
                        val next = when (menuRow.fillAlpha) { 100 -> 75; 75 -> 50; 50 -> 25; else -> 100 }
                        onFill(menuRow.index, next)
                        menuFor = -1
                    }
                    LayerMenuEntry("颜色标签：" + listOf("无", "红", "橙", "黄", "绿", "蓝", "紫").getOrElse(menuRow.colorTag) { "无" }) {
                        onColorTag(menuRow.index, (menuRow.colorTag + 1) % 7)
                        menuFor = -1
                    }
                    if (menuRow.hasText) {
                        LayerMenuEntry("栅格化图层") {
                            onRasterize(menuRow.index)
                            menuFor = -1
                        }
                    }
                    LayerMenuEntry("选择同色标签图层") {
                        onSelectSimilar(menuRow.index)
                        menuFor = -1
                    }
                    LayerMenuEntry("图层样式（描边 / 投影）…") {
                        onLayerStyle(menuRow.index)
                        menuFor = -1
                    }
                    if (!menuRow.hasMask) {
                        LayerMenuEntry("添加蒙版") {
                            onSelect(menuRow.index)
                            onAddMask()
                            menuFor = -1
                        }
                    } else {
                        LayerMenuEntry(if (maskEditing) "正在编辑蒙版（点此切回图层）" else "编辑蒙版（点此开始）") {
                            onSelect(menuRow.index)
                            onToggleMaskEdit()
                            menuFor = -1
                        }
                        LayerMenuEntry("应用蒙版（真的挖掉）") {
                            onSelect(menuRow.index)
                            onApplyMask()
                            menuFor = -1
                        }
                        LayerMenuEntry("删除蒙版") {
                            onSelect(menuRow.index)
                            onDeleteMask()
                            menuFor = -1
                        }
                    }
                    LayerMenuEntry("重命名") {
                        renameFor = menuRow.index
                        renameText = menuRow.name
                        menuFor = -1
                    }
                    LayerMenuEntry("复制图层") {
                        onDuplicate(menuRow.index)
                        menuFor = -1
                    }
                    LayerMenuEntry("删除图层") {
                        onSelect(menuRow.index)
                        onDelete()
                        menuFor = -1
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuFor = -1 }) { Text("关闭", fontSize = 12.sp) }
            },
        )
    }

    if (blendRow != null) {
        AlertDialog(
            onDismissRequest = { blendFor = -1 },
            title = { Text("混合模式", fontSize = 13.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SketchView.BLEND_NAMES.forEachIndexed { mode, name ->
                        val picked = blendRow.blendMode == mode
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            color = if (picked) MaterialTheme.colorScheme.primary else Color(0xFF22262B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onBlendMode(blendRow.index, mode)
                                    blendFor = -1
                                }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { blendFor = -1 }) { Text("关闭", fontSize = 12.sp) }
            },
        )
    }

    if (renameFor >= 0) {
        AlertDialog(
            onDismissRequest = { renameFor = -1 },
            title = { Text("重命名图层", fontSize = 13.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(renameFor, renameText)
                    renameFor = -1
                }) { Text("确定", fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = -1 }) { Text("取消", fontSize = 12.sp) }
            },
        )
    }
}

@Composable
private fun LayerMenuEntry(label: String, onClick: () -> Unit) {
    // 一条一条的小方块，之间有间隙，菜单才不会糊成一片
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFFFFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF2A2F35))
    }
}
@Composable
internal fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(26.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            modifier = Modifier.width(46.dp),
            color = Rv.textSub,
        )
        ThinSlider(
            value = value,
            range = range,
            modifier = Modifier.weight(1f),
            onChange = onChange,
        )
        Text(
            text = display,
            fontSize = 11.sp,
            modifier = Modifier.width(38.dp),
            textAlign = TextAlign.End,
            color = Rv.text,
        )
    }
}

/** 细线 + 小圆点，比系统滑块省一半高度，视觉上也安静 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ThinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        enabled = enabled,
        modifier = modifier.height(20.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Rv.accent, CircleShape)
                    .border(2.5.dp, Color.White, CircleShape),
            )
        },
        track = { state ->
            val span = range.endInclusive - range.start
            val t = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(t)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        },
    )
}
/** 图层样式：12 组效果；每根滑块都带名字，色块点开用和画笔同一套取色 */
@Composable
fun LayerStylePanel(
    layerName: String,
    init: SketchView.StyleParams,
    onDismiss: () -> Unit,
    onApply: (SketchView.StyleParams) -> Unit,
    onPreview: (SketchView.StyleParams) -> Unit = {},
) {
    var s by remember { mutableStateOf(init) }
    var pickSlot by remember { mutableStateOf(-1) }
    // 实时预览：改完停 80ms 就应用到画布（不用等点「应用」）
    LaunchedEffect(s) {
        kotlinx.coroutines.delay(80)
        onPreview(s)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        color = Color.White,
        contentColor = Rv.text,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图层样式 \u00b7 " + layerName, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 11.sp) }
                TextButton(onClick = { onApply(s) }) { Text("应用", fontSize = 11.sp) }
            }
            if (pickSlot >= 0) {
                ColorPickerPanel(
                    initial = styleSlotColor(s, pickSlot),
                    onColorChange = { c -> s = styleSlotSet(s, pickSlot, c) },
                    onCancel = { pickSlot = -1 },
                    onConfirm = { c ->
                        s = styleSlotSet(s, pickSlot, c)
                        pickSlot = -1
                    },
                )
            } else {
                Text(
                    "拖滑块会自动打开这一组；点左边的小色块可以换色",
                    fontSize = 10.sp, color = Color(0xFF6B7178),
                    modifier = Modifier.padding(bottom = 2.dp),
                )

                // ---- 描边 ----
                StyleHead("描边", s.strokeOn, { s = s.copy(strokeOn = it) }, s.strokeColor, { pickSlot = 1 })
                StyleSlider("大小", s.strokeWidth.toFloat(), 1f..24f, s.strokeWidth.toString() + "px") {
                    s = s.copy(strokeWidth = it.roundToInt().coerceIn(1, 24), strokeOn = true)
                }

                // ---- 投影 ----
                StyleHead("投影", s.shadowOn, { s = s.copy(shadowOn = it) }, s.shadowColor, { pickSlot = 2 })
                StyleSlider("模糊", s.shadowBlur.toFloat(), 0f..60f, s.shadowBlur.toString() + "px") {
                    s = s.copy(shadowBlur = it.roundToInt().coerceIn(0, 60), shadowOn = true)
                }
                StyleSlider("左右", s.shadowDX.toFloat(), -60f..60f, s.shadowDX.toString()) {
                    s = s.copy(shadowDX = it.roundToInt(), shadowOn = true)
                }
                StyleSlider("上下", s.shadowDY.toFloat(), -60f..60f, s.shadowDY.toString()) {
                    s = s.copy(shadowDY = it.roundToInt(), shadowOn = true)
                }

                // ---- 外发光 ----
                StyleHead("外发光", s.glowOn, { s = s.copy(glowOn = it) }, s.glowColor, { pickSlot = 3 })
                StyleSlider("大小", s.glowSize.toFloat(), 1f..60f, s.glowSize.toString() + "px") {
                    s = s.copy(glowSize = it.roundToInt().coerceIn(1, 60), glowOn = true)
                }

                // ---- 内阴影 ----
                StyleHead("内阴影", s.innerShadowOn, { s = s.copy(innerShadowOn = it) }, s.innerShadowColor, { pickSlot = 4 })
                StyleSlider("大小", s.innerShadowSize.toFloat(), 0f..40f, s.innerShadowSize.toString() + "px") {
                    s = s.copy(innerShadowSize = it.roundToInt().coerceIn(0, 40), innerShadowOn = true)
                }
                StyleSlider("左右", s.innerShadowDX.toFloat(), -30f..30f, s.innerShadowDX.toString()) {
                    s = s.copy(innerShadowDX = it.roundToInt(), innerShadowOn = true)
                }
                StyleSlider("上下", s.innerShadowDY.toFloat(), -30f..30f, s.innerShadowDY.toString()) {
                    s = s.copy(innerShadowDY = it.roundToInt(), innerShadowOn = true)
                }

                // ---- 内发光 ----
                StyleHead("内发光", s.innerGlowOn, { s = s.copy(innerGlowOn = it) }, s.innerGlowColor, { pickSlot = 5 })
                StyleSlider("大小", s.innerGlowSize.toFloat(), 1f..40f, s.innerGlowSize.toString() + "px") {
                    s = s.copy(innerGlowSize = it.roundToInt().coerceIn(1, 40), innerGlowOn = true)
                }

                // ---- 颜色叠加 ----
                StyleHead("颜色叠加", s.colorOverlayOn, { s = s.copy(colorOverlayOn = it) }, s.colorOverlayColor, { pickSlot = 6 })

                // ---- 渐变叠加 ----
                StyleHead("渐变叠加", s.gradientOverlayOn, { s = s.copy(gradientOverlayOn = it) }, s.gradientOverlayColor1, { pickSlot = 7 })
                StyleSlider("角度", s.gradientOverlayAngle.toFloat(), 0f..180f, s.gradientOverlayAngle.toString() + "\u00b0") {
                    s = s.copy(gradientOverlayAngle = it.roundToInt(), gradientOverlayOn = true)
                }
                Text(
                    "第二个色标",
                    fontSize = 10.sp, color = Color(0xFF6B7178),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(s.gradientOverlayColor2))
                        .border(1.dp, Color(0x1F000000), RoundedCornerShape(6.dp))
                        .clickable { pickSlot = 8 }
                )

                // ---- 斜面和浮雕 ----
                StyleHead("斜面和浮雕", s.bevelOn, { s = s.copy(bevelOn = it) }, 0xFFFFFFFF.toInt(), {}, showColor = false)
                StyleSlider("大小", s.bevelSize.toFloat(), 1f..24f, s.bevelSize.toString() + "px") {
                    s = s.copy(bevelSize = it.roundToInt(), bevelOn = true)
                }
                StyleSlider("深度", s.bevelDepth.toFloat(), 0f..100f, s.bevelDepth.toString() + "%") {
                    s = s.copy(bevelDepth = it.roundToInt(), bevelOn = true)
                }

                // ---- 光泽 ----
                StyleHead("光泽", s.satinOn, { s = s.copy(satinOn = it) }, s.satinColor, { pickSlot = 9 })
                StyleSlider("大小", s.satinSize.toFloat(), 0f..30f, s.satinSize.toString()) {
                    s = s.copy(satinSize = it.roundToInt(), satinOn = true)
                }
                StyleSlider("距离", s.satinDistance.toFloat(), 0f..40f, s.satinDistance.toString()) {
                    s = s.copy(satinDistance = it.roundToInt(), satinOn = true)
                }
                StyleSlider("角度", s.satinAngle.toFloat(), -180f..180f, s.satinAngle.toString() + "\u00b0") {
                    s = s.copy(satinAngle = it.roundToInt(), satinOn = true)
                }

                // ---- 图案叠加 ----
                StyleHead("图案叠加", s.patternOverlayOn, { s = s.copy(patternOverlayOn = it) }, 0xFFFFFFFF.toInt(), {}, showColor = false)
                StyleSlider("图案", s.patternOverlayIndex.toFloat(), 0f..((Patterns.all().size - 1).coerceAtLeast(1)).toFloat(),
                    "第" + (s.patternOverlayIndex + 1) + "种") {
                    s = s.copy(patternOverlayIndex = it.roundToInt(), patternOverlayOn = true)
                }
                StyleSlider("缩放", s.patternOverlayScale.toFloat(), 10f..400f, s.patternOverlayScale.toString() + "%") {
                    s = s.copy(patternOverlayScale = it.roundToInt(), patternOverlayOn = true)
                }
                StyleSlider("不透明度", s.patternOverlayOpacity.toFloat(), 0f..100f, s.patternOverlayOpacity.toString() + "%") {
                    s = s.copy(patternOverlayOpacity = it.roundToInt(), patternOverlayOn = true)
                }

                // ---- 纹理：用图案明暗压出凹凸 ----
                StyleHead("纹理", s.textureOn, { s = s.copy(textureOn = it) }, 0xFFFFFFFF.toInt(), {}, showColor = false)
                StyleSlider("图案", s.texturePatternIndex.toFloat(), 0f..((Patterns.all().size - 1).coerceAtLeast(1)).toFloat(),
                    "第" + (s.texturePatternIndex + 1) + "种") {
                    s = s.copy(texturePatternIndex = it.roundToInt(), textureOn = true)
                }
                StyleSlider("缩放", s.textureScale.toFloat(), 10f..400f, s.textureScale.toString() + "%") {
                    s = s.copy(textureScale = it.roundToInt(), textureOn = true)
                }
                StyleSlider("深度", s.textureDepth.toFloat(), 0f..100f, s.textureDepth.toString() + "%") {
                    s = s.copy(textureDepth = it.roundToInt(), textureOn = true)
                }

                // ---- 等高线 ----
                StyleHead("等高线", s.contourOn, { s = s.copy(contourOn = it) }, 0xFFFFFFFF.toInt(), {}, showColor = false)
                StyleSlider("形状", s.contourKind.toFloat(), 0f..5f,
                    listOf("线性", "锥形", "平滑", "环形", "圆角", "锯齿").getOrElse(s.contourKind) { "线性" }) {
                    s = s.copy(contourKind = it.roundToInt(), contourOn = true)
                }
            }
        }
    }
}

/** 一行效果标题：名字 + 开关 + 色块（点了换色） */
@Composable
private fun StyleHead(
    label: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    color: Int,
    onColorTap: () -> Unit,
    showColor: Boolean = true,
) {
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = onToggle)
        if (showColor) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(color))
                    .border(1.dp, Color(0x1F000000), RoundedCornerShape(6.dp))
                    .clickable { onColorTap() }
            )
        }
    }
}

/** 带名字的细线滑块：左标签 / 中滑条 / 右数值，省高度也不迷糊 */
@Composable
private fun StyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6B7178), modifier = Modifier.width(52.dp))
        ThinSlider(value = value, range = range, modifier = Modifier.weight(1f), onChange = onChange)
        Text(
            text,
            fontSize = 10.sp,
            color = Color(0xFF7A8188),
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.End,
        )
    }
}

private fun styleSlotColor(s: SketchView.StyleParams, slot: Int): Int = when (slot) {
    1 -> s.strokeColor
    2 -> s.shadowColor
    3 -> s.glowColor
    4 -> s.innerShadowColor
    5 -> s.innerGlowColor
    6 -> s.colorOverlayColor
    7 -> s.gradientOverlayColor1
    8 -> s.gradientOverlayColor2
    9 -> s.satinColor
    else -> 0xFFFFFFFF.toInt()
}

private fun styleSlotSet(s: SketchView.StyleParams, slot: Int, c: Int): SketchView.StyleParams = when (slot) {
    1 -> s.copy(strokeColor = c, strokeOn = true)
    2 -> s.copy(shadowColor = c, shadowOn = true)
    3 -> s.copy(glowColor = c, glowOn = true)
    4 -> s.copy(innerShadowColor = c, innerShadowOn = true)
    5 -> s.copy(innerGlowColor = c, innerGlowOn = true)
    6 -> s.copy(colorOverlayColor = c, colorOverlayOn = true)
    7 -> s.copy(gradientOverlayColor1 = c, gradientOverlayOn = true)
    8 -> s.copy(gradientOverlayColor2 = c, gradientOverlayOn = true)
    9 -> s.copy(satinColor = c, satinOn = true)
    else -> s
}

/** 常用色板：桌面端默认圆点那套基础色，两行 16 个（颜色面板里用） */
internal val SWATCHES: List<Int> = listOf(
    0xFF000000.toInt(), 0xFFDDE1E6.toInt(), 0xFF5F656C.toInt(), 0xFFC9CED6.toInt(),
    0xFFFFFFFF.toInt(), 0xFF7F0000.toInt(), 0xFFFF0000.toInt(), 0xFFFF7F7F.toInt(),
    0xFF7F3F00.toInt(), 0xFFFF7F00.toInt(), 0xFFFFBF7F.toInt(), 0xFFFFE97F.toInt(),
    0xFF007F00.toInt(), 0xFF00FF00.toInt(), 0xFF7FFF7F.toInt(), 0xFF00FFFF.toInt(),
    0xFF007FFF.toInt(), 0xFF0000FF.toInt(), 0xFF7F00FF.toInt(), 0xFFFF00FF.toInt(),
    0xFF8B4513.toInt(), 0xFFD2691E.toInt(), 0xFF2E8B57.toInt(), 0xFFF4F5F7.toInt(),
)

@Composable
private fun ColorChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF22262B),
        fontSize = 10.sp,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(Color(0xFFFFFFFF), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** 最近用过的颜色 + 背景色，跟着 App 走（SharedPreferences `reveal_colors`） */
internal object ColorHistory {
    private const val PREF = "reveal_colors"
    private const val KEY = "recent"
    private const val MAX = 32

    /** 背景色（这一版先放内存里，交换 / 复位都动它） */
    var bg: Int = 0xFFFFFFFF.toInt()

    fun load(ctx: android.content.Context): List<Int> {
        val raw = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull()?.toInt() }
    }

    fun push(ctx: android.content.Context, c: Int): List<Int> {
        val old = load(ctx).toMutableList()
        old.remove(c)
        old.add(0, c)
        while (old.size > MAX) old.removeAt(old.size - 1)
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit()
            .putString(KEY, old.joinToString(",") { it.toString() }).apply()
        return old
    }
}
