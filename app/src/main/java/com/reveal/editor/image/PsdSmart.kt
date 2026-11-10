package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 智能对象：把 PSD 里「置入的原图」抠出来。
 *
 * 桌面端存置入图的位置有两处：
 *  - 图层附加块 `SoLd`：谁是谁、原图多大、四个角落在画布哪里（描述符）
 *  - 文档级附加块 `lnk2`：真正的文件字节（uuid → 文件内容）
 * 两边靠 uuid 对上。抠出来以后这一层在 App 里就是真智能对象：能换内容、反复缩放不糊。
 */
internal class SmartPlaced(
    /** 链接文件的 uuid（和 lnk2 的条目对号） */
    val linkId: String,
    /** 原图尺寸（Sz 里的 Wdth/Hght；没有就是 0） */
    val origW: Int,
    val origH: Int,
)

internal object PsdSmart {

    private const val TAG = "soLD"

    /** 只认能直接解码的格式（PNG/JPEG）；PSB/TIFF 之类的置入图先跳过，图层照样能用 */
    fun decode(bytes: ByteArray): Bitmap? {
        if (!looksLikeImage(bytes)) return null
        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        if (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte()) return true
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return true
        return false
    }

    /**
     * SoLd：4 字节标记 `soLD` + 4 字节版本 + 描述符。
     * 描述符是 桌面端的「第二个方言」：键不是 UTF-16 字符串，而是 4 字节长度 + ASCII（长度为 0 时读 4 字节）。
     */
    fun parsePlaced(raw: ByteArray): SmartPlaced? {
        if (raw.size < 16) return null
        if (String(raw, 0, 4, Charsets.US_ASCII) != TAG) return null
        val r = Rd(raw)
        r.p = 8
        val d = runCatching { descriptor(r, hasVersion = true) }.getOrNull() ?: return null
        val id = (d["Idnt"] as? String)?.trimEnd('\u0000') ?: return null
        if (id.isEmpty()) return null
        val sz = d["Sz  "] as? Map<*, *>
        val w = num(sz?.get("Wdth"))
        val h = num(sz?.get("Hght"))
        return SmartPlaced(id, w, h)
    }

    /**
     * lnk2（链接的文件）：一串「8 字节长度 + 条目」，条目里 liFD 表示文件内容直接内嵌。
     * 返回 uuid → 文件字节。
     */
    fun parseLinked(raw: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val r = Rd(raw)
        while (r.left() >= 8) {
            val len = r.u64()
            if (len <= 0 || len > r.left()) break
            val body = r.take(len.toInt())
            val pad = ((4 - (len % 4)) % 4).toInt()
            if (pad > 0) r.skip(pad)
            val e = parseEntry(body) ?: continue
            out[e.first] = e.second
        }
        return out
    }

    private fun parseEntry(d: ByteArray): Pair<String, ByteArray>? {
        val r = Rd(d)
        val kind = r.ascii(4)
        r.u32()
        val uuid = r.pascal()
        r.uni()
        r.ascii(4)
        r.ascii(4)
        val dataSize = r.u64()
        val openFlag = r.u8()
        if (kind != "liFD") return null
        if (dataSize <= 0 || dataSize > d.size) return null
        val size = dataSize.toInt()
        // 条目里 openFlag 时会先跟一段描述符，靠解析它定位很脆（桌面端的方言版本多）。
        // 原图一定是 PNG/JPEG，长度就是 dataSize，直接按文件头找，找到就以它为准。
        val found = findImageStart(d, r.p, size)
        val bytes = when {
            found >= 0 -> d.copyOfRange(found, found + size)
            else -> {
                if (openFlag != 0) runCatching { descriptor(r, hasVersion = true) }
                if (dataSize > r.left()) return null
                r.take(size)
            }
        }
        if (!looksLikeImage(bytes)) return null
        return uuid to bytes
    }

    /** 在条目里找内嵌原图的起点：PNG `89 50 4E 47` 或 JPEG `FF D8 FF`，且后面还有 dataSize 字节 */
    private fun findImageStart(d: ByteArray, from: Int, size: Int): Int {
        var i = from.coerceAtLeast(0)
        val last = d.size - size
        while (i <= last - 3) {
            if (d[i] == 0x89.toByte() && d[i + 1] == 0x50.toByte() &&
                d[i + 2] == 0x4E.toByte() && d[i + 3] == 0x47.toByte()
            ) return i
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte() && d[i + 2] == 0xFF.toByte()) return i
            i++
        }
        return -1
    }

    private fun num(v: Any?): Int = when (v) {
        is Double -> v.toInt()
        is Int -> v
        is Number -> v.toInt()
        else -> 0
    }

    /** 读描述符：版本(可省) + 名字 + 类名 + 条目 */
    private fun descriptor(r: Rd, hasVersion: Boolean): Map<String, Any?> {
        if (hasVersion) r.u32()
        r.uni()
        r.key()
        val count = r.u32().coerceAtMost(4096L).toInt()
        val map = LinkedHashMap<String, Any?>(count)
        for (i in 0 until count) {
            val k = r.key()
            val type = r.ascii(4)
            map[k] = value(r, type)
        }
        return map
    }

    private fun value(r: Rd, type: String): Any? = when (type) {
        "Objc", "GlbO", "type" -> descriptor(r, hasVersion = false)
        "VlLs" -> {
            val n = r.u32().coerceAtMost(4096L).toInt()
            val list = ArrayList<Any?>(n)
            for (i in 0 until n) list.add(value(r, r.ascii(4)))
            list
        }
        "doub" -> r.f64()
        "comp" -> r.f64()
        "UntF" -> {
            r.ascii(4)
            r.f64()
        }
        "long" -> r.u64()
        "bool" -> r.u8() != 0
        "TEXT" -> r.uni()
        "enum" -> {
            r.key()
            r.key()
        }
        // 类别引用（CLASS1）读的是「长度 + 键」，不是一个描述符
        "type" -> r.key()
        "tdta" -> r.take(r.u32().coerceIn(0L, r.left().toLong()).toInt())
        "alis" -> r.take(r.u32().coerceIn(0L, r.left().toLong()).toInt())
        else -> null
    }

    /** 读字节的小工具，越界一律给 0，不抛异常 */
    private class Rd(val b: ByteArray) {
        var p = 0
        fun left(): Int = b.size - p
        fun u8(): Int = if (p < b.size) (b[p++].toInt() and 0xFF) else 0
        fun u32(): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or u8().toLong()
            return v
        }
        fun i32(): Int = u32().toInt()
        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or u8().toLong()
            return v
        }
        fun f64(): Double {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or u8().toLong()
            return java.lang.Double.longBitsToDouble(v)
        }
        fun ascii(n: Int): String {
            val sb = StringBuilder(n)
            for (i in 0 until n) sb.append(u8().toChar())
            return sb.toString()
        }
        fun uni(): String {
            val n = u32().coerceIn(0L, 4096L).toInt()
            val sb = StringBuilder(n)
            for (i in 0 until n) {
                val hi = u8()
                val lo = u8()
                sb.append(((hi shl 8) or lo).toChar())
            }
            return sb.toString()
        }
        fun key(): String {
            val n = u32()
            val len = if (n <= 0) 4 else n.coerceAtMost(left().toLong()).toInt()
            return ascii(len)
        }
        fun pascal(): String {
            val n = u8().coerceAtMost(left())
            return ascii(n)
        }
        fun take(n: Int): ByteArray {
            val k = n.coerceIn(0, left())
            val out = ByteArray(k)
            System.arraycopy(b, p, out, 0, k)
            p += k
            return out
        }
        fun skip(n: Int) {
            p += n.coerceIn(0, left())
        }
    }
}