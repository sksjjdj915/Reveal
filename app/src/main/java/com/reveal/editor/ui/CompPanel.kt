package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 图层复合：存下当前「显示/隐藏 + 当前层」，点一下切回那套状态 */
@Composable
internal fun LayerCompPanel(sketch: SketchView?, onClose: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val names = remember(tick) { sketch?.layerCompNames() ?: emptyList() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("图层复合", color = Color(0xFF22262B), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "把现在「哪些层显示、哪层被选中」存成一份，以后点名字就能切回这套状态。",
            color = Color(0xFF6B7178), fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompChip("存为复合 " + (names.size + 1)) {
                sketch?.saveLayerComp("复合 " + (names.size + 1))
                tick++
            }
            CompChip("关闭") { onClose() }
        }
        if (names.isEmpty()) {
            Text("还没有存过复合", color = Color(0xFF6B7178), fontSize = 11.sp)
        }
        names.forEachIndexed { i, n ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompChip(n) { sketch?.applyLayerComp(i) }
                CompChip("×") { sketch?.deleteLayerComp(i); tick++ }
            }
        }
    }
}

@Composable
private fun CompChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF22262B),
        fontSize = 11.sp,
        modifier = Modifier
            .background(Color(0xFFFFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
