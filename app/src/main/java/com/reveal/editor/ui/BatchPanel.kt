package com.reveal.editor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 批处理：挑几张图片，把「动作录制」里那串步骤挨个跑一遍，结果存成 PNG。
 * 不需要什么权限，存到 App 自己的外部目录 batch/ 里，面板上直接显示路径。
 */
@Composable
internal fun BatchPanel(sketch: SketchView?, onClose: () -> Unit) {
    val ctx = sketch?.context
    var status by remember { mutableStateOf("挑几张图片，会把「动作录制」里的步骤依次套在每张上。") }
    var busy by remember { mutableStateOf(false) }
    var frameStep by remember { mutableStateOf(2) }
    var videoFps by remember { mutableStateOf(24) }
    val scope = rememberCoroutineScope()
    val steps = ActionBank.steps.toList()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            status = "没选图片"
            return@rememberLauncherForActivityResult
        }
        busy = true
        status = "正在处理 ${uris.size} 张…"
        scope.launch {
            val done = withContext(Dispatchers.IO) { runBatch(ctx, uris) }
            busy = false
            status = done
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F5F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text("批处理", color = Color(0xFF22262B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "把动作录制里记下的步骤，按顺序套到每一张选中的图片上，处理完存成 PNG（App 目录 batch/ 下）。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Text("当前动作：${ActionBank.steps.size} 步", color = Color(0xFFD2D6DC), fontSize = 12.sp)
        if (ActionBank.steps.isNotEmpty()) {
            Text(ActionBank.steps.joinToString(" → ") { it.title }, color = Color(0xFF6B7178), fontSize = 11.sp)
        } else {
            Text("还没有动作：先到「动作录制…」里录几步再来", color = Color(0xFFE0A030), fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(status, color = Color(0xFF7CD992), fontSize = 11.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy && ActionBank.steps.isNotEmpty(), onClick = {
                picker.launch(arrayOf("image/*"))
            }) { Text("选图片并开始", fontSize = 11.sp) }
            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Text("导出帧（做动画 / 视频用）", color = Color(0xFF22262B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "把当前文档的历史记录，每隔几画一步存成一张 PNG（App 目录 frames/ 下，按顺序编号），"
                + "或者直接用手机自带的硬件编码器压成一个 MP4 视频（videos/ 下）。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 5, 10).forEach { n ->
                TextButton(enabled = !busy, onClick = { frameStep = n }) {
                    Text(
                        if (frameStep == n) "每 ${n} 步 ✓" else "每 ${n} 步",
                        fontSize = 11.sp,
                        color = if (frameStep == n) Color(0xFF7CD992) else Color(0xFF6B7178),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy && sketch != null, onClick = {
                busy = true
                status = "正在导出帧…"
                scope.launch {
                    val r = exportFrames(sketch, ctx, frameStep)
                    busy = false
                    status = r
                }
            }) { Text("导出历史帧", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("导出视频（MP4 / H.264）", color = Color(0xFF22262B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "同样的历史帧，直接压成一个 MP4：每张是一帧，帧越多播放越慢越流畅。"
                + "用手机自带的硬件编码器，长边超过 1920 会自动缩小。",
            color = Color(0xFF6B7178),
            fontSize = 11.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(12, 24, 30).forEach { f ->
                TextButton(enabled = !busy, onClick = { videoFps = f }) {
                    Text(
                        if (videoFps == f) "${f} 帧/秒 ✓" else "${f} 帧/秒",
                        fontSize = 11.sp,
                        color = if (videoFps == f) Color(0xFF7CD992) else Color(0xFF6B7178),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy && sketch != null, onClick = {
                busy = true
                status = "正在编码视频…"
                scope.launch {
                    val r = exportVideo(sketch, ctx, frameStep, videoFps)
                    busy = false
                    status = r
                }
            }) { Text("导出视频 MP4", fontSize = 11.sp) }
        }
    }
    // 面板打开时顺手把保存过的动作读回来，「当前动作」才不会是空的
    remember(ctx) { ActionBank.load(ctx); true }
}

/** 真正干活的部分：解码 → 依次套动作 → 存 PNG。返回给面板看的一句话。 */
private fun runBatch(ctx: android.content.Context?, uris: List<Uri>): String {
    if (ctx == null) return "没有上下文，处理不了"
    val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "batch")
    if (!dir.exists() && !dir.mkdirs()) return "建不了输出目录"
    var ok = 0
    var fail = 0
    var lastErr = ""
    uris.forEachIndexed { index, uri ->
        var bmp: Bitmap? = null
        try {
            bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp == null) {
                fail++
                lastErr = "第 ${index + 1} 张读不出来"
                return@forEachIndexed
            }
            var cur: Bitmap = bmp
            for (rec in ActionBank.steps.toList()) {
                val next = ActionBank.runStep(rec, cur)
                if (next == null) break
                if (next !== cur) cur.recycle()
                cur = next
            }
            val name = "batch_${System.currentTimeMillis()}_${index + 1}.png"
            File(dir, name).outputStream().use { out -> cur.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (cur !== bmp) cur.recycle()
            ok++
        } catch (e: Exception) {
            fail++
            lastErr = e.message ?: "未知错误"
        } finally {
            bmp?.recycle()
        }
    }
    val where = dir.absolutePath
    return if (fail == 0) {
        "处理完成：$ok 张，存在 $where"
    } else {
        "处理了 $ok 张，失败 $fail 张（$lastErr），存在 $where"
    }
}

/**
 * 帧导出：把历史记录每隔 [step] 步渲染一张 PNG（App 目录 frames/ 下，按顺序编号）。
 * flatten() 必须在主线程调用（它读的是 View 的状态），压缩存盘丢到 IO 线程。
 * 导完把历史游标放回原来的位置，免得用户发现自己的画被倒回去了。
 */
private suspend fun exportFrames(sketch: SketchView?, ctx: android.content.Context?, step: Int): String {
    if (sketch == null || ctx == null) return "没有上下文，导不了"
    val total = sketch.historyTotal()
    val keep = sketch.historyPosition()
    if (total < 1) return "历史里还没有步骤，画几笔再来"
    val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "frames")
    if (!dir.exists() && !dir.mkdirs()) return "建不了输出目录"
    val stamp = System.currentTimeMillis()
    var n = 0
    var pos = 0
    var bytes = 0L
    try {
        while (pos <= total) {
            sketch.jumpToHistory(pos)
            val bmp = sketch.flatten()
            if (bmp == null) {
                if (pos == 0) { pos += step; continue } else break
            }
            n++
            val name = "frame_${stamp}_${n.toString().padStart(4, '0')}.png"
            val file = File(dir, name)
            val saved = withContext(Dispatchers.IO) {
                try {
                    file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    file.length()
                } catch (e: Exception) {
                    0L
                }
            }
            bmp.recycle()
            if (saved <= 0L) break
            bytes += saved
            if (pos == total) break
            pos = (pos + step).coerceAtMost(total)
        }
    } finally {
        sketch.jumpToHistory(keep)
    }
    if (n == 0) return "一张都没导出来，看看历史面板里有没有步骤"
    val mb = bytes / 1024.0 / 1024.0
    return "导出 $n 张（每 $step 步一张，共 ${String.format("%.1f", mb)} MB），存在 ${dir.absolutePath}"
}

/**
 * 视频导出：同样沿历史每 [step] 步取一格画面，直接喂给手机的 H.264 编码器压成 MP4。
 * 取画面（jumpToHistory + flatten）必须在主线程，编码丢到 IO 线程，一帧喂完就回收。
 * 导完同样把历史游标放回去。编码器只有真机上有，模拟器会直接报错。
 */
private suspend fun exportVideo(
    sketch: SketchView?,
    ctx: android.content.Context?,
    step: Int,
    fps: Int,
): String {
    if (sketch == null || ctx == null) return "没有上下文，导不了"
    val total = sketch.historyTotal()
    val keep = sketch.historyPosition()
    if (total < 1) return "历史里还没有步骤，画几笔再来"
    val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "videos")
    if (!dir.exists() && !dir.mkdirs()) return "建不了输出目录"
    val file = File(dir, "video_${System.currentTimeMillis()}.mp4")
    var writer: Mp4Writer? = null
    var n = 0
    var err = ""
    var pos = 0
    try {
        while (pos <= total) {
            val bmp = withContext(Dispatchers.Main) {
                sketch.jumpToHistory(pos)
                sketch.flatten()
            }
            if (bmp == null) {
                if (pos == 0) { pos += step; continue } else break
            }
            if (writer == null) {
                val (w, h) = Mp4Writer.evenSize(bmp.width, bmp.height)
                val w2 = Mp4Writer(w, h, fps, file)
                withContext(Dispatchers.IO) { w2.start() }
                writer = w2
            }
            withContext(Dispatchers.IO) { writer?.addFrame(bmp) }
            bmp.recycle()
            n++
            if (pos == total) break
            pos = (pos + step).coerceAtMost(total)
        }
    } catch (e: Exception) {
        err = e.message ?: "编码器不给用"
    } finally {
        withContext(Dispatchers.IO) {
            runCatching { writer?.finish() }
        }
        sketch.jumpToHistory(keep)
    }
    if (n == 0) {
        runCatching { file.delete() }
        return if (err.isEmpty()) "一帧都没取到，看看历史面板里有没有步骤" else "导出失败：$err"
    }
    val kb = file.length() / 1024.0
    val sec = String.format("%.1f", n.toFloat() / fps)
    return if (err.isEmpty()) {
        "导出 $n 帧 / $sec 秒，${String.format("%.0f", kb)} KB，存在 ${file.absolutePath}"
    } else {
        "导了 $n 帧但中途出错（$err），文件可能不完整：${file.absolutePath}"
    }
}
