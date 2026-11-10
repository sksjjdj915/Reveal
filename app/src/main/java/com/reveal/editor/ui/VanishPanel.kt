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
 * 消失点：在画面里点 4 个角圈出一块「有透视的平面」，
 * 然后把这平面拉正成正面看的矩形（新图层），或者直接变成选区。
 */
@Composable
internal fun VanishToolBar(sketch: SketchView?, onExit: () -> Unit = {}) {
    var count by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }

    Column {
        ToolOptionsBar(
            title = "消失点",
            hint = "依次点这块平面的 4 个角（左上→右上→右下→左下），再点「拉正」",
            chips = emptyList(),
            chipIndex = 0,
            onChip = {},
            actionLabel = null,
            onAction = {},
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
                VanishChip("退出") {
                    sketch?.vanishClear()
                    count = 0
                    msg = ""
                    onExit()
                }
                VanishChip("拉正为新图层") {
                    msg = if (sketch?.vanishStraighten() == true) "已生成「透视校正」图层" else "先点满 4 个角"
                    count = 0
                }
                VanishChip("透视裁剪成新文档") {
                    msg = if (sketch?.perspectiveCrop() == true) "已按拉正后的画面新建文档" else "先点满 4 个角"
                    count = 0
                }
                VanishChip("变成选区") {
                    msg = if (sketch?.vanishToSelection() == true) "选区已生成" else "先点满 4 个角"
                }
                VanishChip("撤一个角") {
                    sketch?.vanishUndoPoint()
                    count = sketch?.vanishPointCount() ?: 0
                }
                VanishChip("清空") {
                    sketch?.vanishClear()
                    count = 0
                    msg = ""
                }
                Text(
                    text = "已点 " + (sketch?.vanishPointCount() ?: count) + "/4" + (if (msg.isEmpty()) "" else " · " + msg),
                    color = Color(0xFF6B7178),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                text = "拉正为新图层：贴在原位不拉伸；透视裁剪：整张文档换成拉正后的画面（外面的内容会丢）；变成选区：只取这块区域",
                color = Color(0xFF5F656C),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun VanishChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontSize = 11.sp, color = Color(0xFFC9CED6))
    }
}
