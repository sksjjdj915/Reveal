package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 钢笔工具面板：点一下落一个锚点，锚点之间用平滑曲线连（可切折线），
 * 然后可以填充 / 描边 / 变成选区。
 */
@Composable
internal fun PenToolBar(sketch: SketchView?) {
    var smooth by remember { mutableStateOf(sketch?.penSmooth ?: true) }
    var closed by remember { mutableStateOf(sketch?.penClosed ?: false) }
    var count by remember { mutableStateOf(sketch?.penPointCount() ?: 0) }

    Column {
        ToolOptionsBar(
            title = "钢笔",
            hint = "在画面上点一下落一个锚点，点够了再选填充 / 描边 / 变选区",
            chips = listOf("平滑曲线", "折线"),
            chipIndex = if (smooth) 0 else 1,
            onChip = { smooth = it == 0; sketch?.penSmooth = it == 0 },
            actionLabel = if (closed) "闭合：开" else "闭合：关",
            onAction = {
                closed = !closed
                sketch?.penSetClosed(closed)
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF21C1C1E))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PenChip("填充") { sketch?.penPaint(true) }
                PenChip("描边") { sketch?.penPaint(false) }
                PenChip("变选区") {
                    if (sketch?.penToSelection() == true) {
                        count = 0
                        closed = false
                    }
                }
                PenChip("撤一个点") {
                    sketch?.penUndoPoint()
                    count = sketch?.penPointCount() ?: 0
                }
                PenChip("清空") {
                    sketch?.penClear()
                    count = 0
                    closed = false
                }
                Text(
                    text = "已落 " + count + " 个点",
                    color = Color(0xFF6B7178),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                text = "填充用当前前景色；描边粗细用底部的画笔大小",
                color = Color(0xFF5F656C),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PenChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontSize = 11.sp, color = Color(0xFFC9CED6))
    }
}
