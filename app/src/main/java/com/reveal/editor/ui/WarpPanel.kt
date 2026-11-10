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
 * 变形（网格变形）：图层上盖一张 4×4 的网，把网上的点拖到哪，
 * 画面就跟着弯到哪。拖完点「应用网格」才真的改像素。
 */
@Composable
internal fun WarpToolBar(sketch: SketchView?) {
    var note by remember { mutableStateOf("") }

    Column {
        ToolOptionsBar(
            title = "变形",
            hint = "按住网格上的蓝点拖动，画面会跟着弯；拖完点「应用网格」定下来",
            actionLabel = "应用网格",
            onAction = {
                note = if (sketch?.warpBake() == true) "已把变形应用到图层" else "先拖几个点，或者模板已经是原样"
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
                WarpChip("重置网格") {
                    sketch?.warpResetMesh()
                    note = "网格已还原成田字格"
                }
                WarpChip("应用网格") {
                    note = if (sketch?.warpBake() == true) "已把变形应用到图层" else "还没有可以应用的变形"
                }
                WarpChip("关掉变形") {
                    sketch?.warpOff()
                    note = "变形已关掉，画面回到原样"
                }
                if (note.isNotEmpty()) {
                    Text(
                        text = note,
                        color = Color(0xFF8FD18A),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            Text(
                text = "网格是 4×4 共 16 个点；只作用当前图层。点「应用网格」后网格消失，像素就定死了。",
                color = Color(0xFF5F656C),
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun WarpChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontSize = 11.sp, color = Color(0xFFC9CED6))
    }
}