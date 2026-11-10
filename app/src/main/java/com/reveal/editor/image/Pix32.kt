package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 图层的 32 位浮点像素缓冲（每通道 0..1 的浮点，超出 1 也能存）。
 *
 * 和 [Pix16] 是同一套思路：安卓画布只有 8 位，画的时候仍按 8 位落笔，
 * 这一份是「精度备份」——笔迹落下后用这一笔的覆盖率在浮点空间重混，
 * 于是 32 位文档画过之后导出不会掉成 256 级台阶。
 *
 * 只在 32 位文档里、第一次改动某一层时才建（见 SketchView.ensurePix32），不画不占内存。
 */
class Pix32(val width: Int, val height: Int) {

    val r = FloatArray(width * height)
    val g = FloatArray(width * height)
    val b = FloatArray(width * height)

    /** 自上次存盘/导出后有没有变过 */
    var dirty: Boolean = false

    fun fillFrom(bmp: Bitmap) {
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        for (i in px.indices) {
            val c = px[i]
            r[i] = Color.red(c) / 255f
            g[i] = Color.green(c) / 255f
            b[i] = Color.blue(c) / 255f
        }
        dirty = true
    }

    fun snapshot(x: Int, y: Int, w: Int, h: Int): FloatArray? {
        if (w <= 0 || h <= 0) return null
        val out = FloatArray(w * h * 3)
        var o = 0
        for (row in 0 until h) {
            var i = (y + row) * width + x
            for (col in 0 until w) {
                out[o++] = r[i]
                out[o++] = g[i]
                out[o++] = b[i]
                i++
            }
        }
        return out
    }

    fun restore(x: Int, y: Int, w: Int, h: Int, data: FloatArray) {
        var o = 0
        for (row in 0 until h) {
            var i = (y + row) * width + x
            for (col in 0 until w) {
                r[i] = data[o++]
                g[i] = data[o++]
                b[i] = data[o++]
                i++
            }
        }
        dirty = true
    }

    /**
     * 按笔迹缓冲的覆盖率直接混色（和 Pix16.applyCoverage 等价，只是精度是浮点）。
     * cover 里 alpha = 压感/软边，RGB = 笔刷实际颜色。
     */
    fun applyCoverage(x: Int, y: Int, w: Int, h: Int, cover: IntArray, opacity: Float) {
        val op = opacity.coerceIn(0f, 1f)
        if (op <= 0f) return
        var touched = false
        for (row in 0 until h) {
            val py = y + row
            if (py < 0 || py >= height) continue
            var si = row * w
            for (col in 0 until w) {
                val pxARGB = cover[si]
                si++
                val a8 = (pxARGB ushr 24) and 0xFF
                if (a8 == 0) continue
                val pxx = x + col
                if (pxx < 0 || pxx >= width) continue
                val a = (a8 / 255f) * op
                if (a <= 0f) continue
                val di = py * width + pxx
                // 笔迹缓冲是预乘 alpha 的：半透明处的 RGB 被乘过一遍，先还原再混
                val unp = if (a8 < 255) 255f / a8 else 1f
                val sr = (((pxARGB ushr 16) and 0xFF) * unp).coerceAtMost(255f) / 255f
                val sg = (((pxARGB ushr 8) and 0xFF) * unp).coerceAtMost(255f) / 255f
                val sb = ((pxARGB and 0xFF) * unp).coerceAtMost(255f) / 255f
                r[di] += (sr - r[di]) * a
                g[di] += (sg - g[di]) * a
                b[di] += (sb - b[di]) * a
                touched = true
            }
        }
        if (touched) dirty = true
    }

    /** 纯色填充：8 位里变过的像素直接写成填充色（不透明处就是精确值） */
    fun applySolidDiff(x: Int, y: Int, w: Int, h: Int, before: IntArray?, after: IntArray?) {
        if (before == null || after == null) return
        var touched = false
        for (row in 0 until h) {
            val py = y + row
            var si = row * w
            for (col in 0 until w) {
                val p0 = before[si]
                val p1 = after[si]
                si++
                val pxx = x + col
                if (p0 != p1 && py >= 0 && py < height && pxx >= 0 && pxx < width) {
                    val di = py * width + pxx
                    if (Color.alpha(p1) > 0) {
                        r[di] = Color.red(p1) / 255f
                        g[di] = Color.green(p1) / 255f
                        b[di] = Color.blue(p1) / 255f
                        touched = true
                    }
                }
            }
        }
        if (touched) dirty = true
    }

    /**
     * 明暗类操作（减淡 / 加深）：8 位结果等价于「按通道比例缩放」，把同一个比例乘到浮点上。
     */
    fun applyRatioDiff(x: Int, y: Int, w: Int, h: Int, before: IntArray, after: IntArray) {
        var touched = false
        for (row in 0 until h) {
            val py = y + row
            if (py < 0 || py >= height) continue
            var si = row * w
            for (col in 0 until w) {
                val p0 = before[si]
                val p1 = after[si]
                si++
                if (p0 == p1) continue
                val pxx = x + col
                if (pxx < 0 || pxx >= width) continue
                val di = py * width + pxx
                val r0 = Color.red(p0)
                val g0 = Color.green(p0)
                val b0 = Color.blue(p0)
                val kr = if (r0 > 0) Color.red(p1).toFloat() / r0 else 1f
                val kg = if (g0 > 0) Color.green(p1).toFloat() / g0 else 1f
                val kb = if (b0 > 0) Color.blue(p1).toFloat() / b0 else 1f
                r[di] = (r[di] * kr).coerceIn(0f, 1f)
                g[di] = (g[di] * kg).coerceIn(0f, 1f)
                b[di] = (b[di] * kb).coerceIn(0f, 1f)
                touched = true
            }
        }
        if (touched) dirty = true
    }

    /** 导出用：三条通道的 32 位浮点大端字节 */
    fun planes(): List<ByteArray> {
        val n = width * height
        val out = ArrayList<ByteArray>(3)
        for (p in 0 until 3) {
            val src = when (p) {
                0 -> r
                1 -> g
                else -> b
            }
            val bytes = ByteArray(n * 4)
            for (i in 0 until n) {
                // PSD 的 32 位通道是线性光：桌面端打开会套 sRGB 传递曲线显示。
                // 我们内部（Pix32 / 8 位位图）存的是 sRGB 编码值，写盘前要转成线性，
                // 否则导进 桌面端会整体变亮、发灰（用户 2026-11-05「色彩还是不一样」）。
                val bits = java.lang.Float.floatToIntBits(toLinear(src[i]))
                bytes[i * 4] = ((bits ushr 24) and 0xFF).toByte()
                bytes[i * 4 + 1] = ((bits ushr 16) and 0xFF).toByte()
                bytes[i * 4 + 2] = ((bits ushr 8) and 0xFF).toByte()
                bytes[i * 4 + 3] = (bits and 0xFF).toByte()
            }
            out.add(bytes)
        }
        return out
    }

    fun toBitmap(target: Bitmap) {
        val px = IntArray(width * height)
        for (i in px.indices) {
            val rr = (r[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val gg = (g[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bb = (b[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        target.setPixels(px, 0, width, 0, 0, width, height)
    }

    companion object {
        /** sRGB 编码值 -> 线性光 */
        internal fun toLinear(v: Float): Float =
            if (v <= 0.04045f) v / 12.92f
            else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        /** 线性光 -> sRGB 编码值 */
        internal fun toSrgb(v: Float): Float =
            if (v <= 0.0031308f) v * 12.92f
            else (1.055f * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055f).toFloat()

        /** 浮点缓冲是 12 字节/像素，上限比 16 位低一半，省得老手机 OOM */
        const val MAX_PIXELS = 6_000_000L

        fun fromPlanes(width: Int, height: Int, planes: List<ByteArray>): Pix32? {
            if (planes.size < 3) return null
            val n = width * height
            if (planes[0].size < n * 4) return null
            val p = Pix32(width, height)
            for (c in 0 until 3) {
                val src = planes[c]
                val dst = when (c) {
                    0 -> p.r
                    1 -> p.g
                    else -> p.b
                }
                for (i in 0 until n) {
                    // 反过来：文件里是线性光，读进编辑用的 Pix32 要转回 sRGB 编码值
                    dst[i] = toSrgb(readFloat(src, i * 4))
                }
            }
            p.dirty = false
            return p
        }

        /** 存进工程用的字节（PIX2 | 宽 | 高 | R | G | B，小端浮点） */
        fun pack(p: Pix32): ByteArray {
            val n = p.width * p.height
            val out = ByteArray(12 + n * 12)
            out[0] = 'P'.code.toByte()
            out[1] = 'I'.code.toByte()
            out[2] = 'X'.code.toByte()
            out[3] = '2'.code.toByte()
            writeI32(out, 4, p.width)
            writeI32(out, 8, p.height)
            var o = 12
            for (arr in listOf(p.r, p.g, p.b)) {
                for (i in 0 until n) {
                    val bits = java.lang.Float.floatToIntBits(arr[i])
                    out[o++] = (bits and 0xFF).toByte()
                    out[o++] = ((bits ushr 8) and 0xFF).toByte()
                    out[o++] = ((bits ushr 16) and 0xFF).toByte()
                    out[o++] = ((bits ushr 24) and 0xFF).toByte()
                }
            }
            return out
        }

        fun unpack(bytes: ByteArray?): Pix32? {
            if (bytes == null || bytes.size < 12) return null
            if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'X'.code.toByte() || bytes[3] != '2'.code.toByte()
            ) return null
            val w = readI32(bytes, 4)
            val h = readI32(bytes, 8)
            if (w <= 0 || h <= 0) return null
            val n = w.toLong() * h.toLong()
            if (n > MAX_PIXELS) return null
            if (bytes.size < 12 + n * 12) return null
            val p = Pix32(w, h)
            var o = 12
            for (arr in listOf(p.r, p.g, p.b)) {
                for (i in 0 until n.toInt()) {
                    val bits = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
                    arr[i] = java.lang.Float.intBitsToFloat(bits)
                    o += 4
                }
            }
            return p
        }

        /** 大端 float 读一个 */
        fun readFloat(b: ByteArray, at: Int): Float {
            val bits = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
            return java.lang.Float.intBitsToFloat(bits)
        }

        private fun writeI32(out: ByteArray, at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v ushr 8) and 0xFF).toByte()
            out[at + 2] = ((v ushr 16) and 0xFF).toByte()
            out[at + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun readI32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}