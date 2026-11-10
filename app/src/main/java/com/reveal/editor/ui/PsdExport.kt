package com.reveal.editor.ui

import android.content.Context
import com.reveal.editor.data.Diag
import com.reveal.editor.data.PsdBrowser
import com.reveal.editor.image.Psd
import com.reveal.editor.image.PsdEffects
import com.reveal.editor.image.PsdTextInfo

/**
 * 把当前画布拼成 PSD 字节：保留层名/可见性/不透明度/混合模式/剪贴/组结构/蒙版/调整层参数/
 * 文字层信息/图层样式块/16 位与 32 位原始通道（层没被画过时）。
 * 从旧界面的 EditorScreen 里搬出来，新界面与旧界面共用同一份。
 */
internal fun buildPsdBytes(view: SketchView, context: Context, projectId: String): ByteArray {
    val base = view.layers.firstOrNull()?.let { view.needBitmap(it) } ?: error("画布是空的")
    val psdMeta = PsdBrowser.meta(context, projectId)
    val w = base.width
    val h = base.height
    val pls: List<Psd.PsdLayer> = run {
        // 渐变映射（GdMp）和调整组在 桌面端里没有对应的原生块，直接写就是个空层。
        // 这里把它们连同下面的图层一起烘成一张像素层，桌面端打开看到的就是屏幕上那张图。
        val exportLayers = ArrayList<SketchView.Layer>()
        for (l in view.layers) {
            if (l.visible && view.needsBakeForPsd(l.adjust)) {
                val idx = view.layers.indexOfFirst { it === l }
                val comp = runCatching { view.compositeUpTo(idx) }.getOrNull()
                if (comp != null) {
                    val baked = runCatching { view.bakeOpIntoBitmap(comp, l.adjust!!) }.getOrNull()
                    if (baked != null) {
                        // 下面那些层已经合进这一张，不能再单独导出（否则 桌面端里会叠两次）
                        exportLayers.clear()
                        exportLayers.add(
                            SketchView.Layer(baked, l.name + "（已栅格化）")
                        )
                        continue
                    }
                }
            }
            exportLayers.add(l)
        }
        exportLayers.mapNotNull { l ->
        Psd.PsdLayer(l.name, l.bitmap, l.visible, l.alpha, l.blendMode, clipped = l.clipped, alphaLock = l.alphaLock).also { it.bitmapSource = { view.needBitmap(l) };
            it.mask = l.mask
            it.rawMask = l.rawMask
            it.adjust = if (view.needsBakeForPsd(l.adjust)) null else l.adjust
            it.rawHead = l.rawHead?.let { b -> Psd.RawHead.unpack(b) }
            // 这层没被画过（像素指纹一致）才搬原始 16 位通道；画过就只能按 8 位重建
            it.pix16 = if (Psd.pix16Matches(l.pix16, view.needBitmap(l))) l.pix16 else null
            // 32 位：画过的层按容差判别，没画坏就把浮点缓冲原样写回
            it.pix32 = if (Psd.pix32Matches(l.pix32, view.needBitmap(l))) l.pix32 else null
                it.maskDirty = l.maskDirty
            it.rawPix = if (l.rawPix != null && l.pixHash != 0 &&
                l.pixHash == Psd.bitmapHash(view.needBitmap(l), l.mask)
            ) l.rawPix else null
            // 精度来源：不管画没画过都留着，灰度/CMYK 的 16 位导出全靠它
            it.pix16src = l.rawPix
            it.sectionType = l.sectionType
            val tc = l.textContent
            if (tc != null && tc.isNotEmpty()) {
                it.text = PsdTextInfo(tc, if (l.textSize > 0f) l.textSize else 24f, l.textColor, l.textFont)
            }
            if (l.strokeOn || l.shadowOn || l.glowOn || l.innerShadowOn || l.innerGlowOn || l.colorOverlayOn || l.gradientOverlayOn || l.bevelOn || l.satinOn || l.patternOverlayOn || l.textureOn || l.contourOn) {
                it.effects = PsdEffects(
                    strokeOn = l.strokeOn,
                    strokeColor = l.strokeColor,
                    strokeWidth = l.strokeWidth,
                    shadowOn = l.shadowOn,
                    shadowColor = l.shadowColor,
                    shadowDX = l.shadowDX,
                    shadowDY = l.shadowDY,
                    shadowBlur = l.shadowBlur,
                    colorOverlayOn = l.colorOverlayOn,
                    colorOverlayColor = l.colorOverlayColor,
                    glowOn = l.glowOn,
                    glowColor = l.glowColor,
                    glowSize = l.glowSize,
                    innerShadowOn = l.innerShadowOn,
                    innerShadowColor = l.innerShadowColor,
                    innerShadowDX = l.innerShadowDX,
                    innerShadowDY = l.innerShadowDY,
                    innerShadowSize = l.innerShadowSize,
                    innerGlowOn = l.innerGlowOn,
                    innerGlowColor = l.innerGlowColor,
                    innerGlowSize = l.innerGlowSize,
                    satinOn = l.satinOn,
                    satinColor = l.satinColor,
                    satinSize = l.satinSize,
                    satinDistance = l.satinDistance,
                    satinAngle = l.satinAngle,
                )
            }
        }
    }
    }
    if (pls.isEmpty()) error("没有图层")
    val pristine = if (false) null else PsdBrowser.original(context, projectId)
    if (pristine != null) return pristine
    val doc = if (psdMeta != null) {
        val (outMode, outDepth) = Psd.exportModeAndDepth(psdMeta.colorMode, psdMeta.depth)
        val modeChanged = outMode != psdMeta.colorMode || outDepth != psdMeta.depth
        if (modeChanged) {
            android.widget.Toast.makeText(
                context,
                "原文件是索引/位图/双色调/多通道，像素没法按原模式写回，这次存成 RGB（主流软件能正常打开）",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        Psd.Document(
            w, h, pls,
            version = psdMeta.version,
            depth = outDepth,
            colorMode = outMode,
            channels = psdMeta.channels,
            icc = if (modeChanged) null else psdMeta.icc,
        )
    } else {
        Psd.Document(w, h, pls)
    }
    runCatching {
        // 为什么会掉到 8 位：把 16 位缓冲和画面的差异分布记下来（最差点在哪、差多少）
        if (view.docDepth == 16) {
            view.layers.forEachIndexed { idx, l ->
                Diag.append(
                    context, "p16chk",
                    "layer=" + idx + ":" + l.name + " " + Psd.pix16Report(l.pix16, view.needBitmap(l))
                )
            }
        }
        Diag.append(
            context, "EXPORT",
            "build docDepth=" + view.docDepth + " metaDepth=" + (psdMeta?.depth ?: -1) +
                " outDepth=" + doc.depth + " layers=" + pls.size +
                " p16=" + pls.count { it.pix16 != null } + " raw=" + pls.count { it.rawPix != null } +
                " flat=" + (if (pls.size == 1 && pls[0].pix16 != null && pls[0].adjust == null &&
                    pls[0].mask == null && pls[0].text == null && pls[0].opacity == 255 &&
                    pls[0].blendMode == 0 && pls[0].effects == null) 1 else 0) +
                " p32=" + pls.count { it.pix32 != null } +
                " p32s=" + pls.joinToString(";") { p ->
                    val q = p.pix32
                    if (q == null) "-" else {
                        val n = q.width * q.height
                        fun f(v: Float) = ((v * 10000f).toInt() / 10000f).toString()
                        f(q.r[0]) + "," + f(q.r[n / 2])
                    }
                } +
                " p32diff=" + view.layers.joinToString(",") {
                    Psd.pix32Diff(it.pix32, view.needBitmap(it)).toString()
                } +
                " p16diff=" + view.layers.joinToString(",") {
                    Psd.pix16Diff(it.pix16, view.needBitmap(it)).toString()
                }
        )
    }
    // 导出期间先放掉合成缓存，一层写完就把它换出内存
    view.releaseComposite()
    val bytes = Psd.encode(doc) { view.trimMemoryNow() }
    runCatching { Diag.append(context, "EXPORT", "enc size=" + bytes.size + " note16=" + Psd.note16) }
    // 导出后自己再解析一遍：结构一旦写坏，这里立刻能发现（桌面端那边只会说“版本不兼容”）
    val check = runCatching { Psd.open(java.io.ByteArrayInputStream(bytes)) }.getOrNull()
    if (check == null) {
        android.util.Log.e("RevealPerf", "导出的 PSD 自解析失败，大小=" + bytes.size)
    } else if (check.layers.size != pls.size) {
        android.util.Log.w("RevealPerf", "导出的 PSD 图层数不符 " + check.layers.size + "/" + pls.size)
    }
    return bytes
}