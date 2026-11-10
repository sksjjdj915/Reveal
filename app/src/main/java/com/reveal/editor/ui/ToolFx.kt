package com.reveal.editor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 像素级工具算法（颜色替换、海绵、背景橡皮、图案图章、污点修复、红眼、
 * 历史记录画笔、历史记录艺术画笔、魔术橡皮、混合器画笔）。
 *
 * 输入统一是「一块矩形像素 + 该矩形的笔迹覆盖度掩码」，
 * 输出改好的像素数组；和 桌面端的算法不是一回事，但操作方式与观感对齐。
 */
internal object ToolFx {

    /** 只处理覆盖度 > 阈值的像素 */
    private const val COV_MIN = 3

    private fun cov(c: Int) = (c ushr 24) and 0xFF

    private fun luma(r: Int, g: Int, b: Int) = (0.299f * r + 0.587f * g + 0.114f * b)

    /** 两色差异（0..255），用于容差判断 */
    fun colorDistance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return (abs(dr) + abs(dg) + abs(db)) / 3
    }

    // ---------------- 颜色替换画笔 ----------------
    /**
     * 桌面端的「颜色替换」：把画到的颜色换成前景色，保留原图明暗。
     * tolerance：只换和笔刷下颜色接近的像素；0 = 不限。
     */
    fun replaceColor(
        before: IntArray, covMask: IntArray, sel: IntArray?,
        color: Int, target: Int, tolerance: Int, opacity: Float,
        mode: Int = 0, limit: Int = 1, width: Int = 0, limitMask: BooleanArray? = null,
    ): IntArray {
        val out = before.copyOf()
        // 前景色的色相/饱和度：替换后保留原像素的明暗，所以只借这两项
        val fgHsv = FloatArray(3)
        Color.colorToHSV(color or 0xFF000000.toInt(), fgHsv)
        val hsv = FloatArray(3)
        val tol = tolerance.coerceIn(0, 255)
        for (i in before.indices) {
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val p = before[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            // 限制=连续：只动落点那片连通的颜色；限制=查找边缘：颜色跳变处不碰
            if (limitMask != null && i < limitMask.size && !limitMask[i]) continue
            if (limit == 2 && width > 0) {
                // 查找边缘：用 Sobel 梯度找真正的边界（只比右邻一个像素会把纹理噪点也当边界），
                // 梯度大的地方不换，只换平坦区
                val hh = if (width > 0) before.size / width else 0
                val gx = sobelX(before, width, hh, i)
                val gy = sobelY(before, width, hh, i)
                val mag = (kotlin.math.abs(gx) + kotlin.math.abs(gy)) / 4
                val edgeLimit = (tol * 0.6f + 26f).toInt()
                if (mag > edgeLimit) continue
            }
            // 容差：只换和「笔刷落点那一块的颜色」接近的像素（tol=0 表示整片都换）。
            // 边缘给 25% 的软过渡：纯硬阈值配上笔尖的渐变，红块里会出一个个小孔
            var soft = 1f
            if (tol > 0) {
                val dist = colorDistance(p, target)
                if (dist > tol) {
                    val over = dist - tol
                    val span = max(4, (tol * 0.25f).toInt())
                    if (over >= span) continue
                    soft = 1f - over.toFloat() / span
                }
            }
            // 覆盖度用陡一点的曲线：软边只留 1~2px。以前按 c/255 线性混，笔迹边上会留下一圈
            // 半新半旧的色，看着像给笔迹描了一道轮廓（用户报「有边缘」）
            val covK = (c / 110f).coerceIn(0f, 1f)
            val k = (opacity * covK).coerceIn(0f, 1f) * soft
            if (k <= 0.004f) continue
            Color.colorToHSV(p, hsv)
            val lum = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            // 桌面端的「模式」：颜色 = 前景色的色相+饱和度、保留明暗；色相/饱和度/亮度各自只换一项
            when (mode) {
                1 -> hsv[0] = fgHsv[0]
                2 -> hsv[1] = fgHsv[1]
                3 -> hsv[2] = fgHsv[2]
                else -> {
                    hsv[0] = fgHsv[0]
                    hsv[1] = fgHsv[1]
                    hsv[2] = lum
                }
            }
            val nr = Color.HSVToColor(a, hsv)
            out[i] = Color.argb(
                a,
                (Color.red(p) + (Color.red(nr) - Color.red(p)) * k).toInt().coerceIn(0, 255),
                (Color.green(p) + (Color.green(nr) - Color.green(p)) * k).toInt().coerceIn(0, 255),
                (Color.blue(p) + (Color.blue(nr) - Color.blue(p)) * k).toInt().coerceIn(0, 255),
            )
        }
        return out
    }

    // ---------------- 海绵工具 ----------------
    /** mode: 0 去色 1 加色。flow 0..1 */
    fun sponge(
        before: IntArray, covMask: IntArray, sel: IntArray?, mode: Int, flow: Float,
        airbrush: Boolean = false,
    ): IntArray {
        val out = before.copyOf()
        val hsv = FloatArray(3)
        for (i in before.indices) {
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val p = before[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            Color.colorToHSV(p, hsv)
            // 以前固定乘 0.12/0.22，拖半天几乎看不出变化（用户报「太弱」）；加大到 0.45/0.7
            val k = (flow * c / 255f).coerceIn(0f, 1f) * (if (airbrush) 0.7f else 0.45f)
            hsv[1] = if (mode == 0) (hsv[1] - k).coerceIn(0f, 1f)
            else (hsv[1] + k).coerceIn(0f, 1f)
            out[i] = Color.HSVToColor(a, hsv)
        }
        return out
    }

    // ---------------- 背景橡皮擦 ----------------
    /** 只擦掉与 target 颜色接近的像素；protect 不为 0 时保护该颜色不擦 */
    fun eraseByColor(
        before: IntArray, covMask: IntArray, sel: IntArray?,
        target: Int, tolerance: Int, protect: Int, limitMask: BooleanArray? = null,
    ): IntArray {
        val out = before.copyOf()
        for (i in before.indices) {
            if (limitMask != null && !limitMask[i]) continue
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val p = before[i]
            if (Color.alpha(p) == 0) continue
            if (colorDistance(p, target) > tolerance) continue
            if (protect != 0 && colorDistance(p, protect) <= tolerance) continue
            val a = Color.alpha(p)
            // 命中就按覆盖度一次擦到位；覆盖度够高直接清零，不留半透明灰底（以前 a*(1-c/255) 永远不为 0）
            val frac = c / 255f
            val na = if (frac >= 0.96f) 0 else (a * (1f - frac)).toInt().coerceIn(0, 255)
            out[i] = if (na <= 0) 0 else Color.argb(na, Color.red(p), Color.green(p), Color.blue(p))
        }
        return out
    }

    // ---------------- 图案图章 ----------------
    /** 按图案铺满笔迹；aligned=false 时图案以落笔点为原点 */
    fun patternStamp(
        before: IntArray, covMask: IntArray, sel: IntArray?, width: Int,
        pattern: Bitmap, originX: Int, originY: Int, opacity: Float,
        impressionist: Boolean = false,
    ): IntArray {
        val out = before.copyOf()
        val pw = max(1, pattern.width)
        val ph = max(1, pattern.height)
        val pp = IntArray(pw * ph)
        pattern.getPixels(pp, 0, pw, 0, 0, pw, ph)
        for (i in before.indices) {
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val x = ((i % width) - originX) % pw
            val y = ((i / width) - originY) % ph
            // 印象派效果：取样点随机跳开，图案边缘被搅成笔触感（桌面端图案图章的「印象派效果」）
            val rnd = if (impressionist) java.util.Random((i * 2654435761L) xor (i.toLong() shl 7)) else null
            val jx = if (rnd != null) rnd.nextInt(pw) else 0
            val jy = if (rnd != null) rnd.nextInt(ph) else 0
            val sx = (if (x < 0) x + pw else x).coerceIn(0, pw - 1)
            val sy = (if (y < 0) y + ph else y).coerceIn(0, ph - 1)
            val s = pp[((sy + jy) % ph) * pw + ((sx + jx) % pw)]
            val sa = Color.alpha(s)
            if (sa == 0) continue
            val k = (opacity * c / 255f).coerceIn(0f, 1f)
            val d = before[i]
            val da = Color.alpha(d)
            val rr = Color.red(d) + (Color.red(s) - Color.red(d)) * k
            val gg = Color.green(d) + (Color.green(s) - Color.green(d)) * k
            val bb = Color.blue(d) + (Color.blue(s) - Color.blue(d)) * k
            val aa = (da + (255 - da) * k * (sa / 255f)).toInt().coerceIn(0, 255)
            out[i] = Color.argb(aa, rr.toInt().coerceIn(0, 255), gg.toInt().coerceIn(0, 255), bb.toInt().coerceIn(0, 255))
        }
        return out
    }

    // ---------------- 污点修复 ----------------
    /**
     * 用周围颜色盖掉瑕疵：每个被覆盖像素取四周环上的平均色（越靠外权重越低）。
     * 简化版「内容识别」，去斑、去痘痘够用。
     */
    /**
     * 污点修复。
     *
     * type 0 内容识别：把笔迹外圈当边界，在洞内解拉普拉斯方程（调和插值，Bertalmio 等 2000 的经典做法），
     *   颜色和明暗会顺着四周的走向填进去，不再像「取一圈平均」那样铺成一坨平的。
     * type 1 创建纹理：直接拿外圈最近的样本，保留颗粒感。
     * type 2 近似匹配：先在环上挑一块纹理最像的样本整块搬进洞，再解泊松方程
     *   （Perez 等 2003《Poisson Image Editing》的无缝克隆）把搬来的块对齐到目标边界，接缝就没了。
     */
    fun spotHeal(
        before: IntArray, covMask: IntArray, sel: IntArray?,
        width: Int, height: Int, radius: Int,
        type: Int = 0,
        ringScale: Float = 1f,
        blend: Float = 1f,
        itersCap: Int = 96,
    ): IntArray {
        val out = before.copyOf()
        val r = radius.coerceIn(2, 40)
        val rs = ringScale.coerceIn(0.5f, 3f)

        // 1) 洞 = 笔迹覆盖到、且没被选区排除的像素
        val hole = BooleanArray(before.size)
        var hx0 = width
        var hx1 = -1
        var hy0 = height
        var hy1 = -1
        var sumX = 0L
        var sumY = 0L
        var count = 0
        for (i in before.indices) {
            if (cov(covMask[i]) <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            if (Color.alpha(before[i]) == 0) continue
            hole[i] = true
            val x = i % width
            val y = i / width
            if (x < hx0) hx0 = x
            if (x > hx1) hx1 = x
            if (y < hy0) hy0 = y
            if (y > hy1) hy1 = y
            sumX += x
            sumY += y
            count++
        }
        if (count == 0) return out
        // 洞外扩 1px：让泊松边界落在笔迹外面的原像素上，笔迹边缘不会留一圈半成品
        for (y in max(0, hy0 - 1)..min(height - 1, hy1 + 1)) {
            for (x in max(0, hx0 - 1)..min(width - 1, hx1 + 1)) {
                val i = y * width + x
                if (hole[i]) continue
                if (x > 0 && hole[i - 1]) hole[i] = true else if (x + 1 < width && hole[i + 1]) hole[i] = true
                else if (y > 0 && hole[i - width]) hole[i] = true else if (y + 1 < height && hole[i + width]) hole[i] = true
            }
        }
        val cx = (sumX / count).toInt().coerceIn(0, width - 1)
        val cy = (sumY / count).toInt().coerceIn(0, height - 1)

        // 2) 三通道浮点图：先在洞内放初值，再迭代修
        val f = Array(3) { FloatArray(before.size) }
        for (i in before.indices) {
            val c = before[i]
            f[0][i] = Color.red(c).toFloat()
            f[1][i] = Color.green(c).toFloat()
            f[2][i] = Color.blue(c).toFloat()
        }
        val angles = intArrayOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)

        // 源偏移：把外圈最像的那块整体平移过来当初值
        // 0 内容识别 / 2 近似匹配 都靠它保留纹理，再用泊松把边界对平；1 创建纹理 直接整块搬、不迭代
        var dx = 0
        var dy = 0
        if (type == 1) {
            // 创建纹理：样本合成（exemplar synthesis）—— 由外向内一圈圈填，
            // 每个像素都在外圈候选里挑「和它周围已填区域最像」的那块取色，纹理连贯又有颗粒
            val known = BooleanArray(width * height)
            for (i in before.indices) known[i] = !hole[i] && Color.alpha(before[i]) > 24
            val holeSet = BooleanArray(width * height) { hole[it] }
            var left = 0
            for (i in before.indices) if (holeSet[i]) left++
            val band = ArrayList<Int>()
            var passGuard = 0
            while (left > 0 && passGuard++ < 4096) {
                band.clear()
                for (i in before.indices) {
                    if (!holeSet[i] || known[i]) continue
                    val x = i % width
                    val y = i / width
                    val adj = (x > 0 && known[i - 1]) || (x < width - 1 && known[i + 1]) ||
                        (y > 0 && known[i - width]) || (y < height - 1 && known[i + width])
                    if (adj) band.add(i)
                }
                if (band.isEmpty()) {
                    for (i in before.indices) if (holeSet[i] && !known[i]) band.add(i)
                    if (band.isEmpty()) break
                }
                // Criminisi：先补「周围已知多、并且有结构」的地方。
                // 优先级 = 置信度（5×5 已知占比）× (0.3 + 数据项)，数据项用已知邻居的局部梯度近似等照度线强度
                val pri = FloatArray(band.size)
                for (bi in band.indices) {
                    val i = band[bi]
                    val x = i % width
                    val y = i / width
                    var kn = 0
                    var tot = 0
                    for (oy in -2..2) {
                        for (ox in -2..2) {
                            val qx = x + ox
                            val qy = y + oy
                            if (qx < 0 || qy < 0 || qx >= width || qy >= height) continue
                            tot++
                            if (known[qy * width + qx]) kn++
                        }
                    }
                    val conf = if (tot > 0) kn.toFloat() / tot else 0f
                    var gsum = 0f
                    var gcnt = 0
                    for (oy in -1..1) {
                        for (ox in -1..1) {
                            if (ox == 0 && oy == 0) continue
                            val qx = x + ox
                            val qy = y + oy
                            if (qx < 0 || qy < 0 || qx >= width || qy >= height) continue
                            val qi = qy * width + qx
                            if (!known[qi]) continue
                            val qx2 = qx + ox
                            val qy2 = qy + oy
                            if (qx2 < 0 || qy2 < 0 || qx2 >= width || qy2 >= height) continue
                            val qi2 = qy2 * width + qx2
                            if (!known[qi2]) continue
                            val c1 = before[qi]
                            val c2 = before[qi2]
                            gsum += kotlin.math.abs(Color.red(c1) - Color.red(c2)).toFloat() +
                                kotlin.math.abs(Color.green(c1) - Color.green(c2)).toFloat() +
                                kotlin.math.abs(Color.blue(c1) - Color.blue(c2)).toFloat()
                            gcnt++
                        }
                    }
                    val data = if (gcnt > 0) (gsum / gcnt / 255f).coerceIn(0f, 1f) else 0f
                    pri[bi] = conf * (0.3f + data)
                }
                val order = band.indices.sortedByDescending { pri[it] }
                for (bi in order) {
                    val i = band[bi]
                    val x = i % width
                    val y = i / width
                    var bestScore = Long.MAX_VALUE
                    var bR = Color.red(before[i])
                    var bG = Color.green(before[i])
                    var bB = Color.blue(before[i])
                    var found = false
                    for (t in 0 until angles.size) {
                        val rad = Math.toRadians(angles[t].toDouble())
                        val cosA = kotlin.math.cos(rad).toFloat()
                        val sinA = kotlin.math.sin(rad).toFloat()
                        // 几何步进：从笔刷边缘一路搜到画面尺度，大结构纹理也能找到同源样本
                        val far = min(max(width, height) / 2, 2048).toFloat()
                        var step = max(2f, r * 0.9f * rs)
                        var tried = 0
                        while (tried < 8 && step <= far) {
                            val nx = (x + cosA * step).toInt()
                            val ny = (y + sinA * step).toInt()
                            step *= 1.7f
                            if (nx < 1 || ny < 1 || nx >= width - 1 || ny >= height - 1) continue
                            val ni = ny * width + nx
                            if (holeSet[ni] && !known[ni]) continue
                            tried++
                            var score = 0L
                            for (oy in -1..1) {
                                for (ox in -1..1) {
                                    if (ox == 0 && oy == 0) continue
                                    val qx = x + ox
                                    val qy = y + oy
                                    if (qx < 0 || qy < 0 || qx >= width || qy >= height) continue
                                    val qi = qy * width + qx
                                    if (!known[qi]) continue
                                    val sx2 = nx + ox
                                    val sy2 = ny + oy
                                    if (sx2 < 0 || sy2 < 0 || sx2 >= width || sy2 >= height) continue
                                    val si = sy2 * width + sx2
                                    if (holeSet[si] && !known[si]) continue
                                    val a = before[qi]
                                    val b2 = before[si]
                                    score += kotlin.math.abs(Color.red(a) - Color.red(b2)).toLong() +
                                        kotlin.math.abs(Color.green(a) - Color.green(b2)).toLong() +
                                        kotlin.math.abs(Color.blue(a) - Color.blue(b2)).toLong()
                                }
                            }
                            if (score < bestScore) {
                                bestScore = score
                                bR = Color.red(before[ni])
                                bG = Color.green(before[ni])
                                bB = Color.blue(before[ni])
                                found = true
                            }
                        }
                    }
                    if (!found) {
                        // 候选全落空（细长洞）：退到最近的干净像素
                        var nx = x
                        var ny = y
                        var guard2 = 0
                        while (guard2++ < 64 && (holeSet[ny * width + nx] && !known[ny * width + nx])) {
                            nx = (nx + if (x < cx) 1 else -1).coerceIn(0, width - 1)
                            ny = (ny + if (y < cy) 1 else -1).coerceIn(0, height - 1)
                        }
                        val p = before[ny * width + nx]
                        bR = Color.red(p)
                        bG = Color.green(p)
                        bB = Color.blue(p)
                    }
                    f[0][i] = bR.toFloat()
                    f[1][i] = bG.toFloat()
                    f[2][i] = bB.toFloat()
                    known[i] = true
                }
                for (bi in band.indices) {
                    val i = band[bi]
                    if (holeSet[i]) { holeSet[i] = false; left-- }
                    known[i] = true
                }
            }
        } else {
            // 内容识别 / 近似匹配：整体平移最像的块当初值。内容识别搜得更远，便于找到同结构
            val spread = if (type == 0) 1.7f else 1f
            val best = bestRingSample(before, covMask, width, height, cx, cy, r, rs * spread) ?: return out
            dx = best[0] - cx
            dy = best[1] - cy
            for (i in before.indices) {
                if (!hole[i]) continue
                val x = i % width
                val y = i / width
                val sx = (x + dx).coerceIn(0, width - 1)
                val sy = (y + dy).coerceIn(0, height - 1)
                val p = before[sy * width + sx]
                f[0][i] = Color.red(p).toFloat()
                f[1][i] = Color.green(p).toFloat()
                f[2][i] = Color.blue(p).toFloat()
            }
        }

        // 3) 高斯-赛德尔迭代。泊松：洞内用「邻居 + 源的梯度差」推；调和：只让邻居把洞抹平到边界值
        // 创建纹理（1）不迭代：保留搬过来的颗粒，迭代会把它抹平
        val iters = if (type == 1) 0 else (max(hx1 - hx0, hy1 - hy0) * 2).coerceIn(16, itersCap)
        val guide = type != 1 && (dx != 0 || dy != 0)
        for (it in 0 until iters) {
            for (y in hy0..hy1) {
                var idx = y * width + hx0
                for (x in hx0..hx1) {
                    if (hole[idx]) {
                        for (ch in 0 until 3) {
                            val arr = f[ch]
                            var acc = 0f
                            var n = 0
                            if (x > 0) {
                                val q = idx - 1
                                acc += if (hole[q]) arr[q] else arr[q]
                                if (guide) acc += srcAt(before, width, height, idx, q, ch, dx, dy)
                                n++
                            }
                            if (x + 1 < width) {
                                val q = idx + 1
                                acc += arr[q]
                                if (guide) acc += srcAt(before, width, height, idx, q, ch, dx, dy)
                                n++
                            }
                            if (y > 0) {
                                val q = idx - width
                                acc += arr[q]
                                if (guide) acc += srcAt(before, width, height, idx, q, ch, dx, dy)
                                n++
                            }
                            if (y + 1 < height) {
                                val q = idx + width
                                acc += arr[q]
                                if (guide) acc += srcAt(before, width, height, idx, q, ch, dx, dy)
                                n++
                            }
                            if (n > 0) arr[idx] = acc / n
                        }
                    }
                    idx++
                }
            }
        }

        // 4) 落回 8 位，按覆盖度做软边
        val bl = blend.coerceIn(0f, 1f)
        for (i in before.indices) {
            if (!hole[i]) continue
            val d = before[i]
            val a = Color.alpha(d)
            val ck = (cov(covMask[i]) / 255f).coerceIn(0f, 1f)
            val k = if (bl >= 0.99f) ck else ((ck - 0.5f) / max(0.02f, bl) + 0.5f).coerceIn(0f, 1f)
            val pr = Color.red(d).toFloat()
            val pg = Color.green(d).toFloat()
            val pb = Color.blue(d).toFloat()
            val kr = f[0][i].coerceIn(0f, 255f)
            val kg = f[1][i].coerceIn(0f, 255f)
            val kb = f[2][i].coerceIn(0f, 255f)
            out[i] = Color.argb(
                a,
                (pr + (kr - pr) * k).toInt().coerceIn(0, 255),
                (pg + (kg - pg) * k).toInt().coerceIn(0, 255),
                (pb + (kb - pb) * k).toInt().coerceIn(0, 255),
            )
        }
        return out
    }

    /** 泊松项的源梯度：v(p→q) = S(p) - S(q)，S 是整体平移了 (dx,dy) 的源图 */
    private fun srcAt(
        px: IntArray, width: Int, height: Int, pi: Int, qi: Int, ch: Int, dx: Int, dy: Int,
    ): Float {
        val px0 = pi % width + dx
        val py0 = pi / width + dy
        val qx0 = qi % width + dx
        val qy0 = qi / width + dy
        val pv = px[py0.coerceIn(0, height - 1) * width + px0.coerceIn(0, width - 1)]
        val qv = px[qy0.coerceIn(0, height - 1) * width + qx0.coerceIn(0, width - 1)]
        val a = when (ch) {
            0 -> Color.red(pv) - Color.red(qv)
            1 -> Color.green(pv) - Color.green(qv)
            else -> Color.blue(pv) - Color.blue(qv)
        }
        return a.toFloat()
    }

    /** 在外圈 12 个方向上挑一块 3×3 最接近（纹理走向像）的样本，返回样本中心坐标 */
    private fun bestRingSample(
        px: IntArray, covMask: IntArray, width: Int, height: Int,
        x: Int, y: Int, r: Int, rs: Float,
    ): IntArray? {
        val angles = intArrayOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
        var best: IntArray? = null
        var bestD = Int.MAX_VALUE
        for (ang in angles) {
            val rad = Math.toRadians(ang.toDouble())
            val cosA = kotlin.math.cos(rad).toFloat()
            val sinA = kotlin.math.sin(rad).toFloat()
            var step = r * 1.6f * rs
            while (step <= r * 2.4f * rs + 0.5f) {
                val nx = (x + cosA * step).toInt()
                val ny = (y + sinA * step).toInt()
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) break
                val ni = ny * width + nx
                if (cov(covMask[ni]) <= COV_MIN && Color.alpha(px[ni]) > 24) {
                    val dd = patchDiff(px, width, height, nx, ny, x, y)
                    if (dd < bestD) {
                        bestD = dd
                        best = intArrayOf(nx, ny)
                    }
                    break
                }
                step += 1.5f
            }
        }
        return best
    }

    /** Sober 水平梯度（亮度，3×3，边界夹取） */
    private fun sobelX(px: IntArray, w: Int, h: Int, i: Int): Int {
        if (w <= 2 || h <= 2) return 0
        val x = i % w
        val y = i / w
        val x0 = (x - 1).coerceIn(0, w - 1)
        val x1 = x
        val x2 = (x + 1).coerceIn(0, w - 1)
        val y0 = (y - 1).coerceIn(0, h - 1)
        val y1 = y
        val y2 = (y + 1).coerceIn(0, h - 1)
        return -lum(px[y0 * w + x0]) - 2 * lum(px[y1 * w + x0]) - lum(px[y2 * w + x0]) +
            lum(px[y0 * w + x2]) + 2 * lum(px[y1 * w + x2]) + lum(px[y2 * w + x2])
    }

    /** Sobel 垂直梯度 */
    private fun sobelY(px: IntArray, w: Int, h: Int, i: Int): Int {
        if (w <= 2 || h <= 2) return 0
        val x = i % w
        val y = i / w
        val x0 = (x - 1).coerceIn(0, w - 1)
        val x1 = x
        val x2 = (x + 1).coerceIn(0, w - 1)
        val y0 = (y - 1).coerceIn(0, h - 1)
        val y1 = y
        val y2 = (y + 1).coerceIn(0, h - 1)
        return -lum(px[y0 * w + x0]) - 2 * lum(px[y0 * w + x1]) - lum(px[y0 * w + x2]) +
            lum(px[y2 * w + x0]) + 2 * lum(px[y2 * w + x1]) + lum(px[y2 * w + x2])
    }

    private fun lum(c: Int): Int =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()

    /** 3×3 邻域的像素差之和：用来判断「这块纹理和目标像不像」，比只比中心点稳 */
    private fun patchDiff(
        px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tx: Int, ty: Int,
    ): Int {
        var sum = 0
        for (dy in -1..1) {
            val ay = (sy + dy).coerceIn(0, h - 1)
            val by = (ty + dy).coerceIn(0, h - 1)
            for (dx in -1..1) {
                val ax = (sx + dx).coerceIn(0, w - 1)
                val bx = (tx + dx).coerceIn(0, w - 1)
                val a = px[ay * w + ax]
                val b = px[by * w + bx]
                sum += kotlin.math.abs(Color.red(a) - Color.red(b)) +
                    kotlin.math.abs(Color.green(a) - Color.green(b)) +
                    kotlin.math.abs(Color.blue(a) - Color.blue(b))
            }
        }
        return sum
    }

    // ---------------- 红眼 ----------------
    /**
     * 把区域里的红瞳孔压暗：红通道限到绿蓝均值以下，并按 pupil 大小做圆形羽化。
     * pupil 0..1（瞳孔大小），darken 0..1（变暗量）。
     */
    fun redEye(
        before: IntArray, width: Int, height: Int, pupil: Float, darken: Float,
    ): IntArray {
        val out = before.copyOf()
        val cx = width / 2f
        val cy = height / 2f
        val rad = max(1f, min(cx, cy) * (0.35f + 0.65f * pupil.coerceIn(0f, 1f)))
        for (i in before.indices) {
            val p = before[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            // 以前卡 r>40 && r>1.15g && r>1.15b，金瞳棕瞳全不命中 → 整框静默没反应。
            // 现在只要「红明显压过绿蓝」且饱和度够就处理，桌面端的红眼也是看红色占优
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx <= 0) 0f else (mx - mn).toFloat() / mx
            if (r < 24) continue
            if (r <= g * 1.06f || r <= b * 1.06f) continue
            if (r - max(g, b) < 6 && sat < 0.35f) continue
            val x = i % width
            val y = i / width
            val dist = kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (dist > rad) continue
            val edge = (1f - (dist / rad).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val k = (edge * (0.4f + 0.6f * darken.coerceIn(0f, 1f))).coerceIn(0f, 1f)
            val target = ((g + b) / 2f) * (1f - 0.35f * darken.coerceIn(0f, 1f))
            val nr = r + (min(r.toFloat(), target) - r) * k
            out[i] = Color.argb(a, nr.toInt().coerceIn(0, 255), g, b)
        }
        return out
    }

    // ---------------- 历史记录画笔 ----------------
    /** 把快照里的像素按笔迹刷回来；opacity 为笔迹强度 */
    fun historyBrush(
        before: IntArray, covMask: IntArray, sel: IntArray?, snapshot: IntArray, opacity: Float,
    ): IntArray {
        val out = before.copyOf()
        if (snapshot.size != before.size) return out
        for (i in before.indices) {
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val k = (opacity * c / 255f).coerceIn(0f, 1f)
            out[i] = mixArgb(before[i], snapshot[i], k)
        }
        return out
    }

    /** 艺术历史记录：把快照按笔触抖动刷回来。style 0..9，fidelity 0..100 */
    fun artHistory(
        before: IntArray, covMask: IntArray, sel: IntArray?, snapshot: IntArray,
        width: Int, height: Int, opacity: Float, style: Int, fidelity: Int, seed: Int,
    ): IntArray {
        val out = before.copyOf()
        if (snapshot.size != before.size) return out
        val rnd = Random(seed)
        val tight = style / 2f
        val jitter = ((100 - fidelity.coerceIn(0, 100)) / 100f * (8f + tight * 6f)).toInt().coerceAtLeast(1)
        val dot = style >= 8
        // 每一小格（8~14px）共用一个偏移量：以前是逐像素各取一次随机，看起来是一层雪花噪点；
        // 按格聚簇之后同一片颜色来自同一处，才像一笔一笔的笔触
        val cell = (6 + tight.toInt() * 2).coerceIn(6, 18)
        for (i in before.indices) {
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val x = i % width
            val y = i / width
            val cx = x / cell
            val cy = y / cell
            val hsh = (cx * 73856093) xor (cy * 19349663) xor (seed * 83492791)
            val cellRnd = Random(hsh)
            val sx = (x + if (dot) 0 else cellRnd.nextInt(-jitter, jitter + 1)).coerceIn(0, width - 1)
            val sy = (y + if (dot) 0 else cellRnd.nextInt(-jitter, jitter + 1)).coerceIn(0, height - 1)
            val s = snapshot[sy * width + sx]
            val k = (opacity * c / 255f).coerceIn(0f, 1f) * if (dot) 1f else 0.9f
            out[i] = mixArgb(before[i], s, k)
        }
        return out
    }

    private fun mixArgb(from: Int, to: Int, k: Float): Int {
        if (k <= 0f) return from
        if (k >= 1f) return to
        val a1 = Color.alpha(from)
        val a2 = Color.alpha(to)
        val a = (a1 + (a2 - a1) * k).toInt().coerceIn(0, 255)
        val r = Color.red(from) + (Color.red(to) - Color.red(from)) * k
        val g = Color.green(from) + (Color.green(to) - Color.green(from)) * k
        val b = Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k
        return Color.argb(a, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    // ---------------- 魔术橡皮擦 ----------------
    /**
     * 点击处泛洪擦除（continuous=true）或全图相似色擦除。
     * 返回改好的整幅像素。
     */
    fun magicErase(
        before: IntArray, width: Int, height: Int, startX: Int, startY: Int,
        tolerance: Int, continuous: Boolean, wholeLayer: Boolean, opacity: Float,
        antialias: Boolean = true,
    ): IntArray {
        val out = before.copyOf()
        if (startX < 0 || startY < 0 || startX >= width || startY >= height) return out
        val seed = before[startY * width + startX]
        if (Color.alpha(seed) == 0) return out
        val tol = tolerance.coerceIn(0, 255)
        val k = opacity.coerceIn(0f, 1f)
        if (!continuous) {
            for (i in before.indices) {
                if (colorDistance(before[i], seed) > tol) continue
                val a = Color.alpha(before[i])
                if (a == 0) continue
                val na = (a * (1f - k)).toInt().coerceIn(0, 255)
                out[i] = if (na <= 0) 0 else Color.argb(na, Color.red(before[i]), Color.green(before[i]), Color.blue(before[i]))
            }
            return out
        }
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var sp = 0
        stack[sp++] = startY * width + startX
        seen[startY * width + startX] = true
        val erased = BooleanArray(width * height)
        while (sp > 0) {
            val i = stack[--sp]
            if (colorDistance(before[i], seed) > tol) continue
            erased[i] = true
            val x = i % width
            val y = i / width
            if (x > 0 && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
            if (x < width - 1 && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
            if (y > 0 && !seen[i - width]) { seen[i - width] = true; stack[sp++] = i - width }
            if (y < height - 1 && !seen[i + width]) { seen[i + width] = true; stack[sp++] = i + width }
        }
        // 消除锯齿：紧贴边界的像素只擦一半，边缘不会切得像剪纸
        val soft = if (antialias) BooleanArray(width * height) else null
        if (soft != null) {
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val idx = j * width + i
                    if (!erased[idx]) continue
                    val boundary = (i > 0 && !erased[idx - 1]) ||
                        (i < width - 1 && !erased[idx + 1]) ||
                        (j > 0 && !erased[idx - width]) ||
                        (j < height - 1 && !erased[idx + width])
                    if (boundary) soft[idx] = true
                }
            }
        }
        for (i in before.indices) {
            if (!erased[i]) continue
            val a = Color.alpha(before[i])
            val kk = if (soft != null && soft[i]) k * 0.5f else k
            val na = (a * (1f - kk)).toInt().coerceIn(0, 255)
            out[i] = if (na <= 0) 0 else Color.argb(na, Color.red(before[i]), Color.green(before[i]), Color.blue(before[i]))
        }
        return out
    }

    // ---------------- 混合器画笔 ----------------
    /**
     * 笔刷先「沾」画布颜色再画出去。load 0..1 = 载入量，mix 0..1 = 与画笔色混合比例。
     * state 是跨笔迹保存的当前载荷色（r,g,b 0..255）。
     */
    fun mixer(
        before: IntArray, covMask: IntArray, sel: IntArray?,
        color: Int, state: FloatArray, load: Float, mix: Float, opacity: Float,
    ): IntArray {
        val out = before.copyOf()
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var n = 0
        for (i in before.indices) {
            if (cov(covMask[i]) <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val p = before[i]
            if (Color.alpha(p) < 24) continue
            sr += Color.red(p)
            sg += Color.green(p)
            sb += Color.blue(p)
            n++
        }
        if (n > 0) {
            val l = load.coerceIn(0f, 1f)
            state[0] = state[0] + ((sr / n).toFloat() - state[0]) * l
            state[1] = state[1] + ((sg / n).toFloat() - state[1]) * l
            state[2] = state[2] + ((sb / n).toFloat() - state[2]) * l
        }
        val m = mix.coerceIn(0f, 1f)
        val cr = Color.red(color).toFloat()
        val cg = Color.green(color).toFloat()
        val cb = Color.blue(color).toFloat()
        val fr = state[0] + (cr - state[0]) * m
        val fg = state[1] + (cg - state[1]) * m
        val fb = state[2] + (cb - state[2]) * m
        for (i in before.indices) {
            val c = cov(covMask[i])
            if (c <= COV_MIN) continue
            if (sel != null && Color.alpha(sel[i]) <= 8) continue
            val k = (opacity * c / 255f).coerceIn(0f, 1f)
            val d = before[i]
            val rr = Color.red(d) + (fr - Color.red(d)) * k
            val gg = Color.green(d) + (fg - Color.green(d)) * k
            val bb = Color.blue(d) + (fb - Color.blue(d)) * k
            // alpha 原样保留：混合器是抹开画面颜色，不该把画面抹透明
            out[i] = Color.argb(Color.alpha(d), rr.toInt().coerceIn(0, 255), gg.toInt().coerceIn(0, 255), bb.toInt().coerceIn(0, 255))
        }
        return out
    }
}

/** 内置图案库：图案图章用 */
internal object Patterns {
    private var cache: List<Pair<String, Bitmap>>? = null
    private val custom = ArrayList<Pair<String, Bitmap>>()

    /** 把一张图（一般是选区里的那部分）存成图案，桌面端的「定义图案」 */
    fun addCustom(name: String, src: Bitmap): Int {
        val bmp = if (src.width <= 128 && src.height <= 128) src.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(src, 64, 64, true)
        all() // 先保证内置的建好
        custom.add(name to bmp)
        cache = null
        return 8 + custom.size - 1
    }

    fun all(): List<Pair<String, Bitmap>> {
        cache?.let { return it }
        val list = ArrayList<Pair<String, Bitmap>>()
        list.add("条纹" to make { g, s ->
            if (g % 16 < 8) Color.rgb(245, 245, 245) else Color.rgb(60, 60, 64)
        })
        list.add("圆点" to make { g, s ->
            val x = g % s
            val y = (g / s) % s
            val dx = x - s / 2f
            val dy = y - s / 2f
            if (dx * dx + dy * dy < 20f) Color.rgb(255, 210, 80) else Color.rgb(40, 44, 52)
        })
        list.add("格子" to make { g, s ->
            val x = g % s
            val y = (g / s) % s
            if (x < 16 && y < 16) Color.rgb(230, 80, 80) else Color.rgb(250, 250, 250)
        })
        list.add("斜线" to make { g, s ->
            val x = g % s
            val y = (g / s) % s
            if ((x + y) % 20 < 4) Color.rgb(70, 140, 255) else Color.rgb(248, 248, 248)
        })
        list.add("星点" to make { g, s ->
            val x = g % s
            val y = (g / s) % s
            val cx = s / 2
            val cy = s / 2
            val d = abs(x - cx) + abs(y - cy)
            if (d < 5) Color.rgb(255, 255, 255) else if (d < 9) Color.rgb(120, 120, 160) else Color.rgb(20, 24, 40)
        })
        list.add("噪点" to make { g, _ ->
            val v = (g * 2654435761L % 200L).toInt() + 40
            Color.rgb(v, v, v)
        })
        list.add("木纹" to make { g, s ->
            val x = g % s
            val y = (g / s) % s
            val w = (kotlin.math.sin(x * 0.35 + y * 0.06) * 22 + 120).toInt()
            Color.rgb((w + 40).coerceIn(0, 255), (w + 10).coerceIn(0, 255), (w - 30).coerceIn(0, 255))
        })
        list.add("格纹布" to make { g, s ->
            val x = g % s
            val y = (g / s) % s
            val a = (x / 8) % 2 == 0
            val b = (y / 8) % 2 == 0
            when {
                a && b -> Color.rgb(200, 60, 60)
                a || b -> Color.rgb(240, 240, 240)
                else -> Color.rgb(90, 110, 160)
            }
        })
        list.addAll(custom)
        cache = list
        return list
    }

    private fun make(f: (Int, Int) -> Int): Bitmap {
        val s = 32
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val px = IntArray(s * s)
        for (i in px.indices) px[i] = f(i, s)
        bmp.setPixels(px, 0, s, 0, 0, s, s)
        return bmp
    }
}