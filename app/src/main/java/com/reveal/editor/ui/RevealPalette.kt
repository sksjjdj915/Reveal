package com.reveal.editor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Reveal 浅色设计系统（0.69 起的统一配色与尺寸）。
 * 新写界面一律从这里取，不要再写死颜色。
 */
object Rv {
    // —— 面 ——
    val appBg = Color(0xFFF4F5F7)      // 应用底
    val surface = Color(0xFFFFFFFF)     // 卡片 / 面板
    val surfaceAlt = Color(0xFFF0F2F5)  // 次级面（输入框、chip 底）
    val rail = Color(0xFFFFFFFF)        // 左侧工具条
    val scrim = Color(0x33000000)       // 弹层遮罩

    // —— 线 ——
    val line = Color(0xFFE8EBEF)        // 分隔线
    val lineStrong = Color(0xFFD2D6DC)  // 描边

    // —— 字 ——
    val text = Color(0xFF22262B)        // 主文字
    val textSub = Color(0xFF6B7178)     // 次要文字
    val textFaint = Color(0xFF9AA0A6)   // 提示

    // —— 主色 ——
    val accent = Color(0xFF3A7BFF)      // 主色（选中、应用）
    val accentSoft = Color(0xFFE3EDFF)  // 主色浅底
    val accentText = Color(0xFF1F5BD8)  // 主色文字
    val danger = Color(0xFFFF3B30)
    val ok = Color(0xFF1FA463)
    val warn = Color(0xFFFF9F0A)

    // —— 圆角 ——
    val rSm = 8.dp
    val rMd = 12.dp
    val rLg = 18.dp
    val rXl = 24.dp

    // —— 间距 ——
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp

    // —— 动效（Material 3 标准时长） ——
    const val durFast = 150   // 触摸反馈
    const val durBase = 220   // 面板开合
    const val durSlow = 320   // 模式切换
}

/** Material 3 的强调缓动曲线（标准 cubic-bezier(0.2, 0, 0, 1)） */
val RvEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
