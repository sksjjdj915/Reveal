package com.reveal.editor.ui

import com.reveal.editor.data.LoadedDocument
import com.reveal.editor.data.SaveLayer

/**
 * 工程 → 画布：把磁盘上读出来的文档套到 SketchView 上。
 *
 * 原来这段写在 EditorScreen 这个大 composable 里（150 行），两个问题：
 *  1. 它已经贴着 64KB 方法上限，塞不下了；
 *  2. 桌面模式（DesktopEditorScreen）也要用同一套，不能抄两份。
 * 所以搬到这里，两边共用。
 */
internal fun attachDocument(view: SketchView, doc: LoadedDocument, depth: Int) {
    val layers = doc.layers.map { sl ->
        SketchView.Layer(sl.bitmap, sl.name).apply {
            visible = sl.visible
            alpha = sl.alpha
            alphaLock = sl.alphaLock
            clipped = sl.clipped
            blendMode = sl.blendMode
            lockImage = sl.lockImage
            lockPos = sl.lockPos
            lockAll = sl.lockAll
            fillAlpha = sl.fillAlpha
            colorTag = sl.colorTag
            mask = sl.mask
            rawMask = sl.maskRaw
            maskDirty = sl.maskDirty
            maskEdit = sl.maskEditing
            strokeOn = sl.strokeOn
            strokeColor = sl.strokeColor
            strokeWidth = sl.strokeWidth
            shadowOn = sl.shadowOn
            shadowDX = sl.shadowDX
            shadowDY = sl.shadowDY
            shadowBlur = sl.shadowBlur
            shadowColor = sl.shadowColor
            glowOn = sl.glowOn
            glowColor = sl.glowColor
            glowSize = sl.glowSize
            innerShadowOn = sl.innerShadowOn
            innerShadowColor = sl.innerShadowColor
            innerShadowDX = sl.innerShadowDX
            innerShadowDY = sl.innerShadowDY
            innerShadowSize = sl.innerShadowSize
            innerGlowOn = sl.innerGlowOn
            innerGlowColor = sl.innerGlowColor
            innerGlowSize = sl.innerGlowSize
            colorOverlayOn = sl.colorOverlayOn
            colorOverlayColor = sl.colorOverlayColor
            gradientOverlayOn = sl.gradientOverlayOn
            gradientOverlayColor1 = sl.gradientOverlayColor1
            gradientOverlayColor2 = sl.gradientOverlayColor2
            gradientOverlayAngle = sl.gradientOverlayAngle
            bevelOn = sl.bevelOn
            bevelSize = sl.bevelSize
            bevelDepth = sl.bevelDepth
            satinOn = sl.satinOn
            satinColor = sl.satinColor
            satinSize = sl.satinSize
            satinDistance = sl.satinDistance
            satinAngle = sl.satinAngle
            patternOverlayOn = sl.patternOverlayOn
            patternOverlayIndex = sl.patternOverlayIndex
            patternOverlayScale = sl.patternOverlayScale
            patternOverlayOpacity = sl.patternOverlayOpacity
            textureOn = sl.textureOn
            texturePatternIndex = sl.texturePatternIndex
            textureScale = sl.textureScale
            textureDepth = sl.textureDepth
            contourOn = sl.contourOn
            contourKind = sl.contourKind
            smartFxText = sl.smartFx
            adjust = sl.adjust
            rawHead = sl.rawHead
            rawPix = sl.rawPix
            pixHash = sl.pixHash
            pix16 = sl.pix16?.let { b -> com.reveal.editor.image.Pix16.unpack(b) }
            pix32 = sl.pix32?.let { b -> com.reveal.editor.image.Pix32.unpack(b) }
            smartSrc = sl.smartSrc
            sectionType = sl.sectionType
            textContent = sl.textContent
            textSize = sl.textSize
            textColor = sl.textColor
            textX = sl.textX
            textY = sl.textY
            textFont = sl.textFont
            textBold = sl.textBold
            textItalic = sl.textItalic
            textUnderline = sl.textUnderline
            textStrike = sl.textStrike
            textTracking = sl.textTracking
            textLeading = sl.textLeading
            textAlign = sl.textAlign
            textScaleX = sl.textScaleX
            textScaleY = sl.textScaleY
            textShift = sl.textShift
            textCaps = sl.textCaps
            textSuperSub = sl.textSuperSub
            textWarp = sl.textWarp
            textWarpBend = sl.textWarpBend
            textVertical = sl.textVertical
            textOnPath = sl.textOnPath
            textPathIndex = sl.textPathIndex
        }
    }
    // 老工程存盘时还没记位深（默认 8），这里用原文件的位深兜底，否则 16 位文档永远拿不到 16 位缓冲
    view.docDepth = depth
    view.setDocument(layers)
}

/**
 * 图层面板要显示的行（从上往下、带组的缩进）。
 * 原来写在 EditorScreen.refreshLayers 里，桌面模式也要用，所以抽出来。
 */
internal fun buildLayerRows(view: SketchView): List<LayerRow> {
    // layers 是从下往上存的，面板要从上往下显示；组在 PSD 里是「上面组头 + 下面组底分隔层」，
    // 中间夹的才是成员。这里按深度缩进，分隔层本身不显示（桌面端里也不显示）。
    val rows = ArrayList<LayerRow>(view.layers.size)
    var depth = 0
    var collapsedAt = -1
    for (index in view.layers.indices.reversed()) {
        val layer = view.layers[index]
        if (layer.sectionType == 3) {
            if (collapsedAt >= 0 && depth - 1 <= collapsedAt) collapsedAt = -1
            depth = (depth - 1).coerceAtLeast(0)
            continue
        }
        val isGroup = layer.sectionType == 1 || layer.sectionType == 2
        val hidden = collapsedAt >= 0 && depth > collapsedAt
        if (!hidden) {
            rows.add(
                LayerRow(
                    name = layer.name,
                    visible = layer.visible,
                    index = index,
                    active = index == view.activeIndex,
                    alphaLock = layer.alphaLock,
                    clipped = layer.clipped,
                    alpha = layer.alpha,
                    blendMode = layer.blendMode,
                    hasText = layer.textContent != null,
                    hasMask = layer.mask != null,
                    depth = depth,
                    isGroup = isGroup,
                    collapsed = layer.sectionType == 2,
                    lockImage = layer.lockImage,
                    lockPos = layer.lockPos,
                    lockAll = layer.lockAll,
                    fillAlpha = layer.fillAlpha,
                    colorTag = layer.colorTag,
                )
            )
        }
        if (isGroup) {
            if (layer.sectionType == 2 && collapsedAt < 0 && !hidden) collapsedAt = depth
            depth++
        }
    }
    return rows
}

internal fun collectSaveLayers(view: SketchView): List<SaveLayer> {
    val out = mutableListOf<SaveLayer>()
        for (layer in view.layers) {
            // 被换到磁盘上的层要先装回内存，不能跳过，否则保存/缩略图会丢层
            val bmp = view.needBitmap(layer) ?: continue
            out.add(
                SaveLayer(
                    bitmap = bmp,
                    name = layer.name,
                    visible = layer.visible,
                    alpha = layer.alpha,
                    alphaLock = layer.alphaLock,
                    clipped = layer.clipped,
                    blendMode = layer.blendMode,
                    mask = layer.mask,
                    maskEditing = layer.maskEdit,
                    smartSrc = layer.smartSrc,
                    textContent = layer.textContent,
                    textSize = layer.textSize,
                    textColor = layer.textColor,
                    textX = layer.textX,
                    textY = layer.textY,
                    textFont = layer.textFont,
                    textBold = layer.textBold,
                    textItalic = layer.textItalic,
                    textUnderline = layer.textUnderline,
                    textStrike = layer.textStrike,
                    textTracking = layer.textTracking,
                    textLeading = layer.textLeading,
                    textAlign = layer.textAlign,
                    textScaleX = layer.textScaleX,
                    textScaleY = layer.textScaleY,
                    textShift = layer.textShift,
                    textCaps = layer.textCaps,
                    textSuperSub = layer.textSuperSub,
                    textWarp = layer.textWarp,
                    textWarpBend = layer.textWarpBend,
                    textVertical = layer.textVertical,
                    textOnPath = layer.textOnPath,
                    textPathIndex = layer.textPathIndex,
                    strokeOn = layer.strokeOn,
                    strokeColor = layer.strokeColor,
                    strokeWidth = layer.strokeWidth,
                    shadowOn = layer.shadowOn,
                    shadowDX = layer.shadowDX,
                    shadowDY = layer.shadowDY,
                    shadowBlur = layer.shadowBlur,
                    shadowColor = layer.shadowColor,
                    glowOn = layer.glowOn,
                    glowColor = layer.glowColor,
                    glowSize = layer.glowSize,
                    innerShadowOn = layer.innerShadowOn,
                    innerShadowColor = layer.innerShadowColor,
                    innerShadowDX = layer.innerShadowDX,
                    innerShadowDY = layer.innerShadowDY,
                    innerShadowSize = layer.innerShadowSize,
                    innerGlowOn = layer.innerGlowOn,
                    innerGlowColor = layer.innerGlowColor,
                    innerGlowSize = layer.innerGlowSize,
                    colorOverlayOn = layer.colorOverlayOn,
                    colorOverlayColor = layer.colorOverlayColor,
                    gradientOverlayOn = layer.gradientOverlayOn,
                    gradientOverlayColor1 = layer.gradientOverlayColor1,
                    gradientOverlayColor2 = layer.gradientOverlayColor2,
                    gradientOverlayAngle = layer.gradientOverlayAngle,
                    bevelOn = layer.bevelOn,
                    bevelSize = layer.bevelSize,
                    bevelDepth = layer.bevelDepth,
                    satinOn = layer.satinOn,
                    satinColor = layer.satinColor,
                    satinSize = layer.satinSize,
                    satinDistance = layer.satinDistance,
                    satinAngle = layer.satinAngle,
                    patternOverlayOn = layer.patternOverlayOn,
                    patternOverlayIndex = layer.patternOverlayIndex,
                    patternOverlayScale = layer.patternOverlayScale,
                    patternOverlayOpacity = layer.patternOverlayOpacity,
                    textureOn = layer.textureOn,
                    texturePatternIndex = layer.texturePatternIndex,
                    textureScale = layer.textureScale,
                    textureDepth = layer.textureDepth,
                    contourOn = layer.contourOn,
                    contourKind = layer.contourKind,
                    smartFx = layer.smartFxText,
                    lockImage = layer.lockImage,
                    lockPos = layer.lockPos,
                    lockAll = layer.lockAll,
                    fillAlpha = layer.fillAlpha,
                    colorTag = layer.colorTag,
                    adjust = layer.adjust,
                    rawHead = layer.rawHead,
                    rawPix = layer.rawPix,
                    pixHash = layer.pixHash,
                    pix16 = layer.pix16?.let { p16 -> com.reveal.editor.image.Pix16.pack(p16) },
                    pix32 = layer.pix32?.let { p32 -> com.reveal.editor.image.Pix32.pack(p32) },
                    maskRaw = layer.rawMask,
                    maskDirty = layer.maskDirty,
                    sectionType = layer.sectionType,
                )
            )
        }
        return out
    
}
