package com.reveal.editor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reveal.editor.data.ProjectStore
import com.reveal.editor.image.ColorAdjust

/**
 * 调色区：独立的图片调色页。
 *
 * 规则（和作画区解耦）：
 *  - 进来先看工程里存过的调色进度，没有就是空白；
 *  - 图只能通过「导入相册」或「从作画区导入」拿到，也可以按「+」加空白层；
 *  - 调色只作用当前选中的那一层，切层各调各的；
 *  - 调色区自己一套撤销重做，作画区的图层不受影响；
 *  - 关闭时把进度交给工程保存，应用时把结果写回作画区。
 */
@Composable
fun ColorAdjustDialog(
    merged: Bitmap?,
    layers: List<Bitmap>,
    initial: ProjectStore.AdjustProgress?,
    onCancel: (ProjectStore.AdjustProgress?) -> Unit,
    onApply: (List<Bitmap>, ProjectStore.AdjustProgress?) -> Unit,
) {
    val context = LocalContext.current

    var images by remember { mutableStateOf(initial?.images ?: emptyList()) }
    var visible by remember { mutableStateOf(initial?.visible ?: emptyList()) }
    var active by remember { mutableStateOf(initial?.active ?: 0) }
    var history by remember {
        mutableStateOf(
            listOf(initial?.params ?: emptyList())
        )
    }
    var cursor by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showBasic by remember { mutableStateOf(true) }
    var showFine by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var askSourceState by remember { mutableStateOf(false) }

    val paramsList = history[cursor]
    val activeParams = paramsList.getOrElse(active) { ColorAdjust.Params() }

    fun snapshot(): ProjectStore.AdjustProgress? {
        if (images.isEmpty()) return null
        return ProjectStore.AdjustProgress(images, paramsList, visible, active)
    }

    fun pushParams(newList: List<ColorAdjust.Params>) {
        history = history.subList(0, cursor + 1) + listOf(newList)
        cursor = history.size - 1
    }

    fun setActiveParams(p: ColorAdjust.Params) {
        val list = paramsList.toMutableList()
        while (list.size <= active) list.add(ColorAdjust.Params())
        list[active] = p
        pushParams(list)
    }

    fun loadImages(list: List<Bitmap>) {
        images = list
        visible = list.map { true }
        active = 0
        history = listOf(list.map { ColorAdjust.Params() })
        cursor = 0
        zoom = 1f
        offset = Offset.Zero
    }

    fun loadFromUri(uri: Uri) {
        val bmp = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return
        loadImages(listOf(bmp))
    }

    // 加空白层：尺寸跟第一层一致
    fun addBlankLayer() {
        val first = images.firstOrNull() ?: return
        val blank = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        val next = images + blank
        images = next
        visible = visible + true
        active = next.lastIndex
        pushParams(paramsList + ColorAdjust.Params())
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadFromUri(uri)
    }

    // 「+」再加一层：从相册挑一张添进现有图层
    val addLayerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        val next = images + bmp
        images = next
        visible = visible + true
        active = next.lastIndex
        history = listOf(next.map { ColorAdjust.Params() })
        cursor = 0
    }

    Dialog(onDismissRequest = { onCancel(snapshot()) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部：标题 + 撤销/重做 + 重置
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("调色", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { if (cursor > 0) cursor -= 1 },
                        enabled = cursor > 0,
                    ) { Text("撤销", fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (cursor < history.size - 1) cursor += 1 },
                        enabled = cursor < history.size - 1,
                    ) { Text("重做", fontSize = 12.sp) }
                    TextButton(onClick = { pushParams(images.indices.map { ColorAdjust.Params() }) }) {
                        Text("重置", fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                // 导入条
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TipChip("导入相册") { picker.launch("image/*") }
                    TipChip("从作画区导入") { askSourceState = true }
                    if (images.isNotEmpty()) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${images.size} 层 · 正在调第 ${active + 1} 层",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 上：预览（按图片比例铺满，可双指缩放拖动）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFFF0F2F5)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (images.isEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("这里是空的", fontSize = 13.sp, color = Color(0xFF6B7178))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "从上面导入一张图片，或者把作画区搬过来调",
                                fontSize = 11.sp,
                                color = Color(0xFF5A6068),
                            )
                        }
                    } else {
                        val previewBase = remember(images) { images.map { ColorAdjust.scaledForPreview(it, 700) } }
                        val shown = remember(previewBase, paramsList, visible) {
                            val drawn = previewBase.mapIndexed { i, bmp ->
                                if (!visible.getOrElse(i) { true }) return@mapIndexed null
                                ColorAdjust.apply(bmp, paramsList.getOrElse(i) { ColorAdjust.Params() }) ?: bmp
                            }.filterNotNull()
                            compositeOf(drawn)
                        }
                        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val ratio = shown.width.toFloat() / shown.height.toFloat()
                            var w = maxWidth
                            var h = w / ratio
                            if (h > maxHeight) {
                                h = maxHeight
                                w = h * ratio
                            }
                            Image(
                                bitmap = shown.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(w, h)
                                    .graphicsLayer(
                                        scaleX = zoom,
                                        scaleY = zoom,
                                        translationX = offset.x,
                                        translationY = offset.y,
                                    )
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, z, _ ->
                                            zoom = (zoom * z).coerceIn(1f, 6f)
                                            offset = if (zoom <= 1.01f) Offset.Zero else offset + pan
                                        }
                                    },
                            )
                        }
                    }
                }

                // 中：横向图层条
                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        images.forEachIndexed { index, bmp ->
                            LayerThumb(
                                bitmap = bmp,
                                active = index == active,
                                visible = visible.getOrElse(index) { true },
                                onClick = { active = index },
                                onToggle = {
                                    val v = visible.toMutableList()
                                    while (v.size <= index) v.add(true)
                                    v[index] = !v[index]
                                    visible = v
                                },
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(56.dp).clickable { addBlankLayer() },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, contentDescription = "加图层", modifier = Modifier.size(20.dp))
                            }
                        }
                        TextButton(onClick = { addLayerPicker.launch("image/*") }) {
                            Text("加图片层", fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }

                // 下：参数区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Section("亮", showBasic, onToggle = { showBasic = !showBasic }) {
                        SliderRow("曝光", activeParams.exposure) { setActiveParams(activeParams.copy(exposure = it)) }
                        SliderRow("对比度", activeParams.contrast) { setActiveParams(activeParams.copy(contrast = it)) }
                        SliderRow("高光", activeParams.highlights) { setActiveParams(activeParams.copy(highlights = it)) }
                        SliderRow("阴影", activeParams.shadows) { setActiveParams(activeParams.copy(shadows = it)) }
                        SliderRow("白色", activeParams.whites) { setActiveParams(activeParams.copy(whites = it)) }
                        SliderRow("黑色", activeParams.blacks) { setActiveParams(activeParams.copy(blacks = it)) }
                    }
                    Section("颜色", showFine, onToggle = { showFine = !showFine }) {
                        SliderRow("色温", activeParams.temperature) { setActiveParams(activeParams.copy(temperature = it)) }
                        SliderRow("色调", activeParams.tint) { setActiveParams(activeParams.copy(tint = it)) }
                        SliderRow("自然饱和度", activeParams.vibrance) { setActiveParams(activeParams.copy(vibrance = it)) }
                        SliderRow("饱和度", activeParams.saturation) { setActiveParams(activeParams.copy(saturation = it)) }
                        SliderRow("色相", activeParams.hue) { setActiveParams(activeParams.copy(hue = it)) }
                    }
                    Section("效果", showFilter, onToggle = { showFilter = !showFilter }) {
                        SliderRow("清晰度", activeParams.clarity) { setActiveParams(activeParams.copy(clarity = it)) }
                        SliderRow("去朦胧", activeParams.dehaze) { setActiveParams(activeParams.copy(dehaze = it)) }
                        SliderRow("暗角", activeParams.vignette) { setActiveParams(activeParams.copy(vignette = it)) }
                        SliderRow("颗粒", activeParams.grain) { setActiveParams(activeParams.copy(grain = it)) }
                    }
                    Section("细节", showAi, onToggle = { showAi = !showAi }) {
                        SliderRow("锐化", activeParams.sharpening) { setActiveParams(activeParams.copy(sharpening = it)) }
                        SliderRow("减少杂色", activeParams.noiseReduction) { setActiveParams(activeParams.copy(noiseReduction = it)) }
                    }
                }

                // 底部操作
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onCancel(snapshot()) }) { Text("取消") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (images.isEmpty()) return@TextButton
                            val adjusted = images.mapIndexed { i, img ->
                                ColorAdjust.apply(img, paramsList.getOrElse(i) { ColorAdjust.Params() }) ?: img
                            }
                            onApply(adjusted, snapshot())
                        },
                        enabled = images.isNotEmpty(),
                    ) { Text("应用", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    // 从作画区导入：先问合并还是一层层
    if (askSourceState) {
        Dialog(onDismissRequest = { askSourceState = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("从作画区导入", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "合并成一张：所有图层压成一张图导进来。\n逐层导入：作画区每个图层各占一层，进来后可以分开调。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    TextButton(
                        onClick = {
                            askSourceState = false
                            if (merged != null) loadImages(listOf(merged))
                        },
                    ) { Text("合并成一张", fontSize = 13.sp) }
                    TextButton(
                        onClick = {
                            askSourceState = false
                            if (layers.isNotEmpty()) loadImages(layers)
                        },
                    ) { Text("逐层导入（${layers.size} 层）", fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun LayerThumb(
    bitmap: Bitmap,
    active: Boolean,
    visible: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFFFFFF))
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary else Color(0xFFE8EBEF),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable { onClick() },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer(alpha = if (visible) 1f else 0.3f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x99000000))
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = "显示/隐藏",
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private fun compositeOf(images: List<Bitmap>): Bitmap {
    if (images.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    if (images.size == 1) return images.first()
    val w = images.maxOf { it.width }
    val h = images.maxOf { it.height }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    images.forEach { canvas.drawBitmap(it, 0f, 0f, paint) }
    return out
}

@Composable
private fun TipChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() },
    ) {
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun Section(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开", fontSize = 11.sp) }
    }
    if (expanded) {
        content()
        Spacer(modifier = Modifier.height(6.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(String.format("%.2f", value), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = -1f..1f)
    }
}
