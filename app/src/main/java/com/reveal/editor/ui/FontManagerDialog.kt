package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 字体管理：列出随包的开源字体（都带 OFL 授权原文），每款都用它自己的字形做预览，
 * 可以逐款删除/恢复，也能清理解压出来的副本。
 *
 * 字体在安装包里是压缩存放的，用户第一次选中某款时才解压。删除只记在本地偏好里，
 * 列表里不再出现、用到它的文字退回系统字体；想用回来说一声「恢复」即可。
 */
@Composable
fun FontManagerDialog(
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var licenseOf by remember { mutableStateOf<BundledFonts.Entry?>(null) }
    val visible = remember(tick) { BundledFonts.visible(ctx) }
    val hiddenCount = remember(tick) { BundledFonts.hidden(ctx).size }
    val extractedCount = remember(tick) { BundledFonts.extractedCount(ctx) }
    val extractedMb = remember(tick) { BundledFonts.extractedSize(ctx) / (1024.0 * 1024.0) }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(330.dp)
                .background(Color(0xFFFFFFFF), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("字体管理", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF22262B))
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7178),
                    modifier = Modifier.clickable { onClose() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                "共 ${visible.size} 款随包字体，全部 SIL OFL 1.1，可免费商用。字体在安装包里是压缩存放的" +
                    "（16 款合计约 22MB），第一次用哪款就解压哪款；删掉的字体不再出现在列表里，用到它的文字会退回系统字体。",
                fontSize = 10.sp,
                color = Color(0xFF6B7178),
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 330.dp)) {
                listOf("中", "英").forEach { lang ->
                    val group = visible.filter { it.lang == lang }
                    if (group.isNotEmpty()) {
                        item(key = "h-$lang") {
                            Text(
                                if (lang == "中") "中文" else "英文",
                                fontSize = 10.sp,
                                color = Color(0xFF5A6068),
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }
                        items(group.size, key = { "f-" + group[it].key }) { i ->
                            val e = group[i]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            ) {
                                FontSample(
                                    sample = if (e.lang == "中") "${e.label} 永" else "Aa Bb 123",
                                    typeface = remember(e.key) { BundledFonts.previewTypeface(ctx, e.key) },
                                    color = Color(0xFF22262B),
                                    modifier = Modifier.width(if (e.lang == "中") 96.dp else 78.dp).height(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(e.sizeText, fontSize = 9.sp, color = Color(0xFF5A6068))
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "许可",
                                    fontSize = 10.sp,
                                    color = Color(0xFF4F8CFF),
                                    modifier = Modifier
                                        .clickable { licenseOf = e }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                                Text(
                                    "删除",
                                    fontSize = 10.sp,
                                    color = Color(0xFFFF7A7A),
                                    modifier = Modifier
                                        .clickable {
                                            BundledFonts.setHidden(ctx, e.key, true)
                                            tick++
                                            onChanged()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(
                    "已解压 $extractedCount 款（${String.format("%.1f", extractedMb)}MB）",
                    fontSize = 10.sp,
                    color = Color(0xFF6B7178),
                )
                Spacer(Modifier.weight(1f))
                if (extractedCount > 0) {
                    Text(
                        "清理解压文件",
                        fontSize = 10.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier
                            .background(Color(0xFFE8EBEF), RoundedCornerShape(6.dp))
                            .clickable {
                                BundledFonts.clearExtracted(ctx)
                                tick++
                                onChanged()
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(
                    if (hiddenCount > 0) "已删除 $hiddenCount 款" else "没有删除任何字体",
                    fontSize = 10.sp,
                    color = Color(0xFF6B7178),
                )
                Spacer(Modifier.weight(1f))
                if (hiddenCount > 0) {
                    Text(
                        "全部恢复",
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFF4F8CFF), RoundedCornerShape(6.dp))
                            .clickable {
                                BundledFonts.restoreAll(ctx)
                                tick++
                                onChanged()
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    val show = licenseOf
    if (show != null) {
        val text = remember(show.key) { readAsset(ctx, show.license) }
        Dialog(onDismissRequest = { licenseOf = null }) {
            Column(
                modifier = Modifier
                    .width(322.dp)
                    .height(420.dp)
                    .background(Color(0xFFFFFFFF), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${show.label} · 商用许可", fontSize = 12.sp, color = Color(0xFF22262B))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "关闭",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier.clickable { licenseOf = null }.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text.ifEmpty { "许可原文读不出来（assets/fonts/license 缺失）" },
                    fontSize = 10.sp,
                    color = Color(0xFFC9CED6),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

private fun readAsset(ctx: android.content.Context, path: String): String = runCatching {
    ctx.assets.open(path).bufferedReader().use { it.readText() }
}.getOrDefault("")