package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 一段文字的排版参数（桌面端字符 + 段落面板里那些常用项）。
 *
 * 单独放一个文件是为了不让 EditorScreen 那个 @Composable 超过 JVM 单方法 64KB 上限。
 */
data class TextSpec(
    val content: String = "",
    val size: Float = 32f,
    val color: Int = 0xFF000000.toInt(),
    val font: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 字距：千分之一 em，-100~1000 */
    val tracking: Float = 0f,
    /** 行距增减：百分比，-50~200 */
    val leading: Float = 0f,
    /** 0 左 1 中 2 右 3 两端对齐 */
    val align: Int = 1,
    /** 水平/垂直缩放 10%~400% */
    val scaleX: Float = 100f,
    val scaleY: Float = 100f,
    /** 基线偏移：字号百分比 */
    val shift: Float = 0f,
    /** 0 原样 1 全大写 2 全小写 3 小型大写 */
    val caps: Int = 0,
    /** 0 无 1 上标 2 下标 */
    val superSub: Int = 0,
    /** 0 无变形，1~12 分别是 弧形/下弧/上弧/拱形/凸起/贝壳/花冠/旗帜/波浪/鱼形/膨胀/挤压 */
    val warp: Int = 0,
    val warpBend: Float = 50f,
    /** 竖排 */
    val vertical: Boolean = false,
    /** 路径文字：沿「路径」面板里的某条路径排字（桌面端的路径文字） */
    val pathText: Boolean = false,
    val pathIndex: Int = 0,
)

/** 文字变形名字，下标对应 TextSpec.warp */
val TextWarps: List<String> = listOf(
    "无", "弧形", "下弧", "上弧", "拱形", "凸起", "贝壳", "花冠", "旗帜", "波浪", "鱼形", "膨胀", "挤压",
)

@Composable
internal fun TextStylePanel(
    spec: TextSpec,
    onSpec: (TextSpec) -> Unit,
    modifier: Modifier = Modifier,
    pathNames: List<String> = emptyList(),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
    ) {
        Toggle("B", spec.bold) { onSpec(spec.copy(bold = it)) }
        Toggle("I", spec.italic) { onSpec(spec.copy(italic = it)) }
        Toggle("U", spec.underline) { onSpec(spec.copy(underline = it)) }
        Toggle("S", spec.strike) { onSpec(spec.copy(strike = it)) }
        Spacer(Modifier.width(4.dp))
        Toggle("TT", spec.caps == 1) { onSpec(spec.copy(caps = if (it) 1 else 0)) }
        Toggle("SC", spec.caps == 3) { onSpec(spec.copy(caps = if (it) 3 else 0)) }
        Toggle("上", spec.superSub == 1) { onSpec(spec.copy(superSub = if (it) 1 else 0)) }
        Toggle("下", spec.superSub == 2) { onSpec(spec.copy(superSub = if (it) 2 else 0)) }
        Toggle("竖排", spec.vertical) { onSpec(spec.copy(vertical = it)) }
        Spacer(Modifier.width(4.dp))
        listOf("左", "中", "右", "两端").forEachIndexed { i, label ->
            Toggle(label, spec.align == i) { onSpec(spec.copy(align = i)) }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
    ) {
        Text("字距", fontSize = 10.sp, color = Color(0xFF6B7178))
        Toggle("-", false) { onSpec(spec.copy(tracking = (spec.tracking - 20f).coerceIn(-100f, 1000f))) }
        Text(spec.tracking.roundToInt().toString(), fontSize = 10.sp, color = Color(0xFFD2D6DC))
        Toggle("+", false) { onSpec(spec.copy(tracking = (spec.tracking + 20f).coerceIn(-100f, 1000f))) }
        Spacer(Modifier.width(6.dp))
        Text("行距", fontSize = 10.sp, color = Color(0xFF6B7178))
        Toggle("-", false) { onSpec(spec.copy(leading = (spec.leading - 10f).coerceIn(-50f, 200f))) }
        Text(spec.leading.roundToInt().toString(), fontSize = 10.sp, color = Color(0xFFD2D6DC))
        Toggle("+", false) { onSpec(spec.copy(leading = (spec.leading + 10f).coerceIn(-50f, 200f))) }
        Spacer(Modifier.width(6.dp))
        Text("横向", fontSize = 10.sp, color = Color(0xFF6B7178))
        Toggle("-", false) { onSpec(spec.copy(scaleX = (spec.scaleX - 10f).coerceIn(10f, 400f))) }
        Text(spec.scaleX.roundToInt().toString(), fontSize = 10.sp, color = Color(0xFFD2D6DC))
        Toggle("+", false) { onSpec(spec.copy(scaleX = (spec.scaleX + 10f).coerceIn(10f, 400f))) }
        Spacer(Modifier.width(6.dp))
        Text("纵向", fontSize = 10.sp, color = Color(0xFF6B7178))
        Toggle("-", false) { onSpec(spec.copy(scaleY = (spec.scaleY - 10f).coerceIn(10f, 400f))) }
        Text(spec.scaleY.roundToInt().toString(), fontSize = 10.sp, color = Color(0xFFD2D6DC))
        Toggle("+", false) { onSpec(spec.copy(scaleY = (spec.scaleY + 10f).coerceIn(10f, 400f))) }
        Spacer(Modifier.width(6.dp))
        Text("基线", fontSize = 10.sp, color = Color(0xFF6B7178))
        Toggle("-", false) { onSpec(spec.copy(shift = (spec.shift - 10f).coerceIn(-100f, 100f))) }
        Text(spec.shift.roundToInt().toString(), fontSize = 10.sp, color = Color(0xFFD2D6DC))
        Toggle("+", false) { onSpec(spec.copy(shift = (spec.shift + 10f).coerceIn(-100f, 100f))) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
    ) {
        Text("变形", fontSize = 10.sp, color = Color(0xFF6B7178))
        TextWarps.forEachIndexed { i, label ->
            Toggle(label, spec.warp == i) { onSpec(spec.copy(warp = i)) }
        }
        Spacer(Modifier.width(6.dp))
        Toggle("路径文字", spec.pathText) { onSpec(spec.copy(pathText = it)) }
        if (spec.pathText) {
            if (pathNames.isEmpty()) {
                Text("（先用钢笔/形状存一条路径）", fontSize = 10.sp, color = Color(0xFF6B7178))
            } else {
                pathNames.forEachIndexed { i, nm ->
                    Toggle(
                        if (nm.length > 6) nm.take(6) else nm,
                        spec.pathIndex == i,
                    ) { onSpec(spec.copy(pathIndex = i)) }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("弯曲", fontSize = 10.sp, color = Color(0xFF6B7178))
        Toggle("-", false) { onSpec(spec.copy(warpBend = (spec.warpBend - 10f).coerceIn(0f, 100f))) }
        Text(spec.warpBend.roundToInt().toString(), fontSize = 10.sp, color = Color(0xFFD2D6DC))
        Toggle("+", false) { onSpec(spec.copy(warpBend = (spec.warpBend + 10f).coerceIn(0f, 100f))) }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: (Boolean) -> Unit) {
    Text(
        text = label,
        fontSize = 10.sp,
        color = if (on) Color.White else Color(0xFFD2D6DC),
        modifier = Modifier
            .background(
                if (on) Color(0xFF4F8CFF) else Color(0xFFE8EBEF),
                RoundedCornerShape(6.dp),
            )
            .clickable { onClick(!on) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}