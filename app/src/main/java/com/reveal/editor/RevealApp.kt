package com.reveal.editor

import android.app.Application
import com.reveal.editor.data.Diag
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃/异常日志。手机上看不到 logcat，就把异常写进应用私有目录，
 * 用户在「帮助 → 错误日志」里能看到，截图发过来就能定位。
 */
object CrashLog {
    private const val NAME = "reveal_crash.txt"
    private const val MAX_CHARS = 64_000

    fun file(context: Context): File = File(context.filesDir, NAME)

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun append(context: Context, tag: String, t: Throwable) {
        runCatching {
            val head = "==== " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()) +
                "  " + tag + "\n"
            val body = Log.getStackTraceString(t) + "\n"
            val f = file(context)
            val old = if (f.exists()) f.readText() else ""
            val merged = old + head + body
            f.writeText(if (merged.length > MAX_CHARS) merged.takeLast(MAX_CHARS) else merged)
        }
    }
}

class RevealApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            runCatching { SelfTest.run(this) }.onFailure {
                Log.e(SelfTest.TAG, "自检异常: " + Log.getStackTraceString(it))
            }
        }.start()
        runCatching {
            val v = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"
            // 以前这里 Diag.clear() 会把上一次闪退前的记录抹掉，
            // 现在只追加，长度由 Diag 自己滚动控制
            Diag.append(this, "APP START", "v=" + v)
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.append(this, "未捕获异常 @${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
