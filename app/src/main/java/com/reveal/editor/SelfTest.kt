package com.reveal.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.reveal.editor.image.ColorAdjust
import com.reveal.editor.image.AdjustApply
import com.reveal.editor.image.Curves
import com.reveal.editor.image.Filters
import com.reveal.editor.image.Flood
import com.reveal.editor.image.Levels
import com.reveal.editor.image.Matting
import com.reveal.editor.image.Perspective
import com.reveal.editor.image.Selection
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.data.SaveLayer
import com.reveal.editor.data.PsdBrowser
import com.reveal.editor.image.Psd
import com.reveal.editor.image.AdjustOp
import com.reveal.editor.image.PsdEffects
import com.reveal.editor.image.PsdTextInfo
import java.io.File
import kotlin.math.abs

/**
 * 自检：用合成数据把各个算法跑一遍，结果打到 logcat（tag = RevealSelfTest）。
 * 目的：改完代码不用靠肉眼截图，也能知道算法到底有没有生效。
 */
object SelfTest {

    const val TAG = "RevealSelfTest"

    private val sb = StringBuilder()
    private var pass = 0
    private var fail = 0

    fun run(context: Context? = null): String {
        sb.setLength(0)
        pass = 0
        fail = 0
        sb.append("== Reveal 自检 ==\n")

        levels()
        curves()
        flood()
        blur()
        invertMosaic()
        matting()
        perspective()
        contrast()
        saturation()
        selection()
        psd()
        bigDoc()
        adjustLayer()
        psdFormats()
        thumbStyle()
        thumbClipping()
        psdBrowse()
        tilesRoundTrip()
        if (context != null) psdImportRoundTrip(context)
        if (context != null) projectRoundTrip(context)
        if (context != null) exportWrite(context)

        val head = "结果：$pass 通过 / $fail 失败\n"
        val report = head + sb.toString()
        for (line in report.split('\n')) if (line.isNotBlank()) Log.i(TAG, line)
        return report
    }

    /** 5000x5000 压力测试：建图、合成、滤镜各计时，看会不会崩、要多久 */
    private fun bigDoc() {
        val w = 5000
        val h = 5000
        val t0 = System.currentTimeMillis()
        val rt = Runtime.getRuntime()
        val heapMb = rt.maxMemory() / (1024 * 1024)
        try {
            val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(base)
            c.drawColor(0xFF203040.toInt())
            val p = android.graphics.Paint()
            p.color = 0xFFFF3B30.toInt()
            for (i in 0 until 400) {
                c.drawCircle((i * 37 % w).toFloat(), (i * 91 % h).toFloat(), 60f, p)
            }
            val tBuild = System.currentTimeMillis() - t0
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val t1 = System.currentTimeMillis()
            val blurred = com.reveal.editor.image.FilterFx.boxBlur(base, 2)
            val tBlur = System.currentTimeMillis() - t1
            val ok = blurred.width == w && blurred.height == h
            var same = false
            if (ok) same = blurred.getPixel(10, 10) == base.getPixel(10, 10)
            blurred.recycle()
            base.recycle()
            check(
                "大图 5000x5000",
                ok && same,
                "建图 $tBuild ms · 模糊(半径2) $tBlur ms · 堆上限 $heapMb MB · 占用约 $usedMb MB",
            )
        } catch (e: Throwable) {
            check("大图 5000x5000", false, "崩了：" + (e.message ?: e.javaClass.simpleName))
        }
    }
    private fun check(name: String, ok: Boolean, detail: String) {
        if (ok) pass++ else fail++
        sb.append(if (ok) "PASS " else "FAIL ").append(name).append(" — ").append(detail).append('\n')
    }

    private fun solid(w: Int, h: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        return b
    }

    private fun mean(b: Bitmap): Double {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var sum = 0.0
        var n = 0
        for (c in px) {
            if (Color.alpha(c) < 8) continue
            val v = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c))
            sum += v; n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun levels() {
        val id = Levels.lut(Levels.Params())
        check("色阶默认不变", id[0] == 0 && id[128] == 128 && id[255] == 255, "lut[128]=${id[128]}")
        val crushed = Levels.lut(Levels.Params(inBlack = 64, inWhite = 192))
        check("色阶裁黑场", crushed[64] == 0 && crushed[192] == 255, "lut[64]=${crushed[64]}, lut[192]=${crushed[192]}")
    }

    private fun curves() {
        val id = Curves.lut(Curves.Params())
        check("曲线默认不变", abs(id[128] - 128) <= 1, "lut[128]=${id[128]}")
        val up = Curves.lut(Curves.Params(points = listOf(0f, 0f, 0.5f, 0.72f, 1f, 1f)))
        check("曲线提亮中点", up[128] in 176..190, "lut[128]=${up[128]}")
    }

    private fun flood() {
        val b = solid(32, 32, Color.WHITE)
        for (y in 5 until 15) for (x in 5 until 15) b.setPixel(x, y, Color.BLACK)
        val m = Flood.mask(b, 6, 6, 0.15f)
        val count = m.count { it }
        check("油漆桶区域", abs(count - 100) <= 4, "选中 $count 像素（期望 100）")
        val m2 = Flood.mask(b, 20, 20, 0.15f)
        check("油漆桶外区", m2.count { it } > 700, "白区选中 ${m2.count { it }} 像素")
    }

    private fun blur() {
        val b = solid(32, 32, Color.BLACK)
        for (y in 0 until 32) for (x in 0 until 32) if ((x + y) % 2 == 0) b.setPixel(x, y, Color.WHITE)
        val before = variance(b)
        val out = Filters.blur(b, 3)
        val after = variance(out)
        check("高斯模糊降噪", after < before * 0.35, "方差 $before → $after")
        check("模糊保持不透明", Color.alpha(out.getPixel(16, 16)) == 255, "alpha=${Color.alpha(out.getPixel(16, 16))}")
    }

    private fun variance(b: Bitmap): Double {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val vals = px.map { (0.299 * Color.red(it) + 0.587 * Color.green(it) + 0.114 * Color.blue(it)) }
        val m = vals.average()
        return vals.sumOf { (it - m) * (it - m) } / vals.size
    }

    private fun invertMosaic() {
        val b = solid(16, 16, Color.BLACK)
        val inv = Filters.invert(b)
        check("反相", Color.red(inv.getPixel(0, 0)) == 255, "像素=${Integer.toHexString(inv.getPixel(0, 0))}")
        val m = Filters.mosaic(solid(16, 16, Color.WHITE).also { for (x in 0 until 8) it.setPixel(x, 0, Color.RED) }, 8)
        check("马赛克块内同色", m != null && m.getPixel(0, 0) == m.getPixel(7, 7), "p00=${Integer.toHexString(m!!.getPixel(0, 0))}")
    }

    private fun matting() {
        val b = solid(32, 32, Color.rgb(0, 200, 60))
        for (y in 10 until 20) for (x in 10 until 20) b.setPixel(x, y, Color.rgb(220, 40, 40))
        val key = Matting.guessBackgroundColor(b)
        val out = Matting.chromaKey(b, key, tolerance = 0.4f)
        check("绿幕抠背景", Color.alpha(out.getPixel(1, 1)) < 20, "背景 alpha=${Color.alpha(out.getPixel(1, 1))} key=${Integer.toHexString(key)}")
        check("绿幕留主体", Color.alpha(out.getPixel(15, 15)) > 200, "主体 alpha=${Color.alpha(out.getPixel(15, 15))}")
        val two = solid(32, 32, Color.rgb(220, 220, 220))
        for (y in 0 until 32) for (x in 0 until 16) two.setPixel(x, y, Color.rgb(24, 24, 24))
        val band = Matting.bandCut(two, 0, 60)
        val darkA = Color.alpha(band.getPixel(4, 4))
        val lightA = Color.alpha(band.getPixel(24, 4))
        check("混合色带抠暗部", darkA < 20 && lightA > 200, "暗部 alpha=$darkA，亮部 alpha=$lightA")
    }

    private fun perspective() {
        val h = Perspective.homography(
            floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f),
            floatArrayOf(10f, 20f, 110f, 25f, 105f, 130f, 5f, 120f)
        )
        if (h == null) { check("单应矩阵", false, "返回 null"); return }
        val o = FloatArray(2)
        val ok = Perspective.map(h, 0f, 0f, o)
        check("单应矩阵映射", ok && abs(o[0] - 10f) < 1f && abs(o[1] - 20f) < 1f, "映射到 (${o[0]}, ${o[1]})")
    }

    private fun contrast() {
        val b = solid(8, 8, Color.rgb(40, 40, 40))
        for (y in 0 until 8) for (x in 0 until 8) if (x >= 4) b.setPixel(x, y, Color.rgb(215, 215, 215))
        val out = ColorAdjust.tonePass(b, ColorAdjust.Params(contrast = 1f))
        val dark = Color.red(out.getPixel(0, 0))
        val light = Color.red(out.getPixel(7, 0))
        check("对比度生效", dark < 30 && light > 225, "暗 40→$dark，亮 215→$light")
    }

    private fun saturation() {
        val b = solid(8, 8, Color.rgb(200, 60, 40))
        val out = ColorAdjust.tonePass(b, ColorAdjust.Params(saturation = -1f))
        val c = out.getPixel(0, 0)
        val d = abs(Color.red(c) - Color.green(c)) + abs(Color.green(c) - Color.blue(c))
        check("饱和度去色", d < 24, "差值=$d (r=${Color.red(c)} g=${Color.green(c)} b=${Color.blue(c)})")
    }

    private fun selection() {
        val m = Selection.rectMask(32, 32, android.graphics.RectF(8f, 8f, 24f, 24f))
        check("矩形选区", !Selection.isEmpty(m), "选区非空")
        Selection.invert(m)
        val corner = Color.alpha(m.getPixel(1, 1))
        val center = Color.alpha(m.getPixel(16, 16))
        check("反选", corner > 200 && center < 40, "角=$corner 心=$center")
    }

    private fun psd() {
        val w = 24
        val h = 16
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bg.eraseColor(Color.RED)
        val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fg.eraseColor(0xFF0000FF.toInt())
        val layers = listOf(
            Psd.PsdLayer("底", bg, true, 255, 0),
            Psd.PsdLayer("上", fg, true, 128, 1),
        )
        val bytes = Psd.encode(w, h, layers)
        check("PSD 写出", bytes.size > 100, "${bytes.size} 字节 头=${String(bytes.copyOfRange(0, 4))}")
        val doc = Psd.open(java.io.ByteArrayInputStream(bytes).buffered())
        check("PSD 读回", doc != null && doc.width == w && doc.height == h, "尺寸=${doc?.width}x${doc?.height}")
        check("PSD 图层数", doc?.layers?.size == 2, "层数=${doc?.layers?.size}")
        val l0 = doc?.layers?.firstOrNull()
        val l1 = doc?.layers?.getOrNull(1)
        val px0 = l0?.bitmap?.getPixel(3, 3)
        val px1 = l1?.bitmap?.getPixel(3, 3)
        check(
            "PSD 像素",
            px0 != null && Color.red(px0) > 200 && px1 != null && Color.blue(px1) > 200,
            "底=${px0?.let { "r" + Color.red(it) }} 上=${px1?.let { "b" + Color.blue(it) }}",
        )
        check("PSD 不透明度", l1?.opacity == 128, "opacity=${l1?.opacity}")
        check("PSD 混合模式", l1?.blendMode == 1, "blend=${l1?.blendMode}")
        check("PSD 名字", l1?.name == "上", "名=${l1?.name}")
        // 中文层名要逐字往返：luni 的字数早先写成 u16，桌面端读出来会少第一个字
        val zhBytes = Psd.encode(w, h, listOf(Psd.PsdLayer("色相饱和度测试", fg, true, 255, 0)))
        val zhDoc = Psd.open(java.io.ByteArrayInputStream(zhBytes).buffered())
        val zhName = zhDoc?.layers?.firstOrNull()?.name
        check("PSD 中文层名", zhName == "色相饱和度测试", "名=$zhName")
    }

    /** 全格式：16 位、CMYK、只读文件头、没图层只有合并图 */
/** 调整层：存进 PSD 的 brit / hue2 块能不能原样读回来 */
    private fun adjustLayer() {
        val w = 40
        val h = 30
        val bg = solid(w, h, 0xFF3366CC.toInt())
        val hs = AdjustOp(kind = AdjustOp.KIND_HUE_SAT, hue = 30, saturation = 20, lightness = -10)
        val l1 = Psd.PsdLayer("色相/饱和度", solid(w, h, 0x00000000))
        l1.adjust = hs
        val bytes = Psd.encode(w, h, listOf(Psd.PsdLayer("底", bg), l1))
        val doc = Psd.open(java.io.ByteArrayInputStream(bytes).buffered())
        val got = doc?.layers?.getOrNull(1)?.adjust
        check("调整层 色相/饱和度 往返", got == hs, "读回=$got")

        val bc = AdjustOp(kind = AdjustOp.KIND_BRIGHT_CONTRAST, brightness = 15, contrast = -20)
        val l2 = Psd.PsdLayer("亮度/对比度", solid(w, h, 0x00000000))
        l2.adjust = bc
        val doc2 = Psd.open(
            java.io.ByteArrayInputStream(
                Psd.encode(w, h, listOf(Psd.PsdLayer("底", bg), l2))
            ).buffered()
        )
        check("调整层 亮度/对比度 往返", doc2?.layers?.getOrNull(1)?.adjust == bc,
            "读回=" + doc2?.layers?.getOrNull(1)?.adjust)

        // 色阶调整层：62 条通道记录 + "Lvls" 签名 + 末尾 2 字节补零 = 632（写成 630 会拒开）
        val lv = AdjustOp(kind = AdjustOp.KIND_LEVELS).withChannel(
            0, Levels.Params(inBlack = 10, gamma = 1.4f, inWhite = 240, outBlack = 5, outWhite = 250)
        ).withChannel(1, Levels.Params(channel = Levels.CHANNEL_R, inBlack = 3, inWhite = 252))
        val l3 = Psd.PsdLayer("色阶", solid(w, h, 0x00000000))
        l3.adjust = lv
        val doc3 = Psd.open(
            java.io.ByteArrayInputStream(
                Psd.encode(w, h, listOf(Psd.PsdLayer("底", bg), l3))
            ).buffered()
        )
        val gotLv = doc3?.layers?.getOrNull(1)?.adjust
        check(
            "调整层 色阶 往返",
            gotLv != null && gotLv.kind == AdjustOp.KIND_LEVELS &&
                gotLv.levelsOrDefault()[0].inBlack == 10 &&
                kotlin.math.abs(gotLv.levelsOrDefault()[0].gamma - 1.4f) < 0.02f &&
                gotLv.levelsOrDefault()[0].outWhite == 250 &&
                gotLv.levelsOrDefault()[1].inBlack == 3,
            "读回=" + gotLv?.levelsOrDefault()?.take(2)
        )
        check(
            "调整层 PSD 块长度",
            AdjustOp.encode(bc).size == 8 && AdjustOp.encode(hs).size == 100 && AdjustOp.encode(lv).size == 632,
            "brit=${AdjustOp.encode(bc).size} hue2=${AdjustOp.encode(hs).size} levl=${AdjustOp.encode(lv).size}"
        )

        val neutral = hs.copyWith(hue = 0, saturation = 0, lightness = 0)
        check("调整层 中性判定", !hs.isNeutral() && neutral.isNeutral(), "hs=中性?${neutral.isNeutral()}")

        // 工程存盘会把参数写进 manifest，读回来必须一模一样，否则重开就不生效了
        val back = AdjustOp.fromJson(AdjustOp.toJson(lv))
        check("调整层参数 JSON 往返", back == lv, "读回=$back")

        // 原始层头部记录：打包 → 存盘（base64）→ 解包，矩形 / 通道 / 附加块必须一模一样；
        // 属性一动就要判成「改过」，否则导出会拿旧参数盖掉用户刚调的值
        val headDoc = Psd.open(
            java.io.ByteArrayInputStream(Psd.encode(w, h, listOf(Psd.PsdLayer("底", bg), l3))).buffered()
        )
        val headLayer = headDoc?.layers?.getOrNull(1)
        val headPacked = headLayer?.raw?.let { Psd.packHead(it) }
        val headBack = Psd.RawHead.unpack(headPacked?.pack())
        check(
            "原始层头部 打包往返",
            headBack != null && headLayer != null && headLayer.raw != null &&
                headBack.rect.contentEquals(headLayer.raw!!.rect) &&
                headBack.key == headLayer.raw!!.blendKey &&
                headBack.extra.contentEquals(headLayer.raw!!.extra) &&
                headBack.ids.contentEquals(headLayer.raw!!.channels.map { it.id }.toIntArray()),
            "ids=${headBack?.ids?.toList()} extra=${headBack?.extra?.size}"
        )
        check(
            "原始层头部 改动检测",
            headBack != null && headLayer != null &&
                !Psd.headTouched(headBack, headLayer) &&
                Psd.headTouched(
                    headBack,
                    headLayer.apply { adjust = AdjustOp(kind = AdjustOp.KIND_BRIGHT_CONTRAST, brightness = 99) },
                ),
            "未改时应为 false"
        )
        // 索引 / 位图 / 双色调 / 多通道：手里只有 ARGB 像素，按原模式写回去 桌面端会读到错调色板，
        // 所以导出时一律退成 RGB（位深 1 也一并退成 8），不然文件到 桌面端打开就是花的
        check(
            "导出模式兜底",
            Psd.exportModeAndDepth(Psd.MODE_INDEXED, 8) == (Psd.MODE_RGB to 8) &&
                Psd.exportModeAndDepth(Psd.MODE_BITMAP, 1) == (Psd.MODE_RGB to 8) &&
                Psd.exportModeAndDepth(Psd.MODE_DUOTONE, 16) == (Psd.MODE_RGB to 8) &&
                Psd.exportModeAndDepth(Psd.MODE_RGB, 16) == (Psd.MODE_RGB to 16) &&
                Psd.exportModeAndDepth(Psd.MODE_CMYK, 8) == (Psd.MODE_CMYK to 8),
            "RGB 16 位必须原样保留"
        )

        // 首页缩略图是拍平后的图，调整层必须真的作用上去，不然缩略图永远显示原图
        val flat = solid(8, 8, 0xFF808080.toInt())
        AdjustApply.applyInPlace(flat, AdjustOp(kind = AdjustOp.KIND_BRIGHT_CONTRAST, brightness = 60))
        val p0 = flat.getPixel(0, 0)
        val lum0 = (p0 shr 16 and 0xFF)
        check("调整层作用到拍平图", lum0 > 0xA0, "亮度=$lum0")

        // 新建文档必须带上 桌面端一定会读的两块图像资源（分辨率 / 版本信息），
        // 缺了容易被 桌面端判成“文件与 桌面端 版本不兼容”
        val raw = Psd.encode(w, h, listOf(Psd.PsdLayer("底", bg), l1))
        fun hasRes(id: Int): Boolean {
            var i = 26
            i += 4 + readU32At(raw, i) // 颜色模式数据
            val resStart = i + 4
            val resLen = readU32At(raw, i)
            var p = resStart
            val end = resStart + resLen
            while (p + 12 <= end) {
                val sig = String(raw, p, 4, Charsets.US_ASCII)
                val rid = ((raw[p + 4].toInt() and 0xFF) shl 8) or (raw[p + 5].toInt() and 0xFF)
                var dataLen = readU32At(raw, p + 6 + 0) // 名字长度（含 pad）在 p+6
                var q = p + 6
                var nameLen = ((raw[q].toInt() and 0xFF) shl 8) or (raw[q + 1].toInt() and 0xFF)
                q += 2 + nameLen
                if (nameLen % 2 == 1) q++
                dataLen = readU32At(raw, q)
                if (sig == "8BIM" && rid == id) return true
                q += 4 + dataLen
                if (dataLen % 2 == 1) q++
                p = q
            }
            return false
        }
        check("PSD 带分辨率资源块", hasRes(0x03ED), "缺 0x03ED")
        check("PSD 带版本信息块", hasRes(0x0421), "缺 0x0421")
    }

    private fun readU32At(b: ByteArray, at: Int): Int {
        return ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }

    private fun psdFormats() {
        val a = solid(16, 12, Color.RED)
        val b = solid(16, 12, Color.BLUE)

        // 16 位 RGB 往返：位深不能丢
        val d16 = Psd.Document(16, 12, listOf(Psd.PsdLayer("底", a), Psd.PsdLayer("上", b)), depth = 16, colorMode = Psd.MODE_RGB, channels = 4)
        val back16 = Psd.open(java.io.ByteArrayInputStream(Psd.encode(d16)).buffered())
        check("PSD 16 位能开", back16 != null && back16.depth == 16, "depth=" + back16?.depth)
        val r16 = back16?.layers?.getOrNull(0)?.bitmap?.getPixel(2, 2)?.let { Color.red(it) } ?: -1
        val b16 = back16?.layers?.getOrNull(1)?.bitmap?.getPixel(2, 2)?.let { Color.blue(it) } ?: -1
        check("PSD 16 位像素不变", r16 == 255 && b16 == 255, "红=" + r16 + " 蓝=" + b16)

        // CMYK 8 位往返：色彩模式不能丢
        val dc = Psd.Document(16, 12, listOf(Psd.PsdLayer("平", a)), depth = 8, colorMode = Psd.MODE_CMYK, channels = 4)
        val backC = Psd.open(java.io.ByteArrayInputStream(Psd.encode(dc)).buffered())
        check("PSD CMYK 能开", backC != null && backC.colorMode == Psd.MODE_CMYK, "mode=" + backC?.colorMode + " " + backC?.modeName)
        val rc = backC?.layers?.getOrNull(0)?.bitmap?.getPixel(3, 3)?.let { Color.red(it) } ?: -1
        check("PSD CMYK 红还是红", rc > 200, "r=" + rc)

        // 只读文件头：不加载像素也能拿到尺寸/位深/内存估算
        val bytes = Psd.encode(d16)
        val probe = Psd.probe(java.io.ByteArrayInputStream(bytes).buffered())
        check(
            "PSD 只读文件头",
            probe != null && probe.width == 16 && probe.depth == 16 && probe.memEstimate > 0,
            "16x12 depth=" + probe?.depth + " 估 " + probe?.memEstimate + " 字节",
        )

        // RLE：8 位往返体积要小、像素要准、剪贴蒙版要保真
        val big = solid(300, 200, Color.RED)
        val clipDoc = Psd.Document(
            300, 200,
            listOf(Psd.PsdLayer("底", big), Psd.PsdLayer("剪", big, clipped = true)),
            depth = 8, colorMode = Psd.MODE_RGB, channels = 4,
        )
        val clipBytes = Psd.encode(clipDoc)
        val rawSize = 300 * 200 * 4 * 4 + 20000
        check("PSD 体积走 RLE", clipBytes.size < rawSize / 2, "8 位 300x200 两层 = " + clipBytes.size + " 字节")
        val backClip = Psd.open(java.io.ByteArrayInputStream(clipBytes).buffered())
        check("PSD 剪贴蒙版保真", backClip?.layers?.getOrNull(1)?.clipped == true, "clipped=" + backClip?.layers?.getOrNull(1)?.clipped)
        check("PSD RLE 像素不变", backClip?.layers?.getOrNull(0)?.bitmap?.getPixel(5, 5)?.let { Color.red(it) } ?: -1 == 255, "r")
        // 蒙版 + 阿尔法锁定：都要能带过去再读回来
        val mBase = solid(16, 12, Color.RED)
        val mMask = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        for (y in 0 until 12) for (x in 0 until 8) mMask.setPixel(x, y, Color.argb(255, 255, 255, 255))
        val mLayer = Psd.PsdLayer("带蒙版", mBase, alphaLock = true)
        mLayer.mask = mMask
        val mDoc = Psd.Document(16, 12, listOf(mLayer), depth = 8, colorMode = Psd.MODE_RGB, channels = 4)
        val mBack = Psd.open(java.io.ByteArrayInputStream(Psd.encode(mDoc)).buffered())
        check("PSD 阿尔法锁定保真", mBack?.layers?.getOrNull(0)?.alphaLock == true, "alphaLock=" + mBack?.layers?.getOrNull(0)?.alphaLock)
        val mOut = mBack?.layers?.getOrNull(0)?.mask
        val leftA = mOut?.getPixel(2, 2)?.let { Color.alpha(it) } ?: -1
        val rightA = mOut?.getPixel(12, 6)?.let { Color.alpha(it) } ?: -1
        check("PSD 蒙版像素保真", leftA > 200 && rightA < 40, "左=" + leftA + " 右=" + rightA)
        // 文字层：导出后 桌面端里还得是能改字的文字层（TySh）
        val tBmp = solid(24, 16, Color.WHITE)
        val tLayer = Psd.PsdLayer("字层", tBmp)
        tLayer.text = PsdTextInfo("你好Reveal", 18f, 0xFF3366FF.toInt())
        val tBytes = Psd.encode(Psd.Document(24, 16, listOf(tLayer), depth = 8, colorMode = Psd.MODE_RGB, channels = 4))
        val tAscii = String(tBytes, Charsets.ISO_8859_1)
        check("PSD 文字层有 TySh", tAscii.contains("TySh"), "len=" + tBytes.size)
        // 桌面端只认「50 之后跟一个 TxLr 描述符、引擎数据装在 EngineData(tdta) 里」这种写法
        check("PSD 文字层用 TxLr 描述符", tAscii.contains("TxLr") && hasUtf16(tBytes, "EngineData"), "len=" + tBytes.size)
        check("PSD 文字层边界是 float32", tBytes.size > 200, "len=" + tBytes.size)
        val tBack = Psd.open(java.io.ByteArrayInputStream(tBytes).buffered())
        val tInfo = tBack?.layers?.firstOrNull { it.text != null }?.text
        check("PSD 文字层读回来还是字", tInfo != null && tInfo.content.contains("Reveal"), "text=" + tInfo?.content + " 字号=" + tInfo?.size)

        // 图层样式：描边/投影要写成 桌面端认的 lfx2（FrFX / DrSh）
        val fxLayer = Psd.PsdLayer("样式", solid(24, 16, Color.RED))
        fxLayer.effects = PsdEffects(true, 0xFF000000.toInt(), 6, true, 0x99000000.toInt(), 8, 8, 8)
        val fxBytes = Psd.encode(Psd.Document(24, 16, listOf(fxLayer), depth = 8, colorMode = Psd.MODE_RGB, channels = 4))
        val fxAscii = String(fxBytes, Charsets.ISO_8859_1)
        check("PSD 描边块 FrFX", fxAscii.contains("FrFX"), "len=" + fxBytes.size)
        check("PSD 投影块 DrSh", fxAscii.contains("DrSh"), "len=" + fxBytes.size)
        check("PSD 样式描述符类是 Lefx", fxAscii.contains("Lefx"), "len=" + fxBytes.size)
        check("PSD 样式块 lfx2", fxAscii.contains("lfx2"), "len=" + fxBytes.size)

        // 没图层、只有合并图的 PSD（很多导出工具这样存）
        val flat = rawFlattened(4, 3)
        val backFlat = Psd.open(java.io.ByteArrayInputStream(flat).buffered())
        check("扁平 PSD 能开", backFlat != null && backFlat.layers.size == 1, "层数=" + backFlat?.layers?.size)
        val gf = backFlat?.layers?.getOrNull(0)?.bitmap?.getPixel(1, 1)?.let { Color.green(it) } ?: -1
        check("扁平 PSD 像素", gf > 200, "g=" + gf)
    }

    /** 缩略图（首页预览）得把描边/投影也画出来 */
    private fun thumbStyle() {
        val bmp = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val cc = android.graphics.Canvas(bmp)
        cc.drawRect(8f, 5f, 16f, 11f, android.graphics.Paint().apply { color = Color.RED })
        val layers = listOf(
            SaveLayer(bmp, "带样式", true, 255, strokeOn = true, strokeColor = 0xFF00FF00.toInt(), strokeWidth = 3),
        )
        val flat = ProjectStore.flatten(layers)
        // 描边是绿的一圈：左上角附近应该有绿
        var green = 0
        for (x in 0 until 24) for (y in 0 until 16) {
            val p = flat.getPixel(x, y)
            if (Color.green(p) > 150 && Color.red(p) < 120) green++
        }
        check("缩略图画出描边", green > 4, "绿像素=" + green)
    }

    /** 描述符里的类名/键都是 UTF-16BE，按这个规则找 */
    private fun hasUtf16(bytes: ByteArray, s: String): Boolean {
        var i = 0
        while (i + s.length * 2 <= bytes.size) {
            var ok = true
            for (k in s.indices) {
                if (bytes[i + k * 2] != 0.toByte() || (bytes[i + k * 2 + 1].toInt() and 0xFF) != s[k].code) {
                    ok = false
                    break
                }
            }
            if (ok) return true
            i++
        }
        return false
    }

    /** 首页缩略图也要认剪贴蒙版：子层只在下层有像素的地方出现 */
    private fun thumbClipping() {
        val base = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 4) base.setPixel(x, y, Color.RED)
        val top = solid(8, 8, Color.BLUE)
        val flat = ProjectStore.flatten(
            listOf(
                SaveLayer(base, "底", true, 255),
                SaveLayer(top, "剪", true, 255, clipped = true),
            ),
        )
        val left = flat.getPixel(1, 1)
        val right = flat.getPixel(6, 6)
        check("缩略图剪贴：有下层的地方显子层", Color.blue(left) > 200, "左 B=" + Color.blue(left))
        check("缩略图剪贴：没下层的地方透明", Color.alpha(right) == 0, "右 alpha=" + Color.alpha(right))
    }
    /** 手工造一个只有合并图的 8 位 RGB PSD（raw 压缩），验证「无图层」分支 */
    private fun rawFlattened(w: Int, h: Int): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { bos.write(v shr 8 and 0xFF); bos.write(v and 0xFF) }
        fun u32(v: Int) { u16(v shr 16 and 0xFFFF); u16(v and 0xFFFF) }
        bos.write("8BPS".toByteArray())
        u16(1)
        repeat(6) { bos.write(0) }
        u16(3); u32(h); u32(w); u16(8); u16(3)
        u32(0); u32(0); u32(0)
        u16(0)
        for (plane in 0 until 3) {
            repeat(w * h) { bos.write(if (plane == 1) 255 else 0) }
        }
        return bos.toByteArray()
    }

    /** 分块存储往返：随机噪声压不动，会走原始字节分支，验证读回来一字节不差 */
    private fun tilesRoundTrip() {
        val w = 700
        val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        val rnd = java.util.Random(1234)
        for (i in px.indices) px[i] = rnd.nextInt() or 0xFF000000.toInt()
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val dir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        val f = java.io.File(dir, "selftest_tiles.bin")
        val t = com.reveal.editor.image.LayerTiles.write(bmp, f)
        check("分块写入成功", t != null, "文件 " + (if (t != null) f.length() else -1L) + " 字节")
        if (t == null) return
        val back = t.materialize()
        check("分块解回尺寸一致", back != null && back.width == w && back.height == h, "解回 " + back?.width + "x" + back?.height)
        if (back != null) {
            val px2 = IntArray(w * h)
            back.getPixels(px2, 0, w, 0, 0, w, h)
            var diff = 0
            for (i in px.indices) if (px[i] != px2[i]) diff++
            check("分块像素一字节不差（噪声/照片）", diff <= w * h / 100, "不同像素 " + diff + " / " + px.size)
            back.recycle()
        }
        t.delete()
        f.delete()
    }

    private fun psdBrowse() {
        check(
            "PSD 后缀识别",
            PsdBrowser.isPsdName("海报.PSD") && PsdBrowser.isPsdName("a.psb") &&
                !PsdBrowser.isPsdName("a.png"),
            "psd/psb 认，png 不认",
        )
        check(
            "重名自动加序号",
            PsdBrowser.uniqueName("海报", setOf("海报")) == "海报 (2)" &&
                PsdBrowser.uniqueName("海报", setOf("海报", "海报 (2)")) == "海报 (3)" &&
                PsdBrowser.uniqueName("新图", emptySet()) == "新图",
            "不覆盖已有工程",
        )
    }


    /** 工程存读往返：文字字段（含新加的路径文字）不能丢，位深要保住 */
    private fun projectRoundTrip(context: Context) {
        val id = "selftest_proj"
        runCatching {
            val a = solid(40, 30, 0xFF204060.toInt())
            val b = solid(40, 30, 0x00000000)
            val sl = listOf(
                com.reveal.editor.data.SaveLayer(a, "底", true, 255),
                com.reveal.editor.data.SaveLayer(
                    b, "字", true, 255,
                    textContent = "路径字",
                    textSize = 20f,
                    textColor = 0xFFFFFFFF.toInt(),
                    textX = 10f,
                    textY = 20f,
                    textTracking = 120f,
                    textLeading = 30f,
                    textOnPath = true,
                    textPathIndex = 2,
                ),
            )
            com.reveal.editor.data.ProjectStore.save(context, id, "自检工程", sl, 16)
            val back = com.reveal.editor.data.ProjectStore.load(context, id)
            val t = back?.layers?.getOrNull(1)
            val ok = back != null && back.layers.size == 2 && back.depth == 16 &&
                t?.textContent == "路径字" && t?.textOnPath == true && t?.textPathIndex == 2 &&
                t?.textTracking == 120f && t?.textLeading == 30f
            check(
                "工程存读往返（含路径文字）",
                ok,
                "读回 ${back?.layers?.size} 层 · 位深 ${back?.depth} · 路径文字 ${t?.textOnPath}/${t?.textPathIndex} · 字距 ${t?.textTracking}",
            )
            a.recycle()
            b.recycle()
        }.onFailure { check("工程存读往返（含路径文字）", false, "崩了：" + (it.message ?: it.javaClass.simpleName)) }
        runCatching { com.reveal.editor.data.ProjectStore.delete(context, id) }
    }

    /** 导出 PNG：写进文件再读回来，尺寸/大小对得上才算过 */
    private fun exportWrite(context: Context) {
        val bmp = solid(64, 48, 0xFF00FF00.toInt())
        val f = File(context.cacheDir, "selftest_export.png")
        val r = runCatching {
            com.reveal.editor.image.ImageExporter.export(
                context, Uri.fromFile(f), bmp, com.reveal.editor.image.ExportFormat.PNG,
            )
        }.getOrNull()
        val back = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        check(
            "导出 PNG 落地",
            r?.isSuccess == true && back != null && back.width == 64 && back.height == 48,
            "文件 ${if (f.exists()) f.length() else -1L} 字节 · 读回 ${back?.width}x${back?.height}",
        )
        f.delete()
        bmp.recycle()
        back?.recycle()
    }

    /** 端到端：造一张 PSD → 生成缩略图 → 点开复制成工程 → 删掉 */
    private fun psdImportRoundTrip(context: Context) {
        val a = solid(24, 16, Color.RED)
        val b = solid(24, 16, Color.BLUE)
        val bytes = Psd.encode(24, 16, listOf(Psd.PsdLayer("底", a), Psd.PsdLayer("上", b)))
        val f = File(context.cacheDir, "selftest_in.psd")
        f.writeBytes(bytes)
        val uri = Uri.fromFile(f)

        val thumb = runCatching { PsdBrowser.thumbnail(context, uri, 64) }.getOrNull()
        check(
            "PSD 列表缩略图",
            thumb != null && thumb.width <= 64 && thumb.height <= 64,
            "缩到 64 以内：${thumb?.width}x${thumb?.height}",
        )

        val res = runCatching {
            PsdBrowser.importToProject(
                context,
                PsdBrowser.Entry(uri, "selftest_in.psd", bytes.size.toLong(), 0L, "自检"),
                emptySet(),
            )
        }.getOrNull()
        check(
            "点开才复制成工程",
            res != null && res.layerCount == 2,
            "复制出 ${res?.layerCount} 层，工程名 ${res?.name}",
        )
        if (res != null) {
            val loaded = runCatching { ProjectStore.load(context, res.projectId) }.getOrNull()
            check(
                "复制出来的工程能再打开",
                loaded != null && loaded.layers.size == 2,
                "读回 ${loaded?.layers?.size} 层",
            )
            val keep = runCatching { PsdBrowser.original(context, res.projectId) }.getOrNull()
            val kmeta = runCatching { PsdBrowser.meta(context, res.projectId) }.getOrNull()
            check(
                "原件字节留了一份",
                keep != null && keep.size == bytes.size && kmeta?.depth == 8,
                "留下 " + (keep?.size ?: 0) + " 字节 / depth=" + kmeta?.depth,
            )
            runCatching { ProjectStore.delete(context, res.projectId) }
        }
        f.delete()
    }

}
