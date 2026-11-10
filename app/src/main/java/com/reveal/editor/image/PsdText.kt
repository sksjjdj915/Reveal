package com.reveal.editor.image

import java.io.ByteArrayOutputStream

/** 文字层信息（够 桌面端里继续编辑字、字号、颜色、字体） */
internal class PsdTextInfo(
    var content: String,
    var size: Float,
    var color: Int,
    /** 桌面端里显示的字体名；空 = 用默认的 MyriadPro-Regular */
    var font: String = "",
)

/**
 * 文字层（TySh）写入。
 *
 * 桌面端认不认一个文字层，全看这里写的「文字引擎数据」（EngineData）结构对不对。
 * 结构照 ag-psd 的实现来：EngineDict（Editor/ParagraphRun/StyleRun/Rendered）+ ResourceDict（字体表、样式表）。
 *
 * 注意：引擎数据里**键是 ASCII、字符串值才是 UTF-16BE**，两者不能混着当字符串拼。
 */
internal object PsdText {

    private const val FONT_NAME = "MyriadPro-Regular"

    /** 桌面端字体名 → Android 家族名（打开 PSD 里的文字层时反查回来） */
    fun familyForPsName(ps: String): String = BundledFonts.keyForPsName(ps) ?: when {
        ps.isEmpty() -> ""
        ps.startsWith("ArialNarrow") -> "sans-serif-condensed"
        ps.startsWith("Arial") || ps.startsWith("Helvetica") || ps.contains("HanSans") -> "sans-serif"
        ps.startsWith("SimSun") || ps.startsWith("Times") || ps.startsWith("Songti") || ps.contains("Serif") -> "serif"
        ps.startsWith("Courier") || ps.contains("Mono") -> "monospace"
        ps.startsWith("Comic") || ps.contains("Script") -> "cursive"
        else -> ""
    }

    /** Android 家族名 → 桌面端里的字体名（桌面端里没有这个字体会自动替换，但名字至少是对的） */
    fun psFontName(family: String): String = BundledFonts.psNameOf(family) ?: when (family) {
        "sans-serif" -> "ArialMT"
        "serif" -> "SimSun"
        "monospace" -> "CourierNewPSMT"
        "cursive" -> "ComicSansMS"
        "sans-serif-condensed" -> "ArialNarrow"
        else -> FONT_NAME
    }

    fun encodeTySh(info: PsdTextInfo, rect: IntArray, docW: Int, docH: Int): ByteArray {
        val text = info.content.replace("\r\n", "\r").replace("\n", "\r") + "\r"
        val fontName = if (info.font.isEmpty()) FONT_NAME else psFontName(info.font)
        val engine = EngineBuilder(text, info.size, info.color, fontName).build()

        val b = ByteArrayOutputStream(engine.size + 512)
        u16(b, 1)                    // version
        // transform: xx, xy, yx, yy, tx, ty
        f64(b, 1.0); f64(b, 0.0); f64(b, 0.0); f64(b, 1.0)
        f64(b, rect[0].toDouble()); f64(b, rect[1].toDouble())
        u16(b, 50)                   // text version

        // 桌面端真正的写法：紧跟一个类名 TxLr 的描述符，引擎数据装在它的 EngineData 里
        val d = Desc("TxLr")
        d.text("Txt ", text)
        d.enum("textGridding", "textGridding", "None")
        d.enum("Ornt", "Ornt", "Hrzn")
        d.enum("AntA", "Annt", "Annt")
        d.int("TextIndex", 0)
        d.tdta("EngineData", engine)
        d.unitPnt("Left", rect[0].toDouble())
        d.unitPnt("Top", rect[1].toDouble())
        d.unitPnt("Rght", rect[2].toDouble())
        d.unitPnt("Btom", rect[3].toDouble())
        val db = d.toBytes()
        b.write(db, 0, db.size)

        u16(b, 1)                    // warp version
        val warp = Desc("warp")
        warp.enum("warpStyle", "warp", "warpNone")
        warp.doub("warpValue", 0.0)
        warp.doub("warpPerspective", 0.0)
        warp.doub("warpPerspectiveOther", 0.0)
        warp.enum("warpRotate", "Ornt", "Hrzn")
        val wb = warp.toBytes()
        b.write(wb, 0, wb.size)

        // 文字框边界：4 个 float32（不是 double）
        f32(b, rect[0].toFloat()); f32(b, rect[1].toFloat())
        f32(b, rect[2].toFloat()); f32(b, rect[3].toFloat())
        return b.toByteArray()
    }

    /** 从 TySh 块里把文字抠出来（给「桌面端做的文字层再打开」用） */
    fun decodeTySh(block: ByteArray): PsdTextInfo? {
        return try {
            var i = 2 + 48 + 2
            if (block.size < i + 4) return null
            // 描述符：u32 版本(16) + 名字(u32 长度 + UTF-16) + 类名(4 字节) + u32 项数
            i += 4
            val nameLen = readS32(block, i); i += 4 + nameLen * 2
            i += 4
            val count = readS32(block, i); i += 4
            var txt: String? = null
            var engine: ByteArray? = null
            for (k in 0 until count) {
                if (i + 4 > block.size) break
                val keyLen = readS32(block, i); i += 4
                val key = readUtf16(block, i, keyLen)
                i += keyLen.coerceAtLeast(0) * 2
                if (i + 4 > block.size) break
                val type = String(block, i, 4, Charsets.US_ASCII); i += 4
                when (type) {
                    "TEXT" -> {
                        val n = readS32(block, i); i += 4
                        val sb = StringBuilder()
                        for (c in 0 until n) {
                            if (i + 1 >= block.size) break
                            val ch = (((block[i].toInt() and 0xFF) shl 8) or (block[i + 1].toInt() and 0xFF)).toChar()
                            if (ch.code != 0) sb.append(ch)
                            i += 2
                        }
                        if (key == "Txt ") txt = sb.toString()
                    }
                    "tdta" -> {
                        val n = readS32(block, i); i += 4
                        if (key == "EngineData") engine = block.copyOfRange(i, (i + n).coerceAtMost(block.size))
                        i += n
                    }
                    "long" -> i += 4
                    "doub" -> i += 8
                    "UntF" -> i += 12
                    "bool" -> i += 1
                    "enum" -> {
                        val a = readS32(block, i); i += 4 + a * 2
                        val b2 = readS32(block, i); i += 4 + b2 * 2
                    }
                    "Objc", "GlbO" -> {
                        val r = skipDesc(block, i)
                        i = r
                    }
                    else -> return null
                }
            }
            val raw = txt ?: engine?.let { readEngineText(it) } ?: return null
            val size = engine?.let { readFontSize(it) } ?: 0f
            val font = engine?.let { readFontName(it) } ?: ""
            PsdTextInfo(raw.replace("\r\n", "\n").replace('\r', '\n'), size, 0xFF000000.toInt(), font)
        } catch (t: Throwable) {
            android.util.Log.e("PsdText", "decodeTySh 失败: " + android.util.Log.getStackTraceString(t))
            null
        }
    }

    private fun readUtf16(b: ByteArray, at: Int, chars: Int): String {
        val sb = StringBuilder()
        for (c in 0 until chars.coerceAtLeast(0)) {
            val o = at + c * 2
            if (o + 1 >= b.size) break
            val ch = (((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)).toChar()
            if (ch.code != 0) sb.append(ch)
        }
        return sb.toString()
    }

    private fun readS32(b: ByteArray, at: Int): Int {
        if (at + 3 >= b.size) return 0
        return ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }

    /** 跳过一段没处理的描述符，返回其后的位置 */
    private fun skipDesc(b: ByteArray, from: Int): Int {
        var i = from
        i += 4 // version
        val nameLen = readS32(b, i); i += 4 + nameLen * 2
        i += 4 // class
        val count = readS32(b, i); i += 4
        for (k in 0 until count) {
            val keyLen = readS32(b, i); i += 4 + keyLen * 2
            val type = String(b, i, 4, Charsets.US_ASCII); i += 4
            when (type) {
                "TEXT" -> { val n = readS32(b, i); i += 4 + n * 2 }
                "tdta" -> { val n = readS32(b, i); i += 4 + n }
                "long" -> i += 4
                "doub" -> i += 8
                "UntF" -> i += 12
                "bool" -> i += 1
                "enum" -> { val a = readS32(b, i); i += 4 + a * 2; val c = readS32(b, i); i += 4 + c * 2 }
                "Objc", "GlbO" -> { i = skipDesc(b, i) }
                else -> return i
            }
        }
        return i
    }

    private fun readEngineText(engine: ByteArray): String? {
        val key = "/Text ".toByteArray(Charsets.US_ASCII)
        val at = indexOf(engine, key) ?: return null
        var i = at + key.size
        while (i < engine.size && (engine[i].toInt() and 0xFF).toChar() != '(') i++
        if (i >= engine.size) return null
        i++
        val sb = StringBuilder()
        val utf16 = engine.size > i + 1 && (engine[i].toInt() and 0xFF) == 0xFE && (engine[i + 1].toInt() and 0xFF) == 0xFF
        if (utf16) i += 2
        while (i < engine.size && (engine[i].toInt() and 0xFF).toChar() != ')') {
            if ((engine[i].toInt() and 0xFF) == 0x5C && i + 1 < engine.size) i++
            if (utf16 && i + 1 < engine.size) {
                val ch = (((engine[i].toInt() and 0xFF) shl 8) or (engine[i + 1].toInt() and 0xFF)).toChar()
                if (ch.code != 0) sb.append(ch)
                i += 2
            } else {
                val ch = (engine[i].toInt() and 0xFF).toChar()
                if (ch.code != 0) sb.append(ch)
                i++
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun readFontSize(engine: ByteArray): Float {
        val key = "/FontSize ".toByteArray(Charsets.US_ASCII)
        val at = indexOf(engine, key) ?: return 0f
        var i = at + key.size
        val sb = StringBuilder()
        while (i < engine.size) {
            val c = (engine[i].toInt() and 0xFF).toChar()
            if (c.isWhitespace() && sb.isNotEmpty()) break
            if (!c.isWhitespace() && c != ')') sb.append(c)
            i++
        }
        return sb.toString().toFloatOrNull() ?: 0f
    }

    /** 从引擎数据里读字体名：FontSet 里第一个不是 AdobeInvisFont 的 /Name */
    private fun readFontName(engine: ByteArray): String {
        val setAt = indexOf(engine, "/FontSet".toByteArray(Charsets.US_ASCII)) ?: return ""
        // 从 FontSet 往后找 "/Name " 的值，跳过 AdobeInvisFont
        var i = setAt
        val key = "/Name ".toByteArray(Charsets.US_ASCII)
        repeat(8) {
            val at = indexOfFrom(engine, key, i) ?: return ""
            var p = at + key.size
            val sb = StringBuilder()
            while (p < engine.size) {
                val c = (engine[p].toInt() and 0xFF).toChar()
                if (c.isWhitespace() && sb.isNotEmpty()) break
                if (!c.isWhitespace() && c != ')') sb.append(c)
                p++
            }
            i = p
            val name = sb.toString()
            if (name.isNotEmpty() && name != "AdobeInvisFont") return name
        }
        return ""
    }

    private fun indexOfFrom(data: ByteArray, key: ByteArray, from: Int): Int? {
        outer@ for (i in from..data.size - key.size) {
            for (k in key.indices) if (data[i + k] != key[k]) continue@outer
            return i
        }
        return null
    }

    private fun indexOf(data: ByteArray, key: ByteArray): Int? {
        outer@ for (i in 0..data.size - key.size) {
            return i
        }
        return null
    }

    // ---------------------------------------------------------------- 引擎数据

    private class EngineBuilder(val text: String, val fontSize: Float, val color: Int, val fontName: String = FONT_NAME) {

        private val out = ByteArrayOutputStream(4096)
        private var indent = 0

        fun build(): ByteArray {
            ascii("\n\n")
            line("<<")
            indent = 2
            open("/EngineDict")
            indent++
            open("/Editor")
            indent++
            key("/Text"); ascii(" "); str(text); ascii("\n")
            close()
            paragraphRun()
            styleRun()
            open("/AntiAlias")
            ascii(" 4\n")
            key("/UseFractionalGlyphWidths"); ascii(" true\n")
            rendered()
            close()          // EngineDict
            resourceDict()
            indent--
            line(">>")
            return out.toByteArray()
        }

        private fun rendered() {
            open("/Rendered")
            indent++
            key("/Version"); ascii(" 1\n")
            open("/Shapes")
            indent++
            key("/WritingDirection"); ascii(" 0\n")
            key("/Children"); ascii("\n")
            indent++
            arrayOpen()
            indent++
            open(null)
            indent++
            key("/ShapeType"); ascii(" 0\n")
            key("/Procession"); ascii(" 0\n")
            open("/Lines")
            indent++
            key("/WritingDirection"); ascii(" 0\n")
            key("/Children"); ascii(" [ ]\n")
            close()
            open("/Cookie")
            indent++
            // 这个键名是分层文件文字引擎描述符规范里固定的一部分，不能改，改了文字层就失效
            open("/Photoshop")
            indent++
            key("/ShapeType"); ascii(" 0\n")
            key("/PointBase"); ascii(" [ 0 0 ]\n")
            close()
            close()
            close()       // item
            indent--
            arrayClose()
            indent--
            close()       // Shapes
            close()       // Rendered
        }

        private fun paragraphRun() {
            open("/ParagraphRun")
            indent++
            open("/DefaultRunData")
            indent++
            paragraphSheet(null)
            close()
            key("/RunArray"); ascii("\n")
            indent++
            arrayOpen()
            indent++
            open(null)
            indent++
            paragraphSheet(paragraphStyle())
            close()
            indent--
            arrayClose()
            indent--
            key("/RunLengthArray"); ascii(" [ "); ascii(text.length.toString()); ascii(" ]\n")
            key("/IsJoinable"); ascii(" 1\n")
            close()
        }

        private fun paragraphSheet(props: Map<String, Any>?) {
            open("/ParagraphSheet")
            indent++
            key("/DefaultStyleSheet"); ascii(" 0\n")
            propsDict("/Properties", props ?: emptyMap())
            close()
            open("/Adjustments")
            indent++
            key("/Axis"); ascii(" [ 1 0 1 ]\n")
            key("/XY"); ascii(" [ 0 0 ]\n")
            close()
        }

        private fun paragraphStyle(): Map<String, Any> = linkedMapOf(
            "Justification" to 0,
            "FirstLineIndent" to 0.0,
            "StartIndent" to 0.0,
            "EndIndent" to 0.0,
            "SpaceBefore" to 0.0,
            "SpaceAfter" to 0.0,
            "AutoHyphenate" to true,
            "HyphenatedWordSize" to 6,
            "PreHyphen" to 2,
            "PostHyphen" to 2,
            "ConsecutiveHyphens" to 8,
            "Zone" to 36.0,
            "WordSpacing" to doubleArrayOf(0.8, 1.0, 1.33),
            "LetterSpacing" to doubleArrayOf(0.0, 0.0, 0.0),
            "GlyphSpacing" to doubleArrayOf(1.0, 1.0, 1.0),
            "AutoLeading" to 1.2,
            "LeadingType" to 0,
            "Hanging" to false,
            "Burasagari" to false,
            "KinsokuOrder" to 0,
            "EveryLineComposer" to false,
        )

        private fun styleRun() {
            open("/StyleRun")
            indent++
            open("/DefaultRunData")
            indent++
            styleSheet(emptyMap())
            close()
            key("/RunArray"); ascii("\n")
            indent++
            arrayOpen()
            indent++
            open(null)
            indent++
            styleSheet(style())
            close()
            indent--
            arrayClose()
            indent--
            key("/RunLengthArray"); ascii(" [ "); ascii(text.length.toString()); ascii(" ]\n")
            key("/IsJoinable"); ascii(" 2\n")
            close()
        }

        private fun style(): Map<String, Any> {
            val r = ((color ushr 16) and 0xFF) / 255.0
            val g = ((color ushr 8) and 0xFF) / 255.0
            val b = (color and 0xFF) / 255.0
            return linkedMapOf(
                "Font" to 1,
                "FontSize" to fontSize.toDouble(),
                "FauxBold" to false,
                "FauxItalic" to false,
                "AutoLeading" to true,
                "Leading" to 0.0,
                "HorizontalScale" to 1.0,
                "VerticalScale" to 1.0,
                "Tracking" to 0,
                "AutoKerning" to true,
                "Kerning" to 0,
                "BaselineShift" to 0.0,
                "FontCaps" to 0,
                "FontBaseline" to 0,
                "Underline" to false,
                "Strikethrough" to false,
                "Ligatures" to true,
                "DLigatures" to false,
                "BaselineDirection" to 2,
                "Tsume" to 0.0,
                "StyleRunAlignment" to 2,
                "Language" to 0,
                "NoBreak" to false,
                "FillColor" to linkedMapOf<String, Any>("Type" to 1, "Values" to doubleArrayOf(1.0, r, g, b)),
                "StrokeColor" to linkedMapOf<String, Any>("Type" to 1, "Values" to doubleArrayOf(0.0, 0.0, 0.0, 0.0)),
                "FillFlag" to true,
                "StrokeFlag" to false,
                "FillFirst" to true,
                "YUnderline" to 1,
                "OutlineWidth" to 1.0,
                "CharacterDirection" to 0,
                "HindiNumbers" to false,
                "Kashida" to 1,
                "DiacriticPos" to 2,
            )
        }

        private fun styleSheet(style: Map<String, Any>) {
            open("/StyleSheet")
            indent++
            propsDict("/StyleSheetData", style)
            close()
        }

        private fun resourceDict() {
            indent--
            open("/ResourceDict")
            indent++
            key("/ParagraphSheetSet"); ascii("\n")
            indent++
            arrayOpen()
            indent++
            open(null)
            indent++
            key("/Name"); ascii(" "); str("Normal RGB"); ascii("\n")
            key("/DefaultStyleSheet"); ascii(" 0\n")
            propsDict("/Properties", paragraphStyle())
            close()
            indent--
            arrayClose()
            indent--
            key("/StyleSheetSet"); ascii("\n")
            indent++
            arrayOpen()
            indent++
            open(null)
            indent++
            key("/Name"); ascii(" "); str("Normal RGB"); ascii("\n")
            propsDict("/StyleSheetData", style())
            close()
            indent--
            arrayClose()
            indent--
            key("/FontSet"); ascii("\n")
            indent++
            arrayOpen()
            indent++
            for (name in arrayOf("AdobeInvisFont", fontName)) {
                open(null)
                indent++
                key("/Name"); ascii(" "); str(name); ascii("\n")
                key("/Script"); ascii(" 0\n")
                key("/FontType"); ascii(" 0\n")
                key("/Synthetic"); ascii(" 0\n")
                close()
            }
            indent--
            arrayClose()
            indent--
            key("/TheNormalStyleSheet"); ascii(" 0\n")
            key("/TheNormalParagraphSheet"); ascii(" 0\n")
            key("/SuperscriptSize"); ascii(" 0.583\n")
            key("/SuperscriptPosition"); ascii(" 0.333\n")
            key("/SubscriptSize"); ascii(" 0.583\n")
            key("/SubscriptPosition"); ascii(" 0.333\n")
            key("/SmallCapSize"); ascii(" 0.7\n")
            close()
            indent--
        }

        private fun propsDict(k: String, props: Map<String, Any>) {
            if (props.isEmpty()) {
                key(k); ascii(" << >>\n")
                return
            }
            key(k); ascii(" <<\n")
            indent++
            for ((pk, pv) in props) {
                key("/$pk")
                ascii(" ")
                value(pv)
                ascii("\n")
            }
            indent--
            line(">>")
        }

        private fun open(k: String?) {
            if (k != null) {
                key(k)
                ascii(" ")
            } else {
                tabs()
            }
            ascii("<<\n")
        }

        private fun close() {
            indent--
            line(">>")
        }

        private fun arrayOpen() {
            tabs()
            ascii("[\n")
        }

        private fun arrayClose() {
            indent--
            tabs()
            ascii("]\n")
        }

        private fun key(k: String) {
            tabs()
            ascii(k)
        }

        private fun line(s: String) {
            tabs()
            ascii(s)
            ascii("\n")
        }

        private fun tabs() {
            repeat(indent) { ascii("\t") }
        }

        private fun ascii(s: String) {
            for (ch in s) out.write(ch.code and 0xFF)
        }

        private fun value(v: Any) {
            when (v) {
                is Boolean -> ascii(if (v) "true" else "false")
                is Int -> ascii(v.toString())
                is Double -> ascii(num(v))
                is Float -> ascii(num(v.toDouble()))
                is String -> str(v)
                is DoubleArray -> {
                    ascii("[")
                    for (x in v) {
                        ascii(" ")
                        ascii(num(x))
                    }
                    ascii(" ]")
                }
                is Map<*, *> -> {
                    ascii("<<\n")
                    indent++
                    for ((k, x) in v) {
                        key("/$k")
                        ascii(" ")
                        value(x as Any)
                        ascii("\n")
                    }
                    indent--
                    line(">>")
                }
                else -> ascii("0")
            }
        }

        private fun num(v: Double): String {
            if (v == v.toInt().toDouble()) return v.toInt().toString()
            var s = String.format(java.util.Locale.US, "%.5f", v)
            s = s.trimEnd('0')
            if (s.startsWith("0.")) s = s.substring(1)
            if (s.startsWith("-0.")) s = "-" + s.substring(2)
            return s
        }

        /** 字符串值：UTF-16BE，前面加 FE FF，( ) \ 转义 */
        private fun str(s: String) {
            ascii("(")
            out.write(0xFE)
            out.write(0xFF)
            for (ch in s) {
                when (ch) {
                    '(', ')', '\\' -> out.write(0x5C)
                }
                out.write((ch.code shr 8) and 0xFF)
                out.write(ch.code and 0xFF)
            }
            ascii(")")
        }
    }

    private fun u16(b: ByteArrayOutputStream, v: Int) {
        b.write((v shr 8) and 0xFF)
        b.write(v and 0xFF)
    }

    private fun u32(b: ByteArrayOutputStream, v: Long) {
        Desc.writeS32(b, v)
    }

    private fun f64(b: ByteArrayOutputStream, v: Double) {
        Desc.writeF64(b, v)
    }

    private fun f32(b: ByteArrayOutputStream, v: Float) {
        val bits = java.lang.Float.floatToIntBits(v)
        for (i in 3 downTo 0) b.write((bits ushr (i * 8)) and 0xFF)
    }
}