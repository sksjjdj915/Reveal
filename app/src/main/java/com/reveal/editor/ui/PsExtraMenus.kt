package com.reveal.editor.ui

import android.graphics.Bitmap
import com.reveal.editor.image.Filters

/**
 * 桌面端桌面版多出来的几组菜单：文字 / 滤镜 / 视图 / 窗口。
 * 单独放在这里是为了给 EditorScreen 那个巨型 composable 腾字节（已贴 64KB 上限）。
 */

internal fun textMenuGroup(
    hasDoc: Boolean,
    onTextTool: () -> Unit,
    onTextPanel: () -> Unit,
    onRasterize: () -> Unit,
): MenuGroup = MenuGroup(
    title = "文字",
    actions = listOf(
        MenuAction("文字工具", enabled = hasDoc) { onTextTool() },
        MenuAction("文字面板…", enabled = hasDoc) { onTextPanel() },
        MenuAction("栅格化文字", enabled = hasDoc, dividerBefore = true) { onRasterize() },
    ),
)

internal fun filterMenuGroup(
    hasDoc: Boolean,
    run: (String, (Bitmap) -> Bitmap?) -> Unit,
    onBlur: () -> Unit,
    onThreshold: () -> Unit,
): MenuGroup = MenuGroup(
    title = "滤镜",
    actions = listOf(
        MenuAction("反相", enabled = hasDoc) { run("反相") { b -> Filters.invert(b) } },
        MenuAction("去色", enabled = hasDoc) { run("去色") { b -> Filters.grayscale(b) } },
        MenuAction("高斯模糊…", enabled = hasDoc, dividerBefore = true) { onBlur() },
        MenuAction("锐化", enabled = hasDoc) { run("锐化") { b -> Filters.sharpen(b, 0.8f) } },
        MenuAction("阈值…", enabled = hasDoc) { onThreshold() },
        MenuAction("马赛克（12 像素）", enabled = hasDoc) {
            run("马赛克") { b -> Filters.mosaic(b, 12) }
        },
    ),
)

internal class ViewMenuProps(
    val sketch: SketchView?,
    val hasDoc: Boolean,
    val showGrid: Boolean,
    val pixelZoom: Boolean,
    val onOpenChannels: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onToggleGrid: () -> Unit,
    val onTogglePixelZoom: () -> Unit,
)

internal fun viewMenuGroup(p: ViewMenuProps): MenuGroup = MenuGroup(
    title = "视图",
    actions = listOf(
        MenuAction("适应画布", enabled = p.hasDoc) { p.sketch?.fitToView() },
        MenuAction("实际大小 1:1", enabled = p.hasDoc) { p.sketch?.zoomToActualSize() },
        MenuAction("通道…", enabled = p.hasDoc, dividerBefore = true) { p.onOpenChannels() },
        MenuAction("历史记录…", enabled = p.hasDoc) { p.onOpenHistory() },
        MenuAction("新建水平参考线", enabled = p.hasDoc) { p.sketch?.addGuideCenter(true) },
        MenuAction("新建垂直参考线", enabled = p.hasDoc) { p.sketch?.addGuideCenter(false) },
        MenuAction(
            label = if (p.sketch?.showGuides == false) "参考线：关" else "参考线：开",
            enabled = p.hasDoc,
        ) {
            val on = p.sketch?.showGuides != false
            p.sketch?.showGuides = !on
            p.sketch?.invalidate()
        },
        MenuAction(
            label = if (p.sketch?.snapGuides == false) "吸附参考线：关" else "吸附参考线：开",
            enabled = p.hasDoc,
        ) {
            val on = p.sketch?.snapGuides != false
            p.sketch?.snapGuides = !on
        },
        MenuAction("清除全部参考线", enabled = p.hasDoc && (p.sketch?.guideCount() ?: 0) > 0) {
            p.sketch?.clearGuides()
        },
        MenuAction(label = if (p.showGrid) "网格：开" else "网格：关", dividerBefore = true) {
            p.onToggleGrid()
        },
        MenuAction(label = if (p.sketch?.showRuler == true) "标尺：开" else "标尺：关") {
            val on = p.sketch?.showRuler == true
            p.sketch?.showRuler = !on
            p.sketch?.invalidate()
        },
        MenuAction(
            label = "网格间距：" + when (p.sketch?.gridStepPx ?: 0) {
                0 -> "自动"
                25 -> "25 px"
                50 -> "50 px"
                100 -> "100 px"
                else -> "200 px"
            },
            enabled = p.hasDoc,
        ) {
            val cur = p.sketch?.gridStepPx ?: 0
            val next = when (cur) {
                0 -> 25
                25 -> 50
                50 -> 100
                100 -> 200
                else -> 0
            }
            p.sketch?.gridStepPx = next
        },
        MenuAction("水平分布（等间距）", enabled = p.hasDoc) { p.sketch?.distributeLayers(true, true) },
        MenuAction("垂直分布（等间距）", enabled = p.hasDoc) { p.sketch?.distributeLayers(false, true) },
        MenuAction(label = if (p.pixelZoom) "像素化放大：开" else "像素化放大：关") {
            p.onTogglePixelZoom()
        },
    ),
)

internal fun windowMenuGroup(
    showLayers: Boolean,
    onToggleLayers: () -> Unit,
    onChannels: () -> Unit,
    onHistory: () -> Unit,
    onColor: () -> Unit,
    onActions: () -> Unit,
): MenuGroup = MenuGroup(
    title = "窗口",
    actions = listOf(
        MenuAction(if (showLayers) "隐藏图层面板" else "显示图层面板") { onToggleLayers() },
        MenuAction("通道") { onChannels() },
        MenuAction("历史记录") { onHistory() },
        MenuAction("颜色…") { onColor() },
        MenuAction("动作") { onActions() },
    ),
)