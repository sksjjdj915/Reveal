package com.reveal.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * 画笔那一组（桌面端同款 18 个工具）的参数栏。
 * 单独放一个文件，避免 EditorScreen 这个方法太大（Kotlin 单方法 64KB 上限）。
 */
@Composable
internal fun PsToolOptionsBar(
    tool: SketchView.Tool,
    sketch: SketchView?,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    tolerance: Int,
    onTolerance: (Int) -> Unit,
    spongeMode: Int,
    onSpongeMode: (Int) -> Unit,
    bgSample: Int,
    onBgSample: (Int) -> Unit,
    bgProtect: Boolean,
    onBgProtect: (Boolean) -> Unit,
    bgLimit: Int,
    onBgLimit: (Int) -> Unit,
    replaceSample: Int,
    onReplaceSample: (Int) -> Unit,
    mixerMix: Float,
    onMixerMix: (Float) -> Unit,
    mixerFg: Float,
    onMixerFg: (Float) -> Unit,
    patternIdx: Int,
    onPatternIdx: (Int) -> Unit,
    artStyle: Int,
    onArtStyle: (Int) -> Unit,
    artFidelity: Int,
    onArtFidelity: (Int) -> Unit,
    artRegion: Int,
    onArtRegion: (Int) -> Unit,
    toneProtect: Boolean,
    onToneProtect: (Boolean) -> Unit,
    patternImpression: Boolean,
    onPatternImpression: (Boolean) -> Unit,
    eraseToHistory: Boolean,
    onEraseToHistory: (Boolean) -> Unit,
    redeyePupil: Float,
    onRedeyePupil: (Float) -> Unit,
    redeyeDarken: Float,
    onRedeyeDarken: (Float) -> Unit,
    magicCont: Boolean,
    onMagicCont: (Boolean) -> Unit,
) {
    val pct = { v: Float -> "${(v * 100).roundToInt()}%" }
    when (tool) {
        SketchView.Tool.REPLACE -> ToolOptionsBar(
            title = "颜色替换画笔",
            hint = "把画到的颜色换成前景色，保留原图明暗",
            sliderLabel = "大小",
            sliderValue = sketch?.brushSize ?: 24f,
            sliderRange = 1f..300f,
            sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
            onSlider = { sketch?.brushSize = it },
            slider2Label = "容差",
            slider2Value = tolerance.toFloat(),
            slider2Range = 0f..255f,
            slider2Display = tolerance.toString(),
            onSlider2 = { onTolerance(it.toInt()); sketch?.fxTolerance = it.toInt() },
            chips = listOf("颜色", "色相", "饱和度", "亮度"),
            chipIndex = sketch?.replaceMode ?: 0,
            onChip = { sketch?.replaceMode = it },
            actions = listOf(
                ("取样：" + listOf("连续", "一次", "背景色板")[replaceSample.coerceIn(0, 2)]) to {
                    val n = (replaceSample + 1) % 3
                    onReplaceSample(n)
                    sketch?.replaceSample = n
                },
                ("限制：" + listOf("连续", "不连续", "查找边缘")[(sketch?.replaceLimit ?: 1).coerceIn(0, 2)]) to {
                    sketch?.replaceLimit = ((sketch?.replaceLimit ?: 1) + 1) % 3
                },
            ),
        )
        SketchView.Tool.ERASER_BG -> {
            // 必须用 Compose 状态：直接读 sketch 的普通 var 不会触发重组，滑块看着拖不动
            var bsz by remember { mutableFloatStateOf(sketch?.bgEraserSize ?: 60f) }
            var bhard by remember { mutableFloatStateOf(sketch?.bgEraserHardness ?: 0.85f) }
            var bedge by remember { mutableFloatStateOf(sketch?.bgEdge ?: 20f) }
            ToolOptionsBar(
            title = "背景橡皮擦",
            hint = "只擦掉和取样色相近的像素，保留边缘",
            sliderLabel = "大小",
            sliderValue = bsz,
            sliderRange = 1f..300f,
            sliderDisplay = bsz.roundToInt().toString(),
            onSlider = { bsz = it; sketch?.bgEraserSize = it },
            slider2Label = "容差",
            slider2Value = tolerance.toFloat(),
            slider2Range = 1f..255f,
            slider2Display = tolerance.toString(),
            onSlider2 = { onTolerance(it.toInt()); sketch?.fxTolerance = it.toInt() },
            extraSliders = listOf(
                BarSlider("硬度", bhard, 0f..1f, "${(bhard * 100).roundToInt()}%") {
                    bhard = it
                    sketch?.bgEraserHardness = it
                },
                BarSlider("边缘阈值", bedge, 0f..100f, bedge.roundToInt().toString()) {
                    bedge = it
                    sketch?.bgEdge = it
                },
            ),
            chips = listOf("连续", "一次", "背景色板"),
            chipIndex = bgSample,
            onChip = { onBgSample(it); sketch?.bgSampleMode = it },
            actionLabel = if (bgProtect) "保护前景色：开" else "保护前景色：关",
            onAction = { onBgProtect(!bgProtect); sketch?.bgProtect = !bgProtect },
            actions = listOf(
                ("限制：" + listOf("连续", "不连续", "查找边缘")[bgLimit.coerceIn(0, 2)]) to {
                    val n = (bgLimit + 1) % 3
                    onBgLimit(n)
                    sketch?.bgLimit = n
                },
            ),
            )
        }
        SketchView.Tool.ERASER_MAGIC -> {
            var aa by remember { mutableStateOf(sketch?.magicAntialias ?: true) }
            var all by remember { mutableStateOf(sketch?.magicAllLayers ?: false) }
            ToolOptionsBar(
                title = "魔术橡皮擦",
                hint = "点一下，把相同颜色一次擦掉",
                sliderLabel = "容差",
                sliderValue = tolerance.toFloat(),
                sliderRange = 1f..255f,
                sliderDisplay = tolerance.toString(),
                onSlider = { onTolerance(it.toInt()); sketch?.fxTolerance = it.toInt() },
                chips = listOf("当前图层", "所有图层"),
                chipIndex = if (all) 1 else 0,
                onChip = { all = it == 1; sketch?.magicAllLayers = all },
                actionLabel = if (magicCont) "连续：开" else "连续：关",
                onAction = { onMagicCont(!magicCont); sketch?.magicContinuous = !magicCont },
                actions = listOf(
                    (if (aa) "消除锯齿：开" else "消除锯齿：关") to {
                        aa = !aa
                        sketch?.magicAntialias = aa
                    },
                ),
            )
        }
        SketchView.Tool.ERASER -> {
            // 橡皮擦有自己的一套大小/硬度/不透明度（以前全跟画笔共用，所以三种模式看着一样硬）
            var esz by remember { mutableFloatStateOf(sketch?.eraserSize ?: 48f) }
            var ehard by remember { mutableFloatStateOf(sketch?.eraserHardness ?: 0.85f) }
            var eop by remember { mutableFloatStateOf(sketch?.eraserOpacity ?: 1f) }
            ToolOptionsBar(
                title = "橡皮擦",
                hint = "擦掉像素；开「抹到历史记录」则把历史状态刷回来",
                sliderLabel = "大小",
                sliderValue = esz,
                sliderRange = 1f..300f,
                sliderDisplay = esz.roundToInt().toString(),
                onSlider = {
                    esz = it
                    sketch?.eraserSize = it
                },
                slider2Label = "硬度",
                slider2Value = ehard,
                slider2Range = 0f..1f,
                slider2Display = pct(ehard),
                onSlider2 = {
                    ehard = it
                    sketch?.eraserHardness = it
                },
                extraSliders = listOf(
                    BarSlider("不透明度", eop, 0.02f..1f, pct(eop)) {
                        eop = it
                        sketch?.eraserOpacity = it
                    },
                ),
                chips = listOf("画笔", "铅笔", "块"),
                chipIndex = (sketch?.eraserMode ?: 0).coerceIn(0, 2),
                onChip = { sketch?.eraserMode = it },
                actions = listOf(
                    (if (sketch?.eraseToHistory == true) "抹到历史记录：开" else "抹到历史记录：关") to {
                        val b = !(sketch?.eraseToHistory ?: false)
                        sketch?.eraseToHistory = b
                        onEraseToHistory(b)
                    },
                ),
            )
        }
        SketchView.Tool.SPONGE -> {
            var sstr by remember { mutableFloatStateOf(sketch?.spongeStrength ?: 0.45f) }
            ToolOptionsBar(
                title = "海绵",
                hint = "降低 / 提高颜色饱和度",
                sliderLabel = "大小",
                sliderValue = sketch?.brushSize ?: 24f,
                sliderRange = 1f..300f,
                sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
                onSlider = { sketch?.brushSize = it },
                slider2Label = "流量",
                slider2Value = opacity,
                slider2Range = 0.05f..1f,
                slider2Display = pct(opacity),
                onSlider2 = onOpacity,
                extraSliders = listOf(
                    // 强度以前是「点一下换一档」的按钮，没法细调
                    BarSlider("强度", sstr, 0.05f..1f, pct(sstr)) {
                        sstr = it
                        sketch?.spongeStrength = it
                    },
                ),
                chips = listOf("去色", "加色"),
                chipIndex = spongeMode,
                onChip = { onSpongeMode(it); sketch?.spongeMode = it },
                actions = listOf(
                    (if (sketch?.spongeAirbrush == true) "喷枪式：开" else "喷枪式：关") to {
                        sketch?.spongeAirbrush = !(sketch?.spongeAirbrush ?: false)
                    },
                ),
            )
        }
        SketchView.Tool.MIXER -> {
            var mload by remember { mutableFloatStateOf(sketch?.mixerLoad ?: 0.35f) }
            ToolOptionsBar(
            title = "混合器画笔",
            hint = "笔下会带出画布上已有的颜色，越拖越混",
            sliderLabel = "大小",
            sliderValue = sketch?.brushSize ?: 24f,
            sliderRange = 1f..300f,
            sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
            onSlider = { sketch?.brushSize = it },
            slider2Label = "混合",
            slider2Value = mixerMix,
            slider2Range = 0f..1f,
            slider2Display = pct(mixerMix),
            onSlider2 = { onMixerMix(it); sketch?.mixerMix = it },
            extraSliders = listOf(
                BarSlider("载入", mload, 0f..1f, "${(mload * 100).roundToInt()}%") {
                    mload = it
                    sketch?.mixerLoad = it
                },
                BarSlider("前景色", mixerFg, 0f..1f, pct(mixerFg)) {
                    onMixerFg(it)
                    sketch?.mixerFg = it
                },
                BarSlider("流量", opacity, 0.05f..1f, pct(opacity)) { onOpacity(it) },
            ),
            chips = listOf("干", "湿", "非常湿"),
            chipIndex = (sketch?.mixerWet ?: 1).coerceIn(0, 2),
            onChip = {
                sketch?.mixerWet = it
                // 桌面端的湿度档位：越湿带出的颜色越多、和前景色混得越少
                val load = listOf(0.25f, 0.55f, 0.85f)[it]
                val mix = listOf(0.75f, 0.5f, 0.3f)[it]
                sketch?.mixerLoad = load
                sketch?.mixerMix = mix
                onMixerMix(mix)
            },
            )
        }
        SketchView.Tool.SPOT -> {
            var type by remember { mutableIntStateOf(sketch?.spotType ?: 0) }
            var sring by remember { mutableFloatStateOf(sketch?.spotRing ?: 1f) }
            var sfea by remember { mutableFloatStateOf(sketch?.spotFeather ?: 0.6f) }
            ToolOptionsBar(
                title = "污点修复画笔",
                hint = "涂在瑕疵上，用周围颜色盖掉",
                sliderLabel = "大小",
                sliderValue = sketch?.brushSize ?: 24f,
                sliderRange = 1f..300f,
                sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
                onSlider = { sketch?.brushSize = it },
                slider2Label = "强度",
                slider2Value = opacity,
                slider2Range = 0.05f..1f,
                slider2Display = pct(opacity),
                onSlider2 = onOpacity,
                extraSliders = listOf(
                    BarSlider(
                        label = "取样圈宽",
                        value = sring,
                        range = 0.5f..3f,
                        display = String.format(java.util.Locale.US, "%.1f", sring),
                        onChange = { sring = it; sketch?.spotRing = it },
                    ),
                    BarSlider(
                        label = "边缘融合",
                        value = sfea,
                        range = 0f..1f,
                        display = pct(sfea),
                        onChange = { sfea = it; sketch?.spotFeather = it },
                    ),
                ),
                chips = listOf("内容识别", "创建纹理", "近似匹配"),
                chipIndex = type.coerceIn(0, 2),
                onChip = {
                    type = it
                    sketch?.spotType = it
                },
            )
        }
        SketchView.Tool.REDEYE -> ToolOptionsBar(
            title = "红眼工具",
            hint = "把瞳孔框起来，松手就会压暗红色",
            sliderLabel = "瞳孔大小",
            sliderValue = redeyePupil,
            sliderRange = 0f..1f,
            sliderDisplay = pct(redeyePupil),
            onSlider = { onRedeyePupil(it); sketch?.redeyePupil = it },
            chips = listOf("变暗 40%", "变暗 60%", "变暗 85%"),
            chipIndex = when {
                redeyeDarken < 0.5f -> 0
                redeyeDarken < 0.75f -> 1
                else -> 2
            },
            onChip = {
                val v = listOf(0.4f, 0.6f, 0.85f)[it]
                onRedeyeDarken(v)
                sketch?.redeyeDarken = v
            },
        )
        SketchView.Tool.HISTORY_BRUSH -> ToolOptionsBar(
            title = "历史记录画笔",
            hint = "先用历史面板退到想恢复的样子，再在这里涂",
            sliderLabel = "不透明度",
            sliderValue = opacity,
            sliderRange = 0.05f..1f,
            sliderDisplay = pct(opacity),
            onSlider = onOpacity,
            chips = listOf("上一状态", "打开文档时"),
            chipIndex = if ((sketch?.historySource ?: 0) == 0) 0 else 1,
            onChip = { sketch?.historySource = if (it == 0) 0 else 1 },
        )
        SketchView.Tool.ART_HISTORY -> ToolOptionsBar(
            title = "历史记录艺术画笔",
            hint = "把历史状态用抖动的笔触刷回来",
            sliderLabel = "大小",
            sliderValue = sketch?.brushSize ?: 24f,
            sliderRange = 1f..120f,
            sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
            onSlider = { sketch?.brushSize = it },
            slider2Label = "保真度",
            slider2Value = artFidelity.toFloat(),
            slider2Range = 0f..100f,
            slider2Display = artFidelity.toString(),
            onSlider2 = { onArtFidelity(it.toInt()); sketch?.artFidelity = it.toInt() },
            // 桌面端的 10 种样式，对应 artHistory 的 style 0..9
            chips = listOf(
                "紧绷短", "紧绷中", "紧绷长", "松散中", "松散长",
                "点涂", "涂抹", "涂抹短", "涂抹中", "涂抹长",
            ),
            chipIndex = (artStyle).coerceIn(0, 9),
            onChip = {
                onArtStyle(it)
                sketch?.artStyle = it
            },
            actions = listOf(
                (if (sketch?.artRegion == 0) "区域：整个文档" else "区域：当前图层") to {
                    val r = if (sketch?.artRegion == 0) 1 else 0
                    sketch?.artRegion = r
                    onArtRegion(r)
                },
            ),
        )
        SketchView.Tool.LIQUIFY -> {
            var str by remember { mutableFloatStateOf(sketch?.liquifyStrength ?: 60f) }
            var mode by remember { mutableIntStateOf(sketch?.liquifyMode ?: 0) }
            ToolOptionsBar(
                title = "液化",
                hint = "手指按住往外拖，圈子里的像素跟着走；不涂新颜色",
                sliderLabel = "力度",
                sliderValue = str,
                sliderRange = 0f..100f,
                sliderDisplay = str.roundToInt().toString(),
                onSlider = {
                    str = it
                    sketch?.liquifyStrength = it
                },
                chips = listOf("推进", "膨胀", "收缩"),
                chipIndex = mode.coerceIn(0, 2),
                onChip = {
                    mode = it
                    sketch?.liquifyMode = it
                },
            )
        }
        SketchView.Tool.PATTERN -> {
            var aligned by remember { mutableStateOf(sketch?.patternAligned ?: true) }
            ToolOptionsBar(
                title = "图案图章",
                hint = "用图案刷",
                actionLabel = "换图案：" + Patterns.all()[patternIdx.coerceIn(0, Patterns.all().size - 1)].first,
                onAction = {
                    val n = (patternIdx + 1) % Patterns.all().size
                    onPatternIdx(n)
                    sketch?.patternIndex = n
                },
                actions = listOf(
                    (if (aligned) "对齐：开" else "对齐：关") to {
                        aligned = !aligned
                        sketch?.patternAligned = aligned
                    },
                    "存选区为图案" to {
                        val idx = sketch?.definePatternFromSelection()
                        if (idx != null && idx >= 0) {
                            onPatternIdx(idx)
                            sketch.patternIndex = idx
                        }
                    },
                    (if (sketch?.patternImpressionist == true) "印象派：开" else "印象派：关") to {
                        val b = !(sketch?.patternImpressionist ?: false)
                        sketch?.patternImpressionist = b
                        onPatternImpression(b)
                    },
                ),
            )
        }
        SketchView.Tool.TEXT -> ToolOptionsBar(
            title = "文字",
            hint = "在画布上点一下写字；做完文字还能把它转成选区（文字蒙版那种用法）",
            actions = listOf(
                ("把上一段文字转选区") to {
                    val v = sketch
                    val ok = v?.textMaskSelectLast() == true
                    if (!ok && v != null) {
                        v.post {
                            android.widget.Toast
                                .makeText(v.context, "先做一段文字，再转选区", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                },
            ),
        )
        else -> Unit
    }
}
/**
 * 上面那个参数栏的宿主：所有参数状态放在这里，EditorScreen 只传工具、画布和底部不透明度。
 * 这样 EditorScreen 那个 @Composable 不会因为参数爆炸撞上 JVM 单方法 64KB 上限。
 */
@Composable
internal fun PsToolOptionsHost(
    tool: SketchView.Tool,
    sketch: SketchView?,
    opacity: Float,
    onOpacity: (Float) -> Unit,
) {
    var tolerance by remember { mutableIntStateOf(sketch?.fxTolerance ?: 32) }
    var sponge by remember { mutableIntStateOf(sketch?.spongeMode ?: 0) }
    var bgSample by remember { mutableIntStateOf(sketch?.bgSampleMode ?: 1) }
    var bgProtect by remember { mutableStateOf(sketch?.bgProtect ?: false) }
    var bgLimit by remember { mutableIntStateOf(sketch?.bgLimit ?: 1) }
    var replaceSample by remember { mutableIntStateOf(sketch?.replaceSample ?: 1) }
    var mixer by remember { mutableFloatStateOf(sketch?.mixerMix ?: 0.5f) }
    var mixerFg by remember { mutableFloatStateOf(sketch?.mixerFg ?: 0.12f) }
    var patternIdx by remember { mutableIntStateOf(sketch?.patternIndex ?: 0) }
    var artStyle by remember { mutableIntStateOf(sketch?.artStyle ?: 2) }
    var artFidelity by remember { mutableIntStateOf(sketch?.artFidelity ?: 50) }
    var artRegion by remember { mutableIntStateOf(sketch?.artRegion ?: 1) }
    var toneProtect by remember { mutableStateOf(sketch?.toneProtect ?: false) }
    var impression by remember { mutableStateOf(sketch?.patternImpressionist ?: false) }
    var eraseToHistory by remember { mutableStateOf(sketch?.eraseToHistory ?: false) }
    var redeyePupil by remember { mutableFloatStateOf(sketch?.redeyePupil ?: 0.5f) }
    var redeyeDarken by remember { mutableFloatStateOf(sketch?.redeyeDarken ?: 0.6f) }
    var magicCont by remember { mutableStateOf(sketch?.magicContinuous ?: true) }

    androidx.compose.runtime.LaunchedEffect(tolerance) { sketch?.fxTolerance = tolerance }
    androidx.compose.runtime.LaunchedEffect(sponge) { sketch?.spongeMode = sponge }
    androidx.compose.runtime.LaunchedEffect(bgSample) { sketch?.bgSampleMode = bgSample }
    androidx.compose.runtime.LaunchedEffect(bgProtect) { sketch?.bgProtect = bgProtect }
    androidx.compose.runtime.LaunchedEffect(bgLimit) { sketch?.bgLimit = bgLimit }
    androidx.compose.runtime.LaunchedEffect(replaceSample) { sketch?.replaceSample = replaceSample }
    androidx.compose.runtime.LaunchedEffect(mixer) { sketch?.mixerMix = mixer }
    androidx.compose.runtime.LaunchedEffect(mixerFg) { sketch?.mixerFg = mixerFg }
    androidx.compose.runtime.LaunchedEffect(artStyle) { sketch?.artStyle = artStyle }
    androidx.compose.runtime.LaunchedEffect(artFidelity) { sketch?.artFidelity = artFidelity }
    androidx.compose.runtime.LaunchedEffect(artRegion) { sketch?.artRegion = artRegion }
    androidx.compose.runtime.LaunchedEffect(toneProtect) { sketch?.toneProtect = toneProtect }
    androidx.compose.runtime.LaunchedEffect(impression) { sketch?.patternImpressionist = impression }
    androidx.compose.runtime.LaunchedEffect(eraseToHistory) { sketch?.eraseToHistory = eraseToHistory }
    androidx.compose.runtime.LaunchedEffect(redeyePupil) { sketch?.redeyePupil = redeyePupil }
    androidx.compose.runtime.LaunchedEffect(redeyeDarken) { sketch?.redeyeDarken = redeyeDarken }
    androidx.compose.runtime.LaunchedEffect(magicCont) { sketch?.magicContinuous = magicCont }

    // 换工具时把这一排参数从画布重读一遍：每条工具的参数都存在 SketchView 里，
    // 不重读就会一直显示第一次进来时的旧值（和选区参数条那个「显示过期」同类问题）。
    androidx.compose.runtime.LaunchedEffect(tool) {
        val s = sketch ?: return@LaunchedEffect
        tolerance = s.fxTolerance
        sponge = s.spongeMode
        bgSample = s.bgSampleMode
        bgProtect = s.bgProtect
        bgLimit = s.bgLimit
        replaceSample = s.replaceSample
        mixer = s.mixerMix
        mixerFg = s.mixerFg
        patternIdx = s.patternIndex
        artStyle = s.artStyle
        artFidelity = s.artFidelity
        artRegion = s.artRegion
        toneProtect = s.toneProtect
        impression = s.patternImpressionist
        eraseToHistory = s.eraseToHistory
        redeyePupil = s.redeyePupil
        redeyeDarken = s.redeyeDarken
        magicCont = s.magicContinuous
    }

    PsToolOptionsBar(
        tool = tool,
        sketch = sketch,
        opacity = opacity,
        onOpacity = onOpacity,
        tolerance = tolerance,
        onTolerance = { tolerance = it },
        spongeMode = sponge,
        onSpongeMode = { sponge = it },
        bgSample = bgSample,
        onBgSample = { bgSample = it },
        bgProtect = bgProtect,
        onBgProtect = { bgProtect = it },
        bgLimit = bgLimit,
        onBgLimit = { bgLimit = it },
        replaceSample = replaceSample,
        onReplaceSample = { replaceSample = it },
        mixerMix = mixer,
        onMixerMix = { mixer = it },
        mixerFg = mixerFg,
        onMixerFg = { mixerFg = it },
        patternIdx = patternIdx,
        onPatternIdx = { patternIdx = it; sketch?.patternIndex = it },
        artStyle = artStyle,
        onArtStyle = { artStyle = it },
        artFidelity = artFidelity,
        onArtFidelity = { artFidelity = it },
        artRegion = artRegion,
        onArtRegion = { artRegion = it },
        toneProtect = toneProtect,
        onToneProtect = { toneProtect = it },
        patternImpression = impression,
        onPatternImpression = { impression = it },
        eraseToHistory = eraseToHistory,
        onEraseToHistory = { eraseToHistory = it },
        redeyePupil = redeyePupil,
        onRedeyePupil = { redeyePupil = it },
        redeyeDarken = redeyeDarken,
        onRedeyeDarken = { redeyeDarken = it },
        magicCont = magicCont,
        onMagicCont = { magicCont = it },
    )
}
