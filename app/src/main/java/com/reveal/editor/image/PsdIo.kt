package com.reveal.editor.image

import java.util.zip.Inflater
import kotlin.math.floor

/**
 * PSD 底层字节工具：游标、通道解压（raw / RLE / ZIP）、样本转 8 位、ICC 色彩转换。
 *
 * 只做和字节有关的事，不含图层语义，方便单独测。
 */
internal class Cur(val b: ByteArray, private val map: java.nio.MappedByteBuffer? = null) {
    var p = 0

    /** 总字节数：内存数组或映射文件都算 */
    val size: Int get() = map?.limit() ?: b.size

    // 映射文件是 off-heap 的，但 m.get(i) 是一次 JNI 调用：RLE 逐字节解时 4096 的图要调几千万次，
    // 打开一张大图就变成几分钟。这里加一个 256KB 的滑动窗口，顺序读只在外层换块时才碰一次 JNI。
    private var win = ByteArray(0)
    private var winStart = 0

    private fun fillWin(at: Int) {
        val m = map ?: return
        val size = m.limit()
        val start = (at - (at % 4096)).coerceAtLeast(0)
        val n = minOf(1 shl 18, size - start)
        if (n <= 0) {
            win = ByteArray(0)
            winStart = 0
            return
        }
        val arr = ByteArray(n)
        val d = m.duplicate()
        d.position(start)
        d.get(arr, 0, n)
        win = arr
        winStart = start
    }

    /** 取第 i 个字节（越界给 0）。映射文件时走窗口，不占 Java 堆 */
    fun byteInt(i: Int): Int {
        val m = map
        if (m != null) {
            if (i < 0 || i >= m.limit()) return 0
            val off = i - winStart
            if (off >= 0 && off < win.size) return win[off].toInt() and 0xFF
            fillWin(i)
            val off2 = i - winStart
            return if (off2 >= 0 && off2 < win.size) win[off2].toInt() and 0xFF else 0
        }
        return if (i >= 0 && i < b.size) b[i].toInt() and 0xFF else 0
    }

    /** 取一段字节（越界自动截断） */
    fun copyRange(from: Int, to: Int): ByteArray {
        val s = from.coerceIn(0, size)
        val e = to.coerceIn(s, size)
        val n = e - s
        if (n <= 0) return ByteArray(0)
        val m = map
        if (m != null) {
            val out = ByteArray(n)
            // 注意：ByteBuffer.get(int, byte[], int, int)（绝对位置批量读）是 Java 13 才加的，
            // 部分 ROM 上会抛 NoSuchMethodError（之前 37MB 的 PSD 打不开就是这个）。
            // duplicate + 相对位置读是 API 1 就有的，等价且安全。
            val d = m.duplicate()
            d.position(s)
            d.get(out, 0, n)
            return out
        }
        return b.copyOfRange(s, e)
    }

    fun u8(): Int = if (map == null) b[p++].toInt() and 0xFF else byteInt(p++)
    fun u16(): Int = (u8() shl 8) or u8()
    fun i16(): Int = u16().let { if (it > 0x7FFF) it - 0x10000 else it }

    fun u32(): Long {
        var v = 0L
        repeat(4) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun take(n: Int): ByteArray {
        val real = n.coerceIn(0, (size - p).coerceAtLeast(0))
        val r = copyRange(p, p + real)
        p += real
        return if (r.size == n) r else r.copyOf(n)
    }

    fun ascii(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(byteInt(p + i).toChar())
        p += n
        return sb.toString()
    }

    fun skip(n: Long) {
        p += n.toInt()
        if (p < 0) p = 0
        if (p > size) p = size
    }

    fun left(): Int = (size - p).coerceAtLeast(0)
}

internal object PsdIo {

    class Header(
        val version: Int,
        val channels: Int,
        val height: Int,
        val width: Int,
        val depth: Int,
        val mode: Int,
    )

    const val MODE_BITMAP = 0
    const val MODE_GRAY = 1
    const val MODE_INDEXED = 2
    const val MODE_RGB = 3
    const val MODE_CMYK = 4
    const val MODE_MULTICHANNEL = 7
    const val MODE_DUOTONE = 8
    const val MODE_LAB = 9

    fun bytesPerSample(depth: Int): Int = when (depth) {
        1, 8 -> 1
        16 -> 2
        32 -> 4
        else -> 0
    }

    fun header(c: Cur): Header? {
        if (c.size < 26) return null
        if (c.ascii(4) != "8BPS") return null
        val version = c.u16()
        c.skip(6)
        val ch = c.u16()
        val h = c.u32().toInt()
        val w = c.u32().toInt()
        val depth = c.u16()
        val mode = c.u16()
        if (w <= 0 || h <= 0 || w > 60000 || h > 60000) return null
        if (bytesPerSample(depth) == 0) return null
        return Header(version, ch, h, w, depth, mode)
    }

    /**
     * 读一条通道。c 当前位置正好在 compression 字段上（u16），dataLen 是含 compression 的总长度。
     * 返回按样本交织的大端原始字节；ZIP+prediction 的内部布局是「按字节平面分开」的，
     * 这里已经转回交织，调用方用 samples() 直接取值即可。
     */
    fun readChannel(c: Cur, w: Int, h: Int, depth: Int, comp: Int, psb: Boolean, dataLen: Long): ByteArray {
        val bps = bytesPerSample(depth).coerceAtLeast(1)
        val total = w * h * bps
        return when (comp) {
            0 -> {
                val n = minOf((dataLen - 2).coerceAtLeast(0), total.toLong()).toInt()
                val out = ByteArray(total)
                val got = c.take(n)
                System.arraycopy(got, 0, out, 0, minOf(got.size, total))
                out
            }
            1 -> readRle(c, w, h, bps, psb)
            2, 3 -> readZip(c, (dataLen - 2).coerceAtLeast(0), w, h, bps, comp == 3)
            else -> ByteArray(total)
        }
    }

    private fun readRle(c: Cur, w: Int, h: Int, bps: Int, psb: Boolean): ByteArray {
        val rowBytes = w * bps
        val out = ByteArray(rowBytes * h)
        val counts = IntArray(h) { if (psb) c.u32().toInt() else c.u16() }
        var need = 0
        for (y in 0 until h) need += counts[y]
        // 先把这段压缩数据整体拷进内存：映射文件逐字节读是几千万次 JNI，一张 4096 就要几分钟
        val payload = if (need > 0) c.take(need) else ByteArray(0)
        var pi = 0
        for (y in 0 until h) {
            var x = 0
            val base = y * rowBytes
            val end = minOf(pi + counts[y], payload.size)
            while (x < rowBytes && pi < end) {
                val n = payload[pi++].toInt() and 0xFF
                if (n < 128) {
                    var cnt = n + 1
                    while (cnt > 0 && x < rowBytes && pi < payload.size) {
                        out[base + x++] = payload[pi++]
                        cnt--
                    }
                } else if (n > 128) {
                    val cnt = 257 - n
                    if (pi < payload.size) {
                        val v = payload[pi++]
                        var k = cnt
                        while (k > 0 && x < rowBytes) {
                            out[base + x++] = v
                            k--
                        }
                    }
                }
            }
            pi = end
        }
        return out
    }

    /**
     * 合并图的 RLE：行表已经在外层一次性读完（通道 × 行 连续存放），这里只解第 base 行起的 rows 行。
     * 以前按「每通道一份行表」去解合并图，从第二个通道起整体错位，行长度被读成几千万，直接越界崩。
     */
    fun readRleRows(c: Cur, counts: IntArray, base: Int, rows: Int, rowBytes: Int): ByteArray {
        val out = ByteArray(rowBytes * rows)
        var need = 0
        for (y in 0 until rows) need += counts[base + y]
        val payload = if (need > 0) c.take(need) else ByteArray(0)
        var pi = 0
        for (y in 0 until rows) {
            var x = 0
            val b0 = y * rowBytes
            val end = minOf(pi + counts[base + y], payload.size)
            while (x < rowBytes && pi < end) {
                val n = payload[pi++].toInt() and 0xFF
                if (n < 128) {
                    var cnt = n + 1
                    while (cnt > 0 && x < rowBytes && pi < payload.size) {
                        out[b0 + x++] = payload[pi++]
                        cnt--
                    }
                } else if (n > 128) {
                    val cnt = 257 - n
                    if (pi < payload.size) {
                        val v = payload[pi++]
                        var k = cnt
                        while (k > 0 && x < rowBytes) {
                            out[b0 + x++] = v
                            k--
                        }
                    }
                }
            }
            pi = end
        }
        return out
    }

    private fun readZip(c: Cur, payloadLen: Long, w: Int, h: Int, bps: Int, predicted: Boolean): ByteArray {
        val total = w * h * bps
        // payloadLen 可能是 Long.MAX_VALUE，toInt 会变负数再抛 NegativeArraySizeException，先夹住
        val payload = c.take(payloadLen.coerceIn(0L, c.left().toLong()).toInt())
        val buf = inflate(payload, total)
        if (!predicted) return buf.copyOf(total)
        val work = buf.copyOf(total)
        if (bps == 1) {
            for (y in 0 until h) {
                val b0 = y * w
                for (x in 1 until w) work[b0 + x] = (work[b0 + x] + work[b0 + x - 1]).toByte()
            }
        } else if (bps == 2) {
            // 16 位：解压出来是「交织」的像素对（高字节、低字节、高字节、低字节…），
            // 预测是 16 位整数按行累加（&0xffff）。照 ag-psd readDataZip 的 bpv==2 分支写。
            // 以前这里按「字节平面」去预测，16 位文件（桌面端存 16 位默认 ZIP+预测）会解成一片垃圾。
            val row = w * bps
            for (y in 0 until h) {
                var prev = 0
                var p = y * row
                for (x in 0 until w) {
                    val hi = work[p].toInt() and 0xFF
                    val lo = work[p + 1].toInt() and 0xFF
                    var v = (hi shl 8) or lo
                    v = (prev + v) and 0xFFFF
                    prev = v
                    work[p] = ((v ushr 8) and 0xFF).toByte()
                    work[p + 1] = (v and 0xFF).toByte()
                    p += 2
                }
            }
        } else {
            // 32 位反过来：预测数据是「字节平面序」（先所有第 1 字节、再所有第 2 字节…）
            val plane = w * h
            for (pl in 0 until bps) {
                val off = pl * plane
                for (y in 0 until h) {
                    val b0 = off + y * w
                    if (b0 + w > work.size) continue
                    for (x in 1 until w) work[b0 + x] = (work[b0 + x] + work[b0 + x - 1]).toByte()
                }
            }
        }
        return work
    }

    private fun inflate(payload: ByteArray, expect: Int): ByteArray {
        val out = ByteArray(expect)
        var off = 0
        val inf = Inflater()
        try {
            inf.setInput(payload)
            var guard = 0
            while (!inf.finished() && off < expect && guard < 1 shl 20) {
                val n = inf.inflate(out, off, expect - off)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                off += n
                guard++
            }
        } catch (t: Throwable) {
            android.util.Log.e("RevealPsd", "zip inflate: ${t.message}")
        } finally {
            inf.end()
        }
        return if (off == expect) out else out.copyOf(off)
    }

    /** 交织样本 → 0..255 的整数值 */
    /** 给 Psd.kt 解 ZIP 压缩的合并图用（内部就是 frame 解压）*/
    internal fun inflateRaw(payload: ByteArray, expect: Int): ByteArray = inflate(payload, expect)

    fun samples(buf: ByteArray, count: Int, depth: Int, srgb: Boolean = false): IntArray {
        val out = IntArray(count)
        when (depth) {
            1 -> for (i in 0 until count) out[i] = (buf.getOrElse(i) { 0 }.toInt() and 0xFF)
            8 -> for (i in 0 until count) out[i] = (buf.getOrElse(i) { 0 }.toInt() and 0xFF)
            16 -> for (i in 0 until count) {
                val hi = buf.getOrElse(i * 2) { 0 }.toInt() and 0xFF
                val lo = buf.getOrElse(i * 2 + 1) { 0 }.toInt() and 0xFF
                // 16 位降到 8 位用四舍五入：直接砍掉低字节会整体偏暗半级，渐变上能看出接线
                out[i] = (((hi shl 8) or lo) * 255 + 32767) / 65535
            }
            32 -> for (i in 0 until count) {
                var bits = 0
                for (k in 0 until 4) bits = (bits shl 8) or (buf.getOrElse(i * 4 + k) { 0 }.toInt() and 0xFF)
                val f = Float.fromBits(bits).coerceIn(0f, 1f)
                // 32 位 PSD 的颜色通道存的是线性光；显示用的 8 位是 sRGB 编码值
                val enc = if (srgb) Pix32.toSrgb(f) else f
                out[i] = (enc * 255f + 0.5f).toInt()
            }
        }
        return out
    }

    /** 1 位通道（bitmap 模式）按行解包成 0/255 */
    fun unpackBits(buf: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val rowBytes = (w + 7) / 8
        for (y in 0 until h) {
            for (x in 0 until w) {
                val byteIdx = y * rowBytes + x / 8
                val bit = 7 - (x % 8)
                val v = buf.getOrElse(byteIdx) { 0 }.toInt()
                out[y * w + x] = if ((v shr bit) and 1 == 1) 255 else 0
            }
        }
        return out
    }

    // ---------------------------------------------------------------- ICC

    class Lut(
        val inCh: Int,
        val outCh: Int,
        val grid: Int,
        private val inTables: Array<ShortArray>?,
        private val clut: ShortArray?,
        private val outTables: Array<ShortArray>?,
    ) {
        fun apply(src: IntArray): IntArray {
            val n = inCh
            val pos = FloatArray(n)
            for (i in 0 until n) {
                var v = src.getOrElse(i) { 0 }.toFloat() / 65535f
                val t = inTables?.getOrNull(i)
                if (t != null && t.size > 1) {
                    val f = v * (t.size - 1)
                    val i0 = floor(f).toInt().coerceIn(0, t.size - 2)
                    val fr = (f - i0).coerceIn(0f, 1f)
                    val a = (t[i0].toInt() and 0xFFFF).toFloat()
                    val b = (t[i0 + 1].toInt() and 0xFFFF).toFloat()
                    v = (a * (1 - fr) + b * fr) / 65535f
                }
                pos[i] = v.coerceIn(0f, 1f)
            }
            if (clut == null) return IntArray(outCh) { 0 }
            val g = grid
            val idx = IntArray(n)
            val fr = FloatArray(n)
            for (i in 0 until n) {
                val f = pos[i] * (g - 1)
                val i0 = floor(f).toInt().coerceIn(0, g - 1)
                idx[i] = i0
                fr[i] = (f - i0).coerceIn(0f, 1f)
            }
            val acc = FloatArray(outCh)
            val corners = 1 shl n
            for (cn in 0 until corners) {
                var wgt = 1f
                var off = 0L
                var mul = 1L
                for (d in 0 until n) {
                    val plus = (cn ushr d) and 1
                    val ii = (idx[d] + plus).coerceAtMost(g - 1)
                    wgt *= if (plus == 1) fr[d] else (1f - fr[d])
                    off += ii * mul
                    mul *= g
                }
                if (wgt == 0f) continue
                val base = (off * outCh).toInt()
                for (o in 0 until outCh) {
                    val v = clut.getOrElse(base + o) { 0 }.toInt() and 0xFFFF
                    acc[o] += wgt * v
                }
            }
            val res = IntArray(outCh)
            for (o in 0 until outCh) {
                var v = acc[o].coerceIn(0f, 65535f)
                val t = outTables?.getOrNull(o)
                if (t != null && t.size > 1) {
                    val f = v / 65535f * (t.size - 1)
                    val i0 = floor(f).toInt().coerceIn(0, t.size - 2)
                    val f2 = (f - i0).coerceIn(0f, 1f)
                    val a = (t[i0].toInt() and 0xFFFF).toFloat()
                    val b = (t[i0 + 1].toInt() and 0xFFFF).toFloat()
                    v = a * (1 - f2) + b * f2
                }
                res[o] = v.toInt().coerceIn(0, 65535)
            }
            return res
        }
    }

    fun parseIcc(bytes: ByteArray?): Lut? {
        if (bytes == null || bytes.size < 132) return null
        try {
            val c = Cur(bytes)
            c.skip(128)
            val count = c.u32().toInt()
            if (count <= 0 || count > 2000) return null
            var off = -1
            var size = 0
            for (i in 0 until count) {
                if (c.left() < 12) break
                val sig = c.ascii(4)
                val o = c.u32().toInt()
                val s = c.u32().toInt()
                if ((sig == "A2B0" || sig == "A2B1") && off < 0 && o > 0 && s > 0 && o + s <= bytes.size) {
                    off = o
                    size = s
                }
            }
            if (off < 0) return null
            return parseLut(bytes.copyOfRange(off, off + size))
        } catch (t: Throwable) {
            android.util.Log.e("RevealPsd", "icc parse: ${t.message}")
            return null
        }
    }

    private fun parseLut(tag: ByteArray): Lut? {
        val c = Cur(tag)
        val type = c.ascii(4)
        var inCh = 0
        var outCh = 0
        var grid = 0
        var inEntries = 0
        var outEntries = 0
        if (type == "mft2") {
            c.skip(4)
            inCh = c.u8()
            outCh = c.u8()
            grid = c.u8()
            c.u8()
            c.skip(36)
            inEntries = c.u16()
            outEntries = c.u16()
            if (inCh <= 0 || inCh > 8 || outCh <= 0 || outCh > 8 || grid <= 1) return null
            val inT = Array(inCh) { ShortArray(inEntries) { c.u16().toShort() } }
            val clutSize = 1
            var blocks = 1
            repeat(inCh) { blocks *= grid }
            val clut = ShortArray(blocks * outCh) { c.u16().toShort() }
            val outT = Array(outCh) { ShortArray(outEntries) { c.u16().toShort() } }
            return Lut(inCh, outCh, grid, inT, clut, outT)
        }
        if (type == "mft1") {
            c.skip(4)
            inCh = c.u8()
            outCh = c.u8()
            grid = c.u8()
            c.u8()
            c.skip(36)
            if (inCh <= 0 || inCh > 8 || outCh <= 0 || outCh > 8 || grid <= 1) return null
            val inT = Array(inCh) { ShortArray(256) { (c.u8() * 257).toShort() } }
            var blocks = 1
            repeat(inCh) { blocks *= grid }
            val clut = ShortArray(blocks * outCh) { (c.u8() * 257).toShort() }
            val outT = Array(outCh) { ShortArray(256) { (c.u8() * 257).toShort() } }
            return Lut(inCh, outCh, grid, inT, clut, outT)
        }
        return null
    }

    /** CMYK(0..255) → RGB。有 ICC 就走 ICC，没有就退回 桌面端常用的近似公式并只打一次日志 */
    fun cmykToRgb(lut: Lut?, c: Int, m: Int, y: Int, k: Int): Int {
        if (lut != null && lut.inCh == 4) {
            val src = intArrayOf(c * 257, m * 257, y * 257, k * 257)
            val out = lut.apply(src)
            return android.graphics.Color.rgb(
                (out[0] ushr 8).coerceIn(0, 255),
                (out[1] ushr 8).coerceIn(0, 255),
                (out[2] ushr 8).coerceIn(0, 255),
            )
        }
        val r = 255 - minOf(255, c + k * (255 - c) / 255)
        val g = 255 - minOf(255, m + k * (255 - m) / 255)
        val b = 255 - minOf(255, y + k * (255 - y) / 255)
        return android.graphics.Color.rgb(r, g, b)
    }
}