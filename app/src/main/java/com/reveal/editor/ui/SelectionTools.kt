package com.reveal.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveal.editor.image.SelectMode
import com.reveal.editor.image.SelectOp
import kotlin.math.roundToInt

/**
 * 选区那一类工具的选项条：选区方式 / 运算 / 容差 / 修改 / 变换 / 存储。
 *
 * 和 BrushDynControls 一样单独放一个文件，避免 EditorScreen 的 @Composable 撞 JVM 64KB。
 * 所有值都直接写回 SketchView，不往外传状态。
 */
@Composable
internal fun SelectionOptionsBar(
    sketch: SketchView?,
    curMode: SelectMode,
    curOp: SelectOp,
    onMode: (SelectMode) -> Unit,
    onOp: (SelectOp) -> Unit,
    onMatting: () -> Unit,
    onExitTool: () -> Unit = {},
) {
    // 形状类（同一个工具的几种画法）只放在「方式」这一行；
    // 魔棒 / 快速选择是独立工具，不掺进形状参数里，各自显示自己的参数。
    val modes = listOf(
        SelectMode.RECT, SelectMode.ELLIPSE, SelectMode.ROW, SelectMode.COLUMN,
        SelectMode.LASSO, SelectMode.POLY, SelectMode.MAGNETIC,
    )
    val modeNames = listOf("矩形", "椭圆", "单行", "单列", "套索", "多边形", "磁性")
    // 每个工具「按什么选」的一句话说明，直接显示在参数条上
    val tip = when (curMode) {
        SelectMode.WAND ->
            "魔棒：点一下就把和落点颜色相近、连在一起的一整片选上；容差越大选得越多，连续关掉是全图同色都选"
        SelectMode.QUICK ->
            "快速选择：像画笔一样在相近颜色上涂，选区会顺着颜色边界自己长；涂到别处不合适就点「取消」重来"
        else -> null
    }
    val ops = listOf(SelectOp.NEW, SelectOp.ADD, SelectOp.SUB, SelectOp.INTERSECT)
    val opNames = listOf("新建", "加选", "减选", "交叉")

    // 方式/运算由外层（EditorScreen）统一持有，这里只显示和回传，避免两边各存一份互相覆盖
    val mode = modes.indexOf(curMode).coerceAtLeast(0)
    val op = ops.indexOf(curOp).coerceAtLeast(0)
    var tol by remember { mutableFloatStateOf(sketch?.wandTolerance ?: 0.12f) }
    var magnet by remember { mutableFloatStateOf(sketch?.magnetWidth ?: 14f) }
    var modKind by remember { mutableIntStateOf(0) }
    // 五项各自一个值：以前共用一个「修改量」，改成五项独立后能同时挂着（羽化 8 + 收缩 4 …）
    // 默认全部为 0：以前羽化 8 / 平滑 4 / 扩张 4 / 收缩 4 / 边界 3 全都预置了值，
    // 用户只动「羽化」一个滑块，另外四项就一起被算进去了（一拖就羽化 + 扩张 + 收缩互相打架）
    var modFeather by remember { mutableFloatStateOf(0f) }
    var modSmooth by remember { mutableFloatStateOf(0f) }
    var modGrow by remember { mutableFloatStateOf(0f) }
    var modShrink by remember { mutableFloatStateOf(0f) }
    var modBorder by remember { mutableFloatStateOf(0f) }
    var modTouched by remember { mutableStateOf(false) }
    val shapeMode = curMode == SelectMode.RECT || curMode == SelectMode.ELLIPSE ||
        curMode == SelectMode.ROW || curMode == SelectMode.COLUMN ||
        curMode == SelectMode.LASSO || curMode == SelectMode.POLY || curMode == SelectMode.MAGNETIC
    var savedTick by remember { mutableIntStateOf(0) }
    var contig by remember { mutableStateOf(sketch?.wandContiguous ?: true) }
    var sampleAll by remember { mutableStateOf(sketch?.wandSampleAll ?: false) }
    var quickSize by remember { mutableFloatStateOf(sketch?.quickSize ?: 40f) }
    var subjectTol by remember { mutableFloatStateOf(sketch?.subjectTolerance ?: 30f) }

    LaunchedEffect(tol) {
        sketch?.wandTolerance = tol
        sketch?.quickTolerance = tol
    }
    LaunchedEffect(magnet) { sketch?.magnetWidth = magnet }
    LaunchedEffect(contig) { sketch?.wandContiguous = contig }
    LaunchedEffect(sampleAll) { sketch?.wandSampleAll = sampleAll }
    LaunchedEffect(quickSize) { sketch?.quickSize = quickSize }
    LaunchedEffect(subjectTol) { sketch?.subjectTolerance = subjectTol }
    // 进形状类工具时把当前选区存成基准图；离开就丢掉，下次重新来
    LaunchedEffect(curMode) {
        if (shapeMode) sketch?.beginSelectionEdges() else sketch?.revertSelectionEdges()
        modTouched = false
    }
    // 拖动实时预览：始终从基准图算一次，不叠加（以前每点一次都在改过的选区上再改，越点越大）
    LaunchedEffect(modFeather, modSmooth, modGrow, modShrink, modBorder) {
        val sk = sketch ?: return@LaunchedEffect
        if (!modTouched || !shapeMode || !sk.hasSelection()) return@LaunchedEffect
        kotlinx.coroutines.delay(110)
        sk.applySelectionEdges(
            modFeather.roundToInt(), modSmooth.roundToInt(),
            modGrow.roundToInt(), modShrink.roundToInt(), modBorder.roundToInt(), 0, false,
        )
    }
    LaunchedEffect(curMode) {
        // 换工具时把这一行的值重新读一遍，避免显示上一个工具留下的数
        val sk = sketch ?: return@LaunchedEffect
        tol = sk.wandTolerance
        quickSize = sk.quickSize
        subjectTol = sk.subjectTolerance
        contig = sk.wandContiguous
        sampleAll = sk.wandSampleAll
        magnet = sk.magnetWidth
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PanelColorHeader,
        contentColor = androidx.compose.ui.graphics.Color(0xFF22262B),
        tonalElevation = 0.dp,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
        ) {
            LocalBarClose.current?.let { close ->
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.fillMaxWidth()
                ) {
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(
                        onClick = close,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp
                        ),
                    ) {
                        androidx.compose.material3.Text(
                            "✕",
                            fontSize = 12.sp,
                            color = androidx.compose.ui.graphics.Color(0xFF6B7178),
                        )
                    }
                }
            }
            when (curMode) {
                SelectMode.RECT, SelectMode.ELLIPSE, SelectMode.ROW, SelectMode.COLUMN,
                SelectMode.LASSO, SelectMode.POLY, SelectMode.MAGNETIC -> {
                    DynChips("方式", modeNames, mode, enabled = true) { onMode(modes[it]) }
                    DynChips("运算", opNames, op, enabled = true) { onOp(ops[it]) }
                    if (curMode == SelectMode.MAGNETIC) {
                        SliderRow(
                            label = "吸附宽度",
                            value = magnet,
                            valueRange = 2f..40f,
                            display = "${magnet.roundToInt()} px",
                            enabled = true,
                            onChange = { magnet = it },
                        )
                    }
                    DynChips(
                        "修改",
                        listOf("羽化", "平滑", "扩张", "收缩", "边界"),
                        modKind,
                        enabled = true,
                    ) { modKind = it }
                    val modName = listOf("羽化", "平滑", "扩张", "收缩", "边界")[modKind.coerceIn(0, 4)]
                    val modVal = when (modKind) {
                        0 -> modFeather
                        1 -> modSmooth
                        2 -> modGrow
                        3 -> modShrink
                        else -> modBorder
                    }
                    SliderRow(
                        label = modName,
                        value = modVal,
                        valueRange = 0f..50f,
                        display = "${modVal.roundToInt()} px",
                        enabled = true,
                        onChange = { v ->
                            modTouched = true
                            when (modKind) {
                                0 -> modFeather = v
                                1 -> modSmooth = v
                                2 -> modGrow = v
                                3 -> modShrink = v
                                else -> modBorder = v
                            }
                        },
                    )
                    SelButtonRow(
                        listOf(
                            "应用修改" to {
                                sketch?.applySelectionEdges(
                                    modFeather.roundToInt(), modSmooth.roundToInt(),
                                    modGrow.roundToInt(), modShrink.roundToInt(),
                                    modBorder.roundToInt(), 0, true,
                                )
                            },
                            "放大选区" to { sketch?.transformSelection(1.1f, 0f, 0f) },
                            "缩小选区" to { sketch?.transformSelection(0.9f, 0f, 0f) },
                            "←" to { sketch?.transformSelection(1f, -20f, 0f) },
                            "→" to { sketch?.transformSelection(1f, 20f, 0f) },
                            "↑" to { sketch?.transformSelection(1f, 0f, -20f) },
                            "↓" to { sketch?.transformSelection(1f, 0f, 20f) },
                        )
                    )
                }

                SelectMode.WAND -> {
                    DynChips("运算", opNames, op, enabled = true) { onOp(ops[it]) }
                    SliderRow(
                        label = "容差",
                        value = tol,
                        valueRange = 0f..1f,
                        display = "${(tol * 100).roundToInt()}%",
                        enabled = true,
                        onChange = { tol = it },
                    )
                    SelButtonRow(
                        listOf(
                            "连续：${if (contig) "开" else "关"}" to { contig = !contig },
                            "对所有图层取样：${if (sampleAll) "开" else "关"}" to { sampleAll = !sampleAll },
                        )
                    )
                }

                SelectMode.QUICK -> {
                    DynChips("运算", opNames, op, enabled = true) { onOp(ops[it]) }
                    SliderRow(
                        label = "笔刷大小",
                        value = quickSize,
                        valueRange = 4f..300f,
                        display = "${quickSize.roundToInt()} px",
                        enabled = true,
                        onChange = { quickSize = it },
                    )
                    SliderRow(
                        label = "容差",
                        value = tol,
                        valueRange = 0f..1f,
                        display = "${(tol * 100).roundToInt()}%",
                        enabled = true,
                        onChange = { tol = it },
                    )
                }

                SelectMode.SUBJECT -> {
                    DynChips("运算", opNames, op, enabled = true) { onOp(ops[it]) }
                    SliderRow(
                        label = "抠图范围",
                        value = subjectTol,
                        valueRange = 0f..100f,
                        display = "${subjectTol.roundToInt()}%",
                        enabled = true,
                        onChange = { subjectTol = it },
                    )
                    SelButtonRow(listOf("重新找主体" to { sketch?.selectSubject() }))
                }

                SelectMode.RANGE -> {
                    DynChips("运算", opNames, op, enabled = true) { onOp(ops[it]) }
                    SliderRow(
                        label = "容差",
                        value = tol,
                        valueRange = 0f..1f,
                        display = "${(tol * 100).roundToInt()}%",
                        enabled = true,
                        onChange = { tol = it },
                    )
                    SelButtonRow(listOf("按前景色重选" to { sketch?.selectColorRange(tol) }))
                }
            }
            // 这一行只出现在当前工具自己的说明位置，没有就不占地方
            val tipText = when (curMode) {
                SelectMode.WAND -> "魔棒：点一下就把和落点颜色相近、连在一起的一整片选上；容差越大选得越多"
                SelectMode.QUICK -> "快速选择：在相近颜色上涂，选区顺着颜色边界自己长；不合适点「取消」重来"
                SelectMode.SUBJECT -> "选择主体：点一下画面，自动把主体抠出来（图层有透明区就按透明区，否则按四周颜色去背景）"
                SelectMode.RANGE -> "色彩范围：点一下画面，和那个颜色相近的像素整片选上（不需要拖）"
                SelectMode.MAGNETIC -> "磁性套索：沿着颜色边缘自动吸附，拖着走就行"
                else -> null
            }
            tipText?.let {
                Text(
                    it,
                    fontSize = 10.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            SelButtonRow(
                listOf(
                    "全选" to { sketch?.selectAll() },
                    "反选" to { sketch?.invertSelection() },
                    "取消" to { sketch?.clearSelectionUndoable(); onExitTool() },
                    "色彩范围" to { sketch?.selectColorRange(tol) },
                    "选择主体" to { sketch?.selectSubject() },
                    "存储选区" to {
                        sketch?.saveSelection()
                        savedTick = sketch?.savedSelections?.size ?: 0
                    },
                    "载入选区" to { sketch?.loadSelection(maxOf(0, (sketch.savedSelections.size) - 1)) },
                    "抠图" to { onMatting() },
                )
            )
            if (savedTick > 0) {
                Text(
                    "已存 $savedTick 个选区（载入取最后一个）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** 一排小按钮（选区面板的命令） */
@Composable
internal fun SelButtonRow(items: List<Pair<String, () -> Unit>>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
    ) {
        items.forEach { (label, act) ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { act() },
            ) {
                Text(label, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

/** 侧栏 `sel:*` 条目 -> 选区方式（返回 null 表示这一项不改方式） */
internal fun selectModeFor(id: String): SelectMode? = when (id) {
    "sel:rect" -> SelectMode.RECT
    "sel:ellipse" -> SelectMode.ELLIPSE
    "sel:row" -> SelectMode.ROW
    "sel:col" -> SelectMode.COLUMN
    "sel:lasso" -> SelectMode.LASSO
    "sel:poly" -> SelectMode.POLY
    "sel:magnetic" -> SelectMode.MAGNETIC
    "sel:quick" -> SelectMode.QUICK
    "sel:wand" -> SelectMode.WAND
    "sel:subject" -> SelectMode.SUBJECT
    "sel:range" -> SelectMode.RANGE
    else -> null
}

/** 侧栏 `sel:*` 里立即执行的命令 */
internal fun runSelectionCommand(sketch: SketchView?, id: String) {
    if (sketch == null) return
    when (id) {
        "sel:all" -> sketch.selectAll()
        "sel:invert" -> sketch.invertSelection()
        "sel:clear" -> sketch.clearSelectionUndoable()
        "sel:range" -> sketch.selectColorRange(sketch.wandTolerance)
        "sel:subject" -> sketch.selectSubject()
        "sel:save" -> sketch.saveSelection()
        "sel:load" -> sketch.loadSelection(sketch.savedSelections.size - 1)
    }
}
