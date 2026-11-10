package com.reveal.editor.image

import android.graphics.Bitmap

/**
 * 3D LUT：把「逐像素要算一堆函数（HSV 转换、肤色权重…）」的效果，先在小立方体上采样一遍，
 * 之后每个像素只做 8 次查表 + 三线性插值。
 *
 * 为什么这么做（参考文献 / 成熟做法）：
 * - 影视调色行业的标准做法就是 3D LUT（.cube / 通用 LUT 文件），一个 17³~33³ 的表足够表达
 *   色相偏移、饱和度、自然饱和度这类「点对点颜色映射」，误差肉眼几乎看不出；
 * - Android 侧没有内建 3D LUT，但逐像素查表的开销远小于 `Color.colorToHSV` +
 *   `Color.HSVToColor`（每像素两次 JNI），在 480px 预览上通常能快 5~10 倍；
 * - 表只在参数变化时重建一次（24³ = 13824 次，几十毫秒），拖动时每帧只做查表。
 */
object Lut3D {

    /** 默认立方体边长；24³ 在 480px 预览上已看不出分层 */
    const val DEFAULT_N = 24

    /** 采样表：下标 (r*N + g)*N + b，值 packed 0xRRGGBB */
    fun table(n: Int, f: (Int) -> Int): IntArray {
        val out = IntArray(n * n * n)
        val step = 255f / (n - 1)
        var i = 0
        for (r in 0 until n) {
            val rr = (r * step).toInt()
            for (g in 0 until n) {
                val gg = (g * step).toInt()
                for (b in 0 until n) {
                    val bb = (b * step).toInt()
                    out[i++] = f((0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb) and 0xFFFFFF
                }
            }
        }
        return out
    }

    /** 三线性插值应用 */
    fun apply(src: Bitmap, lut: IntArray, n: Int = DEFAULT_N): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (w <= 0 || h <= 0) return out
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val scale = (n - 1) / 255f
        val nm1 = n - 1
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            val rf = r * scale; val gf = g * scale; val bf = b * scale
            val r0 = rf.toInt(); val g0 = gf.toInt(); val b0 = bf.toInt()
            val r1 = if (r0 < nm1) r0 + 1 else nm1
            val g1 = if (g0 < nm1) g0 + 1 else nm1
            val b1 = if (b0 < nm1) b0 + 1 else nm1
            val fr = rf - r0; val fg = gf - g0; val fb = bf - b0

            val i000 = (r0 * n + g0) * n + b0
            val i001 = (r0 * n + g0) * n + b1
            val i010 = (r0 * n + g1) * n + b0
            val i011 = (r0 * n + g1) * n + b1
            val i100 = (r1 * n + g0) * n + b0
            val i101 = (r1 * n + g0) * n + b1
            val i110 = (r1 * n + g1) * n + b0
            val i111 = (r1 * n + g1) * n + b1

            val c000 = lut[i000]; val c001 = lut[i001]; val c010 = lut[i010]; val c011 = lut[i011]
            val c100 = lut[i100]; val c101 = lut[i101]; val c110 = lut[i110]; val c111 = lut[i111]

            // 分别对 R/G/B 做三线性插值
            val rOut = lerp3(c000 shr 16, c001 shr 16, c010 shr 16, c011 shr 16,
                c100 shr 16, c101 shr 16, c110 shr 16, c111 shr 16, fr, fg, fb)
            val gOut = lerp3((c000 shr 8) and 0xFF, (c001 shr 8) and 0xFF, (c010 shr 8) and 0xFF, (c011 shr 8) and 0xFF,
                (c100 shr 8) and 0xFF, (c101 shr 8) and 0xFF, (c110 shr 8) and 0xFF, (c111 shr 8) and 0xFF, fr, fg, fb)
            val bOut = lerp3(c000 and 0xFF, c001 and 0xFF, c010 and 0xFF, c011 and 0xFF,
                c100 and 0xFF, c101 and 0xFF, c110 and 0xFF, c111 and 0xFF, fr, fg, fb)

            px[i] = (a shl 24) or ((rOut and 0xFF) shl 16) or ((gOut and 0xFF) shl 8) or (bOut and 0xFF)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun lerp3(
        c000: Int, c001: Int, c010: Int, c011: Int,
        c100: Int, c101: Int, c110: Int, c111: Int,
        fr: Float, fg: Float, fb: Float,
    ): Int {
        val c00 = c000 + (c001 - c000) * fb
        val c01 = c010 + (c011 - c010) * fb
        val c10 = c100 + (c101 - c100) * fb
        val c11 = c110 + (c111 - c110) * fb
        val c0 = c00 + (c01 - c00) * fg
        val c1 = c10 + (c11 - c10) * fg
        return (c0 + (c1 - c0) * fr + 0.5f).toInt().coerceIn(0, 255)
    }
}
