package com.reveal.editor.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import com.reveal.editor.data.Project
import com.reveal.editor.data.Incoming
import com.reveal.editor.data.Diag
import com.reveal.editor.data.PsdShare
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.data.PsdFiles
import com.reveal.editor.data.SaveLayer
import com.reveal.editor.image.BitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：像画世界那样一屏看到以前画的工程，点一下就接着画。
 */
@Composable
fun HomeScreen(
    onEdit: (String) -> Unit,
    desktopMode: Boolean = false,
    onDesktopToggle: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var showNew by remember { mutableStateOf(false) }
    var showFindPsd by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    // 长按工程行弹出的小菜单：复制一份 / 进多选
    var copyTarget by remember { mutableStateOf<Project?>(null) }
    // 多选：长按进入，再点是勾选；顶栏变成「已选 N / 全选 / 删除 / 取消」
    var selMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var psdIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var incoming by remember { mutableStateOf<List<Incoming.Item>>(emptyList()) }
    var incomingThumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    // 只是登记了位置、还没复制进来的（点一下才复制）
    var pendings by remember { mutableStateOf<List<Incoming.Pending>>(emptyList()) }
    // 导入/新建/删除这类要等磁盘的事，给一句人话，别让用户对着空屏猜
    var busyText by remember { mutableStateOf<String?>(null) }
    // 打开失败时弹出来给用户复制的那段诊断信息（null = 不弹）
    var diagText by remember { mutableStateOf<String?>(null) }
    // 日志弹窗里「诊断」和「崩溃」分成两页，不混在一段文字里
    var diagCrash by remember { mutableStateOf(false) }


    fun reload() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { ProjectStore.list(context) }
            val map = withContext(Dispatchers.IO) {
                list.mapNotNull { p ->
                    ProjectStore.thumb(context, p.id)?.let { p.id to it }
                }.toMap()
            }
            val psdSet = withContext(Dispatchers.IO) {
                list.filter { p ->
                    File(File(File(context.filesDir, "projects"), p.id), "psd_meta.json").exists()
                }.map { it.id }.toSet()
            }
            projects = list
            thumbs = map
            psdIds = psdSet
            loading = false
        }
    }

    fun reloadIncoming() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { Incoming.list(context) }
            pendings = withContext(Dispatchers.IO) { Incoming.pendings(context) }
            // 先把卡片摆出来（名字 + 灰底），缩略图一个个解码完再填——
            // 以前是等全部解码完才赋值，收到几个大 PSD 首页要空等十几秒
            incoming = list
            val keep = list.map { it.file.absolutePath }.toSet()
            incomingThumbs = incomingThumbs.filterKeys { keep.contains(it) }
            for (item in list) {
                val path = item.file.absolutePath
                if (incomingThumbs.containsKey(path)) continue
                val thumb = withContext(Dispatchers.IO) {
                    runCatching { PsdFiles.thumbnail(item.file, 260) }.getOrNull()
                } ?: continue
                incomingThumbs = incomingThumbs + (path to thumb)
            }
        }
    }

    LaunchedEffect(Unit) { reload(); reloadIncoming() }
    LaunchedEffect(Incoming.revision.intValue) { reloadIncoming() }
    // 外部打开（QQ 里点「用其他应用打开」）时给一句「正在接收文件…」：
    // 大文件复制要好几秒，不然用户不知道是在加载、卡住还是失败了
    LaunchedEffect(Incoming.busy.intValue) {
        if (Incoming.busy.intValue > 0) {
            busyText = "正在接收文件…"
        } else {
            if (busyText == "正在接收文件…") busyText = null
            Incoming.lastError?.let { msg ->
                diagText = msg
                Diag.append(context, "提示框", msg)
                Incoming.lastError = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busyText = "正在载入图片…"
                val bmp = withContext(Dispatchers.IO) { BitmapLoader.load(context, uri) }
                if (bmp == null) {
                    busyText = null
                    return@launch
                }
                val id = withContext(Dispatchers.IO) {
                    val pid = ProjectStore.create(
                        context,
                        "未命名画布",
                        bmp.width,
                        bmp.height,
                    )
                    ProjectStore.save(
                        context,
                        pid,
                        "未命名画布",
                        listOf(SaveLayer(bmp, "图层 1", true, 255)),
                    )
                    pid
                }
                busyText = null
                onEdit(id)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "创作",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Rv.text,
                    )
                    Text(
                        text = "REVEAL · 手机修图",
                        fontSize = 10.sp,
                        color = Rv.textFaint,
                        letterSpacing = 0.6.sp,
                    )
                }
                if (selMode) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Rv.accentSoft)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "已选 " + selected.size,
                            fontSize = 11.sp,
                            color = Rv.accent,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TabChip("全选", false) { selected = projects.map { it.id }.toSet() }
                    Spacer(Modifier.width(6.dp))
                    TabChip("删除", false) {
                        if (selected.isNotEmpty()) {
                            val ids = selected.toList()
                            scope.launch {
                                busyText = "正在删除 " + ids.size + " 个…"
                                withContext(Dispatchers.IO) {
                                    ids.forEach { runCatching { ProjectStore.delete(context, it) } }
                                }
                                busyText = null
                                selected = emptySet()
                                selMode = false
                                reload()
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    TabChip("取消", false) { selMode = false; selected = emptySet() }
                } else {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { diagText = Diag.read(context) }) {
                    Text(
                        text = "日志",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Filled.Add,
                            "打开文件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        DropdownMenuItem(
                            text = { Text("新建画布", fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                showNew = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("打开 PSD（手机里的文件）", fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                showFindPsd = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入图片", fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                importLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("诊断日志（复制发我）", fontSize = 13.sp) },
                            onClick = {
                                showMenu = false
                                diagText = Diag.read(context)
                            },
                        )
                    }
                }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            if (incoming.isNotEmpty() || pendings.isNotEmpty()) {
                Text(
                    text = "QQ / 微信发来的（点一下才复制）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (Incoming.busy.intValue > 0) {
                        items(count = 1) {
                            IncomingCard(
                                name = "正在接收…",
                                thumb = null,
                                onClick = {},
                                onLongClick = {},
                            )
                        }
                    }
                    items(count = pendings.size) { index ->
                        val p = pendings[index]
                        IncomingCard(
                            name = p.name,
                            thumb = null,
                            onClick = {
                                scope.launch {
                                    busyText = "正在复制文件…"
                                    val item = withContext(Dispatchers.IO) { Incoming.add(context, p.uri) }
                                    busyText = null
                                    if (item == null) {
                                        val msg = Incoming.lastError ?: "复制失败"
                                        Incoming.lastError = null
                                        Diag.append(context, "提示框", msg)
                                        diagText = msg
                                        return@launch
                                    }
                                    Incoming.removePending(context, p)
                                    busyText = "正在打开 PSD…"
                                    val names = withContext(Dispatchers.IO) {
                                        runCatching { ProjectStore.list(context).map { x -> x.name }.toSet() }
                                            .getOrDefault(emptySet())
                                    }
                                    val res = withContext(Dispatchers.IO) {
                                        runCatching { PsdFiles.backupFile(context, item.file, names) }.getOrNull()
                                    }
                                    busyText = null
                                    if (res == null) {
                                        val why = com.reveal.editor.image.Psd.lastError ?: "文件可能坏了"
                                        val rep = Diag.report(context, item.file, why)
                                        Diag.append(context, "导入失败 " + item.name, rep)
                                        diagText = rep
                                    } else {
                                        Incoming.remove(context, item)
                                        onEdit(res.projectId)
                                    }
                                }
                            },
                            onLongClick = { Incoming.removePending(context, p) },
                        )
                    }
                    items(count = incoming.size) { index ->
                        val item = incoming[index]
                        IncomingCard(
                            name = displayPsdName(item.name),
                            thumb = incomingThumbs[item.file.absolutePath],
                            onClick = {
                                scope.launch {
                                    if (!item.file.exists()) {
                                        // 卡片还在、文件没了：多半是又收到一次同名文件把旧的换掉了
                                        Incoming.remove(context, item)
                                        diagText = "接到的文件已经不在了：\n" + item.name +
                                            "\n（同名文件又被接收了一次，旧的被换掉）\n请重新从 QQ 发一次。"
                                                                                Diag.append(context, "文件不在", "接到的文件已经不在了：" + item.name)
return@launch
                                    }
                                    busyText = "正在打开 PSD…"
                                    val names = withContext(Dispatchers.IO) {
                                        runCatching { ProjectStore.list(context).map { p -> p.name }.toSet() }
                                            .getOrDefault(emptySet())
                                    }
                                    val res = withContext(Dispatchers.IO) {
                                        // 直接给 File：大文件走内存映射，堆里不再同时躺两份整份文件
                                        runCatching { PsdFiles.backupFile(context, item.file, names) }.getOrNull()
                                    }
                                    busyText = null
                                    if (res == null) {
                                        // 失败要能复制出来发给我：只弹一句「打不开」什么也查不了
                                        val why = com.reveal.editor.image.Psd.lastError ?: "文件可能坏了"
                                        val rep = Diag.report(context, item.file, why)
                                        Diag.append(context, "导入失败 " + item.name, rep)
                                        diagText = rep
                                    } else {
                                        Incoming.remove(context, item)
                                        val st = PsdFiles.lastStats
                                        if (st.contains("慢在")) {
                                            // 慢解析：直接把可复制的诊断丢出来，Toast 显示不全
                                            Diag.append(context, "导入耗时", st)
                                            diagText = st
                                        } else if (st.isNotEmpty()) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "打开完成：" + st,
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        onEdit(res.projectId)
                                    }
                                }
                            },
                            onLongClick = { Incoming.remove(context, item) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }

            if (projects.isEmpty() && !loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "还没有作品，点右下角 + 开始",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            thumb = thumbs[project.id],
                            psd = psdIds.contains(project.id),
                            selected = selMode && selected.contains(project.id),
                            onClick = {
                                if (selMode) {
                                    selected = if (selected.contains(project.id)) selected - project.id else selected + project.id
                                    return@ProjectCard
                                }
                                // 打开大工程要 4~6 秒，先给一句反馈，别让人以为点空了
                                android.widget.Toast.makeText(
                                    context, "正在打开工程…", android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onEdit(project.id)
                            },
                            onLongClick = { copyTarget = project },
                            onShare = {
                                scope.launch {
                                    val pair = withContext(Dispatchers.IO) {
                                        runCatching { PsdShare.bytesFromProject(context, project.id) }.getOrNull()
                                    }
                                    if (pair == null) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "这份工程还没存好，先打开一下再分享",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        runCatching { PsdShare.share(context, pair.first, pair.second) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNew = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(Icons.Filled.Add, "新建", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    if (showNew) {
        val docPref = remember {
            context.getSharedPreferences("reveal_settings", android.content.Context.MODE_PRIVATE)
        }
        NewDocumentDialog(
            initialW = docPref.getInt("new_doc_w", 1080),
            initialH = docPref.getInt("new_doc_h", 1620),
            onDismiss = { showNew = false },
            onCreate = { width, height, transparent ->
                showNew = false
                // 记住上次填的尺寸，下次新建直接就是它（用户每次都要重填一遍很烦）
                docPref.edit().putInt("new_doc_w", width).putInt("new_doc_h", height).apply()
                scope.launch {
                    busyText = "正在建画布…"
                    val id = withContext(Dispatchers.IO) {
                        val pid = ProjectStore.create(context, "未命名画布", width, height)
                        if (!transparent) {
                            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            ProjectStore.save(
                                context,
                                pid,
                                "未命名画布",
                                listOf(SaveLayer(bmp, "图层 1", true, 255)),
                            )
                        }
                        pid
                    }
                    busyText = null
                    onEdit(id)
                }
            },
        )
    }

    if (showFindPsd) {
        PsdBrowserDialog(
            onDismiss = { showFindPsd = false },
            onOpenProject = { pid ->
                showFindPsd = false
                onEdit(pid)
            },
        )
    }

    diagText?.let { text ->
        val crashBody = remember(diagCrash) {
            if (diagCrash) com.reveal.editor.CrashLog.read(context).ifBlank { "（还没有闪退记录）" } else ""
        }
        val shown = if (diagCrash) crashBody else text
        AlertDialog(
            onDismissRequest = { diagText = null; diagCrash = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (diagCrash) "崩溃记录" else "诊断信息", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "诊断",
                        fontSize = 12.sp,
                        color = if (diagCrash) Color(0xFF6B7178) else Color(0xFF7AB8FF),
                        modifier = Modifier.clickable { diagCrash = false },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "崩溃",
                        fontSize = 12.sp,
                        color = if (diagCrash) Color(0xFF7AB8FF) else Color(0xFF6B7178),
                        modifier = Modifier.clickable { diagCrash = true },
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(
                            text = shown,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Reveal", shown))
                    }
                    android.widget.Toast.makeText(context, "已复制，发给开发者就能定位", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
                TextButton(onClick = {
                    val f = Diag.exportText(context)
                    if (f == null) {
                        android.widget.Toast.makeText(context, "导出日志失败", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", f,
                            )
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Reveal 日志")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "发送日志"))
                        }.onFailure {
                            android.widget.Toast.makeText(context, "没有可用的发送应用", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = {
                    diagText = null
                    showFindPsd = true
                }) { Text("从文件管理器选") }
                TextButton(onClick = { diagText = null }) { Text("关闭") }
            },
        )
    }

    busyText?.let { msg ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xCC14161A), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF6EA8FF),
                )
                Spacer(Modifier.width(10.dp))
                Text(msg, fontSize = 12.sp, color = Color(0xFF22262B))
            }
        }
    }

    copyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { copyTarget = null },
            title = { Text(target.name, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "复制一份",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                copyTarget = null
                                scope.launch {
                                    busyText = "正在复制…"
                                    val newId = withContext(Dispatchers.IO) {
                                        runCatching { ProjectStore.copy(context, target.id) }.getOrNull()
                                    }
                                    busyText = null
                                    android.widget.Toast.makeText(
                                        context,
                                        if (newId == null) "复制失败：工程文件读不到" else "已复制出一份一模一样的工程",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                    reload()
                                }
                            }
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        "多选（批量删除）",
                        fontSize = 14.sp,
                        color = Color(0xFF22262B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                copyTarget = null
                                selMode = true
                                selected = selected + target.id
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { copyTarget = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个工程？", fontSize = 16.sp) },
            text = { Text("${target.name}　${formatDate(target.updatedAt)}", fontSize = 12.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        pendingDelete = null
                        scope.launch {
                            busyText = "正在删除…"
                            runCatching {
                                withContext(Dispatchers.IO) { ProjectStore.delete(context, id) }
                            }
                            busyText = null
                            reload()
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IncomingCard(
    name: String,
    thumb: Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(108.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Rv.surfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text("PSD", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            fontSize = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "点一下备份",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    thumb: Bitmap?,
    psd: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .then(
                    if (selected) Modifier.border(
                        2.dp,
                        Color(0xFF4C8DFF),
                        RoundedCornerShape(8.dp),
                    ) else Modifier
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = if (project.width > 0) "${project.width}×${project.height}" else "空工程",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(Color(0xFF4C8DFF), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            if (!selected) IconButton(
                onClick = onShare,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(30.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(15.dp)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "分享这张 PSD",
                    tint = Color(0xFF22262B),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (psd) displayPsdName(project.name) else project.name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = formatDate(project.updatedAt),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
    }
}

private fun displayPsdName(name: String): String =
    if (name.lowercase().endsWith(".psd") || name.lowercase().endsWith(".psb")) name else "$name.psd"

private fun formatDate(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
