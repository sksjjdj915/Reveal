package com.reveal.editor.image

import kotlin.math.abs

/**
 * 透视变换用到的数学：四点到四点的单应矩阵（homography）。
 * 透视变换在「自由变换」里用来把四角随便拖成梯形，而普通 Matrix 只能做仿射（平行四边形），做不到。
 */
object Perspective {

    /**
     * 求把 src 四点映射到 dst 四点的 3x3 矩阵（最后一格归一化为 1）。
     * 四点顺序都必须是 左上、右上、右下、左下。退化情况返回 null。
     */
    fun homography(src: FloatArray, dst: FloatArray): FloatArray? {
        if (src.size < 8 || dst.size < 8) return null
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i * 2].toDouble()
            val y = src[i * 2 + 1].toDouble()
            val u = dst[i * 2].toDouble()
            val v = dst[i * 2 + 1].toDouble()
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (col in 0 until 8) {
            var pivot = -1
            var best = 1e-9
            for (row in col until 8) {
                val v = abs(a[row][col])
                if (v > best) {
                    best = v
                    pivot = row
                }
            }
            if (pivot < 0) return null
            if (pivot != col) {
                val tmp = a[col]
                a[col] = a[pivot]
                a[pivot] = tmp
            }
            val p = a[col][col]
            for (k in col until 9) a[col][k] /= p
            for (row in 0 until 8) {
                if (row == col) continue
                val f = a[row][col]
                if (f == 0.0) continue
                for (k in col until 9) a[row][k] -= f * a[col][k]
            }
        }
        val h = FloatArray(9)
        for (i in 0 until 8) {
            val v = a[i][8]
            if (!v.isFinite()) return null
            h[i] = v.toFloat()
        }
        h[8] = 1f
        return h
    }

    /** 用单应矩阵把一个点映射过去；分母为 0（无穷远）时返回 null */
    fun map(h: FloatArray, x: Float, y: Float, out: FloatArray): Boolean {
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-6f) return false
        out[0] = (h[0] * x + h[1] * y + h[2]) / w
        out[1] = (h[3] * x + h[4] * y + h[5]) / w
        return out[0].isFinite() && out[1].isFinite()
    }

    /**
     * 四点构成的四边形是不是「合法」的：四个点顺序一致（不自交）、面积不能太小。
     * 拖角时用它当约束，拖出交叉/凹掉的一律不接受。
     */
    fun isValidQuad(q: FloatArray, minArea: Float = 64f): Boolean {
        if (q.size < 8) return false
        var sign = 0
        for (i in 0 until 4) {
            val x1 = q[i * 2]
            val y1 = q[i * 2 + 1]
            val x2 = q[((i + 1) % 4) * 2]
            val y2 = q[((i + 1) % 4) * 2 + 1]
            val x3 = q[((i + 2) % 4) * 2]
            val y3 = q[((i + 2) % 4) * 2 + 1]
            val cross = (x2 - x1) * (y3 - y2) - (y2 - y1) * (x3 - x2)
            if (abs(cross) < 1e-3f) return false
            val s = if (cross > 0f) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return area(q) >= minArea
    }

    /** 四边形面积（鞋带公式） */
    fun area(q: FloatArray): Float {
        if (q.size < 8) return 0f
        var sum = 0f
        for (i in 0 until 4) {
            val x1 = q[i * 2]
            val y1 = q[i * 2 + 1]
            val x2 = q[((i + 1) % 4) * 2]
            val y2 = q[((i + 1) % 4) * 2 + 1]
            sum += x1 * y2 - x2 * y1
        }
        return abs(sum) / 2f
    }
}
