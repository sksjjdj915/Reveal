package com.reveal.editor.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 六个模式：一个模式 = 一套工具流程。切模式只换左侧工具，画布 / 图层 / 缩放都不动。
 * 顺序按功能完善度排：抠图 → 调色 → 排版 → 修图 → 绘制 → AI。
 */
enum class RvMode(val label: String, val hint: String) {
    CUT("抠图", "选区与蒙版"),
    COLOR("调色", "色阶曲线与滤镜"),
    LAYOUT("排版", "文字形状与变换"),
    RETOUCH("修图", "修复与局部处理"),
    PAINT("绘制", "画笔与上色"),
    AI("AI", "待接入"),
}

val rvModes: List<RvMode> = listOf(RvMode.CUT, RvMode.COLOR, RvMode.LAYOUT, RvMode.RETOUCH, RvMode.PAINT, RvMode.AI)

/** 修图类工具（从「绘制」里分出来） */
private val retouchLabels = setOf(
    "修复画笔", "污点修复", "仿制图章", "图案图章", "红眼", "历史画笔", "艺术历史",
    "减淡", "加深", "海绵", "涂抹", "颜色替换", "混合器", "魔术橡皮",
)

/** 任何模式都保留的全局类目 */
private val globalCats = setOf("图层", "样式", "辅助", "文件", "面板")

/**
 * 按模式过滤左侧类目。
 * - 全局类目（图层/样式/辅助/文件/面板）永远在；
 * - 绘制类目按工具再切一刀：修图模式只留修复类，绘制模式只留上色类。
 */
internal fun filterForMode(cats: List<RailCategory>, mode: RvMode): List<RailCategory> {
    fun pick(vararg names: String) = cats.filter { it.label in names }
    val base = when (mode) {
        RvMode.CUT -> pick("选区")
        RvMode.COLOR -> pick("调色", "滤镜")
        RvMode.LAYOUT -> pick("变换", "文字", "形状")
        RvMode.RETOUCH -> pick("绘制", "特效").mapNotNull { c ->
            val es = c.entries.filter { it.label in retouchLabels }
            if (es.isEmpty()) null else RailCategory(c.label, c.fullName, c.icon, es)
        }
        RvMode.PAINT -> pick("绘制").mapNotNull { c ->
            val es = c.entries.filter { it.label !in retouchLabels }
            if (es.isEmpty()) null else RailCategory(c.label, c.fullName, c.icon, es)
        }
        RvMode.AI -> emptyList()
    }
    return base + cats.filter { it.label in globalCats }
}

/** 顶部模式栏：点一下就换一套工具，选中态用滑块动画过渡 */
@Composable
internal fun ModeBar(current: RvMode, onSelect: (RvMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Rv.surface, modifier = modifier.fillMaxWidth(), shadowElevation = 2.dp) {
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rvModes.forEach { m ->
                ModePill(m == current, m.label) { onSelect(m) }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Rv.line),
        )
        }
    }
}

@Composable
private fun ModePill(selected: Boolean, label: String, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) Rv.accent else Color.Transparent,
        animationSpec = tween(Rv.durBase, easing = RvEase),
        label = "modeBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color.White else Rv.textSub,
        animationSpec = tween(Rv.durBase, easing = RvEase),
        label = "modeFg",
    )
    val sc by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = tween(Rv.durBase, easing = RvEase),
        label = "modeScale",
    )
    Box(
        modifier = Modifier
            .scale(sc)
            .clip(RoundedCornerShape(Rv.rMd))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
        )
    }
}

/** 右下角图层按钮：整个编辑器里最显眼的一个入口 */
@Composable
internal fun LayerFab(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Rv.accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Layers, "图层", tint = Color.White, modifier = Modifier.size(26.dp))
        if (count > 0) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 5.dp)
                    .size(17.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$count", fontSize = 9.sp, color = Rv.accentText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** AI 模式占位：说明这里将来放什么、现在能做什么，不要假装有功能 */
@Composable
internal fun AiPlaceholder(onPick: (RvMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(20.dp)
            .fillMaxWidth(0.86f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = Color.White,
        contentColor = Rv.text,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("AI 模式还没接入", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Rv.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "这里以后放：文生图、局部重绘、抠图/扩图、智能放大、风格化。" +
                    "现在能用的都在另外五个模式里，点下面任意一个切过去。",
                fontSize = 11.sp,
                color = Rv.textSub,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("抠图", false) { onPick(RvMode.CUT) }
                TabChip("调色", false) { onPick(RvMode.COLOR) }
                TabChip("绘制", false) { onPick(RvMode.PAINT) }
                TabChip("修图", false) { onPick(RvMode.RETOUCH) }
            }
        }
    }
}

/** 左侧竖排模式栏：一列模式，选中的是浅蓝圆角块。栏只包住内容，不拖到屏幕底 */
@Composable
internal fun ModeRail(current: RvMode, onSelect: (RvMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = Rv.appBg,
    ) {
        Column(
            modifier = Modifier
                .width(58.dp)
                .padding(top = 6.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rvModes.forEach { m ->
                val on = m == current
                val bg by animateColorAsState(
                    targetValue = if (on) Rv.accentSoft else Color.Transparent,
                    animationSpec = tween(Rv.durFast, easing = RvEase),
                    label = "modeRailBg",
                )
                val fg by animateColorAsState(
                    targetValue = if (on) Rv.accent else Rv.textSub,
                    animationSpec = tween(Rv.durFast, easing = RvEase),
                    label = "modeRailFg",
                )
                Column(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(width = 50.dp, height = 54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .clickable { onSelect(m) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = modeIcon(m),
                        contentDescription = m.label,
                        tint = fg,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(m.label, fontSize = 10.sp, color = fg)
                }
            }
        }
    }
}

private fun modeIcon(m: RvMode): ImageVector = when (m) {
    RvMode.CUT -> Icons.Filled.CenterFocusStrong
    RvMode.COLOR -> Icons.Filled.Tune
    RvMode.LAYOUT -> Icons.Filled.TextFields
    RvMode.RETOUCH -> Icons.Filled.Healing
    RvMode.PAINT -> Icons.Filled.Brush
    RvMode.AI -> Icons.Filled.AutoAwesome
}
