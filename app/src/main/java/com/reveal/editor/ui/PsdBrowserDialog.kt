package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.data.PsdFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BarColor = Color(0xFFF4F5F7)
private val BarHeader = Color(0xFFFFFFFF)
private val Accent = Color(0xFF8AB4F8)

/**
 * 手机里的 PSD：要「所有文件访问权限」，然后像电脑一样逐级点，或者一键扫。
 * 列表只显示、点哪个才复制哪个；原文件一个字节都不会动。
 */
@Composable
fun PsdBrowserDialog(
    onDismiss: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(PsdFiles.hasAllFiles()) }
    var tab by remember { mutableStateOf("scan") } // scan / browse
    var entries by remember { mutableStateOf<List<PsdFiles.Node>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }
    var scanText by remember { mutableStateOf("") }
    var busyText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // 逐级浏览
    var stack by remember { mutableStateOf<List<File>>(emptyList()) }
    var dirs by remember { mutableStateOf<List<PsdFiles.Node>>(emptyList()) }
    var files by remember { mutableStateOf<List<PsdFiles.Node>>(emptyList()) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        granted = PsdFiles.hasAllFiles()
        if (granted) error = null else error = "开关还没打开，我进不去手机文件夹呀"
    }

    fun loadThumbs(list: List<PsdFiles.Node>) {
        scope.launch {
            for (n in list) {
                val key = n.file.absolutePath
                if (thumbs.containsKey(key)) continue
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { PsdFiles.thumbnail(n.file) }.getOrNull()
                } ?: continue
                thumbs = thumbs + (key to bmp)
            }
        }
    }

    fun openDir(path: List<File>) {
        scope.launch {
            val dir = path.lastOrNull() ?: PsdFiles.storageRoot()
            val (d, f) = withContext(Dispatchers.IO) { dirsAndFiles(dir) }
            stack = path
            dirs = d
            files = f
            loadThumbs(f)
        }
    }

    fun scan(roots: List<File>, label: String) {
        scope.launch {
            scanning = true
            error = null
            entries = emptyList()
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    PsdFiles.scan(roots, query, onProgress = { msg -> scanText = msg })
                }.getOrNull() ?: emptyList()
            }
            entries = found
            val stopped = PsdFiles.cancelledForUi()
            scanning = false
            if (stopped) error = "扫描已取消，先看看扫到的这些"
            scanText = ""
            if (found.isEmpty() && !stopped) error = label + "里没找到 PSD"
            loadThumbs(found)
        }
    }

    fun doBackup(node: PsdFiles.Node) {
        scope.launch {
            busyText = "正在备份到软件里，原文件不会被改动"
            val names = withContext(Dispatchers.IO) {
                runCatching { ProjectStore.list(context).map { it.name }.toSet() }.getOrDefault(emptySet())
            }
            val res = withContext(Dispatchers.IO) {
                runCatching { PsdFiles.backup(context, node.file, names) }.getOrNull()
            }
            busyText = null
            if (res == null) {
                error = "这个 PSD 打不开：可能文件损坏，或者它加了密码/图层被合成了特殊格式"
            } else {
                onOpenProject(res.projectId)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------- 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BarHeader)
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tab == "browse" && stack.isNotEmpty()) {
                        IconButton(onClick = { openDir(stack.dropLast(1)) }) {
                            Icon(Icons.Filled.ArrowBack, "上一级", tint = Color(0xFF22262B))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (tab == "browse" && stack.isNotEmpty()) {
                            stack.joinToString(" / ") { it.name.ifEmpty { "手机存储" } }
                        } else {
                            "手机里的 PSD"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF22262B),
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", tint = Color(0xFF22262B))
                    }
                }

                // ---------- 没权限：先要权限，说清为什么
                if (!granted) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(40.dp))
                        Text("要先借一下你的文件夹钥匙 🔑", fontSize = 16.sp, color = Color(0xFF22262B))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "目的只有一个：翻出手机里的 PSD 给你看。\n" +
                                "只读不删、只看不搬，你不点「备份」，我一个文件都不复制。\n\n" +
                                "点了「去打开开关」以后，在系统页面里把「允许管理所有文件」打开，再回来就行。",
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = Color(0xFF8A9099),
                        )
                        Spacer(Modifier.height(18.dp))
                        TextButton(onClick = {
                            runCatching { permLauncher.launch(PsdFiles.requestIntent(context)) }
                        }) {
                            Text("去打开开关", fontSize = 14.sp, color = Accent)
                        }
                        if (error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(error ?: "", fontSize = 12.sp, color = Color(0xFFFF8A80))
                        }
                    }
                } else {
                    // ---------- 说明（固定位置，一直看得见）
                    Text(
                        text = "软件里放的是复制品，删了不影响手机里的原 PSD；要发改过的图，在软件里点「分享 PSD」就行。",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )

                    // ---------- 两个页签
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Toggle("自动扫描", tab == "scan", Accent) {
                            tab = "scan"
                            query = ""
                        }
                        Toggle("手动查找", tab == "browse", Accent) {
                            tab = "browse"
                            query = ""
                            openDir(emptyList())
                        }
                    }

                    if (tab == "scan") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = {
                                scan(listOf(PsdFiles.storageRoot()), "手机存储")
                            }) { Text("全盘扫", fontSize = 12.sp, color = Accent) }
                            TextButton(onClick = {
                                val qq = PsdFiles.chats().map { it.second }
                                if (qq.isEmpty()) error = "没找到 QQ / 微信的文件夹，可能它们把文件存到别处了" else scan(qq, "QQ 微信")
                            }) { Text("扫 QQ 微信", fontSize = 12.sp, color = Accent) }
                            TextButton(onClick = {
                                val home = PsdFiles.homeDirs().map { it.second }
                                scan(home, "常用文件夹")
                            }) { Text("扫常用", fontSize = 12.sp, color = Accent) }
                        }
                    }

                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("搜文件名", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, null, tint = Color(0xFF6B7178), modifier = Modifier.size(18.dp))
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BarColor,
                            unfocusedContainerColor = BarColor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color(0xFF22262B),
                            unfocusedTextColor = Color(0xFF22262B),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .height(46.dp),
                    )

                    if (error != null) {
                        Text(
                            text = error ?: "",
                            fontSize = 11.sp,
                            color = Color(0xFFFF8A80),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                    }

                    if (scanning) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Accent,
                            trackColor = Color(0xFFFFFFFF),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (scanText.isEmpty()) "正在找 PSD，只看不动原文件" else scanText,
                                fontSize = 10.sp,
                                color = Color(0xFF6B7178),
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { PsdFiles.cancelScan() }) {
                                Text("取消扫描", fontSize = 12.sp, color = Color(0xFFFF8A80))
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFFFFFFF))

                    val q = query.trim().lowercase()
                    val shownDirs = if (q.isEmpty()) dirs else emptyList()
                    val shownFiles = if (q.isEmpty()) {
                        if (tab == "browse") files else entries
                    } else {
                        (entries + files).filter { it.name.lowercase().contains(q) }
                            .distinctBy { it.file.absolutePath }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(shownDirs, key = { "d:" + it.file.absolutePath }) { d ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openDir(stack + d.file) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = Color(0xFFC8A24A), modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(d.name, fontSize = 13.sp, color = Color(0xFF22262B), maxLines = 1)
                            }
                        }
                        items(shownFiles, key = { "f:" + it.file.absolutePath }) { e ->
                            val key = e.file.absolutePath
                            val bmp = thumbs[key]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { doBackup(e) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp, 54.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFFFFFFFF)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Text("PSD", fontSize = 10.sp, color = Accent)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(e.name, fontSize = 13.sp, color = Color(0xFF22262B), maxLines = 1)
                                    Text(
                                        text = buildString {
                                            append(sizeText(e.size))
                                            val where = PsdFiles.shortPath(e.file).substringBeforeLast('/')
                                            if (where.isNotEmpty()) append("　").append(where)
                                        },
                                        fontSize = 10.sp,
                                        color = Color(0xFF6B7178),
                                        maxLines = 1,
                                    )
                                }
                                Text("点击备份", fontSize = 11.sp, color = Accent)
                            }
                        }
                    }
                }
            }

            // ---------- 备份中的遮罩
            val busy = busyText
            if (busy != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xB3000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            modifier = Modifier.width(160.dp),
                            color = Accent,
                            trackColor = Color(0xFFFFFFFF),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(busy, fontSize = 12.sp, color = Color(0xFF22262B))
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        color = if (on) accent else Color(0xFF6B7178),
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) Color(0xFFFFFFFF) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** 列目录：文件夹一堆、PSD 一堆，其余文件不显示（免得刷屏） */
private fun dirsAndFiles(dir: File): Pair<List<PsdFiles.Node>, List<PsdFiles.Node>> {
    val all = PsdFiles.list(dir)
    val folders = all.filter { it.isDir }
    val psds = all.filter { !it.isDir && it.psd }
    return folders to psds
}

private fun sizeText(size: Long): String = when {
    size <= 0L -> "大小未知"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> String.format("%.1f MB", size / 1024.0 / 1024.0)
}