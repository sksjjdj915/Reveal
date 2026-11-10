package com.reveal.editor.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.reveal.editor.data.Diag
import com.reveal.editor.data.LoadedDocument
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.data.PsdBrowser
import com.reveal.editor.data.PsdShare
import com.reveal.editor.data.SaveLayer
import com.reveal.editor.image.Curves
import com.reveal.editor.image.BitmapLoader
import com.reveal.editor.image.ColorAdjust
import com.reveal.editor.image.Filters
import com.reveal.editor.image.ExportFormat
import com.reveal.editor.image.ImageExporter
import com.reveal.editor.image.Levels
import com.reveal.editor.image.Matting
import com.reveal.editor.image.Psd
import com.reveal.editor.image.PsdEffects
import com.reveal.editor.image.AdjustOp
import com.reveal.editor.image.PsdTextInfo
import com.reveal.editor.image.SelectMode
import com.reveal.editor.image.SelectOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val PREVIEW_MAX_SIDE = 900

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.7"
    }

    var hasDoc by remember { mutableStateOf(false) }
    var docName by remember { mutableStateOf("未命名画布") }
    var pendingDoc by remember { mutableStateOf<LoadedDocument?>(null) }
    var autoSaveTick by remember { mutableIntStateOf(0) }
    // 动过任何一个像素就不再「原样保存」，改为按原格式重写
    var psdEdited by remember { mutableStateOf(false) }
    var showCrashLog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSelfTest by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf(ExportFormat.PNG) }
    var exportQuality by remember { mutableIntStateOf(95) }
    var exportScale by remember { mutableIntStateOf(100) }
    var showFormatDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("处理中…") }

    var tool by remember { mutableStateOf(SketchView.Tool.BRUSH) }
    // 选中图层变了就 +1，用来让「形状/渐变参数条」跟着选中的对象切换
    var specTick by remember { mutableStateOf(0) }
    /** 左栏「修改」面板开关 */
    var showModify by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(SelectMode.RECT) }
    var selectOp by remember { mutableStateOf(SelectOp.NEW) }
    var hasSelection by remember { mutableStateOf(false) }
    var selMenuAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var brushSize by remember { mutableFloatStateOf(16f) }
    var brushType by remember { mutableIntStateOf(SketchView.BRUSH_ROUND) }
    var brushHardness by remember { mutableFloatStateOf(0.85f) }
    var brushFlow by remember { mutableFloatStateOf(1f) }
    var brushSpacing by remember { mutableFloatStateOf(0.12f) }
    var brushTaper by remember { mutableFloatStateOf(0f) }
    var brushJitter by remember { mutableFloatStateOf(0f) }
    var brushScatter by remember { mutableFloatStateOf(0f) }
    var brushRoundness by remember { mutableFloatStateOf(1f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var color by remember { mutableIntStateOf(0xFFEFF1F4.toInt()) }
    var docInfo by remember { mutableStateOf("") }
    var sketch by remember { mutableStateOf<SketchView?>(null) }

    // 设置（记住上次选择）
    val prefs = remember {
        context.getSharedPreferences("reveal_settings", android.content.Context.MODE_PRIVATE)
    }
    var pixelZoom by remember { mutableStateOf(prefs.getBoolean("pixelZoom", false)) }
    var pressureOn by remember { mutableStateOf(prefs.getBoolean("penPressure", true)) }
    var showSettings by remember { mutableStateOf(false) }
    var showTouch by remember { mutableStateOf(prefs.getBoolean("showTouch", false)) }
    // 调色（底部面板，只调当前选中的图层）
    var showAdjust by remember { mutableStateOf(false) }
    var adjustFullSource by remember { mutableStateOf<Bitmap?>(null) }
    var adjustPreviewSource by remember { mutableStateOf<Bitmap?>(null) }
    var adjustLayerIndex by remember { mutableIntStateOf(0) }
    var adjustLayerName by remember { mutableStateOf("") }
    var adjustParams by remember { mutableStateOf(ColorAdjust.Params()) }
    // 文字工具
    var showText by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var textSize by remember { mutableFloatStateOf(64f) }
    var textAtX by remember { mutableFloatStateOf(0f) }
    var editTextFor by remember { mutableIntStateOf(-1) }
    var showGrid by remember { mutableStateOf(false) }
    var gridCount by remember { mutableIntStateOf(12) }
    var smudgeStrength by remember { mutableFloatStateOf(0.65f) }
    var smudgeMode by remember { mutableIntStateOf(0) }
    var shapeKind by remember { mutableIntStateOf(0) }
    var shapeFill by remember { mutableStateOf(false) }
    var smudgeFinger by remember { mutableStateOf(false) }
    var toneStrength by remember { mutableFloatStateOf(0.45f) }
    var toneRange by remember { mutableIntStateOf(0) }
    var showTextColor by remember { mutableStateOf(false) }
    var maskHintShown by remember { mutableStateOf(false) }
    var textColorFor by remember { mutableIntStateOf(0) }
    /** 文字字体（Android 家族名，空 = 默认） */
    var textFont by remember { mutableStateOf("") }
    /** 字体管理窗口 + 字体列表版本号（删/恢复字体后 +1，让选择器刷新） */
    var showFontMgr by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }
    var fontsVersion by remember { mutableIntStateOf(0) }
    var textSpec by remember { mutableStateOf(TextSpec()) }
    var textAtY by remember { mutableFloatStateOf(0f) }
    var transformOn by remember { mutableStateOf(false) }
    var tfAngle by remember { mutableStateOf(0f) }
    var cropOn by remember { mutableStateOf(false) }
    var cropScopeOnly by remember { mutableStateOf(false) }
    var showLayerStyle by remember { mutableStateOf(false) }
    var styleFor by remember { mutableIntStateOf(-1) }
    var showResize by remember { mutableStateOf(false) }
    var resizeW by remember { mutableStateOf("") }
    var resizeH by remember { mutableStateOf("") }
    var showFeather by remember { mutableStateOf(false) }
    var showSelMask by remember { mutableStateOf(false) }
    // 打开「选择并遮住」前的工具：关面板时还回去，别让人从面板出来发现自己在选区工具上
    var toolBeforeSelMask by remember { mutableStateOf<SketchView.Tool?>(null) }
    var adjIndex by remember { mutableStateOf(-1) }
    var adjOp by remember { mutableStateOf<AdjustOp?>(null) }
    var aopChannel by remember { mutableIntStateOf(0) }
    var showAdjustLayer by remember { mutableStateOf(false) }
    // 调色里那个显眼的「调整图层…」入口：先选要建哪种调整层
    var showAdjustChooser by remember { mutableStateOf(false) }
    var showBlur by remember { mutableStateOf(false) }
    var blurRadius by remember { mutableStateOf(6f) }
    var blurFull by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var blurSmall by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var blurLayer by remember { mutableIntStateOf(0) }
    var thresholdFull by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var thresholdSmall by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var thresholdLayer by remember { mutableIntStateOf(0) }
    var showThreshold by remember { mutableStateOf(false) }
    var thresholdLevel by remember { mutableStateOf(128f) }
    var showCanvasSize by remember { mutableStateOf(false) }
    var canvasW by remember { mutableStateOf("") }
    var canvasH by remember { mutableStateOf("") }
    var featherRadius by remember { mutableStateOf(6f) }
    var perspMode by remember { mutableStateOf(false) }

    // 图层
    var showLayers by remember { mutableStateOf(false) }
    var layerRows by remember { mutableStateOf<List<LayerRow>>(emptyList()) }
    var activeIndex by remember { mutableIntStateOf(0) }

    // 抠图（底部面板 + 实时预览）
    var showMatting by remember { mutableStateOf(false) }
    var mattingFullSource by remember { mutableStateOf<Bitmap?>(null) }
    var mattingPreviewSource by remember { mutableStateOf<Bitmap?>(null) }
    var mattingLayerIndex by remember { mutableIntStateOf(0) }
    var mattingParams by remember { mutableStateOf<MattingParams?>(null) }
    var mattingNextFull by remember { mutableStateOf<Bitmap?>(null) }
    var mattingNextPreview by remember { mutableStateOf<Bitmap?>(null) }

    // 色阶（照 桌面端的图像 → 调整 → 色阶）
    var showLevels by remember { mutableStateOf(false) }
    var levelsFullSource by remember { mutableStateOf<Bitmap?>(null) }
    var levelsPreviewSource by remember { mutableStateOf<Bitmap?>(null) }
    var levelsLayerIndex by remember { mutableIntStateOf(0) }
    var levelsParams by remember { mutableStateOf(Levels.Params()) }

    // 曲线（照 桌面端的图像 → 调整 → 曲线）
    var showCurves by remember { mutableStateOf(false) }
    var curvesFullSource by remember { mutableStateOf<Bitmap?>(null) }
    var curvesPreviewSource by remember { mutableStateOf<Bitmap?>(null) }
    var curvesLayerIndex by remember { mutableIntStateOf(0) }
    var curvesParams by remember { mutableStateOf(Curves.Params()) }
    // 曲线吸管取到的色阶值（null = 没取）
    var curvePickVal by remember { mutableStateOf<Int?>(null) }

    // 通道面板（RGB / 红 / 绿 / 蓝）
    var showChannels by remember { mutableStateOf(false) }
    var channelsThumb by remember { mutableStateOf<Bitmap?>(null) }
    var channelView by remember { mutableIntStateOf(-1) }

    // 历史记录面板：historyTick 变了就重新读一遍撤销栈
    var showHistory by remember { mutableStateOf(false) }
    var tfDialog by remember { mutableIntStateOf(0) };
    var fxId by remember { mutableStateOf("") };
    var showEfx by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }
    var showComps by remember { mutableStateOf(false) }
    var smartEditIdx by remember { mutableStateOf(-1) }
    var uiPanel by remember { mutableStateOf<String?>(null) }
    var historyTick by remember { mutableIntStateOf(0) }
    var zoomPct by remember { mutableIntStateOf(100) }

    // 底部画笔参数展开态：提到这里是为了和其它面板互斥（开一个自动关别的）
    var showBrushDetail by remember { mutableStateOf(false) }

    // 打开面板时把底部工具条收起来，保证“同时只有一个参数条”
    // 启动时底部是空的：点哪个工具才浮出哪条参数（用户要求「下方什么也没有」）
    var barHidden by remember { mutableStateOf(true) }
    // 当前模式（抠图/调色/排版/修图/绘制/AI）：决定左侧栏放哪些工具
    var rvMode by remember { mutableStateOf(RvMode.CUT) }

    /** 关掉所有底部/浮层面板（含特效/动作/批处理/合成/文字/图层样式等） */
    fun closeAllPanels() {
        barHidden = false
        showBrushDetail = false
        showLayers = false
        showAdjust = false
        showMatting = false
        showCurves = false
        showLevels = false
        showChannels = false
        showHistory = false
        showAdjustLayer = false
        showBlur = false
        showThreshold = false
        showResize = false
        showCanvasSize = false
        showFeather = false
        showSelMask = false
        showText = false
        // 关面板时把文字的实时预览一起撤掉：预览一直挂在图层上，之后改图层样式、
        // 变换、撤销都「看不出反应」（屏幕上画的还是那张旧预览图）
        sketch?.clearPreview()
        editTextFor = -1
        showLayerStyle = false
        showEfx = false
        showActions = false
        showBatch = false
        showComps = false
        showColorDialog = false
        showModify = false
    }
    /** 面板互斥：同时只开一个，谁被打开就把其它的收起 */
    fun openPanel(which: String) {
        closeAllPanels()
        barHidden = true
        showGrid = false
        sketch?.showGrid = false
        showBrushDetail = which == "brush"
        showLayers = which == "layers"
        showCurves = which == "curves"
        showLevels = which == "levels"
        showChannels = which == "channels"
        showHistory = which == "history"
        showAdjust = which == "adjust"
        showMatting = which == "matting"
        showAdjustLayer = which == "adjustlayer"
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    var maskEditing by remember { mutableStateOf(false) }

    fun refreshLayers() {
        val view = sketch ?: return
        activeIndex = view.activeIndex
        layerRows = buildLayerRows(view)
    }

    fun alignActive(mode: Int) {
        val idx = sketch?.activeIndex ?: return
        if (sketch?.alignLayer(idx, mode) == true) {
            refreshLayers()
            toast("已对齐")
        } else {
            toast("这一层是空的")
        }
    }

    fun collectLayers(): List<SaveLayer> {
        val view = sketch ?: return emptyList()
        return collectSaveLayers(view)
    }

    fun saveNow(onDone: (() -> Unit)? = null) {
        scope.launch {
            busyText = "正在保存…"
            busy = true
            // 收集图层会顺带把换到磁盘上的层装回内存，大图要一两秒，放 IO 线程别卡界面
            val layers = withContext(Dispatchers.IO) { collectLayers() }
            if (layers.isEmpty()) {
                busy = false
                onDone?.invoke()
                return@launch
            }
            withContext(Dispatchers.IO) {
                ProjectStore.save(context, projectId, docName, layers, sketch?.docDepth ?: 8)
                if (psdEdited) PsdBrowser.markEdited(context, projectId)
            }
            busy = false
            onDone?.invoke()
        }
    }

    suspend fun saveNowSync() {
        val layers = withContext(Dispatchers.IO) { collectLayers() }
        if (layers.isEmpty()) return
        withContext(Dispatchers.IO) {
            ProjectStore.save(context, projectId, docName, layers, sketch?.docDepth ?: 8)
            if (psdEdited) PsdBrowser.markEdited(context, projectId)
        }
    }

    // 打开抠图：抠的是当前层，先抓全分辨率快照，再缩一张小图当实时预览源
    fun openMatting() {
        val view = sketch
        val snap = view?.snapshotActiveLayer()
        if (view == null || snap == null) {
            toast("画布还没准备好")
            return
        }
        openPanel("matting")
        mattingLayerIndex = view.activeIndex
        mattingFullSource = snap
        mattingPreviewSource = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
        mattingNextFull = view.layerBelowActive()
        mattingNextPreview = mattingNextFull?.let { ProjectStore.scaleDown(it, PREVIEW_MAX_SIDE) }
        mattingParams = null
        showMatting = true
    }

    // 打开色阶：同样先抓全分辨率快照 + 一张小图当实时预览源
    fun openLevels() {
        val view = sketch
        val snap = view?.snapshotActiveLayer()
        if (view == null || snap == null) {
            toast("画布还没准备好")
            return
        }
        openPanel("levels")
        levelsLayerIndex = view.activeIndex
        levelsFullSource = snap
        levelsPreviewSource = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
        levelsParams = Levels.Params()
        showLevels = true
    }

    fun runFilter(label: String, action: (android.graphics.Bitmap) -> android.graphics.Bitmap?) {
        val view = sketch ?: return
        if (!view.hasDocument) return
        // 调整层没有像素：以前点滤镜完全没反应也不提示，用户以为软件坏了
        if (view.activeLayerIsAdjust()) {
            toast("当前是调整层，滤镜只作用于像素层（先在右下角选一个普通图层）")
            return
        }
        if (view.applyFilterToActive(action, label)) {
            refreshLayers()
            toast(label + " 完成")
        } else {
            toast("这个图层没有可处理的像素")
        }
    }

    fun openBlur() {
        val view = sketch ?: return
        val snap = view.snapshotActiveLayer() ?: return
        closeAllPanels()
        blurFull = snap
        blurSmall = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
        blurLayer = view.activeLayerIndex()
        blurRadius = 6f
        showBlur = true
    }

    fun closeBlur(apply: Boolean) {
        showBlur = false
        val full = blurFull
        val idx = blurLayer
        val r = blurRadius.toInt().coerceAtLeast(1)
        blurFull = null
        blurSmall = null
        sketch?.setPreview(null)
        if (!apply || full == null) return
        scope.launch {
            val out = withContext(Dispatchers.Default) { Filters.blur(full, r) }
            sketch?.replaceLayerAt(idx, out)
            refreshLayers()
            toast("模糊完成")
        }
    }

    fun openThreshold() {
        val view = sketch ?: return
        val snap = view.snapshotActiveLayer() ?: return
        closeAllPanels()
        thresholdFull = snap
        thresholdSmall = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
        thresholdLayer = view.activeLayerIndex()
        thresholdLevel = 128f
        showThreshold = true
    }

    fun closeThreshold(apply: Boolean) {
        showThreshold = false
        val full = thresholdFull
        val idx = thresholdLayer
        val lv = thresholdLevel.toInt()
        thresholdFull = null
        thresholdSmall = null
        sketch?.setPreview(null)
        if (!apply || full == null) return
        scope.launch {
            val out = withContext(Dispatchers.Default) { Filters.threshold(full, lv) }
            sketch?.replaceLayerAt(idx, out)
            refreshLayers()
            toast("阈值完成")
        }
    }

    // 打开裁剪：直接在画布上拖框，所有图层一起裁
    fun openCrop() {
        val view = sketch ?: return
        if (!view.hasDocument) return
        closeAllPanels()
        perspMode = false
        view.cancelTransform()
        view.clearPreview()
        view.beginCrop()
    }

    // 打开曲线：和色阶一样，全分辨率快照 + 小图预览源
    fun openCurves() {
        val view = sketch
        val snap = view?.snapshotActiveLayer()
        if (view == null || snap == null) {
            toast("画布还没准备好")
            return
        }
        openPanel("curves")
        curvesLayerIndex = view.activeIndex
        curvesFullSource = snap
        curvesPreviewSource = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
        curvesParams = Curves.Params()
        showCurves = true
    }

    // 打开通道面板：缩略图用当前图层
    fun openChannels() {
        channelsThumb = sketch?.snapshotActiveLayer()
        openPanel("channels")
    }

    // 打开调色：只调当前选中的图层，先抓全分辨率快照 + 一张小图当实时预览源
    fun openAdjust() {
        val view = sketch
        val snap = view?.snapshotActiveLayer()
        if (view == null || snap == null) {
            toast("画布还没准备好")
            return
        }
        openPanel("adjust")
        adjustLayerIndex = view.activeIndex
        adjustLayerName = view.layers.getOrNull(view.activeIndex)?.name ?: "当前图层"
        adjustFullSource = snap
        adjustPreviewSource = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
        adjustParams = ColorAdjust.Params()
        showAdjust = true
    }

    LaunchedEffect(projectId) {
        val perfT0 = System.currentTimeMillis()
        val doc = withContext(Dispatchers.IO) {
            runCatching { ProjectStore.load(context, projectId) }
                .onFailure { com.reveal.editor.CrashLog.append(context, "读工程失败", it) }
                .getOrNull()
        }
        val perfMs = System.currentTimeMillis() - perfT0
        val perfRt = Runtime.getRuntime()
        android.util.Log.i(
            "RevealPerf",
            "load " + perfMs + "ms layers=" + (doc?.layers?.size ?: 0) +
                " heapUsed=" + (perfRt.totalMemory() - perfRt.freeMemory()) / 1048576 + "MB max=" + perfRt.maxMemory() / 1048576 + "MB"
        )
        if (doc == null) {
            toast("工程读不出来（可能存的时候被中断了）")
            onBack()
        } else {
            pendingDoc = doc
        }
    }

    // 自动保存：停手 6 秒后覆盖写回工程（每次改动重新计时，不会和正在画的手抢像素）
    LaunchedEffect(autoSaveTick) {
        if (autoSaveTick == 0) return@LaunchedEffect
        delay(6_000)
        val view = sketch ?: return@LaunchedEffect
        if (view.layers.isEmpty()) return@LaunchedEffect
        val layers = collectLayers()
        if (layers.isEmpty()) return@LaunchedEffect
        val name = docName
        val ok = withContext(Dispatchers.IO) {
            runCatching { ProjectStore.save(context, projectId, name, layers) }.isSuccess
        }
        if (ok) autoSaveTick = 0
    }

    // 抠图预览：参数一变就算一遍（小图，快），画布上立刻能看到
    LaunchedEffect(mattingParams) {
        val p = mattingParams ?: return@LaunchedEffect
        val src = mattingPreviewSource ?: return@LaunchedEffect
        delay(50)
        val out = withContext(Dispatchers.Default) { applyMatting(p, src, mattingNextPreview) }
        sketch?.setPreview(out, mattingLayerIndex)
    }

    // 色阶预览：拖滑块时在小图上算，画布即时变
    LaunchedEffect(levelsParams) {
        if (!showLevels) return@LaunchedEffect
        val src = levelsPreviewSource ?: return@LaunchedEffect
        delay(50)
        val out = withContext(Dispatchers.Default) { Levels.apply(src, levelsParams) }
        if (out == null) sketch?.clearPreview() else sketch?.setPreview(out, levelsLayerIndex)
    }

    // 曲线预览：拖控制点时在小图上算
    LaunchedEffect(curvesParams, showCurves) {
        if (!showCurves) return@LaunchedEffect
        val src = curvesPreviewSource ?: return@LaunchedEffect
        delay(50)
        val out = withContext(Dispatchers.Default) { Curves.apply(src, curvesParams) }
        if (out == null) sketch?.clearPreview() else sketch?.setPreview(out, curvesLayerIndex)
    }

    // 调色预览：拖滑块时在小图上算，画布即时变
    LaunchedEffect(adjustParams, showAdjust) {
        if (!showAdjust) return@LaunchedEffect
        val src = adjustPreviewSource ?: return@LaunchedEffect
        delay(50)
        val p = adjustParams
        val out = withContext(Dispatchers.Default) {
            if (p.isNeutral()) null else ColorAdjust.apply(src, p)
        }
        if (out == null) sketch?.clearPreview() else sketch?.setPreview(out, adjustLayerIndex)
    }

    val insertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && smartEditIdx >= 0) {
            val idx = smartEditIdx
            smartEditIdx = -1
            scope.launch {
                busyText = "正在替换智能对象内容…"
                busy = true
                val bmp = withContext(Dispatchers.IO) { BitmapLoader.load(context, uri) }
                busy = false
                if (bmp == null) {
                    toast("这张图读不出来")
                } else if (sketch?.replaceSmartContent(idx, bmp) == true) {
                    refreshLayers()
                    toast("智能对象里的画面已替换")
                } else {
                    toast("当前图层不是智能对象")
                }
            }
        } else if (uri != null) {
            scope.launch {
                busyText = "正在载入图片…"
                busy = true
                val bmp = withContext(Dispatchers.IO) { BitmapLoader.load(context, uri) }
                busy = false
                if (bmp == null) {
                    toast("这张图读不出来：${BitmapLoader.lastError ?: "未知原因"}")
                } else if (sketch?.addImageLayer(bmp) == true) {
                    refreshLayers()
                    toast("已置入为新图层")
                } else {
                    toast("置入失败")
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = remember(selectedFormat) {
            ActivityResultContracts.CreateDocument(selectedFormat.mime)
        },
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val full = sketch?.flatten()
                        ?: return@withContext Result.failure<Unit>(IllegalStateException("画布是空的"))
                    val source = if (exportScale >= 100) {
                        full
                    } else {
                        val sw = (full.width * exportScale / 100).coerceAtLeast(1)
                        val sh = (full.height * exportScale / 100).coerceAtLeast(1)
                        val small = android.graphics.Bitmap.createScaledBitmap(full, sw, sh, true)
                        small
                    }
                    val res = ImageExporter.export(context, uri, source, selectedFormat, exportQuality)
                    if (source !== full) source.recycle()
                    res
                }
                toast(
                    result.fold(
                        onSuccess = { "已导出 ${selectedFormat.displayName}" },
                        onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                    ),
                )
            }
        }
    }

    val psdOpenLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val doc = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { Psd.openSmart(it, context.cacheDir) }
                    }.getOrNull()
                }
                busy = false
                if (doc == null) {
                    toast("PSD 读不出来（支持 8 位 RGB / 灰度的 PSD 和 PSB）")
                } else if (doc.layers.isEmpty()) {
                    toast("这个 PSD 里没有可读图层")
                } else {
                    val newLayers = doc.layers.map { pl ->
                        SketchView.Layer(pl.bitmap, pl.name).also {
                            it.visible = pl.visible
                            it.alpha = pl.opacity
                            it.blendMode = pl.blendMode
                            it.clipped = pl.clipped
                            it.alphaLock = pl.alphaLock
                            it.mask = pl.mask
                it.rawMask = pl.rawMask
                it.maskDirty = pl.maskDirty
                            it.adjust = pl.adjust
                            it.rawHead = pl.raw?.let { r -> Psd.packHead(r).pack() }
                            it.rawPix = if (doc.depth > 8) {
                                pl.raw?.let { r -> Psd.packPix(r) }
                                    ?: if (doc.layers.size == 1) Psd.packCompositePix(doc) else null
                            } else null
                            it.pixHash = Psd.bitmapHash(pl.bitmap, pl.mask)
                            it.sectionType = pl.sectionType
                            // 置入的智能对象：把原图挂上，让这层在 App 里也能换内容、反复缩放不糊
                            val smart = pl.smartSrc
                            if (smart != null) {
                                it.smartSrc = smart
                                val r = pl.raw?.rect
                                val lx = (r?.getOrNull(1) ?: 0).toFloat()
                                val ly = (r?.getOrNull(0) ?: 0).toFloat()
                                val lw = ((r?.getOrNull(3) ?: 0) - (r?.getOrNull(1) ?: 0)).toFloat()
                                val lh = ((r?.getOrNull(2) ?: 0) - (r?.getOrNull(0) ?: 0)).toFloat()
                                val sx = if (smart.width > 0 && lw > 0f) lw / smart.width else 1f
                                val sy = if (smart.height > 0 && lh > 0f) lh / smart.height else 1f
                                it.smartMatrix = Matrix().apply {
                                    setScale(sx, sy)
                                    postTranslate(lx, ly)
                                }
                            }
                            pl.text?.let { t ->
                                it.textContent = t.content
                                it.textSize = t.size
                                it.textColor = t.color
                                it.textFont = com.reveal.editor.image.PsdText.familyForPsName(t.font)
                            }
                        }
                    }
                    runCatching {
                        Diag.append(
                            context, "OPEN",
                            "depth=" + doc.depth + " chans=" + doc.channels +
                                " layers=" + doc.layers.size + " rawPix=" +
                                newLayers.count { it.rawPix != null } + " pix32=" +
                                newLayers.count { it.pix32 != null },
                        )
                    }
                    sketch?.docDepth = doc.depth
                    sketch?.setDocument(newLayers)
                    // setDocument 会触发 onDocChanged，打开一份文件不等于改过它，
                    // 这里复位，否则「一个像素没改就直接交原件」的分支永远走不到
                    psdEdited = false
                    refreshLayers()
                    toast("已打开 PSD：${newLayers.size} 层")
                }
            }
        }
    }

    // 把当前画布拼成 PSD 字节（一个像素都没改就原样吐原件）
    fun buildPsdNow(): ByteArray = com.reveal.editor.ui.buildPsdBytes(
        view = sketch ?: error("画布是空的"),
        context = context,
        projectId = projectId,
    )

    val psdSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = buildPsdNow()
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("写不进这个位置")
                        "已另存为 PSD（${bytes.size / 1024} KB），原件没动"
                    }
                }
                busy = false
                toast(
                    res.fold(
                        onSuccess = { it },
                        onFailure = { "另存 PSD 失败：${it.message ?: "未知错误"}" },
                    ),
                )
            }
        }
    }

    // 分享：先把当前进度存好，再把这份 PSD 交给系统分享面板
    fun shareCurrentPsd() {
        if (!hasDoc) {
            toast("画布还没准备好")
            return
        }
        scope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    saveNowSync()
                    val bytes = buildPsdNow()
                    PsdShare.share(context, PsdShare.psdName(docName), bytes)
                }
            }
            busy = false
            res.onFailure { toast("分享失败：${it.message ?: "未知错误"}") }
        }
    }

    BackHandler {
        if (showAdjust) {
            showAdjust = false
            sketch?.clearPreview()
            adjustFullSource = null
            adjustPreviewSource = null
        } else if (showMatting) {
            showMatting = false
            sketch?.clearPreview()
            mattingParams = null
            mattingFullSource = null
            mattingPreviewSource = null
            mattingNextFull = null
            mattingNextPreview = null
        } else if (showCurves) {
            showCurves = false
            curvesFullSource = null
            curvesPreviewSource = null
            sketch?.clearPreview()
        } else if (showLevels) {
            showLevels = false
            levelsFullSource = null
            levelsPreviewSource = null
            sketch?.clearPreview()
        } else if (showChannels) {
            showChannels = false
        } else if (showHistory) {
            showHistory = false
        } else if (showLayers) {
            showLayers = false
        } else {
            saveNow { onBack() }
        }
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部横向状态栏：工程名 + 撤销/重做/保存/设置
        val undoable = remember(historyTick, hasDoc) { hasDoc && (sketch?.canUndo() ?: false) }
        val redoable = remember(historyTick, hasDoc) { hasDoc && (sketch?.canRedo() ?: false) }
        TopStatusBar(
            title = if (hasDoc) docName else "Reveal",
            canUndo = undoable,
            canRedo = redoable,
            onUndo = { sketch?.undo(); historyTick++ },
            onRedo = { sketch?.redo(); historyTick++ },
            onSave = { saveNow { toast("已保存") } },
            onSettings = { showSettings = true },
            onHelp = { showHelp = true },
        )

        // 桌面模式：工具选项条放到菜单栏下方（桌面端位置）；竖屏仍在底部
        // 参数条跟着「选中的对象」走：层上有形状参数，就算当前是移动/画笔工具也显示形状条
        val optTool = remember(tool, specTick) {
            if (tool != SketchView.Tool.SHAPE && sketch?.activeShapeSpec() != null) {
                SketchView.Tool.SHAPE
            } else tool
        }
        val optionsStrip: @Composable () -> Unit = {
            when (optTool) {
                SketchView.Tool.SHAPE -> ShapeToolBar(sketch)
                SketchView.Tool.PEN -> PenToolBar(sketch)
                SketchView.Tool.WARP -> WarpToolBar(sketch)
                SketchView.Tool.VANISH -> VanishToolBar(sketch) { tool = SketchView.Tool.BRUSH }
                SketchView.Tool.SMUDGE -> ToolOptionsBar(
                    title = "涂抹",
                    hint = "按住拖，把颜色往前带",
                    sliderLabel = "大小",
                    sliderValue = sketch?.brushSize ?: 24f,
                    sliderRange = 1f..300f,
                    sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
                    onSlider = { sketch?.brushSize = it },
                    slider2Label = "强度",
                    slider2Value = smudgeStrength,
                    slider2Range = 0.05f..1f,
                    slider2Display = "${(smudgeStrength * 100).roundToInt()}%",
                    onSlider2 = { smudgeStrength = it },
                    chips = listOf("正常", "变暗", "变亮", "色相", "饱和度", "颜色", "明度"),
                    chipIndex = smudgeMode,
                    onChip = { smudgeMode = it },
                    actionLabel = if (smudgeFinger) "手指绘画：开" else "手指绘画：关",
                    onAction = { smudgeFinger = !smudgeFinger },
                )
                SketchView.Tool.DODGE, SketchView.Tool.BURN -> {
                    val isDodge = tool == SketchView.Tool.DODGE
                    ToolOptionsBar(
                        title = if (isDodge) "减淡" else "加深",
                        hint = if (isDodge) "曝光式提亮：越亮的地方提得越多，色相不变"
                        else "曝光式压暗：越暗的地方降得越少，不会压成黑块",
                        sliderLabel = "大小",
                        sliderValue = sketch?.brushSize ?: 24f,
                        sliderRange = 1f..300f,
                        sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
                        onSlider = { sketch?.brushSize = it },
                        slider2Label = "曝光",
                        slider2Value = if (isDodge) (sketch?.dodgeStrength ?: 0.45f)
                        else (sketch?.burnStrength ?: 0.45f),
                        slider2Range = 0.05f..1f,
                        slider2Display = if (isDodge)
                            "${((sketch?.dodgeStrength ?: 0.45f) * 100).roundToInt()}%"
                        else "${((sketch?.burnStrength ?: 0.45f) * 100).roundToInt()}%",
                        onSlider2 = { v ->
                            if (isDodge) sketch?.dodgeStrength = v else sketch?.burnStrength = v
                        },
                        chips = listOf("全部", "阴影", "中间调", "高光"),
                        chipIndex = if (isDodge) (sketch?.dodgeRange ?: 0) else (sketch?.burnRange ?: 0),
                        onChip = { v ->
                            if (isDodge) sketch?.dodgeRange = v else sketch?.burnRange = v
                        },
                        actionLabel = if (isDodge)
                            (if (sketch?.dodgeProtect == true) "保护色调：开" else "保护色调：关")
                        else (if (sketch?.burnProtect == true) "保护色调：开" else "保护色调：关"),
                        onAction = {
                            if (isDodge) sketch?.dodgeProtect = !(sketch?.dodgeProtect ?: false)
                            else sketch?.burnProtect = !(sketch?.burnProtect ?: false)
                        },
                    )
                }
                SketchView.Tool.CLONE, SketchView.Tool.HEAL -> ToolOptionsBar(
                    title = if (tool == SketchView.Tool.CLONE) "仿制图章" else "修复画笔",
                    hint = if (tool == SketchView.Tool.CLONE) "点一下取样，再涂" else "先点一下取样，再涂到瑕疵上",
                    sliderLabel = "大小",
                    sliderValue = sketch?.brushSize ?: 24f,
                    sliderRange = 1f..300f,
                    sliderDisplay = (sketch?.brushSize ?: 24f).roundToInt().toString(),
                    onSlider = { sketch?.brushSize = it },
                    slider2Label = if (tool == SketchView.Tool.HEAL) "修正半径" else "不透明度",
                    // 以前不透明度是个点了往下掉的循环按钮（100→75→50→25），没法细调
                    slider2Value = if (tool == SketchView.Tool.HEAL) (sketch?.healDiffusion ?: 3f)
                    else (sketch?.cloneOpacity ?: 100).toFloat(),
                    slider2Range = if (tool == SketchView.Tool.HEAL) 0f..7f else 1f..100f,
                    slider2Display = if (tool == SketchView.Tool.HEAL) (sketch?.healDiffusion ?: 3f).roundToInt().toString()
                    else "${sketch?.cloneOpacity ?: 100}%",
                    onSlider2 = {
                        if (tool == SketchView.Tool.HEAL) sketch?.healDiffusion = it
                        else sketch?.cloneOpacity = it.roundToInt().coerceIn(1, 100)
                    },
                    chips = if (tool == SketchView.Tool.CLONE) listOf("对齐", "不对齐") else emptyList(),
                    chipIndex = if (sketch?.cloneAligned != false) 0 else 1,
                    onChip = { sketch?.cloneAligned = it == 0 },
                    actions = listOf(
                        (if (sketch?.cloneAllLayers == true) "样本：所有图层" else "样本：当前图层") to {
                            sketch?.cloneAllLayers = !(sketch?.cloneAllLayers ?: false)
                        },
                        "重取样" to {
                            sketch?.resetCloneSource()
                            toast("取样点已清除，点画面重新取样")
                        },
                    ),
                )
                SketchView.Tool.REPLACE, SketchView.Tool.ERASER, SketchView.Tool.ERASER_BG, SketchView.Tool.ERASER_MAGIC,
                SketchView.Tool.SPONGE, SketchView.Tool.MIXER, SketchView.Tool.PATTERN,
                SketchView.Tool.SPOT, SketchView.Tool.REDEYE, SketchView.Tool.HISTORY_BRUSH,
                SketchView.Tool.ART_HISTORY -> PsToolOptionsHost(
                    tool = tool,
                    sketch = sketch,
                    opacity = opacity,
                    onOpacity = { opacity = it },
                )
                SketchView.Tool.SELECT -> SelectionOptionsBar(
                    sketch = sketch,
                    curMode = selectMode,
                    curOp = selectOp,
                    onMode = { selectMode = it },
                    onOp = { selectOp = it },
                    onMatting = { openMatting() },
                    onExitTool = {
                        tool = SketchView.Tool.BRUSH
                        barHidden = true
                    },
                )
                SketchView.Tool.BRUSH -> Unit
                else -> Unit
            }
        }

        // 画笔条 + 参数条放到顶部：底部要留给画布，别再挡（用户 2026-11-07 要求）
        BrushTypeBar(
            hasDoc = hasDoc,
            brushType = brushType,
            onBrushTypeChange = { brushType = it },
            showBrushDetail = showBrushDetail,
            onBrushDetailToggle = {
                if (showBrushDetail) showBrushDetail = false else openPanel("brush")
            },
        )

        if (showGrid && !barHidden) androidx.compose.runtime.CompositionLocalProvider(
            LocalBarClose provides { barHidden = true }
        ) {
        ToolOptionsBar(
            title = "网格",
            hint = "格数越多格越密（跟着图片缩放）",
            sliderLabel = "格数",
            sliderValue = gridCount.toFloat(),
            sliderRange = 2f..64f,
            sliderDisplay = "${gridCount} 格",
            onSlider = {
                gridCount = it.roundToInt().coerceIn(2, 64)
                sketch?.gridCount = gridCount
            },
            chips = listOf("6", "8", "12", "16", "24"),
            chipIndex = listOf(6, 8, 12, 16, 24).indexOf(gridCount),
            onChip = { i ->
                gridCount = listOf(6, 8, 12, 16, 24)[i]
                sketch?.gridCount = gridCount
            },
        )
        }

        val menus = listOf(
                MenuGroup(
                    title = "文件",
                    actions = listOf(
                        MenuAction("保存", enabled = hasDoc) { saveNow { toast("已保存") } },
                        MenuAction("导出…", enabled = hasDoc) {
                            if (hasDoc) showFormatDialog = true else toast("画布还没准备好")
                        },
                                MenuAction("打开 PSD / PSB…") { psdOpenLauncher.launch(arrayOf("*/*")) },
                                MenuAction("另存为 PSD…", enabled = hasDoc) {
                    psdSaveLauncher.launch(PsdShare.psdName(docName))
                },
                                MenuAction("分享 PSD…", enabled = hasDoc) { shareCurrentPsd() },
                        MenuAction("返回工程列表", dividerBefore = true) { saveNow { onBack() } },
                    ),
                ),
                MenuGroup(
                    title = "编辑",
                    actions = listOf(
                        MenuAction("复制选区到新图层（Ctrl+J）", enabled = hasSelection) {
                            if (sketch?.duplicateSelectionLayer() == true) {
                                refreshLayers()
                                toast("已复制到新图层")
                            } else {
                                toast("先选一块区域")
                            }
                        },
                    ),
                ),
                MenuGroup(
                    title = "图像",
                    actions = listOf(
                        MenuAction("画布大小…", enabled = hasDoc) {
                            val b = sketch?.activeLayer?.bitmap
                            if (b != null) {
                                canvasW = b.width.toString()
                                canvasH = b.height.toString()
                                closeAllPanels()
                                showCanvasSize = true
                            }
                        },
                        MenuAction("图像大小…", enabled = hasDoc) {
                            val b = sketch?.activeLayer?.bitmap
                            if (b != null) {
                                resizeW = b.width.toString()
                                resizeH = b.height.toString()
                                closeAllPanels()
                                showResize = true
                            }
                        },
                        MenuAction(
                            label = "旋转",
                            enabled = hasDoc,
                            dividerBefore = true,
                            children = listOf(
                                MenuAction("顺时针 90°（整张图）") {
                                    if (sketch?.rotateDocument(90) == true) { refreshLayers(); toast("已旋转") }
                                },
                                MenuAction("逆时针 90°（整张图）") {
                                    if (sketch?.rotateDocument(-90) == true) { refreshLayers(); toast("已旋转") }
                                },
                                MenuAction("180°（整张图）") {
                                    if (sketch?.rotateDocument(180) == true) { refreshLayers(); toast("已旋转") }
                                },
                                MenuAction("顺时针 15°（当前图层）", dividerBefore = true) {
                                    if (sketch?.rotateActiveLayer(15f) == true) { refreshLayers(); toast("当前层已转 15°") }
                                },
                                MenuAction("逆时针 15°（当前图层）") {
                                    if (sketch?.rotateActiveLayer(-15f) == true) { refreshLayers(); toast("当前层已转 -15°") }
                                },
                            ),
                        ),
                        MenuAction(
                            label = "翻转",
                            enabled = hasDoc,
                            children = listOf(
                                MenuAction("当前图层 · 水平") {
                                    if (sketch?.flipActiveLayer(true) == true) { refreshLayers(); toast("当前层已水平翻转") }
                                },
                                MenuAction("当前图层 · 垂直") {
                                    if (sketch?.flipActiveLayer(false) == true) { refreshLayers(); toast("当前层已垂直翻转") }
                                },
                                MenuAction("整张图 · 水平", dividerBefore = true) {
                                    if (sketch?.flipDocument(true) == true) { refreshLayers(); toast("已水平翻转") }
                                },
                                MenuAction("整张图 · 垂直") {
                                    if (sketch?.flipDocument(false) == true) { refreshLayers(); toast("已垂直翻转") }
                                },
                            ),
                        ),
                        MenuAction("置入图片…", enabled = hasDoc, dividerBefore = true) {
                            insertLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        MenuAction("裁掉透明边", enabled = hasDoc) {
                            if (sketch?.trimTransparent() == true) {
                                refreshLayers()
                                toast("已裁掉四周透明边")
                            } else {
                                toast("四周没有多余透明边")
                            }
                        },
                        MenuAction("调色…", enabled = hasDoc, dividerBefore = true) { openAdjust() },
                        MenuAction("色阶…", enabled = hasDoc) { openLevels() },
                        MenuAction("曲线…", enabled = hasDoc) { openCurves() },
                        MenuAction("抠图…", enabled = hasDoc) { openMatting() },
                    ),
                ),
                MenuGroup(
                    title = "图层",
                    actions = listOf(
                        MenuAction("图层面板…", enabled = hasDoc) {
                            refreshLayers()
                            openPanel("layers")
                        },
                        MenuAction("新建图层", enabled = hasDoc) {
                            sketch?.addLayer()
                            refreshLayers()
                        },
MenuAction(
                            label = "新建调整层",
                            enabled = hasDoc,
                            children = listOf(
                                // 空调整组：先建一个空容器，再用面板上的「＋叠加」往里加效果，
                                // 效果顺序由下往上作用在它下面的图层上（和 桌面端调整层一样）
                                MenuAction("空调整组（可叠加多个）") {
                                    val i = sketch?.addAdjustLayer(AdjustOp(kind = AdjustOp.KIND_CONTAINER)) ?: -1
                                    if (i >= 0) {
                                        refreshLayers()
                                        adjIndex = i
                                        adjOp = AdjustOp(kind = AdjustOp.KIND_CONTAINER)
                                        openPanel("adjustlayer")
                                        showAdjustLayer = true
                                        toast("已建空调整组，点面板上的「＋叠加」加效果")
                                    } else {
                                        toast("画布是空的")
                                    }
                                },
                                MenuAction("亮度/对比度") {
                                    val i = sketch?.addAdjustLayer(AdjustOp(kind = AdjustOp.KIND_BRIGHT_CONTRAST)) ?: -1
                                    if (i >= 0) {
                                        refreshLayers()
                                        adjIndex = i
                                        adjOp = AdjustOp(kind = AdjustOp.KIND_BRIGHT_CONTRAST)
                                        openPanel("adjustlayer")
                                        showAdjustLayer = true
                                    } else {
                                        toast("画布是空的")
                                    }
                                },
                                                                MenuAction("色阶") {
                                    val i = sketch?.addAdjustLayer(AdjustOp(kind = AdjustOp.KIND_LEVELS)) ?: -1
                                    if (i >= 0) {
                                        refreshLayers()
                                        adjIndex = i
                                        aopChannel = 0
                                        adjOp = AdjustOp(kind = AdjustOp.KIND_LEVELS)
                                        openPanel("adjustlayer")
                                        showAdjustLayer = true
                                    } else {
                                        toast("画布是空的")
                                    }
                                },
MenuAction("色相/饱和度") {
                                    val i = sketch?.addAdjustLayer(AdjustOp(kind = AdjustOp.KIND_HUE_SAT)) ?: -1
                                    if (i >= 0) {
                                        refreshLayers()
                                        adjIndex = i
                                        adjOp = AdjustOp(kind = AdjustOp.KIND_HUE_SAT)
                                        openPanel("adjustlayer")
                                        showAdjustLayer = true
                                    } else {
                                        toast("画布是空的")
                                    }
                                },
                            ),
                        ),
                        MenuAction("调整层参数…", enabled = hasDoc, dividerBefore = true) {
                            val i = sketch?.activeIndex ?: 0
                            val op = sketch?.adjustAt(i)
                            if (op != null) {
                                adjIndex = i
                                aopChannel = 0
                                // 调整组：面板一次编辑一项，默认编辑第一项
                                adjOp = if (op.kind == AdjustOp.KIND_CONTAINER)
                                    (op.children.firstOrNull() ?: AdjustOp())
                                else op
                                openPanel("adjustlayer")
                                showAdjustLayer = true
                            } else {
                                toast("当前层不是调整层")
                            }
                        },
                        MenuAction("向下合并", enabled = hasDoc && layerRows.size > 1, dividerBefore = true) {
                            if (sketch?.mergeDown() == true) { refreshLayers(); toast("已向下合并") }
                        },
                        MenuAction("拼合图像", enabled = hasDoc && layerRows.size > 1) {
                            if (sketch?.flattenAll() == true) { refreshLayers(); toast("已拼合") }
                        },
                        MenuAction("删除当前图层", enabled = hasDoc) {
                            if (sketch?.deleteActiveLayer() == true) refreshLayers() else toast("最后一层删不掉")
                        },
                        MenuAction(
                            label = "对齐",
                            enabled = hasDoc,
                            dividerBefore = true,
                            children = listOf(
                                MenuAction("左") { alignActive(0) },
                                MenuAction("水平居中") { alignActive(1) },
                                MenuAction("右") { alignActive(2) },
                                MenuAction("上", dividerBefore = true) { alignActive(3) },
                                MenuAction("垂直居中") { alignActive(4) },
                                MenuAction("下") { alignActive(5) },
                            ),
                        ),
                        MenuAction("上移一层", enabled = hasDoc, dividerBefore = true) {
                            if (sketch?.moveActiveLayer(true) == true) refreshLayers() else toast("已经是最上面了")
                        },
                        MenuAction("下移一层", enabled = hasDoc) {
                            if (sketch?.moveActiveLayer(false) == true) refreshLayers() else toast("已经是最下面了")
                        },
                    ),
                ),
                textMenuGroup(
                    hasDoc = hasDoc,
                    onTextTool = { tool = SketchView.Tool.TEXT },
                    onTextPanel = {
                        closeAllPanels()
                        showText = true
                    },
                    onRasterize = {
                        if (sketch?.rasterizeLayer(sketch?.activeIndex ?: 0) == true) {
                            refreshLayers()
                            toast("已栅格化文字")
                        } else {
                            toast("当前图层没有文字")
                        }
                    },
                ),
                selectionMenuGroup(hasDoc, hasSelection, sketch, {
                    closeAllPanels(); featherRadius = 6f; showFeather = true
                }) { toast(it) },
                filterMenuGroup(
                    hasDoc = hasDoc,
                    run = { label, action -> runFilter(label, action) },
                    onBlur = { openBlur() },
                    onThreshold = { openThreshold() },
                ),
                viewMenuGroup(
                    ViewMenuProps(
                        sketch = sketch,
                        hasDoc = hasDoc,
                        showGrid = showGrid,
                        pixelZoom = pixelZoom,
                        onOpenChannels = { openChannels() },
                        onOpenHistory = {
                            openPanel("history")
                            historyTick++
                        },
                        onToggleGrid = {
                            showGrid = !showGrid
                            sketch?.showGrid = showGrid
                        },
                        onTogglePixelZoom = {
                            pixelZoom = !pixelZoom
                            prefs.edit().putBoolean("pixelZoom", pixelZoom).apply()
                        },
                    ),
                ),
                windowMenuGroup(
                    showLayers = showLayers,
                    onToggleLayers = { showLayers = !showLayers },
                    onChannels = {
                        closeAllPanels()
                        showChannels = true
                    },
                    onHistory = {
                        openPanel("history")
                        historyTick++
                    },
                    onColor = {
                        closeAllPanels()
                        showColorDialog = true
                    },
                    onActions = {
                        closeAllPanels()
                        showActions = true
                    },
                ),
                MenuGroup(
                    title = "帮助",
                    actions = listOf(
                        MenuAction("Reveal $appVersion") { toast("Reveal $appVersion · 画得顺手的话请作者喝杯奶茶") },
                        MenuAction("自检…", dividerBefore = true) { showSelfTest = true },
                        MenuAction("错误日志…") { showCrashLog = true },
                    ),
                ),
        )


        // 桌面模式：顶部换成 桌面端那种菜单栏（同一份 menus，功能一个不少）

        var showRail by remember { mutableStateOf(false) }
        var colorBack by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ModeRail(
                current = rvMode,
                onSelect = { m ->
                    if (m != rvMode) {
                        rvMode = m
                        closeAllPanels()
                        barHidden = true
                        val label = when (m) {
                            RvMode.CUT -> "抠图"
                            RvMode.COLOR -> "调色"
                            RvMode.LAYOUT -> "排版"
                            RvMode.RETOUCH -> "修图"
                            RvMode.PAINT -> "绘制"
                            RvMode.AI -> "AI"
                        }
                        toast("切到「$label」：下面这排工具换成 $label 用的")
                    }
                },
            )

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { },
                ) {
                AndroidView(
                    factory = { ctx ->
                        SketchView(ctx).also { view ->
                            sketch = view
                                            view.onColorPicked = { picked ->
                                color = picked
                                tool = SketchView.Tool.BRUSH
                            }
                            view.onSelectionChanged = { hasSelection = it }
                            view.onHint = { msg -> toast(msg) }
                            view.onCloneSample = { toast("已取样，再涂就是复制这块") }
                            view.onTextTap = { x, y ->
                                closeAllPanels()
                                textAtX = x
                                textAtY = y
                                textDraft = ""
                                showText = true
                            }
                            view.onDocChanged = {
        autoSaveTick++
        psdEdited = true
        historyTick++
        // 文档被整张换掉（例如透视裁剪）时图层数量会变，顺手刷新图层面板
        if (view.layers.size != layerRows.size) refreshLayers()
        // 裁剪/透视/旋转之后画布尺寸会变，右上角那行字要跟着走
        view.activeLayer?.bitmap?.let { docInfo = "${it.width} × ${it.height}" }
    }
                            view.onMaskStroke = {
                                if (!maskHintShown) {
                                    maskHintShown = true
                                    toast("正在涂蒙版：黑=遮住，白=露出")
                                }
                            }
                            view.onCropChanged = { active ->
                                cropOn = active
                                if (active) { view.cropOnlyActive = false; cropScopeOnly = false }
                            }
                            view.onTransformChanged = { active ->
                                transformOn = active
                                // 每次进变换都从「自由变换」开始：四角=等比缩放，含义固定
                                if (active) {
                                    perspMode = false
                                    tfAngle = view.currentTransformRotation()
                                }
                                if (!active && tool == SketchView.Tool.TRANSFORM) tool = SketchView.Tool.BRUSH
                                if (!active) { perspMode = false; sketch?.setPerspectiveMode(false) }
                            }
                            // 换层了就同步：调色面板跟着切到新选中的那层，不然会对着旧图层调
                            // 手柄 / 双指转出来的角度回填到底部条，两边实时一致
                            view.onTransformRotation = { deg ->
                                tfAngle = deg
                            }
                            view.onActiveLayerChanged = {
                                specTick++
                                // 选中带形状 / 渐变参数的层时，把底部参数条露出来：面板开着也不挡
                                if (view.activeShapeSpec() != null || view.activeGradientSpec() != null) barHidden = false
                                refreshLayers()
                                maskEditing = view.maskEditing
                                if (showAdjust) {
                                    val snap = view.snapshotActiveLayer()
                                    if (snap != null) {
                                        adjustLayerIndex = view.activeIndex
                                        adjustLayerName = view.layers.getOrNull(view.activeIndex)?.name ?: "当前图层"
                                        adjustFullSource = snap
                                        adjustPreviewSource = ProjectStore.scaleDown(snap, PREVIEW_MAX_SIDE)
                                        adjustParams = ColorAdjust.Params()
                                    }
                                }
                            }
                            view.showTouchIndicator = showTouch
                            view.onSelectionMenu = { x, y ->
                                selMenuAt = androidx.compose.ui.geometry.Offset(x, y)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                    view.onCanvasDown = { if (showBrushDetail) showBrushDetail = false }
                        view.tool = tool
                        view.mouseMode = false
                        view.brushSize = brushSize
                        view.brushOpacity = opacity
                        view.brushColor = color
                        view.pixelZoom = pixelZoom
        view.brushPressure = pressureOn
            // 压感同时驱动不透明度（笔压小则淡），开关跟「数位板压感」共用
            view.brushPressureOpacity = pressureOn
            view.brushPressureHardness = pressureOn
            view.onZoomChanged = { pct -> zoomPct = pct }
            view.smudgeStrength = smudgeStrength
            view.smudgeMode = smudgeMode
            view.smudgeFinger = smudgeFinger
            view.toneStrength = toneStrength
            view.toneRange = toneRange
                        view.brushType = brushType
                        view.brushHardness = brushHardness
                        view.brushFlow = brushFlow
                        view.brushSpacing = brushSpacing
                        view.brushTaper = brushTaper
                        view.brushJitter = brushJitter
                        view.brushScatter = brushScatter
                        view.brushRoundness = brushRoundness
                        view.selectMode = selectMode
                        view.selectOp = selectOp
                    },
                )

                // 文档在这里套上去：放到 LaunchedEffect 里做，避免在 AndroidView 的 update 里改状态
                LaunchedEffect(pendingDoc, sketch) {
                    val doc = pendingDoc ?: return@LaunchedEffect
                    val view = sketch ?: return@LaunchedEffect
                    attachDocument(
                        view, doc,
                        maxOf(doc.depth, PsdBrowser.meta(context, projectId)?.depth ?: 8),
                    )
                    docName = doc.name
                    docInfo = "${doc.width} × ${doc.height}"
                    hasDoc = true
                    refreshLayers()
                    autoSaveTick = 0
                    pendingDoc = null
                }

                val menuAt = selMenuAt
                if (menuAt != null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.material3.DropdownMenu(
                        expanded = true,
                        onDismissRequest = { selMenuAt = null },
                        offset = with(density) {
                            androidx.compose.ui.unit.DpOffset(
                                menuAt.x.toDp(),
                                menuAt.y.toDp()
                            )
                        },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("全选") },
                            onClick = { selMenuAt = null; sketch?.selectAll() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("反选") },
                            onClick = { selMenuAt = null; sketch?.invertSelection() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("取消选择") },
                            onClick = { selMenuAt = null; sketch?.clearSelection() },
                        )
                        HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("填充前景色") },
                            onClick = {
                                selMenuAt = null
                                val ok = sketch?.fillSelection(color) ?: false
                                if (ok) refreshLayers()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("删除选区内容") },
                            onClick = {
                                selMenuAt = null
                                val ok = sketch?.deleteSelection() ?: false
                                if (ok) refreshLayers()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("复制选区到新图层（Ctrl+J）") },
                            onClick = {
                                selMenuAt = null
                                val ok = sketch?.duplicateSelectionLayer() ?: false
                                if (ok) {
                                    refreshLayers()
                                    toast("已复制到新图层")
                                }
                            },
                        )
                    }
                }

                if (busy) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF6EA8FF),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = busyText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (docInfo.isNotEmpty()) {
                    Text(
                        text = docInfo,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (showLevels || showCurves || showChannels || showAdjust || showMatting || showLayers) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                closeAllPanels()
                                sketch?.clearPreview()
                            }
                    )
                }

                if (cropOn) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFFFFFFFF),
                        contentColor = Color(0xFF22262B),
                        shadowElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 说明文字只占「剩下的空间」，按钮永远完整露出来
                            Text(
                                text = "拖边/角调整",
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                color = Color(0xFF6B7178),
                            )
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = {
                                    val v = sketch ?: return@TextButton
                                    v.cropOnlyActive = !v.cropOnlyActive
                                    cropScopeOnly = v.cropOnlyActive
                                },
                            ) {
                                Text(
                                    if (cropScopeOnly) "范围：当前层" else "范围：整张",
                                    fontSize = 12.sp,
                                    color = if (cropScopeOnly) Color(0xFF7AB8FF) else Color(0xFF22262B),
                                )
                            }
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = { sketch?.cancelCrop() },
                            ) { Text("取消", fontSize = 12.sp) }
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                onClick = {
                                    val done = sketch?.applyCrop() ?: false
                                    refreshLayers()
                                    val dw = sketch?.docPixelWidth() ?: 0
                                    val dh = sketch?.docPixelHeight() ?: 0
                                    if (dw > 0 && dh > 0) docInfo = "$dw × $dh"
                                    if (done) toast(
                                        if (cropScopeOnly) "已裁剪当前图层（画布尺寸不变）"
                                        else "已裁剪整张：$dw × $dh"
                                    ) else toast("裁剪框没变，什么都没裁（先拖一下框）")
                                },
                            ) { Text("应用", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }

                // 渐变条（状态与逻辑都在 GradientBar.kt 里，省主方法体）
                GradientSection(sketch, tool, specTick, Modifier.align(Alignment.BottomCenter))
                }
                if (showTextColor) {
                    ColorPickerPanel(
                        initial = if (editTextFor >= 0) textColorFor else color,
                        onColorChange = { c -> if (editTextFor >= 0) textColorFor = c else color = c },
                        onCancel = { showTextColor = false },
                        onConfirm = { c ->
                            if (editTextFor >= 0) textColorFor = c else color = c
                            showTextColor = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showText) {
                    TextPanel(
                        editing = editTextFor >= 0,
                        value = textDraft,
                        onValue = { textDraft = it },
                        size = textSize,
                        onSize = { textSize = it },
                        color = if (editTextFor >= 0) textColorFor else color,
                        onPickColor = {
                            showColorDialog = true
                            showTextColor = true
                        },
                        font = textFont,
                        spec = textSpec.copy(size = textSize, font = textFont),
                        onSpec = { textSpec = it },
                        pathNames = sketch?.pathNames() ?: emptyList(),
                        onFont = { key ->
                            textFont = key
                            // 「点哪款解压哪款」：第一次选某款内置字体时才把它从安装包里解出来
                            val builtin = com.reveal.editor.image.BundledFonts.find(key)
                            if (builtin != null) {
                                val first = com.reveal.editor.image.BundledFonts.ensureExtracted(context, key)
                                fontsVersion++
                                // 真的试着加载一次，别让「点了字体没变化」再悄悄发生
                                val tf = com.reveal.editor.image.BundledFonts.typeface(context, key)
                                if (tf == null) {
                                    toast("${builtin.label} 加载失败，先用默认字体")
                                } else if (first) {
                                    toast("${builtin.label} 已解压")
                                }
                            }
                        },
                        onManageFonts = { showFontMgr = true },
                        onPickFont = { showFontPicker = true },
                        fontsVersion = fontsVersion,
                        onCancel = {
                            showText = false
                            editTextFor = -1
                            sketch?.clearPreview()
                        },
                        onConfirm = {
                            val view2 = sketch
                            val useSpec = textSpec.copy(
                                content = textDraft, size = textSize, font = textFont,
                                color = if (editTextFor >= 0) textColorFor else color,
                            )
                            if (editTextFor >= 0) {
                                view2?.clearPreview()
                                val ok2 = view2?.updateTextLayerSpec(editTextFor, useSpec) == true
                                if (ok2) {
                                    refreshLayers()
                                    toast("文字已更新")
                                } else {
                                    toast("文字是空的")
                                }
                                editTextFor = -1
                            } else {
                                val ok = view2?.addTextLayerSpec(useSpec, textAtX, textAtY) == true
                                if (ok) {
                                    refreshLayers()
                                    toast("文字已加到新图层")
                                } else {
                                    toast("文字是空的")
                                }
                            }
                            showText = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 改字号/字距/变形等参数时，正在编辑的那层文字立刻按新参数重画（预览，不动撤销栈）
                    LaunchedEffect(editTextFor, textSpec, textSize, textFont, textDraft, textColorFor) {
                        val idx = editTextFor
                        val view3 = sketch
                        if (idx >= 0 && view3 != null && textDraft.isNotBlank()) {
                            kotlinx.coroutines.delay(60)
                            val s2 = textSpec.copy(
                                content = textDraft, size = textSize, font = textFont, color = textColorFor,
                            )
                            val bmp = withContext(Dispatchers.Default) { view3.renderTextSpecPreview(idx, s2) }
                            if (bmp != null) view3.setPreview(bmp, idx)
                        }
                    }
                }

                if (showMatting) {
                    MattingPanel(
                        autoKeyColor = mattingFullSource?.let {
                            Matting.guessBackgroundColor(it)
                        } ?: 0xFF00A650.toInt(),
                        onParams = { mattingParams = it },
                        onApply = { params ->
                            showMatting = false
                            mattingParams = null
                            val full = mattingFullSource
                            val view = sketch
                            mattingFullSource = null
                            mattingPreviewSource = null
                            if (full != null && view != null) {
                                busy = true
                                scope.launch {
                                    val nextFull = mattingNextFull
                                    val out = withContext(Dispatchers.Default) {
                                        applyMatting(params, full, nextFull)
                                    }
                                    view.replaceActiveLayer(out)
                                    view.clearPreview()
                                    busy = false
                                    refreshLayers()
                                    toast("抠好了，可撤销")
                                }
                            }
                        },
                        onCancel = {
                            showMatting = false
                            mattingParams = null
                            mattingFullSource = null
                            mattingPreviewSource = null
                            mattingNextFull = null
                            mattingNextPreview = null
                            sketch?.clearPreview()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        hasLayerBelow = mattingFullSource != null && mattingNextFull != null,
                    )
                }

                if (showLevels) {
                    LevelsPanel(
                        initial = levelsParams,
                        onParams = { levelsParams = it },
                        onApply = {
                            val full = levelsFullSource
                            val view = sketch
                            val p = levelsParams
                            showLevels = false
                            levelsFullSource = null
                            levelsPreviewSource = null
                            if (full != null && view != null) {
                                busy = true
                                scope.launch {
                                    val out = withContext(Dispatchers.Default) {
                                        Levels.apply(full, p) ?: full
                                    }
                                    view.replaceActiveLayer(out)
                                    view.clearPreview()
                                    busy = false
                                    refreshLayers()
                                    toast("色阶已应用，可撤销")
                                }
                            }
                        },
                        onCancel = {
                            showLevels = false
                            levelsFullSource = null
                            levelsPreviewSource = null
                            sketch?.clearPreview()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (showAdjustLayer) {
                    val aopEdit = adjOp
                    if (aopEdit != null) {
                        Surface(
                            color = Color(0xFFF4F5F7),
                            contentColor = Color(0xFF22262B),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(aopEdit.title() + " 调整层", fontSize = 12.sp, color = Color(0xFF22262B))
                                    Spacer(Modifier.width(6.dp))
                                    Text("拖完点完成", fontSize = 10.sp, color = Color(0xFF5F656C))
                                    Spacer(Modifier.width(6.dp))
                                    // 一个调整层里叠多个效果（桌面端要建好几层，这里合成一层）
                                    var addChildMenu by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { addChildMenu = true },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        ) { Text("＋叠加", fontSize = 11.sp, color = Color(0xFF6FA8FF)) }
                                        DropdownMenu(
                                            expanded = addChildMenu,
                                            onDismissRequest = { addChildMenu = false },
                                        ) {
                                            listOf(
                                                "亮度/对比度" to AdjustOp.KIND_BRIGHT_CONTRAST,
                                                "色相/饱和度" to AdjustOp.KIND_HUE_SAT,
                                                "色阶" to AdjustOp.KIND_LEVELS,
                                                "渐变映射" to AdjustOp.KIND_GRADIENT_MAP,
                                            ).forEach { pair ->
                                                DropdownMenuItem(
                                                    text = { Text(pair.first, fontSize = 12.sp) },
                                                    onClick = {
                                                        addChildMenu = false
                                                        val added = sketch?.appendAdjustChild(
                                                            adjIndex, AdjustOp(kind = pair.second)
                                                        )
                                                        if (added != null) {
                                                            adjOp = added
                                                            refreshLayers()
                                                            toast("已叠加：${pair.first}")
                                                        } else toast("这一层已经不在了")
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(
                                        onClick = {
                                            showAdjustLayer = false
                                            adjOp = null
                                            refreshLayers()
                                            toast("调整层已更新")
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) { Text("完成", fontSize = 12.sp) }
                                }
                    if (aopEdit.kind == AdjustOp.KIND_HUE_SAT) {
                        ThinSlider(
                            value = aopEdit.hue.toFloat(),
                            range = -180f..180f,
                            onChange = { v ->
                                val n = aopEdit.copyWith(hue = v.toInt())
                                adjOp = n
                                sketch?.setAdjust(adjIndex, n)
                            },
                        )
                        Text("色相 ${aopEdit.hue}° · 负数往红走，正数往青走", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = aopEdit.saturation.toFloat(),
                            range = -100f..100f,
                            onChange = { v ->
                                val n = aopEdit.copyWith(saturation = v.toInt())
                                adjOp = n
                                sketch?.setAdjust(adjIndex, n)
                            },
                        )
                        Text("饱和度 ${aopEdit.saturation} · -100 变黑白，+100 更艳", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = aopEdit.lightness.toFloat(),
                            range = -100f..100f,
                            onChange = { v ->
                                val n = aopEdit.copyWith(lightness = v.toInt())
                                adjOp = n
                                sketch?.setAdjust(adjIndex, n)
                            },
                        )
                        Text("明度 ${aopEdit.lightness}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (aopEdit.kind == AdjustOp.KIND_LEVELS) {
                        val ps = aopEdit.levelsOrDefault()
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("RGB", "红", "绿", "蓝").forEachIndexed { idx, name ->
                                TextButton(
                                    onClick = { aopChannel = idx },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        name,
                                        fontSize = 12.sp,
                                        color = if (aopChannel == idx) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        val cur = ps[aopChannel]
                        fun put(p: Levels.Params) {
                            val n = aopEdit.withChannel(aopChannel, p)
                            adjOp = n
                            sketch?.setAdjust(adjIndex, n)
                        }
                        ThinSlider(
                            value = cur.inBlack.toFloat(),
                            range = 0f..254f,
                            onChange = { v -> put(cur.copy(inBlack = v.toInt().coerceAtMost(cur.inWhite - 1))) },
                        )
                        Text("黑场 ${cur.inBlack}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = cur.gamma,
                            range = 0.1f..4f,
                            onChange = { v -> put(cur.copy(gamma = v)) },
                        )
                        Text("中间调 γ ${String.format("%.2f", cur.gamma)}（<1 变暗，>1 变亮）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = cur.inWhite.toFloat(),
                            range = 1f..255f,
                            onChange = { v -> put(cur.copy(inWhite = v.toInt().coerceAtLeast(cur.inBlack + 1))) },
                        )
                        Text("白场 ${cur.inWhite}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = cur.outBlack.toFloat(),
                            range = 0f..255f,
                            onChange = { v -> put(cur.copy(outBlack = v.toInt())) },
                        )
                        Text("输出黑 ${cur.outBlack}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = cur.outWhite.toFloat(),
                            range = 0f..255f,
                            onChange = { v -> put(cur.copy(outWhite = v.toInt())) },
                        )
                        Text("输出白 ${cur.outWhite}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (aopEdit.kind == AdjustOp.KIND_GRADIENT_MAP) {
                        fun putColors(lo: Int, hi: Int) {
                            val n = aopEdit.copy(gmLo = lo, gmHi = hi)
                            adjOp = n
                            sketch?.setAdjust(adjIndex, n)
                        }
                        val loR = (aopEdit.gmLo shr 16) and 0xFF
                        val loG = (aopEdit.gmLo shr 8) and 0xFF
                        val loB = aopEdit.gmLo and 0xFF
                        val hiR = (aopEdit.gmHi shr 16) and 0xFF
                        val hiG = (aopEdit.gmHi shr 8) and 0xFF
                        val hiB = aopEdit.gmHi and 0xFF
                        Text("暗部颜色（画面上最黑的地方）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(value = loR.toFloat(), range = 0f..255f, onChange = { v ->
                            putColors((0xFF shl 24) or (v.toInt().coerceIn(0, 255) shl 16) or (loG shl 8) or loB, aopEdit.gmHi)
                        })
                        Text("红 $loR", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(value = loG.toFloat(), range = 0f..255f, onChange = { v ->
                            putColors((0xFF shl 24) or (loR shl 16) or (v.toInt().coerceIn(0, 255) shl 8) or loB, aopEdit.gmHi)
                        })
                        Text("绿 $loG", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(value = loB.toFloat(), range = 0f..255f, onChange = { v ->
                            putColors((0xFF shl 24) or (loR shl 16) or (loG shl 8) or v.toInt().coerceIn(0, 255), aopEdit.gmHi)
                        })
                        Text("蓝 $loB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("亮部颜色（画面上最白的地方）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(value = hiR.toFloat(), range = 0f..255f, onChange = { v ->
                            putColors(aopEdit.gmLo, (0xFF shl 24) or (v.toInt().coerceIn(0, 255) shl 16) or (hiG shl 8) or hiB)
                        })
                        Text("红 $hiR", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(value = hiG.toFloat(), range = 0f..255f, onChange = { v ->
                            putColors(aopEdit.gmLo, (0xFF shl 24) or (hiR shl 16) or (v.toInt().coerceIn(0, 255) shl 8) or hiB)
                        })
                        Text("绿 $hiG", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(value = hiB.toFloat(), range = 0f..255f, onChange = { v ->
                            putColors(aopEdit.gmLo, (0xFF shl 24) or (hiR shl 16) or (hiG shl 8) or v.toInt().coerceIn(0, 255))
                        })
                        Text("蓝 $hiB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "不是方向渐变：按亮度映射 —— 暗的地方用「暗部颜色」，亮的地方用「亮部颜色」。" +
                                "想要上下/左右斜的过渡，那是「渐变」工具或渐变填充，不是渐变映射。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ThinSlider(
                            value = aopEdit.brightness.toFloat(),
                            range = -150f..150f,
                            onChange = { v ->
                                val n = aopEdit.copyWith(brightness = v.toInt())
                                adjOp = n
                                sketch?.setAdjust(adjIndex, n)
                            },
                        )
                        Text("亮度 ${aopEdit.brightness}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThinSlider(
                            value = aopEdit.contrast.toFloat(),
                            range = -50f..100f,
                            onChange = { v ->
                                val n = aopEdit.copyWith(contrast = v.toInt())
                                adjOp = n
                                sketch?.setAdjust(adjIndex, n)
                            },
                        )
                        Text("对比度 ${aopEdit.contrast}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                            }
                        }
                    }
                }

                if (showCurves) {
                    CurvesPanel(
                        initial = curvesParams,
                        onParams = { curvesParams = it },
                        onAuto = { ch -> sketch?.toneHistogram(ch) },
                        onPickStart = { ch ->
                            sketch?.curvePickChannel = ch
                            sketch?.onCurvePick = { v ->
                                curvePickVal = v
                                sketch?.curvePickMode = false
                            }
                            sketch?.curvePickMode = true
                            toast("点画面取一个点")
                        },
                        pickValue = curvePickVal,
                        onPickConsumed = { curvePickVal = null },
                        onSampleAll = { all -> sketch?.curvePickAllLayers = all },
                        onApply = {
                            val full = curvesFullSource
                            val view = sketch
                            val p = curvesParams
                            showCurves = false
                            curvesFullSource = null
                            curvesPreviewSource = null
                            if (full != null && view != null) {
                                busy = true
                                scope.launch {
                                    val res = withContext(Dispatchers.Default) { Curves.apply(full, p) }
                                    if (res == null) {
                                        view.clearPreview()
                                        busy = false
                                        toast("曲线没有改动")
                                        return@launch
                                    }
                                    view.replaceLayerAt(curvesLayerIndex, res)
                                    view.clearPreview()
                                    busy = false
                                    refreshLayers()
                                    toast("曲线已应用，可撤销")
                                }
                            }
                        },
                        onCancel = {
                            showCurves = false
                            curvesFullSource = null
                            curvesPreviewSource = null
                            sketch?.curvePickMode = false
                            sketch?.clearPreview()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (showChannels) {
                    ChannelsPanel(
                        thumb = channelsThumb,
                        active = channelView,
                        onSelect = {
                            channelView = it
                            sketch?.channelView = it
                        },
                        onClose = { showChannels = false },
                        modifier = Modifier.fillMaxWidth(),
                        onLoadSelection = { ch ->
                            val ok = sketch?.channelToSelection(ch) ?: false
                            toast(if (ok) "已按该通道亮度载入选区" else "这个通道没有超过阈值的像素")
                        },
                    )
                }

                if (showHistory) {
                    // historyTick 只用来触发重组，读一次就会跟着撤销栈变化刷新
                    @Suppress("UNUSED_EXPRESSION")
                    historyTick
                    val view = sketch
                    HistoryPanel(
                        labels = view?.historyLabels() ?: emptyList(),
                        position = view?.historyPosition() ?: 0,
                        onJump = { pos ->
                            view?.jumpToHistory(pos)
                            refreshLayers()
                            historyTick++
                        },
                        snapshots = view?.snapshots?.map { it.name } ?: emptyList(),
                        onSnapshot = {
                            toast(view?.takeSnapshot() ?: "画布还没准备好")
                            historyTick++
                        },
                        onRestoreSnapshot = { i ->
                            if (view?.restoreSnapshot(i) == true) {
                                refreshLayers()
                                historyTick++
                                toast("已回到这张快照")
                            } else {
                                toast("恢复不了：图层数变了，或者内存不够")
                            }
                        },
                        onDeleteSnapshot = { i ->
                            view?.deleteSnapshot(i)
                            historyTick++
                        },
                        onClose = { showHistory = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (fxId.isNotEmpty()) {
                    ColorFxDialog(id = fxId, sketch = sketch, onClose = { fxId = "" })
                }

                if (showEfx) EffectPanel(sketch = sketch, onClose = { showEfx = false })
        if (showActions) ActionBankPanel(sketch = sketch, onClose = { showActions = false })
        if (showBatch) BatchPanel(sketch = sketch, onClose = { showBatch = false })
    if (showComps) LayerCompPanel(sketch = sketch, onClose = { showComps = false })
        when (uiPanel) {
            "nav" -> NavigatorPanel(sketch = sketch, onClose = { uiPanel = null })
            "info" -> InfoPanel(sketch = sketch, onClose = { uiPanel = null })
            "action" -> ActionsPanel(sketch = sketch, onClose = { uiPanel = null })
            "hist" -> HistogramPanel(sketch = sketch, onClose = { uiPanel = null })
            else -> Unit
        }
                if (tfDialog == 1) {
                    TransformNumberDialog(
                        title = "拉直",
                        hint = "按度数摆正，再自动裁掉空白边",
                        initial = 0f,
                        range = -45f..45f,
                        unitText = ::fmtDeg,
                        onApply = {
                            sketch?.straighten(it)
                            refreshLayers()
                        },
                        onClose = { tfDialog = 0 },
                    )
                }
                if (tfDialog == 2) {
                    TransformNumberDialog(
                        title = "内容识别缩放",
                        hint = "主体等比，四周拉伸",
                        initial = 100f,
                        range = 10f..200f,
                        unitText = ::fmtPct,
                        onApply = {
                            applyContentScalePct(sketch, it)
                            refreshLayers()
                        },
                        onClose = { tfDialog = 0 },
                    )
                }
                LaunchedEffect(showBlur, blurRadius) {
                    if (!showBlur) return@LaunchedEffect
                    val small = blurSmall ?: return@LaunchedEffect
                    val full = blurFull ?: return@LaunchedEffect
                    delay(60)
                    val scale = small.width.toFloat() / full.width.toFloat()
                    val r = (blurRadius * scale).toInt().coerceAtLeast(1)
                    val out = withContext(Dispatchers.Default) { Filters.blur(small, r) }
                    sketch?.setPreview(out, blurLayer)
                }
                LaunchedEffect(showThreshold, thresholdLevel) {
                    if (!showThreshold) return@LaunchedEffect
                    val small = thresholdSmall ?: return@LaunchedEffect
                    delay(60)
                    val out = withContext(Dispatchers.Default) { Filters.threshold(small, thresholdLevel.toInt()) }
                    sketch?.setPreview(out, thresholdLayer)
                }
                if (showBlur) {
                    FilterPanel(
                        title = "高斯模糊",
                        hint = "半径 ${blurRadius.toInt()} 像素 · 拖的时候画布实时变化",
                        value = blurRadius,
                        range = 1f..40f,
                        onValue = { blurRadius = it },
                        onApply = { closeBlur(true) },
                        onCancel = { closeBlur(false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showThreshold) {
                    FilterPanel(
                        title = "阈值",
                        hint = "亮度高于 ${thresholdLevel.toInt()} 的变白，其余变黑",
                        value = thresholdLevel,
                        range = 0f..255f,
                        onValue = { thresholdLevel = it },
                        onApply = { closeThreshold(true) },
                        onCancel = { closeThreshold(false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
    if (showAdjust) {
        AdjustPanel(
            initial = adjustParams,
            layerName = adjustLayerName,
            onParams = { adjustParams = it },
            onApply = {
                val full = adjustFullSource
                val p = adjustParams
                if (full == null || p.isNeutral()) {
                    toast("还没调参数")
                    sketch?.clearPreview()
                } else {
                    busy = true
                    scope.launch {
                        val out = withContext(Dispatchers.Default) { ColorAdjust.apply(full, p) }
                        if (out != null) {
                            sketch?.replaceLayerAt(adjustLayerIndex, out)
                            refreshLayers()
                        }
                        sketch?.clearPreview()
                        busy = false
                    }
                }
                showAdjust = false
                adjustFullSource = null
                adjustPreviewSource = null
            },
            onCancel = {
                sketch?.clearPreview()
                showAdjust = false
                adjustFullSource = null
                adjustPreviewSource = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

                if (showLayers) {
                    LayerPanelHost(
                        sketch = sketch,
                        rows = layerRows,
                        maskEditing = maskEditing,
                        onRefresh = { refreshLayers() },
                        onToast = { msg -> toast(msg) },
                        onEditText = { index ->
                            val ly = sketch?.layers?.getOrNull(index)
                            val t = ly?.textContent
                            if (t == null) {
                                toast("这一层不是文字层")
                            } else {
                                // 一个功能只能开一个面板：进文字编辑前把图层面板收掉
                                closeAllPanels()
                                editTextFor = index
                                textDraft = t
                                textSize = ly.textSize
                                textColorFor = ly.textColor
                                textFont = ly.textFont
                                textSpec = sketch?.textSpecOf(index) ?: TextSpec()
                                showText = true
                            }
                        },
                        onLayerStyle = { index ->
                            showLayers = false
                            styleFor = index
                            showLayerStyle = true
                        },
                        onMaskEditing = { maskEditing = it },
                        onAdjustLayer = { index ->
                            val op = sketch?.adjustAt(index)
                            if (op != null) {
                                adjIndex = index
                                aopChannel = 0
                                adjOp = op
                                openPanel("adjustlayer")
                                showAdjustLayer = true
                            }
                        },
                        onPlaceImage = {
                            if (hasDoc) {
                                insertLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            } else {
                                toast("先新建或打开一个画布")
                            }
                        },
                        onClose = { showLayers = false },
                    )
                }

                    /** 左栏「修改」里点了一条：按 key 打开对应的参数面板 */
                    fun pickFeature(key: String) {
                        showModify = false
                        val idx = sketch?.activeIndex ?: 0
                        when (key) {
                            "shape" -> {
                                closeAllPanels()
                                tool = SketchView.Tool.SHAPE
                            }
                            "grad" -> closeAllPanels()
                            "text" -> {
                                val ly = sketch?.layers?.getOrNull(idx)
                                val t = ly?.textContent
                                if (t == null) {
                                    toast("这一层不是文字层")
                                } else {
                                    closeAllPanels()
                                    editTextFor = idx
                                    textDraft = t
                                    textSize = ly.textSize
                                    textColorFor = ly.textColor
                                    textFont = ly.textFont
                                    textSpec = sketch?.textSpecOf(idx) ?: TextSpec()
                                    showText = true
                                }
                            }
                            "style" -> {
                                closeAllPanels()
                                styleFor = idx
                                showLayerStyle = true
                            }
                            "mask" -> {
                                closeAllPanels()
                                maskEditing = true
                                sketch?.maskEditing = true
                            }
                            "adjust" -> {
                                closeAllPanels()
                                openAdjust()
                            }
                            "smartfx" -> {
                                closeAllPanels()
                                showEfx = true
                            }
                        }
                    }
                ModifyPanelHost(
                    show = showModify,
                    layerName = sketch?.layers?.getOrNull(sketch?.activeIndex ?: 0)?.name ?: "",
                    feats = remember(showModify, specTick) { sketch?.activeLayerFeatures() ?: emptyList() },
                    onDismiss = { showModify = false },
                    onPick = { key -> pickFeature(key) },
                )

                LayerStyleHost(
                    sketch = sketch,
                    show = showLayerStyle,
                    styleFor = styleFor,
                    onClose = { showLayerStyle = false },
                    onApplied = {
                        showLayerStyle = false
                        refreshLayers()
                    },
                )
            }
        }

        LaunchedEffect(tool) { barHidden = false }

        androidx.compose.runtime.CompositionLocalProvider(
            LocalBarClose provides { barHidden = true }
        ) {
            // 「选择并遮住」打开时直接接管底部这一条：以前它浮在根布局里顶到左上角，
            // 底下还压着选区工具的参数条，看着像两个界面叠着
            if (showSelMask) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    SelectMaskPanel(sketch = sketch, onClose = {
                        showSelMask = false
                        barHidden = false
                        toolBeforeSelMask?.let { tool = it }
                        toolBeforeSelMask = null
                    })
                }
            } else if (transformOn) {
                TransformBar(
                    perspMode = perspMode,
                    angle = tfAngle,
                    onAngle = {
                        tfAngle = it
                        sketch?.setTransformRotation(it)
                    },
                    onResetAngle = {
                        tfAngle = 0f
                        sketch?.setTransformRotation(0f)
                    },
                    onTogglePersp = {
                        val v = !perspMode
                        perspMode = v
                        sketch?.setPerspectiveMode(v)
                    },
                    onResetPersp = { sketch?.resetPerspective() },
                    onCancel = {
                        sketch?.cancelTransform()
                        perspMode = false
                        transformOn = false
                    },
                    onApply = {
                        val ok = sketch?.applyTransform() == true
                        perspMode = false
                        transformOn = false
                        refreshLayers()
                        if (!ok) toast("这次没有可以应用的变换（先拖角或拖边）")
                    },
                )
            } else {
                androidx.compose.animation.AnimatedVisibility(
            visible = !barHidden,
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)),
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(180),
            ),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it },
                animationSpec = androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)),
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(120),
            ),
        ) {
            optionsStrip()
        }
            }
        }
        // 颜色 / 不透明度 / 参数 留在底部（跟上面那行笔型分开）
        BottomBar(
            hasDoc = hasDoc,
            color = color,
            brushSize = brushSize,
            opacity = opacity,
            brushType = brushType,
            hardness = brushHardness,
            flow = brushFlow,
            spacing = brushSpacing,
        taper = brushTaper,
        jitter = brushJitter,
        scatter = brushScatter,
        roundness = brushRoundness,
            onColorClick = { showColorDialog = true },
            onSizeChange = { brushSize = it },
            onOpacityChange = { opacity = it },
            onBrushTypeChange = { brushType = it },
            onHardnessChange = { brushHardness = it },
            onFlowChange = { brushFlow = it },
            onSpacingChange = { brushSpacing = it },
        onTaperChange = { brushTaper = it },
        onJitterChange = { brushJitter = it },
        onScatterChange = { brushScatter = it },
        onRoundnessChange = { brushRoundness = it },
            showBrushDetail = showBrushDetail,
            onBrushDetailToggle = {
                if (showBrushDetail) showBrushDetail = false else openPanel("brush")
            },
            smudgeTool = tool == SketchView.Tool.SMUDGE,
            smudgeStrength = smudgeStrength,
            onSmudgeStrengthChange = { smudgeStrength = it },
            onLayers = {
                refreshLayers()
                if (showLayers) closeAllPanels() else openPanel("layers")
            },
            brushSketch = sketch,
            onBrushPreset = { p ->
                brushSize = p.size
                brushHardness = p.hardness
                brushFlow = p.flow
                brushSpacing = p.spacing
                brushRoundness = p.roundness
                brushType = if (p.pencil) SketchView.BRUSH_PENCIL else SketchView.BRUSH_ROUND
                sketch?.let { BrushPresets.apply(it, p) }
            },
        )
        // 工具行放在最底部（参数在它上面）
        ToolRail(
    tool = tool,
    hasDoc = hasDoc,
    mode = rvMode,
    onTool = { tool = it; closeAllPanels(); barHidden = false },
    onCategory = {
    // 类别层级高于工具：点分类先把工具收起来、参数条收起、面板关掉
    tool = SketchView.Tool.BRUSH
    closeAllPanels()
    barHidden = true
    showGrid = false
    sketch?.showGrid = false
    },
    brushType = brushType,
    onCrop = { openCrop() },
    onResetClone = {
    sketch?.resetCloneSource()
    toast("取样点已清除：点画面选新位置")
    },
    // 提示（组 / 无像素 / 锁定）由 SketchView 通过 onHint 弹出，这里只发起
    onTransform = { sketch?.beginTransform() },
    onAction = { id ->
    val addAdjust: (Int) -> Unit = { kind ->
    val i = sketch?.addAdjustLayer(AdjustOp(kind = kind)) ?: -1
    if (i >= 0) {
    refreshLayers()
    adjIndex = i
    adjOp = AdjustOp(kind = kind)
    openPanel("adjustlayer")
    showAdjustLayer = true
    } else {
    toast("画布是空的")
    }
    }
    when (id) {
    "selectAll" -> sketch?.selectAll()
    "invertSel" -> sketch?.invertSelection()
    "clearSel" -> sketch?.clearSelection()
    "matting" -> openMatting()
    "layers" -> openPanel("layers")
    "newLayer" -> { sketch?.addLayer(); refreshLayers() }
    "layerUp" -> if (sketch?.moveActiveLayer(true) == true) refreshLayers() else toast("已经是最上面了")
    "layerDown" -> if (sketch?.moveActiveLayer(false) == true) refreshLayers() else toast("已经是最下面了")
    "mergeDown" -> if (sketch?.mergeDown() == true) { refreshLayers(); toast("已向下合并") }
    "flatten" -> if (sketch?.flattenAll() == true) { refreshLayers(); toast("已拼合") }
    "mergeVisible" -> if (sketch?.mergeVisible() == true) { refreshLayers(); toast("已合并可见图层") } else toast("合并可见不可用")
    "stampVisible" -> if (sketch?.stampVisible() == true) { refreshLayers(); toast("已盖印") } else toast("盖印不可用")
    "rasterize" -> if (sketch?.rasterizeLayer(sketch?.activeIndex ?: 0) == true) { refreshLayers(); toast("已栅格化") } else toast("这一层不需要栅格化")
    "rasterizeText" -> if (sketch?.rasterizeLayer(sketch?.activeIndex ?: 0) == true) { refreshLayers(); toast("已栅格化文字") } else toast("当前层不是文字层")
    "allVisible" -> { sketch?.setAllLayersVisible(true); refreshLayers(); toast("已全部显示") }
    "allHidden" -> { sketch?.setAllLayersVisible(false); refreshLayers(); toast("已全部隐藏") }
    "copyStyle" -> if (sketch?.copyLayerStyle(sketch?.activeIndex ?: 0) == true) toast("已拷贝样式") else toast("这一层没有样式")
    "pasteStyle" -> if (sketch?.pasteLayerStyle(sketch?.activeIndex ?: 0) == true) { refreshLayers(); toast("已粘贴样式") } else toast("还没有拷贝过样式")
    "layer:smart" -> if (sketch?.makeSmartObject(sketch?.activeIndex ?: 0) == true) {
    refreshLayers(); toast("这一层现在是智能对象了，反复缩放旋转都不会糊")
    } else toast("当前图层不能转")
    "layer:resetsmart" -> if (sketch?.resetSmartObject(sketch?.activeIndex ?: 0) == true) {
    refreshLayers(); toast("已按原图还原")
    } else toast("当前图层不是智能对象")
    "layer:comps" -> { closeAllPanels(); showComps = true }
    "layer:smarteditch" -> {
    val i = sketch?.activeIndex ?: 0
    if (sketch?.isSmartLayer(i) == true) {
    smartEditIdx = i
    insertLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    } else toast("先「转为智能对象」，才能换里面的画面")
    }
    "similarLayers" -> {
    val view = sketch
    if (view == null) toast("还没有文档") else {
    val tag = view.layers.getOrNull(view.activeIndex)?.colorTag ?: 0
    toast("同色标签 " + view.selectSimilarLayers(tag).size + " 层")
    }
    }
    "deleteLayer" -> if (sketch?.deleteActiveLayer() == true) refreshLayers() else toast("最后一层删不掉")
    "copySel" -> if (sketch?.duplicateSelectionLayer() == true) { refreshLayers(); toast("已复制到新图层") }
    "resizeImage" -> {
    val dw = sketch?.docPixelWidth() ?: 0
    val dh = sketch?.docPixelHeight() ?: 0
    if (dw > 0 && dh > 0) { resizeW = dw.toString(); resizeH = dh.toString() }
    closeAllPanels(); showResize = true
    }
    "resizeCanvas" -> {
    val dw = sketch?.docPixelWidth() ?: 0
    val dh = sketch?.docPixelHeight() ?: 0
    if (dw > 0 && dh > 0) { canvasW = dw.toString(); canvasH = dh.toString() }
    closeAllPanels(); showCanvasSize = true
    }
    "rotCW" -> if (sketch?.rotateDocument(90) == true) { refreshLayers(); toast("已旋转") }
    "rotCCW" -> if (sketch?.rotateDocument(-90) == true) { refreshLayers(); toast("已旋转") }
    "rot180" -> if (sketch?.rotateDocument(180) == true) { refreshLayers(); toast("已旋转") }
    "flipH" -> if (sketch?.flipDocument(true) == true) { refreshLayers(); toast("已水平翻转") }
    "repeatTf" -> if (sketch?.repeatTransform() == true) { refreshLayers(); toast("已再次变换") } else toast("还没有做过变换")
    "straighten" -> tfDialog = 1
    "contentScale" -> tfDialog = 2
    "flipV" -> if (sketch?.flipDocument(false) == true) { refreshLayers(); toast("已垂直翻转") }
    "cfx:exposure", "cfx:vibrance", "cfx:colorBalance", "cfx:blackWhite",
    "cfx:photoFilter", "cfx:posterize", "cfx:gradientMap", "cfx:shadowsHighlights",
    "cfx:channelMixer", "cfx:equalize", "cfx:replace", "cfx:selective",
    "cfx:matchColor", "cfx:variation", "cfx:hdr",
    "cfx:autoTone", "cfx:autoContrast", "cfx:autoColor",
    "cfx:findEdges", "cfx:emboss", "cfx:motionBlur", "cfx:radialBlur",
    "cfx:boxBlur", "cfx:surfaceBlur", "cfx:unsharp", "cfx:smartSharpen", "cfx:addNoise",
    "cfx:median", "cfx:dust", "cfx:ripple", "cfx:spherize", "cfx:twirl",
    "cfx:pinch", "cfx:polar", "cfx:crystallize", "cfx:pointillize",
    "cfx:clouds", "cfx:lensFlare", "cfx:highPass", "cfx:oil", "cfx:lighting",
    "cfx:minimum", "cfx:maximum", "cfx:offset", "cfx:fragments",
    "cfx:colorHalftone", "cfx:gaussBlur", "cfx:reduceNoise", "cfx:cutout",
    "cfx:watercolor", "cfx:filmGrain", "cfx:lensCorrection", "cfx:adaptiveWide" -> fxId = id.substring(4)
    "efx:panel" -> { closeAllPanels(); showEfx = true }
    "efx:actions" -> { closeAllPanels(); showActions = true }
    "efx:batch" -> { closeAllPanels(); showBatch = true }
    "ui:nav" -> uiPanel = "nav"
    "ui:info" -> uiPanel = "info"
    "ui:action" -> uiPanel = "action"
    "ui:hist" -> uiPanel = "hist"
    "adjust" -> openAdjust()
    "adjust:new" -> { closeAllPanels(); showAdjustChooser = true }
    "levels" -> openLevels()
    "curves" -> openCurves()
    "adjustBright" -> addAdjust(AdjustOp.KIND_BRIGHT_CONTRAST)
    "adjustHue" -> addAdjust(AdjustOp.KIND_HUE_SAT)
    "adjustGradient" -> addAdjust(AdjustOp.KIND_GRADIENT_MAP)
    "fInvert" -> runFilter("反相") { b -> Filters.invert(b) }
    "fGray" -> runFilter("去色") { b -> Filters.grayscale(b) }
    "fBlur" -> openBlur()
    "fSharpen" -> runFilter("锐化") { b -> Filters.sharpen(b, 0.8f) }
    "fThreshold" -> openThreshold()
    "fMosaic" -> runFilter("马赛克") { b -> Filters.mosaic(b, 12) }
    "fWarm" -> runFilter("色彩平衡") { b -> Filters.colorBalance(b, 14, 2, -14) }
    "fCool" -> runFilter("色彩平衡") { b -> Filters.colorBalance(b, -14, 0, 16) }
    "fGreen" -> runFilter("色彩平衡") { b -> Filters.colorBalance(b, -8, 14, -6) }
    "modify" -> {
    closeAllPanels()
    specTick++
    showModify = true
    }
    "layerStyle" -> {
    closeAllPanels()
    val cur = sketch?.activeIndex ?: 0
    styleFor = cur
    showLayerStyle = true
    }
    "guideH" -> { sketch?.addGuideCenter(true); sketch?.invalidate() }
    "guideV" -> { sketch?.addGuideCenter(false); sketch?.invalidate() }
    "guideShow" -> {
    val v = sketch
    if (v != null) {
    v.showGuides = !v.showGuides
    v.invalidate()
    }
    }
    "guideSnap" -> {
    val v = sketch
    if (v != null) {
    v.snapGuides = !v.snapGuides
    toast(if (v.snapGuides) "吸附已开" else "吸附已关")
    }
    }
    "guideClear" -> { sketch?.clearGuides(); sketch?.invalidate() }
    "fitCanvas" -> sketch?.fitToView()
    "actualSize" -> sketch?.zoomToActualSize()
    "save" -> saveNow { toast("已保存") }
    "saveAs" -> psdSaveLauncher.launch(PsdShare.psdName(docName))
    "open" -> psdOpenLauncher.launch(arrayOf("*/*"))
    "export" -> if (hasDoc) showFormatDialog = true else toast("画布还没准备好")
    "share" -> shareCurrentPsd()
    "channels" -> openPanel("channels")
    "history" -> openPanel("history")
    "pencil" -> {
    tool = SketchView.Tool.BRUSH
    brushType = SketchView.BRUSH_PENCIL
    sketch?.brushType = SketchView.BRUSH_PENCIL
    }
    "brush" -> {
    tool = SketchView.Tool.BRUSH
    brushType = SketchView.BRUSH_ROUND
    sketch?.brushType = SketchView.BRUSH_ROUND
    }
    "eyedropper" -> tool = SketchView.Tool.EYEDROPPER
    "bucket" -> tool = SketchView.Tool.BUCKET
    }
    if (id == "sel:mask") {
    closeAllPanels()
    if (!showSelMask) toolBeforeSelMask = tool
    tool = SketchView.Tool.SELECT
    showSelMask = true
    } else if (id.startsWith("sel:")) {
    if (id == "sel:clear") {
    tool = SketchView.Tool.BRUSH
    barHidden = true
    runSelectionCommand(sketch, id)
    } else {
    tool = SketchView.Tool.SELECT
    selectModeFor(id)?.let { selectMode = it }
    runSelectionCommand(sketch, id)
    }
    }
    },
    )

    }

    // 右下角图层按钮：模式化之后图层面板从这里进（整个界面最显眼的一个入口）
    LayerFab(
        count = sketch?.layers?.size ?: 0,
        onClick = { openPanel("layers") },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 96.dp),
    )

    // AI 模式：工具还没接，先给一张说明卡，别让左栏空着像坏了
    if (rvMode == RvMode.AI) {
        AiPlaceholder(
            onPick = { m -> rvMode = m; closeAllPanels() },
            modifier = Modifier.align(Alignment.Center),
        )
    }
    }
    val clipboard = LocalClipboardManager.current
    if (showFontPicker) {
        FontPickerDialog(
            current = textFont,
            version = fontsVersion,
            onPick = { key ->
                showFontPicker = false
                textFont = key
                textSpec = textSpec.copy(font = key)
                val builtin = com.reveal.editor.image.BundledFonts.find(key)
                if (builtin != null) {
                    // 「点哪款解压哪款」：第一次用这款字体时才把它从安装包里解出来
                    val first = com.reveal.editor.image.BundledFonts.ensureExtracted(context, key)
                    fontsVersion++
                    if (first) toast("${builtin.label} 已解压")
                }
            },
            onManage = {
                showFontPicker = false
                showFontMgr = true
            },
            onClose = { showFontPicker = false },
        )
    }
    if (showFontMgr) {
        FontManagerDialog(
            onClose = { showFontMgr = false },
            onChanged = {
                fontsVersion++
                // 当前选中的字体被删了，就退回默认，别让参数条上显示一个已经不在的字体
                if (textFont.isNotEmpty() && com.reveal.editor.image.BundledFonts.isHidden(context, textFont)) {
                    textFont = ""
                    textSpec = textSpec.copy(font = "")
                }
            },
        )
    }
    if (showHelp) {
        val ver = remember(showHelp) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("帮助 · Reveal $ver", fontSize = 14.sp) },
            text = {
                Text(
                    text = """
顶部：左边是笔型（画笔），右边「参数」展开笔刷参数；
底部：颜色圆点、笔刷大小与不透明度、图层。

· 选区：左侧「选区」→ 选方式，点画面即可；蚂蚁线是矢量的
· 图层：长按图层可出菜单（合并/栅格化/盖印…）
· 导出：顶栏「保存」存工程，导出在菜单里（PNG/JPG/PSD）
· 卡住或报错：点下面的「错误日志」，再点「发送日志 TXT」，把文件发给我
""".trimIndent(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { showSelfTest = true; showHelp = false }) { Text("自检") }
                    TextButton(onClick = { showCrashLog = true; showHelp = false }) { Text("错误日志") }
                    TextButton(
                        onClick = {
                            val crash = com.reveal.editor.CrashLog.read(context)
                            val extra = if (crash.isBlank()) "" else "\n\n==== 崩溃记录 ====\n" + crash
                            val f = com.reveal.editor.data.Diag.exportText(context, extra)
                            if (f == null) {
                                toast("导出日志失败")
                            } else {
                                runCatching {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", f,
                                    )
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Reveal 日志")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(send, "发送日志"))
                                }.onFailure { toast("没有可用的发送应用") }
                            }
                        },
                    ) { Text("发送日志 TXT") }
                    TextButton(onClick = { showHelp = false }) { Text("知道了") }
                }
            },
        )
    }
    if (showSelfTest) {
        val report = remember(showSelfTest) { com.reveal.editor.SelfTest.run() }
        val cb = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showSelfTest = false },
            title = { Text("自检结果", fontSize = 14.sp) },
            text = {
                Text(
                    report,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        cb.setText(AnnotatedString(report))
                        toast("自检结果已复制")
                    }) { Text("复制") }
                    TextButton(onClick = { showSelfTest = false }) { Text("知道了") }
                }
            },
        )
    }
    if (showCrashLog) {
        AlertDialog(
            onDismissRequest = { showCrashLog = false },
            title = { Text("诊断 / 错误日志", fontSize = 14.sp) },
            text = {
                val text = remember(showCrashLog) {
                    val crash = com.reveal.editor.CrashLog.read(context)
                    com.reveal.editor.data.Diag.read(context) +
                        if (crash.isBlank()) "\n（没有崩溃记录）" else "\n\n==== 崩溃记录 ====\n" + crash
                }
                Text(
                    text = text,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            val crash = com.reveal.editor.CrashLog.read(context)
                            clipboard.setText(
                                AnnotatedString(
                                    com.reveal.editor.data.Diag.read(context) +
                                        if (crash.isBlank()) "" else "\n\n==== 崩溃记录 ====\n" + crash
                                )
                            )
                            toast("日志已复制")
                        },
                    ) { Text("复制") }
                    TextButton(
                        onClick = {
                            val crash = com.reveal.editor.CrashLog.read(context)
                            val extra = if (crash.isBlank()) "" else "\n\n==== 崩溃记录 ====\n" + crash
                            val f = com.reveal.editor.data.Diag.exportText(context, extra)
                            if (f == null) {
                                toast("导出日志失败")
                            } else {
                                runCatching {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", f,
                                    )
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Reveal 日志")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(send, "发送日志"))
                                }.onFailure { toast("没有可用的发送应用") }
                            }
                        },
                    ) { Text("发送") }
                    TextButton(onClick = { showCrashLog = false }) { Text("知道了") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        com.reveal.editor.CrashLog.clear(context)
                        runCatching { com.reveal.editor.data.Diag.logFile(context).delete() }
                        showCrashLog = false
                        toast("日志已清空")
                    },
                ) { Text("清空") }
            },
        )
    }

    if (showCanvasSize) {
        SizeDialog(
            title = "画布大小",
            hint = "原图居中，四周补透明（不会缩放内容）；会清空撤销历史。",
            initialW = canvasW,
            initialH = canvasH,
            onDismiss = { showCanvasSize = false },
            onApply = { w, h, _ ->
                if (sketch?.resizeCanvas(w, h) == true) {
                    refreshLayers()
                    val dw = sketch?.docPixelWidth() ?: w
                    val dh = sketch?.docPixelHeight() ?: h
                    docInfo = "$dw × $dh"
                    toast("画布已改为 $dw × $dh（共 ${sketch?.layers?.size ?: 0} 层）")
                }
                showCanvasSize = false
            },
        )
    }

    val addAdjustFromChooser: (Int) -> Unit = { kind ->
        val i = sketch?.addAdjustLayer(AdjustOp(kind = kind)) ?: -1
        if (i >= 0) {
            refreshLayers()
            adjIndex = i
            adjOp = AdjustOp(kind = kind)
            openPanel("adjustlayer")
            showAdjustLayer = true
            if (kind == AdjustOp.KIND_CONTAINER)
                toast("已建空调整组，点面板上的「＋叠加」加效果")
        } else toast("画布是空的")
    }
    if (showAdjustChooser) {
        AlertDialog(
            onDismissRequest = { showAdjustChooser = false },
            title = { Text("新建调整图层", fontSize = 16.sp) },
            text = {
                Column {
                    Text("调整层只影响它下面的图层；一个组里可以叠多个效果", fontSize = 11.sp,
                        color = Color(0xFF6B7178))
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "空调整组（可叠加多个效果）" to AdjustOp.KIND_CONTAINER,
                        "亮度/对比度" to AdjustOp.KIND_BRIGHT_CONTRAST,
                        "色相/饱和度" to AdjustOp.KIND_HUE_SAT,
                        "色阶" to AdjustOp.KIND_LEVELS,
                        "渐变映射" to AdjustOp.KIND_GRADIENT_MAP,
                    ).forEach { pair ->
                        Text(
                            pair.first,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAdjustChooser = false
                                    addAdjustFromChooser(pair.second)
                                }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider(thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdjustChooser = false }) { Text("取消") }
            },
        )
    }
    if (showResize) {
        SizeDialog(
            title = "图像大小",
            hint = "改大是插值放大会变糊，改小不可逆；会清空撤销历史。",
            initialW = resizeW,
            initialH = resizeH,
            showScope = true,
            onDismiss = { showResize = false },
            onApply = { w, h, onlyActive ->
                sketch?.resizeOnlyActive = onlyActive
                if (sketch?.resizeDocument(w, h) == true) {
                    refreshLayers()
                    val dw = sketch?.docPixelWidth() ?: w
                    val dh = sketch?.docPixelHeight() ?: h
                    docInfo = "$dw × $dh"
                    // 只改当前层时画布尺寸没变，还报「已改为 1080 × 1620」会让人以为没生效
                    if (onlyActive) toast("已把当前图层缩放为 $w × $h（画布仍是 $dw × $dh）")
                    else toast("已改为 $dw × $dh（共 ${sketch?.layers?.size ?: 0} 层）")
                }
                showResize = false
            },
        )
    }
    if (showFeather) {
        AlertDialog(
            onDismissRequest = { showFeather = false },
            title = { Text("羽化选区", fontSize = 16.sp) },
            text = {
                Column {
                    ThinSlider(
                        value = featherRadius,
                        range = 1f..50f,
                        onChange = { featherRadius = it },
                    )
                    Text(
                        "半径 ${featherRadius.toInt()} 像素：数字越大边缘越软",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sketch?.featherSelection(featherRadius.toInt())
                    showFeather = false
                    toast("选区已羽化")
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showFeather = false }) { Text("取消") }
            },
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("设置", fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("放大时按像素显示", fontSize = 13.sp)
                        Text(
                            text = "关：放大像看相册，边缘平滑。开：放大后是一个个像素点。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = pixelZoom,
                        onCheckedChange = {
                            pixelZoom = it
                            prefs.edit().putBoolean("pixelZoom", it).apply()
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("数位板压感", fontSize = 13.sp)
                        Text(
                            text = "开：接绘图笔时按压力变粗细（越用力越粗）。手指画不受影响。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = pressureOn,
                        onCheckedChange = {
                            pressureOn = it
                            prefs.edit().putBoolean("penPressure", it).apply()
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示触摸位置", fontSize = 13.sp)
                        Text(
                            text = "手指按在画布上时显示一个圆环，大小等于当前笔刷；抬手消失。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showTouch,
                        onCheckedChange = {
                            showTouch = it
                            prefs.edit().putBoolean("showTouch", it).apply()
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showSettings = false
                            openAdjust()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("调色", fontSize = 13.sp)
                        Text(
                            text = "底部面板调色：只调当前选中的图层，按专业调色的五组来（基础/亮/颜色/效果/细节）。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("进入", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("好") }
            },
        )
    }

    if (showColorDialog) {
        ColorPickerDialog(
            initial = color,
            onDismiss = { showColorDialog = false },
            onPick = {
                color = it
                showColorDialog = false
                if (tool == SketchView.Tool.ERASER) tool = SketchView.Tool.BRUSH
            },
        )
    }
}

/** 按面板选的模式走不同算法。next 只在「混合色带 + 参考下一图层」时用。 */
private fun applyMatting(params: MattingParams, src: Bitmap, next: Bitmap? = null): Bitmap =
    when (params.mode) {
        1 -> Matting.levelsCut(src, params.channel, params.black, params.white, params.invert)
        2 -> Matting.bandCut(
            src = src,
            lo = params.bandLo,
            hi = params.bandHi,
            invert = params.bandInvert,
            next = if (params.useNextLayer) next else null,
            nextLo = params.nextLo,
            nextHi = params.nextHi,
        )
        else -> Matting.chromaKey(src, params.keyColor, params.tolerance, 0.12f, params.spill)
    }

@Composable
private fun TopBar(
    title: String,
    hasDoc: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    menus: List<MenuGroup>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onUndo, enabled = hasDoc) {
                Icon(Icons.AutoMirrored.Filled.Undo, "撤销", tint = tint(hasDoc))
            }
            IconButton(onClick = onRedo, enabled = hasDoc) {
                Icon(Icons.AutoMirrored.Filled.Redo, "重做", tint = tint(hasDoc))
            }
            IconButton(onClick = onSave, enabled = hasDoc) {
                Icon(Icons.Filled.Save, "保存", tint = tint(hasDoc))
            }
            IconButton(onClick = onExport, enabled = hasDoc) {
                Icon(Icons.Filled.IosShare, "导出", tint = tint(hasDoc))
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        PsMenuBar(
            menus = menus,
            modifier = Modifier.padding(start = 4.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
private fun tint(enabled: Boolean) = if (enabled) {
    MaterialTheme.colorScheme.onSurfaceVariant
} else {
    MaterialTheme.colorScheme.outline
}

@Composable
private fun ToolRail(
    tool: SketchView.Tool,
    hasDoc: Boolean,
    mode: RvMode = RvMode.CUT,
    onTool: (SketchView.Tool) -> Unit,
    onCrop: () -> Unit,
    onResetClone: () -> Unit,
    onTransform: () -> Unit,
    onAction: (String) -> Unit,
    onCategory: () -> Unit = {},
    brushType: Int = SketchView.BRUSH_ROUND,
) {
    var categoriesMode by remember { mutableStateOf(false) }
    var openCat by remember { mutableStateOf(-1) }
    var pinnedText by remember { mutableStateOf("brush,eraser") }
    val pinned = remember(pinnedText) { pinnedText.split(',').filter { it.isNotEmpty() } }

    fun togglePin(id: String) {
        val list = pinned.toMutableList()
        if (!list.remove(id)) list.add(id)
        pinnedText = list.joinToString(",")
    }

    val allCats = railCategories(tool, hasDoc, onTool, onCrop, onResetClone, onTransform, onAction, brushType)
    // 只显示当前模式这一套工具（+ 图层/样式/辅助/文件/面板这些全局类目）
    val cats = filterForMode(allCats, mode)
    val byId = remember(cats) { cats.flatMap { it.entries }.associateBy { it.id } }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }

    // 切模式时整列淡入一下（左栏整套工具换掉，不提示会显得是被刷掉了）
    val railFade = androidx.compose.animation.core.Animatable(1f)
    // 切模式时整套工具先轻轻下沉再归位（整列 8dp 滑入 + 淡入），比纯淡入更有「换了一套工具」的实感
    val railSlide = androidx.compose.animation.core.Animatable(8f)
    LaunchedEffect(mode) {
        railFade.snapTo(0.3f)
        railSlide.snapTo(8f)
        launch { railFade.animateTo(1f, androidx.compose.animation.core.tween(220, easing = RvEase)) }
        launch { railSlide.animateTo(0f, androidx.compose.animation.core.tween(260, easing = RvEase)) }
    }
    Surface(
        color = Rv.surface,
        modifier = Modifier.graphicsLayer {
            alpha = railFade.value
            translationY = railSlide.value
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (categoriesMode) {
                    cats.forEachIndexed { i, c ->
                        RailItem(c.label, c.icon, openCat == i, true) {
                            if (openCat != i) onCategory()
                            openCat = if (openCat == i) -1 else i
                        }
                    }
                } else {
                    pinned.forEach { id ->
                        val e = byId[id] ?: return@forEach
                        RailItem(e.label, e.icon, e.active, e.enabled) { e.onClick() }
                    }
                }
            }
        }
        if (categoriesMode && openCat >= 0 && openCat < cats.size) {
            val cat = cats[openCat]
            Popup(
                alignment = Alignment.BottomStart,
                onDismissRequest = { openCat = -1 },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFFFFFFF),
                    contentColor = Color(0xFF22262B),
                    shadowElevation = 10.dp,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.padding(10.dp).width(236.dp)) {
                        Text(cat.fullName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "点一下就用；长按固定到左边，再长按取消",
                            fontSize = 9.sp,
                            color = Color(0xFF6B7178),
                        )
                        Spacer(Modifier.height(6.dp))
                        if (cat.entries.isEmpty()) {
                            Text("这一类还没做", fontSize = 11.sp, color = Color(0xFF6B7178),
                                modifier = Modifier.padding(vertical = 8.dp))
                        } else {
                            // 高度跟着条目数走：以前固定 3 行（215dp），条目多的分类
                            // （调色 29 项、滤镜 40+ 项）后面一大半被挡住，用户会以为
                            // 「没有这个功能」。每行 54dp + 分节标题 22dp，上限半屏左右。
                            val rows = cat.entries
                                .groupBy { it.section }
                                .entries
                                .sumOf { e -> (if (e.key.isNotEmpty()) 1 else 0) + (e.value.size + 3) / 4 }
                            val popH = (rows * 54 + 24).coerceIn(120, 430)
                            Column(
                                modifier = Modifier
                                    .heightIn(max = popH.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                cat.entries.groupBy { it.section }.forEach { entry ->
                                    val secName = entry.key
                                    val secItems = entry.value
                                    if (secName.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp, bottom = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(secName, fontSize = 9.sp, color = Color(0xFF6B7178))
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                Modifier
                                                    .weight(1f)
                                                    .height(1.dp)
                                                    .background(Color(0xFFE8EBEF))
                                            )
                                        }
                                    }
                                    secItems.chunked(4).forEach { rowItems ->
                                        Row {
                                            rowItems.forEach { item ->
                                                RailCell(
                                                    item = item,
                                                    pinned = pinned.contains(item.id),
                                                    onLong = { togglePin(item.id) },
                                                ) {
                                                    openCat = -1
                                                    item.onClick()
                                                }
                                            }
                                            repeat(4 - rowItems.size) { Spacer(Modifier.width(54.dp)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    if (searchOpen) {
        SearchPanel(
            cats = cats,
            onDismiss = { searchOpen = false },
            onPick = { e -> searchOpen = false; if (e.enabled) e.onClick() },
        )
    }
}

/** 左侧栏里的一个条目（工具 / 面板 / 命令） */
internal class RailEntry(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val section: String = "",
    val onClick: () -> Unit,
)

/** 左侧栏的一个类别 */
internal class RailCategory(
    val label: String,
    val fullName: String,
    val icon: ImageVector,
    val entries: List<RailEntry>,
)

/** 13 个类别。没做的类别也留着，打开会显示「这一类还没做」 */
internal fun railCategories(
    tool: SketchView.Tool,
    hasDoc: Boolean,
    onTool: (SketchView.Tool) -> Unit,
    onCrop: () -> Unit,
    onResetClone: () -> Unit,
    onTransform: () -> Unit,
    onAction: (String) -> Unit,
    brushType: Int = SketchView.BRUSH_ROUND,
): List<RailCategory> {
    fun t(id: String, label: String, icon: ImageVector, tt: SketchView.Tool) =
        RailEntry(id, label, icon, tool == tt, hasDoc) { onTool(tt) }
    fun a(
        id: String,
        label: String,
        icon: ImageVector,
        enabled: Boolean = true,
        section: String = "",
    ) = RailEntry(id, label, icon, false, hasDoc && enabled, section = section) { onAction(id) }
    fun av(id: String, label: String, icon: ImageVector, active: Boolean) =
        RailEntry(id, label, icon, active, hasDoc) { onAction(id) }
    return listOf(
        RailCategory(
            "绘制", "画笔 / 橡皮 / 涂抹 / 减淡", Icons.Filled.Brush,
            listOf(
                RailEntry(
                    "brush", "画笔", Icons.Filled.Brush,
                    tool == SketchView.Tool.BRUSH && brushType != SketchView.BRUSH_PENCIL,
                    hasDoc,
                ) { onAction("brush") },
                RailEntry(
                    "pencil", "铅笔", Icons.Filled.Create,
                    tool == SketchView.Tool.BRUSH && brushType == SketchView.BRUSH_PENCIL,
                    hasDoc,
                ) { onAction("pencil") },
                t("replace", "颜色替换", Icons.Filled.ColorLens, SketchView.Tool.REPLACE),
                t("mixer", "混合器", Icons.Filled.Opacity, SketchView.Tool.MIXER),
                t("eraser", "橡皮擦", Icons.Filled.AutoFixNormal, SketchView.Tool.ERASER),
                t("eraserBg", "背景橡皮", Icons.Filled.AutoFixOff, SketchView.Tool.ERASER_BG),
                t("eraserMagic", "魔术橡皮", Icons.Filled.AutoFixHigh, SketchView.Tool.ERASER_MAGIC),
                t("smudge", "涂抹", Icons.Filled.BlurOn, SketchView.Tool.SMUDGE),
                t("dodge", "减淡", Icons.Filled.Brightness7, SketchView.Tool.DODGE),
                t("burn", "加深", Icons.Filled.Brightness4, SketchView.Tool.BURN),
                t("sponge", "海绵", Icons.Filled.FilterAlt, SketchView.Tool.SPONGE),
                t("clone", "仿制图章", Icons.Filled.ContentCopy, SketchView.Tool.CLONE),
                t("pattern", "图案图章", Icons.Filled.GridView, SketchView.Tool.PATTERN),
                t("heal", "修复画笔", Icons.Filled.Healing, SketchView.Tool.HEAL),
                t("spot", "污点修复", Icons.Filled.Build, SketchView.Tool.SPOT),
                t("redeye", "红眼", Icons.Filled.RemoveRedEye, SketchView.Tool.REDEYE),
                t("hist", "历史画笔", Icons.Filled.History, SketchView.Tool.HISTORY_BRUSH),
                t("artHist", "艺术历史", Icons.Filled.Palette, SketchView.Tool.ART_HISTORY),
            ),
        ),
        RailCategory(
            "选区", "选区 / 抠图", Icons.Filled.CropSquare,
            listOf(
                // 形状类（矩形/椭圆/单行/单列/套索/多边形/磁性）统一收在「矩形选框」里，
                // 进参数条的「方式」那一行切；魔棒/快速选择是完全不同的工具，单独列。
                a("sel:rect", "矩形选框", Icons.Filled.CropSquare),
                // 椭圆/套索以前只藏在矩形选框的「方式」那一行里，用户根本找不到，
                // 想圈一个圆形物体只能用矩形框，看着就像「选区出来是个方框」。这里直接列出来。
                a("sel:ellipse", "椭圆选框", Icons.Filled.Circle),
                a("sel:lasso", "套索", Icons.Filled.Gesture),
                a("sel:poly", "多边形", Icons.Filled.Timeline),
                a("sel:wand", "魔棒", Icons.Filled.AutoFixHigh),
                a("sel:quick", "快速选择", Icons.Filled.AdsClick),
                a("sel:range", "色彩范围", Icons.Filled.InvertColors),
                a("sel:subject", "选择主体", Icons.Filled.CenterFocusStrong),
                a("sel:all", "全选", Icons.Filled.SelectAll),
                a("sel:invert", "反选", Icons.Filled.Contrast),
                a("sel:clear", "取消选区", Icons.Filled.Deselect),
                a("sel:save", "存储选区", Icons.Filled.Save),
                a("sel:load", "载入选区", Icons.Filled.FolderOpen),
                a("sel:mask", "蒙版 / 抠图", Icons.Filled.Tune),
            ),
        ),
        RailCategory(
            "图层", "图层", Icons.Filled.Layers,
            listOf(
                a("layers", "图层面板", Icons.Filled.Layers),
                a("mergeDown", "向下合并", Icons.Filled.CallMerge),
                a("flatten", "拼合图像", Icons.Filled.LayersClear),
                RailEntry("move", "移动", Icons.Filled.OpenWith, tool == SketchView.Tool.MOVE, hasDoc) {
                    onTool(SketchView.Tool.MOVE)
                },
                RailEntry("copySel", "选区复制成层", Icons.Filled.ContentCopy, false, hasDoc) {
                    onAction("copySel")
                },
                a("mergeVisible", "合并可见", Icons.Filled.CallMerge),
                a("stampVisible", "盖印可见", Icons.Filled.CallMerge),
                a("rasterize", "栅格化图层", Icons.Filled.PhotoSizeSelectLarge),
                a("rasterizeText", "栅格化文字", Icons.Filled.Create),
                a("allVisible", "全部显示", Icons.Filled.Visibility),
                a("allHidden", "全部隐藏", Icons.Filled.Visibility),
                a("copyStyle", "拷贝图层样式", Icons.Filled.ContentCopy),
                a("pasteStyle", "粘贴图层样式", Icons.Filled.SaveAlt),
                a("layerStyle", "图层样式…", Icons.Filled.AutoAwesome),
                a("similarLayers", "选择同色标签", Icons.Filled.SelectAll),
                a("layer:smart", "转为智能对象", Icons.Filled.AutoAwesome),
                a("layer:resetsmart", "智能对象还原", Icons.Filled.Refresh),
            a("layer:comps", "图层复合…", Icons.Filled.Layers),
            a("layer:smarteditch", "换智能对象内容…", Icons.Filled.AutoAwesome),
            ),
        ),
        RailCategory(
            "变换", "变换 / 裁剪 / 透视", Icons.Filled.Crop,
            listOf(
                RailEntry("transform", "变换", Icons.Filled.AspectRatio, tool == SketchView.Tool.TRANSFORM, hasDoc) {
                    onTool(SketchView.Tool.TRANSFORM)
                    onTransform()
                },
                RailEntry("crop", "裁剪", Icons.Filled.Crop, false, hasDoc) { onCrop() },
                a("resizeImage", "图像大小", Icons.Filled.PhotoSizeSelectLarge),
                a("resizeCanvas", "画布大小", Icons.Filled.CropFree),
                a("rotCW", "右转 90°", Icons.Filled.RotateRight),
                a("rotCCW", "左转 90°", Icons.Filled.RotateLeft),
                a("rot180", "转 180°", Icons.Filled.Rotate90DegreesCcw),
                a("flipH", "水平翻转", Icons.Filled.Flip),
                a("flipV", "垂直翻转", Icons.Filled.Flip),
                a("repeatTf", "再次变换", Icons.Filled.Refresh),
                a("straighten", "拉直…", Icons.Filled.Straighten),
                a("contentScale", "内容识别缩放…", Icons.Filled.PhotoSizeSelectLarge),
            ),
        ),
        RailCategory(
            "调色", "调色", Icons.Filled.Tune,
            listOf(
                // 放在最前面、单独一格：以前「新建调整图层」只是分组标题，夹在一堆小方格中间，用户找不到
                a("adjust:new", "调整图层…", Icons.Filled.Layers),
                a("adjust", "调色…", Icons.Filled.Tune, section = "普通调色"),
                a("levels", "色阶…", Icons.Filled.ShowChart, section = "普通调色"),
                a("curves", "曲线…", Icons.Filled.Timeline, section = "普通调色"),
                a("fInvert", "反相", Icons.Filled.InvertColors, section = "普通调色"),
                a("fGray", "去色", Icons.Filled.Brightness4, section = "普通调色"),
                a("fThreshold", "阈值…", Icons.Filled.Contrast, section = "普通调色"),
                a("adjustBright", "亮度/对比度", Icons.Filled.Brightness6, section = "新建调整图层"),
                a("adjustHue", "色相/饱和度", Icons.Filled.ColorLens, section = "新建调整图层"),
                a("cfx:exposure", "曝光度…", Icons.Filled.WbSunny, section = "专业调色"),
                a("cfx:vibrance", "自然饱和度…", Icons.Filled.AutoAwesome, section = "专业调色"),
                a("cfx:colorBalance", "色彩平衡…", Icons.Filled.Contrast, section = "专业调色"),
                a("cfx:blackWhite", "黑白…", Icons.Filled.Contrast, section = "专业调色"),
                a("cfx:photoFilter", "照片滤镜…", Icons.Filled.ColorLens, section = "专业调色"),
                a("cfx:posterize", "色调分离…", Icons.Filled.FilterAlt, section = "专业调色"),
                a("cfx:gradientMap", "渐变映射…", Icons.Filled.FilterAlt, section = "专业调色"),
                a("cfx:shadowsHighlights", "阴影/高光…", Icons.Filled.Brightness6, section = "专业调色"),
                a("cfx:channelMixer", "通道混合器…", Icons.Filled.Tune, section = "专业调色"),
                a("cfx:equalize", "色调均化", Icons.Filled.AutoAwesome, section = "专业调色"),
            a("cfx:replace", "替换颜色…", Icons.Filled.ColorLens, section = "专业调色"),
            a("cfx:selective", "可选颜色…", Icons.Filled.Tune, section = "专业调色"),
            a("cfx:matchColor", "匹配颜色…", Icons.Filled.Palette, section = "专业调色"),
            a("cfx:variation", "变化…", Icons.Filled.AutoAwesome, section = "专业调色"),
            a("cfx:hdr", "HDR 色调…", Icons.Filled.WbSunny, section = "专业调色"),
            a("cfx:autoTone", "自动色调", Icons.Filled.AutoAwesome, section = "专业调色"),
            a("cfx:autoContrast", "自动对比度", Icons.Filled.Contrast, section = "专业调色"),
            a("cfx:autoColor", "自动颜色", Icons.Filled.ColorLens, section = "专业调色"),
            ),
        ),
        RailCategory(
            "滤镜", "滤镜", Icons.Filled.FilterAlt,
            listOf(
                a("fBlur", "高斯模糊", Icons.Filled.BlurOn),
                a("fSharpen", "锐化", Icons.Filled.Details),
                a("fMosaic", "马赛克", Icons.Filled.GridOn),
                a("cfx:findEdges", "查找边缘", Icons.Filled.Timeline),
                a("cfx:emboss", "浮雕效果", Icons.Filled.Details),
                a("cfx:motionBlur", "动感模糊", Icons.Filled.Straighten),
                a("cfx:radialBlur", "径向模糊", Icons.Filled.CenterFocusStrong),
                a("cfx:boxBlur", "方框模糊", Icons.Filled.FilterAlt),
                a("cfx:surfaceBlur", "表面模糊", Icons.Filled.FilterAlt),
                a("cfx:unsharp", "USM 锐化", Icons.Filled.AutoAwesome),
                a("cfx:smartSharpen", "智能锐化…", Icons.Filled.Details),
                a("cfx:addNoise", "添加杂色", Icons.Filled.ScatterPlot),
                a("cfx:median", "中间值", Icons.Filled.FilterAlt),
                a("cfx:dust", "蒙尘与划痕", Icons.Filled.FilterAlt),
                a("cfx:ripple", "波纹", Icons.Filled.Gesture),
                a("cfx:spherize", "球面化", Icons.Filled.AdsClick),
                a("cfx:twirl", "旋转扭曲", Icons.Filled.RotateRight),
                a("cfx:pinch", "挤压", Icons.Filled.CenterFocusStrong),
                a("cfx:polar", "极坐标", Icons.Filled.Polyline),
                a("cfx:crystallize", "晶格化", Icons.Filled.GridView),
                a("cfx:pointillize", "点状化", Icons.Filled.ScatterPlot),
                a("cfx:clouds", "云彩", Icons.Filled.AcUnit),
                a("cfx:lensFlare", "镜头光晕", Icons.Filled.WbSunny),
                a("cfx:highPass", "高反差保留", Icons.Filled.Contrast),
                a("cfx:oil", "油画", Icons.Filled.Palette),
                a("cfx:lighting", "光照效果", Icons.Filled.Brightness6),
                a("cfx:minimum", "最小值", Icons.Filled.Contrast),
                a("cfx:maximum", "最大值", Icons.Filled.Contrast),
                a("cfx:offset", "位移", Icons.Filled.AdsClick),
                a("cfx:fragments", "碎片", Icons.Filled.GridView),
                a("cfx:colorHalftone", "彩色半调", Icons.Filled.ScatterPlot),
                a("cfx:reduceNoise", "减少杂色", Icons.Filled.FilterAlt),
                a("cfx:cutout", "木刻", Icons.Filled.Details),
                a("cfx:watercolor", "水彩画纸", Icons.Filled.Palette),
                a("cfx:filmGrain", "胶片颗粒", Icons.Filled.Timeline),
            a("cfx:lensCorrection", "镜头校正…", Icons.Filled.CenterFocusStrong),
            a("cfx:adaptiveWide", "自适应广角…", Icons.Filled.CenterFocusStrong),
            t("liquify", "液化", Icons.Filled.BlurOn, SketchView.Tool.LIQUIFY),
            t("vanish", "消失点", Icons.Filled.CenterFocusStrong, SketchView.Tool.VANISH),
            ),
        ),
        RailCategory(
            "样式", "图层样式", Icons.Filled.AutoAwesome,
            listOf(a("layerStyle", "图层样式…", Icons.Filled.AutoAwesome)),
        ),
        RailCategory(
            "文字", "文字", Icons.Filled.Title,
            listOf(t("text", "文字", Icons.Filled.Title, SketchView.Tool.TEXT)),
        ),
        RailCategory(
            "形状", "矢量 / 形状 / 路径", Icons.Filled.Category,
            listOf(
                t("shape", "形状", Icons.Filled.Category, SketchView.Tool.SHAPE),
                t("pen", "钢笔", Icons.Filled.Create, SketchView.Tool.PEN),
                t("warp", "变形", Icons.Filled.OpenWith, SketchView.Tool.WARP),
            ),
        ),
        RailCategory(
            "辅助", "排版辅助（参考线 / 吸附 / 标尺）", Icons.Filled.Straighten,
            listOf(
                a("guideH", "水平参考线", Icons.Filled.Add),
                a("guideV", "垂直参考线", Icons.Filled.Add),
                a("guideShow", "显示参考线", Icons.Filled.Visibility),
                a("guideSnap", "吸附参考线", Icons.Filled.GridOn),
                a("guideClear", "清除参考线", Icons.Filled.Delete),
                a("fitCanvas", "适应画布", Icons.Filled.FitScreen),
                a("actualSize", "实际大小 1:1", Icons.Filled.CenterFocusStrong),
            ),
        ),
        RailCategory(
            "文件", "文件与格式兼容", Icons.Filled.FolderOpen,
            listOf(
                a("save", "保存", Icons.Filled.Save),
                a("saveAs", "另存为 PSD", Icons.Filled.SaveAlt),
                RailEntry("open", "打开 PSD", Icons.Filled.FolderOpen, false, true) { onAction("open") },
                a("export", "导出…", Icons.Filled.IosShare),
                a("share", "分享 PSD", Icons.Filled.Share),
            ),
        ),
        RailCategory(
            "特效", "自动化 / 合成 / 视频", Icons.Filled.Movie,
            listOf(a("efx:panel", "特效…", Icons.Filled.Movie),
                a("efx:actions", "动作录制…", Icons.Filled.Timeline),
                a("efx:batch", "批处理…", Icons.Filled.IosShare)),
        ),
        RailCategory(
            "面板", "面板与界面", Icons.Filled.Dashboard,
            listOf(
                a("ui:nav", "导航器", Icons.Filled.FitScreen),
                a("ui:info", "信息", Icons.Filled.Details),
                a("ui:action", "动作", Icons.Filled.Timeline),
                a("ui:hist", "直方图", Icons.Filled.ShowChart),
                a("channels", "通道", Icons.Filled.Layers),
                a("history", "历史记录", Icons.Filled.History),
                a("eyedropper", "吸管", Icons.Filled.Colorize),
                a("bucket", "油漆桶", Icons.Filled.FormatColorFill),
                RailEntry("gradient", "渐变", Icons.Filled.Gradient, tool == SketchView.Tool.GRADIENT, hasDoc) {
                    onTool(SketchView.Tool.GRADIENT)
                },
            ),
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailCell(
    item: RailEntry,
    pinned: Boolean,
    onLong: () -> Unit,
    onClick: () -> Unit,
) {
    val tint = if (item.active) MaterialTheme.colorScheme.primary
    else if (item.enabled) Color(0xFF22262B) else Color(0xFF4A5057)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(54.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (pinned) Color(0x14000000) else Color.Transparent)
            .combinedClickable(enabled = item.enabled, onClick = onClick, onLongClick = onLong)
            .padding(vertical = 4.dp),
    ) {
        Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(item.label, fontSize = 8.5.sp, color = tint, maxLines = 1)
        if (pinned) Text("已固定", fontSize = 7.sp, color = MaterialTheme.colorScheme.primary) else Spacer(Modifier.height(1.dp))
    }
}

@Composable
internal fun RailItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = when {
        active -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 9.sp, color = color)
    }
}

@Composable
private fun BottomBar(
    hasDoc: Boolean,
    color: Int,
    brushSize: Float,
    opacity: Float,
    brushType: Int,
    hardness: Float,
    flow: Float,
    spacing: Float,
    taper: Float,
    jitter: Float,
    scatter: Float,
    roundness: Float,
    onColorClick: () -> Unit,
    onSizeChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBrushTypeChange: (Int) -> Unit,
    onHardnessChange: (Float) -> Unit,
    onFlowChange: (Float) -> Unit,
    onSpacingChange: (Float) -> Unit,
    onTaperChange: (Float) -> Unit,
    onJitterChange: (Float) -> Unit,
    onScatterChange: (Float) -> Unit,
    onRoundnessChange: (Float) -> Unit,
    showBrushDetail: Boolean,
    onBrushDetailToggle: () -> Unit,
    onLayers: () -> Unit,
    smudgeTool: Boolean = false,
    smudgeStrength: Float = 0.65f,
    onSmudgeStrengthChange: (Float) -> Unit = {},
    brushSketch: SketchView? = null,
    onBrushPreset: ((BrushPreset) -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
            ) {
                if (showBrushDetail) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 118.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                            SliderRow(
                                label = "硬度",
                                value = hardness,
                                valueRange = 0f..1f,
                                display = "${(hardness * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onHardnessChange,
                            )
                            SliderRow(
                                label = "流量",
                                value = flow,
                                valueRange = 0.05f..1f,
                                display = "${(flow * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onFlowChange,
                            )
                            SliderRow(
                                label = "变尖",
                                value = taper,
                                valueRange = 0f..1f,
                                display = "${(taper * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onTaperChange,
                            )
                            SliderRow(
                                label = "抖动",
                                value = jitter,
                                valueRange = 0f..1f,
                                display = "${(jitter * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onJitterChange,
                            )
                            SliderRow(
                                label = "散开",
                                value = scatter,
                                valueRange = 0f..1f,
                                display = "${(scatter * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onScatterChange,
                            )
                            SliderRow(
                                label = "圆度",
                                value = roundness,
                                valueRange = 0.2f..1f,
                                display = "${(roundness * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onRoundnessChange,
                            )
                            SliderRow(
                                label = "间距",
                                value = spacing,
                                valueRange = 0.03f..1f,
                                display = "${(spacing * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onSpacingChange,
                            )
                            BrushTextureRow(sketch = brushSketch, hasDoc = hasDoc)
                            BrushDynControls(
                                sketch = brushSketch,
                                hasDoc = hasDoc,
                                enabled = true,
                                onPreset = onBrushPreset,
                            )
                            if (smudgeTool) {
                                SliderRow(
                                    label = "强度",
                                    value = smudgeStrength,
                                    valueRange = 0.05f..1f,
                                    display = "${(smudgeStrength * 100).roundToInt()}%",
                                    enabled = hasDoc,
                                    onChange = onSmudgeStrengthChange,
                                )
                            }
                            SliderRow(
                                label = "笔刷",
                                value = brushSize,
                                valueRange = 1f..300f,
                                display = "${brushSize.roundToInt()}",
                                enabled = hasDoc,
                                onChange = onSizeChange,
                            )
                            SliderRow(
                                label = "不透明",
                                value = opacity,
                                valueRange = 0.05f..1f,
                                display = "${(opacity * 100).roundToInt()}%",
                                enabled = hasDoc,
                                onChange = onOpacityChange,
                            )
                                    }
}
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color(color), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onColorClick() },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${brushSize.roundToInt()} · ${(opacity * 100).roundToInt()}%",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 顶部的画笔条：只有「画笔」和「参数」两个按钮（铅笔已按用户要求删掉，颜色/不透明度仍在底部） */
@Composable
private fun BrushTypeBar(
    hasDoc: Boolean,
    brushType: Int,
    onBrushTypeChange: (Int) -> Unit,
    showBrushDetail: Boolean,
    onBrushDetailToggle: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                val picked = brushType != SketchView.BRUSH_PENCIL
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(enabled = hasDoc) { onBrushTypeChange(SketchView.BRUSH_ROUND) },
                ) {
                    Text(
                        text = "画笔",
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (showBrushDetail) "收起" else "参数",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBrushDetailToggle() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 9.sp,
            modifier = Modifier.width(32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThinSlider(
            value = value,
            range = valueRange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onChange = onChange,
        )
        Text(
            text = display,
            fontSize = 9.sp,
            modifier = Modifier.width(38.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun defaultFileName(format: ExportFormat): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "reveal_$stamp.${format.extension}"
}


/** 文件名里去掉系统不认的字符 */
private fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "未命名" }
