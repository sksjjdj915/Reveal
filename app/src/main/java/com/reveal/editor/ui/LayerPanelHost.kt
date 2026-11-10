package com.reveal.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * 图层面板的所有回调集中在这里。
 * 目的是把这一坨从 EditorScreen 的 @Composable 里搬走——它已经顶到 64KB 上限了。
 */
@Composable
internal fun LayerPanelHost(
    sketch: SketchView?,
    rows: List<LayerRow>,
    maskEditing: Boolean,
    onRefresh: () -> Unit,
    onToast: (String) -> Unit,
    onEditText: (Int) -> Unit,
    onLayerStyle: (Int) -> Unit,
    onMaskEditing: (Boolean) -> Unit,
    onAdjustLayer: (Int) -> Unit,
    onPlaceImage: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    LayerPanel(
        rows = rows,
        onSelect = { index ->
            sketch?.selectLayer(index)
            onRefresh()
            onAdjustLayer(index)
        },
        onToggle = { index ->
            val view = sketch
            val layer = view?.layers?.getOrNull(index)
            if (view != null && layer != null) {
                // 组头点了眼睛 = 整组一起显示 / 隐藏
                if (layer.sectionType == 1 || layer.sectionType == 2) {
                    view.setGroupVisible(index, !layer.visible)
                } else {
                    view.setLayerVisible(index, !layer.visible)
                }
                onRefresh()
            }
        },
        onToggleCollapse = { index ->
            sketch?.toggleGroupCollapsed(index)
            onRefresh()
        },
        onAddGroup = {
            if (sketch?.addGroup() == -1) onToast("画布是空的")
            onRefresh()
        },
        onPlaceImage = onPlaceImage,
        onLock = { index, kind ->
            val view = sketch
            val layer = view?.layers?.getOrNull(index)
            if (view != null && layer != null) {
                val cur = if (kind == 0) layer.lockImage else if (kind == 1) layer.lockPos else layer.lockAll
                view.setLayerLock(index, kind, !cur)
                onRefresh()
            }
        },
        onFill = { index, fill ->
            sketch?.setLayerFill(index, fill)
            onRefresh()
        },
        onColorTag = { index, tag ->
            sketch?.setLayerColorTag(index, tag)
            onRefresh()
        },
        onRasterize = { index ->
            sketch?.rasterizeLayer(index)
            onRefresh()
        },
        onSelectSimilar = { index ->
            val tag = sketch?.layers?.getOrNull(index)?.colorTag ?: 0
            val hits = sketch?.selectSimilarLayers(tag) ?: emptyList()
            if (hits.size > 1) onToast("同色标签 " + hits.size + " 层") else onToast("没有同色标签的层")
        },
        onAlphaLock = { index ->
            val view = sketch
            val layer = view?.layers?.getOrNull(index)
            if (view != null && layer != null) {
                view.setLayerAlphaLock(index, !layer.alphaLock)
                onRefresh()
            }
        },
        onClip = { index ->
            val view = sketch
            val layer = view?.layers?.getOrNull(index)
            if (view != null && layer != null) {
                if (!layer.clipped && index == 0) {
                    onToast("最下面那层没有可剪贴的底图")
                } else {
                    view.setLayerClipped(index, !layer.clipped)
                    onRefresh()
                }
            }
        },
        onAlpha = { index, alpha ->
            sketch?.setLayerAlpha(index, alpha)
            onRefresh()
        },
        maskEditing = maskEditing,
        onEditText = onEditText,
        onLayerStyle = onLayerStyle,
        onAddMask = {
            if (sketch?.addMask() == true) {
                sketch?.maskEditing = true
                onMaskEditing(true)
                onToast("蒙版已加：黑笔遮住（显示红），白笔还原")
            }
            onRefresh()
        },
        onDeleteMask = {
            sketch?.deleteMask()
            sketch?.maskEditing = false
            onMaskEditing(false)
            onRefresh()
        },
        onApplyMask = {
            sketch?.applyMask()
            sketch?.maskEditing = false
            onMaskEditing(false)
            onRefresh()
        },
        onToggleMaskEdit = {
            val next = !maskEditing
            sketch?.maskEditing = next
            onMaskEditing(next)
            onRefresh()
        },
        onBlendMode = { index, mode ->
            sketch?.setLayerBlendMode(index, mode)
            onRefresh()
        },
        onRename = { index, name ->
            sketch?.setLayerName(index, name)
            onRefresh()
        },
        onDuplicate = { index ->
            sketch?.duplicateLayer(index)
            onRefresh()
        },
        onMove = { from, to ->
            sketch?.moveLayer(from, to)
            onRefresh()
        },
        multiMode0 = sketch?.multiSelectMode ?: false,
        picked0 = sketch?.multiPicked?.toList() ?: emptyList(),
        onMultiState = { mode, list -> sketch?.setMultiState(mode, list) },
        onBatch = { action, list ->
            when (action) {
                "delete" -> if (sketch?.deleteLayersAt(list) != true) onToast("至少留一层")
                "group" -> if ((sketch?.groupLayersAt(list) ?: -1) < 0) onToast("至少要勾两层才能成组")
                "merge" -> if (sketch?.mergeLayersAt(list) != true) onToast("至少要勾两层才能合并")
                "show" -> sketch?.setLayersVisibleAt(list, true)
                "hide" -> sketch?.setLayersVisibleAt(list, false)
                "up" -> if (sketch?.moveLayersAt(list, true) != true) onToast("已经到顶了")
                "down" -> if (sketch?.moveLayersAt(list, false) != true) onToast("已经到底了")
            }
            onRefresh()
        },
        onAdd = {
            sketch?.addLayer()
            onRefresh()
        },
        onDelete = {
            val view = sketch
            val cur = view?.layers?.getOrNull(view.activeIndex)
            // 选中的是组 → 整组连内容一起删（跟 桌面端一样）
            val ok = if (cur != null && (cur.sectionType == 1 || cur.sectionType == 2)) {
                view.deleteGroupAt(view.activeIndex)
            } else {
                view?.deleteActiveLayer() == true
            }
            if (ok) onRefresh() else onToast("至少留一层")
        },
        onUp = {
            sketch?.moveActiveLayer(up = true)
            onRefresh()
        },
        onDown = {
            sketch?.moveActiveLayer(up = false)
            onRefresh()
        },
        onClose = onClose,
        modifier = Modifier.fillMaxWidth(),
    )
}