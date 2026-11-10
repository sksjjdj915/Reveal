package com.reveal.editor.ui

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 虚拟鼠标（照 UU 远程的用法）：
 *  - 一个小球浮在屏幕上，拖动球 → 指针按相对位移移动；
 *  - 点球展开 / 收起，展开后有「左键 / 右键 / 滚轮」三个按钮；
 *  - 左键单击 = 点一下；按住不放 = 按住拖动（画线）；
 *  - 右键 = 长按（画布上等于工具的右键行为）；
 *  - 滚轮 = 上下拖，画布上下滚。
 *
 * 指针事件是直接构造成 MotionEvent 塞给 SketchView 的，
 * 所以画笔画线、选区、变换这些逻辑一行都不用改。
 */
@Composable
internal fun VirtualMouseOverlay(
    target: SketchView?,
    zoomProvider: () -> Int = { 100 },
    onZoomTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var zoomPct by remember { mutableStateOf(zoomProvider()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            zoomPct = zoomProvider()
        }
    }
    var boxSize by remember { mutableStateOf(Offset.Zero) }
    var cursor by remember { mutableStateOf(Offset.Zero) }
    var ball by remember { mutableStateOf(Offset(-1f, -1f)) }
    var expanded by remember { mutableStateOf(false) }
    var small by remember { mutableStateOf(false) }
    // 指针速度：手臂动作大一点也走不远，点小图标才稳
    val speed = 1.8f
    val ballDp = if (small) 34.dp else 52.dp
    val ballPx = with(androidx.compose.ui.platform.LocalDensity.current) { ballDp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sz ->
                boxSize = Offset(sz.width.toFloat(), sz.height.toFloat())
                if (ball.x < 0f) ball = Offset(sz.width * 0.78f, sz.height * 0.62f)
                if (cursor == Offset.Zero) cursor = Offset(sz.width / 2f, sz.height / 2f)
            },
    ) {
        // 指针
        Canvas(Modifier.fillMaxSize()) {
            if (boxSize != Offset.Zero) {
                val p = Path().apply {
                    moveTo(cursor.x, cursor.y)
                    lineTo(cursor.x + 2f, cursor.y + 30f)
                    lineTo(cursor.x + 9f, cursor.y + 22f)
                    lineTo(cursor.x + 16f, cursor.y + 34f)
                    lineTo(cursor.x + 21f, cursor.y + 31f)
                    lineTo(cursor.x + 14f, cursor.y + 20f)
                    lineTo(cursor.x + 24f, cursor.y + 19f)
                    close()
                }
                drawPath(p, Color.White, style = Stroke(width = 3f))
                drawPath(p, Color(0xFFF0F2F5))
            }
        }

        // 展开后的三个按钮（在球的上方）
        if (expanded) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (ball.x - 96.dp.toPx()).roundToInt(),
                            (ball.y - 74.dp.toPx()).roundToInt(),
                        )
                    },
            ) {
                androidx.compose.foundation.layout.Row {
                    MouseChip("左键") { down ->
                        if (down > 0f) press(target, cursor) else release(target, cursor)
                    }
                    MouseChip("右键") { down ->
                        if (down > 0f) rightPress(target, cursor)
                    }
                    MouseChip("滚轮", wheel = true) { dy ->
                        target?.panView(0f, dy)
                    }
                }
            }
        }

        // 悬浮球
        Box(
            modifier = Modifier
                .offset { IntOffset(ball.x.roundToInt(), ball.y.roundToInt()) }
                .size(ballDp)
                .clip(CircleShape)
                .background(Color(0xCC2A2E36))
                .pointerInput(speed, ballDp) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { },
                        onDragCancel = { },
                    ) { change, drag ->
                        change.consume()
                        // 拖球 = 指针相对位移
                        cursor = Offset(
                            (cursor.x + drag.x * speed).coerceIn(0f, boxSize.x),
                            (cursor.y + drag.y * speed).coerceIn(0f, boxSize.y),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { expanded = !expanded })
                },
        ) {
            // 球中心那个小方块，表示可以拖
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (small) 8.dp else 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF23272E)),
            )
        }

        // 缩小 / 放大球的按钮（贴在球右下）
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (ball.x + ballPx - 10.dp.toPx()).roundToInt(),
                        (ball.y + ballPx - 10.dp.toPx()).roundToInt(),
                    )
                }
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0xAA1C1F26))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { small = !small })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (small) "＋" else "－", fontSize = 13.sp, color = Color(0xFF23272E))
        }

        // 缩放百分比（贴在球下方，点一下 = 适应屏幕）
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        ball.x.roundToInt(),
                        (ball.y + ballPx + 4.dp.toPx()).roundToInt(),
                    )
                }
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xAA1C1F26))
                .pointerInput(Unit) { detectTapGestures(onTap = { onZoomTap() }) }
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(zoomPct.toString() + "%", fontSize = 10.sp, color = Color(0xFF23272E))
        }
    }
}

@Composable
private fun MouseChip(label: String, wheel: Boolean = false, onEvent: (Float) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (held) Color(0xFF3D6EE0) else Color(0xCC2A2E36))
            .pointerInput(wheel) {
                if (wheel) {
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        onEvent(-dy * 1.6f)
                    }
                } else {
                    detectTapGestures(
                        onPress = {
                            held = true
                            onEvent(1f)
                            tryAwaitRelease()
                            held = false
                            onEvent(0f)
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF23272E))
    }
}

// ---- 事件注入 ----
private fun send(view: View, action: Int, x: Float, y: Float, downTime: Long, pointers: Int = 1) {
    val e = MotionEvent.obtain(downTime, System.currentTimeMillis(), action, x, y, 0)
    e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
    runCatching { view.dispatchTouchEvent(e) }
    e.recycle()
}

private var downTime = 0L

private fun press(view: View?, at: Offset) {
    val v = view ?: return
    downTime = System.currentTimeMillis()
    send(v, MotionEvent.ACTION_DOWN, at.x, at.y, downTime)
}

private fun release(view: View?, at: Offset) {
    val v = view ?: return
    send(v, MotionEvent.ACTION_UP, at.x, at.y, if (downTime == 0L) System.currentTimeMillis() else downTime)
    downTime = 0L
}

/** 右键：模拟长按（600ms 后抬起），触发工具原有长按行为 */
private fun rightPress(view: View?, at: Offset) {
    val v = view ?: return
    val t = System.currentTimeMillis()
    send(v, MotionEvent.ACTION_DOWN, at.x, at.y, t)
    v.postDelayed({
        send(v, MotionEvent.ACTION_UP, at.x, at.y, t)
    }, 620)
}