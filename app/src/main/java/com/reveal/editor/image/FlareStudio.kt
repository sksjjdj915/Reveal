package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 光晕工作室：把「基础光元素」自由组合成一组光效。
 * 参考常见光晕插件的构成——辉光(glow)、光环(ring)、鬼影(ghost)、星芒(star)、拉丝(streak)，
 * 每个元素各自有位置/大小/亮度/色温，叠加后就是一组自定义光效。
 */
object FlareStudio {

    /** 基础光元素；位置与大小都是相对画面比例（0~1） */
    class Elem(
        var kind: Int = 0,        // 0 辉光 / 1 光环 / 2 鬼影 / 3 星芒 / 4 拉丝
        var xp: Float = 0.5f,
        var yp: Float = 0.5f,
        var size: Float = 0.25f,  // 相对 min(w,h)
        var bright: Float = 0.6f, // 0~1
        var tint: Int = 1,        // 0 中性 / 1 暖 / 2 冷
    )

    val NAMES = arrayOf("辉光", "光环", "鬼影", "星芒", "拉丝")

    private fun tintRgb(t: Int): FloatArray = when (t) {
        1 -> floatArrayOf(1f, 0.94f, 0.78f)
        2 -> floatArrayOf(0.86f, 0.94f, 1f)
        else -> floatArrayOf(1f, 1f, 1f)
    }

    fun compose(src: Bitmap, elems: List<Elem>): Bitmap {
        if (elems.isEmpty()) return src.copy(Bitmap.Config.ARGB_8888, true)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()
        val base = min(w, h).toFloat()
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var ar = 0f; var ag = 0f; var ab = 0f
                for (e in elems) {
                    val cx = e.xp * w
                    val cy = e.yp * h
                    val maxR = (e.size * base).coerceAtLeast(4f)
                    val dx = x - cx
                    val dy = y - cy
                    val d = hypot(dx, dy)
                    val t = tintRgb(e.tint)
                    var a = 0f
                    when (e.kind) {
                        0 -> if (d < maxR) {
                            val k = 1f - d / maxR
                            a = k * k * k * 1.3f
                        }
                        1 -> {
                            val rr = maxR * 0.75f
                            val band = abs(d - rr) / (maxR * 0.12f + 1f)
                            if (band < 1f) a = (1f - band) * 0.55f
                        }
                        2 -> {
                            // 鬼影：沿光轴反向排布的几个小圆
                            for (i in 1..5) {
                                val f = -0.45f * i
                                val gx = cx + dx * f
                                val gy = cy + dy * f
                                val gr = maxR * (0.06f + 0.05f * ((i * 3) % 4))
                                val gd = hypot(x - gx, y - gy)
                                if (gd < gr) {
                                    val k = 1f - gd / gr
                                    a += k * k * 0.3f
                                }
                            }
                        }
                        3 -> {
                            // 星芒：水平/垂直（+对角）几条细光条
                            val vx = abs(dx)
                            val vy = abs(dy)
                            val thin = maxR * 0.035f
                            if (d < maxR) {
                                val k = 1f - d / maxR
                                if (vy < thin) a += k * 0.7f
                                if (vx < thin) a += k * 0.7f
                                if (abs(vx - vy) < thin * 0.9f && d < maxR * 0.8f) a += k * 0.35f
                            }
                        }
                        else -> {
                            // 拉丝：横向一条长光条
                            val thin = maxR * 0.05f
                            val len = maxR * 2.6f
                            if (abs(dy) < thin && abs(dx) < len) {
                                val k = 1f - abs(dx) / len
                                a += k * k * 0.8f
                            }
                        }
                    }
                    a *= e.bright
                    ar += a * 255f * t[0]; ag += a * 255f * t[1]; ab += a * 255f * t[2]
                }
                if (ar + ag + ab > 0.5f) {
                    val c = px[y * w + x]
                    val r = cl(((c shr 16) and 0xFF) + ar.coerceAtMost(255f))
                    val g = cl(((c shr 8) and 0xFF) + ag.coerceAtMost(255f))
                    val b = cl((c and 0xFF) + ab.coerceAtMost(255f))
                    out[y * w + x] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun cl(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> v.toInt()
    }
}
