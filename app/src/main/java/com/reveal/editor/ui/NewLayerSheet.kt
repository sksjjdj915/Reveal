package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 图层面板（新）：从右侧滑出的白卡片，占屏 72%。
 * 只做最常用那几件：选中、显示/隐藏、加层、删层、上下移、不透明度。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NewLayerSheet(
    layers: List<SketchView.Layer>,
    activeIndex: Int,
    onPick: (Int) -> Unit,
    onToggleVisible: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onMoveTo: (Int, Int) -> Unit = { _, _ -> },
    onDuplicate: (Int) -> Unit = {},
    onUngroup: (Int) -> Unit = {},
    onRename: (Int, String) -> Unit = { _, _ -> },
    onMoveGroup: (Int, Boolean) -> Unit = { _, _ -> },
    onAlpha: (Int, Float) -> Unit,
    onPlace: () -> Unit = {},
    onGroup: () -> Unit = {},
    onToggleGroup: (Int) -> Unit = {},
    onClose: () -> Unit,
) {
    // 行模型：跳过组底分隔层；组头记深度；折叠的组（sectionType==2）后面到分隔层之间的成员跳过
    val rows = remember(layers, activeIndex) {
        val out = ArrayList<Triple<Int, SketchView.Layer, Int>>()
        var depth = 0
        var skipUntilDivider = false
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            when (l.sectionType) {
                3 -> { if (depth > 0) depth--; skipUntilDivider = false; continue }
                2 -> { out.add(Triple(i, l, depth)); depth++; skipUntilDivider = true; continue }
                1 -> { out.add(Triple(i, l, depth)); depth++; skipUntilDivider = false; continue }
                else -> { if (skipUntilDivider) continue; out.add(Triple(i, l, depth)) }
            }
        }
        out
    }
    var renameTarget by remember { mutableStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        // 点空白处关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x1A000000))
                .clickable { onClose() },
        )
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.74f)
                .heightIn(max = 640.dp)
                .padding(vertical = 10.dp),
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
            color = Color.White,
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图层", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Rv.text)
                    Text(
                        "共 ${layers.size}",
                        fontSize = 10.sp,
                        color = Rv.textFaint,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    SheetAction("组+") { onGroup() }
                    SheetAction("置入图") { onPlace() }
                    SheetAction("新建") { onAdd() }
                    SheetAction("删除") { onDelete() }
                }
                Spacer(Modifier.height(8.dp))

                // 层列表：最上面一层排最前（和画布叠放顺序一致）
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rows.size) { rowIdx ->
                        val (i, l, depth) = rows[rowIdx]
                        val on = i == activeIndex
                        val isGroup = l.sectionType == 1 || l.sectionType == 2
                        var menuOpen by remember { mutableStateOf(false) }
                        var dragAcc by remember { mutableStateOf(0f) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (depth * 10).dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (on) Rv.accentSoft else Color.Transparent)
                                .combinedClickable(
                                    onClick = { onPick(i) },
                                    onLongClick = { menuOpen = true },
                                )
                                .pointerInput(i) {
                                    detectDragGestures(
                                        onDragStart = { dragAcc = 0f },
                                    ) { change, delta ->
                                        change.consume()
                                        dragAcc += delta.y
                                        val step = 46f
                                        if (dragAcc > step) {
                                            if (isGroup) onMoveGroup(i, true) else if (i < layers.lastIndex) onMoveTo(i, i + 1)
                                            dragAcc = 0f
                                        } else if (dragAcc < -step) {
                                            if (isGroup) onMoveGroup(i, false) else if (i > 0) onMoveTo(i, i - 1)
                                            dragAcc = 0f
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clickable { onToggleVisible(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (l.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = "显示",
                                    tint = if (l.visible) Rv.textSub else Rv.textFaint,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            if (isGroup) {
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .clickable { onToggleGroup(i) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (l.sectionType == 1) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                        "折叠",
                                        tint = Rv.textSub,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                l.name,
                                fontSize = 12.sp,
                                color = if (on) Rv.accent else Rv.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (l.mask != null) {
                                Text("M", fontSize = 9.sp, color = Rv.accent, modifier = Modifier.padding(end = 6.dp))
                            }
                            if (on) Text("编辑中", fontSize = 9.sp, color = Rv.accent)
                            Box {
                                if (menuOpen) {
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = true,
                                        onDismissRequest = { menuOpen = false },
                                    ) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("复制这一层", fontSize = 12.sp) },
                                            onClick = { menuOpen = false; onDuplicate(i) },
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("打成组", fontSize = 12.sp) },
                                            onClick = { menuOpen = false; onPick(i); onGroup() },
                                        )
                                        if (isGroup) {
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text("解组（保留成员）", fontSize = 12.sp) },
                                                onClick = { menuOpen = false; onUngroup(i) },
                                            )
                                        }
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("重命名…", fontSize = 12.sp) },
                                            onClick = { menuOpen = false; renameText = l.name; renameTarget = i },
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("删除这一层", fontSize = 12.sp) },
                                            onClick = { menuOpen = false; onPick(i); onDelete() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                var alpha by remember(activeIndex, layers.size) {
                    mutableStateOf((layers.getOrNull(activeIndex)?.alpha ?: 255) / 255f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("不透明度", fontSize = 10.5.sp, color = Rv.textSub)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        ThinSlider(
                            value = alpha,
                            range = 0f..1f,
                            onChange = { v ->
                                alpha = v
                                if (activeIndex in layers.indices) onAlpha(activeIndex, v)
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${(alpha * 100).toInt()}%", fontSize = 10.5.sp, color = Rv.text)
                }
            }
        }

        if (renameTarget >= 0) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { renameTarget = -1 },
                title = { Text("重命名图层", fontSize = 14.sp) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    )
                },
                confirmButton = {
                    Text(
                        "确定",
                        fontSize = 13.sp,
                        color = Rv.accent,
                        modifier = Modifier.clickable {
                            onRename(renameTarget, renameText.trim())
                            renameTarget = -1
                        }.padding(8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        "取消",
                        fontSize = 13.sp,
                        color = Rv.textSub,
                        modifier = Modifier.clickable { renameTarget = -1 }.padding(8.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Rv.textSub)
    }
}

@Composable
private fun SheetIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Rv.textSub, modifier = Modifier.size(16.dp))
    }
}
