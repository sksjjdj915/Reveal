package com.reveal.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部「自由变换」条：角度滑块 + 提示 + 透视 / 复位 / 取消 / 应用。
 * 单独放一个文件，EditorScreen 那个组合函数已经贴着 JVM 64KB 方法上限了。
 */
@Composable
internal fun TransformBar(
    perspMode: Boolean,
    angle: Float,
    onAngle: (Float) -> Unit,
    onResetAngle: () -> Unit,
    onTogglePersp: () -> Unit,
    onResetPersp: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {

    // 变换接管底部这一条：应用 / 取消 一定在，不会被参数条压住
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFFFFFFF),
            contentColor = Color(0xFF22262B),
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (perspMode) "透视变换" else "自由变换",
                        fontSize = 12.sp,
                        color = if (perspMode) Color(0xFF7AB8FF) else Color(0xFFE8C27A),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "角度 ${angle.toInt()}\u00b0",
                        fontSize = 10.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier.width(52.dp),
                    )
                    ThinSlider(
                        value = angle,
                        range = -180f..180f,
                        modifier = Modifier.width(112.dp),
                        onChange = onAngle,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "归零",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier
                            .clickable { onResetAngle() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (perspMode) "拖四角各自移动 \u00b7 三指缩画布"
                        else "拖角等比 \u00b7 拖边拉伸 \u00b7 双指缩放旋转 \u00b7 双击确认",
                        fontSize = 9.5.sp,
                        color = Color(0xFF6B7178),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (perspMode) "切自由" else "切透视",
                        fontSize = 11.sp,
                        color = Color(0xFF7AB8FF),
                        modifier = Modifier
                            .clickable { onTogglePersp() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    if (perspMode) {
                        Text(
                            "复位",
                            fontSize = 11.sp,
                            color = Color(0xFF6B7178),
                            modifier = Modifier
                                .clickable { onResetPersp() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        "取消",
                        fontSize = 12.sp,
                        color = Color(0xFF6B7178),
                        modifier = Modifier
                            .clickable { onCancel() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        "应用",
                        fontSize = 12.sp,
                        color = Color(0xFF7AB8FF),
                        modifier = Modifier
                            .clickable { onApply() }
                            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}
