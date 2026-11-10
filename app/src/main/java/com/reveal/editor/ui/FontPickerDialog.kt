package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reveal.editor.image.BundledFonts

/**
 * 字体选择器：小窗、可滚动，每款都用它自己的字形做预览。
 * 系统字体排前面，随包开源字体分中文/英文两组。
 */
@Composable
fun FontPickerDialog(
    current: String,
    version: Int,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val bundled = remember(version) { BundledFonts.visible(ctx) }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(252.dp)
                .background(Color(0xFFFFFFFF), RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("字体", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF22262B))
                Spacer(Modifier.weight(1f))
                Text(
                    "管理字体",
                    fontSize = 10.sp,
                    color = Color(0xFF4F8CFF),
                    modifier = Modifier.clickable { onManage() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    "关闭",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier.clickable { onClose() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            LazyColumn(modifier = Modifier.heightIn(max = 340.dp).padding(top = 4.dp)) {
                item(key = "h-sys") { SectionTitle("系统") }
                items(TextFonts.size, key = { "s-" + TextFonts[it].first }) { i ->
                    val (family, label) = TextFonts[i]
                    val tf = remember(family) {
                        if (family.isEmpty()) android.graphics.Typeface.DEFAULT
                        else runCatching {
                            android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL)
                        }.getOrNull()
                    }
                    FontRow(
                        sample = if (family == "cursive") "手写 Aa" else "$label 永 Aa",
                        typeface = tf,
                        label = label,
                        on = current == family,
                        onClick = { onPick(family) },
                    )
                }
                listOf("中", "英").forEach { lang ->
                    val group = bundled.filter { it.lang == lang }
                    if (group.isNotEmpty()) {
                        item(key = "h-$lang") { SectionTitle(if (lang == "中") "中文" else "英文") }
                        items(group.size, key = { "b-" + group[it].key }) { i ->
                            val e = group[i]
                            FontRow(
                                sample = if (e.lang == "中") "${e.label} 永" else "Aa Bb 123",
                                typeface = remember(e.key) { BundledFonts.previewTypeface(ctx, e.key) },
                                label = e.label,
                                on = current == e.key,
                                onClick = { onPick(e.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        color = Color(0xFF5A6068),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun FontRow(
    sample: String,
    typeface: android.graphics.Typeface?,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(if (on) Color(0xFFE3EDFF) else Color(0x00000000), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        FontSample(
            sample = sample,
            typeface = typeface,
            color = if (on) Color.White else Color(0xFFD2D6DC),
            fontSizeSp = 14f,
            modifier = Modifier.width(112.dp).height(19.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, color = if (on) Color(0xFFAFC6FF) else Color(0xFF5A6068))
    }
}