package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 曝光度：桌面端的三个参数都给了——曝光度(EV)、偏移、灰度系数校正。
 * 在线性空间里算：v' = (v * 2^EV + 偏移) ^ (1/伽马)，所以拉 EV 时高光不会先糊成一片白。
 */
@Composable
internal fun FxExposureDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var ev by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(0f) }
    var gamma by remember { mutableStateOf(1f) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    val snap = rememberUpdatedState(Triple(ev, offset, gamma))
    LaunchedEffect(Unit) {
        val b = base ?: return@LaunchedEffect
        var built: Triple<Float, Float, Float>? = null
        while (true) {
            val cur = snap.value
            if (cur != built) {
                built = cur
                val out = withContext(Dispatchers.Default) {
                    runCatching { exposure(b, cur.first, cur.second, cur.third) }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    ColorDockShell("曝光度", "以 2 的幂整体提亮/压暗，另加偏移与伽马；在线性空间里算，高光不容易糊", modifier) {
        LevelRow("曝光度", ev, -5f..5f, "%.2f") { ev = it }
        LevelRow("偏移", offset, -0.5f..0.5f, "%.2f") { offset = it }
        LevelRow("伽马", gamma, 0.1f..3f, "%.2f") { gamma = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DockButton("应用") {
                val e = ev
                val o = offset
                val g = gamma
                sketch?.clearPreview()
                sketch?.applyFilterToActive({ bmp -> exposure(bmp, e, o, g) }, "曝光度")
                tick++
            }
            DockButton("归零") { ev = 0f; offset = 0f; gamma = 1f }
            DockButton("取消") { sketch?.clearPreview(); tick++ }
        }
    }
}

/** 色彩平衡：阴影/中间调/高光 三段各有一组青-红 / 洋红-绿 / 黄-蓝，外加「保持明度」 */
@Composable
internal fun FxColorBalanceDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var range by remember { mutableStateOf(1) } // 0 阴影 1 中间调 2 高光
    // 用 SnapshotStateList 存三组值：写回列表才会触发重组，预览泵才拿得到新快照
    val cr = remember { androidx.compose.runtime.mutableStateListOf(0f, 0f, 0f) }
    val mg = remember { androidx.compose.runtime.mutableStateListOf(0f, 0f, 0f) }
    val yb = remember { androidx.compose.runtime.mutableStateListOf(0f, 0f, 0f) }
    var preserve by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    val snap = rememberUpdatedState(
        listOf(range, if (preserve) 1 else 0, cr[range], mg[range], yb[range]) to
            (cr.toList() + mg.toList() + yb.toList())
    )
    LaunchedEffect(Unit) {
        val b = base ?: return@LaunchedEffect
        var built: Any? = null
        while (true) {
            val cur = snap.value
            if (cur != built) {
                built = cur
                val head = cur.first
                val all = cur.second
                val out = withContext(Dispatchers.Default) {
                    runCatching {
                        colorBalance(
                            b,
                            // all = cr(3) + mg(3) + yb(3)；三组必须各自连续取，否则
                            // 「中间调」那档读到的是别的通道，表现为「拖了没反应」
                            floatArrayOf(all[0], all[1], all[2]),
                            floatArrayOf(all[3], all[4], all[5]),
                            floatArrayOf(all[6], all[7], all[8]),
                            head[1] == 1,
                        )
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    ColorDockShell(
        "色彩平衡",
        "分别调阴影 / 中间调 / 高光的偏色；「保持明度」只改色相不动亮度",
        modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip("阴影", range == 0) { range = 0 }
            TabChip("中间调", range == 1) { range = 1 }
            TabChip("高光", range == 2) { range = 2 }
        }
        Spacer(Modifier.height(6.dp))
        LevelRow("青 → 红", cr[range], -100f..100f) { cr[range] = it }
        LevelRow("洋红 → 绿", mg[range], -100f..100f) { mg[range] = it }
        LevelRow("黄 → 蓝", yb[range], -100f..100f) { yb[range] = it }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabChip(if (preserve) "保持明度：开" else "保持明度：关", preserve) { preserve = !preserve }
            Spacer(Modifier.width(10.dp))
            Text("关掉后亮度会随偏色一起变化", fontSize = 10.sp, color = Rv.textFaint)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DockButton("应用") {
                val r0 = cr.toList()
                val g0 = mg.toList()
                val b0 = yb.toList()
                val pr = preserve
                sketch?.clearPreview()
                sketch?.applyFilterToActive({ bmp ->
                    colorBalance(
                        bmp,
                        floatArrayOf(r0[0], r0[1], r0[2]),
                        floatArrayOf(g0[0], g0[1], g0[2]),
                        floatArrayOf(b0[0], b0[1], b0[2]),
                        pr,
                    )
                }, "色彩平衡")
                tick++
            }
            DockButton("归零") {
                for (i in 0..2) { cr[i] = 0f; mg[i] = 0f; yb[i] = 0f }
                }
            DockButton("取消") { sketch?.clearPreview(); tick++ }
        }
    }
}

/** 色调分离：把每个通道量化到 N 级，参数只有一级，但配上说明就不至于看不懂 */
@Composable
internal fun FxPosterizeDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var levels by remember { mutableStateOf(6f) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    val snap = rememberUpdatedState(levels)
    LaunchedEffect(Unit) {
        val b = base ?: return@LaunchedEffect
        var built: Float? = null
        while (true) {
            val cur = snap.value
            if (cur != built) {
                built = cur
                val out = withContext(Dispatchers.Default) {
                    runCatching {
                        com.reveal.editor.image.ColorFx.posterize(b, cur.roundToInt())
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    ColorDockShell("色调分离", "每个颜色通道只保留 N 个台阶，数越小色块越硬", modifier) {
        LevelRow("台阶数", levels, 2f..32f) { levels = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DockButton("应用") {
                val l = levels.roundToInt()
                sketch?.clearPreview()
                sketch?.applyFilterToActive({ bmp ->
                    com.reveal.editor.image.ColorFx.posterize(bmp, l)
                }, "色调分离")
                tick++
            }
            DockButton("重置") { levels = 6f }
            DockButton("取消") { sketch?.clearPreview(); tick++ }
        }
    }
}

/** 统一的白卡片外壳：标题 + 说明 + 可滚内容 */
@Composable
private fun ColorDockShell(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        val scroll = rememberScrollState()
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
                Text(hint, fontSize = 10.sp, color = Rv.textSub)
                Spacer(Modifier.height(8.dp))
                content()
            }
            ScrollBar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** 线性空间曝光：v' = (v * 2^ev + offset) ^ (1/gamma) */
private fun exposure(src: Bitmap, ev: Float, offset: Float, gamma: Float): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    if (w <= 0 || h <= 0) return out
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    val k = 2f.pow(ev)
    val invG = 1f / gamma.coerceIn(0.05f, 10f)
    for (i in px.indices) {
        val c = px[i]
        val a = c ushr 24
        if (a == 0) continue
        var r = ((c shr 16) and 0xFF) / 255f
        var g = ((c shr 8) and 0xFF) / 255f
        var b = (c and 0xFF) / 255f
        r = ((r * k + offset).coerceIn(0f, 1f)).pow(invG)
        g = ((g * k + offset).coerceIn(0f, 1f)).pow(invG)
        b = ((b * k + offset).coerceIn(0f, 1f)).pow(invG)
        px[i] = (a shl 24) or
            ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            ((b * 255f + 0.5f).toInt().coerceIn(0, 255))
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** 三段色彩平衡；preserve=true 时只挪色相、亮度按原图的 luma 拉回 */
private fun colorBalance(
    src: Bitmap,
    cr: FloatArray,
    mg: FloatArray,
    yb: FloatArray,
    preserve: Boolean,
): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    if (w <= 0 || h <= 0) return out
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val c = px[i]
        val a = c ushr 24
        if (a == 0) continue
        var r = ((c shr 16) and 0xFF) / 255f
        var g = ((c shr 8) and 0xFF) / 255f
        var b = (c and 0xFF) / 255f
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        // 三段权重：暗部 / 中间调 / 亮部
        val wShadow = ((0.5f - luma) * 2f).coerceIn(0f, 1f)
        val wHigh = ((luma - 0.5f) * 2f).coerceIn(0f, 1f)
        val wMid = (1f - wShadow - wHigh).coerceIn(0f, 1f)
        val dr = (cr[0] * wShadow + cr[1] * wMid + cr[2] * wHigh) / 100f * 0.5f
        val dg = (mg[0] * wShadow + mg[1] * wMid + mg[2] * wHigh) / 100f * 0.5f
        val db = (yb[0] * wShadow + yb[1] * wMid + yb[2] * wHigh) / 100f * 0.5f
        var nr = (r + dr).coerceIn(0f, 1f)
        var ng = (g + dg).coerceIn(0f, 1f)
        var nb = (b + db).coerceIn(0f, 1f)
        if (preserve) {
            val nl = 0.299f * nr + 0.587f * ng + 0.114f * nb
            if (nl > 0.0001f) {
                val f = luma / nl
                nr = (nr * f).coerceIn(0f, 1f)
                ng = (ng * f).coerceIn(0f, 1f)
                nb = (nb * f).coerceIn(0f, 1f)
            }
        }
        px[i] = (a shl 24) or
            ((nr * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((ng * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            ((nb * 255f + 0.5f).toInt().coerceIn(0, 255))
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}
