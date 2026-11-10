package com.reveal.editor.image

import java.io.ByteArrayOutputStream

/**
 * 桌面端 的「描述符」（OSType descriptor）写入器。
 *
 * 图层样式（lfx2）、文字层的变形（warp）都是这种结构：一个类名 + 若干「键 / 类型 / 值」。
 * PSD 里所有字符串都是「u32 长度 + UTF-16BE 字符」，不是普通的 ASCII。
 */
internal class Desc(val classId: String) {

    private class Item(val key: String, val type: String, val data: ByteArray)

    private val items = ArrayList<Item>()

    fun isEmpty(): Boolean = items.isEmpty()

    fun bool(key: String, v: Boolean): Desc {
        items.add(Item(key, "bool", byteArrayOf(if (v) 1 else 0)))
        return this
    }

    fun int(key: String, v: Int): Desc {
        val b = ByteArrayOutputStream(4)
        writeS32(b, v.toLong())
        items.add(Item(key, "long", b.toByteArray()))
        return this
    }

    fun doub(key: String, v: Double): Desc {
        val b = ByteArrayOutputStream(8)
        writeF64(b, v)
        items.add(Item(key, "doub", b.toByteArray()))
        return this
    }

    fun enum(key: String, typeId: String, value: String): Desc {
        val b = ByteArrayOutputStream(32)
        writeUni(b, typeId)
        writeUni(b, value)
        items.add(Item(key, "enum", b.toByteArray()))
        return this
    }

    /** 百分比（UntF #Prc），桌面端里存 0~100 */
    fun percent(key: String, v: Double): Desc = unitF(key, "#Prc", v)

    /** 像素（UntF #Pxl） */
    fun px(key: String, v: Double): Desc = unitF(key, "#Pxl", v)

    /** 角度（UntF #Ang） */
    fun angle(key: String, v: Double): Desc = unitF(key, "#Ang", v)

    private fun unitF(key: String, unit: String, v: Double): Desc {
        val b = ByteArrayOutputStream(12)
        writeAscii(b, unit)
        writeF64(b, v)
        items.add(Item(key, "UntF", b.toByteArray()))
        return this
    }

    fun text(key: String, s: String): Desc {
        val b = ByteArrayOutputStream(s.length * 2 + 4)
        writeS32(b, s.length.toLong())
        for (ch in s) {
            b.write((ch.code shr 8) and 0xFF)
            b.write(ch.code and 0xFF)
        }
        items.add(Item(key, "TEXT", b.toByteArray()))
        return this
    }

    /** 二进制数据（tdta），文字引擎数据用它装 */
    fun tdta(key: String, data: ByteArray): Desc {
        val b = ByteArrayOutputStream(data.size + 4)
        writeS32(b, data.size.toLong())
        b.write(data, 0, data.size)
        items.add(Item(key, "tdta", b.toByteArray()))
        return this
    }

    /** 磅（UntF #Pnt） */
    fun unitPnt(key: String, v: Double): Desc = unitF(key, "#Pnt", v)

    fun obj(key: String, child: Desc): Desc {
        items.add(Item(key, "Objc", child.toBytes()))
        return this
    }

    /** RGBC 颜色（0~255） */
    fun color(key: String, argb: Int): Desc {
        val c = Desc("RGBC")
        c.doub("Rd  ", ((argb ushr 16) and 0xFF).toDouble())
        c.doub("Grn ", ((argb ushr 8) and 0xFF).toDouble())
        c.doub("Bl  ", (argb and 0xFF).toDouble())
        items.add(Item(key, "Objc", c.toBytes()))
        return this
    }

    fun toBytes(): ByteArray {
        val b = ByteArrayOutputStream(256)
        writeS32(b, 16L) // descriptor version
        writeS32(b, 0L) // 名字：空串（桌面端写的是 0）
        writeAscii(b, classId) // 类名是 4 字节 ASCII，不是 UTF-16
        writeS32(b, items.size.toLong())
        for (it in items) {
            writeUni(b, it.key)
            writeAscii(b, it.type)
            b.write(it.data, 0, it.data.size)
        }
        return b.toByteArray()
    }

    companion object {
        fun writeS32(b: ByteArrayOutputStream, v: Long) {
            b.write(((v shr 24) and 0xFF).toInt())
            b.write(((v shr 16) and 0xFF).toInt())
            b.write(((v shr 8) and 0xFF).toInt())
            b.write((v and 0xFF).toInt())
        }

        fun writeAscii(b: ByteArrayOutputStream, s: String) {
            for (ch in s) b.write(ch.code and 0xFF)
        }

        fun writeUni(b: ByteArrayOutputStream, s: String) {
            writeS32(b, s.length.toLong())
            for (ch in s) {
                b.write((ch.code shr 8) and 0xFF)
                b.write(ch.code and 0xFF)
            }
        }

        fun writeF64(b: ByteArrayOutputStream, v: Double) {
            val bits = java.lang.Double.doubleToLongBits(v)
            for (i in 7 downTo 0) b.write(((bits ushr (i * 8)) and 0xFF).toInt())
        }
    }
}

/**
 * 描述符读取器，用来把 桌面端存下来的 lfx2（图层样式）读回来。
 *
 * 只读我们要的那些键，其余类型照长度跳过——描述符里的类型有二十几种
 * （路径、别名、原始数据、列表…），全都实现没必要，能安全跳过就够了。
 */
internal class DescRead(private val b: ByteArray, var p: Int = 0) {

    fun left(): Int = b.size - p

    fun u8(): Int {
        if (p >= b.size) return 0
        return b[p++].toInt() and 0xFF
    }

    fun u32(): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or u8().toLong()
        return v
    }

    fun s32(): Int = u32().toInt()

    fun f64(): Double {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or u8().toLong()
        return java.lang.Double.longBitsToDouble(v)
    }

    fun ascii(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(u8().toChar())
        return sb.toString()
    }

    fun uni(): String {
        val n = u32()
        if (n < 0 || n > 4096) return ""
        val sb = StringBuilder(n.toInt())
        for (i in 0 until n.toInt()) {
            val hi = u8()
            val lo = u8()
            sb.append(((hi shl 8) or lo).toChar())
        }
        return sb.toString()
    }

    private fun skip(n: Long) {
        val k = n.coerceIn(0L, left().toLong())
        p += k.toInt()
    }

    /** 读一个描述符：版本 + 名字 + 类名 + 条目 */
    fun descriptor(): Map<String, Any?> {
        s32()
        uni()
        ascii(4)
        val count = u32().coerceAtMost(4096L)
        val map = LinkedHashMap<String, Any?>()
        for (i in 0 until count.toInt()) {
            val key = uni()
            val type = ascii(4)
            map[key] = value(type)
        }
        return map
    }

    private fun value(type: String): Any? = when (type) {
        "Objc" -> descriptor()
        "VlLs" -> {
            val n = u32().coerceAtMost(4096L)
            val list = ArrayList<Any?>(n.toInt())
            for (i in 0 until n.toInt()) list.add(value(ascii(4)))
            list
        }
        "doub" -> f64()
        "UntF" -> {
            ascii(4)
            f64()
        }
        "long" -> s32().toDouble()
        "bool" -> u8() != 0
        "TEXT" -> uni()
        "enum" -> {
            uni()
            uni()
        }
        "tdta" -> {
            skip(u32())
            null
        }
        "Pth " -> {
            // 路径：u16 条数，然后每条是若干「段」，段数不定；直接当空处理
            skip(2L + u32() * 26L)
            null
        }
        "alis", "UnFl", "GlbC" -> null
        "type", "GlbO" -> descriptor()
        else -> {
            // 未知类型：为了不崩，直接把剩下的丢掉
            skip(left().toLong())
            null
        }
    }

    companion object {
        fun readDescriptor(bytes: ByteArray): Map<String, Any?>? = runCatching {
            DescRead(bytes).descriptor()
        }.getOrNull()
    }
}

/** 图层效果（描边 / 投影），能写进 PSD 让 桌面端里继续调参数 */
internal class PsdEffects(
    val strokeOn: Boolean,
    val strokeColor: Int,
    val strokeWidth: Int,
    val shadowOn: Boolean,
    val shadowColor: Int,
    val shadowDX: Int,
    val shadowDY: Int,
    val shadowBlur: Int,
    /** 颜色叠加：桌面端里能直接认，所以一起写进去 */
    val colorOverlayOn: Boolean = false,
    val colorOverlayColor: Int = 0xFFFF3B30.toInt(),
    /** 外发光 / 内阴影 / 内发光 / 光泽 也写进 lfx2，桌面端里能继续调 */
    val glowOn: Boolean = false,
    val glowColor: Int = 0xCCFFF3B0.toInt(),
    val glowSize: Int = 6,
    val innerShadowOn: Boolean = false,
    val innerShadowColor: Int = 0x99000000.toInt(),
    val innerShadowDX: Int = 4,
    val innerShadowDY: Int = 4,
    val innerShadowSize: Int = 6,
    val innerGlowOn: Boolean = false,
    val innerGlowColor: Int = 0xCCFFF3B0.toInt(),
    val innerGlowSize: Int = 6,
    val satinOn: Boolean = false,
    val satinColor: Int = 0x99000000.toInt(),
    val satinSize: Int = 10,
    val satinDistance: Int = 12,
    val satinAngle: Int = 19,
)

internal object PsdFx {

    /** lfx2 块：u32 版本 0 + 描述符（类 Lefx） */
    fun encode(fx: PsdEffects): ByteArray {
        val root = Desc("Lefx")
        root.doub("Scl ", 100.0)
        if (fx.strokeOn) root.obj("FrFX", stroke(fx))
        if (fx.shadowOn) root.obj("DrSh", shadow(fx))
        if (fx.glowOn) root.obj("OrGl", outerGlow(fx))
        if (fx.innerShadowOn) root.obj("ISDr", innerShadow(fx))
        if (fx.innerGlowOn) root.obj("IrGl", innerGlow(fx))
        if (fx.colorOverlayOn) root.obj("ClrO", colorOverlay(fx))
        if (fx.satinOn) root.obj("ChFX", satin(fx))
        val body = root.toBytes()
        val out = java.io.ByteArrayOutputStream(body.size + 4)
        Desc.writeS32(out, 0L)
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    /**
     * 把 lfx2 读回 PsdEffects。桌面端分两层存：lfx2（新版，描述符）和 lmfx/lsct。
     * 我们只认 Lefx；读不出来的部分保持默认值，不影响导入。
     */
    fun decode(raw: ByteArray?): PsdEffects? {
        if (raw == null || raw.size < 8) return null
        // 前 4 字节是版本号，后面才是描述符
        val body = raw.copyOfRange(4, raw.size)
        val root = DescRead.readDescriptor(body) ?: return null
        val fr = fxMap(root["FrFX"])
        val dr = fxMap(root["DrSh"])
        val og = fxMap(root["OrGl"])
        val isd = fxMap(root["ISDr"])
        val irg = fxMap(root["IrGl"])
        val clo = fxMap(root["ClrO"])
        val chf = fxMap(root["ChFX"])
        if (fr == null && dr == null && og == null && isd == null && irg == null && clo == null && chf == null) {
            return null
        }
        val strokeColor = colorOf(fr, 0xFF000000.toInt())
        val shadowColor = colorOf(dr, 0x99000000.toInt())
        return PsdEffects(
            strokeOn = on(fr),
            strokeColor = strokeColor,
            strokeWidth = px(fr, "Sz  ", 6.0).toInt().coerceIn(1, 100),
            shadowOn = on(dr),
            shadowColor = shadowColor,
            shadowDX = 0,
            shadowDY = px(dr, "Dstn", 8.0).toInt().coerceIn(0, 200),
            shadowBlur = px(dr, "blur", 8.0).toInt().coerceIn(0, 200),
            colorOverlayOn = on(clo),
            colorOverlayColor = colorOf(clo, 0xFFFF3B30.toInt()),
            glowOn = on(og),
            glowColor = colorOf(og, 0xCCFFF3B0.toInt()),
            glowSize = px(og, "blur", 8.0).toInt().coerceIn(0, 200),
            innerShadowOn = on(isd),
            innerShadowColor = colorOf(isd, 0x99000000.toInt()),
            innerShadowDX = 0,
            innerShadowDY = px(isd, "Dstn", 4.0).toInt().coerceIn(0, 200),
            innerShadowSize = px(isd, "blur", 6.0).toInt().coerceIn(0, 200),
            innerGlowOn = on(irg),
            innerGlowColor = colorOf(irg, 0xCCFFF3B0.toInt()),
            innerGlowSize = px(irg, "blur", 6.0).toInt().coerceIn(0, 200),
            satinOn = on(chf),
            satinColor = colorOf(chf, 0x99000000.toInt()),
            satinSize = px(chf, "Sz  ", 10.0).toInt().coerceIn(0, 40),
            satinDistance = px(chf, "Dstn", 12.0).toInt().coerceIn(0, 40),
            satinAngle = angleOf(chf, 19.0),
        )
    }

    private fun fxMap(v: Any?): Map<*, *>? = v as? Map<*, *>

    private fun on(m: Map<*, *>?): Boolean {
        if (m == null) return false
        val e = m["enab"] as? Boolean ?: return true
        val show = m["show"] as? Boolean ?: true
        return e && show
    }

    private fun num(m: Map<*, *>?, key: String): Double? = (m?.get(key) as? Double)

    private fun px(m: Map<*, *>?, key: String, dflt: Double): Double = num(m, key) ?: dflt

    /** 桌面端的 lagl 是「光源角度」（180 反向），这里换算回「投影角度」 */
    private fun angleOf(m: Map<*, *>?, dflt: Double): Int {
        val a = num(m, "lagl") ?: return dflt.toInt()
        return ((360.0 - a + 19.0).toInt() % 360 - 180).coerceIn(-180, 180)
    }

    /** 颜色：Clr 是 RGBC 子描述符（Rd/Grn/Bl 0~255）+ Opct 百分比 */
    private fun colorOf(m: Map<*, *>?, dflt: Int): Int {
        val c = fxMap(m?.get("Clr ")) ?: return dflt
        val r = (num(c, "Rd  ") ?: 255.0).toInt().coerceIn(0, 255)
        val g = (num(c, "Grn ") ?: 255.0).toInt().coerceIn(0, 255)
        val b = (num(c, "Bl  ") ?: 255.0).toInt().coerceIn(0, 255)
        val opct = num(m, "Opct") ?: 100.0
        val a = (opct / 100.0 * 255.0).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun stroke(fx: PsdEffects): Desc {
        val d = Desc("FrFX")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Styl", "FStl", "OutF")     // 外描边
        d.enum("PntT", "FrFl", "SClr")    // 实色
        d.enum("Md  ", "BlnM", "Nrml")
        d.percent("Opct", alpha(fx.strokeColor) * 100.0 / 255.0)
        d.px("Sz  ", fx.strokeWidth.toDouble())
        d.color("Clr ", fx.strokeColor)
        d.bool("overprint", false)
        return d
    }

    private fun shadow(fx: PsdEffects): Desc {
        val d = Desc("DrSh")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Md  ", "BlnM", "Mltp")
        d.color("Clr ", fx.shadowColor)
        d.percent("Opct", alpha(fx.shadowColor) * 100.0 / 255.0)
        d.bool("uglg", true)
        d.angle("lagl", 120.0)
        d.px("Dstn", fx.shadowDY.toDouble().coerceAtLeast(0.0))
        d.px("Ckmt", 0.0)
        d.px("blur", fx.shadowBlur.toDouble())
        d.percent("Nose", 0.0)
        d.bool("AntA", true)
        val trn = Desc("TrnS")
        trn.text("Nm  ", "")
        trn.px("Ckmt", 0.0)
        d.obj("TrnS", trn)
        d.bool("layerConceals", true)
        return d
    }

    /** 外发光：和投影同一套字段，只是没有位移、颜色模式是正常 */
    private fun outerGlow(fx: PsdEffects): Desc {
        val d = Desc("OrGl")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Md  ", "BlnM", "Nrml")
        d.color("Clr ", fx.glowColor)
        d.percent("Opct", alpha(fx.glowColor) * 100.0 / 255.0)
        d.bool("uglg", true)
        d.angle("lagl", 120.0)
        d.px("Dstn", 0.0)
        d.px("Ckmt", 0.0)
        d.px("blur", fx.glowSize.toDouble())
        d.percent("Nose", 0.0)
        d.bool("AntA", true)
        val trn = Desc("TrnS")
        trn.text("Nm  ", "")
        trn.px("Ckmt", 0.0)
        d.obj("TrnS", trn)
        return d
    }

    /** 内阴影 */
    private fun innerShadow(fx: PsdEffects): Desc {
        val d = Desc("ISDr")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Md  ", "BlnM", "Mltp")
        d.color("Clr ", fx.innerShadowColor)
        d.percent("Opct", alpha(fx.innerShadowColor) * 100.0 / 255.0)
        d.bool("uglg", false)
        d.angle("lagl", 120.0)
        d.px("Dstn", fx.innerShadowDY.toDouble())
        d.px("Ckmt", 0.0)
        d.px("blur", fx.innerShadowSize.toDouble())
        d.percent("Nose", 0.0)
        d.bool("AntA", true)
        val trn = Desc("TrnS")
        trn.text("Nm  ", "")
        trn.px("Ckmt", 0.0)
        d.obj("TrnS", trn)
        return d
    }

    /** 内发光 */
    private fun innerGlow(fx: PsdEffects): Desc {
        val d = Desc("IrGl")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Md  ", "BlnM", "Nrml")
        d.color("Clr ", fx.innerGlowColor)
        d.percent("Opct", alpha(fx.innerGlowColor) * 100.0 / 255.0)
        d.bool("uglg", false)
        d.angle("lagl", 120.0)
        d.px("Dstn", 0.0)
        d.px("Ckmt", 0.0)
        d.px("blur", fx.innerGlowSize.toDouble())
        d.percent("Nose", 0.0)
        d.bool("AntA", true)
        d.enum("Srce", "Stch", "SrcE")
        val trn = Desc("TrnS")
        trn.text("Nm  ", "")
        trn.px("Ckmt", 0.0)
        d.obj("TrnS", trn)
        return d
    }

    /** 光泽 */
    private fun satin(fx: PsdEffects): Desc {
        val d = Desc("ChFX")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Md  ", "BlnM", "Mltp")
        d.color("Clr ", fx.satinColor)
        d.percent("Opct", alpha(fx.satinColor) * 100.0 / 255.0)
        d.angle("lagl", 360.0 - fx.satinAngle + 19.0)
        d.px("Dstn", fx.satinDistance.toDouble())
        d.px("Sz  ", fx.satinSize.toDouble())
        d.bool("InvT", true)
        d.bool("AntA", true)
        return d
    }

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    /** 颜色叠加（桌面端描述符键名 ClrO） */
    private fun colorOverlay(fx: PsdEffects): Desc {
        val d = Desc("ClrO")
        d.bool("enab", true)
        d.bool("present", true)
        d.bool("show", true)
        d.enum("Md  ", "BlnM", "Nrml")
        d.color("Clr ", fx.colorOverlayColor)
        d.percent("Opct", alpha(fx.colorOverlayColor) * 100.0 / 255.0)
        d.bool("uglg", false)
        d.bool("AntA", true)
        return d
    }
}