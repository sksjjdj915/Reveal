package com.reveal.editor.data

import android.content.Context
import com.reveal.editor.image.AdjustApply
import com.reveal.editor.image.AdjustOp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 首页列表用的一条工程信息 */
data class Project(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val width: Int,
    val height: Int,
    val layerCount: Int,
)

/** 存/读工程时传的一层 */
class SaveLayer(
    val bitmap: Bitmap,
    val name: String,
    val visible: Boolean,
    val alpha: Int,
    val alphaLock: Boolean = false,
    val clipped: Boolean = false,

    /** 混合模式（与 SketchView.BLEND_* 对应） */
    val blendMode: Int = 0,
    val lockImage: Boolean = false,
    val lockPos: Boolean = false,
    val lockAll: Boolean = false,
    val fillAlpha: Int = 100,
    val colorTag: Int = 0,

    /** 蒙版（白色不透明=显示）；没有就是 null */
    val mask: Bitmap? = null,
    val maskEditing: Boolean = false,

    // ---- 图层样式（描边 / 投影）----
    val strokeOn: Boolean = false,
    val strokeColor: Int = 0xFF000000.toInt(),
    val strokeWidth: Int = 6,
    val shadowOn: Boolean = false,
    val shadowDX: Int = 8,
    val shadowDY: Int = 8,
    val shadowBlur: Int = 8,
    val shadowColor: Int = 0x99000000.toInt(),

    // ---- 图层样式（外发光 / 内阴影 / 内发光 / 颜色叠加 / 渐变叠加 / 斜面浮雕）----
    val glowOn: Boolean = false,
    val glowColor: Int = 0xCCFFE066.toInt(),
    val glowSize: Int = 8,
    val innerShadowOn: Boolean = false,
    val innerShadowColor: Int = 0x99000000.toInt(),
    val innerShadowDX: Int = 4,
    val innerShadowDY: Int = 4,
    val innerShadowSize: Int = 6,
    val innerGlowOn: Boolean = false,
    val innerGlowColor: Int = 0xCCFFF3B0.toInt(),
    val innerGlowSize: Int = 6,
    val colorOverlayOn: Boolean = false,
    val colorOverlayColor: Int = 0xFFFF3B30.toInt(),
    val gradientOverlayOn: Boolean = false,
    val gradientOverlayColor1: Int = 0xFFFF9F0A.toInt(),
    val gradientOverlayColor2: Int = 0xFF3A7BFF.toInt(),
    val gradientOverlayAngle: Int = 90,
    val bevelOn: Boolean = false,
    val bevelSize: Int = 6,
    val bevelDepth: Int = 60,
    val satinOn: Boolean = false,
    val satinColor: Int = 0x99000000.toInt(),
    val satinSize: Int = 10,
    val satinDistance: Int = 12,
    val satinAngle: Int = 19,
    val patternOverlayOn: Boolean = false,
    val patternOverlayIndex: Int = 0,
    val patternOverlayScale: Int = 100,
    val patternOverlayOpacity: Int = 100,
    val textureOn: Boolean = false,
    val texturePatternIndex: Int = 0,
    val textureScale: Int = 100,
    val textureDepth: Int = 50,
    val contourOn: Boolean = false,
    val contourKind: Int = 2,

    /** 智能滤镜：一行一步（见 StepRec.encode） */
    val smartFx: String = "",

    /** 智能对象的原始图（没有就是普通层）；存盘时单独压成 smart_<层号>.png */
    val smartSrc: Bitmap? = null,

    // ---- 文字层（非文字层为 null / 0）----
    val textContent: String? = null,
    val textSize: Float = 0f,
    val textColor: Int = 0,
    val textX: Float = 0f,
    val textY: Float = 0f,
    /** 文字字体（Android 家族名）；空 = 默认字体 */
    val textFont: String = "",
    val textBold: Boolean = false,
    val textItalic: Boolean = false,
    val textUnderline: Boolean = false,
    val textStrike: Boolean = false,
    val textTracking: Float = 0f,
    val textLeading: Float = 0f,
    val textAlign: Int = 1,
    val textScaleX: Float = 100f,
    val textScaleY: Float = 100f,
    val textShift: Float = 0f,
    val textCaps: Int = 0,
    val textSuperSub: Int = 0,
    val textWarp: Int = 0,
    val textWarpBend: Float = 50f,
    val textVertical: Boolean = false,
    val textOnPath: Boolean = false,
    val textPathIndex: Int = 0,

    /** 调整层参数；null 表示普通像素层 */
    val adjust: AdjustOp? = null,

    /** 从 PSD 打开时的原始层头部记录（Psd.RawHead.pack() 的字节）；导出时属性没改就原样搬回 */
    val rawHead: ByteArray? = null,

    /** 16 位原始通道数据；像素没动过时导出照搬，保住 16 位精度 */
    val rawPix: ByteArray? = null,

    /** 导入时的像素指纹：和导出时算的一致才算「没动过」 */
    val pixHash: Int = 0,

    /** 16 位缓冲（Pix16.pack 的字节）：画过的 16 位图层靠它保住精度 */
    val pix16: ByteArray? = null,
    /** 32 位浮点缓冲（Pix32.pack 的字节） */
    val pix32: ByteArray? = null,
    /** 桌面端原始蒙版通道字节（16 位蒙版靠它保精度）*/
    val maskRaw: ByteArray? = null,
    /** 蒙版被画过：原始通道作废，按当前蒙版重算 */
    val maskDirty: Boolean = false,

    /** 图层组标记：0=普通层，1=组展开，2=组折叠，3=组底分隔层 */
    val sectionType: Int = 0,
)

class LoadedDocument(
    val name: String,
    val width: Int,
    val height: Int,
    val layers: List<SaveLayer>,
    /** 文档位深：8 或 16（16 位文档重新打开后要继续按 16 位攒图层精度） */
    val depth: Int = 8,
)

/**
 * 工程文件：每个工程一个目录 filesDir/projects/<id>/
 *   project.rvl  = zip(manifest.json + layer_0.png ...)
 *   thumb.jpg    = 首页缩略图
 *   manifest.json= 首页读列表用（不用解压整个包）
 */
object ProjectStore {

    private const val DIR = "projects"
    private const val RVL = "project.rvl"
    private const val MANIFEST = "manifest.json"
    private const val THUMB = "thumb.jpg"

    private fun root(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun dir(context: Context, id: String): File = File(root(context), id)

    fun list(context: Context): List<Project> {
        val out = mutableListOf<Project>()
        val files = root(context).listFiles() ?: return out
        for (d in files) {
            if (!d.isDirectory) continue
            val m = readManifestFile(File(d, MANIFEST)) ?: continue
            out.add(
                Project(
                    id = d.name,
                    name = m.optString("name", "未命名画布"),
                    updatedAt = m.optLong("updatedAt", d.lastModified()),
                    width = m.optInt("width", 0),
                    height = m.optInt("height", 0),
                    layerCount = m.optJSONArray("layers")?.length() ?: 1,
                )
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun create(context: Context, name: String, width: Int, height: Int): String {
        val id = "p" + System.currentTimeMillis().toString(36)
        val dir = dir(context, id)
        dir.mkdirs()
        val blank = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blank.eraseColor(android.graphics.Color.WHITE)
        save(context, id, name, listOf(SaveLayer(blank, "图层 1", true, 255)))
        return id
    }

    fun rename(context: Context, id: String, name: String) {
        val file = File(dir(context, id), MANIFEST)
        val m = readManifestFile(file) ?: return
        m.put("name", name)
        m.put("updatedAt", System.currentTimeMillis())
        file.writeText(m.toString())
    }

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
    }

    /**
     * 复制一个工程：目录整体拷一份、名字加「副本」，首页上就多出一条一模一样的。
     * 连缩略图、调色进度、上一次的备份包都照搬，所以复制出来的能直接打开接着画。
     */
    @Synchronized
    fun copy(context: Context, id: String): String? {
        val src = dir(context, id)
        if (!src.isDirectory) return null
        val oldManifest = readManifestFile(File(src, MANIFEST))
        val baseName = (oldManifest?.optString("name") ?: "").ifEmpty { "未命名画布" }
        val taken = list(context).map { it.name }.toSet()
        var name = "$baseName 副本"
        var n = 2
        while (taken.contains(name)) {
            name = "$baseName 副本 $n"
            n++
        }
        var newId = "p" + System.currentTimeMillis().toString(36)
        var guard = 0
        while (dir(context, newId).exists() && guard++ < 50) {
            newId = "p" + (System.currentTimeMillis() + guard).toString(36)
        }
        val dst = dir(context, newId).apply { mkdirs() }
        // 1) 目录里的文件照着拷（工程包 / 缩略图 / 调色进度 / .bak 备份）
        src.listFiles()?.forEach { f ->
            if (f.isFile) runCatching { f.copyTo(File(dst, f.name), overwrite = true) }
        }
        val now = System.currentTimeMillis()
        // 2) 首页列表读的 manifest.json 改名
        runCatching {
            val m = readManifestFile(File(dst, MANIFEST)) ?: JSONObject()
            m.put("name", name)
            m.put("updatedAt", now)
            File(dst, MANIFEST).writeText(m.toString())
        }
        // 3) 工程包里那份 manifest 也要改名，否则打开后标题还是老名字
        runCatching { rewritePackageName(File(dst, RVL), name, now) }
        return newId
    }

    /**
     * 只改工程包里的 manifest.json，其余条目原样流式搬过去——
     * 不整包解压再压，几十层的 4096 工程也不会把内存吃爆。
     */
    private fun rewritePackageName(file: File, name: String, updatedAt: Long) {
        if (!file.exists()) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        ZipInputStream(file.inputStream().buffered()).use { zin ->
            ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
                runCatching { zout.setLevel(1) }
                var entry = runCatching { zin.nextEntry }.getOrNull()
                while (entry != null) {
                    val entryName = entry.name
                    if (entryName == MANIFEST) {
                        val bytes = zin.readBytes()
                        val m = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                            .getOrNull() ?: JSONObject()
                        m.put("name", name)
                        m.put("updatedAt", updatedAt)
                        zout.putNextEntry(ZipEntry(MANIFEST))
                        zout.write(m.toString().toByteArray())
                        zout.closeEntry()
                    } else {
                        zout.putNextEntry(ZipEntry(entryName))
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = runCatching { zin.read(buf) }.getOrDefault(-1)
                            if (r <= 0) break
                            zout.write(buf, 0, r)
                        }
                        zout.closeEntry()
                    }
                    entry = runCatching { zin.nextEntry }.getOrNull()
                }
            }
        }
        if (tmp.exists()) {
            file.delete()
            if (!tmp.renameTo(file)) {
                runCatching { tmp.copyTo(file, overwrite = true) }
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun save(context: Context, id: String, name: String, layers: List<SaveLayer>, depth: Int = 8) {
        if (layers.isEmpty()) return
        val dir = dir(context, id).apply { mkdirs() }
        val w = layers.first().bitmap.width
        val h = layers.first().bitmap.height

        val manifest = JSONObject()
        manifest.put("name", name)
        manifest.put("width", w)
        manifest.put("height", h)
        manifest.put("depth", depth)
        manifest.put("updatedAt", System.currentTimeMillis())
        val arr = JSONArray()
        layers.forEachIndexed { index, layer ->
            val entry = JSONObject()
            entry.put("file", "layer_$index.bin")
            entry.put("name", layer.name)
            entry.put("visible", layer.visible)
            entry.put("alpha", layer.alpha)
            entry.put("alphaLock", layer.alphaLock)
            entry.put("clipped", layer.clipped)
            entry.put("blendMode", layer.blendMode)
        if (layer.lockImage) entry.put("lockImage", true)
        if (layer.lockPos) entry.put("lockPos", true)
        if (layer.lockAll) entry.put("lockAll", true)
        if (layer.fillAlpha != 100) entry.put("fillAlpha", layer.fillAlpha)
        if (layer.colorTag != 0) entry.put("colorTag", layer.colorTag)
            if (layer.mask != null) entry.put("hasMask", true)
        layer.adjust?.let { entry.put("adjust", AdjustOp.toJson(it)) }
            layer.rawHead?.let {
                entry.put("rawHead", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
            }
            if (layer.pixHash != 0) entry.put("pixHash", layer.pixHash)
            if (layer.sectionType != 0) entry.put("sectionType", layer.sectionType)
            if (layer.maskEditing) entry.put("maskEdit", true)
            if (layer.strokeOn) {
                entry.put("strokeOn", true)
                entry.put("strokeColor", layer.strokeColor)
                entry.put("strokeWidth", layer.strokeWidth)
            }
            if (layer.textContent != null) {
                entry.put("text", layer.textContent)
                entry.put("textSize", layer.textSize.toDouble())
                entry.put("textColor", layer.textColor)
                entry.put("textX", layer.textX.toDouble())
                entry.put("textY", layer.textY.toDouble())
                if (layer.textFont.isNotEmpty()) entry.put("textFont", layer.textFont)
                entry.put("txBold", layer.textBold)
                entry.put("txItalic", layer.textItalic)
                entry.put("txUnder", layer.textUnderline)
                entry.put("txStrike", layer.textStrike)
                entry.put("txTrack", layer.textTracking.toDouble())
                entry.put("txLead", layer.textLeading.toDouble())
                entry.put("txAlign", layer.textAlign)
                entry.put("txScaleX", layer.textScaleX.toDouble())
                entry.put("txScaleY", layer.textScaleY.toDouble())
                entry.put("txShift", layer.textShift.toDouble())
                entry.put("txCaps", layer.textCaps)
                entry.put("txSuper", layer.textSuperSub)
                entry.put("txWarp", layer.textWarp)
                entry.put("txBend", layer.textWarpBend.toDouble())
                entry.put("txVert", layer.textVertical)
                if (layer.textOnPath) {
                    entry.put("txPath", true)
                    entry.put("txPathIdx", layer.textPathIndex)
                }
            }
            if (layer.shadowOn) {
                entry.put("shadowOn", true)
                entry.put("shadowDX", layer.shadowDX)
                entry.put("shadowDY", layer.shadowDY)
                entry.put("shadowBlur", layer.shadowBlur)
                entry.put("shadowColor", layer.shadowColor)
            }
            if (layer.glowOn) {
                entry.put("glowOn", true)
                entry.put("glowColor", layer.glowColor)
                entry.put("glowSize", layer.glowSize)
            }
            if (layer.innerShadowOn) {
                entry.put("isOn", true)
                entry.put("isColor", layer.innerShadowColor)
                entry.put("isDX", layer.innerShadowDX)
                entry.put("isDY", layer.innerShadowDY)
                entry.put("isSize", layer.innerShadowSize)
            }
            if (layer.innerGlowOn) {
                entry.put("igOn", true)
                entry.put("igColor", layer.innerGlowColor)
                entry.put("igSize", layer.innerGlowSize)
            }
            if (layer.colorOverlayOn) {
                entry.put("coOn", true)
                entry.put("coColor", layer.colorOverlayColor)
            }
            if (layer.gradientOverlayOn) {
                entry.put("goOn", true)
                entry.put("goColor1", layer.gradientOverlayColor1)
                entry.put("goColor2", layer.gradientOverlayColor2)
                entry.put("goAngle", layer.gradientOverlayAngle)
            }
            if (layer.bevelOn) {
                entry.put("bevOn", true)
                entry.put("bevSize", layer.bevelSize)
                entry.put("bevDepth", layer.bevelDepth)
            }
            if (layer.satinOn) {
                entry.put("satOn", true)
                entry.put("satColor", layer.satinColor)
                entry.put("satSize", layer.satinSize)
                entry.put("satDist", layer.satinDistance)
                entry.put("satAngle", layer.satinAngle)
            }
            if (layer.patternOverlayOn) {
                entry.put("poOn", true)
                entry.put("poIdx", layer.patternOverlayIndex)
                entry.put("poScale", layer.patternOverlayScale)
                entry.put("poOp", layer.patternOverlayOpacity)
            if (layer.textureOn) {
                entry.put("texOn", true)
                entry.put("texIdx", layer.texturePatternIndex)
                entry.put("texScale", layer.textureScale)
                entry.put("texDepth", layer.textureDepth)
            }
            if (layer.contourOn) {
                entry.put("conOn", true)
                entry.put("conKind", layer.contourKind)
            }
            if (layer.smartFx.isNotEmpty()) entry.put("sFx", layer.smartFx)
            }
            arr.put(entry)
        }
        manifest.put("layers", arr)

        // 1) 先把每层的 PNG 并行编码好，再顺序写进 zip。
        // 原来是一边压缩一边往 zip 里写，30 层就是 30 次串行 PNG 编码（打开一张三十层的图要一分钟的主因）。
        // 并行编码用的本地实现（ProjectStore 里另有两个同名不同返回值的 encodePng，别撞名）
        fun pngBytes(b: Bitmap): ByteArray {
            val o = java.io.ByteArrayOutputStream(1 shl 20)
            b.compress(Bitmap.CompressFormat.PNG, 100, o)
            return o.toByteArray()
        }

        val tEnc0 = System.currentTimeMillis()
        val encMask = arrayOfNulls<ByteArray>(layers.size)
        val encSmart = arrayOfNulls<ByteArray>(layers.size)
        runCatching {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            )
            try {
                val jobs = ArrayList<java.util.concurrent.Future<*>>(layers.size)
                layers.forEachIndexed { i, layer ->
                    jobs.add(pool.submit {
                        layer.mask?.let { mk -> runCatching { encMask[i] = pngBytes(mk) } }
                        layer.smartSrc?.let { sm -> runCatching { encSmart[i] = pngBytes(sm) } }
                    })
                }
                jobs.forEach { runCatching { it.get() } }
            } finally {
                pool.shutdown()
            }
        }
        val tEnc1 = System.currentTimeMillis()
        // 1b) 工程包：先写临时文件，写完再替换，避免中途被杀导致工程包被截断（打不开）
        val tmp = File(dir, "$RVL.tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            // 1 级：图层写的是原始 ARGB 字节，压缩比交给 zip；PNG 那种高质量编码在手机上太慢
            runCatching { zip.setLevel(1) }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            layers.forEachIndexed { index, layer ->
                zip.putNextEntry(ZipEntry("layer_$index.bin"))
                writeRawPixels(zip, layer.bitmap)
                zip.closeEntry()
                val rawPix = layer.rawPix
                if (rawPix != null) {
                    zip.putNextEntry(ZipEntry("rawpix_$index.bin"))
                    zip.write(rawPix)
                    zip.closeEntry()
                }
                val maskRaw = layer.maskRaw
                if (maskRaw != null) {
                    zip.putNextEntry(ZipEntry("maskraw_$index.bin"))
                    zip.write(maskRaw)
                    zip.closeEntry()
                }
                if (layer.maskDirty) {
                    zip.putNextEntry(ZipEntry("maskdirty_$index.bin"))
                    zip.write(1)
                    zip.closeEntry()
                }
                val pix16 = layer.pix16
                if (pix16 != null) {
                    zip.putNextEntry(ZipEntry("pix16_$index.bin"))
                    zip.write(pix16)
                    zip.closeEntry()
                }
                val pix32 = layer.pix32
                if (pix32 != null) {
                    zip.putNextEntry(ZipEntry("pix32_$index.bin"))
                    zip.write(pix32)
                    zip.closeEntry()
                }
                // 智能对象的原始图：不存的话重开工程就变成普通层，反复缩放又会糊
                val smart = layer.smartSrc
                if (smart != null) {
                    zip.putNextEntry(ZipEntry("smart_$index.png"))
                    val encs = encSmart[index]
                    if (encs != null) zip.write(encs) else smart.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                }
                val mk = layer.mask ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry("mask_$index.png"))
                val encm = encMask[index]
                if (encm != null) zip.write(encm) else mk.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
        val target = File(dir, RVL)
        val backup = File(dir, "$RVL.bak")
        if (target.exists()) {
            backup.delete()
            target.renameTo(backup)
        }
        if (tmp.exists() && !tmp.renameTo(target)) {
            runCatching { tmp.copyTo(target, overwrite = true) }
            tmp.delete()
        }

        val tZip1 = System.currentTimeMillis()

        // 2) 首页用的 manifest 副本（不用解开 zip）
        File(dir, MANIFEST).writeText(manifest.toString())

        // 3) 缩略图 = 所有可见图层合成的结果（直接在 480 的小图上合成，不烘焙整幅）
        val thumb = flatten(layers, 480)
        File(dir, THUMB).outputStream().buffered().use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        val tEnd = System.currentTimeMillis()
        android.util.Log.i(
            "RevealPerf",
            "save layers=" + layers.size + " ${w}x${h}" +
                " encode=" + (tEnc1 - tEnc0) + "ms" +
                " zip=" + (tZip1 - tEnc1) + "ms" +
                " thumb=" + (tEnd - tZip1) + "ms" +
                " total=" + (tEnd - tEnc0) + "ms"
        )
    }

    /**
     * 把整张位图按 ARGB_8888 原始字节写出去（由 zip 压）。比 PNG 编码快一个量级，
     * 分条读写，内存占用只跟一行像素有关，4096 的图也不会 OOM。
     */
    private fun writeRawPixels(out: java.io.OutputStream, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        val rows = ((1 shl 21) / (w * 4)).coerceIn(1, 256)
        val px = IntArray(w * rows)
        val buf = ByteArray(w * rows * 4)
        var y = 0
        while (y < h) {
            val r = minOf(rows, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, r)
            var bi = 0
            for (k in 0 until w * r) {
                val c = px[k]
                buf[bi] = ((c ushr 24) and 0xFF).toByte()
                buf[bi + 1] = ((c ushr 16) and 0xFF).toByte()
                buf[bi + 2] = ((c ushr 8) and 0xFF).toByte()
                buf[bi + 3] = (c and 0xFF).toByte()
                bi += 4
            }
            out.write(buf, 0, w * r * 4)
            y += r
        }
    }

    /** 读原始字节图层：老工程是 layer_N.png，新工程是 layer_N.bin */
    private fun readRawPixels(zip: java.util.zip.ZipInputStream, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val rows = ((1 shl 21) / (w * 4)).coerceIn(1, 256)
        val px = IntArray(w * rows)
        val buf = ByteArray(w * rows * 4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var y = 0
        while (y < h) {
            val r = minOf(rows, h - y)
            var off = 0
            val want = w * r * 4
            while (off < want) {
                val n = zip.read(buf, off, want - off)
                if (n <= 0) break
                off += n
            }
            if (off < want) return null
            var bi = 0
            for (k in 0 until w * r) {
                px[k] = ((buf[bi].toInt() and 0xFF) shl 24) or
                    ((buf[bi + 1].toInt() and 0xFF) shl 16) or
                    ((buf[bi + 2].toInt() and 0xFF) shl 8) or
                    (buf[bi + 3].toInt() and 0xFF)
                bi += 4
            }
            bmp.setPixels(px, 0, w, 0, y, w, r)
            y += r
        }
        return bmp
    }

    fun load(context: Context, id: String): LoadedDocument? {
        val folder = dir(context, id)
        val t0 = System.currentTimeMillis()
        val doc = runCatching { readDocument(File(folder, RVL)) }.getOrNull()
        if (doc != null) {
            android.util.Log.i(
                "RevealPerf",
                "load " + id + " layers=" + doc.layers.size + " " + (System.currentTimeMillis() - t0) + "ms"
            )
            return doc
        }
        // 主包坏了（保存时被中断/被杀）就退回上一次的备份
        return runCatching { readDocument(File(folder, "$RVL.bak")) }.getOrNull()
    }

    private fun readDocument(file: File): LoadedDocument? {
        if (!file.exists()) return null
        var manifest: JSONObject? = null
        val bitmaps = mutableListOf<Bitmap>()
        val maskMap = HashMap<Int, Bitmap>()
        val pixMap = HashMap<Int, ByteArray>()
    val pix16Map = HashMap<Int, ByteArray>()
    val pix32Map = HashMap<Int, ByteArray>()
        val smartMap = HashMap<Int, Bitmap>()
    val maskRawMap = HashMap<Int, ByteArray>()
    val maskDirtySet = HashSet<Int>()
        // 单个条目坏了（文件被截断/写坏）就跳过它，尽量把还能读的图层捞出来
        runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var entry = runCatching { zip.nextEntry }.getOrNull()
                while (entry != null) {
                    val name = entry.name
                    runCatching {
                        if (name == MANIFEST) {
                            manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        } else if (name.startsWith("layer_") && name.endsWith(".bin")) {
                            // 新格式：原始 ARGB 字节（manifest 写在最前面，宽高已经从里面拿到了）
                            val w = manifest?.optInt("width", 0) ?: 0
                            val h = manifest?.optInt("height", 0) ?: 0
                            val bmp = readRawPixels(zip, w, h)
                            if (bmp != null) bitmaps.add(bmp)
                        } else if (name.startsWith("mask_") && name.endsWith(".png")) {
                            val bytes = zip.readBytes()
                            val bmp = BitmapFactory.decodeByteArray(
                                bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                            )
                            if (bmp != null) {
                                val idx = name.removePrefix("mask_").removeSuffix(".png").toIntOrNull() ?: -1
                                if (idx >= 0) maskMap[idx] = bmp
                            }
                        } else if (name.startsWith("rawpix_") && name.endsWith(".bin")) {
                            val idx = name.removePrefix("rawpix_").removeSuffix(".bin").toIntOrNull() ?: -1
                            if (idx >= 0) pixMap[idx] = zip.readBytes()
                        } else if (name.startsWith("maskraw_") && name.endsWith(".bin")) {
                            val idx = name.removePrefix("maskraw_").removeSuffix(".bin").toIntOrNull() ?: -1
                            if (idx >= 0) maskRawMap[idx] = zip.readBytes()
                        } else if (name.startsWith("maskdirty_") && name.endsWith(".bin")) {
                            val idx = name.removePrefix("maskdirty_").removeSuffix(".bin").toIntOrNull() ?: -1
                            if (idx >= 0) maskDirtySet.add(idx)
                        } else if (name.startsWith("pix16_") && name.endsWith(".bin")) {
                            val idx = name.removePrefix("pix16_").removeSuffix(".bin").toIntOrNull() ?: -1
                            if (idx >= 0) pix16Map[idx] = zip.readBytes()
                        } else if (name.startsWith("pix32_") && name.endsWith(".bin")) {
                            val idx = name.removePrefix("pix32_").removeSuffix(".bin").toIntOrNull() ?: -1
                            if (idx >= 0) pix32Map[idx] = zip.readBytes()
                        } else if (name.startsWith("smart_") && name.endsWith(".png")) {
                            val bytes = zip.readBytes()
                            val bmp = BitmapFactory.decodeByteArray(
                                bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                            )
                            val idx = name.removePrefix("smart_").removeSuffix(".png").toIntOrNull() ?: -1
                            if (bmp != null && idx >= 0) smartMap[idx] = bmp
                        } else if (name.endsWith(".png")) {
                            val bytes = zip.readBytes()
                            val bmp = BitmapFactory.decodeByteArray(
                                bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                            )
                            if (bmp != null) {
                                bitmaps.add(
                                    if (bmp.isMutable && bmp.config == Bitmap.Config.ARGB_8888) {
                                        bmp
                                    } else {
                                        bmp.copy(Bitmap.Config.ARGB_8888, true) ?: bmp
                                    }
                                )
                            }
                        }
                        zip.closeEntry()
                    }
                    entry = runCatching { zip.nextEntry }.getOrNull()
                }
            }
        }
        val m = manifest
        val arr = m?.optJSONArray("layers")
        val layers = bitmaps.mapIndexed { index, bmp ->
            val meta = arr?.optJSONObject(index)
            SaveLayer(
                bitmap = bmp,
                name = meta?.optString("name")?.takeIf { it.isNotEmpty() } ?: "图层 ${index + 1}",
                visible = meta?.optBoolean("visible", true) ?: true,
                alpha = meta?.optInt("alpha", 255) ?: 255,
                alphaLock = meta?.optBoolean("alphaLock", false) ?: false,
                clipped = meta?.optBoolean("clipped", false) ?: false,
                blendMode = meta?.optInt("blendMode", 0) ?: 0,
            lockImage = meta?.optBoolean("lockImage", false) ?: false,
            lockPos = meta?.optBoolean("lockPos", false) ?: false,
            lockAll = meta?.optBoolean("lockAll", false) ?: false,
            fillAlpha = meta?.optInt("fillAlpha", 100) ?: 100,
            colorTag = meta?.optInt("colorTag", 0) ?: 0,
                adjust = AdjustOp.fromJson(meta?.optJSONObject("adjust")),
                sectionType = meta?.optInt("sectionType", 0) ?: 0,
                rawHead = meta?.optString("rawHead")?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() },
                mask = maskMap[index],
                rawPix = pixMap[index],
                pix16 = pix16Map[index],
                pix32 = pix32Map[index],
                maskRaw = maskRawMap[index],
                maskDirty = maskDirtySet.contains(index),
                pixHash = meta?.optInt("pixHash", 0) ?: 0,
                maskEditing = meta?.optBoolean("maskEdit", false) ?: false,
                strokeOn = meta?.optBoolean("strokeOn", false) ?: false,
                strokeColor = meta?.optInt("strokeColor", 0xFF000000.toInt()) ?: 0xFF000000.toInt(),
                strokeWidth = meta?.optInt("strokeWidth", 6) ?: 6,
                shadowOn = meta?.optBoolean("shadowOn", false) ?: false,
                shadowDX = meta?.optInt("shadowDX", 8) ?: 8,
                shadowDY = meta?.optInt("shadowDY", 8) ?: 8,
                shadowBlur = meta?.optInt("shadowBlur", 8) ?: 8,
                shadowColor = meta?.optInt("shadowColor", 0x99000000.toInt()) ?: 0x99000000.toInt(),
                glowOn = meta?.optBoolean("glowOn", false) ?: false,
                glowColor = meta?.optInt("glowColor", 0xCCFFE066.toInt()) ?: 0xCCFFE066.toInt(),
                glowSize = meta?.optInt("glowSize", 8) ?: 8,
                innerShadowOn = meta?.optBoolean("isOn", false) ?: false,
                innerShadowColor = meta?.optInt("isColor", 0x99000000.toInt()) ?: 0x99000000.toInt(),
                innerShadowDX = meta?.optInt("isDX", 4) ?: 4,
                innerShadowDY = meta?.optInt("isDY", 4) ?: 4,
                innerShadowSize = meta?.optInt("isSize", 6) ?: 6,
                innerGlowOn = meta?.optBoolean("igOn", false) ?: false,
                innerGlowColor = meta?.optInt("igColor", 0xCCFFF3B0.toInt()) ?: 0xCCFFF3B0.toInt(),
                innerGlowSize = meta?.optInt("igSize", 6) ?: 6,
                colorOverlayOn = meta?.optBoolean("coOn", false) ?: false,
                colorOverlayColor = meta?.optInt("coColor", 0xFFFF3B30.toInt()) ?: 0xFFFF3B30.toInt(),
                gradientOverlayOn = meta?.optBoolean("goOn", false) ?: false,
                gradientOverlayColor1 = meta?.optInt("goColor1", 0xFFFF9F0A.toInt()) ?: 0xFFFF9F0A.toInt(),
                gradientOverlayColor2 = meta?.optInt("goColor2", 0xFF3A7BFF.toInt()) ?: 0xFF3A7BFF.toInt(),
                gradientOverlayAngle = meta?.optInt("goAngle", 90) ?: 90,
                bevelOn = meta?.optBoolean("bevOn", false) ?: false,
                bevelSize = meta?.optInt("bevSize", 6) ?: 6,
                bevelDepth = meta?.optInt("bevDepth", 60) ?: 60,
                satinOn = meta?.optBoolean("satOn", false) ?: false,
                satinColor = meta?.optInt("satColor", 0x99000000.toInt()) ?: 0x99000000.toInt(),
                satinSize = meta?.optInt("satSize", 10) ?: 10,
                satinDistance = meta?.optInt("satDist", 12) ?: 12,
                satinAngle = meta?.optInt("satAngle", 19) ?: 19,
                patternOverlayOn = meta?.optBoolean("poOn", false) ?: false,
                patternOverlayIndex = meta?.optInt("poIdx", 0) ?: 0,
                patternOverlayScale = meta?.optInt("poScale", 100) ?: 100,
                patternOverlayOpacity = meta?.optInt("poOp", 100) ?: 100,
            textureOn = meta?.optBoolean("texOn", false) ?: false,
            texturePatternIndex = meta?.optInt("texIdx", 0) ?: 0,
            textureScale = meta?.optInt("texScale", 100) ?: 100,
            textureDepth = meta?.optInt("texDepth", 50) ?: 50,
            contourOn = meta?.optBoolean("conOn", false) ?: false,
            contourKind = meta?.optInt("conKind", 2) ?: 2,
            smartFx = meta?.optString("sFx") ?: "",
                smartSrc = smartMap[index],
                textContent = meta?.optString("text") ?: null,
                textSize = meta?.optDouble("textSize", 0.0)?.toFloat() ?: 0f,
                textColor = meta?.optInt("textColor", 0) ?: 0,
                textX = meta?.optDouble("textX", 0.0)?.toFloat() ?: 0f,
                textY = meta?.optDouble("textY", 0.0)?.toFloat() ?: 0f,
                textFont = meta?.optString("textFont") ?: "",
                textBold = meta?.optBoolean("txBold", false) ?: false,
                textItalic = meta?.optBoolean("txItalic", false) ?: false,
                textUnderline = meta?.optBoolean("txUnder", false) ?: false,
                textStrike = meta?.optBoolean("txStrike", false) ?: false,
                textTracking = (meta?.optDouble("txTrack", 0.0) ?: 0.0).toFloat(),
                textLeading = (meta?.optDouble("txLead", 0.0) ?: 0.0).toFloat(),
                textAlign = meta?.optInt("txAlign", 1) ?: 1,
                textScaleX = (meta?.optDouble("txScaleX", 100.0) ?: 100.0).toFloat(),
                textScaleY = (meta?.optDouble("txScaleY", 100.0) ?: 100.0).toFloat(),
                textShift = (meta?.optDouble("txShift", 0.0) ?: 0.0).toFloat(),
                textCaps = meta?.optInt("txCaps", 0) ?: 0,
                textSuperSub = meta?.optInt("txSuper", 0) ?: 0,
                textWarp = meta?.optInt("txWarp", 0) ?: 0,
                textWarpBend = (meta?.optDouble("txBend", 50.0) ?: 50.0).toFloat(),
                textVertical = meta?.optBoolean("txVert", false) ?: false,
                textOnPath = meta?.optBoolean("txPath", false) ?: false,
                textPathIndex = meta?.optInt("txPathIdx", 0) ?: 0,
            )
        }
        if (layers.isEmpty()) return null
        return LoadedDocument(
            name = m?.optString("name", "未命名画布") ?: "未命名画布",
            width = layers.first().bitmap.width,
            height = layers.first().bitmap.height,
            layers = layers,
            depth = m?.optInt("depth", 8) ?: 8,
        )
    }

    fun thumb(context: Context, id: String): Bitmap? {
        val file = File(dir(context, id), THUMB)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun readManifestFile(file: File): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /**
     * 首页缩略图 / 合并图：按图层面板看到的顺序把图层叠起来。
     *
     * 必须和编辑器画面上是同一张图 —— 混合模式、组的混合模式与整组不透明度、调整层、
     * 剪贴蒙版、描边投影、图层蒙版都要算进去。以前这里只按透明度叠，
     * 所以把组调成「正片叠底」后首页预览图一点变化都没有。
     */
    fun flatten(layers: List<SaveLayer>, maxSide: Int = 0): Bitmap {
        val w = layers.first().bitmap.width
        val h = layers.first().bitmap.height
        // maxSide > 0 时直接在小图上合成：缩略图不必先烘焙一张整幅（4096 就是 50MB），
        // 图层位图交给 Canvas 的缩放矩阵做 FILTER_BITMAP 缩采样，内存只跟缩略图尺寸有关。
        val k = if (maxSide > 0 && maxOf(w, h) > maxSide) maxSide.toFloat() / maxOf(w, h) else 1f
        val sw = maxOf(1, (w * k).toInt())
        val sh = maxOf(1, (h * k).toInt())
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())

        class Target(val bitmap: Bitmap, val canvas: Canvas)

        fun applyBlend(paint: Paint, mode: Int) {
            paint.xfermode = null
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                paint.blendMode = when (mode) {
                    1 -> android.graphics.BlendMode.MULTIPLY
                    2 -> android.graphics.BlendMode.SCREEN
                    3 -> android.graphics.BlendMode.OVERLAY
                    4 -> android.graphics.BlendMode.SOFT_LIGHT
                    5 -> android.graphics.BlendMode.HARD_LIGHT
                    6 -> android.graphics.BlendMode.DARKEN
                    7 -> android.graphics.BlendMode.LIGHTEN
                    8 -> android.graphics.BlendMode.COLOR_BURN
                    9 -> android.graphics.BlendMode.COLOR_DODGE
                    10 -> android.graphics.BlendMode.DIFFERENCE
                    11 -> android.graphics.BlendMode.EXCLUSION
                    12 -> android.graphics.BlendMode.HUE
                    13 -> android.graphics.BlendMode.SATURATION
                    14 -> android.graphics.BlendMode.COLOR
                    15 -> android.graphics.BlendMode.LUMINOSITY
                    else -> android.graphics.BlendMode.SRC_OVER
                }
            } else {
                paint.xfermode = when (mode) {
                    1 -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    2 -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    3 -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                    6 -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                    7 -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                    else -> null
                }
            }
        }

        fun drawOne(canvas: Canvas, layer: SaveLayer) {
            val styled = layer.strokeOn || layer.shadowOn
            val mk = layer.mask
            val needsLayer = styled || mk != null
            if (needsLayer) canvas.saveLayer(null, null)
            if (styled) {
                val ov = styleOverlay(layer, w, h)
                if (ov != null) canvas.drawBitmap(ov, 0f, 0f, null)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.alpha = layer.alpha
            applyBlend(paint, layer.blendMode)
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
            if (mk != null) {
                val mp = Paint(Paint.FILTER_BITMAP_FLAG)
                mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawBitmap(mk, null, dst, mp)
            }
            if (needsLayer) canvas.restore()
        }

        /** 组底分隔层 → 组头的位置；不是组返回 -1 */
        fun headerOf(divider: Int, until: Int): Int {
            var depth = 1
            var i = divider + 1
            while (i < until) {
                when (layers[i].sectionType) {
                    3 -> depth++
                    1, 2 -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }

        fun drawRun(target: Target, from: Int, until: Int) {
            var i = from
            while (i < until) {
                val base = layers[i]
                if (base.sectionType == 3) {
                    val header = headerOf(i, until)
                    if (header < 0) {
                        i++
                        continue
                    }
                    val head = layers[header]
                    if (head.visible) {
                        // 组有不透明度 / 混合模式时才单独开草稿，否则直接叠，省一块整幅位图
                        if (head.alpha < 255 || head.blendMode != 0) {
                            val scratch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                            val sc = Canvas(scratch)
                            sc.scale(k, k)
                            val sub = Target(scratch, sc)
                            drawRun(sub, i + 1, header)
                            val gp = Paint(Paint.FILTER_BITMAP_FLAG)
                            gp.alpha = head.alpha
                            applyBlend(gp, head.blendMode)
                            target.canvas.drawBitmap(scratch, 0f, 0f, gp)
                            scratch.recycle()
                        } else {
                            drawRun(target, i + 1, header)
                        }
                    }
                    i = header + 1
                    continue
                }
                // 组头 / 组折叠本身不画像素
                if (base.sectionType == 1 || base.sectionType == 2) {
                    i++
                    continue
                }
                // 调整层没有像素，只把下面已经画好的结果调一遍
                val adj = base.adjust
                if (adj != null) {
                    if (base.visible && !adj.isNeutral()) AdjustApply.applyInPlace(target.bitmap, adj)
                    i++
                    continue
                }
                if (base.visible) drawOne(target.canvas, base)
                var j = i + 1
                while (j < until && layers[j].clipped) {
                    val child = layers[j]
                    if (base.visible && child.visible) {
                        target.canvas.saveLayer(null, null)
                        drawOne(target.canvas, child)
                        val mp = Paint(Paint.FILTER_BITMAP_FLAG)
                        mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        target.canvas.drawBitmap(base.bitmap, 0f, 0f, mp)
                        target.canvas.restore()
                    }
                    j++
                }
                i = j
            }
        }

        val root = Canvas(out)
        root.scale(k, k)
        drawRun(Target(out, root), 0, layers.size)
        return out
    }

    /** 描边 / 投影叠图（首页缩略图也要有，不然缩略图和编辑器里看到的不一样） */
    private fun styleOverlay(layer: SaveLayer, w: Int, h: Int): Bitmap? {
        if (!layer.strokeOn && !layer.shadowOn) return null
        val bmp = layer.bitmap
        if (bmp.width != w || bmp.height != h) return null
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(res)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (layer.shadowOn) {
            p.colorFilter = PorterDuffColorFilter(layer.shadowColor, PorterDuff.Mode.SRC_IN)
            val dx = layer.shadowDX.toFloat()
            val dy = layer.shadowDY.toFloat()
            val b = layer.shadowBlur.toFloat()
            p.alpha = 255
            c.drawBitmap(bmp, dx, dy, p)
            if (b > 0.5f) {
                p.alpha = 70
                for (k in 0 until 8) {
                    val ang = k * Math.PI / 4.0
                    c.drawBitmap(bmp, dx + (Math.cos(ang) * b).toFloat(), dy + (Math.sin(ang) * b).toFloat(), p)
                }
            }
        }
        if (layer.strokeOn) {
            p.colorFilter = PorterDuffColorFilter(layer.strokeColor, PorterDuff.Mode.SRC_IN)
            p.alpha = 255
            val sw = layer.strokeWidth.coerceIn(1, 40).toFloat()
            for (k in 0 until 8) {
                val ang = k * Math.PI / 4.0
                c.drawBitmap(bmp, (Math.cos(ang) * sw).toFloat(), (Math.sin(ang) * sw).toFloat(), p)
            }
        }
        return res
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    fun encodePng(bitmap: Bitmap): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out
    }

    // ---------- 调色区进度（跟着工程一起存） ----------

    private const val ADJUST = "adjust.rvl"

    /** 调色页的状态：手上几张图 + 每层的参数 + 当前选中层 */
    class AdjustProgress(
        val images: List<Bitmap>,
        val params: List<com.reveal.editor.image.ColorAdjust.Params>,
        val visible: List<Boolean>,
        val active: Int,
    )

    fun saveAdjust(context: Context, id: String, progress: AdjustProgress) {
        if (progress.images.isEmpty()) return
        val dir = dir(context, id).apply { mkdirs() }
        val meta = JSONObject()
        meta.put("active", progress.active)
        val vis = JSONArray()
        progress.visible.forEach { vis.put(it) }
        meta.put("visible", vis)
        val arr = JSONArray()
        progress.params.forEach { p ->
            val o = JSONObject()
            o.put("brightness", p.brightness.toDouble())
            o.put("contrast", p.contrast.toDouble())
            o.put("saturation", p.saturation.toDouble())
            o.put("hue", p.hue.toDouble())
            o.put("temperature", p.temperature.toDouble())
            o.put("curve", p.brightnessCurve.toDouble())
            o.put("exposure", p.exposure.toDouble())
            o.put("highlights", p.highlights.toDouble())
            o.put("shadows", p.shadows.toDouble())
            o.put("whites", p.whites.toDouble())
            o.put("blacks", p.blacks.toDouble())
            o.put("tint", p.tint.toDouble())
            o.put("vibrance", p.vibrance.toDouble())
            o.put("clarity", p.clarity.toDouble())
            o.put("dehaze", p.dehaze.toDouble())
            o.put("vignette", p.vignette.toDouble())
            o.put("grain", p.grain.toDouble())
            o.put("sharpening", p.sharpening.toDouble())
            o.put("noiseReduction", p.noiseReduction.toDouble())
            arr.put(o)
        }
        meta.put("params", arr)

        ZipOutputStream(File(dir, ADJUST).outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("adjust.json"))
            zip.write(meta.toString().toByteArray())
            zip.closeEntry()
            progress.images.forEachIndexed { index, bmp ->
                zip.putNextEntry(ZipEntry("adj_$index.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
    }

    fun loadAdjust(context: Context, id: String): AdjustProgress? {
        val file = File(dir(context, id), ADJUST)
        if (!file.exists()) return null
        var meta: JSONObject? = null
        val bitmaps = mutableListOf<Bitmap>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "adjust.json") {
                    meta = runCatching { JSONObject(zip.readBytes().toString(Charsets.UTF_8)) }.getOrNull()
                } else if (name.endsWith(".png")) {
                    val bytes = zip.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        bitmaps.add(bmp.copy(Bitmap.Config.ARGB_8888, true) ?: bmp)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val m = meta ?: return null
        if (bitmaps.isEmpty()) return null
        val arr = m.optJSONArray("params")
        val params = bitmaps.indices.map { i ->
            val o = arr?.optJSONObject(i)
            if (o == null) {
                com.reveal.editor.image.ColorAdjust.Params()
            } else {
                com.reveal.editor.image.ColorAdjust.Params(
                    brightness = o.optDouble("brightness", 0.0).toFloat(),
                    contrast = o.optDouble("contrast", 0.0).toFloat(),
                    saturation = o.optDouble("saturation", 0.0).toFloat(),
                    hue = o.optDouble("hue", 0.0).toFloat(),
                    temperature = o.optDouble("temperature", 0.0).toFloat(),
                    brightnessCurve = o.optDouble("curve", 0.0).toFloat(),
                    exposure = o.optDouble("exposure", 0.0).toFloat(),
                    highlights = o.optDouble("highlights", 0.0).toFloat(),
                    shadows = o.optDouble("shadows", 0.0).toFloat(),
                    whites = o.optDouble("whites", 0.0).toFloat(),
                    blacks = o.optDouble("blacks", 0.0).toFloat(),
                    tint = o.optDouble("tint", 0.0).toFloat(),
                    vibrance = o.optDouble("vibrance", 0.0).toFloat(),
                    clarity = o.optDouble("clarity", 0.0).toFloat(),
                    dehaze = o.optDouble("dehaze", 0.0).toFloat(),
                    vignette = o.optDouble("vignette", 0.0).toFloat(),
                    grain = o.optDouble("grain", 0.0).toFloat(),
                    sharpening = o.optDouble("sharpening", 0.0).toFloat(),
                    noiseReduction = o.optDouble("noiseReduction", 0.0).toFloat(),
                )
            }
        }
        val visArr = m.optJSONArray("visible")
        val visible = bitmaps.indices.map { i -> visArr?.optBoolean(i, true) ?: true }
        val active = m.optInt("active", 0).coerceIn(0, bitmaps.lastIndex)
        return AdjustProgress(bitmaps, params, visible, active)
    }

    fun clearAdjust(context: Context, id: String) {
        File(dir(context, id), ADJUST).delete()
    }
}