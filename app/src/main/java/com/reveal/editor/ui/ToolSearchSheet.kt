package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 工具搜索：输入即过滤全部模式的工具（含滤镜子工具）。
 * 选中后由调用方切模式 → 其它模式的入口自然关闭（activeTool 只有一个）。
 */
@Composable
internal fun ToolSearchSheet(
    onPick: (String, NewMode) -> Unit,
    onClose: () -> Unit,
) {
    val all = remember { allToolsForSearch() }
    var q by remember { mutableStateOf("") }
    val hits = remember(q) {
        if (q.isBlank()) all else all.filter {
            it.second.contains(q, true) || it.first.contains(q, true)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable { onClose() },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp, start = 10.dp, end = 10.dp)
                .fillMaxWidth()
                .heightIn(max = 460.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索工具", fontSize = 13.sp, color = Rv.text)
                    Spacer(Modifier.weight(1f))
                    Text("关闭", fontSize = 11.sp, color = Rv.textSub, modifier = Modifier.clickable { onClose() })
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Rv.surfaceAlt)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (q.isEmpty()) Text("打字过滤：画笔 / 曲线 / 马赛克 / 光晕…", fontSize = 11.sp, color = Rv.textFaint)
                    BasicTextField(
                        value = q,
                        onValueChange = { q = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Rv.text, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(hits) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(t.first, t.third) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t.second,
                                fontSize = 12.sp,
                                color = Rv.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(t.third.label, fontSize = 10.sp, color = Rv.textFaint)
                        }
                    }
                }
            }
        }
    }
}
