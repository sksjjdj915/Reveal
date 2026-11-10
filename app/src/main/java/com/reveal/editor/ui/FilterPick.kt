package com.reveal.editor.ui

/**
 * 「点画面选中心」的临时状态：面板打开取点模式时置 active，
 * 画布上的点击把归一化坐标回传给面板（0~1）。
 */
internal object FilterPick {
    var active: Boolean = false
    var onPick: ((Float, Float) -> Unit)? = null

    fun start(cb: (Float, Float) -> Unit) {
        active = true
        onPick = cb
    }

    fun stop() {
        active = false
        onPick = null
    }
}
