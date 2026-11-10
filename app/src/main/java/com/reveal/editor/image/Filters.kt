package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color

/** 几个基础滤镜，作用在整张图层上（选区限制由调用方处理） */
object Filters {

    fun invert(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            px[i] = (a shl 24) or (255 - Color.red(c) shl 16) or (255 - Color.green(c) shl 8) or (255 - Color.blue(c))
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    fun grayscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val g = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
            px[i] = (a shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    fun threshold(src: Bitmap, level: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lv = level.coerceIn(0, 255)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val g = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
            val v = if (g >= lv) 255 else 0
            px[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** 预乘 alpha：透明边不会被拖成黑边 */
    private fun premul(c: Int): Int {
        val a = c ushr 24
        if (a == 255) return c
        if (a == 0) return 0
        val r = (((c shr 16) and 0xFF) * a) / 255
        val g = (((c shr 8) and 0xFF) * a) / 255
        val b = ((c and 0xFF) * a) / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 盒式模糊近似高斯。全程在预乘 alpha 空间里算：
     * 横向按条带读写、纵向用 (2r+1) 行的滑动窗口，峰值内存与画布总像素数无关，
     * 4096² 的大图 + 大半径也不会 OOM。
     */
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val r = radius.coerceIn(1, 60).coerceAtMost(minOf(w, h) / 2).coerceAtLeast(1)
        val cnt = 2 * r + 1
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // ---- 第一遍：横向（只依赖本行，条带处理，内存只跟宽度有关）----
        val rowsPerStrip = (1_000_000 / w).coerceAtLeast(1)
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(rowsPerStrip, h - y0)
            val raw = IntArray(rows * w)
            src.getPixels(raw, 0, w, 0, y0, w, rows)
            for (i in raw.indices) raw[i] = premul(raw[i])
            val rowOut = IntArray(w)
            for (row in 0 until rows) {
                boxRowPremul(raw, row * w, rowOut, w, r)
                System.arraycopy(rowOut, 0, raw, row * w, w)
            }
            out.setPixels(raw, 0, w, 0, y0, w, rows)
            y0 += rows
        }

        // ---- 第二遍：纵向滑动窗口（窗口里是预乘值，且只读还没被写过的行）----
        val sa = IntArray(w)
        val sr = IntArray(w)
        val sg = IntArray(w)
        val sb = IntArray(w)

        fun readRow(y: Int): IntArray =
            IntArray(w).also { out.getPixels(it, 0, w, 0, y.coerceIn(0, h - 1), w, 1) }

        fun acc(row: IntArray, sign: Int) {
            for (x in 0 until w) {
                val c = row[x]
                sa[x] += sign * (c ushr 24)
                sr[x] += sign * ((c shr 16) and 0xFF)
                sg[x] += sign * ((c shr 8) and 0xFF)
                sb[x] += sign * (c and 0xFF)
            }
        }

        val win = ArrayDeque<IntArray>()
        for (k in -r..r) {
            val row = readRow(k)
            win.addLast(row)
            acc(row, +1)
        }
        val outRow = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = sa[x] / cnt
                if (a <= 0) {
                    outRow[x] = 0
                } else {
                    val rr = (((sr[x] / cnt) * 255) / a).coerceIn(0, 255)
                    val gg = (((sg[x] / cnt) * 255) / a).coerceIn(0, 255)
                    val bb = (((sb[x] / cnt) * 255) / a).coerceIn(0, 255)
                    outRow[x] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            out.setPixels(outRow, 0, w, 0, y, w, 1)
            if (y < h - 1) {
                acc(win.removeFirst(), -1)
                val fresh = readRow(y + r + 1)
                win.addLast(fresh)
                acc(fresh, +1)
            }
        }
        return out
    }

    /** 单行横向盒式模糊，输入/输出都是预乘 alpha 的 ARGB，边界按夹取处理 */
    private fun boxRowPremul(src: IntArray, base: Int, dst: IntArray, w: Int, r: Int) {
        val cnt = 2 * r + 1
        var sa = 0
        var sr = 0
        var sg = 0
        var sb = 0
        for (dx in -r..r) {
            val c = src[base + dx.coerceIn(0, w - 1)]
            sa += c ushr 24
            sr += (c shr 16) and 0xFF
            sg += (c shr 8) and 0xFF
            sb += c and 0xFF
        }
        for (x in 0 until w) {
            dst[x] = ((sa / cnt) shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            val co = src[base + (x - r).coerceIn(0, w - 1)]
            sa -= co ushr 24
            sr -= (co shr 16) and 0xFF
            sg -= (co shr 8) and 0xFF
            sb -= co and 0xFF
            val ci = src[base + (x + r + 1).coerceIn(0, w - 1)]
            sa += ci ushr 24
            sr += (ci shr 16) and 0xFF
            sg += (ci shr 8) and 0xFF
            sb += ci and 0xFF
        }
    }

    /** 锐化（无参数版：对每个像素往邻居方向推一点） */
    fun sharpen(src: Bitmap, amount: Float = 1f): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val k = amount.coerceIn(0.1f, 3f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                if (Color.alpha(c) == 0) {
                    out[y * w + x] = c
                    continue
                }
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val cc = px[yy * w + (x + dx).coerceIn(0, w - 1)]
                        if (Color.alpha(cc) == 0) continue
                        r += Color.red(cc)
                        g += Color.green(cc)
                        b += Color.blue(cc)
                        n++
                    }
                }
                if (n == 0) {
                    out[y * w + x] = c
                    continue
                }
                val nr = (Color.red(c) + (Color.red(c) - r.toFloat() / n) * k).coerceIn(0f, 255f).toInt()
                val ng = (Color.green(c) + (Color.green(c) - g.toFloat() / n) * k).coerceIn(0f, 255f).toInt()
                val nb = (Color.blue(c) + (Color.blue(c) - b.toFloat() / n) * k).coerceIn(0f, 255f).toInt()
                out[y * w + x] = (Color.alpha(c) shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    /** 马赛克：每块 block×block 取平均色 */
    fun mosaic(src: Bitmap, block: Int): Bitmap? {
        val b = block.coerceIn(2, 200)
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val out = inPx.copyOf()
        var by = 0
        while (by < h) {
            val yEnd = minOf(by + b, h)
            var bx = 0
            while (bx < w) {
                val xEnd = minOf(bx + b, w)
                var r = 0L
                var g = 0L
                var bl = 0L
                var a = 0L
                var n = 0L
                for (y in by until yEnd) {
                    val row = y * w
                    for (x in bx until xEnd) {
                        val c = inPx[row + x]
                        val ca = Color.alpha(c)
                        r += Color.red(c) * ca
                        g += Color.green(c) * ca
                        bl += Color.blue(c) * ca
                        a += ca
                        n++
                    }
                }
                val avg = if (a > 0) Color.argb(
                    (a / n).toInt().coerceIn(0, 255),
                    (r / a).toInt().coerceIn(0, 255),
                    (g / a).toInt().coerceIn(0, 255),
                    (bl / a).toInt().coerceIn(0, 255),
                ) else 0
                for (y in by until yEnd) {
                    val row = y * w
                    for (x in bx until xEnd) out[row + x] = avg
                }
                bx = xEnd
            }
            by = yEnd
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 色彩平衡：红/青、绿/品红、蓝/黄的偏移（-100..100） */
    fun colorBalance(src: Bitmap, shiftR: Int, shiftG: Int, shiftB: Int): Bitmap? {
        if (shiftR == 0 && shiftG == 0 && shiftB == 0) return null
        val kr = (shiftR * 1.2f).toInt()
        val kg = (shiftG * 1.2f).toInt()
        val kb = (shiftB * 1.2f).toInt()
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val r = (Color.red(c) + kr).coerceIn(0, 255)
            val g = (Color.green(c) + kg).coerceIn(0, 255)
            val b = (Color.blue(c) + kb).coerceIn(0, 255)
            px[i] = Color.argb(a, r, g, b)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
