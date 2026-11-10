package com.reveal.editor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveal.editor.image.ColorFx
import com.reveal.editor.image.FilterFx
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * 第 12 类「特效」里那些不需要单个滑块、而是一次性组合好几个滤镜的东西：
 * 3D 立体化（把图层往一个方向挤出一叠厚度）、五个一键调色动作、历史动画预览。
 */
object EffectOps {

    /** 3D 立体化：沿着角度复制 N 层并逐层压暗，最后盖回原图。 */
    fun extrude(src: Bitmap, angleDeg: Int, depth: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val steps = depth.coerceIn(1, 60)
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = -sin(rad).toFloat()
        val base = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in steps downTo 1) {
            val f = i / steps.toFloat()
            val paint = Paint(base)
            val cm = ColorMatrix()
            val k = (1f - 0.55f * f).coerceAtLeast(0.25f)
            cm.setScale(k, k, k, 1f)
            paint.colorFilter = ColorMatrixColorFilter(cm)
            c.drawBitmap(src, dx * i, dy * i, paint)
        }
        c.drawBitmap(src, 0f, 0f, base)
        return out
    }

    /** 一键动作的标题（给撤销栈用） */
    fun autoTitle(id: String): String = when (id) {
        "efx:cinema" -> "一键 黑白大片"
        "efx:cyber" -> "一键 赛博朋克"
        "efx:film" -> "一键 复古胶片"
        "efx:soft" -> "一键 柔光人像"
        else -> "一键 高对比锐化"
    }

    /** 一键动作的分派，返回 null 表示这个 id 不认识 */
    fun autoApply(id: String, src: Bitmap): Bitmap? = when (id) {
        "efx:cinema" -> autoCinema(src)
        "efx:cyber" -> autoCyber(src)
        "efx:film" -> autoFilm(src)
        "efx:soft" -> autoSoft(src)
        "efx:sharp" -> autoSharp(src)
        else -> null
    }

    /** 一键：黑白大片（去色 + 压暗四周式对比 + 轻锐化） */
    fun autoCinema(src: Bitmap): Bitmap {
        var b = ColorFx.blackWhite(src, 32, 56, 12)
        b = ColorFx.shadowsHighlights(b, 18, -12)
        b = ColorFx.vibrance(b, -8)
        return FilterFx.unsharpMask(b, 90, 2, 2)
    }

    /** 一键：赛博朋克（青蓝阴影 + 洋红高光） */
    fun autoCyber(src: Bitmap): Bitmap {
        var b = ColorFx.colorBalance(src, -18, 8, 30)
        b = ColorFx.selectiveColor(b, 4, 6, 42, -20, 22)
        b = ColorFx.vibrance(b, 42)
        b = ColorFx.shadowsHighlights(b, 10, -14)
        return FilterFx.unsharpMask(b, 120, 2, 3)
    }

    /** 一键：复古胶片（暖滤镜 + 降饱和 + 颗粒） */
    fun autoFilm(src: Bitmap): Bitmap {
        var b = ColorFx.photoFilter(src, 0xFFFFB877.toInt(), 26)
        b = ColorFx.vibrance(b, -16)
        b = ColorFx.shadowsHighlights(b, 14, -8)
        return FilterFx.filmGrain(b, 26, 7)
    }

    /** 一键：柔光人像（提亮暗部、压高光、自动噪点） */
    fun autoSoft(src: Bitmap): Bitmap {
        var b = ColorFx.hdrTone(src, 14, 55, 8)
        b = ColorFx.shadowsHighlights(b, 16, -10)
        b = FilterFx.reduceNoise(b, 22)
        return ColorFx.vibrance(b, 8)
    }

    /** 一键：高对比锐化（加强对比 + 强 USM） */
    fun autoSharp(src: Bitmap): Bitmap {
        var b = ColorFx.shadowsHighlights(src, 0, -18)
        b = ColorFx.exposure(b, 0.05f)
        b = FilterFx.highPass(b, 3)
        return FilterFx.unsharpMask(b, 210, 2, 0)
    }

// ---------- 自动化合成：桌面端「文件 → 自动」里的三件事 ----------

    /** 焦点堆栈：几张不同焦点的照片叠在一起，每个像素挑最锐利的那张。 */
    fun mergeFocus(layers: List<Bitmap>): Bitmap? {
        if (layers.size < 2) return null
        val w = layers[0].width
        val h = layers[0].height
        val n = layers.size
        val buf = Array(n) { i ->
            val src = layers[i]
            val b = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            if (b !== src) b.recycle()
            px
        }
        val sharp = Array(n) { i -> sharpnessOf(buf[i], w, h) }
        val dst = IntArray(w * h)
        for (k in dst.indices) {
            var best = 0
            var bv = sharp[0][k]
            for (i in 1 until n) {
                val v = sharp[i][k]
                if (v > bv) { bv = v; best = i }
            }
            dst[k] = buf[best][k]
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    /** 局部清晰度：先求亮度边缘强度，再用半径 4 的方框求和（避免单像素噪点决定取舍）。 */
    private fun sharpnessOf(px: IntArray, w: Int, h: Int): FloatArray {
        val lum = FloatArray(w * h)
        for (k in px.indices) {
            val c = px[k]
            lum[k] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        val edge = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val k = y * w + x
                edge[k] = abs(lum[k + 1] - lum[k - 1]) + abs(lum[k + w] - lum[k - w])
            }
        }
        return boxSum(edge, w, h, 4)
    }

    /** 可分离方框求和（半径 r），用来把清晰度抹开成一块一块的权重。 */
    private fun boxSum(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val span = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) if (x in 0 until w) acc += src[y * w + x]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / span
                val add = x + r + 1
                val rem = x - r
                if (add < w) acc += src[y * w + add]
                if (rem >= 0) acc -= src[y * w + rem]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) if (y in 0 until h) acc += tmp[y * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / span
                val add = y + r + 1
                val rem = y - r
                if (add < h) acc += tmp[add * w + x]
                if (rem >= 0) acc -= tmp[rem * w + x]
            }
        }
        return out
    }

    /**
     * 全景拼接：把第二张往第一张的右边（或下边）接上去。
     * 先在重叠区里逐个试错开量，挑差别最小的那一个，再把接缝做羽化过渡。
     */
    fun photomerge(a: Bitmap, b: Bitmap, horizontal: Boolean): Bitmap {
        val aw = a.width
        val ah = a.height
        val bw = b.width
        val bh = b.height
        val pa = IntArray(aw * ah)
        a.getPixels(pa, 0, aw, 0, 0, aw, ah)
        val pb = IntArray(bw * bh)
        b.getPixels(pb, 0, bw, 0, 0, bw, bh)

        val limit = if (horizontal) min(aw, bw) / 2 else min(ah, bh) / 2
        val stepD = max(1, limit / 100)
        var bestD = 0
        var bestScore = Float.MAX_VALUE
        var d = 0
        while (d <= limit) {
            var sum = 0.0
            var cnt = 0
            if (horizontal) {
                val x1 = min(aw, d + bw)
                var x = d
                while (x < x1) {
                    var y = 0
                    while (y < min(ah, bh)) {
                        val ca = pa[y * aw + x]
                        val cb = pb[y * bw + (x - d)]
                        sum += abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)) +
                            abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
                            abs((ca and 0xFF) - (cb and 0xFF))
                        cnt++
                        y += 6
                    }
                    x += 6
                }
            } else {
                val y1 = min(ah, d + bh)
                var y = d
                while (y < y1) {
                    var x = 0
                    while (x < min(aw, bw)) {
                        val ca = pa[y * aw + x]
                        val cb = pb[(y - d) * bw + x]
                        sum += abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)) +
                            abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
                            abs((ca and 0xFF) - (cb and 0xFF))
                        cnt++
                        x += 6
                    }
                    y += 6
                }
            }
            if (cnt > 0) {
                val score = (sum / cnt).toFloat()
                if (score < bestScore) { bestScore = score; bestD = d }
            }
            d += stepD
        }

        val outW = if (horizontal) bestD + bw else max(aw, bw)
        val outH = if (horizontal) max(ah, bh) else bestD + bh
        val out = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val plain = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(a, 0f, 0f, plain)
        val overlap = if (horizontal) (aw - bestD).coerceAtLeast(1) else (ah - bestD).coerceAtLeast(1)
        val soft = Paint(Paint.FILTER_BITMAP_FLAG)
        soft.shader = if (horizontal) {
            LinearGradient(
                0f, 0f, overlap.toFloat(), 0f,
                0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, overlap.toFloat(),
                0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP,
            )
        }
        c.drawBitmap(b, bestD.toFloat(), if (horizontal) 0f else bestD.toFloat(), soft)
        return out
    }

    /** HDR 合成：几张不同曝光的照片按「亮度离中间灰有多近」加权平均，暗部取亮的、亮部取暗的。 */
    fun mergeHdr(layers: List<Bitmap>): Bitmap? {
        if (layers.size < 2) return null
        val w = layers[0].width
        val h = layers[0].height
        val n = layers.size
        val buf = Array(n) { i ->
            val src = layers[i]
            val b = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            if (b !== src) b.recycle()
            px
        }
        val dst = IntArray(w * h)
        for (k in dst.indices) {
            var r = 0f
            var g = 0f
            var bl = 0f
            var sum = 0f
            for (i in 0 until n) {
                val c = buf[i][k]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb2 = c and 0xFF
                val lum = 0.299f * cr + 0.587f * cg + 0.114f * cb2
                val t = (lum - 128f) / 72f
                val wt = exp(-(t * t))
                r += cr * wt
                g += cg * wt
                bl += cb2 * wt
                sum += wt
            }
            if (sum < 1e-4f) {
                dst[k] = buf[0][k]
            } else {
                val a2 = (buf[0][k] ushr 24) and 0xFF
                dst[k] = (a2 shl 24) or
                    ((r / sum).toInt().coerceIn(0, 255) shl 16) or
                    ((g / sum).toInt().coerceIn(0, 255) shl 8) or
                    (bl / sum).toInt().coerceIn(0, 255)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }
}

/** 3D 立体化对话框：角度 + 厚度 */
@Composable
internal fun Extrude3dDialog(sketch: SketchView?, onClose: () -> Unit) {
    var angle by remember { mutableIntStateOf(45) }
    var depth by remember { mutableIntStateOf(18) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("3D 立体化", color = Color(0xFF22262B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "把当前图层往一个方向复制出一叠厚度，像给图层加了侧壁。原图会盖在最上面。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("方向：$angle°", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        ThinSlider(value = angle.toFloat(), range = -180f..180f, onChange = { v -> angle = v.roundToInt() })
        Text("厚度：$depth 层", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        ThinSlider(value = depth.toFloat(), range = 1f..60f, onChange = { v -> depth = v.roundToInt().coerceIn(1, 60) })
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("取消", fontSize = 12.sp) }
            TextButton(
                onClick = {
                    val ok = sketch?.applyFilterToActive({ b -> EffectOps.extrude(b, angle, depth) }, "3D 立体化") == true
                    if (ok) onClose()
                },
            ) { Text("应用", fontSize = 12.sp) }
        }
    }
}

/**
 * 历史动画预览：把历史记录当动画逐帧播放。
 * 停止时回到播放前的位置，不破坏正在编辑的状态。
 */
@Composable
internal fun HistoryPlayerPanel(sketch: SketchView?, onClose: () -> Unit) {
    var playing by remember { mutableStateOf(true) }
    var speedMs by remember { mutableIntStateOf(220) }
    var pos by remember { mutableIntStateOf(0) }
    val total = sketch?.historyLabels()?.size ?: 0
    LaunchedEffect(playing, speedMs) {
        if (!playing || total == 0) return@LaunchedEffect
        while (true) {
            delay(speedMs.toLong())
            pos = (pos + 1) % total
            sketch?.jumpToHistory(pos)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("动画预览（历史回放）", color = Color(0xFF22262B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "把这条时间线上的每一步当成动画帧播放。这里只是预览，不会改图；停止后停在当前帧。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text("第 ${pos + 1} / $total 步", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        Text("速度：$speedMs ms/帧", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        ThinSlider(value = speedMs.toFloat(), range = 40f..800f, onChange = { v ->
            speedMs = v.roundToInt().coerceIn(40, 800)
        })
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { playing = !playing }) { Text(if (playing) "暂停" else "播放", fontSize = 12.sp) }
            TextButton(
                onClick = {
                    playing = false
                    sketch?.jumpToHistory(total - 1)
                    onClose()
                },
            ) { Text("跳到最新", fontSize = 12.sp) }
            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        }
    }
}
/** 特效类的浮层：3D 立体化 + 历史回放。集中在这里，EditorScreen 就不会被顶过 64KB。 */
@Composable
internal fun EffectOverlays(
    sketch: SketchView?,
    show3d: Boolean,
    onClose3d: () -> Unit,
    showAnim: Boolean,
    onCloseAnim: () -> Unit,
) {
    if (show3d) Extrude3dDialog(sketch = sketch, onClose = onClose3d)
    if (showAnim) HistoryPlayerPanel(sketch = sketch, onClose = onCloseAnim)
}

/**
 * 第 12 类「特效」的整块面板：3D 立体化 + 五个一键动作 + 历史动画预览。
 * 全放在一个 composable 里，EditorScreen 只留一行调用。
 */
@Composable
internal fun EffectPanel(sketch: SketchView?, onClose: () -> Unit) {
    var autoMsg by remember { mutableStateOf("") }
    var angle by remember { mutableIntStateOf(45) }
    var depth by remember { mutableIntStateOf(18) }
    var playing by remember { mutableStateOf(false) }
    var speedMs by remember { mutableIntStateOf(220) }
    var pos by remember { mutableIntStateOf(0) }
    val total = sketch?.historyLabels()?.size ?: 0
    LaunchedEffect(playing, speedMs, total) {
        if (!playing || total == 0) return@LaunchedEffect
        while (true) {
            delay(speedMs.toLong())
            pos = (pos + 1) % total
            sketch?.jumpToHistory(pos)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("特效", color = Color(0xFF22262B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("3D 立体化、一键调色动作、历史动画预览。", color = Color(0xFF6B7178), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text("3D 立体化：沿方向复制出厚度", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        Text("方向：$angle°", color = Color(0xFF6B7178), fontSize = 11.sp)
        ThinSlider(value = angle.toFloat(), range = -180f..180f, onChange = { v -> angle = v.roundToInt() })
        Text("厚度：$depth 层", color = Color(0xFF6B7178), fontSize = 11.sp)
        ThinSlider(value = depth.toFloat(), range = 1f..60f, onChange = { v -> depth = v.roundToInt().coerceIn(1, 60) })
        TextButton(
            onClick = { sketch?.applyFilterToActive({ b -> EffectOps.extrude(b, angle, depth) }, "3D 立体化") },
        ) { Text("应用 3D 立体化", fontSize = 12.sp) }
        Spacer(Modifier.height(4.dp))
        Text("一键动作（组合现成滤镜，可撤销）", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        val autos = listOf(
            "efx:cinema" to "黑白大片",
            "efx:cyber" to "赛博朋克",
            "efx:film" to "复古胶片",
            "efx:soft" to "柔光人像",
            "efx:sharp" to "高对比锐化",
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            autos.take(3).forEach { (id, label) ->
                TextButton(onClick = {
                    sketch?.applyFilterToActive({ b -> EffectOps.autoApply(id, b) }, EffectOps.autoTitle(id))
                }) { Text(label, fontSize = 11.sp) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            autos.drop(3).forEach { (id, label) ->
                TextButton(onClick = {
                    sketch?.applyFilterToActive({ b -> EffectOps.autoApply(id, b) }, EffectOps.autoTitle(id))
                }) { Text(label, fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("自动合成（把可见图层合成一张新图层）", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        Text("焦点堆栈＝挑每处最清晰的；全景拼接＝左右接起来；HDR 合成＝暗部取亮的、亮部取暗的。", color = Color(0xFF6B7178), fontSize = 11.sp)
        if (autoMsg.isNotEmpty()) Text(autoMsg, color = Color(0xFF7CD992), fontSize = 11.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { autoMsg = runAutoSynth(sketch, AUTO_FOCUS) }) { Text("焦点堆栈", fontSize = 11.sp) }
            TextButton(onClick = { autoMsg = runAutoSynth(sketch, AUTO_PANO) }) { Text("全景拼接", fontSize = 11.sp) }
            TextButton(onClick = { autoMsg = runAutoSynth(sketch, AUTO_HDR) }) { Text("HDR 合成", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Text("动画预览（历史回放）", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        Text("第 ${pos + 1} / $total 步 · $speedMs ms/帧", color = Color(0xFF6B7178), fontSize = 11.sp)
        ThinSlider(value = speedMs.toFloat(), range = 40f..800f, onChange = { v ->
            speedMs = v.roundToInt().coerceIn(40, 800)
        })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { playing = !playing }) { Text(if (playing) "暂停" else "播放", fontSize = 12.sp) }
            TextButton(onClick = { playing = false; sketch?.jumpToHistory((total - 1).coerceAtLeast(0)) }) {
                Text("跳到最新", fontSize = 12.sp)
            }
            TextButton(onClick = { playing = false; onClose() }) { Text("关闭", fontSize = 12.sp) }
        }
    }
}


private const val AUTO_FOCUS = 0
private const val AUTO_PANO = 1
private const val AUTO_HDR = 2

/** 自动合成：拿当前文档所有可见图层算出一张新图，加成一个新图层。返回给面板显示的一句话。 */
private fun runAutoSynth(sketch: SketchView?, kind: Int): String {
    val s = sketch ?: return "没有打开的文档"
    val bitmaps = s.layers.filter { it.visible }.mapNotNull { it.bitmap }
    if (bitmaps.size < 2) return "至少要两个可见图层（现在 ${bitmaps.size} 个）"
    val name = when (kind) {
        AUTO_FOCUS -> "焦点合成"
        AUTO_PANO -> "全景拼接"
        else -> "HDR 合成"
    }
    val out = when (kind) {
        AUTO_FOCUS -> EffectOps.mergeFocus(bitmaps)
        AUTO_PANO -> EffectOps.photomerge(bitmaps[0], bitmaps[1], true)
        else -> EffectOps.mergeHdr(bitmaps)
    } ?: return "合成失败：图层尺寸不合适"
    val idx = s.addLayer(name)
    if (idx < 0) { out.recycle(); return "加图层失败" }
    val dst = s.layers[idx].bitmap
    if (dst == null) { out.recycle(); return "新图层是空的" }
    Canvas(dst).drawBitmap(out, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    val ow = out.width
    val oh = out.height
    out.recycle()
    s.invalidate()
    return "$name 完成：${ow}×${oh}，已加到图层「$name」"
}
