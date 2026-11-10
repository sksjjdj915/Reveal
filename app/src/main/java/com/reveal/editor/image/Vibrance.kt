package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 自然饱和度（Vibrance）的完整实现。
 *
 * 和「饱和度」（Saturation）的区别，按公开资料里的普遍说法是：
 * - 饱和度：所有像素按同一比例加色，已经鲜艳的地方更容易溢出、肤色最容易先崩；
 * - 自然饱和度：**只往不够鲜的地方加，已经饱和的地方几乎不动**，并且**保护肤色**
 *   （皮肤在 HSV 里是橙红一带，权重再降一档）。
 *
 * 这里的权重：
 *   w = (1 - s)                     （越不鲜，越值得加）
 *   w *= skinFactor                 （开保护肤色时，橙红一带乘 0.35）
 * 负向（降低自然饱和度）则按 s 衰减，避免把纯色块抽成灰。
 *
 * 饱和度是独立一档，作用在自然饱和度之后（和 桌面端面板里两个滑块并列一致）。
 */
object Vibrance {

    /** 肤色判定：色相 330°~40° 且饱和度 < 0.65，认为是皮肤 */
    private fun skinFactor(hue: Float, sat: Float): Float {
        if (sat > 0.65f) return 1f
        val h = if (hue < 60f) hue + 360f else hue // 330~360 与 0~40 合成一段
        return if (h >= 330f || h <= 40f) 0.35f else 1f
    }

    fun apply(
        src: Bitmap,
        vibrance: Int,
        saturation: Int,
        protectSkin: Boolean = true,
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return out
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)

        val vk = (vibrance / 100f).coerceIn(-1f, 1f)
        val sk = 1f + (saturation / 100f).coerceIn(-1f, 1f)
        val hsv = FloatArray(3)

        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            Color.colorToHSV(c or 0xFF000000.toInt(), hsv)
            var s = hsv[1]

            // 自然饱和度：低饱和加得多、高饱和几乎不动；正向保护肤色
            if (vk != 0f) {
                val weight = if (vk > 0) {
                    var wgt = 1f - s
                    if (protectSkin) wgt *= skinFactor(hsv[0], s)
                    wgt
                } else {
                    s
                }
                s = (s + vk * weight * 0.85f).coerceIn(0f, 1f)
            }

            // 饱和度：统一比例
            if (sk != 1f) s = (s * sk).coerceIn(0f, 1f)

            hsv[1] = s
            val rgb = Color.HSVToColor(hsv) and 0x00FFFFFF
            px[i] = (a shl 24) or rgb
        }

        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 自然饱和度的 LUT 快路径：效果是「颜色 → 颜色」的点映射，可以采样成 24³ 的 3D LUT，
     * 之后每像素只做查表，避免每像素两次 HSV 的 JNI 调用（掉帧的主因）。
     */
    /** 3D LUT 缓存：拖动时参数不变的那几帧不必重建表（13824 项 × 一次 HSV） */
    private var lutKey: String = ""
    private var lutCache: IntArray? = null

    fun applyLut(
        src: Bitmap,
        vibrance: Int,
        saturation: Int,
        protectSkin: Boolean = true,
    ): Bitmap {
        val vk = (vibrance / 100f).coerceIn(-1f, 1f)
        val sk = 1f + (saturation / 100f).coerceIn(-1f, 1f)
        val key = vibrance.toString() + "/" + saturation + "/" + protectSkin
        var lut = lutCache
        if (lut == null || key != lutKey) {
            lut = Lut3D.table(Lut3D.DEFAULT_N) { c -> onePixel(c, vk, sk, protectSkin) }
            lutCache = lut
            lutKey = key
        }
        return Lut3D.apply(src, lut, Lut3D.DEFAULT_N)
    }

    /** 单像素版本（和 [apply] 的循环体一致），返回 0xRRGGBB */
    fun onePixel(cIn: Int, vk: Float, sk: Float, protectSkin: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(cIn or 0xFF000000.toInt(), hsv)
        var s = hsv[1]
        if (vk != 0f) {
            val weight = if (vk > 0) {
                var wgt = 1f - s
                if (protectSkin) wgt *= skinFactor(hsv[0], s)
                wgt
            } else {
                s
            }
            s = (s + vk * weight * 0.85f).coerceIn(0f, 1f)
        }
        if (sk != 1f) s = (s * sk).coerceIn(0f, 1f)
        hsv[1] = s
        return Color.HSVToColor(hsv) and 0xFFFFFF
    }
}
