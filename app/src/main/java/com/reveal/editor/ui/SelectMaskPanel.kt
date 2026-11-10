package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 选择并遮住：把选区的边缘单独调（半径 / 平滑 / 位移 / 对比），
 * 再决定结果落在哪：还是选区、变图层蒙版、还是抠成新图层。
 *
 * 所有边缘调整都从「打开面板时的选区」这一份基准图上算，
 * 拖动只是预览，不会再像以前那样每拖一次就在已经改过的选区上再改一遍（越点越大）。
 */
@Composable
internal fun SelectMaskPanel(sketch: SketchView?, onClose: () -> Unit) {
    // 默认全是中性值：以前羽化 6 / 平滑 4 / 对比 40 一打开就先改了一遍选区，
    // 用户还没动任何滑块，选区就被柔化缩小了
    var feather by remember { mutableStateOf(0f) }
    var smooth by remember { mutableStateOf(0f) }
    var edge by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var output by remember { mutableStateOf(0) }
    // 桌面端里那两项：智能半径（边缘处自动收窄，避免吃到背景）和净化颜色（去白边彩边）
    var smart by remember { mutableStateOf(true) }
    var clean by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf("") }
    // 智能半径的边缘阈值（%）：手机屏幕小、图源杂，给一个能自己调的旋钮
    var sense by remember { mutableStateOf(22f) }

    val outputs = listOf("选区", "图层蒙版", "新图层", "新图层+蒙版")

    // 打开面板时把当前选区存成基准图
    LaunchedEffect(Unit) { sketch?.beginSelectionEdges() }
    // 拖动就实时预览（80ms 节流），但都从基准图算，不叠加
    LaunchedEffect(feather, smooth, edge, contrast, smart, clean, sense) {
        val s = sketch ?: return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        s.selCleanAmount = clean.toInt()
        s.selEdgeThr = (sense / 100f).coerceIn(0.03f, 0.8f)
        s.applySelectionEdges(
            feather.toInt(), smooth.toInt(),
            if (edge > 0f) edge.toInt() else 0,
            if (edge < 0f) (-edge).toInt() else 0,
            0, contrast.toInt(), false, smart = smart,
        )
    }

    Column(
        Modifier
            .padding(10.dp)
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .width(320.dp)
            // 参数条这块高度有限：最多占这么高，内容多了自己滚，
            // 不许像以前那样铺满屏幕把画布挡掉
            .heightIn(max = 232.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("选择并遮住", color = Color(0xFF22262B), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.padding(3.dp))
        Text(
            "拖滑块就能在画布上直接看到边缘变化（都从打开面板时那份选区算起）。" +
                "满意了点「应用」落定，再选输出到哪。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Spacer(Modifier.padding(4.dp))

        SelectMaskRow("半径", feather, 0f..50f) { feather = it }
        SelectMaskRow("平滑", smooth, 0f..30f) { smooth = it }
        SelectMaskRow("边缘位移", edge, -40f..40f) { edge = it }
        SelectMaskRow("对比", contrast, 0f..100f) { contrast = it }
        SelectMaskToggle("智能半径", smart, "边缘处自动收窄，少吃到背景") { smart = it }
        if (smart) {
            SelectMaskRow("边缘灵敏度", sense, 5f..55f) { sense = it }
        }
        SelectMaskRow("净化颜色", clean, 0f..100f) { clean = it }

        Spacer(Modifier.padding(4.dp))
        Text("输出到", color = Color(0xFF6B7178), fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            outputs.forEachIndexed { i, name ->
                val on = output == i
                TextButton(onClick = { output = i }) {
                    Text(
                        name,
                        color = if (on) Color(0xFF7AB8FF) else Color(0xFF8A9099),
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.padding(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val s = sketch
                if (s == null || !s.hasSelection()) {
                    note = "现在没有选区，先用选框或魔棒圈一块"
                    return@TextButton
                }
                // 从基准图一次算完并落一次撤销，不再连着叠加四步
                s.selCleanAmount = clean.toInt()
                s.selEdgeThr = (sense / 100f).coerceIn(0.03f, 0.8f)
                s.applySelectionEdges(
                    feather.toInt(), smooth.toInt(),
                    if (edge > 0f) edge.toInt() else 0,
                    if (edge < 0f) (-edge).toInt() else 0,
                    0, contrast.toInt(), true, smart = smart,
                )
                note = when (output) {
                    1 -> if (s.selectionAsMask()) "已把选区变成当前图层的蒙版" else "加蒙版失败（可能已经有蒙版）"
                    2 -> if (s.selectionToNewLayer() >= 0) "已抠成新图层「选区抠图」（放在最上层）" else "抠图失败"
                    3 -> if (s.selectionToNewLayerWithMask() >= 0) "已新建「选区蒙版层」（整层 + 选区蒙版）" else "新建失败"
                    else -> "边缘已调整，选区保留"
                }
            }) { Text("应用", color = Color(0xFF7AB8FF), fontSize = 13.sp) }

            TextButton(onClick = {
                // 取消 = 丢掉这次预览，选区回到打开面板时的样子（以前会连选区一起清掉）
                sketch?.revertSelectionEdges()
                onClose()
            }) { Text("取消", color = Color(0xFF8A9099), fontSize = 12.sp) }

            TextButton(onClick = {
                sketch?.revertSelectionEdges()
                onClose()
            }) { Text("关闭", color = Color(0xFF8A9099), fontSize = 12.sp) }
        }

        if (note.isNotEmpty()) {
            Spacer(Modifier.padding(2.dp))
            Text(note, color = Color(0xFF8FD18A), fontSize = 11.sp)
        }
        Spacer(Modifier.padding(2.dp))
        Text(
            "提示：想看羽化的软边，把「对比」拉到 0；想要硬边，拉到 90 以上。",
            color = Color(0xFF5A6068),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SelectMaskToggle(label: String, on: Boolean, hint: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8A9099), fontSize = 11.sp, modifier = Modifier.width(56.dp))
        TextButton(onClick = { onChange(!on) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(
                if (on) "开" else "关",
                color = if (on) Color(0xFF7AB8FF) else Color(0xFF5F656C),
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Text(hint, color = Color(0xFF5A6068), fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SelectMaskRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8A9099), fontSize = 11.sp, modifier = Modifier.width(56.dp))
        ThinSlider(value, range, modifier = Modifier.weight(1f)) { onChange(it) }
        Text(value.toInt().toString(), color = Color(0xFF6B7178), fontSize = 11.sp, modifier = Modifier.width(38.dp))
    }
}