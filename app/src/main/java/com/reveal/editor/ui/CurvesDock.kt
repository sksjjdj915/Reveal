package com.reveal.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 曲线：可拖控制点 + 四通道 + 两页（参数 / 直方图），实时预览。
 * 控制点用扁平 Float 列表存（x0,y0,x1,y1…，归一化 0~1）。
 */
@Composable
internal fun CurvesDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var channel by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var ptsRgb by remember { mutableStateOf(listOf(0f, 0f, 1f, 1f)) }
    var ptsR by remember { mutableStateOf(listOf(0f, 0f, 1f, 1f)) }
    var ptsG by remember { mutableStateOf(listOf(0f, 0f, 1f, 1f)) }
    var ptsB by remember { mutableStateOf(listOf(0f, 0f, 1f, 1f)) }

    val cur = when (channel) {
        1 -> ptsR
        2 -> ptsG
        3 -> ptsB
        else -> ptsRgb
    }
    fun setCur(newPts: List<Float>) {
        when (channel) {
            1 -> ptsR = newPts
            2 -> ptsG = newPts
            3 -> ptsB = newPts
            else -> ptsRgb = newPts
        }
    }

    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) {
        previewBase(sketch?.flatten())
    }
    // 直方图：先缩到小图再统计，尺寸稳定、不会越界
    val hist = remember(base) { luminanceHistogram(base) }

    fun params(): com.reveal.editor.image.Curves.Params =
        com.reveal.editor.image.Curves.Params(
            points = ptsRgb,
            redPoints = ptsR,
            greenPoints = ptsG,
            bluePoints = ptsB,
        )

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
                    detectHorizontalDragGestures { _, drag ->
                        if (kotlin.math.abs(drag) > 40f) page = if (drag > 0) 0 else 1
                    }
                },
        ) {
            // 分页：顶部两段（曲线｜预设），点胶囊或左右滑动都能翻
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip("曲线", page == 0) { page = 0 }
                Spacer(Modifier.width(6.dp))
                TabChip("预设", page == 1) { page = 1 }
            }
            Spacer(Modifier.height(6.dp))
            if (page == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabChip("RGB", channel == 0) { channel = 0 }
                    TabChip("红", channel == 1) { channel = 1 }
                    TabChip("绿", channel == 2) { channel = 2 }
                    TabChip("蓝", channel == 3) { channel = 3 }
                    Spacer(Modifier.weight(1f))
                    var picking by remember { mutableStateOf(false) }
                    TabChip(if (picking) "吸管…" else "吸管", picking) {
                        picking = !picking
                        if (picking) {
                            FilterPick.start { fx, fy ->
                                // 取画面上这一点的颜色（当前通道），在曲线上放一个「输入=输出」的点
                                val b = base
                                if (b != null) {
                                    val x = (fx * (b.width - 1)).toInt().coerceIn(0, b.width - 1)
                                    val y = (fy * (b.height - 1)).toInt().coerceIn(0, b.height - 1)
                                    val c = b.getPixel(x, y)
                                    val v = when (channel) {
                                        1 -> android.graphics.Color.red(c)
                                        2 -> android.graphics.Color.green(c)
                                        3 -> android.graphics.Color.blue(c)
                                        else -> {
                                            val r = android.graphics.Color.red(c)
                                            val g = android.graphics.Color.green(c)
                                            val bl = android.graphics.Color.blue(c)
                                            (0.299f * r + 0.587f * g + 0.114f * bl).roundToInt()
                                        }
                                    }
                                    val t = (v / 255f).coerceIn(0f, 1f)
                                    val list = cur.toMutableList()
                                    var inserted = false
                                    var i = 0
                                    while (i + 1 < list.size) {
                                        if (kotlin.math.abs(list[i] - t) < 0.02f) { inserted = true; break }
                                        i += 2
                                    }
                                    if (!inserted) {
                                        list.add(t); list.add(t)
                                        // 按 x 排序，保证曲线单调可用
                                        val pairs = list.chunked(2).sortedBy { it[0] }
                                        setCur(pairs.flatten())
                                    }
                                }
                                picking = false
                                FilterPick.stop()
                            }
                        } else {
                            FilterPick.stop()
                        }
                    }
                    androidx.compose.runtime.DisposableEffect(picking) {
                        onDispose { if (picking) FilterPick.stop() }
                    }
                    Spacer(Modifier.width(6.dp))
                    TabChip("自动", false) {
                        val h = com.reveal.editor.ui.luminanceHistogram(base)
                        val total = h.sum().coerceAtLeast(1)
                        var acc = 0
                        var lo = 0
                        var hi = 255
                        for (i in 0..255) { acc += h[i]; if (acc >= total * 0.005) { lo = i; break } }
                        acc = 0
                        for (i in 255 downTo 0) { acc += h[i]; if (acc >= total * 0.005) { hi = i; break } }
                        val nl = (lo / 255f).coerceIn(0f, 0.45f)
                        val nh = (hi / 255f).coerceIn(0.55f, 1f)
                        val pts = listOf(nl, 0f, (nl + nh) / 2f, 0.52f, nh, 1f)
                        when (channel) {
                            0 -> ptsRgb = pts
                            1 -> ptsR = pts
                            2 -> ptsG = pts
                            else -> ptsB = pts
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(page) {
                        detectHorizontalDragGestures { _, drag ->
                            if (kotlin.math.abs(drag) > 12f) {
                                page = if (drag > 0) 0 else 1
                            }
                        }
                    },
            ) {
                if (page == 0) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Rv.surfaceAlt),
                    ) {
                        CurveCanvas(
                            points = cur,
                            onPoints = { setCur(it) },
                        )
                    }
                } else {
                    // 第 2 页：预设曲线 + 控制点数，不放图
                    Column(Modifier.fillMaxSize()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TabChip("线性", false) { setCur(listOf(0f, 0f, 1f, 1f)) }
                            TabChip("S 形", false) {
                                setCur(listOf(0f, 0f, 0.25f, 0.16f, 0.75f, 0.84f, 1f, 1f))
                            }
                            TabChip("提亮", false) { setCur(listOf(0f, 0f, 0.5f, 0.58f, 1f, 1f)) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TabChip("压暗", false) { setCur(listOf(0f, 0f, 0.5f, 0.42f, 1f, 1f)) }
                            TabChip("反相", false) { setCur(listOf(0f, 1f, 1f, 0f)) }
                            TabChip("加对比", false) {
                                setCur(listOf(0f, 0f, 0.2f, 0.1f, 0.8f, 0.9f, 1f, 1f))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("当前通道有 ${cur.size / 2} 个控制点", fontSize = 10.5.sp, color = Rv.textSub)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "第 1 页：拖点移动、点空白加点、点已有的点删除；这一页放常用预设",
                            fontSize = 10.sp,
                            color = Rv.textFaint,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("取消") {
                    sketch?.clearPreview()
                    setCur(listOf(0f, 0f, 1f, 1f))
                }
                DockButton("应用") {
                    sketch?.clearPreview()
                    val p = params()
                    sketch?.applyFilterToActive({ bmp -> com.reveal.editor.image.Curves.apply(bmp, p) ?: bmp }, "曲线")
                }
                DockButton("重置全部") {
                    ptsRgb = listOf(0f, 0f, 1f, 1f)
                    ptsR = listOf(0f, 0f, 1f, 1f)
                    ptsG = listOf(0f, 0f, 1f, 1f)
                    ptsB = listOf(0f, 0f, 1f, 1f)
                    sketch?.clearPreview()
                }
            }
        }
    }

    LaunchedEffect(ptsRgb, ptsR, ptsG, ptsB, base) {
        val bmp = base ?: return@LaunchedEffect
        val out = runCatching { com.reveal.editor.image.Curves.apply(bmp, params()) }.getOrNull() ?: bmp
        sketch?.setPreview(out)
        sketch?.invalidate()
    }
    DisposableEffect(Unit) {
        onDispose { sketch?.clearPreview() }
    }
}

/**
 * 曲线画布：一个手势循环搞定三件事——
 * 拖已有点 = 移动；点空白 = 加一个点；点已有的点（不拖）= 删掉它（端点不能删）。
 */
@Composable
private fun CurveCanvas(points: List<Float>, onPoints: (List<Float>) -> Unit) {
    val live = androidx.compose.runtime.rememberUpdatedState(points)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val curList = live.value
                    val nx = down.position.x / w
                    val ny = 1f - down.position.y / h

                    // 命中最近的控制点（阈值按屏幕比例）
                    var hit = -1
                    var bestD = 0.16f
                    var i = 0
                    while (i < curList.size / 2) {
                        val d = kotlin.math.abs(curList[i * 2] - nx) + kotlin.math.abs(curList[i * 2 + 1] - ny)
                        if (d < bestD) {
                            bestD = d
                            hit = i
                        }
                        i++
                    }

                    var moved = false
                    val editing = curList.toMutableList()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            ch.consume()
                            break
                        }
                        val dx = ch.position.x - ch.previousPosition.x
                        val dy = ch.position.y - ch.previousPosition.y
                        if (!moved && kotlin.math.abs(ch.position.x - down.position.x) +
                            kotlin.math.abs(ch.position.y - down.position.y) > 5f
                        ) moved = true
                        if (moved && hit >= 0) {
                            val n = editing.size / 2
                            when {
                                hit == 0 || hit == n - 1 ->
                                    editing[hit * 2 + 1] = (editing[hit * 2 + 1] - dy / h).coerceIn(0f, 1f)
                                else -> {
                                    val lo = editing[(hit - 1) * 2] + 0.02f
                                    val hi = editing[(hit + 1) * 2] - 0.02f
                                    editing[hit * 2] = (editing[hit * 2] + dx / w).coerceIn(lo, hi)
                                    editing[hit * 2 + 1] = (editing[hit * 2 + 1] - dy / h).coerceIn(0f, 1f)
                                }
                            }
                            onPoints(editing.toList())
                            ch.consume()
                        }
                    }

                    if (!moved) {
                        val n = live.value.size / 2
                        if (hit in 0 until n) {
                            // 点已有的点 = 删除（端点保留）
                            if (hit != 0 && hit != n - 1) {
                                val del = live.value.toMutableList()
                                del.removeAt(hit * 2 + 1)
                                del.removeAt(hit * 2)
                                onPoints(del)
                            }
                        } else {
                            // 点空白 = 加一个点
                            val add = live.value.toMutableList()
                            var at = 0
                            while (at < add.size && add[at] < nx) at += 2
                            add.add(at, nx.coerceIn(0.02f, 0.98f))
                            add.add(at + 1, ny.coerceIn(0f, 1f))
                            onPoints(add)
                        }
                    }
                }
            },
    ) {
        val grid = Color(0xFFDDE1E7)
        for (i in 1..3) {
            val x = size.width * i / 4f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        drawLine(Color(0x33000000), Offset(0f, size.height), Offset(size.width, 0f), 1f)

        val lut = com.reveal.editor.image.Curves.lutOf(points)
        val path = Path()
        for (i in 0 until 256) {
            val x = size.width * i / 255f
            val y = size.height * (1f - lut[i] / 255f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Rv.accent, style = Stroke(width = 2f))

        var i = 0
        while (i < points.size / 2) {
            val c = Offset(size.width * points[i * 2], size.height * (1f - points[i * 2 + 1]))
            drawCircle(Color.White, 7f, c)
            drawCircle(Rv.accent, 7f, c, style = Stroke(width = 2f))
            i++
        }
    }
}

/** 直方图：256 档亮度，区间外浅灰 */
@Composable
private fun Histogram(counts: IntArray) {
    Canvas(Modifier.fillMaxSize()) {
        val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val barW = size.width / 256f
        for (i in 0 until 256) {
            val v = counts[i].toFloat() / peak
            val bh = size.height * (v.coerceIn(0f, 1f))
            drawRect(
                color = Rv.textFaint.copy(alpha = 0.55f),
                topLeft = Offset(i * barW, size.height - bh),
                size = androidx.compose.ui.geometry.Size(barW.coerceAtLeast(1f), bh),
            )
        }
    }
}

/** 亮度直方图（缩到 192×192 再统计，稳定且不占内存） */
internal fun luminanceHistogram(src: android.graphics.Bitmap?): IntArray {
    val out = IntArray(256)
    if (src == null || src.width <= 0 || src.height <= 0) return out
    val small = runCatching {
        android.graphics.Bitmap.createScaledBitmap(src, 192, 192, true)
    }.getOrNull() ?: return out
    val px = IntArray(192 * 192)
    runCatching { small.getPixels(px, 0, 192, 0, 0, 192, 192) }
    for (c in px) {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        out[(r * 77 + g * 151 + b * 28) shr 8]++
    }
    if (small !== src) small.recycle()
    return out
}

/** 预览用的底图：超过 maxSide 就先缩小，保证拖动跟手（setPreview 会把它拉伸回图层大小） */
internal fun previewBase(src: android.graphics.Bitmap?, maxSide: Int = 900): android.graphics.Bitmap? {
    if (src == null) return null
    val long = kotlin.math.max(src.width, src.height)
    val out = if (long <= maxSide) src else {
        val k = maxSide.toFloat() / long
        runCatching {
            android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * k).toInt().coerceAtLeast(1),
                (src.height * k).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull()
    }
    return out?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
}
