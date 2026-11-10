package com.reveal.editor.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.reveal.editor.image.Psd
import java.io.File

/**
 * 直接按路径浏览手机里的 PSD（要「所有文件访问权限」）。
 * 为什么不用系统的选文件夹：安卓 11 以后系统选择器不给选根目录、也不给选 QQ/微信目录，
 * 还会弹「为保护隐私请选择其他文件夹」，用户根本选不到东西。
 */
object PsdFiles {

    const val MAX_SCAN = 4000
    const val MAX_DEPTH = 6

    class Node(
        val file: File,
        val isDir: Boolean,
        val name: String,
        val size: Long = 0,
        val modified: Long = 0,
        val psd: Boolean = false,
        val where: String = "",
    )

    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun requestIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName),
            )
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    /** QQ / 微信存 PSD 的几个常见地方，有就列出来 */
    fun chats(): List<Pair<String, File>> {
        val root = storageRoot()
        val list = ArrayList<Pair<String, File>>()
        list.add("QQ 收文件" to File(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"))
        list.add("QQ 下载" to File(root, "tencent/QQfile_recv"))
        list.add("QQ 保存" to File(root, "QQfile_recv"))
        list.add("微信文件" to File(root, "Android/data/com.tencent.mm/MicroMsg/Download"))
        list.add("微信保存" to File(root, "Download/WeiXin"))
        list.add("微信 Tencent" to File(root, "tencent/MicroMsg/Download"))
        return list.filter { it.second.isDirectory && it.second.canRead() }
    }

    fun homeDirs(): List<Pair<String, File>> {
        val root = storageRoot()
        val out = ArrayList<Pair<String, File>>()
        out.add("手机存储" to root)
        for (n in listOf("Download", "Documents", "Pictures", "DCIM")) {
            val f = File(root, n)
            if (f.isDirectory) out.add(n to f)
        }
        return out
    }

    fun isPsd(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".psd") || n.endsWith(".psb")
    }

    fun list(dir: File): List<Node> {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val dirs = ArrayList<Node>()
        val psds = ArrayList<Node>()
        val others = ArrayList<Node>()
        for (f in files) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                dirs.add(Node(f, true, n))
            } else if (isPsd(n)) {
                psds.add(Node(f, false, n, f.length(), f.lastModified(), psd = true, where = shortPath(f)))
            } else {
                others.add(Node(f, false, n, f.length(), f.lastModified(), psd = false, where = shortPath(f)))
            }
        }
        dirs.sortBy { it.name.lowercase() }
        psds.sortByDescending { it.modified }
        others.sortBy { it.name.lowercase() }
        return dirs + psds + others.take(60)
    }

    fun shortPath(f: File): String {
        val root = storageRoot().absolutePath
        val p = f.absolutePath
        return if (p.startsWith(root)) p.substring(root.length).trimStart('/') else p
    }

    @Volatile private var cancelled = false

    fun cancelScan() {
        cancelled = true
    }

    fun cancelledForUi(): Boolean = cancelled

    /** 递归扫 PSD；progress 回调当前扫到哪 */
    fun scan(
        dirs: List<File>,
        query: String = "",
        onProgress: (String) -> Unit = {},
        onFound: (Node) -> Unit = {},
    ): List<Node> {
        val out = ArrayList<Node>()
        val q = query.trim().lowercase()
        cancelled = false
        fun walk(dir: File, depth: Int) {
            if (cancelled || out.size >= MAX_SCAN || depth > MAX_DEPTH) return
            onProgress(shortPath(dir))
            val files = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in files) {
                if (cancelled || out.size >= MAX_SCAN) return
                val n = f.name
                if (n.startsWith(".")) continue
                if (f.isDirectory) {
                    if (n == "Android" && depth > 0) continue
                    walk(f, depth + 1)
                } else if (isPsd(n)) {
                    if (q.isNotEmpty() && !n.lowercase().contains(q)) continue
                    val node = Node(f, false, n, f.length(), f.lastModified(), psd = true, where = shortPath(f))
                    out.add(node)
                    onFound(node)
                }
            }
        }
        for (d in dirs) walk(d, 0)
        out.sortByDescending { it.modified }
        return out
    }

    // ---------------------------------------------------------------- 缩略图 / 备份

    /**
     * 列表缩略图。两步走：
     * 1) 先读 PSD 里自带的缩略图（image resource 1036/1033）——只扫头部，几十毫秒，不用解图层；
     * 2) 取不到时才完整解析，而且只对 24MB 以内的文件做（大文件为了一个小图全解码太贵）。
     */
    fun thumbnail(file: File, maxSide: Int = 360): Bitmap? {
        runCatching { embeddedThumb(file) }.getOrNull()?.let { t ->
            val side = maxOf(t.width, t.height)
            if (side <= 0) return t
            val scale = maxSide.toFloat() / side
            if (scale >= 1f) return t
            return runCatching {
                Bitmap.createScaledBitmap(t, (t.width * scale).toInt().coerceAtLeast(1), (t.height * scale).toInt().coerceAtLeast(1), true)
            }.getOrNull() ?: t
        }
        if (file.length() > 24L * 1024 * 1024) return null
        return runCatching {
            val doc = file.inputStream().use { Psd.open(it) } ?: return null
            val src = doc.layers.firstOrNull { it.bitmap != null }?.bitmap ?: return null
            val scale = maxSide.toFloat() / maxOf(src.width, src.height)
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(Color.WHITE)
            c.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            out
        }.getOrNull()
    }

    /** PSD 头里自带的缩略图：1036 = JPEG，1033 = BGR 原始像素。只读文件头和资源段，不解图层。 */
    fun embeddedThumb(file: File): Bitmap? {
        if (file.length() < 40) return null
        java.io.RandomAccessFile(file, "r").use { raf ->
            fun be32(): Long {
                return ((raf.read().toLong() and 0xFF) shl 24) or
                    ((raf.read().toLong() and 0xFF) shl 16) or
                    ((raf.read().toLong() and 0xFF) shl 8) or
                    (raf.read().toLong() and 0xFF)
            }
            fun be16(): Int = ((raf.read() and 0xFF) shl 8) or (raf.read() and 0xFF)
            fun ascii(n: Int): String {
                val bb = ByteArray(n)
                raf.readFully(bb)
                return String(bb, Charsets.US_ASCII)
            }
            if (ascii(4) != "8BPS") return null
            raf.skipBytes(2)          // version
            raf.skipBytes(6)          // reserved
            raf.skipBytes(2)          // channels
            raf.skipBytes(4)          // height
            raf.skipBytes(4)          // width
            raf.skipBytes(2)          // depth
            raf.skipBytes(2)          // color mode
            val cmLen = be32()
            if (cmLen < 0) return null
            raf.skipBytes(cmLen.toInt())
            val resLen = be32()
            if (resLen <= 0) return null
            val resEnd = raf.filePointer + resLen
            while (raf.filePointer + 12 <= resEnd) {
                val sig = ascii(4)
                if (sig != "8BIM" && sig != "MeSa" && sig != "AgHg" && sig != "PHUT") break
                val id = be16()
                val nameLen = raf.read()
                var skip = nameLen
                if (nameLen % 2 == 0) skip += 1      // pascal 串按偶数字节补齐
                raf.skipBytes(skip)
                val size = be32()
                if (size < 0 || size > 8L * 1024 * 1024) break
                if (id == 1036 && size > 32) {
                    val data = ByteArray(size.toInt())
                    raf.readFully(data)
                    // 头 28 字节：格式(4) 宽(4) 高(4) 每行字节(4) 总长(4) 压缩后长(4) 位深(2) 通道(2)
                    val jpg = android.graphics.BitmapFactory.decodeByteArray(data, 28, data.size - 28)
                    if (jpg != null) return jpg
                    break
                }
                raf.skipBytes(size.toInt())
                if (size % 2 == 1L) raf.skipBytes(1)
            }
            return null
        }
    }

    /** 点一下才备份：把手机里的文件复制一份进软件，原文件不动 */
    /** 最近一次导入的分段耗时，首页直接弹给用户看 */
    @Volatile
    var lastStats: String = ""

    fun backup(context: Context, file: File, existingNames: Set<String>): PsdBrowser.ImportResult? =
        runCatching { backupFile(context, file, existingNames) }.getOrNull()

    /** 按文件导入：交给 Psd.openFile 决定「整份读」还是「内存映射」，大 PSD 不再整份躺进堆 */
    fun backupFile(context: Context, file: File, existingNames: Set<String>): PsdBrowser.ImportResult? {
        if (file.length() <= 0L) return null
        val t0 = System.currentTimeMillis()
        // 解析超过 12 秒就抓一次当前栈：不用再猜，直接看卡在哪一帧
        Psd.slowStack = ""
        val worker = Thread.currentThread()
        var finished = false
        val watchdog = Thread {
            try {
                Thread.sleep(12000)
                if (!finished) {
                    val frames = worker.stackTrace.take(12).joinToString(" < ") {
                        it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
                    }
                    Psd.slowStack = frames
                    android.util.Log.i("RevealPsd", "slow parse: " + frames)
                }
            } catch (t: Throwable) {
                // 被打断就什么也不做
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        val doc = try {
            Psd.openFile(file)
        } finally {
            finished = true
            watchdog.interrupt()
        } ?: return null
        val t1 = System.currentTimeMillis()
        val r = writeProject(context, file.name, doc, existingNames, null, file)
        val t2 = System.currentTimeMillis()
        // 给用户看的一行：不用去翻日志，弹一下就够我定位慢在哪一段
        val slow = Psd.slowStack
        lastStats = "解析 " + (t1 - t0) + "ms（" + Psd.lastPhase + "）· 存盘 " + (t2 - t1) + "ms" +
            if (slow.isEmpty()) "" else "\n慢在：" + slow
        android.util.Log.i(
            "RevealPerf",
            "import " + file.name + " " + (file.length() / 1024) + "KB" +
                " layers=" + doc.layers.size +
                " parse=" + (t1 - t0) + "ms save=" + (t2 - t1) + "ms"
        )
        return r
    }

    fun backupBytes(context: Context, fileName: String, raw: ByteArray, existingNames: Set<String>): PsdBrowser.ImportResult? {
        val doc = runCatching { Psd.open(raw.inputStream()) }.getOrNull() ?: return null
        return writeProject(context, fileName, doc, existingNames, raw, null)
    }

    private fun writeProject(
        context: Context,
        fileName: String,
        doc: Psd.Document,
        existingNames: Set<String>,
        raw: ByteArray?,
        src: File?,
    ): PsdBrowser.ImportResult? {
        if (doc.layers.isEmpty()) return null
        val base = fileName.substringBeforeLast('.').ifEmpty { "PSD 工程" }
        val name = PsdBrowser.uniqueName(base, existingNames)
        val id = ProjectStore.create(context, name, doc.width, doc.height)
        val layers = doc.layers.mapNotNull { l ->
            // 和 PsdBrowser.importToProject 一样：PSD 里的东西必须全搬过来，
            // 少搬一样（调整层参数 / 原始头部 / 组 / 16 位原始通道），工程里就永久少一样。
            val fx = l.effects
            SaveLayer(
                bitmap = l.bitmap ?: return@mapNotNull null,
                name = l.name.ifEmpty { "图层" },
                visible = l.visible,
                alpha = l.opacity,
                blendMode = l.blendMode,
                alphaLock = l.alphaLock,
                clipped = l.clipped,
                mask = l.mask,
                strokeOn = fx?.strokeOn ?: false,
                strokeColor = fx?.strokeColor ?: 0xFF000000.toInt(),
                strokeWidth = fx?.strokeWidth ?: 6,
                shadowOn = fx?.shadowOn ?: false,
                shadowDX = fx?.shadowDX ?: 8,
                shadowDY = fx?.shadowDY ?: 8,
                shadowBlur = fx?.shadowBlur ?: 8,
                shadowColor = fx?.shadowColor ?: 0x99000000.toInt(),
                colorOverlayOn = fx?.colorOverlayOn ?: false,
                colorOverlayColor = fx?.colorOverlayColor ?: 0xFFFF3B30.toInt(),
                glowOn = fx?.glowOn ?: false,
                glowColor = fx?.glowColor ?: 0xCCFFE066.toInt(),
                glowSize = fx?.glowSize ?: 8,
                innerShadowOn = fx?.innerShadowOn ?: false,
                innerShadowColor = fx?.innerShadowColor ?: 0x99000000.toInt(),
                innerShadowDX = fx?.innerShadowDX ?: 4,
                innerShadowDY = fx?.innerShadowDY ?: 4,
                innerShadowSize = fx?.innerShadowSize ?: 6,
                innerGlowOn = fx?.innerGlowOn ?: false,
                innerGlowColor = fx?.innerGlowColor ?: 0xCCFFF3B0.toInt(),
                innerGlowSize = fx?.innerGlowSize ?: 6,
                satinOn = fx?.satinOn ?: false,
                satinColor = fx?.satinColor ?: 0x99000000.toInt(),
                satinSize = fx?.satinSize ?: 10,
                satinDistance = fx?.satinDistance ?: 12,
                satinAngle = fx?.satinAngle ?: 19,
                textContent = l.text?.content,
                textSize = l.text?.size ?: 0f,
                textColor = l.text?.color ?: 0,
                textX = 0f,
                textY = 0f,
                adjust = l.adjust,
                rawHead = l.raw?.let { Psd.packHead(it).pack() },
                // 合并过的 PSD 没有层记录，16 位数据只在合并图里，要从合并图拆一份
                rawPix = if (doc.depth > 8) {
                    l.raw?.let { r -> Psd.packPix(r) } ?: if (doc.layers.size == 1) Psd.packCompositePix(doc) else null
                } else null,
                pixHash = Psd.bitmapHash(l.bitmap, l.mask),
                sectionType = l.sectionType,
            )
        }
        ProjectStore.save(context, id, name, layers, doc.depth)
        if (src != null) PsdBrowser.rememberOriginal(context, id, src, doc)
        else if (raw != null) PsdBrowser.rememberOriginal(context, id, raw, doc)
        return PsdBrowser.ImportResult(id, name, layers.size)
    }
}
