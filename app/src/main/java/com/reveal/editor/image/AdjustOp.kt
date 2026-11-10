package com.reveal.editor.image

import android.graphics.ColorMatrix
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调整层参数（桌面端的「调整图层」）。
 *
 * 三种都按 桌面端原生的附加图层信息块存，电脑 桌面端打开能双击再改：
 *  - [KIND_BRIGHT_CONTRAST] → `brit`
 *  - [KIND_HUE_SAT] → `hue2`
 *  - [KIND_LEVELS] → `levl`
 *
 * 块结构全部照 ag-psd 的读写实现抄（那是唯一被验证过的公开实现）：
 *  brit:  i16 brightness, i16 contrast, i16 mean(=127), u8 lab, u8 0   → 8 字节，**没有版本号**
 *  hue2:  u16 version(=2), u8 colorize, u8 0, i16×3 colorization,
 *         i16×3 master, 6×i16×3 分色段（红黄绿青蓝洋红）             → 100 字节
 *  levl:  u16 version(=2), 29×10 字节通道记录, "Lvls", u16 3, u16 62, 33×10 字节通道记录,
 *         末尾 2 字节补零                                          → 632 字节
 *         （桌面端自己写 632；写 630 时 桌面端2026 直接报「打开选项不正确」拒开）
 *
 * 所有参数都用 桌面端的整数刻度：亮度/对比度 -100..100，色相 -180..180，
 * 饱和度/明度 -100..100，色阶 0..255。这样存进 PSD 再读回来不会有浮点漂移。
 */

/** 渐变映射中间的一个色标：pos 0~1（0 = 最黑，1 = 最白），color 是 0xRRGGBB */
data class GradStop(val pos: Float, val color: Int) {
    fun colorLo(): Int = color and 0xFFFFFF
}
data class AdjustOp(
    val kind: Int = KIND_BRIGHT_CONTRAST,
    /** 亮度 -100..100（桌面端亮度/对比度） */
    val brightness: Int = 0,
    /** 对比度 -100..100（桌面端亮度/对比度） */
    val contrast: Int = 0,
    /** 色相 -180..180（桌面端色相/饱和度） */
    val hue: Int = 0,
    /** 饱和度 -100..100 */
    val saturation: Int = 0,
    /** 明度 -100..100 */
    val lightness: Int = 0,
    /** 色阶：下标 0=RGB 1=红 2=绿 3=蓝，空表示全默认 */
    val levels: List<Levels.Params> = emptyList(),
    /** 渐变映射：暗部颜色（ARGB） */
    val gmLo: Int = 0xFF1B2A46.toInt(),
    /** 渐变映射：亮部颜色（ARGB） */
    val gmHi: Int = 0xFFE8C27A.toInt(),
    /** 渐变映射的中间色标（位置 0~1 + 颜色），升序；空表示只有两端两色 */
    val gmStops: List<GradStop> = emptyList(),
    /** 平滑度 0~100：0 = 分段线性，100 = 缓入缓出（smoothstep） */
    val gmSmooth: Int = 0,
    /** 反向：亮部用左端色、暗部用右端色 */
    val gmReverse: Boolean = false,
    /** 调整组：一个调整层里叠多个效果（KIND_CONTAINER 时用） */
    val children: List<AdjustOp> = emptyList(),
) {

    /** 把嵌套的调整组摊平成一层效果序列（渲染和导出都用它） */
    fun flatten(): List<AdjustOp> = when {
        kind == KIND_CONTAINER -> children.flatMap { it.flatten() }
        else -> listOf(this)
    }

    fun isNeutral(): Boolean = when (kind) {
        KIND_BRIGHT_CONTRAST -> brightness == 0 && contrast == 0
        KIND_HUE_SAT -> hue == 0 && saturation == 0 && lightness == 0
        KIND_LEVELS -> levelsOrDefault().all { it.isNeutral() }
        // 渐变映射只要可见就一定生效（两端同色时才是空的）
        KIND_GRADIENT_MAP -> (gmLo and 0xFFFFFF) == (gmHi and 0xFFFFFF) && gmStops.all { it.colorLo() == gmLo }
        // 调整组：子项全是中性才算空
        KIND_CONTAINER -> children.all { it.isNeutral() }
        else -> true
    }

    /** 面板上显示的名字 */
    fun title(): String = when (kind) {
        KIND_HUE_SAT -> "色相/饱和度"
        KIND_LEVELS -> "色阶"
        KIND_GRADIENT_MAP -> "渐变映射"
        KIND_CONTAINER -> "调整组（" + children.size + " 项）"
        else -> "亮度/对比度"
    }

    /**
     * 渐变映射的 256 级查表：按亮度取色。
     * 有多色标时按位置分段线性插值（0/1 两端直接用 gmLo/gmHi）。
     */
    fun gradLut(): IntArray {
        val stops = if (gmStops.isEmpty()) emptyList()
        else gmStops.sortedBy { it.pos }
        val smooth = gmSmooth.coerceIn(0, 100) / 100f
        // 平滑度：在 0 与 smoothstep(t) 之间插值；反向：亮部取左端色
        fun shape(t: Float): Float {
            var x = if (gmReverse) 1f - t else t
            if (smooth > 0f) {
                val sm = x * x * (3f - 2f * x)
                x += (sm - x) * smooth
            }
            return x.coerceIn(0f, 1f)
        }
        fun colorAt(t: Float): Int {
            if (stops.isEmpty()) {
                val r1 = (gmLo shr 16) and 0xFF; val g1 = (gmLo shr 8) and 0xFF; val b1 = gmLo and 0xFF
                val r2 = (gmHi shr 16) and 0xFF; val g2 = (gmHi shr 8) and 0xFF; val b2 = gmHi and 0xFF
                val r = (r1 + (r2 - r1) * t).toInt().coerceIn(0, 255)
                val g = (g1 + (g2 - g1) * t).toInt().coerceIn(0, 255)
                val b = (b1 + (b2 - b1) * t).toInt().coerceIn(0, 255)
                return (r shl 16) or (g shl 8) or b
            }
            // 首尾用 gmLo / gmHi 兜住，中间按色标插值
            var prevPos = 0f
            var prev = gmLo and 0xFFFFFF
            for (s in stops) {
                val pos = s.pos.coerceIn(0f, 1f)
                val col = s.colorLo()
                if (t <= pos) {
                    val span = (pos - prevPos).coerceAtLeast(1e-4f)
                    val k = ((t - prevPos) / span).coerceIn(0f, 1f)
                    return lerpRgb(prev, col, k)
                }
                prevPos = pos
                prev = col
            }
            return lerpRgb(prev, gmHi and 0xFFFFFF, ((t - prevPos) / (1f - prevPos).coerceAtLeast(1e-4f)).coerceIn(0f, 1f))
        }
        return IntArray(256) { i -> (0xFF shl 24) or colorAt(shape(i / 255f)) }
    }

    /** 一句话摘要，给图层列表用 */
    fun summary(): String = when (kind) {
        KIND_HUE_SAT -> "色相 $hue° · 饱和 $saturation · 明度 $lightness"
        KIND_LEVELS -> {
            val p = levelsOrDefault()[0]
            if (p.isNeutral()) "色阶（默认）"
            else "输入 ${p.inBlack}/$p.inWhite · γ ${String.format("%.2f", p.gamma)}"
        }
        KIND_GRADIENT_MAP -> "暗部 #" + String.format("%06X", gmLo and 0xFFFFFF) +
            " → 亮部 #" + String.format("%06X", gmHi and 0xFFFFFF) +
            (if (gmStops.isNotEmpty()) " · 中间 ${gmStops.size} 色标" else "")
        KIND_CONTAINER -> children.joinToString(" + ") { it.title() }
        else -> "亮度 $brightness · 对比度 $contrast"
    }

    /** 转成 ColorMatrix；色阶走查表，这里返回单位矩阵 */
    fun matrix(): ColorMatrix = when (kind) {
        KIND_HUE_SAT -> hueSatMatrix(hue, saturation, lightness)
        // 渐变映射是查表，不能压成矩阵；渲染路径单独处理（见 applyGradientToTarget）
        KIND_GRADIENT_MAP -> ColorMatrix()
        else -> brightContrastMatrix(brightness, contrast)
    }

    /** 色阶四组参数（RGB/红/绿/蓝），缺的补默认 */
    fun levelsOrDefault(): List<Levels.Params> = List(4) { i ->
        levels.getOrNull(i) ?: Levels.Params(channel = if (i == 0) Levels.CHANNEL_RGB else i - 1)
    }

    /** 换掉某一通道的色阶参数 */
    fun withChannel(index: Int, p: Levels.Params): AdjustOp {
        val list = levelsOrDefault().toMutableList()
        list[index.coerceIn(0, 3)] = p
        return copy(levels = list)
    }

    fun copyWith(
        brightness: Int = this.brightness,
        contrast: Int = this.contrast,
        hue: Int = this.hue,
        saturation: Int = this.saturation,
        lightness: Int = this.lightness,
    ): AdjustOp = AdjustOp(
        kind = kind,
        brightness = brightness,
        contrast = contrast,
        hue = hue,
        saturation = saturation,
        lightness = lightness,
        levels = levels,
        gmLo = gmLo,
        gmHi = gmHi,
        gmStops = gmStops,
        children = children,
    )

    /** 换掉渐变映射的色标（多色标面板用） */
    fun withStops(lo: Int, hi: Int, stops: List<GradStop>) =
        copy(gmLo = lo, gmHi = hi, gmStops = stops.sortedBy { it.pos })

    /** 调整组：加一个子效果（同类已有就替换） */
    fun withChild(child: AdjustOp): AdjustOp {
        val list = children.toMutableList()
        val at = list.indexOfFirst { it.kind == child.kind }
        if (at >= 0) list[at] = child else list.add(child)
        return copy(kind = KIND_CONTAINER, children = list)
    }

    /** 调整组：删掉某一项 */
    fun withoutChild(index: Int): AdjustOp {
        val list = children.toMutableList()
        if (index in list.indices) list.removeAt(index)
        return if (list.isEmpty()) AdjustOp(kind = KIND_CONTAINER, children = emptyList())
        else copy(kind = KIND_CONTAINER, children = list)
    }

    companion object {
        const val KIND_BRIGHT_CONTRAST = 0
        const val KIND_HUE_SAT = 1
        const val KIND_LEVELS = 2
        const val KIND_GRADIENT_MAP = 3
        const val KIND_CONTAINER = 4

        /** 两个 0xRRGGBB 颜色按 k 线性插值 */
        fun lerpRgb(a: Int, b: Int, k: Float): Int {
            val kk = k.coerceIn(0f, 1f)
            val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
            val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
            val r = (ar + (br - ar) * kk).toInt().coerceIn(0, 255)
            val g = (ag + (bg - ag) * kk).toInt().coerceIn(0, 255)
            val bl = (ab + (bb - ab) * kk).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or bl
        }

        /** 亮度/对比度：R' = k·R + off，k 由对比度决定，off 由亮度决定 */
        fun brightContrastMatrix(brightness: Int, contrast: Int): ColorMatrix {
            val b = brightness.coerceIn(-150, 150)
            val c = contrast.coerceIn(-50, 100)
            // 对比度以中灰 127.5 为支点缩放；亮度是加法偏移
            val k = Math.pow(2.0, c / 100.0).toFloat()
            val off = 127.5f * (1f - k) + b * 1.275f
            return ColorMatrix(
                floatArrayOf(
                    k, 0f, 0f, 0f, off,
                    0f, k, 0f, 0f, off,
                    0f, 0f, k, 0f, off,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }

        /** 色相旋转 × 饱和度，矩阵按 S×H 相乘（之前逐行插值算错了） */
        fun hueSatMatrix(hue: Int, saturation: Int, lightness: Int): ColorMatrix {
            val h = Math.toRadians(hue.coerceIn(-180, 180).toDouble())
            val sat = 1.0 + saturation.coerceIn(-100, 100) / 100.0
            val cosV = Math.cos(h)
            val sinV = Math.sin(h)
            val lr = 0.213
            val lg = 0.715
            val lb = 0.072
            // H：标准色相旋转矩阵
            val hm = doubleArrayOf(
                lr + cosV * (1 - lr) + sinV * (-lr), lg + cosV * (-lg) + sinV * (-lg), lb + cosV * (-lb) + sinV * (1 - lb),
                lr + cosV * (-lr) + sinV * 0.143, lg + cosV * (1 - lg) + sinV * 0.140, lb + cosV * (-lb) + sinV * (-0.283),
                lr + cosV * (-lr) + sinV * (-(1 - lr)), lg + cosV * (-lg) + sinV * lg, lb + cosV * (1 - lb) + sinV * lb,
            )
            // S：标准饱和度矩阵
            val sm = doubleArrayOf(
                lr + sat * (1 - lr), lg * (1 - sat), lb * (1 - sat),
                lr * (1 - sat), lg + sat * (1 - lg), lb * (1 - sat),
                lr * (1 - sat), lg * (1 - sat), lb + sat * (1 - lb),
            )
            val m = DoubleArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    var acc = 0.0
                    for (k in 0 until 3) acc += sm[r * 3 + k] * hm[k * 3 + c]
                    m[r * 3 + c] = acc
                }
            }
            val add = lightness.coerceIn(-100, 100) * 1.27f
            return ColorMatrix(
                floatArrayOf(
                    m[0].toFloat(), m[1].toFloat(), m[2].toFloat(), 0f, add,
                    m[3].toFloat(), m[4].toFloat(), m[5].toFloat(), 0f, add,
                    m[6].toFloat(), m[7].toFloat(), m[8].toFloat(), 0f, add,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }
        /** 存工程用：参数写进 manifest，读回来还能继续调 */
        fun toJson(op: AdjustOp): JSONObject {
            val o = JSONObject()
            o.put("kind", op.kind)
            o.put("brightness", op.brightness)
            o.put("contrast", op.contrast)
            o.put("hue", op.hue)
            o.put("saturation", op.saturation)
            o.put("lightness", op.lightness)
            val arr = JSONArray()
            for (p in op.levelsOrDefault()) {
                val c = JSONArray()
                c.put(p.inBlack); c.put(p.inWhite); c.put(p.outBlack); c.put(p.outWhite)
                c.put(p.gamma.toDouble())
                arr.put(c)
            }
            o.put("levels", arr)
            o.put("gmLo", op.gmLo)
            o.put("gmHi", op.gmHi)
            return o
        }

        fun fromJson(o: JSONObject?): AdjustOp? {
            if (o == null) return null
            val list = ArrayList<Levels.Params>(4)
            val arr = o.optJSONArray("levels")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONArray(i) ?: continue
                    list.add(
                        Levels.Params(
                            channel = if (i > 0) i - 1 else Levels.CHANNEL_RGB,
                            inBlack = c.optInt(0, 0),
                            inWhite = c.optInt(1, 255),
                            outBlack = c.optInt(2, 0),
                            outWhite = c.optInt(3, 255),
                            gamma = c.optDouble(4, 1.0).toFloat(),
                        ),
                    )
                }
            }
            return AdjustOp(
                kind = o.optInt("kind", KIND_BRIGHT_CONTRAST),
                brightness = o.optInt("brightness", 0),
                contrast = o.optInt("contrast", 0),
                hue = o.optInt("hue", 0),
                saturation = o.optInt("saturation", 0),
                lightness = o.optInt("lightness", 0),
                levels = list,
                gmLo = o.optInt("gmLo", 0xFF1B2A46.toInt()),
                gmHi = o.optInt("gmHi", 0xFFE8C27A.toInt()),
            )
        }

        /** 这个调整层对应 PSD 的哪个附加图层信息块键名 */
        fun blockKey(kind: Int): String = when (kind) {
            KIND_HUE_SAT -> "hue2"
            KIND_LEVELS -> "levl"
            else -> "brit"
        }

        /** 编码成 PSD 附加图层信息块的数据段（结构见类注释） */
        fun encode(op: AdjustOp): ByteArray = when (op.kind) {
            KIND_HUE_SAT -> ByteArray(100).also { b ->
                // 按 桌面端的 hue2 结构：version(2) + colorize(1) + pad(1)
                //   + colorization 3 × i16 + master 3 × i16
                //   + 六个分色段（红黄绿青蓝洋红）每段 7 × i16（a/b/c/d/hue/sat/light）
                putU16(b, 0, 2)
                b[2] = 0
                b[3] = 0
                putI16(b, 4, 0)
                putI16(b, 6, 0)
                putI16(b, 8, 0)
                putI16(b, 10, op.hue.coerceIn(-180, 180))
                putI16(b, 12, op.saturation.coerceIn(-100, 100))
                putI16(b, 14, op.lightness.coerceIn(-100, 100))
                // 16..99 是六个分色段，全 0 表示不调
            }
            KIND_LEVELS -> ByteArray(632).also { b ->
                // version(2) + 29 条 10 字节记录 + 'Lvls' + version(3) + 总数(62) + 后 33 条记录 + 末尾 2 字节补零
                putU16(b, 0, 2)
                val list = op.levelsOrDefault()
                fun writeRec(at: Int, ch: Int) {
                    val p = if (ch <= 3) list[ch] else Levels.Params()
                    putI16(b, at, p.inBlack)
                    putI16(b, at + 2, p.inWhite)
                    putI16(b, at + 4, p.outBlack)
                    putI16(b, at + 6, p.outWhite)
                    putI16(b, at + 8, Math.round(p.gamma * 100f))
                }
                for (i in 0 until 29) writeRec(2 + i * 10, i)
                val sig = 2 + 29 * 10
                b[sig] = 'L'.code.toByte()
                b[sig + 1] = 'v'.code.toByte()
                b[sig + 2] = 'l'.code.toByte()
                b[sig + 3] = 's'.code.toByte()
                putU16(b, sig + 4, 3)
                putU16(b, sig + 6, 62)
                for (i in 29 until 62) writeRec(sig + 8 + (i - 29) * 10, i)
            }
            else -> ByteArray(8).also { b ->
                // brit：亮度 i16 + 对比度 i16 + mean i16(127) + labColorOnly + pad（没有版本号）
                putI16(b, 0, op.brightness.coerceIn(-150, 150))
                putI16(b, 2, op.contrast.coerceIn(-50, 100))
                putI16(b, 4, 127)
                b[6] = 0
                b[7] = 0
            }
        }

        fun decode(key: String, data: ByteArray): AdjustOp? = when {
            key == "brit" && data.size >= 8 -> AdjustOp(
                kind = KIND_BRIGHT_CONTRAST,
                brightness = readI16(data, 0),
                contrast = readI16(data, 2),
            )
            // 早期我们自己写的 6 字节版本（u16 版本 + 两个 i8），只为了读回旧文件
            key == "brit" && data.size >= 4 -> AdjustOp(
                kind = KIND_BRIGHT_CONTRAST,
                brightness = data[2].toInt(),
                contrast = data[3].toInt(),
            )
            key == "hue2" && data.size >= 16 && ((data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)) >= 2 ->
                AdjustOp(
                    kind = KIND_HUE_SAT,
                    hue = readI16(data, 10),
                    saturation = readI16(data, 12),
                    lightness = readI16(data, 14),
                )
            key == "hue2" && data.size >= 10 -> AdjustOp(
                kind = KIND_HUE_SAT,
                hue = readI16(data, 4),
                saturation = readI16(data, 6),
                lightness = readI16(data, 8),
            )
            key == "levl" && data.size >= 12 -> {
                val list = List(4) { i ->
                    val at = 2 + i * 10
                    if (at + 10 > data.size) Levels.Params(channel = if (i == 0) Levels.CHANNEL_RGB else i - 1)
                    else Levels.Params(
                        channel = if (i == 0) Levels.CHANNEL_RGB else i - 1,
                        inBlack = readI16(data, at).coerceIn(0, 255),
                        inWhite = readI16(data, at + 2).coerceIn(0, 255),
                        outBlack = readI16(data, at + 4).coerceIn(0, 255),
                        outWhite = readI16(data, at + 6).coerceIn(0, 255),
                        gamma = (readI16(data, at + 8) / 100f).coerceIn(0.1f, 10f),
                    )
                }
                AdjustOp(kind = KIND_LEVELS, levels = list)
            }
            else -> null
        }

        private fun putU16(b: ByteArray, at: Int, v: Int) {
            b[at] = ((v ushr 8) and 0xFF).toByte()
            b[at + 1] = (v and 0xFF).toByte()
        }

        private fun putI16(b: ByteArray, at: Int, v: Int) {
            putU16(b, at, v and 0xFFFF)
        }

        private fun readI16(b: ByteArray, at: Int): Int {
            val v = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
            return if (v >= 0x8000) v - 0x10000 else v
        }

    }
}