package com.reveal.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 菜单里的一条。dividerBefore=true 时在这条上面画一条分隔线。 */
class MenuAction(
    val label: String,
    val enabled: Boolean = true,
    val dividerBefore: Boolean = false,
    val children: List<MenuAction> = emptyList(),
    val onClick: () -> Unit = {},
)

/** 一个菜单（文件 / 编辑 / …）。 */
class MenuGroup(
    val title: String,
    val actions: List<MenuAction>,
)

private fun menuAccel(title: String): String = when (title) {
    "文件" -> "文件(F)"
    "编辑" -> "编辑(E)"
    "图像" -> "图像(I)"
    "图层" -> "图层(L)"
    "文字" -> "文字(Y)"
    "选择" -> "选择(S)"
    "滤镜" -> "滤镜(T)"
    "视图" -> "视图(V)"
    "窗口" -> "窗口(W)"
    "帮助" -> "帮助(H)"
    else -> title
}

/**
 * 桌面端那样的顶部菜单栏：一排菜单名，点开往下弹列表。
 * 横向可滚动，小屏也不会挤成一团。
 */
@Composable
fun PsMenuBar(
    menus: List<MenuGroup>,
    modifier: Modifier = Modifier,
    psStyle: Boolean = false,
) {
    var openIndex by remember { mutableIntStateOf(-1) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        menus.forEachIndexed { index, menu ->
            Box {
                Text(
                    text = menuAccel(menu.title),
                    fontSize = if (psStyle) 11.sp else 12.sp,
                    fontWeight = if (openIndex == index) FontWeight.Medium else FontWeight.Normal,
                    color = if (psStyle) {
                        if (openIndex == index) Color.White else Color(0xFFD2D6DC)
                    } else if (openIndex == index) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable { openIndex = if (openIndex == index) -1 else index }
                        .padding(horizontal = if (psStyle) 8.dp else 10.dp, vertical = if (psStyle) 4.dp else 6.dp),
                )
                DropdownMenu(
                    expanded = openIndex == index,
                    onDismissRequest = { openIndex = -1 },
                    modifier = Modifier.background(if (psStyle) Color(0xFFDDE1E6) else MaterialTheme.colorScheme.surface),
                ) {
                    menu.actions.forEach { action ->
                        if (action.dividerBefore) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            )
                        }
                        MenuLeaf(action, psStyle) { openIndex = -1 }
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

/** 一条菜单项；带 children 时当二级菜单展开 */
@Composable
private fun MenuLeaf(action: MenuAction, psStyle: Boolean, closeAll: () -> Unit) {
    var sub by remember { mutableStateOf(false) }
    Box {
        DropdownMenuItem(
            text = {
                Text(
                    text = if (action.children.isEmpty()) action.label else action.label + "  ›",
                    fontSize = 12.sp,
                    color = if (psStyle) {
                        if (action.enabled) Color(0xFF2A2F35) else Color(0xFF5A6068)
                    } else if (action.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            },
            enabled = action.enabled,
            onClick = {
                if (action.children.isEmpty()) {
                    closeAll()
                    action.onClick()
                } else {
                    sub = true
                }
            },
        )
        DropdownMenu(
            expanded = sub,
            onDismissRequest = { sub = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        ) {
            action.children.forEach { child ->
                if (child.dividerBefore) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
                DropdownMenuItem(
                    text = { Text(child.label, fontSize = 12.sp) },
                    enabled = child.enabled,
                    onClick = {
                        sub = false
                        closeAll()
                        child.onClick()
                    },
                )
            }
        }
    }
}
