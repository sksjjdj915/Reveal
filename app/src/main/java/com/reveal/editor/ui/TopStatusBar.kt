package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * 顶部横向状态栏：左边是工程名，右边是撤销 / 重做 / 保存 / 设置。
 * 单独成文件是为了不再往 EditorScreen 那个巨型 composable 里塞代码（它贴着 64KB 上限）。
 */
@Composable
internal fun TopStatusBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Rv.surface)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = title,
            color = Rv.text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BarAction("↶ 撤销", canUndo, onUndo)
        BarAction("↷ 重做", canRedo, onRedo)
        BarAction("保存", true, onSave)
        BarAction("?", true, onHelp)
        BarAction("⚙", true, onSettings)
    }
}

@Composable
private fun BarAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) Rv.textSub else Color(0xFFC3C8D0)
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .width(if (label.length > 2) 54.dp else 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}