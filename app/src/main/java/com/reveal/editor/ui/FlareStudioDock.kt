package com.reveal.editor.ui

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reveal.editor.image.FlareStudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 光晕工作室：和流行的光效插件一样，用「基础光元素」自由组合。
 * 辉光 / 光环 / 鬼影 / 星芒 / 拉丝，各元素独立位置、大小、亮度、色温；
 * 组合结果可以存成命名预设，下次一键调出。
 */
@Composable
internal fun FlareStudioDock(
    sketch: SketchView?,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 340,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sc = rememberCoroutineScope()
    val elems = remember { mutableStateListOf<FlareStudio.Elem>() }
    var sel by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    val presets = remember { mutableStateListOf<Pair<String, List<FlareStudio.Elem>>>() }
    var tick by remember { mutableStateOf(0) }
    remember(tick) { runCatching { presets.clear(); presets.addAll(loadPresets(ctx)) } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeightDp.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("光晕工作室", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Rv.text)
            Spacer(Modifier.width(8.dp))
            Text("用基础光元素自由组合", fontSize = 10.sp, color = Rv.textFaint)
        }
        Spacer(Modifier.height(8.dp))
        // 添加元素
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlareStudio.NAMES.forEachIndexed { i, name ->
                TabChip("+ " + name, false) {
                    elems.add(FlareStudio.Elem(kind = i, xp = 0.5f, yp = 0.5f, size = 0.28f, bright = 0.6f, tint = i % 3))
                    sel = elems.lastIndex
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (elems.isEmpty()) {
            Text("先点上面的名字加一个光元素；叠加多个就有复杂光效了", fontSize = 10.sp, color = Rv.textFaint)
        } else {
            elems.forEachIndexed { i, e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(if (i == sel) Rv.accentSoft else Color.Transparent)
                        .clickable { sel = i }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        FlareStudio.NAMES.getOrElse(e.kind) { "光" },
                        fontSize = 11.5.sp,
                        fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = Rv.text,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "位置 ${(e.xp * 100).toInt()}% / ${(e.yp * 100).toInt()}%  ·  大小 ${(e.size * 100).toInt()}%  ·  亮度 ${(e.bright * 100).toInt()}%",
                        fontSize = 9.5.sp,
                        color = Rv.textFaint,
                        modifier = Modifier.weight(1f),
                    )
                    Text("删", fontSize = 11.sp, color = Rv.textFaint,
                        modifier = Modifier.clickable {
                            elems.removeAt(i)
                            sel = -1
                        }.padding(horizontal = 4.dp))
                }
            }
        }
        val cur = elems.getOrNull(sel)
        if (cur != null) {
            Spacer(Modifier.height(6.dp))
            LevelRow("位置 X", cur.xp * 100f, 0f..100f) { cur.xp = it / 100f; tick++ }
            LevelRow("位置 Y", cur.yp * 100f, 0f..100f) { cur.yp = it / 100f; tick++ }
            LevelRow("大小", cur.size * 100f, 1f..90f) { cur.size = it / 100f; tick++ }
            LevelRow("亮度", cur.bright * 100f, 0f..100f) { cur.bright = it / 100f; tick++ }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabChip("中性", cur.tint == 0) { cur.tint = 0; tick++ }
                TabChip("暖", cur.tint == 1) { cur.tint = 1; tick++ }
                TabChip("冷", cur.tint == 2) { cur.tint = 2; tick++ }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DockButton(if (busy) "算…" else "预览") {
                if (elems.isEmpty()) return@DockButton
                val snapshot = elems.map { copyElem(it) }
                busy = true
                sc.launch {
                    val out = withContext(Dispatchers.Default) {
                        runCatching {
                            val b = previewBase(sketch?.flatten()) ?: return@runCatching null
                            FlareStudio.compose(b, snapshot)
                        }.getOrNull()
                    }
                    sketch?.setPreview(out)
                    busy = false
                }
            }
            DockButton("应用") {
                if (elems.isEmpty()) return@DockButton
                val snapshot = elems.map { copyElem(it) }
                sketch?.clearPreview()
                sketch?.applyFilterToActive({ bmp -> FlareStudio.compose(bmp, snapshot) }, "光晕")
            }
            DockButton("清空") { elems.clear(); sel = -1; sketch?.clearPreview(); tick++ }
        }
        Spacer(Modifier.height(10.dp))
        // 预设
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预设", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Rv.text)
            Spacer(Modifier.width(8.dp))
            TabChip("存为预设", false) {
                if (elems.isEmpty()) return@TabChip
                val list = loadPresets(ctx).toMutableList()
                val name = presetName.ifBlank { "光效 " + (list.size + 1) }
                list.add(name to elems.map { copyElem(it) })
                savePresets(ctx, list)
                presetName = ""
                tick++
            }
        }
        if (presets.isEmpty()) {
            Text("还没有预设；搭好一组光效后点「存为预设」，下次直接调出", fontSize = 10.sp, color = Rv.textFaint)
        } else {
            presets.forEach { (name, list) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        fontSize = 11.sp,
                        color = Rv.accent,
                        modifier = Modifier.weight(1f).clickable {
                            elems.clear(); list.forEach { elems.add(copyElem(it)) }
                            sel = if (elems.isEmpty()) -1 else 0
                            tick++
                        },
                    )
                    Text("删", fontSize = 11.sp, color = Rv.textFaint,
                        modifier = Modifier.clickable {
                            val all = loadPresets(ctx).filter { it.first != name }
                            savePresets(ctx, all)
                            tick++
                        }.padding(horizontal = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "辉光＝中心光核；光环＝同心圆环；鬼影＝沿光轴排的小圆；星芒＝十字细光条；拉丝＝横向长光条。多个元素叠起来就是复杂镜头光晕。",
            fontSize = 9.5.sp,
            color = Rv.textFaint,
        )
    }
}

private fun copyElem(e: FlareStudio.Elem) =
    FlareStudio.Elem(e.kind, e.xp, e.yp, e.size, e.bright, e.tint)

private fun flareFile(ctx: Context): File = File(ctx.filesDir, "flare_presets.json")

private fun loadPresets(ctx: Context): List<Pair<String, List<FlareStudio.Elem>>> {
    val f = flareFile(ctx)
    if (!f.exists()) return emptyList()
    return runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val es = o.getJSONArray("elements")
            val list = (0 until es.length()).map { k ->
                val e = es.getJSONObject(k)
                FlareStudio.Elem(
                    kind = e.optInt("kind", 0),
                    xp = e.optDouble("xp", 0.5).toFloat(),
                    yp = e.optDouble("yp", 0.5).toFloat(),
                    size = e.optDouble("size", 0.28).toFloat(),
                    bright = e.optDouble("bright", 0.6).toFloat(),
                    tint = e.optInt("tint", 1),
                )
            }
            o.optString("name", "光效") to list
        }
    }.getOrDefault(emptyList())
}

private fun savePresets(ctx: Context, list: List<Pair<String, List<FlareStudio.Elem>>>) {
    runCatching {
        val arr = JSONArray()
        list.forEach { (name, es) ->
            val o = JSONObject()
            o.put("name", name)
            val a = JSONArray()
            es.forEach { e ->
                val eo = JSONObject()
                eo.put("kind", e.kind)
                eo.put("xp", e.xp.toDouble())
                eo.put("yp", e.yp.toDouble())
                eo.put("size", e.size.toDouble())
                eo.put("bright", e.bright.toDouble())
                eo.put("tint", e.tint)
                a.put(eo)
            }
            o.put("elements", a)
            arr.put(o)
        }
        flareFile(ctx).writeText(arr.toString())
    }
}

/** 从「镜头光晕」参数面板跳到光晕工作室的通道（面板自己不知道外层工具状态） */
internal object FlareOpen {
    var onOpen: (() -> Unit)? = null
    fun request() { onOpen?.invoke() }
}
