package com.reveal.editor.ui

/**
 * 画笔预设（桌面端里「画笔设置」面板下面那一排）。
 *
 * 每一组预设只负责把参数一次性铺好；铺完仍然是普通画笔，可以再手动改。
 * 参数含义和 SketchView 里的一致，这里只做「一套数值」。
 */
internal data class BrushPreset(
    val name: String,
    val size: Float,
    val hardness: Float,
    val flow: Float,
    val spacing: Float,
    val roundness: Float,
    val angle: Float,
    val smooth: Float = 0f,
    val sizeJitter: Float = 0f,
    val roundJitter: Float = 0f,
    val flowJitter: Float = 0f,
    val minDiameter: Float = 0.1f,
    val wetEdge: Boolean = false,
    val texture: Int = -1,
    val textureMode: Int = 0,
    val textureDepth: Float = 0.5f,
    val pencil: Boolean = false,
)

internal object BrushPresets {
    val all: List<BrushPreset> = listOf(
        BrushPreset("硬边圆", 24f, 0.95f, 1f, 0.10f, 1f, 0f, smooth = 0.15f),
        BrushPreset("柔边圆", 40f, 0.15f, 0.85f, 0.12f, 1f, 0f, smooth = 0.2f),
        BrushPreset("铅笔", 8f, 0.98f, 1f, 0.06f, 1f, 0f, pencil = true),
        BrushPreset(
            "喷枪", 60f, 0.05f, 0.35f, 0.05f, 1f, 0f,
            smooth = 0.1f, flowJitter = 0.35f, minDiameter = 0.4f,
        ),
        BrushPreset(
            "油画", 36f, 0.85f, 0.8f, 0.18f, 0.7f, 35f,
            roundJitter = 0.2f, sizeJitter = 0.15f, wetEdge = true,
        ),
        BrushPreset(
            "粉笔", 26f, 0.9f, 0.7f, 0.22f, 0.85f, 20f,
            sizeJitter = 0.35f, texture = 5, textureMode = 0, textureDepth = 0.55f,
        ),
        BrushPreset(
            "水彩", 48f, 0.35f, 0.45f, 0.14f, 0.95f, 0f,
            smooth = 0.3f, flowJitter = 0.2f, wetEdge = true, texture = 7, textureDepth = 0.3f,
        ),
        BrushPreset(
            "马克笔", 30f, 0.75f, 1f, 0.08f, 0.45f, 0f,
            sizeJitter = 0f, roundJitter = 0f, texture = 1, textureDepth = 0.25f,
        ),
    )

    /** 把一条预设铺到画布上（默认铺到当前画笔工具） */
    fun apply(sketch: SketchView, p: BrushPreset) {
        sketch.brushSize = p.size
        sketch.brushHardness = p.hardness
        sketch.brushFlow = p.flow
        sketch.brushSpacing = p.spacing
        sketch.brushRoundness = p.roundness
        sketch.brushAngle = p.angle
        sketch.brushSmooth = p.smooth
        sketch.brushSizeJitter = p.sizeJitter
        sketch.brushRoundJitter = p.roundJitter
        sketch.brushFlowJitter = p.flowJitter
        sketch.brushMinDiameter = p.minDiameter
        sketch.brushWetEdge = p.wetEdge
        sketch.brushTexture = p.texture
        sketch.brushTextureMode = p.textureMode
        sketch.brushTextureDepth = p.textureDepth
        sketch.brushType = if (p.pencil) SketchView.BRUSH_PENCIL else SketchView.BRUSH_ROUND
        sketch.brushRev++
        sketch.invalidate()
    }

    /** 从当前参数里找出最像的那条预设（用来高亮），找不到返回 -1 */
    fun nearest(sketch: SketchView): Int {
        val pencil = sketch.brushType == SketchView.BRUSH_PENCIL
        return all.indexOfFirst { p ->
            p.pencil == pencil &&
                kotlin.math.abs(p.size - sketch.brushSize) < 0.6f &&
                kotlin.math.abs(p.hardness - sketch.brushHardness) < 0.06f
        }
    }
}