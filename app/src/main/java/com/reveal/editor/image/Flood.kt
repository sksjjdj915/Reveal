package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Color
import java.util.BitSet
import kotlin.math.abs

/**
 * 油漆桶：从某点开始把颜色相近的连通区域选出来（返回 w*h 的布尔遮罩）。
 *
 * 用「队列 + 访问标记」的广度优先：每个像素最多入队一次，队列容量 w*h 就绝不会越界
 * （旧版逐像素压栈时容量算漏，个别大图会 ArrayIndexOutOfBounds 闪退）。
 */
object Flood {

    fun mask(src: Bitmap, sx: Int, sy: Int, tolerance: Float): BooleanArray {
        val w = src.width
        val h = src.height
        val out = BooleanArray(w * h)
        if (w <= 0 || h <= 0) return out
        if (sx !in 0 until w || sy !in 0 until h) return out

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val seed = px[sy * w + sx]
        val limit = (tolerance.coerceIn(0f, 1f) * 320f + 1f).toInt()
        val seedAlphaZero = Color.alpha(seed) == 0

        val visited = BitSet(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        val startIdx = sy * w + sx
        queue[tail++] = startIdx
        visited.set(startIdx)

        while (head < tail) {
            val idx = queue[head++]
            if (!similar(px[idx], seed, limit, seedAlphaZero)) continue
            out[idx] = true
            val x = idx % w
            val y = idx / w

            if (x > 0) {
                val n = idx - 1
                if (!visited.get(n)) { visited.set(n); queue[tail++] = n }
            }
            if (x < w - 1) {
                val n = idx + 1
                if (!visited[n]) { visited[n] = true; queue[tail++] = n }
            }
            if (y > 0) {
                val n = idx - w
                if (!visited[n]) { visited[n] = true; queue[tail++] = n }
            }
            if (y < h - 1) {
                val n = idx + w
                if (!visited[n]) { visited[n] = true; queue[tail++] = n }
            }
        }
        return out
    }

    private fun similar(c: Int, seed: Int, limit: Int, seedAlphaZero: Boolean): Boolean {
        val a = Color.alpha(c)
        if (seedAlphaZero) return a == 0
        if (a == 0) return false
        val d = abs(Color.red(c) - Color.red(seed)) +
                abs(Color.green(c) - Color.green(seed)) +
                abs(Color.blue(c) - Color.blue(seed))
        return d <= limit
    }
}