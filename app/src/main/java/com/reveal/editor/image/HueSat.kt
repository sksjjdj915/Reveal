package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 色相/饱和度（桌面端面板那套）：
 * - 一个「全图」通道 + 六个色相带（红 / 黄 / 绿 / 青 / 蓝 / 洋红）；
 * - 每个通道各有 色相(-180..180) / 饱和度(-100..100) / 明度(-100..100)；
 * - 「着色」勾上时，先把整幅图转成单色，再用「色相 / 饱和度」当这两个参数上色（等同 桌面端的 Colorize）。
 *
 * 色相带的权用余弦衰减：离带中心越远影响越小，边界处平滑过渡，避免出现生硬的色块边缘。
 */
object HueSat {

    data class Band(
        var hue: Int = 0,
        var sat: Int = 0,
        var light: Int = 0,
    ) {
        val isZero: Boolean get() = hue == 0 && sat == 0 && light == 0
    }

    /** 六个色相带的中心角（度） */
    val BAND_CENTERS = intArrayOf(0, 45, 90, 180, 225, 300)
    val BAND_NAMES = arrayOf("红", "黄", "绿", "青", "蓝", "洋红")

    fun apply(
        src: Bitmap,
        hue: Int,
        sat: Int,
        light: Int,
        bands: List<Band>,
        colorize: Boolean,
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return out
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)

        val hsv = FloatArray(3)
        val hs = hue.toFloat()
        val ss = 1f + sat / 100f
        val ls = light / 100f

        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            Color.colorToHSV(c or 0xFF000000.toInt(), hsv)

            if (colorize) {
                // 转单色后按色相/饱和度上色
                val lum = (0.299f * hsv[2] + 0.587f * hsv[2] + 0.114f * hsv[2])
                hsv[0] = ((hs % 360f) + 360f) % 360f
                hsv[1] = (ss).coerceIn(0f, 1f)
                hsv[2] = (lum + ls).coerceIn(0f, 1f)
                val rgb = Color.HSVToColor(hsv) and 0x00FFFFFF
                px[i] = (a shl 24) or rgb
                continue
            }

            // 全图通道
            var dh = 0f
            var dS = 1f
            var dL = 0f
            if (hs != 0f) {
                dh += hs
                dS *= ss
                dL += ls
            }

            // 各色相带：按角距取权重
            if (bands.isNotEmpty()) {
                var weight = 0f
                var bh = 0f
                var bs = 1f
                var bl = 0f
                for (bi in bands.indices) {
                    val b = bands[bi]
                    if (b.isZero) continue
                    val d = angleDistance(hsv[0], BAND_CENTERS[bi % BAND_CENTERS.size].toFloat())
                    val wgt = if (d >= 60f) 0f else (0.5f + 0.5f * kotlin.math.cos(Math.PI * d / 60.0)).toFloat()
                    if (wgt <= 0f) continue
                    weight = maxOf(weight, wgt)
                    bh += b.hue * wgt
                    bs *= 1f + (b.sat / 100f) * wgt
                    bl += (b.light / 100f) * wgt
                }
                if (weight > 0f) {
                    dh += bh
                    dS *= bs
                    dL += bl
                }
            }

            hsv[0] = (((hsv[0] + dh) % 360f) + 360f) % 360f
            hsv[1] = (hsv[1] * dS).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + dL * 0.5f).coerceIn(0f, 1f)

            val rgb = Color.HSVToColor(hsv) and 0x00FFFFFF
            px[i] = (a shl 24) or rgb
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 全图快速路径：色相旋转 / 饱和度 / 明度 合成一个 4x5 颜色矩阵，交给 GPU 画一遍。
     * 参考 Android 官方 ColorMatrix（android.graphics.ColorMatrix）+ 饱和度为
     * 亮度加权线性变换（Rec.709 系数），比逐像素 HSV 快一到两个数量级，拖动时跟手。
     */
    fun applyMatrix(src: Bitmap, hue: Int, sat: Int, light: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (hue == 0 && sat == 0 && light == 0) return out
        val cm = android.graphics.ColorMatrix()
        // 饱和度
        if (sat != 0) {
            val k = 1f + sat / 100f
            val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
            val satM = android.graphics.ColorMatrix(floatArrayOf(
                lr + k * (1 - lr), lg - k * lg, lb - k * lb, 0f, 0f,
                lr - k * lr, lg + k * (1 - lg), lb - k * lb, 0f, 0f,
                lr - k * lr, lg - k * lg, lb + k * (1 - lb), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
            cm.postConcat(satM)
        }
        // 色相旋转（绕亮度轴）
        if (hue != 0) {
            val a = Math.toRadians(hue.toDouble())
            val c = Math.cos(a).toFloat()
            val s2 = Math.sin(a).toFloat()
            val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
            val hueM = android.graphics.ColorMatrix(floatArrayOf(
                lr + c * (1 - lr) + s2 * (-lr), lg + c * (-lg) + s2 * (-lg), lb + c * (-lb) + s2 * (1 - lb), 0f, 0f,
                lr + c * (-lr) + s2 * 0.143f, lg + c * (1 - lg) + s2 * 0.140f, lb + c * (-lb) + s2 * (-0.283f), 0f, 0f,
                lr + c * (-lr) + s2 * (-(1 - lr)), lg + c * (-lg) + s2 * lg, lb + c * (1 - lb) + s2 * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
            cm.postConcat(hueM)
        }
        // 明度：整体加减（-100..100 → -255..255 的一半）
        if (light != 0) {
            val d = light * 1.28f
            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, d,
                0f, 1f, 0f, 0f, d,
                0f, 0f, 1f, 0f, d,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 色相带快速路径：把「单像素颜色 → 结果」这段逻辑采样成 24³ 的 3D LUT，再逐像素查表。
     * 逐像素 HSV 每像素要两次 JNI 调用，480px 预览一次 100~200ms；换 LUT 后只剩 8 次查表。
     */
    /** 3D LUT 缓存：同一组色相带参数（拖动中参数不变的那几帧）不必重复建表 */
    private var lutKey: String = ""
    private var lutCache: IntArray? = null

    fun applyBandsLut(src: Bitmap, bands: List<Band>): Bitmap {
        val key = bands.joinToString(",") { it.hue.toString() + "/" + it.sat + "/" + it.light }
        var lut = lutCache
        if (lut == null || key != lutKey) {
            lut = Lut3D.table(Lut3D.DEFAULT_N) { c -> onePixel(c, 0, 0, 0, bands, false) }
            lutCache = lut
            lutKey = key
        }
        return Lut3D.apply(src, lut, Lut3D.DEFAULT_N)
    }

    /**
     * 单像素版本的完整算法（和 [apply] 里的循环体保持一致），供 LUT 采样使用。
     * 返回 0xRRGGBB。
     */
    fun onePixel(cIn: Int, hue: Int, sat: Int, light: Int, bands: List<Band>, colorize: Boolean): Int {
        val c = cIn or 0xFF000000.toInt()
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        val hs = hue.toFloat()
        val ss = 1f + sat / 100f
        val ls = light / 100f
        if (colorize) {
            hsv[0] = ((hs % 360f) + 360f) % 360f
            hsv[1] = ss.coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + ls).coerceIn(0f, 1f)
            return Color.HSVToColor(hsv) and 0xFFFFFF
        }
        var dh = 0f
        var dS = 1f
        var dL = 0f
        if (hs != 0f) { dh += hs; dS *= ss; dL += ls }
        if (bands.isNotEmpty()) {
            var weight = 0f
            var bh = 0f
            var bs = 1f
            var bl = 0f
            for (bi in bands.indices) {
                val b = bands[bi]
                if (b.isZero) continue
                val d = angleDistance(hsv[0], BAND_CENTERS[bi % BAND_CENTERS.size].toFloat())
                val wgt = if (d >= 60f) 0f else (0.5f + 0.5f * kotlin.math.cos(Math.PI * d / 60.0)).toFloat()
                if (wgt <= 0f) continue
                weight = maxOf(weight, wgt)
                bh += b.hue * wgt
                bs *= 1f + (b.sat / 100f) * wgt
                bl += (b.light / 100f) * wgt
            }
            if (weight > 0f) { dh += bh; dS *= bs; dL += bl }
        }
        hsv[0] = (((hsv[0] + dh) % 360f) + 360f) % 360f
        hsv[1] = (hsv[1] * dS).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + dL * 0.5f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv) and 0xFFFFFF
    }

    private fun angleDistance(a: Float, b: Float): Float {
        var d = kotlin.math.abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }
}
