package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * PSD / PSB 读写。
 *
 * 读：PSD(v1) 与 PSB(v2)；位深 1/8/16/32；色彩模式 位图/灰度/索引/RGB/CMYK/多通道/双色调/Lab；
 *     通道压缩 raw / RLE / ZIP(含 prediction)。
 * 写：**按原文件的版本、位深、色彩模式、ICC 原样写回**；没改过的图层和合并图，通道数据一字不差搬回去。
 *
 * 参考公开的分层图像文件格式规范（File Formats Specification：Color Mode Data / Image Resources /
 * Layer and Mask Information / Image Data）。
 */
object Psd {

    val BLEND_KEYS = arrayOf(
        "norm", "mul ", "scrn", "over", "sLit", "hLit", "dark", "lite",
        "idiv", "div ", "diff", "smud", "hue ", "sat ", "colr", "lum ",
    )

    const val MODE_BITMAP = 0
    const val MODE_GRAY = 1
    const val MODE_INDEXED = 2
    const val MODE_RGB = 3
    const val MODE_CMYK = 4
    const val MODE_MULTICHANNEL = 7
    const val MODE_DUOTONE = 8
    const val MODE_LAB = 9

    /**
     * 导出前的模式兜底。
     *
     * 索引 / 位图 / 双色调 / 多通道这四种，PSD 里除了通道数据还带调色板、半调参数、
     * 专色通道表；我们手里只有 ARGB 像素，按原模式写回去 桌面端会读到对不上的数据，
     * 打开就是花的（或者干脆报「打开选项不正确」）。所以这四种一律按 RGB 写：
     * 画面一致、桌面端能正常打开，只是色彩模式变了。
     * 位深 1 只在位图模式下合法，跟着一起退成 8。
     */
    internal fun exportModeAndDepth(mode: Int, depth: Int): Pair<Int, Int> {
        val m = when (mode) {
            MODE_INDEXED, MODE_BITMAP, MODE_DUOTONE, MODE_MULTICHANNEL -> MODE_RGB
            else -> mode
        }
        val d = if (m != mode || depth == 1) 8 else depth
        return m to d
    }

    class PsdLayer(
        var name: String,
        var bitmap: Bitmap?,
        var visible: Boolean = true,
        var opacity: Int = 255,
        var blendMode: Int = 0,
        /** 剪贴蒙版：跟下面那层剪在一起 */
        var clipped: Boolean = false,
        /** 阿尔法锁定（桌面端的「锁定透明像素」） */
        var alphaLock: Boolean = false,
    ) {
        /** 图层自身蒙版（有的话），保存时跟通道一起写回 */
        var mask: Bitmap? = null

        /** 文字层信息：有了它，导出到 桌面端里还是能改字的文字层 */
        internal var text: PsdTextInfo? = null

        /** 调整层参数：非 null 表示这是一个调整层，存 PSD 时写 brit / hue2 块 */
        var adjust: AdjustOp? = null

        /** 图层样式（描边 / 投影），导出后到 桌面端里还能继续调 */
        internal var effects: PsdEffects? = null

        /** 位图没在内存里时（分块存储换出）按需装回；导完一层可以由回调再换出去 */
        var bitmapSource: (() -> Bitmap?)? = null

        /** 智能对象：置入的原始图（SoLd + lnk2 里抠出来的），有了它这层就能换内容、反复缩放不糊 */
        var smartSrc: Bitmap? = null

        /** 智能对象在 SoLd 里写的链接 uuid，等文档级 lnk2 读完再按它对号 */
        internal var smartLinkId: String? = null

        /** 从文件读出来的原始记录；改过的图层要置 dirty */
        internal var raw: RawLayer? = null

        /**
         * 从文件读出来的「层头部记录」，已打包成字节，可以跟着工程一起存盘。
         * 属性没被改过时，导出直接把矩形 / 混合 / 标志位 / 附加块整段搬回原始字节——
         * 曲线、曝光、智能对象、图层组、带字体字距的文字这些我们解析不了的块，靠它才能一个不丢。
         */
        internal var rawHead: RawHead? = null

        /** 16 位原始通道数据（Psd.packPix 的字节）。像素没动过就照这些字节写回，保住 16 位精度 */
        internal var rawPix: ByteArray? = null

        /**
         * 16 位原始通道数据（只做「精度来源」用，永远不参与图层像素写盘）。
         * 灰度 / CMYK 没有 16 位编辑缓冲，导出合并图时得靠它把原始 16 位样本捞回来。
         */
        internal var pix16src: ByteArray? = null

    /** 16 位缓冲（画过之后才有）：导出时按这里的三条 16 位通道写，别被 8 位位图截断 */
    internal var pix16: Pix16? = null
    internal var pix32: Pix32? = null
    /** 桌面端原始蒙版通道字节（16 位蒙版不会被截成 8 位）*/
    internal var rawMask: ByteArray? = null
    /** 蒙版被画过，原始通道作废 */
    internal var maskDirty: Boolean = false

        /** 图层组的标记：0=普通层，1=组（展开），2=组（折叠），3=组底分隔层；和 PSD 的 lsct 块一致 */
        var sectionType: Int = 0

        var dirty: Boolean = true
    }

    class Document internal constructor(
        val width: Int,
        val height: Int,
        val layers: List<PsdLayer>,
        val version: Int = 1,
        val depth: Int = 8,
        val colorMode: Int = MODE_RGB,
        val channels: Int = 4,
        val icc: ByteArray? = null,
        internal val rawDoc: RawDoc? = null,
    ) {
        val isPsb: Boolean get() = version == 2
        val modeName: String
            get() = when (colorMode) {
                MODE_BITMAP -> "位图"
                MODE_GRAY -> "灰度"
                MODE_INDEXED -> "索引"
                MODE_RGB -> "RGB"
                MODE_CMYK -> "CMYK"
                MODE_MULTICHANNEL -> "多通道"
                MODE_DUOTONE -> "双色调"
                MODE_LAB -> "Lab"
                else -> "未知"
            }

        /** 保存时有多少层是原封不动的 */
        val untouchedLayers: Int get() = layers.count { !it.dirty && it.raw != null }
    }

    class Probe(
        val width: Int,
        val height: Int,
        val depth: Int,
        val colorMode: Int,
        val version: Int,
        val channels: Int,
        val bytes: Long,
        val layerCount: Int,
    ) {
        /** 估算打开要吃的内存（字节），给“手机扛不扛得住”用 */
        val memEstimate: Long
            get() {
                val px = width.toLong() * height.toLong()
                val perLayer = px * 4L + px * channels.coerceAtLeast(1) * (depth / 8).coerceAtLeast(1)
                return px * 4L + perLayer * layerCount.coerceAtLeast(1)
            }
    }

    internal class RawChannel(val id: Int, val compression: Int, val data: ByteArray)

    /** 鍘熷閫氶亾鏁版嵁鍧楋細16 浣嶆枃浠跺鍏ユ椂鏁存瀛樹笅鏉ワ紝瀵煎嚭鏃跺儚绱犳病鍔ㄨ繃灏卞師鏍峰啓鍥?*/
    internal class PixBlob(val rect: IntArray, val channels: List<RawChannel>)

    /**
     * 鍍忕礌鎸囩汗锛氬鍏ユ椂璁颁竴浠藉瓨杩涘伐绋嬶紝瀵煎嚭鏃跺啀绠椾竴閬嶃€?     * 涓や唤涓€鏍疯鏄庤繖灞備竴涓儚绱犻兘娌¤鐢荤瑪/婊ら暅纰拌繃锛屽彲浠ユ妸鍘熷 16 浣嶉€氶亾瀛楄妭鍘熸牱鎼洖 桌面端锛?     * 涓棿涓嶇粡杩?8 浣嶄綅鍥撅紝绮惧害涓€鐐归兘涓嶄涪銆?     */
    /** 16 位缓冲和当前位图对不对得上（抽样比较，防止滤镜/裁剪等改过位图后缓冲区还是旧像素）*/
    internal fun pix16Matches(p: Pix16?, bmp: Bitmap?): Boolean {
        val d = pix16Diff(p, bmp)
        return d >= 0f && d <= 3f
    }

    /**
     * 16 位缓冲和当前位图的抽样平均差（0..255）；-1 表示没有缓冲或尺寸对不上。
     * 为什么用「平均差」而不是「精确相等」：8 位位图是四舍五入过的，画一笔之后
     * 16 位缓冲必然和它差 1 上下，精确比较会把好缓冲判成坏的、直接退回 8 位。
     */
    internal fun pix16Diff(p: Pix16?, bmp: Bitmap?): Float {
        if (p == null || bmp == null) return -1f
        if (p.width != bmp.width || p.height != bmp.height) return -1f
        val total = p.width * p.height
        if (total <= 0) return -1f
        val step = if (total > 4096) total / 4096 else 1
        var i = 0
        var sum = 0L
        var n = 0
        while (i < total) {
            val x = i % p.width
            val y = i / p.width
            val v = bmp.getPixel(x, y)
            // alpha=0 的像素在 8 位位图里 RGB 被预乘成 0，16 位缓冲里存的是原色 —— 没法比，跳过
            if (((v ushr 24) and 0xFF) == 0) { i += step; continue }
            sum += kotlin.math.abs(((p.r[i].toInt() and 0xFFFF) shr 8) - ((v ushr 16) and 0xFF))
            sum += kotlin.math.abs(((p.g[i].toInt() and 0xFFFF) shr 8) - ((v ushr 8) and 0xFF))
            sum += kotlin.math.abs(((p.b[i].toInt() and 0xFFFF) shr 8) - (v and 0xFF))
            n += 3
            i += step
        }
        return if (n == 0) -1f else (sum.toFloat() / n)
    }

    /** 诊断用：16 位缓冲和位图的差异分布（最差点在哪、差多少、多少点超容差） */
    internal fun pix16Report(p: Pix16?, bmp: Bitmap?): String {
        if (p == null) return "none"
        if (bmp == null) return "nobmp"
        if (p.width != bmp.width || p.height != bmp.height) {
            return "size " + p.width + "x" + p.height + " vs " + bmp.width + "x" + bmp.height
        }
        val total = p.width * p.height
        val step = if (total > 4096) total / 4096 else 1
        var i = 0
        var sum = 0L
        var n = 0
        var worst = 0
        var wx = 0
        var wy = 0
        var over = 0
        while (i < total) {
            val x = i % p.width
            val y = i / p.width
            val v = bmp.getPixel(x, y)
            if (((v ushr 24) and 0xFF) == 0) { i += step; continue }
            val d0 = kotlin.math.abs(((p.r[i].toInt() and 0xFFFF) shr 8) - ((v ushr 16) and 0xFF))
            val d1 = kotlin.math.abs(((p.g[i].toInt() and 0xFFFF) shr 8) - ((v ushr 8) and 0xFF))
            val d2 = kotlin.math.abs(((p.b[i].toInt() and 0xFFFF) shr 8) - (v and 0xFF))
            val mx = maxOf(d0, d1, d2)
            if (mx > 3) over++
            if (mx > worst) { worst = mx; wx = x; wy = y }
            sum += d0 + d1 + d2
            n += 3
            i += step
        }
        if (n == 0) return "empty"
        return "avg=" + (sum.toFloat() / n) + " max=" + worst + " at=" + wx + "," + wy +
            " over=" + over + "/" + (n / 3)
    }

    /** 32 位缓冲和当前位图对不对得上（抽样平均差 ≤3/255 就算对得上） */
    internal fun pix32Matches(p: Pix32?, bmp: Bitmap?): Boolean {
        val d = pix32Diff(p, bmp)
        return d >= 0f && d <= 0.012f
    }

    /** 32 位缓冲与位图的抽样平均差（0..1）；-1 表示没有缓冲或尺寸对不上 */
    internal fun pix32Diff(p: Pix32?, bmp: Bitmap?): Float {
        if (p == null || bmp == null) return -1f
        if (p.width != bmp.width || p.height != bmp.height) return -1f
        val total = p.width * p.height
        if (total <= 0) return -1f
        val step = if (total > 4096) total / 4096 else 1
        var i = 0
        var sum = 0.0
        var n = 0
        while (i < total) {
            val v = bmp.getPixel(i % p.width, i / p.width)
            // alpha=0 的像素在 8 位位图里 RGB 被预乘成 0，浮点缓冲里存的是原色 —— 没法比，跳过
            if (((v ushr 24) and 0xFF) == 0) { i += step; continue }
            sum += kotlin.math.abs(p.r[i] - ((v ushr 16) and 0xFF) / 255f)
            sum += kotlin.math.abs(p.g[i] - ((v ushr 8) and 0xFF) / 255f)
            sum += kotlin.math.abs(p.b[i] - (v and 0xFF) / 255f)
            n += 3
            i += step
        }
        return if (n == 0) -1f else (sum / n).toFloat()
    }

    internal fun bitmapHash(bmp: Bitmap?, mask: Bitmap?): Int {
        if (bmp == null) return 0
        var h = bmp.width * 31 + bmp.height
        val w = bmp.width
        val line = IntArray(w)
        var row = 0
        while (row < bmp.height) {
            bmp.getPixels(line, 0, w, 0, row, w, 1)
            for (v in line) h = h * 31 + v
            row++
        }
        if (mask != null) {
            val ml = IntArray(mask.width)
            var r = 0
            while (r < mask.height) {
                mask.getPixels(ml, 0, mask.width, 0, r, mask.width, 1)
                for (v in ml) h = h * 31 + v
                r++
            }
        }
        return h
    }

    /**
     * 从「原始 16 位通道字节」还原一份 16 位缓冲（打开 16 位 PSD 时用）。
     * 图层只占画布一部分（rect 比位图小）就返回 null，退回按 8 位放大。
     */
    internal fun pix16FromRawPix(raw: ByteArray?, bmp: Bitmap): Pix16? {
        val pb = unpackPix(raw) ?: return null
        val rect = pb.rect
        val rw = rect[3] - rect[1]
        val rh = rect[2] - rect[0]
        if (rw != bmp.width || rh != bmp.height) return null
        if (rw.toLong() * rh > Pix16.MAX_PIXELS) return null
        val byId = HashMap<Int, ByteArray>()
        for (ch in pb.channels) {
            val c = Cur(ch.data)
            val comp = if (c.left() >= 2) c.u16() else ch.compression
            byId[ch.id] = PsdIo.readChannel(c, rw, rh, 16, comp, false, ch.data.size.toLong())
        }
        val planes = ArrayList<ByteArray>(3)
        for (id in intArrayOf(0, 1, 2)) planes.add(byId[id] ?: return null)
        return Pix16.fromPlanes(rw, rh, planes)
    }

    /** 位图的 alpha 摊成 16 位平面（图层本身没有 16 位 alpha，透明度沿用画布那份） */
    /** 32 位用的 alpha 平面：位图的 alpha 摊成浮点 0..1 */
    private fun alphaFloat(bmp: Bitmap?, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        if (bmp == null) {
            var o = 0
            while (o + 3 < out.size) {
                val one = java.lang.Float.floatToIntBits(1f)
                out[o] = (one ushr 24).toByte(); out[o + 1] = (one ushr 16).toByte()
                out[o + 2] = (one ushr 8).toByte(); out[o + 3] = one.toByte()
                o += 4
            }
            return out
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w.coerceAtMost(bmp.width), h.coerceAtMost(bmp.height))
        var o = 0
        for (i in px.indices) {
            val bits = java.lang.Float.floatToIntBits(((px[i] ushr 24) and 0xFF) / 255f)
            out[o++] = (bits ushr 24).toByte(); out[o++] = (bits ushr 16).toByte()
            out[o++] = (bits ushr 8).toByte(); out[o++] = bits.toByte()
        }
        return out
    }

    /** 从导入时留下的原始 32 位通道还原浮点缓冲（尺寸对不上就放弃，改用 8 位放大） */
    internal fun pix32FromRawPix(raw: ByteArray?, bmp: Bitmap): Pix32? {
        val pix = unpackPix(raw) ?: return null
        val rw = pix.rect[3] - pix.rect[1]
        val rh = pix.rect[2] - pix.rect[0]
        if (rw != bmp.width || rh != bmp.height || rw <= 0 || rh <= 0) return null
        val byId = HashMap<Int, ByteArray>(3)
        for (ch in pix.channels) {
            if (ch.id < 0) continue
            val c = Cur(ch.data)
            val comp = if (c.left() >= 2) c.u16() else ch.compression
            val plane = runCatching {
                PsdIo.readChannel(c, rw, rh, 32, comp, false, ch.data.size.toLong())
            }.getOrNull() ?: continue
            byId[ch.id] = plane
        }
        val p0 = byId[0] ?: return null
        val p1 = byId[1] ?: return null
        val p2 = byId[2] ?: return null
        return Pix32.fromPlanes(rw, rh, listOf(p0, p1, p2))
    }

    /**
     * 图像资源里没有内嵌色彩配置（0x040F）就补一份 sRGB。
     * 不补的话 桌面端会用它自己的默认/当前设置解释，手机上看着更艳、桌面端里发灰。
     */
    private fun withSrgbProfile(res: ByteArray): ByteArray {
        var i = 0
        while (i + 12 <= res.size) {
            val sig = String(res, i, 4, Charsets.ISO_8859_1)
            if (sig != "8BIM" && sig != "MeSa" && sig != "AgHg" && sig != "PHUT" && sig != "DCSR") break
            val id = ((res[i + 4].toInt() and 0xFF) shl 8) or (res[i + 5].toInt() and 0xFF)
            var p = i + 6
            val nameLen = res[p].toInt() and 0xFF
            p += 1 + nameLen
            if ((1 + nameLen) % 2 == 1) p += 1
            if (p + 4 > res.size) break
            var size = 0L
            for (k in 0 until 4) size = (size shl 8) or (res[p + k].toLong() and 0xFF)
            p += 4
            if (id == 0x040F) return res
            if (size < 0 || size > res.size) break
            i = p + size.toInt() + (size.toInt() and 1)
        }
        val out = ByteArrayOutputStream(res.size + 32 + SrgbProfile.bytes.size)
        out.write(res, 0, res.size)
        if (res.size % 2 == 1) out.write(0)
        writeAscii(out, "8BIM")
        writeU16(out, 0x040F)
        out.write(0)
        out.write(0)
        writeU32(out, SrgbProfile.bytes.size.toLong())
        out.write(SrgbProfile.bytes, 0, SrgbProfile.bytes.size)
        return out.toByteArray()
    }

    /** 抽样判断位图里有没有非不透明像素（导出时决定能不能用扁平写法）。 */
    private fun hasAlphaPixels(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 64).coerceAtLeast(1)
        val stepY = (bmp.height / 64).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (((bmp.getPixel(x, y) ushr 24) and 0xFF) < 250) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun alpha16(bmp: Bitmap?, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 2)
        if (bmp == null) {
            for (i in 0 until w * h) {
                out[i * 2] = 0xFF.toByte()
                out[i * 2 + 1] = 0xFF.toByte()
            }
            return out
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val v = ((px[i] ushr 24) and 0xFF) * 257
            out[i * 2] = ((v ushr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (v and 0xFF).toByte()
        }
        return out
    }

    internal fun packPix(raw: RawLayer): ByteArray = packPixChannels(raw.rect, raw.channels)

    private fun packPixChannels(rect: IntArray, channels: List<RawChannel>): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
        out.write('R'.code); out.write('W'.code); out.write('P'.code); out.write('1'.code)
        for (v in rect) writeU32(out, v.toLong())
        writeU16(out, channels.size)
        for (ch in channels) {
            writeU16(out, if (ch.id < 0) ch.id + 0x10000 else ch.id)
            writeU16(out, ch.compression)
            writeU32(out, ch.data.size.toLong())
            out.write(ch.data, 0, ch.data.size)
        }
        return out.toByteArray()
    }

    /**
     * 合并过的 PSD（桌面端存的时候没留图层）里，画面只有「合并图」这一段，没有层记录。
     * 16 位数据就在合并图里，不搬出来就永远只能是 8 位。这里把合并图拆成逐通道的原始字节，
     * 形状和 packPix 打出来的袋子一样，挂到假的「背景」层上，导出时原样搬回 桌面端。
     * 只处理不压缩(0) / RLE(1)：桌面端存 16 位常用 ZIP(2/3)，那种先走 8 位路径，不在这里硬解。
     */
    internal fun packCompositePix(doc: Document): ByteArray? {
        if (doc.depth <= 8) return null
        val rc = doc.rawDoc?.composite ?: return null
        val w = doc.width
        val h = doc.height
        if (w <= 0 || h <= 0) return null
        val chans = channelsOf(doc.colorMode, doc.channels)
        val compChans = doc.channels.coerceIn(1, 8)
        val bps = PsdIo.bytesPerSample(doc.depth).coerceAtLeast(1)
        val rowBytes = w * bps
        val raw = rc.data // 含开头 2 字节压缩方式
        if (raw.size < 4) return null
        val comp = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        if (comp < 0 || comp > 3) return null
        val ids = when (doc.colorMode) {
            MODE_RGB -> intArrayOf(0, 1, 2, -1)
            MODE_CMYK -> intArrayOf(0, 1, 2, 3, -1)
            MODE_LAB -> intArrayOf(0, 1, 2, -1)
            else -> intArrayOf(0, -1)
        }
        val channels = ArrayList<RawChannel>(chans)
        if (comp == 0) {
            val plane = rowBytes * h
            for (k in 0 until chans) {
                val from = 2 + k * plane
                if (from + plane > raw.size) return null
                val data = ByteArray(2 + plane)
                data[1] = 0 // 压缩方式 0：原样
                System.arraycopy(raw, from, data, 2, plane)
                channels.add(RawChannel(if (k < ids.size) ids[k] else k, 0, data))
            }
        } else if (comp == 1) {
            val tableLen = compChans * h * 2
            if (2 + tableLen > raw.size) return null
            var dp = 2 + tableLen
            for (k in 0 until chans) {
                var len = 0
                for (r in 0 until h) {
                    val at = 2 + (k * h + r) * 2
                    len += ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)
                }
                if (dp + len > raw.size) return null
                val data = ByteArray(2 + h * 2 + len)
                data[1] = 1 // 压缩方式 1：自己的行表 + 行数据
                System.arraycopy(raw, 2 + k * h * 2, data, 2, h * 2)
                System.arraycopy(raw, dp, data, 2 + h * 2, len)
                channels.add(RawChannel(if (k < ids.size) ids[k] else k, 1, data))
                dp += len
            }
        } else {
            // ZIP(2) / ZIP+预测(3)：整幅合并图是一条 zlib 流，通道×行顺序，行内交织
            val expect = chans * h * rowBytes
            val flat = runCatching { PsdIo.inflateRaw(raw.copyOfRange(2, raw.size), expect) }.getOrNull()
                ?: return null
            if (flat.size < expect) return null
            if (comp == 3) undoZipPredict(flat, chans, h, rowBytes, bps)
            for (k in 0 until chans) {
                val plane = ByteArray(h * rowBytes)
                for (r in 0 until h) {
                    System.arraycopy(flat, (k * h + r) * rowBytes, plane, r * rowBytes, rowBytes)
                }
                channels.add(RawChannel(if (k < ids.size) ids[k] else k, 1, rlePlane(plane, rowBytes, h, doc.isPsb)))
            }
        }
        note16 = "packed compChans=" + compChans + " chans=" + chans
        return packPixChannels(intArrayOf(0, 0, h, w), channels)
    }

    internal fun unpackPix(bytes: ByteArray?): PixBlob? {
        if (bytes == null || bytes.size < 20) return null
        return runCatching {
            val c = Cur(bytes)
            if (c.ascii(4) != "RWP1") return null
            val rect = intArrayOf(c.i32(), c.i32(), c.i32(), c.i32())
            val n = c.u16()
            if (n <= 0 || n > 16) return null
            val chans = ArrayList<RawChannel>(n)
            repeat(n) {
                val id = c.i16()
                val comp = c.u16()
                val len = c.u32().toInt()
                chans.add(RawChannel(id, comp, c.take(len)))
            }
            PixBlob(rect, chans)
        }.getOrNull()
    }

    internal class RawLayer(
        val rect: IntArray, // top, left, bottom, right
        val blendSig: String,
        val blendKey: String,
        val opacity: Int,
        val clipping: Int,
        val flags: Int,
        val filler: Int,
        val extra: ByteArray,
        val channels: List<RawChannel>,
        val maskOffset: Int,
    )

    /**
     * 「原始层头部记录」的可存盘形式：只留能改回 PSD 头部的那些字节（矩形、混合模式、标志位、
     * 通道 id、附加块），不含像素。像素每次导出都从位图重新压，头部按这里的字节原样搬。
     *
     * 打包格式（大端，和 PSD 一致）：
     *   "RWH1" | u32 rect×4 | 4B sig | 4B key | u8 opacity,clipping,flags,filler
     *        | u16 n | u16 id×n | u8 emptyFlag×n | u32 extraLen | extra
     */
    internal class RawHead(
        val rect: IntArray, // top, left, bottom, right
        val sig: String,
        val key: String,
        val opacity: Int,
        val clipping: Int,
        val flags: Int,
        val filler: Int,
        val ids: IntArray,
        /** 对应通道在原文件里是不是「空」的（只有 2 字节压缩方式、没有像素） */
        val empty: BooleanArray,
        val extra: ByteArray,
    ) {

        fun pack(): ByteArray {
            val out = ByteArrayOutputStream(64 + extra.size)
            writeAscii(out, "RWH1")
            for (v in rect) writeU32(out, v.toLong())
            writeAscii(out, sig.padEnd(4, ' ').take(4))
            writeAscii(out, key.padEnd(4, ' ').take(4))
            writeU8(out, opacity)
            writeU8(out, clipping)
            writeU8(out, flags)
            writeU8(out, filler)
            writeU16(out, ids.size)
            for (id in ids) writeU16(out, if (id < 0) id + 0x10000 else id)
            for (e in empty) writeU8(out, if (e) 1 else 0)
            writeU32(out, extra.size.toLong())
            out.write(extra, 0, extra.size)
            return out.toByteArray()
        }

        companion object {
            fun unpack(bytes: ByteArray?): RawHead? {
                if (bytes == null || bytes.size < 24) return null
                return try {
                    val c = Cur(bytes)
                    if (c.ascii(4) != "RWH1") return null
                    val rect = IntArray(4) { c.u32().toInt() }
                    val sig = c.ascii(4)
                    val key = c.ascii(4).trim().padEnd(4, ' ').take(4)
                    val opacity = c.u8()
                    val clipping = c.u8()
                    val flags = c.u8()
                    val filler = c.u8()
                    val n = c.u16()
                    if (n <= 0 || n > 64) return null
                    val ids = IntArray(n) {
                        val v = c.u16()
                        if (v > 0x7FFF) v - 0x10000 else v
                    }
                    val empty = BooleanArray(n) { c.u8() != 0 }
                    val len = c.u32().toInt().coerceIn(0, c.left())
                    RawHead(rect, sig, key, opacity, clipping, flags, filler, ids, empty, c.take(len))
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }

    internal class RawComposite(val target: Int, val compression: Int, val data: ByteArray)

    internal class RawDoc(
        val colorModeData: ByteArray,
        val resources: ByteArray,
        val additional: ByteArray,
        val composite: RawComposite?,
        val icc: ByteArray?,
        val lut: PsdIo.Lut?,
        val palette: IntArray?,
    )

    // ---------------------------------------------------------------- 读

    /** 上一次 open() 失败的原因，给界面显示用（不然用户只看到「打不开」） */
    @Volatile var lastError: String? = null

    /** 上一次解析各阶段耗时，导入后直接弹给用户看 */
    @Volatile
    var lastPhase: String = ""

    /** 解析超过 12 秒时抓一次堆栈，直接告诉我慢在哪一帧 */
    @Volatile
    var slowStack: String = ""

    /** 上一次 16 位合并图为什么没走成（只给诊断日志用） */
    @Volatile internal var note16: String = ""

    /** 超过这个大小就改用内存映射读，避免整份文件躺在 Java 堆里 */
    private const val MAP_THRESHOLD = 24L * 1024 * 1024

    fun open(stream: InputStream): Document? {
        lastError = null
        return try {
            val doc = parse(readAll(stream))
            if (doc == null) lastError = "文件头读不出来（可能是 PSB 或这个格式不支持）"
            doc
        } catch (t: Throwable) {
            android.util.Log.e("RevealPsd", "parse fail: " + android.util.Log.getStackTraceString(t))
            lastError = describeFailure(t)
            null
        }
    }

    /**
     * 打开文件：大文件走内存映射，字节不进 Java 堆。
     * 300MB 级的 PSB 以前光是把整份文件读成数组就爆内存，现在解析过程中堆里不含文件本身。
     */
    fun openFile(file: File): Document? {
        lastError = null
        val len = file.length()
        if (len <= 0L) {
            lastError = "文件是空的"
            return null
        }
        return try {
            if (len <= MAP_THRESHOLD) {
                file.inputStream().use { open(it) }
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    val ch = raf.channel
                    val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, len)
                    val c = Cur(ByteArray(0), mapped)
                    val doc = parseCur(c)
                    if (doc == null) lastError = "文件头读不出来（可能是 PSB 或这个格式不支持）"
                    doc
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("RevealPsd", "openFile fail: " + android.util.Log.getStackTraceString(t))
            lastError = describeFailure(t)
            null
        }
    }

    /**
     * 从流打开：先落到临时文件再按大小决定「整份读进内存」还是「内存映射」。
     * 临时文件解析完就删（映射在 Linux 上删了也还能读，页面已缓存）。
     */
    fun openSmart(stream: InputStream, cacheDir: File): Document? {
        val tmp = File(cacheDir, "psd_open_tmp.bin")
        return try {
            tmp.outputStream().use { out -> stream.copyTo(out, 256 * 1024) }
            openFile(tmp)
        } catch (t: Throwable) {
            android.util.Log.e("RevealPsd", "openSmart fail: " + android.util.Log.getStackTraceString(t))
            lastError = describeFailure(t)
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun describeFailure(t: Throwable): String {
        val mb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when (t) {
            is OutOfMemoryError -> {
                val rt = Runtime.getRuntime()
                val used = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
                "内存不够（上限 " + mb + "MB，失败时已用 " + used + "MB）：这张图太大，先缩小尺寸再打开"
            }
            is NegativeArraySizeException -> "图太大，尺寸算出来溢出了"
            else -> t.javaClass.simpleName + (t.message?.let { ": " + it.take(80) } ?: "")
        }
    }

    fun probe(stream: InputStream): Probe? {
        return try {
            probeBytes(readAll(stream))
        } catch (t: Throwable) {
            android.util.Log.e("RevealPsd", "probe fail: ${t.message}")
            null
        }
    }

    private fun probeBytes(data: ByteArray): Probe? {
        val c = Cur(data)
        val h = PsdIo.header(c) ?: return null
        // 头部之后紧跟着就是「颜色模式数据长度」。以前这里多写了一句 skip(4)，
        // 后面每读一个长度都整体错位 4 字节，层数就解析成 806 / 2052 / 0 这种乱数。
        var layerCount = 0
        try {
            val colorLen = c.u32()
            c.skip(colorLen)
            val resLen = c.u32()
            c.skip(resLen)
            val psb = h.version == 2
            val lmLen = if (psb) c.u64() else c.u32()
            if (lmLen > 0) {
                val liLen = if (psb) c.u64() else c.u32()
                if (liLen > 0) layerCount = kotlin.math.abs(c.i16())
            }
        } catch (t: Throwable) {
            // 头能读出来就够判断内存了
        }
        return Probe(h.width, h.height, h.depth, h.mode, h.version, h.channels, data.size.toLong(), layerCount)
    }

    private fun readAll(stream: InputStream): ByteArray {
        // 大文件按「容量翻倍」扩容：以前每次只加 64KB 就整块复制一遍，
        // 300MB 的 PSB 光复制就要挪几十 GB 的量，手机直接卡死/爆内存。
        val hint = runCatching { stream.available() }.getOrDefault(0)
        var buf = ByteArray((hint + 1).coerceIn(64 * 1024, 8 * 1024 * 1024))
        var off = 0
        while (true) {
            if (off == buf.size) {
                val grow = (buf.size.toLong() * 2).coerceAtMost(Int.MAX_VALUE - 8L).toInt()
                val nb = ByteArray(grow)
                System.arraycopy(buf, 0, nb, 0, off)
                buf = nb
            }
            val n = stream.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return if (off == buf.size) buf else buf.copyOf(off)
    }

    private fun parse(data: ByteArray): Document? = parseCur(Cur(data))

    /** 解析主体：c 可以是内存数组，也可以是映射文件（大 PSB 用这条路） */
    private fun parseCur(c: Cur): Document? {
        val head = PsdIo.header(c) ?: return null
        val psb = head.version == 2
        val width = head.width
        val height = head.height
        val depth = head.depth
        val mode = head.mode
        val psbBps = PsdIo.bytesPerSample(depth)

        // 颜色模式数据（索引色里就是调色板）
        val colorLen = c.u32()
        val colorStart = c.p
        val colorModeData = c.take(colorLen.toInt().coerceIn(0, c.left()))
        var palette: IntArray? = null
        if (mode == MODE_INDEXED && colorModeData.size >= 768) {
            palette = IntArray(256) { i ->
                Color.rgb(
                    colorModeData[i * 3].toInt() and 0xFF,
                    colorModeData[i * 3 + 1].toInt() and 0xFF,
                    colorModeData[i * 3 + 2].toInt() and 0xFF,
                )
            }
        }

        // 图像资源：原样留着，顺便抄出 ICC
        val resLen = c.u32()
        val resources = c.take(resLen.toInt().coerceIn(0, c.left()))
        val icc = findIcc(resources)
        val lut = PsdIo.parseIcc(icc)

        // 图层与蒙版
        val lmLen = if (psb) c.u64() else c.u32()
        val lmEnd = c.p + lmLen.toInt()
        val outLayers = ArrayList<PsdLayer>()
        val outRaw = ArrayList<RawLayer>()
        var layerMs = 0L
        var compMs = 0L
        var composite: RawComposite? = null
        var additional = ByteArray(0)

        if (lmLen > 0) {
            val liLen = if (psb) c.u64() else c.u32()
            val liEnd = c.p + liLen.toInt()
            if (liLen > 0) {
                val tl = System.currentTimeMillis()
                parseLayers(c, psb, width, height, depth, mode, palette, lut, outLayers, outRaw)
                layerMs = System.currentTimeMillis() - tl
                c.p = liEnd.coerceIn(0, c.size)
            }
            // 全局蒙版 + 附加信息
            if (c.p + 4 <= c.size) {
                val globalMask = c.u32()
                c.skip(globalMask)
                val addLen = (lmEnd - c.p).coerceAtLeast(0)
                if (addLen > 0) additional = c.take(addLen.coerceIn(0, c.left()))
            }
            // 16/32 位文档的图层记录不在经典段里，而在附加信息的 Lr16/Lr32 块里（桌面端自己也这么存）
            if (outLayers.isEmpty() && depth != 8) {
                val lr = docBlock(additional, if (depth == 32) "Lr32" else "Lr16", psb)
                if (lr != null && lr.size > 8) {
                    val lc = Cur(lr)
                    parseLayers(lc, psb, width, height, depth, mode, palette, lut, outLayers, outRaw)
                }
            }
            c.p = lmEnd.coerceIn(0, c.size)
        }

        // 置入的智能对象：把 lnk2 里内嵌的原图抠出来挂回各层（对不上 uuid 的层就照旧当普通层）
        if (outLayers.any { it.smartLinkId != null }) {
            val linked = docBlock(additional, "lnk2", psb)?.let { runCatching { PsdSmart.parseLinked(it) }.getOrNull() }
            if (!linked.isNullOrEmpty()) {
                for (l in outLayers) {
                    val id = l.smartLinkId ?: continue
                    val bytes = linked[id] ?: continue
                    l.smartSrc = PsdSmart.decode(bytes)
                }
            }
        }

        // 合并图
        if (c.p + 2 <= c.size) {
            val tc = System.currentTimeMillis()
            composite = parseComposite(c, psb, width, height, depth, mode, palette, lut)
            compMs = System.currentTimeMillis() - tc
        }
        lastPhase = "图层 " + layerMs + "ms / 合并图 " + compMs + "ms / 层数 " + outLayers.size
        if (outLayers.isEmpty() && composite != null) {
            val merged = decodeComposite(Cur(composite.data), psb, width, height, depth, mode, channelsOf(mode, head.channels), palette, lut)
            if (merged != null) {
                val only = PsdLayer("背景", merged, true, 255, 0)
                only.dirty = false
                outLayers.add(only)
            }
        }

        val doc = Document(
            width = width,
            height = height,
            layers = outLayers,
            version = head.version,
            depth = depth,
            colorMode = mode,
            channels = head.channels,
            icc = icc,
            rawDoc = RawDoc(colorModeData, resources, additional, composite, icc, lut, palette),
        )
        if (doc.layers.isEmpty()) return null
        return doc
    }

    private fun findIcc(resources: ByteArray): ByteArray? {
        if (resources.size < 12) return null
        val c = Cur(resources)
        while (c.left() > 12) {
            val sig = c.ascii(2)
            if (sig != "8B") break
            val two = c.ascii(2)
            if (two != "IM" && two != "64") break
            val id = c.u16()
            val nameLen = c.u8()
            val name = c.ascii(nameLen)
            var pad = (nameLen + 1) % 2
            if (pad != 0) c.skip(pad.toLong())
            val size = c.u32()
            val payload = c.take(size.toInt().coerceIn(0, c.left()))
            if (id == 1039) return payload
            if (payload.size % 2 == 1) c.skip(1)
        }
        return null
    }

    private fun parseLayers(
        c: Cur,
        psb: Boolean,
        width: Int,
        height: Int,
        depth: Int,
        mode: Int,
        palette: IntArray?,
        lut: PsdIo.Lut?,
        outLayers: MutableList<PsdLayer>,
        outRaw: MutableList<RawLayer>,
    ) {
        val countRaw = c.i16()
        val count = kotlin.math.abs(countRaw)
        if (count <= 0 || count > 5000) return
        val records = ArrayList<Triple<RawLayer, IntArray, Int>>(count)
        val chans = ArrayList<List<Pair<Int, Long>>>(count)
        for (i in 0 until count) {
            if (c.left() < 40) return
            val top = c.i32()
            val left = c.i32()
            val bottom = c.i32()
            val right = c.i32()
            val chCount = c.u16()
            val list = ArrayList<Pair<Int, Long>>(chCount)
            repeat(chCount) {
                val id = c.i16()
                val len = if (psb) c.u64() else c.u32()
                list.add(id to len)
            }
            val blendSig = c.ascii(4)
            val blendKey = c.ascii(4)
            val opacity = c.u8()
            val clipping = c.u8()
            val flags = c.u8()
            val filler = c.u8()
            val extraLen = c.u32()
            val extra = c.take(extraLen.toInt().coerceIn(0, c.left()))
            val raw = RawLayer(
                rect = intArrayOf(top, left, bottom, right),
                blendSig = blendSig,
                blendKey = blendKey,
                opacity = opacity,
                clipping = clipping,
                flags = flags,
                filler = filler,
                extra = extra,
                channels = emptyList(),
                maskOffset = 0,
            )
            records.add(Triple(raw, intArrayOf(top, left, bottom, right), i))
            chans.add(list)
        }

        for (i in 0 until count) {
            val tl1 = System.currentTimeMillis()
            val (rawHead, rect, _) = records[i]
            val list = chans[i]
            val w = (rect[3] - rect[1]).coerceAtLeast(1)
            val h = (rect[2] - rect[0]).coerceAtLeast(1)
            // 8 位 RGB 快路径：边解边填 argb，不再为每个通道各留一份 packed 字节平面。
            // 4096 的图四通道会产生 4×12.6MB 的临时数组，配上 argb/位图很容易把堆顶到上限，
            // 到时候不是算得慢，是一直在 GC（实测同一份解析时间翻几倍）。
            val nPx = w * h
            val fastArgb = if (depth == 8 && mode == MODE_RGB && list.all { it.first == -1 || it.first in 0..2 }) {
                IntArray(nPx) { 0xFF000000.toInt() }
            } else null
            val planes = HashMap<Int, ByteArray>(4)
            val rawChans = ArrayList<RawChannel>(list.size)
            for ((id, len) in list) {
                val startAt = c.p
                val comp = if (c.left() >= 2) c.u16() else 0
                val planeBytes = PsdIo.readChannel(c, w, h, depth, comp, psb, len)
                rawChans.add(RawChannel(id, comp, c.copyRange(startAt, c.p.coerceAtMost(c.size))))
                // 直接降成字节平面：先摊成 IntArray 再截字节，2.68M 像素的图上单通道就多占 10MB，
                // 四通道 16 位文件（比如 桌面端导的扁平渐变）光这一下就 40MB，正是「内存不够」的来源。
                if (fastArgb != null) {
                    val sh = when (id) {
                        -1 -> 24
                        0 -> 16
                        1 -> 8
                        else -> 0
                    }
                    if (sh == 24) {
                        for (k in 0 until nPx) {
                            fastArgb[k] = (fastArgb[k] and 0x00FFFFFF) or ((planeBytes[k].toInt() and 0xFF) shl 24)
                        }
                    } else {
                        val mask = (0xFF shl sh).inv()
                        for (k in 0 until nPx) {
                            fastArgb[k] = (fastArgb[k] and mask) or ((planeBytes[k].toInt() and 0xFF) shl sh)
                        }
                    }
                    continue
                }
                val packed = ByteArray(w * h)
                when (depth) {
                    16 -> for (k in packed.indices) {
                        val hi = planeBytes.getOrElse(k * 2) { 0 }.toInt() and 0xFF
                        val lo = planeBytes.getOrElse(k * 2 + 1) { 0 }.toInt() and 0xFF
                        // 和 PsdIo.samples 一样四舍五入，别整体偏暗半级
                        packed[k] = ((((hi shl 8) or lo) * 255 + 32767) / 65535).toByte()
                    }
                    32 -> for (k in packed.indices) {
                        var bits = 0
                        for (j in 0 until 4) {
                            bits = (bits shl 8) or (planeBytes.getOrElse(k * 4 + j) { 0 }.toInt() and 0xFF)
                        }
                        val f = Float.fromBits(bits).coerceIn(0f, 1f)
                        // 颜色通道是线性光，位图要 sRGB 编码值；alpha/蒙版（id<0）本来就不是颜色
                        val enc = if (id >= 0) Pix32.toSrgb(f) else f
                        packed[k] = (enc * 255f + 0.5f).toInt().toByte()
                    }
                    1 -> {
                        val bits = PsdIo.unpackBits(planeBytes, w, h)
                        for (k in packed.indices) packed[k] = bits[k].toByte()
                    }
                    else -> System.arraycopy(planeBytes, 0, packed, 0, minOf(packed.size, planeBytes.size))
                }
                planes[id] = packed
            }
            val argb = fastArgb ?: buildArgb(planes, w * h, mode, palette, lut)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(argb, 0, w, 0, 0, w, h)
            android.util.Log.i("RevealPsd", "layer " + i + " " + w + "x" + h + " ch=" + list.size + " " + (System.currentTimeMillis() - tl1) + "ms")
            val full = if (rect[1] == 0 && rect[0] == 0 && w == width && h == height) {
                bmp
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(bmp, rect[1].toFloat(), rect[0].toFloat(), null)
                    bmp.recycle()
                }
            }
            val blend = BLEND_KEYS.indexOf(rawHead.blendKey).let { if (it < 0) 0 else it }
            val layer = PsdLayer(
                name = readName(rawHead.extra),
                bitmap = full,
                visible = (rawHead.flags and 0x02) == 0,
                opacity = rawHead.opacity,
                blendMode = blend,
                clipped = rawHead.clipping > 0,
                alphaLock = (rawHead.flags and 0x01) != 0,
            )
            layer.raw = RawLayer(
                rect = rawHead.rect,
                blendSig = rawHead.blendSig,
                blendKey = rawHead.blendKey,
                opacity = rawHead.opacity,
                clipping = rawHead.clipping,
                flags = rawHead.flags,
                filler = rawHead.filler,
                extra = rawHead.extra,
                channels = rawChans,
                maskOffset = 0,
            )
            layer.sectionType = readSectionType(rawHead.extra)
            // 组头 / 组折叠的混合模式：lsct 里那份备份是 桌面端判断组的依据，优先用它
            if (layer.sectionType == 1 || layer.sectionType == 2) {
                val sb = readSectionBlend(rawHead.extra)
                if (sb >= 0) layer.blendMode = sb
            }
            findBlock(rawHead.extra, "TySh")?.let { layer.text = PsdText.decodeTySh(it) }
            // 图层样式也读回来：导出过 lfx2 的 PSD，再导入时描边/投影/发光这些不能丢
            findBlock(rawHead.extra, "lfx2")?.let { layer.effects = PsdFx.decode(it) }
            // 置入的智能对象：先记下链接 uuid，文件字节在文档级 lnk2 里，等那边读完再对号
            findBlock(rawHead.extra, "SoLd")?.let { raw -> layer.smartLinkId = PsdSmart.parsePlaced(raw)?.linkId }
            for (adjKey in arrayOf("brit", "hue2", "levl")) {
                val adjData = findBlock(rawHead.extra, adjKey) ?: continue
                layer.adjust = AdjustOp.decode(adjKey, adjData)
                break
            }
            val maskPlane = planes[-2]
            layer.rawMask = rawChans.firstOrNull { it.id == -2 }?.data

            if (maskPlane != null) {
                val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val mp = IntArray(w * h)
                for (i in mp.indices) {
                    val mv = (maskPlane[i].toInt() and 0xFF)
                    mp[i] = (mv shl 24) or 0x00FFFFFF
                }
                mb.setPixels(mp, 0, w, 0, 0, w, h)
                layer.mask = if (rect[1] == 0 && rect[0] == 0 && w == width && h == height) mb else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(mb, rect[1].toFloat(), rect[0].toFloat(), null)
                    mb.recycle()
                }
            }
            layer.dirty = false
            outLayers.add(layer)
            outRaw.add(layer.raw!!)
        }
    }

    /**
     * lsct：桌面端用这个块表示「这是组头（1 展开 / 2 折叠）或组底分隔层（3）」。
     * 没有这个块就是普通图层。
     */
    private fun readSectionType(extra: ByteArray): Int {
        val d = findBlock(extra, "lsct") ?: return 0
        if (d.size < 4) return 0
        val v = ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or
            ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        return if (v in 1..3) v else 0
    }

    /**
     * lsct：4 字节类型（1 组展开 / 2 组折叠 / 3 组底分隔层）。
     * 类型 1/2 时 桌面端后面还会跟一份「组混合模式」的备份：'8BIM' + 4 字节混合模式键 + 4 字节 subtype。
     * 只写 4 字节的话，App 里新建的组到 桌面端里会退回「正常」模式——那份备份没写，桌面端就按默认理解。
     */
    /**
     * lsct 里紧跟类型的那份「组混合模式备份」：'8BIM' + 4 字节混合模式键（16 字节块才有）。
     * 返回 -1 表示块里没写 —— 那就沿图层记录里的混合模式。
     */
    private fun readSectionBlend(extra: ByteArray): Int {
        val d = findBlock(extra, "lsct") ?: return -1
        if (d.size < 12) return -1
        if (d[4] != 0x38.toByte() || d[5] != 0x42.toByte() || d[6] != 0x49.toByte() || d[7] != 0x4D.toByte()) {
            return -1
        }
        val key = String(byteArrayOf(d[8], d[9], d[10], d[11]), Charsets.US_ASCII)
        val i = BLEND_KEYS.indexOf(key)
        return if (i < 0) -1 else i
    }

    private fun lsctBytes(type: Int, blendKey: String = "norm", subType: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(16)
        writeU32(out, type.toLong())
        if (type == 1 || type == 2) {
            writeAscii(out, "8BIM")
            writeAscii(out, if (blendKey.length == 4) blendKey else "norm")
            writeU32(out, subType.toLong())
        }
        return out.toByteArray()
    }

    private fun readName(extra: ByteArray): String {
        // extra: maskLen(4)+mask, rangesLen(4)+ranges, name(pascal, pad4), blocks
        val c = Cur(extra)
        if (c.left() < 4) return ""
        c.skip(c.u32())
        if (c.left() < 4) return ""
        c.skip(c.u32())
        if (c.left() < 1) return ""
        val n = c.u8()
        val legacy = if (c.left() >= n) c.ascii(n) else ""
        // 中文等 Unicode 层名只存在 luni 块里，Pascal 那段会是 "?"，必须优先 luni
        val idx = indexOfLuni(extra)
        if (idx >= 0) {
            val uni = readLuni(extra, idx)
            if (uni.isNotBlank()) return uni
        }
        return legacy
    }

    private fun indexOfLuni(extra: ByteArray): Int {
        for (i in 0..extra.size - 4) {
            if (extra[i] == 'l'.code.toByte() && extra[i + 1] == 'u'.code.toByte() &&
                extra[i + 2] == 'n'.code.toByte() && extra[i + 3] == 'i'.code.toByte()
            ) return i
        }
        return -1
    }

    private fun readLuni(extra: ByteArray, at: Int): String {
        val c = Cur(extra)
        // at 指向 "luni" 里的 l：at+4 是块长度，at+8 才是「字数」
        // （早先当成 at+4 直接读字数，读到的是块长度，于是永远退回 Pascal 的 GBK 字节）
        c.p = at + 8
        if (c.left() < 4) return ""
        val declared = c.u32()
        if (declared >= 0 && declared * 2 <= c.left().toLong()) {
            val n = declared.toInt()
            val sb = StringBuilder(n)
            for (i in 0 until n) {
                if (c.left() < 2) break
                sb.append(c.u16().toChar())
            }
            return sb.toString()
        }
        // 老版本自己写的文件：u16 字数 + 字符
        c.p = at + 8
        if (c.left() < 2) return ""
        val n = c.u16()
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            if (c.left() < 2) break
            sb.append(c.u16().toChar())
        }
        return sb.toString()
    }

    // 名字的旧字段（pascal）按 桌面端的做法写系统编码（GBK），实在不行退回拉丁
    private fun gbkBytes(s: String): ByteArray = runCatching {
        s.toByteArray(java.nio.charset.Charset.forName("GBK"))
    }.getOrElse { s.toByteArray(Charsets.ISO_8859_1) }

    private fun parseComposite(
        c: Cur,
        psb: Boolean,
        width: Int,
        height: Int,
        depth: Int,
        mode: Int,
        palette: IntArray?,
        lut: PsdIo.Lut?,
    ): RawComposite? {
        val start = c.p
        val comp = c.u16()
        // 读完重建一遍像素用于显示？这里只把原始字节存起来，显示交给调用方（合并图只在没图层时用）
        c.p = start
        // 记录整段原始字节，保存时原样写回
        val data = c.take((c.size - start).coerceAtLeast(0))
        return RawComposite(mode, comp, data)
    }

    private fun channelsOf(mode: Int, headerChannels: Int): Int = when (mode) {
        // 文件头里多出来的那个通道就是 alpha（以前一律按 3 通道处理，透明区会被当不透明）
        MODE_RGB -> if (headerChannels >= 4) 4 else 3
        MODE_CMYK -> if (headerChannels >= 5) 5 else 4
        MODE_LAB -> if (headerChannels >= 4) 4 else 3
        MODE_GRAY, MODE_INDEXED, MODE_BITMAP, MODE_DUOTONE -> if (headerChannels >= 2) 2 else 1
        else -> headerChannels.coerceIn(1, 8)
    }

    /** 解合并图（含 compression 字段）→ 位图；alpha 通道不参与也要能读 */
    private fun decodeComposite(
        c: Cur,
        psb: Boolean,
        width: Int,
        height: Int,
        depth: Int,
        mode: Int,
        chans: Int,
        palette: IntArray?,
        lut: PsdIo.Lut?,
    ): Bitmap? {
        if (c.left() < 2) return null
        val comp = c.u16()
        val planes = LinkedHashMap<Int, ByteArray>(chans)
        var alphaAt = -1
        val ids = when (mode) {
            MODE_RGB -> intArrayOf(0, 1, 2)
            MODE_CMYK -> intArrayOf(0, 1, 2, 3)
            MODE_LAB -> intArrayOf(0, 1, 2)
            else -> intArrayOf(0)
        }
        // 合并图的行长表是「通道 × 行」一次性写在前面的（桌面端的规矩）；图层通道才是每个通道自带一份。
        // 按每通道一份去解合并图，第二个通道起就整体错位、行长度读成几千万，直接越界崩。
        val bps = PsdIo.bytesPerSample(depth).coerceAtLeast(1)
        val rowBytes = width * bps
        if (comp == 2 || comp == 3) {
            // ZIP / ZIP+预测：整幅合并图是**一条** zlib 流，按「通道 × 行」排列、行内交织。
            // 以前这里按每通道一条流去读（readChannel），payloadLen 传 Long.MAX_VALUE 被 toInt 成负数，
            // 直接抛 NegativeArraySizeException（界面显示「图太大，尺寸算出来溢出了」）。
            val expect = chans * rowBytes * height
            val flat = runCatching { PsdIo.inflateRaw(c.take(c.left()), expect) }.getOrNull()
            if (flat == null || flat.size < expect) return null
            if (comp == 3) undoZipPredict(flat, chans, height, rowBytes, bps)
            for (i in 0 until chans) {
                val plane = ByteArray(rowBytes * height)
                for (r in 0 until height) {
                    System.arraycopy(flat, (i * height + r) * rowBytes, plane, r * rowBytes, rowBytes)
                }
                val vals = PsdIo.samples(plane, width * height, depth, srgb = i < ids.size)
                val packed = ByteArray(vals.size)
                for (k in vals.indices) packed[k] = vals[k].toByte()
                if (i < ids.size) planes[ids[i]] = packed else planes[-1] = packed
            }
        } else {
            val counts = if (comp == 1) IntArray(chans * height) { if (c.left() >= 2) c.u16() else 0 } else null
            for (i in 0 until chans) {
                val vals = when {
                    depth == 1 -> PsdIo.unpackBits(c.take((width + 7) / 8 * height), width, height)
                    comp == 1 -> PsdIo.samples(
                        PsdIo.readRleRows(c, counts!!, i * height, height, rowBytes),
                        width * height,
                        depth,
                        srgb = i < ids.size,
                    )
                    else -> PsdIo.samples(
                        PsdIo.readChannel(c, width, height, depth, comp, psb, Long.MAX_VALUE),
                        width * height,
                        depth,
                        srgb = i < ids.size,
                    )
                }
                // 合成图也存字节平面：16 位大图的 IntArray 平面太占内存
                val packed = ByteArray(vals.size)
                for (k in vals.indices) packed[k] = vals[k].toByte()
                if (i < ids.size) planes[ids[i]] = packed else planes[-1] = packed
            }
        }
        val argb = buildArgb(planes, width * height, mode, palette, lut)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun buildArgb(planes: Map<Int, ByteArray>, n: Int, mode: Int, palette: IntArray?, lut: PsdIo.Lut?): IntArray {
        val out = IntArray(n)
        fun v(id: Int, i: Int, dflt: Int): Int {
            val p = planes[id] ?: return dflt
            if (i >= p.size) return dflt
            return p[i].toInt() and 0xFF
        }
        when (mode) {
            MODE_RGB -> {
                for (i in 0 until n) out[i] = Color.argb(v(-1, i, 255), v(0, i, 0), v(1, i, 0), v(2, i, 0))
            }
            MODE_GRAY, MODE_DUOTONE, MODE_MULTICHANNEL -> {
                for (i in 0 until n) {
                    val y = v(0, i, v(1, i, 0))
                    out[i] = Color.argb(v(-1, i, 255), y, y, y)
                }
            }
            MODE_CMYK -> {
                for (i in 0 until n) {
                    val rgb = PsdIo.cmykToRgb(lut, v(0, i, 0), v(1, i, 0), v(2, i, 0), v(3, i, 0))
                    out[i] = Color.argb(v(-1, i, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
                }
            }
            MODE_INDEXED -> {
                for (i in 0 until n) {
                    val col = palette?.getOrNull(v(0, i, 0)) ?: Color.BLACK
                    out[i] = Color.argb(v(-1, i, 255), Color.red(col), Color.green(col), Color.blue(col))
                }
            }
            MODE_LAB -> {
                for (i in 0 until n) {
                    val l = v(0, i, 0) * 100f / 255f
                    val a = v(1, i, 128) - 128f
                    val b = v(2, i, 128) - 128f
                    val rgb = labToRgb(l, a, b)
                    out[i] = Color.argb(v(-1, i, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
                }
            }
            MODE_BITMAP -> {
                for (i in 0 until n) {
                    val y = v(0, i, 0)
                    out[i] = Color.argb(255, y, y, y)
                }
            }
            else -> for (i in 0 until n) out[i] = Color.BLACK
        }
        return out
    }

    private fun labToRgb(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        fun finv(t: Float): Float = if (t > 6f / 29f) t * t * t else 3f * (6f / 29f) * (6f / 29f) * (t - 4f / 29f)
        val x = 0.95047f * finv(fx)
        val y = 1.00000f * finv(fy)
        val z = 1.08883f * finv(fz)
        var r = x * 3.2406f + y * -1.5372f + z * -0.4986f
        var g = x * -0.9689f + y * 1.8758f + z * 0.0415f
        var bl = x * 0.0557f + y * -0.2040f + z * 1.0570f
        fun gamma(v: Float): Float = if (v <= 0.0031308f) 12.92f * v else 1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f
        r = gamma(r.coerceIn(0f, 1f))
        g = gamma(g.coerceIn(0f, 1f))
        bl = gamma(bl.coerceIn(0f, 1f))
        return Color.rgb((r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255), (bl * 255).toInt().coerceIn(0, 255))
    }

    // ---------------------------------------------------------------- 写

    /** 旧签名：8 位 RGB 的 PSD（给新建工程用） */
    fun encode(width: Int, height: Int, layers: List<PsdLayer>): ByteArray {
        val doc = Document(width, height, layers)
        return encode(doc)
    }

    /** 按文档原本的版本 / 位深 / 色彩模式 / ICC 写回；没改过的层与合并图原样搬回 */
    fun encode(doc: Document, onLayerWritten: ((PsdLayer) -> Unit)? = null): ByteArray {
        val psb = doc.isPsb
        val depth = doc.depth
        val mode = doc.colorMode
        val width = doc.width
        val height = doc.height
        val channels = when (mode) {
            MODE_RGB -> 4
            MODE_CMYK -> 5
            MODE_GRAY, MODE_BITMAP, MODE_DUOTONE -> 2
            MODE_INDEXED -> 2
            MODE_LAB -> 4
            else -> doc.channels.coerceAtLeast(4)
        }
        val bos = ByteArrayOutputStream(1 shl 20)
        writeAscii(bos, "8BPS")
        writeU16(bos, if (psb) 2 else 1)
        repeat(3) { writeU16(bos, 0) }
        writeU16(bos, channels)
        writeU32(bos, height.toLong())
        writeU32(bos, width.toLong())
        writeU16(bos, depth)
        writeU16(bos, mode)

        // 颜色模式数据（索引色调色板）
        val movedCm = doc.rawDoc?.colorModeData
        // 32 位：原文件没带（或本来就没有）这段 HDR 块时补上，否则 桌面端不认
        val colorData = if (depth == 32 && (movedCm == null || movedCm.isEmpty())) HDR_CM_32 else (movedCm ?: ByteArray(0))
        writeU32(bos, colorData.size.toLong())
        bos.write(colorData, 0, colorData.size)

        // 图像资源：原文件有的原样搬回；新建的文档补上 桌面端期望的最小必备块
        val resources = (doc.rawDoc?.resources?.takeIf { it.isNotEmpty() } ?: defaultResources()).let { withSrgbProfile(it) }
        writeU32(bos, resources.size.toLong())
        bos.write(resources, 0, resources.size)

        // 只有一层、而且这一层一个像素都没动过（还带着原始 16 位通道）：图层段干脆不写。
        // 桌面端自己保存这种单层文件也是不写图层的，重开时按合并图还原成一层「背景」，内容完全一样；
        // 好处是 16 位文件少存一份图层通道，体积直接省一半（16 位原样一份就够大了）。
        // 画过的层也一样：单层、满画布、普通混合、不透明、无蒙版/调整/文字/样式/选区，
        // 那图层通道和合并图其实是同一份像素，写两份纯属浪费体积，只写合并图。
        /* 有透明像素的文档不能走「只写合并图」的扁平写法：PSD 的透明只存在图层通道（id=-1）里，
           不写图层段时桌面端一律当不透明背景打开，用户看不到透明洞。 */
        val alphaProbe = doc.layers.singleOrNull()?.let { l -> l.bitmap ?: l.bitmapSource?.invoke() }
        val hasTransparency = alphaProbe != null && hasAlphaPixels(alphaProbe)
        val flatOnly = !hasTransparency && doc.layers.size == 1 && doc.layers[0].let { l ->
            val full = l.pix16?.let { it.width == doc.width && it.height == doc.height } ?: false
            val full32 = l.pix32?.let { it.width == doc.width && it.height == doc.height } ?: false
            (l.rawPix != null || full || full32) && l.raw == null && l.adjust == null && l.text == null &&
                l.mask == null && !l.clipped && l.visible &&
                l.opacity == 255 && l.blendMode == 0 && l.sectionType == 0 &&
                // 导出时 effects 总是被塞成对象，所以这里认「描边和投影都没开」，不认 null
                (l.effects == null || (!l.effects!!.strokeOn && !l.effects!!.shadowOn))
        }
        // 图层信息
        val layerInfo = ByteArrayOutputStream(1 shl 20)
        writeU16(layerInfo, doc.layers.size)
        val planned = ArrayList<Planned>(doc.layers.size)
        if (!flatOnly) for (l in doc.layers) {
            val raw = l.raw
            val head = l.rawHead
            val useRaw = raw != null && !l.dirty
        /** 16 位原始通道数据（Psd.packPix 的字节）。像素没动过就照这些字节写回，保住 16 位精度 */
            val pix = if (!useRaw) l.rawPix?.let { unpackPix(it) } else null
            // 扁平 PSD 的假「背景」层压根没有层记录（head==null），但像素字节照样能原样搬
            val usePix = pix != null && l.adjust == null && l.text == null
            // 存过「原始头部记录」、且这一层的属性没被改过 → 头部照原始字节搬，不认识的块一个不丢
            val useHead = !useRaw && head != null && !headTouched(head, l)
            // 调整层在 桌面端里是一条「空层」：没有像素，四条通道长度都只有 2（只放压缩方式）
            val emptyAdjust = !useRaw && !useHead && l.adjust != null && l.text == null
            val rect = when {
                useRaw -> raw!!.rect
                usePix -> pix!!.rect
                // 原始通道全是空的（调整层 / 图层组分隔层 / 文字层）→ 矩形也照原样搬；
                // 有像素的层统一按整幅画布写，免得用户在图外补画的笔迹被裁掉
                useHead -> if (head!!.empty.all { it }) head.rect else intArrayOf(0, 0, height, width)
                emptyAdjust -> intArrayOf(0, 0, 0, 0)
                else -> raw?.rect ?: intArrayOf(0, 0, height, width)
            }
            writeU32(layerInfo, rect[0].toLong())
            writeU32(layerInfo, rect[1].toLong())
            writeU32(layerInfo, rect[2].toLong())
            writeU32(layerInfo, rect[3].toLong())
            // 调整层在 桌面端里只有一条 -1（阿尔法）通道、没有 RGB 像素通道；写成 RGB 会让 桌面端判文件损坏
            val baseIds = when {
                useRaw -> raw!!.channels.map { it.id }
                usePix -> pix!!.channels.map { it.id }
                useHead -> head!!.ids.toList()
                emptyAdjust -> listOf(-1, 0, 1, 2)
                else -> channelIds(doc)
            }
            val ids0 = if (!useRaw && !useHead && !emptyAdjust && l.mask != null) baseIds + listOf(-2) else baseIds
            // 原文件本来没有 alpha（3 通道），但画布上确实有透明像素 → 补一条 -1，否则 桌面端里不透明
            val ids = if (hasTransparency && !useRaw && !emptyAdjust && !useHead && !ids0.contains(-1)) ids0 + listOf(-1) else ids0
            writeU16(layerInfo, ids.size)
            val positions = IntArray(ids.size)
            for ((k, id) in ids.withIndex()) {
                writeU16(layerInfo, if (id < 0) id + 0x10000 else id)
                positions[k] = layerInfo.size()
                if (psb) writeU64(layerInfo, 0L) else writeU32(layerInfo, 0L)
            }
            val key = when {
                useRaw -> raw!!.blendKey
                useHead -> head!!.key
                else -> BLEND_KEYS.getOrElse(l.blendMode) { "norm" }
            }
            val sig = when {
                useRaw -> raw!!.blendSig
                useHead -> head!!.sig
                else -> "8BIM"
            }
            writeAscii(layerInfo, sig.padEnd(4, ' ').take(4))
            writeAscii(layerInfo, key.padEnd(4, ' ').take(4))
            val opacityByte = when {
                useRaw -> raw!!.opacity
                useHead -> head!!.opacity
                // 桌面端 的「不透明度」是按百分比存的：178 会被显示成 69%，179 才是 70%。
                // 所以导出前先归到整数百分比，再换算成字节，保证在 桌面端里读到的百分比和我们界面一致。
                else -> {
                    val pct = Math.round(l.opacity.coerceIn(0, 255) * 100f / 255f)
                    Math.round(pct * 2.55f).coerceIn(0, 255)
                }
            }
            writeU8(layerInfo, opacityByte)
            val clipByte = when {
                useRaw -> raw!!.clipping
                useHead -> head!!.clipping
                else -> if (l.clipped) 1 else 0
            }
            writeU8(layerInfo, clipByte)
            var flagByte = when {
                useRaw -> raw!!.flags
                useHead -> head!!.flags
                else -> (if (l.visible) 0 else 0x02) or 0x08
            }
            if (!useRaw && !useHead) {
                // bit0：锁定透明像素；bit4：像素数据与画面无关（调整层 / 文字层）
                if (l.alphaLock) flagByte = flagByte or 0x01
                if (l.adjust != null || l.text != null) flagByte = flagByte or 0x10
            }
            writeU8(layerInfo, flagByte)
            writeU8(layerInfo, if (useRaw) raw!!.filler else if (useHead) head!!.filler else 0)
            val extra = when {
                useRaw -> raw!!.extra
                usePix -> if (head != null) extraWithName(head, l.name, l.sectionType, l.blendMode) else buildExtra(l, rect)
                useHead -> extraWithName(head!!, l.name, l.sectionType, l.blendMode)
                else -> buildExtra(l, rect)
            }
            writeU32(layerInfo, extra.size.toLong())
            layerInfo.write(extra, 0, extra.size)
            planned.add(Planned(rect, ids, useRaw, raw, l, positions, useHead, head, if (usePix) pix else null, if (depth == 16) l.pix16 else null, if (depth == 32) l.pix32 else null))
        }
        // 逐通道写、写完立刻回填长度：不再把所有通道的数据同时留在内存里
        for (p in planned) {
            if (p.useRaw) {
                val chans = p.raw!!.channels
                val rw = (p.raw!!.rect[3] - p.raw!!.rect[1]).coerceAtLeast(1)
                val rh = (p.raw!!.rect[2] - p.raw!!.rect[0]).coerceAtLeast(1)
                for ((k, ch) in chans.withIndex()) {
                    if (k >= p.positions.size) break
                    val start = layerInfo.size()
                    val bytes = channelBytes(ch.data, ch.compression, rw, rh, depth, psb)
                    layerInfo.write(bytes, 0, bytes.size)
                    patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                }
            } else {
                val w = (p.rect[3] - p.rect[1]).coerceAtLeast(1)
                val h = (p.rect[2] - p.rect[0]).coerceAtLeast(1)
                if (p.p32 != null) {
                    // 画过的 32 位层：三条通道按浮点缓冲写 RLE；alpha 沿用位图
                    val srcBmp32 = p.layer.bitmap ?: p.layer.bitmapSource?.invoke()
                    val planes32 = p.p32.planes()
                    val ap32 = if (p.ids.contains(-1)) alphaFloat(srcBmp32, w, h) else null
                    for ((k, id) in p.ids.withIndex()) {
                        if (k >= p.positions.size) break
                        val start = layerInfo.size()
                        val plane = when (id) {
                            -1 -> ap32
                            0 -> planes32[0]
                            1 -> planes32[1]
                            2 -> planes32[2]
                            else -> null
                        }
                        if (plane == null) {
                            writeU16(layerInfo, 0)
                        } else {
                            val bytes = rlePlane(plane, w * 4, h, psb)
                            layerInfo.write(bytes, 0, bytes.size)
                        }
                        patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                    }
                    onLayerWritten?.invoke(p.layer)
                    continue
                }
                if (p.p16 != null) {
                    // 画过的 16 位层：三条通道按 16 位缓冲写 RLE；alpha 沿用位图（8 位放大）
                    val srcBmp = p.layer.bitmap ?: p.layer.bitmapSource?.invoke()
                    val planes16 = p.p16.planes()
                    val ap = if (p.ids.contains(-1)) alpha16(srcBmp, w, h) else null
                    for ((k, id) in p.ids.withIndex()) {
                        if (k >= p.positions.size) break
                        val start = layerInfo.size()
                        val plane = when (id) {
                            -1 -> ap
                            0 -> planes16[0]
                            1 -> planes16[1]
                            2 -> planes16[2]
                            else -> null
                        }
                        if (plane == null) {
                            writeU16(layerInfo, 0)
                        } else {
                            val bytes = rlePlane(plane, w * 2, h, psb)
                            layerInfo.write(bytes, 0, bytes.size)
                        }
                        patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                    }
                    onLayerWritten?.invoke(p.layer)
                    continue
                }
                if (p.pix != null) {
                    // 原始 16 位通道：数值原样，但改成 RLE 存（桌面端2026 不收我们自己写的 16 位 ZIP）
                    for ((k, ch) in p.pix.channels.withIndex()) {
                        if (k >= p.positions.size) break
                        val start = layerInfo.size()
                        val bytes = channelBytes(ch.data, ch.compression, w, h, depth, psb)
                        layerInfo.write(bytes, 0, bytes.size)
                        patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                    }
                    onLayerWritten?.invoke(p.layer)
                    continue
                }
                val emptyFlags = p.head?.empty
                if (!p.useHead && p.layer.adjust != null && p.layer.text == null) {
                    // 空调整层：每条通道只有 2 字节（压缩方式 0），没有任何像素数据
                    for (k in p.ids.indices) {
                        if (k >= p.positions.size) break
                        val start = layerInfo.size()
                        writeU16(layerInfo, 0)
                        patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                    }
                    onLayerWritten?.invoke(p.layer)
                    continue
                }
                val bmp = p.layer.bitmap ?: p.layer.bitmapSource?.invoke()
                val maskPx = if (p.ids.contains(-2)) maskSamples(p.layer.mask, p.rect) else null
                for ((k, id) in p.ids.withIndex()) {
                    if (k >= p.positions.size) break
                    val start = layerInfo.size()
                    // 原文件里这条通道本来就是空的（调整层 / 智能对象的 -1 通道）→ 照旧只写 2 字节
                    if (emptyFlags != null && k < emptyFlags.size && emptyFlags[k]) {
                        writeU16(layerInfo, 0)
                        patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                        continue
                    }
                    val srcBmp = if (id == -1 && !p.useHead && p.layer.adjust != null && p.layer.text == null) null else bmp
                    writeChannelRle(
                        layerInfo, srcBmp, maskPx, w, h, id, mode, depth, doc, psb,
                        raw = if (id == -2 && !p.layer.maskDirty) p.layer.rawMask else null,
                    )
                    patchLen(layerInfo, p.positions[k], (layerInfo.size() - start).toLong(), psb)
                }
            }
            onLayerWritten?.invoke(p.layer)
        }

        if (flatOnly) {
            // 图层与蒙版信息段留空（长度 0），桌面端打开后就是一张普通背景图
            writeU32(bos, 0L, psb)
        } else {
            var li = layerInfo.toByteArray()
            if (li.size % 2 == 1) li += byteArrayOf(0)
            val additional = doc.rawDoc?.additional ?: ByteArray(0)
            val maskBlock = ByteArrayOutputStream(li.size + 64 + additional.size)
            if (depth == 16 || depth == 32) {
                // 桌面端对 16/32 位文档不认「经典」图层段：图层记录必须放进 Lr16/Lr32 附加信息块，
                // 经典段长度写 0、前面再放一个 Mt16/Mt32（蒙版，长度 0），否则 桌面端会把 alpha 当成
                // 多余通道、并且另存时把图层丢掉（用户 2026-11-05 报「拖进 桌面端没有透明孔」）。
                writeU32(maskBlock, 0L)
                writeU32(maskBlock, 0L) // 全局蒙版
                writeAscii(maskBlock, "8BIM")
                writeAscii(maskBlock, if (depth == 32) "Mt32" else "Mt16")
                writeU32(maskBlock, 0L)
                writeAscii(maskBlock, "8BIM")
                writeAscii(maskBlock, if (depth == 32) "Lr32" else "Lr16")
                writeU32(maskBlock, li.size.toLong(), psb)
                maskBlock.write(li, 0, li.size)
                if (li.size % 2 == 1) maskBlock.write(0)
            } else {
                writeU32(maskBlock, li.size.toLong(), psb)
                maskBlock.write(li, 0, li.size)
                writeU32(maskBlock, 0L) // 全局蒙版（固定 4 字节）
            }
            if (additional.isNotEmpty()) maskBlock.write(additional, 0, additional.size)
            var maskBytes = maskBlock.toByteArray()
            if (maskBytes.size % 2 == 1) maskBytes += byteArrayOf(0)
            writeU32(bos, maskBytes.size.toLong(), psb)
            bos.write(maskBytes, 0, maskBytes.size)
        }

        // 合并图
        val rawComposite = doc.rawDoc?.composite
        // 像素没动过（raw 原样，或者 rawPix 原样）就别重算合并图，直接搬原始字节：16 位才不会掉成 8 位
        val untouched = doc.layers.all { !it.dirty && (it.raw != null || it.rawPix != null) }
        if (rawComposite != null && untouched) {
            bos.write(rawComposite.data, 0, rawComposite.data.size)
        } else {
            writeComposite(bos, doc, width, height, depth, mode, channels)
        }
        return bos.toByteArray()
    }

    /** 新建文档（不是从 PSD 打开）时补两块 桌面端必然读取的图像资源，避免“版本不兼容”式误判 */
    /**
     * 32 位文档必备的色彩模式数据块（桌面端自己存 32 位 PSD 时写的就是这 102 字节：hdrt/hdra 标签）。
     * 没有它，桌面端2026 打开 32 位文件会报「无法打开文件，因为打开选项不正确」。
     */
    private val HDR_CM_32: ByteArray = (
        "68647274000000033e6b851f00000002000000037ebf60270000000200020000" +
        "000000ff00ff010100000000000000004180000000000001000000003f800000" +
        "68647261000000060000000041a0000041f0000000000000000000003f800000" +
        "000000000000"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun defaultResources(): ByteArray {
        val out = ByteArrayOutputStream(96)
        // 0x03ED 分辨率信息：72 ppi，单位 px/inch
        val res = ByteArrayOutputStream(24)
        writeAscii(res, "8BIM")
        writeU16(res, 0x03ED)
        writeU16(res, 0) // 名字长度 0（含 pad）
        writeU32(res, 16L) // 数据长度
        writeU32(res, 0x00480000L) // hRes 72.0
        writeU16(res, 1) // hResUnit = ppi
        writeU16(res, 1) // widthUnit = inches
        writeU32(res, 0x00480000L) // vRes 72.0
        writeU16(res, 1)
        writeU16(res, 1)
        out.write(res.toByteArray(), 0, res.size())

        // 0x0421 版本信息：桌面端用它判断“文件是否由更新的 桌面端保存”
        val ver = ByteArrayOutputStream(48)
        val writer = "Reveal"
        writeAscii(ver, "8BIM")
        writeU16(ver, 0x0421)
        writeU16(ver, 0)
        val body = ByteArrayOutputStream(40)
        writeU32(body, 1L) // version = 1
        writeU8(body, 1) // hasRealMergedData
        writeU32(body, writer.length.toLong()) // 写入名（unicode）
        for (ch in writer) writeU16(body, ch.code)
        writeU32(body, writer.length.toLong()) // 读取名
        for (ch in writer) writeU16(body, ch.code)
        writeU32(body, 1L) // fileVersion
        var b = body.toByteArray()
        if (b.size % 2 == 1) b += byteArrayOf(0) // 块长度取偶数，桌面端才认
        writeU32(ver, b.size.toLong())
        ver.write(b, 0, b.size)
        out.write(ver.toByteArray(), 0, ver.size())
        return out.toByteArray()
    }

    private class Planned(
        val rect: IntArray,
        val ids: List<Int>,
        val useRaw: Boolean,
        val raw: RawLayer?,
        val layer: PsdLayer,
        val positions: IntArray,
        val useHead: Boolean = false,
        val head: RawHead? = null,
        val pix: PixBlob? = null,
        val p16: Pix16? = null,
        val p32: Pix32? = null,
    )

    private fun channelIds(doc: Document): List<Int> = when (doc.colorMode) {
        MODE_RGB -> listOf(-1, 0, 1, 2)
        MODE_CMYK -> listOf(-1, 0, 1, 2, 3)
        MODE_GRAY, MODE_BITMAP, MODE_DUOTONE, MODE_INDEXED -> listOf(-1, 0)
        MODE_LAB -> listOf(-1, 0, 1, 2)
        else -> listOf(-1, 0, 1, 2)
    }

    private fun buildExtra(l: PsdLayer, rect: IntArray): ByteArray {
        val extra = ByteArrayOutputStream(256)
        val mask = l.mask
        if (mask == null) {
            writeU32(extra, 0)
        } else {
            // 有蒙版：写一个简单矩形蒙版记录（-1 表示整个图层矩形）
            val mb = ByteArrayOutputStream(64)
            writeU32(mb, 0); writeU32(mb, 0); writeU32(mb, mask.height.toLong()); writeU32(mb, mask.width.toLong())
            writeU8(mb, 255)
            writeU8(mb, 0)
            writeU16(mb, 0)
            writeU32(extra, mb.size().toLong())
            extra.write(mb.toByteArray())
        }
        writeU32(extra, 0) // 混合范围
        val name = l.name
        val nameBytes = gbkBytes(name)
        val nameLen = nameBytes.size.coerceAtMost(255)
        writeU8(extra, nameLen)
        extra.write(nameBytes, 0, nameLen)
        val pad = (4 - ((nameLen + 1) % 4)) % 4
        repeat(pad) { writeU8(extra, 0) }
        // luni 的写法是「u32 字数 + UTF-16BE 字符」，长度 = 4 + 2×字数。
        // 早先错写成 u16 字数，桌面端读出来层名会少第一个字（色阶 → 阶）
        val luniLen = 4 + name.length * 2
        writeAscii(extra, "8BIM")
        writeAscii(extra, "luni")
        writeU32(extra, luniLen.toLong())
        writeU32(extra, name.length.toLong())
        for (ch in name) writeU16(extra, ch.code)

        // 原图层里其它块（蒙版参数、通道信息、我们自己不认识的块）原样搬回来，别丢东西
        val raw = l.raw
        if (raw != null) {
            forEachBlock(raw.extra) { key, data ->
                if (key != "TySh" && key != "lfx2" && key != "brit" && key != "hue2" && key != "levl" && key != "lsct") {
                    writeBlock(extra, key, data)
                }
            }
        }
        // 组标记：我们新建的组也要写回 lsct，不然 桌面端那边就是两个孤零零的普通图层
        if (l.sectionType in 1..3) {
            writeBlock(extra, "lsct", lsctBytes(l.sectionType, BLEND_KEYS.getOrElse(l.blendMode) { "norm" }))
        }
        val text = l.text
        if (text != null) writeBlock(extra, "TySh", PsdText.encodeTySh(text, rect, 0, 0))
        val adjOp = l.adjust
        // 渐变映射还没有对应的 PSD 附加信息块，先不写（写错块会让 桌面端打不开）
        if (adjOp != null && adjOp.kind != AdjustOp.KIND_GRADIENT_MAP) {
            writeBlock(extra, AdjustOp.blockKey(adjOp.kind), AdjustOp.encode(adjOp))
        }
        val fx = l.effects
        if (fx != null) writeBlock(extra, "lfx2", PsdFx.encode(fx))
        return extra.toByteArray()
    }

    /** 把读出来的原始层记录打包成可存盘的字节 */
    internal fun packHead(raw: RawLayer): RawHead = RawHead(
        rect = raw.rect,
        sig = raw.blendSig,
        key = raw.blendKey,
        opacity = raw.opacity,
        clipping = raw.clipping,
        flags = raw.flags,
        filler = raw.filler,
        ids = IntArray(raw.channels.size) { raw.channels[it].id },
        empty = BooleanArray(raw.channels.size) { raw.channels[it].data.size <= 2 },
        extra = raw.extra,
    )

    /**
     * 只把原始 extra 里的图层名换成本地名字，蒙版记录、混合范围、以及所有附加块
     * （调整层、图层样式、文字、智能对象、图层组……）全部按原始字节搬，不认识的也不丢。
     */
    private fun extraWithName(head: RawHead, name: String, sectionType: Int = 0, blendMode: Int = 0): ByteArray {
        val src = head.extra
        val c = Cur(src)
        if (c.left() < 4) return src
        val out = ByteArrayOutputStream(src.size + 64)
        val maskLen = c.u32().toInt().coerceIn(0, c.left())
        writeU32(out, maskLen.toLong())
        if (maskLen > 0) out.write(src, c.p, maskLen)
        c.skip(maskLen.toLong())
        if (c.left() < 4) return src
        val rangeLen = c.u32().toInt().coerceIn(0, c.left())
        writeU32(out, rangeLen.toLong())
        if (rangeLen > 0) out.write(src, c.p, rangeLen)
        c.skip(rangeLen.toLong())

        val nameBytes = gbkBytes(name)
        val nameLen = nameBytes.size.coerceAtMost(255)
        writeU8(out, nameLen)
        if (nameLen > 0) out.write(nameBytes, 0, nameLen)
        repeat((4 - ((nameLen + 1) % 4)) % 4) { writeU8(out, 0) }

        // Unicode 层名（中文靠它）；原来的 luni 丢掉，别出现两个名字块
        writeAscii(out, "8BIM")
        writeAscii(out, "luni")
        val luniLen = 4 + name.length * 2
        writeU32(out, luniLen.toLong())
        writeU32(out, name.length.toLong())
        for (ch in name) writeU16(out, ch.code)

        forEachBlock(src) { key, data ->
            if (key == "luni") return@forEachBlock
            // lsct 里存三样：组类型（展开/折叠/组底）+ 组混合模式备份 + subtype。
            // 整块重写，混合模式按图层现在的设置走，剩下的 subtype 沿用原值
            if (key == "lsct" && sectionType in 1..3) {
                val sub = if (data.size >= 16) {
                    ((data[12].toInt() and 0xFF) shl 24) or ((data[13].toInt() and 0xFF) shl 16) or
                        ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
                } else 0
                writeBlock(
                    out, "lsct",
                    lsctBytes(sectionType, BLEND_KEYS.getOrElse(blendMode) { "norm" }, sub)
                )
                return@forEachBlock
            }
            writeBlock(out, key, data)
        }
        if (sectionType in 1..3 && findBlock(src, "lsct") == null) {
            writeBlock(out, "lsct", lsctBytes(sectionType, BLEND_KEYS.getOrElse(blendMode) { "norm" }))
        }
        return out.toByteArray()
    }

    /**
     * 原始头部里记的属性，和当前内存里的属性是否已经不一致。
     * 一致 → 整段搬原始字节；不一致 → 退回按现有信息重写（认得的块照写，不认得的会丢）。
     */
    internal fun headTouched(head: RawHead, l: PsdLayer): Boolean {
        var rawAdj: AdjustOp? = null
        for (k in listOf("brit", "hue2", "levl")) {
            val d = findBlock(head.extra, k) ?: continue
            rawAdj = AdjustOp.decode(k, d) ?: rawAdj
        }
        if (rawAdj != l.adjust) return true

        val textRaw = findBlock(head.extra, "TySh")
        val cur = l.text
        if (textRaw == null) {
            if (cur != null) return true
        } else if (cur == null) {
            return true
        } else {
            val dec = PsdText.decodeTySh(textRaw)
            if (dec != null && (dec.content != cur.content ||
                    kotlin.math.abs(dec.size - cur.size) > 0.5f || dec.color != cur.color)
            ) return true
        }

        val fxRaw = findBlock(head.extra, "lfx2")
        if ((fxRaw == null) != (l.effects == null)) return true

        // 有蒙版的层一律走重写路径：蒙版通道的尺寸是跟着蒙版记录走的，
        // 原样搬原始记录却按新矩形写通道，桌面端会把这份蒙版读成垃圾
        if (l.mask != null) return true
        return false
    }

    private fun writeBlock(out: ByteArrayOutputStream, key: String, data: ByteArray) {
        writeAscii(out, "8BIM")
        writeAscii(out, key.padEnd(4, ' ').take(4))
        writeU32(out, data.size.toLong())
        out.write(data, 0, data.size)
        if (data.size % 2 == 1) writeU8(out, 0)
    }

    /** 图层额外数据里，名字之后那块「块列表」从哪开始 */
    private fun blocksStart(extra: ByteArray): Int {
        val c = Cur(extra)
        if (c.left() < 4) return extra.size
        c.skip(c.u32())
        if (c.left() < 4) return extra.size
        c.skip(c.u32())
        if (c.left() < 1) return extra.size
        val n = c.u8()
        val used = 1 + n
        c.skip(n.toLong())
        c.skip(((4 - (used % 4)) % 4).toLong())
        return c.p.coerceAtMost(extra.size)
    }

    private inline fun forEachBlock(extra: ByteArray, action: (String, ByteArray) -> Unit) {
        val c = Cur(extra)
        c.p = blocksStart(extra)
        while (c.left() >= 12) {
            val sig = c.ascii(4)
            val key = c.ascii(4).trim()
            val len = c.u32()
            if (len < 0 || len > c.left().toLong()) break
            val data = c.take(len.toInt())
            if (len % 2 == 1L && c.left() > 0) c.skip(1)
            if (sig == "8BIM") action(key, data)
        }
    }

    internal fun findBlock(extra: ByteArray, key: String): ByteArray? {
        var found: ByteArray? = null
        forEachBlock(extra) { k, d -> if (k == key && found == null) found = d }
        return found
    }

    /**
     * 文档级附加信息里的块（图层蒙版段最后那一段）：'8BIM'/'8B64' + 4 字节键 + 长度 + 内容。
     * 和图层附加块不同，这里没有 10 字节的「蒙版/名字」头，直接从块本身开始走。
     */
    internal fun docBlock(additional: ByteArray, key: String, psb: Boolean): ByteArray? {
        val c = Cur(additional)
        while (c.left() >= 12) {
            val sig = c.ascii(4)
            if (sig != "8BIM" && sig != "8B64" && sig != "MeSa" && sig != "AgHg" && sig != "PHUT" && sig != "DCSR") break
            val k = c.ascii(4).trim()
            val len = if (psb) c.u64() else c.u32()
            if (len < 0 || len > c.left().toLong()) break
            val data = c.take(len.toInt())
            // 文档级块内容按 4 字节对齐（桌面端2026 的 PSD/PSB 都这样，实测 Patt/CAI/OCIO/GenI/lnk2 串起来正好走到底）
            val used = len % 4
            if (used != 0L && c.left() > 0) c.skip((4 - used.toInt()).toLong())
            if (k == key) return data
        }
        return null
    }

    /** 图层蒙版取样：PSD 里蒙版是 0..255 的灰度（255 = 显示） */
    private fun maskSamples(mask: Bitmap?, rect: IntArray): IntArray {
        val w = (rect[3] - rect[1]).coerceAtLeast(1)
        val h = (rect[2] - rect[0]).coerceAtLeast(1)
        val out = IntArray(w * h) { 255 }
        if (mask == null) return out
        val local = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(local).drawBitmap(mask, -rect[1].toFloat(), -rect[0].toFloat(), null)
        val px = IntArray(w * h)
        local.getPixels(px, 0, w, 0, 0, w, h)
        local.recycle()
        for (i in px.indices) out[i] = (px[i] ushr 24) and 0xFF
        return out
    }

    /** 单通道：算样本 → 逐行 PackBits → 直接写进流（内存只需一行 + 压好的行） */
    private fun writeChannelRle(
        out: ByteArrayOutputStream,
        bmp: Bitmap?,
        maskPx: IntArray?,
        w: Int,
        h: Int,
        id: Int,
        mode: Int,
        depth: Int,
        doc: Document,
        psb: Boolean,
        raw: ByteArray? = null,
    ) {
        if (raw != null && raw.isNotEmpty()) {
            writeU32(out, raw.size.toLong(), psb)
            out.write(raw, 0, raw.size)
            return
        }
        val bps = PsdIo.bytesPerSample(depth)
        val rowBytes = w * bps
        val rowBuf = ByteArray(rowBytes)
        val rows = ArrayList<ByteArray>(h)
        // 逐行读像素：不把整层 ARGB 同时摊在内存里（4000² 一层就是 64 MB）
        val rowPx = IntArray(w)
        for (y in 0 until h) {
            if (id != -2 && bmp != null) bmp.getPixels(rowPx, 0, w, 0, y, w, 1)
            val base = y * w
            var at = 0
            for (x in 0 until w) {
                val v = if (id == -2) (maskPx?.get(base + x) ?: 255) else sampleFor(id, rowPx[x], mode, doc)
                when (bps) {
                    1 -> rowBuf[at] = v.toByte()
                    2 -> { rowBuf[at] = ((v shl 8) or v).toByte(); rowBuf[at + 1] = v.toByte() }
                    4 -> {
                        val f32 = v / 255f
                        val bits = java.lang.Float.floatToIntBits(if (id >= 0) Pix32.toLinear(f32) else f32)
                        rowBuf[at] = (bits ushr 24).toByte(); rowBuf[at + 1] = (bits ushr 16).toByte()
                        rowBuf[at + 2] = (bits ushr 8).toByte(); rowBuf[at + 3] = bits.toByte()
                    }
                }
                at += bps
            }
            rows.add(packBits(rowBuf, 0, rowBytes))
        }
        writeU16(out, 1)
        for (r in rows) { if (psb) writeU32(out, r.size.toLong()) else writeU16(out, r.size) }
        for (r in rows) out.write(r, 0, r.size)
    }

    private fun sampleFor(id: Int, v: Int, mode: Int, doc: Document): Int {
        val r = (v ushr 16) and 0xFF
        val g = (v ushr 8) and 0xFF
        val b = v and 0xFF
        val a = (v ushr 24) and 0xFF
        return when (id) {
            -1 -> a
            0 -> when (mode) {
                MODE_RGB -> r
                MODE_CMYK -> rgbToCmyk(r, g, b)[0]
                MODE_LAB -> rgbToLab(r, g, b)[0]
                else -> (r * 299 + g * 587 + b * 114) / 1000
            }
            1 -> when (mode) {
                MODE_RGB -> g
                MODE_CMYK -> rgbToCmyk(r, g, b)[1]
                MODE_LAB -> rgbToLab(r, g, b)[1]
                else -> 0
            }
            2 -> when (mode) {
                MODE_RGB -> b
                MODE_CMYK -> rgbToCmyk(r, g, b)[2]
                MODE_LAB -> rgbToLab(r, g, b)[2]
                else -> 0
            }
            3 -> if (mode == MODE_CMYK) rgbToCmyk(r, g, b)[3] else 0
            else -> 0
        }
    }

    /** PackBits（PSD 的 RLE）压一行 */
    private fun packBits(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(len / 2 + 16)
        var i = 0
        while (i < len) {
            var run = 1
            while (i + run < len && run < 128 && src[off + i + run] == src[off + i]) run++
            if (run >= 3) {
                out.write(257 - run)
                out.write(src[off + i].toInt() and 0xFF)
                i += run
            } else {
                var j = i
                var lit = 0
                while (j < len && lit < 128) {
                    if (j + 2 < len && src[off + j] == src[off + j + 1] && src[off + j] == src[off + j + 2]) break
                    j++; lit++
                }
                if (lit == 0) lit = 1
                out.write(lit - 1)
                out.write(src, off + i, lit)
                i += lit
            }
        }
        return out.toByteArray()
    }

    /**
     * ZIP 压缩一个通道平面。16 位要用 桌面端的「预测」：行内做前向差分（16 位整数，&0xFFFF），
     * 再整体 deflate。渐变这种平滑数据压完只有原来的百分之几；解出来和原值一模一样，不丢精度。
     */
    private fun zipPlane(data: ByteArray, rowBytes: Int, h: Int, bps: Int, predict: Boolean): ByteArray {
        val src = if (predict) predicted(data, rowBytes, h, bps) else data
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        deflater.setInput(src)
        deflater.finish()
        val bos = ByteArrayOutputStream(src.size / 4 + 1024)
        val buf = ByteArray(1 shl 16)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        deflater.end()
        return bos.toByteArray()
    }

    private fun predicted(data: ByteArray, rowBytes: Int, h: Int, bps: Int): ByteArray {
        val out = data.copyOf()
        if (bps == 1) {
            for (y in 0 until h) {
                val b0 = y * rowBytes
                for (x in rowBytes - 1 downTo 1) {
                    out[b0 + x] = (out[b0 + x].toInt() - (out[b0 + x - 1].toInt() and 0xFF)).toByte()
                }
            }
        } else if (bps == 2) {
            // 和读端一致：每行从 prev=0 开始，逐样本做 16 位差分（大端存）
            for (y in 0 until h) {
                var prev = 0
                var p = y * rowBytes
                while (p + 1 < (y + 1) * rowBytes) {
                    val v = ((out[p].toInt() and 0xFF) shl 8) or (out[p + 1].toInt() and 0xFF)
                    val d = (v - prev) and 0xFFFF
                    prev = v
                    out[p] = ((d ushr 8) and 0xFF).toByte()
                    out[p + 1] = (d and 0xFF).toByte()
                    p += 2
                }
            }
        }
        return out
    }

    /** 把一段「含 2 字节压缩字」的通道数据解成原始样本字节（1/2/4 字节每样本） */
    private fun decodeChannelBytes(data: ByteArray, w: Int, h: Int, depth: Int, psb: Boolean): ByteArray? = runCatching {
        val c = Cur(data)
        val comp = if (c.left() >= 2) c.u16() else 0
        PsdIo.readChannel(c, w, h, depth, comp, psb, data.size.toLong())
    }.getOrNull()

    /**
     * 16 位通道改写成 RLE（compression=1）。
     * 实测 桌面端2026 打不开我们自己写的 16 位 ZIP（compression=2/3），但 RLE 一直是好的；
     * RLE 对渐变色这种整行同值的图画压得极狠，照片会大一些，但保证 桌面端能开。
     * 已经是 RLE 或不是 16 位就原样返回。
     */
    private fun channelBytes(data: ByteArray, comp: Int, w: Int, h: Int, depth: Int, psb: Boolean): ByteArray {
        if (depth != 16 || comp == 1) return data
        val plane = decodeChannelBytes(data, w, h, depth, psb) ?: return data
        return rlePlane(plane, w * 2, h, psb)
    }

    /** 合并图的 ZIP 写法：一个压缩字 3，后面每个通道一条独立的 deflate 流 */
    private fun writeCompositeZip(planes: List<ByteArray>, rowBytes: Int, h: Int, bps: Int): ByteArray {
        val bos = ByteArrayOutputStream(rowBytes * h / 4 + 64)
        writeU16(bos, 3)
        for (p in planes) {
            val b = zipPlane(p, rowBytes, h, bps, true)
            bos.write(b, 0, b.size)
        }
        return bos.toByteArray()
    }
    /** 一整个通道：compression=1 + 每行长度表 + 行数据 */
    /** ZIP+预测：每行 prev 归零，8 位按字节、16 位按大端样本做差分累加 */
    private fun undoZipPredict(data: ByteArray, chans: Int, h: Int, rowBytes: Int, bps: Int) {
        for (k in 0 until chans) {
            for (r in 0 until h) {
                val o = (k * h + r) * rowBytes
                var prev = 0
                if (bps != 2) { // 8 位 / 32 位浮点：按字节做差分；16 位按大端样本
                    for (i in 0 until rowBytes) {
                        val v = ((data[o + i].toInt() and 0xFF) + prev) and 0xFF
                        data[o + i] = v.toByte()
                        prev = v
                    }
                } else {
                    var i = 0
                    while (i + 1 < rowBytes) {
                        val rawv = ((data[o + i].toInt() and 0xFF) shl 8) or (data[o + i + 1].toInt() and 0xFF)
                        val v = (rawv + prev) and 0xFFFF
                        data[o + i] = ((v ushr 8) and 0xFF).toByte()
                        data[o + i + 1] = (v and 0xFF).toByte()
                        prev = v
                        i += 2
                    }
                }
            }
        }
    }

    private fun rlePlane(data: ByteArray, rowBytes: Int, h: Int, psb: Boolean): ByteArray {
        val rows = ArrayList<ByteArray>(h)
        for (r in 0 until h) rows.add(packBits(data, r * rowBytes, rowBytes))
        val bos = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        writeU16(bos, 1)
        for (r in rows) { if (psb) writeU32(bos, r.size.toLong()) else writeU16(bos, r.size) }
        for (r in rows) bos.write(r, 0, r.size)
        return bos.toByteArray()
    }

    /** 合并图也走 RLE，不然 1MB 的图能写出 11MB */
    private fun writeCompositeRle(planes: List<ByteArray>, rowBytes: Int, h: Int, psb: Boolean): ByteArray {
        val rowsPer = planes.map { d -> (0 until h).map { r -> packBits(d, r * rowBytes, rowBytes) } }
        val bos = java.io.ByteArrayOutputStream(rowBytes * h / 2 + 64)
        writeU16(bos, 1)
        for (rows in rowsPer) for (r in rows) { if (psb) writeU32(bos, r.size.toLong()) else writeU16(bos, r.size) }
        for (rows in rowsPer) for (r in rows) bos.write(r, 0, r.size)
        return bos.toByteArray()
    }

    private fun rgbToCmyk(r: Int, g: Int, b: Int): IntArray {
        val k = 255 - maxOf(r, g, b)
        if (k >= 255) return intArrayOf(0, 0, 0, 255)
        val c = (255 - r - k) * 255 / (255 - k)
        val m = (255 - g - k) * 255 / (255 - k)
        val y = (255 - b - k) * 255 / (255 - k)
        return intArrayOf(c.coerceIn(0, 255), m.coerceIn(0, 255), y.coerceIn(0, 255), k)
    }

    private fun rgbToLab(r: Int, g: Int, b: Int): IntArray {
        fun lin(v: Int): Float {
            val f = v / 255f
            return if (f <= 0.04045f) f / 12.92f else Math.pow(((f + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        val x = (lin(r) * 0.4124f + lin(g) * 0.3576f + lin(b) * 0.1805f) / 0.95047f
        val y = lin(r) * 0.2126f + lin(g) * 0.7152f + lin(b) * 0.0722f
        val z = (lin(r) * 0.0193f + lin(g) * 0.1192f + lin(b) * 0.9505f) / 1.08883f
        fun f(t: Float): Float = if (t > 0.008856f) Math.pow(t.toDouble(), 1 / 3.0).toFloat() else 7.787f * t + 16f / 116f
        val fx = f(x); val fy = f(y); val fz = f(z)
        val l = (116 * fy - 16) * 255 / 100
        val a = (500 * (fx - fy)) + 128
        val bb = (200 * (fy - fz)) + 128
        return intArrayOf(l.toInt().coerceIn(0, 255), a.toInt().coerceIn(0, 255), bb.toInt().coerceIn(0, 255))
    }

    /**
     * 16 位合并图（16 位收尾第 2、3 项）。
     *
     * 只处理「简单图层栈」：没有图层组、蒙版、文字、图层样式、剪贴蒙版；像素层必须还带着
     * 原始 16 位通道（rawPix，即从 桌面端打开后没被画过），且混合模式「正常」、不透明度 100%。
     * 调整层可以叠在上面，按 16 位精度应用。任何一条不满足就返回 null，退回 8 位合成——
     * 那样导出的还是 16 位容器，但只有 256 个台阶。
     */
    private fun composite16(
        width: Int,
        height: Int,
        outChannels: Int,
        psb: Boolean,
        layers: List<PsdLayer>,
    ): List<ByteArray>? {
        if (layers.isEmpty() || width <= 0 || height <= 0) return null
        var pixelLayers = 0
        for (l in layers) {
            if (l.sectionType != 0) { note16 = "sec"; return null }
            if (l.mask != null || l.text != null || l.effects != null || l.clipped) { note16 = "extra"; return null }
            if (l.adjust == null) {
                if (l.rawPix == null && l.pix16 == null) { note16 = "nopix"; return null }
                if (l.opacity != 255 || l.blendMode != 0) { note16 = "blend"; return null }
                pixelLayers++
            }
        }
        if (pixelLayers == 0) return null
        note16 = "ok"
        val n = width * height
        val accR = ShortArray(n)
        val accG = ShortArray(n)
        val accB = ShortArray(n)
        val accA = ShortArray(n)
        var painted = false
        for (l in layers) {
            val adj = l.adjust
            if (adj != null) {
                if (l.visible && painted) applyAdjust16(accR, accG, accB, adj)
                continue
            }
            if (!l.visible) continue
            val p16 = l.pix16
            val pix = if (p16 != null && p16.width == width && p16.height == height) null
                else (unpackPix(l.rawPix) ?: run { note16 = "unpack"; return null })
            val rect = if (pix != null) pix.rect else intArrayOf(0, 0, height, width)
            val left = rect[1]
            val top = rect[0]
            val rw = rect[3] - left
            val rh = rect[2] - top
            if (rw <= 0 || rh <= 0) continue
            var cr: ShortArray? = null
            var cg: ShortArray? = null
            var cb: ShortArray? = null
            var ca: ShortArray? = null
            if (pix == null) {
                cr = p16!!.r.copyOf()
                cg = p16.g.copyOf()
                cb = p16.b.copyOf()
            } else for (ch in pix.channels) {
                // ch.data 是「compression 字段 + 数据」的原始切片（和 parseLayers 存下来的一致）
                val c = Cur(ch.data)
                val compHere = if (c.left() >= 2) c.u16() else ch.compression
                val bytes = PsdIo.readChannel(c, rw, rh, 16, compHere, psb, ch.data.size.toLong())
                val arr = ShortArray(rw * rh)
                for (i in arr.indices) {
                    val at = i * 2
                    if (at + 1 < bytes.size) {
                        arr[i] = (((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort()
                    }
                }
                when (ch.id) {
                    -1 -> ca = arr
                    0 -> cr = arr
                    1 -> cg = arr
                    2 -> cb = arr
                }
            }
            for (y in 0 until rh) {
                val dy = top + y
                if (dy < 0 || dy >= height) continue
                val rowSrc = y * rw
                val rowDst = dy * width
                for (x in 0 until rw) {
                    val dx = left + x
                    if (dx < 0 || dx >= width) continue
                    val si = rowSrc + x
                    val di = rowDst + dx
                    val sa = ((ca?.get(si)?.toInt() ?: 0xFFFF) and 0xFFFF) / 65535f
                    if (sa <= 0f) continue
                    val da = (accA[di].toInt() and 0xFFFF) / 65535f
                    val oa = sa + da * (1f - sa)
                    if (oa <= 0f) continue
                    val w1 = sa / oa
                    val w2 = da * (1f - sa) / oa
                    val sr = ((cr?.get(si)?.toInt() ?: 0) and 0xFFFF).toFloat()
                    val sg = ((cg?.get(si)?.toInt() ?: 0) and 0xFFFF).toFloat()
                    val sb = ((cb?.get(si)?.toInt() ?: 0) and 0xFFFF).toFloat()
                    accR[di] = (sr * w1 + (accR[di].toInt() and 0xFFFF) * w2).toInt().coerceIn(0, 65535).toShort()
                    accG[di] = (sg * w1 + (accG[di].toInt() and 0xFFFF) * w2).toInt().coerceIn(0, 65535).toShort()
                    accB[di] = (sb * w1 + (accB[di].toInt() and 0xFFFF) * w2).toInt().coerceIn(0, 65535).toShort()
                    accA[di] = (oa * 65535f).toInt().coerceIn(0, 65535).toShort()
                }
            }
            painted = true
        }
        if (!painted) return null
        val out = ArrayList<ByteArray>(4)
        fun plane(a: ShortArray): ByteArray {
            val d = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = a[i].toInt() and 0xFFFF
                d[i * 2] = (v ushr 8).toByte()
                d[i * 2 + 1] = v.toByte()
            }
            return d
        }
        out.add(plane(accR))
        out.add(plane(accG))
        out.add(plane(accB))
        if (outChannels >= 4) out.add(plane(accA))
        return out
    }

    /** 调整层按 16 位精度应用：色阶走 65536 项查表，矩阵类（亮度对比度 / 色相饱和度）直接线性算 */
    private fun applyAdjust16(r: ShortArray, g: ShortArray, b: ShortArray, op: AdjustOp) {
        if (op.isNeutral()) return
        if (op.kind == AdjustOp.KIND_LEVELS) {
            val ps = op.levelsOrDefault()
            val lc = lut16(ps[0])
            val lr = lut16(ps[1])
            val lg = lut16(ps[2])
            val lb = lut16(ps[3])
            for (i in r.indices) {
                var vr = lc[r[i].toInt() and 0xFFFF]
                var vg = lc[g[i].toInt() and 0xFFFF]
                var vb = lc[b[i].toInt() and 0xFFFF]
                vr = lr[vr]
                vg = lg[vg]
                vb = lb[vb]
                r[i] = vr.toShort()
                g[i] = vg.toShort()
                b[i] = vb.toShort()
            }
            return
        }
        val m = op.matrix().array
        val r0 = m[0]
        val r1 = m[1]
        val r2 = m[2]
        val ro = m[4] * 257f
        val g0 = m[5]
        val g1 = m[6]
        val g2 = m[7]
        val go = m[9] * 257f
        val b0 = m[10]
        val b1 = m[11]
        val b2 = m[12]
        val bo = m[14] * 257f
        for (i in r.indices) {
            val vr = (r[i].toInt() and 0xFFFF).toFloat()
            val vg = (g[i].toInt() and 0xFFFF).toFloat()
            val vb = (b[i].toInt() and 0xFFFF).toFloat()
            r[i] = (r0 * vr + r1 * vg + r2 * vb + ro).toInt().coerceIn(0, 65535).toShort()
            g[i] = (g0 * vr + g1 * vg + g2 * vb + go).toInt().coerceIn(0, 65535).toShort()
            b[i] = (b0 * vr + b1 * vg + b2 * vb + bo).toInt().coerceIn(0, 65535).toShort()
        }
    }

    /** 色阶的 16 位查表：面板参数是 8 位刻度，乘 257 拉回 16 位后逐值算（不是 256 级插值，那还是台阶） */
    private fun lut16(p: Levels.Params): IntArray {
        val inB0 = p.inBlack.coerceIn(0, 254)
        val inB = inB0 * 257
        val inW = p.inWhite.coerceIn(inB0 + 1, 255) * 257
        val g = p.gamma.coerceIn(0.1f, 9.9f)
        val outB = p.outBlack.coerceIn(0, 255) * 257
        val outW = p.outWhite.coerceIn(0, 255) * 257
        val span = (inW - inB).toFloat()
        val table = IntArray(65536)
        for (i in 0..65535) {
            var t = ((i - inB) / span).coerceIn(0f, 1f)
            if (g != 1f) t = Math.pow(t.toDouble(), 1.0 / g).toFloat()
            table[i] = (outB + t * (outW - outB)).toInt().coerceIn(0, 65535)
        }
        return table
    }

    private fun writeComposite(
        bos: ByteArrayOutputStream,
        doc: Document,
        width: Int,
        height: Int,
        depth: Int,
        mode: Int,
        channels: Int,
    ) {
        if (depth == 16 && mode == MODE_RGB) {
            val planes16 = composite16(width, height, channels, doc.isPsb, doc.layers)
            if (planes16 != null) {
                // 16 位合并图走 RLE：桌面端2026 打不开我们自己写的 16 位 ZIP，RLE 是实测能开的
                note16 = "rgb16"
                val rle16 = writeCompositeRle(planes16, width * 2, height, doc.isPsb)
                bos.write(rle16, 0, rle16.size)
                return
            }
        }
        if (depth == 32 && mode == MODE_RGB) {
            val only32 = doc.layers.singleOrNull()
            // pix32 只是「画过之后」的加速缓冲；没画过/被丢弃时要能从原始通道重建，
            // 否则会掉到 8 位兜底——那条路写出来的 32 位合并图 桌面端2026 不认（打开选项不正确）
            val p32 = only32?.pix32 ?: run {
                val bmp = only32?.bitmap ?: only32?.bitmapSource?.invoke()
                if (only32 != null && only32.rawPix != null && bmp != null) {
                    pix32FromRawPix(only32.rawPix, bmp)
                } else {
                    null
                }
            }
            if (only32 != null && p32 != null && p32.width == width && p32.height == height &&
                only32.adjust == null && only32.text == null && only32.mask == null &&
                only32.sectionType == 0 && !only32.clipped && only32.blendMode == 0 && only32.opacity == 255
            ) {
                note16 = "rgb32"
                val all = ArrayList<ByteArray>(channels)
                all.addAll(p32.planes())
                while (all.size < channels) {
                    all.add(alphaFloat(only32.bitmap ?: only32.bitmapSource?.invoke(), width, height))
                }
                val rle32 = writeCompositeRle(all, width * 4, height, doc.isPsb)
                bos.write(rle32, 0, rle32.size)
                return
            }
        }
        if (depth != 8 && (mode != MODE_RGB || depth == 32)) {
            val only = doc.layers.singleOrNull()
            if (only != null && only.adjust == null && only.text == null && only.mask == null &&
                only.sectionType == 0 && !only.clipped && only.rawPix != null &&
                only.blendMode == 0 && only.opacity == 255
            ) {
                val colorChans = channelsOf(mode, channels)
                val planes = planesFromRawPix(only, width, height, colorChans, depth, doc.isPsb)
                note16 = if (planes == null) "direct-null" else "direct"
                if (planes != null) {
                    val all = ArrayList<ByteArray>(channels)
                    all.addAll(planes)
                    val bpsHere = PsdIo.bytesPerSample(depth)
                    while (all.size < channels) {
                        val a = ByteArray(width * bpsHere) { 0xFF.toByte() }
                        if (bpsHere == 4) {
                            // 32 位浮点的不透明是 float 1.0；0xFFFFFFFF 是 NaN
                            val one = java.lang.Float.floatToIntBits(1f)
                            var oa = 0
                            while (oa + 3 < a.size) {
                                a[oa] = (one ushr 24).toByte(); a[oa + 1] = (one ushr 16).toByte()
                                a[oa + 2] = (one ushr 8).toByte(); a[oa + 3] = one.toByte()
                                oa += 4
                            }
                        }
                        all.add(a)
                    }
                    val rle16 = writeCompositeRle(all, width * bpsHere, height, doc.isPsb)
                    bos.write(rle16, 0, rle16.size)
                    return
                }
            }
        }
        // 灰度 / CMYK 的 16 位：原始文件的 16 位样本 + 当前画面的余数拼回真 16 位
        // （只按 8 位算再乘 257 的话每级差 257，等于假 16 位）
        if (depth == 16 && (mode == MODE_GRAY || mode == MODE_CMYK) &&
            width.toLong() * height <= 4_000_000L
        ) {
            val colorCount = if (mode == MODE_CMYK) 4 else 1
            val rawChans = channelsOf(mode, doc.channels)
            // 优先用图层带进来的原始 16 位通道（导出时不会丢），合并图兜底
            val src16 = doc.layers.asSequence()
                .mapNotNull { planesFromPacked(it.pix16src, width, height, colorCount, 16, doc.isPsb) }
                .firstOrNull()
                ?: compositePlanes16(doc, width, height, rawChans, colorCount, doc.isPsb)
            if (src16 != null) {
                val merged16 = composite(width, height, doc.layers)
                val mpx = IntArray(width * height)
                merged16.getPixels(mpx, 0, width, 0, 0, width, height)
                merged16.recycle()
                val lut = doc.rawDoc?.lut
                val planes = ArrayList<ByteArray>(channels)
                fun put16(a: ByteArray, o: Int, v: Int) {
                    a[o] = (v ushr 8).toByte(); a[o + 1] = v.toByte()
                }
                // 只有「原始 16 位算出来的颜色跟当前画面一模一样」的像素才敢原样写回 16 位值，
                // 改过色的像素只能按当前 8 位写（CMYK/灰度没有 16 位编辑缓冲，这是唯一可靠判据）
                var kept = 0
                if (mode == MODE_CMYK) {
                    val p0 = ByteArray(width * height * 2)
                    val p1 = ByteArray(width * height * 2)
                    val p2 = ByteArray(width * height * 2)
                    val p3 = ByteArray(width * height * 2)
                    val s0 = src16[0]; val s1 = src16[1]; val s2 = src16[2]; val s3 = src16[3]
                    for (i in mpx.indices) {
                        val v = mpx[i]
                        val r8 = (v ushr 16) and 0xFF
                        val g8 = (v ushr 8) and 0xFF
                        val b8 = v and 0xFF
                        val o = i * 2
                        val oc = ((s0[o].toInt() and 0xFF) shl 8) or (s0[o + 1].toInt() and 0xFF)
                        val om = ((s1[o].toInt() and 0xFF) shl 8) or (s1[o + 1].toInt() and 0xFF)
                        val oy = ((s2[o].toInt() and 0xFF) shl 8) or (s2[o + 1].toInt() and 0xFF)
                        val ok = ((s3[o].toInt() and 0xFF) shl 8) or (s3[o + 1].toInt() and 0xFF)
                        val rgb = PsdIo.cmykToRgb(
                            lut,
                            (oc * 255 + 32767) / 65535,
                            (om * 255 + 32767) / 65535,
                            (oy * 255 + 32767) / 65535,
                            (ok * 255 + 32767) / 65535,
                        )
                        // 容差 ±1：解码时 16→8 位取整方式可能和这里的 cmykToRgb 差 1 级，
                        // 严格相等会让这些像素走 c8*257 的近似分支，往返一次就产生 0.001% 的漂移
                        val same = kotlin.math.abs(((rgb ushr 16) and 0xFF) - r8) <= 1 &&
                            kotlin.math.abs(((rgb ushr 8) and 0xFF) - g8) <= 1 &&
                            kotlin.math.abs((rgb and 0xFF) - b8) <= 1
                        if (same) {
                            kept++
                            put16(p0, o, oc); put16(p1, o, om); put16(p2, o, oy); put16(p3, o, ok)
                        } else {
                            val kk = 255 - maxOf(r8, g8, b8)
                            val c8: Int; val m8: Int; val y8: Int
                            if (kk >= 255) {
                                c8 = 0; m8 = 0; y8 = 0
                            } else {
                                c8 = (255 - r8 - kk) * 255 / (255 - kk)
                                m8 = (255 - g8 - kk) * 255 / (255 - kk)
                                y8 = (255 - b8 - kk) * 255 / (255 - kk)
                            }
                            put16(p0, o, c8 * 257); put16(p1, o, m8 * 257)
                            put16(p2, o, y8 * 257); put16(p3, o, kk * 257)
                        }
                    }
                    planes.add(p0); planes.add(p1); planes.add(p2); planes.add(p3)
                } else {
                    val p0 = ByteArray(width * height * 2)
                    val s0 = src16[0]
                    for (i in mpx.indices) {
                        val v = mpx[i]
                        val r8 = (v ushr 16) and 0xFF
                        val g8 = (v ushr 8) and 0xFF
                        val b8 = v and 0xFF
                        val o = i * 2
                        val orig = ((s0[o].toInt() and 0xFF) shl 8) or (s0[o + 1].toInt() and 0xFF)
                        val refY = (orig * 255 + 32767) / 65535
                        // 同样留 ±1 容差（16→8 取整不一致时不至于重新量化成 *257）
                        if (kotlin.math.abs(r8 - refY) <= 1 && kotlin.math.abs(g8 - refY) <= 1 &&
                            kotlin.math.abs(b8 - refY) <= 1
                        ) {
                            kept++
                            put16(p0, o, orig)
                        } else {
                            put16(p0, o, ((r8 * 299 + g8 * 587 + b8 * 114 + 500) / 1000) * 257)
                        }
                    }
                    planes.add(p0)
                }
                if (planes.size < channels) {
                    val a = ByteArray(width * height * 2)
                    for (i in mpx.indices) {
                        val av = ((mpx[i] ushr 24) and 0xFF) * 257
                        a[i * 2] = (av ushr 8).toByte(); a[i * 2 + 1] = av.toByte()
                    }
                    planes.add(a)
                }
                note16 = "planar16 keep=" + (kept * 100L / mpx.size) + "%"
                val rle = writeCompositeRle(planes, width * 2, height, doc.isPsb)
                bos.write(rle, 0, rle.size)
                return
            }
            note16 = "planar-noSrc"
        }
        // 文件头声明的通道数必须和这里写的条数完全一致，差一条 桌面端就从第二条通道起整体错位
        // （灰度/CMYK 以前固定只写 4 条，头里写的是 2/5，桌面端打开就是半透明乱码）
        note16 = "flat8 chans=" + channels + " mode=" + mode
        val merged = composite(width, height, doc.layers)
        val mpx = IntArray(width * height)
        merged.getPixels(mpx, 0, width, 0, 0, width, height)
        val bps = PsdIo.bytesPerSample(depth)
        val colorCount = when (mode) {
            MODE_CMYK -> 4
            MODE_GRAY -> 1
            else -> 3
        }
        val planes = ArrayList<ByteArray>(channels)
        if (mode == MODE_CMYK) {
            val cmyk = IntArray(mpx.size * 4)
            for (i in mpx.indices) {
                val v = mpx[i]
                val k = rgbToCmyk((v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
                cmyk[i * 4] = k[0]; cmyk[i * 4 + 1] = k[1]; cmyk[i * 4 + 2] = k[2]; cmyk[i * 4 + 3] = k[3]
            }
            for (ch in 0 until 4) {
                val d = ByteArray(mpx.size * bps)
                for (i in mpx.indices) writeSampleAt(d, i * bps, cmyk[i * 4 + ch], bps)
                planes.add(d)
            }
        } else if (mode == MODE_GRAY) {
            val d = ByteArray(mpx.size * bps)
            for (i in mpx.indices) {
                val v = mpx[i]
                val r = (v ushr 16) and 0xFF
                val g = (v ushr 8) and 0xFF
                val b = v and 0xFF
                writeSampleAt(d, i * bps, (r * 299 + g * 587 + b * 114 + 500) / 1000, bps)
            }
            planes.add(d)
        } else {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ByteArray(mpx.size * bps)
                for (i in mpx.indices) {
                    // 32 位文档的浮点是线性光，颜色通道要转换
                    writeSampleAt(d, i * bps, (mpx[i] ushr shift) and 0xFF, bps, linear = depth == 32)
                }
                planes.add(d)
            }
        }
        // alpha 是覆盖率，不转线性
        while (planes.size < channels && planes.size == colorCount) {
            val d = ByteArray(mpx.size * bps)
            for (i in mpx.indices) writeSampleAt(d, i * bps, (mpx[i] ushr 24) and 0xFF, bps)
            planes.add(d)
        }
        // 还有多出来的通道就补不透明
        while (planes.size < channels) {
            val a = ByteArray(width * bps)
            if (bps == 2) {
                java.util.Arrays.fill(a, 0xFF.toByte())
            } else if (bps == 4) {
                val one = java.lang.Float.floatToIntBits(1f)
                var o = 0
                while (o + 3 < a.size) {
                    a[o] = (one ushr 24).toByte(); a[o + 1] = (one ushr 16).toByte()
                    a[o + 2] = (one ushr 8).toByte(); a[o + 3] = one.toByte()
                    o += 4
                }
            } else {
                java.util.Arrays.fill(a, 0xFF.toByte())
            }
            planes.add(a)
        }
        merged.recycle()
        val rle = writeCompositeRle(planes, width * bps, height, doc.isPsb)
        bos.write(rle, 0, rle.size)
    }

    /** 非 RGB 16 位：没动过就照原始通道写回，避免掉回 8 位台阶 */
    private fun planesFromRawPix(
        l: PsdLayer,
        width: Int,
        height: Int,
        chans: Int,
        depth: Int,
        psb: Boolean,
    ): List<ByteArray>? = planesFromPacked(l.rawPix, width, height, chans, depth, psb)

    private fun planesFromPacked(
        packed: ByteArray?,
        width: Int,
        height: Int,
        chans: Int,
        depth: Int,
        psb: Boolean,
    ): List<ByteArray>? {
        val pix = unpackPix(packed) ?: return null
        val rw = pix.rect[3] - pix.rect[1]
        val rh = pix.rect[2] - pix.rect[0]
        if (rw != width || rh != height) return null
        val byId = HashMap<Int, ByteArray>(chans)
        for (ch in pix.channels) {
            if (ch.id < 0) continue
            val c = Cur(ch.data)
            val comp = if (c.left() >= 2) c.u16() else ch.compression
            val plane = runCatching {
                PsdIo.readChannel(c, rw, rh, depth, comp, psb, ch.data.size.toLong())
            }.getOrNull() ?: return null
            byId[ch.id] = plane
        }
        val out = ArrayList<ByteArray>(chans)
        for (k in 0 until chans) {
            val plane = byId[k] ?: if (k == chans - 1) byId[-1] else null
            out.add(plane ?: return null)
        }
        return out
    }

    /**
     * 灰度 / CMYK 的 16 位：把原始文件的合并图拆回 16 位大端平面（灰度 1 条、CMYK 4 条）。
     * 文件里可能有 alpha，帧格式的通道数比颜色通道多，所以要按 rawChans 读、只留前 colorChans 条。
     */
    private fun compositePlanes16(
        doc: Document,
        width: Int,
        height: Int,
        rawChans: Int,
        colorChans: Int,
        psb: Boolean,
    ): List<ByteArray>? {
        val rc = doc.rawDoc?.composite ?: return null
        if (doc.depth != 16 || rawChans <= 0 || colorChans <= 0) return null
        val rowBytes = width * 2
        val total = rowBytes * height
        val c = Cur(rc.data)
        if (c.left() < 2) return null
        val comp = c.u16()
        val planes = ArrayList<ByteArray>(rawChans)
        if (comp == 2 || comp == 3) {
            val expect = rawChans * total
            val flat = runCatching { PsdIo.inflateRaw(c.take(c.left()), expect) }.getOrNull() ?: return null
            if (flat.size < expect) return null
            if (comp == 3) undoZipPredict(flat, rawChans, height, rowBytes, 2)
            for (i in 0 until rawChans) {
                val plane = ByteArray(total)
                for (r in 0 until height) {
                    System.arraycopy(flat, (i * height + r) * rowBytes, plane, r * rowBytes, rowBytes)
                }
                planes.add(plane)
            }
        } else if (comp == 1) {
            val counts = IntArray(rawChans * height) { if (c.left() >= 2) c.u16() else 0 }
            for (i in 0 until rawChans) {
                val plane = runCatching {
                    PsdIo.readRleRows(c, counts, i * height, height, rowBytes)
                }.getOrNull() ?: return null
                if (plane.size < total) return null
                planes.add(plane)
            }
        } else {
            for (i in 0 until rawChans) {
                val plane = runCatching {
                    PsdIo.readChannel(c, width, height, 16, comp, psb, Long.MAX_VALUE)
                }.getOrNull() ?: return null
                if (plane.size < total) return null
                planes.add(plane)
            }
        }
        return if (planes.size >= colorChans) planes.subList(0, colorChans) else null
    }

    private fun writeSampleAt(dst: ByteArray, at: Int, v: Int, bps: Int, linear: Boolean = false) {
        when (bps) {
            1 -> dst[at] = v.toByte()
            2 -> { dst[at] = v.toByte(); dst[at + 1] = v.toByte() }
            else -> {
                val f = v / 255f
                val bits = java.lang.Float.floatToIntBits(if (linear) Pix32.toLinear(f) else f)
                dst[at] = (bits ushr 24).toByte(); dst[at + 1] = (bits ushr 16).toByte()
                dst[at + 2] = (bits ushr 8).toByte(); dst[at + 3] = bits.toByte()
            }
        }
    }

    private fun putSample(o: OutputStream, v: Int, bps: Int) {
        when (bps) {
            1 -> writeU8(o, v)
            2 -> { writeU8(o, v); writeU8(o, v) }
            4 -> {
                val bits = java.lang.Float.floatToIntBits(v / 255f)
                writeU8(o, (bits ushr 24) and 0xFF); writeU8(o, (bits ushr 16) and 0xFF)
                writeU8(o, (bits ushr 8) and 0xFF); writeU8(o, bits and 0xFF)
            }
        }
    }

    /**
     * 合并图（桌面端用来做缩略图 / 兼容预览的那张）。
     * 必须和编辑器里看到的一致：组的混合模式与整组不透明度、调整层、剪贴蒙版都算进去，
     * 不然导出的 PSD 在 桌面端里的预览和实际画面不是同一张。
     */
    private fun composite(width: Int, height: Int, layers: List<PsdLayer>): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

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

        fun paintFor(alpha: Int, mode: Int, base: Bitmap? = null): Paint {
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.alpha = alpha.coerceIn(0, 255)
            p.xfermode = if (mode == 0) null else blendOf(mode)
            if (base != null) {
                // 剪贴蒙版：只按下面那层的像素范围显示
                val m = Paint(Paint.FILTER_BITMAP_FLAG)
                m.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                return p
            }
            return p
        }

        fun drawRun(canvas: Canvas, target: Bitmap, from: Int, until: Int) {
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
                        if (head.opacity < 255 || head.blendMode != 0) {
                            val scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            drawRun(Canvas(scratch), scratch, i + 1, header)
                            canvas.drawBitmap(scratch, 0f, 0f, paintFor(head.opacity, head.blendMode))
                            scratch.recycle()
                        } else {
                            drawRun(canvas, target, i + 1, header)
                        }
                    }
                    i = header + 1
                    continue
                }
                if (base.sectionType == 1 || base.sectionType == 2) {
                    i++
                    continue
                }
                val adj = base.adjust
                if (adj != null) {
                    if (base.visible && !adj.isNeutral()) AdjustApply.applyInPlace(target, adj)
                    i++
                    continue
                }
                if (base.visible) {
                    val bmp = base.bitmap ?: base.bitmapSource?.invoke()
                    if (bmp != null) canvas.drawBitmap(bmp, 0f, 0f, paintFor(base.opacity, base.blendMode))
                }
                var j = i + 1
                while (j < until && layers[j].clipped) {
                    val child = layers[j]
                    val childBmp = child.bitmap ?: child.bitmapSource?.invoke()
                    val baseBmp = base.bitmap ?: base.bitmapSource?.invoke()
                    if (base.visible && child.visible && childBmp != null && baseBmp != null) {
                        canvas.saveLayer(null, null)
                        canvas.drawBitmap(childBmp, 0f, 0f, paintFor(child.opacity, child.blendMode))
                        val mp = Paint(Paint.FILTER_BITMAP_FLAG)
                        mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        canvas.drawBitmap(baseBmp, 0f, 0f, mp)
                        canvas.restore()
                    }
                    j++
                }
                i = j
            }
        }

        drawRun(Canvas(out), out, 0, layers.size)
        return out
    }

    private fun blendOf(mode: Int): PorterDuffXfermode? = when (mode) {
        1 -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        2 -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        3 -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        6 -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
        7 -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
        else -> null
    }

    private fun patchLen(out: ByteArrayOutputStream, at: Int, len: Long, psb: Boolean) {
        val bytes = out.toByteArray()
        when {
            psb -> {
                var v = len
                for (i in 7 downTo 0) { bytes[at + i] = (v and 0xFF).toByte(); v = v ushr 8 }
            }
            else -> {
                bytes[at] = ((len ushr 24) and 0xFF).toByte()
                bytes[at + 1] = ((len ushr 16) and 0xFF).toByte()
                bytes[at + 2] = ((len ushr 8) and 0xFF).toByte()
                bytes[at + 3] = (len and 0xFF).toByte()
            }
        }
        out.reset()
        out.write(bytes, 0, bytes.size)
    }

    private fun writeU8(o: OutputStream, v: Int) { o.write(v and 0xFF) }
    private fun writeU16(o: OutputStream, v: Int) {
        o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
    }
    private fun writeU32(o: OutputStream, v: Long, psb: Boolean = false) {
        if (psb) { writeU64(o, v); return }
        o.write(((v ushr 24) and 0xFF).toInt()); o.write(((v ushr 16) and 0xFF).toInt())
        o.write(((v ushr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt())
    }
    private fun writeU64(o: OutputStream, v: Long) {
        for (i in 7 downTo 0) o.write(((v ushr (i * 8)) and 0xFF).toInt())
    }
    private fun writeAscii(o: OutputStream, s: String) {
        for (ch in s) o.write(ch.code and 0xFF)
    }
}