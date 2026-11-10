package com.reveal.editor.image

import android.graphics.Bitmap

/**
 * 色阶（照 桌面端的「图像 → 调整 → 色阶」做）。
 *
 * 通道：-1 = RGB（三个通道一起），0/1/2 = 只作用于 红/绿/蓝。
 * 输入色阶：黑场、伽马（中灰）、白场；输出色阶：黑场、白场。
 */
object Levels {

    const val CHANNEL_RGB = -1
    const val CHANNEL_R = 0
    const val CHANNEL_G = 1
    const val CHANNEL_B = 2

    data class Params(
        val channel: Int = CHANNEL_RGB,
        val inBlack: Int = 0,
        val gamma: Float = 1f,
        val inWhite: Int = 255,
        val outBlack: Int = 0,
        val outWhite: Int = 255,
    ) {
        fun isNeutral(): Boolean =
            inBlack == 0 && inWhite == 255 && outBlack == 0 && outWhite == 255 &&
                kotlin.math.abs(gamma - 1f) < 0.001f
    }

    /** 256 项查找表：先按输入色阶拉伸 + 伽马，再压缩到输出色阶 */
    fun lut(p: Params): IntArray {
        val inB = p.inBlack.coerceIn(0, 254)
        val inW = p.inWhite.coerceIn(inB + 1, 255)
        val g = p.gamma.coerceIn(0.1f, 9.9f)
        val outB = p.outBlack.coerceIn(0, 255)
        val outW = p.outWhite.coerceIn(0, 255)
        val table = IntArray(256)
        val span = (inW - inB).toFloat()
        for (i in 0..255) {
            var t = ((i - inB) / span).coerceIn(0f, 1f)
            if (g != 1f) t = Math.pow(t.toDouble(), 1.0 / g).toFloat()
            table[i] = (outB + t * (outW - outB)).toInt().coerceIn(0, 255)
        }
        return table
    }

    /** 出图；参数中性返回 null（省一次复制） */
    fun apply(src: Bitmap, p: Params): Bitmap? {
        if (p.isNeutral()) return null
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val table = lut(p)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24 and 0xFF
            if (a == 0) continue
            val r = c ushr 16 and 0xFF
            val g = c ushr 8 and 0xFF
            val b = c and 0xFF
            val nr = if (p.channel == CHANNEL_RGB || p.channel == CHANNEL_R) table[r] else r
            val ng = if (p.channel == CHANNEL_RGB || p.channel == CHANNEL_G) table[g] else g
            val nb = if (p.channel == CHANNEL_RGB || p.channel == CHANNEL_B) table[b] else b
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
