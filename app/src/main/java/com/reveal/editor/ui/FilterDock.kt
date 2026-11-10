package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 「滤镜」子工具的参数面板。
 *
 * 与其它调色面板保持同一套交互（用户明确要求）：
 * - 拖参数时**实时预览**（常驻泵 + 后台线程 + 降采样底图）；
 * - 只有点「应用」才真正落到图层，并进撤销；
 * - 「说明」是第二页：写清这一项对应 桌面端的哪个滤镜、每个参数的物理含义，
 *   参数多的时候分页而不是把面板拉得很长。
 */
@Composable
internal fun FilterDock(
    sketch: SketchView?,
    specId: String,
    maxHeightDp: Int = 420,
    modifier: Modifier = Modifier,
    /** 镜头光晕面板里那颗「光晕工作室」按钮：点它进自定义光效界面 */
    onOpenFlareStudio: (() -> Unit)? = null,
) {
    val spec = remember(specId) { fxSpec(specId) }
    if (spec == null) return
    // 每个滑块的当前值（按 spec 的默认值初始化）
    val values = remember(specId) {
        androidx.compose.runtime.mutableStateListOf<Float>().apply {
            spec.knobs.forEach { add(it.init) }
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    // 自适应预览分辨率：算得慢（>28ms）就自动降一档，保证拖动时画面跟手；「应用」永远是全分辨率
    // 预览分辨率固定 900：降档会让画面变糊、影响调参判断（用户明确要求「宁卡不糊」）
    val previewSide = 900
    val base = remember(sketch?.activeIndex, tick, specId) {
        previewBase(sketch?.flatten(), previewSide)
    }

    // 常驻泵：参数变了就重算预览（后台线程），不做「抬手才变」
    val key = rememberUpdatedState(values.toList() to base)
    // 不再做「停手才出图」：宁可慢一点，也要拖动时就看到画面（清晰度优先）
    LaunchedEffect(specId) {
        var built: Any? = null
        var lastChangeMs = System.currentTimeMillis()
        var lastStrokeMs = 0L
        while (true) {
            val cur = key.value
            val bmp = cur.second
            if (cur != built && bmp != null) {
                lastChangeMs = System.currentTimeMillis()
                built = cur
                val v = cur.first.toFloatArray()
                val t0 = System.nanoTime()
                val out = withContext(Dispatchers.Default) {
                    runCatching { spec.apply(v, IntArray(0), bmp) }.getOrNull()
                }
                // 只统计耗时（日志用），不再根据耗时降档

                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
                lastChangeMs = System.currentTimeMillis()
            }
            delay(20)
        }
    }
    DisposableEffect(specId) { onDispose { sketch?.clearPreview() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = maxHeightDp.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            }
            // 自动：按拍摄信息一次算好（镜头校正的 EXIF 畸变估计）
            spec.auto?.let { auto ->
                Spacer(Modifier.height(6.dp))
                TabChip("自动", false) {
                    val vals = auto()
                    for (i in values.indices) if (i < vals.size) values[i] = vals[i]
                }
                Text(
                    com.reveal.editor.data.ExifInfo.summary(),
                    fontSize = 9.5.sp,
                    color = Rv.textFaint,
                    maxLines = 2,
                )
            }
            // 镜头光晕：预设下面挂一颗「光晕工作室」，进去自己拼光效
            if (specId == "lensFlare" && onOpenFlareStudio != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabChip("光晕工作室", true) { onOpenFlareStudio() }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "用辉光/光环/鬼影/星芒/拉丝自由组合，存成预设",
                        fontSize = 9.5.sp,
                        color = Rv.textFaint,
                    )
                }
            }
            // 预设：点一下把整组参数套上（光照效果、镜头光晕这类都有）
            if (spec.presets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    spec.presets.forEach { (name, vals) ->
                        TabChip(name, false) {
                            for (i in values.indices) {
                                if (i < vals.size) values[i] = vals[i]
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            // 只给参数区限高并滚动：标题与按钮固定，不会因为整块一起滚而把按钮挤出屏幕
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (maxHeightDp - 110).dp),
            ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(end = 8.dp),
            ) {
                if (spec.knobs.isEmpty()) {
                    Text(
                        "这个滤镜没有可调参数，点「应用」直接生效",
                        fontSize = 10.sp,
                        color = Rv.textSub,
                    )
                }
                spec.knobs.forEachIndexed { i, k ->
                    LevelRow(k.label, values[i], k.min..k.max) { v -> values[i] = v }
                }
                Spacer(Modifier.height(8.dp))
                // 光照类：同时有「方位角」「仰角」时，可以点画面直接定光源
                val angIdx = spec.knobs.indexOfFirst { it.label == "方位角" }
                val elvIdx = spec.knobs.indexOfFirst { it.label == "仰角" }
                if (angIdx >= 0 && elvIdx >= 0) {
                    var lightingPick by remember(specId) { mutableStateOf(false) }
                    Spacer(Modifier.height(4.dp))
                    TabChip(if (lightingPick) "点画面定光源 · 等待点击" else "点画面定光源", lightingPick) {
                        lightingPick = !lightingPick
                        if (lightingPick) {
                            FilterPick.start { fx, fy ->
                                // 画面中心为 0°，右=0°、下=90°；离中心越远仰角越低
                                val dx = fx - 0.5f
                                val dy = fy - 0.5f
                                val deg = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                                val dist = kotlin.math.sqrt(dx * dx + dy * dy) * 2f
                                values[angIdx] = deg.coerceIn(-180f, 180f)
                                values[elvIdx] = (90f - dist * 90f).coerceIn(5f, 90f)
                            }
                        } else {
                            FilterPick.stop()
                        }
                    }
                    androidx.compose.runtime.DisposableEffect(lightingPick) {
                        onDispose { if (lightingPick) FilterPick.stop() }
                    }
                }

                // 含「中心 X/Y」参数的滤镜：可以点画面直接取中心
                val cxIdx = spec.knobs.indexOfFirst { it.label.startsWith("中心 X") }
                if (cxIdx >= 0 && values.size > cxIdx + 1) {
                    var picking by remember(specId) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TabChip(if (picking) "点画面选中心：开" else "点画面选中心", picking) {
                            picking = !picking
                            if (picking) {
                                FilterPick.start { fx, fy ->
                                    values[cxIdx] = (fx * 100f).coerceIn(0f, 100f)
                                    values[cxIdx + 1] = (fy * 100f).coerceIn(0f, 100f)
                                    picking = false
                                    FilterPick.stop()
                                }
                            } else {
                                FilterPick.stop()
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (picking) "现在点画布上任意位置" else "也可以用两个滑块微调",
                            fontSize = 10.sp,
                            color = if (picking) Rv.accentText else Rv.textFaint,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.runtime.DisposableEffect(specId) { onDispose { FilterPick.stop() } }
                }
            }
                ScrollBar(scroll, Modifier.align(Alignment.CenterEnd))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                    DockButton("应用") {
                        val v = values.toFloatArray()
                        sketch?.clearPreview()
                        sketch?.applyFilterToActive({ bmp -> spec.apply(v, IntArray(0), bmp) }, spec.title)
                        tick++
                    }
                    Spacer(Modifier.width(8.dp))
                    DockButton("复位") {
                        spec.knobs.forEachIndexed { i, k -> values[i] = k.init }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        spec.hint,
                        fontSize = 10.sp,
                        color = Rv.textFaint,
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                }
        }
    }
}
