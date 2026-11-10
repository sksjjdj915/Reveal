package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 形状工具条 + 路径面板。
 *
 * 放在单独文件里有两个原因：一是不让 EditorScreen 那个大 @Composable 继续膨胀（JVM 单方法 64KB），
 * 二是形状相关的状态本来就该跟形状工具待在一起。
 */
@Composable
internal fun ShapeToolBar(sketch: SketchView?) {
    var kind by remember { mutableIntStateOf(sketch?.shapeKind ?: 0) }
    var fill by remember { mutableStateOf(sketch?.shapeFill ?: false) }
    var corner by remember { mutableIntStateOf(sketch?.shapeCorner ?: 16) }
    var sides by remember { mutableIntStateOf(sketch?.shapeSides ?: 6) }
    var inner by remember { mutableIntStateOf(sketch?.shapeInner ?: 45) }
    var lineW by remember { mutableIntStateOf(sketch?.shapeWidth ?: 0) }
    var boxW by remember { mutableIntStateOf(300) }
    var boxH by remember { mutableIntStateOf(300) }
    var newLayer by remember { mutableStateOf(sketch?.shapeNewLayer ?: true) }
    var circle by remember { mutableStateOf(sketch?.shapeCircle ?: false) }
    var lineDeg by remember { mutableIntStateOf((sketch?.shapeLineAngle ?: 0f).toInt()) }
    var paths by remember { mutableStateOf(sketch?.pathNames() ?: emptyList()) }

    // 选中形状层时：参数条显示这一层的真实参数，改一下立刻重画（实时预览）
    val sp = sketch?.activeShapeSpec()
    var editing by remember { mutableStateOf(false) }
    var editSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(editSeq) {
        if (editSeq > 0) {
            delay(450)
            if (editing) {
                editing = false
                sketch?.endShapeSpecEdit()
            }
        }
    }
    fun editSpec(mut: (SketchView.ShapeSpec) -> Unit): Boolean {
        val base = sketch?.activeShapeSpec() ?: return false
        if (!editing) {
            editing = true
            sketch.beginShapeSpecEdit()
        }
        val ns = base.copyOf()
        mut(ns)
        sketch.updateShapeSpec(ns)
        editSeq++
        return true
    }

    Column {
        ToolOptionsBar(
            title = "形状",
            hint = "拖出一个形状；两根手指按住拖 = 正方形 / 正圆；粗细用底部笔刷",
            chips = listOf("矩形", "椭圆", "直线", "多边形", "圆角", "星形"),
            chipIndex = sp?.kind ?: kind,
            onChip = {
                kind = it
                sketch?.shapeKind = it
                editSpec { s2 -> s2.kind = it }
            },
            actionLabel = if (sp?.fill ?: fill) "填充：开" else "填充：关",
            onAction = {
                val now = !(sp?.fill ?: fill)
                fill = now
                sketch?.shapeFill = now
                editSpec { s2 -> s2.fill = now }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 172.dp)
                .background(Color(0xF21C1C1E))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            if (sp != null) {
                Text(
                    text = "这一层是可调形状：改下面的值会立刻重画（撤销可以回退）",
                    color = Color(0xFF0A84FF),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // 每种形状只显示它自己的参数：矩形是长/宽，椭圆是横/纵直径，直线是长度/角度
            val k = sp?.kind ?: kind
            val curW = if (sp != null) abs((sp.x1 - sp.x0).toInt()).coerceAtLeast(1) else boxW
            val curH = if (sp != null) abs((sp.y1 - sp.y0).toInt()).coerceAtLeast(1) else boxH
            val curLen = if (sp != null)
                kotlin.math.hypot((sp.x1 - sp.x0).toDouble(), (sp.y1 - sp.y0).toDouble()).toInt().coerceAtLeast(1) else boxW
            val curDeg = if (sp != null)
                Math.toDegrees(kotlin.math.atan2((sp.y1 - sp.y0).toDouble(), (sp.x1 - sp.x0).toDouble())).toInt() else lineDeg
            fun specSize(s2: SketchView.ShapeSpec, w: Int?, h: Int?) {
                val cx2 = (s2.x0 + s2.x1) / 2f
                val cy2 = (s2.y0 + s2.y1) / 2f
                var nw = (w?.toFloat() ?: abs(s2.x1 - s2.x0)).coerceAtLeast(1f)
                var nh = (h?.toFloat() ?: abs(s2.y1 - s2.y0)).coerceAtLeast(1f)
                if (s2.kind == 1 && s2.circle) {
                    // 正圆：按用户刚改的那一边定直径（改横就用横、改纵就用纵），
                    // 原来取 max 会导致只能放大、缩不回去
                    val d = if (w != null) nw else nh
                    nw = d
                    nh = d
                }
                s2.x0 = cx2 - nw / 2f
                s2.x1 = cx2 + nw / 2f
                s2.y0 = cy2 - nh / 2f
                s2.y1 = cy2 + nh / 2f
            }
            fun specLine(s2: SketchView.ShapeSpec, len: Float, deg: Float) {
                val r = Math.toRadians(deg.toDouble())
                s2.x1 = s2.x0 + (kotlin.math.cos(r) * len).toFloat()
                s2.y1 = s2.y0 + (kotlin.math.sin(r) * len).toFloat()
            }
            if (k == 2) {
                NumRow("长度", curLen, 1, 4096) { v ->
                    boxW = v
                    editSpec { s2 ->
                        val d = Math.toDegrees(
                            kotlin.math.atan2((s2.y1 - s2.y0).toDouble(), (s2.x1 - s2.x0).toDouble())
                        ).toFloat()
                        specLine(s2, v.toFloat(), d)
                    }
                }
                NumRow("角度（度）", curDeg, -180, 180) { v ->
                    lineDeg = v
                    sketch?.shapeLineAngle = v.toFloat()
                    editSpec { s2 ->
                        val len = kotlin.math.hypot(
                            (s2.x1 - s2.x0).toDouble(), (s2.y1 - s2.y0).toDouble()
                        ).toFloat().coerceAtLeast(1f)
                        specLine(s2, len, v.toFloat())
                    }
                }
            } else {
                NumRow(if (k == 1) "横直径" else "长度", curW, 1, 4096) { v ->
                    boxW = v
                    editSpec { s2 -> specSize(s2, v, null) }
                }
                if (!(k == 1 && (sp?.circle ?: circle))) {
                    NumRow(if (k == 1) "纵直径" else "宽度", curH, 1, 4096) { v ->
                        boxH = v
                        editSpec { s2 -> specSize(s2, null, v) }
                    }
                }
            }
            if (k == 1) {
                val circleOn = sp?.circle ?: circle
                SmallChip(if (circleOn) "圆形：开" else "圆形：关", circleOn) {
                    val on = !circleOn
                    circle = on
                    sketch?.shapeCircle = on
                    editSpec { s2 ->
                        s2.circle = on
                        if (on) {
                            val d = kotlin.math.max(abs(s2.x1 - s2.x0), abs(s2.y1 - s2.y0)).toInt()
                            specSize(s2, d, null)
                        }
                    }
                }
            }
            NumRow("线宽（0=跟笔刷）", sp?.width ?: lineW, 0, 200) {
                lineW = it
                sketch?.shapeWidth = it
                editSpec { s2 -> s2.width = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallChip("按尺寸生成", false) { sketch?.makeShapeAtSize(boxW, boxH) }
                var snapAngle by remember { mutableStateOf(sketch?.shapeSnapAngle ?: true) }
            SmallChip(if (snapAngle) "吸附 45\u00b0：开" else "吸附 45\u00b0：关", snapAngle) {
                snapAngle = !snapAngle
                sketch?.shapeSnapAngle = snapAngle
            }
            SmallChip(if (newLayer) "新建图层：开" else "新建图层：关", newLayer) {
                    newLayer = !newLayer
                    sketch?.shapeNewLayer = newLayer
                }
            }
            Spacer(Modifier.height(6.dp))
            val kk = sp?.kind ?: kind
            if (kk == 0) {
                Text(
                    text = "矩形：填上面「长度 / 宽度」就是标准矩形；要圆角换成右边的「圆角」形状",
                    color = Color(0xFF6B7178),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (kk == 4) {
                NumRow("圆角半径", sp?.corner ?: corner, 0, 400) {
                    corner = it
                    sketch?.shapeCorner = it
                    editSpec { s2 -> s2.corner = it }
                }
            }
            if (kk == 3 || kk == 5) {
                NumRow("边数", sp?.sides ?: sides, 3, 24) {
                    sides = it
                    sketch?.shapeSides = it
                    editSpec { s2 -> s2.sides = it }
                }
            }
            if (kk == 5) {
                NumRow("星形内径", sp?.inner ?: inner, 5, 95) {
                    inner = it
                    sketch?.shapeInner = it
                    editSpec { s2 -> s2.inner = it }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallChip("存为路径", true) {
                    if (sketch?.storeLastPath() == true) paths = sketch?.pathNames() ?: emptyList()
                }
                SmallChip("选区→路径", false) {
                    if (sketch?.selectionToPath() == true) paths = sketch?.pathNames() ?: emptyList()
                }
                Text(
                    text = if (paths.isEmpty()) "（还没有路径）" else "路径 ${paths.size} 条，点名字载入成选区",
                    color = Color(0xFF6B7178),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (paths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    paths.forEachIndexed { i, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallChip(name, false) { sketch?.loadPathAsSelection(i) }
                            SmallChip("填", false) { sketch?.paintPath(i, true) }
                            SmallChip("描", false) { sketch?.paintPath(i, false) }
                            SmallChip("×", false) {
                                if (sketch?.deletePath(i) == true) paths = sketch?.pathNames() ?: emptyList()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    // 数字本身也能左右拖（跟桌面端那种擦洗输入一样），不用非得去够滑块
    val cur by rememberUpdatedState(value)
    val step = stepFor(max)
    val dens = LocalDensity.current
    val pxPerStep = with(dens) { 7.dp.toPx() }.coerceAtLeast(1f)
    var acc by remember { mutableFloatStateOf(0f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(text = label, color = Color(0xFF6B7178), fontSize = 10.sp, modifier = Modifier.width(126.dp))
        SmallChip("-", false) { onChange((value - step).coerceAtLeast(min)) }
        Text(
            text = value.toString(),
            color = Color(0xFF22262B),
            fontSize = 10.sp,
            modifier = Modifier
                .width(34.dp)
                .pointerInput(min, max) {
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = { acc = 0f },
                        onDragCancel = { acc = 0f },
                    ) { change, drag ->
                        change.consume()
                        acc += drag
                        val n = (acc / pxPerStep).toInt()
                        if (n != 0) {
                            acc -= n * pxPerStep
                            val nv = (cur + n * step).coerceIn(min, max)
                            if (nv != cur) onChange(nv)
                        }
                    }
                },
        )
        SmallChip("+", false) { onChange((value + step).coerceAtMost(max)) }
        Spacer(Modifier.width(8.dp))
        ThinSlider(
            value = value.toFloat(),
            range = min.toFloat()..max.toFloat(),
            modifier = Modifier.weight(1f),
            onChange = { onChange(it.roundToInt().coerceIn(min, max)) },
        )
    }
}

private fun stepFor(max: Int): Int = if (max > 100) 5 else 1

@Composable
private fun SmallChip(label: String, highlight: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (highlight) Color(0xFF0A84FF) else Color(0xFF2A2F35),
        fontSize = 9.sp,
        modifier = Modifier
            .padding(end = 4.dp)
            .background(
                if (highlight) Color(0xFFE9F0FF) else Color(0xFFFFFFFF),
                RoundedCornerShape(6.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}