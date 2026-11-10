package com.reveal.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 画笔的「形状动态 / 传递 / 纹理 / 平滑」这一排。
 *
 * 单独放一个文件是为了不让 EditorScreen 那个 @Composable 超过 JVM 单方法 64KB 的上限
 * （Kotlin 编译会报 Method too large）。参数直接写回 SketchView，所以这里不需要往外传。
 */
@Composable
internal fun BrushDynControls(
    sketch: SketchView?,
    hasDoc: Boolean,
    enabled: Boolean,
    onPreset: ((BrushPreset) -> Unit)? = null,
) {
    var angle by remember { mutableFloatStateOf(sketch?.brushAngle ?: 0f) }
    var sizeJ by remember { mutableFloatStateOf(sketch?.brushSizeJitter ?: 0f) }
    var angleJ by remember { mutableFloatStateOf(sketch?.brushAngleJitter ?: 0f) }
    var roundJ by remember { mutableFloatStateOf(sketch?.brushRoundJitter ?: 0f) }
    var opJ by remember { mutableFloatStateOf(sketch?.brushOpacityJitter ?: 0f) }
    var flowJ by remember { mutableFloatStateOf(sketch?.brushFlowJitter ?: 0f) }
    var minD by remember { mutableFloatStateOf(sketch?.brushMinDiameter ?: 0.1f) }
    var smooth by remember { mutableFloatStateOf(sketch?.brushSmooth ?: 0f) }
    var velocity by remember { mutableFloatStateOf(sketch?.brushVelocity ?: 0f) }
    var wet by remember { mutableStateOf(sketch?.brushWetEdge ?: false) }
    var tex by remember { mutableIntStateOf(sketch?.brushTexture ?: -1) }
    var texMode by remember { mutableIntStateOf(sketch?.brushTextureMode ?: 0) }
    var texDepth by remember { mutableFloatStateOf(sketch?.brushTextureDepth ?: 0.5f) }
    var noise by remember { mutableFloatStateOf(sketch?.brushNoise ?: 0f) }
    var blend by remember { mutableIntStateOf(sketch?.brushBlend ?: 0) }
    var cj by remember { mutableFloatStateOf(sketch?.brushColorJitter ?: 0f) }
    var color2 by remember { mutableIntStateOf(sketch?.brushColor2 ?: 0xFFFFFFFF.toInt()) }
    var dual by remember { mutableStateOf(sketch?.brushDualOn ?: false) }
    var dualSize by remember { mutableFloatStateOf(sketch?.brushDualSize ?: 0.4f) }
    var dualCount by remember { mutableFloatStateOf((sketch?.brushDualCount ?: 3).toFloat()) }
    var autoErase by remember { mutableStateOf(sketch?.pencilAutoErase ?: false) }

    LaunchedEffect(angle) { sketch?.brushAngle = angle }
    LaunchedEffect(sizeJ) { sketch?.brushSizeJitter = sizeJ }
    LaunchedEffect(angleJ) { sketch?.brushAngleJitter = angleJ }
    LaunchedEffect(roundJ) { sketch?.brushRoundJitter = roundJ }
    LaunchedEffect(opJ) { sketch?.brushOpacityJitter = opJ }
    LaunchedEffect(flowJ) { sketch?.brushFlowJitter = flowJ }
    LaunchedEffect(minD) { sketch?.brushMinDiameter = minD }
    LaunchedEffect(smooth) { sketch?.brushSmooth = smooth }
    LaunchedEffect(velocity) { sketch?.brushVelocity = velocity }
    LaunchedEffect(wet) { sketch?.brushWetEdge = wet }
    LaunchedEffect(tex) { sketch?.brushTexture = tex }
    LaunchedEffect(texMode) { sketch?.brushTextureMode = texMode }
    LaunchedEffect(texDepth) { sketch?.brushTextureDepth = texDepth }
    LaunchedEffect(noise) { sketch?.brushNoise = noise }
    LaunchedEffect(blend) { sketch?.brushBlend = blend }
    LaunchedEffect(cj) { sketch?.brushColorJitter = cj }
    LaunchedEffect(color2) { sketch?.brushColor2 = color2 }
    LaunchedEffect(dual) { sketch?.brushDualOn = dual }
    LaunchedEffect(dualSize) { sketch?.brushDualSize = dualSize }
    LaunchedEffect(dualCount) { sketch?.brushDualCount = dualCount.roundToInt() }
    LaunchedEffect(autoErase) { sketch?.pencilAutoErase = autoErase }

    // 套预设 / 换画笔时 brushRev 会变：把整排参数从画布重读一遍。
    // 不重读的话面板会一直显示套用之前的旧值（用户得切一次工具才刷新）。
    val brushRevNow = sketch?.brushRev ?: 0
    LaunchedEffect(brushRevNow) {
        val s = sketch ?: return@LaunchedEffect
        if (brushRevNow == 0) return@LaunchedEffect
        angle = s.brushAngle
        sizeJ = s.brushSizeJitter
        angleJ = s.brushAngleJitter
        roundJ = s.brushRoundJitter
        opJ = s.brushOpacityJitter
        flowJ = s.brushFlowJitter
        minD = s.brushMinDiameter
        smooth = s.brushSmooth
        velocity = s.brushVelocity
        wet = s.brushWetEdge
        tex = s.brushTexture
        texMode = s.brushTextureMode
        texDepth = s.brushTextureDepth
        noise = s.brushNoise
        blend = s.brushBlend
        cj = s.brushColorJitter
        color2 = s.brushColor2
        dual = s.brushDualOn
        dualSize = s.brushDualSize
        dualCount = s.brushDualCount.toFloat()
        autoErase = s.pencilAutoErase
    }

    if (!enabled) return
    // 预设排在最上面：点一下把一整套参数铺好，下面的滑杆还是可以继续微调
    var presetIdx by remember { mutableIntStateOf(if (sketch != null) BrushPresets.nearest(sketch) else -1) }
    DynChips(
        label = "预设",
        labels = BrushPresets.all.map { it.name },
        index = presetIdx,
        enabled = hasDoc,
    ) { i ->
        presetIdx = i
        val p = BrushPresets.all[i]
        if (onPreset != null) onPreset(p) else sketch?.let { BrushPresets.apply(it, p) }
    }
    DynChips(
        label = "模式",
        labels = BlendNames,
        index = BlendModes.indexOf(blend).coerceAtLeast(0),
        enabled = hasDoc,
    ) { i -> blend = BlendModes[i] }
    SliderRow(
        label = "角度",
        value = angle,
        valueRange = 0f..180f,
        display = "${angle.roundToInt()}°",
        enabled = hasDoc,
        onChange = { angle = it },
    )
    SliderRow(
        label = "大小抖动",
        value = sizeJ,
        valueRange = 0f..1f,
        display = "${(sizeJ * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { sizeJ = it },
    )
    SliderRow(
        label = "最小直径",
        value = minD,
        valueRange = 0.05f..1f,
        display = "${(minD * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { minD = it },
    )
    SliderRow(
        label = "角度抖动",
        value = angleJ,
        valueRange = 0f..1f,
        display = "${(angleJ * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { angleJ = it },
    )
    SliderRow(
        label = "圆度抖动",
        value = roundJ,
        valueRange = 0f..1f,
        display = "${(roundJ * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { roundJ = it },
    )
    SliderRow(
        label = "浓淡抖动",
        value = opJ,
        valueRange = 0f..1f,
        display = "${(opJ * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { opJ = it },
    )
    SliderRow(
        label = "流量抖动",
        value = flowJ,
        valueRange = 0f..1f,
        display = "${(flowJ * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { flowJ = it },
    )
    SliderRow(
        label = "平滑",
        value = smooth,
        valueRange = 0f..1f,
        display = "${(smooth * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { smooth = it },
    )
    SliderRow(
        label = "速度细分",
        value = velocity,
        valueRange = 0f..1f,
        display = "${(velocity * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { velocity = it },
    )
    SliderRow(
        label = "颜色抖动",
        value = cj,
        valueRange = 0f..1f,
        display = "${(cj * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { cj = it },
    )
    DynChips(
        label = "第二颜色",
        labels = Color2Names,
        index = Color2Values.indexOf(color2).coerceAtLeast(0),
        enabled = hasDoc,
    ) { i -> color2 = Color2Values[i] }
    DynChips(
        label = "双重画笔",
        labels = listOf("关", "开"),
        index = if (dual) 1 else 0,
        enabled = hasDoc,
    ) { dual = it == 1 }
    if (dual) {
        SliderRow(
            label = "第二笔尖",
            value = dualSize,
            valueRange = 0.1f..0.95f,
            display = "${(dualSize * 100).roundToInt()}%",
            enabled = hasDoc,
            onChange = { dualSize = it },
        )
        SliderRow(
            label = "笔尖数",
            value = dualCount,
            valueRange = 1f..8f,
            display = "${dualCount.roundToInt()}",
            enabled = hasDoc,
            onChange = { dualCount = it },
        )
    }
    DynChips(
        label = "湿边",
        labels = listOf("关", "开"),
        index = if (wet) 1 else 0,
        enabled = hasDoc,
    ) { wet = it == 1 }
    DynChips(
        label = "铅笔自动抹除",
        labels = listOf("关", "开"),
        index = if (autoErase) 1 else 0,
        enabled = hasDoc,
    ) { autoErase = it == 1 }
    DynChips(
        label = "纹理",
        labels = listOf("无") + Patterns.all().map { it.first },
        index = tex + 1,
        enabled = hasDoc,
    ) { tex = it - 1 }
    if (tex >= 0) {
        DynChips(
            label = "纹理模式",
            labels = listOf("正片叠底", "叠加"),
            index = texMode,
            enabled = hasDoc,
        ) { texMode = it }
        SliderRow(
            label = "纹理深度",
            value = texDepth,
            valueRange = 0f..1f,
            display = "${(texDepth * 100).roundToInt()}%",
            enabled = hasDoc,
            onChange = { texDepth = it },
        )
    }
    SliderRow(
        label = "杂色",
        value = noise,
        valueRange = 0f..1f,
        display = "${(noise * 100).roundToInt()}%",
        enabled = hasDoc,
        onChange = { noise = it },
    )
}

/** 桌面端画笔的「模式」下拉（和图层混合模式同一套编号） */
private val BlendNames = listOf(
    "正常", "正片叠底", "滤色", "叠加", "柔光", "强光",
    "变暗", "变亮", "颜色加深", "颜色减淡", "差值", "排除",
    "色相", "饱和度", "颜色", "明度",
)
private val BlendModes = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

/** 颜色动态用的第二颜色（桌面端是背景色，这里给一排常用色） */
private val Color2Names = listOf("白", "黑", "红", "橙", "黄", "绿", "蓝", "紫")
private val Color2Values = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFFF3B30.toInt(),
    0xFFFF9F0A.toInt(),
    0xFFFFD60A.toInt(),
    0xFF34C759.toInt(),
    0xFF0A84FF.toInt(),
    0xFFBF5AF2.toInt(),
)

/** 一排小方块做单选（湿边开关、纹理选择、纹理模式） */
@Composable
internal fun DynChips(
    label: String,
    labels: List<String>,
    index: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        labels.forEachIndexed { i, name ->
            val picked = i == index
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable(enabled = enabled) { onPick(i) },
            ) {
                Text(
                    text = name,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}