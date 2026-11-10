package com.reveal.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 色阶：两页。
 * 第 1 页 = 直方图 + 黑场/白场（直接在直方图上拖两根竖线），第 2 页 = 伽马与输出色阶。
 */
@Composable
internal fun LevelsDock2(sketch: SketchView?, modifier: Modifier = Modifier) {
    var page by remember { mutableIntStateOf(0) }
    var inBlack by remember { mutableStateOf(0f) }
    var inWhite by remember { mutableStateOf(255f) }
    var gamma by remember { mutableStateOf(1f) }
    var outBlack by remember { mutableStateOf(0f) }
    var outWhite by remember { mutableStateOf(255f) }
    var channel by remember { mutableIntStateOf(0) }

    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) {
        previewBase(sketch?.flatten())
    }
    val hist = remember(base) { luminanceHistogram(base) }

    fun params(): List<com.reveal.editor.image.Levels.Params> {
        val p = com.reveal.editor.image.Levels.Params(
            inBlack = inBlack.toInt().coerceIn(0, 254),
            inWhite = inWhite.toInt().coerceIn(1, 255),
            gamma = if (gamma <= 0.05f) 0.05f else gamma,
            outBlack = outBlack.toInt().coerceIn(0, 255),
            outWhite = outWhite.toInt().coerceIn(0, 255),
        )
        return List(4) { p }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 340.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(page) {
                    // 左右滑动翻页（直方图/曲线自己会吃掉拖动，所以不会误翻）
                    detectHorizontalDragGestures { _, drag ->
                        if (kotlin.math.abs(drag) > 40f) page = if (drag > 0) 0 else 1
                    }
                },
        ) {
            // 分页用分段控件（UX Patterns Guide / M3：并列内容用 tabs，而不是底部细长条）
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip("直方图", page == 0) { page = 0 }
                Spacer(Modifier.width(6.dp))
                TabChip("伽马 / 输出", page == 1) { page = 1 }
                Spacer(Modifier.weight(1f))
                if (page == 0) {
                    Text("黑 ${inBlack.toInt()} · 白 ${inWhite.toInt()}", fontSize = 10.5.sp, color = Rv.text)
                }
            }
            Spacer(Modifier.height(6.dp))

            if (page == 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Rv.surfaceAlt),
                ) {
                    LevelsHistogram(
                        counts = hist,
                        inBlack = inBlack,
                        inWhite = inWhite,
                        onChange = { b, wh ->
                            inBlack = b
                            inWhite = wh
                        },
                    )
                }
            } else {
                Column(Modifier.weight(1f)) {
                    LevelRow("伽马", gamma, 0.05f..3f, "%.2f") { gamma = it }
                    Spacer(Modifier.height(8.dp))
                    LevelRow("输出黑场", outBlack, 0f..254f) { outBlack = it }
                    Spacer(Modifier.height(8.dp))
                    LevelRow("输出白场", outWhite, 1f..255f) { outWhite = it }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "伽马 < 1 提亮中间调，> 1 压暗中间调",
                        fontSize = 10.sp,
                        color = Rv.textFaint,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("取消") {
                    sketch?.clearPreview()
                    inBlack = 0f
                    inWhite = 255f
                    gamma = 1f
                    outBlack = 0f
                    outWhite = 255f
                }
                DockButton("应用") {
                    sketch?.clearPreview()
                    val p = params()
                    sketch?.applyFilterToActive({ bmp -> com.reveal.editor.image.Levels.apply(bmp, p[0]) ?: bmp }, "色阶")
                }
            }
        }
    }

    LaunchedEffect(inBlack, inWhite, gamma, outBlack, outWhite, base) {
        val bmp = base ?: return@LaunchedEffect
        val out = runCatching { com.reveal.editor.image.Levels.apply(bmp, params()[0]) }.getOrNull() ?: bmp
        sketch?.setPreview(out)
        sketch?.invalidate()
    }
    DisposableEffect(Unit) {
        onDispose { sketch?.clearPreview() }
    }
}

/** 直方图 + 两根可拖的竖直黑/白场线 */
@Composable
private fun LevelsHistogram(
    counts: IntArray,
    inBlack: Float,
    inWhite: Float,
    onChange: (Float, Float) -> Unit,
) {
    var dragWhich by remember { mutableStateOf(-1) }
    val liveB = androidx.compose.runtime.rememberUpdatedState(inBlack)
    val liveW = androidx.compose.runtime.rememberUpdatedState(inWhite)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 用官方低层 API：awaitEachGesture + drag()。
                // 每轮手势独立、全程跟手（pointerInput 的 key 固定为 Unit，重组不会打断拖动）
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val v = (down.position.x / w) * 255f
                    dragWhich = if (kotlin.math.abs(v - liveB.value) <= kotlin.math.abs(v - liveW.value)) 0 else 1
                    down.consume()
                    drag(down.id) { change ->
                        change.consume()
                        val dx = change.position.x - change.previousPosition.x
                        val dv = dx / w * 255f
                        if (dragWhich == 0) {
                            onChange((liveB.value + dv).coerceIn(0f, liveW.value - 1f), liveW.value)
                        } else {
                            onChange(liveB.value, (liveW.value + dv).coerceIn(liveB.value + 1f, 255f))
                        }
                    }
                    dragWhich = -1
                }
            },
    ) {
        val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val barW = size.width / 256f
        for (i in 0 until 256) {
            val v = counts[i].toFloat() / peak
            val bh = size.height * v.coerceIn(0f, 1f)
            val inRange = i >= inBlack.toInt() && i <= inWhite.toInt()
            drawRect(
                color = if (inRange) Rv.textSub.copy(alpha = 0.75f) else Rv.textFaint.copy(alpha = 0.35f),
                topLeft = Offset(i * barW, size.height - bh),
                size = androidx.compose.ui.geometry.Size(barW.coerceAtLeast(1f), bh),
            )
        }
        // 黑场 / 白场竖线
        val xb = size.width * inBlack / 255f
        val xw = size.width * inWhite / 255f
        drawLine(Rv.text, Offset(xb, 0f), Offset(xb, size.height), 2f)
        drawLine(Rv.text, Offset(xw, 0f), Offset(xw, size.height), 2f)
        // 顶部小三角，方便抓
        drawCircle(Rv.accent, 7f, Offset(xb, 8f))
        drawCircle(Rv.accent, 7f, Offset(xw, 8f))
    }
}

/** 一行参数：标签 + 细线滑块 + 数值 */
@Composable
internal fun LevelRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fmt: String = "%.0f",
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(label, fontSize = 10.5.sp, color = Rv.textSub, modifier = Modifier.width(56.dp))
        Box(Modifier.weight(1f)) {
            ThinSlider(
                value = value,
                range = range,
                onChange = onChange,
            )
        }
        Text(
            String.format(fmt, value),
            fontSize = 10.5.sp,
            color = Rv.text,
            modifier = Modifier.width(48.dp),
        )
    }
}
