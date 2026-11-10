package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.image.SelectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新第一屏：进去就是画布。
 * 只有四样：最上方状态栏、画布、底部可左右滚的工具栏、左下角悬浮的模式切换窗。
 * 参数面板、图层面板、各种入口全部还没接（按用户要求「什么都不要加」）。
 */
@Composable
fun CanvasPage(projectId: String, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    var sketch by remember { mutableStateOf<SketchView?>(null) }
    var docName by remember { mutableStateOf("未命名画布") }
    var docInfo by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(NewMode.CUT) }
    var activeTool by remember { mutableStateOf<String?>(null) }
    var hasDoc by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var logTick by remember { mutableIntStateOf(0) }
    var railOpen by remember { mutableStateOf(true) }
    var showBlank by remember { mutableStateOf(false) }
    var showFile by remember { mutableStateOf(false) }
    var showParams by remember { mutableStateOf(false) }
    var layerTick by remember { mutableIntStateOf(0) }

    // 导入图片：选一张图，作为新图层放到画布上
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 读一次拍摄信息（镜头校正的「自动」要用）
        runCatching { com.reveal.editor.data.ExifInfo.read(context, uri) }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp == null) {
                toast("这张图读不出来")
            } else {
                val v = sketch
                if (v != null) {
                    val empty = v.layers.isEmpty() || v.activeLayer?.bitmap == null
                    if (empty) {
                        // 第一张图 = 打开图片（整份文档就是它）
                        v.openImageAsDocument(bmp)
                    } else {
                        // 之后导入 = 置入图片（作为新图层叠上去）
                        v.addImageLayer(bmp, "置入图片")
                    }
                    // 恢复这个工程之前存过的范围
                    runCatching {
                        val (regs, act) = com.reveal.editor.data.RegionStore.load(context, projectId)
                        if (regs.isNotEmpty()) v.setRegions(regs, act)
                    }
                    docInfo = "${bmp.width} × ${bmp.height}"
                    layerTick++
                }
            }
        }
    }

    // 导入 PSD：整份文档换成 PSD 里的图层
    val psdLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png"),
    ) { _ -> }
    val psdOpenLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = context.contentResolver.openInputStream(uri)?.use {
                        com.reveal.editor.image.Psd.openSmart(it, context.cacheDir)
                    } ?: error("PSD 读不出来（支持 8 位 RGB / 灰度）")
                    if (doc.layers.isEmpty()) error("这个 PSD 里没有可读图层")
                    val ls = doc.layers.map { pl ->
                        SketchView.Layer(pl.bitmap, pl.name).also { l ->
                            l.visible = pl.visible
                            l.alpha = pl.opacity
                            l.blendMode = pl.blendMode
                            l.clipped = pl.clipped
                            l.alphaLock = pl.alphaLock
                            l.mask = pl.mask
                        }
                    }
                    withContext(Dispatchers.Main) {
                        sketch?.replaceLayersWith(ls, doc.width, doc.height)
                        layerTick++
                    }
                    "已打开 PSD：${ls.size} 个图层"
                }.getOrElse { "打开失败：${it.message ?: it::class.java.simpleName}" }
            }
            toast(msg)
        }
    }

    // 导出 PSD
    val psdSaveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    // 用完整版导出（保留调整层参数、文字信息、样式块、16/32 位通道）
                    val view = sketch ?: error("画布是空的")
                    val bytes = com.reveal.editor.ui.buildPsdBytes(view, context, projectId)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("写不进这个位置")
                    "已导出 PSD（${bytes.size / 1024} KB）"
                }.getOrElse { "导出 PSD 失败：${it.message ?: it::class.java.simpleName}" }
            }
            toast(msg)
        }
    }

    // 导出：0 = PNG，1 = JPG
    var exportJpg by remember { mutableStateOf(false) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
            "image/png",
        ),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val full = sketch?.flatten() ?: error("画布是空的")
                    val fmt = if (exportJpg) {
                        com.reveal.editor.image.ExportFormat.JPEG
                    } else {
                        com.reveal.editor.image.ExportFormat.PNG
                    }
                    com.reveal.editor.image.ImageExporter.export(context, uri, full, fmt, 95)
                        .getOrThrow()
                    if (exportJpg) "已导出 JPG" else "已导出 PNG"
                }.getOrElse { "导出失败：${it.message ?: "未知错误"}" }
            }
            toast(msg)
        }
    }


    val layers = sketch?.layers ?: emptyList()


    Box(
        Modifier
            .fillMaxSize()
            .background(Rv.appBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            NewTopBar(
                onMenu = { showBlank = true },
                onUndo = { sketch?.undo() },
                onRedo = { sketch?.redo() },
                onFile = { showFile = true },
                onSave = {
                    val v = sketch ?: return@NewTopBar
                    val layers = collectSaveLayers(v)
                    scope.launch(Dispatchers.IO) {
                        ProjectStore.save(context, projectId, docName, layers, v.docDepth)
                    // 范围随工程保存（单独文件，不动图层包格式）
                    com.reveal.editor.data.RegionStore.save(context, projectId, v.regions, v.activeRegion)
                    }
                },
                onLayers = { showLayers = true; layerTick++ },
                onLog = { logTick++; showLog = true },
                onSearch = { showSearch = true },
            )

            if (showSearch) {
                ToolSearchSheet(
                    onPick = { id, m ->
                        showSearch = false
                        // 切到该工具所属模式，并把其它模式关掉（单一 activeTool）
                        mode = m
                        activeTool = id
                        showParams = true
                        applyTool(sketch, id, m)
                    },
                    onClose = { showSearch = false },
                )
            }

            // 画布：白底 + 引擎自己画的灰工作区和白纸
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SketchView(ctx).also { view ->
                            sketch = view
                            view.onHint = { }
                            view.onSelectionChanged = { }
                            view.onColorPicked = { c ->
                                view.brushColor = c
                            }
                        }
                    },
                )

                // 取点模式：整块画布上一层透明感应区，点一下把归一化坐标回传
                if (FilterPick.active) {
                    var pickTick by remember { mutableStateOf(0) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pickTick) {
                                detectTapGestures { off ->
                                    val fx = off.x / size.width.toFloat()
                                    val fy = off.y / size.height.toFloat()
                                    FilterPick.onPick?.invoke(fx, fy)
                                    pickTick++
                                }
                            },
                    ) {
                        Text(
                            "点一下要作为中心的画面位置",
                            fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                                .background(androidx.compose.ui.graphics.Color(0xB3000000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                // 模式栏：固定左侧中间，可收起
                ModeRailPinned(
                    current = mode,
                    onSelect = { m ->
                        mode = m
                        activeTool = toolsForMode(m).firstOrNull()?.id
                        applyTool(sketch, activeTool, m)
                    },
                    open = railOpen,
                    onToggle = { railOpen = it },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
                )
                SideArrow(
                    open = railOpen,
                    onToggle = { railOpen = it },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }

            // 顺序（自上而下）：画布 → 参数面板 → 子工具栏 → 主工具栏
            // 「滤镜」本身只是工具包：点它只展开子工具栏，不出参数面板；点子工具才出面板
            val inFilterGroup = activeTool == "fx.filters" || activeTool?.startsWith("fx.cfx.") == true
            // 镜头光晕面板里点「光晕工作室」→ 切到工作室工具并展开参数区
            com.reveal.editor.ui.FlareOpen.onOpen = {
                activeTool = "fx.cfx.flareStudio"
                showParams = true
            }
            if (showParams && activeTool != null && activeTool != "fx.filters") {
                ParamDock(
                    sketch = sketch,
                    toolId = activeTool,
                    mode = mode,
                )
            }
            if (inFilterGroup) {
                NewToolRow(
                    tools = com.reveal.editor.ui.FILTER_SUB_TOOLS,
                    activeId = if (activeTool?.startsWith("fx.cfx.") == true) activeTool else null,
                    compact = true,
                    onPick = { t ->
                        // 再点同一个子工具 = 收起参数面板（子工具栏仍在）
                        if (activeTool == t.id && showParams) {
                            showParams = false
                        } else {
                            activeTool = t.id
                            showParams = true
                        }
                    },
                )
            }

            // 子工具栏与主工具栏之间一条 1dp 分隔线
            if (inFilterGroup) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(com.reveal.editor.ui.Rv.line),
                )
            }

            // 底部工具栏：能左右滚
            NewToolRow(
                // 「范围」固定在最左、不参与滚动；竖线右边才是滚动区
                pinned = com.reveal.editor.ui.PINNED_TOOLS,
                tools = toolsForMode(mode),
                // 滤镜组内时，「滤镜」这颗保持蓝色（父级一直亮）
                activeId = when {
                    inFilterGroup -> "fx.filters"
                    showParams -> activeTool
                    else -> null
                },
                onPick = { t ->
                    if (t.id == "fx.filters") {
                        // 工具包：只展开/收起子工具栏，不碰参数面板
                        if (activeTool == "fx.filters" || activeTool?.startsWith("fx.cfx.") == true) {
                            activeTool = null
                            showParams = false
                        } else {
                            activeTool = "fx.filters"
                            showParams = false
                        }
                    } else if (t.id == activeTool && showParams) {
                        // 再点一次：收起面板，同时取消高亮
                        showParams = false
                        activeTool = null
                    } else {
                        activeTool = t.id
                        showParams = true
                        applyTool(sketch, t.id, mode)
                    }
                    // 点了工具就把左侧模式栏收起来，把画面让出来
                    if (t.id != "fx.filters") railOpen = false
                },
            )
        }
    }

        if (showLog) {
            key(logTick) {
                LogSheet(
                    diag = remember { com.reveal.editor.data.Diag.read(context) },
                    crash = remember { com.reveal.editor.CrashLog.read(context) },
                    onCopy = { t ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Reveal 日志", t))
                        toast("日志已复制")
                    },
                    onClearDiag = { com.reveal.editor.data.Diag.clear(context); logTick++ },
                    onClearCrash = { com.reveal.editor.CrashLog.clear(context); logTick++ },
                    onClose = { showLog = false },
                )
            }
        }

        // 图层面板（覆盖在最上层）
        if (showLayers) {
            androidx.compose.runtime.key(layerTick) {
            NewLayerSheet(
                layers = sketch?.layers ?: emptyList(),
                activeIndex = sketch?.activeIndex ?: 0,
                onPick = { i -> sketch?.selectLayer(i); layerTick++ },
                onToggleVisible = { i ->
                    sketch?.layers?.getOrNull(i)?.let { l ->
                        l.visible = !l.visible
                        sketch?.invalidate()
                        layerTick++
                    }
                },
                onAdd = { sketch?.addLayer(); layerTick++ },
                onDelete = { sketch?.deleteActiveLayer(); layerTick++ },
                onMoveTo = { from, to ->
                    sketch?.moveLayer(from, to)
                    sketch?.invalidate()
                    layerTick++
                },
                onDuplicate = { i -> sketch?.duplicateLayer(i); layerTick++ },
                onUngroup = { i -> sketch?.ungroupAt(i); layerTick++ },
                onRename = { i, name ->
                    if (name.isNotEmpty()) sketch?.layers?.getOrNull(i)?.name = name
                    sketch?.invalidate()
                    layerTick++
                },
                onMoveGroup = { i, up -> sketch?.moveGroupBlock(i, up); layerTick++ },
                onAlpha = { i, a ->
                    sketch?.layers?.getOrNull(i)?.let { l ->
                        l.alpha = (a * 255).toInt().coerceIn(0, 255)
                        sketch?.invalidate()
                    }
                },
                onGroup = { sketch?.groupLayersAt(listOf(sketch?.activeIndex ?: 0)); layerTick++ },
                onToggleGroup = { i ->
                    sketch?.layers?.getOrNull(i)?.let { l ->
                        l.sectionType = if (l.sectionType == 1) 2 else 1
                        sketch?.invalidate()
                        layerTick++
                    }
                },
                onPlace = {
                    showLayers = false
                    importLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onClose = { showLayers = false },
            )
            }
        }

    if (showFile) {
        Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable { showFile = false }) {
            Surface(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.82f),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 14.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("文件", fontSize = 13.sp, color = Rv.text)
                    Spacer(Modifier.height(10.dp))
                    DockButton("导入图片") { showFile = false; importLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    Spacer(Modifier.height(8.dp))
                    DockButton("导出 PNG") { showFile = false; exportJpg = false; exportLauncher.launch("reveal.png") }
                    Spacer(Modifier.height(8.dp))
                    DockButton("导出 JPG") { showFile = false; exportJpg = true; exportLauncher.launch("reveal.jpg") }
                    Spacer(Modifier.height(8.dp))
                    DockButton("导入 PSD") { showFile = false; psdOpenLauncher.launch("*/*") }
                    DockButton("导出 PSD") { showFile = false; psdSaveLauncher.launch("reveal.psd") }
                    Spacer(Modifier.height(14.dp))
                    Text("导出 PSD 时调整层会烘成像素", fontSize = 10.sp, color = Rv.textFaint)
                }
            }
        }
    }

    if (showBlank) {
        Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("这里以后放工程列表 / 新建 / 设置", fontSize = 12.sp, color = Rv.textSub)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Rv.accent)
                        .clickable { showBlank = false }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text("回到画布", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }

    // 打开文档：有工程就读工程，没有就开一张白纸
    LaunchedEffect(projectId, sketch) {
        val view = sketch ?: return@LaunchedEffect
        if (hasDoc) return@LaunchedEffect
        // 启动一律先给空白画布，不自动打开上次的工程
        val doc: com.reveal.editor.data.LoadedDocument? = null
        if (doc != null) {
            attachDocument(view, doc, doc.depth)
            docName = doc.name
            docInfo = "${doc.width} × ${doc.height}"
        } else {
            // 启动不建画布：等用户导入图片再按图片尺寸建
            docName = "未命名画布"
            docInfo = ""
        }
        hasDoc = true
        applyTool(view, activeTool ?: "sel.rect", mode)
    }
}

internal fun applyTool(view: SketchView?, id: String?, mode: NewMode) {
    val v = view ?: return
    when (id) {
        // ---- 绘制 ----
        "tool.brush" -> v.tool = SketchView.Tool.BRUSH
        "tool.pencil" -> {
            v.tool = SketchView.Tool.BRUSH
            v.brushType = SketchView.BRUSH_PENCIL
        }
        "tool.eraser" -> v.tool = SketchView.Tool.ERASER
        "tool.eraserBg" -> v.tool = SketchView.Tool.ERASER_BG
        "tool.eraserMagic" -> v.tool = SketchView.Tool.ERASER_MAGIC
        "tool.gradient" -> v.tool = SketchView.Tool.GRADIENT
        "tool.bucket" -> v.tool = SketchView.Tool.BUCKET
        "tool.replace" -> v.tool = SketchView.Tool.REPLACE
        "tool.mixer" -> v.tool = SketchView.Tool.MIXER
        "tool.artHistory" -> v.tool = SketchView.Tool.ART_HISTORY
        "tool.histBrush" -> v.tool = SketchView.Tool.HISTORY_BRUSH
        "tool.pen" -> v.tool = SketchView.Tool.PEN
        // ---- 修图 ----
        "tool.heal" -> v.tool = SketchView.Tool.HEAL
        "tool.spot" -> v.tool = SketchView.Tool.SPOT
        "tool.clone" -> v.tool = SketchView.Tool.CLONE
        "tool.pattern" -> v.tool = SketchView.Tool.PATTERN
        "tool.redeye" -> v.tool = SketchView.Tool.REDEYE
        "tool.dodge" -> v.tool = SketchView.Tool.DODGE
        "tool.burn" -> v.tool = SketchView.Tool.BURN
        "tool.sponge" -> v.tool = SketchView.Tool.SPONGE
        "tool.smudge" -> v.tool = SketchView.Tool.SMUDGE
        "cut.band" -> v.tool = SketchView.Tool.MOVE
        "tool.region" -> v.tool = SketchView.Tool.REGION
        "tool.liquify" -> v.tool = SketchView.Tool.LIQUIFY
        "tool.warp" -> v.tool = SketchView.Tool.WARP
        // ---- 排版 ----
        "tool.text" -> v.tool = SketchView.Tool.TEXT
        "tool.shape" -> v.tool = SketchView.Tool.SHAPE
        "tool.transform" -> v.tool = SketchView.Tool.TRANSFORM
        "act.flipH" -> v.flipDocument(horizontal = true)
        "act.rotate" -> v.rotateDocument(90)
        "act.crop" -> v.tool = SketchView.Tool.MOVE
        // ---- 抠图 ----
        "sel.rect" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.RECT }
        "sel.ellipse" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.ELLIPSE }
        "sel.lasso" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.LASSO }
        "sel.poly" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.POLY }
        "sel.magnetic" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.MAGNETIC }
        "sel.wand" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.WAND }
        "sel.quick" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.QUICK }
        "sel.subject" -> { v.tool = SketchView.Tool.SELECT; v.selectMode = SelectMode.SUBJECT }
        // 非画笔类入口（色阶/曲线/滤镜/动作菜单…）：把引擎切到「只平移」，画布上就不会再画线、也不再拉选区
        else -> v.tool = SketchView.Tool.MOVE
    }
}

/**
 * 工具参数区：固定高度（72dp）的一条白色浮层，压在画布底部。
 * 不改变画布尺寸，收起时画布也是这么大。
 */
@Composable
private fun ParamDock(
    sketch: SketchView?,
    toolId: String?,
    mode: NewMode,
    modifier: Modifier = Modifier,
) {
    // 参数面板上限：屏幕一半先扣掉子工具栏(54)+主工具栏(68)+边距
    val panelMaxDp = ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2) - 150)
        .coerceIn(180, 380)
    if (mode == NewMode.CUT && toolId == "cut.band") {
        BandCutDock(sketch, modifier)
        return
    }
    if (mode == NewMode.CUT) {
        CutDock(sketch = sketch, toolId = toolId, modifier = modifier)
        return
    }
    // 文字：内嵌原文字面板，确认时把文字渲染成新图层
    if (toolId == "tool.text") {
        var txt by remember { mutableStateOf("") }
        var size by remember { mutableStateOf(72f) }
        var color by remember { mutableStateOf(0xFF22262B.toInt()) }
        var font by remember { mutableStateOf("") }
        var spec by remember { mutableStateOf(TextSpec()) }
        var pickColor by remember { mutableStateOf(false) }
        Column(
            modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Box(Modifier.heightIn(max = 360.dp)) {
                com.reveal.editor.ui.TextPanel(
                    editing = false,
                    value = txt,
                    onValue = { txt = it },
                    size = size,
                    onSize = { size = it },
                    color = color,
                    onPickColor = { pickColor = true },
                    font = font,
                    onFont = { font = it },
                    spec = spec,
                    onSpec = { spec = it },
                    onCancel = { /* 面板常驻，取消只清内容 */ txt = "" },
                    onConfirm = {
                        if (txt.isNotBlank()) {
                            val bmp = sketch?.flatten()
                            val cx = (bmp?.width ?: 1080) / 2f
                            val cy = (bmp?.height ?: 1620) / 2f
                            sketch?.addTextLayer(txt, size, color, cx, cy, font)
                            sketch?.onActiveLayerChanged?.invoke()
                        }
                    },
                )
            }
            if (pickColor) {
                com.reveal.editor.ui.ColorPickerPanel(
                    initial = color,
                    onColorChange = { color = it },
                    onCancel = { pickColor = false },
                    onConfirm = { color = it; pickColor = false },
                )
            }
        }
        return
    }
    // 图像大小：直接改文档尺寸
    if (toolId == "act.resize") {
        var w by remember { mutableStateOf(0f) }
        var h by remember { mutableStateOf(0f) }
        val docW = sketch?.docPixelWidth() ?: 0
        val docH = sketch?.docPixelHeight() ?: 0
        LaunchedEffect(docW, docH) { if (w == 0f && docW > 0) { w = docW.toFloat(); h = docH.toFloat() } }
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .heightIn(max = 420.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("图像大小", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
                Text("改的是整份文档（所有图层一起缩放），不是只改当前层", fontSize = 10.sp, color = Rv.textSub)
                Spacer(Modifier.height(6.dp))
                LevelRow("宽度", w, 16f..8192f) { w = it }
                LevelRow("高度", h, 16f..8192f) { h = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DockButton("应用") {
                        if (w >= 16f && h >= 16f) {
                            sketch?.resizeDocument(w.toInt(), h.toInt())
                            sketch?.onActiveLayerChanged?.invoke()
                        }
                    }
                    DockButton("还原") { w = docW.toFloat(); h = docH.toFloat() }
                }
            }
        }
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.levels") {
        com.reveal.editor.ui.LevelsDock2(sketch = sketch, modifier = modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.curves") {
        com.reveal.editor.ui.CurvesDock(sketch = sketch, modifier = modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.hsl") {
        FxHueSatDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.bc") {
        FxBrightContrastDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && (toolId == "fx.hsl" || toolId == "fx.bc")) {
        AdjustDock(sketch = sketch, hueSat = toolId == "fx.hsl", modifier = modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.bw") {
        FxBlackWhiteDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.exposure") {
        com.reveal.editor.ui.FxExposureDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.balance") {
        com.reveal.editor.ui.FxColorBalanceDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.posterize") {
        com.reveal.editor.ui.FxPosterizeDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && toolId == "fx.vibrance") {
        FxVibranceDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR && (toolId == "fx.photoFilter" || toolId == "fx.filter")) {
        FxPhotoFilterDock(sketch, modifier)
        return
    }
    // 「滤镜」工具：不占参数区，子滤镜在工具行上方展开（面板由下面的分支负责）
    val cfxId = (toolId ?: "").removePrefix("fx.cfx.")
    if (mode == NewMode.COLOR && cfxId != (toolId ?: "")) {
        // 「光晕工作室」是一套自己的编辑器，不走通用滤镜参数卡
        if (cfxId == "flareStudio") {
            FlareStudioDock(sketch, modifier, maxHeightDp = panelMaxDp)
            return
        }
        // 和别的调色面板一致：拖参数实时预览，「应用」才落层
        FilterDock(
            sketch, cfxId, maxHeightDp = panelMaxDp, modifier = modifier,
            onOpenFlareStudio = if (cfxId == "lensFlare") {
                { com.reveal.editor.ui.FlareOpen.request() }
            } else null,
        )
        return
    }
    // 液化 / 变形 / 图案图章：各自的专属参数（不再和画笔共用一套）
    if (toolId == "tool.region") {
        RegionDock(sketch, modifier)
        return
    }
    if (toolId == "tool.liquify") {
        LiquifyDock(sketch, modifier)
        return
    }
    if (toolId == "tool.warp") {
        WarpDock(sketch, modifier)
        return
    }
    if (toolId == "tool.pattern") {
        PatternDock(sketch, modifier)
        return
    }
    if (mode == NewMode.COLOR) {
        val fxId = when (toolId) {
            "fx.exposure" -> "exposure"
            "fx.vibrance" -> "vibrance"
            "fx.balance" -> "colorBalance"
            "fx.bw" -> "blackWhite"
            "fx.filter" -> "photoFilter"
            "fx.posterize" -> "posterize"
            "fx.gradientMap" -> "gradientMap"
            else -> null
        }
        if (fxId != null && com.reveal.editor.ui.fxSpec(fxId) != null) {
            Box(
                modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .heightIn(max = 420.dp),
            ) {
                com.reveal.editor.ui.ColorFxDialog(fxId, sketch) { }
            }
            return
        }
        // 注意：这里不能 return —— 调色模式里还有「范围」等工具要靠后面的分支出面板
    }
    // 形状：直接用原来的形状参数条（宽高/长度/角度/圆角/边数/填充…）
    if (toolId == "tool.shape") {
        Box(
            modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .heightIn(max = 420.dp),
        ) { com.reveal.editor.ui.ShapeToolBar(sketch) }
        return
    }
    // 渐变：用原来的渐变参数（线性/径向、两端取色、平滑度、缩放、反向、只画范围）
    if (toolId == "tool.gradient") {
        Box(
            modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .heightIn(max = 420.dp),
        ) {
            com.reveal.editor.ui.GradientSection(sketch, SketchView.Tool.GRADIENT, 0)
        }
        return
    }
    if (mode == NewMode.LAYOUT || mode == NewMode.AI) {
        EmptyDock(mode = mode, modifier = modifier)
        return
    }
    // 只有画笔/橡皮用得到大小；其余工具先共用这两个值，后面按工具细分
    var size by remember { mutableStateOf(sketch?.brushSize ?: 16f) }
    var alpha by remember { mutableStateOf(sketch?.brushOpacity ?: 1f) }
    var flow by remember { mutableStateOf(sketch?.brushFlow ?: 1f) }
    var hard by remember { mutableStateOf(sketch?.brushHardness ?: 1f) }
    LaunchedEffect(sketch) {
        size = sketch?.brushSize ?: size
        alpha = sketch?.brushOpacity ?: alpha
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ParamCell(
                label = "大小",
                value = "${size.roundToInt()}",
                frac = (size / 200f).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f),
                onChange = { f ->
                    size = (f * 200f).coerceIn(1f, 200f)
                    sketch?.brushSize = size
                    sketch?.eraserSize = size
                },
            )
            Spacer(Modifier.width(18.dp))
            ParamCell(
                label = "不透明",
                value = "${(alpha * 100).roundToInt()}%",
                frac = alpha,
                modifier = Modifier.weight(1f),
                onChange = { f ->
                    alpha = f
                    sketch?.brushOpacity = f
                    sketch?.eraserOpacity = f
                },
            )
            Spacer(Modifier.width(18.dp))
            ParamCell(
                label = "流量",
                value = "${(flow * 100).roundToInt()}%",
                frac = flow,
                modifier = Modifier.weight(1f),
                onChange = { f ->
                    flow = f
                    sketch?.brushFlow = f
                },
            )
            Spacer(Modifier.width(18.dp))
            ParamCell(
                label = "硬度",
                value = "${(hard * 100).roundToInt()}%",
                frac = hard,
                modifier = Modifier.weight(1f),
                onChange = { f ->
                    hard = f
                    sketch?.brushHardness = f
                },
            )
        }
    }
}

@Composable
private fun ParamCell(
    label: String,
    value: String,
    frac: Float,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.5.sp, color = Rv.textSub, modifier = Modifier.weight(1f))
            Text(value, fontSize = 10.5.sp, color = Rv.text)
        }
        Spacer(Modifier.height(2.dp))
        ThinSlider(
            value = frac.coerceIn(0f, 1f),
            range = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            onChange = onChange,
        )
    }
}

/** 抠图模式的参数区：容差 / 羽化 + 四个选区动作 */
@Composable
private fun CutDock(sketch: SketchView?, toolId: String?, modifier: Modifier = Modifier) {
    // 引擎里的 wandTolerance 是 0~1，界面按 0~100 显示
    var tol by remember { mutableStateOf(((sketch?.wandTolerance ?: 0.12f) * 100f).coerceIn(0f, 100f)) }
    var feather by remember { mutableStateOf(0f) }
    var selOp by remember { mutableStateOf(sketch?.selectOp ?: com.reveal.editor.image.SelectOp.NEW) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
                ParamCell(
                    label = "容差",
                    value = "${tol.roundToInt()}",
                    frac = (tol / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    onChange = { f ->
                        tol = f * 100f
                        sketch?.wandTolerance = (tol / 100f).coerceIn(0f, 1f)
                    },
                )
                Spacer(Modifier.width(16.dp))
                ParamCell(
                    label = "羽化",
                    value = "${feather.roundToInt()}",
                    frac = (feather / 50f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    onChange = { f -> feather = f * 50f },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 选区运算：新建 / 加选 / 减选 / 交叉
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabChip("新建", selOp == com.reveal.editor.image.SelectOp.NEW) {
                    selOp = com.reveal.editor.image.SelectOp.NEW
                    sketch?.selectOp = selOp
                }
                TabChip("加选", selOp == com.reveal.editor.image.SelectOp.ADD) {
                    selOp = com.reveal.editor.image.SelectOp.ADD
                    sketch?.selectOp = selOp
                }
                TabChip("减选", selOp == com.reveal.editor.image.SelectOp.SUB) {
                    selOp = com.reveal.editor.image.SelectOp.SUB
                    sketch?.selectOp = selOp
                }
                TabChip("交叉", selOp == com.reveal.editor.image.SelectOp.INTERSECT) {
                    selOp = com.reveal.editor.image.SelectOp.INTERSECT
                    sketch?.selectOp = selOp
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("羽化") { if (feather >= 1f) sketch?.modifySelection(0, feather.roundToInt()) }
                DockButton("平滑") { if (feather >= 1f) sketch?.modifySelection(1, feather.roundToInt()) }
                DockButton("扩张") { if (feather >= 1f) sketch?.modifySelection(2, feather.roundToInt()) }
                DockButton("收缩") { if (feather >= 1f) sketch?.modifySelection(3, feather.roundToInt()) }
                DockButton("边界") { if (feather >= 1f) sketch?.modifySelection(4, feather.roundToInt()) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("全选") { sketch?.selectAll() }
                DockButton("反选") { sketch?.invertSelection() }
                DockButton("取消") { sketch?.clearSelectionUndoable() }
                DockButton("到新层") { sketch?.selectionToNewLayer() }
                DockButton("选主体") { sketch?.selectSubject() }
            }
        }
    }
}

@Composable
internal fun DockButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Rv.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Rv.text)
    }
}

/** 还没接面板的模式：先给同样的卡片外壳，写清这里是空的 */
@Composable
private fun EmptyDock(mode: NewMode, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                when (mode) {
                    NewMode.COLOR -> "调色模式：参数面板还没接（下一步做）"
                    NewMode.LAYOUT -> "排版模式：参数面板还没接（下一步做）"
                    NewMode.AI -> "AI 模式：还没接入"
                    else -> "参数面板还没接"
                },
                fontSize = 11.sp,
                color = Rv.textFaint,
            )
        }
    }
}

/** 调色模式：曝光 / 自然饱和度，点应用才作用到当前层（可撤销） */
@Composable
private fun ColorDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var ev by remember { mutableStateOf(0f) }
    var vib by remember { mutableStateOf(0f) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
                ParamCell(
                    label = "曝光",
                    value = String.format("%.1f", ev),
                    frac = ((ev + 5f) / 10f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    onChange = { f -> ev = f * 10f - 5f },
                )
                Spacer(Modifier.width(16.dp))
                ParamCell(
                    label = "饱和度",
                    value = "${vib.roundToInt()}",
                    frac = ((vib + 100f) / 200f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    onChange = { f -> vib = f * 200f - 100f },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用曝光") {
                    if (kotlin.math.abs(ev) > 0.05f) {
                        val v = ev
                        sketch?.applyFilterToActive({ bmp -> com.reveal.editor.image.ColorFx.exposure(bmp, v) }, "曝光度")
                    }
                }
                DockButton("应用饱和度") {
                    if (kotlin.math.abs(vib) > 0.5f) {
                        val v = vib.roundToInt()
                        sketch?.applyFilterToActive({ bmp -> com.reveal.editor.image.ColorFx.vibrance(bmp, v) }, "自然饱和度")
                    }
                }
                DockButton("归零") { ev = 0f; vib = 0f }
            }
        }
    }
}

/** 色相/饱和度 与 亮度/对比度：滑块 + 应用，走 AdjustOp + AdjustApply */
@Composable
private fun AdjustDock(sketch: SketchView?, hueSat: Boolean, modifier: Modifier = Modifier) {
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) {
        previewBase(sketch?.flatten())
    }
    var a by remember { mutableStateOf(0f) }
    var b by remember { mutableStateOf(0f) }
    var c by remember { mutableStateOf(0f) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
            if (hueSat) {
                LevelRow("色相", a, -180f..180f) { a = it }
                LevelRow("饱和度", b, -100f..100f) { b = it }
                LevelRow("明度", c, -100f..100f) { c = it }
            } else {
                LevelRow("亮度", a, -100f..100f) { a = it }
                LevelRow("对比度", b, -100f..100f) { b = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    sketch?.clearPreview()
                    val op = if (hueSat) {
                        com.reveal.editor.image.AdjustOp(
                            kind = com.reveal.editor.image.AdjustOp.KIND_HUE_SAT,
                            hue = a.roundToInt(),
                            saturation = b.roundToInt(),
                            lightness = c.roundToInt(),
                        )
                    } else {
                        com.reveal.editor.image.AdjustOp(
                            kind = com.reveal.editor.image.AdjustOp.KIND_BRIGHT_CONTRAST,
                            brightness = a.roundToInt(),
                            contrast = b.roundToInt(),
                        )
                    }
                    sketch?.applyFilterToActive({ bmp ->
                        val out = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        com.reveal.editor.image.AdjustApply.applyInPlace(out, op)
                        out
                    }, if (hueSat) "色相/饱和度" else "亮度/对比度")
                }
                DockButton("取消") { a = 0f; b = 0f; c = 0f; sketch?.clearPreview() }
            }
        }
    }

    LaunchedEffect(a, b, c, hueSat, base) {
        val bmp = base ?: return@LaunchedEffect
        val op = if (hueSat) {
            com.reveal.editor.image.AdjustOp(
                kind = com.reveal.editor.image.AdjustOp.KIND_HUE_SAT,
                hue = a.roundToInt(),
                saturation = b.roundToInt(),
                lightness = c.roundToInt(),
            )
        } else {
            com.reveal.editor.image.AdjustOp(
                kind = com.reveal.editor.image.AdjustOp.KIND_BRIGHT_CONTRAST,
                brightness = a.roundToInt(),
                contrast = b.roundToInt(),
            )
        }
        val out = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        com.reveal.editor.image.AdjustApply.applyInPlace(out, op)
        sketch?.setPreview(out)
        sketch?.invalidate()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { sketch?.clearPreview() }
    }
}

/** 色阶：直方图 + 5 个参数（每个参数单独一行，便于微调）+ 实时预览 */
@Composable
private fun LevelsDockOld(sketch: SketchView?, modifier: Modifier = Modifier) {
    var inB by remember { mutableStateOf(0f) }
    var gamma by remember { mutableStateOf(1f) }
    var inW by remember { mutableStateOf(255f) }
    var outB by remember { mutableStateOf(0f) }
    var outW by remember { mutableStateOf(255f) }

    // 打开面板时留一张底图：预览在它上面算，不动真实图层
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) {
        // 用整幅合成图当底图：既做预览底，也算直方图
        previewBase(sketch?.flatten())
    }
    // 直方图：公用函数（缩小后统计，稳定不越界）
    val hist = remember(base) { luminanceHistogram(base) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            // 直方图
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rv.surfaceAlt),
            ) {
                val maxV = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
                val bw = size.width / 256f
                for (i in 0 until 256) {
                    val v = hist[i].toFloat() / maxV
                    if (v <= 0f) continue
                    val hgt = v * size.height
                    drawRect(
                        color = if (i < inB || i > inW) androidx.compose.ui.graphics.Color(0xFFB9BFC8)
                        else Rv.text.copy(alpha = 0.55f),
                        topLeft = androidx.compose.ui.geometry.Offset(i * bw, size.height - hgt),
                        size = androidx.compose.ui.geometry.Size(bw.coerceAtLeast(1f), hgt),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            LevelRow("输入黑场", inB, 0f..254f) { inB = it }
            LevelRow("伽马", gamma, 0.1f..3f, fmt = "%.2f") { gamma = it }
            LevelRow("输入白场", inW, 1f..255f) { inW = it }
            LevelRow("输出黑场", outB, 0f..255f) { outB = it }
            LevelRow("输出白场", outW, 0f..255f) { outW = it }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    val p = com.reveal.editor.image.Levels.Params(
                        inBlack = inB.roundToInt().coerceIn(0, 254),
                        gamma = gamma.coerceIn(0.1f, 3f),
                        inWhite = inW.roundToInt().coerceIn(1, 255),
                        outBlack = outB.roundToInt().coerceIn(0, 255),
                        outWhite = outW.roundToInt().coerceIn(0, 255),
                    )
                    sketch?.clearPreview()
                    sketch?.applyFilterToActive({ bmp -> com.reveal.editor.image.Levels.apply(bmp, p) ?: bmp }, "色阶")
                }
                DockButton("取消") {
                    sketch?.clearPreview()
                    inB = 0f; gamma = 1f; inW = 255f; outB = 0f; outW = 255f
                }
            }
        }
    }

    // 实时预览：参数一变就在底图副本上算一次，贴到预览层
    LaunchedEffect(inB, gamma, inW, outW, outB, base) {
        val bmp = base ?: return@LaunchedEffect
        kotlinx.coroutines.delay(16)
        val p = com.reveal.editor.image.Levels.Params(
            inBlack = inB.roundToInt().coerceIn(0, 254),
            gamma = gamma.coerceIn(0.1f, 3f),
            inWhite = inW.roundToInt().coerceIn(1, 255),
            outBlack = outB.roundToInt().coerceIn(0, 255),
            outWhite = outW.roundToInt().coerceIn(0, 255),
        )
        val out = com.reveal.editor.image.Levels.apply(bmp, p) ?: bmp
        sketch?.setPreview(out)
        sketch?.invalidate()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { sketch?.clearPreview() }
    }
}

/** 一行一个参数：标签 56dp + 滑块 + 数值 44dp（比三列并排更好微调） */

/** 照片滤镜：滤镜色用完整取色面板（方形色板 + 色带），浓度实时预览 */
@Composable
private fun FxPhotoFilterDock(sketch: SketchView?, modifier: Modifier) {
    var color by remember { mutableStateOf(0xFFEC8A3C.toInt()) }
    var density by remember { mutableStateOf(25f) }
    var pick by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    // 常驻循环 + 后台线程：LaunchedEffect(color, density) 会在每帧被取消重开，表现就是抬手才变
    val pfKey = androidx.compose.runtime.rememberUpdatedState(Triple(color, density.roundToInt(), base))
    LaunchedEffect(Unit) {
        var built: Any? = null
        while (true) {
            val cur = pfKey.value
            if (cur != built && cur.third != null) {
                built = cur
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        com.reveal.editor.image.ColorFx.photoFilterMatrix(cur.third!!, cur.first, cur.second)
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            kotlinx.coroutines.delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
            Text("照片滤镜", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("滤镜色", fontSize = 11.sp, color = Rv.textSub)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(color))
                        .border(1.dp, Rv.line, RoundedCornerShape(9.dp))
                        .clickable { pick = true },
                )
                Spacer(Modifier.width(10.dp))
                Text("点色块取色", fontSize = 10.sp, color = Rv.textFaint)
            }
            Spacer(Modifier.height(6.dp))
            LevelRow("浓度", density, 0f..100f, "%.0f%%") { density = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    val c = color
                    val d = density.roundToInt()
                    sketch?.clearPreview()
                    sketch?.applyFilterToActive({ bmp -> com.reveal.editor.image.ColorFx.photoFilter(bmp, c, d) }, "照片滤镜")
                    tick++
                }
                DockButton("取消") { sketch?.clearPreview(); tick++ }
            }
        }
    }

    if (pick) {
        ColorPickerPanel(
            initial = color,
            onColorChange = { color = it },
            onCancel = { pick = false },
            onConfirm = { color = it; pick = false },
        )
    }
}

/**
 * 自然饱和度：两个滑块（自然饱和度 / 饱和度）+ 保护肤色，跟手预览。
 * 算法见 `image/Vibrance.kt`——低饱和加得多、高饱和几乎不动，肤色打三折。
 */
@Composable
private fun FxVibranceDock(sketch: SketchView?, modifier: Modifier) {
    var vib by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(0f) }
    var protect by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    // 常驻泵 + 后台线程（LaunchedEffect(参数) 每帧被取消 → 抬手才变；主线程算 → 卡）
    val vibKey = androidx.compose.runtime.rememberUpdatedState(
        listOf(vib.roundToInt(), sat.roundToInt(), if (protect) 1 else 0) to base
    )
    LaunchedEffect(Unit) {
        var built: Any? = null
        while (true) {
            val cur = vibKey.value
            val bmp = cur.second
            if (cur != built && bmp != null) {
                built = cur
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        com.reveal.editor.image.Vibrance.applyLut(
                            bmp,
                            (cur.first[0] as Int),
                            (cur.first[1] as Int),
                            (cur.first[2] as Int) == 1,
                        )
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            kotlinx.coroutines.delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
            Text("自然饱和度", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            Text(
                "只往不够鲜的地方加，已经鲜艳的地方几乎不动；保护肤色开启时橙红一带权重再打三折",
                fontSize = 10.sp,
                color = Rv.textSub,
            )
            Spacer(Modifier.height(8.dp))
            LevelRow("自然饱和度", vib, -100f..100f) { vib = it }
            LevelRow("饱和度", sat, -100f..100f) { sat = it }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip(if (protect) "保护肤色：开" else "保护肤色：关", protect) { protect = !protect }
                Spacer(Modifier.width(10.dp))
                Text("肤色不容易被抽红/发灰", fontSize = 10.sp, color = Rv.textFaint)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    val v = vib.roundToInt()
                    val s2 = sat.roundToInt()
                    val pr = protect
                    sketch?.clearPreview()
                    sketch?.applyFilterToActive({ bmp ->
                        com.reveal.editor.image.Vibrance.apply(bmp, v, s2, pr)
                    }, "自然饱和度")
                    tick++
                }
                DockButton("归零") { vib = 0f; sat = 0f }
                DockButton("取消") { sketch?.clearPreview(); tick++ }
            }
        }
    }
}

/**
 * 色相/饱和度（完整版，按 桌面端面板）：全图 + 六个色相带，每带三参数，另有「着色」。
 * 算法见 `image/HueSat.kt`；参数一变立刻重算预览。
 */
@Composable
private fun FxHueSatDock(sketch: SketchView?, modifier: Modifier) {
    var channel by remember { mutableStateOf(0) } // 0=全图，1..6=红黄绿青蓝洋红
    var masterHue by remember { mutableStateOf(0f) }
    var masterSat by remember { mutableStateOf(0f) }
    var masterLight by remember { mutableStateOf(0f) }
    val bands = remember { androidx.compose.runtime.mutableStateListOf(*Array(6) { com.reveal.editor.image.HueSat.Band() }) }
    var colorize by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    val cur = if (channel == 0) null else bands[channel - 1]

    val snapKey = rememberUpdatedState(
        listOf(channel, colorize ?: false, masterHue, masterSat, masterLight) to bands.map { Triple(it.hue, it.sat, it.light) }
    )
    LaunchedEffect(Unit) {
        val b = base ?: return@LaunchedEffect
        var built: Any? = null
        while (true) {
            val snap = snapKey.value
            if (snap != built) {
                built = snap
                val head = snap.first
                val bl = snap.second
                val ch = (head[0] as Int)
                val cz = head[1] as Boolean
                val out = withContext(Dispatchers.Default) {
                    runCatching {
                        if (ch == 0) {
                            // 全图通道且没有动任何色相带 → 走颜色矩阵（GPU，跟手）
                            if (bl.all { it.first == 0 && it.second == 0 && it.third == 0 } && !cz) {
                                return@runCatching com.reveal.editor.image.HueSat.applyMatrix(
                                    b,
                                    hue = (head[2] as Float).roundToInt(),
                                    sat = (head[3] as Float).roundToInt(),
                                    light = (head[4] as Float).roundToInt(),
                                )
                            }
                            com.reveal.editor.image.HueSat.apply(
                                b,
                                hue = (head[2] as Float).roundToInt(),
                                sat = (head[3] as Float).roundToInt(),
                                light = (head[4] as Float).roundToInt(),
                                bands = bl.map { com.reveal.editor.image.HueSat.Band(it.first, it.second, it.third) },
                                colorize = cz,
                            )
                        } else {
                            val one = bl[ch - 1]
                            val zeros = List(6) { com.reveal.editor.image.HueSat.Band() }
                            val bandList = zeros.toMutableList()
                            bandList[ch - 1] = com.reveal.editor.image.HueSat.Band(one.first, one.second, one.third)
                            // 3D LUT 快路径：逐像素 HSV 太慢（每像素两次 JNI），拖动会明显掉帧
                            com.reveal.editor.image.HueSat.applyBandsLut(b, bandList)
                        }
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            kotlinx.coroutines.delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
            Text("色相 / 饱和度", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            Text(
                "全图调所有颜色；下面的红黄绿青蓝洋红只作用在对应色相带（边界平滑过渡）。「着色」把整幅图做成单色再赋色。",
                fontSize = 10.sp,
                color = Rv.textSub,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabChip("全图", channel == 0) { channel = 0 }
                com.reveal.editor.image.HueSat.BAND_NAMES.forEachIndexed { i, name ->
                    TabChip(name, channel == i + 1) { channel = i + 1 }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (cur == null) {
                LevelRow("色相", masterHue, -180f..180f) { masterHue = it }
                LevelRow("饱和度", masterSat, -100f..100f) { masterSat = it }
                LevelRow("明度", masterLight, -100f..100f) { masterLight = it }
            } else {
                LevelRow("色相", cur.hue.toFloat(), -180f..180f) {
                    bands[channel - 1] = cur.copy(hue = it.roundToInt())
                }
                LevelRow("饱和度", cur.sat.toFloat(), -100f..100f) {
                    bands[channel - 1] = cur.copy(sat = it.roundToInt())
                }
                LevelRow("明度", cur.light.toFloat(), -100f..100f) {
                    bands[channel - 1] = cur.copy(light = it.roundToInt())
                }
                Text(
                    "这个通道只影响「${com.reveal.editor.image.HueSat.BAND_NAMES[channel - 1]}」一带的颜色，其它颜色不动",
                    fontSize = 10.sp,
                    color = Rv.textFaint,
                )
            }
            Spacer(Modifier.height(6.dp))
            TabChip(if (colorize) "着色：开" else "着色：关", colorize) { colorize = !colorize }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    val ch = channel
                    val bs = bands.map { com.reveal.editor.image.HueSat.Band(it.hue, it.sat, it.light) }
                    val cz = colorize
                    sketch?.clearPreview()
                    sketch?.applyFilterToActive({ bmp ->
                        val c = if (ch == 0) null else bs[ch - 1]
                        com.reveal.editor.image.HueSat.apply(
                            bmp,
                            hue = c?.hue ?: 0,
                            sat = c?.sat ?: 0,
                            light = c?.light ?: 0,
                            bands = if (ch == 0) bs else emptyList(),
                            colorize = cz,
                        )
                    }, "色相/饱和度")
                    tick++
                }
                DockButton(if (channel == 0) "重置全图" else "重置本通道") {
                    if (channel == 0) {
                        // 全图：把三个主参数清零
                        masterHue = 0f
                        masterSat = 0f
                        masterLight = 0f
                    } else {
                        // 色相带：把这一带的三项清零（写回列表才会触发重组）
                        bands[channel - 1] = com.reveal.editor.image.HueSat.Band()
                    }
                }
                DockButton("取消") { sketch?.clearPreview(); tick++ }
            }
        }
    }
}

/** 亮度/对比度（补了高光、阴影，桌面端里这四项是一组） */
@Composable
private fun FxBrightContrastDock(sketch: SketchView?, modifier: Modifier) {
    var bright by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var highlight by remember { mutableStateOf(0f) }
    var shadow by remember { mutableStateOf(0f) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    // 常驻泵 + 后台线程 + 3D LUT：原来主线程逐像素跑，拖滑块时主线程被占住 → 参数条不跟手
    val bcKey = androidx.compose.runtime.rememberUpdatedState(
        listOf(bright.roundToInt(), contrast.roundToInt(), highlight.roundToInt(), shadow.roundToInt()) to base
    )
    LaunchedEffect(Unit) {
        var built: Any? = null
        while (true) {
            val cur = bcKey.value
            val bmp = cur.second
            if (cur != built && bmp != null) {
                built = cur
                val v = cur.first
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        com.reveal.editor.image.Tone.applyLut(bmp, v[0], v[1], v[2], v[3])
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            kotlinx.coroutines.delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
            Text("亮度 / 对比度", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            Text(
                "亮度整体抬降；对比度以中灰为轴拉开或压平；高光 / 阴影只动亮部与暗部，中间调基本不动。",
                fontSize = 10.sp,
                color = Rv.textSub,
            )
            Spacer(Modifier.height(8.dp))
            LevelRow("亮度", bright, -100f..100f) { bright = it }
            LevelRow("对比度", contrast, -100f..100f) { contrast = it }
            LevelRow("高光", highlight, -100f..100f) { highlight = it }
            LevelRow("阴影", shadow, -100f..100f) { shadow = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    val br = bright.roundToInt(); val ct = contrast.roundToInt()
                    val hi = highlight.roundToInt(); val sh = shadow.roundToInt()
                    sketch?.clearPreview()
                    sketch?.applyFilterToActive({ bmp ->
                        com.reveal.editor.image.Tone.apply(bmp, br, ct, hi, sh)
                    }, "亮度/对比度")
                    tick++
                }
                DockButton("归零") { bright = 0f; contrast = 0f; highlight = 0f; shadow = 0f }
                DockButton("取消") { sketch?.clearPreview(); tick++ }
            }
        }
    }
}

/**
 * 黑白：红/绿/蓝三个权重决定亮度占比，参数一动就预览；带「重置」回 30/59/11。
 */
@Composable
private fun FxBlackWhiteDock(sketch: SketchView?, modifier: Modifier) {
    var wr by remember { mutableStateOf(30f) }
    var wg by remember { mutableStateOf(59f) }
    var wb by remember { mutableStateOf(11f) }
    var tick by remember { mutableStateOf(0) }
    val base = remember(sketch?.activeIndex, tick) { previewBase(sketch?.flatten()) }

    val snap = rememberUpdatedState(Triple(wr, wg, wb))
    LaunchedEffect(Unit) {
        val b = base ?: return@LaunchedEffect
        var built: Triple<Float, Float, Float>? = null
        while (true) {
            val cur = snap.value
            if (cur != built) {
                built = cur
                val out = withContext(Dispatchers.Default) {
                    runCatching {
                        com.reveal.editor.image.ColorFx.blackWhite(
                            b,
                            cur.first.roundToInt(),
                            cur.second.roundToInt(),
                            cur.third.roundToInt(),
                        )
                    }.getOrNull()
                }
                if (out != null) {
                    sketch?.setPreview(out)
                    sketch?.invalidate()
                }
            }
            kotlinx.coroutines.delay(12)
        }
    }
    DisposableEffect(Unit) { onDispose { sketch?.clearPreview() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(max = 420.dp),
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
            Text("黑白", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            Text("三个权重决定红绿蓝各占多少亮度；三个都是 0 时按等权算。", fontSize = 10.sp, color = Rv.textSub)
            Spacer(Modifier.height(8.dp))
            LevelRow("红", wr, 0f..300f) { wr = it }
            LevelRow("绿", wg, 0f..300f) { wg = it }
            LevelRow("蓝", wb, 0f..300f) { wb = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockButton("应用") {
                    val r0 = wr.roundToInt()
                    val g0 = wg.roundToInt()
                    val b0 = wb.roundToInt()
                    sketch?.clearPreview()
                    sketch?.applyFilterToActive({ bmp ->
                        com.reveal.editor.image.ColorFx.blackWhite(bmp, r0, g0, b0)
                    }, "黑白")
                    tick++
                }
                DockButton("重置") { wr = 30f; wg = 59f; wb = 11f }
                DockButton("取消") { sketch?.clearPreview(); tick++ }
            }
        }
    }
}

/** 液化：大小 / 力度 / 模式（推挤 / 膨胀 / 收缩） */
@Composable
private fun LiquifyDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(sketch?.brushSize ?: 60f) }
    var strength by remember { mutableStateOf(sketch?.liquifyStrength ?: 60f) }
    var mode by remember { mutableIntStateOf(sketch?.liquifyMode ?: 0) }
    DockCard("液化", "在画面上按住拖动：推挤会推着像素走，膨胀/收缩朝笔尖中心放大或收紧", modifier) {
        LevelRow("大小", size, 4f..300f) { size = it; sketch?.brushSize = it }
        LevelRow("力度", strength, 0f..100f) { strength = it; sketch?.liquifyStrength = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip("推挤", mode == 0) { mode = 0; sketch?.liquifyMode = 0 }
            TabChip("膨胀", mode == 1) { mode = 1; sketch?.liquifyMode = 1 }
            TabChip("收缩", mode == 2) { mode = 2; sketch?.liquifyMode = 2 }
        }
    }
}

/** 变形：4×4 网格（拖动画面上的控制点），这里给开关与复位 */
@Composable
private fun WarpDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(false) }
    DockCard("变形", "网格 4×4：开网格后在画面上拖动控制点扭曲当前层；关掉就恢复原样", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip(if (on) "网格：开" else "网格：关", on) {
                on = !on
                if (on) sketch?.warpStart() else sketch?.warpOff()
            }
            DockButton("复位网格") { sketch?.warpResetMesh(); on = true }
        }
    }
}

/** 图案图章：图案 / 对齐 / 大小 */
@Composable
private fun PatternDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    val pats = remember { com.reveal.editor.ui.Patterns.all() }
    var idx by remember { mutableStateOf(sketch?.patternIndex ?: 0) }
    var aligned by remember { mutableStateOf(sketch?.patternAligned ?: true) }
    var size by remember { mutableStateOf(sketch?.brushSize ?: 40f) }
    DockCard("图案图章", "用图案盖住笔迹：对齐 = 图案跟着画布走，不对齐 = 每笔重新起图案", modifier) {
        LevelRow("大小", size, 4f..300f) { size = it; sketch?.brushSize = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip(if (aligned) "对齐" else "不对齐", aligned) {
                aligned = !aligned
                sketch?.patternAligned = aligned
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pats.forEachIndexed { i, pair ->
                TabChip(pair.first, idx == i) { idx = i; sketch?.patternIndex = i }
            }
        }
    }
}

/** 参数卡片的统一外壳（标题 + 说明 + 内容），给液化/变形/图案图章这些新面板用 */
@Composable
private fun DockCard(
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
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
            Text(hint, fontSize = 10.sp, color = Rv.textSub)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 区域范围：形状 / 羽化 / 已有范围列表（点一条就切到它，调色只在这个范围内生效） */
@Composable
private fun RegionDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var kind by remember { mutableIntStateOf(sketch?.regionKind ?: 0) }
    // 自检结果（点「自检圈外」后显示）
    var auditMsg by remember { mutableStateOf("") }
    var feather by remember { mutableIntStateOf(sketch?.regionFeather ?: 0) }
    var tick by remember { mutableIntStateOf(0) }
    val list = sketch?.regions ?: emptyList()
    DockCard(
        "区域范围",
        "在画面上拉一个形状：之后的调色只作用在它里面；点下面某一条可以切回那个范围改参数",
        modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip("圆", kind == 0) { kind = 0; sketch?.regionKind = 0 }
            TabChip("椭圆", kind == 1) { kind = 1; sketch?.regionKind = 1 }
            TabChip("矩形", kind == 2) { kind = 2; sketch?.regionKind = 2 }
        }
        Spacer(Modifier.height(6.dp))
        // 大小：宽/高半径（拖动即时改圈，画面上跟着变）
        val curS = list.getOrNull(sketch?.activeRegion ?: -1)
        if (curS != null) {
            LevelRow("宽", curS.rx, 4f..4000f) {
                curS.rx = it.coerceAtLeast(4f); sketch?.invalidate(); tick++
                sketch?.previewRegionComposite()
            }
            LevelRow("高", curS.ry, 4f..4000f) {
                curS.ry = it.coerceAtLeast(4f); sketch?.invalidate(); tick++
                sketch?.previewRegionComposite()
            }
        }
        LevelRow("羽化", feather.toFloat(), 0f..80f) {
            feather = it.roundToInt()
            sketch?.regionFeather = feather
            list.getOrNull(sketch?.activeRegion ?: -1)?.let {
                sketch?.regionSetFeather(sketch.activeRegion, feather)
                tick++
            }
            sketch?.previewRegionComposite()
        }
        // 反转：范围外生效；强度：整体遮罩的 alpha（半强度就是只生效一半）
        var showMask by remember { mutableStateOf(sketch?.showMask ?: false) }
        val cur0 = list.getOrNull(sketch?.activeRegion ?: -1)
        var strength by remember(cur0, tick) { mutableStateOf((cur0?.opacity ?: 255).toFloat()) }
        LevelRow("强度", strength, 0f..255f) {
            strength = it
            cur0?.let { r -> r.opacity = it.roundToInt(); sketch?.invalidate(); tick++ }
            sketch?.previewRegionComposite()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("新建" to 0, "相加" to 1, "相减" to 2, "相交" to 3).forEach { (n, v) ->
                    TabChip(n, (cur0?.op ?: 0) == v) {
                        cur0?.let { r ->
                            r.op = v
                            sketch?.invalidate()
                            sketch?.previewRegionComposite()
                            tick++
                        }
                    }
                }
            }
            TabChip(
                if (showMask) "蒙版：显示" else "蒙版：隐藏",
                showMask,
            ) {
                showMask = !showMask
                sketch?.showMask = showMask
            }
            TabChip("自检圈外", false) {
                auditMsg = sketch?.auditRegionOutsideAlpha() ?: "引擎没准备好"
            }
            // 处理链：应用=加一步（活的），烧录=真正写像素并删掉圈
            val steps = sketch?.chainStepCount() ?: 0
            if (steps > 0) {
                Text("处理链：$steps 步（活的，换工具继续叠加）", fontSize = 10.sp, color = Rv.textFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TabChip("烧录", true) {
                        auditMsg = if (sketch?.commitChain() == true) "正在烧录…" else "烧录失败：没有可用的链"
                    }
                    TabChip("退一步", false) {
                        sketch?.popChainStep()
                        tick++
                    }
                    TabChip("放弃", false) {
                        sketch?.clearChain()
                        tick++
                    }
                }
            }
            if (auditMsg.isNotEmpty()) {
                Text(auditMsg, fontSize = 10.sp, color = Rv.textFaint)
            }
            TabChip(if (cur0?.invert == true) "反转：开" else "反转：关", cur0?.invert == true) {
                cur0?.let { r -> r.invert = !r.invert; sketch?.invalidate(); tick++ }
                sketch?.previewRegionComposite()
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEachIndexed { i, r ->
                TabChip("${r.name} ${i + 1}", sketch?.activeRegion == i) {
                    sketch?.activeRegion = i
                    tick++
                }
            }
            if (list.isNotEmpty()) {
                TabChip("删除当前", false) {
                    sketch?.regionRemove(sketch.activeRegion)
                    tick++
                }
            }
            if (sketch?.hasSelection() == true) {
                TabChip("由选区生成", false) {
                    sketch?.regionFromSelection(true)
                    tick++
                }
                TabChip("由选区生成矩形", false) {
                    sketch?.regionFromSelection(false)
                    tick++
                }
            }
        }
        if (list.isEmpty()) {
            Text("还没有范围：在画面上按住拖出一个形状", fontSize = 10.sp, color = Rv.textSub)
        }
    }
}

/** 混合颜色带：按通道把纯色背景抠成透明（等价混合选项里的 Blend If） */
@Composable
private fun BandCutDock(sketch: SketchView?, modifier: Modifier = Modifier) {
    var ch by remember { mutableIntStateOf(0) }
    var b0 by remember { mutableStateOf(0f) }
    var b1 by remember { mutableStateOf(0f) }
    var w0 by remember { mutableStateOf(255f) }
    var w1 by remember { mutableStateOf(255f) }
    var feather by remember { mutableStateOf(0f) }
    DockCard(
        "混合颜色带",
        "按某个通道的亮度抠：低于黑场、高于白场的变透明，两个滑块拉开来是软过渡。抠纯色背景最省事",
        modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip("灰度", ch == 0) { ch = 0 }
            TabChip("红", ch == 1) { ch = 1 }
            TabChip("绿", ch == 2) { ch = 2 }
            TabChip("蓝", ch == 3) { ch = 3 }
        }
        Spacer(Modifier.height(6.dp))
        LevelRow("黑场下", b0, 0f..255f) { b0 = it; if (b1 < it) b1 = it }
        LevelRow("黑场上", b1, 0f..255f) { b1 = it; if (b0 > it) b0 = it }
        LevelRow("白场下", w0, 0f..255f) { w0 = it; if (w1 < it) w1 = it }
        LevelRow("白场上", w1, 0f..255f) { w1 = it; if (w0 > it) w0 = it }
        LevelRow("羽化", feather, 0f..100f) { feather = it }
        Spacer(Modifier.height(8.dp))
        // 拖滑块就出预览：改的是预览底图的 alpha，画面里直接能看到哪里被抠掉
        val base = remember(sketch?.activeIndex) { previewBase(sketch?.flatten()) }
        val snapKey = rememberUpdatedState(
            intArrayOf(ch, b0.toInt(), b1.toInt(), w0.toInt(), w1.toInt(), feather.toInt())
        )
        LaunchedEffect(Unit) {
            val b = base ?: return@LaunchedEffect
            var built: String? = null
            while (true) {
                val k = snapKey.value
                val sign = k.joinToString(",")
                if (sign != built) {
                    built = sign
                    val out = withContext(Dispatchers.Default) {
                        runCatching {
                            com.reveal.editor.image.FilterFx.bandCut(b, k[0], k[1], k[2], k[3], k[4], k[5])
                        }.getOrNull()
                    }
                    if (out != null) sketch?.setPreview(out)
                }
                delay(16)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DockButton("应用") {
                val c = ch; val fb = b0.toInt(); val tb = b1.toInt()
                val fw = w0.toInt(); val tw = w1.toInt(); val fth = feather.toInt()
                sketch?.applyFilterToActive({ bmp ->
                    com.reveal.editor.image.FilterFx.bandCut(bmp, c, fb, tb, fw, tw, fth)
                }, "混合颜色带", allowAlpha = true)
            }
            DockButton("复位") { b0 = 0f; b1 = 0f; w0 = 255f; w1 = 255f; feather = 0f }
        }
    }
}
