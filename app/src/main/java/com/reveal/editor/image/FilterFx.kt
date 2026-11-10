package com.reveal.editor.image

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 追加的滤镜实现（桌面端「滤镜」菜单里常用的那些）。
 * 全部输入 Bitmap 输出 Bitmap，8 位精度，走 applyFilterToActive 落到当前图层。
 */
object FilterFx {

    private fun copyPx(src: Bitmap): IntArray {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        return px
    }

    private fun make(w: Int, h: Int, px: IntArray): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun lum(c: Int): Float = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

    private fun cl(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    private fun at(px: IntArray, w: Int, h: Int, x: Int, y: Int): Int =
        px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** 查找边缘：亮度 Sobel，边缘越强越黑 */
    fun findEdges(src: Bitmap, threshold: Int = 0): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val g = FloatArray(w * h) { lum(px[it]) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = (at2(g, w, h, x - 1, y - 1) + 2 * at2(g, w, h, x - 1, y) + at2(g, w, h, x - 1, y + 1)) -
                (at2(g, w, h, x + 1, y - 1) + 2 * at2(g, w, h, x + 1, y) + at2(g, w, h, x + 1, y + 1))
            val gy = (at2(g, w, h, x - 1, y - 1) + 2 * at2(g, w, h, x, y - 1) + at2(g, w, h, x + 1, y - 1)) -
                (at2(g, w, h, x - 1, y + 1) + 2 * at2(g, w, h, x, y + 1) + at2(g, w, h, x + 1, y + 1))
            val m = min(255f, hypot(gx, gy))
            // 阈值：低于它当白底、高于它当黑线、中间线性（阈值 0 = 桌面端原样）
            val t = threshold.coerceIn(0, 100) / 100f * 255f
            val span = if (t <= 0f) 255f else (255f - t).coerceAtLeast(1f)
            val norm = ((m - t) / span).coerceIn(0f, 1f)
            val v = cl(255f - norm * 255f)
            out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return make(w, h, out)
    }

    private fun at2(a: FloatArray, w: Int, h: Int, x: Int, y: Int): Float =
        a[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** 浮雕：左上减右下，灰底 128 */
    /**
     * 浮雕效果（桌面端参数：角度 −360~360、高度 1~10、数量 1~500%）。
     * 原理是**方向导数**：沿光照方向对亮度做差分，得到「斜面朝光/背光」的灰阶，
     * 再叠到中灰 128 上；高度＝差分采样距离（浮雕的厚薄），数量＝对比强度。
     */
    fun embossAt(src: Bitmap, angleDeg: Int = 135, height: Int = 3, amount: Int = 100): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val g = FloatArray(w * h) { lum(px[it]) }
        val dh = height.coerceIn(1, 10)
        val k = amount.coerceIn(1, 500) / 100f * (0.9f / dh) * 3f
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = (Math.cos(a) * dh).toInt()
        val dy = (Math.sin(a) * dh).toInt()
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val ahead = at2(g, w, h, x + dx, y + dy)
                val behind = at2(g, w, h, x - dx, y - dy)
                val d = (ahead - behind) * k
                val v = cl(128f + d)
                out[y * w + x] = (px[y * w + x] and -0x1000000) or (v shl 16) or (v shl 8) or v
            }
        }
        return make(w, h, out)
    }

    fun emboss(src: Bitmap, amount: Int): Bitmap = embossAt(src, 135, 3, amount)

    /** 动感模糊 */
    /**
     * 动感模糊。
     * 优化一：采样点数封顶 33 个——长距离时按步长跳采（模糊本身是低通，跳采肉眼看不出差别），
     * 但计算量从 2d+1 变成常数，距离拉满也不会卡。
     * 优化二：按行区间并行（`Parallel.chunks`）。
     */
    fun motionBlur(src: Bitmap, distance: Int, angle: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val d = distance.coerceIn(1, 120)
        val rad = angle * Math.PI.toFloat() / 180f
        val dx = cos(rad); val dy = sin(rad)
        val out = IntArray(w * h)
        val taps = 33
        val step = if (2 * d + 1 <= taps) 1f else (2f * d + 1f) / taps
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var a = 0; var n = 0
                var i = -d.toFloat()
                while (i <= d.toFloat()) {
                    val c = at(px, w, h, (x + dx * i).roundToInt(), (y + dy * i).roundToInt())
                    a += (c ushr 24); r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    n++
                    i += step
                }
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return make(w, h, out)
    }

    /** 径向模糊（缩放式） */
    /** 径向（缩放）模糊：9 级采样 + 行区间并行；中心点到边缘权重线性（桌面端同款观感） */
    /**
     * 径向模糊（桌面端参数：数量 1~100、模糊方法 旋转/缩放、品质 草图/好/最好）。
     * - 缩放（zoom）：沿半径方向采样，产生「冲向中心」的放射模糊；
     * - 旋转（spin）：沿圆周方向采样，产生「转起来」的环状模糊。
     * 品质只影响采样数（草图 8 / 好 16 / 最好 32），采样越多越平滑也越慢。
     */
    fun radialBlurAt(
        src: Bitmap,
        amount: Int = 10,
        spin: Boolean = false,
        quality: Int = 1,
        centerXpct: Int = 50,
        centerYpct: Int = 50,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w * (centerXpct.coerceIn(0, 100) / 100f)
        val cy = h * (centerYpct.coerceIn(0, 100) / 100f)
        val maxR = hypot(max(cx, w - cx), max(cy, h - cy)).coerceAtLeast(1f)
        val k = amount.coerceIn(1, 100) / 100f
        val samples = when (quality.coerceIn(0, 2)) { 0 -> 8; 1 -> 16; else -> 32 }
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var a = 0; var n = 0
                val dx = x - cx; val dy = y - cy
                val dist = hypot(dx, dy) / maxR
                for (s in 0..samples) {
                    val f = s / samples.toFloat()
                    var sx: Int; var sy: Int
                    if (spin) {
                        // 旋转：角度偏移与半径、数量成比例
                        val ang = k * f * 2.2f
                        val ca = cos(ang); val sa = sin(ang)
                        sx = (cx + dx * ca - dy * sa).roundToInt()
                        sy = (cy + dx * sa + dy * ca).roundToInt()
                    } else {
                        // 缩放：沿半径方向偏移（越靠外位移越大，与 桌面端一致）
                        val t = 1f + k * f * (0.35f + dist)
                        sx = (cx + dx * t).roundToInt()
                        sy = (cy + dy * t).roundToInt()
                    }
                    val c = at(px, w, h, sx, sy)
                    a += (c ushr 24); r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return make(w, h, out)
    }

    fun radialBlur(src: Bitmap, amount: Int): Bitmap = radialBlurAt(src, amount, false, 1)

    /** 方框模糊（可分离，快） */
    fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val r = radius.coerceIn(1, 48)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (x in -r..r) { val c = at(px, w, h, x, y); a += c ushr 24; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; n++ }
            for (x in 0 until w) {
                tmp[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
                val add = at(px, w, h, x + r + 1, y); val sub = at(px, w, h, x - r, y)
                a += (add ushr 24) - (sub ushr 24); rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF); bb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (y in -r..r) { val c = at(tmp, w, h, x, y); a += c ushr 24; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; n++ }
            for (y in 0 until h) {
                out[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
                val add = at(tmp, w, h, x, y + r + 1); val sub = at(tmp, w, h, x, y - r)
                a += (add ushr 24) - (sub ushr 24); rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF); bb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return make(w, h, out)
    }

    /** 表面模糊：只平均与中心亮度接近的邻居，保边 */
    fun surfaceBlur(src: Bitmap, radius: Int, threshold: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val r = radius.coerceIn(1, 8)
        val th = threshold.coerceIn(2, 80).toFloat()
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
            val c0 = px[y * w + x]
            val l0 = lum(c0)
            var a = 0; var r1 = 0; var g1 = 0; var b1 = 0; var n = 0
            val rr = r * r
            for (dy in -r..r) for (dx in -r..r) {
                // 圆盘邻域：方窗会在平坦区留下网格状色块（水彩小方格的根因）
                if (dx * dx + dy * dy > rr) continue
                val c = at(px, w, h, x + dx, y + dy)
                if (abs(lum(c) - l0) > th) continue
                a += c ushr 24; r1 += (c shr 16) and 0xFF; g1 += (c shr 8) and 0xFF; b1 += c and 0xFF; n++
            }
            if (n == 0) { out[y * w + x] = c0; continue }
            out[y * w + x] = ((a / n) shl 24) or ((r1 / n) shl 16) or ((g1 / n) shl 8) or (b1 / n)
        }
        }
        return make(w, h, out)
    }

    /** USM 锐化（半径用方框模糊近似） */
    /**
     * 智能锐化：高斯模糊做基准，只放大「明显是边缘」的差异（暗部/亮部各留噪声底），
     * 并保护高光不溢出——偏好「清晰」而不是「出白边」。
     */
    fun smartSharpen(src: Bitmap, amount: Int, radius: Int, reduceNoise: Int): Bitmap {
        val w = src.width
        val h = src.height
        val blur = gaussBlur(src, radius.coerceIn(1, 12))
        val px = copyPx(src)
        val bp = copyPx(blur)
        blur.recycle()
        val k = amount.coerceIn(0, 300) / 100f * 1.15f
        val floor = 2f + reduceNoise.coerceIn(0, 100) / 100f * 22f
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val b = bp[i]
            val d = lum(c) - lum(b)
            val ad = abs(d)
            if (ad < floor) { out[i] = c; continue }
            // 边缘越强放大越多；平坦区被 noise floor 挡住，不再叠加噪点
            val edge = ((ad - floor) / 42f).coerceIn(0f, 1f)
            val gain = k * (0.35f + 0.65f * edge)
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            // 高光保护：接近纯白时把增益收回来，避免死白
            val hl = (lum(c) - 224f) / 31f
            val g2 = if (hl > 0f) gain * (1f - hl.coerceIn(0f, 1f) * 0.55f) else gain
            val r = cl(cr + (cr - ((b shr 16) and 0xFF)) * g2)
            val g = cl(cg + (cg - ((b shr 8) and 0xFF)) * g2)
            val bb = cl(cb + (cb - (b and 0xFF)) * g2)
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or bb
        }
        return make(w, h, out)
    }

    fun unsharpMask(src: Bitmap, amount: Int, radius: Int, threshold: Int): Bitmap {
        val w = src.width; val h = src.height
        val blur = boxBlur(src, radius)
        val px = copyPx(src)
        val bp = copyPx(blur)
        blur.recycle()
        val k = amount.coerceIn(0, 300) / 100f
        val th = threshold.toFloat()
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val b = bp[i]
            val d = lum(c) - lum(b)
            if (abs(d) < th) { out[i] = c; continue }
            val r = cl(((c shr 16) and 0xFF) + (((c shr 16) and 0xFF) - ((b shr 16) and 0xFF)) * k)
            val g = cl(((c shr 8) and 0xFF) + (((c shr 8) and 0xFF) - ((b shr 8) and 0xFF)) * k)
            val bb = cl((c and 0xFF) + ((c and 0xFF) - (b and 0xFF)) * k)
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or bb
        }
        return make(w, h, out)
    }

    /** 添加杂色 */
    /**
     * 添加杂色。并行时每行用 `seed*31+y` 独立播种：结果仍然可复现（同 seed 同图必得同一结果），
     * 又不用在多个线程间共享一个 Random（那是数据竞争）。
     */
    /**
     * 添加杂色。桌面端有「平均分布 / 高斯分布」两种（外加单色开关），这里补齐：
     * dist=0 高斯分布（钟形，多数像素小偏移、少数大偏移）；dist=1 平均分布（0~数量之间等概率）。
     * 并行时每行用 `seed*31+y` 独立播种：同 seed 同图必得同一结果，且没有共享 RNG 的竞争。
     */
    /**
     * 添加杂色（桌面端：数量 + 分布 平均/高斯 + 单色）。
     * 噪声必须**逐像素哈希**，不能按行播种 `Random(seed+y)`——
     * Java 的 `Random` 相邻种子的前几个输出高度相关，按行播种会排出**竖直条纹**（就是之前的 bug）。
     */
    fun addNoise(src: Bitmap, amount: Int, mono: Boolean, seed: Int, dist: Int = 0): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val k = amount.coerceIn(1, 120).toFloat()
        val avg = dist == 1
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                fun one(salt: Int): Float {
                    if (avg) {
                        // 平均分布：均匀
                        return (hashNoise(x, y, seed + salt) * 2f - 1f) * k
                    }
                    // 高斯分布：Box-Muller（两个独立均匀 → 正态）
                    val u1 = hashNoise(x, y, seed + salt).coerceAtLeast(1e-6f)
                    val u2 = hashNoise(x, y, seed + salt + 7331)
                    val g = kotlin.math.sqrt(-2f * kotlin.math.ln(u1)) * kotlin.math.cos(2f * Math.PI.toFloat() * u2)
                    return g * 0.35f * k
                }
                val dr = one(0)
                val dg = if (mono) dr else one(10007)
                val db = if (mono) dr else one(20011)
                out[i] = (c and -0x1000000) or (cl(((c shr 16) and 0xFF) + dr) shl 16) or
                    (cl(((c shr 8) and 0xFF) + dg) shl 8) or cl((c and 0xFF) + db)
            }
        }
        return make(w, h, out)
    }

    /** 中间值（半径小一点，纯暴力，控制上限） */
    /**
     * 中间值（中值滤波）。桌面端的半径上限是 100；这里放到 30。
     * 半径大时逐点扫全窗口代价是 O(r²)（r=30 时每像素 3721 个采样），所以半径 > 6 时按步长抽样，
     * 采样点封顶约 13×13 —— 中值本身就是秩统计，抽样对结果的影响肉眼不可见。
     */
    fun median(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val r = radius.coerceIn(1, 100)
        val step = if (r <= 6) 1 else (r / 6)
        val span = if (step == 1) r else (r / step)
        val n = (2 * span + 1) * (2 * span + 1)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            val rs = IntArray(n); val gs = IntArray(n); val bs = IntArray(n); val asv = IntArray(n)
            for (y in y0 until y1) for (x in 0 until w) {
                var i = 0
                for (ky in -span..span) for (kx in -span..span) {
                    val c = at(px, w, h, x + kx * step, y + ky * step)
                    asv[i] = c ushr 24; rs[i] = (c shr 16) and 0xFF
                    gs[i] = (c shr 8) and 0xFF; bs[i] = c and 0xFF; i++
                }
                val used = i.coerceAtLeast(1)
                rs.sort(); gs.sort(); bs.sort(); asv.sort()
                val mid = (used - 1) / 2
                out[y * w + x] = (asv[mid] shl 24) or (rs[mid] shl 16) or (gs[mid] shl 8) or bs[mid]
            }
        }
        return make(w, h, out)
    }

    /** 蒙尘与划痕：只改与中间值差得多的像素 */
    /**
     * 蒙尘与划痕。桌面端语义：拿「半径」内的中值当参考，只有和它差别**大于阈值**的像素才被替换，
     * 阈值 0~255（逐通道比较，取三通道最大差）。半径同样支持到 30。
     */
    fun dustScratches(src: Bitmap, radius: Int, threshold: Int): Bitmap {
        val w = src.width; val h = src.height
        val med = median(src, radius)
        val px = copyPx(src)
        val mp = copyPx(med)
        med.recycle()
        val th = threshold.coerceIn(0, 255)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = px[i]; val m = mp[i]
                val dr = kotlin.math.abs(((c shr 16) and 0xFF) - ((m shr 16) and 0xFF))
                val dg = kotlin.math.abs(((c shr 8) and 0xFF) - ((m shr 8) and 0xFF))
                val db = kotlin.math.abs((c and 0xFF) - (m and 0xFF))
                out[i] = if (maxOf(dr, dg, db) > th) m else c
            }
        }
        return make(w, h, out)
    }

    /** 波纹 */
    fun ripple(src: Bitmap, amount: Int, size: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val amp = amount.coerceIn(1, 60).toFloat()
        val period = max(4f, size.coerceIn(4, 200).toFloat())
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val ox = (sin(y / period * 2f * Math.PI).toFloat() * amp).roundToInt()
            val oy = (sin(x / period * 2f * Math.PI).toFloat() * amp).roundToInt()
            out[y * w + x] = at(px, w, h, x + ox, y + oy)
        }
        return make(w, h, out)
    }

    /** 球面化 */
    fun spherize(src: Bitmap, amount: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w / 2f; val cy = h / 2f
        val rad = min(cx, cy)
        val k = amount.coerceIn(-100, 100) / 100f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val d = hypot(dx, dy) / rad
            if (d >= 1f) { out[y * w + x] = px[y * w + x]; continue }
            val t = d * (1f - k * (1f - d) * 0.9f)
            val sx = cx + dx * (t / max(d, 1e-4f))
            val sy = cy + dy * (t / max(d, 1e-4f))
            out[y * w + x] = at(px, w, h, sx.roundToInt(), sy.roundToInt())
        }
        return make(w, h, out)
    }

    /** 旋转扭曲 */
    fun twirl(src: Bitmap, angle: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w / 2f; val cy = h / 2f
        val rad = min(cx, cy)
        val maxA = angle.coerceIn(-360, 360) * Math.PI.toFloat() / 180f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val d = hypot(dx, dy)
            if (d >= rad) { out[y * w + x] = px[y * w + x]; continue }
            val a = maxA * (1f - d / rad)
            val ca = cos(a); val sa = sin(a)
            val sx = cx + dx * ca - dy * sa
            val sy = cy + dx * sa + dy * ca
            out[y * w + x] = at(px, w, h, sx.roundToInt(), sy.roundToInt())
        }
        return make(w, h, out)
    }

    /** 挤压/膨胀 */
    fun pinch(src: Bitmap, amount: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w / 2f; val cy = h / 2f
        val rad = min(cx, cy)
        val k = amount.coerceIn(-100, 100) / 100f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val d = hypot(dx, dy)
            if (d >= rad) { out[y * w + x] = px[y * w + x]; continue }
            val nd = (d / rad).let { t -> t * (1f + k * (1f - t) * 0.85f) } * rad
            val f = nd / max(d, 1e-4f)
            out[y * w + x] = at(px, w, h, (cx + dx * f).roundToInt(), (cy + dy * f).roundToInt())
        }
        return make(w, h, out)
    }

    /** 极坐标：矩形→极坐标 / 极坐标→矩形 */
    fun polar(src: Bitmap, toPolar: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w / 2f; val cy = h / 2f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx: Int; val sy: Int
            if (toPolar) {
                // 把横轴当角度、纵轴当半径
                val a = x / w.toFloat() * 2f * Math.PI.toFloat()
                val r = (1f - y / h.toFloat()) * min(cx, cy)
                sx = (cx + cos(a) * r).roundToInt()
                sy = (cy + sin(a) * r).roundToInt()
            } else {
                val dx = x - cx; val dy = y - cy
                val a = Math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
                val r = hypot(dx, dy)
                sx = ((a / (2f * Math.PI.toFloat()) + 0.5f) * w).roundToInt()
                sy = ((1f - r / min(cx, cy)) * h).roundToInt()
            }
            out[y * w + x] = at(px, w, h, sx, sy)
        }
        return make(w, h, out)
    }

    /** 晶格化：按格子取样块色 */
    fun crystallize(src: Bitmap, cell: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val c = cell.coerceIn(2, 80)
        val rnd = java.util.Random(7)
        val out = IntArray(w * h)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val col = at(px, w, h, x + rnd.nextInt(c) - c / 2, y + rnd.nextInt(c) - c / 2)
                val x1 = min(w, x + c); val y1 = min(h, y + c)
                for (yy in y until y1) for (xx in x until x1) out[yy * w + xx] = col
                x += c
            }
            y += c
        }
        return make(w, h, out)
    }

    /** 点状化：格子铺白底，中间画点 */
    fun pointillize(src: Bitmap, cell: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val c = cell.coerceIn(3, 60)
        val out = IntArray(w * h) { -0x1 }
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val col = at(px, w, h, x + c / 2, y + c / 2)
                val cx = x + c / 2; val cy = y + c / 2
                val r = c / 2
                for (yy in max(0, cy - r) until min(h, cy + r + 1)) for (xx in max(0, cx - r) until min(w, cx + r + 1)) {
                    if (hypot((xx - cx).toFloat(), (yy - cy).toFloat()) <= r) out[yy * w + xx] = col
                }
                x += c
            }
            y += c
        }
        return make(w, h, out)
    }

    /** 云彩：分形噪声，叠加到原图 */
    fun clouds(src: Bitmap, amount: Int, seed: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val rnd = java.util.Random(seed.toLong())
        val octaves = 4
        val noise = FloatArray(w * h)
        var amp = 1f
        var total = 0f
        for (o in 0 until octaves) {
            val cells = 4 shl o
            val gw = max(2, cells)
            val gh = max(2, cells * h / max(1, w))
            val grid = FloatArray(gw * gh) { rnd.nextFloat() }
            for (y in 0 until h) for (x in 0 until w) {
                val gx = x / w.toFloat() * (gw - 1)
                val gy = y / h.toFloat() * (gh - 1)
                val x0 = gx.toInt(); val y0 = gy.toInt()
                val fx = gx - x0; val fy = gy - y0
                val a00 = grid[y0 * gw + x0]
                val a10 = grid[y0 * gw + min(gw - 1, x0 + 1)]
                val a01 = grid[min(gh - 1, y0 + 1) * gw + x0]
                val a11 = grid[min(gh - 1, y0 + 1) * gw + min(gw - 1, x0 + 1)]
                val v = (a00 * (1 - fx) + a10 * fx) * (1 - fy) + (a01 * (1 - fx) + a11 * fx) * fy
                noise[y * w + x] += v * amp
            }
            total += amp
            amp *= 0.5f
        }
        val k = amount.coerceIn(0, 100) / 100f
        val out = IntArray(w * h)
        for (i in px.indices) {
            val n = (noise[i] / total * 255f)
            val c = px[i]
            val r = cl(((c shr 16) and 0xFF) * (1 - k) + n * k)
            val g = cl(((c shr 8) and 0xFF) * (1 - k) + n * k)
            val b = cl((c and 0xFF) * (1 - k) + n * k)
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        return make(w, h, out)
    }

    /** 镜头光晕 */
    fun lensFlare(src: Bitmap, x: Float, y: Float, brightness: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = (x * w).coerceIn(0f, w - 1f)
        val cy = (y * h).coerceIn(0f, h - 1f)
        val maxR = max(w, h) * 0.75f
        val k = brightness.coerceIn(0, 300) / 100f
        val out = IntArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            val d = hypot(xx - cx, yy - cy)
            var f = 0f
            if (d < maxR) f += (1f - d / maxR).let { it * it * it } * 1.2f
            // 光晕环
            val ring = abs(d - maxR * 0.35f) / (maxR * 0.06f)
            if (ring < 1f) f += (1f - ring) * 0.35f
            val c = px[yy * w + xx]
            val r = cl(((c shr 16) and 0xFF) + 255 * f * k)
            val g = cl(((c shr 8) and 0xFF) + 240 * f * k)
            val b = cl((c and 0xFF) + 200 * f * k)
            out[yy * w + xx] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        return make(w, h, out)
    }

    /** 高反差保留：128 + (原图 - 模糊) */
    fun highPass(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        // 低频用高斯而不是方框：方框模糊会在边缘外留一圈方块状光晕，桌面端的高反差保留是高斯
        val blur = gaussBlur(src, radius)
        val px = copyPx(src)
        val bp = copyPx(blur)
        blur.recycle()
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]; val b = bp[i]
            val r = cl(128f + (((c shr 16) and 0xFF) - ((b shr 16) and 0xFF)))
            val g = cl(128f + (((c shr 8) and 0xFF) - ((b shr 8) and 0xFF)))
            val bb = cl(128f + ((c and 0xFF) - (b and 0xFF)))
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or bb
        }
        return make(w, h, out)
    }

    /** 油画：半径内取最多的亮度等级，用那一档的平均色 */
    fun oil(src: Bitmap, radius: Int, levels: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val r = radius.coerceIn(1, 6)
        val lv = levels.coerceIn(4, 32)
        val out = IntArray(w * h)
        val count = IntArray(lv)
        val sr = IntArray(lv); val sg = IntArray(lv); val sb = IntArray(lv)
        for (y in 0 until h) for (x in 0 until w) {
            java.util.Arrays.fill(count, 0); java.util.Arrays.fill(sr, 0); java.util.Arrays.fill(sg, 0); java.util.Arrays.fill(sb, 0)
            for (dy in -r..r) for (dx in -r..r) {
                val c = at(px, w, h, x + dx, y + dy)
                val b = (lum(c) / 255f * (lv - 1)).roundToInt().coerceIn(0, lv - 1)
                count[b]++
                sr[b] += (c shr 16) and 0xFF; sg[b] += (c shr 8) and 0xFF; sb[b] += c and 0xFF
            }
            var best = 0
            for (i in 1 until lv) if (count[i] > count[best]) best = i
            val n = max(1, count[best])
            out[y * w + x] = (px[y * w + x] and -0x1000000) or ((sr[best] / n) shl 16) or ((sg[best] / n) shl 8) or (sb[best] / n)
        }
        return make(w, h, out)
    }

    /** 光照效果（简化：中心聚光 + 环境） */
    /**
     * 光照效果（参考文献：桌面端「光照效果」的 bump-map + Phong 模型，
     * 见《Computer Graphics: Principles and Practice》的 Blinn-Phong 光照，以及 桌面端参数表：
     * 光照类型（点光/全光/聚光）、强度、曝光度、光泽、金属质感、环境、纹理通道）。
     *
     * 做法：把「纹理通道」（亮度/R/G/B）当高度场，Sobel 求梯度当法线，
     * 再用 Blinn-Phong 算漫反射 + 高光；金属质感决定高光是否带底色，环境给底噪亮度。
     */
    fun lightingAt(
        src: Bitmap,
        type: Int = 0,          // 0 点光 / 1 全光 / 2 聚光
        intensity: Int = 50,    // 0~100
        exposure: Int = 0,      // -100~100
        gloss: Int = 30,        // -100~100（-100 极柔，100 极亮硬高光）
        metallic: Int = 0,      // -100~100（金属质感，越大高光越染底色）
        ambient: Int = 15,      // 0~100
        angleDeg: Int = 135,    // 光源方位角
        heightDeg: Int = 45,    // 光源仰角（越高越正对画面）
        channel: Int = 0,       // 0 亮度 / 1 红 / 2 绿 / 3 蓝（纹理通道）
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val amb = ambient.coerceIn(0, 100) / 100f
        val inten = intensity.coerceIn(0, 100) / 100f
        val expo = Math.pow(2.0, exposure.coerceIn(-100, 100) / 60.0).toFloat()
        // 光泽：越小越柔（指数低），越大高光越集中
        val shin = (2f + (gloss.coerceIn(-100, 100) + 100) * 0.6f)
        val metal = metallic.coerceIn(-100, 100) / 100f
        val ch = channel.coerceIn(0, 3)

        // 高度场：按纹理通道取亮度
        val hgt = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            hgt[i] = when (ch) {
                1 -> ((c shr 16) and 0xFF) / 255f
                2 -> ((c shr 8) and 0xFF) / 255f
                3 -> (c and 0xFF) / 255f
                else -> lum(c) / 255f
            }
        }

        // 光源方向（点光/聚光按方位角+仰角，全光正对画面）
        val az = Math.toRadians(angleDeg.toDouble())
        val el = Math.toRadians(heightDeg.coerceIn(0, 90).toDouble())
        var lx: Float; var ly: Float; var lz: Float
        if (type == 1) {
            lx = 0f; ly = 0f; lz = 1f
        } else {
            lx = (Math.cos(az) * Math.cos(el)).toFloat()
            ly = (Math.sin(az) * Math.cos(el)).toFloat()
            lz = Math.sin(el).toFloat()
        }
        // 半程向量（视线固定 (0,0,1)）
        val hx = lx; val hy = ly; val hz = lz + 1f
        val hlen = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-4f)
        val hnx = hx / hlen; val hny = hy / hlen; val hnz = hz / hlen

        val cx = w / 2f; val cy = h / 2f
        val maxR = sqrt((cx * cx + cy * cy).toDouble()).toFloat()
        val bump = 2.2f
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                // 法线：中心差分
                val hl = hgt[y * w + max(0, x - 1)]
                val hr = hgt[y * w + min(w - 1, x + 1)]
                val hu = hgt[max(0, y - 1) * w + x]
                val hd = hgt[min(h - 1, y + 1) * w + x]
                var nx = (hl - hr) * bump
                var ny = (hu - hd) * bump
                var nz = 1f
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
                nx /= nl; ny /= nl; nz /= nl

                var diffuse = nx * lx + ny * ly + nz * lz
                if (diffuse < 0f) diffuse = 0f
                // 聚光：按到中心的距离衰减（桌面端的点光/聚光本来就有衰减）
                if (type == 2) {
                    val d = hypot(x - cx, y - cy) / maxR
                    diffuse *= (1f - d * d * 0.85f).coerceIn(0f, 1f)
                }
                if (type == 0) {
                    val d = hypot(x - cx, y - cy) / maxR
                    diffuse *= (1f - d * d * 0.35f).coerceIn(0f, 1f)
                }

                // 高光（Blinn-Phong）
                var spec = nx * hnx + ny * hny + nz * hnz
                if (spec < 0f) spec = 0f
                var sp = 1f
                var e2 = shin
                while (e2 > 0f) { sp *= spec; e2 -= 1f }
                sp *= inten

                val r0 = ((c shr 16) and 0xFF) / 255f
                val g0 = ((c shr 8) and 0xFF) / 255f
                val b0 = (c and 0xFF) / 255f
                val lit = (amb + diffuse * inten * expo)
                // 金属质感：高光带底色；非金属高光偏白
                val sr = (r0 * metal + (1f - metal)).coerceIn(0f, 1f)
                val sg = (g0 * metal + (1f - metal)).coerceIn(0f, 1f)
                val sb = (b0 * metal + (1f - metal)).coerceIn(0f, 1f)
                val rr = r0 * lit + sr * sp
                val gg = g0 * lit + sg * sp
                val bb = b0 * lit + sb * sp
                out[i] = (c and -0x1000000) or (cl(rr * 255f) shl 16) or (cl(gg * 255f) shl 8) or cl(bb * 255f)
            }
        }
        return make(w, h, out)
    }

    fun lighting(src: Bitmap, intensity: Int, ambient: Int): Bitmap =
        lightingAt(src, intensity = intensity, ambient = ambient)
    /** 最小值（暗部扩张） */
    fun minimum(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val r = radius.coerceIn(1, 6)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
            var a = 255; var rr = 255; var gg = 255; var bb = 255
            for (dy in -r..r) for (dx in -r..r) {
                // 圆形邻域：桌面端的最小值/最大值是圆盘形状，方形会把方角吃尖
                if (dx * dx + dy * dy > r * r) continue
                val c = at(px, w, h, x + dx, y + dy)
                a = min(a, c ushr 24); rr = min(rr, (c shr 16) and 0xFF)
                gg = min(gg, (c shr 8) and 0xFF); bb = min(bb, c and 0xFF)
            }
            out[y * w + x] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        }
        return make(w, h, out)
    }

    /** 最大值（亮部扩张） */
    fun maximum(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val r = radius.coerceIn(1, 6)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (dy in -r..r) for (dx in -r..r) {
                // 圆形邻域：桌面端的最小值/最大值是圆盘形状，方形会把方角吃尖
                if (dx * dx + dy * dy > r * r) continue
                val c = at(px, w, h, x + dx, y + dy)
                a = max(a, c ushr 24); rr = max(rr, (c shr 16) and 0xFF)
                gg = max(gg, (c shr 8) and 0xFF); bb = max(bb, c and 0xFF)
            }
            out[y * w + x] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        }
        return make(w, h, out)
    }

    /** 位移 */
    /**
     * 位移（桌面端参数：水平、垂直、**未定义区域**＝环绕/重复边缘像素/设置为透明）。
     */
    fun offsetAt(src: Bitmap, dx: Int, dy: Int, edgeMode: Int = 0): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val mode = edgeMode.coerceIn(0, 2)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val sx0 = x - dx
                val sy0 = y - dy
                val c = when (mode) {
                    0 -> { // 环绕
                        var sx = sx0 % w; if (sx < 0) sx += w
                        var sy = sy0 % h; if (sy < 0) sy += h
                        px[sy * w + sx]
                    }
                    1 -> px[sy0.coerceIn(0, h - 1) * w + sx0.coerceIn(0, w - 1)] // 重复边缘
                    else -> if (sx0 < 0 || sy0 < 0 || sx0 >= w || sy0 >= h) 0 else px[sy0 * w + sx0]
                }
                out[y * w + x] = c
            }
        }
        return make(w, h, out)
    }

    fun offset(src: Bitmap, dx: Int, dy: Int): Bitmap = offsetAt(src, dx, dy, 0)

    /** 碎片：四个方向错开的副本平均 */
    fun fragments(src: Bitmap, strength: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val d = strength.coerceIn(1, 30)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0
            val offs = arrayOf(intArrayOf(d, 0), intArrayOf(-d, 0), intArrayOf(0, d), intArrayOf(0, -d))
            for (o in offs) {
                val c = at(px, w, h, x + o[0], y + o[1])
                a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            out[y * w + x] = ((a / 4) shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
        }
        return make(w, h, out)
    }

    /** 彩色半调：按通道做网点 */
    /**
     * 彩色半调（参考文献：传统 CMYK 半调网屏——每个色版按各自网角旋转方格网屏，
     * 网点面积与油墨量成正比，四种油墨叠加还原颜色；桌面端参数就是「最大半径 + 通道1~4 网角」，
     * 默认 108/162/90/45 度，四个角度错开是为了避免撞网产生龟纹）。
     */
    fun colorHalftone(
        src: Bitmap,
        maxRadius: Int = 8,
        a1: Int = 108,
        a2: Int = 162,
        a3: Int = 90,
        a4: Int = 45,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val rm = maxRadius.coerceIn(2, 60).toFloat()
        val angles = floatArrayOf(
            Math.toRadians(a1.toDouble()).toFloat(),
            Math.toRadians(a2.toDouble()).toFloat(),
            Math.toRadians(a3.toDouble()).toFloat(),
            Math.toRadians(a4.toDouble()).toFloat(),
        )
        val cosA = FloatArray(4) { kotlin.math.cos(angles[it]) }
        val sinA = FloatArray(4) { kotlin.math.sin(angles[it]) }
        val cell = (rm * 2f).coerceAtLeast(4f)
        val half = cell / 2f

        // 某个色版在 (x,y) 处是否落在网点内
        fun ink(x: Int, y: Int, ch: Int, cov: Float): Boolean {
            if (cov <= 0.01f) return false
            val u = x * cosA[ch] - y * sinA[ch]
            val v = x * sinA[ch] + y * cosA[ch]
            val cx = u - kotlin.math.floor(u / cell) * cell - half
            val cy = v - kotlin.math.floor(v / cell) * cell - half
            val d = kotlin.math.sqrt(cx * cx + cy * cy)
            // 面积覆盖 → 半径：点面积 πr² ∝ cov，所以 r = rm*sqrt(cov)
            return d <= rm * kotlin.math.sqrt(cov)
        }

        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val c = px[y * w + x]
                val r0 = (c shr 16) and 0xFF
                val g0 = (c shr 8) and 0xFF
                val b0 = c and 0xFF
                // RGB → CMYK 油墨量
                val cc = (255 - r0) / 255f
                val mm = (255 - g0) / 255f
                val yy = (255 - b0) / 255f
                val kk = min(cc, min(mm, yy))
                val cInk = if (ink(x, y, 0, cc)) 1f else 0f
                val mInk = if (ink(x, y, 1, mm)) 1f else 0f
                val yInk = if (ink(x, y, 2, yy)) 1f else 0f
                val kInk = if (ink(x, y, 3, kk)) 1f else 0f
                // 四色叠加（取各分量的最大油墨），还原成 RGB
                val rOut = 255f * (1f - max(cInk, kInk))
                val gOut = 255f * (1f - max(mInk, kInk))
                val bOut = 255f * (1f - max(yInk, kInk))
                out[y * w + x] = (c and -0x1000000) or
                    (cl(rOut) shl 16) or (cl(gOut) shl 8) or cl(bOut)
            }
        }
        return make(w, h, out)
    }

    /**
     * 阈值：亮度高于阈值色阶 → 纯白，否则纯黑（只有「阈值色阶 1~255」一个参数）。
     */
    fun thresholdAt(src: Bitmap, level: Int = 128): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val t = level.coerceIn(1, 255).toFloat()
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val v = if (lum(c) >= t) 255 else 0
                out[i] = (c and -0x1000000) or (v shl 16) or (v shl 8) or v
            }
        }
        return make(w, h, out)
    }

    /**
     * 黑白转换（专业黑白那套：六条色相带权重）。
     * 每个像素先取 HSV，按色相落点做余弦加权混合各带权重，再乘到亮度上——同亮度不同颜色会出不同灰阶，
     * 这才是「黑白」而不是简单「去色」。
     */
    fun blackWhiteAt(
        src: Bitmap,
        wr: Int = 40, wy: Int = 60, wg: Int = 40,
        wc: Int = 60, wb: Int = 20, wm: Int = 80,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val weights = floatArrayOf(
            wr / 100f * 2f, wy / 100f * 2f, wg / 100f * 2f,
            wc / 100f * 2f, wb / 100f * 2f, wm / 100f * 2f,
        )
        val centers = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            val hsv = FloatArray(3)
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                android.graphics.Color.colorToHSV(c or 0xFF000000.toInt(), hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                // 六个色相带做余弦窗加权，得到该像素的「亮度放大系数」
                var mix = 0f
                var sum = 0f
                for (b in 0 until 6) {
                    var d = kotlin.math.abs(hue - centers[b])
                    if (d > 180f) d = 360f - d
                    val win = if (d >= 60f) 0f else 0.5f + 0.5f * kotlin.math.cos((Math.PI * d / 60.0).toFloat())
                    mix += weights[b] * win
                    sum += win
                }
                val k = if (sum > 0.001f) mix / sum else 1f
                val l = lum(c)
                // 彩色像素按带权重调整；灰度像素本来就该原样
                val v = (l * (1f + (k - 1f) * sat)).coerceIn(0f, 255f)
                out[i] = (c and -0x1000000) or (cl(v) shl 16) or (cl(v) shl 8) or cl(v)
            }
        }
        return make(w, h, out)
    }

    fun colorHalftone(src: Bitmap, cell: Int): Bitmap =
        colorHalftone(src, maxRadius = cell, a1 = 108, a2 = 162, a3 = 90, a4 = 45)

    /** 高斯模糊（三次方框近似） */
    fun gaussBlur(src: Bitmap, radius: Int): Bitmap {
        var cur = src
        repeat(3) { cur = boxBlur(cur, max(1, radius / 2)) }
        val out = copyPx(cur)
        if (cur !== src) cur.recycle()
        return make(src.width, src.height, out)
    }

    /** 减少杂色：表面模糊的轻量版 */
    /**
     * 减少杂色（独立算法，不再复用表面模糊）。
     * 参考文献口径：桌面端的「减少杂色」是分通道处理的——亮度通道只做轻度边缘保留平滑（强度/保留细节），
     * 色度通道（Cb/Cr）可以重得多（减少彩色杂色），这是它和「表面模糊」最本质的区别：
     * 彩色噪点在亮度上几乎看不见，只有把色度单独重压才能干净。
     *
     * 做法：BT.601 转 YCbCr → Y 走双边（亮度差加权的选择性平滑）→ Cb/Cr 走方框模糊 → 合回 RGB。
     */
    fun reduceNoise(src: Bitmap, amount: Int, detail: Int = 40, chroma: Int = 60, sharpen: Int = 0): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val yArr = FloatArray(w * h)
        val cbArr = FloatArray(w * h)
        val crArr = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            yArr[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cbArr[i] = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
            crArr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
        }
        val st = amount.coerceIn(0, 100) / 100f
        val keep = detail.coerceIn(0, 100) / 100f
        val chr = chroma.coerceIn(0, 100) / 100f
        // 亮度：半径固定 1~2（双边），阈值随「强度↑ / 保留细节↓」变宽
        val rY = if (st > 0.6f) 2 else 1
        val thY = (6f + 42f * st * (1f - keep * 0.8f)).coerceAtLeast(4f)
        // 色度：方框模糊半径随「减少彩色杂色」增大
        val rC = (1 + (chr * 4).toInt()).coerceIn(1, 5)
        val yOut = FloatArray(w * h)
        val cbOut = FloatArray(w * h)
        val crOut = FloatArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i0 = y * w + x
                val l0 = yArr[i0]
                var sum = 0f; var cnt = 0f
                for (dy in -rY..rY) for (dx in -rY..rY) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val v = yArr[sy * w + sx]
                    if (kotlin.math.abs(v - l0) <= thY) { sum += v; cnt += 1f }
                }
                yOut[i0] = if (cnt > 0f) sum / cnt else l0
            }
        }
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var sc = 0f; var sr = 0f; var n = 0f
                for (dy in -rC..rC) for (dx in -rC..rC) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val j = sy * w + sx
                    sc += cbArr[j]; sr += crArr[j]; n += 1f
                }
                cbOut[y * w + x] = sc / n
                crOut[y * w + x] = sr / n
            }
        }
        val shp = sharpen.coerceIn(0, 100) / 100f
        val out = IntArray(w * h)
        for (i in px.indices) {
            // 锐化细节：把「原亮度 − 平滑亮度」的高频按比例加回（桌面端的第 4 个参数）
            val baseY = yOut[i]
            val Y = if (shp > 0f) baseY + (yArr[i] - baseY) * shp * 1.2f else baseY
            val Cb = cbOut[i] - 128f; val Cr = crOut[i] - 128f
            val r = Y + 1.402f * Cr
            val g = Y - 0.344136f * Cb - 0.714136f * Cr
            val b = Y + 1.772f * Cb
            out[i] = ((px[i] ushr 24) shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(b)
        }
        return make(w, h, out)
    }

    /** 木刻：先灰度再按阈值分几档 */
    /**
     * 木刻（桌面端参数：色阶数 2~8、边缘简化度 0~10、边缘逼真度 1~3）。
     * 做法：亮度分档 → Sobel 找边 → 简化度控制边缘图的平滑程度（越大越圆滑、越少碎边），
     * 逼真度控制阈值与是否保留细节（3 最贴合原图、1 最概括）。
     */
    /**
     * 木刻（桌面端参数：色阶数 2~8、边缘简化度 0~10、边缘逼真度 1~3）。
     *
     * 关键点是**边缘要沿色块轮廓走**，不能对原始亮度做梯度阈值——那样细节区（毛发/草地）会碎成一片。
     * 做法（区域分割思路）：
     * 1. 先做边缘保持平滑（表面模糊，圆盘邻域）把纹理抹掉；
     * 2. 亮度量化成 `色阶数` 个标签，得到「区域图」；
     * 3. 边缘 = 相邻像素**标签不同**的位置（区域边界），而不是亮度梯度；
     * 4. 简化度 → 平滑半径（越大越概括、碎边越少）；逼真度 → 8 邻域还是 4 邻域（3 更贴合原图细节）。
     */
    fun cutoutAt(src: Bitmap, levels: Int = 4, simplify: Int = 2, fidelity: Int = 3): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val n = levels.coerceIn(2, 8)
        val simp = simplify.coerceIn(0, 10)
        val fid = fidelity.coerceIn(1, 3)
        // 1) 边缘保持平滑：简化度直接决定平滑半径
        val rad = (1 + simp / 5).coerceIn(1, 3)
        val sm = surfaceBlur(src, rad, 28)
        val sp = copyPx(sm)
        sm.recycle()
        // 2) 量化标签
        val label = IntArray(w * h) { i -> (lum(sp[i]) / 255f * (n - 1)).roundToInt().coerceIn(0, n - 1) }
        // 3) 区域边界当边缘
        val edge = BooleanArray(w * h)
        val eight = fid >= 3
        val four = true
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val l = label[i]
                var hit = false
                if (x > 0 && label[i - 1] != l) hit = true
                if (!hit && x < w - 1 && label[i + 1] != l) hit = true
                if (!hit && y > 0 && label[i - w] != l) hit = true
                if (!hit && y < h - 1 && label[i + w] != l) hit = true
                if (!hit && eight) {
                    if (x > 0 && y > 0 && label[i - w - 1] != l) hit = true
                    else if (x < w - 1 && y > 0 && label[i - w + 1] != l) hit = true
                    else if (x > 0 && y < h - 1 && label[i + w - 1] != l) hit = true
                    else if (x < w - 1 && y < h - 1 && label[i + w + 1] != l) hit = true
                }
                edge[i] = hit && four
            }
        }
        // 4) 逼真度 1：把孤立细边去掉（只保留成规模的边界，更概括）
        if (fid == 1) {
            val keep = BooleanArray(w * h)
            Parallel.chunks(h) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until w) {
                    val i = y * w + x
                    if (!edge[i]) continue
                    var cnt = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                        if (edge[yy * w + xx]) cnt++
                    }
                    if (cnt >= 4) keep[i] = true
                }
            }
            for (i in edge.indices) edge[i] = keep[i]
        }
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = sp[i]
                val l = lum(c) / 255f
                val q = (l * (n - 1)).roundToInt().coerceIn(0, n - 1) / (n - 1f)
                val v = if (edge[i]) 0 else cl(q * 255f)
                out[i] = (px[i] and -0x1000000) or (v shl 16) or (v shl 8) or v
            }
        }
        return make(w, h, out)
    }

    fun cutout(src: Bitmap, levels: Int, edge: Boolean): Bitmap =
        cutoutAt(src, levels, simplify = 0, fidelity = if (edge) 3 else 1)

    /** 浮点数组的边界取样（边缘图用） */
    private fun at3(a: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xx = x.coerceIn(0, w - 1); val yy = y.coerceIn(0, h - 1)
        return a[yy * w + xx]
    }

    /** 水彩：中值去细节 + 轻微提饱和、边缘压深 */
    fun watercolor(src: Bitmap, radius: Int, saturation: Int): Bitmap {
        val base = median(src, min(3, max(1, radius)))
        val w = base.width; val h = base.height
        val px = copyPx(base)
        base.recycle()
        val k = saturation.coerceIn(0, 100) / 100f
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val l = lum(c)
            val nonGray = 220f / max(1f, l)
            val mul = 1f + k * (min(1.6f, nonGray) - 1f)
            val r = cl(((c shr 16) and 0xFF) * mul)
            val g = cl(((c shr 8) and 0xFF) * mul)
            val b = cl((c and 0xFF) * mul)
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        return make(w, h, out)
    }

    /** 胶片颗粒 */
    /**
     * 胶片颗粒（桌面端参数：颗粒 0~20、高光区域 0~10、强度 0~10）。
     * 要点有两处和「添加杂色」不同：①颗粒是**柔和的成团噪声**（低频+高频两层），不是逐像素白噪声；
     * ②高光区域越大 → 亮部保留的颗粒越多（真实胶片亮部银盐颗粒更明显）。
     */
    fun filmGrain(src: Bitmap, grain: Int, highlight: Int, strength: Int, seed: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val g = grain.coerceIn(0, 20) / 20f
        val hi = highlight.coerceIn(0, 10) / 10f
        val st = strength.coerceIn(0, 10) / 10f
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val c = px[y * w + x]
                val l = lum(c) / 255f
                // 高光区域：0 时亮部几乎没有颗粒，10 时亮部颗粒和暗部一样多
                val weight = (1f - l) + l * hi
                // 两层噪声：低频成团（每 3×3 共享）+ 高频细粒
                val n1 = hashNoise(x / 3, y / 3, seed)
                val n2 = hashNoise(x, y, seed + 977)
                val n = (n1 * 0.6f + n2 * 0.4f) - 0.5f
                val amp = (2f + g * 26f) * st * weight
                val d = n * amp * 2f
                out[y * w + x] = (c and -0x1000000) or
                    (cl(((c shr 16) and 0xFF) + d) shl 16) or
                    (cl(((c shr 8) and 0xFF) + d) shl 8) or cl((c and 0xFF) + d)
            }
        }
        return make(w, h, out)
    }

    /** 确定性哈希噪声（0~1），同一坐标恒同值 */
    private fun hashNoise(x: Int, y: Int, seed: Int): Float {
        var n = x * 374761393 + y * 668265263 + seed * 1442695041
        n = (n xor (n shr 13)) * 1274126177
        n = n xor (n shr 16)
        return (n and 0xFFFF) / 65535f
    }

    fun filmGrain(src: Bitmap, amount: Int, seed: Int): Bitmap =
        filmGrain(src, grain = amount / 5, highlight = 2, strength = 7, seed = seed)

    /** 镜头校正：桶形/枕形畸变 + 垂直/水平透视 + 暗角，一次几何校正 */
    /**
     * 镜头校正（桌面端参数表：移去扭曲、色差 红/绿/蓝、晕影 数量+中点、变换 垂直/水平/角度）。
     * 色差的物理来源是镜头对不同波长折射率不同（Axial/Lateral CA），表现是边缘紫边/绿边——
     * 这里用「每通道自己的径向缩放系数」模拟：`f = 1 + ca*k*r²`，三通道略有差异就会在边缘错色。
     */
    fun lensCorrectAt(
        src: Bitmap,
        distort: Float = 0f,
        vertical: Float = 0f,
        horizontal: Float = 0f,
        vignette: Float = 0f,
        vignetteMid: Float = 50f,
        angleDeg: Float = 0f,
        caR: Float = 0f,
        caG: Float = 0f,
        caB: Float = 0f,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val k = distort / 100f * 0.55f
        val kv = vertical / 100f * 0.6f
        val kh = horizontal / 100f * 0.6f
        val vigK = vignette / 100f
        val vigMid = (vignetteMid.coerceIn(0f, 100f) / 100f) * 1.6f + 0.2f
        val ang = Math.toRadians(angleDeg.toDouble()).toFloat()
        val caA = cos(-ang); val caS = sin(-ang)
        val caKs = floatArrayOf(caR / 100f * 0.12f, caG / 100f * 0.12f, caB / 100f * 0.12f)
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val rad = kotlin.math.sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                // 旋转（镜头校正的「角度」）
                var nx = (x - cx)
                var ny = (y - cy)
                if (angleDeg != 0f) {
                    val rx = nx * caA - ny * caS
                    val ry = nx * caS + ny * caA
                    nx = rx; ny = ry
                }
                nx /= rad; ny /= rad
                // 透视：按另一轴位置缩放
                var sx0 = nx * (1f + kv * ny)
                var sy0 = ny * (1f + kh * nx)
                val r2 = sx0 * sx0 + sy0 * sy0
                val f = 1f + k * r2
                sx0 *= f; sy0 *= f
                // 三个通道各自的色差缩放
                var aSum = 0; val ch = IntArray(3)
                for (ci in 0..2) {
                    val cf = 1f + caKs[ci] * r2
                    val px0 = (cx + sx0 * cf * rad).toInt()
                    val py0 = (cy + sy0 * cf * rad).toInt()
                    if (px0 < 0 || py0 < 0 || px0 >= w || py0 >= h) {
                        ch[ci] = 0
                    } else {
                        val c = px[py0 * w + px0]
                        ch[ci] = when (ci) { 0 -> (c shr 16) and 0xFF; 1 -> (c shr 8) and 0xFF; else -> c and 0xFF }
                        val al = (c ushr 24) and 0xFF
                        if (al > aSum) aSum = al
                    }
                }
                var r = ch[0]; var g = ch[1]; var b = ch[2]
                if (vigK != 0f) {
                    val d = kotlin.math.sqrt(nx * nx + ny * ny)
                    val m = (1f - vigK * ((d / vigMid).coerceIn(0f, 1f) * (d / vigMid).coerceIn(0f, 1f)) * 0.9f)
                        .coerceIn(if (vigK > 0f) 0f else 1f, if (vigK > 0f) 1f else 1.6f)
                    r = (r * m).toInt().coerceIn(0, 255)
                    g = (g * m).toInt().coerceIn(0, 255)
                    b = (b * m).toInt().coerceIn(0, 255)
                }
                out[y * w + x] = (aSum shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return make(w, h, out)
    }

    fun lensCorrection(src: Bitmap, distort: Float, vertical: Float, horizontal: Float, vignette: Float): Bitmap =
        lensCorrectAt(src, distort, vertical, horizontal, vignette)

    /**
     * 自适应广角：把超广角/鱼眼的「直线变弯」拉回来。
     * 用真实的镜头映射做反向采样：目标半径经过 tan() 映射回源半径（等距投影 → 直线投影），
     * 所以弯的直线会被掰直，而不是像桶形矫正那样只是整体鼓/缩。
     */
    fun adaptiveWide(src: Bitmap, strength: Int, rotateDeg: Int, scale: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val a = strength.coerceIn(0, 100) / 100f * 1.05f
        val tanA = kotlin.math.tan(a.toDouble()).toFloat()
        val rot = Math.toRadians(rotateDeg.coerceIn(-180, 180).toDouble())
        val cosR = kotlin.math.cos(rot).toFloat()
        val sinR = kotlin.math.sin(rot).toFloat()
        val zoom = (scale.coerceIn(40, 300) / 100f)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val rad = kotlin.math.sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ny = (y - cy) / rad
            for (x in 0 until w) {
                val nx = (x - cx) / rad
                // 先按旋转角把坐标转回去
                val rx = nx * cosR + ny * sinR
                val ry = -nx * sinR + ny * cosR
                val rd = kotlin.math.sqrt(rx * rx + ry * ry)
                val rs = if (a < 1e-4f || rd < 1e-6f) rd else kotlin.math.tan((rd * a).toDouble()).toFloat() / tanA
                val f = (rs / rd) / zoom
                val sx = (cx + rx * f * rad)
                val sy = (cy + ry * f * rad)
                out[y * w + x] = bili(px, w, h, sx, sy, 0)
            }
        }
        return make(w, h, out)
    }

    /** 双线性采样（越界返回 fallback） */
    private fun bili(px: IntArray, w: Int, h: Int, x: Float, y: Float, fallback: Int): Int {
        if (x < 0f || y < 0f || x >= w - 1 || y >= h - 1) return fallback
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i00 = px[y0 * w + x0]
        val i10 = px[y0 * w + x0 + 1]
        val i01 = px[(y0 + 1) * w + x0]
        val i11 = px[(y0 + 1) * w + x0 + 1]
        val out = IntArray(4)
        for (sh in intArrayOf(24, 16, 8, 0)) {
            val c00 = (i00 ushr sh) and 0xFF
            val c10 = (i10 ushr sh) and 0xFF
            val c01 = (i01 ushr sh) and 0xFF
            val c11 = (i11 ushr sh) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bot = c01 + (c11 - c01) * fx
            out[if (sh == 24) 0 else if (sh == 16) 1 else if (sh == 8) 2 else 3] =
                (top + (bot - top) * fy).toInt().coerceIn(0, 255)
        }
        return (out[0] shl 24) or (out[1] shl 16) or (out[2] shl 8) or out[3]
    }

    // ================= 与 桌面端对齐的补充实现 =================

    /** 马赛克：每格取平均色；shape=1 时只画内切圆（圆点马赛克） */
    fun mosaicAt(src: Bitmap, cell: Int, shape: Int = 0): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val n = cell.coerceIn(2, 200)
        val out = IntArray(w * h)
        var by = 0
        while (by < h) {
            var bx = 0
            while (bx < w) {
                val x1 = min(w, bx + n); val y1 = min(h, by + n)
                var r = 0L; var g = 0L; var b = 0L; var a = 0L; var c = 0L
                for (y in by until y1) for (x in bx until x1) {
                    val q = px[y * w + x]
                    a += (q ushr 24) and 0xFF; r += (q shr 16) and 0xFF
                    g += (q shr 8) and 0xFF; b += q and 0xFF; c++
                }
                if (c == 0L) c = 1
                val avg = ((a / c).toInt() shl 24) or ((r / c).toInt() shl 16) or
                    ((g / c).toInt() shl 8) or (b / c).toInt()
                val rcx = (bx + x1) / 2f; val rcy = (by + y1) / 2f
                val rad = min(x1 - bx, y1 - by) / 2f
                for (y in by until y1) for (x in bx until x1) {
                    val ok = if (shape == 1) hypot(x - rcx, y - rcy) <= rad else true
                    out[y * w + x] = if (ok) avg else px[y * w + x]
                }
                bx += n
            }
            by += n
        }
        return make(w, h, out)
    }

    /** 挤压：桶形/枕形径向变形，中心与半径可调（参照镜头畸变的径向项一阶化简） */
    fun pinchAt(src: Bitmap, amount: Int, cxPct: Int = 50, cyPct: Int = 50, radiusPct: Int = 100): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w * (cxPct.coerceIn(0, 100) / 100f)
        val cy = h * (cyPct.coerceIn(0, 100) / 100f)
        val rad = (min(w, h) * (radiusPct.coerceIn(5, 200) / 100f)).coerceAtLeast(2f)
        val k = amount.coerceIn(-100, 100) / 100f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val d = hypot(dx, dy)
            if (d >= rad) { out[y * w + x] = px[y * w + x]; continue }
            val t = d / rad
            val nd = (t * (1f + k * (1f - t) * 0.85f)) * rad
            val f = nd / max(d, 1e-4f)
            out[y * w + x] = at(px, w, h, (cx + dx * f).roundToInt(), (cy + dy * f).roundToInt())
        }
        return make(w, h, out)
    }

    /** 球面化：sin/tan 单调映射（正值顶成球、负值凹进去） */
    fun spherizeAt(
        src: Bitmap, amount: Int, mode: Int = 0, cxPct: Int = 50, cyPct: Int = 50, radiusPct: Int = 100,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w * (cxPct.coerceIn(0, 100) / 100f)
        val cy = h * (cyPct.coerceIn(0, 100) / 100f)
        val rad = (min(w, h) * (radiusPct.coerceIn(5, 200) / 100f)).coerceAtLeast(2f)
        val k = amount.coerceIn(-100, 100) / 100f
        val halfPi = (Math.PI / 2.0).toFloat()
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
            var dx = x - cx; var dy = y - cy
            if (mode == 1) dy *= 0.0001f
            if (mode == 2) dx *= 0.0001f
            val d = hypot(dx, dy)
            if (d >= rad) { out[y * w + x] = px[y * w + x]; continue }
            val t = d / rad
            val nd = if (k >= 0f) {
                (t * (1f - k) + sin(t * halfPi) * k) * rad
            } else {
                val kk = -k
                (t * (1f - kk) + (kotlin.math.tan(t * halfPi) / kotlin.math.tan(halfPi)) * kk) * rad
            }
            val f = nd / max(d, 1e-4f)
            out[y * w + x] = at(px, w, h, (cx + dx * f).roundToInt(), (cy + dy * f).roundToInt())
        }
        }
        return make(w, h, out)
    }

    /** 旋转扭曲：θ' = θ + A·(1−t)² */
    fun twirlAt(src: Bitmap, angle: Int, cxPct: Int = 50, cyPct: Int = 50, radiusPct: Int = 100): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w * (cxPct.coerceIn(0, 100) / 100f)
        val cy = h * (cyPct.coerceIn(0, 100) / 100f)
        val rad = (min(w, h) * (radiusPct.coerceIn(5, 200) / 100f)).coerceAtLeast(2f)
        val maxA = angle.coerceIn(-720, 720) * Math.PI.toFloat() / 180f
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val d = hypot(dx, dy)
            if (d >= rad) { out[y * w + x] = px[y * w + x]; continue }
            val t = d / rad
            val a = maxA * (1f - t) * (1f - t)
            val ca = cos(a); val sa = sin(a)
            out[y * w + x] = at(px, w, h, (cx + dx * ca - dy * sa).roundToInt(), (cy + dx * sa + dy * ca).roundToInt())
        }
        return make(w, h, out)
    }

    /** 极坐标：矩形↔极坐标，中心与半径可调 */
    fun polarAt(
        src: Bitmap, toPolar: Boolean, cxPct: Int = 50, cyPct: Int = 50, radiusPct: Int = 100,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val cx = w * (cxPct.coerceIn(0, 100) / 100f)
        val cy = h * (cyPct.coerceIn(0, 100) / 100f)
        val rad = (min(w, h) * (radiusPct.coerceIn(5, 200) / 100f)).coerceAtLeast(2f)
        val twoPi = (Math.PI * 2).toFloat()
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx: Int; val sy: Int
            if (toPolar) {
                // 画面坐标 → 极坐标展开图：横轴角度、纵轴半径
                val dx = x - cx; val dy = y - cy
                val a = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
                val r = hypot(dx, dy)
                sx = ((a / twoPi + 0.5f) * w).roundToInt()
                sy = ((1f - r / rad) * h).roundToInt()
            } else {
                val a = (x / w.toFloat() - 0.5f) * twoPi
                val r = (1f - y / h.toFloat()) * rad
                sx = (cx + cos(a) * r).roundToInt()
                sy = (cy + sin(a) * r).roundToInt()
            }
            out[y * w + x] = at(px, w, h, sx, sy)
        }
        return make(w, h, out)
    }

    /** 波纹：正弦位移；direction 0=只纵向 1=只横向 2=两者 */
    fun rippleAt(src: Bitmap, amount: Int, size: Int, direction: Int = 2, phasePct: Int = 0): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val amp = amount.coerceIn(1, 60).toFloat()
        val period = max(4f, size.coerceIn(4, 200).toFloat())
        val ph = phasePct / 100f * 2f * Math.PI.toFloat()
        val twoPi = (Math.PI * 2).toFloat()
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val ox = if (direction == 0) 0f else sin(y / period * twoPi + ph) * amp
            val oy = if (direction == 1) 0f else sin(x / period * twoPi + ph) * amp
            out[y * w + x] = at(px, w, h, (x + ox).roundToInt(), (y + oy).roundToInt())
        }
        return make(w, h, out)
    }

    /** 镜头光晕：中央辉光 + 光环 + 沿轴鬼影 */
    /**
     * 镜头光晕（参考常见光晕插件的参数分组：光斑大小/亮度/光环/鬼影/色温）。
     * 组成：①中央辉光（三次衰减）②光环（半径 = maxR×ring）③沿光轴排布的鬼影（数量与强度可调）。
     * ghost 0~100 控制鬼影强度，ring 0~100 控制光环大小，tint 0 中性/1 暖/2 冷。
     */
    fun lensFlareAt(
        src: Bitmap, cxPct: Int = 50, cyPct: Int = 50, brightness: Int = 60, kind: Int = 0,
        radiusPct: Int = 100, ghost: Int = 60, ring: Int = 60, tint: Int = 1,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val out = px.copyOf()
        val cx = w * (cxPct.coerceIn(0, 100) / 100f)
        val cy = h * (cyPct.coerceIn(0, 100) / 100f)
        val maxR = (min(w, h) * (radiusPct.coerceIn(10, 200) / 100f) * 0.9f).coerceAtLeast(8f)
        val br = brightness.coerceIn(0, 100) / 100f
        val gh = ghost.coerceIn(0, 100) / 100f
        val rg = ring.coerceIn(0, 100) / 100f
        // 色温：暖偏红黄、冷偏蓝
        val tr = when (tint) { 1 -> 1.0f; 2 -> 0.86f; else -> 1f }
        val tg = when (tint) { 1 -> 0.94f; 2 -> 0.94f; else -> 1f }
        val tb = when (tint) { 1 -> 0.78f; 2 -> 1.0f; else -> 1f }
        val ghosts = when (kind) { 0 -> 6; 1 -> 3; 2 -> 2; else -> 8 }
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val d = hypot(dx, dy)
                var add = 0f
                // 中央辉光
                if (d < maxR) add += ((1f - d / maxR) * (1f - d / maxR) * (1f - d / maxR) * 1.2f) * br
                // 光环（大小由 ring 控制）
                val ringR = maxR * (0.15f + rg * 0.9f)
                val ringD = abs(d - ringR) / (maxR * 0.05f + 1f)
                if (ringD < 1f) add += (1f - ringD) * 0.55f * br * (0.4f + rg * 0.6f)
                // 鬼影（强度由 ghost 控制）
                if (gh > 0f) {
                    for (i in 1..ghosts) {
                        val f = -0.4f * i
                        val gx = cx + dx * f * 0.9f + dx * 0.1f
                        val gy = cy + dy * f * 0.9f + dy * 0.1f
                        val gr = maxR * (0.05f + 0.06f * ((i * 7) % 5))
                        val gd = hypot(x - gx, y - gy)
                        if (gd < gr) {
                            val a = (1f - gd / gr)
                            add += a * a * 0.42f * br * gh * (if (i % 2 == 0) 1f else 0.8f)
                        }
                    }
                }
                if (add > 0.001f) {
                    val c = px[y * w + x]
                    val r = cl(((c shr 16) and 0xFF) + add * 255f * tr)
                    val g = cl(((c shr 8) and 0xFF) + add * 255f * tg)
                    val b = cl((c and 0xFF) + add * 255f * tb)
                    out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return make(w, h, out)
    }

    /**
     * 混合颜色带（Blend If）：按某个通道的取值把「黑场以下 / 白场以上」变透明，
     * 拆分滑块（loBlack→hiBlack、loWhite→hiWhite）做出软过渡。
     * 抠纯色背景时直接把这层背景抠掉，不用选区。
     * channel: 0 灰度 / 1 红 / 2 绿 / 3 蓝
     */
    fun bandCut(
        src: Bitmap, channel: Int = 0,
        loBlack: Int = 0, hiBlack: Int = 0,
        loWhite: Int = 255, hiWhite: Int = 255,
        feather: Int = 0,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        val out = IntArray(w * h)
        val b0 = loBlack.coerceIn(0, 255); val b1 = hiBlack.coerceIn(b0, 255)
        val w0 = loWhite.coerceIn(0, 255); val w1 = hiWhite.coerceIn(w0, 255)
        val fth = feather.coerceIn(0, 100)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val c = px[y * w + x]
                val a = (c ushr 24) and 0xFF
                if (a == 0) { out[y * w + x] = c; continue }
                val v = when (channel) {
                    1 -> (c shr 16) and 0xFF
                    2 -> (c shr 8) and 0xFF
                    3 -> c and 0xFF
                    else -> (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
                }
                var k = 1f
                if (v <= b0) k = 0f
                else if (v < b1 && b1 > b0) k = (v - b0).toFloat() / (b1 - b0)
                if (v >= w1) k = 0f
                else if (v > w0 && w1 > w0) k = minOf(k, (w1 - v).toFloat() / (w1 - w0))
                if (fth > 0) {
                    // 羽化：把边界附近再拉一段渐变（按通道值距离边界的相对量）
                    val edge = fth / 100f
                    k = (k * (1f - edge) + edge * k * k).coerceIn(0f, 1f)
                }
                out[y * w + x] = ((k * a).toInt().coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
            }
        }
        return make(w, h, out)
    }

    /** 水彩：中值简化 + 梯度阴影 + 确定性纸纹 */
    /**
     * 水彩（参考文献：Kyprianidis 2011《Image and Video Based Painterly Rendering》里水彩类
     * 的四段式流程——①边缘保持的平涂 ②湿边（wet-edge）压暗 ③内部水渍提亮 ④纸纹；桌面端的
     * 「水彩」滤镜参数也只有画笔细节/阴影强度/纹理三项，正好对应这里的 detail/shadow/texture）。
     */
    fun watercolorAt(src: Bitmap, detail: Int = 8, shadow: Int = 2, texture: Int = 1): Bitmap {
        val w = src.width; val h = src.height
        val d = detail.coerceIn(1, 14)
        // ① 平涂：圆盘邻域的边缘保持平滑。细节越大 → 半径越小（笔触越细）
        val r = (1f + (14 - d) * 0.5f).toInt().coerceIn(1, 7)
        val base = surfaceBlur(src, r, 26)
        val bp = copyPx(base)
        base.recycle()

        // ② 湿边：在平滑后图上算 Sobel 梯度，作为「色块边界」的权重
        val edge = FloatArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val l00 = lum(at(bp, w, h, x - 1, y - 1))
                val l10 = lum(at(bp, w, h, x, y - 1))
                val l20 = lum(at(bp, w, h, x + 1, y - 1))
                val l01 = lum(at(bp, w, h, x - 1, y))
                val l21 = lum(at(bp, w, h, x + 1, y))
                val l02 = lum(at(bp, w, h, x - 1, y + 1))
                val l12 = lum(at(bp, w, h, x, y + 1))
                val l22 = lum(at(bp, w, h, x + 1, y + 1))
                val gx = (l20 + 2 * l21 + l22) - (l00 + 2 * l01 + l02)
                val gy = (l02 + 2 * l12 + l22) - (l00 + 2 * l10 + l20)
                val g = kotlin.math.sqrt(gx * gx + gy * gy) / 1020f
                edge[y * w + x] = g.coerceIn(0f, 1f)
            }
        }

        val shK = shadow.coerceIn(0, 10) * 0.085f      // 阴影强度：边缘最多压暗 85%
        val wet = 1f + (d / 14f) * 0.9f                // 细节越大，水渍提亮越明显
        val texK = texture.coerceIn(0, 3) * 2.6f       // 纹理：纸纤维对亮度的扰动幅度
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val i = y * w + x
                val c = bp[i]
                val e = edge[i]
                // ② 湿边压暗：色块交界处积色
                val k = (1f - e * shK).coerceIn(0.35f, 1f)
                var r0 = ((c shr 16) and 0xFF) * k
                var g0 = ((c shr 8) and 0xFF) * k
                var b0 = (c and 0xFF) * k
                // ③ 水渍：色块内部轻微提亮（纸吸水的亮斑），并让颜色更「薄」
                val lift = (1f - e) * wet
                r0 += lift; g0 += lift; b0 += lift
                // ④ 纸纹：两层值噪声（粗纤维 + 细颗粒），确定性、可复现
                if (texK > 0f) {
                    val n1 = paperNoise(x shr 2, y shr 2, 17)
                    val n2 = paperNoise(x shr 1, y shr 1, 91)
                    val grain = ((n1 * 0.7f + n2 * 0.3f) - 0.5f) * texK
                    r0 += grain; g0 += grain * 0.95f; b0 += grain * 0.9f
                }
                out[i] = (0xFF shl 24) or (cl(r0) shl 16) or (cl(g0) shl 8) or cl(b0)
            }
        }
        return make(w, h, out)
    }

    /** 纸纹用的确定性值噪声：双线性插值的哈希格子，范围 0~1 */
    private fun paperNoise(x: Int, y: Int, salt: Int): Float {
        fun h(ix: Int, iy: Int): Float {
            var n = ix * 374761393 + iy * 668265263 + salt * 1442695041
            n = (n xor (n shr 13)) * 1274126177
            n = n xor (n shr 16)
            return (n and 0xFFFF) / 65535f
        }
        val fx = 0.35f; val fy = 0.35f
        val x0 = kotlin.math.floor(x * fx).toInt(); val y0 = kotlin.math.floor(y * fy).toInt()
        val tx = x * fx - x0; val ty = y * fy - y0
        val a = h(x0, y0); val b = h(x0 + 1, y0)
        val c2 = h(x0, y0 + 1); val d2 = h(x0 + 1, y0 + 1)
        val top = a + (b - a) * tx
        val bot = c2 + (d2 - c2) * tx
        return top + (bot - top) * ty
    }

    /** 油画：Kuwahara 分箱取众数 + 清洁度 + 光照 */
    /**
     * 油画（桌面端参数：画笔样式、描边细节、清洁度、缩放、硬毛刷细节、光照 角度+闪耀）。
     * 底子是 Kuwahara 式「亮度分箱取众数」，再补两项：
     * - **画笔样式**：决定笔触形状——0 简单（圆）、1 轻描（横向拉长）、2 湿润（沿光方向强拉长）、
     *   3 干笔（稀疏采样留缝）、4 浓重（半径放大）；
     * - **光照闪耀**：按亮度梯度补高光（Blinn-Phong 的镜面项近似），让颜料有「油亮」的反光。
     */
    fun oilAt(
        src: Bitmap, brush: Int = 3, detail: Int = 20, cleanliness: Int = 5,
        lightAngle: Int = 135, lightStrength: Int = 0,
        style: Int = 0, shine: Int = 0,
    ): Bitmap {
        val w = src.width; val h = src.height
        val px = copyPx(src)
        var rad = brush.coerceIn(1, 10)
        val bins = detail.coerceIn(4, 48)
        val clean = cleanliness.coerceIn(0, 10) / 10f
        val ang = lightAngle * Math.PI.toFloat() / 180f
        val lx = cos(ang); val ly = sin(ang)
        val ls = lightStrength.coerceIn(0, 100) / 100f
        val sh = shine.coerceIn(0, 100) / 100f
        val st = style.coerceIn(0, 4)
        if (st == 4) rad = (rad * 1.6f).toInt().coerceIn(1, 14)
        // 笔触拉伸：轻描 ×2，湿润 ×3；方向沿光照角
        val stretch = when (st) { 1 -> 2f; 2 -> 3f; else -> 1f }
        val cosA = cos(ang); val sinA = sin(ang)
        val out = IntArray(w * h)
        Parallel.chunks(h) { y0, y1 ->
            val count = IntArray(bins)
            val sr = IntArray(bins); val sg = IntArray(bins); val sb = IntArray(bins)
            for (y in y0 until y1) for (x in 0 until w) {
                java.util.Arrays.fill(count, 0)
                java.util.Arrays.fill(sr, 0); java.util.Arrays.fill(sg, 0); java.util.Arrays.fill(sb, 0)
                var best = 0
                var used = 0
                for (ky in -rad..rad) for (kx in -rad..rad) {
                    // 各向异性笔触：把采样点沿光方向拉长（stretch>1 时窄而长）
                    var ax = kx.toFloat(); var ay = ky.toFloat()
                    if (stretch > 1f) {
                        val u = ax * cosA + ay * sinA       // 沿光方向
                        val v = -ax * sinA + ay * cosA      // 垂直光方向
                        ax = u * stretch * cosA - (v / stretch) * sinA
                        ay = u * stretch * sinA + (v / stretch) * cosA
                    }
                    // 圆盘笔尖
                    if (ax * ax + ay * ay > (rad * rad).toFloat()) continue
                    // 干笔：确定性留缝（不采部分点），笔触更「枯」
                    if (st == 3) {
                        val hsh = (x * 374761393 + y * 668265263 + kx * 7919 + ky * 104729)
                        if (((hsh xor (hsh shr 13)) and 3) == 0) continue
                    }
                    val sx = (x + ax.toInt()).coerceIn(0, w - 1)
                    val sy = (y + ay.toInt()).coerceIn(0, h - 1)
                    val c = px[sy * w + sx]
                    val bin = (lum(c) / 255f * (bins - 1)).toInt().coerceIn(0, bins - 1)
                    count[bin]++
                    sr[bin] += (c shr 16) and 0xFF
                    sg[bin] += (c shr 8) and 0xFF
                    sb[bin] += c and 0xFF
                    used++
                    if (count[bin] > count[best]) best = bin
                }
                val n = max(1, count[best])
                val mr = sr[best] / n; val mg = sg[best] / n; val mb = sb[best] / n
                val orig = px[y * w + x]
                var r = mr * (1f - clean) + ((orig shr 16) and 0xFF) * clean
                var g = mg * (1f - clean) + ((orig shr 8) and 0xFF) * clean
                var b = mb * (1f - clean) + (orig and 0xFF) * clean
                if (ls > 0f) {
                    // 光照强度：有符号方向光——朝光一侧变亮、背光一侧变暗，幅度足够大才看得出来
                    val gx = (lum(px[y * w + min(w - 1, x + 1)]) - lum(px[y * w + max(0, x - 1)])) / 255f
                    val gy = (lum(px[min(h - 1, y + 1) * w + x]) - lum(px[max(0, y - 1) * w + x])) / 255f
                    val lit = (gx * lx + gy * ly) * 260f * ls
                    r += lit; g += lit; b += lit
                }
                if (sh > 0f) {
                    // 闪耀：亮度梯度朝光 = 镜面高光，平方衰减做「油亮」层
                    val gx = (lum(px[y * w + min(w - 1, x + 1)]) - lum(px[y * w + max(0, x - 1)])) / 255f
                    val gy = (lum(px[min(h - 1, y + 1) * w + x]) - lum(px[max(0, y - 1) * w + x])) / 255f
                    var spec = (gx * lx + gy * ly)
                    if (spec > 0f) {
                        spec *= spec
                        val add = spec * 210f * sh
                        r += add; g += add; b += add
                    }
                }
                out[y * w + x] = (0xFF shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(b)
            }
        }
        return make(w, h, out)
    }


}
