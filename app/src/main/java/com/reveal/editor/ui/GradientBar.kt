package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部渐变条：线性 / 径向、两端色块（点一下就能取色）、到透明 / 到颜色，
 * 外加「平滑度 / 缩放 / 反向」三个参数。颜色独立于画笔前景色。
 */
@Composable
internal fun GradientBar(
    modifier: Modifier = Modifier,
    radial: Boolean,
    onRadial: (Boolean) -> Unit,
    color1: Int,
    color2: Int,
    toTransparent: Boolean,
    onPickSlot: (Int) -> Unit,
    onToggleTransparent: () -> Unit,
    smooth: Int,
    onSmooth: (Int) -> Unit,
    scale: Int,
    onScale: (Int) -> Unit,
    reversed: Boolean,
    onReverse: () -> Unit,
    clipped: Boolean,
    onToggleClip: () -> Unit,
    selectionClipped: Boolean,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        modifier = modifier
            .padding(bottom = 14.dp, start = 8.dp, end = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFFFFF),
        contentColor = Color(0xFF22262B),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip("线性", !radial) { onRadial(false) }
                Spacer(Modifier.width(6.dp))
                TabChip("径向", radial) { onRadial(true) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(color1))
                        .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp))
                        .clickable { onPickSlot(0) }
                )
                Spacer(Modifier.width(4.dp))
                Text("→", fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (toTransparent) Color(0xFFFFFFFF) else Color(color2))
                        .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp))
                        .clickable { onPickSlot(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (toTransparent) Text("透", fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                TabChip(if (toTransparent) "到透明" else "到颜色", toTransparent) { onToggleTransparent() }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip("反向颜色", reversed) { onReverse() }
                Spacer(Modifier.width(6.dp))
                TabChip("只画范围", clipped) { onToggleClip() }
            }
            Spacer(Modifier.height(2.dp))
            GradRow("平滑度", (smooth / 100f), 0f..1f, "$smooth") { onSmooth((it * 100f).toInt()) }
            GradRow("缩放", (scale / 100f), 0.1f..3f, "$scale%") { onScale((it * 100f).toInt()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selectionClipped) {
                        "有选区：渐变只画在选区内"
                    } else {
                        "点色块取色（与画笔颜色分开）"
                    },
                    fontSize = 10.sp,
                    color = if (selectionClipped) Color(0xFFFFB86B) else Color(0xFF6B7178),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canCancel) {
                    Box(
                        Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCancel() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("取消", fontSize = 12.sp, color = Color(0xFF6B7178)) }
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2F6BFF))
                        .clickable { onApply() }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("应用", fontSize = 12.sp, color = Color.White) }
            }
        }
    }
}

@Composable
private fun GradRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = Color(0xFF6B7178), modifier = Modifier.width(44.dp))
        ThinSlider(
            value = value,
            range = range,
            modifier = Modifier.weight(1f),
            onChange = onChange,
        )
        Text(
            display,
            fontSize = 10.sp,
            color = Color(0xFF8A9099),
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** 渐变取色面板：slot = 0 左端，1 右端；-1 表示关闭 */
@Composable
internal fun GradientPickerHost(
    slot: Int,
    color1: Int,
    color2: Int,
    onColor: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (slot < 0) return
    // key(slot)：换一个色块重新开面板时，取色器要从那个颜色重新起，不能留着上一个的 HSV
    androidx.compose.runtime.key(slot) {
        ColorPickerPanel(
            initial = if (slot == 0) color1 else color2,
            onColorChange = { c -> onColor(slot, c) },
            onCancel = onDismiss,
            onConfirm = { c ->
                onColor(slot, c)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 渐变条 + 取色面板：状态自己管，颜色与画笔前景色完全分开 */
@Composable
internal fun GradientSection(
    sketch: SketchView?,
    tool: SketchView.Tool,
    specTick: Int,
    modifier: Modifier = Modifier,
) {
    var radial by remember { mutableStateOf(false) }
    var toTransparent by remember { mutableStateOf(true) }
    var c1 by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var c2 by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var pickSlot by remember { mutableIntStateOf(-1) }
    var smooth by remember { mutableIntStateOf(0) }
    var scalePct by remember { mutableIntStateOf(100) }
    var reversed by remember { mutableStateOf(false) }
    var clipRange by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    // 只要这一层还留着可回改的渐变，不管当前是什么工具，都当成「改这一层」
    // （原来按 tool 记忆，用渐变工具刚画完就再也拿不到 edit，滑块动了等于没动）
    val edit = if (sketch != null && sketch.canEditGradient()) sketch.activeGradientSpec() else null
    // 新画了一条 / 换了图层时把参数回填一次；同一个渐变的参数改动不走这里，避免和滑块打架
    // 换了图层 / 又拉了一条新的 → 重新显示参数条
    LaunchedEffect(tool, specTick, edit?.x0, edit?.y0, edit?.x1, edit?.y1, edit?.radial) {
        closed = false
    }
    LaunchedEffect(edit?.x0, edit?.y0, edit?.x1, edit?.y1, edit?.radial) {
        val g = edit ?: return@LaunchedEffect
        c1 = g.c1
        c2 = g.c2
        toTransparent = (g.c2 ushr 24) == 0
        radial = g.radial
        smooth = g.smooth
        scalePct = g.scale
        reversed = g.reverse
        clipRange = g.clip
    }
    val clipped = sketch?.hasSelection() == true
    if (!closed && (tool == SketchView.Tool.GRADIENT || edit != null)) {
        GradientBar(
            modifier = modifier,
            radial = radial,
            onRadial = { r ->
                radial = r
                sketch?.gradientRadial = r
                edit?.let { old ->
                    val g = old.copyOf()
                    g.radial = r
                    sketch?.updateGradientSpec(g)
                }
            },
            color1 = c1,
            color2 = if (toTransparent) (c1 and 0x00FFFFFF) else c2,
            toTransparent = toTransparent,
            onPickSlot = { pickSlot = it },
            onToggleTransparent = { toTransparent = !toTransparent },
            smooth = smooth,
            onSmooth = { v -> smooth = v; sketch?.gradientSmooth = v },
            scale = scalePct,
            onScale = { v -> scalePct = v; sketch?.gradientScale = v },
            reversed = reversed,
            onReverse = { reversed = !reversed; sketch?.gradientReverse = reversed },
            clipped = clipRange,
            onToggleClip = { clipRange = !clipRange; sketch?.gradientClip = clipRange },
            selectionClipped = clipped,
            canCancel = edit != null,
            onCancel = {
                sketch?.revertGradient()
                closed = true
            },
            onApply = {
                sketch?.finishGradientEdit()
                closed = true
            },
        )
        LaunchedEffect(toTransparent, c1, c2, smooth, scalePct, reversed, clipRange) {
            // 拖动滑块时每次都会取消上一个 effect，天然节流；再加 70ms 避免整层重画跟不上手指
            kotlinx.coroutines.delay(70)
            val end = if (toTransparent) (c1 and 0x00FFFFFF) else c2
            sketch?.let { v ->
                v.gradientColor1 = c1
                v.gradientColor2 = end
                v.gradientSmooth = smooth
                v.gradientScale = scalePct
                v.gradientReverse = reversed
                v.gradientClip = clipRange
            }
            edit?.let { old ->
                val g = old.copyOf()
                g.c1 = c1
                g.c2 = end
                g.smooth = smooth
                g.scale = scalePct
                g.reverse = reversed
                g.clip = clipRange
                sketch?.updateGradientSpec(g)
            }
        }
        LaunchedEffect(toTransparent, c1, c2, smooth, scalePct, reversed, clipRange) {
            kotlinx.coroutines.delay(420)
            sketch?.endGradientSpecEdit()
        }
    }
    GradientPickerHost(
        slot = pickSlot,
        color1 = c1,
        color2 = c2,
        onColor = { slot, c ->
            val opaque = if ((c ushr 24) == 0) c or 0xFF000000.toInt() else c
            if (slot == 0) {
                c1 = opaque
            } else {
                c2 = opaque
                toTransparent = false
            }
        },
        onDismiss = { pickSlot = -1 },
    )
}
