package com.reveal.editor.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * QQ / 微信「用其他应用打开」发过来的 PSD：
 * 收到就立刻复制到软件自己的目录并出缩略图，关掉软件下次还在，点一下才正式备份成工程。
 */
object Incoming {

    /** 用这个数字通知首页刷新（收到新文件 / 删掉一条） */
    val revision = mutableIntStateOf(0)

    /** 正在复制进来的文件数：>0 时首页要显示「正在接收文件…」，否则用户对着空屏猜 */
    val busy = mutableIntStateOf(0)

    /** 接收进度：-1 = 没在接收，否则 0..100 */
    val recvPct = mutableIntStateOf(-1)

    /** 上一次接收失败的原因（首页弹一句人话） */
    @Volatile var lastError: String? = null

    class Item(val file: File, val name: String, val size: Long, val time: Long)

    fun dir(context: Context): File = File(context.filesDir, "incoming").apply { if (!exists()) mkdirs() }

    /** 失败时统一：写进诊断日志 + 交给首页弹「可复制」的框（以前只给 Toast，抄不下来） */
    private fun fail(
        context: Context,
        uri: Uri,
        name: String,
        why: String,
        t: Throwable? = null,
        extra: String? = null,
    ): Item? {
        val rt = Runtime.getRuntime()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val txt = buildString {
            append("接收失败：").append(why).append('\n')
            append("文件名：").append(name).append('\n')
            append("URI：").append(uri).append('\n')
            append("mime：").append(mime ?: "（对方没给）").append('\n')
            append("堆上限：").append(rt.maxMemory() / 1024 / 1024).append("MB，已用 ")
            append((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024).append("MB\n")
            if (t != null) append("异常：").append(t.javaClass.name).append(": ").append(t.message ?: "")
            if (extra != null) append('\n').append(extra)
        }
        runCatching { Diag.append(context, "RECV", txt) }
        lastError = txt
        return null
    }

    /**
     * QQ 分身（双开）里的文件：URI 路径带 /emulated/<userId>/，那个用户空间里的 provider
     * 在本进程根本不存在 —— 报错就是 "No content provider"。这不是时机问题，重试无效。
     */
    private fun crossUserHint(uri: Uri, t: Throwable?): String? {
        val path = uri.path ?: ""
        val other = Regex("/emulated/(\\d+)/").find(path)?.groupValues?.get(1)?.toIntOrNull()
        val myUser = android.os.Process.myUid() / 100000
        val msg = t?.message ?: ""
        val noProvider = msg.contains("No content provider", ignoreCase = true) ||
            msg.contains("EACCES", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true)
        if (!noProvider && (other == null || other == myUser)) return null
        val who = if (other != null && other != myUser) "QQ 分身（用户空间 $other）" else "另一个账号空间"
        return "这个文件在$who 的私有目录里。\n" +
            "刚才 QQ 自己（同一个 provider）去打开它也被系统拒绝了（EACCES），" +
            "所以不是本软件权限的问题，是文件根本不在这个空间里。\n\n" +
            "能用的三条路，任选一条：\n" +
            "1) 分身里把这个文件「发给我的电脑 / 文件传输助手」，再从主空间 QQ 里打开它 —— 那条路文件在 emulated/0，能直接读；\n" +
            "2) 分身里长按文件 →「保存到手机」→ 看它落在 Download 就用首页「＋」导入；\n" +
            "3) 发到电脑，用数据线/U盘拷到手机的 Download，再「＋」导入。"
    }

    /**
     * 「只是确定了文件在哪」的一条记录：别的应用（QQ/微信/文件管理器）递来文件时，
     * 我们只记下它的位置和名字，不复制、不读取。首页先出卡片，用户点一下才真正复制。
     * 这也是用户要求的做法：从 QQ 打开 = 只是知道是哪个文件，软件里点一下才拷进来。
     */
    class Pending(val id: String, val uri: Uri, val name: String, val time: Long)

    private fun pendingFile(context: Context): File = File(context.filesDir, "incoming_pending.txt")

    fun pendings(context: Context): List<Pending> {
        // 0.67.66 起不再用「只登记位置」这条路：系统授权只在收到那一刻有效，
        // 所以收到就当场复制，卡片上的「点一下」= 备份成工程。旧版留下的登记一律忽略。
        if (true) return emptyList()

        val f = pendingFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val t = parts[0].toLongOrNull() ?: 0L
                val nm = parts[1]
                val uri = Uri.parse(parts[2])
                if (nm.isBlank()) null else Pending(parts[2] + "#" + t, uri, nm, t)
            }
        }.getOrDefault(emptyList())
    }

    private fun savePendings(context: Context, list: List<Pending>) {
        runCatching {
            pendingFile(context).writeText(
                list.take(20).joinToString("\n") { p -> p.time.toString() + "\t" + p.name + "\t" + p.uri.toString() }
            )
        }
        revision.intValue++
    }

    /** 对方递来文件时调用：只登记位置，不读文件（读不到也不影响出卡片） */
    fun register(context: Context, uri: Uri): Pending? {
        val rawName = runCatching {
            var n = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            if (n == null) {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) n = c.getString(i)
                }
            }
            n
        }.getOrNull() ?: "收到的文件"
        val name = if (rawName.endsWith(".psd", true) || rawName.endsWith(".psb", true)) rawName else rawName
        val p = Pending(uri.toString() + "#" + System.currentTimeMillis(), uri, name, System.currentTimeMillis())
        val others = pendings(context).filter { it.uri.toString() != uri.toString() }
        savePendings(context, listOf(p) + others)
        runCatching {
            Diag.append(context, "登记待复制 " + name, "（点卡片时才复制）\n" + probeUri(context, uri))
        }
        return p
    }

    fun clearPendings(context: Context) {
        runCatching { pendingFile(context).delete() }
        revision.intValue++
    }

    fun removePending(context: Context, p: Pending) {
        savePendings(context, pendings(context).filter { it.id != p.id })
    }

    /**
     * provider 解析不到时（分身 / 双开最常见）还有一招：把 content URI 的路径还原成本机文件直接读。
     * QQ 的 fileprovider 会把 external_files 映射到 /storage/emulated/<用户>/...，
     * 所以去掉 /external_files 前缀就是真实路径；能不能读取决于系统，但试一下成本很低。
     */
    private fun directFileOf(uri: Uri): File? {
        val p = uri.path ?: return null
        val raw = when {
            p.startsWith("/external_files/") -> p.removePrefix("/external_files")
            p.startsWith("/external/") -> p.removePrefix("/external")
            else -> p
        }
        if (!raw.startsWith("/storage/") && !raw.startsWith("/sdcard/")) return null
        return runCatching { File(raw) }.getOrNull()
    }

    /**
     * 分身（应用双开）里 QQ 的 provider 有「跨用户」形态：content://999@com.tencent.mobileqq.fileprovider/...
     * 0.67.39 及之前 QQ 递过来的就是这个形态，能正常读；后来递的是不带 999@ 的普通 URI，
     * 主空间要么解析不到分身里的 provider，要么解析到主空间的 provider 却读不到分身私有目录 → EACCES。
     * 所以从路径 /storage/emulated/<N>/ 反推用户号，把 authority 补成 <N>@<authority> 再试一次。
     */
    private fun crossUserUri(uri: Uri): Uri? {
        val auth = uri.authority ?: return null
        val at = auth.indexOf('@')
        if (at > 0 && auth.take(at).all { it.isDigit() }) return null // 已经是跨用户形态
        val p = uri.path ?: return null
        val n = Regex("/(?:storage/)?emulated/(\\d+)/").find(p)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (n == 0 || n == android.os.Process.myUid() / 100000) return null
        return runCatching {
            Uri.Builder().scheme(uri.scheme).authority(n.toString() + "@" + auth).path(p).build()
        }.getOrNull()
    }

    /** 一次性把这个 URI 的所有相关事实写成多行文本，登记时写一遍、失败时再写一遍 */
    fun probeUri(context: Context, uri: Uri): String {
        val pm = context.packageManager
        val auth = uri.authority ?: "（没有 authority）"
        val resolver = runCatching { pm.resolveContentProvider(auth, 0) }.getOrNull()
        val qqVer = runCatching { pm.getPackageInfo("com.tencent.mobileqq", 0).versionName }.getOrNull()
        val direct = directFileOf(uri)
        val emu999 = File("/storage/emulated/999")
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val queryRes = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c -> "游标可用，行数=" + c.count }
        }
        val manager = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        return buildString {
            append("URI：").append(uri).append('\n')
            append("authority：").append(auth).append('\n')
            append("path：").append(uri.path ?: "（无）").append('\n')
            append("mime：").append(mime ?: "（对方没给）").append('\n')
            append("provider 可解析：").append(if (resolver != null) "是（" + resolver.packageName + "）" else "否").append('\n')
            append("QQ 主空间版本：").append(qqVer ?: "查不到").append('\n')
            append("我的 uid=").append(android.os.Process.myUid())
            append("（user=").append(android.os.Process.myUid() / 100000).append("）\n")
            append("所有文件访问权限：").append(if (manager) "已给" else "没给").append('\n')
            append("直读路径：").append(direct?.absolutePath ?: "（不是文件路径）")
            if (direct != null) {
                append("\n  存在=").append(direct.exists())
                append(" 是文件=").append(direct.isFile)
                append(" 可读=").append(direct.canRead())
                append(" 大小=").append(if (direct.exists()) direct.length().toString() else "-")
            }
            append("\n/storage/emulated/999：")
            append(if (!emu999.exists()) "不存在" else "存在，可读=" + emu999.canRead() + "，可列出=" + (emu999.list()?.size ?: -1) + " 项")
            append("\n/storage/emulated/0：").append(if (File("/storage/emulated/0").exists()) "存在" else "不存在")
            append("\nquery 试读：").append(
                queryRes.fold({ it + "" }, { t -> t.javaClass.simpleName + ": " + (t.message ?: "") })
            )
        }
    }

    fun add(context: Context, uri: Uri): Item? {
        busy.intValue++
        recvPct.intValue = 0
        try {
            var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "收到.psd"
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
            }
            var total = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) total = c.getLong(0)
                }
            }
            if (!name.endsWith(".psd", true) && !name.endsWith(".psb", true)) name += ".psd"
            val safe = name.replace(Regex("[\\/:*?\"<>|]"), "_")
            // 实测：第一次打开基本失败、多试几次就成功 —— QQ 这时候还在把文件从聊天缓存里取出来，
            // 或者授权晚到。所以这里退避重试，而不是一次判死；同时把真实异常留下来。
            var openErr: Throwable? = null
            var directUsed: File? = null
            val attempts = StringBuilder()
            fun note(step: String, i: Int, t: Throwable?) {
                if (t == null) return
                if (attempts.isNotEmpty()) attempts.append("\n")
                attempts.append(step).append(" #").append(i + 1).append("：")
                    .append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
            }
            // 先按老版本的做法拿输入流（0.67.37 之前一直是这么读的），拿不到再试 FD：
            // 有些克隆/双开 provider 只认其中一条路。
            var earlyStream: java.io.InputStream? = null
            for (i in 0 until 2) {
                val r = runCatching { context.contentResolver.openInputStream(uri) }
                earlyStream = r.getOrNull()
                if (earlyStream != null) break
                openErr = r.exceptionOrNull() ?: openErr
                note("openInputStream-first", i, r.exceptionOrNull())
                runCatching { Thread.sleep(if (i == 0) 150L else 300L) }
            }
            if (earlyStream != null) {
                runCatching { Diag.append(context, "RECV", "openInputStream 直接拿到流（未用 FD）") }
            }
            var srcStream: java.io.InputStream? = earlyStream
            var pfd: android.os.ParcelFileDescriptor? = null
            for (i in 0 until 4) {
                if (srcStream != null) break
                val r = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
                pfd = r.getOrNull()
                if (pfd != null) break
                openErr = r.exceptionOrNull() ?: openErr
                note("openFileDescriptor", i, r.exceptionOrNull())
                runCatching { Thread.sleep(if (i == 0) 200L else 350L * (i + 1)) }
            }
            // 有些机型 PFD 拿不到但输入流能给
            if (pfd == null && srcStream == null) {
                for (i in 0 until 3) {
                    val r = runCatching { context.contentResolver.openInputStream(uri) }
                    srcStream = r.getOrNull()
                    if (srcStream != null) break
                    openErr = r.exceptionOrNull() ?: openErr
                    note("openInputStream", i, r.exceptionOrNull())
                    runCatching { Thread.sleep(300L) }
                }
            }
            // 分身里的 provider：把 authority 补成 999@… 再试（0.67.39 之前 QQ 给的就是这种 URI，那时是能读的）
            if (pfd == null && srcStream == null) {
                val cu = crossUserUri(uri)
                if (cu != null) {
                    runCatching { Diag.append(context, "RECV", "改用跨用户 URI：" + cu) }
                    srcStream = runCatching { context.contentResolver.openInputStream(cu) }.getOrNull()
                    if (srcStream == null) {
                        pfd = runCatching { context.contentResolver.openFileDescriptor(cu, "r") }.getOrNull()
                    }
                    if (srcStream == null && pfd == null) note("cross-user", 0, openErr)
                }
            }
            // provider 解析不到 → 试试把路径当普通文件直接读（分身里的文件有时这条能通）
            if (pfd == null && srcStream == null) {
                val direct = directFileOf(uri)
                if (direct != null && direct.isFile && direct.length() > 0 && direct.canRead()) {
                    runCatching { direct.inputStream() }.getOrNull()?.let { st ->
                        directUsed = direct
                        srcStream = st
                    }
                }
                if (srcStream != null) {
                    runCatching { Diag.append(context, "RECV", "provider 不可用，改直读文件成功：" + direct?.absolutePath) }
                }
            }
            if (pfd == null && srcStream == null) {
                // 现场信息：这个 authority 在「我们所在的用户空间」里到底能不能解析到。
                // 以前看到 /emulated/999/ 就一口断定是「QQ 分身跨空间」，那是猜的 —— 现在只把事实写出来。
                val auth = uri.authority ?: "（没有 authority）"
                val resolver = runCatching { context.packageManager.resolveContentProvider(auth, 0) }.getOrNull()
                val qqVer = runCatching {
                    context.packageManager.getPackageInfo("com.tencent.mobileqq", 0).versionName
                }.getOrNull()
                // 分身/双开到底是不是「另一个用户空间」，看 /storage/emulated/999 能不能看见就知道了
                val emu999 = runCatching { File("/storage/emulated/999") }.getOrNull()
                val emu999Info = if (emu999 == null) "查不到" else
                    (if (!emu999.exists()) "不存在"
                    else "存在，可列出=" + (emu999.list()?.size ?: -1) + " 项，可读=" + emu999.canRead())
                val probe = buildString {
                    append("provider「").append(auth).append("」在本空间：")
                    append(if (resolver != null) "能解析（" + resolver.packageName + "）" else "解析不到")
                    append("\nQQ 主空间版本：").append(qqVer ?: "查不到（可能只是被系统藏了）")
                    append("\n/storage/emulated/999：").append(emu999Info)
                    val d = directFileOf(uri)
                    append("\n直读路径：").append(d?.absolutePath ?: "（这个地址不是文件路径）")
                    if (d != null) append(" 存在=").append(d.exists()).append(" 可读=").append(d.canRead())
                    append("\n我的 uid=").append(android.os.Process.myUid())
                    append("（user=").append(android.os.Process.myUid() / 100000).append("）")
                    if (attempts.isNotEmpty()) append("\n每次尝试：\n").append(attempts)
                    val cross = crossUserHint(uri, openErr)
                    if (cross != null) append("\n\n可能原因：\n").append(cross)
                }
                val msg0 = openErr?.message ?: ""
                val permDenied = msg0.contains("EACCES") || msg0.contains("Permission denied", true)
                val kind = when {
                    permDenied && crossUserHint(uri, null) != null ->
                        "这个文件在 QQ 分身（另一个用户空间）的私有目录里，系统不允许本空间的应用读"
                    permDenied ->
                        "系统拒绝了读取权限（EACCES）：文件所在目录不让别的应用访问"
                    openErr is java.io.FileNotFoundException && msg0.contains("No content provider", true) ->
                        "系统说找不到能读取这个位置的组件"
                    openErr is java.io.FileNotFoundException ->
                        "文件打不开（可能还没下载完，或已被 QQ 清理）"
                    openErr is SecurityException ->
                        "对方没把读取权限给过来（系统不允许跨应用授权）"
                    else -> "打不开这个文件"
                }
                return fail(context, uri, safe, kind + "（试了 4+3 次）", openErr, probe)
            }
            if (directUsed != null && total <= 0L) total = directUsed!!.length()
            val target = File(dir(context), System.currentTimeMillis().toString() + "__" + safe)
            // 先写 .part，确认写完、不是 0 字节再改名。
            // 以前是先删同名旧文件再复制，复制一旦失败，旧的那份也没了，首页就会出现一张点不开的卡片。
            val part = File(dir(context), target.name + ".part")
            runCatching { part.delete() }
            val copied = runCatching {
                if (pfd == null) {
                    val st = srcStream!!
                    try {
                        java.io.FileOutputStream(part).use { out ->
                            val buf = ByteArray(1024 * 1024)
                            var done = 0L
                            while (true) {
                                val n = st.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) recvPct.intValue = ((done * 100) / total).toInt().coerceIn(0, 100)
                            }
                        }
                    } finally {
                        runCatching { st.close() }
                    }
                    return@runCatching
                }
                pfd.use { d ->
                    val channel = java.io.FileInputStream(d.fileDescriptor).channel
                    val out = java.io.FileOutputStream(part)
                    try {
                        val size = if (total > 0) total else d.statSize
                        if (size > 0 && size <= Int.MAX_VALUE) {
                            // 能拿到大小就走内存映射：不进堆，一次 8MB
                            var pos = 0L
                            while (pos < size) {
                                val len = minOf(8L * 1024 * 1024, size - pos)
                                val m = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, pos, len)
                                out.channel.write(m)
                                pos += len
                                recvPct.intValue = ((pos * 100) / size).toInt().coerceIn(0, 100)
                            }
                        } else {
                            // 管道 / 未知大小：1MB 缓冲流式拷贝
                            val buf = ByteArray(1024 * 1024)
                            var done = 0L
                            while (true) {
                                val n = channel.read(java.nio.ByteBuffer.wrap(buf))
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) recvPct.intValue = ((done * 100) / total).toInt().coerceIn(0, 100)
                            }
                        }
                    } finally {
                        runCatching { channel.close() }
                        runCatching { out.close() }
                    }
                }
            }
            copied.exceptionOrNull()?.let { t ->
                runCatching { part.delete() }
                return fail(context, uri, safe, "复制过程中断了", t)
            }
            if (part.length() <= 0L) {
                runCatching { part.delete() }
                return fail(context, uri, safe, "收到的文件是空的（0 字节）")
            }
            if (!part.renameTo(target)) {
                runCatching { part.delete() }
                return fail(context, uri, safe, "保存失败：写不进软件目录")
            }
            // 验一眼文件头：对方有时候会把别的文件改名成 .psd 发过来
            val head = runCatching {
                java.io.RandomAccessFile(target, "r").use { r ->
                    val b = ByteArray(4)
                    r.readFully(b)
                    String(b, Charsets.US_ASCII)
                }
            }.getOrNull()
            if (head != "8BPS") {
                runCatching { target.delete() }
                return fail(context, uri, safe, "这不是 PSD 文件（文件头是 " + (head ?: "读不到") + "）")
            }
            // 同名只留最新：等新文件确实落盘之后，才清掉上一次那份
            list(context).filter { it.name == safe && it.file.name != target.name }
                .forEach { runCatching { it.file.delete() } }
            lastError = null
            revision.intValue++
            return Item(target, safe, target.length(), target.lastModified())
        } catch (t: Throwable) {
            return fail(context, uri, "未知", "接收失败", t)
        } finally {
            recvPct.intValue = -1
            busy.intValue--
            revision.intValue++
        }
    }

    fun list(context: Context): List<Item> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
            .distinctBy { f -> f.name.substringAfter("__") }
            .map { f ->
                val raw = f.name
                val cut = raw.indexOf("__")
                val shown = if (cut >= 0) raw.substring(cut + 2) else raw
                Item(f, shown, f.length(), f.lastModified())
            }
    }

    fun remove(context: Context, item: Item) {
        runCatching { item.file.delete() }
        revision.intValue++
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        revision.intValue++
    }
}
