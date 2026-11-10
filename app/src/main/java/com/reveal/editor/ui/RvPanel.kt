package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一的参数面板外壳：白色卡片 + 标题行（左标题 / 右 ✕）+ 内容区。
 * 所有弹出来的参数面板都套这一层，保证圆角、阴影、内边距、标题字号一致。
 */
@Composable
internal fun RvPanel(
    title: String,
    onClose: (() -> Unit)? = null,
    subtitle: String? = null,
    scrollable: Boolean = true,
    maxHeightDp: Int = 420,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        contentColor = Rv.text,
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeightDp.dp)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Rv.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onClose() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("✕", fontSize = 13.sp, color = Rv.textFaint) }
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 10.sp,
                    color = Rv.textSub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 面板里的一组参数：小标题 + 一行分隔线，让长面板有层次 */
@Composable
internal fun ColumnScope.RvGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 10.5.sp, color = Rv.textFaint)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Rv.line),
        )
    }
    Spacer(Modifier.height(2.dp))
    content()
}

/**
 * 细滚动条：内容超出一屏时在右侧显示一根 3dp 的竖条，位置/长度跟随滚动状态。
 * Compose 的 verticalScroll 默认不画滚动条，没有它用户不知道下面还有没有参数。
 */
@Composable
internal fun ScrollBar(state: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier) {
    val max = state.maxValue
    if (max <= 0) return
    BoxWithConstraints(modifier.fillMaxHeight().width(4.dp)) {
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val frac = (hPx / (hPx + max)).coerceIn(0.12f, 1f)
        val thumbH = hPx * frac
        val top = (state.value.toFloat() / max) * (hPx - thumbH)
        Box(
            Modifier
                .offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { top.toDp() })
                .width(3.dp)
                .height(with(androidx.compose.ui.platform.LocalDensity.current) { thumbH.toDp() })
                .clip(RoundedCornerShape(2.dp))
                .background(Rv.lineStrong),
        )
    }
}
