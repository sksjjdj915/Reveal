package com.reveal.editor.data

import android.content.Context
import android.util.Base64
import java.io.File
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.reveal.editor.image.Psd
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 手机里的 PSD 浏览：只在列表里给预览，点一下才复制进软件。
 * 用 SAF（DocumentsContract）实现，像电脑一样逐级点；不申请全盘权限，也不碰原文件。
 */
object PsdBrowser {

    const val MIME_PSD = "image/vnd.adobe.photoshop"
    const val MIME_PSB = "image/x-photoshop"

    private const val PREFS = "reveal_psd"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_SNAP = "snapshot"
    private const val MAX_DEPTH = 6
    private const val MAX_ENTRIES = 4000

    /** 列表里的一项（还没复制，只是“看到”） */
    class Entry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val modified: Long,
        /** 所在的文件夹路径，界面上显示用 */
        val where: String,
        /** true = 软件里有副本了 */
        val backedUp: Boolean = false,
    ) {
        val key: String get() = uri.toString()
    }

    /** 手动浏览时的一个文件夹 */
    class Folder(val name: String, val docId: String)

    fun isPsdName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".psd") || n.endsWith(".psb")
    }

    // ------------------------------------------------------------ 授权记忆

    fun savedTree(context: Context): Uri? {
        val s = prefs(context).getString(KEY_TREE, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    /** 记住用户点的那个文件夹（授权一次，以后不再弹） */
    fun rememberTree(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs(context).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun forgetTree(context: Context) {
        prefs(context).edit().remove(KEY_TREE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 浏览

    fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** 列一个文件夹：返回 (子文件夹, 里面的 PSD) */
    fun listFolder(
        context: Context,
        tree: Uri,
        docId: String,
        where: String,
    ): Pair<List<Folder>, List<Entry>> {
        val dirs = ArrayList<Folder>()
        val files = ArrayList<Entry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs.add(Folder(name, id))
                    } else if (isPsdName(name)) {
                        files.add(
                            Entry(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                name = name,
                                size = size,
                                modified = modified,
                                where = where,
                            ),
                        )
                    }
                }
            }
        }
        dirs.sortBy { it.name.lowercase() }
        files.sortByDescending { it.modified }
        return dirs to files
    }

    /**
     * 扫整个（已授权的）文件夹，深度最多 6 层。
     * query 不为空时按名字过滤（搜索文件夹/文件用）。
     */
    fun scanTree(
        context: Context,
        tree: Uri,
        query: String? = null,
        onProgress: (String) -> Unit = {},
    ): List<Entry> {
        val q = query?.trim()?.lowercase().orEmpty()
        val out = ArrayList<Entry>()
        val rootId = rootDocId(tree)
        val rootName = runCatching {
            DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').ifEmpty { "手机存储" }
        }.getOrDefault("手机存储")
        var stack = ArrayDeque<Pair<String, String>>() // docId, where
        stack.addLast(rootId to rootName)
        var depth = 0
        while (stack.isNotEmpty() && out.size < MAX_ENTRIES) {
            val (id, where) = stack.removeFirst()
            onProgress("正在扫描：$where")
            val (dirs, files) = listFolder(context, tree, id, where)
            for (f in files) {
                if (q.isNotEmpty() && !f.name.lowercase().contains(q)) continue
                out.add(f)
            }
            if (dirs.isNotEmpty()) {
                for (d in dirs) {
                    if (d.name.startsWith(".")) continue
                    stack.addLast(d.docId to "$where/${d.name}")
                }
            }
            depth++
            if (depth > MAX_DEPTH * 2000) break
        }
        onProgress("")
        return out
    }

    // ------------------------------------------------------------ 预览缩略图

    /** 解析 PSD 的合成图，缩到 maxSide 内当缩略图；读不出来返回 null */
    fun thumbnail(context: Context, uri: Uri, maxSide: Int = 420): Bitmap? {
        val doc = runCatching {
            context.contentResolver.openInputStream(uri)?.use { Psd.open(it) }
        }.getOrNull() ?: return null
        if (doc.width <= 0 || doc.height <= 0) return null
        val scale = min(1f, maxSide.toFloat() / max(doc.width, doc.height))
        val w = (doc.width * scale).roundToInt().coerceAtLeast(1)
        val h = (doc.height * scale).roundToInt().coerceAtLeast(1)
        return runCatching {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.scale(w.toFloat() / doc.width, h.toFloat() / doc.height)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (l in doc.layers) {
                if (!l.visible) continue
                paint.alpha = l.opacity.coerceIn(0, 255)
                val bmp = l.bitmap ?: continue
                canvas.drawBitmap(bmp, 0f, 0f, paint)
            }
            out
        }.getOrNull()
    }

    // ------------------------------------------------------------ 复制进软件

    // ------------------------------------------------------------ 原样保存：记住原件

    /** 导入时留一份原始 PSD 字节 + 元信息；保存时若一个像素都没改，就把原件原样吐回去 */
    /** 从文件直接记原件：流式拷贝，整份不进 Java 堆（大 PSD 走这条，避免和解析同时占两份） */
    fun rememberOriginal(context: Context, projectId: String, src: java.io.File, doc: Psd.Document) {
        runCatching {
            val dir = File(projectRoot(context), projectId)
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, ORIGINAL)
            src.inputStream().use { input -> dst.outputStream().use { out -> input.copyTo(out, 256 * 1024) } }
            val meta = JSONObject()
            meta.put("depth", doc.depth)
            meta.put("colorMode", doc.colorMode)
            meta.put("channels", doc.channels)
            meta.put("version", doc.version)
            if (doc.icc != null && doc.icc.isNotEmpty()) {
                meta.put("icc", Base64.encodeToString(doc.icc, Base64.NO_WRAP))
            }
            File(dir, META).writeText(meta.toString())
        }
    }

    fun rememberOriginal(context: Context, projectId: String, raw: ByteArray, doc: Psd.Document) {
        runCatching {
            val dir = File(projectRoot(context), projectId)
            if (!dir.exists()) dir.mkdirs()
            File(dir, ORIGINAL).writeBytes(raw)
            val meta = JSONObject()
            meta.put("depth", doc.depth)
            meta.put("colorMode", doc.colorMode)
            meta.put("channels", doc.channels)
            meta.put("version", doc.version)
            if (doc.icc != null && doc.icc.isNotEmpty()) {
                meta.put("icc", Base64.encodeToString(doc.icc, Base64.NO_WRAP))
            }
            File(dir, META).writeText(meta.toString())
        }
    }

    class PsdMeta(val depth: Int, val colorMode: Int, val channels: Int, val version: Int, val icc: ByteArray?)

    /** 记录「这个 PSD 工程被改过了」，改过就不再拿原件当出口 */
    fun markEdited(context: Context, projectId: String) {
        runCatching {
            val dir = File(projectRoot(context), projectId)
            if (!dir.exists()) dir.mkdirs()
            File(dir, EDITED).writeText("1")
        }
    }

    fun isEdited(context: Context, projectId: String): Boolean =
        runCatching { File(File(projectRoot(context), projectId), EDITED).exists() }.getOrDefault(false)

    fun original(context: Context, projectId: String): ByteArray? {
        val f = File(projectRoot(context), "$projectId/$ORIGINAL")
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    fun meta(context: Context, projectId: String): PsdMeta? {
        val f = File(projectRoot(context), "$projectId/$META")
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            PsdMeta(
                j.optInt("depth", 8),
                j.optInt("colorMode", Psd.MODE_RGB),
                j.optInt("channels", 4),
                j.optInt("version", 1),
                j.optString("icc", "").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) },
            )
        }.getOrNull()
    }

    private const val ORIGINAL = "original.psd"
    private const val META = "psd_meta.json"
private const val EDITED = "psd_edited"

    private fun projectRoot(context: Context): File =
        File(context.filesDir, "projects")

    class ImportResult(val projectId: String, val name: String, val layerCount: Int)

    /**
     * 把这张 PSD 复制一份进软件（原文件不动），返回新工程 id。
     * 软件里已有同名工程时自动加序号，不覆盖。
     */
    fun importToProject(
        context: Context,
        entry: Entry,
        existingNames: Set<String>,
    ): ImportResult? {
        val raw = runCatching {
            context.contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val doc = runCatching { Psd.open(java.io.ByteArrayInputStream(raw)) }.getOrNull() ?: return null
        if (doc.layers.isEmpty()) return null

        val base = entry.name.substringBeforeLast('.').ifEmpty { "PSD 工程" }
        val name = uniqueName(base, existingNames)
        val id = ProjectStore.create(context, name, doc.width, doc.height)
        val layers = doc.layers.mapNotNull { l ->
            // 这里必须把 PSD 里的图层属性全搬过来。少搬一样，工程里就永久少一样：
            // 调整层参数（adjust）丢了 → 导出 PSD 只剩一个空像素层，桌面端里看不出调过什么；
            // 蒙版 / 剪贴 / 透明锁 / 文字 / 描边投影丢了同理。改这里之前先想清楚谁在读这些字段。
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
                // 原始层头部记录：曲线 / 智能对象 / 图层组 / 带字体的文字都靠它原样搬回 PSD
                rawHead = l.raw?.let { Psd.packHead(it).pack() },
                // 16 位文件：把原始通道字节整段存进工程，之后像素没动过就原样搬回 桌面端
                // 合并过的 PSD 没有层记录，16 位数据只在合并图里，要从合并图拆一份
                rawPix = if (doc.depth > 8) {
                    l.raw?.let { r -> Psd.packPix(r) } ?: if (doc.layers.size == 1) Psd.packCompositePix(doc) else null
                } else null,
                pixHash = Psd.bitmapHash(l.bitmap, l.mask),
                sectionType = l.sectionType,
            )
        }
        ProjectStore.save(context, id, name, layers, doc.depth)
        // 导入这一步最容易悄悄丢 16/32 位：把拿到的通道、位深、每层有没有原始像素都记下来
        runCatching {
            Diag.append(
                context, "IMPORT",
                "name=" + name + " depth=" + doc.depth + " chans=" + doc.channels +
                    " docLayers=" + doc.layers.size + " compBytes=" +
                    (doc.rawDoc?.composite?.let { it.data.size } ?: -1) +
                    " rawPixLayers=" + layers.count { it.rawPix != null } +
                    " firstRawPix=" + (layers.firstOrNull()?.rawPix?.size ?: -1) +
                    " alpha0=" + runCatching {
                        val bmp = doc.layers.firstOrNull()?.bitmap
                        if (bmp == null) "-1" else {
                            val tot = bmp.width * bmp.height
                            val stepY = if (bmp.height > 64) bmp.height / 64 else 1
                            val stepX = if (bmp.width > 64) bmp.width / 64 else 1
                            var zero = 0
                            var n = 0
                            var y = 0
                            while (y < bmp.height) {
                                var x = 0
                                while (x < bmp.width) {
                                    if (((bmp.getPixel(x, y) ushr 24) and 0xFF) == 0) zero++
                                    n++
                                    x += stepX
                                }
                                y += stepY
                            }
                            if (n == 0 || tot == 0) "-1" else ((zero * 100 / n)).toString() + "%"
                        }
                    }.getOrDefault("err") +
                    " bytes=" + raw.size,
            )
        }
        rememberOriginal(context, id, raw, doc)
        return ImportResult(id, name, layers.size)
    }

    fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    // ------------------------------------------------------------ 上次扫描结果（只扫新增）

    fun snapshot(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_SNAP, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = runCatching { Uri.parse(o.optString("uri")) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(
                    uri = uri,
                    name = o.optString("name"),
                    size = o.optLong("size"),
                    modified = o.optLong("modified"),
                    where = o.optString("where"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveSnapshot(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("uri", e.uri.toString())
                    .put("name", e.name)
                    .put("size", e.size)
                    .put("modified", e.modified)
                    .put("where", e.where),
            )
        }
        prefs(context).edit().putString(KEY_SNAP, arr.toString()).apply()
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}