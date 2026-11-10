package com.reveal.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import com.reveal.editor.data.Incoming
import com.reveal.editor.data.Diag
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.data.PsdFiles
import com.reveal.editor.ui.EditorScreen
import com.reveal.editor.ui.HomeScreen
import com.reveal.editor.ui.theme.PaletteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        logIncoming(intent)
        incoming.value = intent?.extractPsd()
        // 顶栏要显示版本号，这里把 applicationContext 交出去
        com.reveal.editor.ui.AppCtx.get = { applicationContext }
        setContent {
            PaletteTheme {
                RevealApp()
            }
        }
    }

    /** 把「对方到底怎么递过来的」记进日志：有没有给读取授权、authority 是谁 */
    private fun logIncoming(intent: Intent?) {
        val uri = intent?.extractPsd() ?: return
        val flags = intent.flags
        val granted = (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        val txt = "action=" + intent.action +
            "\nflags=0x" + Integer.toHexString(flags) + "（read=" + granted + "）" +
            "\nauthority=" + uri.authority +
            "\nuri=" + uri
        runCatching { Diag.append(this, "INTENT", txt) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logIncoming(intent)
        incoming.value = intent.extractPsd()
    }

    override fun onDestroy() {
        // 正常退出（返回键/关软件）时清掉崩溃记录：真闪退时进程是被系统直接杀的，
        // 这里不会执行，所以「有记录」就等于「这次真崩了」，不会再有旧记录误导
        if (isFinishing) {
            runCatching { CrashLog.clear(this) }
        }
        super.onDestroy()
    }

    /** 别的 App「用其他应用打开」送进来的 PSD */
    companion object {
        val incoming = mutableStateOf<Uri?>(null)
    }
}

@Suppress("DEPRECATION")
private fun Intent.extractPsd(): Uri? {
    // QQ / 微信 / 文件管理器给的位置不一样，三个地方都要看，否则「点了 Reveal 没反应」
    data?.let { return it }
    getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { return it }
    val clip = clipData ?: return null
    for (i in 0 until clip.itemCount) {
        clip.getItemAt(i)?.uri?.let { return it }
    }
    return null
}

@Composable
private fun RevealApp() {
    val context = LocalContext.current
    // 直接进画布：有工程就开最近一个，没有就先建一张 1080×1620 的新画布
    var projectId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val id = withContext(Dispatchers.IO) {
            runCatching {
                ProjectStore.list(context).firstOrNull()?.id
                    ?: ProjectStore.create(context, "未命名画布", 1080, 1620)
            }.getOrNull()
        }
        projectId = id
    }
    val id = projectId
    if (id == null) {
        Box(Modifier.fillMaxSize().background(Color.White))
    } else {
        com.reveal.editor.ui.CanvasPage(projectId = id)
    }
}

private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName ?: ""
}.getOrDefault("")