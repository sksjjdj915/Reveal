package com.reveal.editor.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：打开失败时把「版本、文件多大、多少位、什么颜色模式、内存上限、具体异常」记下来，
 * 首页弹窗能一键复制发给我。
 *
 * 为什么要有这个：以前失败只弹一句「打不开」，用户没法反馈，我也没法定位——16 位那个问题
 * 就是这样绕了一大圈。现在失败至少能说清是内存不够、格式不认，还是文件本身坏了。
 */
object Diag {

    private const val LOG_NAME = "diagnose.log"
    private const val MAX_BYTES = 256L * 1024L

    fun logFile(context: Context): File = File(context.filesDir, LOG_NAME)

    fun append(context: Context, tag: String, text: String) {
        runCatching {
            val f = logFile(context)
            // 超长就留最后一截，不要整份删——崩溃记录往往就在最后几行
            if (f.exists() && f.length() > MAX_BYTES) {
                runCatching {
                    val tail = f.readText().takeLast(120 * 1024)
                    f.writeText("（更早的记录已滚动掉）\n" + tail)
                }
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            f.appendText("[$ts] $tag\n$text\n\n")
        }
    }

    /** 清空日志：下次打开 App 从空的开始，免得旧记录越滚越多看不清 */
    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }

    /** 把当前日志整理成一份 txt 放进 cache/share，交给系统分享（QQ、微信、邮件都能发） */
    fun exportText(context: Context, extra: String = ""): File? = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "Reveal日志-" + SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date()) + ".txt")
        f.writeText(read(context) + extra)
        f
    }.getOrNull()

    fun read(context: Context): String {
        val head = "Reveal " + version(context) + " · " +
            android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
            " · Android " + android.os.Build.VERSION.RELEASE + "\n--- 诊断 ---\n"
        val body = runCatching { logFile(context).takeIf { it.exists() }?.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "（这个版本还没有写入任何诊断。如果刚导过 PSD 却一条没有，说明装的不是新包。）"
        return head + body
    }

    /** 给用户复制的那段话：一句话结论 + 关键事实 + 头部信息 */
    fun report(context: Context, file: File, why: String?): String {
        val sb = StringBuilder()
        sb.appendLine("Reveal 打不开文件 · 诊断信息")
        sb.appendLine("版本：" + version(context))
        sb.appendLine("手机：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
            " · Android " + android.os.Build.VERSION.RELEASE)
        sb.appendLine("内存上限：" + Runtime.getRuntime().maxMemory() / (1024L * 1024L) + " MB")
        sb.appendLine("文件：" + file.name + "（" + size(file.length()) + "）")
        sb.appendLine("头部：" + header(file))
        sb.appendLine("错误：" + (why ?: "未知"))
        return sb.toString()
    }

    private fun version(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: "?") + "（" + pi.longVersionCode + "）"
    }.getOrDefault("?")

    /** 只读前 26 字节的 PSD 头：不加载整份文件，失败之后再赔一次内存不值得 */
    private fun header(file: File): String = runCatching {
        val b = ByteArray(26)
        val n = FileInputStream(file).use { it.read(b) }
        if (n < 26) return@runCatching "文件太短（只有 " + n + " 字节）"
        if (String(b, 0, 4, Charsets.ISO_8859_1) != "8BPS") return@runCatching "开头不是 8BPS（不是 PSD，或文件不完整）"
        fun u16(at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun i32(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        val ver = u16(4)
        val channels = u16(12)
        val h = i32(14)
        val w = i32(18)
        val depth = u16(22)
        val mode = u16(24)
        val modeName = when (mode) {
            0 -> "位图"
            1 -> "灰度"
            2 -> "索引"
            3 -> "RGB"
            4 -> "CMYK"
            7 -> "多通道"
            8 -> "双色调"
            9 -> "Lab"
            else -> "未知"
        }
        "$w x $h，" + depth + " 位，" + modeName + "（模式 " + mode + "），" + channels + " 通道，PSD v" + ver
    }.getOrElse { "读不出来：" + (it.message ?: it.javaClass.simpleName) }

    private fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " 字节"
    }
}