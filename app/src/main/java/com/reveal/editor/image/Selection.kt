package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 选区形状 */
enum class SelectMode { RECT, ELLIPSE, ROW, COLUMN, LASSO, POLY, MAGNETIC, WAND, QUICK, SUBJECT, RANGE }

/** 选区运算：新建 / 加选 / 减选 */
enum class SelectOp { NEW, ADD, SUB, INTERSECT }

/**
 * 选区用一张和文档同尺寸的 ARGB 位图当遮罩：白色不透明 = 选中，透明 = 没选。
 * 所有形状先画到自己的遮罩上，再按运算方式合并进主遮罩。
 */
object Selection {

    fun blank(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun shapeCanvas(mask: Bitmap): Canvas = Canvas(mask)

    fun rectMask(w: Int, h: Int, rect: RectF): Bitmap {
        val mask = blank(w, h)
        shapeCanvas(mask).drawRect(rect, solidPaint())
        return mask
    }

    fun ellipseMask(w: Int, h: Int, rect: RectF): Bitmap {
        val mask = blank(w, h)
        shapeCanvas(mask).drawOval(rect, solidPaint())
        return mask
    }

    fun pathMask(w: Int, h: Int, path: Path): Bitmap {
        val mask = blank(w, h)
        shapeCanvas(mask).drawPath(path, solidPaint())
        return mask
    }

    /** 合并：把 shape 按 op 合进 dst */
    fun apply(dst: Bitmap, shape: Bitmap, op: SelectOp) {
        val canvas = Canvas(dst)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        when (op) {
            SelectOp.NEW -> {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                canvas.drawBitmap(shape, 0f, 0f, paint)
            }

            SelectOp.ADD -> canvas.drawBitmap(shape, 0f, 0f, paint)

            SelectOp.INTERSECT -> {
                val keep = Bitmap.createBitmap(dst.width, dst.height, Bitmap.Config.ARGB_8888)
                val kc = Canvas(keep)
                kc.drawBitmap(dst, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                val p2 = Paint(Paint.FILTER_BITMAP_FLAG)
                p2.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                kc.drawBitmap(shape, 0f, 0f, p2)
                p2.xfermode = null
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                canvas.drawBitmap(keep, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                keep.recycle()
            }

            SelectOp.SUB -> {
                // 减选：dst = dst * (1 - shape.alpha)。用 DST_OUT 而不是 CLEAR：
                // CLEAR 把整个绘制区域清成透明（根本不看源图 alpha），减选就变成「清空选区」。
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                canvas.drawBitmap(shape, 0f, 0f, paint)
                paint.xfermode = null
            }
        }
    }

    fun invert(mask: Bitmap) {
        val w = mask.width
        val h = mask.height
        val inv = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inv)
        canvas.drawColor(Color.WHITE)
        val paint = Paint()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        paint.xfermode = null

        val target = Canvas(mask)
        target.drawColor(0, PorterDuff.Mode.CLEAR)
        target.drawBitmap(inv, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        inv.recycle()
    }

    /** 全选 */
    fun fill(mask: Bitmap) {
        Canvas(mask).drawColor(Color.WHITE)
    }

    /**
     * 魔棒：从 (x,y) 出发按颜色相近做扫描线填充。
     * tolerance 0~1，1 表示什么都算选中。
     */
    fun wandMask(
        src: Bitmap,
        x: Int,
        y: Int,
        tolerance: Float,
        contiguous: Boolean = true,
    ): Bitmap? {
        val w = src.width
        val h = src.height
        if (x !in 0 until w || y !in 0 until h) return null
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val seed = pixels[y * w + x]
        val seedA = Color.alpha(seed)
        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)
        // 颜色距离上限：完全不透明色最大距离约 441
        val limit = (tolerance.coerceIn(0f, 1f) * 320f) + 1f
        val limit2 = limit * limit

        val out = IntArray(w * h)
        val visited = BooleanArray(w * h)
        if (!contiguous) {
            // 不连续：全图所有跟落点颜色相近的像素都选上（桌面端关掉「连续」时的行为）
            var hit = false
            for (i in 0 until w * h) {
                if (match(pixels[i], sr, sg, sb, seedA, limit2)) {
                    out[i] = Color.WHITE
                    hit = true
                }
            }
            if (!hit) return null
            val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            dst.setPixels(out, 0, w, 0, 0, w, h)
            return dst
        }
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = y * w + x
        val white = Color.WHITE

        while (sp > 0) {
            val index = stack[--sp]
            if (visited[index]) continue
            var px = index % w
            val py = index / w
            // 向左扩
            var left = px
            while (left >= 0) {
                val i = py * w + left
                if (visited[i] || !match(pixels[i], sr, sg, sb, seedA, limit2)) break
                left--
            }
            left++
            var right = px
            while (right < w) {
                val i = py * w + right
                if (visited[i] || !match(pixels[i], sr, sg, sb, seedA, limit2)) break
                right++
            }
            right--

            for (i in left..right) {
                val idx = py * w + i
                visited[idx] = true
                out[idx] = white
                if (py > 0) {
                    val up = idx - w
                    if (!visited[up] && match(pixels[up], sr, sg, sb, seedA, limit2)) stack[sp++] = up
                }
                if (py < h - 1) {
                    val down = idx + w
                    if (!visited[down] && match(pixels[down], sr, sg, sb, seedA, limit2)) stack[sp++] = down
                }
            }
        }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(out, 0, w, 0, 0, w, h)
        return mask
    }

    private fun match(c: Int, r: Int, g: Int, b: Int, a: Int, limit2: Float): Boolean {
        val da = Color.alpha(c) - a
        val dr = Color.red(c) - r
        val dg = Color.green(c) - g
        val db = Color.blue(c) - b
        // 全透明像素之间算同色
        if (a == 0 && Color.alpha(c) == 0) return true
        val d = (da * da + dr * dr + dg * dg + db * db).toFloat()
        return d <= limit2
    }

    /**
     * 做一张小的描边图给画布叠显示（避免每帧扫全尺寸）。
     * scale 越大越省，2~4 比较合适。
     */
    fun edgeBitmap(mask: Bitmap, scale: Int): Bitmap {
        val sw = max(1, mask.width / scale)
        val sh = max(1, mask.height / scale)
        val small = Bitmap.createScaledBitmap(mask, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val out = IntArray(sw * sh)
        val threshold = 128
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val i = y * sw + x
                val here = Color.alpha(pixels[i]) >= threshold
                var edge = false
                if (x > 0) {
                    val l = Color.alpha(pixels[i - 1]) >= threshold
                    if (l != here) edge = true
                }
                if (!edge && x < sw - 1) {
                    val r = Color.alpha(pixels[i + 1]) >= threshold
                    if (r != here) edge = true
                }
                if (!edge && y > 0) {
                    val u = Color.alpha(pixels[i - sw]) >= threshold
                    if (u != here) edge = true
                }
                if (!edge && y < sh - 1) {
                    val d = Color.alpha(pixels[i + sw]) >= threshold
                    if (d != here) edge = true
                }
                // 画布边缘也算边界：选到最外圈时，框也要有蚂蚁线
                if (!edge && here && (x == 0 || y == 0 || x == sw - 1 || y == sh - 1)) edge = true
                if (edge) out[i] = Color.WHITE
            }
        }
        val result = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, sw, 0, 0, sw, sh)
        small.recycle()
        return result
    }

    /** 轮廓点数预算：超过就放宽简化容差，保证蚂蚁线描边不吃帧 */
    private const val OUTLINE_POINT_BUDGET = 1500

    /**
     * 把选区遮罩矢量化：沿 50% 透明度的地方描出闭合轮廓（外框和洞都保留）。
     * 蚂蚁线、选区转路径都用它，所以魔棒/快速选择/羽化/反选之后也是清楚的一根线。
     * eps：折线简化容差（像素）。0.6 能保住 1 像素的小方块，又不至于点数爆炸。
     * 碎得没法直接描的选区（魔棒点了一堆散点、噪点）先把遮罩缩一半再描，
     * 最后把轮廓按比例放大回去——宁可线条粗一点，也不要退回位图边缘那圈锯齿。
     */
    fun outlinePath(mask: Bitmap, eps: Float = 0.6f, cap: Int = 60000): Path? {
        var cur = mask
        var scale = 1f
        var depth = 0
        while (true) {
            val p = traceOutline(cur, eps / scale, cap)
            if (p != null) {
                if (scale == 1f) return p
                val big = Path(p)
                val m = Matrix()
                m.setScale(scale, scale)
                big.transform(m)
                return big
            }
            if (depth >= 3) return null
            val nw = maxOf(2, cur.width / 2)
            val nh = maxOf(2, cur.height / 2)
            if (nw == cur.width && nh == cur.height) return null
            val small = try {
                Bitmap.createScaledBitmap(cur, nw, nh, true)
            } catch (t: Throwable) {
                null
            } ?: return null
            if (cur !== mask) cur.recycle()
            cur = small
            scale *= 2f
            depth++
        }
    }

    /** 直接一遍描出来；点数超了返回 null，交给外面上采样重试。 */
    private fun traceOutline(mask: Bitmap, eps: Float, cap: Int): Path? {
        val w = mask.width
        val h = mask.height
        if (w < 2 || h < 2) return null
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)

        // 单趟扫描：一边找边界盒，一边把边界的有向边收集起来。
        // 每条边贴着选中像素的外侧走，相邻边首尾自动相接，拼起来就是闭合轮廓。
        val step = w + 1
        val ex = ArrayList<Int>(1 shl 12)
        val ey = ArrayList<Int>(1 shl 12)
        var any = false
        for (y in 0 until h) {
            val row = y * w
            val up = row - w
            val dn = row + w
            val hasUp = y > 0
            val hasDn = y < h - 1
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) < 128) continue
                any = true
                val upOn = hasUp && (px[up + x] ushr 24) >= 128
                val dnOn = hasDn && (px[dn + x] ushr 24) >= 128
                val lfOn = x > 0 && (px[row + x - 1] ushr 24) >= 128
                val rtOn = x < w - 1 && (px[row + x + 1] ushr 24) >= 128
                if (!upOn) {
                    ex.add(y * step + x)
                    ey.add(y * step + x + 1)
                }
                if (!rtOn) {
                    ex.add(y * step + x + 1)
                    ey.add((y + 1) * step + x + 1)
                }
                if (!dnOn) {
                    ex.add((y + 1) * step + x + 1)
                    ey.add((y + 1) * step + x)
                }
                if (!lfOn) {
                    ex.add((y + 1) * step + x)
                    ey.add(y * step + x)
                }
            }
        }
        if (!any) return null
        val n = ex.size
        if (n == 0 || n > cap * 4) return null
        // 碎到这个程度（噪点选区）就不值得在这儿死磕：交给外面缩小遮罩再描，
        // 否则光是建索引 + 串环就能卡住几秒。
        if (n > 30000) return null

        val outs = HashMap<Int, MutableList<Int>>(n * 2)
        for (i in 0 until n) {
            outs.getOrPut(ex[i]) { ArrayList(2) }.add(i)
        }
        if (n > 400000) return null
        val used = BooleanArray(n)
        // 先把每个环的原始像素点收集起来，简化容差事后再定：
        // 小选区保细节（0.6px），大选区逐步放宽，免得几万个点每帧描边。
        val loopXs = ArrayList<ArrayList<Float>>()
        val loopYs = ArrayList<ArrayList<Float>>()
        val xs = ArrayList<Float>(64)
        val ys = ArrayList<Float>(64)
        for (i in 0 until n) {
            if (used[i]) continue
            used[i] = true
            xs.clear()
            ys.clear()
            val startKey = ex[i]
            xs.add((startKey % step).toFloat())
            ys.add((startKey / step).toFloat())
            var cur = i
            var guard = 0
            while (guard++ <= n) {
                val endKey = ey[cur]
                if (endKey == startKey) break
                xs.add((endKey % step).toFloat())
                ys.add((endKey / step).toFloat())
                val cand = outs[endKey] ?: break
                val cdx = endKey % step - ex[cur] % step
                val cdy = endKey / step - ex[cur] / step
                var nxt = -1
                for (j in cand) {
                    if (used[j]) continue
                    if (nxt < 0) nxt = j
                    // 优先直行：两条轮廓在对角顶点相碰时别串到对方身上
                    if (ey[j] % step - ex[j] % step == cdx && ey[j] / step - ex[j] / step == cdy) {
                        nxt = j
                        break
                    }
                }
                if (nxt < 0) break
                used[nxt] = true
                cur = nxt
            }
            if (xs.size < 4) continue
            loopXs.add(ArrayList(xs))
            loopYs.add(ArrayList(ys))
            if (loopXs.size > 1200) return null
        }
        if (loopXs.isEmpty()) return null
        // 点数预算：容差越细点越多。逐级放宽，取「能满足预算的最小容差」——质量优先，
        // 又不会在 4K 大选区上攒出几万个点拖慢每帧描边。全放过还不收敛就退回位图边缘。
        var tol = eps
        var best: Path? = null
        var bestCount = Int.MAX_VALUE
        repeat(8) {
            val path = Path()
            path.fillType = Path.FillType.WINDING
            var total = 0
            for (li in loopXs.indices) total += addOutlineLoop(path, loopXs[li], loopYs[li], tol)
            if (total > 0 && total < bestCount) {
                best = path
                bestCount = total
            }
            if (total > 0 && total <= OUTLINE_POINT_BUDGET && total <= cap) return path
            tol *= 1.6f
        }
        return if (bestCount <= cap / 2) best else null
    }

    /** 把一个闭合环（先去共线、再 Douglas-Peucker 简化）加进 Path，返回加了几个点 */
    private fun addOutlineLoop(path: Path, xs: MutableList<Float>, ys: MutableList<Float>, eps: Float): Int {
        val n = xs.size
        // 去掉共线点：矩形只剩四个角，斜边也不会全是台阶
        val cx = ArrayList<Float>(n)
        val cy = ArrayList<Float>(n)
        for (i in 0 until n) {
            val ax = xs[(i + n - 1) % n]
            val ay = ys[(i + n - 1) % n]
            val bx = xs[i]
            val by = ys[i]
            val dx = xs[(i + 1) % n]
            val dy = ys[(i + 1) % n]
            if ((bx - ax) * (dy - by) - (by - ay) * (dx - bx) != 0f) {
                cx.add(bx)
                cy.add(by)
            }
        }
        if (cx.size < 3) {
            cx.clear()
            cy.clear()
            for (i in 0 until n) {
                cx.add(xs[i])
                cy.add(ys[i])
            }
        }
        val m = cx.size
        val ax = cx.toFloatArray()
        val ay = cy.toFloatArray()
        val keep = BooleanArray(m)
        if (m <= 3) {
            for (i in 0 until m) keep[i] = true
        } else {
            keep[0] = true
            var split = 1
            var far = -1f
            for (i in 1 until m) {
                val d = (ax[i] - ax[0]) * (ax[i] - ax[0]) + (ay[i] - ay[0]) * (ay[i] - ay[0])
                if (d > far) {
                    far = d
                    split = i
                }
            }
            keep[split] = true
            dpKeep(ax, ay, 0, split, ax[0], ay[0], ax[split], ay[split], eps, keep)
            dpKeep(ax, ay, split, m, ax[split], ay[split], ax[0], ay[0], eps, keep)
        }
        var count = 0
        var first = true
        for (i in 0 until m) {
            if (!keep[i]) continue
            if (first) {
                path.moveTo(ax[i], ay[i])
                first = false
            } else {
                path.lineTo(ax[i], ay[i])
            }
            count++
        }
        path.close()
        return count
    }

    /**
     * Douglas-Peucker：区间 (a, b) 内的点到直线 a-b 的距离，超容差的点留下。
     * 用显式栈而不是递归：一个像素环可能有几万个点，递归会爆栈。
     */
    private fun dpKeep(
        xs: FloatArray, ys: FloatArray, a0: Int, b0: Int,
        ax0: Float, ay0: Float, bx0: Float, by0: Float,
        eps: Float, keep: BooleanArray
    ) {
        val capStack = 4096
        val ia = IntArray(capStack)
        val ib = IntArray(capStack)
        val fax = FloatArray(capStack)
        val fay = FloatArray(capStack)
        val fbx = FloatArray(capStack)
        val fby = FloatArray(capStack)
        ia[0] = a0
        ib[0] = b0
        fax[0] = ax0
        fay[0] = ay0
        fbx[0] = bx0
        fby[0] = by0
        var top = 1
        while (top > 0) {
            top--
            val a = ia[top]
            val b = ib[top]
            val ax = fax[top]
            val ay = fay[top]
            val bx = fbx[top]
            val by = fby[top]
            if (b - a < 2) continue
            val dx = bx - ax
            val dy = by - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            var best = -1f
            var bi = -1
            for (i in a + 1 until b) {
                val d = if (len < 1e-4f) {
                    kotlin.math.hypot((xs[i] - ax).toDouble(), (ys[i] - ay).toDouble()).toFloat()
                } else {
                    kotlin.math.abs(dy * xs[i] - dx * ys[i] + bx * ay - by * ax) / len
                }
                if (d > best) {
                    best = d
                    bi = i
                }
            }
            if (bi < 0 || best <= eps) continue
            keep[bi] = true
            if (top + 2 > capStack) {
                // 极端情况：栈满就先收工，剩下的点保持原样（轮廓只会更细，不会错）
                continue
            }
            ia[top] = a
            ib[top] = bi
            fax[top] = ax
            fay[top] = ay
            fbx[top] = xs[bi]
            fby[top] = ys[bi]
            top++
            ia[top] = bi
            ib[top] = b
            fax[top] = xs[bi]
            fay[top] = ys[bi]
            fbx[top] = bx
            fby[top] = by
            top++
        }
    }

    /** 选区是否为空（抽样看看，别全扫） */
    fun isEmpty(mask: Bitmap?): Boolean {
        if (mask == null) return true
        val step = max(1, min(mask.width, mask.height) / 64)
        var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (Color.alpha(mask.getPixel(x, y)) > 8) return false
                x += step
            }
            y += step
        }
        return true
    }

    fun isSimilar(a: Int, b: Int, tolerance: Float): Boolean {
        val limit = (tolerance.coerceIn(0f, 1f) * 320f) + 1f
        val dr = abs(Color.red(a) - Color.red(b))
        val dg = abs(Color.green(a) - Color.green(b))
        val db = abs(Color.blue(a) - Color.blue(b))
        return (dr + dg + db) / 3f <= limit
    }

    /** 羽化：把选区边缘按半径做一次盒式模糊，边缘变软 */
    fun feather(mask: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 100)
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val a = IntArray(w * h)
        for (i in px.indices) a[i] = Color.alpha(px[i])
        boxBlur(a, w, h, r)
        boxBlur(a, w, h, r)
        for (i in px.indices) {
            val alpha = a[i].coerceIn(0, 255)
            px[i] = (alpha shl 24) or 0x00FFFFFF
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 扩大选区：模糊之后把 alpha 高于阈值的都算进来 */
    fun grow(mask: Bitmap, px: Int): Bitmap {
        val r = px.coerceIn(1, 100)
        val w = mask.width
        val h = mask.height
        val src = IntArray(w * h)
        mask.getPixels(src, 0, w, 0, 0, w, h)
        val a = IntArray(w * h)
        for (i in src.indices) a[i] = Color.alpha(src[i])
        boxBlur(a, w, h, r)
        for (i in src.indices) {
            val keep = a[i] > 2
            src[i] = if (keep) Color.WHITE else 0
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(src, 0, w, 0, 0, w, h)
        return out
    }

    /** 收缩选区：先模糊再要求整圈都是实心（用二次腐蚀近似） */
    fun shrink(mask: Bitmap, px: Int): Bitmap {
        val r = px.coerceIn(1, 100)
        val w = mask.width
        val h = mask.height
        val src = IntArray(w * h)
        mask.getPixels(src, 0, w, 0, 0, w, h)
        val a = IntArray(w * h)
        for (i in src.indices) a[i] = if (Color.alpha(src[i]) > 8) 255 else 0
        // 腐蚀：半径内只要有一个空就挖掉
        val tmp = IntArray(w * h)
        val full = 255
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = a[y * w + x]
                if (v != 0) {
                    var k = -r
                    while (k <= r && v != 0) {
                        val xx = (x + k).coerceIn(0, w - 1)
                        if (a[y * w + xx] != full) v = 0
                        k++
                    }
                }
                tmp[y * w + x] = v
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = tmp[y * w + x]
                if (v != 0) {
                    var k = -r
                    while (k <= r && v != 0) {
                        val yy = (y + k).coerceIn(0, h - 1)
                        if (tmp[yy * w + x] != full) v = 0
                        k++
                    }
                }
                a[y * w + x] = v
            }
        }
        for (i in src.indices) src[i] = if (a[i] == full) Color.WHITE else 0
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(src, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(a.size)
        val div = r * 2 + 1
        for (y in 0 until h) {
            var sum = 0
            val base = y * w
            for (x in -r..r) sum += a[base + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = sum / div
                val outX = (x - r).coerceIn(0, w - 1)
                val inX = (x + r + 1).coerceIn(0, w - 1)
                sum += a[base + inX] - a[base + outX]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / div
                val outY = (y - r).coerceIn(0, h - 1)
                val inY = (y + r + 1).coerceIn(0, h - 1)
                sum += tmp[inY * w + x] - tmp[outY * w + x]
            }
        }
    }


    /** 平滑：半径内多数表决，边角变圆滑 */
    fun smooth(mask: Bitmap, px: Int): Bitmap {
        val r = px.coerceIn(1, 10)
        val w = mask.width
        val h = mask.height
        val src = IntArray(w * h)
        mask.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                var on = 0
                var total = 0
                var yy = y0
                while (yy <= y1) {
                    var xx = x0
                    while (xx <= x1) {
                        total++
                        if ((src[yy * w + xx] ushr 24) != 0) on++
                        xx++
                    }
                    yy++
                }
                out[y * w + x] = if (on * 2 > total) Color.WHITE else 0
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 边界：选区的轮廓带（外扩 px 减原选区） */
    fun border(mask: Bitmap, px: Int): Bitmap {
        val r = px.coerceIn(1, 50)
        val grown = grow(mask, r)
        val c = Canvas(grown)
        val p = Paint()
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        c.drawBitmap(mask, 0f, 0f, p)
        p.xfermode = null
        return grown
    }

    /** 按矩阵变换选区（缩放/平移用） */
    fun transformMask(mask: Bitmap, m: android.graphics.Matrix): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(mask, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** 色彩范围：整层里和 target 相近的像素（全局匹配，不看连通） */
    fun colorRange(src: Bitmap, target: Int, tolerance: Float): Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val limit = (tolerance.coerceIn(0f, 1f) * 320f) + 1f
        val tr = Color.red(target)
        val tg = Color.green(target)
        val tb = Color.blue(target)
        val ta = Color.alpha(target)
        var found = 0
        for (i in 0 until w * h) {
            val c = px[i]
            if (Color.alpha(c) == 0) continue
            val dr = Color.red(c) - tr
            val dg = Color.green(c) - tg
            val db = Color.blue(c) - tb
            val da = abs(Color.alpha(c) - ta) * 0.2f
            val d = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) + da
            if (d <= limit) {
                out[i] = Color.WHITE
                found++
            }
        }
        if (found == 0) return null
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 从整条边界同时漫水（多源 BFS）：把所有「和边界同色、又从边界连过来」的像素选上。
     * 选择主体靠它找背景。早先只从四个角各漫一次，渐变/被栏杆切断时走不远。
     * 复杂度 O(w*h)，一次扫完出结果。
     */
    fun borderWandMask(src: Bitmap, tolerance: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val limit = (tolerance.coerceIn(0f, 1f) * 320f) + 1f
        val limit2 = limit * limit
        val out = IntArray(w * h)
        val visited = BooleanArray(w * h)
        val refColor = IntArray(w * h)
        val q = IntArray(w * h)
        var qh = 0
        var qt = 0
        fun seed(i: Int) {
            if (visited[i]) return
            visited[i] = true
            refColor[i] = px[i]
            q[qt++] = i
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 1 until h - 1) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (qh < qt) {
            val i = q[qh++]
            out[i] = Color.WHITE
            val ref = refColor[i]
            val rr = (ref shr 16) and 0xFF
            val rg = (ref shr 8) and 0xFF
            val rb = ref and 0xFF
            val ra = (ref ushr 24) and 0xFF
            val x = i % w
            val y = i / w
            fun go(n: Int) {
                if (visited[n]) return
                if (!match(px[n], rr, rg, rb, ra, limit2)) return
                visited[n] = true
                refColor[n] = ref
                q[qt++] = n
            }
            if (x > 0) go(i - 1)
            if (x < w - 1) go(i + 1)
            if (y > 0) go(i - w)
            if (y < h - 1) go(i + w)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 只保留遮罩里最大的那一块（选择主体用：去掉零星噪点） */
    fun largestComponent(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val label = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        var bestSize = 0
        var best = -1
        var cur = 0
        for (start in px.indices) {
            if (Color.alpha(px[start]) < 128 || label[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = cur
            var size = 0
            while (sp > 0) {
                val i = stack[--sp]
                size++
                val x = i % w
                val y = i / w
                if (x > 0) {
                    val n = i - 1
                    if (label[n] < 0 && Color.alpha(px[n]) >= 128) { label[n] = cur; stack[sp++] = n }
                }
                if (x < w - 1) {
                    val n = i + 1
                    if (label[n] < 0 && Color.alpha(px[n]) >= 128) { label[n] = cur; stack[sp++] = n }
                }
                if (y > 0) {
                    val n = i - w
                    if (label[n] < 0 && Color.alpha(px[n]) >= 128) { label[n] = cur; stack[sp++] = n }
                }
                if (y < h - 1) {
                    val n = i + w
                    if (label[n] < 0 && Color.alpha(px[n]) >= 128) { label[n] = cur; stack[sp++] = n }
                }
            }
            if (size > bestSize) { bestSize = size; best = cur }
            cur++
        }
        if (best < 0) return mask
        val out = IntArray(w * h)
        for (i in px.indices) if (label[i] == best) out[i] = Color.WHITE
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(out, 0, w, 0, 0, w, h)
        return b
    }

    /** 磁性套索：在半径内找亮度变化最大的点，当作「边缘」吸附过去 */
    fun snapToEdge(px: IntArray, w: Int, h: Int, x: Int, y: Int, radius: Int): FloatArray {
        var bx = x
        var by = y
        var best = -1f
        val x0 = max(1, x - radius)
        val x1 = min(w - 2, x + radius)
        val y0 = max(1, y - radius)
        val y1 = min(h - 2, y + radius)
        for (yy in y0..y1) {
            for (xx in x0..x1) {
                val i = yy * w + xx
                val gx = luma(px[i + 1]) - luma(px[i - 1])
                val gy = luma(px[i + w]) - luma(px[i - w])
                val g = abs(gx) + abs(gy)
                val dx = (xx - x).toFloat()
                val dy = (yy - y).toFloat()
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val score = g - dist * 0.55f
                if (score > best) {
                    best = score
                    bx = xx
                    by = yy
                }
            }
        }
        return floatArrayOf(bx.toFloat(), by.toFloat())
    }

    private fun luma(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    /** 快速选择：从 target 色出发，只把笔刷圆内的相近色加进 dst */
    fun quickAdd(dst: Bitmap, src: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, target: Int, tolerance: Float) {
        val limit = (tolerance.coerceIn(0f, 1f) * 320f) + 1f
        val tr = Color.red(target)
        val tg = Color.green(target)
        val tb = Color.blue(target)
        val r2 = radius * radius
        val x0 = max(0, cx - radius)
        val x1 = min(w - 1, cx + radius)
        val y0 = max(0, cy - radius)
        val y1 = min(h - 1, cy + radius)
        val row = IntArray(x1 - x0 + 1)
        for (yy in y0..y1) {
            for (xx in x0..x1) {
                val dx = xx - cx
                val dy = yy - cy
                if (dx * dx + dy * dy > r2) continue
                val c = src[yy * w + xx]
                if (Color.alpha(c) == 0) continue
                val dr = Color.red(c) - tr
                val dg = Color.green(c) - tg
                val db = Color.blue(c) - tb
                val d = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
                if (d <= limit) {
                    row[xx - x0] = Color.WHITE
                } else {
                    row[xx - x0] = 0
                }
            }
            val c = Canvas(dst)
            for (pxx in x0..x1) {
                if (row[pxx - x0] != 0) {
                    c.drawRect(RectF(pxx.toFloat(), yy.toFloat(), pxx + 1f, yy + 1f), solidPaint())
                }
            }
        }
    }

    private fun solidPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
}
