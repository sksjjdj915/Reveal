package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 抠图算法。两条路线：
 *  1) chromaKey —— 绿幕/蓝幕/纯色底：在 YCbCr 的色度平面上算距离，跟色相走、不受明暗影响；
 *     可选去溢色（spill），把边缘残留的绿反算回中性。
 *  2) levelsCut —— 黑白场（色阶）抠图：按某个通道的亮度做阈值，黑场以下透明、白场以上不透明。
 */
object Matting {

    private fun cb(r: Int, g: Int, b: Int): Float = -0.168736f * r - 0.331264f * g + 0.5f * b
    private fun cr(r: Int, g: Int, b: Int): Float = 0.5f * r - 0.418688f * g - 0.081312f * b

    /** 从四角采样，估算背景色（取四角中位亮度那一块的平均色） */
    fun guessBackgroundColor(src: Bitmap): Int {
        val w = src.width
        val h = src.height
        val step = maxOf(1, minOf(w, h) / 60)
        val samples = ArrayList<Int>(400)
        for (x in 0 until w step step) {
            samples.add(src.getPixel(x, 0))
            samples.add(src.getPixel(x, h - 1))
        }
        for (y in 0 until h step step) {
            samples.add(src.getPixel(0, y))
            samples.add(src.getPixel(w - 1, y))
        }
        if (samples.isEmpty()) return Color.WHITE
        var r = 0L
        var g = 0L
        var b = 0L
        for (c in samples) {
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
        }
        val n = samples.size
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * 色度键控。
     * @param tolerance 0..1，越大抠得越狠（默认 0.35）
     * @param softness 0..1，边缘过渡宽度（默认 0.12）
     * @param spill 去溢色：把边缘的绿色/蓝色分量压回中性
     */
    fun chromaKey(
        src: Bitmap,
        keyColor: Int,
        tolerance: Float = 0.35f,
        softness: Float = 0.12f,
        spill: Boolean = true,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val kr = Color.red(keyColor)
        val kg = Color.green(keyColor)
        val kb = Color.blue(keyColor)
        val kCb = cb(kr, kg, kb)
        val kCr = cr(kr, kg, kb)

        val t = (tolerance.coerceIn(0f, 1f) * 140f) + 8f
        val soft = (softness.coerceIn(0.01f, 1f) * 90f) + 4f

        for (i in pixels.indices) {
            val c = pixels[i]
            val a0 = Color.alpha(c)
            if (a0 == 0) continue
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val dCb = cb(r, g, b) - kCb
            val dCr = cr(r, g, b) - kCr
            val dist = sqrt(dCb * dCb + dCr * dCr)

            var alpha: Float = when {
                dist <= t -> 0f
                dist >= t + soft -> 1f
                else -> (dist - t) / soft
            }
            var nr = r
            var ng = g
            var nb = b
            if (spill && alpha < 0.99f) {
                // 去溢色：把这像素往「背景色方向」的分量收掉，边缘不再发绿
                val strength = 1f - alpha
                val minG = maxOf(0, minOf(r, b) - 6)
                if (g > minG) {
                    ng = (g - (g - minG) * strength).toInt().coerceIn(0, 255)
                }
                if (kg > kr && kg > kb) {
                    nr = (nr + (kg - kr) * strength * 0.15f).toInt().coerceIn(0, 255)
                    nb = (nb + (kg - kb) * strength * 0.15f).toInt().coerceIn(0, 255)
                }
            }
            val finalAlpha = (alpha * a0).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(finalAlpha, nr, ng, nb)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 色阶抠图：按通道亮度做黑白场。
     * @param channel 0=R 1=G 2=B 3=亮度
     * @param black 黑场 0..255，低于它 → 透明
     * @param white 白场 0..255，高于它 → 不透明
     * @param invert 反相（抠掉亮部而不是暗部）
     */
    fun levelsCut(
        src: Bitmap,
        channel: Int,
        black: Float,
        white: Float,
        invert: Boolean = false,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val lo = minOf(black, white - 1f)
        val hi = maxOf(white, lo + 1f)

        for (i in pixels.indices) {
            val c = pixels[i]
            val a0 = Color.alpha(c)
            if (a0 == 0) continue
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val v = when (channel) {
                0 -> r
                1 -> g
                2 -> b
                else -> (0.299f * r + 0.587f * g + 0.114f * b)
            }.toFloat()
            var alpha = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            if (invert) alpha = 1f - alpha
            pixels[i] = Color.argb((alpha * a0).toInt(), r, g, b)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 混合色带抠图（照 桌面端的「混合颜色带」做）：亮度落在 [lo, hi] 区间里的像素被抠成透明。
     * 纯色背景（白底、灰底、纯蓝底）用它最快：拖两根滑块把背景那条亮度带圈进来就行。
     *
     * @param next   下一图层（可选）。给了就只在「下一图层」亮度也落在 [nextLo, nextHi] 的位置抠，
     *               用来避免把主体里同亮度的部分一起抠掉。
     * @param invert true = 反过来抠掉带外的部分（保留背景）
     */
    fun bandCut(
        src: Bitmap,
        lo: Int,
        hi: Int,
        invert: Boolean = false,
        next: Bitmap? = null,
        nextLo: Int = 0,
        nextHi: Int = 255,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var nextPixels: IntArray? = null
        if (next != null && next.width == w && next.height == h) {
            nextPixels = IntArray(w * h)
            next.getPixels(nextPixels, 0, w, 0, 0, w, h)
        }
        val a = minOf(lo, hi)
        val b = maxOf(lo, hi)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a0 = Color.alpha(c)
            if (a0 == 0) continue
            val r = Color.red(c)
            val g = Color.green(c)
            val bl = Color.blue(c)
            val lum = (0.299f * r + 0.587f * g + 0.114f * bl).toInt()
            var inBand = lum in a..b
            if (invert) inBand = !inBand
            if (inBand && nextPixels != null) {
                val n = nextPixels[i]
                val nLum = (0.299f * Color.red(n) + 0.587f * Color.green(n) + 0.114f * Color.blue(n)).toInt()
                inBand = nLum in minOf(nextLo, nextHi)..maxOf(nextLo, nextHi)
            }
            pixels[i] = Color.argb(if (inBand) 0 else a0, r, g, bl)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 判断一张图大概有没有透明区（决定导出时要不要提示铺白底） */
    fun hasTransparency(src: Bitmap): Boolean {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return pixels.any { Color.alpha(it) < 250 }
    }

    fun diffRatio(a: Int, b: Int): Float = abs(a - b) / 255f
}
