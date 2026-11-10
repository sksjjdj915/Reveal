package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 左栏「搜索」：打几个字就过滤所有工具 / 菜单入口，点结果直接执行。
 * 单独放一个文件，避免把 EditorScreen 那个大组合函数撑爆（JVM 方法 64KB 上限）。
 */
@Composable
internal fun SearchPanel(
    cats: List<RailCategory>,
    onDismiss: () -> Unit,
    onPick: (RailEntry) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(62, 84),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(color = Color(0xFFFFFFFF), shape = RoundedCornerShape(10.dp), modifier = Modifier.width(272.dp)) {
            Column(Modifier.padding(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE8EBEF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    BasicTextField(
                        value = q,
                        onValueChange = { q = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color(0xFF23272E), fontSize = 12.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (q.isEmpty()) {
                                Text("搜索工具 / 功能", color = Color(0xFF6B7178), fontSize = 12.sp)
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                val key = q.trim()
                val hits = ArrayList<Pair<RailCategory, RailEntry>>()
                if (key.isNotEmpty()) {
                    for (c in cats) {
                        for (e in c.entries) {
                            if (e.label.contains(key, true) || c.label.contains(key, true) ||
                                c.fullName.contains(key, true)
                            ) {
                                hits.add(c to e)
                                if (hits.size >= 40) break
                            }
                        }
                        if (hits.size >= 40) break
                    }
                }
                if (key.isEmpty()) {
                    Text(
                        "打几个字就能找到功能，不用记它在哪个类别里",
                        color = Color(0xFF6B7178),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(6.dp),
                    )
                } else if (hits.isEmpty()) {
                    Text(
                        "没找到「" + key + "」",
                        color = Color(0xFF6B7178),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(6.dp),
                    )
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        hits.forEach { pair ->
                            val c = pair.first
                            val e = pair.second
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = e.enabled) { onPick(e) }
                                    .padding(horizontal = 6.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    e.label,
                                    color = if (e.enabled) Color(0xFF23272E) else Color(0xFF4A5057),
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(c.label, color = Color(0xFF6B7178), fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 左栏「修改」：列出当前选中图层上还留着参数、能回头改的东西，点哪条开哪条。
 */
@Composable
internal fun ModifyPanel(
    layerName: String,
    feats: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss) {
        Surface(color = Color(0xFFFFFFFF), shape = RoundedCornerShape(12.dp), modifier = Modifier.width(280.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("这一层能改的参数", color = Color(0xFF23272E), fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(layerName, color = Color(0xFF6B7178), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                if (feats.isEmpty()) {
                    Text(
                        "这层没有留下来的参数：笔迹、直接烤进像素的滤镜都改不回去",
                        color = Color(0xFF6B7178),
                        fontSize = 11.sp,
                    )
                } else {
                    feats.forEach { pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pair.first) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(pair.second, color = Color(0xFF23272E), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text("修改 ›", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "关闭",
                        color = Color(0xFF6B7178),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onDismiss() }.padding(6.dp),
                    )
                }
            }
        }
    }
}

/** 「修改」面板的开关壳子：把 if + 组合放进单独函数，别撑大 EditorScreen */
@Composable
internal fun ModifyPanelHost(
    show: Boolean,
    layerName: String,
    feats: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    if (!show) return
    ModifyPanel(layerName = layerName, feats = feats, onDismiss = onDismiss, onPick = onPick)
}

/** 图层样式面板的开关壳子（同样是为了别把 EditorScreen 撑爆） */
@Composable
internal fun LayerStyleHost(
    sketch: SketchView?,
    show: Boolean,
    styleFor: Int,
    onClose: () -> Unit,
    onApplied: () -> Unit,
) {
    if (!show) return
    val st = sketch?.layers?.getOrNull(styleFor) ?: return
    val initStyle = remember(styleFor) {
        sketch.styleParamsOf(st) ?: SketchView.StyleParams()
    }
    LayerStylePanel(
        layerName = st.name,
        init = initStyle,
        onDismiss = {
            // 取消：把实时预览改过的样式还原成打开时的样子
            sketch.setLayerStyle(styleFor, initStyle)
            onClose()
        },
        onApply = { params ->
            sketch.setLayerStyle(styleFor, params)
            onApplied()
        },
        onPreview = { params ->
            sketch.setLayerStyle(styleFor, params)
        },
    )
}
