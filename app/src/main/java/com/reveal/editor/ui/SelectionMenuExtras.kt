package com.reveal.editor.ui

/**
 * 「选择」菜单整组。
 *
 * 为什么放在单独文件：EditorScreen 是一个巨型 composable，已经贴着 JVM 单方法 64KB 上限，
 * 往它里面再内联几十字节就编译不过（Method too large: EditorScreenKt.EditorScreen）。
 * 这里做成普通顶层函数，EditorScreen 里只留一句调用。
 */
internal fun selectionMenuGroup(
    hasDoc: Boolean,
    hasSelection: Boolean,
    sketch: SketchView?,
    onFeather: () -> Unit,
    toast: (String) -> Unit,
): MenuGroup = MenuGroup(
    title = "选择",
    actions = listOf(
        MenuAction("全选", enabled = hasDoc) { sketch?.selectAll() },
        MenuAction("反选", enabled = hasDoc) { sketch?.invertSelection() },
        MenuAction("取消选区", enabled = hasSelection) { sketch?.clearSelection() },
        MenuAction("羽化…", enabled = hasSelection, dividerBefore = true) { onFeather() },
        MenuAction("扩大选区 10 像素", enabled = hasSelection) {
            sketch?.growSelection(10); toast("选区已扩大")
        },
        MenuAction("收缩选区 10 像素", enabled = hasSelection) {
            sketch?.shrinkSelection(10); toast("选区已收缩")
        },
        MenuAction("存储选区", enabled = hasSelection, dividerBefore = true) {
            val view = sketch
            if (view == null || !view.hasSelection()) {
                toast("先框选一块，再存储")
            } else {
                view.saveSelection()
                toast("选区已存储（可再从「载入选区」取回）")
            }
        },
        MenuAction("载入选区") {
            val view = sketch
            val n = view?.savedSelections?.size ?: 0
            if (view == null || n <= 0) {
                toast("还没有存储过选区")
            } else if (view.loadSelection(n - 1)) {
                toast("已载入上次存储的选区")
            } else {
                toast("载入失败")
            }
        },
    ),
)