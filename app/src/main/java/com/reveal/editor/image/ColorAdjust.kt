package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 调色。分组照主流专业调色面板的五组来：
 *  亮：曝光 / 对比度 / 高光 / 阴影 / 白色 / 黑色
 *  颜色：色温 / 色调 / 自然饱和度 / 饱和度 / 色相
 *  效果：清晰度 / 去朦胧 / 暗角 / 颗粒
 *  细节：锐化 / 减少杂色
 *
 * 所有参数都是 -1..1（0 = 不变），一次像素遍历出结果，900px 预览不卡。
 */
object ColorAdjust {

    data class Params(
        // —— 亮 ——
        /** 曝光，-1..1 对应 -2EV..+2EV */
        val exposure: Float = 0f,
        /** 对比度 */
        val contrast: Float = 0f,
        /** 高光：正数压亮部 */
        val highlights: Float = 0f,
        /** 阴影：正数提暗部 */
        val shadows: Float = 0f,
        /** 白色：控制最亮点 */
        val whites: Float = 0f,
        /** 黑色：控制最暗点 */
        val blacks: Float = 0f,
        // —— 颜色 ——
        /** 色温：负数偏冷、正数偏暖 */
        val temperature: Float = 0f,
        /** 色调：负数偏绿、正数偏洋红 */
        val tint: Float = 0f,
        /** 自然饱和度：只提不饱和的颜色 */
        val vibrance: Float = 0f,
        /** 饱和度 */
        val saturation: Float = 0f,
        /** 色相偏移（一圈 360° 的百分比） */
        val hue: Float = 0f,
        // —— 效果 ——
        /** 清晰度（局部对比） */
        val clarity: Float = 0f,
        /** 去朦胧 */
        val dehaze: Float = 0f,
        /** 暗角：正数压暗四角 */
        val vignette: Float = 0f,
        /** 颗粒 */
        val grain: Float = 0f,
        // —— 细节 ——
        /** 锐化 */
        val sharpening: Float = 0f,
        /** 减少杂色 */
        val noiseReduction: Float = 0f,
        // —— 兼容老工程 ——
        val brightness: Float = 0f,
        val brightnessCurve: Float = 0f,
    ) {
        fun isNeutral(): Boolean = equals(Params())
    }

    /** 把颜色类参数拼成 ColorMatrix（曝光等高光暗部走 toneLut，不在这里） */
    fun matrix(p: Params): ColorMatrix {
        val cm = ColorMatrix()

        // 色温：暖 = 抬红压蓝；色调：正 = 偏洋红（抬红蓝、压绿）
        if (p.temperature != 0f || p.tint != 0f) {
            val t = p.temperature.coerceIn(-1f, 1f)
            val g = p.tint.coerceIn(-1f, 1f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f + 0.18f * t + 0.06f * g, 0f, 0f, 0f, 0f,
                        0f, 1f - 0.10f * g, 0f, 0f, 0f,
                        0f, 0f, 1f - 0.18f * t + 0.06f * g, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }

        // 饱和度 + 自然饱和度（自然饱和度在像素循环里单独做，这里只做整体饱和度）
        cm.postConcat(ColorMatrix().apply { setSaturation(1f + p.saturation.coerceIn(-1f, 1f)) })

        // 亮度 / 对比度（老字段，保持兼容）
        val c = 1f + p.contrast.coerceIn(-1f, 1f) * 0.6f
        val b = (p.brightness.coerceIn(-1f, 1f) + p.brightnessCurve.coerceIn(-1f, 1f) * 0.2f) * 100f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, b,
                    0f, c, 0f, 0f, b,
                    0f, 0f, c, 0f, b,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        // 色相：绕灰度轴转
        val deg = p.hue.coerceIn(-1f, 1f) * 180f
        if (deg != 0f) {
            val rad = Math.toRadians(deg.toDouble())
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()
            val lr = 0.213f
            val lg = 0.715f
            val lb = 0.072f
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        lr + cos * (1 - lr) + sin * (-lr), lg + cos * (-lg) + sin * (-lg), lb + cos * (-lb) + sin * (1 - lb), 0f, 0f,
                        lr + cos * (-lr) + sin * 0.143f, lg + cos * (1 - lg) + sin * 0.140f, lb + cos * (-lb) + sin * (-0.283f), 0f, 0f,
                        lr + cos * (-lr) + sin * (-(1 - lr)), lg + cos * (-lg) + sin * lg, lb + cos * (1 - lb) + sin * lb, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        return cm
    }

    /** 亮部相关的色调曲线：曝光 / 高光 / 阴影 / 白色 / 黑色 / 对比度，255 项查找表 */
    fun toneLut(p: Params): IntArray? {
        val active = p.exposure != 0f || p.highlights != 0f || p.shadows != 0f ||
            p.whites != 0f || p.blacks != 0f
        if (!active) return null
        val ev = 2f.pow(p.exposure.coerceIn(-1f, 1f) * 2f)
        val hi = p.highlights.coerceIn(-1f, 1f)
        val sh = p.shadows.coerceIn(-1f, 1f)
        val wh = p.whites.coerceIn(-1f, 1f)
        val bl = p.blacks.coerceIn(-1f, 1f)
        val table = IntArray(256)
        for (i in 0..255) {
            var v = (i / 255f) * ev
            v = v.coerceIn(0f, 1f)
            // 高光：只动亮部
            if (hi != 0f) v -= hi * 0.35f * (v * v).coerceIn(0f, 1f)
            // 阴影：只动暗部
            if (sh != 0f) v += sh * 0.35f * ((1f - v) * (1f - v))
            // 白色：抬最高点
            if (wh != 0f) v += wh * 0.30f * v.pow(3)
            // 黑色：压最暗点
            if (bl != 0f) v += bl * 0.30f * (1f - v).pow(3) * -1f
            table[i] = (v.coerceIn(0f, 1f) * 255f).toInt()
        }
        return table
    }

    /** 出图：参数全中性就返回 null（省一次复制） */
    fun apply(src: Bitmap, p: Params): Bitmap? {
        if (p.isNeutral()) return null
        var work = if (p.noiseReduction != 0f) blur(src, p.noiseReduction) else src
        // 1) 基础 / 亮 / 颜色：一次像素遍历
        work = tonePass(work, p)
        // 2) 效果 / 细节
        if (p.clarity != 0f) work = unsharp(work, p.clarity.coerceIn(-1f, 1f) * 0.8f, radius = 3)
        if (p.dehaze != 0f) work = dehaze(work, p.dehaze.coerceIn(-1f, 1f))
        if (p.sharpening != 0f) work = unsharp(work, p.sharpening.coerceIn(0f, 1f) * 0.9f, radius = 1)
        if (p.vignette != 0f || p.grain != 0f) work = finish(work, p.vignette, p.grain)
        return work
    }

    /**
     * 基础（亮度 / 对比度 / 饱和度 / 自然饱和度）+ 亮（曝光 / 高光 / 阴影 / 白色 / 黑色）
     * + 颜色（色温 / 色调 / 色相），全部逐个参数直接算像素，不用颜色矩阵叠：
     * 这样每个滑块一定有自己的效果，不会出现「只有亮度有反应」的情况。
     */
    fun tonePass(src: Bitmap, p: Params): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // —— 亮度/对比度/曝光等做成 256 项曲线表 ——
        val ev = 2f.pow(p.exposure.coerceIn(-1f, 1f) * 2f)
        val hi = p.highlights.coerceIn(-1f, 1f)
        val sh = p.shadows.coerceIn(-1f, 1f)
        val wh = p.whites.coerceIn(-1f, 1f)
        val bl = p.blacks.coerceIn(-1f, 1f)
        val br = (p.brightness.coerceIn(-1f, 1f) + p.brightnessCurve.coerceIn(-1f, 1f) * 0.3f).coerceIn(-1f, 1f)
        val ct = p.contrast.coerceIn(-1f, 1f)
        val lut = IntArray(256)
        for (i in 0..255) {
            var v = i / 255f
            if (ev != 1f) v *= ev
            if (hi != 0f) v -= hi * 0.30f * v * v                     // 高光：只动亮部
            if (sh != 0f) {
                val d = 1f - v
                v += sh * 0.30f * d * d                               // 阴影：只提/压暗部
            }
            if (wh != 0f) v += wh * 0.22f * v * v * v                 // 白色：抬最亮点
            if (bl != 0f) {
                val d = 1f - v
                v -= bl * 0.22f * d * d * d                           // 黑色：压最暗点
            }
            if (br != 0f) v += br * 0.30f                             // 亮度：整体平移
            if (ct != 0f) v = (v - 0.5f) * (1f + ct * 0.9f) + 0.5f    // 对比度：以中灰为支点
            lut[i] = (v.coerceIn(0f, 1f) * 255f).toInt()
        }

        // —— 色温 / 色调：三个通道各自的增益 ——
        val t = p.temperature.coerceIn(-1f, 1f)
        val tn = p.tint.coerceIn(-1f, 1f)
        val rGain = 1f + t * 0.22f + tn * 0.10f
        val gGain = 1f - tn * 0.14f
        val bGain = 1f - t * 0.22f + tn * 0.10f

        // —— 色相：绕 YIQ 亮度轴转 ——
        val hueDeg = p.hue.coerceIn(-1f, 1f) * 180f
        val hueCos = Math.cos(Math.toRadians(hueDeg.toDouble())).toFloat()
        val hueSin = Math.sin(Math.toRadians(hueDeg.toDouble())).toFloat()
        val useHue = hueDeg != 0f

        val sat = p.saturation.coerceIn(-1f, 1f)
        val vib = p.vibrance.coerceIn(-1f, 1f)
        val useSat = sat != 0f || vib != 0f

        for (idx in pixels.indices) {
            val c = pixels[idx]
            val a = (c ushr 24) and 0xFF
            if (a == 0) continue
            var r = lut[(c ushr 16) and 0xFF].toFloat()
            var g = lut[(c ushr 8) and 0xFF].toFloat()
            var b = lut[c and 0xFF].toFloat()
            if (rGain != 1f || gGain != 1f || bGain != 1f) {
                r *= rGain
                g *= gGain
                b *= bGain
            }
            if (useHue) {
                val yy = 0.299f * r + 0.587f * g + 0.114f * b
                val ii = 0.596f * r - 0.274f * g - 0.322f * b
                val qq = 0.211f * r - 0.523f * g + 0.312f * b
                val ni = ii * hueCos - qq * hueSin
                val nq = ii * hueSin + qq * hueCos
                r = yy + 0.956f * ni + 0.621f * nq
                g = yy - 0.272f * ni - 0.647f * nq
                b = yy - 1.106f * ni + 1.703f * nq
            }
            if (useSat) {
                var k = 1f + sat
                if (vib != 0f) {
                    // 自然饱和度：本来就艳的颜色几乎不动，灰的提得多
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    val s = if (mx <= 0f) 0f else (mx - mn) / mx
                    k += vib * (1f - s) * 1.2f
                }
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                r = luma + (r - luma) * k
                g = luma + (g - luma) * k
                b = luma + (b - luma) * k
            }
            pixels[idx] = (a shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun blur(src: Bitmap, amount: Float): Bitmap {
        // 3x3 均值模糊，次数按强度来（减少杂色用）
        val times = (amount.coerceIn(0f, 1f) * 3f).toInt().coerceAtLeast(1)
        var cur = src
        repeat(times) { cur = boxBlur3(cur) }
        return cur
    }

    private fun boxBlur3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var a = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val c = pixels[yy * w + xx]
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        a += (c ushr 24) and 0xFF
                        n++
                    }
                }
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 反锐化蒙版：原图 - 模糊图 = 边缘，把边缘加回去 */
    private fun unsharp(src: Bitmap, amount: Float, radius: Int): Bitmap {
        if (amount == 0f) return src
        var soft = src
        repeat(if (radius >= 3) 2 else 1) { soft = boxBlur3(soft) }
        val w = src.width
        val h = src.height
        val a1 = IntArray(w * h)
        val a2 = IntArray(w * h)
        src.getPixels(a1, 0, w, 0, 0, w, h)
        soft.getPixels(a2, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in a1.indices) {
            val c1 = a1[i]
            val c2 = a2[i]
            val alpha = (c1 ushr 24) and 0xFF
            if (alpha == 0) continue
            fun ch(shift: Int): Int {
                val v1 = (c1 ushr shift) and 0xFF
                val v2 = (c2 ushr shift) and 0xFF
                return (v1 + (v1 - v2) * amount).toInt().coerceIn(0, 255)
            }
            out[i] = (alpha shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 去朦胧：抬对比 + 压黑场，雾散开一些 */
    private fun dehaze(src: Bitmap, amount: Float): Bitmap {
        val k = 1f + amount * 0.5f
        val off = -amount * 30f
        val cm = ColorMatrix(
            floatArrayOf(
                k, 0f, 0f, 0f, off,
                0f, k, 0f, 0f, off,
                0f, 0f, k, 0f, off,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        return matrixPass(src, cm)
    }

    private fun matrixPass(src: Bitmap, cm: ColorMatrix): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val m = cm.array
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a == 0) continue
            val r = ((c ushr 16) and 0xFF).toFloat()
            val g = ((c ushr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            val nr = (m[0] * r + m[1] * g + m[2] * b + m[4] * 255f).coerceIn(0f, 255f).toInt()
            val ng = (m[5] * r + m[6] * g + m[7] * b + m[9] * 255f).coerceIn(0f, 255f).toInt()
            val nb = (m[10] * r + m[11] * g + m[12] * b + m[14] * 255f).coerceIn(0f, 255f).toInt()
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 暗角 + 颗粒 */
    private fun finish(src: Bitmap, vignette: Float, grain: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val vg = vignette.coerceIn(-1f, 1f)
        val gr = grain.coerceIn(0f, 1f)
        val rnd = Random(src.width * 31 + src.height)
        val cx = w / 2f
        val cy = h / 2f
        val maxD = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = pixels[i]
                val a = (c ushr 24) and 0xFF
                if (a == 0) continue
                var r = ((c ushr 16) and 0xFF).toFloat()
                var g = ((c ushr 8) and 0xFF).toFloat()
                var b = (c and 0xFF).toFloat()
                if (vg != 0f) {
                    val dx = x - cx
                    val dy = y - cy
                    val d = sqrt(dx * dx + dy * dy) / maxD
                    val f = 1f - vg * 0.85f * (d * d)
                    r *= f
                    g *= f
                    b *= f
                }
                if (gr != 0f) {
                    val n = (rnd.nextFloat() - 0.5f) * 60f * gr
                    r += n
                    g += n
                    b += n
                }
                pixels[i] = (a shl 24) or
                    (r.coerceIn(0f, 255f).toInt() shl 16) or
                    (g.coerceIn(0f, 255f).toInt() shl 8) or
                    b.coerceIn(0f, 255f).toInt()
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 预览用：先把图缩到长边不超过 900，滑滑条时才不卡 */
    fun scaledForPreview(src: Bitmap, maxSide: Int = 900): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxSide) return src
        val scale = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
