package com.reveal.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Blender
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 第一屏：画布 + 最上方状态栏 + 底部可左右滚动工具栏 + 一个悬浮窗（点开是 6 种模式切换）。
 * 除了这四样，别的什么都不放。
 */

/** 六个模式（也代表功能完整度顺序） */
internal enum class NewMode(val label: String) {
    CUT("抠图"), COLOR("调色"), LAYOUT("排版"), RETOUCH("修图"), PAINT("绘制"), AI("AI")
}

internal fun newModeIcon(m: NewMode): ImageVector = when (m) {
    NewMode.CUT -> Icons.Filled.CenterFocusStrong
    NewMode.COLOR -> Icons.Filled.Tune
    NewMode.LAYOUT -> Icons.Filled.TextFields
    NewMode.RETOUCH -> Icons.Filled.Healing
    NewMode.PAINT -> Icons.Filled.Brush
    NewMode.AI -> Icons.Filled.AutoAwesome
}

/** 底部工具：一个 id + 名称 + 图标，点下去怎么干活由外层决定 */
internal data class NewTool(val id: String, val label: String, val icon: ImageVector)

internal fun toolsForMode(m: NewMode): List<NewTool> = when (m) {
    // 抠图：最常用的选区五件 + 两个进阶选择
    NewMode.CUT -> listOf(
        NewTool("cut.band", "色带抠图", Icons.Filled.Tune),
        NewTool("sel.rect", "矩形", Icons.Filled.CropSquare),
        NewTool("sel.ellipse", "椭圆", Icons.Filled.Circle),
        NewTool("sel.lasso", "套索", Icons.Filled.Create),
        NewTool("sel.poly", "多边形", Icons.Filled.Category),
        NewTool("sel.magnetic", "磁性", Icons.Filled.AutoFixHigh),
        NewTool("sel.wand", "魔棒", Icons.Filled.AutoFixNormal),
        NewTool("sel.quick", "快速选择", Icons.Filled.BlurCircular),
        NewTool("sel.subject", "选主体", Icons.Filled.PersonOutline),
    )
    // 调色：面板类，逐个进参数区（顺序按常用度）
    NewMode.COLOR -> listOf(
        NewTool("fx.levels", "色阶", Icons.Filled.ShowChart),
        NewTool("fx.curves", "曲线", Icons.Filled.Timeline),
        NewTool("fx.hsl", "色相饱和", Icons.Filled.Palette),
        NewTool("fx.bc", "亮度对比", Icons.Filled.Brightness7),
        NewTool("fx.exposure", "曝光度", Icons.Filled.WbSunny),
        NewTool("fx.vibrance", "饱和度", Icons.Filled.Opacity),
        NewTool("fx.bw", "黑白", Icons.Filled.InvertColors),
        NewTool("fx.balance", "色彩平衡", Icons.Filled.Tune),
        NewTool("fx.photoFilter", "照片滤镜", Icons.Filled.Grading),
        NewTool("fx.filters", "滤镜", Icons.Filled.AutoAwesome),
        NewTool("fx.gradientMap", "渐变映射", Icons.Filled.Gradient),
        NewTool("fx.posterize", "色调分离", Icons.Filled.FilterAlt),
    )
    // 排版：文字 / 形状 / 变换 / 裁切 / 画布
    NewMode.LAYOUT -> listOf(
        NewTool("tool.text", "文字", Icons.Filled.TextFields),
        NewTool("tool.shape", "形状", Icons.Filled.Category),
        NewTool("tool.transform", "变换", Icons.Filled.CropFree),
        NewTool("act.crop", "裁剪", Icons.Filled.Crop),
        NewTool("act.resize", "图像大小", Icons.Filled.AspectRatio),
        NewTool("act.canvas", "画布大小", Icons.Filled.PhotoSizeSelectSmall),
        NewTool("act.flipH", "水平翻转", Icons.Filled.Flip),
        NewTool("act.rotate", "旋转", Icons.Filled.Rotate90DegreesCcw),
    )
    // 修图：修瑕 / 局部明暗 / 模糊
    NewMode.RETOUCH -> listOf(
        NewTool("tool.heal", "修复", Icons.Filled.Healing),
        NewTool("tool.spot", "污点", Icons.Filled.Build),
        NewTool("tool.clone", "仿制", Icons.Filled.ContentCopy),
        NewTool("tool.pattern", "图案", Icons.Filled.GridView),
        NewTool("tool.redeye", "红眼", Icons.Filled.RemoveRedEye),
        NewTool("tool.dodge", "减淡", Icons.Filled.Brightness7),
        NewTool("tool.burn", "加深", Icons.Filled.Brightness4),
        NewTool("tool.sponge", "海绵", Icons.Filled.FilterAlt),
        NewTool("tool.smudge", "涂抹", Icons.Filled.BlurOn),
        NewTool("tool.liquify", "液化", Icons.Filled.Waves),
        NewTool("tool.warp", "变形", Icons.Filled.OpenWith),
    )
    // 绘制：画笔 / 橡皮 / 填充类
    NewMode.PAINT -> listOf(
        NewTool("tool.brush", "画笔", Icons.Filled.Brush),
        NewTool("tool.pencil", "铅笔", Icons.Filled.Create),
        NewTool("tool.eraser", "橡皮", Icons.Filled.AutoFixNormal),
        NewTool("tool.eraserBg", "背景橡皮", Icons.Filled.AutoFixOff),
        NewTool("tool.eraserMagic", "魔术橡皮", Icons.Filled.AutoFixHigh),
        NewTool("tool.gradient", "渐变", Icons.Filled.Gradient),
        NewTool("tool.bucket", "油漆桶", Icons.Filled.FormatColorFill),
        NewTool("tool.replace", "换色", Icons.Filled.Colorize),
        NewTool("tool.mixer", "混合", Icons.Filled.Opacity),
        NewTool("tool.artHistory", "艺术历史", Icons.Filled.Palette),
        NewTool("tool.histBrush", "历史画笔", Icons.Filled.History),
        NewTool("tool.pen", "钢笔", Icons.Filled.Edit),
    )
    // AI：占位
    NewMode.AI -> listOf(
        NewTool("ai.gen", "文生图", Icons.Filled.AutoAwesome),
        NewTool("ai.inpaint", "局部重绘", Icons.Filled.Brush),
    )
}

/**
 * 顶栏：左边 菜单 / 撤销 / 重做，右边 文件夹（导入导出）/ 保存 / 图层。
 * 工程名不放这儿——它画在画布左上角的小标签里。
 */
@Composable
internal fun NewTopBar(
    onMenu: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFile: () -> Unit,
    onSave: () -> Unit,
    onLayers: () -> Unit = {},
    onLog: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    Surface(color = Color.White, modifier = Modifier.fillMaxWidth(), shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.Filled.Menu, "首页", onMenu)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Filled.ArrowBack, "撤销", onUndo, bg = Rv.surfaceAlt)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Filled.ArrowForward, "重做", onRedo, bg = Rv.surfaceAlt)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Filled.Search, "搜索工具", onSearch, bg = Rv.surfaceAlt)
            // 版本号常显：用来确认装的到底是哪一版（排查「和上一版一样」这类问题全靠它）
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    verName(),
                    fontSize = 9.sp,
                    color = Rv.textFaint,
                    maxLines = 1,
                )
            }
            RoundIcon(Icons.Filled.FolderOpen, "导入导出", onFile, bg = Rv.surfaceAlt)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Rv.surfaceAlt)
                    .clickable { onSave() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("保存", fontSize = 12.sp, color = Rv.text)
            }
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Filled.Layers, "图层", onLayers, bg = Rv.surfaceAlt)
        }
    }
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    bg: Color = Color.Transparent,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Rv.text, modifier = Modifier.size(19.dp))
    }
}


@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Rv.textSub, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun BarText(label: String, onClick: () -> Unit, strong: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (strong) Rv.text else Rv.textSub)
    }
}

/** 底部工具栏：左边「固定工具」（不滚动）+ 竖线 + 右边可横向滚动的工具列表 */
@Composable
internal fun NewToolRow(
    tools: List<NewTool>,
    activeId: String?,
    compact: Boolean = false,
    /** 固定在最左、不参与横向滚动的工具（例如「范围」） */
    pinned: List<NewTool> = emptyList(),
    onPick: (NewTool) -> Unit,
) {
    Surface(color = Color.White) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 54.dp else 68.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pinned.forEach { t -> ToolCell(t, t.id == activeId, compact) { onPick(t) } }
            if (pinned.isNotEmpty()) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = 1.dp, height = if (compact) 26.dp else 30.dp)
                        .background(Rv.line),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tools.forEach { t -> ToolCell(t, t.id == activeId, compact) { onPick(t) } }
            }
        }
    }
}

@Composable
private fun ToolCell(t: NewTool, on: Boolean, compact: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = if (compact) 48.dp else 56.dp, height = if (compact) 48.dp else 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Rv.accentSoft else Color.Transparent)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            t.icon,
            contentDescription = t.label,
            tint = if (on) Rv.accent else Rv.textSub,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(t.label, fontSize = 9.5.sp, color = if (on) Rv.accent else Rv.textSub)
    }
}

/** 「滤镜」工具展开的子滤镜（点一下不占画布，只在工具行上方展开一排） */
internal val FILTER_SUB_TOOLS: List<NewTool> = listOf(
    NewTool("fx.cfx.gaussBlur", "高斯模糊", Icons.Filled.BlurOn),
    NewTool("fx.cfx.unsharp", "USM 锐化", Icons.Filled.AutoAwesome),
    NewTool("fx.cfx.mosaic", "马赛克", Icons.Filled.GridView),
    NewTool("fx.cfx.threshold", "阈值", Icons.Filled.Contrast),
    NewTool("fx.cfx.bwFilter", "黑白转换", Icons.Filled.Colorize),
    NewTool("fx.cfx.motionBlur", "动感模糊", Icons.Filled.Straighten),
    NewTool("fx.cfx.findEdges", "查找边缘", Icons.Filled.Timeline),
    NewTool("fx.cfx.addNoise", "添加杂色", Icons.Filled.ScatterPlot),
    NewTool("fx.cfx.oil", "油画", Icons.Filled.Palette),
    NewTool("fx.cfx.surfaceBlur", "表面模糊", Icons.Filled.BlurCircular),
    NewTool("fx.cfx.smartSharpen", "智能锐化", Icons.Filled.Details),
    NewTool("fx.cfx.radialBlur", "径向模糊", Icons.Filled.CenterFocusStrong),
    NewTool("fx.cfx.watercolor", "水彩", Icons.Filled.WaterDrop),
    NewTool("fx.cfx.emboss", "浮雕", Icons.Filled.FilterAlt),
    NewTool("fx.cfx.median", "中间值", Icons.Filled.Gradient),
    NewTool("fx.cfx.dust", "蒙尘划痕", Icons.Filled.ColorLens),
    NewTool("fx.cfx.reduceNoise", "减少杂色", Icons.Filled.InvertColors),
    NewTool("fx.cfx.boxBlur", "方框模糊", Icons.Filled.Contrast),
    NewTool("fx.cfx.ripple", "波纹", Icons.Filled.Waves),
    NewTool("fx.cfx.spherize", "球面化", Icons.Filled.AdsClick),
    NewTool("fx.cfx.twirl", "旋转扭曲", Icons.Filled.RotateRight),
    NewTool("fx.cfx.pinch", "挤压", Icons.Filled.OpenWith),
    NewTool("fx.cfx.polar", "极坐标", Icons.Filled.Polyline),
    NewTool("fx.cfx.clouds", "云彩", Icons.Filled.AcUnit),
    NewTool("fx.cfx.lensFlare", "镜头光晕", Icons.Filled.WbSunny),
    NewTool("fx.cfx.highPass", "高反差保留", Icons.Filled.Gesture),
    NewTool("fx.cfx.minimum", "最小值", Icons.Filled.ArrowDownward),
    NewTool("fx.cfx.maximum", "最大值", Icons.Filled.ArrowUpward),
    NewTool("fx.cfx.offset", "位移", Icons.Filled.ChevronRight),
    NewTool("fx.cfx.filmGrain", "胶片颗粒", Icons.Filled.Movie),
    NewTool("fx.cfx.cutout", "剪纸", Icons.Filled.ContentCopy),
    NewTool("fx.cfx.fragments", "碎片", Icons.Filled.CallMerge),
)

/**
 * 左侧模式栏：60dp 窄栏，收起箭头在框里；收起后只剩贴边箭头。
 */
@Composable
internal fun ModeRailPinned(
    current: NewMode,
    onSelect: (NewMode) -> Unit,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = modifier.width(60.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NewMode.entries.forEach { m ->
                val on = m == current
                Column(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Rv.accentSoft else Color.Transparent)
                        .clickable { onSelect(m) }
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        newModeIcon(m),
                        contentDescription = m.label,
                        tint = if (on) Rv.accent else Rv.textSub,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(m.label, fontSize = 9.sp, color = if (on) Rv.accent else Rv.textSub)
                }
            }
            Spacer(Modifier.height(2.dp))
            // 收起箭头放在最下面：单手拇指够得到
            Box(
                Modifier
                    .size(width = 34.dp, height = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggle(false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ChevronLeft, "收起", tint = Rv.textFaint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 收起状态下的贴边箭头：一颗 26×44dp 的圆角小条，点一下展开。
 */
@Composable
internal fun SideArrow(
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (open) return
    Surface(
        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(26.dp, 44.dp)
            .clickable { onToggle(true) },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.ChevronRight,
                "展开",
                tint = Rv.textSub,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 日志面板：诊断 / 崩溃两页，可复制、可清空 */
@Composable
internal fun LogSheet(
    diag: String,
    crash: String,
    onCopy: (String) -> Unit,
    onClearDiag: () -> Unit,
    onClearCrash: () -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val text = if (tab == 0) diag else crash
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable { onClose() },
        )
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("日志", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Rv.text)
                    Spacer(Modifier.weight(1f))
                    Text("×", fontSize = 18.sp, color = Rv.textFaint, modifier = Modifier.clickable { onClose() })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabChip("诊断", tab == 0) { tab = 0 }
                    TabChip("崩溃", tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    TabChip("复制", false) { onCopy(text) }
                    TabChip("清空", false) { if (tab == 0) onClearDiag() else onClearCrash() }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Rv.surfaceAlt)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text.ifBlank { if (tab == 0) "（还没有诊断记录）" else "（没有崩溃记录）" },
                        fontSize = 10.5.sp,
                        color = Rv.textSub,
                    )
                }
            }
        }
    }
}

/** 搜索用：所有模式的所有工具 + 滤镜子工具，带所属模式 */
internal fun allToolsForSearch(): List<Triple<String, String, NewMode>> {
    val out = ArrayList<Triple<String, String, NewMode>>()
    NewMode.entries.forEach { m ->
        toolsForMode(m).forEach { t -> out.add(Triple(t.id, t.label, m)) }
    }
    FILTER_SUB_TOOLS.forEach { t -> out.add(Triple(t.id, "滤镜 · " + t.label, NewMode.COLOR)) }
    return out
}

/** 工具栏最左侧固定的「范围」工具（调色只在这个范围内生效） */
internal val PINNED_TOOLS: List<NewTool> = listOf(
    NewTool("tool.region", "范围", Icons.Filled.CenterFocusStrong),
)

/** 当前安装包的版本号（顶栏常显，方便确认装的是哪一版） */
internal fun verName(): String =
    runCatching {
        val ctx = com.reveal.editor.ui.AppCtx.get()
        if (ctx == null) "" else {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            (pi.versionName ?: "") + " (" + pi.longVersionCode + ")"
        }
    }.getOrDefault("")

/** 顶栏要读版本号，但 composable 里拿 Context 会给每个调用点都加参数，这里放一个全局引用 */
internal object AppCtx {
    var get: () -> android.content.Context? = { null }
}
