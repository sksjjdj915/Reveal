package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveal.editor.image.ColorAdjust
import com.reveal.editor.image.Levels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.reveal.editor.image.Curves
import kotlin.math.roundToInt

/**
 * 色阶面板（照 桌面端的「图像 → 调整 → 色阶」）：
 * 通道 RGB/红/绿/蓝，输入色阶 黑场/伽马/白场，输出色阶 黑场/白场。
 * 拖滑块即时预览，点「应用」才写回图层。
 */
@Composable
fun LevelsPanel(
    initial: Levels.Params,
    onParams: (Levels.Params) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var channel by remember { mutableIntStateOf(initial.channel) }
    var inBlack by remember { mutableFloatStateOf(initial.inBlack.toFloat()) }
    var gamma by remember { mutableFloatStateOf(initial.gamma) }
    var inWhite by remember { mutableFloatStateOf(initial.inWhite.toFloat()) }
    var outBlack by remember { mutableFloatStateOf(initial.outBlack.toFloat()) }
    var outWhite by remember { mutableFloatStateOf(initial.outWhite.toFloat()) }

    val params = Levels.Params(
        channel = channel,
        inBlack = inBlack.toInt(),
        gamma = gamma,
        inWhite = inWhite.toInt(),
        outBlack = outBlack.toInt(),
        outWhite = outWhite.toInt(),
    )
    LaunchedEffect(params) { onParams(params) }

    Surface(
        modifier = modifier,
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("色阶", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消", fontSize = 12.sp) }
                TextButton(onClick = onApply) { Text("应用", fontSize = 12.sp) }
            }
            RvGroup("通道") {}
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Levels.CHANNEL_RGB to "RGB",
                    Levels.CHANNEL_R to "红",
                    Levels.CHANNEL_G to "绿",
                    Levels.CHANNEL_B to "蓝",
                ).forEach { (value, label) ->
                    TabChip(label, channel == value) { channel = value }
                }
            }
            RvGroup("输入色阶") {}
            ParamSlider("黑场", inBlack, 0f..254f, inBlack.toInt().toString()) {
                inBlack = it
                if (inBlack >= inWhite) inWhite = inBlack + 1
            }
            ParamSlider("灰点", gamma, 0.1f..3f, String.format("%.2f", gamma)) { gamma = it }
            ParamSlider("白场", inWhite, 1f..255f, inWhite.toInt().toString()) {
                inWhite = it
                if (inWhite <= inBlack) inBlack = (inWhite - 1).coerceAtLeast(0f)
            }
            RvGroup("输出色阶") {}
            ParamSlider("黑场", outBlack, 0f..255f, outBlack.toInt().toString()) { outBlack = it }
            ParamSlider("白场", outWhite, 0f..255f, outWhite.toInt().toString()) { outWhite = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    channel = Levels.CHANNEL_RGB
                    inBlack = 0f
                    gamma = 1f
                    inWhite = 255f
                    outBlack = 0f
                    outWhite = 255f
                }) { Text("复归默认", fontSize = 11.sp) }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "滑块即时预览",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 通道面板（照 桌面端的「窗口 → 通道」）：RGB / 红 / 绿 / 蓝 四行，
 * 点哪行就只看那个通道（灰度），再点 RGB 回到正常。
 */
@Composable
fun ChannelsPanel(
    thumb: Bitmap?,
    active: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onLoadSelection: (Int) -> Unit = {},
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("通道", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
            }
            listOf(
                -1 to "RGB",
                0 to "红",
                1 to "绿",
                2 to "蓝",
            ).forEach { (index, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                            .border(
                                width = if (active == index) 1.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(3.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = label,
                                modifier = Modifier.size(28.dp),
                                colorFilter = channelFilter(index),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (active == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (active == index) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    if (active == index) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (index >= 0) {
                        Text(
                            text = "→选区",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onLoadSelection(index) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 历史记录面板：照 桌面端的样子把每一步列出来（最新的在最上面），点哪一步就跳回那一步。
 * 位置 0 = 刚打开时的样子；位置 = labels.size 表示全部步骤都生效。
 */
@Composable
fun HistoryPanel(
    labels: List<String>,
    position: Int,
    onJump: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    snapshots: List<String> = emptyList(),
    onSnapshot: () -> Unit = {},
    onRestoreSnapshot: (Int) -> Unit = {},
    onDeleteSnapshot: (Int) -> Unit = {},
) {
    val steps = labels.size
    Surface(
        modifier = modifier,
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$position / $steps",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onSnapshot) { Text("快照", fontSize = 12.sp) }
                TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
            }
            if (snapshots.isNotEmpty()) {
                // 快照排在最上面：点一下就整份文档回到那个状态（不进撤销栈）
                for (i in snapshots.indices) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRestoreSnapshot(i) }
                            .background(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "◼",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = snapshots[i],
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDeleteSnapshot(i) }) {
                            Text("✕", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 从最新一步往回列，跟 桌面端一致
                for (i in steps downTo 0) {
                    val label = if (i == 0) "打开 / 新建" else labels[i - 1]
                    val active = i == position
                    val undone = i > position
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(i) }
                            .background(
                                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(3.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (active) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                active -> MaterialTheme.colorScheme.primary
                                undone -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 调色面板：贴在画布底部，只调「当前选中的图层」。
 * 分组照主流专业调色的五组：基础 / 亮 / 颜色 / 效果 / 细节，每组可折叠，整块限高可滚。
 */
@Composable
fun AdjustPanel(
    initial: ColorAdjust.Params,
    layerName: String,
    onParams: (ColorAdjust.Params) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var p by remember { mutableStateOf(initial) }
    var openBasic by remember { mutableStateOf(true) }
    var openLight by remember { mutableStateOf(false) }
    var openColor by remember { mutableStateOf(false) }
    var openEffect by remember { mutableStateOf(false) }
    var openDetail by remember { mutableStateOf(false) }
    LaunchedEffect(p) { onParams(p) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        color = Color.White,
        contentColor = Rv.text,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
        ) {
            // 标题条：和参数区用两档深色分开，眼睛不糊
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelColorHeader)
                    .padding(horizontal = 12.dp),
            ) {
                Text("调色", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (layerName.isBlank()) "当前图层" else layerName,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel, modifier = Modifier.height(34.dp)) { Text("取消", fontSize = 12.sp) }
                TextButton(onClick = onApply, modifier = Modifier.height(34.dp)) { Text("应用", fontSize = 12.sp) }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                AdjustSection("基础", openBasic, { openBasic = it }) {
                    ParamSlider("亮度", p.brightness, -1f..1f, fmt(p.brightness)) { p = p.copy(brightness = it) }
                    ParamSlider("对比度", p.contrast, -1f..1f, fmt(p.contrast)) { p = p.copy(contrast = it) }
                    ParamSlider("饱和度", p.saturation, -1f..1f, fmt(p.saturation)) { p = p.copy(saturation = it) }
                    ParamSlider("自然饱和度", p.vibrance, -1f..1f, fmt(p.vibrance)) { p = p.copy(vibrance = it) }
                }
                AdjustSection("亮", openLight, { openLight = it }) {
                    ParamSlider("曝光", p.exposure, -1f..1f, fmt(p.exposure)) { p = p.copy(exposure = it) }
                    ParamSlider("高光", p.highlights, -1f..1f, fmt(p.highlights)) { p = p.copy(highlights = it) }
                    ParamSlider("阴影", p.shadows, -1f..1f, fmt(p.shadows)) { p = p.copy(shadows = it) }
                    ParamSlider("白色", p.whites, -1f..1f, fmt(p.whites)) { p = p.copy(whites = it) }
                    ParamSlider("黑色", p.blacks, -1f..1f, fmt(p.blacks)) { p = p.copy(blacks = it) }
                }
                AdjustSection("颜色", openColor, { openColor = it }) {
                    ParamSlider("色温", p.temperature, -1f..1f, fmt(p.temperature)) { p = p.copy(temperature = it) }
                    ParamSlider("色调", p.tint, -1f..1f, fmt(p.tint)) { p = p.copy(tint = it) }
                    ParamSlider("色相", p.hue, -1f..1f, fmt(p.hue)) { p = p.copy(hue = it) }
                }
                AdjustSection("效果", openEffect, { openEffect = it }) {
                    ParamSlider("清晰度", p.clarity, -1f..1f, fmt(p.clarity)) { p = p.copy(clarity = it) }
                    ParamSlider("去朦胧", p.dehaze, -1f..1f, fmt(p.dehaze)) { p = p.copy(dehaze = it) }
                    ParamSlider("暗角", p.vignette, -1f..1f, fmt(p.vignette)) { p = p.copy(vignette = it) }
                    ParamSlider("颗粒", p.grain, 0f..1f, fmt(p.grain)) { p = p.copy(grain = it) }
                }
                AdjustSection("细节", openDetail, { openDetail = it }) {
                    ParamSlider("锐化", p.sharpening, 0f..1f, fmt(p.sharpening)) { p = p.copy(sharpening = it) }
                    ParamSlider("减少杂色", p.noiseReduction, 0f..1f, fmt(p.noiseReduction)) { p = p.copy(noiseReduction = it) }
                }
            }
        }
    }
}

private fun fmt(v: Float): String = if (v == 0f) "0" else String.format("%.2f", v)

/** 可折叠的一组参数（点标题行展开/收起），标题条单独一档底色 */
@Composable
private fun AdjustSection(
    title: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!open) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            if (open) "收起" else "展开",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) content()
}

/** -1 = 原色；0/1/2 = 只留红/绿/蓝，并把结果铺到 RGB 三通道（就是 桌面端里看单个通道的灰度） */
private fun channelFilter(channel: Int): ColorFilter? {
    if (channel < 0) return null
    val m = FloatArray(20)
    m[channel] = 1f
    m[5 + channel] = 1f
    m[10 + channel] = 1f
    m[18] = 1f
    return ColorFilter.colorMatrix(ColorMatrix(m))
}

/** 面板用色：只用一档中性深灰，靠深浅区分层次，不搞多彩 */
/** 滤镜参数面板：贴在画布底部，拖滑块时实时预览（不挡整屏） */
@Composable
fun FilterPanel(
    title: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        contentColor = Rv.text,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消", fontSize = 12.sp) }
                TextButton(onClick = onApply) { Text("应用", fontSize = 12.sp) }
            }
            ThinSlider(value = value, range = range, onChange = onValue)
            Text(hint, fontSize = 10.sp, color = Color(0xFF6B7178))
        }
    }
}

internal val PanelColorAdjust = Color(0xFFF4F5F7)
internal val PanelColorHeader = Color(0xFFFFFFFF)
internal val PanelColorLayer = Color(0xFFF4F5F7)

/**
 * 曲线面板（照 桌面端的「图像 → 调整 → 曲线」）：
 * 通道 RGB/红/绿/蓝，画布上拖动控制点，空白处点一下加点，两点起点/终点不能动。
 * 拖的时候即时预览，点「应用」才写回图层。
 */
@Composable
fun CurvesPanel(
    initial: Curves.Params,
    onParams: (Curves.Params) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onAuto: ((Int) -> IntArray?)? = null,
    onPickStart: ((Int) -> Unit)? = null,
    pickValue: Int? = null,
    onPickConsumed: (() -> Unit)? = null,
    onSampleAll: ((Boolean) -> Unit)? = null,
) {
    var channel by remember { mutableIntStateOf(initial.channel) }
    var points by remember { mutableStateOf(initial.points.toMutableList()) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    // 每个通道各存一套点：RGB 是主曲线，红/绿/蓝互不影响
    var rgbPts by remember { mutableStateOf(initial.points.toMutableList()) }
    var rPts by remember { mutableStateOf(initial.redPoints.toMutableList()) }
    var gPts by remember { mutableStateOf(initial.greenPoints.toMutableList()) }
    var bPts by remember { mutableStateOf(initial.bluePoints.toMutableList()) }
    var activeIdx by remember { mutableIntStateOf(-1) }
    var previewOn by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(true) }
    var editField by remember { mutableIntStateOf(-1) }
    // 吸管取样：false = 当前图层，true = 混合合成
    var pickAll by remember { mutableStateOf(false) }
    var editVal by remember { mutableStateOf("") }
    fun saveCur() {
        when (channel) {
            Curves.CHANNEL_R -> rPts = points.toMutableList()
            Curves.CHANNEL_G -> gPts = points.toMutableList()
            Curves.CHANNEL_B -> bPts = points.toMutableList()
            else -> rgbPts = points.toMutableList()
        }
    }
    fun loadCur(c: Int) {
        points = when (c) {
            Curves.CHANNEL_R -> rPts
            Curves.CHANNEL_G -> gPts
            Curves.CHANNEL_B -> bPts
            else -> rgbPts
        }.toMutableList()
    }

    val params = Curves.Params(
        channel = channel,
        points = points.toList(),
        redPoints = rPts.toList(),
        greenPoints = gPts.toList(),
        bluePoints = bPts.toList(),
    )
    // 预览开关：关掉就不实时回传，点应用才生效
    LaunchedEffect(params, previewOn) { if (previewOn) onParams(params) }

    // 吸管取到的色阶：在当前曲线上补一个点并选中它，接着能拖能敲数值
    LaunchedEffect(pickValue) {
        val v = pickValue ?: return@LaunchedEffect
        val x = v / 255f
        val list = points.toMutableList()
        var idx = -1
        for (i in list.indices step 2) {
            if (kotlin.math.abs(list[i] - x) < 0.005f) {
                idx = i
                break
            }
        }
        if (idx < 0) {
            var at = list.size
            for (i in list.indices step 2) {
                if (list[i] > x) {
                    at = i
                    break
                }
            }
            if (at > 0 && at <= list.size - 2) {
                val y = Curves.lutOf(points)[v.coerceIn(0, 255)] / 255f
                list.add(at, x)
                list.add(at, y)
                idx = at
            }
        }
        if (idx >= 0) {
            points = list
            activeIdx = idx
        }
        onPickConsumed?.invoke()
    }

    Surface(
        modifier = modifier,
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("曲线", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "取消",
                    fontSize = 11.5.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier.clickable { onCancel() }.padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Text(
                    "应用",
                    fontSize = 11.5.sp,
                    color = Color(0xFF4F8CFF),
                    modifier = Modifier.clickable { onParams(params); onApply() }.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("通道", fontSize = 10.sp, color = Rv.textFaint)
                Spacer(Modifier.width(4.dp))
                listOf(
                    Curves.CHANNEL_RGB to "RGB",
                    Curves.CHANNEL_R to "红",
                    Curves.CHANNEL_G to "绿",
                    Curves.CHANNEL_B to "蓝",
                ).forEach { (value, label) ->
                    TabChip(label, channel == value) {
                        if (channel != value) {
                            saveCur()
                            channel = value
                            loadCur(value)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(Color(0xFFF4F5F7))
                    .border(1.dp, Color(0xFFFFFFFF)),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var lastTapMs = 0L
                            var lastTapIdx = -1
                            // 一个手势自己分发：按在点上就拖它，按在空白就加点，双击点就删点。
                            // 不能用 detectTapGestures + detectDragGestures 叠两层，tap 会先吃掉 down，拖点就废了。
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w <= 0f || h <= 0f) return@awaitEachGesture
                                val nx0 = (down.position.x / w).coerceIn(0f, 1f)
                                val ny0 = (1f - down.position.y / h).coerceIn(0f, 1f)
                                var idx = -1
                                var bestD = Float.MAX_VALUE
                                for (i in points.indices step 2) {
                                    val dx = (points[i] - nx0) * w
                                    val dy = (points[i + 1] - ny0) * h
                                    val d = dx * dx + dy * dy
                                    if (d < bestD) {
                                        bestD = d
                                        idx = i
                                    }
                                }
                                // 手指离点超过 26px 就当按的是空白
                                if (bestD > 26f * 26f) idx = -1
                                activeIdx = idx
                                var moved = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = ch.position.x - down.position.x
                                    val dy = ch.position.y - down.position.y
                                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > 6.0) moved = true
                                    if (!ch.pressed) break
                                    if (idx >= 0 && moved) {
                                        val nx = (ch.position.x / w).coerceIn(0f, 1f)
                                        val ny = (1f - ch.position.y / h).coerceIn(0f, 1f)
                                        val list = points.toMutableList()
                                        val isFirst = idx == 0
                                        val isLast = idx == list.size - 2
                                        // 两端的点只能上下拖，横坐标锁死；中间的点不许越过邻居
                                        val lo = if (idx - 2 >= 0) list[idx - 2] + 0.01f else 0f
                                        val hi = if (idx + 2 < list.size) list[idx + 2] - 0.01f else 1f
                                        list[idx] = if (isFirst || isLast) list[idx] else nx.coerceIn(lo, hi)
                                        list[idx + 1] = ny
                                        points = list
                                    }
                                    ch.consume()
                                }
                                if (!moved) {
                                    if (idx >= 0) {
                                        // 双击同一个点：删掉（两个端点不能删）
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapMs < 400L && lastTapIdx == idx && idx > 0 && idx < points.size - 2) {
                                            val list = points.toMutableList()
                                            list.removeAt(idx + 1)
                                            list.removeAt(idx)
                                            points = list
                                            lastTapIdx = -1
                                        } else {
                                            lastTapMs = now
                                            lastTapIdx = idx
                                        }
                                    } else if (points.size < 40) {
                                        val list = points.toMutableList()
                                        var at = list.size
                                        for (i in list.indices step 2) {
                                            if (list[i] > nx0) {
                                                at = i
                                                break
                                            }
                                        }
                                        // 落在最后一个点右边也算加点（夹在它右侧一点点），
                                        // 原来这里直接 return，导致默认直线状态下怎么点都不加点
                                        if (at >= list.size && nx0 <= list[list.size - 2] + 0.01f) at = -1
                                        if (at > 0) {
                                            val x = if (at >= list.size) nx0.coerceIn(list[list.size - 2] + 0.01f, 1f) else nx0
                                            list.add(at, ny0)
                                            list.add(at, x)
                                            points = list
                                        }
                                        lastTapIdx = -1
                                    }
                                }
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    // 网格（跟 桌面端一样 4×4，可以关）
                    if (showGrid) {
                        for (i in 1..3) {
                            val gx = w * i / 4f
                            val gy = h * i / 4f
                            drawLine(Color(0xFFFFFFFF), Offset(gx, 0f), Offset(gx, h), 1f)
                            drawLine(Color(0xFFFFFFFF), Offset(0f, gy), Offset(w, gy), 1f)
                        }
                        drawLine(Color(0xFFE8EBEF), Offset(0f, h), Offset(w, 0f), 1f)
                    }
                    // 其他通道的曲线：淡色叠在背景上做参考（跟 桌面端一样）
                    val others: List<Pair<List<Float>, Color>> = when (channel) {
                        Curves.CHANNEL_R, Curves.CHANNEL_G, Curves.CHANNEL_B ->
                            listOf(rgbPts.toList() to Color(0x1F000000))
                        else -> listOf(
                            rPts.toList() to Color(0x66FF5A5A),
                            gPts.toList() to Color(0x665AD37A),
                            bPts.toList() to Color(0x665A8CFF),
                        )
                    }
                    for ((pts, col) in others) {
                        val tb = Curves.lutOf(pts)
                        val pth = Path()
                        for (k in 0..63) {
                            val x = k / 63f
                            val v = tb[(x * 255f).toInt().coerceIn(0, 255)] / 255f
                            if (k == 0) pth.moveTo(x * w, (1f - v) * h) else pth.lineTo(x * w, (1f - v) * h)
                        }
                        drawPath(pth, col, style = Stroke(width = 1.2f))
                    }
                    // 当前通道的曲线：RGB 白、红通道红、绿通道绿、蓝通道蓝
                    val curCol = when (channel) {
                        Curves.CHANNEL_R -> Color(0xFFFF5A5A)
                        Curves.CHANNEL_G -> Color(0xFF5AD37A)
                        Curves.CHANNEL_B -> Color(0xFF5A8CFF)
                        else -> Color(0xFF22262B)
                    }
                    val table = Curves.lutOf(points.toList())
                    val path = Path()
                    for (k in 0..63) {
                        val x = k / 63f
                        val v = table[(x * 255f).toInt().coerceIn(0, 255)] / 255f
                        val px = x * w
                        val py = (1f - v) * h
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, curCol, style = Stroke(width = 1.7f))
                    // 控制点
                    for (i in points.indices step 2) {
                        val cx = points[i] * w
                        val cy = (1f - points[i + 1]) * h
                        drawCircle(Color(0xFFF4F5F7), radius = 5.5f, center = Offset(cx, cy))
                        drawCircle(Color(0xFF22262B), radius = 5.5f, center = Offset(cx, cy), style = Stroke(width = 1.6f))
                    }
                }
                // 拖动时显示这一点的输入/输出值（0~255），跟 桌面端一样
                Text(
                    text = if (activeIdx >= 0 && activeIdx + 1 < points.size) {
                        "输入 " + (points[activeIdx] * 255f).toInt() + "  \u2192  输出 " + (points[activeIdx + 1] * 255f).toInt()
                    } else {
                        "点空白加点 \u00b7 双击删点"
                    },
                    color = Color(0xFF6B7178),
                    fontSize = 9.5.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp),
                )
            }
            val hasPt = activeIdx >= 0 && activeIdx + 1 < points.size
            val inTxt = if (hasPt) (points[activeIdx] * 255f).toInt().toString() else "--"
            val outTxt = if (hasPt) (points[activeIdx + 1] * 255f).toInt().toString() else "--"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "输入 " + inTxt,
                    fontSize = 10.5.sp,
                    color = if (hasPt) Color(0xFF22262B) else Color(0xFF4A5057),
                    modifier = Modifier
                        .clickable(enabled = hasPt) {
                            editField = 0
                            editVal = inTxt
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = "输出 " + outTxt,
                    fontSize = 10.5.sp,
                    color = if (hasPt) Color(0xFF22262B) else Color(0xFF4A5057),
                    modifier = Modifier
                        .clickable(enabled = hasPt) {
                            editField = 1
                            editVal = outTxt
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (previewOn) "预览 开" else "预览 关",
                    fontSize = 10.5.sp,
                    color = if (previewOn) Color(0xFF4F8CFF) else Color(0xFF6B7178),
                    modifier = Modifier.clickable { previewOn = !previewOn }.padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Text(
                    text = if (showGrid) "网格 开" else "网格 关",
                    fontSize = 10.5.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier.clickable { showGrid = !showGrid }.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "复归",
                    fontSize = 10.5.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier
                        .clickable {
                            // 只把当前通道拉直，别的通道不动
                            points = Curves.DEFAULT_POINTS.toMutableList()
                            saveCur()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                // 自动：按直方图把最暗/最亮 0.5% 切掉，和 桌面端的「自动」一个意思
                TabChip("自动", false) {
                    val h = onAuto?.invoke(channel)
                    if (h != null) {
                        var total = 0
                        for (v in 0..255) total += h[v]
                        if (total > 0) {
                            var lo = 0
                            var hi = 255
                            var acc = 0
                            for (v in 0..255) {
                                acc += h[v]
                                if (acc >= total * 0.005f) {
                                    lo = v
                                    break
                                }
                            }
                            acc = 0
                            for (v in 255 downTo 0) {
                                acc += h[v]
                                if (acc >= total * 0.005f) {
                                    hi = v
                                    break
                                }
                            }
                            if (hi - lo >= 8) {
                                val loF = lo / 255f
                                val hiF = hi / 255f
                                val pts = mutableListOf<Float>()
                                pts.add(0f)
                                pts.add(0f)
                                if (loF > 0.006f) {
                                    pts.add(loF)
                                    pts.add(0f)
                                }
                                if (hiF < 0.994f) {
                                    pts.add(hiF)
                                    pts.add(1f)
                                }
                                pts.add(1f)
                                pts.add(1f)
                                points = pts
                                saveCur()
                            }
                        }
                    }
                }
                // 吸管：在画面上点一下，取那个点的色阶当控制点
                TabChip("吸管", false) { onPickStart?.invoke(channel) }
                // 吸管取样看当前图层还是看混合结果，随时切
                TabChip(if (pickAll) "取样 混合" else "取样 图层", pickAll) {
                    pickAll = !pickAll
                    onSampleAll?.invoke(pickAll)
                }
            }
        }
    }

    // 点「输入/输出」弹数字键盘，直接敲精确值（桌面端那样）
    if (editField >= 0 && activeIdx >= 0 && activeIdx + 1 < points.size) {
        AlertDialog(
            onDismissRequest = { editField = -1 },
            title = { Text(if (editField == 0) "输入" else "输出", fontSize = 13.sp) },
            text = {
                OutlinedTextField(
                    value = editVal,
                    onValueChange = { v -> editVal = v.filter { c -> c.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val num = editVal.toIntOrNull()
                    if (num != null) {
                        val list = points.toMutableList()
                        val v = num.coerceIn(0, 255) / 255f
                        if (editField == 0) {
                            val isEdge = activeIdx == 0 || activeIdx == list.size - 2
                            val lo = if (activeIdx - 2 >= 0) list[activeIdx - 2] + 0.01f else 0f
                            val hi = if (activeIdx + 2 < list.size) list[activeIdx + 2] - 0.01f else 1f
                            list[activeIdx] = if (isEdge) list[activeIdx] else v.coerceIn(lo, hi)
                        } else {
                            list[activeIdx + 1] = v
                        }
                        points = list
                        saveCur()
                    }
                    editField = -1
                }) { Text("好") }
            },
            dismissButton = { TextButton(onClick = { editField = -1 }) { Text("取消") } },
        )
    }
}

@Composable
fun TextPanel(
    editing: Boolean,
    value: String,
    onValue: (String) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    color: Int,
    onPickColor: () -> Unit,
    font: String,
    onFont: (String) -> Unit,
    spec: TextSpec,
    onSpec: (TextSpec) -> Unit,
    onManageFonts: () -> Unit = {},
    onPickFont: () -> Unit = {},
    fontsVersion: Int = 0,
    pathNames: List<String> = emptyList(),
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 分区折叠：同一时间只展开一个区，面板自然很矮，不用整天滚来滚去
    var sec by remember { mutableStateOf("basic") }
    val ctx = LocalContext.current
    fun toggle(id: String) { sec = if (sec == id) "" else id }
    val curFontLabel = remember(font, fontsVersion) {
        com.reveal.editor.image.BundledFonts.find(font)?.label
            ?: TextFonts.firstOrNull { it.first == font }?.second ?: "默认"
    }
    val bundled = remember(fontsVersion) { com.reveal.editor.image.BundledFonts.visible(ctx) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        color = Color.White,
        contentColor = Rv.text,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (editing) "编辑文字" else "添加文字",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "取消",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier.clickable { onCancel() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "确定",
                    fontSize = 11.sp,
                    color = Color(0xFF7FB0FF),
                    modifier = Modifier.clickable { onConfirm() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0F000000)))

            Column(Modifier.heightIn(max = 322.dp).verticalScroll(rememberScrollState())) {
                // ---------- 文本 ----------
                TextSecRow("文本", if (sec == "basic") "" else value.replace("\n", " ").take(12), sec == "basic") { toggle("basic") }
                if (sec == "basic") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = onValue,
                            label = { Text("内容", fontSize = 10.sp) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 80.dp),
                        )
                        ParamSlider(
                            label = "字号",
                            value = size,
                            range = 12f..1200f,
                            display = size.toInt().toString(),
                            onChange = onSize,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(26.dp)) {
                            Text("颜色", fontSize = 10.sp, modifier = Modifier.width(46.dp), color = Color(0xFF6B7178))
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color(color), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0x1A000000), RoundedCornerShape(4.dp))
                                    .clickable { onPickColor() }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("点色块取色", fontSize = 9.sp, color = Color(0xFF5A6068))
                        }
                    }
                }

                // ---------- 字体 ----------
                TextSecRow("字体", curFontLabel, sec == "font") { toggle("font") }
                if (sec == "font") {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        // 当前字体的实时样例：换字体这里立刻变，不用去画布上猜
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFFFFFF), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            FontSample(
                                sample = "永字八法 Aa Bb 123",
                                typeface = remember(font) {
                                    if (font.isEmpty()) null
                                    else com.reveal.editor.image.BundledFonts.previewTypeface(ctx, font)
                                },
                                fontSizeSp = 15f,
                                color = Color(0xFF22262B),
                                modifier = Modifier.width(210.dp).height(24.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 顺序：系统字体在最上，中间是随包的中文花字（草书/行书/手写），英文最下面
                        FontGroupHeader("系统字体")
                        TextFonts.forEach { (key, label) ->
                            TextFontItem(
                                label = label,
                                sample = if (label.startsWith("等宽") || label.startsWith("手写")) "Aa Bb 123" else "永字八法",
                                typeface = null,
                                on = font == key,
                            ) { onFont(key) }
                        }
                        FontGroupHeader("花字（草书 / 行书 / 手写，随包可商用）")
                        bundled.filter { it.lang == "中" }.forEach { e ->
                            TextFontItem(
                                label = e.label,
                                sample = e.label,
                                typeface = remember(e.key) {
                                    com.reveal.editor.image.BundledFonts.previewTypeface(ctx, e.key)
                                },
                                on = font == e.key,
                            ) { onFont(e.key) }
                        }
                        FontGroupHeader("英文字体（随包可商用）")
                        bundled.filter { it.lang == "英" }.forEach { e ->
                            TextFontItem(
                                label = e.label,
                                sample = "Aa Bb 123",
                                typeface = remember(e.key) {
                                    com.reveal.editor.image.BundledFonts.previewTypeface(ctx, e.key)
                                },
                                on = font == e.key,
                            ) { onFont(e.key) }
                        }
                        Row(Modifier.padding(top = 4.dp)) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                "管理字体",
                                fontSize = 10.sp,
                                color = Color(0xFF7FB0FF),
                                modifier = Modifier.clickable { onManageFonts() }.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // ---------- 字符样式 ----------
                TextSecRow("字符样式", charStyleSummary(spec), sec == "char") { toggle("char") }
                if (sec == "char") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Row {
                            TextChip("粗体", spec.bold) { onSpec(spec.copy(bold = it)) }
                            TextChip("斜体", spec.italic) { onSpec(spec.copy(italic = it)) }
                            TextChip("下划线", spec.underline) { onSpec(spec.copy(underline = it)) }
                            TextChip("删除线", spec.strike) { onSpec(spec.copy(strike = it)) }
                        }
                        Row {
                            TextChip("全大写", spec.caps == 1) { onSpec(spec.copy(caps = if (it) 1 else 0)) }
                            TextChip("全小写", spec.caps == 2) { onSpec(spec.copy(caps = if (it) 2 else 0)) }
                            TextChip("小型大写", spec.caps == 3) { onSpec(spec.copy(caps = if (it) 3 else 0)) }
                        }
                        Row {
                            TextChip("上标", spec.superSub == 1) { onSpec(spec.copy(superSub = if (it) 1 else 0)) }
                            TextChip("下标", spec.superSub == 2) { onSpec(spec.copy(superSub = if (it) 2 else 0)) }
                            TextChip("竖排", spec.vertical) { onSpec(spec.copy(vertical = it)) }
                        }
                    }
                }

                // ---------- 间距 ----------
                TextSecRow("间距", "字距 ${spec.tracking.roundToInt()} · 行距 ${spec.leading.roundToInt()}%", sec == "space") { toggle("space") }
                if (sec == "space") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        ParamSlider("字距", spec.tracking, -100f..1000f, spec.tracking.roundToInt().toString()) {
                            onSpec(spec.copy(tracking = it))
                        }
                        ParamSlider("行距", spec.leading, -50f..200f, "${spec.leading.roundToInt()}%") {
                            onSpec(spec.copy(leading = it))
                        }
                    }
                }

                // ---------- 比例 ----------
                TextSecRow("比例", "横 ${spec.scaleX.roundToInt()}% · 纵 ${spec.scaleY.roundToInt()}%", sec == "scale") { toggle("scale") }
                if (sec == "scale") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        ParamSlider("横向", spec.scaleX, 10f..400f, "${spec.scaleX.roundToInt()}%") {
                            onSpec(spec.copy(scaleX = it))
                        }
                        ParamSlider("纵向", spec.scaleY, 10f..400f, "${spec.scaleY.roundToInt()}%") {
                            onSpec(spec.copy(scaleY = it))
                        }
                        ParamSlider("基线", spec.shift, -100f..100f, spec.shift.roundToInt().toString()) {
                            onSpec(spec.copy(shift = it))
                        }
                    }
                }

                // ---------- 对齐 ----------
                TextSecRow("对齐", listOf("左对齐", "居中", "右对齐", "两端对齐").getOrElse(spec.align) { "居中" }, sec == "align") { toggle("align") }
                if (sec == "align") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Row {
                            listOf("左对齐", "居中", "右对齐", "两端对齐").forEachIndexed { i, label ->
                                TextChip(label, spec.align == i) { onSpec(spec.copy(align = i)) }
                            }
                        }
                    }
                }

                // ---------- 变形 ----------
                TextSecRow("变形", TextWarps.getOrElse(spec.warp) { "无" }, sec == "warp") { toggle("warp") }
                if (sec == "warp") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        TextWarps.indices.chunked(5).forEach { row ->
                            Row {
                                row.forEach { i ->
                                    TextChip(TextWarps[i], spec.warp == i) { onSpec(spec.copy(warp = i)) }
                                }
                            }
                        }
                        ParamSlider("弯曲", spec.warpBend, 0f..100f, spec.warpBend.roundToInt().toString()) {
                            onSpec(spec.copy(warpBend = it))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextChip("路径文字", spec.pathText) { onSpec(spec.copy(pathText = it)) }
                        }
                        if (spec.pathText) {
                            val names = pathNames
                            if (names.isEmpty()) {
                                Text("先在钢笔/形状工具里存一条路径", fontSize = 9.sp, color = Color(0xFF5A6068))
                            } else {
                                names.chunked(2).forEach { row ->
                                    Row {
                                        row.forEach { nm ->
                                            val i = names.indexOf(nm)
                                            TextChip(if (nm.length > 8) nm.take(8) else nm, spec.pathIndex == i) {
                                                onSpec(spec.copy(pathIndex = i))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 分区标题行：折叠/展开，右边带一句当前值的摘要 */
@Composable
private fun TextSecRow(title: String, summary: String, open: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 7.dp),
        ) {
            Text(
                if (open) "▾" else "▸",
                fontSize = 9.sp,
                color = if (open) Color(0xFF7FB0FF) else Color(0xFF4A5057),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (open) Color(0xFF22262B) else Color(0xFF8A9099),
            )
            Spacer(Modifier.weight(1f))
            if (summary.isNotEmpty()) {
                Text(summary, fontSize = 9.sp, color = Color(0xFF5A6068), maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0A000000)))
    }
}

/** 字符样式的摘要（折叠时显示在标题右边） */
private fun charStyleSummary(spec: TextSpec): String {
    val list = ArrayList<String>()
    if (spec.bold) list.add("粗")
    if (spec.italic) list.add("斜")
    if (spec.underline) list.add("下划线")
    if (spec.strike) list.add("删除线")
    if (spec.caps == 1) list.add("大写")
    if (spec.caps == 2) list.add("小写")
    if (spec.caps == 3) list.add("小型大写")
    if (spec.superSub == 1) list.add("上标")
    if (spec.superSub == 2) list.add("下标")
    if (spec.vertical) list.add("竖排")
    return if (list.isEmpty()) "无" else list.joinToString(" ")
}

/** 方角小片开关（比 Material 的按钮省地方，也不会一行挤爆） */
@Composable
private fun TextChip(label: String, on: Boolean, onClick: (Boolean) -> Unit) {
    Text(
        text = label,
        fontSize = 10.sp,
        color = if (on) Color.White else Color(0xFFC9CED6),
        modifier = Modifier
            .padding(end = 5.dp, bottom = 4.dp)
            .background(if (on) Color(0xFF3F6FD8) else Color(0xFFFFFFFF), RoundedCornerShape(5.dp))
            .border(1.dp, if (on) Color(0xFF5C8CF0) else Color(0xFFE8EBEF), RoundedCornerShape(5.dp))
            .clickable { onClick(!on) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 字体列表里的分组小标题 */
@Composable
private fun FontGroupHeader(title: String) {
    Text(
        title,
        fontSize = 9.sp,
        color = Color(0xFF5A6068),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 3.dp),
    )
}

/** 字体列表里的一行：左边用该字体画一段预览，右边名字 */
@Composable
private fun TextFontItem(
    label: String,
    sample: String,
    typeface: android.graphics.Typeface?,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (on) Color(0xFFE9F0FF) else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        FontSample(
            sample = sample,
            typeface = typeface,
            fontSizeSp = 12.5f,
            color = if (on) Color.White else Color(0xFFD2D6DC),
            modifier = Modifier.width(104.dp).height(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 9.5.sp, color = if (on) Color(0xFFDCE7FF) else Color(0xFF5F656C), maxLines = 1)
    }
}

@Composable
private fun FontChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .background(if (on) Color(0xFF4F8CFF) else Color(0xFFE8EBEF), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text = label, fontSize = 11.sp, color = if (on) Color.White else Color(0xFFD2D6DC))
    }
}

/** 可选字体：Android 家族名 → 面板上显示的名字（空串 = 系统默认） */
val TextFonts: List<Pair<String, String>> = listOf(
    "" to "默认（黑体）",
    "sans-serif" to "黑体",
    "serif" to "宋体",
    // 这两个系统家族没有中文字形，中文会回退成默认字体，只有英文看得见差别
    "monospace" to "等宽（英文）",
    "cursive" to "手写（英文）",
)

/** 参数条自己的关闭回调（EditorScreen 提供；为空表示这条参数条不带关闭按钮） */
val LocalBarClose = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** 参数条上的一个滑块（工具参数多的用它追加，ToolOptionsBar 只内置两个不够用） */
data class BarSlider(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val display: String,
    val onChange: (Float) -> Unit,
)

@Composable
fun ToolOptionsBar(
    title: String,
    hint: String,
    extraSliders: List<BarSlider> = emptyList(),
    sliderLabel: String? = null,
    sliderValue: Float = 0f,
    sliderRange: ClosedFloatingPointRange<Float> = 0.05f..1f,
    sliderDisplay: String = "",
    onSlider: (Float) -> Unit = {},
    slider2Label: String? = null,
    slider2Value: Float = 0f,
    slider2Range: ClosedFloatingPointRange<Float> = 0.05f..1f,
    slider2Display: String = "",
    onSlider2: (Float) -> Unit = {},
    chips: List<String> = emptyList(),
    chipIndex: Int = 0,
    onChip: (Int) -> Unit = {},
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = Color(0xFFFFFFFF),
        contentColor = Color(0xFF22262B),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel, fontSize = 11.sp) }
                }
                actions.forEach { (label, action) ->
                    TextButton(onClick = action) { Text(label, fontSize = 11.sp) }
                }
                val close = LocalBarClose.current
                if (close != null) {
                    TextButton(
                        onClick = close,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp
                        ),
                    ) { Text("✕", fontSize = 12.sp, color = Color(0xFF6B7178)) }
                }
            }
            // 提示文字单独占一行：以前和标题、按钮挤在同一行（用 weight(1f)），
            // 按钮一多就被压成一字宽、竖着排下来，和标题叠成十字架
            Text(
                hint,
                fontSize = 9.sp,
                color = Color(0xFF6B7178),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (chips.isNotEmpty()) {
                Row {
                    chips.forEachIndexed { i, c ->
                        TabChip(c, i == chipIndex) { onChip(i) }
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            if (sliderLabel != null) {
                ParamSlider(
                    label = sliderLabel,
                    value = sliderValue,
                    range = sliderRange,
                    display = sliderDisplay,
                    onChange = onSlider,
                )
            }
            if (slider2Label != null) {
                ParamSlider(
                    label = slider2Label,
                    value = slider2Value,
                    range = slider2Range,
                    display = slider2Display,
                    onChange = onSlider2,
                )
            }
            extraSliders.forEach { s ->
                ParamSlider(
                    label = s.label,
                    value = s.value,
                    range = s.range,
                    display = s.display,
                    onChange = s.onChange,
                )
            }
        }
    }
}
