package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 第 13 类「面板与界面」：导航器 / 信息 / 动作。
 * 单独放一个文件，别把主界面那个 @Composable 再撑大。
 */

private val PanelBg = Color(0xFFF4F5F7)
private val PanelTitle = Color(0xFF22262B)
private val PanelHint = Color(0xFF6B7178)
private val PanelLabel = Color(0xFF6B7178)

private fun docSizeOf(sketch: SketchView?): Pair<Int, Int> {
    val b = sketch?.layers?.firstOrNull()?.bitmap ?: return 0 to 0
    return b.width to b.height
}

/** 导航器：缩略图 + 当前视口红框 + 缩放滑杆 */
@Composable
internal fun NavigatorPanel(sketch: SketchView?, onClose: () -> Unit) {
    val (dw, dh) = docSizeOf(sketch)
    var thumb by remember(sketch, dw, dh) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sketch, dw, dh) {
        if (dw > 0 && dh > 0 && thumb == null) {
            val full = sketch?.flatten()
            if (full != null) {
                val w = 320
                val h = (dh.toFloat() / dw * w).roundToInt().coerceAtLeast(1)
                thumb = Bitmap.createScaledBitmap(full, w, h, true)
            }
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sketch) {
        while (true) {
            delay(250)
            tick++
        }
    }
    var zoom by remember(sketch) { mutableIntStateOf(((sketch?.zoomFactor() ?: 1f) * 100).roundToInt()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("导航器", color = PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("缩略图里的红框就是手机屏幕现在看到的那块。", color = PanelHint, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        val bmp = thumb
        if (bmp != null && dw > 0) {
            val aspect = bmp.width.toFloat() / bmp.height
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((220 / aspect).dp.coerceIn(80.dp, 260.dp)),
            ) {
                val img = bmp.asImageBitmap()
                drawImage(
                    image = img,
                    dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                )
                val r = sketch?.viewportRect()
                if (r != null) {
                    val sx = size.width / dw.toFloat()
                    val sy = size.height / dh.toFloat()
                    drawRect(
                        color = Color(0xFFFF3B30),
                        topLeft = Offset(r.left * sx, r.top * sy),
                        size = Size((r.width() * sx).coerceAtLeast(2f), (r.height() * sy).coerceAtLeast(2f)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }
        } else {
            Text("还没有打开图片", color = PanelHint, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("缩放：$zoom%（12%–800%）", color = PanelLabel, fontSize = 11.sp)
        ThinSlider(
            value = zoom.toFloat(),
            range = 12f..800f,
            onChange = { v ->
                zoom = v.roundToInt().coerceIn(12, 800)
                sketch?.zoomToPercent(zoom)
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                zoom = 100
                sketch?.zoomToPercent(100)
            }) { Text("100%", fontSize = 12.sp) }
            TextButton(onClick = {
                zoom = 200
                sketch?.zoomToPercent(200)
            }) { Text("200%", fontSize = 12.sp) }
            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        }
        // tick 只是为了让红框跟着手走
        if (tick < 0) Text("", fontSize = 1.sp)
    }
}

/** 信息面板：文档尺寸 / 图层数 / 缩放 / 状态 */
@Composable
internal fun InfoPanel(sketch: SketchView?, onClose: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sketch) {
        while (true) {
            delay(300)
            tick++
        }
    }
    val (dw, dh) = docSizeOf(sketch)
    val zoom = ((sketch?.zoomFactor() ?: 1f) * 100).roundToInt()
    val layers = sketch?.layers?.size ?: 0
    val masks = sketch?.layers?.count { it.mask != null } ?: 0
    val sel = if (sketch?.selection != null) "有选区" else "无选区"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("信息", color = PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("文档：$dw × $dh 像素", color = PanelLabel, fontSize = 12.sp)
        val pxWan = (sketch?.pixelCount() ?: 0L) / 10000L
        val modeText = if (sketch?.isHugeDoc() == true) "大图模式（8 位精度 / 撤销 20 步）" else "常规模式"
        Text("像素：" + pxWan + " 万 · " + modeText, color = PanelLabel, fontSize = 12.sp)
        Text("缩放：$zoom%", color = PanelLabel, fontSize = 12.sp)
        Text("图层：$layers 层（带蒙版 $masks 层）", color = PanelLabel, fontSize = 12.sp)
        Text("选区：$sel", color = PanelLabel, fontSize = 12.sp)
        Text("历史：${sketch?.historyLabels()?.size ?: 0} 步", color = PanelLabel, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        if (tick < 0) Text("", fontSize = 1.sp)
    }
}

/** 动作面板：把历史记录当动作播放 / 单步 */
@Composable
internal fun ActionsPanel(sketch: SketchView?, onClose: () -> Unit) {
    val labels = sketch?.historyLabels() ?: emptyList()
    var pos by remember { mutableIntStateOf(labels.size.coerceAtLeast(1) - 1) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(200) }
    LaunchedEffect(playing, speed, labels.size) {
        if (!playing || labels.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(speed.toLong())
            pos = if (pos + 1 >= labels.size) 0 else pos + 1
            sketch?.jumpToHistory(pos)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("动作", color = PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("把这次做过的所有步骤当成一段动作回放，可以当成教程演示。", color = PanelHint, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Text("第 ${pos + 1} / ${labels.size} 步：${labels.getOrNull(pos) ?: "—"}", color = PanelLabel, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                pos = 0
                sketch?.jumpToHistory(0)
            }) { Text("回到开头", fontSize = 12.sp) }
            TextButton(onClick = {
                val next = if (pos + 1 >= labels.size) labels.size - 1 else pos + 1
                pos = next.coerceAtLeast(0)
                sketch?.jumpToHistory(pos)
            }) { Text("下一步", fontSize = 12.sp) }
            TextButton(onClick = { playing = !playing }) { Text(if (playing) "暂停" else "播放", fontSize = 12.sp) }
        }
        Text("播放速度：${speed}ms / 步", color = PanelLabel, fontSize = 11.sp)
        ThinSlider(value = speed.toFloat(), range = 60f..900f, onChange = { v -> speed = v.roundToInt() })
        TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
    }
}

private class HistoData(
    val r: IntArray,
    val g: IntArray,
    val b: IntArray,
    val l: IntArray,
    val mean: Int,
    val median: Int,
    val total: Int,
)

private fun buildHisto(src: Bitmap?): HistoData? {
    val bmp = src ?: return null
    val scale = (420f / bmp.width.coerceAtLeast(1)).coerceAtMost(1f)
    val w = (bmp.width * scale).toInt().coerceIn(1, 512)
    val h = (bmp.height * scale).toInt().coerceIn(1, 512)
    val small = if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== bmp) small.recycle()
    val r = IntArray(256)
    val g = IntArray(256)
    val b = IntArray(256)
    val l = IntArray(256)
    var sum = 0L
    for (p in px) {
        val rv = (p ushr 16) and 0xFF
        val gv = (p ushr 8) and 0xFF
        val bv = p and 0xFF
        r[rv]++; g[gv]++; b[bv]++
        val lv = (0.299f * rv + 0.587f * gv + 0.114f * bv).toInt().coerceIn(0, 255)
        l[lv]++
        sum += lv
    }
    val total = px.size
    val mean = (sum / total.coerceAtLeast(1)).toInt()
    var acc = 0
    var median = 0
    for (i in 0..255) {
        acc += l[i]
        if (acc * 2 >= total) { median = i; break }
    }
    return HistoData(r, g, b, l, mean, median, total)
}

/** 直方图面板：RGB 三通道叠加 + 明度轮廓 + 均值/中间值/像素数 */
@Composable
internal fun HistogramPanel(sketch: SketchView?, onClose: () -> Unit) {
    var data by remember { mutableStateOf<HistoData?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sketch, tick) {
        data = null
        data = runCatching { buildHisto(sketch?.flatten()) }.getOrNull()
    }
    val d = data
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("直方图", color = PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (d == null) {
            Text("算直方图中…", color = PanelHint, fontSize = 12.sp)
        } else {
            val maxBin = max(
                max(d.r.maxOrNull() ?: 1, d.g.maxOrNull() ?: 1),
                max(d.b.maxOrNull() ?: 1, d.l.maxOrNull() ?: 1),
            ).coerceAtLeast(1)
            Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                val bw = size.width / 256f
                fun drawChannel(arr: IntArray, color: Color) {
                    for (i in 0..255) {
                        val v = arr[i].toFloat() / maxBin
                        if (v <= 0f) continue
                        val top = size.height * (1f - v)
                        drawRect(
                            color = color,
                            topLeft = Offset(i * bw, top),
                            size = Size(bw.coerceAtLeast(1f), size.height - top),
                        )
                    }
                }
                drawChannel(d.r, Color(0xFFFF3B30).copy(alpha = 0.45f))
                drawChannel(d.g, Color(0xFF34C759).copy(alpha = 0.45f))
                drawChannel(d.b, Color(0xFF3A7BFF).copy(alpha = 0.45f))
                for (i in 0..255) {
                    val v = d.l[i].toFloat() / maxBin
                    val x = i * bw
                    val y = size.height * (1f - v)
                    drawRect(Color(0xFF22262B).copy(alpha = 0.55f), Offset(x, y), Size(bw.coerceAtLeast(1f), 2f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("均值 " + d.mean + " · 中间值 " + d.median + " · 像素 " + d.total, color = PanelLabel, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { tick++ }) { Text("刷新", fontSize = 12.sp) }
            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        }
    }
}
