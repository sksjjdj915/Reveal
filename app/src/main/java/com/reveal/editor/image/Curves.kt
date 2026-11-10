package com.reveal.editor.image

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 曲线：和 桌面端的「图像 → 调整 → 曲线」一个意思。
 * 控制点是拍平存放的 [x0, y0, x1, y1, ...]，坐标都是 0~1，x 递增。
 * 默认只有两个点：(0,0) 和 (1,1)，也就是不改变任何像素。
 */
object Curves {

    const val CHANNEL_RGB = -1
    const val CHANNEL_R = 0
    const val CHANNEL_G = 1
    const val CHANNEL_B = 2

    data class Params(
        val channel: Int = CHANNEL_RGB,
        val points: List<Float> = DEFAULT_POINTS,
        val redPoints: List<Float> = DEFAULT_POINTS,
        val greenPoints: List<Float> = DEFAULT_POINTS,
        val bluePoints: List<Float> = DEFAULT_POINTS,
    )

    val DEFAULT_POINTS = listOf(0f, 0f, 1f, 1f)

    private fun isFlat(pts: List<Float>): Boolean {
        if (pts.size < 4) return true
        for (i in pts.indices step 2) {
            if (abs(pts[i] - pts[i + 1]) > 0.004f) return false
        }
        return true
    }

    /** 四条曲线全平才叫「没改」 */
    fun isNeutral(p: Params): Boolean =
        isFlat(p.points) && isFlat(p.redPoints) && isFlat(p.greenPoints) && isFlat(p.bluePoints)

    /** 生成 256 项查找表；用单调三次插值（Fritsch–Carlson），不会出现 桌面端里那种过冲 */
    fun lut(p: Params): IntArray = lutOf(p.points)

    /** 同 [lut]，直接给点表（每个通道各一条曲线时用） */
    fun lutOf(pts: List<Float>): IntArray {
        val n = pts.size / 2
        val out = IntArray(256) { it }
        if (n < 2) return out

        val xs = FloatArray(n) { pts[it * 2].coerceIn(0f, 1f) }
        val ys = FloatArray(n) { pts[it * 2 + 1].coerceIn(0f, 1f) }
        // 保证 x 严格递增（面板里已经夹住，这里再兜一次底）
        for (i in 1 until n) if (xs[i] <= xs[i - 1]) xs[i] = xs[i - 1] + 1e-4f

        val d = FloatArray(n - 1) {
            (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-6f)
        }
        val m = FloatArray(n)
        if (n == 2) {
            m[0] = d[0]
            m[1] = d[0]
        } else {
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) {
                m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            }
            for (i in 0 until n - 1) {
                if (d[i] == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                    continue
                }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }

        for (k in 0..255) {
            val x = k / 255f
            var v: Float
            if (x <= xs[0]) {
                v = ys[0]
            } else if (x >= xs[n - 1]) {
                v = ys[n - 1]
            } else {
                var seg = 0
                for (i in 0 until n - 1) {
                    if (x >= xs[i] && x <= xs[i + 1]) {
                        seg = i
                        break
                    }
                }
                val h = xs[seg + 1] - xs[seg]
                val t = ((x - xs[seg]) / h).coerceIn(0f, 1f)
                val t2 = t * t
                val t3 = t2 * t
                val h00 = 2 * t3 - 3 * t2 + 1
                val h10 = t3 - 2 * t2 + t
                val h01 = -2 * t3 + 3 * t2
                val h11 = t3 - t2
                v = h00 * ys[seg] + h10 * h * m[seg] + h01 * ys[seg + 1] + h11 * h * m[seg + 1]
            }
            out[k] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return out
    }

    /** 应用曲线；参数是默认的就返回 null（调用方当作「什么都没变」） */
    fun apply(src: Bitmap, p: Params): Bitmap? {
        if (isNeutral(p)) return null
        // 桌面端的做法：主 RGB 曲线先算，再叠各通道自己的曲线，三者互不共用一个点表
        val master = lutOf(p.points)
        val tr = lutOf(p.redPoints)
        val tg = lutOf(p.greenPoints)
        val tb = lutOf(p.bluePoints)
        val fr = IntArray(256) { tr[master[it]] }
        val fg = IntArray(256) { tg[master[it]] }
        val fb = IntArray(256) { tb[master[it]] }
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 0) continue
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            r = fr[r]
            g = fg[g]
            b = fb[b]
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
