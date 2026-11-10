package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 图层的 16 位像素缓冲（每通道 0..65535）。
 *
 * 安卓的画布只有 8 位，所以画笔、滤镜这些还是按 8 位画；这一份是「精度备份」：
 * 笔迹落下之后，用 8 位的前后差异反推出这一笔的覆盖率，再按 16 位重新混一遍，
 * 于是渐变、过渡这些地方不会被截成 256 级。
 *
 * 只有在 16 位文档里、第一次改动某一层的时候才建（见 SketchView.ensurePix16），
 * 不画就不占内存。
 */
class Pix16(val width: Int, val height: Int) {

    val r = ShortArray(width * height)
    val g = ShortArray(width * height)
    val b = ShortArray(width * height)

    /** 自上次存盘/导出后有没有变过 */
    var dirty: Boolean = false

    fun index(x: Int, y: Int): Int = y * width + x

    fun fillFrom(bmp: Bitmap) {
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        for (i in px.indices) {
            val c = px[i]
            r[i] = (Color.red(c) * 257).toShort()
            g[i] = (Color.green(c) * 257).toShort()
            b[i] = (Color.blue(c) * 257).toShort()
        }
        dirty = true
    }

    /** 把一块区域的 16 位数据拷出来（给撤销用），没有变化就返回 null */
    fun snapshot(x: Int, y: Int, w: Int, h: Int): ShortArray? {
        if (w <= 0 || h <= 0) return null
        val out = ShortArray(w * h * 3)
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

    fun restore(x: Int, y: Int, w: Int, h: Int, data: ShortArray) {
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
     * 按 8 位查表重映射（色阶 / 曲线 / 亮度对比度 / 反相 / 去色这类逐像素操作）。
     *
     * 8 位算出来的只是一张 256 项的表，这里把 16 位值夹在两个表项之间线性插值，
     * 于是渐变里每 1/65536 一级都还在，不会被重新截成 256 级台阶。
     */
    fun applyLut8(
        lutR: IntArray, lutG: IntArray, lutB: IntArray,
        rx: Int = 0, ry: Int = 0, rw: Int = width, rh: Int = height,
    ) {
        val x0 = rx.coerceIn(0, width)
        val y0 = ry.coerceIn(0, height)
        val x1 = (rx + rw).coerceIn(0, width)
        val y1 = (ry + rh).coerceIn(0, height)
        if (x1 <= x0 || y1 <= y0) return
        for (py in y0 until y1) {
            var i = py * width + x0
            for (px in x0 until x1) {
                r[i] = remap(lutR, r[i].toInt() and 0xFFFF)
                g[i] = remap(lutG, g[i].toInt() and 0xFFFF)
                b[i] = remap(lutB, b[i].toInt() and 0xFFFF)
                i++
            }
        }
        dirty = true
    }

    private fun remap(lut: IntArray, v: Int): Short {
        val hi = (v ushr 8).coerceIn(0, 255)
        val lo = v and 0xFF
        val a = lut[hi].coerceIn(0, 255)
        val b = lut[(hi + 1).coerceAtMost(255)].coerceIn(0, 255)
        val out = a * 257 + ((b - a) * 257 * lo) / 256
        return out.coerceIn(0, 65535).toShort()
    }

    /** 裁一块出来（越界部分当全黑，跟位图裁剪的语义一致） */
    fun cropRegion(x0: Int, y0: Int, w: Int, h: Int): Pix16 {
        val out = Pix16(w, h)
        for (row in 0 until h) {
            val sy = y0 + row
            if (sy < 0 || sy >= height) continue
            for (col in 0 until w) {
                val sx = x0 + col
                if (sx < 0 || sx >= width) continue
                val s = sy * width + sx
                val d = row * w + col
                out.r[d] = r[s]
                out.g[d] = g[s]
                out.b[d] = b[s]
            }
        }
        out.dirty = true
        return out
    }

    /** 顺时针旋转 90 度的整数倍（1=90°、2=180°、3=270°），尺寸跟着换 */
    fun rotateQuarters(times: Int): Pix16 {
        val t = ((times % 4) + 4) % 4
        if (t == 0) return this
        val nw = if (t == 2) width else height
        val nh = if (t == 2) height else width
        val out = Pix16(nw, nh)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val s = y * width + x
                val nx = when (t) {
                    1 -> height - 1 - y
                    2 -> width - 1 - x
                    else -> y
                }
                val ny = when (t) {
                    1 -> x
                    2 -> height - 1 - y
                    else -> width - 1 - x
                }
                val d = ny * nw + nx
                out.r[d] = r[s]
                out.g[d] = g[s]
                out.b[d] = b[s]
            }
        }
        out.dirty = true
        return out
    }

    /** 水平 / 垂直镜像 */
    fun flipImage(horizontal: Boolean): Pix16 {
        val out = Pix16(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val s = y * width + x
                val nx = if (horizontal) width - 1 - x else x
                val ny = if (horizontal) y else height - 1 - y
                val d = ny * width + nx
                out.r[d] = r[s]
                out.g[d] = g[s]
                out.b[d] = b[s]
            }
        }
        out.dirty = true
        return out
    }

    /** 目标像素 (dstX,dstY) 对应的源坐标（亚像素，原点在像素左上角） */
    fun interface Sampler {
        fun sourceAt(dstX: Int, dstY: Int, out: FloatArray): Boolean
    }

    /**
     * 通用重采样：按 [sampler] 的逆映射逐像素双线性取样。
     *
     * 自由变换、任意角度旋转、改图像大小都靠它把 16 位数据一起搬过去——
     * 不然这些操作只能退回 8 位，渐变就会重新出现台阶。
     */
    fun resample(dw: Int, dh: Int, sampler: Sampler): Pix16 {
        val out = Pix16(dw, dh)
        val pt = FloatArray(2)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                if (!sampler.sourceAt(x, y, pt)) continue
                sampleBilinear(pt[0], pt[1], out, y * dw + x)
            }
        }
        out.dirty = true
        return out
    }

    /** 按矩阵重采样：m 把源坐标映射到目标坐标（内部自动求逆） */
    fun resampleMatrix(m: android.graphics.Matrix, dw: Int, dh: Int): Pix16 {
        val inv = android.graphics.Matrix()
        if (!m.invert(inv)) return this
        val v = FloatArray(2)
        return resample(dw, dh) { x, y, out ->
            v[0] = x + 0.5f
            v[1] = y + 0.5f
            inv.mapPoints(v)
            out[0] = v[0]
            out[1] = v[1]
            true
        }
    }

    /** 把本图贴进 dw×dh 的新缓冲，左上角放在 (dx,dy)，露出来的空处填白 */
    fun pasteInto(dw: Int, dh: Int, dx: Int, dy: Int): Pix16 {
        val out = Pix16(dw, dh)
        java.util.Arrays.fill(out.r, 0xFFFF.toShort())
        java.util.Arrays.fill(out.g, 0xFFFF.toShort())
        java.util.Arrays.fill(out.b, 0xFFFF.toShort())
        for (y in 0 until height) {
            val ty = y + dy
            if (ty < 0 || ty >= dh) continue
            for (x in 0 until width) {
                val tx = x + dx
                if (tx < 0 || tx >= dw) continue
                val s = y * width + x
                val d = ty * dw + tx
                out.r[d] = r[s]
                out.g[d] = g[s]
                out.b[d] = b[s]
            }
        }
        out.dirty = true
        return out
    }

    private fun sampleBilinear(sx: Float, sy: Float, out: Pix16, d: Int) {
        val fx = sx - 0.5f
        val fy = sy - 0.5f
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = x0.coerceIn(0, width - 1)
        val xb = (x0 + 1).coerceIn(0, width - 1)
        val ya = y0.coerceIn(0, height - 1)
        val yb = (y0 + 1).coerceIn(0, height - 1)
        val i00 = ya * width + xa
        val i10 = ya * width + xb
        val i01 = yb * width + xa
        val i11 = yb * width + xb
        out.r[d] = mix4(r[i00], r[i10], r[i01], r[i11], tx, ty)
        out.g[d] = mix4(g[i00], g[i10], g[i01], g[i11], tx, ty)
        out.b[d] = mix4(b[i00], b[i10], b[i01], b[i11], tx, ty)
    }

    private fun mix4(c00: Short, c10: Short, c01: Short, c11: Short, tx: Float, ty: Float): Short {
        val v00 = c00.toInt() and 0xFFFF
        val v10 = c10.toInt() and 0xFFFF
        val v01 = c01.toInt() and 0xFFFF
        val v11 = c11.toInt() and 0xFFFF
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        val v = top + (bottom - top) * ty
        return v.toInt().coerceIn(0, 65535).toShort()
    }

    /**
     * 渐变填充：8 位那边只是给个覆盖率，颜色这里按同样的渐变方程在 16 位空间算，
     * 所以填出来的渐变本身也是 16 位，不会一出场就带 256 级台阶。
     *
     * cover 是渐变临时图的 ARGB（alpha = 选区/阿尔法锁定剪出来的覆盖率），
     * 为 null 表示整块都填。
     */
    fun applyGradient(
        x0: Float, y0: Float, x1: Float, y1: Float, radial: Boolean,
        c1: Int, c2: Int, cover: IntArray?,
    ) {
        val r1 = (Color.red(c1) * 257)
        val g1 = (Color.green(c1) * 257)
        val b1 = (Color.blue(c1) * 257)
        val r2 = (Color.red(c2) * 257)
        val g2 = (Color.green(c2) * 257)
        val b2 = (Color.blue(c2) * 257)
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = if (radial) {
            (kotlin.math.hypot(dx, dy).coerceAtLeast(1f)).let { it * it }
        } else {
            (dx * dx + dy * dy).coerceAtLeast(1f)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val a = if (cover == null) 255 else (cover[i] ushr 24) and 0xFF
                if (a == 0) continue
                val px = x + 0.5f
                val py = y + 0.5f
                var t = if (radial) {
                    val ddx = px - x0
                    val ddy = py - y0
                    (ddx * ddx + ddy * ddy) / len2
                } else {
                    ((px - x0) * dx + (py - y0) * dy) / len2
                }
                t = t.coerceIn(0f, 1f)
                val sr = r1 + (r2 - r1) * t
                val sg = g1 + (g2 - g1) * t
                val sb = b1 + (b2 - b1) * t
                val f = a / 255f
                if (f >= 1f) {
                    r[i] = sr.toInt().toShort()
                    g[i] = sg.toInt().toShort()
                    b[i] = sb.toInt().toShort()
                } else {
                    r[i] = ((r[i].toInt() and 0xFFFF) * (1f - f) + sr * f).toInt().coerceIn(0, 65535).toShort()
                    g[i] = ((g[i].toInt() and 0xFFFF) * (1f - f) + sg * f).toInt().coerceIn(0, 65535).toShort()
                    b[i] = ((b[i].toInt() and 0xFFFF) * (1f - f) + sb * f).toInt().coerceIn(0, 65535).toShort()
                }
            }
        }
        dirty = true
    }

    /**
     * 8 位操作改了一块像素之后同步过来：变过的像素按 8 位结果写死。
     *
     * 涂抹、仿制、渐变填充这种「没法用查表反推」的操作，精度只能在被涂到的地方掉一级，
     * 没碰到的像素仍然是原来的 16 位数据——比整层作废好得多。
     */
    fun applyFrom8Diff(x: Int, y: Int, w: Int, h: Int, before: IntArray, after: IntArray) {
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
                val d = py * width + pxx
                // 8 位算出来的新值，加上原像素「不到一级」的那点余数（residual），
                // 这样模糊/锐化/去色之后渐变仍然平滑，不会打回 256 级台阶
                val cr = (r[d].toInt() and 0xFFFF) - ((p0 shr 16 and 0xFF) * 257)
                val cg = (g[d].toInt() and 0xFFFF) - ((p0 shr 8 and 0xFF) * 257)
                val cb = (b[d].toInt() and 0xFFFF) - (p0 and 0xFF) * 257
                r[d] = ((Color.red(p1) * 257) + cr).coerceIn(0, 65535).toShort()
                g[d] = ((Color.green(p1) * 257) + cg).coerceIn(0, 65535).toShort()
                b[d] = ((Color.blue(p1) * 257) + cb).coerceIn(0, 65535).toShort()
                touched = true
            }
        }
        if (touched) dirty = true
    }

    /**
     * 一笔画完之后，按 8 位的前后差异把这笔的 16 位效果补上。
     * before/after 是同一块区域的 ARGB（8 位），color 是这一笔的颜色。
     *
     * 覆盖率怎么来的：8 位那次绘制等于 out = src + (color - src) * α，
     * 从任意一个变化最大的通道就能解出 α，再拿 α 在 16 位空间里混一遍。
     * 擦除类（alpha 掉了）不动颜色，只当它是透明的。
     */
    fun applyStroke(
        x: Int, y: Int, w: Int, h: Int,
        before: IntArray, after: IntArray, color: Int,
    ) {
        val cr = (Color.red(color) * 257)
        val cg = (Color.green(color) * 257)
        val cb = (Color.blue(color) * 257)
        var touched = false
        for (row in 0 until h) {
            val py = y + row
            var si = row * w // 数组是「矩形宽 × 矩形高」，不能按整图宽度跨行
            for (col in 0 until w) {
                val p0 = before[si]
                val p1 = after[si]
                si++
                val pxx = x + col
                if (py < 0 || py >= height || pxx < 0 || pxx >= width) continue
                val di = py * width + pxx
                if (p0 == p1) continue
                val a0 = Color.alpha(p0)
                val a1 = Color.alpha(p1)
                // 比覆盖率：取变化幅度最大的那个通道来解，避免除零
                var alpha = 0f
                val dr = Color.red(p1) - Color.red(p0)
                val dg = Color.green(p1) - Color.green(p0)
                val db = Color.blue(p1) - Color.blue(p0)
                val scale = maxOf(kotlin.math.abs(Color.red(color) - Color.red(p0)), kotlin.math.abs(Color.green(color) - Color.green(p0)), kotlin.math.abs(Color.blue(color) - Color.blue(p0)))
                if (scale > 0) {
                    val d = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
                    alpha = (d.toFloat() / scale.toFloat()).coerceIn(0f, 1f)
                }
                // alpha 掉了（擦除 / 透明笔）：颜色按比例向 0 收，但 8 位没有颜色信息可依，就不动
                if (a1 < a0) {
                    val keep = a1.toFloat() / a0.toFloat()
                    if (keep < 1f) {
                        r[di] = (r[di] * keep).toInt().toShort()
                        g[di] = (g[di] * keep).toInt().toShort()
                        b[di] = (b[di] * keep).toInt().toShort()
                        touched = true
                    }
                    continue
                }
                if (alpha <= 0f) continue
                r[di] = (r[di] + (cr - r[di]) * alpha).toInt().coerceIn(0, 65535).toShort()
                g[di] = (g[di] + (cg - g[di]) * alpha).toInt().coerceIn(0, 65535).toShort()
                b[di] = (b[di] + (cb - b[di]) * alpha).toInt().coerceIn(0, 65535).toShort()
                touched = true
            }
        }
        if (touched) dirty = true
    }

    /**
     * 一笔画完之后，直接按这一笔的覆盖率在 16 位空间混色（不经过 8 位位图）。
     *
     * cover 是笔迹缓冲在矩形内的 ARGB：alpha = 这一笔压到多少（压感/软边都在里面），
     * RGB = 笔刷实际颜色（纹理笔刷也行）。于是
     *   dst16 = dst16 + (src16 - dst16) * (alpha / 255 * opacity)
     * 累积几十笔也还是 16 位，不会像「从 8 位差异反推」那样每一笔都截一次。
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
                val sr = (((pxARGB ushr 16) and 0xFF) * unp).toInt().coerceAtMost(255) * 257
                val sg = (((pxARGB ushr 8) and 0xFF) * unp).toInt().coerceAtMost(255) * 257
                val sb = ((pxARGB and 0xFF) * unp).toInt().coerceAtMost(255) * 257
                val dr = r[di].toInt() and 0xFFFF
                val dg = g[di].toInt() and 0xFFFF
                val db = b[di].toInt() and 0xFFFF
                r[di] = (dr + (sr - dr) * a).toInt().coerceIn(0, 65535).toShort()
                g[di] = (dg + (sg - dg) * a).toInt().coerceIn(0, 65535).toShort()
                b[di] = (db + (sb - db) * a).toInt().coerceIn(0, 65535).toShort()
                touched = true
            }
        }
        if (touched) dirty = true
    }

    /** 纯色填充：8 位里变过的像素直接写成填充色（不透明处是精确值） */
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
                    val a = Color.alpha(p1)
                    if (a > 0) {
                        r[di] = (Color.red(p1) * 257).toShort()
                        g[di] = (Color.green(p1) * 257).toShort()
                        b[di] = (Color.blue(p1) * 257).toShort()
                        touched = true
                    }
                }
            }
        }
        if (touched) dirty = true
    }

    /** 纯色填充 / 区域替换：直接按 8 位结果反推，整块覆盖处就是精确值 */
    fun fillRegion(x: Int, y: Int, w: Int, h: Int, argb: IntArray) {
        val cr = (Color.red(argb[0]) * 257)
        val cg = (Color.green(argb[0]) * 257)
        val cb = (Color.blue(argb[0]) * 257)
        val ca = Color.alpha(argb[0])
        for (row in 0 until h) {
            var si = row * w
            var di = (y + row) * width + x
            for (col in 0 until w) {
                val c = argb[si]
                si++
                val a = Color.alpha(c)
                if (a == 0) {
                    continue
                }
                // 半透明边缘：按 alpha 混；不透明就是填色本身
                if (a >= ca || ca == 255) {
                    r[di] = cr.toShort()
                    g[di] = cg.toShort()
                    b[di] = cb.toShort()
                } else {
                    val t = a.toFloat() / 255f
                    r[di] = (r[di] * (1f - t) + cr * t).toInt().coerceIn(0, 65535).toShort()
                    g[di] = (g[di] * (1f - t) + cg * t).toInt().coerceIn(0, 65535).toShort()
                    b[di] = (b[di] * (1f - t) + cb * t).toInt().coerceIn(0, 65535).toShort()
                }
                di++
            }
        }
        dirty = true
    }

    /**
     * 明暗类操作（减淡 / 加深）：8 位结果是「按通道比例缩放」，这里把同一个比例
     * 乘到 16 位缓冲上。整幅的色相和饱和度不变，也不会因为 8 位取整再掉一级。
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
                r[di] = ((r[di].toInt() and 0xFFFF) * kr).toInt().coerceIn(0, 65535).toShort()
                g[di] = ((g[di].toInt() and 0xFFFF) * kg).toInt().coerceIn(0, 65535).toShort()
                b[di] = ((b[di].toInt() and 0xFFFF) * kb).toInt().coerceIn(0, 65535).toShort()
                touched = true
            }
        }
        if (touched) dirty = true
    }

    /** 导出用：三条通道的 16 位大端样本字节 */
    fun planes(): List<ByteArray> {
        val n = width * height
        val out = ArrayList<ByteArray>(3)
        for (p in 0 until 3) {
            val src = when (p) {
                0 -> r
                1 -> g
                else -> b
            }
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = src[i].toInt() and 0xFFFF
                bytes[i * 2] = ((v ushr 8) and 0xFF).toByte()
                bytes[i * 2 + 1] = (v and 0xFF).toByte()
            }
            out.add(bytes)
        }
        return out
    }

    fun toBitmap(target: Bitmap) {
        val px = IntArray(width * height)
        for (i in px.indices) {
            val v = (r[i].toInt() and 0xFFFF)
            val rr = ((v * 255 + 32767) / 65535).coerceIn(0, 255)
            val vg = (g[i].toInt() and 0xFFFF)
            val gg = ((vg * 255 + 32767) / 65535).coerceIn(0, 255)
            val vb = (b[i].toInt() and 0xFFFF)
            val bb = ((vb * 255 + 32767) / 65535).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        target.setPixels(px, 0, width, 0, 0, width, height)
    }

    companion object {
        /** 超过这个像素数就不建 16 位缓冲了，省得老手机 OOM */
        const val MAX_PIXELS = 12_000_000L

        /** 从三条 16 位大端样本平面还原 */
        fun fromPlanes(width: Int, height: Int, planes: List<ByteArray>): Pix16? {
            if (planes.size < 3) return null
            val n = width * height
            if (planes[0].size < n * 2) return null
            val p = Pix16(width, height)
            for (c in 0 until 3) {
                val src = planes[c]
                val dst = when (c) {
                    0 -> p.r
                    1 -> p.g
                    else -> p.b
                }
                for (i in 0 until n) {
                    val v = ((src[i * 2].toInt() and 0xFF) shl 8) or (src[i * 2 + 1].toInt() and 0xFF)
                    dst[i] = v.toShort()
                }
            }
            p.dirty = false
            return p
        }

        /** 存进工程用的字节（PIX1 | 宽 | 高 | R | G | B，小端 16 位） */
        fun pack(p: Pix16): ByteArray {
            val n = p.width * p.height
            val out = ByteArray(12 + n * 6)
            out[0] = 'P'.code.toByte()
            out[1] = 'I'.code.toByte()
            out[2] = 'X'.code.toByte()
            out[3] = '1'.code.toByte()
            writeI32(out, 4, p.width)
            writeI32(out, 8, p.height)
            var o = 12
            for (arr in listOf(p.r, p.g, p.b)) {
                for (i in 0 until n) {
                    val v = arr[i].toInt() and 0xFFFF
                    out[o++] = (v and 0xFF).toByte()
                    out[o++] = ((v ushr 8) and 0xFF).toByte()
                }
            }
            return out
        }

        fun unpack(bytes: ByteArray?): Pix16? {
            if (bytes == null || bytes.size < 12) return null
            if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'X'.code.toByte() || bytes[3] != '1'.code.toByte()
            ) return null
            val w = readI32(bytes, 4)
            val h = readI32(bytes, 8)
            if (w <= 0 || h <= 0) return null
            val n = w.toLong() * h.toLong()
            if (n > MAX_PIXELS) return null
            if (bytes.size < 12 + n * 6) return null
            val p = Pix16(w, h.toInt())
            var o = 12
            for (arr in listOf(p.r, p.g, p.b)) {
                for (i in 0 until n.toInt()) {
                    val v = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
                    arr[i] = v.toShort()
                    o += 2
                }
            }
            return p
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