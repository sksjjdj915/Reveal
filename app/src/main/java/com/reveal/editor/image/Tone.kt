package com.reveal.editor.image

import android.graphics.Bitmap

/**
 * 亮度 / 对比度 / 高光 / 阴影（桌面端「亮度/对比度」+ 高光阴影那一组的合并实现）。
 *
 * - 亮度：整体加偏移（-100..100 → -1..1 的归一化偏移）；
 * - 对比度：以中灰 0.5 为轴，`out = (in - 0.5) * k + 0.5`，k 由滑块映射到 0.2~2.0；
 * - 高光：只抬/压亮部，权重 `w = max(0, (v - 0.5) * 2)`；
 * - 阴影：只抬/压暗部，权重 `w = max(0, (0.5 - v) * 2)`。
 * 三通道同样处理，不做白平衡偏移，所以不会偏色。
 */
object Tone {

    fun apply(
        src: Bitmap,
        brightness: Int,
        contrast: Int,
        highlight: Int,
        shadow: Int,
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return out
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)

        val b = brightness / 100f
        val c = (contrast / 100f).coerceIn(-1f, 1f)
        val k = if (c >= 0) 1f + c * 1f else 1f + c * 0.8f
        val hi = highlight / 100f
        val sh = shadow / 100f

        for (i in px.indices) {
            val col = px[i]
            val a = col ushr 24
            if (a == 0) continue
            var r = ((col shr 16) and 0xFF) / 255f
            var g = ((col shr 8) and 0xFF) / 255f
            var bl = (col and 0xFF) / 255f

            fun tone(v: Float): Float {
                var t = v + b
                t = (t - 0.5f) * k + 0.5f
                val luma = (0.299f * r + 0.587f * g + 0.114f * bl)
                if (hi != 0f) {
                    val wgt = ((luma - 0.5f) * 2f).coerceIn(0f, 1f)
                    t += hi * wgt * 0.6f
                }
                if (sh != 0f) {
                    val wgt = ((0.5f - luma) * 2f).coerceIn(0f, 1f)
                    t += sh * wgt * 0.6f
                }
                return t.coerceIn(0f, 1f)
            }

            r = tone(r)
            g = tone(g)
            bl = tone(bl)

            val rr = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gg = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bb = (bl * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }

        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * LUT 快路径：亮/对比/高光/阴影都是「颜色 → 颜色」的点映射，
     * 采样成 24³ 的 3D LUT 后每像素只查表，拖动时不再因为逐像素运算卡住主线程。
     */
    fun applyLut(
        src: Bitmap,
        brightness: Int,
        contrast: Int,
        highlight: Int,
        shadow: Int,
    ): Bitmap {
        val lut = Lut3D.table(Lut3D.DEFAULT_N) { c ->
            onePixel(c, brightness, contrast, highlight, shadow)
        }
        return Lut3D.apply(src, lut, Lut3D.DEFAULT_N)
    }

    /** 单像素版本（与 [apply] 的循环体一致），返回 0xRRGGBB */
    fun onePixel(cIn: Int, brightness: Int, contrast: Int, highlight: Int, shadow: Int): Int {
        val b = brightness / 100f
        val c = (contrast / 100f).coerceIn(-1f, 1f)
        val k = if (c >= 0) 1f + c else 1f + c * 0.8f
        val hi = highlight / 100f
        val sh = shadow / 100f
        var r = ((cIn shr 16) and 0xFF) / 255f
        var g = ((cIn shr 8) and 0xFF) / 255f
        var bl = (cIn and 0xFF) / 255f
        val luma = 0.299f * r + 0.587f * g + 0.114f * bl
        fun tone(v: Float): Float {
            var t = v + b
            t = (t - 0.5f) * k + 0.5f
            if (hi != 0f) t += hi * ((luma - 0.5f) * 2f).coerceIn(0f, 1f) * 0.6f
            if (sh != 0f) t += sh * ((0.5f - luma) * 2f).coerceIn(0f, 1f) * 0.6f
            return t.coerceIn(0f, 1f)
        }
        r = tone(r); g = tone(g); bl = tone(bl)
        return ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (bl * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
