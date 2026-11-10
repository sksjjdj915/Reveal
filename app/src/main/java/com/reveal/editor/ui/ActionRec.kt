package com.reveal.editor.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一步「动作」：某次滤镜/调色用的是哪一条、滑块数值、颜色槽。
 * 数值和颜色都存成数组，回放时原样喂回同一个 apply 函数。
 */
internal class StepRec(
    val id: String,
    val title: String,
    val values: FloatArray,
    val colors: IntArray,
) {
    fun encode(): String {
        val v = values.joinToString(",")
        val c = colors.joinToString(",")
        return id + "|" + title.replace("|", "/") + "|" + v + "|" + c
    }

    companion object {
        fun decode(line: String): StepRec? {
            val parts = line.split("|")
            if (parts.size < 4) return null
            val id = parts[0]
            if (id.isEmpty()) return null
            val values = part1(parts[2])
            val colors = part1(parts[3]).map { it.toInt() }.toIntArray()
            return StepRec(id, parts[1], values, colors)
        }

        private fun part1(s: String): FloatArray =
            if (s.isEmpty()) FloatArray(0) else s.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
    }
}

/**
 * 动作库：把「我刚才调了什么」记成一串步骤，之后可以整串回放，也能存起来下次用。
 * 录制只发生在滤镜/调色的「确定」上，一步一条，跟 桌面端的动作面板一个思路。
 */
internal object ActionBank {
    private const val PREFS = "reveal_actions"
    private const val KEY = "steps"
    private const val MAX_STEPS = 200

    val steps = mutableListOf<StepRec>()
    var recording: Boolean = false
    private var loaded = false

    fun record(id: String, title: String, values: FloatArray, colors: IntArray) {
        if (!recording) return
        val idx = steps.indexOfFirst { it.id == id }
        val rec = StepRec(id, title, values.copyOf(), colors.copyOf())
        if (idx >= 0) steps[idx] = rec else steps.add(rec)
        if (steps.size > MAX_STEPS) steps.removeAt(0)
    }

    fun clear() {
        steps.clear()
    }

    fun encoded(): String = steps.joinToString("\n") { it.encode() }

    fun decode(text: String): Int {
        val list = text.split("\n").mapNotNull { StepRec.decode(it.trim()) }
        steps.clear()
        steps.addAll(list)
        return steps.size
    }

    fun save(context: Context?): Int {
        val ctx = context ?: return 0
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encoded()).apply()
        return steps.size
    }

    fun load(context: Context?): Int {
        val ctx = context ?: return 0
        if (loaded) return steps.size
        val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        loaded = true
        if (text.isBlank()) return 0
        return decode(text)
    }

    /** 一步跑一张图：找不到这个 id 就返回 null，让调用方跳过。 */
    fun runStep(rec: StepRec, bmp: android.graphics.Bitmap): android.graphics.Bitmap? {
        val spec = fxSpec(rec.id) ?: return null
        return runCatching { spec.apply(rec.values, rec.colors, bmp) }.getOrNull()
    }

    /** 整串回放到当前图层，返回跑完了几步（可撤销，撤销一次退掉整串）。 */
    fun replay(sketch: SketchView?): Int {
        val s = sketch ?: return 0
        if (steps.isEmpty()) return 0
        var count = 0
        val list = steps.toList()
        s.applyFilterToActive({ bmp ->
            var cur: android.graphics.Bitmap? = bmp
            count = 0
            for (rec in list) {
                val next = runStep(rec, cur ?: return@applyFilterToActive null) ?: break
                if (next !== cur) (cur as? android.graphics.Bitmap)?.takeIf { it !== bmp }?.recycle()
                cur = next
                count++
            }
            cur
        }, "回放动作（${list.size} 步）")
        return count
    }
}

/** 动作录制面板：录/停、看步数、回放、保存、读取、清空。 */
@Composable
internal fun ActionBankPanel(sketch: SketchView?, onClose: () -> Unit) {
    val ctx = sketch?.context
    var tick by remember { mutableIntStateOf(0) }
    var rec by remember { mutableStateOf(ActionBank.recording) }
    var msg by remember { mutableStateOf("") }
    StepCountLoad(ctx)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("动作录制", color = Color(0xFF22262B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "开着录制时，你在「滤镜」「调色」里每点一次确定都会被记成一步；关掉录制就不再记。回放会把这一串按顺序再过一遍（只影响当前图层，一次撤销可退掉整串）。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text("已记录 ${ActionBank.steps.size} 步 · 录制${if (rec) "中" else "关"}", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        if (ActionBank.steps.isNotEmpty()) {
            Text(
                ActionBank.steps.takeLast(4).joinToString(" → ") { it.title },
                color = Color(0xFF6B7178),
                fontSize = 11.sp,
            )
        }
        if (msg.isNotEmpty()) Text(msg, color = Color(0xFF7CD992), fontSize = 11.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                ActionBank.recording = !ActionBank.recording
                rec = ActionBank.recording
                msg = if (rec) "开始录制" else "停止录制"
                tick++
            }) { Text(if (rec) "停止录制" else "开始录制", fontSize = 11.sp) }
            TextButton(onClick = {
                val n = ActionBank.replay(sketch)
                msg = if (n == 0) "没有可回放的步骤" else "回放完成：$n 步"
                tick++
            }) { Text("回放", fontSize = 11.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                val n = ActionBank.save(ctx)
                msg = if (n == 0) "没有可保存的步骤" else "已保存 $n 步"
                tick++
            }) { Text("保存", fontSize = 11.sp) }
            TextButton(onClick = {
                val n = ActionBank.load(ctx)
                msg = if (n == 0) "没有存过的动作" else "读回 $n 步"
                tick++
            }) { Text("读取", fontSize = 11.sp) }
            TextButton(onClick = {
                ActionBank.clear()
                msg = "已清空"
                tick++
            }) { Text("清空", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Text("智能滤镜（非破坏性）", color = Color(0xFF22262B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "把上面那串动作套到「当前图层」上，但像素一点不改——随时能清掉、能关掉看原图。"
                + "之后在这一层上继续画，滤镜会按新像素重新算；拼合、导出、缩略图都带着滤镜效果。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        val fxNames = sketch?.smartFilterNames().orEmpty()
        val fxOn = sketch?.smartFiltersEnabled() ?: true
        Text(
            if (fxNames.isEmpty()) "当前图层：没有智能滤镜"
            else "当前图层：" + fxNames.joinToString(" → ") + if (fxOn) "" else "（已关掉，显示原图）",
            color = if (fxNames.isEmpty()) Color(0xFF6B7178) else Color(0xFF7CD992),
            fontSize = 11.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                val n = sketch?.addSmartFilters() ?: 0
                msg = if (n == 0) "动作是空的，先录几步或点读取" else "已套上 $n 个智能滤镜（像素没改）"
                tick++
            }) { Text("套成智能滤镜", fontSize = 11.sp) }
            TextButton(onClick = {
                val on = !(sketch?.smartFiltersEnabled() ?: true)
                msg = if (sketch?.setSmartFiltersEnabled(on) == true)
                    (if (on) "滤镜已打开" else "滤镜已关掉，现在是原图") else "当前图层没有智能滤镜"
                tick++
            }) { Text(if (fxOn) "关掉看原图" else "打开滤镜", fontSize = 11.sp) }
            TextButton(onClick = {
                val n = sketch?.clearSmartFilters() ?: 0
                msg = if (n == 0) "当前图层没有智能滤镜" else "已清掉 $n 个智能滤镜（像素本来就没改）"
                tick++
            }) { Text("清掉滤镜", fontSize = 11.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StepCountLoad(ctx: Context?) {
    remember(ctx) {
        ActionBank.load(ctx)
        true
    }
}