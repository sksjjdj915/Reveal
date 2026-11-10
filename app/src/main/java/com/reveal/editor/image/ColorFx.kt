package com.reveal.editor.image

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Color

/**
 * 调色类操作（对应 桌面端的「图像 > 调整」），全部是「整张位图进、整张位图出」，
 * 选区限制与 16 位同步由 SketchView.applyFilterToActive 统一处理。
 */
object ColorFx {

    private fun map(src: Bitmap, f: (Int) -> Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val n = f(c)
            px[i] = (a shl 24) or (n and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun cl(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** 曝光度：EV 制，-5..5，按 2^ev 线性缩放 */
    fun exposure(src: Bitmap, ev: Float): Bitmap {
        val k = Math.pow(2.0, ev.toDouble()).toFloat()
        return map(src) { c ->
            (cl((Color.red(c) * k).toInt()) shl 16) or (cl((Color.green(c) * k).toInt()) shl 8) or cl((Color.blue(c) * k).toInt())
        }
    }

    /** 自然饱和度：只提低饱和像素，避免过饱和 */
    fun vibrance(src: Bitmap, amount: Int): Bitmap {
        val k = (amount / 100f).coerceIn(-1f, 1f)
        return map(src) { c ->
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            val boost = if (k >= 0) k * (1f - hsv[1]) else k * hsv[1]
            hsv[1] = (hsv[1] + boost).coerceIn(0f, 1f)
            Color.HSVToColor(hsv) and 0x00FFFFFF
        }
    }

    /** 色彩平衡：三段偏移，cr/mg/yb 各 -100..100 */
    fun colorBalance(src: Bitmap, cr: Int, mg: Int, yb: Int): Bitmap {
        val r0 = (cr * 1.0f)
        val g0 = (mg * 1.0f)
        val b0 = (yb * 1.0f)
        return map(src) { c ->
            (cl(Color.red(c) + r0.toInt()) shl 16) or (cl(Color.green(c) + g0.toInt()) shl 8) or cl(Color.blue(c) + b0.toInt())
        }
    }

    /**
     * 黑白：三个权重（%）决定红绿蓝占比。
     * 本质是线性加权 → 用 4×5 颜色矩阵一次画完（GPU），拖动权重不再掉帧。
     */
    fun blackWhite(src: Bitmap, wr: Int, wg: Int, wb: Int): Bitmap {
        val s = (wr + wg + wb).coerceAtLeast(1).toFloat()
        val r = wr / s; val g = wg / s; val b = wb / s
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            r, g, b, 0f, 0f,
            r, g, b, 0f, 0f,
            r, g, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 照片滤镜：把滤镜色按浓度混进来。
     *
     * 数学上就是 `out = base * (1-k) + filterColor * k`，纯线性 → 用 4×5 颜色矩阵一次画完，
     * 拖动浓度不掉帧（原来逐像素 `map` 在 900px 预览上每帧要跑 120 万次）。
     */
    fun photoFilter(src: Bitmap, filterColor: Int, density: Int): Bitmap {
        val k = (density / 100f).coerceIn(0f, 1f) * 0.6f
        val inv = 1f - k
        val fr = Color.red(filterColor) * k
        val fg = Color.green(filterColor) * k
        val fb = Color.blue(filterColor) * k
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            inv, 0f, 0f, 0f, fr,
            0f, inv, 0f, 0f, fg,
            0f, 0f, inv, 0f, fb,
            0f, 0f, 0f, 1f, 0f,
        ))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 照片滤镜的 GPU 快路径：本质是每通道向滤镜色插值，可写成纯缩放矩阵
     * out = c * (1-k) + f * k  →  c * (1-k) + k*f
     * 用 ColorMatrix 一次画完，拖动密度时跟手。
     */
    fun photoFilterMatrix(src: Bitmap, filterColor: Int, density: Int): Bitmap {
        val k = (density / 100f).coerceIn(0f, 1f) * 0.6f
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val sc = 1f - k
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            sc, 0f, 0f, 0f, Color.red(filterColor) * k,
            0f, sc, 0f, 0f, Color.green(filterColor) * k,
            0f, 0f, sc, 0f, Color.blue(filterColor) * k,
            0f, 0f, 0f, 1f, 0f,
        ))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** 色调分离：每通道量化到 N 档。用 256 项 1D 查表，避免每像素反复除法 */
    fun posterize(src: Bitmap, levels: Int): Bitmap {
        val n = levels.coerceIn(2, 255)
        val step = 255f / (n - 1)
        val lut = IntArray(256) { v -> cl((Math.round(v / step) * step).toInt()) }
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = lut[(c shr 16) and 0xFF]
            val g = lut[(c shr 8) and 0xFF]
            val b = lut[c and 0xFF]
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 渐变映射：按明度在两色之间取色 */
    fun gradientMap(src: Bitmap, c1: Int, c2: Int): Bitmap = gradientMap(src, c1, c2, emptyList())

    /**
     * 渐变映射（多色标版）：按明度取色，中间可以再加若干色标。
     * 多色标和调整层里那套用同一个 [AdjustOp.gradLut]，预览和真正落到图层上结果一致。
     */
    fun gradientMap(
        src: Bitmap,
        c1: Int,
        c2: Int,
        stops: List<GradStop>,
        dither: Boolean = false,
        strength: Int = 100,
        smooth: Int = 0,
        reverse: Boolean = false,
    ): Bitmap {
        val op = AdjustOp(
            kind = AdjustOp.KIND_GRADIENT_MAP,
            gmLo = (0xFF shl 24) or (c1 and 0xFFFFFF),
            gmHi = (0xFF shl 24) or (c2 and 0xFFFFFF),
            gmStops = stops,
            gmSmooth = smooth,
            gmReverse = reverse,
        )
        val lut = op.gradLut()
        val w = src.width
        // 桌面端的「仿色」：8 位查表容易出色带，用有序抖动（Bayer 4×4）把量化误差打成噪点
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val px = IntArray(w * src.height)
        src.getPixels(px, 0, w, 0, 0, w, src.height)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            var luma = (299 * r + 587 * g + 114 * b) / 1000
            if (dither) {
                val idx = ((i % w) and 3) + ((i / w) and 3) * 4
                luma = (luma + (bayer[idx] - 8) / 8).coerceIn(0, 255)
            }
            var mapped = lut[luma.coerceIn(0, 255)] and 0xFFFFFF
            if (strength < 100) {
                // 强度：把结果和原图按比例混合（桌面端本体没有这档，但多数手机端调色都有）
                val t = (strength.coerceIn(0, 100)) / 100f
                val mr = (mapped shr 16) and 0xFF
                val mg = (mapped shr 8) and 0xFF
                val mb = mapped and 0xFF
                val nr = (r + (mr - r) * t).toInt().coerceIn(0, 255)
                val ng = (g + (mg - g) * t).toInt().coerceIn(0, 255)
                val nb = (b + (mb - b) * t).toInt().coerceIn(0, 255)
                mapped = (nr shl 16) or (ng shl 8) or nb
            }
            px[i] = (a shl 24) or mapped
        }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, src.height)
        return out
    }

    /** 阴影/高光：sh 提亮暗部，hi 压暗亮部（-100..100） */
    fun shadowsHighlights(src: Bitmap, sh: Int, hi: Int): Bitmap {
        val s = (sh / 100f).coerceIn(-1f, 1f)
        val hl = (hi / 100f).coerceIn(-1f, 1f)
        return map(src) { c ->
            var r = Color.red(c).toFloat(); var g = Color.green(c).toFloat(); var b = Color.blue(c).toFloat()
            val l = (299 * r + 587 * g + 114 * b) / 1000f / 255f
            val d = if (l < 0.5f) s * (1f - l * 2f) * 90f else hl * (l - 0.5f) * 2f * 90f
            r += d; g += d; b += d
            (cl(r.toInt()) shl 16) or (cl(g.toInt()) shl 8) or cl(b.toInt())
        }
    }

    /** 通道混合器：9 个系数（%），输出 = 输入矩阵 × 系数 */
    fun channelMixer(src: Bitmap, m: IntArray): Bitmap {
        val k = FloatArray(9) { m.getOrElse(it) { 0 }.toFloat() / 100f }
        return map(src) { c ->
            val r = Color.red(c).toFloat(); val g = Color.green(c).toFloat(); val b = Color.blue(c).toFloat()
            val nr = r * k[0] + g * k[1] + b * k[2]
            val ng = r * k[3] + g * k[4] + b * k[5]
            val nb = r * k[6] + g * k[7] + b * k[8]
            (cl(nr.toInt()) shl 16) or (cl(ng.toInt()) shl 8) or cl(nb.toInt())
        }
    }

    /** 色调均化：把明度直方图拉开 */
    fun equalize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        var total = 0
        for (c in px) {
            if (Color.alpha(c) == 0) continue
            val l = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
            hist[l]++; total++
        }
        if (total == 0) return src.copy(Bitmap.Config.ARGB_8888, false)
        val lut = IntArray(256)
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            lut[i] = cl(acc * 255 / total)
        }
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val l = (299 * r + 587 * g + 114 * b) / 1000
            val d = lut[l] - l
            out[i] = (a shl 24) or (cl(r + d) shl 16) or (cl(g + d) shl 8) or cl(b + d)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    /** 替换颜色：只改与目标色相近的像素（色相旋转 + 饱和度倍数 + 明度偏移） */
    fun replaceColor(src: Bitmap, target: Int, tolerance: Int, hueShift: Int, satScale: Int, lightShift: Int): Bitmap {
        val hsv = FloatArray(3)
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        return map(src) { c ->
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val d = kotlin.math.abs(r - tr) + kotlin.math.abs(g - tg) + kotlin.math.abs(b - tb)
            if (tolerance > 0 && d > tolerance * 3) c else {
                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                hsv[0] = (hsv[0] + hueShift + 360) % 360f
                hsv[1] = (hsv[1] * (satScale / 100f)).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] + lightShift / 100f).coerceIn(0f, 1f)
                val a = c ushr 24
                (a shl 24) or (android.graphics.Color.HSVToColor(hsv) and 0x00FFFFFF)
            }
        }
    }

    /** 可选颜色：按颜色范围调 CMYK（范围 0 红 1 黄 2 绿 3 青 4 蓝 5 洋红 6 白 7 中性 8 黑） */
    fun selectiveColor(src: Bitmap, range: Int, cIn: Int, mIn: Int, yIn: Int, kIn: Int): Bitmap {
        val hsv = FloatArray(3)
        return map(src) { c ->
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
            val w = when (range) {
                0 -> weight(h, 0f, 40f) * s
                1 -> weight(h, 60f, 40f) * s
                2 -> weight(h, 120f, 50f) * s
                3 -> weight(h, 180f, 40f) * s
                4 -> weight(h, 240f, 45f) * s
                5 -> weight(h, 300f, 40f) * s
                6 -> (1f - s) * v
                7 -> (1f - s) * (1f - kotlin.math.abs(v - 0.5f) * 2f)
                else -> (1f - v)
            }
            if (w <= 0.001f) c else {
                val cmyk = rgbToCmyk(r, g, b)
                val nc = (cmyk[0] + cIn * w / 100f).coerceIn(0f, 1f)
                val nm = (cmyk[1] + mIn * w / 100f).coerceIn(0f, 1f)
                val ny = (cmyk[2] + yIn * w / 100f).coerceIn(0f, 1f)
                val nk = (cmyk[3] + kIn * w / 100f).coerceIn(0f, 1f)
                val out = cmykToRgb(floatArrayOf(nc, nm, ny, nk))
                (c ushr 24 shl 24) or out
            }
        }
    }

    private fun weight(h: Float, center: Float, span: Float): Float {
        var d = kotlin.math.abs(h - center)
        if (d > 180f) d = 360f - d
        return (1f - (d / span)).coerceIn(0f, 1f)
    }

    private fun rgbToCmyk(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val k = 1f - maxOf(rf, gf, bf)
        if (k >= 0.999f) return floatArrayOf(0f, 0f, 0f, 1f)
        return floatArrayOf(
            (1f - rf - k) / (1f - k),
            (1f - gf - k) / (1f - k),
            (1f - bf - k) / (1f - k),
            k
        )
    }

    private fun cmykToRgb(cmyk: FloatArray): Int {
        val k = cmyk[3]
        val r = ((1f - cmyk[0]) * (1f - k) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((1f - cmyk[1]) * (1f - k) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((1f - cmyk[2]) * (1f - k) * 255f).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** 匹配颜色：把当前图的平均色往参照图的平均色拉 */
    fun matchColor(src: Bitmap, ref: Bitmap, strength: Int): Bitmap {
        val a = meanRgb(src); val b = meanRgb(ref)
        val k = (strength / 100f).coerceIn(0f, 1f)
        val dr = (b[0] - a[0]) * k; val dg = (b[1] - a[1]) * k; val db = (b[2] - a[2]) * k
        return map(src) { c ->
            val r = cl((((c shr 16) and 0xFF) + dr).roundToInt())
            val g = cl((((c shr 8) and 0xFF) + dg).roundToInt())
            val bb = cl(((c and 0xFF) + db).roundToInt())
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bb
        }
    }

    private fun meanRgb(b: Bitmap): FloatArray {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            if ((c ushr 24) < 8) continue
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
        }
        if (n == 0) return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat())
    }

    /** 变化：整体往某个方向偏色 + 提亮/压暗（桌面端「变化」的简化版） */
    fun variation(src: Bitmap, hueShift: Int, satScale: Int, bright: Int): Bitmap {
        val hsv = FloatArray(3)
        return map(src) { c ->
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            hsv[0] = (hsv[0] + hueShift + 360) % 360f
            hsv[1] = (hsv[1] * (satScale / 100f)).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + bright / 100f).coerceIn(0f, 1f)
            (c and 0xFF000000.toInt()) or (android.graphics.Color.HSVToColor(hsv) and 0x00FFFFFF)
        }
    }

    /** HDR 色调：用亮度局部平均做提亮暗部 + 局部对比，最后加饱和度 */
    fun hdrTone(src: Bitmap, radius: Int, strength: Int, saturation: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }
        val r = radius.coerceIn(1, 24)
        val blur = boxBlur(lum, w, h, r)
        val s = (strength / 100f).coerceIn(0f, 2f)
        val satK = 1f + saturation / 100f
        val out = IntArray(px.size)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a < 8) { out[i] = c; continue }
            var rr = ((c shr 16) and 0xFF) / 255f
            var gg = ((c shr 8) and 0xFF) / 255f
            var bb = (c and 0xFF) / 255f
            // 暗部提亮：局部平均越亮、原像素越暗，提得越多
            val lift = ((blur[i] - lum[i]) * s * 0.85f).coerceIn(-0.45f, 0.45f)
            rr = (rr + lift).coerceIn(0f, 1f)
            gg = (gg + lift).coerceIn(0f, 1f)
            bb = (bb + lift).coerceIn(0f, 1f)
            android.graphics.Color.RGBToHSV((rr * 255).roundToInt(), (gg * 255).roundToInt(), (bb * 255).roundToInt(), hsv)
            hsv[1] = (hsv[1] * satK).coerceIn(0f, 1f)
            out[i] = (a shl 24) or (android.graphics.Color.HSVToColor(hsv) and 0x00FFFFFF)
        }
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(out, 0, w, 0, 0, w, h)
        return dst
    }


    /** 自动色调：各通道直方图剪掉两端 0.5% 后拉满 */
    fun autoTone(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val n = w * h
        val cut = max(1, (n * 0.005f).toInt())
        val lutR = IntArray(256); val lutG = IntArray(256); val lutB = IntArray(256)
        for (c in 0..2) {
            val hist = IntArray(256)
            for (p in px) hist[shift(p, c) and 0xFF]++
            var lo = 0; var acc = 0
            while (lo < 255 && acc + hist[lo] < cut) { acc += hist[lo]; lo++ }
            var hi = 255; acc = 0
            while (hi > 0 && acc + hist[hi] < cut) { acc += hist[hi]; hi-- }
            val span = (hi - lo).coerceAtLeast(1)
            val lut = when (c) { 0 -> lutR; 1 -> lutG; else -> lutB }
            for (i in 0..255) lut[i] = (((i - lo) * 255f / span).toInt()).coerceIn(0, 255)
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            out[i] = (p and 0xFF000000.toInt()) or (lutR[(p ushr 16) and 0xFF] shl 16) or (lutG[(p ushr 8) and 0xFF] shl 8) or lutB[p and 0xFF]
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 自动对比度：按整体明度剪两端，三通道用同一条曲线 */
    fun autoContrast(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val n = w * h
        val cut = max(1, (n * 0.005f).toInt())
        val hist = IntArray(256)
        for (p in px) hist[((0.299f * ((p ushr 16) and 0xFF) + 0.587f * ((p ushr 8) and 0xFF) + 0.114f * (p and 0xFF)).toInt()).coerceIn(0, 255)]++
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] < cut) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] < cut) { acc += hist[hi]; hi-- }
        val span = (hi - lo).coerceAtLeast(1)
        val pf = IntArray(256) { (((it - lo) * 255f / span).toInt()).coerceIn(0, 255) }
        val out = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            out[i] = (p and 0xFF000000.toInt()) or
                (pf[(p ushr 16) and 0xFF] shl 16) or (pf[(p ushr 8) and 0xFF] shl 8) or pf[p and 0xFF]
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 自动颜色：各通道裁两端，再拉向中性灰，消掉偏色 */
    fun autoColor(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val n = w * h
        val cut = max(1, (n * 0.005f).toInt())
        val luts = Array(3) { IntArray(256) }
        for (c in 0..2) {
            val hist = IntArray(256)
            for (p in px) hist[shift(p, c) and 0xFF]++
            var lo = 0; var acc = 0
            while (lo < 255 && acc + hist[lo] < cut) { acc += hist[lo]; lo++ }
            var hi = 255; acc = 0
            while (hi > 0 && acc + hist[hi] < cut) { acc += hist[hi]; hi-- }
            val span = (hi - lo).coerceAtLeast(1)
            val lut = luts[c]
            for (i in 0..255) lut[i] = (((i - lo) * 255f / span).toInt()).coerceIn(0, 255)
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = luts[0][(p ushr 16) and 0xFF]
            val g = luts[1][(p ushr 8) and 0xFF]
            val b = luts[2][p and 0xFF]
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            val nr = ((r + gray) / 2).coerceIn(0, 255)
            val ng = ((g + gray) / 2).coerceIn(0, 255)
            val nb = ((b + gray) / 2).coerceIn(0, 255)
            out[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun shift(color: Int, c: Int): Int = when (c) { 0 -> color ushr 16; 1 -> color ushr 8; else -> color }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            var sum = 0f; var n = 0
            for (x in -r..r) { val xx = x.coerceIn(0, w - 1); sum += src[y * w + xx]; n++ }
            for (x in 0 until w) {
                tmp[y * w + x] = sum / n
                val add = (x + r + 1).coerceIn(0, w - 1); val sub = (x - r).coerceIn(0, w - 1)
                sum += src[y * w + add] - src[y * w + sub]
            }
        }
        for (x in 0 until w) {
            var sum = 0f; var n = 0
            for (y in -r..r) { val yy = y.coerceIn(0, h - 1); sum += tmp[yy * w + x]; n++ }
            for (y in 0 until h) {
                out[y * w + x] = sum / n
                val add = (y + r + 1).coerceIn(0, h - 1); val sub = (y - r).coerceIn(0, h - 1)
                sum += tmp[add * w + x] - tmp[sub * w + x]
            }
        }
        return out
    }
}

