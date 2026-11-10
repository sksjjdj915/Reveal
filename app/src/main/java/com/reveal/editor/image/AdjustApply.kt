package com.reveal.editor.image

import android.graphics.Bitmap

/**
 * 把调整层直接作用到一张位图的像素上。
 *
 * 画布上是用 ColorFilter / 色阶查表实时做的，但首页缩略图和工程预览是「拍平后的图」，
 * 那里没有图层链，只能逐像素算一遍，否则缩略图永远显示成没调过的原图。
 */
object AdjustApply {

    fun applyInPlace(bmp: Bitmap, op: AdjustOp) {
        if (op.isNeutral()) return
        if (op.kind == AdjustOp.KIND_LEVELS) applyLevels(bmp, op) else applyMatrix(bmp, op)
    }

    private fun applyMatrix(bmp: Bitmap, op: AdjustOp) {
        val m = op.matrix().array
        val r0 = m[0]; val r1 = m[1]; val r2 = m[2]; val ro = m[4]
        val g0 = m[5]; val g1 = m[6]; val g2 = m[7]; val go = m[9]
        val b0 = m[10]; val b1 = m[11]; val b2 = m[12]; val bo = m[14]
        val band = 128
        var y = 0
        while (y < bmp.height) {
            val h = minOf(band, bmp.height - y)
            val px = IntArray(bmp.width * h)
            bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, h)
            for (k in px.indices) {
                val c = px[k]
                val a = c ushr 24 and 0xFF
                if (a == 0) continue
                val r = c ushr 16 and 0xFF
                val g = c ushr 8 and 0xFF
                val b = c and 0xFF
                val nr = (r0 * r + r1 * g + r2 * b + ro).toInt().coerceIn(0, 255)
                val ng = (g0 * r + g1 * g + g2 * b + go).toInt().coerceIn(0, 255)
                val nb = (b0 * r + b1 * g + b2 * b + bo).toInt().coerceIn(0, 255)
                px[k] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            bmp.setPixels(px, 0, bmp.width, 0, y, bmp.width, h)
            y += band
        }
    }

    private fun applyLevels(bmp: Bitmap, op: AdjustOp) {
        val ps = op.levelsOrDefault()
        val lutR = Levels.lut(ps[1])
        val lutG = Levels.lut(ps[2])
        val lutB = Levels.lut(ps[3])
        val lutC = Levels.lut(ps[0])
        val band = 128
        var y = 0
        while (y < bmp.height) {
            val h = minOf(band, bmp.height - y)
            val px = IntArray(bmp.width * h)
            bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, h)
            for (k in px.indices) {
                val c = px[k]
                val a = c ushr 24 and 0xFF
                if (a == 0) continue
                val r0 = lutR[c ushr 16 and 0xFF]
                val g0 = lutG[c ushr 8 and 0xFF]
                val b0 = lutB[c and 0xFF]
                px[k] = (a shl 24) or (lutC[r0] shl 16) or (lutC[g0] shl 8) or lutC[b0]
            }
            bmp.setPixels(px, 0, bmp.width, 0, y, bmp.width, h)
            y += band
        }
    }
}
