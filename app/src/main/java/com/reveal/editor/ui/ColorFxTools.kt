package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reveal.editor.image.AdjustOp
import com.reveal.editor.image.ColorFx
import com.reveal.editor.image.GradStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal class FxKnob(
    val label: String,
    val min: Float,
    val max: Float,
    val init: Float,
)

internal class FxSpec(
    val title: String,
    val hint: String,
    val knobs: List<FxKnob>,
    val colorSlots: Int,
    /** 可选：预设立（名字 → 全部旋钮值），面板顶部会渲染成一排胶囊。
     *  必须放在 apply 前面，否则 FxSpec(...) { ... } 的尾随 lambda 会绑到 presets 上。 */
    val presets: List<Pair<String, List<Float>>> = emptyList(),
    /** 可选：一次性「自动」取值（如镜头校正按 EXIF 估畸变），面板会显示一颗「自动」胶囊 */
    val auto: (() -> List<Float>)? = null,
    val apply: (FloatArray, IntArray, Bitmap) -> Bitmap?,
)

/** 数值显示：小范围参数（曝光、色阶之类）保留一位小数，大范围按整数 */
internal fun fxText(v: Float, range: ClosedFloatingPointRange<Float>): String =
    if (range.endInclusive - range.start <= 12f) {
        java.util.Locale.US.let { java.lang.String.format(it, "%.1f", v) }
    } else {
        v.roundToInt().toString()
    }

private val PaletteColors = listOf(
    0xFFFF3B30.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(),
    0xFF32ADE6.toInt(), 0xFF3A7BFF.toInt(), 0xFFB052DE.toInt(), 0xFFFFFFFF.toInt(),
    0xFF5F656C.toInt(), 0xFF000000.toInt(),
)



internal fun fxSpec(id: String): FxSpec? = when (id) {
    "exposure" -> FxSpec("曝光度", "整张提亮或压暗，单位 EV", listOf(FxKnob("曝光", -5f, 5f, 0f)), 0) { v, _, bmp ->
        val ev = v[0]
        ColorFx.exposure(bmp, ev)
    }
    "vibrance" -> FxSpec("自然饱和度", "只提低饱和的地方，过饱和的不动", listOf(FxKnob("数值", -100f, 100f, 0f)), 0) { v, _, bmp ->
        val amount = v[0].roundToInt()
        ColorFx.vibrance(bmp, amount)
    }
    "colorBalance" -> FxSpec(
        "色彩平衡", "三组滑块：青-红 / 洋红-绿 / 黄-蓝",
        listOf(FxKnob("青 - 红", -100f, 100f, 0f), FxKnob("洋红 - 绿", -100f, 100f, 0f), FxKnob("黄 - 蓝", -100f, 100f, 0f)), 0,
    ) { v, _, bmp ->
        val r = v[0].roundToInt(); val g = v[1].roundToInt(); val b = v[2].roundToInt()
        ColorFx.colorBalance(bmp, r, g, b)
    }
    "blackWhite" -> FxSpec(
        "黑白", "三个权重决定红绿蓝的亮度占比",
        listOf(FxKnob("红", 0f, 100f, 30f), FxKnob("绿", 0f, 100f, 59f), FxKnob("蓝", 0f, 100f, 11f)), 0,
    ) { v, _, bmp ->
        val r = v[0].roundToInt(); val g = v[1].roundToInt(); val b = v[2].roundToInt()
        ColorFx.blackWhite(bmp, r, g, b)
    }
    "photoFilter" -> FxSpec(
        "照片滤镜", "选一个滤镜色，再调浓度",
        listOf(FxKnob("浓度", 0f, 100f, 25f)), 1,
    ) { v, c, bmp ->
        val density = v[0].roundToInt()
        val col = PaletteColors.getOrElse(c[0]) { 0xFFFF9F0A.toInt() }
        ColorFx.photoFilter(bmp, col, density)
    }
    "posterize" -> FxSpec("色调分离", "把颜色压成有限的几级", listOf(FxKnob("色阶数 2~255", 2f, 255f, 6f)), 0) { v, _, bmp ->
        val n = v[0].roundToInt()
        ColorFx.posterize(bmp, n)
    }
    "gradientMap" -> FxSpec("渐变映射", "按明度映射到两色渐变", emptyList(), 2) { _, c, bmp ->
        val c1 = PaletteColors.getOrElse(c[0]) { 0xFF000000.toInt() }
        val c2 = PaletteColors.getOrElse(c[1]) { 0xFFFFFFFF.toInt() }
        ColorFx.gradientMap(bmp, c1, c2)
    }
    "shadowsHighlights" -> FxSpec(
        "阴影/高光", "正向提亮，负向压暗",
        listOf(FxKnob("阴影", -100f, 100f, 35f), FxKnob("高光", -100f, 100f, 0f)), 0,
    ) { v, _, bmp ->
        val s = v[0].roundToInt(); val h = v[1].roundToInt()
        ColorFx.shadowsHighlights(bmp, s, h)
    }
    "channelMixer" -> FxSpec(
        "通道混合器", "输出红/绿/蓝 各由输入三通道按百分比混合",
        listOf(
            FxKnob("红←红", -200f, 200f, 100f), FxKnob("红←绿", -200f, 200f, 0f), FxKnob("红←蓝", -200f, 200f, 0f),
            FxKnob("绿←红", -200f, 200f, 0f), FxKnob("绿←绿", -200f, 200f, 100f), FxKnob("绿←蓝", -200f, 200f, 0f),
            FxKnob("蓝←红", -200f, 200f, 0f), FxKnob("蓝←绿", -200f, 200f, 0f), FxKnob("蓝←蓝", -200f, 200f, 100f),
        ), 0,
    ) { v, _, bmp ->
        val m = IntArray(9) { v[it].roundToInt() }
        ColorFx.channelMixer(bmp, m)
    }
    "equalize" -> FxSpec("色调均化", "把明度直方图拉开", emptyList(), 0) { _, _, bmp ->
        ColorFx.equalize(bmp)
    }
    "replace" -> FxSpec(
        "替换颜色", "相近的颜色换色，别的像素不动",
        listOf(FxKnob("容差", 0f, 100f, 30f), FxKnob("色相", -180f, 180f, 0f), FxKnob("饱和度", 0f, 200f, 100f), FxKnob("明度", -100f, 100f, 0f)), 1,
    ) { v, c, bmp ->
        val target = PaletteColors.getOrElse(c[0]) { 0xFFFF3B30.toInt() }
        ColorFx.replaceColor(bmp, target, v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt(), v[3].roundToInt())
    }
    "selective" -> FxSpec(
        "可选颜色", "按颜色范围调 CMYK：0 红 1 黄 2 绿 3 青 4 蓝 5 洋红 6 白 7 中性 8 黑",
        listOf(FxKnob("范围", 0f, 8f, 0f), FxKnob("青", -100f, 100f, 0f), FxKnob("洋红", -100f, 100f, 0f), FxKnob("黄", -100f, 100f, 0f), FxKnob("黑", -100f, 100f, 0f)), 0,
    ) { v, _, bmp ->
        ColorFx.selectiveColor(bmp, v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt(), v[3].roundToInt(), v[4].roundToInt())
    }
    "matchColor" -> FxSpec(
        "匹配颜色", "把整张图的平均色拉向所选参考色",
        listOf(FxKnob("强度", 0f, 100f, 50f)), 1,
    ) { v, c, bmp ->
        val col = PaletteColors.getOrElse(c[0]) { 0xFF5F656C.toInt() }
        val ref = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        ref.setPixel(0, 0, col)
        val out = ColorFx.matchColor(bmp, ref, v[0].roundToInt())
        ref.recycle()
        out
    }
    "variation" -> FxSpec(
        "变化", "整体偏色：调色相 / 饱和 / 明暗",
        listOf(FxKnob("色相", -180f, 180f, 0f), FxKnob("饱和", 0f, 200f, 100f), FxKnob("明暗", -50f, 50f, 0f)), 0,
    ) { v, _, bmp ->
        ColorFx.variation(bmp, v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt())
    }
    "autoTone" -> FxSpec("自动色调", "按直方图剪掉两端 0.5% 再拉满", emptyList(), 0) { _, _, bmp ->
        ColorFx.autoTone(bmp)
    }
    "autoContrast" -> FxSpec("自动对比度", "按整体明暗剪掉两端 0.5% 再拉满", emptyList(), 0) { _, _, bmp ->
        ColorFx.autoContrast(bmp)
    }
    "autoColor" -> FxSpec("自动颜色", "各通道拉满后再拉向中性灰，去掉偏色", emptyList(), 0) { _, _, bmp ->
        ColorFx.autoColor(bmp)
    }
    "hdr" -> FxSpec(
        "HDR 色调", "提亮暗部、加局部对比，适合逆光照片",
        listOf(FxKnob("半径", 1f, 24f, 8f), FxKnob("强度", 0f, 200f, 100f), FxKnob("饱和度", 0f, 100f, 20f)), 0,
    ) { v, _, bmp ->
        ColorFx.hdrTone(bmp, v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt())
    }
    else -> filterSpec(id)
}

@Composable
internal fun ColorFxDialog(id: String, sketch: SketchView?, onClose: () -> Unit) {
    // 渐变映射用专门的「色带」面板（一条渐变 + 两端正方形取色块）
    if (id == "gradientMap") {
        GradientMapPanel(sketch, onClose)
        return
    }
    val spec = fxSpec(id) ?: return
    FxSpecDialog(spec, sketch, onClose, id)
}
/**
 * 渐变映射专用面板：一条色带（左=暗部/最黑，右=亮部/最白）+ 两个正方形取色块。
 * 色带只是「按亮度取色」的可视化，不是方向渐变。
 */
@Composable
internal fun GradientMapPanel(sketch: SketchView?, onClose: () -> Unit) {
    var lo by remember { mutableIntStateOf(0xFFE3EDFF.toInt()) }
    var hi by remember { mutableIntStateOf(0xFFE8C27A.toInt()) }
    var editSlot by remember { mutableIntStateOf(-1) }
    var dither by remember { mutableStateOf(false) }
    var strength by remember { mutableStateOf(100f) }
    // 平滑度 0~100（0 分段线性 / 100 缓入缓出）与反向
    var smooth by remember { mutableStateOf(0f) }
    var reverse by remember { mutableStateOf(false) }
    // 中间色标（桌面端的渐变条上可以随便加）：pos 0~1，颜色 0xRRGGBB
    val stops = remember { mutableStateListOf<GradStop>() }

    val previewSrc = remember {
        sketch?.snapshotActiveLayer()?.let { full ->
            val maxSide = maxOf(full.width, full.height)
            if (maxSide <= 700) full.copy(Bitmap.Config.ARGB_8888, false)
            else {
                val k = 700f / maxSide
                Bitmap.createScaledBitmap(
                    full,
                    (full.width * k).toInt().coerceAtLeast(1),
                    (full.height * k).toInt().coerceAtLeast(1),
                    true,
                )
            }
        }
    }
    val previewLayer = remember { sketch?.activeIndex ?: 0 }
    // 常驻泵：拖动色标/滑块时不会被反复取消，计算放后台线程
    val gmKey = rememberUpdatedState(
        listOf(lo, hi, if (dither) 1 else 0, strength.roundToInt(), smooth.roundToInt(), if (reverse) 1 else 0) to
            stops.map { it.pos to it.color }
    )
    LaunchedEffect(Unit) {
        val src = previewSrc ?: return@LaunchedEffect
        var built: Any? = null
        while (true) {
            val cur = gmKey.value
            if (cur != built) {
                built = cur
                val head = cur.first
                val st = cur.second.map { com.reveal.editor.image.GradStop(it.first, it.second) }
                val out = withContext(Dispatchers.Default) {
                    runCatching {
                        ColorFx.gradientMap(
                            src.copy(Bitmap.Config.ARGB_8888, false),
                            head[0] as Int,
                            head[1] as Int,
                            st,
                            (head[2] as Int) == 1,
                            head[3] as Int,
                            head[4] as Int,
                            (head[5] as Int) == 1,
                        )
                    }.getOrNull()
                }
                if (out != null) sketch?.setPreview(out, previewLayer)
            }
            delay(16)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    RvPanel(
        title = "渐变映射",
        subtitle = "按亮度取色：最黑用左端，最白用右端",
        onClose = { sketch?.clearPreview(); onClose() },
    ) {

        // 色带：左黑右白，中间有加过的色标
        val brushStops: Array<Pair<Float, Color>> = buildList {
            add(0f to Color(lo))
            stops.sortedBy { it.pos }.forEach { s ->
                add(s.pos.coerceIn(0f, 1f) to Color(0xFF000000.toInt() or s.colorLo()))
            }
            add(1f to Color(hi))
        }.toTypedArray()
        // 色带：点某个位置就在那儿加一个色标（桌面端渐变编辑器的手感）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(*brushStops))
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        val p = (off.x / size.width.toFloat()).coerceIn(0f, 1f)
                        // 取该位置当前的渐变色，作为新色标的初值
                        val lut = AdjustOp(
                            kind = AdjustOp.KIND_GRADIENT_MAP,
                            gmLo = (0xFF shl 24) or (lo and 0xFFFFFF),
                            gmHi = (0xFF shl 24) or (hi and 0xFFFFFF),
                            gmStops = stops.toList(),
                        ).gradLut()
                        stops.add(com.reveal.editor.image.GradStop(p, lut[(p * 255).toInt().coerceIn(0, 255)] and 0xFFFFFF))
                        editSlot = 2 + stops.lastIndex
                    }
                },
        )
        Spacer(Modifier.height(6.dp))
        // 预设渐变（桌面端的渐变预设库精简版）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            data class Preset(val name: String, val a: Int, val b: Int)
            listOf(
                Preset("黑→白", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
                Preset("紫→橙", 0xFF6A3FD1.toInt(), 0xFFF5A65B.toInt()),
                Preset("青→粉", 0xFF2DB7C4.toInt(), 0xFFF06CA8.toInt()),
                Preset("冷调", 0xFF1D3B6B.toInt(), 0xFFF2E6D0.toInt()),
            ).forEach { pr ->
                Box(
                    Modifier
                        .size(width = 46.dp, height = 22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            Brush.horizontalGradient(listOf(Color(pr.a), Color(pr.b)))
                        )
                        .border(1.dp, Color(0xFFC9CED6), RoundedCornerShape(5.dp))
                        .clickable {
                            lo = pr.a
                            hi = pr.b
                            stops.clear()
                        },
                )
            }
            Spacer(Modifier.weight(1f))
            TabChip(if (dither) "仿色：开" else "仿色：关", dither) { dither = !dither }
        }
        Spacer(Modifier.height(4.dp))
        LevelRow("强度", strength, 0f..100f) { strength = it }
        // 平滑度：0 = 色标之间线性插入（桌面端默认），100 = 缓入缓出（暗部亮部更「聚集」）
        LevelRow("平滑度", smooth, 0f..100f) { smooth = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip(if (reverse) "反向：开" else "反向：关", reverse) { reverse = !reverse }
            TabChip(if (dither) "仿色：开" else "仿色：关", dither) { dither = !dither }
        }

        // 左端 / 右端：正方形取色块 + 文字标注
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(lo))
                    .border(1.dp, Color(0xFFC9CED6), RoundedCornerShape(7.dp))
                    .clickable { editSlot = 0 },
            )
            Text("  左 · 暗部（最黑处）", color = Color(0xFF6B7178), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("右 · 亮部（最白处）  ", color = Color(0xFF6B7178), fontSize = 10.sp)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(hi))
                    .border(1.dp, Color(0xFFC9CED6), RoundedCornerShape(7.dp))
                    .clickable { editSlot = 1 },
            )
        }

        // 中间色标：一行一个，色块点开取色、滑块调位置、✕ 删掉
        val stopView = stops.toList()
        if (stopView.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            stopView.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFF000000.toInt() or s.colorLo()))
                            .border(1.dp, Color(0xFFC9CED6), RoundedCornerShape(5.dp))
                            .clickable { editSlot = 2 + i },
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        ThinSlider(
                            value = s.pos,
                            range = 0f..1f,
                            onChange = { v ->
                                val idx = stops.indexOfFirst { it === s }
                                if (idx >= 0) stops[idx] = s.copy(pos = v.coerceIn(0f, 1f))
                            },
                        )
                    }
                    Text("${(s.pos * 100).toInt()}%", color = Color(0xFF6B7178), fontSize = 10.sp)
                    Text(
                        "  ✕",
                        color = Color(0xFF5F656C),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val idx = stops.indexOfFirst { it === s }
                            if (idx >= 0) stops.removeAt(idx)
                            editSlot = -1
                        },
                    )
                }
            }
        }
        Text(
            "＋ 加色标",
            color = Color(0xFF6FA8FF),
            fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable {
                    // 新色标默认落在 50% 处、取该位置的当前渐变颜色
                    val lut = AdjustOp(
                        kind = AdjustOp.KIND_GRADIENT_MAP,
                        gmLo = lo, gmHi = hi, gmStops = stops.toList(),
                    ).gradLut()
                    stops.add(GradStop(0.5f, lut[128] and 0xFFFFFF))
                    editSlot = 2 + stops.lastIndex
                },
        )

        if (editSlot >= 0) {
            Spacer(Modifier.height(4.dp))
            val initial = when {
                editSlot == 0 -> lo
                editSlot == 1 -> hi
                else -> stops.getOrNull(editSlot - 2)?.let { 0xFF000000.toInt() or it.colorLo() } ?: lo
            }
            fun put(c: Int) {
                when {
                    editSlot == 0 -> lo = c
                    editSlot == 1 -> hi = c
                    else -> {
                        val idx = editSlot - 2
                        val old = stops.getOrNull(idx)
                        if (old != null) stops[idx] = old.copy(color = c and 0xFFFFFF)
                    }
                }
            }
            ColorPickerPanel(
                initial = initial,
                onColorChange = { c -> put(c) },
                onCancel = { editSlot = -1 },
                onConfirm = { c ->
                    put(c)
                    editSlot = -1
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TabChip("取消", false) { sketch?.clearPreview(); onClose() }
            Spacer(Modifier.width(8.dp))
            TabChip("确定", true) {
                sketch?.clearPreview()
                val snap = stops.toList()
                val dz = dither
                val stv = strength.roundToInt()
                sketch?.applyFilterToActive({
            bmp -> ColorFx.gradientMap(bmp, lo, hi, snap, dz, stv, smooth.roundToInt(), reverse)
        }, "渐变映射")
                onClose()
            }
        }
    }
}


@Composable
internal fun FxSpecDialog(spec: FxSpec, sketch: SketchView?, onClose: () -> Unit, specId: String = "") {
    val state = remember(spec) { mutableStateListOf<Float>().also { l -> spec.knobs.forEach { l.add(it.init) } } }
    val picked = remember(spec) { mutableStateListOf<Int>().also { l -> repeat(spec.colorSlots) { idx -> l.add(if (idx == 1) 7 else 9) } } }
    var editIndex by remember { mutableIntStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    // 实时预览：先抓一张小图当预览源，参数一动就算一遍贴回画布
    val previewSrc = remember(spec) {
        sketch?.snapshotActiveLayer()?.let { full ->
            val maxSide = maxOf(full.width, full.height)
            if (maxSide <= 700) full.copy(Bitmap.Config.ARGB_8888, false)
            else {
                val k = 700f / maxSide
                Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true)
            }
        }
    }
    val previewLayer = remember(spec) { sketch?.activeIndex ?: 0 }
    // 常驻预览循环：之前用 LaunchedEffect(参数) 会被下一个事件反复取消，只有停手才轮到它算完，
    // 表现就是「抬手才有预览」。这里循环不随参数重启，算完取最新一版再算。
    val previewSnap = rememberUpdatedState(state.toList() to picked.toList())
    LaunchedEffect(Unit) {
        val src = previewSrc ?: return@LaunchedEffect
        var built: Pair<List<Float>, List<Int>>? = null
        while (true) {
            val snap = previewSnap.value
            if (snap != built) {
                built = snap
                val vals = FloatArray(spec.knobs.size) { snap.first.getOrElse(it) { spec.knobs[it].init } }
                val cols = IntArray(spec.colorSlots) { snap.second.getOrElse(it) { 0 } }
                val out = withContext(Dispatchers.Default) {
                    runCatching { spec.apply(vals, cols, src.copy(Bitmap.Config.ARGB_8888, false)) }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out, previewLayer)
                    sketch?.invalidate()
                }
            }
            delay(12)
        }
    }
    DisposableEffect(Unit) {
        onDispose { sketch?.clearPreview() }
    }

    // 统一面板外壳：白卡片 + 标题/✕ + 底部两颗胶囊按钮
    RvPanel(
        title = spec.title,
        subtitle = spec.hint,
        onClose = { sketch?.clearPreview(); onClose() },
        maxHeightDp = 380,
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 196.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            spec.knobs.forEachIndexed { i, k ->
                val v = state.getOrElse(i) { k.init }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = k.label,
                        color = Color(0xFF6B7178),
                        fontSize = 10.sp,
                        modifier = Modifier.width(78.dp),
                    )
                    ThinSlider(
                        value = v,
                        range = k.min..k.max,
                        modifier = Modifier.weight(1f),
                        onChange = { nv -> if (i < state.size) state[i] = nv },
                    )
                    Text(
                        text = fxText(v, k.min..k.max),
                        color = Rv.text,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .width(44.dp)
                            .clickable {
                                editIndex = i
                                editText = fxText(v, k.min..k.max)
                            },
                    )
                }
                Spacer(Modifier.height(3.dp))
            }
            for (slot in 0 until spec.colorSlots) {
                Text(
                    text = when {
                        specId == "gradientMap" && slot == 0 -> "黑场颜色"
                        specId == "gradientMap" -> "白场颜色"
                        specId == "photoFilter" -> "滤镜色"
                        specId == "matchColor" -> "参考色"
                        specId == "replace" -> "要被替换的颜色"
                        else -> if (slot == 0) "颜色" else "颜色 " + (slot + 1)
                    },
                    color = Color(0xFF6B7178),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PaletteColors.forEachIndexed { ci, col ->
                        Row(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color(col), CircleShape)
                                .clickable { if (slot < picked.size) picked[slot] = ci },
                        ) {}
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TabChip("取消", false) { sketch?.clearPreview(); onClose() }
            Spacer(Modifier.width(8.dp))
            TabChip("确定", true) {
                val vals = FloatArray(spec.knobs.size) { state.getOrElse(it) { spec.knobs[it].init } }
                val cols = IntArray(spec.colorSlots) { picked.getOrElse(it) { 0 } }
                val fn: (Bitmap) -> Bitmap? = { bmp -> spec.apply(vals, cols, bmp) }
                sketch?.clearPreview()
                sketch?.applyFilterToActive(fn, spec.title)
                if (specId.isNotEmpty()) ActionBank.record(specId, spec.title, vals, cols)
                onClose()
            }
        }
    }

    // 点数字 -> 直接敲精确值（桌面端的滑杆都带数字框）
    if (editIndex in spec.knobs.indices) {
        val k = spec.knobs[editIndex]
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = { Text(k.label, fontSize = 13.sp) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { s -> editText = s.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nv = editText.toFloatOrNull()
                    if (nv != null && editIndex < state.size) state[editIndex] = nv.coerceIn(k.min, k.max)
                    editIndex = -1
                }) { Text("\u597d") }
            },
            dismissButton = {
                TextButton(onClick = { editIndex = -1 }) { Text("\u53d6\u6d88") }
            },
        )
    }
}

/** 给 FxSpec 挂预设（构造后链式设置，避免改全部现有构造调用） */
internal fun FxSpec.withPresets(vararg ps: Pair<String, List<Float>>): FxSpec =
    FxSpec(title, hint, knobs, colorSlots, presets = ps.toList(), auto = auto, apply = apply)

/** 挂「自动」取值 */
internal fun FxSpec.withAuto(f: () -> List<Float>): FxSpec =
    FxSpec(title, hint, knobs, colorSlots, presets = presets, auto = f, apply = apply)
