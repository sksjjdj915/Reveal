package com.reveal.editor.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.BlendMode
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.reveal.editor.image.AdjustOp
import com.reveal.editor.image.Levels
import com.reveal.editor.image.Perspective
import com.reveal.editor.image.SelectMode
import com.reveal.editor.image.SelectOp
import com.reveal.editor.image.BundledFonts
import com.reveal.editor.image.Selection
import com.reveal.editor.image.Flood
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 画布：单图层绘制（第 2 步换多图层），支持画笔 / 橡皮 / 吸管 / 移动，
 * 双指缩放旋转平移，双指轻点撤销，三指轻点重做。
 * 撤销用「脏矩形快照」：只存被画到的那一小块像素的前后两份，内存随涂抹面积增长。
 */
class SketchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool {
        BRUSH, ERASER, EYEDROPPER, MOVE, SELECT, TEXT, TRANSFORM, BUCKET, CLONE, HEAL,
        GRADIENT, DODGE, BURN, SMUDGE, LIQUIFY, WARP, SHAPE, PEN, VANISH,
        REPLACE, MIXER, ERASER_BG, ERASER_MAGIC, SPONGE, PATTERN, SPOT, REDEYE, HISTORY_BRUSH, ART_HISTORY,
        REGION,
    }

    /**
     * 形状层参数：这一层是可调的形状，不是烧死的像素。
     * 改任何一项都按这个重画整层，所以「随时能调、实时能看变化」。
     */
    class ShapeSpec(
        var kind: Int = 0,
        var x0: Float = 0f,
        var y0: Float = 0f,
        var x1: Float = 0f,
        var y1: Float = 0f,
        var corner: Int = 16,
        var sides: Int = 6,
        var inner: Int = 45,
        /** 椭圆专用：true = 正圆（横纵直径强制相等） */
        var circle: Boolean = false,
        var fill: Boolean = false,
        var width: Int = 0,
        var color: Int = 0xFFEFF1F4.toInt(),
        var alpha: Int = 255,
    ) {
        fun copyOf(): ShapeSpec = ShapeSpec(kind, x0, y0, x1, y1, corner, sides, inner, circle, fill, width, color, alpha)
    }

    /**
     * 渐变层参数：渐变照原样画进当前图层（不新建层），但这里记住这次用的参数，
     * 加上画之前的像素快照，就能「选中这层 → 改颜色/方向 → 整层重画」。
     */
    class GradientSpec(
        var x0: Float = 0f,
        var y0: Float = 0f,
        var x1: Float = 0f,
        var y1: Float = 0f,
        var radial: Boolean = false,
        var c1: Int = 0xFF000000.toInt(),
        var c2: Int = 0x00000000,
        /** 平滑度 0~100：0 = 线性，100 = S 形缓入缓出（桌面端的「平滑度」） */
        var smooth: Int = 0,
        /** 反向：两端颜色对调 */
        var reverse: Boolean = false,
        /** 缩放 10~300（%）：过渡带长度，100 = 从起点到终点 */
        var scale: Int = 100,
        /** 只画在起点到终点之间：两侧不再延伸成纯色（默认关，开=像 桌面端的「仅限范围内」） */
        var clip: Boolean = false,
    ) {
        fun copyOf(): GradientSpec = GradientSpec(x0, y0, x1, y1, radial, c1, c2, smooth, reverse, scale, clip)
    }

    class Layer(
        initial: Bitmap?,
        var name: String = "图层",
    ) {
        /** 组样式：整组拍平出来的缓冲图 + 缓存键（只有组头会用） */
        var groupCache: Bitmap? = null
        var groupKey: Long = -1L
        /** 组样式用的临时层（借它跑 styleOverlay/styleTop 的缓存） */
        var groupProxy: Layer? = null
        var groupProxyStamp: Int = Int.MIN_VALUE
        /** 最近一次渐变的参数（没有渐变就是 null） */
        var gradSpec: GradientSpec? = null
        /** 画这次渐变之前的像素，用来回改时还原 */
        var gradBase: IntArray? = null
        /** 记下应用渐变时的像素版本，之后别处画过东西就对不上了 */
        var gradStamp: Int = -1
        /** 图层像素；被换到磁盘上时为 null，用 tiles 取像素 */
        var bitmap: Bitmap? = initial
            set(value) {
                field = value
                if (value != null) {
                    tiles = null
                    pixelId++
                }
            }

        /** 分块存储：非 null 表示像素在磁盘上（大图省内存） */
        internal var tiles: com.reveal.editor.image.LayerTiles? = null

        /** 像素身份号：换进换出时变，绘制缓存靠它判断是否过期 */
        var pixelId: Int = 1

        var visible: Boolean = true
        var alpha: Int = 255

        /** 阿尔法锁定：笔迹只作用在已有像素上，透明区域涂不上 */
        var alphaLock: Boolean = false

        /** 调整层：非 null 表示这层不画像素，而是把它下面的图层一起改色 */
        var adjust: AdjustOp? = null

        /** 形状层：非 null 表示这层是可调参数的形状，改参数就重画 */
        var shapeSpec: ShapeSpec? = null

        /** 形状参数版本号：参数条靠它知道自己该读新值 */
        var shapeVer: Int = 0

        /** 从 PSD 打开的层：原始头部记录（字节）。属性没改的话导出照搬，不认识的块也不丢 */
        var rawHead: ByteArray? = null

    /** 16 位原始通道数据（Psd.packPix 的字节）：像素没动过就原样搬回，精度不掉 */
    var rawPix: ByteArray? = null

    /** 导入时的像素指纹；和导出时算的一致才算「这层没被画过」 */
    var pixHash: Int = 0

    /** 16 位像素缓冲：16 位文档里被画过之后才有，导出时按这里的数据写回 */
    internal var pix16: com.reveal.editor.image.Pix16? = null
    internal var pix32: com.reveal.editor.image.Pix32? = null

        /** 图层组标记：0=普通层，1=组展开，2=组折叠，3=组底分隔层（跟 PSD 的 lsct 一致） */
        var sectionType: Int = 0

        /** 剪贴蒙版：只显示在下面那一层的像素范围内 */
        var clipped: Boolean = false

        /** 混合模式，见 BLEND_* 常量 */
        var blendMode: Int = BLEND_NORMAL

        /** 蒙版：白色不透明处显示、透明处遮住；和图层同尺寸 */
        var mask: Bitmap? = null
        /** 该层上次是不是在「画蒙版」状态，随工程存 */
        var maskEdit: Boolean = false
        var maskVer: Int = 0
        /** 蒙版被画过：导出时不能再拿 桌面端的原始蒙版通道 */
        var maskDirty: Boolean = false
        /** 桌面端原始蒙版通道（导出时原样写回，保住 16 位蒙版）*/
        var rawMask: ByteArray? = null

        /** 图层样式：描边 */
        var strokeOn: Boolean = false
        var strokeColor: Int = 0xFF000000.toInt()
        var strokeWidth: Int = 6

        /** 图层样式：投影 */
        var shadowOn: Boolean = false
        var shadowDX: Int = 8
        var shadowDY: Int = 8
        var shadowBlur: Int = 8
        var shadowColor: Int = 0x99000000.toInt()

        /** 图层样式：外发光 */
        var glowOn: Boolean = false
        var glowColor: Int = 0xCCFFE066.toInt()
        var glowSize: Int = 8

        /** 图层样式：内阴影 */
        var innerShadowOn: Boolean = false
        var innerShadowColor: Int = 0x99000000.toInt()
        var innerShadowDX: Int = 4
        var innerShadowDY: Int = 4
        var innerShadowSize: Int = 6

        /** 图层样式：内发光 */
        var innerGlowOn: Boolean = false
        var innerGlowColor: Int = 0xCCFFF3B0.toInt()
        var innerGlowSize: Int = 6

        /** 图层样式：颜色叠加 / 渐变叠加 */
        var colorOverlayOn: Boolean = false
        var colorOverlayColor: Int = 0xFFFF3B30.toInt()
        var gradientOverlayOn: Boolean = false
        var gradientOverlayColor1: Int = 0xFFFF9F0A.toInt()
        var gradientOverlayColor2: Int = 0xFF3A7BFF.toInt()
        var gradientOverlayAngle: Int = 90

        /** 图层样式：斜面和浮雕 */
        var bevelOn: Boolean = false
        var bevelSize: Int = 6
        var bevelDepth: Int = 120

        /** 图层样式：光泽 / 图案叠加 */
        var satinOn: Boolean = false
        var satinColor: Int = 0x99000000.toInt()
        var satinSize: Int = 10
        var satinDistance: Int = 12
        var satinAngle: Int = 19
        var patternOverlayOn: Boolean = false
        var patternOverlayIndex: Int = 0
        var patternOverlayScale: Int = 100
        var patternOverlayOpacity: Int = 100

        /**
         * 智能对象：原始像素留一份，之后的每次缩放/旋转都从这份原图重新采样，
         * 所以反复变十次也不会越变越糊（普通图层是拿已经糊掉的图再糊一次）。
         */
        var smartSrc: Bitmap? = null
        var smartMatrix: Matrix? = null
        /** 图层样式：纹理（用图案明暗压出凹凸） */
        var textureOn: Boolean = false
        var texturePatternIndex: Int = 0
        var textureScale: Int = 100
        var textureDepth: Int = 50

        /** 图层样式：等高线（把样式的浓淡按曲线重新分配） */
        var contourOn: Boolean = false
        var contourKind: Int = 2

        /** 网格变形：开着的时候按 warpPts 的 4×4 控制点扭曲画面 */
        var warpOn: Boolean = false
        /** 16 个控制点，顺序为行优先，值是相对图层宽高的 0..1 */
        var warpPts: FloatArray? = null

        /** 智能滤镜：非破坏性滤镜栈（来自动作录制的步骤），像素本身不动 */
        internal val smartFx: MutableList<StepRec> = mutableListOf()
        /** 智能滤镜总开关：关掉就显示原始像素 */
        var smartFxOn: Boolean = true
        /** 滤镜栈版本号：改动一次 +1，用来作废缓存 */
        var smartFxVer: Int = 0
        /** 算好的滤镜结果缓存，键 = pixelId*31 + smartFxVer */
        internal var smartCache: Bitmap? = null
        internal var smartCacheKey: Int = -1
        /** 智能滤镜的存档形态：一行一步，存进工程 / 从工程读回 */
        internal var smartFxText: String
            get() = smartFx.joinToString("\n") { it.encode() }
            set(value) {
                smartFx.clear()
                for (line in value.split("\n")) {
                    if (line.isBlank()) continue
                    val rec = StepRec.decode(line) ?: continue
                    smartFx.add(rec)
                }
                smartFxVer++
            }

        /** 文字层元数据（非文字层为 null），用于重新编辑 */
        var textContent: String? = null
        var textSize: Float = 0f
        var textColor: Int = 0
        var textX: Float = 0f
        var textY: Float = 0f
        /** 字体：Android 家族名（sans-serif / serif / monospace / cursive / sans-serif-condensed） */
        var textFont: String = ""
        /** 文字排版（桌面端字符/段落面板那些项） */
        var textBold: Boolean = false
        var textItalic: Boolean = false
        var textUnderline: Boolean = false
        var textStrike: Boolean = false
        var textTracking: Float = 0f
        var textLeading: Float = 0f
        var textAlign: Int = 1
        var textScaleX: Float = 100f
        var textScaleY: Float = 100f
        var textShift: Float = 0f
        var textCaps: Int = 0
        var textSuperSub: Int = 0
        var textWarp: Int = 0
        var textWarpBend: Float = 50f
        var textVertical: Boolean = false
        /** 路径文字：沿哪条路径排（0 = 不沿路径） */
        var textOnPath: Boolean = false
        var textPathIndex: Int = 0

        /** 样式缓存（内容或参数变了就作废重算） */
        var styleVer: Int = 0
        var styleKey: Int = -1
        var styleCache: Bitmap? = null
        /** 压在图层上面的那些效果（颜色叠加/渐变叠加/内阴影/内发光/浮雕）单独缓存 */
        var topKey: Int = -1
        var topCache: Bitmap? = null

        /** 缩小显示用的金字塔：缩到 1/2、1/4，画小图时不必每帧采样全尺寸位图 */
        var mip2: Bitmap? = null
        var mip4: Bitmap? = null
        /** 锁定：图像像素（不能画）/ 位置（不能移）/ 全部 */
        var lockImage: Boolean = false
        var lockPos: Boolean = false
        var lockAll: Boolean = false
        /** 填充不透明度（0..100），只影响像素内容，不影响图层样式 */
        var fillAlpha: Int = 100
        /** 颜色标签：0 无，1 红 2 橙 3 黄 4 绿 5 蓝 6 紫 */
        var colorTag: Int = 0
    }

    // ---- 渐变填充 ----
    private val gradientRadialS = androidx.compose.runtime.mutableStateOf(false)
    var gradientRadial: Boolean
        get() = gradientRadialS.value
        set(v) { gradientRadialS.value = v }
    var gradientColor1: Int = 0xFF000000.toInt()
    var gradientColor2: Int = 0x00FFFFFF
    /** 渐变平滑度 0~100 */
    var gradientSmooth: Int = 0
    /** 渐变反向 */
    var gradientReverse: Boolean = false
    /** 渐变缩放（%）10~300 */
    var gradientScale: Int = 100
    /** 只画在起点到终点之间（关掉就是两端一直延伸成纯色） */
    var gradientClip: Boolean = false
    /** 改渐变的临时图与拖动前的像素快照（每帧复用，避免每次拖滑块都新建大位图） */
    private var gradScratch: Bitmap? = null
    private var gradEditPre: IntArray? = null
    private var gradActive = false
    private var gradX0 = 0f
    private var gradY0 = 0f
    private var gradX1 = 0f
    private var gradY1 = 0f
    private val gradGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xCC4F8CFF.toInt()
    }

    /** 每个绘制工具各自记住自己的笔刷大小（用户要求：不要所有工具共用一个画笔大小） */
    private val toolSizes = HashMap<Tool, Float>()

    var tool: Tool = Tool.BRUSH
        set(v) {
            if (v == field) return
            toolSizes[field] = brushSize
            field = v
            toolSizes[v]?.let { brushSize = it }
            invalidate()
        }

    // ── 桌面模式（远程桌面那种感觉）：手指只移动光标，不落笔；轻点才等于真的点一下 ──
    /** 鼠标模式：开=拖动只移光标，关=手指直接画 */
    var mouseMode: Boolean = false
    /** 光标位置（View 坐标，也就是屏幕像素） */
    var cursorSx: Float = -1f
    var cursorSy: Float = -1f
    var cursorVisible: Boolean = false
    private var mouseDownX = 0f
    private var mouseDownY = 0f
    private var mouseMoved = false
    private var injectingTap = false

    /** 手指按到画布上（用来收起底部参数条/面板） */
    var onCanvasDown: (() -> Unit)? = null

    /** 缩放变了通知外面（桌面模式的状态栏/数值面板靠它刷新，不用轮询） */
    var onZoomChanged: ((Int) -> Unit)? = null

    /** 上次通知出去的缩放百分比/时间：两个手指搓的时候不能每个事件都往 Compose 里写，
     *  否则整屏重组把画面卡住（表现就是手指离开后才慢慢缩放） */
    private var lastZoomPct = -1
    private var lastZoomMs = 0L
    /** 正在两指缩放：这期间不画选区覆盖层，也不往 Compose 写状态（否则画面卡住） */
    private var zooming = false
    /** 这次按下之前的状态快照：第二根手指落下时逐项退回，做到「缩放不产生任何副作用」 */
    private var prevCloneSource: PointF? = null
    private var prevBrushColor = 0
    private var bucketPending = false
    private var pinchAborted = false

    private fun notifyZoom(force: Boolean = false) {
        val pct = viewScalePercent()
        if (!force) {
            if (zooming) return
            if (pct == lastZoomPct) return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastZoomMs < 120L) return
            lastZoomMs = now
        }
        lastZoomPct = pct
        onZoomChanged?.invoke(pct)
    }

    /** 只重画光标附近两块小区域，别整张 2048 画布重绘 */
    private fun invalidateCursor(oldX: Float, oldY: Float) {
        val pad = 48f
        val l = (minOf(oldX, cursorSx) - pad).coerceAtLeast(0f)
        val t = (minOf(oldY, cursorSy) - pad).coerceAtLeast(0f)
        val r = (maxOf(oldX, cursorSx) + pad).coerceAtMost(width.toFloat())
        val b = (maxOf(oldY, cursorSy) + pad).coerceAtMost(height.toFloat())
        if (r > l && b > t) invalidate(l.toInt(), t.toInt(), r.toInt(), b.toInt()) else invalidate()
    }

    /**
     * 把文档坐标里的一块映射成屏幕矩形，只重画这一块。
     * 落笔时每帧其实只有「这一小段笔迹」变了，整屏 invalidate 会连带把
     * 合成缓存（整屏）和预览（整屏）都重新采样一遍：缩到整幅画时这就是主要开销。
     */
    private val spacingTmp = FloatArray(9)

    /**
     * 笔头之间的关系按「屏幕像素」算才有意义：文档里隔 3px、屏幕上只隔 0.2px 的两次盖章，
     * 画出来是同一颗像素，纯属白干。缩放越小，这个下限在文档坐标里就越大，盖章次数随之下降。
     */
    private fun minSpacingDoc(): Float {
        viewMatrix.getValues(spacingTmp)
        val z = spacingTmp[android.graphics.Matrix.MSCALE_X]
        return 1.2f / z.coerceIn(0.02f, 64f)
    }

    private fun invalidateDocRect(r: Rect) {
        if (r.isEmpty) {
            invalidate()
            return
        }
        val p = FloatArray(8)
        p[0] = r.left.toFloat(); p[1] = r.top.toFloat()
        p[2] = r.right.toFloat(); p[3] = r.top.toFloat()
        p[4] = r.left.toFloat(); p[5] = r.bottom.toFloat()
        p[6] = r.right.toFloat(); p[7] = r.bottom.toFloat()
        viewMatrix.mapPoints(p)
        val pad = 6f
        val l = (minOf(p[0], p[2], p[4], p[6]) - pad).coerceAtLeast(0f)
        val t = (minOf(p[1], p[3], p[5], p[7]) - pad).coerceAtLeast(0f)
        val rr = (maxOf(p[0], p[2], p[4], p[6]) + pad).coerceAtMost(width.toFloat())
        val bb = (maxOf(p[1], p[3], p[5], p[7]) + pad).coerceAtMost(height.toFloat())
        if (rr > l && bb > t) {
            invalidate(
                l.toInt(),
                t.toInt(),
                kotlin.math.ceil(rr.toDouble()).toInt(),
                kotlin.math.ceil(bb.toDouble()).toInt()
            )
        } else {
            invalidate()
        }
    }

    /** 光标整体挪动一段（触控板式相对位移） */
    fun moveCursorBy(dx: Float, dy: Float) {
        val ox = cursorSx
        val oy = cursorSy
        if (!cursorVisible) {
            cursorSx = width * 0.5f
            cursorSy = height * 0.5f
        }
        cursorSx = (cursorSx + dx).coerceIn(0f, width.toFloat())
        cursorSy = (cursorSy + dy).coerceIn(0f, height.toFloat())
        cursorVisible = true
        invalidateCursor(ox, oy)
    }

    /** 把光标放到某个屏幕点（绝对定位） */
    fun setCursor(sx: Float, sy: Float) {
        val ox = cursorSx
        val oy = cursorSy
        cursorSx = sx
        cursorSy = sy
        cursorVisible = true
        invalidateCursor(ox, oy)
    }

    /** 在光标处点一下（左键；没光标就先摆到画面中央） */
    fun cursorClick(longPress: Boolean = false) {
        if (!cursorVisible) {
            cursorSx = width * 0.5f
            cursorSy = height * 0.5f
            cursorVisible = true
            invalidate()
        }
        injectTapAt(cursorSx, cursorSy, longPress)
    }

    private fun injectTapAt(sx: Float, sy: Float, longPress: Boolean) {
        if (injectingTap) return
        injectingTap = true
        try {
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx, sy, 0)
            onTouchEvent(down)
            down.recycle()
            val doUp = {
                val t2 = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, sx, sy, 0)
                onTouchEvent(up)
                up.recycle()
                injectingTap = false
            }
            if (longPress) postDelayed({ doUp() }, 620L) else doUp()
        } catch (t: Throwable) {
            injectingTap = false
        }
    }

    /** 正在拖的网格点下标，-1 = 没在拖 */
    private var warpDragIdx: Int = -1
    private val brushSizeS = androidx.compose.runtime.mutableFloatStateOf(20f)
    var brushSize: Float
        get() = brushSizeS.floatValue
        set(v) { brushSizeS.floatValue = v }
    private val brushOpacityS = androidx.compose.runtime.mutableFloatStateOf(1f)
    var brushOpacity: Float
        get() = brushOpacityS.floatValue
        set(v) { brushOpacityS.floatValue = v }
    /** 整排笔刷参数被外部（套预设/换工具）改过时自增；参数面板据此重读，别显示旧值 */
    private val brushRevS = androidx.compose.runtime.mutableIntStateOf(0)
    var brushRev: Int
        get() = brushRevS.intValue
        set(v) { brushRevS.intValue = v }

    /** 画笔混合模式（0 正常、1 正片叠底…，常量见 BLEND_*） */
    private val brushBlendS = androidx.compose.runtime.mutableIntStateOf(BLEND_NORMAL)
    var brushBlend: Int
        get() = brushBlendS.intValue
        set(v) { brushBlendS.intValue = v }

    /** 颜色动态：第二个颜色（桌面端的「背景色」那一端），和 brushColor 之间随机取色 */
    private val brushColor2S = androidx.compose.runtime.mutableIntStateOf(0xFFFFFFFF.toInt())
    var brushColor2: Int
        get() = brushColor2S.intValue
        set(v) { brushColor2S.intValue = v }

    /** 颜色动态强度 0~1：每一下笔尖在 brushColor 与 brushColor2 之间抖动 */
    private val brushColorJitterS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushColorJitter: Float
        get() = brushColorJitterS.floatValue
        set(v) { brushColorJitterS.floatValue = v }

    /** 涂抹强度 0~1 */
    var smudgeStrength: Float = 0.65f

    /** 液化：推挤力度 0..100，0 推进 1 膨胀 2 收缩 */
    private val liquifyStrengthS = androidx.compose.runtime.mutableFloatStateOf(60f)
    var liquifyStrength: Float
        get() = liquifyStrengthS.floatValue
        set(v) { liquifyStrengthS.floatValue = v }
    private val liquifyModeS = androidx.compose.runtime.mutableIntStateOf(0)
    var liquifyMode: Int
        get() = liquifyModeS.intValue
        set(v) { liquifyModeS.intValue = v }

    /**
     * 区域范围：一块「只在这里生效」的形状区域。
     * 画面坐标用文档像素；feather 是边缘羽化像素。
     * 后续调色（applyFilterToActive）会用 activeRegion 的 alpha 当遮罩。
     */
    class RegionSpec(
        var kind: Int = 0,        // 0 圆 / 1 椭圆 / 2 矩形
        var cx: Float = 0f, var cy: Float = 0f,
        var rx: Float = 0f, var ry: Float = 0f,
        var feather: Int = 0,
        var name: String = "范围",
        /** 反转：范围外生效、范围内不生效 */
        var invert: Boolean = false,
        /** 强度 0~255：遮罩整体 alpha 系数（做「半强度」效果） */
        var opacity: Int = 255,
        /** 运算：0 新建 / 1 相加 / 2 相减 / 3 相交（对齐影视调色软件的窗口运算） */
        var op: Int = 0,
    )

    private val regionsS = androidx.compose.runtime.mutableStateListOf<RegionSpec>()
    val regions: List<RegionSpec> get() = regionsS
    private val activeRegionS = androidx.compose.runtime.mutableIntStateOf(-1)
    var activeRegion: Int
        get() = activeRegionS.intValue
        set(v) { activeRegionS.intValue = v; invalidate() }

    /** 正在拉的区域（按下到抬起之间） */
    private var regionDrag: RegionSpec? = null
    /** 拖动已有范围：0 没在拖 / 1 整体移动 / 2 改大小 */
    private var regionMoveMode: Int = 0
    private var regionMoveStartX = 0f
    private var regionMoveStartY = 0f
    private var regionMoveOriginCx = 0f
    private var regionMoveOriginCy = 0f
    private var regionMoveOriginRx = 0f
    private var regionMoveOriginRy = 0f

    /** 点在哪个范围里（从后往前，后加的在上）；返回索引或 -1 */
    private fun regionHit(ix: Float, iy: Float): Int {
        for (i in regionsS.indices.reversed()) {
            val r = regionsS[i]
            val nx = (ix - r.cx) / kotlin.math.max(r.rx, 1f)
            val ny = (iy - r.cy) / kotlin.math.max(r.ry, 1f)
            val inside = if (r.kind == 2) kotlin.math.abs(nx) <= 1f && kotlin.math.abs(ny) <= 1f
            else nx * nx + ny * ny <= 1f
            if (inside) return i
        }
        return -1
    }

    /** 按下：命中已有范围就进入「移动/改大小」，否则开始新画 */
    fun regionPress(ix: Float, iy: Float) {
        val hit = regionHit(ix, iy)
        if (hit >= 0) {
            activeRegion = hit
            val r = regionsS[hit]
            regionMoveMode = 1
            regionMoveStartX = ix; regionMoveStartY = iy
            regionMoveOriginCx = r.cx; regionMoveOriginCy = r.cy
            regionMoveOriginRx = r.rx; regionMoveOriginRy = r.ry
            // 靠边（15% 环带）按下 = 改大小
            val nx = kotlin.math.abs((ix - r.cx) / kotlin.math.max(r.rx, 1f))
            val ny = kotlin.math.abs((iy - r.cy) / kotlin.math.max(r.ry, 1f))
            if (nx > 0.8f || ny > 0.8f) regionMoveMode = 2
            invalidate()
            return
        }
        regionBegin(ix, iy)
    }

    fun regionMove(ix: Float, iy: Float) {
        if (regionMoveMode == 0) { regionDragTo(ix, iy); return }
        val r = regionsS.getOrNull(activeRegion) ?: return
        if (regionMoveMode == 1) {
            r.cx = regionMoveOriginCx + (ix - regionMoveStartX)
            r.cy = regionMoveOriginCy + (iy - regionMoveStartY)
        } else {
            r.rx = (regionMoveOriginRx + (ix - regionMoveStartX)).coerceAtLeast(4f)
            r.ry = (regionMoveOriginRy + (iy - regionMoveStartY)).coerceAtLeast(4f)
        }
        invalidate()
        // 拖圈时圈里的效果跟着重算（200ms 节流，避免每帧都跑一遍滤镜）
        val now = System.currentTimeMillis()
        if (lastFilterFn != null && now - regionPreviewAt > 100) {
            regionPreviewAt = now
            previewRegionComposite()
        }
    }

    private var regionPreviewAt = 0L

    /** 抬起：结束移动或提交新范围 */
    fun regionRelease() {
        if (regionMoveMode != 0) {
            regionMoveMode = 0
            // 松手不烧像素：保持「活的」——效果跟着蒙版走，只有点「烧录」才落盘
            previewRegionComposite()
            onDocChanged?.invoke()
            invalidate()
            return
        }
        regionEnd()
    }

    /**
     * 把「上一次应用时的原图 + 滤镜 + 当前范围蒙版」重新合成并落进图层。
     * 拖圈结束、改范围参数时调用，保证圈外的像素永远等于原图。
     */
    fun bakeRegionComposite(): Boolean {
        val fn = lastFilterFn ?: return false
        val base = lastFilterBase ?: return false
        val layer = activeLayer ?: return false
        val w = base.width
        val h = base.height
        if (regionsS.isEmpty()) return false
        val maskBmp = runCatching { regionMask() }.getOrNull() ?: return false
        return runCatching {
            val filtered = fn(base.copy(Bitmap.Config.ARGB_8888, false)) ?: return false
            val bp = IntArray(w * h); base.getPixels(bp, 0, w, 0, 0, w, h)
            val tp = IntArray(w * h); filtered.getPixels(tp, 0, w, 0, 0, w, h)
            val mp = IntArray(maskBmp.width * maskBmp.height)
            maskBmp.getPixels(mp, 0, maskBmp.width, 0, 0, maskBmp.width, maskBmp.height)
            val out = IntArray(w * h)
            val mw = maskBmp.width
            val mh = maskBmp.height
            for (y in 0 until h) {
                val mrow = (y * mh / h) * mw
                for (x in 0 until w) {
                    val i = y * w + x
                    val m = if (y < mh && (x * mw / w) < mw) (mp[mrow + (x * mw / w)] ushr 24) else 255
                    if (m <= 0) { out[i] = bp[i]; continue }
                    val a = Color.alpha(bp[i])
                    if (m >= 255) { out[i] = (a shl 24) or (tp[i] and 0x00FFFFFF); continue }
                    val k = m / 255f
                    val cr0 = Color.red(bp[i]); val cg0 = Color.green(bp[i]); val cb0 = Color.blue(bp[i])
                    val cr1 = Color.red(tp[i]); val cg1 = Color.green(tp[i]); val cb1 = Color.blue(tp[i])
                    val rr = (cr0 + (cr1 - cr0) * k).toInt().coerceIn(0, 255)
                    val gg = (cg0 + (cg1 - cg0) * k).toInt().coerceIn(0, 255)
                    val bb = (cb0 + (cb1 - cb0) * k).toInt().coerceIn(0, 255)
                    out[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            val dst = needBitmap(layer) ?: return false
            dst.setPixels(out, 0, w, 0, 0, w, h)
            dropMips()
            post { clearPreview(); invalidate() }
            true
        }.getOrDefault(false)
    }

    fun regionBegin(ix: Float, iy: Float) {
        regionDrag = RegionSpec(kind = regionKind, cx = ix, cy = iy, rx = 0f, ry = 0f, feather = regionFeather)
        invalidate()
    }

    fun regionDragTo(ix: Float, iy: Float) {
        val r = regionDrag ?: return
        r.rx = kotlin.math.abs(ix - r.cx)
        r.ry = kotlin.math.abs(iy - r.cy)
        invalidate()
    }

    /** 抬手：形成正式区域并选中它（太小的丢弃） */
    fun regionEnd() {
        val r = regionDrag ?: return
        regionDrag = null
        if (r.rx < 4f || r.ry < 4f) { invalidate(); return }
        regionsS.add(r)
        activeRegion = regionsS.lastIndex
        onDocChanged?.invoke()
        invalidate()
    }

    /** 最近一次应用到图层上的滤镜（含参数闭包）和它的原图，用来重算范围预览 */
    private var lastFilterBase: Bitmap? = null
    private var lastFilterFn: ((Bitmap) -> Bitmap?)? = null
    var lastFilterLabel: String = ""

    // ===== 活的处理链：原图 → 滤镜A → 滤镜B → …… ，最后再乘范围蒙版 =====
    /** 这条链绑在哪个图层上（换图层就作废） */
    private var chainLayerIndex: Int = -1
    /** 链底：还没跑任何滤镜的原图 */
    private var chainBase: Bitmap? = null
    /** 每一步滤镜（名字 + 函数，函数里带住本次参数） */
    private val chainSteps = mutableListOf<Pair<String, (Bitmap) -> Bitmap?>>()

    /** 链上现在有多少步（给 UI 显示） */
    fun chainStepCount(): Int = if (chainLayerIndex == activeIndex) chainSteps.size else 0

    /** 链是否还活着（有底图、且绑在当前图层） */
    fun hasLiveChain(): Boolean = chainBase != null && chainLayerIndex == activeIndex

    private fun chainBaseForCurrentLayer(): Bitmap? {
        if (chainBase != null && chainLayerIndex == activeIndex) return chainBase
        val layer = activeLayer ?: return null
        val bmp = runCatching { needBitmap(layer) }.getOrNull() ?: return null
        chainBase = bmp.copy(Bitmap.Config.ARGB_8888, false)
        chainLayerIndex = activeIndex
        chainSteps.clear()
        return chainBase
    }

    /** 从天底重放整条链（可选缩放），返回结果位图（不含蒙版） */
    private fun replayChain(base: Bitmap, scale: Float): Bitmap? {
        var cur: Bitmap = if (scale >= 0.999f) base.copy(Bitmap.Config.ARGB_8888, false) else {
            val w = (base.width * scale).toInt().coerceAtLeast(1)
            val h = (base.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(base, w, h, true)
        }
        for ((_, fn) in chainSteps) {
            cur = runCatching { fn(cur) }.getOrNull() ?: return cur
        }
        return cur
    }

    /**
     * 把这次滤镜「加进链」而不是写像素：换工具再调就自然叠在上一次结果上。
     * 返回 false 表示没有可用的底图。
     */
    fun pushChainStep(label: String, fn: (Bitmap) -> Bitmap?): Boolean {
        chainBaseForCurrentLayer() ?: return false
        chainSteps.add(label to fn)
        previewRegionComposite()
        return true
    }

    /** 撤销链上最后一步（参数调错了可以退） */
    fun popChainStep(): Boolean {
        if (!hasLiveChain() || chainSteps.isEmpty()) return false
        chainSteps.removeAt(chainSteps.size - 1)
        previewRegionComposite()
        return true
    }

    /** 放弃整条链：像素还原成链底原图 */
    fun clearChain(restoreBase: Boolean = true) {
        if (restoreBase) {
            val base = chainBase
            val layer = activeLayer
            if (base != null && layer != null && chainLayerIndex == activeIndex) {
                val dst = runCatching { needBitmap(layer) }.getOrNull()
                if (dst != null && dst.width == base.width && dst.height == base.height) {
                    dst.setPixels(IntArray(base.width * base.height).also { base.getPixels(it, 0, base.width, 0, 0, base.width, base.height) },
                        0, base.width, 0, 0, base.width, base.height)
                    dropMips()
                }
            }
        }
        chainSteps.clear()
        chainBase = null
        chainLayerIndex = -1
        clearPreview()
        invalidate()
    }

    /**
     * 烧录：从天底重放整条链，按当前范围蒙版合成，写进图层像素，然后删掉范围（圈消失）。
     * 这一步之后就是「死像素」，不再是活的。
     */
    fun commitChain(): Boolean {
        val base = chainBase ?: return false
        val layer = activeLayer ?: return false
        if (chainLayerIndex != activeIndex) return false
        val dst = runCatching { needBitmap(layer) }.getOrNull() ?: return false
        val w = base.width
        val h = base.height
        if (dst.width != w || dst.height != h) return false
        val steps = chainSteps.toList()
        onHint?.invoke("正在烧录…")
        Thread {
            val t0 = System.currentTimeMillis()
            val result = runCatching {
                var cur: Bitmap = base.copy(Bitmap.Config.ARGB_8888, false)
                for ((_, fn) in steps) cur = fn(cur) ?: cur
                val tp = IntArray(w * h)
                cur.getPixels(tp, 0, w, 0, 0, w, h)
                val bp = IntArray(w * h)
                base.getPixels(bp, 0, w, 0, 0, w, h)
                val maskBmp = if (regionsS.isNotEmpty()) regionMask() else null
                val out = IntArray(w * h)
                if (maskBmp == null) {
                    System.arraycopy(tp, 0, out, 0, w * h)
                } else {
                    val mp = IntArray(maskBmp.width * maskBmp.height)
                    maskBmp.getPixels(mp, 0, maskBmp.width, 0, 0, maskBmp.width, maskBmp.height)
                    val mw = maskBmp.width; val mh = maskBmp.height
                    for (y in 0 until h) {
                        val mrow = (y * mh / h) * mw
                        for (x in 0 until w) {
                            val i = y * w + x
                            val m = (mp[mrow + (x * mw / w)] ushr 24)
                            if (m <= 0) { out[i] = bp[i]; continue }
                            val a = Color.alpha(bp[i])
                            if (m >= 255) { out[i] = (a shl 24) or (tp[i] and 0x00FFFFFF); continue }
                            val k = m / 255f
                            val rr = (Color.red(bp[i]) + (Color.red(tp[i]) - Color.red(bp[i])) * k).toInt().coerceIn(0, 255)
                            val gg = (Color.green(bp[i]) + (Color.green(tp[i]) - Color.green(bp[i])) * k).toInt().coerceIn(0, 255)
                            val bb = (Color.blue(bp[i]) + (Color.blue(tp[i]) - Color.blue(bp[i])) * k).toInt().coerceIn(0, 255)
                            out[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                        }
                    }
                }
                out
            }.getOrNull()
            val ms = System.currentTimeMillis() - t0
            if (result == null) { post { onHint?.invoke("烧录失败") }; return@Thread }
            post {
                // 记一份「改动前」的像素，撤销时贴回去（Stroke/Patch 是私有的，直接在这里造）
                val beforePx = bpCopy(dst)
                val pat = Patch(layer, 0, 0, w, h, beforePx, result.copyOf())
                val stroke = Stroke().also { it.label = "烧录调色" }
                stroke.patches.add(pat)
                pushUndo(stroke)
                dst.setPixels(result, 0, w, 0, 0, w, h)
                dropMips()
                chainSteps.clear(); chainBase = null; chainLayerIndex = -1
                regionsS.clear(); activeRegionS.intValue = -1
                clearPreview()
                invalidate()
                onDocChanged?.invoke()
                onHint?.invoke("已烧录（${ms}ms）：范围已移除，像素已改")
            }
        }.start()
        return true
    }

    private fun bpCopy(b: Bitmap): IntArray {
        val a = IntArray(b.width * b.height)
        b.getPixels(a, 0, b.width, 0, 0, b.width, b.height)
        return a
    }


    /**
     * 范围参数改动后的预览：原图 + 上次滤镜，按（新的）区域遮罩合成。
     * 范围内 = 滤镜结果，范围外 = 原像素 —— 位置不会动，只是遮罩变了。
     */
    fun previewRegionComposite(maxSide: Int = 900): Boolean {
        if (regionsS.isEmpty()) return false
        val live = hasLiveChain()
        val baseFull = if (live) chainBase else lastFilterBase
        baseFull ?: return false
        if (!live && lastFilterFn == null) return false
        val sc = kotlin.math.min(1f, maxSide.toFloat() / kotlin.math.max(baseFull.width, baseFull.height))
        val bw = (baseFull.width * sc).toInt().coerceAtLeast(1)
        val bh = (baseFull.height * sc).toInt().coerceAtLeast(1)
        Thread {
            val out = runCatching {
                val smallBase = Bitmap.createScaledBitmap(baseFull, bw, bh, true)
                // 修正结果：活的链就从链底重放整条链（新滤镜自然叠在旧结果上）
                val corrected = if (live) {
                    val full = replayChain(baseFull, sc) ?: return@runCatching null
                    if (full.width == bw && full.height == bh) full
                    else Bitmap.createScaledBitmap(full, bw, bh, true)
                } else {
                    lastFilterFn?.invoke(smallBase) ?: return@runCatching null
                }
                val maskFull = regionMask() ?: return@runCatching null
                val maskSmall = Bitmap.createScaledBitmap(maskFull, bw, bh, true)
                val bp = IntArray(bw * bh); smallBase.getPixels(bp, 0, bw, 0, 0, bw, bh)
                val tp = IntArray(bw * bh); corrected.getPixels(tp, 0, bw, 0, 0, bw, bh)
                val mp = IntArray(bw * bh); maskSmall.getPixels(mp, 0, bw, 0, 0, bw, bh)
                val res = IntArray(bw * bh)
                compositeWithMask(bp, tp, IntArray(bw * bh) { (mp[it] ushr 24) }, false, res)
                Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                    it.setPixels(res, 0, bw, 0, 0, bw, bh)
                }
            }.getOrNull()
            if (out != null) post { setPreview(out) }
        }.start()
        return true
    }

    /** 从工程里恢复范围列表（保存/打开工程时用） */
    fun setRegions(list: List<RegionSpec>, active: Int = -1) {
        regionsS.clear()
        regionsS.addAll(list)
        activeRegion = if (list.isEmpty()) -1 else active.coerceIn(0, list.lastIndex)
        invalidate()
    }

    fun regionRemove(index: Int) {
        if (index in regionsS.indices) {
            regionsS.removeAt(index)
            activeRegion = if (regionsS.isEmpty()) -1 else (index - 1).coerceIn(0, regionsS.lastIndex)
            onDocChanged?.invoke()
            invalidate()
        }
    }

    fun regionSetFeather(index: Int, f: Int) {
        regionsS.getOrNull(index)?.let { it.feather = f; invalidate() }
    }

    /** 当前区域遮罩（全画布 alpha：区域内 255，羽化边缘渐变，外面 0） */
    /** 单个范围自己的 alpha 遮罩（羽化 / 反转 / 强度都在这里算） */
    private fun regionShapeAlpha(r: RegionSpec, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt()
        if (r.feather > 0) {
            p.maskFilter = android.graphics.BlurMaskFilter(
                r.feather.toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        if (r.invert) {
            c.drawColor(0xFFFFFFFF.toInt())
            val cut = Paint(Paint.ANTI_ALIAS_FLAG)
            cut.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
            cut.maskFilter = p.maskFilter
            val cutPaint = Paint(cut)
            if (r.kind == 2) {
                c.drawRect(r.cx - r.rx, r.cy - r.ry, r.cx + r.rx, r.cy + r.ry, cutPaint)
            } else {
                c.drawOval(r.cx - r.rx, r.cy - r.ry, r.cx + r.rx, r.cy + r.ry, cutPaint)
            }
        } else {
            when (r.kind) {
                2 -> c.drawRect(r.cx - r.rx, r.cy - r.ry, r.cx + r.rx, r.cy + r.ry, p)
                else -> c.drawOval(r.cx - r.rx, r.cy - r.ry, r.cx + r.rx, r.cy + r.ry, p)
            }
        }
        val op = r.opacity.coerceIn(0, 255)
        if (op < 255) {
            val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(scaled).drawBitmap(bmp, 0f, 0f, Paint().apply { alpha = op })
            bmp.recycle()
            return scaled
        }
        return bmp
    }

    /**
     * 自检：圈外（蒙版为 0 的地方）有多少像素是不透明的。
     * 返回一句人话，直接显示给用户 —— 用来判断「圈外消失」是像素问题还是绘制问题。
     */
    fun auditRegionOutsideAlpha(): String {
        val r = regionsS.toList()
        if (r.isEmpty()) return "没有范围，先在画面上拉一个"
        val m = regionMask() ?: return "范围蒙版生成失败"
        val bmp = activeLayer?.let { runCatching { needBitmap(it) }.getOrNull() } ?: return "当前图层没有像素"
        val w = minOf(m.width, bmp.width)
        val h = minOf(m.height, bmp.height)
        val mp = IntArray(m.width * m.height)
        m.getPixels(mp, 0, m.width, 0, 0, m.width, m.height)
        val bp = IntArray(bmp.width * bmp.height)
        bmp.getPixels(bp, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var outside = 0
        var bad = 0
        for (y in 0 until h) {
            val rowM = y * m.width
            val rowB = y * bmp.width
            for (x in 0 until w) {
                if ((mp[rowM + x] ushr 24) > 5) continue
                outside++
                if ((bp[rowB + x] ushr 24) < 250) bad++
            }
        }
        val pct = if (outside == 0) "0" else String.format("%.1f", bad * 100.0 / outside)
        return if (bad == 0) "圈外完好：$outside 个像素全部不透明"
        else "圈外有 $bad / $outside 个像素是半透明（$pct%）—— 像素真的被写坏了，不是绘制问题"
    }

    /**
     * 组合后的范围蒙版：所有范围按各自的「运算」叠在一起
     * （新建＝清空重来，相加 / 相减 / 相交 = SRC_OVER / DST_OUT / DST_IN）。
     * 只按 alpha 语义使用，不参与画面合成——合成的 alpha 永远取原图。
     */
    fun regionMask(): Bitmap? {
        val list = regionsS.toList()
        if (list.isEmpty()) return null
        val w = docPixelWidth(); val h = docPixelHeight()
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val add = Paint(Paint.ANTI_ALIAS_FLAG)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }
        val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        var started = false
        for (r in list) {
            val one = regionShapeAlpha(r, w, h) ?: continue
            when (r.op) {
                1 -> {
                    if (!started) { c.drawBitmap(one, 0f, 0f, null); started = true }
                    else c.drawBitmap(one, 0f, 0f, add)
                }
                2 -> {
                    if (!started) { c.drawColor(0xFFFFFFFF.toInt()); started = true }
                    c.drawBitmap(one, 0f, 0f, sub)
                }
                3 -> {
                    if (!started) { c.drawBitmap(one, 0f, 0f, null); started = true }
                    else c.drawBitmap(one, 0f, 0f, ip)
                }
                else -> {
                    c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                    c.drawBitmap(one, 0f, 0f, null)
                    started = true
                }
            }
            one.recycle()
        }
        if (!started) return null
        return bmp
    }

    /** 区域工具参数：形状 / 羽化 */
    var regionKind: Int = 0
    var regionFeather: Int = 0

    /** 涂抹模式：0 正常 / 1 变暗 / 2 变亮 / 3 色相 / 4 饱和度 / 5 颜色 / 6 明度 */
    var smudgeMode: Int = 0

    /** 手指绘画：起笔不抓取画布颜色，直接用当前画笔色（桌面端的「手指绘画」） */
    var smudgeFinger: Boolean = false

    private val hsvA = FloatArray(3)
    private val hsvB = FloatArray(3)

    /** 减淡/加深强度 0~1 */
    // 减淡和加深是两个独立工具：曝光、色调范围、保护色调各存一份，互不影响。
    // 必须用 Compose 状态：普通 var 改了不触发重组，参数条看起来就是「拖了没反应」。
    private val dodgeStrengthS = androidx.compose.runtime.mutableFloatStateOf(0.45f)
    var dodgeStrength: Float
        get() = dodgeStrengthS.floatValue
        set(v) { dodgeStrengthS.floatValue = v }
    private val dodgeRangeS = androidx.compose.runtime.mutableIntStateOf(0)
    var dodgeRange: Int
        get() = dodgeRangeS.intValue
        set(v) { dodgeRangeS.intValue = v }
    private val dodgeProtectS = androidx.compose.runtime.mutableStateOf(false)
    var dodgeProtect: Boolean
        get() = dodgeProtectS.value
        set(v) { dodgeProtectS.value = v }
    private val burnStrengthS = androidx.compose.runtime.mutableFloatStateOf(0.45f)
    var burnStrength: Float
        get() = burnStrengthS.floatValue
        set(v) { burnStrengthS.floatValue = v }
    private val burnRangeS = androidx.compose.runtime.mutableIntStateOf(0)
    var burnRange: Int
        get() = burnRangeS.intValue
        set(v) { burnRangeS.intValue = v }
    private val burnProtectS = androidx.compose.runtime.mutableStateOf(false)
    var burnProtect: Boolean
        get() = burnProtectS.value
        set(v) { burnProtectS.value = v }

    @Deprecated("改用 dodgeStrength / burnStrength")
    var toneStrength: Float
        get() = dodgeStrength
        set(v) { dodgeStrength = v; burnStrength = v }

    /** 减淡/加深作用范围：0 全部、1 阴影、2 中间调、3 高光 */
    @Deprecated("改用 dodgeRange / burnRange")
    var toneRange: Int
        get() = dodgeRange
        set(v) { dodgeRange = v; burnRange = v }
    var brushColor: Int = 0xFFEFF1F4.toInt()

    // ---- 形状工具 ----
    /** 0 矩形、1 椭圆、2 直线、3 多边形（六边形） */
    var shapeKind: Int = 0
    /** true = 填充，false = 描边（粗细跟笔刷大小） */
    var shapeFill: Boolean = false
    /** 形状线宽；0 = 跟画笔大小 */
    var shapeWidth: Int = 0
    /** 画形状时是否单独新建一层 */
    var shapeNewLayer: Boolean = true
    /** 形状：圆角半径（像素）/ 多边形边数 / 星形内径百分比 */
    var shapeCorner: Int = 16
    var shapeSides: Int = 6
    var shapeInner: Int = 45
    /** 钢笔：已落的锚点（文档坐标）、是否闭合、是否平滑 */
    val penPoints = mutableListOf<PointF>()
    var penClosed: Boolean = false
    var penSmooth: Boolean = true
    /** 消失点：平面四角（文档坐标，顺序 左上→右上→右下→左下） */
    val vanishPoints = mutableListOf<PointF>()
    /** 上一次画出的形状路径，路径面板「存储路径」用 */
    var lastShapePath: Path? = null
    /** 路径面板里存下来的路径 */
    val pathList = mutableListOf<Pair<String, Path>>()

    private var shapeX0 = 0f
    private var shapeY0 = 0f
    private var shapeX1 = 0f
    private var shapeY1 = 0f
    private var shapeActive = false
    /** 落笔那一刻的屏幕坐标：提交时用它重新换算，避免手势过程中 viewMatrix 变过导致落点错位 */
    private var shapeViewX0 = 0f
    private var shapeViewY0 = 0f
    private var shapeViewX = 0f

    /** 直线吸附：手拉很难正好水平，靠近 0/45/90 度就吸过去（默认开） */
    var shapeSnapAngle: Boolean = true

    /** 吸附后的角度，只用于画面上提示（-999 = 这次没吸） */
    private var shapeSnapDeg = -999f

    /** 椭圆：新画的默认是不是正圆（参数区的「圆形」开关） */
    var shapeCircle: Boolean = false

    /** 直线的默认角度（按尺寸生成时用，0 = 水平） */
    var shapeLineAngle: Float = 0f
    private var shapeViewY = 0f
    /** 双指按住 = 正方形/正圆 */
    private var shapeSquare = false

    /** 蒙版编辑：打开后笔刷改的是当前层的蒙版（亮色露出、暗色遮住） */
    var maskEditing: Boolean
        get() = activeLayer?.maskEdit == true
        set(value) {
            activeLayer?.maskEdit = value
            invalidate()
        }

    /** 设置里的「像素化放大」：打开后放大看到的是一个一个像素块，而不是相册那种平滑放大 */
    var pixelZoom: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onColorPicked: ((Int) -> Unit)? = null
    var onDocChanged: (() -> Unit)? = null

    /** 蒙版笔迹落定时回调，用于界面提示「黑=遮住 / 白=露出」 */
    var onMaskStroke: (() -> Unit)? = null
    /** 当前编辑的图层变了（点图层 / 新建 / 删除都算），界面拿它同步调色这类按层操作的目标 */
    var onActiveLayerChanged: (() -> Unit)? = null

    /** 通道查看：-1 正常彩色，0=红 1=绿 2=蓝（只看这一个通道的灰度） */
    private val channelViewS = androidx.compose.runtime.mutableIntStateOf(-1)
    var channelView: Int
        get() = channelViewS.intValue
        set(value) {
            channelViewS.intValue = value
            channelFilter = if (value < 0) null else ColorMatrixColorFilter(channelMatrix(value))
            invalidate()
        }

    private var channelFilter: ColorFilter? = null

    /** 当前正在画的图层要套的调整层滤镜（上面有几个调整层就乘几个矩阵） */
    private var adjFilter: ColorFilter? = null

    private fun channelMatrix(channel: Int): ColorMatrix {
        val m = ColorMatrix()
        m.set(
            floatArrayOf(
                if (channel == 0) 1f else 0f, 0f, 0f, 0f, 0f,
                if (channel == 1) 1f else 0f, 0f, 0f, 0f, 0f,
                if (channel == 2) 1f else 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        return m
    }

    /** 选区变了就回调一次（有没有选区），给界面用 */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    /** 文字工具点了画布：回调文档坐标，由界面弹输入框 */
    var onTextTap: ((Float, Float) -> Unit)? = null

    // ================= 自由变换 =================
    /** 变换中：把源图按 tf* 参数画出来，应用时才写回图层 */
    var transformActive: Boolean = false
        private set
    private var tfSource: Bitmap? = null
    private var tfLayerIndex: Int = -1
    private var tfTx: Float = 0f
    private var tfTy: Float = 0f
    private var tfSx: Float = 1f
    private var tfSy: Float = 1f
    private var tfRot: Float = 0f
    private var tfPivotX: Float = 0f
    private var tfPivotY: Float = 0f
    private var tfMode: Int = 0 // 0 无 1 移动 2 缩放 3 旋转
    private var lastTfTapAt = 0L
    private var lastTfTapX = 0f
    private var lastTfTapY = 0f
    private var tfStartX: Float = 0f
    private var tfStartY: Float = 0f
    private var tfStartSx: Float = 1f
    private var tfStartSy: Float = 1f
    private var tfStartLocalX: Float = 0f
    private var tfStartLocalY: Float = 0f
    private var tfStartInv: Matrix? = null
    private var tfBox: RectF = RectF()
    private var tfStartRot: Float = 0f
    private var tfStartDist: Float = 1f
    private var tfStartAngle: Float = 0f
    var onTransformChanged: ((Boolean) -> Unit)? = null
    /** 旋转变了（含手柄拖动 / 双指旋转 / 参数条设定），把角度回传给底部条，保证两边实时一致 */
    var onTransformRotation: ((Float) -> Unit)? = null

    /** 当前选区遮罩：白色不透明=选中，null=没有选区（整张图都算） */
    var selection: Bitmap? = null
        private set

    /** 选区工具的形状 */
    var selectMode: SelectMode = SelectMode.RECT
    /** 选区运算方式 */
    var selectOp: SelectOp = SelectOp.NEW
    /** 磁性套索的吸附宽度（px） */
    private val magnetWidthS = androidx.compose.runtime.mutableFloatStateOf(14f)
    var magnetWidth: Float
        get() = magnetWidthS.floatValue
        set(v) { magnetWidthS.floatValue = v }
    /** 快速选择的颜色容差 */
    private val quickToleranceS = androidx.compose.runtime.mutableFloatStateOf(0.15f)
    var quickTolerance: Float
        get() = quickToleranceS.floatValue
        set(v) { quickToleranceS.floatValue = v }
    /** 存储的选区（桌面端的「存储选区」，存在内存里） */
    val savedSelections = ArrayList<Bitmap>()
    private var magnetPoints: ArrayList<PointF>? = null
    private var magnetPixels: IntArray? = null
    private var quickMask: Bitmap? = null
    /** 快速选择上一个生长点（节流用） */
    private var quickLastX = -1f
    private var quickLastY = -1f
    private var quickColor: Int = 0
    /** 魔棒容差 0~1 */
    private val wandToleranceS = androidx.compose.runtime.mutableFloatStateOf(0.12f)
    var wandTolerance: Float
        get() = wandToleranceS.floatValue
        set(v) { wandToleranceS.floatValue = v }
    /** 选择主体：离背景色多远才算主体（0..100，越大越宽松） */
    private val subjectToleranceS = androidx.compose.runtime.mutableFloatStateOf(30f)
    var subjectTolerance: Float
        get() = subjectToleranceS.floatValue
        set(v) { subjectToleranceS.floatValue = v }
    /** 这一次按下之前选区长什么样：双指缩放时要还原回去，不能把选区改掉 */
    private var selSnapBefore: ByteArray? = null
    private var selSnapW = 0
    private var selSnapH = 0
    /** 魔棒「连续」：关掉就是全图同色都选上（桌面端的连续） */
    private val wandContiguousS = androidx.compose.runtime.mutableStateOf(true)
    var wandContiguous: Boolean
        get() = wandContiguousS.value
        set(v) { wandContiguousS.value = v }
    /** 魔棒「对所有图层取样」 */
    private val wandSampleAllS = androidx.compose.runtime.mutableStateOf(false)
    var wandSampleAll: Boolean
        get() = wandSampleAllS.value
        set(v) { wandSampleAllS.value = v }
    /** 快速选择的笔刷大小（px） */
    private val quickSizeS = androidx.compose.runtime.mutableFloatStateOf(40f)
    var quickSize: Float
        get() = quickSizeS.floatValue
        set(v) { quickSizeS.floatValue = v }

    val layers = mutableListOf<Layer>()
    var activeIndex: Int = 0
        private set

    val activeLayer: Layer? get() = layers.getOrNull(activeIndex)

    /** 曲线面板的吸管：打开后点一下画面，把这一点的色阶值（0~255）交给面板 */
    var curvePickMode = false
    var curvePickChannel = -1
    /** true = 取扁平合成图（看混合结果），false = 只取当前图层 */
    var curvePickAllLayers = false
    var onCurvePick: ((Int) -> Unit)? = null
    /** 需要给用户一句提示时用（Toast），例如色彩范围一个像素都没找到 */
    var onHint: ((String) -> Unit)? = null

    private fun pickCurveTone(vx: Float, vy: Float) {
        val own = activeLayer?.bitmap ?: return
        val p = toImage(vx, vy)
        val x = p.x.toInt()
        val y = p.y.toInt()
        // 混合模式先合一张图（读完马上回收）
        val flat = if (curvePickAllLayers) flatten() else null
        val bmp = flat ?: own
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) {
            if (flat != null && flat !== own) flat.recycle()
            return
        }
        val c = bmp.getPixel(x, y)
        if (flat != null && flat !== own) flat.recycle()
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val v = when (curvePickChannel) {
            0 -> r
            1 -> g
            2 -> b
            else -> (r * 299 + g * 587 + b * 114) / 1000
        }
        onCurvePick?.invoke(v)
    }

    /** 当前图层的色阶直方图（256 档），给曲线的「自动」用；每 3 像素采一个，4096 的图也不卡 */
    fun toneHistogram(channel: Int): IntArray {
        val hist = IntArray(256)
        val bmp = activeLayer?.bitmap ?: return hist
        val w = bmp.width
        if (w <= 0 || bmp.height <= 0) return hist
        val row = IntArray(w)
        var y = 0
        while (y < bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                if (((c ushr 24) and 0xFF) > 8) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    hist[when (channel) {
                        0 -> r
                        1 -> g
                        2 -> b
                        else -> (r * 299 + g * 587 + b * 114) / 1000
                    }]++
                }
                x += 3
            }
            y += 3
        }
        return hist
    }


    /** 当前图层序号（滤镜/调色要按序号写回） */
    fun activeLayerIndex(): Int = activeIndex

    /** 当前层是不是调整层：调整层没有像素，滤镜/调色点它只会静默无反应，要提前告诉用户 */
    fun activeLayerIsAdjust(): Boolean = activeLayer?.adjust != null
    var hasDocument: Boolean = false
        private set

    /** 文档位深：8 或 16。16 位文档里，图层第一次被改动时会建一份 16 位缓冲 */
    var docDepth: Int = 8

    /** 大块像素用 Deflate 压着存：一笔 1000×1000 是 8 MB，压完通常只剩几百 KB */
    private class PatchData(arr: IntArray) {
        private var decoded: IntArray? = null
        private var zip: ByteArray? = null

        init {
            if (arr.size >= 65536) zip = deflate(arr) else decoded = arr
        }

        val bytes: Long
            get() = zip?.size?.toLong() ?: (decoded?.size ?: 0).toLong() * 4L

        fun take(): IntArray {
            val d = decoded
            if (d != null) return d
            val out = inflate(zip!!, (zip!!.size))
            // 小块留一份在内存里，反复撤销/重做不用反复解压；大块用完就放掉
            if (out.size <= 1 shl 20) decoded = out
            return out
        }

        private fun deflate(src: IntArray): ByteArray {
            val buf = java.io.ByteArrayOutputStream(src.size * 2)
            val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
            val out = java.util.zip.DeflaterOutputStream(buf, deflater)
            val tmp = ByteArray(src.size * 4)
            for (i in src.indices) {
                val v = src[i]
                val o = i * 4
                tmp[o] = (v ushr 24).toByte()
                tmp[o + 1] = (v ushr 16).toByte()
                tmp[o + 2] = (v ushr 8).toByte()
                tmp[o + 3] = v.toByte()
            }
            out.write(tmp)
            out.close()
            return buf.toByteArray()
        }

        private fun inflate(zip: ByteArray, hint: Int): IntArray {
            val bytes = java.io.ByteArrayInputStream(zip).let {
                java.util.zip.InflaterInputStream(it).readBytes()
            }
            val n = bytes.size / 4
            val out = IntArray(n)
            for (i in 0 until n) {
                val o = i * 4
                out[i] = ((bytes[o].toInt() and 0xFF) shl 24) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 2].toInt() and 0xFF) shl 8) or
                        (bytes[o + 3].toInt() and 0xFF)
            }
            return out
        }
    }

    private class Patch(
        val layer: Layer,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        beforeArr: IntArray,
        afterArr: IntArray
        ,
        /** 这一笔改的是蒙版，不是图层像素 */
        val onMask: Boolean = false
    ) {
        val before = PatchData(beforeArr)
        val after = PatchData(afterArr)

        /** 这一笔之前的 16 位数据（撤销时按块还原；8 位文档里是 null） */
        var p16Before: ShortArray? = null
        var p16After: ShortArray? = null
        /** 抓快照时缓冲的尺寸，撤销时尺寸对不上就别硬塞 */
        var p16W = 0
        var p16H = 0

        init {
            if (!onMask && w > 0 && h > 0) {
                val p = layer.pix16
                if (p != null) {
                    val snap = runCatching { p.snapshot(x, y, w, h) }.getOrNull()
                    if (snap != null) {
                        p16Before = snap
                        p16W = p.width
                        p16H = p.height
                    }
                }
            }
        }
    }

    private fun patchTarget(p: Patch): Bitmap? = if (p.onMask) p.layer.mask else p.layer.bitmap

    private class Stroke(val patches: MutableList<Patch> = mutableListOf()) {
        /** 选区操作的快照（只存 alpha，省内存）；非选区操作这俩是 null */
        var selBefore: ByteArray? = null
        var selAfter: ByteArray? = null
        var selW = 0
        var selH = 0
        /** 历史面板上显示的名字（画笔、滤镜名、裁剪……） */
        var label: String = ""
        /**
         * 图层结构步骤：这一笔删掉的图层 + 它原来的下标（-1 表示不是删层步骤）。
         * 以前删层是「removeAt 完直接清空撤销栈」，所以删完连之前的画都撤不回来。
         * 现在把被删的层对象整个留在这一步里（位图不 recycle），撤销时插回原位。
         */
        var removedLayer: Layer? = null
        var removedIndex: Int = -1
        /**
         * 新增图层步骤（形状工具画的每个形状都是独立一层）：
         * 撤销就把这一层整层摘掉，重做再插回去。位图留在这一步里，不 recycle。
         */
        var addedLayer: Layer? = null
        var addedIndex: Int = -1
        /**
         * 批量结构步骤（多选删除 / 打成组 / 合并 / 批量移动）：
         * 只记图层表的前后顺序（存引用，几十个引用而已），撤销就是把整表换回去。
         */
        var orderBefore: List<Layer>? = null
        var orderAfter: List<Layer>? = null

        /** 是不是结构步骤（不碰像素，只动图层列表） */
        fun isStruct(): Boolean =
            removedLayer != null || addedLayer != null || orderBefore != null
    }

    private val undoStack = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()
    /** 文档总像素数（大图判断都靠它） */
    fun pixelCount(): Long {
        val b = layers.firstOrNull()?.bitmap ?: return 0L
        return b.width.toLong() * b.height.toLong()
    }

    /** 大图（超过 1200 万像素，约 4000×3000 以上）：占内存的精度缓冲要省着用 */
    fun isHugeDoc(): Boolean = pixelCount() > 12_000_000L

    private val maxUndo: Int
        get() = if (isHugeDoc()) 20 else 60
    /** 撤销栈总字节预算：取 96 MB 与堆上限 15% 的较小者；大图再压到 48 MB */
    private val undoByteBudget: Long
        get() {
            val base = minOf(96L * 1024L * 1024L, (Runtime.getRuntime().maxMemory() * 0.15).toLong())
            return if (isHugeDoc()) minOf(base, 48L * 1024L * 1024L) else base
        }

    private fun strokeBytes(s: Stroke): Long {
        var n = 0L
        for (p in s.patches) {
            n += p.before.bytes + p.after.bytes
            // 16 位快照：每像素 3 条 short
            n += (p.p16Before?.size ?: 0).toLong() * 2
            n += (p.p16After?.size ?: 0).toLong() * 2
        }
        s.selBefore?.let { n += it.size }
        s.selAfter?.let { n += it.size }
        // 删层步骤把整层位图留在栈里，必须计入预算，否则连删几层就把内存吃光
        s.removedLayer?.let { lay ->
            n += runCatching { (lay.bitmap?.allocationByteCount ?: 0).toLong() }.getOrDefault(0L)
        }
        s.addedLayer?.let { lay ->
            n += runCatching { (lay.bitmap?.allocationByteCount ?: 0).toLong() }.getOrDefault(0L)
        }
        // 批量结构步骤把整批旧层留在栈里（位图不回收），一样要计入预算
        s.orderBefore?.let { back ->
            for (lay in back) {
                if (layers.none { it === lay }) {
                    n += runCatching { (lay.bitmap?.allocationByteCount ?: 0).toLong() }.getOrDefault(0L)
                }
            }
        }
        return n
    }

    /** 这一笔碰到的文档矩形：只重画那一块，不必整屏重合成 */
    private fun strokeRect(s: Stroke): Rect? {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        var any = false
        for (p in s.patches) {
            if (p.x < l) l = p.x
            if (p.y < t) t = p.y
            if (p.x + p.w > r) r = p.x + p.w
            if (p.y + p.h > b) b = p.y + p.h
            any = true
        }
        return if (any) Rect(l, t, r, b) else null
    }

    private fun pushUndo(stroke: Stroke) {
        compositePixelStamp++
        markDocDirty(strokeRect(stroke))
        // 顺手把每块的「16 位现状」也抓下来，撤销/重做时连精度一起还原
        for (p in stroke.patches) {
            if (p.p16Before != null && p.p16After == null) {
                p.p16After = runCatching { p.layer.pix16?.snapshot(p.x, p.y, p.w, p.h) }.getOrNull()
            }
        }
        undoStack.add(stroke)
        var total = 0L
        for (s in undoStack) total += strokeBytes(s)
        while (undoStack.size > 1 && (total > undoByteBudget || undoStack.size > maxUndo)) {
            total -= strokeBytes(undoStack.removeAt(0))
        }
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
    }
    private var currentStroke: Stroke? = null

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** 历史面板用：已发生的步骤名（从旧到新），第一条永远是「新建画布」 */
    fun historyLabels(): List<String> =
        undoStack.map { it.label.ifEmpty { "编辑" } }

    /** 当前停在第几步（= 已应用的步骤数）；历史面板据此显示“当前”位置 */
    fun historyPosition(): Int = undoStack.size

    fun historyTotal(): Int = undoStack.size + redoStack.size

    /** 历史面板点击：跳到第 [pos] 步（0 = 最初）。往回走撤销、往前走重做 */
    fun jumpToHistory(pos: Int) {
        val target = pos.coerceIn(0, historyTotal())
        var guard = 0
        while (undoStack.size > target && guard++ < 400) undo()
        while (undoStack.size < target && redoStack.isNotEmpty() && guard++ < 400) redo()
        captureHistorySnap()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
    }

    /** 历史面板的「快照」：整份文档的一份副本，点一下直接回到这个状态（不进撤销栈） */
    class Snapshot(
        val name: String,
        val bitmaps: List<Bitmap>,
        val active: Int,
    )

    /** 最多留 3 张：整份文档像素的副本很占内存 */
    val snapshots = mutableListOf<Snapshot>()

    fun takeSnapshot(): String {
        val ls = layers
        if (ls.isEmpty()) return "还没有图层"
        val copies = ArrayList<Bitmap>(ls.size)
        try {
            for (ly in ls) {
                val b = ly.bitmap ?: return "有图层还在磁盘上，等它换回来再存"
                val c = b.copy(Bitmap.Config.ARGB_8888, false) ?: return "内存不够，存不了快照"
                copies.add(c)
            }
        } catch (e: Throwable) {
            copies.forEach { runCatching { it.recycle() } }
            return "内存不够，存不了快照"
        }
        while (snapshots.size >= 3) {
            snapshots.removeAt(0).bitmaps.forEach { runCatching { it.recycle() } }
        }
        snapshots.add(Snapshot("快照 " + (snapshots.size + 1), copies, activeIndex))
        return "已存快照（最多 3 张，新的会顶掉最老的）"
    }

    /** 回到第 [index] 张快照；图层数对不上就不动，免得错位 */
    fun restoreSnapshot(index: Int): Boolean {
        val sn = snapshots.getOrNull(index) ?: return false
        val ls = layers
        if (sn.bitmaps.size != ls.size) return false
        for (i in ls.indices) {
            val c = runCatching { sn.bitmaps[i].copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return false
            ls[i].bitmap = c
            // 快照只存 8 位像素，16 位精度缓冲失效，下面按 8 位继续画
            ls[i].pix16 = null
        }
        activeIndex = sn.active.coerceIn(0, ls.size - 1)
        undoStack.clear()
        redoStack.clear()
        dropMips()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    fun deleteSnapshot(index: Int): Boolean {
        val sn = snapshots.getOrNull(index) ?: return false
        snapshots.removeAt(index)
        sn.bitmaps.forEach { runCatching { it.recycle() } }
        return true
    }

    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val checkerPaint = Paint()
    /** 工作区底色：浅灰，用来和纯白画布区分开 */
    private val WORKSPACE_GRAY = 0xFFD3D8DF.toInt()
    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val paperStrokePaint = Paint().apply {
        color = 0xFFCFD4DB.toInt(); style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = false
    }
    private val paperShadowPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL
        setShadowLayer(10f, 0f, 4f, 0x55000000)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 蒙版笔迹：把任意颜色强制变成白色，只保留 alpha */
    private val maskInkPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskWhiteFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    private val compositePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tfPath = Path()
    private val tfFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0xFF4F8CFF.toInt()
    }
    private val tfHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val tfBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x222F80ED
    }
    private val tfBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8C27A.toInt()
    }
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fencePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 画笔笔迹先画在这张透明缓冲上，抬手时才按不透明度合成进图层，避免笔迹重叠处越描越实 */
    private var strokeBuffer: Bitmap? = null
    /**
     * 这一笔里每个像素「已经吃过」的覆盖度（0..255）。减淡/加深/海绵按增量算效果，
     * 同一笔里同一处不管经过多少次，总强度只按最大覆盖度算一次，画出来才均匀。
     */
    private var strokeMax: ByteArray? = null
    /** 低不透明度逐段合成：按 256 像素小块存「起笔时的原图」，只存画到的地方，大文档也不吃内存 */
    private val liveTiles = HashMap<Long, IntArray>()
    private val liveTile = 256
    private var liveMode = false
    // 大图落笔期间不写回图层（每写一次 Skia 要整张重传纹理，必然不跟手）：
    // 笔迹先画进一张「屏幕大小」的预览位图（约 4MB），收笔再一次性合成进图层。
    // 必须是整笔累积、屏幕空间——按窗口裁剪的话窗口一挪，之前画的整段就消失（0.67.56 的 bug）
    private var deferLive = false
    private var overlayCanvas: Canvas? = null
    private var strokeOverlay: Bitmap? = null
    private val overlayView = Matrix()
    private val overlayInvView = Matrix()
    private val overlayDrawMatrix = Matrix()
    private val overlayCopyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** 数位笔倾斜（0=垂直屏幕，PI/2=几乎平放）与笔尖朝向，用来做扁平笔锋 */
    private var strokeTilt = 0f
    private var strokeOrient = 0f
    // 仿制图章 / 修复画笔
    private var cloneSnapshot: Bitmap? = null
    private var cloneDX = 0f
    private var cloneDY = 0f
    private var cloneLastX = Float.NaN
    private var cloneLastY = Float.NaN
    private var cloneDab: Bitmap? = null
    private var cloneDabCanvas: Canvas? = null
    private var cloneDabSize = 0
    private var cloneDabPaint: Paint? = null
    private var cloneDabMask: Paint? = null
    private val cloneMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4F8CFF.toInt()
    }

    /** 取样点（文档坐标）；为空时第一次点击就是取样 */
    var cloneSource: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 对齐：开着时每一笔都沿用同一个相对位置（桌面端的「对齐」勾选） */
    private val cloneAlignedS = androidx.compose.runtime.mutableStateOf(true)
    var cloneAligned: Boolean
        get() = cloneAlignedS.value
        set(v) { cloneAlignedS.value = v }

    /** 样本：false 当前图层，true 所有图层（桌面端的「样本」下拉） */
    private val cloneAllLayersS = androidx.compose.runtime.mutableStateOf(false)
    var cloneAllLayers: Boolean
        get() = cloneAllLayersS.value
        set(v) { cloneAllLayersS.value = v }

    /** 修复画笔的扩散（0~7），越大边缘越糊 */
    private val healDiffusionS = androidx.compose.runtime.mutableFloatStateOf(3f)
    var healDiffusion: Float
        get() = healDiffusionS.floatValue
        set(v) { healDiffusionS.floatValue = v }

    /** 污点修复的类型：0 内容识别（周围平均）、1 创建纹理（取最近样本）、2 近似匹配（取颜色最像的） */
    private val spotTypeS = androidx.compose.runtime.mutableIntStateOf(0)
    var spotType: Int
        get() = spotTypeS.intValue
        set(v) { spotTypeS.intValue = v }

    /** 污点修复：取样圈宽（在笔迹外侧多少倍半径处取样），0.5~3 */
    private val spotRingS = androidx.compose.runtime.mutableFloatStateOf(1.0f)
    var spotRing: Float
        get() = spotRingS.floatValue
        set(v) { spotRingS.floatValue = v }

    /** 污点修复：边缘融合（1 = 软过渡，0 = 硬边） */
    private val spotFeatherS = androidx.compose.runtime.mutableFloatStateOf(0.6f)
    var spotFeather: Float
        get() = spotFeatherS.floatValue
        set(v) { spotFeatherS.floatValue = v }

    /**
     * 把选区里的画面存成图案（桌面端的「编辑 → 定义图案」）。
     * 返回图案在 Patterns.all() 里的下标；没有选区就用整个图层。
     */
    fun definePatternFromSelection(name: String = "自定"): Int? {
        val layer = activeLayer ?: return null
        val bmp = needBitmap(layer) ?: return null
        val sel = selection
        val left: Int
        val top: Int
        val w: Int
        val h: Int
        if (sel != null) {
            val px = IntArray(sel.width * sel.height)
            sel.getPixels(px, 0, sel.width, 0, 0, sel.width, sel.height)
            var x0 = sel.width
            var y0 = sel.height
            var x1 = -1
            var y1 = -1
            for (y in 0 until sel.height) {
                for (x in 0 until sel.width) {
                    if ((px[y * sel.width + x] ushr 24) > 128) {
                        if (x < x0) x0 = x
                        if (y < y0) y0 = y
                        if (x > x1) x1 = x
                        if (y > y1) y1 = y
                    }
                }
            }
            if (x1 < x0 || y1 < y0) return null
            left = x0
            top = y0
            w = x1 - x0 + 1
            h = y1 - y0 + 1
        } else {
            left = 0
            top = 0
            w = bmp.width
            h = bmp.height
        }
        val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(
            bmp, Rect(left, top, left + w, top + h), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        if (sel != null) {
            val cc = Canvas(crop)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            cc.drawBitmap(sel, Rect(left, top, left + w, top + h), Rect(0, 0, w, h), p)
        }
        return Patterns.addCustom(name, crop)
    }

    /** 取样完成回调（界面弹提示用） */
    var onCloneSample: (() -> Unit)? = null
    private val strokeBounds = Rect()
    private var strokeBoundsValid = false

    // ---- 选区交互状态 ----
    private var selStart = PointF(0f, 0f)
    private var selCurrent = PointF(0f, 0f)
    private var selDragging = false
    private var lassoPath: Path? = null
    private val polyPoints = mutableListOf<PointF>()
    /** 这次的按下动作是不是刚加了一个多边形顶点（双指缩放时要撤回它） */
    private var polyAddedOnDown = false
    private var wandPoint: PointF? = null
    private var selectionEdge: Bitmap? = null

    /** 几何选区（矩形/椭圆/单行/单列/套索/多边形/磁性）的轮廓，按屏幕像素画 1px 矢量蚂蚁线 */
    private var selOutline: Path? = null
    private val selInnerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xB3B9DCFF.toInt()
        isAntiAlias = true
    }

    private val selAntsDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val selAntsLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    /** 选区外压暗的遮罩（选区内正常显示，外面压暗），只在选区变化时重建 */
    private var selectionDim: Bitmap? = null

    /** 当前选区里到底有没有像素：以前 onDraw 每帧都读一遍整幅遮罩，缩放时掉帧 */
    private var selHasPixels = false
    private val selectionDimPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** 选区外压暗改成「偶奇路径填充」：不再需要一张全幅位图（8000px 文档省一次整图分配） */
    private var selDimPath: Path? = null
    private val selDimFill = Paint().apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val selectionEdgePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 蚂蚁线：黑白相间方块，沿边缘流动 */
    private val antsPattern = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val antsPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 棋盘 shader 复涂时用：只画在已有像素上（SRC_IN），别把整块矩形铺满 */
    private val antsFillPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val antsMatrix = Matrix()
    private var antsPhase = 0f
    private val antsTicker = object : Runnable {
        override fun run() {
            if (selection == null) return
            // 缩放中不再暂停：现在缩放期间只画矢量轮廓和压暗（都是一两笔），
            // 停掉反而让用户看不见边界在哪
            if (selStaleDuringZoom) {
                postDelayed(this, 200)
                return
            }
            // 回卷周期 80：棋盘 8px 与矢量虚线 10px 都能整除，动画接缝不跳
            antsPhase = (antsPhase + 1f) % 80f
            invalidate()
            postDelayed(this, 90)
        }
    }

    /** 这次手势是不是从画布外面按下的：外面按下不启动任何工具，只保留缩放和长按 */
    private var downOutsideDoc = false
    /** 长按弹选区菜单 */
    var onSelectionMenu: ((Float, Float) -> Unit)? = null
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (movedDistance > 26f || selection == null) return@Runnable
        longPressFired = true
        cancelStroke()
        selDragging = false
        lassoPath = null
        onSelectionMenu?.invoke(lastViewPoint.x, lastViewPoint.y)
        invalidate()
    }
    private val selShapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    init {
        selectionEdgePaint.alpha = 255
        val c = Canvas(antsPattern)
        c.drawColor(Color.WHITE)
        val p = Paint()
        p.color = Color.BLACK
        c.drawRect(0f, 0f, 4f, 4f, p)
        c.drawRect(4f, 4f, 8f, 8f, p)
        val shader =
            BitmapShader(antsPattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        antsPaint.shader = shader
        antsFillPaint.shader = shader
    }

    /**
     * 实时预览（抠图/色阶调参时用）：这张图暂时顶替 [previewLayerIndex] 那一层绘制。
     * 可能比文档小（预览用小尺寸算得快），绘制时会拉伸到文档大小。
     */
    /** 显示蒙版：整幅铺黑白蒙版，用来确认范围到底罩住了哪（对齐影视调色软件的显示蒙版） */
    var showMask: Boolean = false
        set(v) { field = v; invalidate() }

    var previewBitmap: Bitmap? = null
        private set
    var previewLayerIndex: Int = -1
        private set

    private var downTime = 0L
    private var maxPointers = 0
    private var movedDistance = 0f
    private var lastViewPoint = PointF()
    private var lastImagePoint = PointF()
    private var lastMid = PointF()
    private var lastDist = 0f
    private var lastAngle = 0f
    private var transforming = false
    private var drawing = false

    init {
        val checker = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val cc = Canvas(checker)
        cc.drawColor(0xFFEFF1F4.toInt())
        val p = Paint()
        p.color = 0xFFF4F5F7.toInt()
        cc.drawRect(0f, 0f, 16f, 16f, p)
        cc.drawRect(16f, 16f, 32f, 32f, p)
        checkerPaint.shader = BitmapShader(checker, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        fencePaint.style = Paint.Style.STROKE
        fencePaint.strokeWidth = resources.displayMetrics.density
        fencePaint.color = 0x1F000000
    }

    // ================= 文档 =================

    fun setImage(source: Bitmap) {
        clearCloneState()
        val bmp = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true) ?: return
        }
        layers.clear()
        layers.add(Layer(bmp))
        activeIndex = 0
        undoStack.clear()
        redoStack.clear()
        hasDocument = true
        clearSelection()
        fitToView()
        onDocChanged?.invoke()
        invalidate()
    }

    fun newDocument(width: Int, height: Int, transparent: Boolean) {
        clearCloneState()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (!transparent) bmp.eraseColor(Color.WHITE)
        layers.clear()
        layers.add(Layer(bmp))
        activeIndex = 0
        undoStack.clear()
        redoStack.clear()
        hasDocument = true
        clearSelection()
        fitToView()
        onDocChanged?.invoke()
        invalidate()
    }

    /**
     * 打开一张图片当文档：整份文档就是这张图（不是「置入到已有画布」）。
     * 第一次导入走这里，之后导入才用 addImageLayer 当置入。
     */
    fun openImageAsDocument(src: Bitmap): Boolean {
        if (src.width <= 0 || src.height <= 0) return false
        clearCloneState()
        val base = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(base).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        layers.clear()
        layers.add(Layer(base, "图片"))
        activeIndex = 0
        undoStack.clear()
        redoStack.clear()
        hasDocument = true
        clearSelection()
        dropMips()
        invalidateFlatSnapshot()
        fitToView()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        invalidate()
        return true
    }

    /**
     * 把当前画布导成 PSD 字节：每层带上名字/可见性/不透明度/混合模式/剪贴/组结构/蒙版。
     * 调整层（色相饱和度、亮度对比、色阶）与渐变映射在这种「整层重建」路径下会烘成像素，
     * 因为 桌面端的调整块需要原始参数与层引用，重建时不保证对得上。
     */
    fun exportPsdBytes(): ByteArray? {
        val first = layers.firstOrNull { it.bitmap != null || it.tiles != null } ?: return null
        val base = needBitmap(first) ?: return null
        val w = base.width
        val h = base.height
        val out = ArrayList<com.reveal.editor.image.Psd.PsdLayer>()
        for (l in layers) {
            val bmp = needBitmap(l) ?: continue
            if (l.sectionType == 3) {
                // 组底分隔层：PSD 里用 lsct=3 的空层表示
                out.add(com.reveal.editor.image.Psd.PsdLayer(l.name, bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false), l.visible, 0, 0).also { it.sectionType = 3 })
                continue
            }
            val pl = com.reveal.editor.image.Psd.PsdLayer(
                l.name,
                bmp,
                l.visible,
                l.alpha,
                l.blendMode,
                l.clipped,
                l.alphaLock,
            )
            pl.mask = l.mask
            pl.sectionType = l.sectionType
            out.add(pl)
        }
        if (out.isEmpty()) return null
        return runCatching { com.reveal.editor.image.Psd.encode(w, h, out) }.getOrNull()
    }

    /** 用一组现成图层替换整份文档（PSD 导入用） */
    fun replaceLayersWith(newLayers: List<Layer>, width: Int, height: Int) {
        if (newLayers.isEmpty() || width <= 0 || height <= 0) return
        layers.clear()
        layers.addAll(newLayers)
        activeIndex = (layers.size - 1).coerceIn(0, layers.lastIndex)
        undoStack.clear()
        redoStack.clear()
        hasDocument = true
        clearSelection()
        dropMips()
        invalidateFlatSnapshot()
        fitToView()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        invalidate()
    }

    fun flatten(): Bitmap? {
        val first = layers.firstOrNull { it.bitmap != null || it.tiles != null }
            ?.let { needBitmap(it) } ?: return null
        val out = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val savedPreview = previewBitmap
        val savedIndex = previewLayerIndex
        val savedFilter = bitmapPaint.isFilterBitmap
        previewBitmap = null
        previewLayerIndex = -1
        bitmapPaint.isFilterBitmap = true
        drawLayers(c)
        bitmapPaint.isFilterBitmap = savedFilter
        previewBitmap = savedPreview
        previewLayerIndex = savedIndex
        return out
    }

    fun documentSize(): Pair<Int, Int>? {
        val bmp = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return null
        return bmp.width to bmp.height
    }

    /** 当前图层的一份拷贝（给抠图这类会算很久的处理用，别直接改屏幕上的位图） */
    fun snapshotActiveLayer(): Bitmap? = activeLayer?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)

    /** 当前图层下面那一层（尺寸对齐到当前图层）——混合色带抠图要用它当参考 */
    fun layerBelowActive(): Bitmap? {
        val below = layers.getOrNull(activeIndex - 1) ?: return null
        val src = below.bitmap ?: return null
        val target = activeLayer?.bitmap ?: return null
        if (src.width == target.width && src.height == target.height) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        val out = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, null, Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    /** 把所有可见图层压成一张图（调色区「合并成一张」用） */
    fun mergedBitmap(): Bitmap? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        layers.forEach { layer ->
            if (!layer.visible) return@forEach
            val bmp = needBitmap(layer) ?: return@forEach
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.alpha = effectiveAlpha(layer)
            canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), p)
        }
        return out
    }

    /** 每个可见图层各导一张（调色区「逐层导入」用） */
    fun layerBitmaps(): List<Bitmap> =
        layers.filter { it.visible && it.bitmap != null }.mapNotNull { layer ->
            layer.bitmap?.let { bmp ->
                if (bmp.width == width && bmp.height == height) bmp.copy(Bitmap.Config.ARGB_8888, false)
                else {
                    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(out).drawBitmap(bmp, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                    out
                }
            }
        }

    /** 用处理结果替换指定图层（调色/色阶这类按层处理的用，可撤销） */
    fun replaceLayerAt(index: Int, newBitmap: Bitmap, label: String = "调整") {
        val layer = layers.getOrNull(index) ?: return
        val old = needBitmap(layer) ?: return
        if (newBitmap.width != old.width || newBitmap.height != old.height) return
        val w = old.width
        val h = old.height
        val before = IntArray(w * h)
        old.getPixels(before, 0, w, 0, 0, w, h)
        val incoming = IntArray(w * h)
        newBitmap.getPixels(incoming, 0, w, 0, 0, w, h)
        // 先建 Patch 抓 16 位快照，再改像素——顺序反了的话快照抓到的就是「改完之后」的缓冲
        val stroke = Stroke().also { it.label = label }
        stroke.patches.add(Patch(layer, 0, 0, w, h, before, incoming))
        old.setPixels(incoming, 0, w, 0, 0, w, h)
        syncPix16ByLut(layer, before, incoming, w, h)
        pushUndo(stroke)
        dropMips()
        invalidate()
        onDocChanged?.invoke()
    }

    /** 用处理结果替换当前图层内容，整个过程可撤销 */
    fun replaceActiveLayer(newBitmap: Bitmap, label: String = "调整") {
        val layer = activeLayer ?: return
        val old = needBitmap(layer) ?: return
        if (newBitmap.width != old.width || newBitmap.height != old.height) return
        val w = old.width
        val h = old.height
        val before = IntArray(w * h)
        old.getPixels(before, 0, w, 0, 0, w, h)
        val incoming = IntArray(w * h)
        newBitmap.getPixels(incoming, 0, w, 0, 0, w, h)
        val stroke = Stroke().also { it.label = label }
        stroke.patches.add(Patch(layer, 0, 0, w, h, before, incoming))
        old.setPixels(incoming, 0, w, 0, 0, w, h)
        syncPix16ByLut(layer, before, incoming, w, h)
        pushUndo(stroke)
        dropMips()
        invalidate()
    }

    // ================= 实时预览 =================

    /**
     * 调参时用：把 [layerIndex] 那层临时换成预览图（可小于文档尺寸，绘制时拉伸）。
     *
     * 关键保护：预览图如果是「带蒙版的半成品」（范围蒙版常见做法，蒙版外 alpha = 0），
     * 直接贴上去会让圈外变透明。这里统一把它**叠在原图层上**再显示：
     * 预览图 alpha=0 的地方露出原图，圈外永远不会消失。
     */
    fun setPreview(bitmap: Bitmap?, layerIndex: Int = activeIndex) {
        if (bitmap == null) {
            previewBitmap = null
            previewLayerIndex = -1
            invalidate()
            return
        }
        val base = layers.getOrNull(layerIndex)?.let { runCatching { needBitmap(it) }.getOrNull() }
        val safe = if (base != null && (activeRegion >= 0 || regionsS.isNotEmpty())) {
            runCatching {
                val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val cv = Canvas(out)
                cv.drawBitmap(
                    base, null, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                cv.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                out
            }.getOrDefault(bitmap)
        } else bitmap
        previewBitmap = safe
        previewLayerIndex = layerIndex
        invalidate()
    }

    fun clearPreview() {
        if (previewBitmap == null) return
        previewBitmap = null
        previewLayerIndex = -1
        invalidate()
    }

    // ================= 图层 =================

    /** 用一整套图层替换当前文档（读工程时用） */
    fun setDocument(newLayers: List<Layer>) {
        clearCloneState()
        if (newLayers.isEmpty()) return
        layers.clear()
        layers.addAll(newLayers)
        activeIndex = layers.lastIndex
        undoStack.clear()
        redoStack.clear()
        strokeBuffer = null
        strokeBoundsValid = false
        previewBitmap = null
        previewLayerIndex = -1
        hasDocument = true
        clearSelection()
        fitToView()
        onDocChanged?.invoke()
        invalidate()
    }

    fun selectLayer(index: Int) {
        if (index !in layers.indices || index == activeIndex) return
        cancelStroke()
        clearPreview()
        activeIndex = index
        invalidate()
        onActiveLayerChanged?.invoke()
    }

    /** 在当前图层上方插入一张同样大小的透明图层，返回新图层下标 */
    /**
     * 新建图层 / 调整层插在哪儿。
     * 当前选中是组头时插到组头下面 = 组里最上面那一层；否则插在当前层上面。
     * 选着组却把新层建到组外面去，用户看到的就是"新建的层跑出组了"。
     */
    private fun insertSpotForNew(): Int {
        val at = activeIndex.coerceIn(0, layers.lastIndex)
        val l = layers.getOrNull(at)
        val inside = l != null && (l.sectionType == 1 || l.sectionType == 2)
        return (if (inside) at else at + 1).coerceIn(0, layers.size)
    }

    fun addLayer(name: String? = null): Int {
        val ref = activeLayer?.bitmap ?: return -1
        val bmp = Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888)
        val insertAt = insertSpotForNew()
        layers.add(insertAt, Layer(bmp, name ?: "图层 ${layers.size + 1}"))
        activeIndex = insertAt
        onActiveLayerChanged?.invoke()
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        return insertAt
    }

    /** 新建调整层（放在当前层上面），返回新层下标 */
    fun addAdjustLayer(op: AdjustOp): Int {
        val ref = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return -1
        val insertAt = insertSpotForNew()
        val l = Layer(Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888), op.title())
        l.adjust = op
        layers.add(insertAt, l)
        activeIndex = insertAt
        onActiveLayerChanged?.invoke()
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        return insertAt
    }

    /**
     * 新建一个空组（插在当前层上面）。
     * 组在 PSD 里是两层：上面是组头（lsct 1/2），最下面是组底分隔层（lsct 3），
     * 中间夹着的层就是这个组的成员。所以这里一次性插两层。
     */
    fun addGroup(name: String? = null): Int {
        val ref = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return -1
        val insertAt = (activeIndex + 1).coerceIn(0, layers.size)
        val title = name ?: "组 " + (layers.count { it.sectionType == 1 || it.sectionType == 2 } + 1)
        val divider = Layer(Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888), title)
        divider.sectionType = 3
        val header = Layer(Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888), title)
        header.sectionType = 1
        layers.add(insertAt, divider)
        layers.add(insertAt + 1, header)
        activeIndex = insertAt + 1
        onActiveLayerChanged?.invoke()
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        return insertAt + 1
    }

    /** 组头到组底分隔层一共占了几个位置（含两端的标记层）；不是组就是 1 */
    private fun groupSpan(header: Int): Int {
        var depth = 0
        var i = header
        while (i >= 0) {
            val t = layers[i].sectionType
            if (t == 1 || t == 2) depth++
            if (t == 3) {
                depth--
                if (depth == 0) return header - i + 1
            }
            i--
        }
        return 1
    }

    /** 折叠 / 展开一个组；状态写回 lsct（1=展开 2=折叠），存工程和导出 PSD 都跟着走 */
    fun toggleGroupCollapsed(index: Int): Boolean {
        val l = layers.getOrNull(index) ?: return false
        l.sectionType = when (l.sectionType) {
            1 -> 2
            2 -> 1
            else -> return false
        }
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 整组显示 / 隐藏：成员一起改 */
    fun setGroupVisible(index: Int, visible: Boolean) {
        val l = layers.getOrNull(index) ?: return
        if (l.sectionType != 1 && l.sectionType != 2) return
        val span = groupSpan(index)
        for (i in (index - span + 1)..index) {
            layers.getOrNull(i)?.visible = visible
        }
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
    }

    /** 整组删掉（组头 + 成员 + 组底分隔层） */
    fun deleteGroupAt(index: Int): Boolean {
        val l = layers.getOrNull(index) ?: return false
        if (l.sectionType != 1 && l.sectionType != 2) return false
        val span = groupSpan(index)
        val from = index - span + 1
        if (from < 0 || layers.size - span < 1) return false
        repeat(span) { layers.removeAt(from) }
        activeIndex = activeIndex.coerceIn(0, layers.lastIndex)
        onActiveLayerChanged?.invoke()
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 改调整层参数（面板拖滑时调） */
    fun setAdjust(index: Int, op: AdjustOp) {
        val l = layers.getOrNull(index) ?: return
        // 调整组（一个层叠多个效果）：面板传来的还是「单个效果」的参数，
        // 这里按 kind 就地替换组里的那一项，没这项就加进去。
        val cur = l.adjust
        val real = if (cur != null && cur.kind == AdjustOp.KIND_CONTAINER && op.kind != AdjustOp.KIND_CONTAINER) {
            cur.withChild(op)
        } else op
        l.adjust = real
        l.name = real.title()
        invalidate()
        onDocChanged?.invoke()
    }

    /** 往调整组里加一个效果（不是组就先包成组），返回组里这一项的最新参数 */
    fun appendAdjustChild(index: Int, op: AdjustOp): AdjustOp? {
        val l = layers.getOrNull(index) ?: return null
        val cur = l.adjust ?: return null
        val real = if (cur.kind == AdjustOp.KIND_CONTAINER) cur.withChild(op)
        else AdjustOp(kind = AdjustOp.KIND_CONTAINER, children = listOf(cur, op))
        l.adjust = real
        l.name = real.title()
        invalidate()
        onDocChanged?.invoke()
        return op
    }

    /** 调整组：删掉第几项 */
    fun removeAdjustChild(index: Int, childIndex: Int): AdjustOp? {
        val l = layers.getOrNull(index) ?: return null
        val cur = l.adjust ?: return null
        if (cur.kind != AdjustOp.KIND_CONTAINER) return null
        val real = cur.withoutChild(childIndex)
        l.adjust = real
        l.name = real.title()
        invalidate()
        onDocChanged?.invoke()
        return real
    }

    fun adjustAt(index: Int): AdjustOp? = layers.getOrNull(index)?.adjust

    fun deleteActiveLayer(): Boolean {
        // 最后一张也能删：删完就是空文档（之后导入图片会当成「打开图片」）
        val at = activeIndex.coerceIn(0, layers.lastIndex)
        if (layers.isEmpty()) return false
        // 以前这里删完就 undoStack.clear()，等于把整个历史一起扔了，
        // 用户按撤销什么都不回来。现在把被删的层记成一条结构步骤，位图也不回收。
        val gone = layers.removeAt(at)
        activeIndex = activeIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
        if (layers.isEmpty()) {
            hasDocument = false
            clearSelection()
            invalidateFlatSnapshot()
        }
        onActiveLayerChanged?.invoke()
        pushUndo(Stroke().also {
            it.label = "删除图层"
            it.removedLayer = gone
            it.removedIndex = at
        })
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 上移 = 往数组后面（画在更上层），下移 = 往数组前面 */
    fun moveActiveLayer(up: Boolean): Boolean {
        val target = if (up) activeIndex + 1 else activeIndex - 1
        if (target !in layers.indices) return false
        val layer = layers.removeAt(activeIndex)
        layers.add(target, layer)
        activeIndex = target
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 多选状态（放在这里而不是面板里：关掉面板再打开、切工具回来都还在） */
    var multiSelectMode: Boolean = false
    val multiPicked = mutableListOf<Int>()

    fun setMultiState(mode: Boolean, picked: List<Int>) {
        multiSelectMode = mode
        multiPicked.clear()
        multiPicked.addAll(picked)
    }

    /** 批量结构步骤：把图层表的前后顺序记进撤销栈（只存引用，很便宜） */
    private fun pushLayerOrder(label: String, before: List<Layer>) {
        pushUndo(Stroke().also {
            it.label = label
            it.orderBefore = before
            it.orderAfter = layers.toList()
        })
    }

    /** 多选删除：一次撤销能把整批层都拿回来 */
    fun deleteLayersAt(indices: List<Int>): Boolean {
        val list = indices.filter { it in layers.indices }.distinct().sorted()
        if (list.isEmpty() || list.size >= layers.size) return false
        val before = layers.toList()
        for (i in list.sortedDescending()) layers.removeAt(i)
        activeIndex = activeIndex.coerceIn(0, layers.lastIndex)
        onActiveLayerChanged?.invoke()
        pushLayerOrder("删除 " + list.size + " 层", before)
        invalidateFlatSnapshot()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 多选打成组：组头在最上面一层的上面，组底分隔层在最下面一层的下面 */
    fun groupLayersAt(indices: List<Int>): Int {
        val ref = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return -1
        val list = indices.filter { it in layers.indices }.distinct().sorted()
        if (list.isEmpty()) return -1
        val before = layers.toList()
        val top = list.last()
        val title = "组 " + (layers.count { it.sectionType == 1 || it.sectionType == 2 } + 1)
        val divider = Layer(Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888), title)
        divider.sectionType = 3
        val header = Layer(Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888), title)
        // 打成一包之后顺手折起来：多选一般是为了收拾，折起来一眼看到成品
        header.sectionType = 2
        layers.add(list.first(), divider)
        layers.add(top + 1, header)
        activeIndex = top + 1
        onActiveLayerChanged?.invoke()
        pushLayerOrder("打成组 " + list.size + " 层", before)
        invalidateFlatSnapshot()
        invalidate()
        onDocChanged?.invoke()
        return top + 1
    }

    /** 组头对应的组底分隔层（没有就 null） */
    private fun groupDividerFor(headerIndex: Int): Layer? {
        val span = groupSpan(headerIndex)
        if (span <= 1) return null
        val idx = headerIndex - span + 1
        return layers.getOrNull(idx)?.takeIf { it.sectionType == 3 }
    }

    /** 解组：去掉组头与组底分隔层，成员原样留下 */
    fun ungroupAt(headerIndex: Int): Boolean {
        val header = layers.getOrNull(headerIndex) ?: return false
        if (header.sectionType != 1 && header.sectionType != 2) return false
        val divider = groupDividerFor(headerIndex) ?: return false
        val before = layers.toList()
        layers.removeAt(headerIndex)
        val d = layers.indexOfFirst { it === divider }
        if (d >= 0) layers.removeAt(d)
        activeIndex = activeIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
        pushLayerOrder("解组", before)
        invalidateFlatSnapshot()
        onActiveLayerChanged?.invoke()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 整组上下移动一格（组头和组底分隔层一起动） */
    fun moveGroupBlock(headerIndex: Int, up: Boolean): Boolean {
        val header = layers.getOrNull(headerIndex) ?: return false
        if (header.sectionType != 1 && header.sectionType != 2) return false
        val divider = groupDividerFor(headerIndex) ?: return false
        val hi = layers.indexOfFirst { it === header }
        val di = layers.indexOfFirst { it === divider }
        if (hi < 0 || di < 0) return false
        val before = layers.toList()
        if (up) {
            // 往上一层挪：块顶 = divider，块底 = header+1（列表里 index 越大越上层）
            val next = hi + 1
            if (next > layers.lastIndex) return false
            val block = layers.subList(di, hi + 1).toList()
            if (next >= layers.size) return false
            val target = layers[next]
            if (target === header || block.contains(target)) return false
            // 交换：把 target 挪到 divider 前面
            layers.removeAt(next)
            val at = layers.indexOfFirst { it === divider }
            layers.add(if (at >= 0) at else 0, target)
        } else {
            val prev = di - 1
            if (prev < 0) return false
            val target = layers[prev]
            if (target === divider) return false
            layers.removeAt(prev)
            layers.add(layers.indexOfFirst { it === header } + 1, target)
        }
        pushLayerOrder("移动组", before)
        invalidateFlatSnapshot()
        onActiveLayerChanged?.invoke()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 多选合并成一层（正常模式叠加；混合模式按自己的模式画进去） */
    fun mergeLayersAt(indices: List<Int>): Boolean {
        val list = indices.filter { it in layers.indices }.distinct().sorted()
        if (list.size < 2) return false
        val ref = layers.firstNotNullOfOrNull { it.bitmap } ?: return false
        val before = layers.toList()
        val out = runCatching {
            Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return false
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in list) {
            val l = layers.getOrNull(i) ?: continue
            val b = needBitmap(l) ?: continue
            p.alpha = effectiveAlpha(l).coerceIn(0, 255)
            // 每一层按自己的混合模式叠进去，合并出来的样子跟画面上一样
            applyBlendMode(p, l.blendMode)
            runCatching { c.drawBitmap(b, 0f, 0f, p) }
            p.alpha = 255
        }
        p.xfermode = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            p.blendMode = android.graphics.BlendMode.SRC_OVER
        }
        val at = list.first()
        val merged = Layer(out, "合并 " + list.size + " 层")
        for (i in list.sortedDescending()) layers.removeAt(i)
        layers.add(at, merged)
        activeIndex = at
        onActiveLayerChanged?.invoke()
        pushLayerOrder("合并 " + list.size + " 层", before)
        invalidateFlatSnapshot()
        dropMips()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 多选一起显示 / 隐藏 */
    fun setLayersVisibleAt(indices: List<Int>, visible: Boolean) {
        for (i in indices) layers.getOrNull(i)?.visible = visible
        invalidate()
        onDocChanged?.invoke()
    }

    /** 多选一起上移 / 下移一格：连续选中的整段一起挪，不会互相踩 */
    fun moveLayersAt(indices: List<Int>, up: Boolean): Boolean {
        val list = indices.filter { it in layers.indices }.distinct()
        if (list.isEmpty()) return false
        val before = layers.toList()
        val act = layers.getOrNull(activeIndex)
        val sel = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Layer, Boolean>())
        for (i in list) sel.add(layers[i])
        var moved = false
        if (up) {
            var i = layers.lastIndex
            while (i >= 0) {
                if (!sel.contains(layers[i])) {
                    i--
                    continue
                }
                var start = i
                while (start - 1 >= 0 && sel.contains(layers[start - 1])) start--
                if (i + 1 <= layers.lastIndex && !sel.contains(layers[i + 1])) {
                    val run = (start..i).map { layers[it] }
                    for (k in i downTo start) layers.removeAt(k)
                    var ins = start + 1
                    for (l in run) {
                        layers.add(ins, l)
                        ins++
                    }
                    moved = true
                }
                i = start - 1
            }
        } else {
            var i = 0
            while (i <= layers.lastIndex) {
                if (!sel.contains(layers[i])) {
                    i++
                    continue
                }
                var end = i
                while (end + 1 <= layers.lastIndex && sel.contains(layers[end + 1])) end++
                if (i - 1 >= 0 && !sel.contains(layers[i - 1])) {
                    val run = (i..end).map { layers[it] }
                    for (k in end downTo i) layers.removeAt(k)
                    var ins = i - 1
                    for (l in run) {
                        layers.add(ins, l)
                        ins++
                    }
                    moved = true
                }
                i = end + 1
            }
        }
        if (!moved) return false
        activeIndex = layers.indexOfFirst { it === act }.let {
            if (it >= 0) it else activeIndex.coerceIn(0, layers.lastIndex)
        }
        onActiveLayerChanged?.invoke()
        pushLayerOrder((if (up) "上移 " else "下移 ") + list.size + " 层", before)
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        val layer = layers.getOrNull(index) ?: return
        layer.visible = visible
        invalidate()
    }

    /** 实际参与合成的 alpha：图层不透明度 × 填充不透明度 */
    fun effectiveAlpha(layer: Layer): Int =
        if (layer.fillAlpha >= 100) layer.alpha else layer.alpha * layer.fillAlpha / 100

    fun setLayerAlpha(index: Int, alpha: Int) {
        val layer = layers.getOrNull(index) ?: return
        layer.alpha = alpha.coerceIn(0, 255)
        onDocChanged?.invoke()
        invalidate()
    }

    fun setLayerAlphaLock(index: Int, locked: Boolean) {
        val layer = layers.getOrNull(index) ?: return
        layer.alphaLock = locked
    }

    /** 锁定：kind 0=图像像素 1=位置 2=全部 */
    fun setLayerLock(index: Int, kind: Int, on: Boolean) {
        val layer = layers.getOrNull(index) ?: return
        when (kind) {
            0 -> layer.lockImage = on
            1 -> layer.lockPos = on
            else -> layer.lockAll = on
        }
        invalidate()
        onDocChanged?.invoke()
    }

    fun setLayerFill(index: Int, fill: Int) {
        val layer = layers.getOrNull(index) ?: return
        layer.fillAlpha = fill.coerceIn(0, 100)
        invalidate()
        onDocChanged?.invoke()
    }

    fun setLayerColorTag(index: Int, tag: Int) {
        val layer = layers.getOrNull(index) ?: return
        layer.colorTag = tag.coerceIn(0, 6)
        onDocChanged?.invoke()
        invalidate()
    }

    /** 当前层是不是不能画（锁定图像/全部） */
    fun isActiveLayerPaintingLocked(): Boolean {
        val l = activeLayer ?: return false
        return l.lockImage || l.lockAll
    }

    /** 当前层是不是不能移动（锁定位置/全部） */
    fun isActiveLayerMoveLocked(): Boolean {
        val l = activeLayer ?: return false
        return l.lockPos || l.lockAll
    }

    /** 合并可见图层：把所有可见层合成一层，放在最下面那个可见层的位置 */
    fun mergeVisible(): Boolean {
        if (layers.any { it.sectionType == 3 }) return false
        val vis = layers.withIndex().filter { it.value.visible && it.value.sectionType == 0 }
        if (vis.size < 2) return false
        val first = vis.first().index
        val base = vis.mapNotNull { it.value.let { l -> bakedBitmap(l) } }
        if (base.isEmpty()) return false
        val out = Bitmap.createBitmap(base[0].width, base[0].height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for ((_, l) in vis) {
            val b = bakedBitmap(l) ?: continue
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.alpha = if (l.fillAlpha < 100) l.alpha * l.fillAlpha / 100 else l.alpha
            applyBlendMode(p, l.blendMode)
            c.drawBitmap(b, 0f, 0f, p)
            resetBlend(p)
        }
        val merged = Layer(out, layers[first].name)
        for (i in layers.size - 1 downTo 0) {
            if (i != first && layers[i].visible && layers[i].sectionType == 0) layers.removeAt(i)
        }
        val at = layers.indexOfFirst { it === layers.getOrNull(first) }
        val idx = if (at >= 0) at else first.coerceAtMost(layers.size)
        layers[idx] = merged
        activeIndex = idx
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 盖印可见图层：合成一张新层放在最上面，原图层都还在 */
    fun stampVisible(): Boolean {
        val vis = layers.filter { it.visible && it.sectionType == 0 }
        if (vis.isEmpty()) return false
        val base = vis.mapNotNull { bakedBitmap(it) }.firstOrNull() ?: return false
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (l in vis) {
            val b = bakedBitmap(l) ?: continue
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.alpha = if (l.fillAlpha < 100) l.alpha * l.fillAlpha / 100 else l.alpha
            applyBlendMode(p, l.blendMode)
            c.drawBitmap(b, 0f, 0f, p)
            resetBlend(p)
        }
        layers.add(Layer(out, "盖印图层"))
        activeIndex = layers.size - 1
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 栅格化文字层：把文字画进位图，去掉文字属性 */
    fun rasterizeLayer(index: Int): Boolean {
        val l = layers.getOrNull(index) ?: return false
        val tc = l.textContent ?: return false
        val sized = l.bitmap ?: layers.firstOrNull { it.bitmap != null }?.bitmap ?: return false
        l.bitmap = renderText(sized.width, sized.height, tc, l.textSize, l.textColor, l.textX, l.textY, l.textFont)
        l.textContent = null
        l.name = if (l.name.startsWith("文字")) "图层" else l.name
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 全部显示 / 全部隐藏 */
    fun setAllLayersVisible(visible: Boolean) {
        for (l in layers) if (l.sectionType == 0 || l.sectionType == 1) l.visible = visible
        invalidate()
        onDocChanged?.invoke()
    }

    /** 选中和当前层同颜色标签的图层（桌面端的「选择相似图层」简化版） */
    fun selectSimilarLayers(colorTag: Int): List<Int> {
        if (colorTag <= 0) return emptyList()
        return layers.withIndex().filter { it.value.colorTag == colorTag && it.value.sectionType == 0 }.map { it.index }
    }

    private var styleClip: StyleParams? = null

    /** 拷贝当前层样式 */
    fun copyLayerStyle(index: Int): Boolean {
        val l = layers.getOrNull(index) ?: return false
        styleClip = StyleParams(
            strokeOn = l.strokeOn, strokeColor = l.strokeColor, strokeWidth = l.strokeWidth,
            shadowOn = l.shadowOn, shadowDX = l.shadowDX, shadowDY = l.shadowDY,
            shadowBlur = l.shadowBlur, shadowColor = l.shadowColor,
            glowOn = l.glowOn, glowColor = l.glowColor, glowSize = l.glowSize,
            innerShadowOn = l.innerShadowOn, innerShadowColor = l.innerShadowColor,
            innerShadowDX = l.innerShadowDX, innerShadowDY = l.innerShadowDY, innerShadowSize = l.innerShadowSize,
            innerGlowOn = l.innerGlowOn, innerGlowColor = l.innerGlowColor, innerGlowSize = l.innerGlowSize,
            colorOverlayOn = l.colorOverlayOn, colorOverlayColor = l.colorOverlayColor,
            gradientOverlayOn = l.gradientOverlayOn, gradientOverlayColor1 = l.gradientOverlayColor1,
            gradientOverlayColor2 = l.gradientOverlayColor2, gradientOverlayAngle = l.gradientOverlayAngle,
            bevelOn = l.bevelOn, bevelSize = l.bevelSize, bevelDepth = l.bevelDepth,
            satinOn = l.satinOn, satinColor = l.satinColor, satinSize = l.satinSize,
            satinDistance = l.satinDistance, satinAngle = l.satinAngle,
            patternOverlayOn = l.patternOverlayOn, patternOverlayIndex = l.patternOverlayIndex,
            patternOverlayScale = l.patternOverlayScale, patternOverlayOpacity = l.patternOverlayOpacity,
            textureOn = l.textureOn, texturePatternIndex = l.texturePatternIndex,
            textureScale = l.textureScale, textureDepth = l.textureDepth,
            contourOn = l.contourOn, contourKind = l.contourKind,
        )
        return true
    }

    fun hasStyleClip(): Boolean = styleClip != null

    /** 粘贴图层样式到某层 */
    fun pasteLayerStyle(index: Int): Boolean {
        val s0 = styleClip ?: return false
        if (layers.getOrNull(index) == null) return false
        setLayerStyle(index, s0)
        return true
    }

    fun setLayerClipped(index: Int, clipped: Boolean) {
        val layer = layers.getOrNull(index) ?: return
        // 最下面那层没有可用来剪贴的底图
        if (clipped && index == 0) return
        layer.clipped = clipped
        invalidate()
    }

    fun layerNames(): List<String> = layers.map { it.name }

    // ================= 视图 =================

    fun fitToView() {
        val bmp = activeLayer?.bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height) * 0.94f
        viewMatrix.reset()
        viewMatrix.postScale(scale, scale)
        viewMatrix.postTranslate((width - bmp.width * scale) / 2f, (height - bmp.height * scale) / 2f)
        invalidate()
        notifyZoom()
    }

    /** 当前缩放倍数（相对文档像素） */
    fun zoomFactor(): Float = viewScale()

    /** 视图里能看到的那块文档区域（文档坐标） */
    fun viewportRect(): RectF {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val inv = Matrix()
        if (viewMatrix.invert(inv)) inv.mapRect(r)
        return r
    }

    /** 以视图中心为锚点缩放，pct = 12..800 */
    fun zoomToPercent(pct: Int) {
        val target = (pct.coerceIn(12, 800) / 100f)
        val cur = viewScale()
        if (cur <= 0f) return
        val f = target / cur
        viewMatrix.postScale(f, f, width / 2f, height / 2f)
        invalidate()
        notifyZoom()
    }
    /** 桌面模式的滚轮：上下滚画面 */
    fun panView(dx: Float, dy: Float) {
        viewMatrix.postTranslate(dx, dy)
        invalidate()
    }

    /** 当前缩放百分比（给底部状态栏显示） */
    fun viewScalePercent(): Int = (viewScale() * 100f).toInt().coerceIn(1, 8000)

    fun zoomToActualSize() {
        val bmp = activeLayer?.bitmap ?: return
        if (width == 0 || height == 0) return
        viewMatrix.reset()
        viewMatrix.postTranslate((width - bmp.width) / 2f, (height - bmp.height) / 2f)
        invalidate()
        notifyZoom()
    }

    private fun toImage(x: Float, y: Float): PointF {
        inverse.set(viewMatrix)
        if (!inverse.invert(inverse)) return PointF(x, y)
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        compositeDirty = true
        if (!hasDocument || w <= 0 || h <= 0) return
        val bmp = activeLayer?.bitmap ?: return
        if (oldw <= 0 || oldh <= 0) {
            fitToView()
            return
        }
        // 只是控件高度变了（切工具时参数条收起/展开）：保持当前缩放，把文档重新居中，
        // 不要每次 fitToView，否则每换一个工具画面就缩小一次，得手动放大回来
        val sc = viewScale()
        // 保持「视口中心对应的那个文档点」不动：原来直接按画布居中重算，
        // 参数条一出现/一收起，用户平移过的画布就被弹回正中，看着像画面跳了一下。
        val centerDoc = PointF(bmp.width / 2f, bmp.height / 2f)
        runCatching {
            val inv = Matrix()
            inv.set(viewMatrix)
            if (inv.invert(inv)) {
                val pts = floatArrayOf(oldw / 2f, oldh / 2f)
                inv.mapPoints(pts)
                centerDoc.set(pts[0], pts[1])
            }
        }
        viewMatrix.reset()
        viewMatrix.postScale(sc, sc)
        viewMatrix.postTranslate(w / 2f - centerDoc.x * sc, h / 2f - centerDoc.y * sc)
        notifyZoom()
        invalidate()
    }

    // ================= 绘制 =================

    // ===== 图层换页：内存吃紧时把非活动层写成分块文件放磁盘 =====

    private fun tilesDir(): java.io.File? {
        val d = java.io.File(context.cacheDir, "tiles")
        if (!d.exists() && !d.mkdirs()) return null
        return d
    }

    private fun residentBytes(): Long {
        var n = 0L
        for (l in layers) {
            l.bitmap?.let { n += it.byteCount }
            l.styleCache?.let { n += it.byteCount }
        }
        return n
    }

    /** 把某层整层像素装回内存（保存 / 滤镜 / 合并这类操作才需要） */
    /**
     * 16 位文档里第一次改某一层时，把这层的 16 位缓冲建起来：
     * 有原始通道就直接用（精度最高），没有就从当前 8 位放大。
     */
    private fun ensurePix16(layer: Layer): com.reveal.editor.image.Pix16? =
        runCatching { ensurePix16Inner(layer) }.getOrNull()

    private fun ensurePix16Inner(layer: Layer): com.reveal.editor.image.Pix16? {
        // 大图：16 位精度缓冲一张就要 100~200 MB，会直接把堆撑爆。
        // 宁可在超大图上退回 8 位（视觉上几乎看不出差别），也不能让 App 崩。
        if (isHugeDoc()) {
            com.reveal.editor.data.Diag.append(
                context, "p16skip", "huge=" + pixelCount() + "px"
            )
            return null
        }
        if (docDepth != 16) {
            if (layer.rawPix != null) runCatching {
                com.reveal.editor.data.Diag.append(
                    context, "p16skip", "depth=" + docDepth + " but raw=" + (layer.rawPix?.size ?: -1)
                )
            }
            return null
        }
        val bmp = layer.bitmap ?: return null
        val old = layer.pix16
        if (old != null && old.width == bmp.width && old.height == bmp.height) return old
        if (bmp.width.toLong() * bmp.height > com.reveal.editor.image.Pix16.MAX_PIXELS) return null
        val fromRaw = com.reveal.editor.image.Psd.pix16FromRawPix(layer.rawPix, bmp)
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "p16init",
                "depth=" + docDepth + " raw=" + (layer.rawPix?.size ?: -1) +
                    " dims=" + bmp.width + "x" + bmp.height + " fromRaw=" + (fromRaw != null)
            )
        }
        val made = fromRaw
            ?: com.reveal.editor.image.Pix16(bmp.width, bmp.height).also { it.fillFrom(bmp) }
        layer.pix16 = made
        return made
    }

    /**
     * 几何操作（旋转/翻转/裁剪）要动 16 位缓冲之前，先把它“落实”：
     * 有原始通道就按原始通道建（真 16 位），没有才退回当前 8 位放大。
     */
    private fun pix16ForGeometry(layer: Layer): com.reveal.editor.image.Pix16? {
        val made = ensurePix16(layer)
        return made ?: layer.pix16
    }

    /**
     * 32 位文档里第一次改某一层时，把这层的浮点缓冲建起来：
     * 有原始 32 位通道就直接读（精度最高），没有就从当前 8 位放大。
     */
    private fun ensurePix32(layer: Layer): com.reveal.editor.image.Pix32? =
        runCatching { ensurePix32Inner(layer) }.getOrNull()

    /** 日志用：取几个采样点的 R 值，判断缓冲是浮点原值还是 8 位量化值（n/255） */
    private fun px32samples(p: com.reveal.editor.image.Pix32?): String {
        if (p == null) return "-"
        val n = p.width * p.height
        if (n <= 0) return "-"
        fun f(v: Float) = ((v * 10000f).toInt() / 10000f).toString()
        return f(p.r[0]) + "," + f(p.r[n / 2]) + "," + f(p.r[n - 1])
    }

    private fun ensurePix32Inner(layer: Layer): com.reveal.editor.image.Pix32? {
        if (docDepth != 32) {
            runCatching { com.reveal.editor.data.Diag.append(context, "p32skip", "depth=" + docDepth) }
            return null
        }
        val bmp = layer.bitmap ?: return null
        val old = layer.pix32
        if (old != null && old.width == bmp.width && old.height == bmp.height) return old
        if (bmp.width.toLong() * bmp.height > com.reveal.editor.image.Pix32.MAX_PIXELS) return null
        val fromRaw = com.reveal.editor.image.Psd.pix32FromRawPix(layer.rawPix, bmp)
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "p32init",
                "depth=" + docDepth + " raw=" + (layer.rawPix?.size ?: -1) +
                    " dims=" + bmp.width + "x" + bmp.height + " fromRaw=" + (fromRaw != null) +
                    " s=" + px32samples(fromRaw)
            )
        }
        val made = fromRaw
            ?: com.reveal.editor.image.Pix32(bmp.width, bmp.height).also { it.fillFrom(bmp) }
        layer.pix32 = made
        return made
    }

    fun needBitmap(l: Layer): Bitmap? {
        l.bitmap?.let { return it }
        val t = l.tiles ?: return null
        val bmp = t.materialize() ?: return null
        l.bitmap = bmp
        l.tiles = null
        t.delete()
        return bmp
    }


    /** 把某层换到磁盘；有蒙版 / 图层样式的层先留在内存（它们要整层算） */
    private fun evictLayer(l: Layer): Boolean {
        val bmp = l.bitmap ?: return false
        if (l === activeLayer) return false
        if (l.mask != null || l.strokeOn || l.shadowOn) return false
        val dir = tilesDir() ?: return false
        val f = java.io.File(dir, "layer_" + System.nanoTime() + "_" + l.pixelId + ".tiles")
        val t = com.reveal.editor.image.LayerTiles.write(bmp, f) ?: return false
        l.tiles = t
        l.bitmap = null
        l.styleCache = null
        l.topCache = null
        return true
    }

    /** 把当前没用的图层换出去，直到内存回到预算内 */
    /** 导出前把整屏合成缓存放掉：导出期间不需要它，能省一整张画布的显存/内存 */
    fun releaseComposite() {
        composite?.recycle()
        composite = null
        compositeDirty = true
        invalidate()
    }

    private var evicting = false

    /** 内存紧张时把一两个非活动层写到磁盘；写盘放后台线程，主线程只在下一帧把引用切过去，避免卡顿 */
    fun trimMemoryNow() {
        if (layers.size < 2 || evicting) return
        val budget = (Runtime.getRuntime().maxMemory() * 0.30).toLong()
        if (residentBytes() <= budget) return
        val cand = layers.firstOrNull {
            it !== activeLayer && it.bitmap != null && it.mask == null && !it.strokeOn && !it.shadowOn
        } ?: return
        val bmp = cand.bitmap ?: return
        val dir = tilesDir() ?: return
        val f = java.io.File(dir, "layer_" + System.nanoTime() + "_" + cand.pixelId + ".tiles")
        evicting = true
        Thread {
            val t = try {
                com.reveal.editor.image.LayerTiles.write(bmp, f)
            } catch (e: Throwable) {
                null
            }
            post {
                if (t != null && cand.bitmap === bmp) {
                    cand.tiles = t
                    cand.bitmap = null
        cand.styleCache = null
        cand.topCache = null
                    android.util.Log.i(
                        "RevealPerf",
                        "换出 1 层，常驻 " + (residentBytes() / 1048576) + " MB / 预算 " + (budget / 1048576) + " MB"
                    )
                } else {
                    f.delete()
                }
                evicting = false
            }
        }.start()
    }

    // ===== 视口合成缓存：图层只在真正变化/缩放时重合成一次，平移与画笔拖动直接复用 =====
    private var composite: Bitmap? = null
    private var compositeDirty = true
    private val cacheMatrix = Matrix()
    private var compositeDX = 0f
    private var compositeDY = 0f
    private var compositeDocW = 0
    private var compositeDocH = 0
    private var compositeSig = 0
    private var compositePixelStamp = 0

    /** 像素版本号：任何一处像素被改就 +1，用来判断「某个渐变层之后有没有被画过」 */
    private var pixelEpoch = 0
    private var compositeStamp = -1
    private val compositeDrawMatrix = Matrix()
    private var visibleDoc = RectF()

    /** 图层的「外形指纹」：数量、位图身份、可见性、透明度、混合、剪贴、蒙版身份。像素原地修改由 pixelStamp 负责 */
    private fun layersSig(): Int {
        var h = 17
        h = h * 31 + layers.size
        for (l in layers) {
            h = h * 31 + l.pixelId
            h = h * 31 + System.identityHashCode(l.mask)
            h = h * 31 + (if (l.visible) 1 else 0)
            h = h * 31 + l.alpha
            h = h * 31 + l.blendMode
            h = h * 31 + (if (l.clipped) 1 else 0)
            h = h * 31 + l.sectionType
            h = h * 31 + (l.maskVer and 0xFF)
            h = h * 31 + (if (l.alphaLock) 1 else 0)
            h = h * 31 + (l.adjust?.hashCode() ?: 0)
        }
        return h
    }

    /** 部分重绘的脏矩形（缓存坐标）：只重画改到的那一块 */
    private val partialDirtyCache = Rect()
    private var hasPartialDirty = false

    /** 文档坐标的脏矩形换算到缓存坐标并并进去；null 就只能整屏重合成 */
    private fun markDocDirty(r: Rect?) {
        if (r == null || r.isEmpty) {
            compositeDirty = true
            return
        }
        if (compositeDirty) return
        val f = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        cacheMatrix.mapRect(f)
        val ri = Rect(
            f.left.toInt() - 2, f.top.toInt() - 2,
            f.right.toInt() + 3, f.bottom.toInt() + 3
        )
        if (hasPartialDirty) partialDirtyCache.union(ri) else {
            partialDirtyCache.set(ri)
            hasPartialDirty = true
        }
    }

    private fun touchPixels(r: Rect? = null) {
        compositePixelStamp++
        pixelEpoch++
        if (!drawing) dropMips()
        markDocDirty(r)
    }

    /** 内容变了就把金字塔作废（画笔拖动期间先留着，松手后再重建） */
    private fun dropMips() {
        for (l in layers) {
            if (l.mip2 != null || l.mip4 != null) {
                l.mip2?.let { if (!it.isRecycled) it.recycle() }
                l.mip4?.let { if (!it.isRecycled) it.recycle() }
                l.mip2 = null
                l.mip4 = null
            }
        }
    }

    /** 取缩小过的显示用位图；缩得越小用得越小，省掉全尺寸采样 */
    private fun mipFor(layer: Layer, bmp: Bitmap, div: Int): Bitmap {
        if (div <= 1) return bmp
        val cached = if (div == 2) layer.mip2 else layer.mip4
        if (cached != null && !cached.isRecycled) return cached
        val w = (bmp.width / div).coerceAtLeast(1)
        val h = (bmp.height / div).coerceAtLeast(1)
        val made = runCatching { Bitmap.createScaledBitmap(bmp, w, h, true) }.getOrNull() ?: return bmp
        if (div == 2) layer.mip2 = made else layer.mip4 = made
        return made
    }

    /** 像素被原地修改后必须调一次，否则缓存会显示旧内容 */
    fun markPixelsChanged() {
        compositePixelStamp++
        compositeDirty = true
        invalidate()
    }

    /** 画笔拖动期间只有笔迹预览在变，图层位图没动 → 不允许把缓存标脏 */
    private fun brushPreviewOnly(): Boolean =
        drawing && !maskEditing && tool == Tool.BRUSH && strokeBuffer != null


    /**
     * 计算「把缓存贴回屏幕」的矩阵：缓存是按 cacheMatrix 渲染的，现在视图是 viewMatrix，
     * 贴图矩阵 = viewMatrix × cacheMatrix⁻¹。平移、缩放、旋转都能直接复用缓存（不重合成）；
     * 只有缩放倍率漂移出 0.75~1.34（缓存的采样密度明显不匹配了）才要求重建。
     */
    private fun updateDrawMatrix(): Boolean {
        val inv = Matrix()
        if (!cacheMatrix.invert(inv)) return false
        compositeDrawMatrix.setConcat(viewMatrix, inv)
        val v = FloatArray(9)
        compositeDrawMatrix.getValues(v)
        val sx = kotlin.math.hypot(v[0], v[3])
        val sy = kotlin.math.hypot(v[1], v[4])
        val drift = if (sx > sy) sx else sy
        // 手势进行中放宽窗口：缓存还盖得住就先复用，手势结束那一帧再按新倍率重建一次。
        // 原来固定 0.97~1.34，缩小方向每一步都掉出窗口 → 三十层要整体重合成一遍，缩放必然掉帧。
        if (zooming) return drift >= 0.42f && drift <= 2.4f
        // 放大方向复用（缓存里已有的像素够用）；缩小方向那块缓存没覆盖到新暴露的区域，直接重建
        return drift >= 0.97f && drift <= 1.34f
    }

    private fun ensureComposite(): Bitmap? {
        val bmp = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return null
        if (width <= 0 || height <= 0) return null
        var c = composite
        if (c == null || c.width != width || c.height != height) {
            c = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            composite = c
            compositeDirty = true
        }
        if (bmp.width != compositeDocW || bmp.height != compositeDocH) {
            compositeDocW = bmp.width
            compositeDocH = bmp.height
            compositeDirty = true
        }
        val sig = layersSig()
        if (sig != compositeSig) {
            compositeSig = sig
            compositeDirty = true
        }
        if (compositePixelStamp != compositeStamp) {
            compositeStamp = compositePixelStamp
            // 像素改了但没人报脏矩形 → 保险起见整屏重合成
            if (!hasPartialDirty && !compositeDirty) compositeDirty = true
        }
        // 有色阶调整层时局部重绘会绕开查表 → 只有「像素真的有过局部改动」才需要整屏重建。
        // 原来这里无条件置脏，结果平移/缩放时每帧都整屏 getPixels/setPixels 跑一遍 LUT，必掉帧。
        if (hasLevelsAdjust() && hasPartialDirty) compositeDirty = true
        if (!compositeDirty && hasPartialDirty) {
            val t1 = System.nanoTime()
            val pc = Canvas(c)
            pc.save()
            pc.clipRect(partialDirtyCache)
            pc.drawColor(0, PorterDuff.Mode.CLEAR)
            pc.concat(cacheMatrix)
            renderTarget = c
            drawLayers(pc)
            renderTarget = null
            pc.restore()
            val pw = partialDirtyCache.width()
            val ph = partialDirtyCache.height()
            hasPartialDirty = false
            partialDirtyCache.setEmpty()
            val ms1 = (System.nanoTime() - t1) / 1000000.0
            android.util.Log.i("RevealPerf", "composite patch " + String.format("%.1f", ms1) + " ms " + pw + "x" + ph)
            if (updateDrawMatrix()) return c
            compositeDirty = true
        }
        if (!compositeDirty && updateDrawMatrix()) return c
        val t0 = System.nanoTime()
        val cc = Canvas(c)
        cc.drawColor(0, PorterDuff.Mode.CLEAR)
        cc.concat(viewMatrix)
        renderTarget = c
        drawLayers(cc)
        renderTarget = null
        cacheMatrix.set(viewMatrix)
        compositeDrawMatrix.reset()
        compositeDX = 0f
        compositeDY = 0f
        compositeDirty = false
        hasPartialDirty = false
        partialDirtyCache.setEmpty()
        compositeStamp = compositePixelStamp
        trimMemoryNow()
        val ms = (System.nanoTime() - t0) / 1000000.0
        if (ms > 24.0) android.util.Log.i("RevealPerf", "composite rebuild " + String.format("%.1f", ms) + " ms @" + bmp.width + "x" + bmp.height)
        return c
    }

    /** 图层少、文档不大时直接每帧画图层：放大不会空白、缩小不会变形、缩放也不卡 */
    private fun canDrawDirect(): Boolean {
        val b = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return false
        val px = b.width.toLong() * b.height.toLong()
        val vis = layers.count { it.visible }
        return vis <= 8 && px <= 64000000L
    }

    override fun onDraw(canvas: Canvas) {
        val __t0 = System.nanoTime()
        // 自己裁一下：Compose 的 AndroidView 容器不一定替我们裁，图放大后会溢出盖住顶栏/底栏
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        // 工作区底色 + 纸张：纯白画布放在浅灰工作区上，带 1px 描边和投影，边界一眼能看清
        canvas.drawColor(WORKSPACE_GRAY)
        val paperW = docPixelWidth().toFloat()
        val paperH = docPixelHeight().toFloat()
        if (paperW > 0f && paperH > 0f) {
            val paper = RectF(0f, 0f, paperW, paperH)
            canvas.save()
            canvas.concat(viewMatrix)
            paperShadowPaint.setShadowLayer(
                (10f / viewScale()).coerceIn(4f, 40f),
                0f,
                (4f / viewScale()).coerceIn(1f, 20f),
                0x55000000,
            )
            canvas.drawRect(paper, paperShadowPaint)
            // 纸张底用棋盘格：这样图层的透明区看得出来，不会被误当成「白底」
            canvas.drawRect(paper, checkerPaint)
            canvas.drawRect(paper, paperStrokePaint)
            canvas.restore()
        }
        val bmp = activeLayer?.bitmap
        if (bmp != null) {
            // 先算「屏幕上能看到文档的哪一块」：完全在视野外的图层可以直接跳过不画
            val invView = Matrix()
            if (viewMatrix.invert(invView)) {
                val vr = RectF(0f, 0f, width.toFloat(), height.toFloat())
                invView.mapRect(vr)
                visibleDoc = vr
            } else {
                visibleDoc.setEmpty()
            }
            // 缩放中直接画图层：每层按视口裁掉看不见的部分，任意倍率都是矢量级清晰，
            // 也不用为每个缩放步重新合成一次（三十层重合成一次就是一百多毫秒，必然掉帧）。
            // 落笔的时候也走「直接画图层」：合成缓存是给静止画面用的，画到一半它还是旧图，
            // 用户看到的就是「笔迹过一会儿才冒出来、不跟手」
            val direct = canDrawDirect() || zooming || (drawing && !deferLive)
            if (!direct) {
                val cache = ensureComposite()
                if (cache != null) {
                    compositePaint.isFilterBitmap = !pixelZoom || !compositeDrawMatrix.isIdentity
                    canvas.drawBitmap(cache, compositeDrawMatrix, compositePaint)
                }
            } else {
                val __lt = System.nanoTime()
                canvas.save()
                canvas.concat(viewMatrix)
                drawLayers(canvas)
                canvas.restore()
                if (drawing) layerDrawMs += (System.nanoTime() - __lt) / 1_000_000L
            }
            // 大图落笔：预览位图是屏幕空间的，按「当前视图 × 建立时视图⁻¹」贴回去，
            // 缩放/平移中也不会错位；整笔都在这一张里，不存在「画一段丢一段」
            val ov0 = strokeOverlay
            if (deferLive && drawing && ov0 != null && !ov0.isRecycled && overlayView.invert(overlayInvView)) {
                overlayDrawMatrix.setConcat(viewMatrix, overlayInvView)
                previewPaint.isFilterBitmap = true
                previewPaint.alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt()
                resetBlend(previewPaint)
                canvas.drawBitmap(ov0, overlayDrawMatrix, previewPaint)
            }
            canvas.save()
            canvas.concat(viewMatrix)
            val buf = strokeBuffer
            val previewTools = tool == Tool.BRUSH || tool == Tool.CLONE || tool == Tool.HEAL
            // 现场模式下笔迹已经逐段合成进图层了，这里再贴一次就是叠两遍：
            // 手指按着的时候看着粗，一抬手预览消失、笔迹就「收缩」一下（用户报的）
            if (drawing && previewTools && buf != null && !maskEditing && !liveMode && !deferLive) {
                previewPaint.isFilterBitmap = !pixelZoom
                previewPaint.alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt()
                resetBlend(previewPaint)
                if (strokeBoundsValid && !strokeBounds.isEmpty) {
                // 只贴笔迹真正画过的那块：整幅 4000x4000 每帧采样会把帧率打穿
                    val sb = strokeBounds
                    val l = max(0, sb.left - 2)
                    val t = max(0, sb.top - 2)
                    val r = min(buf.width, sb.right + 2)
                    val b2 = min(buf.height, sb.bottom + 2)
                    if (r > l && b2 > t) {
                        val src = Rect(l, t, r, b2)
                        val dst = RectF(l.toFloat(), t.toFloat(), r.toFloat(), b2.toFloat())
                        canvas.drawBitmap(buf, src, dst, previewPaint)
                    }
                } else {
                    canvas.drawBitmap(buf, 0f, 0f, previewPaint)
                }
            }
            if (!zooming) drawSelection(canvas)
            drawTransformFrame(canvas)
            drawCloneMarker(canvas)
            drawGradientGuide(canvas)
            drawShapePreview(canvas)
            drawCropOverlay(canvas)
            drawMaskOverlay(canvas)
            drawGrid(canvas)
            drawGuides(canvas)
        drawPenPreview(canvas)
        drawVanishPreview(canvas)
        if (tool == Tool.WARP) drawWarpMesh(canvas)
            if (!zooming) {
                drawRuler(canvas)
                drawTouchIndicator(canvas)
                drawMouseCursor(canvas)
                drawRedEyeFrame(canvas)
            }
            canvas.restore()

            val fence = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            viewMatrix.mapRect(fence)
            canvas.drawRect(fence, fencePaint)
        }
        canvas.restore()
        val __ms = (System.nanoTime() - __t0) / 1000000.0
        if (drawing) {
            drawFrames++
            if (__ms >= 24.0) {
                drawSlowFrames++
                drawSlowMs += __ms.toLong()
            }
        }
        if (__ms > 16.0) android.util.Log.d("RevealPerf", "onDraw " + String.format("%.1f", __ms) + " ms drawing=" + drawing + " tool=" + tool)
    }

    /** 红眼工具：拖动时那个框要能看见（以前只在抬手才处理，过程中画面上什么都没有） */
    private var redEyeDragging = false

    private fun drawRedEyeFrame(canvas: Canvas) {
        if (tool != Tool.REDEYE || !redEyeDragging) return
        val r = RectF(redeyeStart.x, redeyeStart.y, lastImagePoint.x, lastImagePoint.y)
        r.sort()
        redEyeFramePaint.pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
        canvas.drawRect(r, redEyeFramePaint)
        // 框角画四个小方块，像 桌面端的裁剪/红眼框
        val h = 6f
        for (px in floatArrayOf(r.left, r.right)) {
            for (py in floatArrayOf(r.top, r.bottom)) {
                canvas.drawRect(px - h, py - h, px + h, py + h, redEyeFramePaint)
            }
        }
    }

    private val redEyeFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF7AB8FF.toInt()
    }

    /** 选区叠加显示：选区外压暗 + 蚂蚁线 + 正在拖的形状 */
    private fun drawSelection(canvas: Canvas) {
        // 活动层可能是调整层/组/空层（没有 bitmap），以前这里直接 return，
        // 于是「选中调整层时选区凭空消失」。改成取任一有像素层的尺寸做画布范围。
        val bmp = activeLayer?.bitmap
            ?: layers.firstNotNullOfOrNull { it.bitmap }
            ?: return
        val mask = selection
        // 两指缩放/平移期间一律不画覆盖层：压暗图 + 边缘图都是整幅尺寸，
        // 每帧一次 saveLayer 会把缩放拖成幻灯片（松手后再画回来）
        // 缩放中不再整块跳过：矢量轮廓（几笔 drawPath）和压暗（一张 drawBitmap）照画，
        // 只有「位图蚂蚁线」要每帧一次整幅 saveLayer，那个缩放时先不画（松手再画）。
        // 快速选择在双指落下时还原过选区，这时候旧图对不上，先不画。
        if (selStaleDuringZoom) return
        // 空选区（一个像素都没有）不要整屏压暗，否则看着像画面被关了灯
        val hasSel = mask != null && selHasPixels
        // 拖动过程中也照画压暗和矢量轮廓（都是缓存好的，几乎不花时间），
        // 只是位图蚂蚁线那一次整幅 saveLayer 先跳过 —— 以前整块不画，看着就是「选区一闪一闪」
        if (mask != null && hasSel) {
            val dst = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            val dp = selDimPath
            if (dp != null) {
                canvas.drawPath(dp, selDimFill)
            } else {
                val dim = selectionDim
                if (dim != null) canvas.drawBitmap(dim, null, dst, selectionDimPaint)
            }
            val outline = selOutline
            val edge = if (outline == null) selectionEdge else null
            if (outline != null) {
                // 几何选区：按屏幕像素画 1px 矢量蚂蚁线（黑线打底 + 白虚线流动），缩多小都清楚
                val av = FloatArray(9)
                viewMatrix.getValues(av)
                val zoom = av[Matrix.MSCALE_X].coerceAtLeast(0.01f)
                selAntsDark.strokeWidth = 1.5f / zoom
                selAntsLight.strokeWidth = 1.5f / zoom
                selInnerPaint.strokeWidth = 1f / zoom
                selAntsLight.pathEffect =
                    DashPathEffect(floatArrayOf(5f / zoom, 5f / zoom), antsPhase / zoom)
                // 裁剪到画布范围：拖到画布外时线不能画到工作区上（和 桌面端一致）
                canvas.save()
                canvas.clipRect(dst)
                // 内侧一根不闪的浅蓝细线：画面很暗的时候光靠黑白蚂蚁线看不清边界
                canvas.drawPath(outline, selInnerPaint)
                canvas.drawPath(outline, selAntsDark)
                canvas.drawPath(outline, selAntsLight)
                canvas.restore()
            }
            if (edge != null && !zooming && !selDragging) {
                // 先画白色边缘，再用棋盘 shader 以 SRC_IN 复涂：只有边缘那几像素被黑白色块顶掉
                // 棋盘按缩放反向缩放，让黑白块在屏幕上大小恒定（和 桌面端一样），
                // 否则缩小看是糊块、放大看是巨块
                val antsScale = run {
                    val av = FloatArray(9)
                    viewMatrix.getValues(av)
                    (1f / av[Matrix.MSCALE_X].coerceAtLeast(0.01f)).coerceIn(0.2f, 8f)
                }
                antsMatrix.setScale(antsScale, antsScale)
                antsMatrix.postTranslate(antsPhase, antsPhase)
                antsPaint.shader?.setLocalMatrix(antsMatrix)
                if (antsFillPaint.shader !== antsPaint.shader) antsFillPaint.shader = antsPaint.shader
                val lr = RectF(dst)
                val visible = !lr.intersect(visibleDoc)
                if (!visible) {
                    // 整条边都在视野外：跳过这次整幅 saveLayer
                } else {
                canvas.saveLayer(lr, null)
                canvas.drawBitmap(edge, null, dst, selectionEdgePaint)
                canvas.drawRect(dst, antsFillPaint)
                canvas.restore()
                }
            }
        }
        // 区域范围：高饱和描边（用户能一眼看到边界），选中项更亮
        run {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = run {
                    val m = FloatArray(9); viewMatrix.getValues(m)
                    2.5f / m[Matrix.MSCALE_X].coerceAtLeast(0.01f)
                }
                color = 0xFFFF2D9A.toInt()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0x22FF2D9A
            }
            if (showMask && regionsS.isNotEmpty()) {
                // Show Mask：把当前范围的蒙版整幅铺成黑白，用来确认它到底罩住了哪
                runCatching {
                    val m = regionMask() ?: return@runCatching
                    val bm = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
                    val px = IntArray(m.width * m.height)
                    m.getPixels(px, 0, m.width, 0, 0, m.width, m.height)
                    for (i in px.indices) {
                        val a = Color.alpha(px[i])
                        px[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
                    }
                    bm.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
                    canvas.drawBitmap(bm, null, RectF(0f, 0f, docPixelWidth().toFloat(), (docPixelWidth() * m.height / m.width).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            val list = regionsS.toList() + listOfNotNull(regionDrag)
            list.forEachIndexed { i, r ->
                val isActive = i == activeRegion
                stroke.color = if (isActive) 0xFFFF2D9A.toInt() else 0x8833D1FF.toInt()
                fill.color = if (isActive) 0x22FF2D9A else 0x1533D1FF
                if (r.kind == 2) {
                    val rc = RectF(r.cx - r.rx, r.cy - r.ry, r.cx + r.rx, r.cy + r.ry)
                    canvas.drawRect(rc, fill)
                    canvas.drawRect(rc, stroke)
                } else {
                    val rc = RectF(r.cx - r.rx, r.cy - r.ry, r.cx + r.rx, r.cy + r.ry)
                    canvas.drawOval(rc, fill)
                    canvas.drawOval(rc, stroke)
                }
                // 羽化带：画一条内缩虚线，直观看到「从哪开始渐隐」
                if (r.feather > 0) {
                    val f = r.feather.toFloat()
                    val inner = RectF(
                        r.cx - (r.rx - f).coerceAtLeast(1f),
                        r.cy - (r.ry - f).coerceAtLeast(1f),
                        r.cx + (r.rx - f).coerceAtLeast(1f),
                        r.cy + (r.ry - f).coerceAtLeast(1f),
                    )
                    val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = if (isActive) 0xAAFF2D9A.toInt() else 0x6633D1FF.toInt()
                        strokeWidth = stroke.strokeWidth * 0.8f
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
                    }
                    if (r.kind == 2) canvas.drawRect(inner, dash) else canvas.drawOval(inner, dash)
                }
            }
        }
        if (tool != Tool.SELECT) return
        if (selDragging) {
            when (selectMode) {
                SelectMode.RECT, SelectMode.ROW -> canvas.drawRect(normalizedSelRect(), selShapePaint)
                SelectMode.ELLIPSE -> canvas.drawOval(normalizedSelRect(), selShapePaint)
                SelectMode.COLUMN -> canvas.drawRect(normalizedSelRect(), selShapePaint)
                SelectMode.LASSO -> lassoPath?.let { canvas.drawPath(it, selShapePaint) }
                SelectMode.SUBJECT, SelectMode.RANGE -> Unit
                SelectMode.MAGNETIC -> {
                    val pts = magnetPoints
                    if (pts != null && pts.size > 1) {
                        val path = Path()
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                        drawPolyLine(canvas, path)
                    }
                }
                else -> Unit
            }
        }
        if (selectMode == SelectMode.POLY && polyPoints.isNotEmpty()) {
            if (polyPoints.size > 1) {
            val path = Path()
            path.moveTo(polyPoints[0].x, polyPoints[0].y)
            for (i in 1 until polyPoints.size) path.lineTo(polyPoints[i].x, polyPoints[i].y)
            if (selDragging) path.lineTo(selCurrent.x, selCurrent.y)
                drawPolyLine(canvas, path)
            }
            val closeable = polyPoints.size >= 3
            val first = polyPoints[0]
            polyPoints.forEachIndexed { i, p ->
                val isFirstAndCloseable = closeable && i == 0
                polyVertex(canvas, p.x, p.y, isFirstAndCloseable, i == polyPoints.size - 1)
            }
            if (closeable) {
                // 起点画成绿圈提示「点这里闭合」
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = 0xFF3DDC84.toInt()
                }
                canvas.drawCircle(first.x, first.y, 11f, ring)
            }
        }
    }

    /** 钢笔样式的路径线：深色垫底（浅图上也能看见）+ 亮蓝主线 */
    private fun drawPolyLine(canvas: Canvas, path: Path) {
        val under = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xB3000000.toInt()
        }
        val over = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = POLY_LINE
        }
        canvas.drawPath(path, under)
        canvas.drawPath(path, over)
    }

    /** 钢笔样式的锚点：绿色十字，当前点加亮圈 */
    private fun polyVertex(canvas: Canvas, x: Float, y: Float, first: Boolean, current: Boolean) {
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            color = if (first) POLY_FIRST else POLY_VERTEX
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            color = 0x8C000000.toInt()
        }
        val arm = if (first) 8f else 6.5f
        for (p in listOf(shadow, mark)) {
            canvas.drawLine(x - arm, y, x + arm, y, p)
            canvas.drawLine(x, y - arm, x, y + arm, p)
        }
        if (current) {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = POLY_LINE
            }
            canvas.drawCircle(x, y, 12f, ring)
        }
    }

    /** 按图层顺序画进 canvas（canvas 已经套上 viewMatrix），顺带处理剪贴蒙版 */
    /** 把图层的混合模式套到画笔上（API 29 以下只支持 PorterDuff 那几种，其它退化成正常） */
    private fun applyBlendMode(paint: Paint, mode: Int) {
        if (mode == BLEND_NORMAL) {
            paint.xfermode = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) paint.blendMode = BlendMode.SRC_OVER
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.xfermode = null
            paint.blendMode = blendOf(mode)
        } else {
            paint.xfermode = when (mode) {
                BLEND_MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                BLEND_SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                BLEND_OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                BLEND_DARKEN -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                BLEND_LIGHTEN -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                else -> null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun blendOf(mode: Int): BlendMode = when (mode) {
        BLEND_MULTIPLY -> BlendMode.MULTIPLY
        BLEND_SCREEN -> BlendMode.SCREEN
        BLEND_OVERLAY -> BlendMode.OVERLAY
        BLEND_SOFT_LIGHT -> BlendMode.SOFT_LIGHT
        BLEND_HARD_LIGHT -> BlendMode.HARD_LIGHT
        BLEND_DARKEN -> BlendMode.DARKEN
        BLEND_LIGHTEN -> BlendMode.LIGHTEN
        BLEND_COLOR_BURN -> BlendMode.COLOR_BURN
        BLEND_COLOR_DODGE -> BlendMode.COLOR_DODGE
        BLEND_DIFFERENCE -> BlendMode.DIFFERENCE
        BLEND_EXCLUSION -> BlendMode.EXCLUSION
        BLEND_HUE -> BlendMode.HUE
        BLEND_SATURATION -> BlendMode.SATURATION
        BLEND_COLOR -> BlendMode.COLOR
        BLEND_LUMINOSITY -> BlendMode.LUMINOSITY
        else -> BlendMode.SRC_OVER
    }

    private fun resetBlend(paint: Paint) {
        paint.xfermode = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) paint.blendMode = BlendMode.SRC_OVER
    }
    fun setLayerBlendMode(index: Int, mode: Int) {
        val layer = layers.getOrNull(index) ?: return
        layer.blendMode = mode
        onDocChanged?.invoke()
        invalidate()
    }

    /** 文字工具：把文字画到一张新图层上（居中于点击位置） */
    private fun renderText(w: Int, h: Int, text: String, sizePx: Float, color: Int, cx: Float, cy: Float, font: String = ""): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.textSize = sizePx
        paint.textAlign = Paint.Align.LEFT
        if (font.isNotEmpty()) {
            paint.typeface = BundledFonts.typeface(context, font)
                ?: runCatching { android.graphics.Typeface.create(font, android.graphics.Typeface.NORMAL) }.getOrNull()
        }
        val lines = text.split("\n")
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.15f
        val totalHeight = lineHeight * lines.size
        var y = cy - totalHeight / 2f - fm.ascent
        for (line in lines) {
            val lineWidth = paint.measureText(line)
            canvas.drawText(line, cx - lineWidth / 2f, y, paint)
            y += lineHeight
        }
        return bmp
    }

    /** 按排版参数渲染文字：逐字排、支持字距/对齐/缩放/上下标/变形 */
    private fun renderTextSpec(w: Int, h: Int, spec: TextSpec, cx: Float, cy: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = spec.color
        // 桌面端的上标/下标：字号 58.3%、基线偏移 33%（以前是 68% / 28%，偏大）
        val subScale = if (spec.superSub != 0) 0.583f else 1f
        paint.textSize = spec.size * subScale
        paint.textAlign = Paint.Align.LEFT
        val wantStyle = when {
            spec.bold && spec.italic -> android.graphics.Typeface.BOLD_ITALIC
            spec.bold -> android.graphics.Typeface.BOLD
            spec.italic -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
        // 先试随包内置的开源字体（key 形式），不是内置的就当系统家族名用
        val builtinTf = BundledFonts.typeface(context, spec.font)
        val tf = if (builtinTf != null) {
            runCatching { android.graphics.Typeface.create(builtinTf, wantStyle) }.getOrNull() ?: builtinTf
        } else {
            runCatching {
                android.graphics.Typeface.create(spec.font.ifEmpty { "sans-serif" }, wantStyle)
            }.getOrNull()
        }
        if (tf != null) {
            paint.typeface = tf
            // 字体里没有真正的斜体时，退化成 12° 合成倾斜（和 桌面端的仿斜体一致）
            if (spec.italic && !tf.isItalic) paint.textSkewX = 0.2126f
        }
        val raw = when (spec.caps) {
            1 -> spec.content.uppercase()
            2 -> spec.content.lowercase()
            3 -> {
                if (spec.content.length > 1) {
                    spec.content.substring(0, 1).uppercase() + spec.content.substring(1).lowercase()
                } else {
                    spec.content
                }
            }
            else -> spec.content
        }
        val lines = raw.split("\n")
        val trackingPx = spec.size * spec.tracking / 1000f
        // 路径文字：沿路径面板里的那条路径排字，字跟着切线转
        val onPath = if (spec.pathText) pathList.getOrNull(spec.pathIndex)?.second else null
        if (onPath != null) {
            val pm = android.graphics.PathMeasure(onPath, false)
            val plen = pm.length
            if (plen > 1f) {
                val pos = FloatArray(2)
                val tan = FloatArray(2)
                var dist = 0f
                val shift = spec.size * spec.shift / 100f
                for (line in lines) {
                    var lineLen = 0f
                    for (ch in line) lineLen += paint.measureText(ch.toString()) + trackingPx
                    // 对齐：左＝从路径起点，中＝居中，右＝靠终点
                    dist = when (spec.align) {
                        0 -> 0f
                        2 -> (plen - lineLen).coerceAtLeast(0f)
                        else -> ((plen - lineLen) / 2f).coerceAtLeast(0f)
                    }
                    for (ch in line) {
                        val cs = ch.toString()
                        val cw = paint.measureText(cs)
                        val d = dist + cw / 2f
                        if (d > plen) break
                        pm.getPosTan(d, pos, tan)
                        val ang = Math.toDegrees(Math.atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
                        canvas.save()
                        canvas.translate(pos[0], pos[1])
                        canvas.rotate(ang)
                        canvas.drawText(cs, -cw / 2f, shift, paint)
                        canvas.restore()
                        dist += cw + trackingPx
                    }
                }
                return bmp
            }
        }
        fun lineWidth(s: String): Float {
            if (s.isEmpty()) return 0f
            var t = 0f
            for (ch in s) t += paint.measureText(ch.toString()) + trackingPx
            return t - trackingPx
        }
        val maxW = lines.maxOfOrNull { lineWidth(it) } ?: 0f
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * (1.15f + spec.leading / 100f)
        val totalH = lineH * lines.size
        val sx = spec.scaleX.coerceIn(10f, 400f) / 100f
        val sy = spec.scaleY.coerceIn(10f, 400f) / 100f
        val baseShift = spec.size * spec.shift / 100f +
            (if (spec.superSub == 1) -spec.size * 0.33f else if (spec.superSub == 2) spec.size * 0.33f else 0f)
        var y = cy - totalH / 2f - fm.ascent
        if (spec.vertical) {
            val cellH = spec.size * (1f + spec.leading / 100f)
            val cols = lines.size
            var colX = cx - (cols - 1) * cellH / 2f
            for (line in lines) {
                var vy = cy - line.length * cellH / 2f + spec.size * 0.8f
                for (ch in line) {
                    val cs = ch.toString()
                    val cw = paint.measureText(cs)
                    canvas.drawText(cs, colX - cw / 2f, vy, paint)
                    vy += cellH
                }
                colX += cellH
            }
            return bmp
        }
        for (line in lines) {
            val lw = lineWidth(line)
            val n = line.length
            val gap = if (spec.align == 3 && n > 1) trackingPx + (maxW - lw) / (n - 1) else trackingPx
            var x = when (spec.align) {
                0 -> cx - maxW / 2f
                2 -> cx + maxW / 2f - lw
                else -> cx - lw / 2f
            }
            var i = 0
            for (ch in line) {
                val cs = ch.toString()
                val cw = paint.measureText(cs)
                val t = if (n <= 1) 0.5f else i.toFloat() / (n - 1)
                val dy = warpDy(spec, t)
                canvas.save()
                canvas.scale(sx, sy, x + cw / 2f, y)
                canvas.drawText(cs, x, y + dy + baseShift, paint)
                canvas.restore()
                val th = spec.size * 0.05f
                if (spec.underline) {
                    canvas.drawRect(x, y + dy + baseShift + fm.descent * 0.3f, x + cw, y + dy + baseShift + fm.descent * 0.3f + th, paint)
                }
                if (spec.strike) {
                    canvas.drawRect(x, y + dy + baseShift - spec.size * 0.3f, x + cw, y + dy + baseShift - spec.size * 0.3f + th, paint)
                }
                x += cw + gap
                i++
            }
            y += lineH
        }
        return bmp
    }

    /** 文字变形的起伏量（正数往下） */
    private fun warpDy(spec: TextSpec, t: Float): Float {
        if (spec.warp <= 0) return 0f
        val s = spec.size * spec.warpBend / 50f
        val pi = Math.PI
        return when (spec.warp) {
            1, 2 -> (s * 0.6f * (4f * (t - 0.5f) * (t - 0.5f) - 1f))
            3 -> (-s * 0.6f * (4f * (t - 0.5f) * (t - 0.5f) - 1f))
            4 -> -s * 0.5f * Math.sin(pi * t).toFloat()
            5 -> s * 0.5f * Math.sin(pi * t).toFloat()
            6 -> s * 0.45f * Math.sin(pi * t * 2.0).toFloat()
            7 -> s * 0.32f * Math.sin(pi * t * 3.0).toFloat()
            8 -> s * 0.4f * Math.sin(pi * t * 1.5).toFloat()
            9 -> s * 0.28f * Math.sin(pi * t * 4.0).toFloat()
            10 -> s * 0.22f * Math.sin(pi * t * 6.0).toFloat()
            11 -> -s * 0.6f * (1f - (2f * t - 1f) * (2f * t - 1f))
            12 -> s * 0.6f * (1f - (2f * t - 1f) * (2f * t - 1f))
            else -> 0f
        }
    }

    /** 读出某层的文字排版参数，用来重新编辑 */
    fun textSpecOf(index: Int): TextSpec? {
        val l = layers.getOrNull(index) ?: return null
        val tc = l.textContent ?: return null
        return TextSpec(
            content = tc, size = l.textSize, color = l.textColor, font = l.textFont,
            bold = l.textBold, italic = l.textItalic, underline = l.textUnderline, strike = l.textStrike,
            tracking = l.textTracking, leading = l.textLeading, align = l.textAlign,
            scaleX = l.textScaleX, scaleY = l.textScaleY, shift = l.textShift,
            caps = l.textCaps, superSub = l.textSuperSub, warp = l.textWarp, warpBend = l.textWarpBend,
            vertical = l.textVertical, pathText = l.textOnPath, pathIndex = l.textPathIndex,
        )
    }

    // 最近一次做的文字（锚点 + 排版参数），供「文字转选区」用
    private var lastTextSpec: TextSpec? = null
    private var lastTextX: Float = 0f
    private var lastTextY: Float = 0f

    /**
     * 把最近一次做的文字转成选区（桌面端的「文字蒙版」）。
     * 没有做过文字时返回 false。
     */
    fun textMaskSelectLast(): Boolean {
        val spec = lastTextSpec ?: return false
        if (spec.content.isBlank()) return false
        textMaskSelect(spec, lastTextX, lastTextY)
        return true
    }

    /** 文字蒙版：用这段文字的轮廓建立选区，不生成新图层 */
    fun textMaskSelect(spec: TextSpec, cx: Float, cy: Float) {
        val bmp = activeLayer?.bitmap ?: return
        val text = renderTextSpec(bmp.width, bmp.height, spec, cx, cy)
        val selBefore = selection?.let { packAlpha(it) }
        if (selection == null) selection = Selection.blank(bmp.width, bmp.height)
        selection?.let { Selection.apply(it, text, SelectOp.NEW) }
        text.recycle()
        pushSelectionStroke(selBefore, bmp.width, bmp.height)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 按完整排版参数新建文字层 */
    fun addTextLayerSpec(spec: TextSpec, cx: Float, cy: Float): Boolean {
        if (spec.content.isBlank()) return false
        val active = layers.getOrNull(activeIndex) ?: return false
        val w = active.bitmap?.width ?: return false
        val h = active.bitmap?.height ?: return false
        val bmp = renderTextSpec(w, h, spec, cx, cy)
        lastTextSpec = spec
        lastTextX = cx
        lastTextY = cy
        val created = Layer(bmp, "文字")
        applyTextSpec(created, spec, cx, cy)
        val at = (activeIndex + 1).coerceIn(0, layers.size)
        layers.add(at, created)
        activeIndex = at
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        invalidate()
        return true
    }

    /** 按完整排版参数原地重画文字层 */
    /**
     * 「编辑文字」调参数时的实时预览：按新参数渲染一份位图交给 setPreview 顶替图层绘制，
     * 不写进图层、不动撤销栈（确认时才走 updateTextLayerSpec）。
     */
    fun renderTextSpecPreview(index: Int, spec: TextSpec): Bitmap? {
        val layer = layers.getOrNull(index) ?: return null
        if (spec.content.isBlank()) return null
        val old = layer.bitmap ?: return null
        return runCatching {
            renderTextSpec(old.width, old.height, spec, layer.textX, layer.textY)
        }.getOrNull()
    }

    fun updateTextLayerSpec(index: Int, spec: TextSpec): Boolean {
        val layer = layers.getOrNull(index) ?: return false
        val old = needBitmap(layer) ?: return false
        if (spec.content.isBlank()) return false
        lastTextSpec = spec
        lastTextX = layer.textX
        lastTextY = layer.textY
        // 改文字＝重画这一层。以前这里 undoStack.clear()，等于改一次文字就把整个历史扔掉，
        // 于是「文字用变换挪了位置再想撤回」根本无路可退。现在当成普通一步记进撤销栈。
        val rendered = renderTextSpec(old.width, old.height, spec, layer.textX, layer.textY)
        replaceLayerAt(index, rendered, "编辑文字")
        applyTextSpec(layer, spec, layer.textX, layer.textY)
        layer.styleVer++
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        return true
    }

    private fun applyTextSpec(layer: Layer, spec: TextSpec, cx: Float, cy: Float) {
        layer.textContent = spec.content
        layer.textSize = spec.size
        layer.textColor = spec.color
        layer.textFont = spec.font
        layer.textX = cx
        layer.textY = cy
        layer.textBold = spec.bold
        layer.textItalic = spec.italic
        layer.textUnderline = spec.underline
        layer.textStrike = spec.strike
        layer.textTracking = spec.tracking
        layer.textLeading = spec.leading
        layer.textAlign = spec.align
        layer.textScaleX = spec.scaleX
        layer.textScaleY = spec.scaleY
        layer.textShift = spec.shift
        layer.textCaps = spec.caps
        layer.textSuperSub = spec.superSub
        layer.textWarp = spec.warp
        layer.textWarpBend = spec.warpBend
        layer.textVertical = spec.vertical
        layer.textOnPath = spec.pathText
        layer.textPathIndex = spec.pathIndex
    }

    /** 文字层：把文字画到一张新图层上（以点击位置为中心） */
    fun addTextLayer(text: String, sizePx: Float, color: Int, cx: Float, cy: Float, font: String = ""): Boolean {
        if (text.isBlank()) return false
        val active = layers.getOrNull(activeIndex) ?: return false
        val w = active.bitmap?.width ?: return false
        val h = active.bitmap?.height ?: return false
        val bmp = renderText(w, h, text, sizePx, color, cx, cy, font)
        val created = Layer(bmp, "文字")
        created.textContent = text
        created.textSize = sizePx
        created.textColor = color
        created.textX = cx
        created.textY = cy
        created.textFont = font
        val at = (activeIndex + 1).coerceIn(0, layers.size)
        layers.add(at, created)
        activeIndex = at
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        invalidate()
        return true
    }
    /** 文字工具：把文字画到一张新图层上（以点击位置为中心） */

    /** 改文字层的内容/字号（颜色不变），原地重画 */
    fun updateTextLayer(index: Int, text: String, sizePx: Float, color: Int? = null, font: String? = null): Boolean {
        val layer = layers.getOrNull(index) ?: return false
        val old = needBitmap(layer) ?: return false
        if (text.isBlank()) return false
        val useColor = color ?: layer.textColor
        val useFont = font ?: layer.textFont
        val bmp = renderText(old.width, old.height, text, sizePx, useColor, layer.textX, layer.textY, useFont)
        replaceLayerAt(index, bmp, "编辑文字")
        layer.textContent = text
        layer.textSize = sizePx
        layer.textColor = useColor
        layer.textFont = useFont
        layer.styleVer++
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        return true
    }

    /** 内容外接框（跳过全透明像素），算不出来返回 null */
    private fun contentBounds(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 8) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < l || b < t) return null
        return Rect(l, t, r + 1, b + 1)
    }

    /** 把这一层的内容对齐到画布：0 左 1 水平居中 2 右 3 上 4 垂直居中 5 下 */
    /** 分布：把选中的可见图层沿水平/垂直方向等间距排开（equalSpacing=true 时按边缘等距） */
    fun distributeLayers(horizontal: Boolean, equalSpacing: Boolean): Boolean {
        val items = ArrayList<Triple<Int, Layer, RectF>>()
        for (i in layers.indices) {
            val l = layers[i]
            if (!l.visible) continue
            val b = l.bitmap ?: continue
            val box = contentBounds(b)?.let { RectF(it) } ?: continue
            items.add(Triple(i, l, box))
        }
        if (items.size < 3) return false
        items.sortBy { if (horizontal) it.third.centerX() else it.third.centerY() }
        if (equalSpacing) {
            val first = items.first().third
            val last = items.last().third
            val span = if (horizontal) last.right - first.left else last.bottom - first.top
            var used = 0f
            for (it in items) used += if (horizontal) it.third.width() else it.third.height()
            val gap = (span - used) / (items.size - 1)
            var cursor = if (horizontal) first.left else first.top
            for ((idx, layer, box) in items) {
                val cur = if (horizontal) box.left else box.top
                val d = cursor - cur
                if (d != 0f) shiftLayer(layer, if (horizontal) d else 0f, if (horizontal) 0f else d)
                cursor += (if (horizontal) box.width() else box.height()) + gap
                if (idx < 0) break
            }
        } else {
            val first = items.first().third
            val last = items.last().third
            val a = if (horizontal) first.centerX() else first.centerY()
            val b = if (horizontal) last.centerX() else last.centerY()
            val gap = (b - a) / (items.size - 1)
            items.forEachIndexed { i, (_, layer, box) ->
                val target = a + gap * i
                val cur = if (horizontal) box.centerX() else box.centerY()
                val d = target - cur
                if (d != 0f) shiftLayer(layer, if (horizontal) d else 0f, if (horizontal) 0f else d)
            }
        }
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    private fun shiftLayer(layer: Layer, dx: Float, dy: Float) {
        val bmp = layer.bitmap ?: return
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, dx, dy, Paint(Paint.FILTER_BITMAP_FLAG))
        layer.bitmap = out
        layer.styleVer++
        layer.styleCache = null
        layer.topCache = null
    }

    fun alignLayer(index: Int, mode: Int): Boolean {
        val layer = layers.getOrNull(index) ?: return false
        val bmp = needBitmap(layer) ?: return false
        val box = contentBounds(bmp) ?: return false
        val w = bmp.width
        val h = bmp.height
        val dx = when (mode) {
            0 -> -box.left
            1 -> ((w - box.width()) / 2 - box.left)
            2 -> (w - box.right)
            else -> 0
        }
        val dy = when (mode) {
            3 -> -box.top
            4 -> ((h - box.height()) / 2 - box.top)
            5 -> (h - box.bottom)
            else -> 0
        }
        if (dx == 0 && dy == 0) return false
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, dx.toFloat(), dy.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        layer.bitmap = out
        layer.styleVer++
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        return true
    }

    /** 蒙版上的油漆桶：白色刷子 = 整片露出，暗色刷子 = 整片遮住 */
    private fun ensureMaskSized(layer: Layer): Bitmap? {
        val m = layer.mask ?: return null
        val b = needBitmap(layer) ?: return null
        if (m.width == b.width && m.height == b.height) return m
        val nm = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        nm.eraseColor(0xFFFFFFFF.toInt())
        Canvas(nm).drawBitmap(m, 0f, 0f, null)
        layer.mask = nm
        return nm
    }

    private fun bucketFillMask(mask: Bitmap, ix: Int, iy: Int, tolerance: Float): Boolean {
        val layer = activeLayer ?: return false
        val w = mask.width
        val h = mask.height
        if (ix < 0 || iy < 0 || ix >= w || iy >= h) return false
        val flood = Flood.mask(mask, ix, iy, tolerance)
        val reveal = (0.299f * Color.red(brushColor) + 0.587f * Color.green(brushColor) + 0.114f * Color.blue(brushColor)) >= 128f
        val target = if (reveal) Color.WHITE else 0x00000000
        val sel = selection
        val before = IntArray(w * h)
        mask.getPixels(before, 0, w, 0, 0, w, h)
        val row = IntArray(w)
        val selRow = IntArray(w)
        var changed = false
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            if (sel != null) sel.getPixels(selRow, 0, w, 0, y, w, 1)
            var rowChanged = false
            for (x in 0 until w) {
                if (!flood[y * w + x]) continue
                if (sel != null && Color.alpha(selRow[x]) <= 8) continue
                row[x] = target
                rowChanged = true
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (rowChanged) {
                mask.setPixels(row, 0, w, 0, y, w, 1)
                changed = true
            }
        }
        if (!changed) return false
        layer.styleVer++
        val rect = Rect(minX, minY, maxX + 1, maxY + 1)
        val rw = rect.width()
        val rh = rect.height()
        if (rw.toLong() * rh <= 6_000_000L) {
            val bp = IntArray(rw * rh)
            val ap = IntArray(rw * rh)
            for (y in rect.top until rect.bottom) {
                System.arraycopy(before, y * w + rect.left, bp, (y - rect.top) * rw, rw)
                mask.getPixels(row, 0, w, 0, y, w, 1)
                System.arraycopy(row, rect.left, ap, (y - rect.top) * rw, rw)
            }
            val st = Stroke().also { it.label = "蒙版编辑" }
            st.patches.add(Patch(layer, rect.left, rect.top, rw, rh, bp, ap, true))
            pushUndo(st)
        } else {
            undoStack.clear()
            redoStack.clear()
            invalidate()
        }
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 视图网格（屏幕坐标，40dp 一格） */
    private val showGridS = androidx.compose.runtime.mutableStateOf(false)
    var showGrid: Boolean
        get() = showGridS.value
        set(v) { showGridS.value = v }

    /** 长边分成几格：格数越多格越密 */
    private val gridCountS = androidx.compose.runtime.mutableIntStateOf(12)
    var gridCount: Int
        get() = gridCountS.intValue
        set(value) {
            gridCountS.intValue = value.coerceIn(2, 64)
            invalidate()
        }
    /** 显示标尺（文档顶边 + 左边刻度） */
    private val showRulerS = androidx.compose.runtime.mutableStateOf(false)
    var showRuler: Boolean
        get() = showRulerS.value
        set(value) {
            showRulerS.value = value
            invalidate()
        }

    /** 网格主格间距（文档像素）；0 = 按格数自动计算 */
    private val gridStepPxS = androidx.compose.runtime.mutableIntStateOf(0)
    var gridStepPx: Int
        get() = gridStepPxS.intValue
        set(value) {
            gridStepPxS.intValue = value.coerceIn(0, 2000)
            invalidate()
        }

    /** 网格细分：主格内再分几道浅线 */
    var gridSubdiv: Int = 1
        set(value) {
            field = value.coerceIn(1, 8)
            invalidate()
        }

    /** 显示触摸位置（圆环 + 中心点），设置里开关 */
    var showTouchIndicator: Boolean = false
        set(value) {
            field = value
            if (!value) touchIndicatorOn = false
            invalidate()
        }
    private var touchIndicatorX = 0f
    private var touchIndicatorY = 0f
    private var touchIndicatorOn = false

    private fun drawTouchIndicator(canvas: Canvas) {
        if (!showTouchIndicator || !touchIndicatorOn) return
        val radius = (brushSize / 2f).coerceAtLeast(4f)
        touchRingPaint.strokeWidth = 1.6f / viewScale()
        canvas.drawCircle(touchIndicatorX, touchIndicatorY, radius, touchRingPaint)
        canvas.drawCircle(touchIndicatorX, touchIndicatorY, 2.2f / viewScale(), touchRingCorePaint)
    }

    private fun updateTouchIndicator(event: MotionEvent) {
        if (!showTouchIndicator) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val p = toImage(event.x, event.y)
                touchIndicatorX = p.x
                touchIndicatorY = p.y
                touchIndicatorOn = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 1) touchIndicatorOn = false
                // 只有「缩放复用」期间才需要重建成原分辨率；纯平移/画画时矩阵是单位阵，不必整屏重算
                if (!compositeDrawMatrix.isIdentity) compositeDirty = true
                invalidate()
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (!showGrid) return
        val base = layers.firstOrNull()?.bitmap ?: return
        val dw = base.width.toFloat()
        val dh = base.height.toFloat()
        // 屏幕上看恒定 40dp 一格：换算到文档坐标要除以当前缩放，线宽同样反向补偿
        val s = viewScale().coerceAtLeast(0.0001f)
        // 网格按图片走（文档坐标）：放大图片网格一起放大，缩小时一起缩小
        val step = if (gridStepPx > 0) gridStepPx.toFloat() else (max(dw, dh) / gridCount.toFloat()).coerceIn(1f, 2048f)
        gridPaint.strokeWidth = 1f / s
        var x = 0f
        while (x <= dw) {
            canvas.drawLine(x, 0f, x, dh, gridPaint)
            x += step
        }
        var y = 0f
        while (y <= dh) {
            canvas.drawLine(0f, y, dw, y, gridPaint)
            y += step
        }
        // 细分线：主格中间再切几刀，用更暗的颜色，避免抢画面
        if (gridSubdiv > 1) {
            val sub = step / gridSubdiv
            if (sub >= 2f) {
                val oldColor = gridPaint.color
                gridPaint.color = (oldColor and 0x00FFFFFF) or 0x33000000
                var sx = sub
                while (sx < dw) {
                    canvas.drawLine(sx, 0f, sx, dh, gridPaint)
                    sx += sub
                }
                var sy = sub
                while (sy < dh) {
                    canvas.drawLine(0f, sy, dw, sy, gridPaint)
                    sy += sub
                }
                gridPaint.color = oldColor
            }
        }
        drawRuler(canvas)
    }

    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D000000.toInt()
        textSize = 10f
    }

    /** 标尺：文档顶边和左边的刻度，间距按缩放自动选 10/20/50/100/200/500 档 */
    private fun drawRuler(canvas: Canvas) {
        if (!showRuler) return
        val bmp = activeLayer?.bitmap ?: layers.firstOrNull()?.bitmap ?: return
        val dw = bmp.width.toFloat()
        val dh = bmp.height.toFloat()
        val s = viewScale().coerceAtLeast(0.0001f)
        val band = 16f / s
        val steps = floatArrayOf(10f, 20f, 50f, 100f, 200f, 500f, 1000f)
        var step = steps.last()
        for (cand in steps) {
            if (cand * s >= 44f) { step = cand; break }
        }
        rulerPaint.strokeWidth = 1f / s
        rulerTextPaint.textSize = 9f / s
        canvas.drawRect(0f, 0f, dw, band, rulerBgPaint)
        canvas.drawRect(0f, 0f, band, dh, rulerBgPaint)
        var x = 0f
        while (x <= dw) {
            canvas.drawLine(x, 0f, x, band, rulerPaint)
            canvas.drawText(x.toInt().toString(), x + 2f / s, band - 3f / s, rulerTextPaint)
            x += step
        }
        var y = 0f
        while (y <= dh) {
            canvas.drawLine(0f, y, band, y, rulerPaint)
            canvas.save()
            canvas.rotate(-90f, band - 2f / s, y + 2f / s)
            canvas.drawText(y.toInt().toString(), band - 2f / s, y + 2f / s, rulerTextPaint)
            canvas.restore()
            y += step
        }
    }

    private val rulerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.FILL
    }

    /** 蒙版编辑时把「被遮住」的地方标成红色，这样黑笔看上去是遮住而不是擦掉 */
    private var maskOverlayCache: Bitmap? = null
    private var maskOverlayFor: Bitmap? = null
    private var maskOverlayVer: Int = -1

    /** 被遮住（蒙版 alpha 低）的地方标红：直接生成一张红色叠层，1/4 分辨率够看且不吃内存 */
    private fun buildMaskOverlay(mask: Bitmap): Bitmap? {
        val sw = (mask.width / 4).coerceAtLeast(1)
        val sh = (mask.height / 4).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(mask, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val red = 0x99FF4D6A.toInt()
        for (i in px.indices) {
            px[i] = if (Color.alpha(px[i]) < 200) red else 0
        }
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== mask) small.recycle()
        return out
    }

    private fun drawMaskOverlay(canvas: Canvas) {
        if (!maskEditing) return
        val layer = activeLayer ?: return
        val m = layer.mask ?: return
        val bmp = needBitmap(layer) ?: return
        if (maskOverlayCache == null || maskOverlayFor !== m || maskOverlayVer != layer.maskVer) {
            maskOverlayCache?.recycle()
            maskOverlayCache = buildMaskOverlay(m)
            maskOverlayFor = m
            maskOverlayVer = layer.maskVer
        }
        val cache = maskOverlayCache ?: return
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        canvas.drawBitmap(cache, null, r, maskOverlayPaint)
    }
    private val maskRedPaint = Paint().apply { color = 0x99FF4D6A.toInt() }
    private val maskOverlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 展示被网格用的线宽等；见 drawGrid */
    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC2F80ED.toInt()
    }
    private val touchRingCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE62F80ED.toInt()
    }
    private val gridPaint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x14000000
            strokeWidth = 1f
        }
    }

    /** 裁掉四周全透明的边（所有图层取并集） */
    fun trimTransparent(): Boolean {
        if (layers.isEmpty()) return false
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var b = -1
        for (layer in layers) {
            val bmp = needBitmap(layer) ?: continue
            val box = contentBounds(bmp) ?: continue
            if (box.left < l) l = box.left
            if (box.top < t) t = box.top
            if (box.right > r) r = box.right
            if (box.bottom > b) b = box.bottom
        }
        if (r < l || b < t) return false
        val w = r - l
        val h = b - t
        val docW = layers.first().bitmap?.width ?: return false
        val docH = layers.first().bitmap?.height ?: return false
        if (l <= 0 && t <= 0 && r >= docW && b >= docH) return false
        for (layer in layers) {
            val bmp = needBitmap(layer) ?: continue
            val nl = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(nl).drawBitmap(bmp, -l.toFloat(), -t.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            layer.bitmap = nl
            val mk = layer.mask
            if (mk != null && mk.width == docW && mk.height == docH) {
                val nm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(nm).drawBitmap(mk, -l.toFloat(), -t.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                layer.mask = nm
            }
        }
        afterGeometryChange()
        return true
    }

    fun setLayerName(index: Int, name: String) {
        val layer = layers.getOrNull(index) ?: return
        layer.name = name.ifBlank { "图层" }
        onDocChanged?.invoke()
    }

    /** 当前层有没有蒙版 */
    fun hasMask(index: Int = activeIndex): Boolean = layers.getOrNull(index)?.mask != null

    /** 给当前层加蒙版（一开始全白 = 整层都显示） */
    fun addMask(): Boolean {
        val layer = activeLayer ?: return false
        val bmp = needBitmap(layer) ?: return false
        if (layer.mask != null) return false
        val m = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        m.eraseColor(0xFFFFFFFF.toInt())
        layer.mask = m
        maskEditing = true
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 删掉当前层的蒙版（内容恢复成全显示） */
    fun deleteMask(): Boolean {
        val layer = activeLayer ?: return false
        if (layer.mask == null) return false
        layer.mask = null
        maskEditing = false
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 应用蒙版：把被遮住的地方真的挖掉，蒙版层取消 */
    /** 滤镜/变换/裁剪这类改动不是画笔像素混出来的，旧 16 位缓冲立刻作废 */
    private fun dropPix16(layer: Layer?) {
        layer?.pix16 = null
        layer?.pix32 = null
    }

    /**
     * 8 位像素改完之后，把 16 位缓冲跟着改一遍，别直接扔。
     *
     * 判断办法：如果这次改动对每个输入颜色都只有一个固定输出（色阶、曲线、亮度对比度、反相、
     * 去色、色彩平衡、阈值都是这样），就把它当成 256 项查表，在 16 位空间里插值重映射，
     * 精度就保住了；如果同一个输入颜色出现了不同输出（高斯模糊、马赛克会把邻域混进来），
     * 说明 16 位信息确实没算出来，才把缓冲作废（导出时退回 8 位）。
     */
    /**
     * 没法用查表反推时（模糊、马赛克、alpha 变过）的退路：
     * 只把「8 位里真的变了」的像素按结果同步，没碰到的像素仍然保留 16 位数据。
     */
    private fun syncPix16ByDiff(p: com.reveal.editor.image.Pix16, before: IntArray, after: IntArray, w: Int, h: Int) {
        runCatching { p.applyFrom8Diff(0, 0, w, h, before, after) }
        runCatching {
            com.reveal.editor.data.Diag.append(context, "p16sync", "dims=" + w + "x" + h + " mode=diff")
        }
    }

    private fun syncPix16ByLut(layer: Layer, before: IntArray, after: IntArray, w: Int, h: Int) {
        // 缓冲还没建过：从原始 16 位通道补一份（拿的是这次改动之前的数据），再往下走；
        // 没有原始通道就没法还原改动前的精度，交给导出时的 8 位兜底。
        val p = layer.pix16 ?: run {
            if (docDepth != 16) return
            val bmp = layer.bitmap ?: return
            com.reveal.editor.image.Psd.pix16FromRawPix(layer.rawPix, bmp)?.also { layer.pix16 = it } ?: return
        }
        if (p.width != w || p.height != h) {
            layer.pix16 = null
            return
        }
        if (before.isEmpty() || before.size != after.size) return
        val lr = IntArray(256) { -1 }
        val lg = IntArray(256) { -1 }
        val lb = IntArray(256) { -1 }
        for (i in before.indices) {
            val c0 = before[i]
            val a0 = c0 ushr 24
            if (a0 == 0) continue
            val c1 = after[i]
            if ((c1 ushr 24) != a0) {
                syncPix16ByDiff(p, before, after, w, h)
                return
            }
            if (!putLut(lr, c0 ushr 16 and 0xFF, c1 ushr 16 and 0xFF) ||
                !putLut(lg, c0 ushr 8 and 0xFF, c1 ushr 8 and 0xFF) ||
                !putLut(lb, c0 and 0xFF, c1 and 0xFF)
            ) {
                syncPix16ByDiff(p, before, after, w, h)
                return
            }
        }
        fillLut(lr)
        fillLut(lg)
        fillLut(lb)
        p.applyLut8(lr, lg, lb)
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "p16sync", "dims=" + w + "x" + h + " mode=lut"
            )
        }
    }

    /** 记一项查表；同一个输入出现两个输出就返回 false（说明不是逐像素操作） */
    private fun putLut(lut: IntArray, v: Int, out: Int): Boolean {
        val i = v.coerceIn(0, 255)
        val o = out.coerceIn(0, 255)
        if (lut[i] < 0) {
            lut[i] = o
            return true
        }
        return lut[i] == o
    }

    /** 表里没被覆盖到的值就近补齐，保证整张表连续单调，不会出现空洞 */
    private fun fillLut(lut: IntArray) {
        var last = -1
        for (i in 0 until 256) {
            if (lut[i] >= 0) last = lut[i] else if (last >= 0) lut[i] = last
        }
        var next = -1
        for (i in 255 downTo 0) {
            if (lut[i] >= 0) next = lut[i] else if (next >= 0) lut[i] = next
        }
        for (i in 0 until 256) if (lut[i] < 0) lut[i] = i
    }

    fun applyMask(): Boolean {
        // 应用蒙版只改 alpha，颜色一个像素都没动，16 位缓冲照样有效，不作废
        val layer = activeLayer ?: return false
        val bmp = needBitmap(layer) ?: return false
        val m = layer.mask ?: return false
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return false
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(m, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), p)
        layer.bitmap = out
        layer.mask = null
        maskEditing = false
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 图层内容 + 蒙版压成一张（合并、拼合时用） */
    private fun bakedBitmap(layer: Layer): Bitmap? {
        val bmp = needBitmap(layer) ?: return null
        val m = layer.mask ?: return bmp
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(m, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), p)
        return out
    }

    fun duplicateLayer(index: Int) {
        val layer = layers.getOrNull(index) ?: return
        // 复制组头 = 复制整个组（桌面端里 Ctrl+J 复制组就是这个行为）
        if (layer.sectionType == 1 || layer.sectionType == 2) {
            duplicateGroup(index)
            return
        }
        val at = (index + 1).coerceIn(0, layers.size)
        layers.add(at, copyOfLayer(layer))
        activeIndex = at
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        invalidate()
    }

    /**
     * 连属性一起复制一层。
     * 只搬像素的话：调整层会变成一张空图（参数没了）、图层样式/文字/蒙版全丢 —— 等于复制了个空壳。
     * rawHead 故意不复制：它记的是原层在 PSD 里的位置和原始字节，复用到新层会写出错位的记录。
     */
    private fun copyOfLayer(layer: Layer): Layer {
        val src = needBitmap(layer)
        val dim = src ?: layer.bitmap
        val w = dim?.width ?: 1
        val h = dim?.height ?: 1
        val copy = src?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val created = Layer(copy, "${layer.name} 副本")
        created.visible = layer.visible
        created.alpha = layer.alpha
        created.alphaLock = layer.alphaLock
        created.clipped = layer.clipped
        created.blendMode = layer.blendMode
        created.sectionType = layer.sectionType
        created.adjust = layer.adjust?.copy()
        created.mask = layer.mask?.copy(Bitmap.Config.ARGB_8888, true)
        created.maskEdit = false
        created.strokeOn = layer.strokeOn
        created.strokeColor = layer.strokeColor
        created.strokeWidth = layer.strokeWidth
        created.shadowOn = layer.shadowOn
        created.shadowDX = layer.shadowDX
        created.shadowDY = layer.shadowDY
        created.shadowBlur = layer.shadowBlur
        created.shadowColor = layer.shadowColor
        created.textContent = layer.textContent
        created.textSize = layer.textSize
        created.textColor = layer.textColor
        created.textX = layer.textX
        created.textY = layer.textY
        return created
    }

    /** 复制整组：组底分隔层、成员、组头一起复制，属性逐层照搬 */
    private fun duplicateGroup(header: Int) {
        val span = groupSpan(header)
        val from = header - span + 1
        if (from < 0) return
        val block = ArrayList<Layer>(span)
        for (i in from..header) block.add(copyOfLayer(layers[i]))
        val at = (header + 1).coerceIn(0, layers.size)
        layers.addAll(at, block)
        activeIndex = at + span - 1
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        invalidate()
    }

    /** 拖动排序：把 from 层挪到 to 位置 */
    fun moveLayer(from: Int, to: Int) {
        if (from !in layers.indices) return
        val target = to.coerceIn(0, layers.lastIndex)
        if (target == from) return
        val moving = layers[from]
        // 拖组头就是拖整组：组头、成员、组底分隔层必须挨在一起，只搬组头会当场散架
        if (moving.sectionType == 1 || moving.sectionType == 2) {
            moveGroupTo(from, target)
            return
        }
        val item = layers.removeAt(from)
        layers.add(target, item)
        activeIndex = when {
            activeIndex == from -> target
            from < activeIndex && target >= activeIndex -> activeIndex - 1
            from > activeIndex && target <= activeIndex -> activeIndex + 1
            else -> activeIndex
        }
        onDocChanged?.invoke()
        invalidate()
    }

    /** 整组挪位置：组头永远和组底分隔层、成员一起走 */
    private fun moveGroupTo(header: Int, target: Int) {
        val span = groupSpan(header)
        val from = header - span + 1
        if (from < 0) return
        if (target in from..header) return
        val block = ArrayList<Layer>(span)
        repeat(span) { block.add(layers.removeAt(from)) }
        val at = (if (target > header) target - span + 1 else target).coerceIn(0, layers.size)
        layers.addAll(at, block)
        activeIndex = at + span - 1
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        invalidate()
    }

    // ================= 自由变换 =================

    @Volatile
    private var tfCorner: Int = -1

    /** 变换框：取图层里不透明像素的外接矩形（源图坐标），让框贴合内容而不是整张画布 */
    private fun computeTfBounds(src: Bitmap): RectF {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var left = w
        var right = -1
        var top = h
        var bottom = -1
        val stepX = if (w > 900) 2 else 1
        val stepY = if (h > 900) 2 else 1
        var y = 0
        while (y < h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                if ((row[x] ushr 24) > 8) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                x += stepX
            }
            y += stepY
        }
        if (right < left || bottom < top) return RectF(0f, 0f, w.toFloat(), h.toFloat())
        val pad = 2f
        return RectF(
            (left - pad).coerceAtLeast(0f),
            (top - pad).coerceAtLeast(0f),
            (right + 1 + pad).coerceAtMost(w.toFloat()),
            (bottom + 1 + pad).coerceAtMost(h.toFloat()),
        )
    }

    /** 当前变换矩阵：绕框心旋转 → 缩放（可不等比）→ 平移 */
    private fun tfMatrix(): Matrix {
        val m = Matrix()
        m.postRotate(tfRot, tfPivotX, tfPivotY)
        m.postScale(tfSx, tfSy, tfPivotX, tfPivotY)
        m.postTranslate(tfTx, tfTy)
        return m
    }

    /** 桌面模式的光标：白底黑影的箭头，画在屏幕坐标上（不跟画布缩放） */
    private fun drawMouseCursor(canvas: Canvas) {
        if (!mouseMode) return
        if (!cursorVisible && width > 0 && height > 0) {
            cursorSx = width * 0.5f
            cursorSy = height * 0.5f
            cursorVisible = true
        }
        if (!cursorVisible) return
        if (cursorSx < 0f || cursorSy < 0f) return
        val k = resources.displayMetrics.density.coerceAtLeast(1f)
        val x = cursorSx
        val y = cursorSy
        val h = 17f * k
        val w = 11f * k
        val p = Path().apply {
            moveTo(x, y)
            lineTo(x, y + h)
            lineTo(x + w * 0.30f, y + h * 0.74f)
            lineTo(x + w * 0.55f, y + h * 1.02f)
            lineTo(x + w * 0.72f, y + h * 0.92f)
            lineTo(x + w * 0.48f, y + h * 0.66f)
            lineTo(x + w * 0.80f, y + h * 0.66f)
            close()
        }
        val shadow = Paint().apply { color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * k; isAntiAlias = true }
        val fill = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawPath(p, shadow)
        canvas.drawPath(p, fill)
    }

    private fun viewScale(): Float {
        val v = FloatArray(9)
        viewMatrix.getValues(v)
        return if (v[0] == 0f) 1f else v[0]
    }

    /** 变换框的 8 个手柄（文档坐标）：0-3 四角，4 上 5 右 6 下 7 左 */
    private fun tfHandles(): FloatArray {
        val b = tfBox
        val pts = floatArrayOf(
            b.left, b.top,
            b.right, b.top,
            b.right, b.bottom,
            b.left, b.bottom,
            (b.left + b.right) / 2f, b.top,
            b.right, (b.top + b.bottom) / 2f,
            (b.left + b.right) / 2f, b.bottom,
            b.left, (b.top + b.bottom) / 2f,
        )
        tfMatrix().mapPoints(pts)
        return pts
    }

    /** 旋转手柄：顶边中点再往外挪一段（桌面端那个位置） */
    private fun tfRotHandlePoint(): PointF {
        val pts = tfHandles()
        if (pts.size < 16) return PointF(0f, 0f)
        val cx = (pts[0] + pts[4]) / 2f
        val cy = (pts[1] + pts[5]) / 2f
        val mx = pts[8]
        val my = pts[9]
        var dx = mx - cx
        var dy = my - cy
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        dx /= len
        dy /= len
        val off = 34f / viewScale().coerceAtLeast(0.05f)
        return PointF(mx + dx * off, my + dy * off)
    }

    /** 当前变换的旋转角度（度），规范到 -180..180 */
    fun currentTransformRotation(): Float = if (transformActive) normDeg(tfRot) else 0f

    private fun normDeg(d: Float): Float {
        var v = d % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    private fun notifyRotation() {
        runCatching { onTransformRotation?.invoke(normDeg(tfRot)) }
    }

    /** 直接设定旋转角度（底部参数条用） */
    fun setTransformRotation(deg: Float) {
        if (!transformActive) return
        tfRot = deg
        invalidate()
    }

    /** 只取四角（给矩形/描边用） */
    private fun tfCorners(): FloatArray {
        val b = tfBox
        val pts = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        tfMatrix().mapPoints(pts)
        return pts
    }

    private fun drawTransformFrame(canvas: Canvas) {
        if (!transformActive || tfSource == null) return
        // 顶上一个模式标签：用户一眼能看出现在是自由变换还是透视
        val sc0 = viewScale().coerceAtLeast(0.05f)
        tfBadgePaint.textSize = 26f / sc0
        tfBadgePaint.color = if (tfPersp) 0xFF7AB8FF.toInt() else 0xFFE8C27A.toInt()
        // 缩放多少要看得见：等比时只报一个数，拉边造成不等比时两个都报
        val pctX = (tfSx * 100f).toInt()
        val pctY = (tfSy * 100f).toInt()
        val pct = if (kotlin.math.abs(pctX - pctY) <= 1) "$pctX%" else "$pctX% × $pctY%"
        canvas.drawText(
            if (tfPersp) "透视变换 · $pct · 拖四角各自移动 · 三指缩画布"
            else "自由变换 · $pct · ${tfRot.toInt()}° · 拖角等比 / 拖顶圆点旋转 · 双击确认",
            tfBox.left, (tfBox.top - 12f / sc0).coerceAtLeast(34f / sc0), tfBadgePaint,
        )
        if (tfPersp) {
            drawPerspectiveFrame(canvas)
            return
        }
        if (tfBox.width() <= 0f) return
        val scale = viewScale()
        val pts = tfCorners()
        val handles = tfHandles()

        tfPath.reset()
        tfPath.moveTo(pts[0], pts[1])
        tfPath.lineTo(pts[2], pts[3])
        tfPath.lineTo(pts[4], pts[5])
        tfPath.lineTo(pts[6], pts[7])
        tfPath.close()
        canvas.drawPath(tfPath, tfBoxFillPaint)
        tfFramePaint.strokeWidth = 2.2f / scale
        canvas.drawPath(tfPath, tfFramePaint)
        // 手柄画大一点，和 44 像素的命中范围相称，不然看着像没点到
        val cornerSize = 15f / scale
        val edgeSize = 11f / scale
        for (i in 0 until 4) {
            val cx = handles[i * 2]
            val cy = handles[i * 2 + 1]
            canvas.drawRect(
                cx - cornerSize, cy - cornerSize, cx + cornerSize, cy + cornerSize, tfHandlePaint
            )
        }
        for (i in 4 until 8) {
            val cx = handles[i * 2]
            val cy = handles[i * 2 + 1]
            canvas.drawCircle(cx, cy, edgeSize, tfHandlePaint)
        }
        // 旋转手柄：顶中点外上方一个圆点 + 连接线（拖它绕中心转）
        val rot = tfRotHandlePoint()
        tfFramePaint.strokeWidth = 1.6f / scale
        canvas.drawLine(handles[8], handles[9], rot.x, rot.y, tfFramePaint)
        val rotR = 13f / scale
        canvas.drawCircle(rot.x, rot.y, rotR, tfBoxFillPaint)
        tfFramePaint.strokeWidth = 2.2f / scale
        canvas.drawCircle(rot.x, rot.y, rotR, tfFramePaint)
        tfBadgePaint.textSize = 15f / scale
        canvas.drawText("\u21bb", rot.x - 7f / scale, rot.y + 6f / scale, tfBadgePaint)
    }

    private fun toastTransformApplied() {
        onHint?.invoke("变换已应用")
    }

    /** 开始变换当前图层；返回 false 表示这层变换不了（组 / 无像素 / 锁定） */
    fun beginTransform(): Boolean {
        val layer = layers.getOrNull(activeIndex) ?: return false
        if (layer.sectionType != 0) {
            onHint?.invoke("这是组，整组不能直接变换：先选中组里的某一层再变换")
            return false
        }
        if (layer.lockAll || layer.lockPos) {
            onHint?.invoke("这一层锁住了（图层面板里的锁），先解锁再变换")
            return false
        }
        val bmp = needBitmap(layer) ?: run {
            onHint?.invoke("这一层没有像素（例如空图层或调整层），画点东西或栅格化后再变换")
            return false
        }
        tfSource = bmp
        tfLayerIndex = activeIndex
        tfBox = computeTfBounds(bmp)
        tfTx = 0f
        tfTy = 0f
        tfSx = 1f
        tfSy = 1f
        tfRot = 0f
        tfPivotX = (tfBox.left + tfBox.right) / 2f
        tfPivotY = (tfBox.top + tfBox.bottom) / 2f
        tfMode = 0
        tfCorner = -1
        tfPersp = false
        if (tfPersp) {
            syncQuadFromAffine()
            buildPreviewSource()
        }
        transformActive = true
        lastTfTapAt = 0L
        // 放大到框跑出屏幕时（用户报的），自动缩到能看全，退出变换再还原
        ensureTransformVisible()
        invalidate()
        onTransformChanged?.invoke(true)
        return true
    }

    fun cancelTransform() {
        if (!transformActive) return
        transformActive = false
        tfSource = null
        tfMode = 0
        restoreTfView()
        invalidate()
        onTransformChanged?.invoke(false)
    }

    /** 应用变换：按当前矩阵把源图画进一张新图，替换掉图层（可撤销） */
    /** 上一次自由变换用过的矩阵（再做一次变换时复用） */
    private var lastTfMatrix: Matrix? = null

    /** 进变换时如果框超出屏幕，先把画布缩到能看全；这里记原来的视图，退出时还原 */
    private var tfViewSaved: Matrix? = null

    /** 变换框在屏幕上是否看得全？看不全就等比缩小视图（只缩不放） */
    private fun ensureTransformVisible() {
        if (tfSource == null || width == 0 || height == 0) return
        if (tfViewSaved == null) tfViewSaved = Matrix(viewMatrix)
        val c = tfCorners()
        if (c.size < 8) return
        var minX = c[0]
        var maxX = c[0]
        var minY = c[1]
        var maxY = c[1]
        var i = 2
        while (i < 8) {
            minX = min(minX, c[i])
            maxX = max(maxX, c[i])
            minY = min(minY, c[i + 1])
            maxY = max(maxY, c[i + 1])
            i += 2
        }
        val sw = (maxX - minX).coerceAtLeast(1f) * viewScale()
        val sh = (maxY - minY).coerceAtLeast(1f) * viewScale()
        val margin = 46f
        val availW = width - margin * 2f
        val availH = height - margin * 2f - 44f
        if (availW <= 0f || availH <= 0f) return
        if (sw <= availW && sh <= availH) return
        val f = min(availW / sw, availH / sh).coerceIn(0.05f, 1f)
        viewMatrix.postScale(f, f, width / 2f, height / 2f)
        invalidate()
        notifyZoom()
    }

    /** 退出变换，把进变换时缩小的视图还原 */
    private fun restoreTfView() {
        val m = tfViewSaved ?: return
        tfViewSaved = null
        viewMatrix.set(m)
        invalidate()
        notifyZoom()
    }

    /** 再次变换：把上一次的变换矩阵再套一遍 */
    fun repeatTransform(): Boolean {
        val m = lastTfMatrix ?: return false
        val layer = activeLayer ?: return false
        val bmp = needBitmap(layer) ?: return false
        val base16 = pix16ForGeometry(layer)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        replaceLayerAt(activeIndex, out)
        if (base16 != null) layers[activeIndex].pix16 = base16.resampleMatrix(m, out.width, out.height)
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 拉直：按给定角度旋转，再把四周空白裁掉，画布缩到内容外接矩形 */
    fun straighten(degrees: Float): Boolean {
        val layer = activeLayer ?: return false
        val bmp = needBitmap(layer) ?: return false
        if (kotlin.math.abs(degrees) < 0.01f) return false
        val base16 = pix16ForGeometry(layer)
        val m = Matrix()
        m.postRotate(degrees, bmp.width / 2f, bmp.height / 2f)
        val rotated = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(rotated).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val box = contentBounds(rotated) ?: return false
        val out = Bitmap.createBitmap(box.width(), box.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(rotated, -box.left.toFloat(), -box.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        if (rotated !== bmp) rotated.recycle()
        layers.indices.forEach { i ->
            val l = layers[i]
            l.bitmap = out
            l.styleVer++
            l.pix16 = null
            l.pix32 = null
            l.mip2 = null
            l.mip4 = null
        }
        if (base16 != null) {
            val rm = Matrix(m)
            rm.postTranslate(-box.left.toFloat(), -box.top.toFloat())
            layers[activeIndex].pix16 = base16.resampleMatrix(rm, out.width, out.height)
        }
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        return true
    }

    /** 内容识别缩放：主体按等比缩放、四周按拉伸补齐（桌面端的简化版） */
    fun contentAwareScale(newW: Int, newH: Int): Boolean {
        val layer = activeLayer ?: return false
        val bmp = needBitmap(layer) ?: return false
        if (newW < 1 || newH < 1) return false
        val keep = kotlin.math.min(newW.toFloat() / bmp.width, newH.toFloat() / bmp.height)
        val innerW = (bmp.width * keep).toInt().coerceIn(1, newW)
        val innerH = (bmp.height * keep).toInt().coerceIn(1, newH)
        val out = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        // 1) 整幅拉伸，负责边角
        c.drawBitmap(bmp, null, RectF(0f, 0f, newW.toFloat(), newH.toFloat()), p)
        // 2) 中间那块按等比重新画，覆盖掉拉伸变形的主体
        val dx = (newW - innerW) / 2f
        val dy = (newH - innerH) / 2f
        val keepRect = Rect((bmp.width * 0.12f).toInt(), (bmp.height * 0.12f).toInt(), (bmp.width * 0.88f).toInt(), (bmp.height * 0.88f).toInt())
        val dstRect = RectF(dx + innerW * 0.12f, dy + innerH * 0.12f, dx + innerW * 0.88f, dy + innerH * 0.88f)
        c.drawBitmap(bmp, keepRect, dstRect, p)
        layers.indices.forEach { i ->
            val l = layers[i]
            l.bitmap = out
            l.styleVer++
            l.pix16 = null
            l.pix32 = null
            l.mip2 = null
            l.mip4 = null
        }
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        return true
    }
    fun applyTransform(): Boolean {
        if (!transformActive) return false
        val src = tfSource ?: return false
        val index = tfLayerIndex
        if (index !in layers.indices) return false
        val layer = layers[index]
        // 变换前的 16 位数据（能从原始通道建就建），下面按同一个矩阵重采样搬过去
        val base16 = pix16ForGeometry(layer)
        if (tfPersp) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            warpMesh(c, src, 1f, p)
            transformActive = false
            tfSource = null
            tfPreviewSrc = null
            tfMode = 0
            replaceLayerAt(index, out)
            // 透视是四点单应：把「目标 → 源」的那张单应套在 16 位缓冲上
            val srcQuad = floatArrayOf(
                tfBox.left, tfBox.top,
                tfBox.right, tfBox.top,
                tfBox.right, tfBox.bottom,
                tfBox.left, tfBox.bottom,
            )
            val inv = Perspective.homography(tfQuad, srcQuad)
            lastTfMatrix = null
            if (base16 != null && inv != null) {
                val w = out.width
                val h = out.height
                layers[index].pix16 = base16.resample(w, h) { x, y, o ->
                    Perspective.map(inv, (x + 0.5f), (y + 0.5f), o)
                }
            }
            restoreTfView()
            invalidate()
            onTransformChanged?.invoke(false)
            return true
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = tfMatrix()
        lastTfMatrix = Matrix(m)
        val smart = layer.smartSrc
        if (smart != null) {
            // 智能对象：累计矩阵接着乘，永远从原图采样，所以反复变换不会累积模糊
            val total = Matrix(layer.smartMatrix ?: Matrix())
            total.postConcat(m)
            canvas.drawBitmap(smart, total, paint)
            layer.smartMatrix = total
        } else {
            canvas.drawBitmap(src, m, paint)
        }
        transformActive = false
        tfSource = null
        tfMode = 0
        replaceLayerAt(index, out)
        if (base16 != null) layers[index].pix16 = base16.resampleMatrix(m, out.width, out.height)
        // 形状层：把变换后的位置写回参数，否则再动参数条会弹回原处
        layers[index].shapeSpec?.let { sp ->
            val pts = floatArrayOf(sp.x0, sp.y0, sp.x1, sp.y1)
            runCatching { m.mapPoints(pts) }
            sp.x0 = pts[0]; sp.y0 = pts[1]; sp.x1 = pts[2]; sp.y1 = pts[3]
            layers[index].shapeVer++
        }
        restoreTfView()
        invalidate()
        onTransformChanged?.invoke(false)
        return true
    }

    private var tfFingerDist = 0f
    private var tfFingerAngle = 0f

    /** 双指落下：记下两指距离和角度 */
    private fun tfRecordFinger(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        tfFingerDist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        tfFingerAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /** 双指拖动：按距离比缩放、按角度差旋转 */
    private fun tfMoveFingers(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        var deltaAngle = angle - tfFingerAngle
        if (deltaAngle > 180f) deltaAngle -= 360f
        if (deltaAngle < -180f) deltaAngle += 360f
        tfTwoFinger(dist / tfFingerDist, deltaAngle)
        tfFingerDist = dist
        tfFingerAngle = angle
    }

    /** 反算到源图坐标 */
    private fun tfLocal(x: Float, y: Float): PointF {
        val inv = Matrix()
        if (!tfMatrix().invert(inv)) return PointF(x, y)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return PointF(p[0], p[1])
    }

    /** 按下：四角=等比缩放，四边中点=单向拉伸/压扁，框内=移动，框外=旋转 */
    private fun tfBeginDrag(docX: Float, docY: Float) {
        val src = tfSource ?: return
        val pts = tfHandles()
        if (pts.size < 16) return
        val r = 44f / viewScale()
        var hit = -1
        var bestD = r * r
        // 旋转手柄优先判：它离顶边中点有关系，别被顶边手柄抢走
        val rh = tfRotHandlePoint()
        val rdx = docX - rh.x
        val rdy = docY - rh.y
        if (rdx * rdx + rdy * rdy <= r * r) {
            hit = 8
            bestD = -1f
        }
        for (i in 0 until 8) {
            val dx = docX - pts[i * 2]
            val dy = docY - pts[i * 2 + 1]
            val d = dx * dx + dy * dy
            if (d <= bestD) {
                bestD = d
                hit = i
            }
        }
        tfCorner = hit
        tfStartX = docX
        tfStartY = docY
        tfStartSx = tfSx
        tfStartSy = tfSy
        tfStartRot = tfRot
        val local = tfLocal(docX, docY)
        tfStartLocalX = local.x
        tfStartLocalY = local.y
        tfStartInv = Matrix()
        if (!tfMatrix().invert(tfStartInv!!)) tfStartInv = null
        val cx = tfPivotX + tfTx
        val cy = tfPivotY + tfTy
        tfStartDist = hypot((docX - cx).toDouble(), (docY - cy).toDouble()).toFloat().coerceAtLeast(1f)
        tfStartAngle = Math.toDegrees(atan2((docY - cy).toDouble(), (docX - cx).toDouble())).toFloat()
        tfMode = when {
            hit == 8 -> 3
            hit in 0..3 -> 2
            hit == 4 || hit == 6 -> 4
            hit == 5 || hit == 7 -> 5
            tfContains(docX, docY, src) -> 1
            else -> 3
        }
    }

    /** 点是否落在变换后的图里：反算回原图坐标判断 */
    private fun tfContains(x: Float, y: Float, src: Bitmap): Boolean {
        val inv = Matrix()
        if (!tfMatrix().invert(inv)) return false
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        return pt[0] >= 0f && pt[1] >= 0f &&
            pt[0] <= src.width.toFloat() && pt[1] <= src.height.toFloat()
    }

    private fun tfLocalAt(x: Float, y: Float): PointF {
        val inv = tfStartInv ?: return tfLocal(x, y)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return PointF(p[0], p[1])
    }

    private fun tfDrag(docX: Float, docY: Float) {
        val cx = tfPivotX
        val cy = tfPivotY
        when (tfMode) {
            1 -> {
                tfTx += docX - tfStartX
                tfTy += docY - tfStartY
                tfStartX = docX
                tfStartY = docY
                // 吸附：变换原点贴到参考线 / 画布边角中心
                val (sx, sy) = snapTransform(tfPivotX + tfTx, tfPivotY + tfTy)
                tfTx = sx - tfPivotX
                tfTy = sy - tfPivotY
            }
            2 -> {
                // 四角：等比缩放，用 x/y 里变化大的那个比值
                val l = tfLocalAt(docX, docY)
                val denX = tfStartLocalX - cx
                val denY = tfStartLocalY - cy
                val rx = if (abs(denX) > 1f) (l.x - cx) / denX else 1f
                val ry = if (abs(denY) > 1f) (l.y - cy) / denY else 1f
                val f = (if (abs(rx) > abs(ry)) rx else ry).coerceIn(0.05f, 40f)
                tfSx = (tfStartSx * f).coerceIn(0.02f, 60f)
                tfSy = (tfStartSy * f).coerceIn(0.02f, 60f)
            }
            4 -> {
                // 上下边：只拉高/压扁
                val l = tfLocalAt(docX, docY)
                val denY = tfStartLocalY - cy
                val ry = if (abs(denY) > 1f) (l.y - cy) / denY else 1f
                tfSy = (tfStartSy * ry).coerceIn(0.02f, 60f)
            }
            5 -> {
                // 左右边：只拉宽/压窄
                val l = tfLocalAt(docX, docY)
                val denX = tfStartLocalX - cx
                val rx = if (abs(denX) > 1f) (l.x - cx) / denX else 1f
                tfSx = (tfStartSx * rx).coerceIn(0.02f, 60f)
            }
            3 -> {
                val ox = tfPivotX + tfTx
                val oy = tfPivotY + tfTy
                val angle = Math.toDegrees(atan2((docY - oy).toDouble(), (docX - ox).toDouble())).toFloat()
                var d = angle - tfStartAngle
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                tfRot = tfStartRot + d
                notifyRotation()
            }
        }
        invalidate()
    }

    /** 双指：同时缩放 + 旋转 */
    private fun tfTwoFinger(scaleFactor: Float, deltaDegrees: Float) {
        val f = scaleFactor.coerceIn(0.5f, 2f)
        tfSx = (tfSx * f).coerceIn(0.02f, 60f)
        tfSy = (tfSy * f).coerceIn(0.02f, 60f)
        tfRot += deltaDegrees
        notifyRotation()
        invalidate()
    }

    // ================= 透视变换 =================
    /** 是否处于透视模式（四角随便拖，做梯形/透视效果） */
    private var tfPersp: Boolean = false
    private var tfQuad = FloatArray(8)
    private var tfQuadStart = FloatArray(8)
    private var tfPreviewSrc: Bitmap? = null
    private var tfDragCorner = -1

    fun isPerspectiveMode(): Boolean = tfPersp

    /** 切到/退出透视模式；没在变换里就先开始变换 */
    fun setPerspectiveMode(on: Boolean) {
        if (on && !transformActive) beginTransform()
        if (!transformActive) return
        tfPersp = on
        if (on) {
            syncQuadFromAffine()
            buildPreviewSource()
        } else {
            tfPreviewSrc = null
        }
        invalidate()
    }

    /** 把当前仿射框的四角抄进透视四角 */
    private fun syncQuadFromAffine() {
        val pts = tfCorners()
        if (pts.size >= 8) System.arraycopy(pts, 0, tfQuad, 0, 8)
    }

    /** 透视复位：回到当前仿射框的形状 */
    fun resetPerspective() {
        if (!transformActive || !tfPersp) return
        syncQuadFromAffine()
        invalidate()
    }

    private fun buildPreviewSource() {
        val src = tfSource ?: return
        val maxSide = 900
        tfPreviewSrc = if (src.width <= maxSide && src.height <= maxSide) {
            src
        } else {
            val f = maxSide.toFloat() / max(src.width, src.height)
            Bitmap.createScaledBitmap(
                src,
                (src.width * f).toInt().coerceAtLeast(1),
                (src.height * f).toInt().coerceAtLeast(1),
                true,
            )
        }
    }

    /** 把 bmp（可能是缩小过的预览图）按当前四角映射到画布上 */
    private fun drawPerspectiveWarp(canvas: Canvas, bmp: Bitmap, paint: Paint) {
        val src = tfSource ?: return
        val s = bmp.width.toFloat() / src.width.coerceAtLeast(1)
        warpMesh(canvas, bmp, s, paint)
    }

    /** 用 16x16 网格 + 单应矩阵把图铺到四角上 */
    private fun warpMesh(canvas: Canvas, bmp: Bitmap, scale: Float, paint: Paint) {
        val srcQuad = floatArrayOf(
            tfBox.left * scale, tfBox.top * scale,
            tfBox.right * scale, tfBox.top * scale,
            tfBox.right * scale, tfBox.bottom * scale,
            tfBox.left * scale, tfBox.bottom * scale,
        )
        val h = Perspective.homography(srcQuad, tfQuad) ?: return
        val n = 16
        val verts = FloatArray((n + 1) * (n + 1) * 2)
        val out = FloatArray(2)
        var k = 0
        for (j in 0..n) {
            val sy = bmp.height * j / n.toFloat()
            for (i in 0..n) {
                val sx = bmp.width * i / n.toFloat()
                if (Perspective.map(h, sx, sy, out)) {
                    verts[k++] = out[0]
                    verts[k++] = out[1]
                } else {
                    verts[k++] = sx
                    verts[k++] = sy
                }
            }
        }
        canvas.drawBitmapMesh(bmp, n, n, verts, 0, null, 0, paint)
    }

    private fun drawPerspectiveFrame(canvas: Canvas) {
        val q = tfQuad
        tfPath.reset()
        tfPath.moveTo(q[0], q[1])
        tfPath.lineTo(q[2], q[3])
        tfPath.lineTo(q[4], q[5])
        tfPath.lineTo(q[6], q[7])
        tfPath.close()
        canvas.drawPath(tfPath, tfBoxFillPaint)
        tfFramePaint.strokeWidth = 2.2f / viewScale()
        canvas.drawPath(tfPath, tfFramePaint)
        val size = 10f / viewScale()
        for (i in 0 until 4) {
            val cx = q[i * 2]
            val cy = q[i * 2 + 1]
            canvas.drawRect(cx - size, cy - size, cx + size, cy + size, tfHandlePaint)
        }
    }

    /** 透视模式下按下的处理：四角=拖那个角，内部=整体平移 */
    private fun tfPerspBeginDrag(docX: Float, docY: Float) {
        val r = 44f / viewScale()
        var hit = -1
        var best = r * r
        for (i in 0 until 4) {
            val dx = docX - tfQuad[i * 2]
            val dy = docY - tfQuad[i * 2 + 1]
            val d = dx * dx + dy * dy
            if (d <= best) {
                best = d
                hit = i
            }
        }
        tfDragCorner = hit
        tfStartX = docX
        tfStartY = docY
        System.arraycopy(tfQuad, 0, tfQuadStart, 0, 8)
        tfMode = when {
            hit >= 0 -> 6
            insideQuad(docX, docY) -> 7
            else -> 0
        }
    }

    private fun insideQuad(x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val x1 = tfQuad[i * 2]
            val y1 = tfQuad[i * 2 + 1]
            val x2 = tfQuad[((i + 1) % 4) * 2]
            val y2 = tfQuad[((i + 1) % 4) * 2 + 1]
            val cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
            val s = if (cross >= 0f) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** 透视拖动：带约束，拖出交叉/凹角一律不接受，保持上一次合法的形状 */
    private fun tfPerspDrag(docX: Float, docY: Float) {
        when (tfMode) {
            6 -> {
                val i = tfDragCorner
                if (i !in 0 until 4) return
                val trial = tfQuad.copyOf()
                trial[i * 2] = docX
                trial[i * 2 + 1] = docY
                if (Perspective.isValidQuad(trial, minArea = 400f)) {
                    trial.copyInto(tfQuad)
                }
            }
            7 -> {
                val dx = docX - tfStartX
                val dy = docY - tfStartY
                for (i in 0 until 8) {
                    tfQuad[i] = if (i % 2 == 0) tfQuadStart[i] + dx else tfQuadStart[i] + dy
                }
            }
        }
        invalidate()
    }

    /** 透视模式下双指：整体平移（不解锁缩放，避免和单指拖角打架） */
    private fun tfPerspPan(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val mx = (event.getX(0) + event.getX(1)) / 2f
        val my = (event.getY(0) + event.getY(1)) / 2f
        if (!tfPerspPanning) {
            tfPerspPanning = true
            tfPerspPanX = mx
            tfPerspPanY = my
            return
        }
        val dx = mx - tfPerspPanX
        val dy = my - tfPerspPanY
        for (i in 0 until 8) {
            tfQuad[i] = if (i % 2 == 0) tfQuad[i] + dx else tfQuad[i] + dy
        }
        tfPerspPanX = mx
        tfPerspPanY = my
        invalidate()
    }

    private var tfPerspPanning = false
    private var tfPerspPanX = 0f
    private var tfPerspPanY = 0f

    /** 这层是否和当前视野相交：整层都在视野外就不用画（大画布小图层时省很多） */
    private fun layerInViewport(index: Int): Boolean {
        if (renderFullDoc) return true
        if (transformActive && index == tfLayerIndex) return true
        if (visibleDoc.isEmpty) return true
        val l = layers.getOrNull(index) ?: return false
        // 图层可能已经被换到磁盘上（bitmap 为 null），这时要用分块的尺寸判断
        val w = l.bitmap?.width ?: l.tiles?.width ?: return false
        val h = l.bitmap?.height ?: l.tiles?.height ?: return false
        return RectF.intersects(visibleDoc, RectF(0f, 0f, w.toFloat(), h.toFloat()))
    }

    /** 第 index 层要套的滤镜：把它上面所有调整层的矩阵乘起来（下面先算、上面后叠） */
    private fun adjustFilterFor(index: Int): ColorFilter? {
        var acc: ColorMatrix? = null
        var i = index + 1
        while (i < layers.size) {
            val op = layers[i].adjust
            // 色阶是查表，不走 ColorMatrix（见 applyLevelsToTarget）
            if (op != null && layers[i].visible && op.kind != AdjustOp.KIND_LEVELS &&
                op.kind != AdjustOp.KIND_GRADIENT_MAP && op.kind != AdjustOp.KIND_CONTAINER && !op.isNeutral()
            ) {
                val m = op.matrix()
                if (acc == null) acc = m else acc.postConcat(m)
            }
            i++
        }
        return acc?.let { ColorMatrixColorFilter(it) }
    }

    /** 当前正在画的位图（只有整屏/局部重建时才有；色阶调整层要靠它做原地查表） */
    private var renderTarget: Bitmap? = null

    /** 正在把整组拍平到离屏位图：这时不能按屏幕范围剔除，否则画面外的成员会被漏掉 */
    private var renderFullDoc = false

    /**
     * 调整组：一个调整层里叠了好几个效果，按从上到下的顺序对下面已画好的内容逐像素过一遍。
     * 以前只有「一种效果一个层」，想叠两个就得建两层，图层列表很快就被调整层占满。
     */
    private fun applyContainerToTarget(op: AdjustOp) {
        val t = renderTarget ?: return
        applyOpChainToBitmap(t, op)
    }

    /**
     * 把一串调整效果（调整组 / 渐变映射）直接烘到一张位图上，原地改。
     * 渲染调整层和导出 PSD 时栅格化都用它，保证屏幕看到什么、导出的就是什么。
     */
    fun bakeOpIntoBitmap(bmp: Bitmap, op: AdjustOp): Bitmap {
        applyOpChainToBitmap(bmp, op)
        return bmp
    }

    private fun applyOpChainToBitmap(t: Bitmap, op: AdjustOp) {
        val chain = op.flatten().filter { !it.isNeutral() }
        if (chain.isEmpty()) return
        // 每一项先编译成「矩阵」或「查表」，循环里就不用每次重算了
        val mats = ArrayList<FloatArray?>(chain.size)
        val luts = ArrayList<IntArray?>(chain.size)
        for (c in chain) {
            when (c.kind) {
                AdjustOp.KIND_BRIGHT_CONTRAST, AdjustOp.KIND_HUE_SAT -> {
                    mats.add(c.matrix().array.copyOf()); luts.add(null)
                }
                AdjustOp.KIND_LEVELS -> {
                    val ps = c.levelsOrDefault()
                    // 三个通道表合成一张：先按通道表，再按 RGB 表
                    val lr = Levels.lut(ps[1]); val lg = Levels.lut(ps[2]); val lb = Levels.lut(ps[3])
                    val lc = Levels.lut(ps[0])
                    val flat = IntArray(768)
                    for (i in 0 until 256) {
                        flat[i] = lc[lr[i]]
                        flat[256 + i] = lc[lg[i]]
                        flat[512 + i] = lc[lb[i]]
                    }
                    mats.add(null); luts.add(flat)
                }
                AdjustOp.KIND_GRADIENT_MAP -> { mats.add(null); luts.add(c.gradLut()) }
                else -> { mats.add(null); luts.add(null) }
            }
        }
        val band = 128
        var y = 0
        while (y < t.height) {
            val bh = min(band, t.height - y)
            val px = IntArray(t.width * bh)
            t.getPixels(px, 0, t.width, 0, y, t.width, bh)
            for (k in px.indices) {
                var c = px[k]
                val a = c ushr 24
                if (a == 0) continue
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF
                for (ci in chain.indices) {
                    val m = mats[ci]
                    if (m != null) {
                        val nr = (m[0] * r + m[1] * g + m[2] * b + m[4]).toInt().coerceIn(0, 255)
                        val ng = (m[5] * r + m[6] * g + m[7] * b + m[9]).toInt().coerceIn(0, 255)
                        val nb = (m[10] * r + m[11] * g + m[12] * b + m[14]).toInt().coerceIn(0, 255)
                        r = nr; g = ng; b = nb
                    } else {
                        val lut = luts[ci] ?: continue
                        if (lut.size == 256) {
                            val luma = (299 * r + 587 * g + 114 * b) / 1000
                            val col = lut[luma.coerceIn(0, 255)]
                            r = (col shr 16) and 0xFF; g = (col shr 8) and 0xFF; b = col and 0xFF
                        } else {
                            r = lut[r]; g = lut[256 + g]; b = lut[512 + b]
                        }
                    }
                }
                px[k] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            t.setPixels(px, 0, t.width, 0, y, t.width, bh)
            y += bh
        }
    }

    /** 有没有可见的色阶调整层（有的话局部重绘必须走整屏重建，否则会绕开查表） */
    private fun hasLevelsAdjust(): Boolean =
        layers.any {
            it.visible && (it.adjust?.kind == AdjustOp.KIND_LEVELS ||
                it.adjust?.kind == AdjustOp.KIND_GRADIENT_MAP ||
                it.adjust?.kind == AdjustOp.KIND_CONTAINER)
        }

    /** 渐变映射调整层：对下面已经画好的内容按亮度查表换色（分带处理，避免整幅拷贝） */
    private fun applyGradientToTarget(op: AdjustOp) {
        val t = renderTarget ?: return
        val lut = op.gradLut()
        val w = t.width
        val h = t.height
        if (w <= 0 || h <= 0) return
        val band = 256
        val row = IntArray(w * band)
        var y = 0
        while (y < h) {
            val bh = minOf(band, h - y)
            t.getPixels(row, 0, w, 0, y, w, bh)
            for (i in 0 until w * bh) {
                val c = row[i]
                val a = Color.alpha(c)
                if (a == 0) continue
                val luma = ((299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000)
                    .coerceIn(0, 255)
                row[i] = (a shl 24) or (lut[luma] and 0xFFFFFF)
            }
            t.setPixels(row, 0, w, 0, y, w, bh)
            y += bh
        }
    }

    /** 导出 PSD 用：把 [0..index] 这些可见图层合成一张（混合模式按正常算就够用） */
    fun compositeUpTo(index: Int): Bitmap? {
        val first = layers.firstOrNull()?.let { needBitmap(it) } ?: return null
        val out = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (k in 0..index.coerceAtMost(layers.lastIndex)) {
            val l = layers[k]
            if (!l.visible) continue
            val b = bakedBitmap(l) ?: continue
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.alpha = effectiveAlpha(l)
            c.drawBitmap(b, 0f, 0f, p)
        }
        return out
    }

    /** 这类调整层 桌面端里没有对应的原生块（渐变映射的 GdMp 复杂、调整组更是没有），
     *  导出前必须烘成像素，否则 桌面端打开是个空层。 */
    fun needsBakeForPsd(op: AdjustOp?): Boolean =
        op != null && (op.kind == AdjustOp.KIND_GRADIENT_MAP || op.kind == AdjustOp.KIND_CONTAINER)

    /**
     * 把画布上「已经画好的下半部分」原地套一遍色阶查表。
     * 分带处理，避免一次 getPixels 整张图吃掉几十兆。
     */
    private fun applyLevelsToTarget(op: AdjustOp) {
        val t = renderTarget ?: return
        val ps = op.levelsOrDefault()
        val lutR = Levels.lut(ps[1])
        val lutG = Levels.lut(ps[2])
        val lutB = Levels.lut(ps[3])
        val lutC = Levels.lut(ps[0])
        val band = 128
        var y = 0
        while (y < t.height) {
            val h = min(band, t.height - y)
            val px = IntArray(t.width * h)
            t.getPixels(px, 0, t.width, 0, y, t.width, h)
            var k = 0
            while (k < px.size) {
                val c = px[k]
                val a = c ushr 24
                if (a != 0) {
                    val r0 = lutR[(c shr 16) and 0xFF]
                    val g0 = lutG[(c shr 8) and 0xFF]
                    val b0 = lutB[c and 0xFF]
                    px[k] = (a shl 24) or (lutC[r0] shl 16) or (lutC[g0] shl 8) or lutC[b0]
                }
                k++
            }
            t.setPixels(px, 0, t.width, 0, y, t.width, h)
            y += band
        }
    }

    /**
     * 把组里的成员画进一张缓冲图（整组当一层用）。缓存键挂在「合成版本 + 样式版本」上，
     * 画面或样式没变就直接复用，不每帧重算。
     */
    private fun groupBitmapFor(header: Int, from: Int, until: Int): Bitmap? {
        val h = layers.getOrNull(header) ?: return null
        val ref = h.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return null
        if (ref.width <= 0 || ref.height <= 0) return null
        val key = compositePixelStamp.toLong() * 1000003L + h.styleVer.toLong()
        val cached = h.groupCache
        val bmp = if (cached != null && !cached.isRecycled &&
            cached.width == ref.width && cached.height == ref.height
        ) cached
        else runCatching { Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        if (h.groupCache === bmp && h.groupKey == key) return bmp
        val c = Canvas(bmp)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val old = renderFullDoc
        renderFullDoc = true
        runCatching { drawLayerRun(c, from, until) }
        renderFullDoc = old
        h.groupCache = bmp
        h.groupKey = key
        // 组内容变了：影子/描边/发光那几张缓存必须重算
        h.groupProxy?.let {
            it.styleKey = -1
            it.topKey = -1
        }
        return bmp
    }

    /** 组样式用的临时层：位图是整组拍平的结果，样式参数照抄组头 */
    private fun groupStyledLayer(h: Layer, bmp: Bitmap): Layer {
        var p = h.groupProxy
        if (p == null) {
            p = Layer(bmp, h.name)
            h.groupProxy = p
        }
        p.bitmap = bmp
        if (h.groupProxyStamp != h.styleVer) {
            applyStyleTo(p, styleParamsOf(h))
            h.groupProxyStamp = h.styleVer
        }
        return p
    }

    private fun drawLayers(canvas: Canvas) {
        adjFilter = null
        drawLayerRun(canvas, 0, layers.size)
    }

    /** 组底分隔层往上找它对应的组头（只在 [divider, until) 范围内找），找不到返回 -1 */
    private fun groupHeaderFor(divider: Int, until: Int): Int {
        var depth = 1
        var j = divider + 1
        while (j < until) {
            val t = layers[j].sectionType
            if (t == 3) depth++
            if (t == 1 || t == 2) {
                depth--
                if (depth == 0) return j
            }
            j++
        }
        return -1
    }

    /**
     * 画 [from, until) 这段图层。
     * 遇到组底分隔层就把「分隔层之上到组头之间」的成员当一个整体画进离屏层，
     * 再用组头的不透明度 + 混合模式贴回来——组才真的像 桌面端那样是一层，
     * 而不是只当文件夹（组的混合模式 / 整组不透明度以前完全没效果）。
     */
    private fun drawLayerRun(canvas: Canvas, from: Int, until: Int) {
        var i = from
        while (i < until) {
            if (layers[i].sectionType == 3) {
                val header = groupHeaderFor(i, until)
                if (header >= 0) {
                    val h = layers[header]
                    if (h.visible) {
                        // 组上有图层样式：先把整组拍平成一张图，再当成「一层」算阴影/描边/发光。
                        // 这样投影是整组一个轮廓（桌面端的行为），而不是每个成员各投一圈。
                        if (hasStyle(h)) {
                            val gb = groupBitmapFor(header, i + 1, header)
                            if (gb != null) {
                                val gp = Paint(Paint.FILTER_BITMAP_FLAG)
                                gp.alpha = effectiveAlpha(h)
                                applyBlendMode(gp, h.blendMode)
                                canvas.saveLayer(null, gp)
                                val proxy = groupStyledLayer(h, gb)
                                stylePaint.isFilterBitmap = !pixelZoom
                                stylePaint.alpha = 255
                                val ov = styleOverlay(proxy)
                                if (ov != null) canvas.drawBitmap(ov, 0f, 0f, stylePaint)
                                canvas.drawBitmap(gb, 0f, 0f, stylePaint)
                                val tp = styleTop(proxy)
                                if (tp != null) canvas.drawBitmap(tp, 0f, 0f, stylePaint)
                                canvas.restore()
                                i = header + 1
                                continue
                            }
                        }
                        val grouped = h.alpha < 255 || h.blendMode != 0
                        if (grouped) {
                            // 每次新建画笔：Canvas.saveLayer 之后改同一张画笔会影响已入栈的层
                            val gp = Paint(Paint.FILTER_BITMAP_FLAG)
                            gp.alpha = effectiveAlpha(h)
                            applyBlendMode(gp, h.blendMode)
                            canvas.saveLayer(null, gp)
                        }
                        drawLayerRun(canvas, i + 1, header)
                        if (grouped) canvas.restore()
                    }
                    i = header + 1
                    continue
                }
            }
            val base = layers[i]
            var end = i + 1
            while (end < until && layers[end].clipped) end++
            val baseDrawn = base.visible && (base.bitmap != null || base.tiles != null) && layerInViewport(i)
            // 整组都在视野外：跳过，剪贴子层被组底层当遮罩，底层看不到它们也不可见
            if (!baseDrawn) {
                i = end
                continue
            }
            // 色阶调整层：对下面已经画好的内容做一次查表（它自己没有像素）
            val adjOp = base.adjust
            if (adjOp != null && base.visible && !adjOp.isNeutral()) {
                when (adjOp.kind) {
                    AdjustOp.KIND_LEVELS -> applyLevelsToTarget(adjOp)
                    AdjustOp.KIND_GRADIENT_MAP -> applyGradientToTarget(adjOp)
                    AdjustOp.KIND_CONTAINER -> applyContainerToTarget(adjOp)
                }
            }
            adjFilter = adjustFilterFor(i)
            drawLayerContent(canvas, i)
            var j = i + 1
            while (j < end) {
                val child = layers[j]
                val baseBitmap = base.bitmap
                if (child.visible && (baseBitmap != null || base.tiles != null)) {
                    // 在临时层里画子层，再用下层当遮罩抠一遍，最后整体合成
                    canvas.saveLayer(null, null)
                    adjFilter = adjustFilterFor(j)
                    drawLayerContent(canvas, j)
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    maskPaint.alpha = 255
                    if (baseBitmap != null) canvas.drawBitmap(baseBitmap, 0f, 0f, maskPaint)
                    else base.tiles?.drawVisible(canvas, visibleDoc, maskPaint)
                    canvas.restore()
                }
                j++
            }
            i = j
        }
    }

    private fun drawLayerContent(canvas: Canvas, index: Int) {
        val tfSrc = tfSource
        val layer = layers.getOrNull(index) ?: return
        if (transformActive && tfSrc != null && index == tfLayerIndex) {
            if (tfPersp) {
                bitmapPaint.isFilterBitmap = !pixelZoom
                bitmapPaint.alpha = 255
                bitmapPaint.colorFilter = channelFilter
                resetBlend(bitmapPaint)
                drawPerspectiveWarp(canvas, tfPreviewSrc ?: tfSrc, bitmapPaint)
                return
            }
            bitmapPaint.isFilterBitmap = !pixelZoom
            bitmapPaint.alpha = 255
            bitmapPaint.colorFilter = channelFilter
            resetBlend(bitmapPaint)
            canvas.drawBitmap(tfSrc, tfMatrix(), bitmapPaint)
            return
        }
        val bmp = layer.bitmap
        if (bmp == null) {
            val t = layer.tiles
            if (t != null && layer.mask == null && !hasStyle(layer)) {
                bitmapPaint.isFilterBitmap = !pixelZoom
                bitmapPaint.alpha = effectiveAlpha(layer)
                bitmapPaint.colorFilter = channelFilter
                applyBlendMode(bitmapPaint, layer.blendMode)
                t.drawVisible(canvas, visibleDoc, bitmapPaint)
            }
            return
        }
        bitmapPaint.colorFilter = adjFilter
        val mask = layer.mask
        val styled = hasStyle(layer)
        if (mask == null && !styled) {
            drawLayerPixels(canvas, index, layer, bmp)
            return
        }
        // 有蒙版或图层样式：先画样式+图层，再用蒙版的 alpha 当遮罩剪一遍
        canvas.saveLayer(null, null)
        if (styled) {
            val ov = styleOverlay(layer)
            if (ov != null) {
                stylePaint.isFilterBitmap = !pixelZoom
                stylePaint.alpha = 255
                canvas.drawBitmap(ov, 0f, 0f, stylePaint)
            }
        }
        drawLayerPixels(canvas, index, layer, bmp)
        if (styled) {
            val tp = styleTop(layer)
            if (tp != null) {
                stylePaint.isFilterBitmap = !pixelZoom
                stylePaint.alpha = 255
                canvas.drawBitmap(tp, 0f, 0f, stylePaint)
            }
        }
        if (mask != null) {
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            maskPaint.alpha = 255
            maskPaint.isFilterBitmap = true
            canvas.drawBitmap(mask, null, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), maskPaint)
            maskPaint.xfermode = null
        }
        canvas.restore()
    }

    // ================= 形状工具 =================

    /** 按拖动矩形和类型生成形状路径；equal 为真时按正方形/正圆撑满 */
    private fun buildShapePath(x0: Float, y0: Float, x1: Float, y1: Float, equal: Boolean): Path =
        buildShapePathEx(x0, y0, x1, y1, equal, shapeKind, shapeCorner, shapeSides, shapeInner)

    /** 按给定参数画路径：形状层重画时用这个，避免依赖当前工具条上的值 */
    private fun buildShapePathEx(
        x0: Float, y0: Float, x1: Float, y1: Float, equal: Boolean,
        kind: Int, corner: Int, sides: Int, inner: Int,
    ): Path {
        var l = min(x0, x1)
        var t = min(y0, y1)
        var r = max(x0, x1)
        var b = max(y0, y1)
        if (equal) {
            val s = max(r - l, b - t)
            if (x1 < x0) l = r - s else r = l + s
            if (y1 < y0) t = b - s else b = t + s
        }
        val path = Path()
        when (kind) {
            1 -> path.addOval(RectF(l, t, r, b), Path.Direction.CW)
            2 -> {
                path.moveTo(x0, y0)
                path.lineTo(x1, y1)
            }
            3, 5 -> {
                // 多边形 / 星形：外接椭圆里均匀取点
                val cx = (l + r) / 2f
                val cy = (t + b) / 2f
                val rx = (r - l) / 2f
                val ry = (b - t) / 2f
                val n = sides.coerceIn(3, 24)
                val step = 360.0 / n
                for (i in 0 until n) {
                    val a = Math.toRadians(step * i - 90.0)
                    val px = cx + (rx * Math.cos(a)).toFloat()
                    val py = cy + (ry * Math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    if (kind == 5) {
                        val b2 = Math.toRadians(step * (i + 0.5) - 90.0)
                        val k = inner.coerceIn(5, 95) / 100f
                        path.lineTo(
                            cx + (rx * k * Math.cos(b2)).toFloat(),
                            cy + (ry * k * Math.sin(b2)).toFloat(),
                        )
                    }
                }
                path.close()
            }
            4 -> {
                val rad = corner.toFloat().coerceAtLeast(0f)
                path.addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
            }
            else -> path.addRect(l, t, r, b, Path.Direction.CW)
        }
        return path
    }

    private fun shapePaintOf(sp: ShapeSpec): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.isDither = true
        p.color = if (sp.color and 0x00FFFFFF == 0) 0xFF000000.toInt() else sp.color
        p.alpha = sp.alpha.coerceIn(0, 255)
        p.style = if (sp.fill) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = (if (sp.width > 0) sp.width.toFloat() else brushSize).coerceAtLeast(1f)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        return p
    }

    /** 把一层的位图清空后按形状参数重画（形状层专用） */
    private fun renderShapeInto(bmp: Bitmap, sp: ShapeSpec) {
        bmp.eraseColor(0)
        val path = buildShapePathEx(
            sp.x0, sp.y0, sp.x1, sp.y1,
            sp.circle && sp.kind == 1,
            sp.kind, sp.corner, sp.sides, sp.inner,
        )
        Canvas(bmp).drawPath(path, shapePaintOf(sp))
    }

    /** 当前活动层是形状层就返回它的参数（参数条据此显示真实值） */
    fun activeShapeSpec(): ShapeSpec? = activeLayer?.shapeSpec

    /** 当前图层上「还留着参数、能回头改」的东西（左栏「修改」用） */
    fun activeLayerFeatures(): List<Pair<String, String>> {
        val l = layers.getOrNull(activeIndex) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        if (l.shapeSpec != null) out.add("shape" to "形状")
        if (l.gradSpec != null) out.add("grad" to "渐变")
        if (!l.textContent.isNullOrEmpty()) out.add("text" to "文字")
        if (hasStyle(l)) out.add("style" to "图层样式")
        if (l.mask != null) out.add("mask" to "蒙版")
        l.adjust?.let { out.add("adjust" to "调整 · " + it.title()) }
        if (l.smartFx.isNotEmpty()) out.add("smartfx" to "智能滤镜 · " + l.smartFx.size + " 个")
        return out
    }

    private var specEditLayer: Layer? = null
    private var specEditBefore: IntArray? = null
    private var specEditW = 0
    private var specEditH = 0
    private var specEditSpec0: ShapeSpec? = null
    private var specEditLive: ShapeSpec? = null

    /** 开始改形状参数：先拍一张整层快照，改完只把变化的矩形存成一步撤销 */
    fun beginShapeSpecEdit(): Boolean {
        val layer = activeLayer ?: return false
        val sp = layer.shapeSpec ?: return false
        val bmp = needBitmap(layer) ?: return false
        specEditLayer = layer
        specEditW = bmp.width
        specEditH = bmp.height
        specEditBefore = IntArray(bmp.width * bmp.height)
        bmp.getPixels(specEditBefore!!, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        specEditSpec0 = sp.copyOf()
        specEditLive = sp.copyOf()
        return true
    }

    /** 拖动中：立刻按新参数重画这一层（不压撤销栈） */
    fun updateShapeSpec(sp: ShapeSpec) {
        val layer = specEditLayer ?: return
        val bmp = layer.bitmap ?: return
        val live = sp.copyOf()
        specEditLive = live
        layer.shapeSpec = live.copyOf()
        renderShapeInto(bmp, live)
        layer.styleCache = null
        layer.topCache = null
        layer.styleVer++
        touchPixels(Rect(0, 0, bmp.width, bmp.height))
        invalidate()
    }

    /** 松手（或停手 0.4 秒）：把这次改动压成一步撤销 */
    fun endShapeSpecEdit() {
        val layer = specEditLayer ?: return
        val before = specEditBefore ?: return
        val bmp = needBitmap(layer)
        specEditLayer = null
        specEditBefore = null
        if (bmp == null || specEditW <= 0 || specEditH <= 0) return
        val after = IntArray(bmp.width * bmp.height)
        bmp.getPixels(after, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        // 只找出真正变了的矩形，别把整张图塞进撤销栈
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var b = -1
        var i = 0
        while (i < after.size) {
            if (before[i] != after[i]) {
                val x = i % specEditW
                val y = i / specEditW
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
            i++
        }
        if (r < l || b < t) return
        val pad = 2
        l = (l - pad).coerceAtLeast(0)
        t = (t - pad).coerceAtLeast(0)
        r = (r + pad).coerceAtMost(specEditW - 1)
        b = (b + pad).coerceAtMost(specEditH - 1)
        val w = r - l + 1
        val h = b - t + 1
        val bArr = IntArray(w * h)
        val aArr = IntArray(w * h)
        var y = 0
        while (y < h) {
            System.arraycopy(before, (t + y) * specEditW + l, bArr, y * w, w)
            System.arraycopy(after, (t + y) * specEditW + l, aArr, y * w, w)
            y++
        }
        val st = Stroke().also { it.label = "调整形状" }
        st.patches.add(Patch(layer, l, t, w, h, bArr, aArr))
        pushUndo(st)
        runCatching { layer.pix16?.applyFrom8Diff(l, t, w, h, bArr, aArr) }
        layer.styleCache = null
        layer.topCache = null
        layer.styleVer++
        onDocChanged?.invoke()
        invalidate()
    }

    private fun shapePaint(): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.isDither = true
        p.color = if (brushColor and 0x00FFFFFF == 0) 0xFF000000.toInt() else brushColor
        p.alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt()
        p.style = if (shapeFill) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = (if (shapeWidth > 0) shapeWidth.toFloat() else brushSize).coerceAtLeast(1f)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        return p
    }

    /** 拖动中的预览（直接画在画布上，不落进图层） */
    private fun drawShapePreview(canvas: Canvas) {
        if (!shapeActive) return
        val sq = shapeSquare || (shapeKind == 1 && shapeCircle)
        val path = buildShapePath(shapeX0, shapeY0, shapeX1, shapeY1, sq)
        val p = shapePaint()
        p.alpha = (p.alpha * 0.75f).toInt().coerceAtLeast(20)
        canvas.drawPath(path, p)
        // 直线：画出实际几何角度，吸住了写成整数（用户要「能不能拉出水平直线」看得见）
        if (shapeKind == 2) {
            val dx = (shapeX1 - shapeX0).toDouble()
            val dy = (shapeY1 - shapeY0).toDouble()
            if (dx * dx + dy * dy > 4.0) {
                val deg = Math.toDegrees(kotlin.math.atan2(dy, dx)).toFloat()
                val shown = if (shapeSnapDeg > -900f) shapeSnapDeg else deg
                val sc = viewScale().coerceAtLeast(0.05f)
                tfBadgePaint.textSize = 24f / sc
                tfBadgePaint.color = if (shapeSnapDeg > -900f) 0xFF7AB8FF.toInt() else 0xFFE8C27A.toInt()
                canvas.drawText("${shown.toInt()}\u00b0", shapeX1 + 10f / sc, shapeY1 - 10f / sc, tfBadgePaint)
            }
        }
    }

    /**
     * 松手：形状单独建一层画进去（不再糊在人物层上）。
     * 这样「撤销」= 整层消失一步搞定，「调宽高」= 用变换拖角，只影响这一个形状。
     */
    private fun commitShape() {
        val ref = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: run {
            onHint?.invoke("先选一个有像素的图层再画形状")
            return
        }
        // 提交时按「落笔时的屏幕坐标」重新换算一次：手势过程中视图可能变过，
        // 直接用缓存的文档坐标会画到别处（用户报的「画在中间、结果跑到上方」）
        val p0 = toImage(shapeViewX0, shapeViewY0)
        val p1 = toImage(shapeViewX, shapeViewY)
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "shape",
                "p0=" + p0.x + "," + p0.y + " p1=" + p1.x + "," + p1.y +
                    " view=" + width + "x" + height + " doc=" + p0.let { ref.width } + "x" + ref.height +
                    " square=" + shapeSquare
            )
        }
        val path = buildShapePath(p0.x, p0.y, p1.x, p1.y, shapeSquare || (shapeKind == 1 && shapeCircle))
        lastShapePath = Path(path)
        stampShape(path, "形状")
    }

    /**
     * 把一条路径真正变成像素。按 [shapeNewLayer] 分两条路：
     *  - 开（默认）：单独新建一层，撤销=整层消失，之后能用变换拖角改大小；
     *  - 关：画进当前图层，撤销=一步像素。
     */
    private fun stampShape(path: Path, label: String) {
        val ref = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: run {
            onHint?.invoke("先选一个有像素的图层再画形状")
            return
        }
        if (shapeNewLayer) {
            val bmp = Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888)
            val spec = ShapeSpec(
                kind = shapeKind,
                x0 = shapeX0, y0 = shapeY0, x1 = shapeX1, y1 = shapeY1,
                corner = shapeCorner, sides = shapeSides, inner = shapeInner,
                circle = shapeKind == 1 && (shapeSquare || shapeCircle),
                fill = shapeFill, width = shapeWidth, color = brushColor,
                alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt(),
            )
            Canvas(bmp).drawPath(path, shapePaint())
            val insertAt = insertSpotForNew()
            val made = Layer(bmp, "形状 " + (layers.size + 1))
            made.shapeSpec = spec
            layers.add(insertAt, made)
            activeIndex = insertAt
            onActiveLayerChanged?.invoke()
            val st = Stroke().also {
                it.label = label
                it.addedLayer = made
                it.addedIndex = insertAt
            }
            pushUndo(st)
            touchPixels(Rect(0, 0, bmp.width, bmp.height))
            invalidateFlatSnapshot()
            onHint?.invoke("形状已放在新图层「" + made.name + "」，拖角可以改大小")
            onDocChanged?.invoke()
            invalidate()
            // 像桌面端一样：刚建好的形状层直接挂上变换框，拖角就能改大小
            post { runCatching { if (beginTransform()) invalidate() } }
            return
        }
        // 画进当前层（老行为）：只记一块像素补丁，撤销一步还原
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val pad = ((if (shapeWidth > 0) shapeWidth.toFloat() else brushSize) + 4f).toInt()
        val left = maxOf(0, bounds.left.toInt() - pad)
        val top = maxOf(0, bounds.top.toInt() - pad)
        val right = minOf(bmp.width, bounds.right.toInt() + pad)
        val bottom = minOf(bmp.height, bounds.bottom.toInt() + pad)
        val w = right - left
        val h = bottom - top
        if (w > 0 && h > 0) {
            val before = IntArray(w * h)
            bmp.getPixels(before, 0, w, left, top, w, h)
            val c = Canvas(bmp)
            c.translate(-left.toFloat(), -top.toFloat())
            c.drawPath(path, shapePaint())
            val after = IntArray(w * h)
            bmp.getPixels(after, 0, w, left, top, w, h)
            layer.styleCache = null
            layer.topCache = null
            runCatching { layer.pix16?.applyFrom8Diff(left, top, w, h, before, after) }
            val st = Stroke().also { it2 -> it2.label = label }
            st.patches.add(Patch(layer, left, top, w, h, before, after))
            pushUndo(st)
            touchPixels(Rect(left, top, right, bottom))
        }
        onDocChanged?.invoke()
        invalidate()
    }

    /** 形状面板「按尺寸生成」：在当前画面正中按指定长宽放一个形状 */
    fun makeShapeAtSize(w: Int, h: Int): Boolean {
        val ref = activeLayer?.bitmap ?: layers.firstNotNullOfOrNull { it.bitmap } ?: return false
        val bw = (if (w > 0) w else 200).coerceIn(1, ref.width)
        val bh = (if (h > 0) h else 200).coerceIn(1, ref.height)
        val cx = ref.width / 2f
        val cy = ref.height / 2f
        val path = if (shapeKind == 2) {
            val p = Path()
            if (shapeLineAngle != 0f) {
                // 按角度生成一条以画布中心为中点的直线
                val r = Math.toRadians(shapeLineAngle.toDouble())
                val half = kotlin.math.max(bw, bh) / 2f
                p.moveTo(
                    cx - (kotlin.math.cos(r) * half).toFloat(),
                    cy - (kotlin.math.sin(r) * half).toFloat(),
                )
                p.lineTo(
                    cx + (kotlin.math.cos(r) * half).toFloat(),
                    cy + (kotlin.math.sin(r) * half).toFloat(),
                )
            } else if (bw >= bh) {
                p.moveTo(cx - bw / 2f, cy)
                p.lineTo(cx + bw / 2f, cy)
            } else {
                p.moveTo(cx, cy - bh / 2f)
                p.lineTo(cx, cy + bh / 2f)
            }
            p
        } else if (shapeKind == 1 && shapeCircle) {
            val d = kotlin.math.max(bw, bh)
            buildShapePath(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f, true)
        } else {
            buildShapePath(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f, false)
        }
        lastShapePath = Path(path)
        stampShape(path, "形状")
        return true
    }

    // ================= 参考线与吸附 =================

    /** 水平参考线（文档 y 坐标） */
    val guideH = ArrayList<Float>()
    /** 垂直参考线（文档 x 坐标） */
    val guideV = ArrayList<Float>()
    /** 显示参考线 */
    private val showGuidesS = androidx.compose.runtime.mutableStateOf(true)
    var showGuides: Boolean
        get() = showGuidesS.value
        set(v) { showGuidesS.value = v }
    /** 变换时吸附到参考线 / 画布边缘 */
    private val snapGuidesS = androidx.compose.runtime.mutableStateOf(true)
    var snapGuides: Boolean
        get() = snapGuidesS.value
        set(v) { snapGuidesS.value = v }

    fun addGuide(horizontal: Boolean, at: Float) {
        if (horizontal) {
            guideH.add(at.coerceIn(0f, max(0, height - 1).toFloat()))
        } else {
            guideV.add(at.coerceIn(0f, max(0, width - 1).toFloat()))
        }
        invalidate()
    }

    /** 加一条在画面正中的参考线（菜单用） */
    fun addGuideCenter(horizontal: Boolean) {
        if (horizontal) addGuide(true, height / 2f) else addGuide(false, width / 2f)
    }

    fun clearGuides() {
        guideH.clear()
        guideV.clear()
        invalidate()
    }

    /** 正在拖的参考线：-1 表示没在拖；guideDragH 表示这是横线 */
    private var guideDragIdx: Int = -1
    private var guideDragH: Boolean = false

    fun guideCount(): Int = guideH.size + guideV.size

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00C8FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private fun drawGuides(canvas: Canvas) {
        if (!showGuides) return
        if (guideH.isEmpty() && guideV.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (y in guideH) canvas.drawLine(0f, y, w, y, guidePaint)
        for (x in guideV) canvas.drawLine(x, 0f, x, h, guidePaint)
    }

    /** 把 v 吸附到最近的一条线（±tol 内），没有就原样返回 */
    private fun snapTo(v: Float, lines: List<Float>, tol: Float): Float {
        var best = v
        var bestD = tol
        for (l in lines) {
            val d = abs(l - v)
            if (d <= bestD) {
                bestD = d
                best = l
            }
        }
        return best
    }

    /** 变换拖动过程中的吸附：把变换原点贴到参考线 / 画布边角中心 */
    private fun snapTransform(ox: Float, oy: Float): Pair<Float, Float> {
        if (!snapGuides) return ox to oy
        val mv = FloatArray(9)
        viewMatrix.getValues(mv)
        val scale = if (abs(mv[0]) > 0.01f) abs(mv[0]) else 1f
        val tol = 10f / scale
        val vxs = ArrayList<Float>(guideV.size + 3)
        vxs.addAll(guideV)
        vxs.add(0f)
        vxs.add(width / 2f)
        vxs.add(width.toFloat())
        val hys = ArrayList<Float>(guideH.size + 3)
        hys.addAll(guideH)
        hys.add(0f)
        hys.add(height / 2f)
        hys.add(height.toFloat())
        return snapTo(ox, vxs, tol) to snapTo(oy, hys, tol)
    }

    // ================= 图层样式（描边 / 投影 / 发光 / 叠加 / 内阴影 / 浮雕）=================

    /** 一套图层样式参数（面板改完一次性应用） */
    data class StyleParams(
        val strokeOn: Boolean = false,
        val strokeColor: Int = 0xFF000000.toInt(),
        val strokeWidth: Int = 6,
        val shadowOn: Boolean = false,
        val shadowDX: Int = 8,
        val shadowDY: Int = 8,
        val shadowBlur: Int = 8,
        val shadowColor: Int = 0x99000000.toInt(),
        val glowOn: Boolean = false,
        val glowColor: Int = 0xCCFFE066.toInt(),
        val glowSize: Int = 8,
        val innerShadowOn: Boolean = false,
        val innerShadowColor: Int = 0x99000000.toInt(),
        val innerShadowDX: Int = 4,
        val innerShadowDY: Int = 4,
        val innerShadowSize: Int = 6,
        val innerGlowOn: Boolean = false,
        val innerGlowColor: Int = 0xCCFFF3B0.toInt(),
        val innerGlowSize: Int = 6,
        val colorOverlayOn: Boolean = false,
        val colorOverlayColor: Int = 0xFFFF3B30.toInt(),
        val gradientOverlayOn: Boolean = false,
        val gradientOverlayColor1: Int = 0xFFFF9F0A.toInt(),
        val gradientOverlayColor2: Int = 0xFF3A7BFF.toInt(),
        val gradientOverlayAngle: Int = 90,
        val bevelOn: Boolean = false,
        val bevelSize: Int = 6,
        val bevelDepth: Int = 60,
        val satinOn: Boolean = false,
        val satinColor: Int = 0x99000000.toInt(),
        val satinSize: Int = 10,
        val satinDistance: Int = 12,
        val satinAngle: Int = 19,
        val patternOverlayOn: Boolean = false,
        val patternOverlayIndex: Int = 0,
        val patternOverlayScale: Int = 100,
        val patternOverlayOpacity: Int = 100,
        val textureOn: Boolean = false,
        val texturePatternIndex: Int = 0,
        val textureScale: Int = 100,
        val textureDepth: Int = 50,
        val contourOn: Boolean = false,
        val contourKind: Int = 2,
    )

    fun hasBehindStyle(layer: Layer): Boolean = layer.strokeOn || layer.shadowOn || layer.glowOn

    private val stylePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 描边/发光 8 方向偏移（单位圆） */
    private val ringDirs = floatArrayOf(1f, 0f, 0.707f, 0.707f, 0f, 1f, -0.707f, 0.707f, -1f, 0f, -0.707f, -0.707f, 0f, -1f, 0.707f, -0.707f)

    fun hasTopStyle(layer: Layer): Boolean =
        layer.colorOverlayOn || layer.gradientOverlayOn || layer.innerShadowOn || layer.innerGlowOn || layer.bevelOn || layer.satinOn || layer.patternOverlayOn || layer.textureOn || layer.contourOn

    fun hasStyle(layer: Layer): Boolean = hasBehindStyle(layer) || hasTopStyle(layer)

    /** 把一个图层的样式读成 StyleParams（样式面板用它做初始值 / 取消时还原） */
    fun styleParamsOf(layer: Layer) = StyleParams(
        strokeOn = layer.strokeOn,
        strokeColor = layer.strokeColor,
        strokeWidth = layer.strokeWidth,
        shadowOn = layer.shadowOn,
        shadowDX = layer.shadowDX,
        shadowDY = layer.shadowDY,
        shadowBlur = layer.shadowBlur,
        shadowColor = layer.shadowColor,
        glowOn = layer.glowOn,
        glowColor = layer.glowColor,
        glowSize = layer.glowSize,
        innerShadowOn = layer.innerShadowOn,
        innerShadowColor = layer.innerShadowColor,
        innerShadowDX = layer.innerShadowDX,
        innerShadowDY = layer.innerShadowDY,
        innerShadowSize = layer.innerShadowSize,
        innerGlowOn = layer.innerGlowOn,
        innerGlowColor = layer.innerGlowColor,
        innerGlowSize = layer.innerGlowSize,
        colorOverlayOn = layer.colorOverlayOn,
        colorOverlayColor = layer.colorOverlayColor,
        gradientOverlayOn = layer.gradientOverlayOn,
        gradientOverlayColor1 = layer.gradientOverlayColor1,
        gradientOverlayColor2 = layer.gradientOverlayColor2,
        gradientOverlayAngle = layer.gradientOverlayAngle,
        bevelOn = layer.bevelOn,
        bevelSize = layer.bevelSize,
        bevelDepth = layer.bevelDepth,
        satinOn = layer.satinOn,
        satinColor = layer.satinColor,
        satinSize = layer.satinSize,
        satinDistance = layer.satinDistance,
        satinAngle = layer.satinAngle,
        patternOverlayOn = layer.patternOverlayOn,
        patternOverlayIndex = layer.patternOverlayIndex,
        patternOverlayScale = layer.patternOverlayScale,
        patternOverlayOpacity = layer.patternOverlayOpacity,
        textureOn = layer.textureOn,
        texturePatternIndex = layer.texturePatternIndex,
        textureScale = layer.textureScale,
        textureDepth = layer.textureDepth,
        contourOn = layer.contourOn,
        contourKind = layer.contourKind,
    )

    fun setLayerStyle(index: Int, s: StyleParams) {
        val layer = layers.getOrNull(index) ?: return
        // 组的样式只写在组头上：画的时候会先把整组拍平，再按这一层的样式算轮廓，
        // 所以不需要（也不能）往成员身上逐个写，否则每个成员都会各投一圈影子。
        // 0.68.11 那一版把组样式逐层写到了成员身上；升级后再套组样式会变成
        // 「整组一个轮廓 + 每层各投一圈影子」。这里把「成员样式 == 组头旧样式」的层清回默认，
        // 用户自己单独设过样式的成员不会被误清。
        val headerOld = styleParamsOf(layer)
        applyStyleTo(layer, s)
        if (layer.sectionType == 1 || layer.sectionType == 2) {
            val span = groupSpan(index)
            if (span > 2) {
                for (i in (index - span + 1) until index) {
                    val m = layers.getOrNull(i) ?: continue
                    if (m.sectionType != 0) continue
                    if (styleParamsOf(m) == headerOld) applyStyleTo(m, StyleParams())
                }
            }
            // 组样式变了，拍平出来的那张图也要作废重算
            layer.groupKey = -1L
            layer.groupProxyStamp = Int.MIN_VALUE
            layer.groupProxy?.let {
                it.styleKey = -1
                it.topKey = -1
            }
        }
        invalidate()
        onDocChanged?.invoke()
    }

    /** 把一份样式参数写到某一层上（组成员批量写样式也走这里） */
    private fun applyStyleTo(layer: Layer, s: StyleParams) {
        layer.strokeOn = s.strokeOn
        layer.strokeColor = s.strokeColor
        layer.strokeWidth = s.strokeWidth.coerceIn(1, 40)
        layer.shadowOn = s.shadowOn
        layer.shadowDX = s.shadowDX
        layer.shadowDY = s.shadowDY
        layer.shadowBlur = s.shadowBlur.coerceIn(0, 60)
        layer.shadowColor = s.shadowColor
        layer.glowOn = s.glowOn
        layer.glowColor = s.glowColor
        layer.glowSize = s.glowSize.coerceIn(1, 60)
        layer.innerShadowOn = s.innerShadowOn
        layer.innerShadowColor = s.innerShadowColor
        layer.innerShadowDX = s.innerShadowDX
        layer.innerShadowDY = s.innerShadowDY
        layer.innerShadowSize = s.innerShadowSize.coerceIn(0, 40)
        layer.innerGlowOn = s.innerGlowOn
        layer.innerGlowColor = s.innerGlowColor
        layer.innerGlowSize = s.innerGlowSize.coerceIn(1, 40)
        layer.colorOverlayOn = s.colorOverlayOn
        layer.colorOverlayColor = s.colorOverlayColor
        layer.gradientOverlayOn = s.gradientOverlayOn
        layer.gradientOverlayColor1 = s.gradientOverlayColor1
        layer.gradientOverlayColor2 = s.gradientOverlayColor2
        layer.gradientOverlayAngle = s.gradientOverlayAngle
        layer.bevelOn = s.bevelOn
        layer.bevelSize = s.bevelSize.coerceIn(1, 40)
        layer.bevelDepth = s.bevelDepth.coerceIn(0, 100)
        layer.satinOn = s.satinOn
        layer.textureOn = s.textureOn
        layer.texturePatternIndex = s.texturePatternIndex
        layer.textureScale = s.textureScale.coerceIn(10, 400)
        layer.textureDepth = s.textureDepth.coerceIn(0, 100)
        layer.contourOn = s.contourOn
        layer.contourKind = s.contourKind.coerceIn(0, 5)
        layer.satinColor = s.satinColor
        layer.satinSize = s.satinSize.coerceIn(0, 40)
        layer.satinDistance = s.satinDistance.coerceIn(0, 40)
        layer.satinAngle = s.satinAngle.coerceIn(-180, 180)
        layer.patternOverlayOn = s.patternOverlayOn
        layer.patternOverlayIndex = s.patternOverlayIndex
        layer.patternOverlayScale = s.patternOverlayScale.coerceIn(10, 400)
        layer.patternOverlayOpacity = s.patternOverlayOpacity.coerceIn(0, 100)
        layer.styleVer++
    }

    /** 把图层压成一层单色剪影再做真模糊：缩到 1/4 算，内存小、边缘够柔（不会出现 8 张硬拷贝的重影） */
    private fun softSilhouette(src: Bitmap, color: Int, blur: Int): Bitmap? {
        val sw = (src.width / 4).coerceAtLeast(1)
        val sh = (src.height / 4).coerceAtLeast(1)
        if (sw <= 0 || sh <= 0) return null
        val small = runCatching { Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c0 = Canvas(small)
        val p0 = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        p0.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        c0.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, sw, sh), p0)
        p0.colorFilter = null
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        var a = IntArray(sw * sh) { (px[it] ushr 24) and 0xFF }
        a = boxBlurAlpha(a, sw, sh, (blur / 4).coerceIn(1, 24))
        val cr = (color shr 16) and 0xFF
        val cg = (color shr 8) and 0xFF
        val cb = color and 0xFF
        for (i in a.indices) {
            val al = a[i].coerceIn(0, 255)
            px[i] = (al shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        return small
    }

    /** 两趟一维盒式模糊（只模糊 alpha），够做柔和阴影 */
    private fun boxBlurAlpha(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val span = 2 * r + 1
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val base = y * w
            var sum = 0
            for (x in -r..r) sum += src[base + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = sum / span
                sum -= src[base + (x - r).coerceIn(0, w - 1)]
                sum += src[base + (x + r + 1).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / span
                sum -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    private fun styleOverlay(layer: Layer): Bitmap? {
        val bmp = needBitmap(layer) ?: return null
        val key = System.identityHashCode(bmp) * 31 + layer.styleVer
        if (layer.styleKey == key) return layer.styleCache
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // 投影：真模糊（压成单色剪影 -> 缩到 1/4 盒式模糊 -> 放大铺在层下面）
        if (layer.shadowOn) {
            val dx = layer.shadowDX.toFloat()
            val dy = layer.shadowDY.toFloat()
            val blur = layer.shadowBlur.coerceIn(0, 60)
            if (blur <= 1) {
                p.colorFilter = PorterDuffColorFilter(layer.shadowColor, PorterDuff.Mode.SRC_IN)
                p.alpha = 255
                c.drawBitmap(bmp, dx, dy, p)
                p.colorFilter = null
            } else {
                val sil = softSilhouette(bmp, layer.shadowColor, blur)
                if (sil != null) {
                    p.colorFilter = null
                    p.alpha = 255
                    c.drawBitmap(sil, Rect(0, 0, sil.width, sil.height),
                        RectF(dx, dy, dx + bmp.width, dy + bmp.height), p)
                    sil.recycle()
                }
            }
        }
        // 外发光：同样走真模糊，只是不带位移、范围小一点
        if (layer.glowOn) {
            val g = layer.glowSize.coerceIn(1, 60)
            val sil = softSilhouette(bmp, layer.glowColor, (g * 1.6f).toInt().coerceIn(2, 90))
            if (sil != null) {
                p.colorFilter = null
                p.alpha = 255
                c.drawBitmap(sil, Rect(0, 0, sil.width, sil.height),
                    RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), p)
                sil.recycle()
            }
        }
        // 描边
        if (layer.strokeOn) {
            p.colorFilter = PorterDuffColorFilter(layer.strokeColor, PorterDuff.Mode.SRC_IN)
            val w = layer.strokeWidth.coerceIn(1, 40)
            val step = if (w <= 3) 1 else (w / 4).coerceAtLeast(1)
            var r = step
            while (r <= w) {
                var i = 0
                while (i < ringDirs.size) {
                    c.drawBitmap(bmp, ringDirs[i] * r, ringDirs[i + 1] * r, p)
                    i += 2
                }
                r += step
            }
            p.colorFilter = null
        }
        layer.styleCache = out
        layer.styleKey = key
        return out
    }

    /** 压在图层上面的效果：颜色叠加 / 渐变叠加 / 内阴影 / 内发光 / 斜面浮雕，最后统一用图层 alpha 剪一遍 */
    private fun styleTop(layer: Layer): Bitmap? {
        val bmp = needBitmap(layer) ?: return null
        val key = System.identityHashCode(bmp) * 31 + layer.styleVer
        if (layer.topKey == key) return layer.topCache
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (layer.colorOverlayOn) {
            p.style = Paint.Style.FILL
            p.color = layer.colorOverlayColor
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.style = Paint.Style.FILL
        }
        if (layer.gradientOverlayOn) {
            val ang = Math.toRadians(layer.gradientOverlayAngle.toDouble())
            val ux = Math.cos(ang).toFloat()
            val uy = Math.sin(ang).toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val len = (abs(ux) * w + abs(uy) * h) / 2f
            p.shader = LinearGradient(
                cx - ux * len, cy - uy * len, cx + ux * len, cy + uy * len,
                layer.gradientOverlayColor1, layer.gradientOverlayColor2, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }
        // 图案叠加：图案平铺上去，最后和整层一起裁成形状
        if (layer.patternOverlayOn) {
            val pat = Patterns.all().getOrNull(layer.patternOverlayIndex)?.second
            if (pat != null) {
                val sc = layer.patternOverlayScale.coerceIn(10, 400) / 100f
                val sh = BitmapShader(pat, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val mtx = Matrix()
                mtx.setScale(sc, sc)
                sh.setLocalMatrix(mtx)
                p.shader = sh
                p.alpha = (layer.patternOverlayOpacity.coerceIn(0, 100) * 255 / 100)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                p.alpha = 255
            }
        }
        // 光泽：斜着挪过的自己盖不到的窄条，柔化后当成一道金属反光
        if (layer.satinOn) {
            val ang = Math.toRadians(layer.satinAngle.toDouble())
            val dx = (Math.cos(ang) * layer.satinDistance).toFloat()
            val dy = (Math.sin(ang) * layer.satinDistance).toFloat()
            val band = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bc = Canvas(band)
            val q = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            q.colorFilter = PorterDuffColorFilter(layer.satinColor, PorterDuff.Mode.SRC_IN)
            bc.drawBitmap(bmp, 0f, 0f, q)
            q.colorFilter = null
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            bc.drawBitmap(bmp, dx, dy, q)
            q.xfermode = null
            val s = layer.satinSize.coerceIn(0, 40).toFloat()
            if (s > 0.5f) {
                val soft = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                soft.alpha = 70
                var i = 0
                while (i < ringDirs.size) {
                    c.drawBitmap(band, ringDirs[i] * s, ringDirs[i + 1] * s, soft)
                    i += 2
                }
            }
            c.drawBitmap(band, 0f, 0f, p)
            band.recycle()
        }
        // 内阴影：图层形状里，被「往右下挪过的自己」盖不到的那一窄条
        if (layer.innerShadowOn) {
            val band = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bc = Canvas(band)
            val q = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            q.colorFilter = PorterDuffColorFilter(layer.innerShadowColor, PorterDuff.Mode.SRC_IN)
            bc.drawBitmap(bmp, 0f, 0f, q)
            q.colorFilter = null
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            bc.drawBitmap(bmp, layer.innerShadowDX.toFloat(), layer.innerShadowDY.toFloat(), q)
            q.xfermode = null
            val s = layer.innerShadowSize.coerceIn(0, 40).toFloat()
            if (s > 0.5f) {
                val soft = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                soft.alpha = 90
                var i = 0
                while (i < ringDirs.size) {
                    c.drawBitmap(band, ringDirs[i] * s, ringDirs[i + 1] * s, soft)
                    i += 2
                }
            }
            c.drawBitmap(band, 0f, 0f, p)
            band.recycle()
        }
        // 内发光：贴着边缘的一圈光；把整层染成发光色后，抠掉「缩小一圈的自己」
        if (layer.innerGlowOn) {
            val band = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bc = Canvas(band)
            val q = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val s = layer.innerGlowSize.coerceIn(1, 40).toFloat()
            q.style = Paint.Style.FILL
            q.color = layer.innerGlowColor
            bc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), q)
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            var i = 0
            while (i < ringDirs.size) {
                bc.drawBitmap(bmp, ringDirs[i] * s * 0.5f, ringDirs[i + 1] * s * 0.5f, q)
                i += 2
            }
            q.xfermode = null
            val m = Matrix()
            val kx = ((w - 2 * s) / w).coerceIn(0.05f, 0.98f)
            val ky = ((h - 2 * s) / h).coerceIn(0.05f, 0.98f)
            m.setScale(kx, ky, w / 2f, h / 2f)
            q.style = Paint.Style.FILL
            q.color = 0
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            bc.drawBitmap(bmp, m, q)
            q.xfermode = null
            c.drawBitmap(band, 0f, 0f, p)
            band.recycle()
        }
        // 斜面浮雕：左上亮、右下暗，两条窄带
        if (layer.bevelOn) {
            val s = layer.bevelSize.coerceIn(1, 40).toFloat()
            val a = (layer.bevelDepth.coerceIn(0, 100) * 255 / 100)
            val hl = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val hc = Canvas(hl)
            val q = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            q.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            hc.drawBitmap(bmp, 0f, 0f, q)
            q.colorFilter = null
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            hc.drawBitmap(bmp, -s, -s, q)
            q.xfermode = null
            p.alpha = a
            c.drawBitmap(hl, 0f, 0f, p)
            hl.recycle()
            val sh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sc = Canvas(sh)
            q.colorFilter = PorterDuffColorFilter(0xCC000000.toInt(), PorterDuff.Mode.SRC_IN)
            sc.drawBitmap(bmp, 0f, 0f, q)
            q.colorFilter = null
            q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            sc.drawBitmap(bmp, s, s, q)
            q.xfermode = null
            c.drawBitmap(sh, 0f, 0f, p)
            sh.recycle()
            p.alpha = 255
        }
        // 纹理：拿图案的明暗把样式的浓淡压出凹凸感
        if (layer.textureOn) {
            val pat = Patterns.all().getOrNull(layer.texturePatternIndex)?.second
            if (pat != null) {
                val pw = pat.width
                val ph = pat.height
                val step = max(1, layer.textureScale.coerceIn(10, 400) / 100)
                val depth = layer.textureDepth.coerceIn(0, 100) / 100f
                val patPix = IntArray(pw * ph)
                pat.getPixels(patPix, 0, pw, 0, 0, pw, ph)
                val px = IntArray(w * h)
                out.getPixels(px, 0, w, 0, 0, w, h)
                var y = 0
                while (y < h) {
                    val py = (y / step) % ph
                    var x = 0
                    while (x < w) {
                        val i = y * w + x
                        val a = px[i] ushr 24
                        if (a > 0) {
                            val pc = patPix[py * pw + (x / step) % pw]
                            val lum = (((pc shr 16) and 0xFF) * 299 + ((pc shr 8) and 0xFF) * 587 + (pc and 0xFF) * 114) / 1000
                            val f = 1f + depth * (lum / 127.5f - 1f) * 0.9f
                            val na = (a * f).toInt().coerceIn(0, 255)
                            px[i] = (na shl 24) or (px[i] and 0x00FFFFFF)
                        }
                        x++
                    }
                    y++
                }
                out.setPixels(px, 0, w, 0, 0, w, h)
            }
        }
        // 等高线：把样式的浓淡按曲线重新分配（线性/锥形/平滑/环形/圆角/锯齿）
        if (layer.contourOn) {
            val px = IntArray(w * h)
            out.getPixels(px, 0, w, 0, 0, w, h)
            val kind = layer.contourKind.coerceIn(0, 5)
            for (i in px.indices) {
                val a = px[i] ushr 24
                if (a == 0) continue
                val t = a / 255f
                val nt = when (kind) {
                    0 -> t
                    1 -> t * t
                    2 -> t * t * (3f - 2f * t)
                    3 -> 1f - abs(2f * t - 1f)
                    4 -> Math.sqrt(t.toDouble()).toFloat()
                    else -> (t * 2f) % 1f
                }
                val na = (nt * 255f).toInt().coerceIn(0, 255)
                px[i] = (na shl 24) or (px[i] and 0x00FFFFFF)
            }
            out.setPixels(px, 0, w, 0, 0, w, h)
        }
        // 统一裁成图层形状
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        p.alpha = 255
        c.drawBitmap(bmp, 0f, 0f, p)
        p.xfermode = null
        layer.topCache = out
        layer.topKey = key
        return out
    }

    // ================= 渐变填充 =================


    /** 线性渐变：把起点之前 / 终点之后的 alpha 抹成 0，这样只会画在拖出来那一段里 */
    private fun clipLinearOutside(t: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float) {
        val w = t.width
        val h = t.height
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 < 0.0001f) return
        val px = IntArray(w * h)
        t.getPixels(px, 0, w, 0, 0, w, h)
        var row = 0
        for (y in 0 until h) {
            val ry = (y - y0) * dy
            for (x in 0 until w) {
                val tt = ((x - x0) * dx + ry) / len2
                if (tt < 0f || tt > 1f) px[row] = 0
                row++
            }
        }
        t.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** 按渐变参数造一个着色器：支持平滑度、反向、缩放（过渡带长度） */
    private fun gradientShaderFor(
        radial: Boolean,
        x0: Float, y0: Float, x1: Float, y1: Float,
        c1: Int, c2: Int,
        smooth: Int, reverse: Boolean, scale: Int,
    ): Shader {
        val a = if (reverse) c2 else c1
        val b = if (reverse) c1 else c2
        // 缩放：过渡带长度按比例伸缩（起点不动，终点往起点收/推）
        val k = (scale.coerceIn(10, 300)) / 100f
        val ex = x0 + (x1 - x0) * k
        val ey = y0 + (y1 - y0) * k
        // 平滑度：用 17 个色标近似缓入缓出，0 时就是原样两色标
        val sm = smooth.coerceIn(0, 100) / 100f
        val n = if (sm <= 0.001f) 2 else 17
        val colors = IntArray(n)
        val stops = FloatArray(n)
        for (i in 0 until n) {
            val t = if (n == 2) i.toFloat() else i / (n - 1f)
            stops[i] = t
            // smoothstep 混入：sm=0 时 u=t（线性），sm=1 时 u=3t²-2t³
            val e = t * t * (3f - 2f * t)
            val u = t + (e - t) * sm
            colors[i] = lerpArgb(a, b, u)
        }
        return if (radial) {
            val r = hypot(ex - x0, ey - y0).coerceAtLeast(1f)
            RadialGradient(x0, y0, r, colors, stops, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(x0, y0, ex, ey, colors, stops, Shader.TileMode.CLAMP)
        }
    }

    /** 两个颜色按 t 插值（含 alpha，非预乘） */
    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val aa = (a ushr 24) and 0xFF
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val ba = (b ushr 24) and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val na = (aa + (ba - aa) * u).toInt().coerceIn(0, 255)
        val nr = (ar + (br - ar) * u).toInt().coerceIn(0, 255)
        val ng = (ag + (bg - ag) * u).toInt().coerceIn(0, 255)
        val nb = (ab + (bb - ab) * u).toInt().coerceIn(0, 255)
        return (na shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    fun applyGradient(): Boolean {
        val layer = activeLayer ?: return false
        val src = needBitmap(layer) ?: return false
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return false
        // 画之前先把 16 位缓冲备好，下面按同样的渐变方程在 16 位空间再填一遍
        val base16 = pix16ForGeometry(layer)
        var x1 = gradX1
        var y1 = gradY1
        if (abs(x1 - gradX0) < 1.5f && abs(y1 - gradY0) < 1.5f) {
            x1 = gradX0 + w * 0.5f
            y1 = gradY0
        }
        val t = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(t)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = gradientShaderFor(
            gradientRadial, gradX0, gradY0, x1, y1,
            gradientColor1, gradientColor2,
            gradientSmooth, gradientReverse, gradientScale,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        if (gradientClip && !gradientRadial) clipLinearOutside(t, gradX0, gradY0, x1, y1)
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
        // 限制在选区里
        val sel = selection
        if (sel != null) {
            val mp = Paint()
            mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            c.drawBitmap(sel, null, dst, mp)
        }
        // 阿尔法锁定：只涂已有像素
        if (layer.alphaLock) {
            val lp = Paint()
            lp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            c.drawBitmap(src, null, dst, lp)
        }
        val pixels = w * h
        val record = pixels <= 6_000_000
        val before = if (record) IntArray(pixels) else null
        if (before != null) src.getPixels(before, 0, w, 0, 0, w, h)
        // 渐变临时图的 alpha 就是覆盖率（选区 / 阿尔法锁定都剪在里面），16 位按它混
        val cover = IntArray(pixels)
        t.getPixels(cover, 0, w, 0, 0, w, h)
        Canvas(src).drawBitmap(t, 0f, 0f, Paint())
        val after = if (record) IntArray(pixels) else null
        if (after != null) src.getPixels(after, 0, w, 0, 0, w, h)
        t.recycle()
        runCatching {
            base16?.applyGradient(
                gradX0, gradY0, x1, y1, gradientRadial,
                gradientColor1, gradientColor2, cover
            )
        }
        if (before != null && after != null) {
            pushPatch(before, after, Rect(0, 0, w, h))
        } else {
            undoStack.clear()
            redoStack.clear()
        }
        // 记住这次渐变的参数 + 画之前的像素：之后选中这层，还能回来改颜色/线性径向
        layer.gradSpec = GradientSpec(
            gradX0, gradY0, x1, y1, gradientRadial, gradientColor1, gradientColor2,
            gradientSmooth, gradientReverse, gradientScale, gradientClip,
        )
        layer.gradBase = before?.copyOf()
        layer.gradStamp = pixelEpoch
        layer.styleVer++
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 当前选中的层上一次渐变的参数（没有就 null） */
    fun activeGradientSpec(): GradientSpec? = layers.getOrNull(activeIndex)?.gradSpec?.copyOf()

    /** 这层还能不能回改渐变：这层之后又被画过就不行了 */
    fun canEditGradient(): Boolean {
        val l = layers.getOrNull(activeIndex) ?: return false
        return l.gradSpec != null && l.gradBase != null && l.gradStamp == pixelEpoch
    }

    /** 拖动结束：把「改渐变」这整段操作压成一条撤销 */
    fun endGradientSpecEdit() {
        gradEditPre = null
        val layer = layers.getOrNull(activeIndex) ?: return
        // 撤销要退回「拉渐变之前」，所以优先用 gradBase，而不是本次拖动开始时的画面
        val pre = layer.gradBase ?: return
        val src = needBitmap(layer) ?: return
        val w = src.width
        val h = src.height
        if (pre.size != w * h) return
        val after = IntArray(w * h)
        src.getPixels(after, 0, w, 0, 0, w, h)
        pushPatch(pre, after, Rect(0, 0, w, h), "改渐变")
        // pushPatch 会把 pixelEpoch +1，这里必须跟它对齐，否则 canEditGradient 立刻变 false（表现就是「改参数没变化」）
        layer.gradStamp = pixelEpoch
    }

    /** 取消这次渐变：整层退回拉渐变之前的像素（可撤销） */
    fun revertGradient(): Boolean {
        val layer = layers.getOrNull(activeIndex) ?: return false
        val src = needBitmap(layer) ?: return false
        val base = layer.gradBase ?: return false
        val w = src.width
        val h = src.height
        if (base.size != w * h) return false
        val pre = IntArray(w * h)
        src.getPixels(pre, 0, w, 0, 0, w, h)
        src.setPixels(base, 0, w, 0, 0, w, h)
        dropPix16(layer)
        pushPatch(pre, base.copyOf(), Rect(0, 0, w, h), "取消渐变")
        layer.gradStamp = pixelEpoch
        gradEditPre = null
        layer.gradSpec = null
        layer.gradBase = null
        layer.gradStamp = -1
        layer.styleVer++
        touchPixels()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 应用这次渐变：保留像素，结束「还能回改颜色」的会话 */
    fun finishGradientEdit() {
        endGradientSpecEdit()
        val layer = layers.getOrNull(activeIndex) ?: return
        layer.gradBase = null
        layer.gradStamp = -1
    }

    /** 改了渐变参数：先还原到画之前的像素，再按新参数整层重画 */
    fun updateGradientSpec(sp: GradientSpec): Boolean {
        val layer = layers.getOrNull(activeIndex) ?: return false
        val src = needBitmap(layer) ?: return false
        val base = layer.gradBase ?: return false
        if (layer.gradStamp != pixelEpoch) return false
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || base.size != w * h) return false
        // 参数没变就别重画（选中图层时 UI 会把当前值回推一次，避免白压一条撤销）
        layer.gradSpec?.let { old ->
            if (old.radial == sp.radial && old.c1 == sp.c1 && old.c2 == sp.c2 &&
                old.x0 == sp.x0 && old.y0 == sp.y0 && old.x1 == sp.x1 && old.y1 == sp.y1 &&
                old.smooth == sp.smooth && old.reverse == sp.reverse && old.scale == sp.scale &&
                old.clip == sp.clip
            ) return true
        }
        if (gradEditPre == null) {
            val p0 = IntArray(w * h)
            src.getPixels(p0, 0, w, 0, 0, w, h)
            gradEditPre = p0
        }
        src.setPixels(base, 0, w, 0, 0, w, h)
        val cached = gradScratch
        val t = if (cached != null && !cached.isRecycled && cached.width == w && cached.height == h) cached else {
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            gradScratch = nb
            nb
        }
        t.eraseColor(0)
        val c = Canvas(t)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = gradientShaderFor(
            sp.radial, sp.x0, sp.y0, sp.x1, sp.y1, sp.c1, sp.c2,
            sp.smooth, sp.reverse, sp.scale,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        if (sp.clip && !sp.radial) clipLinearOutside(t, sp.x0, sp.y0, sp.x1, sp.y1)
        selection?.let { sel ->
            val mp = Paint()
            mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            c.drawBitmap(sel, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), mp)
        }
        Canvas(src).drawBitmap(t, 0f, 0f, Paint())
        layer.gradSpec = sp.copyOf()
        layer.styleVer++
        touchPixels()
        layer.gradStamp = pixelEpoch
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    private fun drawGradientGuide(canvas: Canvas) {
        if (!gradActive) return
        gradGuidePaint.strokeWidth = 2f / viewScale().coerceAtLeast(0.0001f)
        canvas.drawLine(gradX0, gradY0, gradX1, gradY1, gradGuidePaint)
        val r = 5f / viewScale().coerceAtLeast(0.0001f)
        canvas.drawCircle(gradX0, gradY0, r, gradGuidePaint)
        canvas.drawCircle(gradX1, gradY1, r, gradGuidePaint)
    }

    /**
     * 把图层上的智能滤镜栈算成一张位图；没有滤镜、关掉了、或者算不出时返回 null（调用方用原图）。
     * 结果按 pixelId + 栈版本缓存，所以每帧不会重复算。
     */
    private fun smartFilterBitmap(layer: Layer): Bitmap? {
        if (!layer.smartFxOn || layer.smartFx.isEmpty()) return null
        val src = layer.bitmap ?: return null
        val key = layer.pixelId * 31 + layer.smartFxVer
        val cached = layer.smartCache
        if (layer.smartCacheKey == key && cached != null && !cached.isRecycled) {
            return if (cached === src) null else cached
        }
        var cur: Bitmap = src
        for (rec in layer.smartFx) {
            val next = ActionBank.runStep(rec, cur)
            if (next == null) break
            if (next !== cur && cur !== src) cur.recycle()
            cur = next
        }
        val old = layer.smartCache
        if (old != null && old !== cur && old !== src && !old.isRecycled) old.recycle()
        layer.smartCache = cur
        layer.smartCacheKey = key
        return if (cur === src) null else cur
    }

    /** 把「动作录制」里那串步骤套成当前图层的智能滤镜（不改像素），返回套上的个数 */
    fun addSmartFilters(): Int {
        val l = layers.getOrNull(activeIndex) ?: return 0
        val steps = ActionBank.steps.toList()
        if (steps.isEmpty()) return 0
        l.smartFx.clear()
        l.smartFx.addAll(steps)
        l.smartFxOn = true
        l.smartFxVer++
        invalidate()
        onDocChanged?.invoke()
        return l.smartFx.size
    }

    /** 清掉当前图层的智能滤镜（像素本来就没改，清掉就回到原样），返回清掉的个数 */
    fun clearSmartFilters(): Int {
        val l = layers.getOrNull(activeIndex) ?: return 0
        val n = l.smartFx.size
        if (n == 0) return 0
        l.smartFx.clear()
        l.smartFxVer++
        val c = l.smartCache
        if (c != null && !c.isRecycled && c !== l.bitmap) c.recycle()
        l.smartCache = null
        l.smartCacheKey = -1
        invalidate()
        onDocChanged?.invoke()
        return n
    }

    /** 智能滤镜总开关：关掉看原图，开着看效果 */
    fun setSmartFiltersEnabled(on: Boolean): Boolean {
        val l = layers.getOrNull(activeIndex) ?: return false
        if (l.smartFx.isEmpty()) return false
        l.smartFxOn = on
        invalidate()
        return true
    }

    fun smartFiltersEnabled(): Boolean = layers.getOrNull(activeIndex)?.smartFxOn ?: true

    /** 当前图层上的智能滤镜名字（给面板显示） */
    fun smartFilterNames(): List<String> = layers.getOrNull(activeIndex)?.smartFx?.map { it.title } ?: emptyList()

    private fun drawLayerPixels(canvas: Canvas, index: Int, layer: Layer, bmpIn: Bitmap) {
        // 智能滤镜：屏幕、拼合、导出、缩略图都走这里，所以只要换掉这张位图就全都对
        val bmp = smartFilterBitmap(layer) ?: bmpIn
        val filter = channelFilter ?: bitmapPaint.colorFilter
        val preview = previewBitmap
        if (preview != null && index == previewLayerIndex) {
            // 关键：先把整层原样画一遍（在 clip 之外！），预览图只能往上盖，绝不可能掏空画面。
            // 之前是先 clip 再画，导致「范围外」连原层都没画 —— 那就是圈外变透明的真凶。
            val dst0 = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            bitmapPaint.isFilterBitmap = !pixelZoom
            bitmapPaint.alpha = layer.alpha
            canvas.drawBitmap(bmp, null, dst0, bitmapPaint)

            // 有选中范围时，预览也裁到范围里（羽化不参与预览，应用时才是渐变边缘）
            val rg = regionsS.getOrNull(activeRegion)
            val clipped = if (rg != null) {
                canvas.save()
                if (rg.kind == 2) {
                    canvas.clipRect(rg.cx - rg.rx, rg.cy - rg.ry, rg.cx + rg.rx, rg.cy + rg.ry)
                } else {
                    val path = android.graphics.Path()
                    path.addOval(
                        RectF(rg.cx - rg.rx, rg.cy - rg.ry, rg.cx + rg.rx, rg.cy + rg.ry),
                        android.graphics.Path.Direction.CW,
                    )
                    canvas.clipPath(path)
                }
                true
            } else false
            // 预览图一般是降采样后再放大，必须走双线性，否则在 100% 缩放下会出现锯齿
            val dst = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            previewPaint.isFilterBitmap = true
            previewPaint.alpha = 255
            previewPaint.colorFilter = filter
            applyBlendMode(previewPaint, layer.blendMode)
            canvas.drawBitmap(preview, null, dst, previewPaint)
            if (clipped) canvas.restore()
        } else {
            bitmapPaint.isFilterBitmap = !pixelZoom
            bitmapPaint.alpha = layer.alpha
            bitmapPaint.colorFilter = filter
            applyBlendMode(bitmapPaint, layer.blendMode)
            val mesh = if (layer.warpOn) layer.warpPts else null
            if (mesh != null && mesh.size >= 32) {
                val verts = FloatArray(32)
                for (i in 0 until 16) {
                    verts[i * 2] = mesh[i * 2] * bmp.width
                    verts[i * 2 + 1] = mesh[i * 2 + 1] * bmp.height
                }
                canvas.drawBitmapMesh(bmp, 3, 3, verts, 0, null, 0, bitmapPaint)
                return
            }
            // 缩小看整张图时用金字塔里的小图，避免每帧采样全尺寸位图
            val sc = kotlin.math.abs(viewScale())
            val div = when {
                pixelZoom || drawing || zooming || filter != null -> 1
                sc < 0.22f -> 4
                sc < 0.45f -> 2
                else -> 1
            }
            if (div == 1) {
                canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            } else {
                val small = mipFor(layer, bmp, div)
                if (small === bmp) {
                    canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
                } else {
                    val dst = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                    bitmapPaint.isFilterBitmap = true
                    canvas.drawBitmap(small, null, dst, bitmapPaint)
                }
            }
        }
    }


    // ================= 笔迹 =================

    private fun brushPaint(): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = brushSize
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = brushColor
        return paint
    }

    // ---- 笔刷类型与参数 ----
    companion object {
        const val BRUSH_ROUND = 0     // 圆头：软边，最常用
        const val BRUSH_PENCIL = 1    // 铅笔：硬边细，边缘不糊
        const val BRUSH_MARKER = 2    // 马克笔：半透明，叠涂加深
        const val BRUSH_AIRBRUSH = 3  // 喷枪：边缘散点

        /** 钢笔工具的配色：亮蓝线 + 绿色十字 */
        const val POLY_LINE = 0xFF29B6F6.toInt()
        const val POLY_VERTEX = 0xFF3DDC84.toInt()
        const val POLY_FIRST = 0xFFFFD54F.toInt()
        const val BLEND_NORMAL = 0
        const val BLEND_MULTIPLY = 1
        const val BLEND_SCREEN = 2
        const val BLEND_OVERLAY = 3
        const val BLEND_SOFT_LIGHT = 4
        const val BLEND_HARD_LIGHT = 5
        const val BLEND_DARKEN = 6
        const val BLEND_LIGHTEN = 7
        const val BLEND_COLOR_BURN = 8
        const val BLEND_COLOR_DODGE = 9
        const val BLEND_DIFFERENCE = 10
        const val BLEND_EXCLUSION = 11
        const val BLEND_HUE = 12
        const val BLEND_SATURATION = 13
        const val BLEND_COLOR = 14
        const val BLEND_LUMINOSITY = 15

        val BLEND_NAMES = arrayOf(
            "正常", "正片叠底", "滤色", "叠加", "柔光", "强光",
            "变暗", "变亮", "颜色加深", "颜色减淡", "差值", "排除",
            "色相", "饱和度", "颜色", "明度",
        )

    }

    private val brushTypeS = androidx.compose.runtime.mutableIntStateOf(BRUSH_ROUND)
    var brushType: Int
        get() = brushTypeS.intValue
        set(v) { brushTypeS.intValue = v }
    /** 0 = 全软，1 = 全硬 */
    private val brushHardnessS = androidx.compose.runtime.mutableFloatStateOf(0.85f)
    var brushHardness: Float
        get() = brushHardnessS.floatValue
        set(v) { brushHardnessS.floatValue = v }
    /** 每一笔落下的浓度（0.05~1） */
    var brushFlow: Float = 1f
    /** 相邻两个印子的间距，按笔头大小的比例（0.05~1） */
    var brushSpacing: Float = 0.12f

    /** 起收笔变尖的比例（0 = 不变尖，1 = 整笔都在变尖） */
    var brushTaper: Float = 0f

    /** 位置抖动（0~1），抖起来就是喷枪的散点感 */
    var brushJitter: Float = 0f

    /** 散开（0~1），每个印子周围再甩几个小点 */
    var brushScatter: Float = 0f

    /** 笔头圆度（0.2~1），压扁就是扁笔 */
    var brushRoundness: Float = 1f

    /** 笔头角度（0~180 度），配合圆度就是扁笔的倾斜方向 */
    private val brushAngleS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushAngle: Float
        get() = brushAngleS.floatValue
        set(v) { brushAngleS.floatValue = v }
    /** 大小抖动（0~1）：每一下印子随机变小，桌面端的「形状动态·大小抖动」 */
    private val brushSizeJitterS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushSizeJitter: Float
        get() = brushSizeJitterS.floatValue
        set(v) { brushSizeJitterS.floatValue = v }
    /** 角度抖动（0~1）：每一下印子随机转向 */
    private val brushAngleJitterS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushAngleJitter: Float
        get() = brushAngleJitterS.floatValue
        set(v) { brushAngleJitterS.floatValue = v }
    /** 圆度抖动（0~1）：每一下印子的扁度随机 */
    private val brushRoundJitterS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushRoundJitter: Float
        get() = brushRoundJitterS.floatValue
        set(v) { brushRoundJitterS.floatValue = v }
    /** 不透明度抖动（0~1）：每一下印子的浓淡随机，桌面端的「传递·不透明度抖动」 */
    private val brushOpacityJitterS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushOpacityJitter: Float
        get() = brushOpacityJitterS.floatValue
        set(v) { brushOpacityJitterS.floatValue = v }
    /** 手抖平滑（0~1）：0 跟手最紧，1 最稳（桌面端的「平滑」） */
    private val brushSmoothS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushSmooth: Float
        get() = brushSmoothS.floatValue
        set(v) { brushSmoothS.floatValue = v }
    /** 速度粗细分（0~1）：画快了笔迹变细，桌面端笔刷常带的「笔压模拟」 */
    private val brushVelocityS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushVelocity: Float
        get() = brushVelocityS.floatValue
        set(v) { brushVelocityS.floatValue = v }
    /** 流量抖动（0~1）：每一下印子的浓度随机，桌面端「传递」里的流量抖动 */
    private val brushFlowJitterS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushFlowJitter: Float
        get() = brushFlowJitterS.floatValue
        set(v) { brushFlowJitterS.floatValue = v }
    /** 最小直径（0.05~1）：大小抖动时最小能缩到原来的多少，桌面端「形状动态」里那项 */
    private val brushMinDiameterS = androidx.compose.runtime.mutableFloatStateOf(0.1f)
    var brushMinDiameter: Float
        get() = brushMinDiameterS.floatValue
        set(v) { brushMinDiameterS.floatValue = v }
    /** 湿边：每一滴边缘积一圈更浓的色（水彩、马克笔那种边） */
    private val brushWetEdgeS = androidx.compose.runtime.mutableStateOf(false)
    var brushWetEdge: Boolean
        get() = brushWetEdgeS.value
        set(v) { brushWetEdgeS.value = v }
    /** 纹理：-1 没有，其它是 Patterns.all() 的下标，桌面端「纹理」那一栏 */
    private val brushTextureS = androidx.compose.runtime.mutableIntStateOf(-1)
    var brushTexture: Int
        get() = brushTextureS.intValue
        set(v) { brushTextureS.intValue = v }
    /** 用户导入的笔刷纹理（优先于内置图案） */
    var brushTexImage: Bitmap? = null
    /** 自定义纹理的深度（0~1） */
    var brushTexDepth: Float = 1f
    /** 自定义纹理亮度（-1~1） */
    var brushTexBright: Float = 0f
    /** 自定义纹理对比度（-1~1） */
    var brushTexContrast: Float = 0f
    /** 纹理模式：0 正片叠底、1 叠加 */
    private val brushTextureModeS = androidx.compose.runtime.mutableIntStateOf(0)
    var brushTextureMode: Int
        get() = brushTextureModeS.intValue
        set(v) { brushTextureModeS.intValue = v }
    /** 纹理深度（0~1）：0 看不出纹理，1 纹理最重 */
    private val brushTextureDepthS = androidx.compose.runtime.mutableFloatStateOf(0.5f)
    var brushTextureDepth: Float
        get() = brushTextureDepthS.floatValue
        set(v) { brushTextureDepthS.floatValue = v }

    /** 双重画笔（桌面端画笔设置里的「双重画笔」）：第二个笔尖把笔迹啃出空隙 */
    private val brushDualOnS = androidx.compose.runtime.mutableStateOf(false)
    var brushDualOn: Boolean
        get() = brushDualOnS.value
        set(v) { brushDualOnS.value = v }
    private val brushDualSizeS = androidx.compose.runtime.mutableFloatStateOf(0.4f)
    var brushDualSize: Float
        get() = brushDualSizeS.floatValue
        set(v) { brushDualSizeS.floatValue = v }
    private val brushDualCountS = androidx.compose.runtime.mutableIntStateOf(3)
    var brushDualCount: Int
        get() = brushDualCountS.intValue
        set(v) { brushDualCountS.intValue = v }

    private val strokePoints = ArrayList<Float>()
    private val rnd = java.util.Random()
    private val dstRect = android.graphics.RectF()

    /** 速度细分用的当前倍率（1 = 不细分） */
    private var dabSpeedScale = 1f
    /** 同一个移动事件里的多段先攒着，末尾一次合成 */
    private var deferFlush = false
    private var deferRect: Rect? = null
    /** 平滑后的上一个落点（跟手/稳定的取舍） */
    /** 数位板压感：开时按笔的压感缩放笔头；手指默认满压 */
    var brushPressure: Boolean = true
    private var strokePressure = 1f
    /** 上一小段结束时的压感（段间做插值，避免一段粗一段细） */
    private var lastSegPressure = 1f

    /** 压感低通：笔的压感有毛刺，直接照抄会一格一格跳；下压跟得快、回弹跟得慢 */
    private fun smoothPressure(stylus: Boolean, raw: Float): Float {
        if (!stylus) return 1f
        val k = if (raw > strokePressure) 0.75f else 0.45f
        return strokePressure + (raw - strokePressure) * k
    }

    /** 当前压感换算出的笔头缩放（无压感设备/关掉时恒为 1） */
    private fun pressureScale(press: Float = strokePressure): Float {
        if (!brushPressure) return 1f
        val p = press.coerceIn(0f, 1f)
        return 0.18f + 0.82f * p
    }

    private var smoothLastX = 0f
    private var smoothLastY = 0f
    private var smoothInit = false
    private val smoothTmp = android.graphics.PointF()

    /** 桌面端的「平滑」：把手指的抖动滤掉，数值越大越稳、越跟不上手 */
    private fun smoothStrokePoint(x: Float, y: Float): android.graphics.PointF {
        val k = brushSmooth.coerceIn(0f, 1f)
        if (k <= 0.01f || !smoothInit) {
            smoothTmp.set(x, y)
            smoothInit = true
            return smoothTmp
        }
        val follow = 1f - k * 0.88f
        smoothTmp.set(
            smoothTmp.x + (x - smoothTmp.x) * follow,
            smoothTmp.y + (y - smoothTmp.y) * follow,
        )
        return smoothTmp
    }

    private var stampBitmap: Bitmap? = null
    private var stampKey = ""
    /** 盖章用的 Canvas 复用：每段新建一个 Canvas 也是白扔掉的对象 */
    private var stampCanvas: Canvas? = null
    private var stampCanvasOf: Bitmap? = null

    private fun stampCanvasFor(buf: Bitmap): Canvas {
        val c = stampCanvas
        if (c != null && stampCanvasOf === buf) return c
        c?.let { }
        val made = Canvas(buf)
        stampCanvas = made
        stampCanvasOf = buf
        return made
    }
    private var lastStampX = Float.NaN
    private var lastStampY = Float.NaN
    /** 平滑笔迹用的「上一段中点」 */
    private var lastMidX = Float.NaN
    private var lastMidY = Float.NaN

    /** 纯圆硬边、不透明、无抖动/纹理/双笔尖的笔头：可以整段描线，不必密集盖章 */
    private fun canStrokeAsPath(): Boolean =
        brushHardness >= 0.8f && brushScatter < 0.01f && brushJitter < 0.01f &&
            brushAngleJitter < 0.01f && brushColorJitter < 0.01f && brushFlowJitter < 0.01f &&
            kotlin.math.abs(brushAngle) < 0.05f && !brushWetEdge && !brushDualOn &&
            brushRoundness > 0.999f && brushFlow >= 0.98f && brushBlend == BLEND_NORMAL &&
            brushTexture == 0 && brushTaper <= 0.01f

    /** 沿二次贝塞尔盖章，笔迹拐角是圆滑的，不会一段一段有折角 */
    private fun stampQuad(
        buf: Bitmap, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float,
        pa: Float = strokePressure, pb: Float = strokePressure,
    ) {
        val stamp = stampFor() ?: return
        val c = stampCanvasFor(buf)
        // 硬边、不透明、无抖动的笔头，用「描一条圆头线」顶上几十次盖章：
        // 这样弧线是连续的一条，不会像一串圆点拼起来的折线，而且快得多
        if (canStrokeAsPath()) {
            val pp = Paint(Paint.ANTI_ALIAS_FLAG)
            pp.style = Paint.Style.STROKE
            pp.strokeCap = Paint.Cap.ROUND
            pp.strokeJoin = Paint.Join.ROUND
            pp.color = brushColor or 0xFF000000.toInt()
            pp.alpha = (255 * brushFlow.coerceIn(0.02f, 1f)).toInt().coerceIn(6, 255)
            pp.strokeWidth = max(1f, brushSize * (0.5f * (pa + pb)).coerceIn(0.15f, 1.25f))
            val path = android.graphics.Path()
            path.moveTo(x0, y0)
            path.quadTo(cx, cy, x1, y1)
            c.drawPath(path, pp)
            ensureOverlayCanvas()?.drawPath(path, pp)
            lastStampX = x1
            lastStampY = y1
            return
        }
        var spacing = max(max(0.6f, brushSize * brushSpacing.coerceIn(0.02f, 1f) * 0.28f), minSpacingDoc())
        // 大图上把笔头间距抬到笔刷的 9%：一段要盖的次数从十几下降到两三下，
        // 视感上还是一条连续的笔迹（软边笔头之间本来就重叠）
        if (isHugeDoc()) spacing = max(spacing, brushSize * 0.22f)
        val approx = kotlin.math.hypot(cx - x0, cy - y0) + kotlin.math.hypot(x1 - cx, y1 - cy)
        val steps = max(1, (approx / spacing).toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (255 * brushFlow.coerceIn(0.02f, 1f)).toInt().coerceIn(6, 255)
        val jitter = brushJitter.coerceIn(0f, 1f) * brushSize * 0.7f
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val it = 1f - t
            var sx = it * it * x0 + 2f * it * t * cx + t * t * x1
            var sy = it * it * y0 + 2f * it * t * cy + t * t * y1
            if (jitter > 0.01f) {
                sx += rnd.nextFloat() * jitter * 2 - jitter
                sy += rnd.nextFloat() * jitter * 2 - jitter
            }
            // 压感沿这一段线性渐变：以前一个移动事件整段共用一个值，快画时就是一段粗一段细
            dab(c, stamp, sx, sy, 1f, p, pa + (pb - pa) * t)
        }
        lastStampX = x1
        lastStampY = y1
    }

    /** 按当前笔型/大小/硬度生成一个笔头印子（缓存，参数不变就不重画） */
    /** 压感软硬用的笔尖缓存：按「压力档 + 尺寸 + 颜色 + 基础硬度」分开存，最多留 8 个 */
    private val pressStampCache = HashMap<String, Bitmap>()

    private fun pressureStamp(bucket: Int): Bitmap? {
        val size = max(2f, brushSize)
        val key = "$bucket|${size.toInt()}|$brushColor|${(brushHardness * 100).toInt()}"
        pressStampCache[key]?.let { return it }
        val d = max(3, size.toInt() + 2)
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = size / 2f
        val color = brushColor or 0xFF000000.toInt()
        // 档位越低（笔压越小）边缘越散
        val hard = (brushHardness * (0.30f + 0.09f * bucket)).coerceIn(0.02f, 0.95f)
        val cr = Color.red(color)
        val cg = Color.green(color)
        val cb = Color.blue(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val e0 = hard.coerceIn(0.02f, 0.9f)
        p.shader = android.graphics.RadialGradient(
            d / 2f, d / 2f, radius,
            intArrayOf(
                color, color,
                Color.argb(216, cr, cg, cb), Color.argb(153, cr, cg, cb),
                Color.argb(77, cr, cg, cb), Color.argb(26, cr, cg, cb),
                Color.argb(0, cr, cg, cb)
            ),
            floatArrayOf(
                0f, e0,
                e0 + (1f - e0) * 0.30f,
                e0 + (1f - e0) * 0.55f,
                e0 + (1f - e0) * 0.75f,
                e0 + (1f - e0) * 0.90f,
                1f
            ),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawCircle(d / 2f, d / 2f, radius, p)
        if (pressStampCache.size > 24) {
            pressStampCache.values.forEach { it.recycle() }
            pressStampCache.clear()
        }
        pressStampCache[key] = bmp
        return bmp
    }

    private fun stampFor(): Bitmap? {
        val size = max(1f, brushSize)
        val radius = size / 2f
        val key = "${if (brushTexImage != null) "U${(brushTexDepth*100).toInt()},${(brushTexBright*100).toInt()},${(brushTexContrast*100).toInt()}" else ""}$brushType|${size.toInt()}|${(brushHardness * 100).toInt()}|$brushColor|t$brushTexture,$brushTextureMode,${(brushTextureDepth * 100).toInt()}|w${if (brushWetEdge) 1 else 0}|d${if (brushDualOn) 1 else 0},${(brushDualSize * 100).toInt()},$brushDualCount"
        if (key == stampKey) return stampBitmap
        val d = max(2, (radius * 2f).toInt() + 2)
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = d / 2f
        val cy = d / 2f
        val color = brushColor or 0xFF000000.toInt()
        when (brushType) {
            BRUSH_PENCIL -> {
                // 铅笔：1 像素级的硬边线，没有柔化边缘，也不随压力变粗（和画笔的软/硬刷头区分开）
                val p = Paint()
                p.isAntiAlias = false
                p.isDither = false
                p.color = color
                val pr = max(0.6f, radius * 0.42f)
                c.drawCircle(cx, cy, pr, p)
            }
            BRUSH_MARKER -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.argb((90 * brushFlow).toInt().coerceIn(6, 255), Color.red(color), Color.green(color), Color.blue(color))
                c.drawCircle(cx, cy, radius, p)
            }
            BRUSH_AIRBRUSH -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.argb((70 * brushFlow).toInt().coerceIn(6, 255), Color.red(color), Color.green(color), Color.blue(color))
                val rnd = java.util.Random(1234L)
                val dots = (radius * radius / 6f).toInt().coerceIn(24, 900)
                val dotR = max(0.8f, radius * 0.06f)
                for (i in 0 until dots) {
                    val a = rnd.nextDouble() * Math.PI * 2
                    val rr = Math.sqrt(rnd.nextDouble()) * radius * 0.92
                    c.drawCircle((cx + Math.cos(a) * rr).toFloat(), (cy + Math.sin(a) * rr).toFloat(), dotR, p)
                }
            }
            else -> {
                val hard = brushHardness.coerceIn(0f, 1f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                if (brushWetEdge && hard < 0.98f) {
                    // 湿边：中间照常，靠近边缘留一圈更浓更暗的边，水彩/马克笔就是这个观感
                    val rim = Color.argb(
                        255,
                        (Color.red(color) * 0.82f).toInt().coerceIn(0, 255),
                        (Color.green(color) * 0.82f).toInt().coerceIn(0, 255),
                        (Color.blue(color) * 0.82f).toInt().coerceIn(0, 255),
                    )
                    val rimAt = (hard * 0.72f).coerceIn(0.2f, 0.9f)
                    val fadeAt = (rimAt + (1f - rimAt) * 0.35f).coerceIn(rimAt + 0.01f, 0.995f)
                    p.shader = android.graphics.RadialGradient(
                        cx, cy, radius,
                        intArrayOf(color, color, rim, rim, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
                        floatArrayOf(0f, rimAt, rimAt + 0.001f, fadeAt, 1f),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                } else if (hard >= 0.98f) {
                    p.color = color
                } else {
                    // 边缘用多段缓动（桌面端笔尖是平滑的 S 形衰减）：只有两段的话，
                    // 从实心一步跳到透明，画出来发硬、像马克笔，手感也不跟 桌面端
                    val cr = Color.red(color)
                    val cg = Color.green(color)
                    val cb2 = Color.blue(color)
                    val a85 = Color.argb(216, cr, cg, cb2)
                    val a60 = Color.argb(153, cr, cg, cb2)
                    val a30 = Color.argb(77, cr, cg, cb2)
                    val a10 = Color.argb(26, cr, cg, cb2)
                    val e0 = hard.coerceIn(0.02f, 0.9f)
                    p.shader = android.graphics.RadialGradient(
                        cx, cy, radius,
                        intArrayOf(color, color, a85, a60, a30, a10, Color.argb(0, cr, cg, cb2)),
                        floatArrayOf(
                            0f, e0,
                            e0 + (1f - e0) * 0.30f,
                            e0 + (1f - e0) * 0.55f,
                            e0 + (1f - e0) * 0.75f,
                            e0 + (1f - e0) * 0.90f,
                            1f
                        ),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                }
                c.drawCircle(cx, cy, radius, p)
            }
        }
        applyTexture(bmp)
        applyDualTip(bmp)
        stampBitmap?.recycle()
        stampBitmap = bmp
        stampKey = key
        return bmp
    }

    /**
     * 把纹理揉进笔头的透明通道：桌面端的「纹理」就是让笔尖有个底子（布纹、纸纹），
     * 正片叠底 = 纹理暗的地方画不出来；叠加 = 明暗都放大。
     */
    private fun applyTexture(bmp: Bitmap) {
        val img = brushTexImage
        if (img != null) {
            applyCustomTexture(bmp, img)
            return
        }
        val idx = brushTexture
        if (idx < 0) return
        val depth = brushTextureDepth.coerceIn(0f, 1f)
        if (depth <= 0.01f) return
        val pat = Patterns.all().getOrNull(idx)?.second ?: return
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val pw = pat.width
        val ph = pat.height
        val patPx = IntArray(pw * ph)
        pat.getPixels(patPx, 0, pw, 0, 0, pw, ph)
        for (y in 0 until h) {
            val prow = (y % ph) * pw
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val a = (px[i] ushr 24) and 0xFF
                if (a == 0) continue
                val pc = patPx[prow + (x % pw)]
                val lum = (0.299f * Color.red(pc) + 0.587f * Color.green(pc) + 0.114f * Color.blue(pc)) / 255f
                val k = if (brushTextureMode == 1) {
                    if (lum < 0.5f) 2f * lum else 1f - 2f * (1f - lum)
                } else {
                    lum
                }
                val m = (1f - depth) + depth * k
                px[i] = (px[i] and 0x00FFFFFF) or (((a * m).toInt().coerceIn(0, 255)) shl 24)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * 用户导入的纹理：按笔尖大小缩放采样，深度控制「纹理压掉多少浓度」。
     * 和内置图案的区别是不做平铺，整张图铺满笔尖（像 桌面端里把一张图存成图案笔刷）。
     */
    private fun applyCustomTexture(bmp: Bitmap, img: Bitmap) {
        val depth = brushTexDepth.coerceIn(0f, 1f)
        if (depth <= 0.01f) return
        val bright = brushTexBright.coerceIn(-1f, 1f)
        val contrast = brushTexContrast.coerceIn(-1f, 1f)
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0 || img.width <= 0 || img.height <= 0) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = (y.toFloat() / h * img.height).toInt().coerceIn(0, img.height - 1)
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val a = (px[i] ushr 24) and 0xFF
                if (a == 0) continue
                val sx = (x.toFloat() / w * img.width).toInt().coerceIn(0, img.width - 1)
                val pc = img.getPixel(sx, sy)
                val lum = (0.299f * Color.red(pc) + 0.587f * Color.green(pc) + 0.114f * Color.blue(pc)) / 255f
                var k = lum
                // 桌面端的「亮度/对比度」作用在纹理颗粒上
                if (bright != 0f || contrast != 0f) {
                    k = ((k - 0.5f) * (1f + contrast * 2f)) + 0.5f + bright * 0.5f
                }
                if (brushTextureMode == 1) {
                    k = if (k < 0.5f) 2f * k else 1f - 2f * (1f - k)
                }
                k = k.coerceIn(0f, 1f)
                val m = (1f - depth) + depth * k
                px[i] = (px[i] and 0x00FFFFFF) or (((a * m).toInt().coerceIn(0, 255)) shl 24)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * 双重画笔：用第二个笔尖把笔头「啃掉」一块，笔迹中间空、边上碎。
     * 安卓没有 桌面端那种笔尖叠加，这里用「多画几个小圆当遮罩再抠掉」来近似。
     */
    private fun applyDualTip(bmp: Bitmap) {
        if (!brushDualOn) return
        val w = bmp.width
        val h = bmp.height
        if (w <= 2 || h <= 2) return
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mask)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt()
        val r = (min(w, h) / 2f) * brushDualSize.coerceIn(0.1f, 0.95f)
        val n = brushDualCount.coerceIn(1, 8)
        val rndLocal = java.util.Random((w * 31 + h * 17).toLong())
        repeat(n) {
            val cx = rndLocal.nextFloat() * w
            val cy = rndLocal.nextFloat() * h
            mc.drawCircle(cx, cy, r * (0.5f + rndLocal.nextFloat() * 0.8f), p)
        }
        val cc = Canvas(bmp)
        val q = Paint()
        q.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        cc.drawBitmap(mask, 0f, 0f, q)
        mask.recycle()
    }

    /** 沿一段路径按间距盖印子，笔迹才像真的笔 */
    private fun stampLine(buf: Bitmap, ix0: Float, iy0: Float, ix1: Float, iy1: Float, scale: Float = 1f) {
        val stamp = stampFor() ?: return
        val c = stampCanvasFor(buf)
        val spacing = max(max(1f, brushSize * brushSpacing.coerceIn(0.02f, 1f)), minSpacingDoc())
        val dx = ix1 - ix0
        val dy = iy1 - iy0
        val len = kotlin.math.hypot(dx, dy)
        var startS = 0f
        if (!lastStampX.isNaN()) {
            // 接着上一段继续走，别每段都从笔头中心重新开始
            startS = 0f
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (255 * brushFlow.coerceIn(0.02f, 1f)).toInt().coerceIn(6, 255)
        val jitter = brushJitter.coerceIn(0f, 1f) * brushSize * 0.7f
        val scatter = brushScatter.coerceIn(0f, 1f)
        if (len < 1f) {
            dab(c, stamp, ix0, iy0, scale, p)
            if (jitter > 0.01f) dab(c, stamp, ix0 + rnd.nextFloat() * jitter * 2 - jitter, iy0 + rnd.nextFloat() * jitter * 2 - jitter, scale, p)
            lastStampX = ix0
            lastStampY = iy0
            return
        }
        var t = startS
        while (t <= len) {
            val f = t / len
            var sx = ix0 + dx * f
            var sy = iy0 + dy * f
            if (jitter > 0.01f) {
                sx += rnd.nextFloat() * jitter * 2 - jitter
                sy += rnd.nextFloat() * jitter * 2 - jitter
            }
            dab(c, stamp, sx, sy, scale, p)
            if (scatter > 0.01f) {
                val n = (scatter * 3f).toInt()
                for (i in 0 until n) {
                    val ang = rnd.nextFloat() * 6.2831853f
                    val rr = rnd.nextFloat() * brushSize * 0.55f * scatter
                    dab(c, stamp, sx + kotlin.math.cos(ang) * rr, sy + kotlin.math.sin(ang) * rr, scale * (0.55f + rnd.nextFloat() * 0.35f), p)
                }
            }
            t += spacing
        }
        lastStampX = ix1
        lastStampY = iy1
    }

    /** 按笔头圆度压扁后盖一个印子 */
    private fun stampAt(c: Canvas, stamp: Bitmap, x: Float, y: Float, scale: Float, p: Paint) {
        stampAt(c, stamp, x, y, scale, p, brushAngle, brushRoundness)
    }

    /** 带角度/圆度的印子：先转到角度，再按圆度压扁 */
    private fun stampAt(c: Canvas, stamp: Bitmap, x: Float, y: Float, scale: Float, p: Paint, angleDeg: Float, roundness: Float) {
        val w = stamp.width * scale
        val h = stamp.height * scale * roundness.coerceIn(0.2f, 1f)
        val noScale = kotlin.math.abs(w - stamp.width) < 0.6f && kotlin.math.abs(h - stamp.height) < 0.6f &&
            kotlin.math.abs(angleDeg) < 0.05f
        dabCount++
        val t0 = if (drawing) System.nanoTime() else 0L
        if (noScale) {
            // 尺寸没变、没旋转：直接整像素贴，不走缩放采样。
            // 缩放贴一张小图在软件画布上大约 1~1.5ms，一次移动几十个笔头就是几十毫秒（0.67.56 实测盖章 604ms）
            val sx = (x - stamp.width / 2f).toInt().toFloat()
            val sy = (y - stamp.height / 2f).toInt().toFloat()
            c.drawBitmap(stamp, sx, sy, p)
            if (t0 != 0L) stampDocMs += (System.nanoTime() - t0) / 1_000_000L
            val t1 = if (drawing) System.nanoTime() else 0L
            overlayStamp(stamp, sx, sy, 0f, null, p)
            if (t1 != 0L) stampOvMs += (System.nanoTime() - t1) / 1_000_000L
            return
        }
        dstRect.set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
        if (kotlin.math.abs(angleDeg) < 0.05f) {
            c.drawBitmap(stamp, null, dstRect, p)
            if (t0 != 0L) stampDocMs += (System.nanoTime() - t0) / 1_000_000L
            val t1 = if (drawing) System.nanoTime() else 0L
            overlayStamp(stamp, x, y, 0f, dstRect, p)
            if (t1 != 0L) stampOvMs += (System.nanoTime() - t1) / 1_000_000L
        } else {
            c.save()
            c.rotate(angleDeg, x, y)
            c.drawBitmap(stamp, null, dstRect, p)
            c.restore()
            if (t0 != 0L) stampDocMs += (System.nanoTime() - t0) / 1_000_000L
            val t1 = if (drawing) System.nanoTime() else 0L
            overlayStamp(stamp, x, y, angleDeg, dstRect, p)
            if (t1 != 0L) stampOvMs += (System.nanoTime() - t1) / 1_000_000L
        }
    }

    /**
     * 大图落笔：同一个印子再盖一份到「屏幕大小」的预览图上。
     * 预览图直接带 overlayView 矩阵，所以传文档坐标即可；图形由 GPU 在屏幕分辨率上重画，
     * 不会像「从 4096 缓冲里抠一块缩到屏幕」那样缩出锯齿。
     */
    private fun ensureOverlayCanvas(): Canvas? {
        overlayCanvas?.let { return it }
        val ov = strokeOverlay
        if (ov == null || ov.isRecycled) return null
        val cv = Canvas(ov)
        cv.setMatrix(overlayView)
        overlayCanvas = cv
        return cv
    }

    private fun overlayStamp(stamp: Bitmap, x: Float, y: Float, angleDeg: Float, dst: RectF?, p: Paint) {
        if (!deferLive) return
        val cv = ensureOverlayCanvas() ?: return
        if (dst != null) {
            if (kotlin.math.abs(angleDeg) < 0.05f) {
                cv.drawBitmap(stamp, null, dst, p)
            } else {
                cv.save()
                cv.rotate(angleDeg, x, y)
                cv.drawBitmap(stamp, null, dst, p)
                cv.restore()
            }
        } else {
            cv.drawBitmap(stamp, x, y, p)
        }
    }

    /** 一次落印：把「形状动态 + 传递抖动」都算进去，桌面端的画笔就是这么一下一下盖的 */
    private fun dab(c: Canvas, stamp: Bitmap, x: Float, y: Float, scale: Float, p: Paint, press: Float = strokePressure) {
        var s = scale * dabSpeedScale * pressureScale(press)
        // 压感→不透明度：笔压小的时候这一滴更淡
        val pressA = if (brushPressureOpacity) (0.18f + 0.82f * press.coerceIn(0f, 1f)) else 1f
        val sj = brushSizeJitter.coerceIn(0f, 1f)
        if (sj > 0.01f) {
            // 大小抖动：缩到「最小直径」为止，桌面端形状动态就是这么算的
            val minD = brushMinDiameter.coerceIn(0.05f, 1f)
            s *= 1f - sj * rnd.nextFloat() * (1f - minD)
        }
        val aj = brushAngleJitter.coerceIn(0f, 1f)
        val ang = brushAngle + if (aj > 0.01f) (rnd.nextFloat() * 2f - 1f) * 180f * aj else 0f
        // 倾斜笔锋：笔越平，笔尖越扁、越朝倾斜方向（内切圆率用 roundness 直接压扁）
        var tiltAng = 0f
        var tiltRound = 0f
        if (brushTiltShape && strokeTilt > 0.12f) {
            val tn = (strokeTilt / (Math.PI.toFloat() / 2f)).coerceIn(0f, 1f)
            tiltRound = brushTiltAmount.coerceIn(0f, 0.95f) * tn
            tiltAng = if (brushTiltRotate) Math.toDegrees(strokeOrient.toDouble()).toFloat() * brushTiltSpin.coerceIn(0f, 1f) else 0f
        }
        val rj = brushRoundJitter.coerceIn(0f, 1f)
        val round = if (rj > 0.01f) {
            (brushRoundness - rj * rnd.nextFloat() * (brushRoundness - 0.2f)).coerceIn(0.2f, 1f)
        } else brushRoundness
        val roundT = (round - tiltRound * round).coerceIn(0.2f, 1f)
        val angT = ang + tiltAng
        // 压感→软硬：按笔压分 4 档换笔尖（只在圆形画笔上生效，铅笔/喷枪保持原样）
        var useStamp = stamp
        if (brushPressureHardness && (brushType == BRUSH_ROUND || brushType == BRUSH_MARKER)) {
            // 连续分 8 档：以前只有 4 档，笔压一变笔尖软硬就整块跳一下
            val bucket = (press.coerceIn(0f, 1f) * 7.99f).toInt().coerceIn(0, 7)
            useStamp = pressureStamp(bucket) ?: stamp
        }
        val oj = brushOpacityJitter.coerceIn(0f, 1f)
        val fj = brushFlowJitter.coerceIn(0f, 1f)
        // 颜色动态：这一滴在「前景色 → 第二颜色」之间随机取色（桌面端的颜色动态/前景背景抖动）
        var tint: Int? = null
        val cj = brushColorJitter.coerceIn(0f, 1f)
        if (cj > 0.01f) {
            val t = 1f - cj * rnd.nextFloat()
            val c0 = brushColor
            val c1 = brushColor2
            val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t).toInt().coerceIn(0, 255)
            val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t).toInt().coerceIn(0, 255)
            val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t).toInt().coerceIn(0, 255)
            tint = Color.rgb(r, g, b)
            p.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        if (pressA < 0.995f || oj > 0.01f || fj > 0.01f || brushNoise > 0.01f) {
            val base = p.alpha
            var a = base.toFloat() * pressA
            if (oj > 0.01f) a *= 1f - oj * rnd.nextFloat() * 0.9f
            if (fj > 0.01f) a *= 1f - fj * rnd.nextFloat() * 0.9f
            // 杂色：每个笔尖随机掉一点浓度，做出颗粒（桌面端画笔设置里的「杂色」）
            if (brushNoise > 0.01f) a *= 1f - brushNoise.coerceIn(0f, 1f) * rnd.nextFloat() * 0.85f
            p.alpha = a.toInt().coerceIn(3, 255)
            stampAt(c, useStamp, x, y, s, p, angT, roundT)
            p.alpha = base
        } else {
            stampAt(c, useStamp, x, y, s, p, angT, roundT)
        }
        if (tint != null) p.colorFilter = null
    }

    /**
     * 把缓冲层这一块立刻合成进图层，然后把这块缓冲清掉。
     * 画笔/图章以前要等抬手才合成，手指拖的时候看不见笔迹；做成逐段合成就是「画多少出现多少」。
     */
    /** 把矩形向外对齐到 256 分块边界：快照整格还原，重画也必须整格覆盖 */
    private fun tileAlignedRect(bmp: Bitmap, r: Rect): Rect {
        val t = liveTile
        return Rect(
            (r.left / t) * t,
            (r.top / t) * t,
            min(bmp.width, ((r.right + t - 1) / t) * t),
            min(bmp.height, ((r.bottom + t - 1) / t) * t),
        )
    }

    /** 把这块区域「没被画过」的原图存进分块快照（只存一次） */
    private fun liveTileCapture(bmp: Bitmap, r: Rect) {
        val t = liveTile
        var ty = (r.top / t) * t
        while (ty < r.bottom) {
            var tx = (r.left / t) * t
            while (tx < r.right) {
                val w = min(t, bmp.width - tx)
                val h = min(t, bmp.height - ty)
                if (w > 0 && h > 0) {
                    val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                    if (!liveTiles.containsKey(key)) {
                        val arr = IntArray(w * h)
                        bmp.getPixels(arr, 0, w, tx, ty, w, h)
                        liveTiles[key] = arr
                    }
                }
                tx += t
            }
            ty += t
        }
    }

    /** 把这块区域还原成起笔时的样子（分块快照里有的块才还原） */
    private fun liveTileRestore(bmp: Bitmap, r: Rect) {
        val t = liveTile
        var ty = (r.top / t) * t
        while (ty < r.bottom) {
            var tx = (r.left / t) * t
            while (tx < r.right) {
                val w = min(t, bmp.width - tx)
                val h = min(t, bmp.height - ty)
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                val arr = liveTiles[key]
                if (arr != null && w > 0 && h > 0) bmp.setPixels(arr, 0, w, tx, ty, w, h)
                tx += t
            }
            ty += t
        }
    }

    /** 只把 [r] 这一块还原成起笔时样子（快照是整块存的，这里按交集拷回来） */
    private fun liveTileRestoreRect(bmp: Bitmap, r: Rect) {
        val t = liveTile
        var ty = (r.top / t) * t
        while (ty < r.bottom) {
            var tx = (r.left / t) * t
            while (tx < r.right) {
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                val arr = liveTiles[key]
                if (arr != null) {
                    val tw = min(t, bmp.width - tx)
                    val th = min(t, bmp.height - ty)
                    val l = max(r.left, tx)
                    val tp = max(r.top, ty)
                    val rr = min(r.right, tx + tw)
                    val bt = min(r.bottom, ty + th)
                    if (rr > l && bt > tp) {
                        val cw = rr - l
                        val ch = bt - tp
                        val tmp = IntArray(cw * ch)
                        for (yy in 0 until ch) {
                            val si = (tp - ty + yy) * tw + (l - tx)
                            System.arraycopy(arr, si, tmp, yy * cw, cw)
                        }
                        bmp.setPixels(tmp, 0, cw, l, tp, cw, ch)
                    }
                }
                tx += t
            }
            ty += t
        }
    }

    private fun flushStrokeRect(stroke: Stroke, layer: Layer, bmp: Bitmap, rect: Rect) {
        val buf = strokeBuffer ?: return
        val r = Rect(
            max(0, rect.left), max(0, rect.top),
            min(bmp.width, rect.right), min(bmp.height, rect.bottom)
        )
        if (r.width() <= 0 || r.height() <= 0) return
        val live = liveMode
        val w = r.width()
        val h = r.height()
        // 现场模式：先把这一小段还原成起笔状态，再把「整段累积缓冲」盖上去 → 每个像素只被合成一次。
        // 快照仍然按 256 整块存（每块只存一次），但还原只还原这一小段：
        // 以前按整块还原 + 整块重合成，4096 上一次移动就要搬 26 万像素，手指一快就卡
        if (live) {
            liveTileCapture(bmp, r)
            liveTileRestoreRect(bmp, r)
        }
        val before = if (live) null else IntArray(w * h)
        if (before != null) bmp.getPixels(before, 0, w, r.left, r.top, w, h)
        val cv = Canvas(bmp)
        cv.save()
        cv.clipRect(r)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt()
        // 只把 r 这一块当源：以前是 drawBitmap(buf, 0f, 0f) 再 clip，
        // Skia 照样要把整幅 4096x3072 的缓冲读一遍（50MB），一次移动就 20~30ms，笔迹就一段一段出来
        cv.drawBitmap(buf, r, r, p)
        cv.restore()
        if (live) {
            // 现场模式：缓冲一直累积，Patch 和 16 位在抬手时统一补
            touchPixels(r)
        } else {
            val after = IntArray(w * h)
            bmp.getPixels(after, 0, w, r.left, r.top, w, h)
            // 非现场模式：缓冲按段清空，所以每段都要自己记 Patch
            if (before != null && !after.contentEquals(before)) {
                stroke.patches.add(Patch(layer, r.left, r.top, w, h, before, after))
                runCatching { layer.pix16?.applyFrom8Diff(r.left, r.top, w, h, before, after) }
                touchPixels(r)
            }
            val cp = Paint()
            cp.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            Canvas(buf).drawRect(r, cp)
        }
        invalidate()
    }

    /** 抬手时按整笔长度重画一遍，起笔和收笔按 brushTaper 变尖 */
    private fun restampTapered() {
        val buf = strokeBuffer ?: return
        val stamp = stampFor() ?: return
        val n = strokePoints.size / 2
        if (n < 2 || brushTaper <= 0.01f) return
        val lens = FloatArray(n)
        var total = 0f
        for (i in 1 until n) {
            val dx = strokePoints[i * 2] - strokePoints[(i - 1) * 2]
            val dy = strokePoints[i * 2 + 1] - strokePoints[(i - 1) * 2 + 1]
            total += kotlin.math.hypot(dx, dy)
            lens[i] = total
        }
        if (total < 2f) return
        buf.eraseColor(0)
        val c = Canvas(buf)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (255 * brushFlow.coerceIn(0.02f, 1f)).toInt().coerceIn(6, 255)
        val spacing = max(max(1f, brushSize * brushSpacing.coerceIn(0.02f, 1f)), minSpacingDoc())
        val taperSpan = brushTaper.coerceIn(0f, 1f) * 0.45f
        var s = 0f
        var seg = 1
        while (s <= total) {
            while (seg < n - 1 && lens[seg] < s) seg++
            val segStart = lens[seg - 1]
            val segLen = max(0.0001f, lens[seg] - segStart)
            val f = ((s - segStart) / segLen).coerceIn(0f, 1f)
            val x = strokePoints[(seg - 1) * 2] + (strokePoints[seg * 2] - strokePoints[(seg - 1) * 2]) * f
            val y = strokePoints[(seg - 1) * 2 + 1] + (strokePoints[seg * 2 + 1] - strokePoints[(seg - 1) * 2 + 1]) * f
            val t = if (total <= 0f) 1f else s / total
            val sc = when {
                t < taperSpan -> (t / taperSpan).coerceIn(0.06f, 1f)
                t > 1f - taperSpan -> ((1f - t) / taperSpan).coerceIn(0.06f, 1f)
                else -> 1f
            }
            stampAt(c, stamp, x, y, sc, p)
            s += spacing
        }
    }

    /** 重新取样：清掉取样点，下一笔重新点一下取样 */
    fun resetCloneSource() {
        cloneSource = null
        clearCloneState()
        invalidate()
    }

    private fun clearCloneState() {
        invalidateFlatSnapshot()
        cloneSource = null
        cloneSnapshot = null
        cloneLastX = Float.NaN
        cloneLastY = Float.NaN
    }

    /** 所有图层的拍平快照：在后台拍，落笔时先用当前层顶着，不会卡一两百毫秒 */
    private var flatSnapCache: Bitmap? = null
    private var flatSnapJob: Thread? = null

    private fun flatSnapshotOrNull(): Bitmap? {
        val c = flatSnapCache
        if (c != null && !c.isRecycled) return c
        if (flatSnapJob == null) {
            flatSnapJob = Thread {
                val f = runCatching { flatten() }.getOrNull()
                post {
                    if (flatSnapCache !== f) flatSnapCache?.recycle()
                    flatSnapCache = f
                    flatSnapJob = null
                }
            }.also { it.start() }
        }
        return null
    }

    /** 画面变了：把拍平缓存丢掉，下次重新拍 */
    private fun invalidateFlatSnapshot() {
        flatSnapCache?.recycle()
        flatSnapCache = null
    }


    // ---------------- 修复画笔：整条笔迹一次泊松求解 ----------------
    // 拖动时每一下笔触先按「低频搬移」出预览（快）；抬手时对整条笔迹解一次泊松，
    // 边界取落笔前的原图像素、梯度取取样源（Pérez 2003 的无缝克隆），这样没有逐笔触的接缝，
    // 也不会越涂越「抹」。先降到 1/4 分辨率解修正量，再放大贴回，一次 20~40ms。
    private var healCov: ByteArray? = null
    private var healCovW = 0
    private var healCovH = 0
    private var healCovX0 = 0
    private var healCovY0 = 0
    private var healCovX1 = -1
    private var healCovY1 = -1
    private var healSolveOn = false
    /** 覆盖度缓冲相对文档的缩小倍数：超大文档用 2（半分辨率），普通文档 1 */
    private var healCovScale = 1

    private fun resetHealCov(w: Int, h: Int): Boolean {
        // 超大文档不再关掉这个功能：覆盖度降到半分辨率（内存 1/4），
        // 「哪些像素被涂过」这种信息半分辨率完全够，泊松解本来也是在 1/2 网格上做的
        val sc = if (w.toLong() * h > 12_000_000L) 2 else 1
        val cw = (w + sc - 1) / sc
        val ch = (h + sc - 1) / sc
        var buf = healCov
        if (buf == null || healCovW != cw || healCovH != ch) {
            buf = ByteArray(cw * ch)
            healCov = buf
            healCovW = cw
            healCovH = ch
            healCovScale = sc
            clearHealCovExtent(buf)
            return true
        }
        healCovScale = sc
        clearHealCovExtent(buf)
        return true
    }

    private fun clearHealCovExtent(buf: ByteArray) {
        if (healCovX1 >= healCovX0 && healCovY1 >= healCovY0) {
            for (y in healCovY0..healCovY1) {
                java.util.Arrays.fill(buf, y * healCovW + healCovX0, y * healCovW + healCovX1 + 1, 0)
            }
        }
        healCovX0 = healCovW
        healCovY0 = healCovH
        healCovX1 = -1
        healCovY1 = -1
    }

    private fun stampHealCov(cxDoc: Float, cyDoc: Float, rDoc: Float) {
        val buf = healCov ?: return
        val sc = healCovScale
        val cx = cxDoc / sc
        val cy = cyDoc / sc
        val rad = (rDoc / sc).coerceAtLeast(1f)
        val x0 = max(0, (cx - rad).toInt())
        val x1 = min(healCovW - 1, (cx + rad).toInt() + 1)
        val y0 = max(0, (cy - rad).toInt())
        val y1 = min(healCovH - 1, (cy + rad).toInt() + 1)
        if (x1 < x0 || y1 < y0) return
        val inner = rad * 0.70f
        val soft = max(0.001f, rad - inner)
        for (y in y0..y1) {
            val dy = y - cy
            for (x in x0..x1) {
                val dx = x - cx
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (d > rad) continue
                val v = if (d <= inner) 255
                else ((1f - (d - inner) / soft) * 255f).toInt().coerceIn(0, 255)
                val i = y * healCovW + x
                if (v > (buf[i].toInt() and 0xFF)) buf[i] = v.toByte()
            }
        }
        if (x0 < healCovX0) healCovX0 = x0
        if (y0 < healCovY0) healCovY0 = y0
        if (x1 > healCovX1) healCovX1 = x1
        if (y1 > healCovY1) healCovY1 = y1
    }

    /**
     * 对整条笔迹解一次泊松：边界值取 beforeArr（落笔前的原图），内部梯度取取样源。
     * 在 1/4 分辨率上解出「修正量」，再双线性放大、按覆盖度羽化贴回预览。
     */
    private fun solveHealStroke(
        target: Bitmap, rx: Int, ry: Int, rw: Int, rh: Int, beforeArr: IntArray,
        selMask: Bitmap? = null, alphaOnly: Boolean = false,
    ) {
        val cov = healCov ?: return
        if (healCovX1 < healCovX0 || rw <= 0 || rh <= 0) return
        val tw = target.width
        val th = target.height
        val cur = IntArray(rw * rh)
        target.getPixels(cur, 0, rw, rx, ry, rw, rh)

        var hx0 = rw
        var hy0 = rh
        var hx1 = -1
        var hy1 = -1
        val cs0 = healCovScale
        val covW = healCovW
        for (y in 0 until rh) {
            var i = y * rw
            var g = ((y + ry) / cs0) * covW + (rx / cs0)
            for (x in 0 until rw) {
                if ((cov[g].toInt() and 0xFF) > 16) {
                    if (x < hx0) hx0 = x
                    if (x > hx1) hx1 = x
                    if (y < hy0) hy0 = y
                    if (y > hy1) hy1 = y
                }
                i++
                g++
            }
        }
        if (hx1 < hx0) return
        hx0 = max(0, hx0 - 1)
        hy0 = max(0, hy0 - 1)
        hx1 = min(rw - 1, hx1 + 1)
        hy1 = min(rh - 1, hy1 + 1)
        val bw = hx1 - hx0 + 1
        val bh = hy1 - hy0 + 1
        if (bw <= 0 || bh <= 0) return

        // 梯度只需要低频：优先用落笔时拍的 1/8 小图，取点少、也避免纹理高频把方程带偏
        val snap = healBlurSource ?: cloneSnapshot ?: target
        val sw = snap.width
        val sh = snap.height
        val sampScale = if (snap === healBlurSource) 8 else 1
        val cs = 2
        val cw = (bw + cs - 1) / cs
        val chh = (bh + cs - 1) / cs
        val n = cw * chh
        val f = Array(3) { FloatArray(n) }
        val hole = BooleanArray(n)
        val srcR = FloatArray(n)
        val srcG = FloatArray(n)
        val srcB = FloatArray(n)
        val prevR = FloatArray(n)
        val prevG = FloatArray(n)
        val prevB = FloatArray(n)
        val bndR = FloatArray(n)
        val bndG = FloatArray(n)
        val bndB = FloatArray(n)

        for (cj in 0 until chh) {
            for (ci in 0 until cw) {
                val k = cj * cw + ci
                var ar = 0
                var ag = 0
                var ab = 0
                var br = 0
                var bg = 0
                var bb = 0
                var cnt = 0
                var any = false
                for (yy in 0 until cs) {
                    val ly = cj * cs + yy
                    if (ly >= bh) break
                    for (xx in 0 until cs) {
                        val lx = ci * cs + xx
                        if (lx >= bw) break
                        val i = (hy0 + ly) * rw + (hx0 + lx)
                        val c = cur[i]
                        ar += Color.red(c)
                        ag += Color.green(c)
                        ab += Color.blue(c)
                        val b0 = beforeArr[i]
                        br += Color.red(b0)
                        bg += Color.green(b0)
                        bb += Color.blue(b0)
                        val g = ((hy0 + ly + ry) / cs0) * covW + ((hx0 + lx + rx) / cs0)
                        if ((cov[g].toInt() and 0xFF) > 0) any = true
                        cnt++
                    }
                }
                if (cnt == 0) continue
                prevR[k] = ar / cnt.toFloat()
                prevG[k] = ag / cnt.toFloat()
                prevB[k] = ab / cnt.toFloat()
                bndR[k] = br / cnt.toFloat()
                bndG[k] = bg / cnt.toFloat()
                bndB[k] = bb / cnt.toFloat()
                hole[k] = any
                // 取样源：整块笔迹共用一个偏移（cloneDX/DY），保证纹理连贯
                val px = (hx0 + ci * cs + cs / 2 + rx + cloneDX)
                val py = (hy0 + cj * cs + cs / 2 + ry + cloneDY)
                val sx = (px / sampScale).toInt().coerceIn(0, sw - 1)
                val sy = (py / sampScale).toInt().coerceIn(0, sh - 1)
                val sp = snap.getPixel(sx, sy)
                srcR[k] = Color.red(sp).toFloat()
                srcG[k] = Color.green(sp).toFloat()
                srcB[k] = Color.blue(sp).toFloat()
            }
        }

        for (k in 0 until n) {
            if (hole[k]) {
                f[0][k] = prevR[k]
                f[1][k] = prevG[k]
                f[2][k] = prevB[k]
            } else {
                f[0][k] = bndR[k]
                f[1][k] = bndG[k]
                f[2][k] = bndB[k]
            }
        }

        // 高斯-赛德尔：在洞内解 Δf = div(src)，洞外保持边界值（初始值已是原图）
        for (it in 0 until 40) {
            for (cj in 0 until chh) {
                for (ci in 0 until cw) {
                    val k = cj * cw + ci
                    if (!hole[k]) continue
                    var accR = 0f
                    var accG = 0f
                    var accB = 0f
                    var cnt = 0
                    if (ci > 0) {
                        val q = k - 1
                        accR += f[0][q] + (srcR[k] - srcR[q])
                        accG += f[1][q] + (srcG[k] - srcG[q])
                        accB += f[2][q] + (srcB[k] - srcB[q])
                        cnt++
                    }
                    if (ci + 1 < cw) {
                        val q = k + 1
                        accR += f[0][q] + (srcR[k] - srcR[q])
                        accG += f[1][q] + (srcG[k] - srcG[q])
                        accB += f[2][q] + (srcB[k] - srcB[q])
                        cnt++
                    }
                    if (cj > 0) {
                        val q = k - cw
                        accR += f[0][q] + (srcR[k] - srcR[q])
                        accG += f[1][q] + (srcG[k] - srcG[q])
                        accB += f[2][q] + (srcB[k] - srcB[q])
                        cnt++
                    }
                    if (cj + 1 < chh) {
                        val q = k + cw
                        accR += f[0][q] + (srcR[k] - srcR[q])
                        accG += f[1][q] + (srcG[k] - srcG[q])
                        accB += f[2][q] + (srcB[k] - srcB[q])
                        cnt++
                    }
                    if (cnt > 0) {
                        val om = 1.85f
                        f[0][k] += om * (accR / cnt - f[0][k])
                        f[1][k] += om * (accG / cnt - f[1][k])
                        f[2][k] += om * (accB / cnt - f[2][k])
                    }
                }
            }
        }

        // 选区 / 阿尔法锁定：先整行读一次 alpha，再逐像素裁（逐像素 getPixel 太慢）
        val guard = IntArray(bw * bh) { 255 }
        if (selMask != null) {
            for (y in 0 until bh) {
                val yy = hy0 + y + ry
                if (yy < 0 || yy >= selMask.height) {
                    for (x in 0 until bw) guard[y * bw + x] = 0
                    continue
                }
                val sx0 = (hx0 + rx).coerceIn(0, selMask.width)
                val n = min(bw, selMask.width - sx0)
                if (n <= 0) {
                    for (x in 0 until bw) guard[y * bw + x] = 0
                    continue
                }
                val row = IntArray(n)
                selMask.getPixels(row, 0, n, sx0, yy, n, 1)
                for (x in 0 until n) guard[y * bw + x] = Color.alpha(row[x])
            }
        }
        // 修正量 = 泊松解 - 预览（只取洞内的低频差），双线性放大后按覆盖度贴回
        val out = cur.copyOf()
        val inv = 1f / cs
        for (y in 0 until bh) {
            val fy = (y + 0.5f) * inv - 0.5f
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = (fy - y0).coerceIn(0f, 1f)
            val cy0 = y0.coerceIn(0, chh - 1)
            val cy1 = (y0 + 1).coerceIn(0, chh - 1)
            for (x in 0 until bw) {
                val g = ((hy0 + y + ry) / cs0) * covW + ((hx0 + x + rx) / cs0)
                val cvi = cov[g].toInt() and 0xFF
                if (cvi <= 6) continue
                val ga = guard[y * bw + x]
                if (ga <= 0) continue
                val fx = (x + 0.5f) * inv - 0.5f
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = (fx - x0).coerceIn(0f, 1f)
                val cx0 = x0.coerceIn(0, cw - 1)
                val cx1 = (x0 + 1).coerceIn(0, cw - 1)
                val k00 = cy0 * cw + cx0
                val k01 = cy0 * cw + cx1
                val k10 = cy1 * cw + cx0
                val k11 = cy1 * cw + cx1
                val w00 = (1f - tx) * (1f - ty)
                val w01 = tx * (1f - ty)
                val w10 = (1f - tx) * ty
                val w11 = tx * ty
                val kf = cvi / 255f * (guard[y * bw + x] / 255f)
                val i = (hy0 + y) * rw + (hx0 + x)
                if (alphaOnly && Color.alpha(beforeArr[i]) == 0) continue
                val c = out[i]
                val dr = (f[0][k00] - prevR[k00]) * w00 + (f[0][k01] - prevR[k01]) * w01 +
                        (f[0][k10] - prevR[k10]) * w10 + (f[0][k11] - prevR[k11]) * w11
                val dg = (f[1][k00] - prevG[k00]) * w00 + (f[1][k01] - prevG[k01]) * w01 +
                        (f[1][k10] - prevG[k10]) * w10 + (f[1][k11] - prevG[k11]) * w11
                val db = (f[2][k00] - prevB[k00]) * w00 + (f[2][k01] - prevB[k01]) * w01 +
                        (f[2][k10] - prevB[k10]) * w10 + (f[2][k11] - prevB[k11]) * w11
                out[i] = Color.argb(
                    Color.alpha(c),
                    (Color.red(c) + dr * kf).toInt().coerceIn(0, 255),
                    (Color.green(c) + dg * kf).toInt().coerceIn(0, 255),
                    (Color.blue(c) + db * kf).toInt().coerceIn(0, 255)
                )
            }
        }
        target.setPixels(out, 0, rw, rx, ry, rw, rh)
    }

    private fun beginCloneStroke(x: Float, y: Float) {
        val bmp = activeLayer?.bitmap ?: return
        val src = cloneSource ?: return
        // 桌面端的「样本」：当前图层就用本层；所有图层就先把整幅拍平了再取样
        // （拍平放后台；这一笔还没拍好就先用当前层，免得落笔卡住）
        // 第一笔也别拖：小图（<=800 万像素）当场拍平，省得「第一笔只用当前层」这个坑
        cloneSnapshot = if (cloneAllLayers) (flatSnapshotOrNull() ?: run {
            val ab = activeLayer?.bitmap
            if (ab != null && ab.width.toLong() * ab.height <= 8_000_000L) {
                val f = flatten()
                if (f != null) {
                    flatSnapCache?.let { if (it !== f && !it.isRecycled) it.recycle() }
                    flatSnapCache = f
                }
                f
            } else {
                null
            }
        }) else null
        // 修复画笔要「用源的低频（明暗+颜色）修正目标的高频（纹理）」。
        // 以前每次按当前图层现算均值，而当前图层正被这一笔改着 → 自己追自己，涂出来是糊的。
        // 落笔时各拍一张 1/8 小图（不模糊也够淡），之后整笔都从这两张图上取均值
        if (tool == Tool.HEAL) {
            healBlurTarget?.recycle()
            healBlurSource?.recycle()
            healBlurTarget = downscale8(bmp)
            healBlurSource = downscale8(cloneSnapshot ?: bmp)
            // 整条笔迹的覆盖度缓冲（抬手时按它解一次泊松）
            healSolveOn = resetHealCov(bmp.width, bmp.height)
        }
        cloneDX = src.x - x
        cloneDY = src.y - y
        currentStroke = Stroke().also { it.label = "仿制图章" }
        drawing = true
        strokeBoundsValid = false
        strokePoints.clear()
        cloneLastX = x
        cloneLastY = y
        ensureStrokeBuffer(bmp)
    }

    private fun drawCloneSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val buf = strokeBuffer ?: return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        if (!drawing) return
        val r = max(2f, brushSize / 2f)
        val spacing = max(1f, r * 0.22f)
        val dist = hypot((ix1 - ix0).toDouble(), (iy1 - iy0).toDouble()).toFloat()
        val steps = (dist / spacing).toInt() + 1
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            cloneStampAt(buf, bmp, ix0 + (ix1 - ix0) * t, iy0 + (iy1 - iy0) * t, r)
        }
        val pad = r + 2f
        val left = (min(ix0, ix1) - pad).toInt()
        val top = (min(iy0, iy1) - pad).toInt()
        val right = (max(ix0, ix1) + pad).toInt() + 1
        val bottom = (max(iy0, iy1) + pad).toInt() + 1
        if (!strokeBoundsValid) {
            strokeBounds.set(left, top, right, bottom)
            strokeBoundsValid = true
        } else {
            strokeBounds.union(left, top, right, bottom)
        }
        // 图章/修复画笔也改成逐段合成：以前抬手才出整片，看起来就是「不跟手」
        val cl = activeLayer
        val cb = cl?.bitmap
        val cs = currentStroke
        if (cl != null && cb != null && cs != null && selection == null && !maskEditing && !cl.alphaLock) {
            val seg = Rect(
                (min(ix0, ix1) - r - 2f).toInt().coerceAtLeast(0),
                (min(iy0, iy1) - r - 2f).toInt().coerceAtLeast(0),
                (max(ix0, ix1) + r + 2f).toInt().coerceAtMost(cb.width),
                (max(iy0, iy1) + r + 2f).toInt().coerceAtMost(cb.height)
            )
            if (seg.width() > 0 && seg.height() > 0) flushStrokeRect(cs, cl, cb, seg)
        }
        invalidate()
    }

    private fun cloneStampAt(buf: Bitmap, layerBmp: Bitmap, x: Float, y: Float, r: Float) {
        val snap = cloneSnapshot ?: layerBmp
        val d = (r * 2f).toInt().coerceAtLeast(6)
        var dab = cloneDab
        if (dab == null || cloneDabSize != d) {
            dab = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
            cloneDab = dab
            cloneDabSize = d
            cloneDabCanvas = Canvas(dab)
            cloneDabMask = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = RadialGradient(
                    d / 2f, d / 2f, d / 2f,
                    intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                    floatArrayOf(0f, 0.70f, 1f),
                    Shader.TileMode.CLAMP
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            cloneDabPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        }
        val c = cloneDabCanvas ?: return
        val mask = cloneDabMask ?: return
        val paint = cloneDabPaint ?: return
        val sx = x + cloneDX
        val sy = y + cloneDY
        if (tool == Tool.HEAL && healSolveOn) stampHealCov(x, y, r)
        dab.eraseColor(0)
        c.save()
        c.clipRect(0, 0, d, d)
        c.translate(d / 2f - sx, d / 2f - sy)
        if (tool == Tool.HEAL && healSourcePattern) {
            // 修复画笔的「源 = 图案」：不取样画面，直接把当前图案平铺进笔尖
            val pat = Patterns.all().getOrNull(patternIndex)?.second ?: Patterns.all()[0].second
            val pw = pat.width.coerceAtLeast(1)
            val ph = pat.height.coerceAtLeast(1)
            var oy = 0
            while (oy < d + ph) {
                var ox = 0
                while (ox < d + pw) {
                    c.drawBitmap(pat, ox.toFloat(), oy.toFloat(), null)
                    ox += pw
                }
                oy += ph
            }
        } else {
            c.drawBitmap(snap, 0f, 0f, null)
        }
        c.restore()
        c.drawRect(0f, 0f, d.toFloat(), d.toFloat(), mask)

        if (tool == Tool.HEAL) {
            // 「修正半径」（扩散 0~7）放大取样范围：越大取的颜色越平均，接缝越软
            val spread = 1f + healDiffusion.coerceIn(0f, 7f) * 0.45f
            val bt = healBlurTarget
            val bs = healBlurSource
            val srcAvg = if (bs != null) {
                meanColor(bs, sx / 8f, sy / 8f, max(2f, r / 8f * spread))
            } else meanColor(snap, sx, sy, r * spread)
            val dstAvg = if (bt != null) {
                meanColor(bt, x / 8f, y / 8f, max(2f, r / 8f * spread))
            } else meanColor(layerBmp, x, y, r * spread)
            if (srcAvg != null && dstAvg != null) {
                val lim = 90f
                val dr = (dstAvg[0] - srcAvg[0]).coerceIn(-lim, lim)
                val dg = (dstAvg[1] - srcAvg[1]).coerceIn(-lim, lim)
                val db = (dstAvg[2] - srcAvg[2]).coerceIn(-lim, lim)
                paint.colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, dr,
                            0f, 1f, 0f, 0f, dg,
                            0f, 0f, 1f, 0f, db,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            } else {
                paint.colorFilter = null
            }
        } else {
            paint.colorFilter = null
        }
        strokeCanvas(buf).drawBitmap(dab, x - d / 2f, y - d / 2f, paint)
        // 「不透明度」和流量分开：这里按不透明度再把笔尖整体压淡一次
        if (cloneOpacity < 100) {
            val op = cloneOpacity.coerceIn(1, 100) / 100f
            val a = (op * 255f).toInt().coerceIn(1, 255)
            cloneFadePaint.color = android.graphics.Color.argb(a, 0, 0, 0)
            strokeCanvas(buf).drawRect(
                x - d / 2f, y - d / 2f, x + d / 2f, y + d / 2f,
                cloneFadePaint
            )
        }
        cloneLastX = x
        cloneLastY = y
    }

    /** 笔迹缓冲的画布（每支笔都 new 一个 Canvas 很贵，缓存起来） */
    private var strokeBufCanvas: Canvas? = null
    private var strokeBufRef: Bitmap? = null
    private val cloneFadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private fun strokeCanvas(buf: Bitmap): Canvas {
        val c = strokeBufCanvas
        if (c != null && strokeBufRef === buf) return c
        val nc = Canvas(buf)
        strokeBufCanvas = nc
        strokeBufRef = buf
        return nc
    }

    private var healBlurTarget: Bitmap? = null
    private var healBlurSource: Bitmap? = null

    /** 缩到 1/8：既省内存，又天然把高频细节磨掉，只留低频的明暗和颜色 */
    private fun downscale8(src: Bitmap): Bitmap? = runCatching {
        val w = max(1, src.width / 8)
        val h = max(1, src.height / 8)
        Bitmap.createScaledBitmap(src, w, h, true)
    }.getOrNull()

    private fun meanColor(bmp: Bitmap, cx: Float, cy: Float, r: Float): FloatArray? {
        val l = max(0, (cx - r).toInt())
        val t = max(0, (cy - r).toInt())
        val rt = min(bmp.width, (cx + r).toInt())
        val bt = min(bmp.height, (cy + r).toInt())
        val w = rt - l
        val h = bt - t
        if (w < 2 || h < 2) return null
        // 只抽 16 行、每行隔 2 个像素取样：修复画笔每个印子都要算一次均值，全量读会明显拖慢手感
        val row = IntArray(w)
        val ys = max(1, h / 16)
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, l, t + y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val a = (c ushr 24) and 0xFF
                if (a > 8) {
                    sr += android.graphics.Color.red(c)
                    sg += android.graphics.Color.green(c)
                    sb += android.graphics.Color.blue(c)
                    n++
                }
                x += 2
            }
            y += ys
        }
        if (n == 0) return null
        return floatArrayOf((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat())
    }


    private fun drawCloneMarker(canvas: Canvas) {
        if (tool != Tool.CLONE && tool != Tool.HEAL) return
        val s = cloneSource ?: return
        cloneMarkPaint.strokeWidth = 2f / viewScale()
        val d = 9f / viewScale()
        canvas.drawCircle(s.x, s.y, d, cloneMarkPaint)
        canvas.drawLine(s.x - d * 1.6f, s.y, s.x + d * 1.6f, s.y, cloneMarkPaint)
        canvas.drawLine(s.x, s.y - d * 1.6f, s.x, s.y + d * 1.6f, cloneMarkPaint)
    }

    private fun ensureStrokeBuffer(bmp: Bitmap) {
        val buf = strokeBuffer
        if (buf == null || buf.width != bmp.width || buf.height != bmp.height) {
            strokeBuffer = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                // 缩小看的时候不加 mipmap，笔迹边缘会一格一格的锯齿
                runCatching { it.setHasMipMap(true) }
            }
        } else {
            // 只清上一笔画到的那块：整幅 eraseColor 在 4096 上是 50MB memset，每笔一次就掉帧
            clearStrokeBuffer()
            strokeBoundsValid = false
        }
    }

    /** 只清这一笔真正画到的那块，而不是整幅清零（4096 画布整幅一次就是 64MB 的 memset） */
    private fun clearStrokeBuffer() {
        val buf = strokeBuffer ?: return
        if (strokeBoundsValid && !strokeBounds.isEmpty) {
            val l = max(0, strokeBounds.left)
            val t = max(0, strokeBounds.top)
            val rr = min(buf.width, strokeBounds.right)
            val bb = min(buf.height, strokeBounds.bottom)
            if (rr > l && bb > t) {
                val c = Canvas(buf)
                val sc = c.save()
                c.clipRect(l.toFloat(), t.toFloat(), rr.toFloat(), bb.toFloat())
                c.drawColor(0, PorterDuff.Mode.CLEAR)
                c.restoreToCount(sc)
                return
            }
        }
        buf.eraseColor(0)
    }

    /** 当前工具在历史面板里的名字 */
    private fun toolLabel(): String = when (tool) {
        Tool.BRUSH -> "画笔"
        Tool.ERASER -> "橡皮擦"
        Tool.CLONE -> "仿制图章"
        Tool.HEAL -> "修复画笔"
        Tool.DODGE -> "减淡"
        Tool.BURN -> "加深"
        Tool.SMUDGE -> "涂抹"
            Tool.LIQUIFY -> "液化"
            Tool.WARP -> "变形"
            Tool.SHAPE -> "形状"
        Tool.PEN -> "钢笔"
        Tool.VANISH -> "消失点"
        Tool.REPLACE -> "颜色替换画笔"
        Tool.SPONGE -> "海绵"
        Tool.ERASER_BG -> "背景橡皮擦"
        Tool.ERASER_MAGIC -> "魔术橡皮擦"
        Tool.MIXER -> "混合器画笔"
        Tool.PATTERN -> "图案图章"
        Tool.SPOT -> "污点修复画笔"
        Tool.REDEYE -> "红眼工具"
        Tool.HISTORY_BRUSH -> "历史记录画笔"
        Tool.ART_HISTORY -> "历史记录艺术画笔"
        Tool.BUCKET -> "填充"
        Tool.TEXT -> "文字"
        Tool.GRADIENT -> "渐变"
        Tool.SELECT -> "选区"
        Tool.REGION -> "区域范围"
        Tool.MOVE -> "移动"
        Tool.TRANSFORM -> "变换"
        Tool.EYEDROPPER -> "吸管"
    }

    // ============ 污点修复：整条笔迹的 PatchMatch 精修 ============
    // 逐下笔尖只能看「笔尖外一圈」，所以纹理走向经常接不上。抬手时对整条笔迹再算一次：
    // 粗网格上做 PatchMatch（随机初始化 → 邻居传播 → 随机搜），再把结果按覆盖度贴回去。

    private var spotStrokeOn = false
    private var spotCov: ByteArray? = null
    private var spotCW = 0
    private var spotCH = 0
    private var spotScale = 1
    private var spotX0 = Int.MAX_VALUE
    private var spotY0 = Int.MAX_VALUE
    private var spotX1 = -1
    private var spotY1 = -1

    private fun resetSpotCov(w: Int, h: Int): Boolean {
        val sc = if (w.toLong() * h > 12_000_000L) 2 else 1
        val cw = (w + sc - 1) / sc
        val ch = (h + sc - 1) / sc
        val old = spotCov
        if (old == null || spotCW != cw || spotCH != ch) {
            spotCov = ByteArray(cw * ch)
        } else {
            java.util.Arrays.fill(old, 0)
        }
        spotCW = cw
        spotCH = ch
        spotScale = sc
        spotX0 = Int.MAX_VALUE
        spotY0 = Int.MAX_VALUE
        spotX1 = -1
        spotY1 = -1
        return true
    }

    private fun stampSpotCov(cov: IntArray, cw: Int, ch: Int, ox: Int, oy: Int) {
        val buf = spotCov ?: return
        val sc = spotScale
        for (y in 0 until ch) {
            val dy = oy + y
            val cy = dy / sc
            if (cy < 0 || cy >= spotCH) continue
            for (x in 0 until cw) {
                val a = cov[y * cw + x] ushr 24
                if (a <= 8) continue
                val dx = ox + x
                val cx = dx / sc
                if (cx < 0 || cx >= spotCW) continue
                val i = cy * spotCW + cx
                if (a > (buf[i].toInt() and 0xFF)) buf[i] = a.toByte()
                if (dx < spotX0) spotX0 = dx
                if (dy < spotY0) spotY0 = dy
                if (dx > spotX1) spotX1 = dx
                if (dy > spotY1) spotY1 = dy
            }
        }
    }

    /**
     * 对整条污点笔迹做一次 PatchMatch 精修。
     * 只在这次提交的这一片里动手，保证撤销记录覆盖得到改过的每一个像素。
     */
    private fun solveSpotStroke(target: Bitmap, rx: Int, ry: Int, rw: Int, rh: Int) {
        val cov = spotCov ?: return
        if (spotX1 < spotX0) return
        val pad = max(4, (brushSize * 0.5f).toInt() + 2)
        val x0 = max(rx, spotX0 - pad)
        val y0 = max(ry, spotY0 - pad)
        val x1 = min(rx + rw - 1, spotX1 + pad)
        val y1 = min(ry + rh - 1, spotY1 + pad)
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw < 6 || bh < 6) return
        if (bw.toLong() * bh > 4_000_000L) return
        val sc = spotScale
        val pxArr = IntArray(bw * bh)
        target.getPixels(pxArr, 0, bw, x0, y0, bw, bh)
        // 整条笔迹按「累计覆盖」一次性补完：逐段补只会补出月牙，中间看着发虚、边上留笔迹。
        // 长笔迹分块跑（块间叠 4px），不再因为范围大退回逐段补。
        run {
            val tile = 320
            val ov = 4
            var tx = x0
            while (true) {
                val ex = min(x1, tx + tile - 1)
                var ty = y0
                while (true) {
                    val ey = min(y1, ty + tile - 1)
                    val tW = ex - tx + 1
                    val tH = ey - ty + 1
                    val tpx = IntArray(tW * tH)
                    target.getPixels(tpx, 0, tW, tx, ty, tW, tH)
                    val tcov = IntArray(tW * tH)
                    for (y in 0 until tH) {
                        val cy2 = ((ty + y) / sc).coerceIn(0, spotCH - 1)
                        for (x in 0 until tW) {
                            val cx2 = ((tx + x) / sc).coerceIn(0, spotCW - 1)
                            val a2 = cov[cy2 * spotCW + cx2].toInt() and 0xFF
                            if (a2 > 12) tcov[y * tW + x] = Color.argb(a2, 255, 255, 255)
                        }
                    }
                    val healed = ToolFx.spotHeal(
                        tpx, tcov, null, tW, tH,
                        (brushSize / 2f).toInt().coerceIn(2, 40), spotType, spotRing, spotFeather, 20,
                    )
                    target.setPixels(healed, 0, tW, tx, ty, tW, tH)
                    if (ey >= y1) break
                    val nty = ey + 1 - ov
                    if (nty <= ty) break
                    ty = nty
                }
                if (ex >= x1) break
                val ntx = ex + 1 - ov
                if (ntx <= tx) break
                tx = ntx
            }
        }
        if (spotType != 2) return
        val cs = 2
        val cw2 = (bw + cs - 1) / cs
        val ch2 = (bh + cs - 1) / cs
        val n = cw2 * ch2
        val cr = IntArray(n)
        val cg = IntArray(n)
        val cb = IntArray(n)
        val hole = BooleanArray(n)
        var holeCount = 0
        for (cj in 0 until ch2) {
            for (ci in 0 until cw2) {
                val k = cj * cw2 + ci
                var sr = 0
                var sg = 0
                var sb = 0
                var cnt = 0
                var anyHole = false
                for (yy in 0 until cs) {
                    val py = (y0 + cj * cs + yy).coerceIn(y0, y1)
                    for (xx in 0 until cs) {
                        val pxx = (x0 + ci * cs + xx).coerceIn(x0, x1)
                        val c = pxArr[(py - y0) * bw + (pxx - x0)]
                        sr += Color.red(c)
                        sg += Color.green(c)
                        sb += Color.blue(c)
                        cnt++
                        val cvi = (cov[(py / sc) * spotCW + (pxx / sc)].toInt() and 0xFF)
                        if (cvi > 20) anyHole = true
                    }
                }
                cr[k] = sr / cnt
                cg[k] = sg / cnt
                cb[k] = sb / cnt
                hole[k] = anyHole
                if (anyHole) holeCount++
            }
        }
        if (holeCount < 6) return
        val off = IntArray(n) { -1 }
        val rnd = java.util.Random(20240101L + spotX0 * 131L + spotY0 * 17L)
        var guard = 0
        for (k in 0 until n) {
            if (!hole[k]) continue
            while (guard++ < 4000) {
                val s = rnd.nextInt(n)
                if (!hole[s]) {
                    off[k] = s
                    cr[k] = cr[s]
                    cg[k] = cg[s]
                    cb[k] = cb[s]
                    break
                }
            }
        }
        fun cost(tk: Int, sk: Int): Long {
            if (sk < 0 || sk >= n || hole[sk]) return Long.MAX_VALUE
            val tx = tk % cw2
            val ty = tk / cw2
            val sx = sk % cw2
            val sy = sk / cw2
            var acc = 0L
            for (dy in -1..1) {
                val yy = ty + dy
                if (yy < 0 || yy >= ch2) continue
                val ny = sy + dy
                if (ny < 0 || ny >= ch2) continue
                for (dx in -1..1) {
                    val xx = tx + dx
                    if (xx < 0 || xx >= cw2) continue
                    val nx = sx + dx
                    if (nx < 0 || nx >= cw2) continue
                    val nk = ny * cw2 + nx
                    if (hole[nk]) continue
                    val tk2 = yy * cw2 + xx
                    val dr = (cr[tk2] - cr[nk]).toLong()
                    val dg = (cg[tk2] - cg[nk]).toLong()
                    val db = (cb[tk2] - cb[nk]).toLong()
                    acc += dr * dr + dg * dg + db * db
                }
            }
            return acc
        }
        // PatchMatch：奇数轮反向扫描（信息从四个方向传播），随机搜索按半径递减、每档试 4 个候选，共 5 轮
        for (round in 0 until 5) {
            val back = round % 2 == 1
            for (idx in 0 until n) {
                val k = if (back) n - 1 - idx else idx
                if (!hole[k]) continue
                val ci = k % cw2
                val cj = k / cw2
                var best = off[k]
                var bestC = cost(k, best)
                // 传播：正向扫描看左/上，反向扫描看右/下
                val nbX = if (!back) { if (ci > 0) k - 1 else -1 } else { if (ci < cw2 - 1) k + 1 else -1 }
                if (nbX >= 0 && off[nbX] >= 0) {
                    val q = off[nbX] + (if (!back) 1 else -1)
                    val c1 = cost(k, q)
                    if (c1 < bestC) { bestC = c1; best = q }
                }
                val nbY = if (!back) { if (cj > 0) k - cw2 else -1 } else { if (cj < ch2 - 1) k + cw2 else -1 }
                if (nbY >= 0 && off[nbY] >= 0) {
                    val q = off[nbY] + (if (!back) cw2 else -cw2)
                    val c1 = cost(k, q)
                    if (c1 < bestC) { bestC = c1; best = q }
                }
                // 随机搜索：半径从整幅减到 1，每档试 4 次
                var rad = max(cw2, ch2)
                while (rad >= 1) {
                    for (t in 0 until 4) {
                        val cand = rnd.nextInt(n)
                        val c1 = cost(k, cand)
                        if (c1 < bestC) { bestC = c1; best = cand }
                    }
                    rad /= 2
                }
                if (best >= 0 && best < n && !hole[best]) {
                    off[k] = best
                    cr[k] = cr[best]
                    cg[k] = cg[best]
                    cb[k] = cb[best]
                }
            }
        }
        val out = pxArr.copyOf()
        for (y in 0 until bh) {
            val dy = y0 + y
            val cj = (y / cs).coerceIn(0, ch2 - 1)
            for (x in 0 until bw) {
                val dx = x0 + x
                val cvi = cov[(dy / sc) * spotCW + (dx / sc)].toInt() and 0xFF
                if (cvi <= 6) continue
                val ci = (x / cs).coerceIn(0, cw2 - 1)
                val k = cj * cw2 + ci
                if (!hole[k] || off[k] < 0) continue
                val o = out[y * bw + x]
                val kf = cvi / 255f
                out[y * bw + x] = Color.argb(
                    Color.alpha(o),
                    (Color.red(o) + (cr[k] - Color.red(o)) * kf).toInt().coerceIn(0, 255),
                    (Color.green(o) + (cg[k] - Color.green(o)) * kf).toInt().coerceIn(0, 255),
                    (Color.blue(o) + (cb[k] - Color.blue(o)) * kf).toInt().coerceIn(0, 255),
                )
            }
        }
        // 1px 精修：把 2px 粗网格的解放大回原分辨率，再做两轮传播 + 小幅随机搜索，去掉块状感
        run {
            val fw = bw
            val fh = bh
            val fn2 = fw * fh
            val fr = IntArray(fn2)
            val fgc = IntArray(fn2)
            val fbc = IntArray(fn2)
            val fhMask = BooleanArray(fn2)
            for (y in 0 until fh) {
                val gy2 = y0 + y
                for (x in 0 until fw) {
                    val fi = y * fw + x
                    val gx2 = x0 + x
                    val cvi = cov[(gy2 / sc) * spotCW + (gx2 / sc)].toInt() and 0xFF
                    val p = out[fi]
                    fr[fi] = Color.red(p)
                    fgc[fi] = Color.green(p)
                    fbc[fi] = Color.blue(p)
                    fhMask[fi] = cvi > 6
                }
            }
            val fx = IntArray(fn2)
            val fy = IntArray(fn2)
            for (cj in 0 until ch2) {
                for (ci in 0 until cw2) {
                    val k = cj * cw2 + ci
                    val o = off[k]
                    if (o < 0) continue
                    val sx = o % cw2
                    val sy = o / cw2
                    for (yy in 0 until cs) {
                        val y = cj * cs + yy
                        if (y >= fh) continue
                        for (xx in 0 until cs) {
                            val x = ci * cs + xx
                            if (x >= fw) continue
                            val fi = y * fw + x
                            fx[fi] = (sx - ci) * cs
                            fy[fi] = (sy - cj) * cs
                        }
                    }
                }
            }
            fun fcost(fi: Int, dx2: Int, dy2: Int): Long {
                val x = fi % fw
                val y = fi / fw
                val sx = x + dx2
                val sy = y + dy2
                if (sx < 1 || sy < 1 || sx >= fw - 1 || sy >= fh - 1) return Long.MAX_VALUE
                var acc = 0L
                var cnt = 0
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        if (ox == 0 && oy == 0) continue
                        val qx = x + ox
                        val qy = y + oy
                        if (qx < 0 || qy < 0 || qx >= fw || qy >= fh) continue
                        val qi = qy * fw + qx
                        if (fhMask[qi]) continue
                        val tx2 = sx + ox
                        val ty2 = sy + oy
                        if (tx2 < 0 || ty2 < 0 || tx2 >= fw || ty2 >= fh) continue
                        val ti = ty2 * fw + tx2
                        if (fhMask[ti]) return Long.MAX_VALUE
                        acc += kotlin.math.abs(fr[qi] - fr[ti]).toLong() +
                            kotlin.math.abs(fgc[qi] - fgc[ti]).toLong() +
                            kotlin.math.abs(fbc[qi] - fbc[ti]).toLong()
                        cnt++
                    }
                }
                return if (cnt >= 2) acc else Long.MAX_VALUE
            }
            for (round in 0 until 2) {
                val back = round == 1
                for (idx in 0 until fn2) {
                    val fi = if (back) fn2 - 1 - idx else idx
                    if (!fhMask[fi]) continue
                    val x = fi % fw
                    val y = fi / fw
                    var bx = fx[fi]
                    var by = fy[fi]
                    var bc = fcost(fi, bx, by)
                    val nxI = if (!back) { if (x > 0) fi - 1 else -1 } else { if (x < fw - 1) fi + 1 else -1 }
                    if (nxI >= 0 && fhMask[nxI]) {
                        val qx = fx[nxI] + (if (!back) 1 else -1)
                        val c1 = fcost(fi, qx, fy[nxI])
                        if (c1 < bc) { bc = c1; bx = qx; by = fy[nxI] }
                    }
                    val nyI = if (!back) { if (y > 0) fi - fw else -1 } else { if (y < fh - 1) fi + fw else -1 }
                    if (nyI >= 0 && fhMask[nyI]) {
                        val qy = fy[nyI] + (if (!back) 1 else -1)
                        val c1 = fcost(fi, fx[nyI], qy)
                        if (c1 < bc) { bc = c1; bx = fx[nyI]; by = qy }
                    }
                    for (t in 0 until 4) {
                        val jx = bx + rnd.nextInt(9) - 4
                        val jy = by + rnd.nextInt(9) - 4
                        val c1 = fcost(fi, jx, jy)
                        if (c1 < bc) { bc = c1; bx = jx; by = jy }
                    }
                    fx[fi] = bx
                    fy[fi] = by
                    val sx = (x + bx).coerceIn(0, fw - 1)
                    val sy = (y + by).coerceIn(0, fh - 1)
                    val si = sy * fw + sx
                    if (!fhMask[si] && bc != Long.MAX_VALUE) {
                        fr[fi] = fr[si]
                        fgc[fi] = fgc[si]
                        fbc[fi] = fbc[si]
                    }
                }
            }
            for (y in 0 until fh) {
                val gy2 = y0 + y
                for (x in 0 until fw) {
                    val fi = y * fw + x
                    if (!fhMask[fi]) continue
                    val gx2 = x0 + x
                    val cvi = cov[(gy2 / sc) * spotCW + (gx2 / sc)].toInt() and 0xFF
                    if (cvi <= 6) continue
                    val o = out[fi]
                    val kf = cvi / 255f
                    out[fi] = Color.argb(
                        Color.alpha(o),
                        (Color.red(o) + (fr[fi] - Color.red(o)) * kf).toInt().coerceIn(0, 255),
                        (Color.green(o) + (fgc[fi] - Color.green(o)) * kf).toInt().coerceIn(0, 255),
                        (Color.blue(o) + (fbc[fi] - Color.blue(o)) * kf).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
        target.setPixels(out, 0, bw, x0, y0, bw, bh)
    }

    private fun beginStroke() {
        if (isActiveLayerPaintingLocked()) return
        // 图层眼睛关掉时画不出东西，但很多软件只是静默失败，用户会以为丢了笔迹
        val aL = activeLayer
        if (aL != null && !aL.visible) {
            onHint?.invoke("当前图层是隐藏的（眼睛关着），画上去看不见：先在图层面板点开眼睛")
            return
        }
        if (!brushPressure) strokePressure = 1f
        val bmp = activeLayer?.bitmap ?: return
        currentStroke = Stroke().also { it.label = toolLabel() }
        drawing = true
        strokeBoundsValid = false
        lastStampX = Float.NaN
        lastStampY = Float.NaN
        lastMidX = Float.NaN
        lastMidY = Float.NaN
        strokePoints.clear()
        deferFlush = false
        deferRect = null
        smoothInit = false
        smoothLastX = lastImagePoint.x
        smoothLastY = lastImagePoint.y
        dabSpeedScale = 1f
        lastSegPressure = strokePressure
        autoEraseDecided = false
        autoEraseStroke = false
        artSeed = (System.currentTimeMillis() and 0x33000000).toInt()
        // 污点修复「近似匹配」：记一条笔迹的覆盖度，抬手时做一次 PatchMatch 精修
        spotStrokeOn = tool == Tool.SPOT && resetSpotCov(bmp.width, bmp.height)
        // 背景橡皮「连续」：一笔之内自己维护一张连通表
        bgSeedX = -1
        bgSeedY = -1
        bgConn?.let { java.util.Arrays.fill(it, false) }
        // 减淡/加深也要用缓冲层（逐段实时合成），以前漏了这里，导致缓冲是上一笔画笔留下的旧图
        if (tool == Tool.BRUSH || tool == Tool.DODGE || tool == Tool.BURN || isMaskTool(tool)) ensureStrokeBuffer(bmp)
        // 所有「按覆盖度直接改像素」的工具都记一份「这笔里每个像素吃过的最大覆盖度」，
        // 同一像素只按增量算一次：否则反复经过处越叠越狠，接缝处还会出台阶
        strokeMax = if (tool == Tool.DODGE || tool == Tool.BURN || tool == Tool.SPONGE ||
            tool == Tool.ERASER_BG || isMaskTool(tool)
        ) ByteArray(bmp.width * bmp.height) else null
        bgOrigA = if (tool == Tool.ERASER_BG) ByteArray(bmp.width * bmp.height) else null
        // 任何透明度下都要按「起笔快照 + 整段缓冲重合成」：每段各自合成一次的话，
        // 相邻段的软边在接缝处会被合两遍，画弧线就是一圈一圈的箍（用户说的「一节一节」）
        liveTiles.clear()
        liveMode = (tool == Tool.BRUSH || tool == Tool.CLONE || tool == Tool.HEAL) && !maskEditing
        // 落笔期间到底画得多贵，取决于「视口在文档坐标里覆盖多大面积」，不是文档总尺寸：
        // 放大到 300% 时视口只覆盖一小块，直接画图层很快；缩到适应屏幕时整幅都要重采样，图层一多就卡。
        // 所以只要可见文档面积超过约 60 万像素（约等于 800×800），就走 overlay 预览这条路。
        val visArea = if (visibleDoc.isEmpty) pixelCount().toFloat()
        else (visibleDoc.width() * visibleDoc.height()).coerceAtLeast(0f)
        deferLive = tool == Tool.BRUSH && liveMode && selection == null && brushTaper <= 0.01f &&
            brushBlend == BLEND_NORMAL && (activeLayer?.alphaLock != true) &&
            (isHugeDoc() || visArea >= 600_000f)
        if (deferLive) {
            liveMode = false
            strokeOverlay?.let { if (!it.isRecycled) it.recycle() }
            strokeOverlay = null
            overlayCanvas = null
            overlayView.set(viewMatrix)
            runCatching {
                strokeOverlay = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            }.onFailure { strokeOverlay = null }
        }
    }

    /** 画笔：只画进缓冲层，抬手时统一合成，这样不透明度才有意义 */
    private fun drawBrushSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val buf = strokeBuffer ?: return
        // 铅笔的「自动抹除」：这一笔落点本来就有颜色，就把整笔当橡皮擦（桌面端铅笔选项）
        if (brushType == BRUSH_PENCIL && pencilAutoErase) {
            if (!autoEraseDecided) {
                autoEraseDecided = true
                val lb = activeLayer?.let { needBitmap(it) }
                if (lb != null && lb.width > 0 && lb.height > 0) {
                    val px = kotlin.math.round(ix0).toInt().coerceIn(0, lb.width - 1)
                    val py = kotlin.math.round(iy0).toInt().coerceIn(0, lb.height - 1)
                    autoEraseStroke = android.graphics.Color.alpha(lb.getPixel(px, py)) > 8
                }
            }
            if (autoEraseStroke) {
                drawEraserSegment(ix0, iy0, ix1, iy1)
                return
            }
        }
        // 桌面端的笔刷「画得越快越细」：按这一段位移估速度，给后面每个印子乘个缩放
        if (brushVelocity > 0.005f) {
            val d = kotlin.math.hypot(ix1 - ix0, iy1 - iy0)
            val k = (d / 90f).coerceIn(0f, 1f)
            val target = 1f - brushVelocity.coerceIn(0f, 1f) * k * 0.65f
            // 低通过渡：以前每段直接跳到目标值，快慢切换时相邻两段粗细硬接（像两截线拼起来）
            dabSpeedScale += (target - dabSpeedScale) * 0.35f
        } else {
            dabSpeedScale = 1f
        }
        if (strokePoints.isEmpty()) {
            strokePoints.add(ix0)
            strokePoints.add(iy0)
        }
        strokePoints.add(ix1)
        strokePoints.add(iy1)
        // 手指给的是一串折线点，直接连折线会看到一段一段的拐角；用「中点 + 上一点当控制点」
        // 的二次曲线接起来，笔迹就是圆滑的一条，跟 桌面端的压感笔迹观感一致
        if (lastMidX.isNaN()) {
            // 起笔不盖章：以前这里先盖一个满尺寸的印子（笔压还没上来），
            // 抬手后就会在笔迹开头留下一个孤立墨点
            lastMidX = ix0
            lastMidY = iy0
            lastSegPressure = strokePressure
            return
        }
        // 手指每帧只报一个点，位移大了直接连就成了折线；先按 1.2px 细分再走二次曲线，
        // 这样弧线才是一整条顺的，不会出现「一段一段」和「多边形」
        val segLen = kotlin.math.hypot(ix1 - ix0, iy1 - iy0)
        // 大图（>1200 万像素）每帧盖章次数减半：4096 上原来 16px 笔刷每段要盖十几下，
        // 每一下都要读写一趟缓冲，手指一快就跟不上
        val stepLen = if (isHugeDoc()) max(2.0f, brushSize * 0.10f) else max(1.2f, brushSize * 0.06f)
        val n = max(1, (segLen / stepLen).toInt())
        var qpx = ix0
        var qpy = iy0
        // 压感从「上一段结束时的值」过渡到「这一段的值」，整段之间是渐变的
        val pStart = lastSegPressure
        val pEnd = strokePressure
        val __st = System.nanoTime()
        for (i in 1..n) {
            val t = i.toFloat() / n
            val qx = ix0 + (ix1 - ix0) * t
            val qy = iy0 + (iy1 - iy0) * t
            val mx = (qpx + qx) / 2f
            val my = (qpy + qy) / 2f
            stampQuad(
                buf, lastMidX, lastMidY, qpx, qpy, mx, my,
                pStart + (pEnd - pStart) * ((i - 1).toFloat() / n),
                pStart + (pEnd - pStart) * t,
            )
            lastMidX = mx
            lastMidY = my
            qpx = qx
            qpy = qy
        }
        moveStampMs += (System.nanoTime() - __st) / 1_000_000L
        lastSegPressure = pEnd

        val radius = brushSize / 2f + 2f
        val left = (min(ix0, ix1) - radius).toInt()
        val top = (min(iy0, iy1) - radius).toInt()
        val right = (max(ix0, ix1) + radius).toInt() + 1
        val bottom = (max(iy0, iy1) + radius).toInt() + 1
        if (!strokeBoundsValid) {
            strokeBounds.set(left, top, right, bottom)
            strokeBoundsValid = true
        } else {
            strokeBounds.union(left, top, right, bottom)
        }
        // 没有收笔变尖、没有选区/蒙版/透明锁定时，逐段就合成到图层，拖着就能看到笔迹
        val fl = activeLayer
        val fb = fl?.bitmap
        val fs = currentStroke
        if (!deferLive && fl != null && fb != null && fs != null && brushTaper <= 0.01f &&
            !maskEditing && selection == null && !fl.alphaLock
        ) {
            val pad = 2f + brushSize * (brushScatter.coerceIn(0f, 1f) * 0.6f + brushJitter.coerceIn(0f, 1f))
            val seg = Rect(
                (min(ix0, ix1) - brushSize / 2f - pad).toInt().coerceAtLeast(0),
                (min(iy0, iy1) - brushSize / 2f - pad).toInt().coerceAtLeast(0),
                (max(ix0, ix1) + brushSize / 2f + pad).toInt().coerceAtMost(fb.width),
                (max(iy0, iy1) + brushSize / 2f + pad).toInt().coerceAtMost(fb.height)
            )
            if (seg.width() > 0 && seg.height() > 0) {
                if (deferFlush) {
                    // 一个移动事件里的多个采样点先攒着，事件末尾只合成一次：少几次整块还原+合成
                    val d = deferRect
                    if (d == null) deferRect = Rect(seg) else d.union(seg)
                } else {
                    flushStrokeRect(fs, fl, fb, seg)
                }
            }
        }
        // 预览模式下这一帧只有「刚画的这一小段」变了，交给 View 一个脏矩形就够了。
        // 缩到整幅可见时，省下的是「整屏合成缓存 + 整屏预览」的重复采样。
        val dr = if (deferLive) deferRect else null
        if (dr != null && !dr.isEmpty) invalidateDocRect(dr) else invalidate()
    }

    /**
     * 大图落笔：把这一段新笔迹搬到「屏幕大小」的预览位图上，整笔都不写回图层。
     * 只搬这一段 dab 的包围盒（几个像素格子），不是整笔、也不是整个视口——
     * 按视口搬在缩小状态下等于搬 34MB，实测 550ms 一次移动。
     */
    private fun refreshPreviewCrop(l0: Int, t0: Int, r0: Int, b0: Int) {
        val buf = strokeBuffer ?: return
        if (!strokeBoundsValid) return
        if (width <= 0 || height <= 0) return
        var ov = strokeOverlay
        if (ov == null || ov.isRecycled || ov.width != width || ov.height != height) {
            ov?.let { if (!it.isRecycled) it.recycle() }
            ov = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            strokeOverlay = ov
            overlayView.set(viewMatrix)
        }
        val sl = max(0, min(l0, r0) - 2)
        val st = max(0, min(t0, b0) - 2)
        val sr = min(buf.width, max(l0, r0) + 2)
        val sbb = min(buf.height, max(t0, b0) + 2)
        if (sr <= sl || sbb <= st) return
        val f = RectF(sl.toFloat(), st.toFloat(), sr.toFloat(), sbb.toFloat())
        overlayDrawMatrix.set(overlayView)
        overlayDrawMatrix.mapRect(f)
        val dl = f.left.toInt() - 1
        val dt = f.top.toInt() - 1
        val dr = f.right.toInt() + 2
        val db = f.bottom.toInt() + 2
        if (dr <= 0 || db <= 0 || dl >= width || dt >= height) return
        runCatching {
            overlayCopyPaint.isFilterBitmap = true
            Canvas(ov).drawBitmap(buf, Rect(sl, st, sr, sbb), Rect(dl, dt, dr, db), overlayCopyPaint)
        }
    }

    /** 把攒下的这几段一次性合成 */
    private fun flushDeferred() {
        val d = deferRect
        deferRect = null
        if (d == null || d.width() <= 0 || d.height() <= 0) return
        val fl = activeLayer ?: return
        val fb = fl.bitmap ?: return
        val fs = currentStroke ?: return
        flushStrokeRect(fs, fl, fb, d)
    }

    /** 橡皮：直接擦进图层，逐段存快照（擦除不受不透明度控制） */
    // ---------------- 涂抹 / 减淡 / 加深 ----------------
    private var smudgePick: Bitmap? = null
    private val smudgePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 涂抹起笔：先把这一块颜色「抓」起来 */
    private fun beginSmudge(x: Float, y: Float) {
        if (isActiveLayerPaintingLocked()) return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        val r = (brushSize / 2f + 2f).toInt().coerceIn(2, 128)
        val d = r * 2
        val pick = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(pick)
        if (smudgeFinger && tool != Tool.MIXER) {
            c.drawColor(brushColor)
        } else {
            // 「对所有图层取样」：抓的是拍平后的画面，拖出来才是看到的颜色
            // 超大文档（>800 万像素）不拍平：一次要分配几十 MB，起笔就卡一下
            val huge = bmp.width.toLong() * bmp.height > 8_000_000L
            val src = if (smudgeAllLayers && !huge) (flatSnapshotOrNull() ?: bmp) else bmp
            c.drawBitmap(src, -x + r, -y + r, null)
        }
        smudgePick?.recycle()
        smudgePick = pick
    }

    private fun blendPixels(dst: Int, src: Int, a: Float): Int {
        if (a >= 1f) return src
        val ia = 1f - a
        val sa = Color.alpha(src).toFloat()
        val da = Color.alpha(dst).toFloat()
        val oaf = sa * a + da * ia
        if (oaf <= 0.5f) return 0
        val oa = oaf.toInt().coerceIn(0, 255)
        val r = ((Color.red(src) * sa * a + Color.red(dst) * da * ia) / oaf).toInt().coerceIn(0, 255)
        val g = ((Color.green(src) * sa * a + Color.green(dst) * da * ia) / oaf).toInt().coerceIn(0, 255)
        val b = ((Color.blue(src) * sa * a + Color.blue(dst) * da * ia) / oaf).toInt().coerceIn(0, 255)
        return Color.argb(oa, r, g, b)
    }

    /** 按涂抹模式把 src 混到 dst 上（桌面端涂抹工具的「模式」下拉） */
    private fun blendModeColor(dst: Int, src: Int, mode: Int): Int {
        if (mode <= 0) return src
        val dr = Color.red(dst)
        val dg = Color.green(dst)
        val db = Color.blue(dst)
        val sr = Color.red(src)
        val sg = Color.green(src)
        val sb = Color.blue(src)
        when (mode) {
            1 -> return Color.argb(
                Color.alpha(src),
                min(dr, sr), min(dg, sg), min(db, sb)
            )
            2 -> return Color.argb(
                Color.alpha(src),
                max(dr, sr), max(dg, sg), max(db, sb)
            )
        }
        Color.colorToHSV(dst, hsvA)
        Color.colorToHSV(src, hsvB)
        when (mode) {
            3 -> hsvA[0] = hsvB[0]
            4 -> hsvA[1] = hsvB[1]
            5 -> {
                hsvA[0] = hsvB[0]
                hsvA[1] = hsvB[1]
            }
            6 -> hsvA[2] = hsvB[2]
        }
        val out = Color.HSVToColor(Color.alpha(src), hsvA)
        return out
    }

    /** 液化：把画笔圈里的像素按力度推走（推进 / 膨胀 / 收缩），不引入新颜色 */
    private fun drawLiquifySegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val stroke = currentStroke ?: return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        val r = (brushSize / 2f).coerceIn(4f, 300f)
        val d = (r * 2f).toInt().coerceIn(8, 600)
        val spacing = max(2f, r * 0.25f)
        val dx = ix1 - ix0
        val dy = iy1 - iy0
        val dist = kotlin.math.hypot(dx, dy)
        val steps = max(1, (dist / spacing).toInt())
        val len = dist.coerceAtLeast(0.0001f)
        val ux = dx / len
        val uy = dy / len
        val k = (liquifyStrength.coerceIn(0f, 100f) / 100f) * r * 0.55f
        val mode = liquifyMode.coerceIn(0, 2)
        val rr = r * r
        val need = d * d
        if (scratchC.size < need) { scratchC = IntArray(need); scratchD = IntArray(need) }
        val before = scratchC
        val out = scratchD
        for (sIdx in 1..steps) {
            val t = sIdx.toFloat() / steps
            val px = ix0 + dx * t
            val py = iy0 + dy * t
            val left = (px - r).toInt()
            val top = (py - r).toInt()
            val w = min(d, bmp.width - left)
            val h = min(d, bmp.height - top)
            if (left < 0 || top < 0 || w <= 0 || h <= 0) continue
            bmp.getPixels(before, 0, d, left, top, w, h)
            val cx = px - left
            val cy = py - top
            var changed = false
            for (y in 0 until h) {
                val oy = y - cy
                val rowIdx = y * d
                for (x in 0 until w) {
                    val idx = rowIdx + x
                    val ox = x - cx
                    val dd2 = ox * ox + oy * oy
                    if (dd2 >= rr) {
                        out[idx] = before[idx]
                        continue
                    }
                    val fall = 1f - kotlin.math.sqrt(dd2) / r
                    val shape = fall * fall
                    val sx: Float
                    val sy: Float
                    if (mode == 0) {
                        sx = ox - ux * k * shape
                        sy = oy - uy * k * shape
                    } else {
                        val g = (k / r) * shape
                        val f = if (mode == 1) (1f - g) else (1f + g)
                        sx = ox * f
                        sy = oy * f
                    }
                    val wxp = (cx + sx).coerceIn(0f, (w - 1).toFloat())
                    val wyp = (cy + sy).coerceIn(0f, (h - 1).toFloat())
                    val v = bilinear(before, d, h, wxp, wyp, before[idx])
                    out[idx] = v
                    if (v != before[idx]) changed = true
                }
            }
            if (!changed) continue
            bmp.setPixels(out, 0, d, left, top, w, h)
            touchPixels(Rect(left, top, left + w, top + h))
            val pb = IntArray(w * h)
            val po = IntArray(w * h)
            for (y in 0 until h) {
                System.arraycopy(before, y * d, pb, y * w, w)
                System.arraycopy(out, y * d, po, y * w, w)
            }
            stroke.patches.add(Patch(layer, left, top, w, h, pb, po))
            runCatching { layer.pix16?.applyFrom8Diff(left, top, w, h, pb, po) }
        }
        invalidate()
    }


    private fun bilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float, fallback: Int): Int {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        fun ch(c: Int, sh: Int) = (c ushr sh) and 0xFF
        fun mix(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        val a0 = mix(ch(c00, 24), ch(c10, 24), fx); val a1 = mix(ch(c01, 24), ch(c11, 24), fx)
        val r0 = mix(ch(c00, 16), ch(c10, 16), fx); val r1 = mix(ch(c01, 16), ch(c11, 16), fx)
        val g0 = mix(ch(c00, 8), ch(c10, 8), fx); val g1 = mix(ch(c01, 8), ch(c11, 8), fx)
        val b0 = mix(ch(c00, 0), ch(c10, 0), fx); val b1 = mix(ch(c01, 0), ch(c11, 0), fx)
        return (mix(a0, a1, fy) shl 24) or (mix(r0, r1, fy) shl 16) or (mix(g0, g1, fy) shl 8) or mix(b0, b1, fy)
    }

    private fun drawSmudgeSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float, tint: Int = 0, tintK: Float = 0f) {
        val stroke = currentStroke ?: return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        val pick = smudgePick ?: return
        val d = pick.width
        val r = d / 2f
        val spacing = max(1.5f, r * 0.34f)
        val dx = ix1 - ix0
        val dy = iy1 - iy0
        val dist = kotlin.math.hypot(dx, dy)
        val steps = max(1, (dist / spacing).toInt())
        val amount = (smudgeStrength.coerceIn(0.05f, 1f) * brushOpacity.coerceIn(0.05f, 1f))
            .coerceIn(0.05f, 0.95f)
        val rr = r * r
        val need = d * d
        if (scratchA.size < need) {
            scratchA = IntArray(need); scratchB = IntArray(need)
            scratchC = IntArray(need); scratchD = IntArray(need)
        }
        val pickPx = scratchA
        val mixed = scratchB
        val before = scratchC
        val out = scratchD
        java.util.Arrays.fill(mixed, 0, need, 0)
        pick.getPixels(pickPx, 0, d, 0, 0, d, d)
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val px = ix0 + dx * t
            val py = iy0 + dy * t
            val left = (px - r).toInt()
            val top = (py - r).toInt()
            val w = min(d, bmp.width - left)
            val h = min(d, bmp.height - top)
            if (left < 0 || top < 0 || w <= 0 || h <= 0) continue
            bmp.getPixels(before, 0, d, left, top, w, h)
            var changed = false
            for (y in 0 until h) {
                val ddy = y - r
                val rowIdx = y * d
                for (x in 0 until w) {
                    val idx = rowIdx + x
                    val ddx = x - r
                    val fall = (1f - (ddx * ddx + ddy * ddy) / rr).coerceIn(0f, 1f)
                    val a = amount * fall * fall
                    if (a <= 0.004f) {
                        out[idx] = before[idx]
                        continue
                    }
                    val p0 = pickPx[idx]
                    val p = if (tintK > 0.004f) blendPixels(p0, tint, tintK.coerceIn(0f, 1f)) else p0
                    // 只混 RGB，alpha 用目标像素的：以前连 alpha 一起插值，涂着涂着整块变透明
                    val mixedColor = (before[idx] and 0xFF000000.toInt()) or
                        (blendPixels(before[idx], p, a) and 0x00FFFFFF)
                    val v = if (smudgeMode == 0) mixedColor
                    else blendModeColor(before[idx], mixedColor, smudgeMode)
                    out[idx] = v
                    mixed[idx] = blendPixels(mixed[idx], v, 0.72f)
                    if (v != before[idx]) changed = true
                }
            }
            bmp.setPixels(out, 0, d, left, top, w, h)
            pick.setPixels(mixed, 0, d, 0, 0, d, d)
            touchPixels(Rect(left, top, left + w, top + h))
            if (changed) {
                val pb = IntArray(w * h)
                val po = IntArray(w * h)
                for (y in 0 until h) {
                    System.arraycopy(before, y * d, pb, y * w, w)
                    System.arraycopy(out, y * d, po, y * w, w)
                }
                stroke.patches.add(Patch(layer, left, top, w, h, pb, po))
                runCatching { layer.pix16?.applyFrom8Diff(left, top, w, h, pb, po) }
            }
        }
        invalidate()
    }


    /** 减淡 / 加深：直接改亮度（dodge=true 变亮） */
    private var toneDab: Bitmap? = null

    // 涂抹/液化每段要用的临时数组，复用不新建：大笔刷时一块就 1MB+，每段都分配会一直 GC 掉帧
    private var scratchA = IntArray(0)
    private var scratchB = IntArray(0)
    private var scratchC = IntArray(0)
    private var scratchD = IntArray(0)

    /** 把软笔尖的形状累积到 strokeBuffer 的 alpha 上，真正的明暗变化等抬手时一次算完 */
    private fun drawToneSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float, dodge: Boolean) {
        val buf = strokeBuffer ?: return
        val r = (brushSize / 2f).coerceIn(1.5f, 256f)
        val d = (r * 2f).toInt().coerceIn(4, 512)
        var dab = toneDab
        if (dab == null || dab.width != d) {
            dab = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
            val c = Canvas(dab)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(
                d / 2f, d / 2f, d / 2f,
                intArrayOf(0xFFFFFFFFL.toInt(), 0x59000000L.toInt(), 0x29000000, 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(d / 2f, d / 2f, d / 2f, p)
            toneDab = dab
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dx = ix1 - ix0
        val dy = iy1 - iy0
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val spacing = max(1.5f, r * 0.25f)
        val steps = if (dist < 0.01f) 1 else (dist / spacing).toInt() + 1
        for (i in 0..steps) {
            val t = if (steps == 0) 0f else i.toFloat() / steps
            val cx = ix0 + dx * t
            val cy = iy0 + dy * t
            val l = (cx - d / 2f).toFloat()
            val tp = (cy - d / 2f).toFloat()
            buf.let {
                val c = Canvas(it)
                c.drawBitmap(dab, l, tp, paint)
            }
            strokeBounds.union(
                (l).toInt(), (tp).toInt(),
                (l + d).toInt(), (tp + d).toInt()
            )
        }
        strokeBoundsValid = true
        // 以前明暗只画进缓冲层、抬手才算，手指拖着的时候画面完全没反应。
        // 现在每画一段就把这段的覆盖度立刻算进图层，然后清掉这段缓冲（和掩码类工具同一套）
        val lyr = activeLayer
        val lb = lyr?.bitmap
        val st = currentStroke
        if (lyr != null && lb != null && st != null) {
            val pad = 2
            val seg = Rect(
                (min(ix0, ix1) - d / 2f - pad).toInt().coerceAtLeast(0),
                (min(iy0, iy1) - d / 2f - pad).toInt().coerceAtLeast(0),
                (max(ix0, ix1) + d / 2f + pad).toInt().coerceAtMost(lb.width),
                (max(iy0, iy1) + d / 2f + pad).toInt().coerceAtMost(lb.height)
            )
            if (seg.width() > 0 && seg.height() > 0) {
                // 不清缓冲：覆盖度全程累积，配 strokeMax 增量算，接缝处不会出现「算过/没算到」的台阶
                applyToolStrokeRect(st, lyr, lb, seg)
            }
        }
        invalidate()
    }

    /** 抬手时按覆盖度做一次明暗映射：k 只跟覆盖度和亮度带有关，笔迹内部平滑、不会出现笔画边 */

    /** 置入图片：等比缩到画布 90% 以内、居中，作为新图层 */
    fun addImageLayer(src: Bitmap, name: String = "图片"): Boolean {
        val base = layers.firstOrNull()?.let { needBitmap(it) } ?: return false
        val docW = base.width
        val docH = base.height
        val scale = min(docW * 0.9f / src.width, docH * 0.9f / src.height).coerceAtMost(1f)
        val dw = (src.width * scale).toInt().coerceAtLeast(1)
        val dh = (src.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawBitmap(
            Bitmap.createScaledBitmap(src, dw, dh, true),
            (docW - dw) / 2f,
            (docH - dh) / 2f,
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val created = Layer(bmp, name)
        val insertAt = (activeIndex + 1).coerceIn(0, layers.size)
        layers.add(insertAt, created)
        activeIndex = insertAt
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        onActiveLayerChanged?.invoke()
        return true
    }

    private fun drawEraserSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val stroke = currentStroke ?: return
        val layer = activeLayer ?: return
        val doc = needBitmap(layer) ?: return
        // 正在编辑蒙版时，橡皮也是擦蒙版（擦掉 = 遮住），不能动图层像素
        val onMask = maskEditing && layer.mask != null && tool == Tool.BRUSH
        val bmp = if (onMask) layer.mask!! else doc
        // 橡皮擦 / 背景橡皮各有自己的大小，不再跟画笔共用一个 brushSize
        val sz = (if (tool == Tool.ERASER_BG) bgEraserSize else eraserSize).coerceIn(1f, 512f)
        val radius = sz / 2f + 2f
        val left = max(0, (min(ix0, ix1) - radius).toInt())
        val top = max(0, (min(iy0, iy1) - radius).toInt())
        val right = min(bmp.width, (max(ix0, ix1) + radius).toInt() + 1)
        val bottom = min(bmp.height, (max(iy0, iy1) + radius).toInt() + 1)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        val before = IntArray(w * h)
        bmp.getPixels(before, 0, w, left, top, w, h)

        val op = eraserOpacity.coerceIn(0f, 1f)
        val mode = eraserMode.coerceIn(0, 2)
        val hard = eraserHardness.coerceIn(0f, 1f)

        /** 把这一段的形状画到画布上；paint 的颜色/混合模式/不透明度由调用方定 */
        fun stamp(c: Canvas, dx: Float, dy: Float, paint: Paint) {
            val x0 = ix0 + dx
            val y0 = iy0 + dy
            val x1 = ix1 + dx
            val y1 = iy1 + dy
            when (mode) {
                // 铅笔：按笔刷大小画一条不打锯齿的硬边线（以前写死 1 像素，擦出来就是一根头发丝）
                1 -> {
                    paint.isAntiAlias = false
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = sz
                    paint.strokeCap = Paint.Cap.ROUND
                    c.drawLine(x0, y0, x1, y1, paint)
                }
                // 块：沿路径盖方块笔头，直角、按整像素对齐（以前是拿折线描边，接头处过冲/缺口 = 坑洼）
                2 -> {
                    paint.isAntiAlias = false
                    paint.style = Paint.Style.FILL
                    val step = max(1f, sz * 0.3f)
                    val dist = kotlin.math.hypot(x1 - x0, y1 - y0)
                    val n = max(1, (dist / step).toInt())
                    val half = sz / 2f
                    for (i in 0..n) {
                        val t = i.toFloat() / n
                        val ccx = x0 + (x1 - x0) * t
                        val ccy = y0 + (y1 - y0) * t
                        val l = (ccx - half).toInt().toFloat()
                        val tp = (ccy - half).toInt().toFloat()
                        c.drawRect(l, tp, l + sz, tp + sz, paint)
                    }
                }
                // 画笔：软边，硬度用橡皮自己的（以前吃的是画笔的硬度，三种模式看起来一样硬）
                else -> {
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = sz
                    paint.strokeCap = Paint.Cap.ROUND
                    val blur = sz * 0.32f * (1f - hard)
                    paint.maskFilter = if (blur > 0.6f) {
                        android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    } else null
                    c.drawLine(x0, y0, x1, y1, paint)
                }
            }
        }

        fun erasePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            alpha = (op * 255f).toInt().coerceIn(1, 255)
        }

        val src = selection
        when {
            src != null -> {
                // 有选区时橡皮只擦选区里面：先在临时图上画覆盖度，再用选区剪一遍
                val cov = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cc = Canvas(cov)
                stamp(cc, -left.toFloat(), -top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                })
                val mp = Paint(Paint.FILTER_BITMAP_FLAG)
                mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                cc.drawBitmap(src, -left.toFloat(), -top.toFloat(), mp)
                Canvas(bmp).drawBitmap(cov, left.toFloat(), top.toFloat(), erasePaint())
                cov.recycle()
            }
            eraseToHistory -> {
                // 抹到历史记录：不是擦成透明，而是把「上一步的历史状态」刷回笔画底下（桌面端的橡皮擦选项）
                val snap = historySnap
                if (snap == null) {
                    stamp(Canvas(bmp), 0f, 0f, erasePaint())
                } else {
                    val cov = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val cc = Canvas(cov)
                    stamp(cc, -left.toFloat(), -top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                    })
                    val hs = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val hc = Canvas(hs)
                    hc.drawBitmap(snap, -left.toFloat(), -top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                    val hp = Paint(Paint.FILTER_BITMAP_FLAG)
                    hp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    hc.drawBitmap(cov, 0f, 0f, hp)
                    cov.recycle()
                    Canvas(bmp).drawBitmap(hs, left.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                    hs.recycle()
                }
            }
            else -> stamp(Canvas(bmp), 0f, 0f, erasePaint())
        }

        val after = IntArray(w * h)
        bmp.getPixels(after, 0, w, left, top, w, h)
        stroke.patches.add(Patch(layer, left, top, w, h, before, after, onMask))
        invalidate()
    }

    // ---------------- 桌面端同款工具（画笔那一组） ----------------
    /** 颜色替换 / 魔术橡皮 / 背景橡皮 的容差 */
    private val fxToleranceS = androidx.compose.runtime.mutableIntStateOf(32)
    var fxTolerance: Int
        get() = fxToleranceS.intValue
        set(v) { fxToleranceS.intValue = v }
    /** 颜色替换的模式：0 颜色 / 1 色相 / 2 饱和度 / 3 亮度（桌面端的「模式」下拉） */
    private val replaceModeS = androidx.compose.runtime.mutableIntStateOf(0)
    var replaceMode: Int
        get() = replaceModeS.intValue
        set(v) { replaceModeS.intValue = v }
    /** 海绵：0 去色 1 加色 */
    private val spongeModeS = androidx.compose.runtime.mutableIntStateOf(0)
    var spongeMode: Int
        get() = spongeModeS.intValue
        set(v) { spongeModeS.intValue = v }
    /** 海绵强度 0.1~1，默认偏弱：以前固定 1.0，一点就把饱和度拉爆 */
    private val spongeStrengthS = androidx.compose.runtime.mutableFloatStateOf(0.45f)
    var spongeStrength: Float
        get() = spongeStrengthS.floatValue
        set(v) { spongeStrengthS.floatValue = v }
    /** 压感→不透明度（桌面端的「传递·不透明度」）：笔压小则这一滴更淡 */
    var brushPressureOpacity: Boolean = false
    /** 压感→软硬：笔压小的时候笔尖更散（桌面端的「传递·硬度」） */
    var brushPressureHardness: Boolean = false
    /** 倾斜笔锋：数位笔倾斜时笔尖压扁并朝倾斜方向（桌面端的「倾斜」形状动态） */
    var brushTiltShape: Boolean = true
    /** 倾斜压扁强度 0~1（越大越扁） */
    var brushTiltAmount: Float = 0.62f
    /** 笔尖是否跟着笔的朝旋转（关=只压扁不转） */
    var brushTiltRotate: Boolean = true
    /** 旋转跟随强度 0~1（1=完全跟着笔的朝向转） */
    var brushTiltSpin: Float = 1f
    /** 背景橡皮取样：0 连续 1 一次 2 背景色板 */
    private val bgSampleModeS = androidx.compose.runtime.mutableIntStateOf(1)
    var bgSampleMode: Int
        get() = bgSampleModeS.intValue
        set(v) { bgSampleModeS.intValue = v }
    /** 背景橡皮限制：0 连续 1 不连续 2 查找边缘 */
    private val bgLimitS = androidx.compose.runtime.mutableIntStateOf(1)
    var bgLimit: Int
        get() = bgLimitS.intValue
        set(v) { bgLimitS.intValue = v }

    /** 背景橡皮「查找边缘」的阈值：越大越难停下（越接近不判边缘） */
    private val bgEdgeS = androidx.compose.runtime.mutableFloatStateOf(20f)
    var bgEdge: Float
        get() = bgEdgeS.floatValue
        set(v) { bgEdgeS.floatValue = v }
    /** 颜色替换取样：0 连续 1 一次 2 背景色板 */
    private val replaceSampleS = androidx.compose.runtime.mutableIntStateOf(1)
    var replaceSample: Int
        get() = replaceSampleS.intValue
        set(v) { replaceSampleS.intValue = v }
    /** 背景橡皮：保护前景色 */
    private val bgProtectS = androidx.compose.runtime.mutableStateOf(true)
    var bgProtect: Boolean
        get() = bgProtectS.value
        set(v) { bgProtectS.value = v }
    /** 混合器画笔：载入量 / 混合量 */
    private val mixerLoadS = androidx.compose.runtime.mutableFloatStateOf(0.35f)
    var mixerLoad: Float
        get() = mixerLoadS.floatValue
        set(v) { mixerLoadS.floatValue = v }
    private val mixerMixS = androidx.compose.runtime.mutableFloatStateOf(0.45f)
    var mixerMix: Float
        get() = mixerMixS.floatValue
        set(v) { mixerMixS.floatValue = v }
    /** 混合器「带多少前景色」0..1：0 只抹开画面颜色，1 就是拿前景色去抹（桌面端的对应滑杆） */
    private val mixerFgS = androidx.compose.runtime.mutableFloatStateOf(0.12f)
    var mixerFg: Float
        get() = mixerFgS.floatValue
        set(v) { mixerFgS.floatValue = v }
    private val mixerState = floatArrayOf(255f, 255f, 255f)
    /** 图案图章：图案序号与是否对齐 */
    private val patternIndexS = androidx.compose.runtime.mutableIntStateOf(0)
    var patternIndex: Int
        get() = patternIndexS.intValue
        set(v) { patternIndexS.intValue = v }
    private val patternAlignedS = androidx.compose.runtime.mutableStateOf(true)
    var patternAligned: Boolean
        get() = patternAlignedS.value
        set(v) { patternAlignedS.value = v }
    private var patternOrigin = PointF(0f, 0f)
    /** 艺术历史记录画笔：样式 0..9 与保真度 */
    private val artStyleS = androidx.compose.runtime.mutableIntStateOf(2)
    var artStyle: Int
        get() = artStyleS.intValue
        set(v) { artStyleS.intValue = v }
    private val artFidelityS = androidx.compose.runtime.mutableIntStateOf(50)
    var artFidelity: Int
        get() = artFidelityS.intValue
        set(v) { artFidelityS.intValue = v }
    /** 红眼：瞳孔大小 / 变暗量 */
    private val redeyePupilS = androidx.compose.runtime.mutableFloatStateOf(0.5f)
    var redeyePupil: Float
        get() = redeyePupilS.floatValue
        set(v) { redeyePupilS.floatValue = v }
    private val redeyeDarkenS = androidx.compose.runtime.mutableFloatStateOf(0.6f)
    var redeyeDarken: Float
        get() = redeyeDarkenS.floatValue
        set(v) { redeyeDarkenS.floatValue = v }
    /** 魔术橡皮：连续擦除 */
    private val magicContinuousS = androidx.compose.runtime.mutableStateOf(true)
    var magicContinuous: Boolean
        get() = magicContinuousS.value
        set(v) { magicContinuousS.value = v }
    /** 消除锯齿：擦掉的边沿留一点半透明，不是硬切 */
    private val magicAntialiasS = androidx.compose.runtime.mutableStateOf(true)
    var magicAntialias: Boolean
        get() = magicAntialiasS.value
        set(v) { magicAntialiasS.value = v }
    /** 对所有图层取样 */
    private val magicAllLayersS = androidx.compose.runtime.mutableStateOf(false)
    var magicAllLayers: Boolean
        get() = magicAllLayersS.value
        set(v) { magicAllLayersS.value = v }
    private var magicAll: Boolean = false
    /** 橡皮擦：抹到历史记录（不擦掉，而是把笔下的历史状态刷回来） */
    var eraseToHistory: Boolean = false
    /** 减淡 / 加深：保护色调（跳过有色彩倾向的皮肤、肤色区域） */
    private val toneProtectS = androidx.compose.runtime.mutableStateOf(false)
    var toneProtect: Boolean
        get() = toneProtectS.value
        set(v) { toneProtectS.value = v }
    /** 画笔：杂色（每个笔尖随机降低一点浓淡，做出颗粒感） */
    private val brushNoiseS = androidx.compose.runtime.mutableFloatStateOf(0f)
    var brushNoise: Float
        get() = brushNoiseS.floatValue
        set(v) { brushNoiseS.floatValue = v }
    /** 图案图章：印象派效果（每次取样点随机偏移，图案不会整齐对齐） */
    private val patternImpressionistS = androidx.compose.runtime.mutableStateOf(false)
    var patternImpressionist: Boolean
        get() = patternImpressionistS.value
        set(v) { patternImpressionistS.value = v }
    /** 艺术历史记录画笔的区域：0 整个文档（拍平） 1 仅当前图层 */
    private val artRegionS = androidx.compose.runtime.mutableIntStateOf(1)
    var artRegion: Int
        get() = artRegionS.intValue
        set(v) { artRegionS.intValue = v }
    /** 修复画笔：源用图案（桌面端修复画笔工具源码选「图案」） */
    var healSourcePattern: Boolean = false
    /** 铅笔：自动抹除（这一笔落在已有颜色上时，整笔当橡皮用） */
    private val pencilAutoEraseS = androidx.compose.runtime.mutableStateOf(false)
    var pencilAutoErase: Boolean
        get() = pencilAutoEraseS.value
        set(v) { pencilAutoEraseS.value = v }
    /** 颜色替换的限制：0 连续 1 不连续 2 查找边缘 */
    private val replaceLimitS = androidx.compose.runtime.mutableIntStateOf(1)
    var replaceLimit: Int
        get() = replaceLimitS.intValue
        set(v) { replaceLimitS.intValue = v }
    /** 混合器湿度：0 干 1 湿 2 非常湿 */
    private val mixerWetS = androidx.compose.runtime.mutableIntStateOf(1)
    var mixerWet: Int
        get() = mixerWetS.intValue
        set(v) { mixerWetS.intValue = v }
    /** 橡皮擦模式：0 画笔 1 铅笔 2 块 */
    /** 橡皮擦自己的大小 / 硬度 / 不透明度：不跟画笔共用（用户要求每个工具独立） */
    private val eraserSizeS = androidx.compose.runtime.mutableFloatStateOf(48f)
    var eraserSize: Float
        get() = eraserSizeS.floatValue
        set(v) { eraserSizeS.floatValue = v }
    private val eraserHardnessS = androidx.compose.runtime.mutableFloatStateOf(0.85f)
    var eraserHardness: Float
        get() = eraserHardnessS.floatValue
        set(v) { eraserHardnessS.floatValue = v }
    private val eraserOpacityS = androidx.compose.runtime.mutableFloatStateOf(1f)
    var eraserOpacity: Float
        get() = eraserOpacityS.floatValue
        set(v) { eraserOpacityS.floatValue = v }
    /** 背景橡皮自己的大小 / 硬度 */
    private val bgEraserSizeS = androidx.compose.runtime.mutableFloatStateOf(60f)
    var bgEraserSize: Float
        get() = bgEraserSizeS.floatValue
        set(v) { bgEraserSizeS.floatValue = v }
    private val bgEraserHardnessS = androidx.compose.runtime.mutableFloatStateOf(0.85f)
    var bgEraserHardness: Float
        get() = bgEraserHardnessS.floatValue
        set(v) { bgEraserHardnessS.floatValue = v }

    /** 橡皮擦模式用 Compose 状态包一层：普通 var 面板读不到变化，点了 chip 不变蓝 */
    private val eraserModeState = androidx.compose.runtime.mutableIntStateOf(0)
    var eraserMode: Int
        get() = eraserModeState.intValue
        set(v) {
            eraserModeState.intValue = v
            invalidate()
        }
    /** 涂抹：对所有图层取样 */
    var smudgeAllLayers: Boolean = false
    /** 海绵：喷枪式累积（同一处刷久了更明显） */
    private val spongeAirbrushS = androidx.compose.runtime.mutableStateOf(false)
    var spongeAirbrush: Boolean
        get() = spongeAirbrushS.value
        set(v) { spongeAirbrushS.value = v }
    /** 仿制图章 / 图案图章：不透明度（和流量分开） */
    private val cloneOpacityS = androidx.compose.runtime.mutableIntStateOf(100)
    var cloneOpacity: Int
        get() = cloneOpacityS.intValue
        set(v) { cloneOpacityS.intValue = v }
    /** 历史记录画笔来源：0 上一状态 1 打开文档时的状态 */
    private val historySourceS = androidx.compose.runtime.mutableIntStateOf(0)
    var historySource: Int
        get() = historySourceS.intValue
        set(v) { historySourceS.intValue = v }
    private var initialSnap: Bitmap? = null
    private var autoEraseStroke: Boolean = false
    private var autoEraseDecided: Boolean = false
    private var bgTarget: Int = 0
    /** 艺术历史画笔：这一笔的固定种子（以前每段都取系统时间，颗粒每段都在跳） */
    private var artSeed: Int = 1
    private var maskDab: Bitmap? = null
    private var historySnap: Bitmap? = null
    private var redeyeStart = PointF(0f, 0f)

    private fun isMaskTool(t: Tool): Boolean = when (t) {
        Tool.REPLACE, Tool.SPONGE,
        Tool.PATTERN, Tool.SPOT, Tool.HISTORY_BRUSH, Tool.ART_HISTORY -> true
        else -> false
    }

    /** 像素工具起笔：记下取样色、图案原点等 */
    private fun prepareMaskTool(x: Float, y: Float) {
        clearStrokeBuffer()
        val bmp = activeLayer?.bitmap
        if (bmp != null) {
            val ix = x.toInt().coerceIn(0, bmp.width - 1)
            val iy = y.toInt().coerceIn(0, bmp.height - 1)
            // 落在透明像素上时取样色是全透明，替换算法会「什么都没换」——往四周找一个不透明的再取
            var c = bmp.getPixel(ix, iy)
            if (Color.alpha(c) == 0) {
                var found = 0
                var step = 2
                while (found == 0 && step <= 24) {
                    for (dy in -step..step step 2) {
                        for (dx in -step..step step 2) {
                            val px2 = (ix + dx).coerceIn(0, bmp.width - 1)
                            val py2 = (iy + dy).coerceIn(0, bmp.height - 1)
                            val t = bmp.getPixel(px2, py2)
                            if (Color.alpha(t) > 8) { found = t; break }
                        }
                        if (found != 0) break
                    }
                    step += 2
                }
                if (found != 0) c = found
            }
            bgTarget = c
            replaceSeedX = ix
            replaceSeedY = iy
        }
        replaceConn = null
        patternOrigin = PointF(x, y)
        // 颜色替换「限制＝连续」：以前起笔就把落点那片连通区一次算死，笔走到第二片同色区域就不管了
        // （用户报的「第二遍等于红换红」）。现在不预计算，跟着笔触按容差逐像素判，
        // 刚涂上去的前景色和取样色差得远，自然不会再把新颜色当成目标再换一遍
    }

    /** 颜色替换「连续」用的连通图：这一笔里已经被替换过的像素 */
    private var replaceConn: BooleanArray? = null

    /** 颜色替换的落点（文档坐标），连通扩张的种子 */
    private var replaceSeedX = -1
    private var replaceSeedY = -1

    /**
     * 颜色替换「限制=连续」的矩形连通遮罩。
     * 种子 = 落点 + 这一笔已经换过的像素；只在矩形里按颜色容差向四邻扩开，
     * 所以笔刷里万一压着一块别的小色块（不连着），它不会被一起换掉。
     * 返回 null 表示不限制（不连续 / 查找边缘走别的判据）。
     */
    private var bgConn: BooleanArray? = null
    /** 背景橡皮这一笔里每个像素「下笔前」的 alpha，配合最大覆盖度一次性算目标值（避免一串月牙） */
    private var bgOrigA: ByteArray? = null
    private var bgSeedX = -1
    private var bgSeedY = -1

    /**
     * 背景橡皮的「限制」：0 连续（从落点沿同色扩开）、1 不连续（不判）、2 查找边缘（跳过强边）。
     * 返回 null 表示不限制。
     */
    private fun buildEraseLimit(
        r: Rect, w: Int, h: Int, before: IntArray, cov: IntArray,
        target: Int, tol: Int, limit: Int, edge: Int,
    ): BooleanArray? {
        if (limit == 1) return null
        val m = BooleanArray(w * h)
        if (limit == 2) {
            val lum = IntArray(w * h) { i ->
                val c = before[i]
                (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()
            }
            fun lumAt(x: Int, y: Int): Int =
                lum[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            // 阈值由「边缘阈值」滑块单独控制（0~100 映射到 20~300），不再只看容差
            val thr = if (edge > 0) (20f + edge * 2.8f).toInt() else (tol * 0.6f + 24f).toInt()
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    val i = yy * w + xx
                    if ((cov[i] ushr 24) <= 8) continue
                    val gx = -lumAt(xx - 1, yy - 1) - 2 * lumAt(xx - 1, yy) - lumAt(xx - 1, yy + 1) +
                        lumAt(xx + 1, yy - 1) + 2 * lumAt(xx + 1, yy) + lumAt(xx + 1, yy + 1)
                    val gy = -lumAt(xx - 1, yy - 1) - 2 * lumAt(xx, yy - 1) - lumAt(xx + 1, yy - 1) +
                        lumAt(xx - 1, yy + 1) + 2 * lumAt(xx, yy + 1) + lumAt(xx + 1, yy + 1)
                    if (kotlin.math.abs(gx) + kotlin.math.abs(gy) > thr) continue
                    m[i] = true
                }
            }
            return m
        }
        val bmp = activeLayer?.bitmap ?: return null
        val bw = bmp.width
        val bh = bmp.height
        if (bw <= 0 || bh <= 0) return null
        val conn = bgConn ?: BooleanArray(bw * bh).also { bgConn = it }
        if (bgSeedX < 0) {
            bgSeedX = r.left + w / 2
            bgSeedY = r.top + h / 2
        }
        val q = IntArray(w * h)
        var qh = 0
        var qt = 0
        fun tryGrow(xx: Int, yy: Int) {
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) return
            val l = yy * w + xx
            if (m[l]) return
            if ((cov[l] ushr 24) <= 8) return
            if (ToolFx.colorDistance(before[l], target) > tol) return
            m[l] = true
            if (qt < q.size) q[qt++] = l
        }
        for (yy in 0 until h) {
            val gy2 = r.top + yy
            if (gy2 < 0 || gy2 >= bh) continue
            for (xx in 0 until w) {
                val gx2 = r.left + xx
                if (gx2 < 0 || gx2 >= bw) continue
                val l = yy * w + xx
                if ((cov[l] ushr 24) <= 8) continue
                if (!(conn[gy2 * bw + gx2] || (gx2 == bgSeedX && gy2 == bgSeedY))) continue
                if (ToolFx.colorDistance(before[l], target) > tol) continue
                m[l] = true
                if (qt < q.size) q[qt++] = l
            }
        }
        while (qh < qt) {
            val l = q[qh++]
            val xx = l % w
            val yy = l / w
            tryGrow(xx - 1, yy)
            tryGrow(xx + 1, yy)
            tryGrow(xx, yy - 1)
            tryGrow(xx, yy + 1)
        }
        for (yy in 0 until h) {
            val gy2 = r.top + yy
            if (gy2 < 0 || gy2 >= bh) continue
            for (xx in 0 until w) {
                if (!m[yy * w + xx]) continue
                val gx2 = r.left + xx
                if (gx2 < 0 || gx2 >= bw) continue
                conn[gy2 * bw + gx2] = true
            }
        }
        return m
    }

    private fun buildReplaceLimit(
        r: Rect, w: Int, h: Int, before: IntArray, cov: IntArray,
        target: Int, tol: Int, limit: Int,
    ): BooleanArray? {
        if (limit != 0) return null
        val bmp = activeLayer?.bitmap ?: return null
        val bw = bmp.width
        val bh = bmp.height
        if (bw <= 0 || bh <= 0) return null
        val conn = replaceConn ?: BooleanArray(bw * bh).also { replaceConn = it }
        val local = BooleanArray(w * h)
        val q = IntArray(w * h)
        var qh = 0
        var qt = 0
        for (yy in 0 until h) {
            val gy = r.top + yy
            if (gy < 0 || gy >= bh) continue
            for (xx in 0 until w) {
                val gx = r.left + xx
                if (gx < 0 || gx >= bw) continue
                val l = yy * w + xx
                if ((cov[l] ushr 24) <= 8) continue
                val g = gy * bw + gx
                if (conn[g] || (gx == replaceSeedX && gy == replaceSeedY)) {
                    local[l] = true
                    q[qt++] = l
                }
            }
        }
        while (qh < qt) {
            val l = q[qh++]
            val xx = l % w
            val yy = l / w
            if (xx > 0) qt = tryGrow(q, qt, local, w, h, xx - 1, yy, before, cov, target, tol)
            if (xx + 1 < w) qt = tryGrow(q, qt, local, w, h, xx + 1, yy, before, cov, target, tol)
            if (yy > 0) qt = tryGrow(q, qt, local, w, h, xx, yy - 1, before, cov, target, tol)
            if (yy + 1 < h) qt = tryGrow(q, qt, local, w, h, xx, yy + 1, before, cov, target, tol)
        }
        for (yy in 0 until h) {
            val gy = r.top + yy
            if (gy < 0 || gy >= bh) continue
            for (xx in 0 until w) {
                if (!local[yy * w + xx]) continue
                val gx = r.left + xx
                if (gx < 0 || gx >= bw) continue
                conn[gy * bw + gx] = true
            }
        }
        return local
    }

    private fun tryGrow(
        q: IntArray, qt: Int, local: BooleanArray, w: Int, h: Int,
        nx: Int, ny: Int, before: IntArray, cov: IntArray, target: Int, tol: Int,
    ): Int {
        val nl = ny * w + nx
        if (local[nl]) return qt
        if ((cov[nl] ushr 24) <= 8) return qt
        if (ToolFx.colorDistance(before[nl], target) > tol) return qt
        local[nl] = true
        q[qt] = nl
        return qt + 1
    }

    /** 从落点做一次颜色泛洪，返回「和落点色差值 ≤ tol」的连通区域 */
    private fun floodMatch(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, target: Int, tol: Int): BooleanArray {
        val mask = BooleanArray(w * h)
        if (w <= 0 || h <= 0) return mask
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = sy * w + sx
        mask[sy * w + sx] = true
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0 && !mask[i - 1] && ToolFx.colorDistance(px[i - 1], target) <= tol) {
                mask[i - 1] = true; stack[sp++] = i - 1
            }
            if (x + 1 < w && !mask[i + 1] && ToolFx.colorDistance(px[i + 1], target) <= tol) {
                mask[i + 1] = true; stack[sp++] = i + 1
            }
            if (y > 0 && !mask[i - w] && ToolFx.colorDistance(px[i - w], target) <= tol) {
                mask[i - w] = true; stack[sp++] = i - w
            }
            if (y + 1 < h && !mask[i + w] && ToolFx.colorDistance(px[i + w], target) <= tol) {
                mask[i + w] = true; stack[sp++] = i + w
            }
        }
        return mask
    }

    /** 像素工具把笔迹画成白色掩码，抬手时按掩码逐像素处理 */
    private fun drawMaskSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val buf = strokeBuffer ?: return
        // 取样＝连续：每小段重新取笔下的颜色（桌面端的连续取样就是这样，一次取样才固定用起笔那点）
        if (tool == Tool.REPLACE && replaceSample == 0) {
            val b0 = activeLayer?.bitmap
            if (b0 != null && b0.width > 1 && b0.height > 1) {
                val sx = ix1.toInt().coerceIn(0, b0.width - 1)
                val sy = iy1.toInt().coerceIn(0, b0.height - 1)
                val c = b0.getPixel(sx, sy)
                // 别把刚涂上去的前景色当成新取样色，否则第二遍就成了「红换红」
                if (ToolFx.colorDistance(c, brushColor) > 40) bgTarget = c
            }
        }
        val r = (brushSize / 2f).coerceIn(1.5f, 256f)
        val d = (r * 2f).toInt().coerceIn(4, 512)
        var dab = maskDab
        if (dab == null || dab.width != d) {
            dab = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
            val c = Canvas(dab)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val hard = if (brushHardness >= 0.9f) 0.92f else 0.30f
            p.shader = RadialGradient(
                d / 2f, d / 2f, d / 2f,
                intArrayOf(0xFFFFFFFFL.toInt(), 0xFFFFFFFFL.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, hard, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(d / 2f, d / 2f, d / 2f, p)
            maskDab = dab
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dx = ix1 - ix0
        val dy = iy1 - iy0
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val spacing = max(1.5f, r * 0.22f)
        val steps = if (dist < 0.01f) 1 else (dist / spacing).toInt() + 1
        val c = Canvas(buf)
        for (i in 0..steps) {
            val t = if (steps == 0) 0f else i.toFloat() / steps
            val cx = ix0 + dx * t
            val cy = iy0 + dy * t
            val l = cx - d / 2f
            val tp = cy - d / 2f
            c.drawBitmap(dab, l, tp, paint)
            strokeBounds.union(l.toInt(), tp.toInt(), (l + d).toInt(), (tp + d).toInt())
        }
        strokeBoundsValid = true
        // 掩码类工具（颜色替换/海绵/图案/污点/混合器/历史画笔）以前要等抬手才出效果，
        // 手指拖的时候看到的是笔刷色的假笔迹。这里每画一段就立刻算进图层。
        val lyr = activeLayer
        val lb = lyr?.bitmap
        val stroke = currentStroke
        if (lyr != null && lb != null && stroke != null) {
            val pad = 2
            val seg = Rect(
                (min(ix0, ix1) - r - pad).toInt().coerceAtLeast(0),
                (min(iy0, iy1) - r - pad).toInt().coerceAtLeast(0),
                (max(ix0, ix1) + r + pad).toInt().coerceAtMost(lb.width),
                (max(iy0, iy1) + r + pad).toInt().coerceAtMost(lb.height)
            )
            if (seg.width() > 0 && seg.height() > 0) {
                // 不清缓冲：覆盖度全程累积，同一像素按 strokeMax 的增量只算一次
                applyToolStrokeRect(stroke, lyr, lb, seg)
            }
        }
        invalidate()
    }

    /** 背景橡皮擦：逐段实时擦，只擦和取样色接近的像素（抬手不用再算一遍，不卡） */
    private fun drawBgEraseSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val stroke = currentStroke ?: return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        val r = (bgEraserSize / 2f).coerceIn(0.5f, 256f)
        val d = (r * 2f).toInt().coerceIn(2, 512)
        if (bgSampleMode == 0) {
            // 连续取样：每一小段都重新取一次笔下的颜色
            val sx = ix1.toInt().coerceIn(0, bmp.width - 1)
            val sy = iy1.toInt().coerceIn(0, bmp.height - 1)
            bgTarget = bmp.getPixel(sx, sy)
        } else if (bgSampleMode == 2) {
            bgTarget = brushColor
        }
        val left = max(0, (min(ix0, ix1) - r).toInt() - 1)
        val top = max(0, (min(iy0, iy1) - r).toInt() - 1)
        val right = min(bmp.width, (max(ix0, ix1) + r).toInt() + 2)
        val bottom = min(bmp.height, (max(iy0, iy1) + r).toInt() + 2)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        // 在这一小段的范围里铺一遍笔尖覆盖度
        val covBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cc = Canvas(covBmp)
        val dab = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val dc = Canvas(dab)
        val dp = Paint(Paint.ANTI_ALIAS_FLAG)
        val hard = bgEraserHardness.coerceIn(0.05f, 0.98f)
        dp.shader = RadialGradient(
            d / 2f, d / 2f, d / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, hard, 1f),
            Shader.TileMode.CLAMP
        )
        dc.drawCircle(d / 2f, d / 2f, d / 2f, dp)
        val cp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val steps = max(1, (kotlin.math.hypot(ix1 - ix0, iy1 - iy0) / max(1f, r * 0.25f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val cx = ix0 + (ix1 - ix0) * t - left - d / 2f
            val cy = iy0 + (iy1 - iy0) * t - top - d / 2f
            cc.drawBitmap(dab, cx, cy, cp)
        }
        dab.recycle()
        val before = IntArray(w * h)
        bmp.getPixels(before, 0, w, left, top, w, h)
        val cov = IntArray(w * h)
        covBmp.getPixels(cov, 0, w, 0, 0, w, h)
        covBmp.recycle()
        val tol = fxTolerance.coerceIn(0, 255)
        val op = brushOpacity.coerceIn(0f, 1f)
        val out = before.copyOf()
        val protect = if (bgProtect) brushColor else 0
        val smx = strokeMax
        val useMax = smx != null && smx.size == bmp.width * bmp.height
        val orig = bgOrigA
        for (i in before.indices) {
            var c = (cov[i] ushr 24) and 0xFF
            if (c <= 2) continue
            val p = before[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            // 限制：连续（只擦贴近落点色的）、不连续（擦所有相近色）、查找边缘（只在平坦区擦，边缘留着）
            var lim = tol
            if (bgLimit == 0) lim = (tol * 0.55f).toInt()
            // 容差不再是硬开关：超一点点还有一段软过渡，否则被「限制」框住的那片会留下
            // 一条笔直的硬边（用户报「方块边」）。软过渡宽度取容差的 0.8 倍。
            var wgt = 1f
            if (tol > 0) {
                val dst = ToolFx.colorDistance(p, bgTarget)
                if (dst > lim) {
                    val over = dst - lim
                    val span = max(6, (lim * 0.8f).toInt())
                    if (over >= span) continue
                    wgt = 1f - over.toFloat() / span
                }
            }
            if (bgLimit == 2) {
                val xi = i % w
                val yi = i / w
                val n = if (xi + 1 < w) before[i + 1] else p
                val dd = ToolFx.colorDistance(p, n)
                if (dd > 48) {
                    // 边缘处不硬跳过，按「多靠近边缘」打折，避免留下硬轮廓
                    wgt *= (48f / dd).coerceIn(0f, 1f)
                    if (wgt <= 0.02f) continue
                }
            }
            if (bgProtect && ToolFx.colorDistance(p, protect) <= 24) continue
            var base = a
            if (useMax) {
                val gi = (top + i / w) * bmp.width + (left + i % w)
                val prev = smx!![gi].toInt() and 0xFF
                if (c <= prev) continue
                smx[gi] = c.toByte()
                // 第一次碰到这个像素时记下下笔前的 alpha；以后一直按「最大覆盖度 + 原始 alpha」算，
                // 反复经过同一处不会一层层往下擦（以前用增量，笔迹上一串月牙/珠子就是这么来的）
                if (prev == 0) {
                    if (orig != null) orig[gi] = a.toByte()
                } else if (orig != null) {
                    base = orig[gi].toInt() and 0xFF
                    if (base == 0) base = a
                }
            }
            val frac = (op * c / 255f * wgt).coerceIn(0f, 1f)
            val nv = if (frac >= 0.996f) 0 else (base * (1f - frac)).toInt().coerceIn(0, 255)
            out[i] = if (nv == 0) 0 else Color.argb(nv, Color.red(p), Color.green(p), Color.blue(p))
        }
        if (out.contentEquals(before)) return
        bmp.setPixels(out, 0, w, left, top, w, h)
        touchPixels(Rect(left, top, left + w, top + h))
        stroke.patches.add(Patch(layer, left, top, w, h, before, out))
        runCatching { layer.pix16?.applyFrom8Diff(left, top, w, h, before, out) }
        invalidate()
    }

    /** 抬手：按掩码执行像素算法并记账 */
    private fun commitToolStroke(stroke: Stroke, layer: Layer, bmp: Bitmap) {
        if (!strokeBoundsValid) return
        val r = Rect(
            max(0, strokeBounds.left), max(0, strokeBounds.top),
            min(bmp.width, strokeBounds.right), min(bmp.height, strokeBounds.bottom)
        )
        applyToolStrokeRect(stroke, layer, bmp, r)
    }

    // ---- 减淡 / 加深：线性光下的曝光 ----
    // 曝光的物理意义是「线性光乘一个系数」（相机传感器是线性的），sRGB 是编码过的非线性值。
    // 直接在 sRGB 数值上乘，暗部提升不够、亮部先过冲，同一片颜色会一块亮一块不动——这就是「不均匀」的根。
    private val srgb2lin = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f
        else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    private val lin2srgbLut = ByteArray(4097) { i ->
        val l = i / 4096f
        val v = if (l <= 0.0031308f) l * 12.92f
        else 1.055f * Math.pow(l.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        (v * 255f).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun linToSrgb(l: Float): Int {
        val idx = (l.coerceIn(0f, 1f) * 4096f).toInt()
        return lin2srgbLut[idx].toInt() and 0xFF
    }

    /**
     * 减淡 / 加深：只改明度，不动色相。
     * 减淡走「线性光 × 系数」——曝光的物理定义就是线性光乘系数，三通道同乘所以色相不变、亮部不会先过冲。
     * 加深走「Lab 的 L* 上减」——只在明度轴上减，色相和彩度方向原样保留（红变深红，不会褪成中性黑）。
     * 两者的系数都只跟「这一帧新盖上去的覆盖度」有关：总量严格等于累计覆盖度的函数，
     * 同一像素被几个笔头盖到、算多少帧结果都一样，所以笔头交叠处不会堆出一圈圈竹节。
     */
    private fun toneApply(
        before: IntArray,
        cov: IntArray,
        covMax: IntArray,
        selPx: IntArray?,
        dodge: Boolean
    ): IntArray {
        val strength = (brushOpacity * (if (dodge) dodgeStrength else burnStrength)).coerceIn(0.02f, 1f)
        val range = if (dodge) dodgeRange else burnRange
        val protect = if (dodge) dodgeProtect else burnProtect
        val out = before.copyOf()
        for (i in before.indices) {
            val dA = Color.alpha(cov[i])
            if (dA <= 0) continue
            if (selPx != null && Color.alpha(selPx[i]) <= 8) continue
            val c = before[i]
            val a = Color.alpha(c)
            // 半透明像素存回位图要预乘，alpha 太低的 RGB 精度本来就差，一放大就是彩色颗粒
            if (a < 12) continue
            val lr = srgb2lin[Color.red(c)]
            val lg = srgb2lin[Color.green(c)]
            val lb = srgb2lin[Color.blue(c)]
            val y = 0.2126729f * lr + 0.7151522f * lg + 0.0721750f * lb
            // 色调带按线性亮度划：非线性亮度会把暗部全算成阴影，高光带几乎选不到东西
            val band = when (range) {
                1 -> ((0.16f - y) / 0.16f).coerceIn(0f, 1f)
                3 -> ((y - 0.16f) / 0.84f).coerceIn(0f, 1f)
                2 -> (1f - kotlin.math.abs(y - 0.16f) / 0.5f).coerceIn(0.15f, 1f)
                else -> 1f
            }
            val amt = (strength * band).coerceIn(0f, 1f)
            if (amt <= 0.002f) continue
            // 只走「这一帧新盖上去的那一点」，所以总量只由累计覆盖度决定，不会叠出竹节
            val step = (dA / 255f) * amt
            if (step <= 0f) continue
            if (dodge) {
                // 满覆盖度、100% 曝光约 1.4 档
                val f = Math.pow(2.0, (1.4f * step).toDouble()).toFloat()
                out[i] = Color.argb(a, linToSrgb(lr * f), linToSrgb(lg * f), linToSrgb(lb * f))
            } else {
                // 加深：L* 减。满覆盖度、100% 曝光约 30 个 L*（≈ 1.5 档观感），比线性乘更保色
                if (y <= 0.0004f) continue
                val lStar = 116f * Math.pow(y.toDouble(), 1.0 / 3.0).toFloat() - 16f
                val floorL = if (protect) 12f else 2.5f
                val target = (lStar - 34f * step).coerceAtLeast(floorL)
                if (target >= lStar - 0.01f) continue
                val y2 = Math.pow(((target + 16f) / 116f).toDouble(), 3.0).toFloat()
                val k = y2 / y
                out[i] = Color.argb(a, linToSrgb(lr * k), linToSrgb(lg * k), linToSrgb(lb * k))
            }
        }
        return out
    }

    /** 把笔迹缓冲里 [r] 这块的覆盖度立刻算成像素效果。掩码类工具靠它做到「画多少出现多少」 */
    private fun applyToolStrokeRect(stroke: Stroke, layer: Layer, bmp: Bitmap, rect: Rect) {
        val buf = strokeBuffer ?: return
        val r = Rect(
            max(0, rect.left), max(0, rect.top),
            min(bmp.width, rect.right), min(bmp.height, rect.bottom)
        )
        if (r.width() <= 0 || r.height() <= 0) return
        val w = r.width()
        val h = r.height()
        val before = IntArray(w * h)
        bmp.getPixels(before, 0, w, r.left, r.top, w, h)
        val cov = IntArray(w * h)
        buf.getPixels(cov, 0, w, r.left, r.top, w, h)
        // 减淡/加深/海绵：同一笔里同一像素只按「新增的覆盖度」算一次。
        // 否则反复经过的地方会一遍遍叠加，画出来深浅不匀，加深还会直接黑掉。
        val smx = strokeMax
        val covUse = if (smx != null && smx.size == bmp.width * bmp.height) {
            val eff = IntArray(w * h)
            for (yy in 0 until h) {
                var idx = (r.top + yy) * bmp.width + r.left
                var di = yy * w
                for (xx in 0 until w) {
                    val cur = cov[di] ushr 24
                    val prev = smx[idx].toInt() and 0xFF
                    if (cur > prev) {
                        smx[idx] = cur.toByte()
                        eff[di] = ((cur - prev) shl 24) or 0x00FFFFFF
                    }
                    idx++
                    di++
                }
            }
            eff
        } else cov
        val sel = selection?.let { IntArray(w * h).also { a -> it.getPixels(a, 0, w, r.left, r.top, w, h) } }
        val op = brushOpacity.coerceIn(0f, 1f)
        val out: IntArray = when (tool) {
            Tool.REPLACE -> {
                // 取样：0 连续（跟着笔走，用最后一次取到的颜色） 1 一次（起笔时取样） 2 背景色板
                val tgt = when (replaceSample) {
                    2 -> brushColor2
                    else -> bgTarget
                }
                // 限制＝连续：从落点出发，把这一笔里和「已经换过的区域」连着的同色像素一点点扩开。
                // 以前要么落笔时把连通区一次算死（第二片同色就不管了），要么完全不判连通（连续/不连续没区别）。
                // 不连续＝笔刷范围内所有符合容差的都换；查找边缘＝跳过颜色跳变处。
                val lim = buildReplaceLimit(r, w, h, before, cov, tgt, if (fxTolerance <= 0) 16 else fxTolerance, replaceLimit)
                ToolFx.replaceColor(
                    // 容差 0 时给个下限，否则「连续」模式下只有和落点一模一样的像素会被换，看着就是没反应
                    // 用「累计覆盖度」：只按新增的那一小段上色会让笔迹一节一节（用户报的像竹子/贪吃蛇）
                    before, cov, sel, brushColor, tgt, if (fxTolerance <= 0) 16 else fxTolerance, op,
                    replaceMode, replaceLimit, w, lim,
                )
            }
            // 明暗用「累计覆盖度」算：按增量算的话每一小段各叠一次，笔迹上会出现一排密集的竹节。
            // 累计覆盖度在笔迹内部是平滑的，算出来才干净。
            // 按「新增的覆盖度」算：手指停着不动时新增为 0，画面就不再继续变深；
            // 以前按累计覆盖度算，停着不动每一帧都再叠一次，就出现一条条过曝的白纹
            Tool.DODGE, Tool.BURN -> toneApply(before, covUse, cov, sel, tool == Tool.DODGE)
            Tool.SPONGE -> ToolFx.sponge(before, covUse, sel, spongeMode, (op * spongeStrength).coerceIn(0.05f, 1f), spongeAirbrush)
            Tool.ERASER_BG -> {
                // 「连续 / 不连续 / 查找边缘」以前只是循环按钮，没真正参与擦除；这里是真判定
                val elim = buildEraseLimit(
                    r, w, h, before, cov, bgTarget,
                    if (fxTolerance <= 0) 16 else fxTolerance, bgLimit, bgEdge.toInt(),
                )
                // 用累计覆盖度：命中就一次擦到位，不会再出现「擦完留一层灰」
                ToolFx.eraseByColor(before, cov, sel, bgTarget, fxTolerance, if (bgProtect) brushColor else 0, elim)
            }
            Tool.PATTERN -> {
                val pat = Patterns.all().getOrNull(patternIndex)?.second ?: Patterns.all()[0].second
                val ox = if (patternAligned) 0 else patternOrigin.x.toInt()
                val oy = if (patternAligned) 0 else patternOrigin.y.toInt()
                ToolFx.patternStamp(before, covUse, sel, w, pat, -r.left + ox, -r.top + oy, op, patternImpressionist)
            }
            Tool.SPOT -> {
                if (spotStrokeOn) stampSpotCov(cov, w, h, r.left, r.top)
                ToolFx.spotHeal(
                    before, covUse, sel, w, h,
                    (brushSize / 2f).toInt().coerceIn(2, 40), spotType, spotRing, spotFeather,
                )
            }
            Tool.MIXER -> ToolFx.mixer(
                before, covUse, sel, brushColor, mixerState, mixerLoad,
                // 前景色只占 mixerFg 那一份（默认 0 = 只抹开画面颜色）；混多少由「混合」控制画布色的掺入
                mixerFg.coerceIn(0f, 1f) * mixerMix.coerceIn(0f, 1f), op,
            )
            Tool.HISTORY_BRUSH, Tool.ART_HISTORY -> {
                // 历史记录画笔的「来源」：上一状态 / 打开文档时；艺术历史的「区域」：整个文档（拍平）或仅当前图层
                val base = if (historySource == 1) (initialSnap ?: historySnap) else historySnap
                val snap = if (tool == Tool.ART_HISTORY && artRegion == 0) (flatten() ?: base) else base
                val snapBmp = snap ?: bmp
                val snapPx = IntArray(w * h)
                if (snapBmp.width == bmp.width && snapBmp.height == bmp.height) {
                    snapBmp.getPixels(snapPx, 0, w, r.left, r.top, w, h)
                } else {
                    snapPx.indices.forEach { snapPx[it] = before[it] }
                }
                if (tool == Tool.HISTORY_BRUSH) {
                    ToolFx.historyBrush(before, covUse, sel, snapPx, op)
                } else {
                    ToolFx.artHistory(before, covUse, sel, snapPx, w, h, op, artStyle, artFidelity, artSeed)
                }
            }
            else -> before
        }
        if (out.contentEquals(before)) return
        bmp.setPixels(out, 0, w, r.left, r.top, w, h)
        touchPixels(Rect(r.left, r.top, r.left + w, r.top + h))
        stroke.patches.add(Patch(layer, r.left, r.top, w, h, before, out))
        runCatching { layer.pix16?.applyFrom8Diff(r.left, r.top, w, h, before, out) }
        invalidate()
        onDocChanged?.invoke()
    }

    /** 魔术橡皮：单击擦掉相同颜色（或整层相同色） */
    private fun magicEraseAt(x: Float, y: Float) {
        if (isActiveLayerPaintingLocked()) return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        val ix = x.toInt().coerceIn(0, bmp.width - 1)
        val iy = y.toInt().coerceIn(0, bmp.height - 1)
        val before = IntArray(bmp.width * bmp.height)
        bmp.getPixels(before, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        // 样本：所有图层时，先拿整幅拍平图去找「该擦哪一片」，再把这片面积落到当前层上
        val probe = if (magicAllLayers) flatten() else null
        val probePx = if (probe != null) {
            val p = IntArray(probe.width * probe.height)
            probe.getPixels(p, 0, probe.width, 0, 0, probe.width, probe.height)
            p
        } else before
        val maskOut = ToolFx.magicErase(
            probePx, bmp.width, bmp.height, ix, iy,
            fxTolerance, magicContinuous, magicAll, brushOpacity.coerceIn(0f, 1f),
            magicAntialias,
        )
        val out = if (probe == null) {
            maskOut
        } else {
            // 用探测结果的 alpha 下降量当遮罩，作用到当前图层的同位置像素
            val r = before.copyOf()
            for (i in before.indices) {
                val a0 = (before[i] ushr 24) and 0xFF
                if (a0 == 0) continue
                val pa0 = (probePx[i] ushr 24) and 0xFF
                val pa1 = (maskOut[i] ushr 24) and 0xFF
                if (pa0 == 0 || pa1 >= pa0) continue
                val keep = pa1.toFloat() / pa0
                val na = (a0 * keep).toInt().coerceIn(0, 255)
                r[i] = (before[i] and 0x00FFFFFF) or (na shl 24)
            }
            r
        }
        if (out.contentEquals(before)) return
        bmp.setPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val rect = Rect(0, 0, bmp.width, bmp.height)
        touchPixels(rect)
        val st = Stroke().also { it.label = "魔术橡皮" }
        st.patches.add(Patch(layer, 0, 0, bmp.width, bmp.height, before, out))
        pushUndo(st)
        runCatching { layer.pix16 = null }
        invalidate()
        onDocChanged?.invoke()
    }

    /** 红眼：拖框结束后把框里的红瞳孔压暗 */
    private fun commitRedEye(x: Float, y: Float) {
        if (isActiveLayerPaintingLocked()) return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        var rx0 = redeyeStart.x
        var ry0 = redeyeStart.y
        var rx1 = x
        var ry1 = y
        // 单击（没拖出框）时以落点为中心、按笔刷大小自动框住瞳孔
        if (abs(rx1 - rx0) < 4f || abs(ry1 - ry0) < 4f) {
            val r = max(brushSize * 1.5f, 24f)
            rx0 = x - r
            ry0 = y - r
            rx1 = x + r
            ry1 = y + r
        }
        val left = min(rx0, rx1).toInt().coerceIn(0, bmp.width - 1)
        val top = min(ry0, ry1).toInt().coerceIn(0, bmp.height - 1)
        val right = max(rx0, rx1).toInt().coerceIn(left + 1, bmp.width)
        val bottom = max(ry0, ry1).toInt().coerceIn(top + 1, bmp.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return
        val before = IntArray(w * h)
        bmp.getPixels(before, 0, w, left, top, w, h)
        val pupil = redeyePupil
        val darken = redeyeDarken
        redEyeDragging = false
        invalidate()
        // 逐像素扫一框在主线程做会卡住指示器（用户报的「绿点要等半天才消」），挪到后台线程
        Thread {
            val out = ToolFx.redEye(before, w, h, pupil, darken)
            if (out.contentEquals(before)) {
                post { onHint?.invoke("框里没找到红眼：把框拉大一点把瞳孔框住，或者把「瞳孔大小」调大") }
                return@Thread
            }
            post {
                if (!bmp.isRecycled) {
                    bmp.setPixels(out, 0, w, left, top, w, h)
                    touchPixels(Rect(left, top, right, bottom))
                    val st = Stroke().also { it.label = "红眼" }
                    st.patches.add(Patch(layer, left, top, w, h, before, out))
                    pushUndo(st)
                    runCatching { layer.pix16?.applyFrom8Diff(left, top, w, h, before, out) }
                    dropMips()
                    invalidate()
                    onDocChanged?.invoke()
                }
            }
        }.start()
    }

    /** 记一张历史快照：历史记录画笔从最近一次跳转/撤销的画面刷回来 */
    private fun captureHistorySnap() {
        runCatching {
            val b = activeLayer?.bitmap ?: return
            historySnap = b.copy(Bitmap.Config.ARGB_8888, false)
            // 「打开文档时」的来源：只在第一次抓，之后不再变
            if (initialSnap == null) initialSnap = b.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    // 笔迹慢的诊断：每一小段计时，太慢就记下来，抬手时汇总一条进日志
    private var segSlowCount = 0
    private var segSlowTotal = 0L
    private var segSlowMax = 0L
    // 画的时候每帧 onDraw 的耗时（用来区分「笔迹处理慢」还是「重绘慢」）
    private var drawFrames = 0
    private var drawSlowFrames = 0
    private var drawSlowMs = 0L
    private var layerDrawMs = 0L
    // 每次 ACTION_MOVE 从进来到处理完的总耗时（含「把这一小段合成进图层」）
    private var moveEvents = 0
    private var moveSlowEvents = 0
    private var moveMsTotal = 0L
    private var flushMsTotal = 0L
    private var moveStampMs = 0L
    private var stampDocMs = 0L
    private var stampOvMs = 0L
    private var dabCount = 0
    private var moveCropMs = 0L

    private fun drawSegment(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        val segT0 = System.nanoTime()
        drawSegmentInner(ix0, iy0, ix1, iy1)
        val segMs = (System.nanoTime() - segT0) / 1_000_000L
        if (segMs >= 24L) {
            segSlowCount++
            segSlowTotal += segMs
            if (segMs > segSlowMax) segSlowMax = segMs
            if (segSlowCount <= 5) {
                android.util.Log.i("RevealPerf", "segment slow " + segMs + "ms tool=" + tool + " size=" + brushSize)
            }
        }
    }

    private fun drawSegmentInner(ix0: Float, iy0: Float, ix1: Float, iy1: Float) {
        when (tool) {
            Tool.ERASER -> drawEraserSegment(ix0, iy0, ix1, iy1)
            Tool.CLONE, Tool.HEAL -> drawCloneSegment(ix0, iy0, ix1, iy1)
            Tool.DODGE -> drawToneSegment(ix0, iy0, ix1, iy1, true)
            Tool.BURN -> drawToneSegment(ix0, iy0, ix1, iy1, false)
            Tool.SMUDGE -> drawSmudgeSegment(ix0, iy0, ix1, iy1)
            Tool.LIQUIFY -> drawLiquifySegment(ix0, iy0, ix1, iy1)
            // 混合器：先「抓」起笔下的颜色往前拖（跟涂抹一样），拖的过程中按「混合」把前景色掺进去
            // 混合器：默认是「把画面上的颜色抹开」，不是拿笔刷色盖上去。
        // 只有把「载入」拉得很高时才真的带色（桌面端里载入 100% 就是这么用的）。
        // 混合器：默认只把画面颜色抹开；「带多少前景色」拉高才会真的掺前景色（桌面端的混合器滑杆）
        Tool.MIXER -> drawSmudgeSegment(
            ix0, iy0, ix1, iy1, brushColor,
            // 「带多少前景色」= 前景色比例 × 混合：两个都为 0 的时候就只抹开画面颜色
            (mixerFg.coerceIn(0f, 1f) * mixerMix.coerceIn(0f, 1f) * (0.35f + 0.65f * mixerLoad.coerceIn(0f, 1f))),
        )
            Tool.ERASER_BG -> drawBgEraseSegment(ix0, iy0, ix1, iy1)
            Tool.REPLACE, Tool.SPONGE,
            Tool.PATTERN, Tool.SPOT, Tool.HISTORY_BRUSH, Tool.ART_HISTORY -> drawMaskSegment(ix0, iy0, ix1, iy1)
            Tool.ERASER_MAGIC, Tool.REDEYE -> Unit
            Tool.GRADIENT -> Unit
            else -> drawBrushSegment(ix0, iy0, ix1, iy1)
        }
    }

    private fun endStroke() {
        if (segSlowCount > 0 || drawSlowFrames > 0 || drawFrames > 0) {
            val msg = "工具 " + tool + " 慢段 " + segSlowCount + " 次 / 合计 " + segSlowTotal + "ms / 最慢 " + segSlowMax + "ms / 画布 " + layers.firstOrNull()?.bitmap?.width + "x" + layers.firstOrNull()?.bitmap?.height + " / 重绘 " + drawFrames + " 帧其中慢 " + drawSlowFrames + " 帧(合计 " + drawSlowMs + "ms)" + " / 图层上屏合计 " + layerDrawMs + "ms" + " / 移动 " + moveEvents + " 次合计 " + moveMsTotal + "ms(其中合成 " + flushMsTotal + "ms) / 慢事件 " + moveSlowEvents + " / 盖章 " + moveStampMs + "ms(文档 " + stampDocMs + "ms / 预览 " + stampOvMs + "ms / 笔头 " + dabCount + " 个) / 预览拷贝 " + moveCropMs + "ms"
            android.util.Log.i("RevealPerf", msg)
            runCatching {
                com.reveal.editor.data.Diag.append(context, "笔迹慢", msg)
            }
        }
        segSlowCount = 0
        segSlowTotal = 0L
        segSlowMax = 0L
        drawFrames = 0
        drawSlowFrames = 0
        drawSlowMs = 0L
        layerDrawMs = 0L
        moveEvents = 0
        moveSlowEvents = 0
        moveMsTotal = 0L
        flushMsTotal = 0L
        moveStampMs = 0L
        moveCropMs = 0L
        stampDocMs = 0L
        stampOvMs = 0L
        dabCount = 0
        val stroke = currentStroke ?: return
        currentStroke = null
        drawing = false
        // 这一笔画完像素就变了，拍平快照作废；下一笔要用时在后台重新拍一张
        invalidateFlatSnapshot()

        if (tool == Tool.BRUSH || tool == Tool.CLONE || tool == Tool.HEAL) {
            if (tool == Tool.BRUSH) restampTapered()
            commitBrushStroke(stroke)
        } else if (tool == Tool.DODGE || tool == Tool.BURN) {
            // 逐段已经按「最大覆盖度增量」算进图层了，抬手不能再整笔算一遍（会叠两遍、接缝更深）
            clearStrokeBuffer()
        } else if (isMaskTool(tool)) {
            clearStrokeBuffer()
        } else {
            clearStrokeBuffer()
        }

        if (deferLive) {
            deferLive = false
            strokeOverlay?.let { if (!it.isRecycled) it.recycle() }
            strokeOverlay = null
            overlayCanvas = null
        }

        if (liveMode) {
            liveTiles.clear()
            liveMode = false
        }
        strokeMax = null
        bgOrigA = null
        healSolveOn = false
        spotStrokeOn = false
        // 缩小看整张图时显示的是金字塔小图（mip）。画的时候 mip 不重建，
        // 抬手后如果还拿旧 mip 贴，新笔迹就「消失」，要等缩放换档、mip 重建才冒出来
        dropMips()
        invalidate()
        if (stroke.patches.isEmpty()) return
        pushUndo(stroke)
    }

    private fun commitBrushStroke(stroke: Stroke) {
        val buf = strokeBuffer ?: return
        val layer = activeLayer ?: return
        val bmp = needBitmap(layer) ?: return
        if (!strokeBoundsValid) return
        var maskBmp = if (maskEditing) ensureMaskSized(layer) else null
        val onMask = maskBmp != null
        var target = maskBmp ?: bmp
        if (!target.isMutable) {
            val fixed = target.copy(Bitmap.Config.ARGB_8888, true)
            if (onMask) {
                layer.mask = fixed
                maskBmp = fixed
            } else {
                layer.bitmap = fixed
            }
            target = fixed
        }
        val r = Rect(
            max(0, strokeBounds.left),
            max(0, strokeBounds.top),
            min(target.width, strokeBounds.right),
            min(target.height, strokeBounds.bottom)
        )
        if (r.width() <= 0 || r.height() <= 0) return

        val w = r.width()
        val h = r.height()
        // 现场模式：先把整块还原成起笔状态，缓冲里是整笔的累积，这样只合成一次
        // 分块快照整格还原，合成也按整格来，否则格子里的笔迹会被抹掉
        val cr = if (liveMode) tileAlignedRect(target, r) else r
        if (liveMode) {
            liveTileCapture(target, cr)
            liveTileRestore(target, cr)
        }
        val before = IntArray(w * h)
        target.getPixels(before, 0, w, r.left, r.top, w, h)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt()
        val canvas = Canvas(target)
        canvas.save()
        canvas.clipRect(cr)
        val sel = selection
        if (onMask) {
            // 蒙版：只改 alpha。亮色 = 露出，暗色 = 遮住
            val reveal = (0.299f * Color.red(brushColor) + 0.587f * Color.green(brushColor) + 0.114f * Color.blue(brushColor)) >= 128f
            maskInkPaint.colorFilter = maskWhiteFilter
            maskInkPaint.alpha = paint.alpha
            maskInkPaint.xfermode = if (reveal) null else PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            if (sel != null) {
                val cut = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cc = Canvas(cut)
                cc.drawBitmap(buf, -r.left.toFloat(), -r.top.toFloat(), null)
                val inPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                inPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                cc.drawBitmap(sel, -r.left.toFloat(), -r.top.toFloat(), inPaint)
                canvas.drawBitmap(cut, r.left.toFloat(), r.top.toFloat(), maskInkPaint)
                cut.recycle()
            } else {
                canvas.drawBitmap(buf, 0f, 0f, maskInkPaint)
            }
            maskInkPaint.xfermode = null
            maskInkPaint.colorFilter = null
            layer.maskVer++
            onMaskStroke?.invoke()
        } else if (layer.alphaLock || sel != null) {
            // 阿尔法锁定：笔迹先用图层现有像素当遮罩剪一遍，只在原有颜色上生效
            // 有选区：再按选区剪一遍，只画在选区里面
            val masked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val mc = Canvas(masked)
            mc.drawBitmap(buf, -r.left.toFloat(), -r.top.toFloat(), null)
            val inPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            inPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            if (layer.alphaLock) mc.drawBitmap(bmp, -r.left.toFloat(), -r.top.toFloat(), inPaint)
            if (sel != null) mc.drawBitmap(sel, -r.left.toFloat(), -r.top.toFloat(), inPaint)
            canvas.drawBitmap(masked, r.left.toFloat(), r.top.toFloat(), paint)
            masked.recycle()
        } else if (brushBlend != BLEND_NORMAL) {
            // 画笔混合模式：笔迹按模式与底下颜色混合（桌面端画笔工具顶上的「模式」）
            val cover = IntArray(w * h)
            buf.getPixels(cover, 0, w, r.left, r.top, w, h)
            val out = before.copyOf()
            val opA = brushOpacity.coerceIn(0f, 1f)
            for (i in before.indices) {
                val src = cover[i]
                val ca = (src ushr 24) and 0xFF
                if (ca == 0) continue
                val k = ca / 255f * opA
                val mixed = blendModeColor(before[i], src or (0xFF shl 24), brushBlend)
                out[i] = blendPixels(before[i], mixed, k)
            }
            target.setPixels(out, 0, w, r.left, r.top, w, h)
        } else {
            canvas.drawBitmap(buf, 0f, 0f, paint)
        }
        canvas.restore()

        // 修复画笔：预览已经合成进图层，这里对整条笔迹解一次泊松，接缝和「抹」的痕迹就没了
        // 有选区 / 阿尔法锁定 / 超大文档都照样解：前者按选区裁、后者只在原像素不透明处写，
        // 覆盖度缓冲在超大文档上自动降半分辨率
        if (tool == Tool.HEAL && healSolveOn && !onMask) {
            runCatching { solveHealStroke(target, r.left, r.top, w, h, before, sel, layer.alphaLock) }
        }
        // 污点修复：抬手后整条笔迹再做一次 PatchMatch（近似的纹理走向能接上）
        if (tool == Tool.SPOT && spotStrokeOn && !onMask) {
            runCatching { solveSpotStroke(target, r.left, r.top, w, h) }
        }

        val after = IntArray(w * h)
        target.getPixels(after, 0, w, r.left, r.top, w, h)
        stroke.patches.add(Patch(layer, r.left, r.top, w, h, before, after, onMask))
        // 16 位文档：这一笔按覆盖率重混进 16 位缓冲，渐变不会掉成 256 级
        if (onMask) layer.maskDirty = true
        if (!onMask) runCatching {
            if (docDepth == 32) {
                val p32 = ensurePix32(layer)
                if (p32 != null) {
                    if (!layer.alphaLock && sel == null) {
                        val cover = IntArray(w * h)
                        buf.getPixels(cover, 0, w, r.left, r.top, w, h)
                        p32.applyCoverage(r.left, r.top, w, h, cover, brushOpacity.coerceIn(0f, 1f))
                    } else {
                        p32.applySolidDiff(r.left, r.top, w, h, before, after)
                    }
                }
                return@runCatching
            }
            val p16 = ensurePix16(layer)
            if (p16 != null) {
                if (!layer.alphaLock && sel == null) {
                    // 有笔迹缓冲：alpha 就是这一笔的覆盖率，直接在 16 位空间混，精度不丢
                    val cover = IntArray(w * h)
                    buf.getPixels(cover, 0, w, r.left, r.top, w, h)
                    p16.applyCoverage(r.left, r.top, w, h, cover, brushOpacity.coerceIn(0f, 1f))
                    com.reveal.editor.data.Diag.append(
                        context, "brush16",
                        "coverage w=" + w + " h=" + h + " op=" + brushOpacity + " flow=" + brushFlow + " size=" + brushSize,
                    )
                } else {
                    // 阿尔法锁定 / 有选区：8 位那边已经按遮罩剪过，只能按前后差异反推
                    p16.applyStroke(r.left, r.top, w, h, before, after, brushColor)
                }
            }
        }.onFailure { com.reveal.editor.data.Diag.append(context, "brush16", it.toString()) }

        compositePixelStamp++
        markDocDirty(r)
        buf.eraseColor(0)
        strokeBoundsValid = false
        invalidate()
    }


    private fun cancelStroke() {
        val stroke = currentStroke ?: return
        currentStroke = null
        drawing = false
        clearStrokeBuffer()
        strokeBoundsValid = false
        strokeMax = null
        bgOrigA = null
        if (deferLive) {
            deferLive = false
            strokeOverlay?.let { if (!it.isRecycled) it.recycle() }
            strokeOverlay = null
            overlayCanvas = null
        }
        dropMips()
        if (liveMode) {
            val lb = activeLayer?.let { needBitmap(it) }
            if (lb != null) {
                val t = liveTile
                for ((k, arr) in liveTiles) {
                    val tx = (k ushr 32).toInt()
                    val ty = k.toInt()
                    val w = min(t, lb.width - tx)
                    val h = min(t, lb.height - ty)
                    if (w > 0 && h > 0) lb.setPixels(arr, 0, w, tx, ty, w, h)
                }
            }
            liveTiles.clear()
            liveMode = false
        }
        for (i in stroke.patches.indices.reversed()) {
            val p = stroke.patches[i]
            patchTarget(p)?.setPixels(p.before.take(), 0, p.w, p.x, p.y, p.w, p.h)
        }
        compositePixelStamp++
        markDocDirty(strokeRect(stroke))
        invalidate()
    }

    fun undo() {
        // 撤销前先清掉滤镜/调色预览：预览是画在图层上面的临时图，
        // 不清的话撤销把图层还原了、预览还盖着，用户看到的就是「按了撤销没反应，再按一下才回去」
        clearPreview()
        // 栈顶可能是空步骤（只动过选区缓存、没改一个像素，日志里就是 patches=0）：
        // 直接丢掉继续往下找，否则按一次撤销画面纹丝不动，用户会以为撤销坏了。
        var stroke = undoStack.removeLastOrNull() ?: return
        while (stroke.patches.isEmpty() && stroke.selW <= 0 && !stroke.isStruct()) {
            stroke = undoStack.removeLastOrNull() ?: return
        }
        // 批量结构步骤：整张图层表按快照还原（多选删除 / 成组 / 合并 / 批量移动）
        stroke.orderBefore?.let { back ->
            if (layers.size != back.size || layers.indices.any { layers[it] !== back[it] }) {
                layers.clear()
                layers.addAll(back)
                activeIndex = activeIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
                onActiveLayerChanged?.invoke()
                invalidateFlatSnapshot()
                invalidate()
            }
        }
        // 删层：把整层插回原位（位图当年没回收，这里能直接复原）
        stroke.removedLayer?.let { back ->
            if (layers.none { it === back }) {
                val at = stroke.removedIndex.coerceIn(0, layers.size)
                layers.add(at, back)
                activeIndex = at
                onActiveLayerChanged?.invoke()
                invalidateFlatSnapshot()
                invalidate()
            }
        }
        // 新增层（形状）：整层摘掉
        stroke.addedLayer?.let { added ->
            val at = layers.indexOfFirst { it === added }
            if (at >= 0) {
                layers.removeAt(at)
                activeIndex = activeIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
                onActiveLayerChanged?.invoke()
                invalidateFlatSnapshot()
                invalidate()
            }
        }
        compositePixelStamp++
        markDocDirty(strokeRect(stroke))
        if (stroke.selW > 0) {
            applySelectionSnapshot(stroke.selBefore, stroke.selW, stroke.selH)
            recycleSelEditBase()
        }
        for (i in stroke.patches.indices.reversed()) {
            val p = stroke.patches[i]
            patchTarget(p)?.setPixels(p.before.take(), 0, p.w, p.x, p.y, p.w, p.h)
            // 样式缓存（描边/投影那张叠加图）按 identityHashCode(bmp)+styleVer 缓存：
            // 位图对象没换、styleVer 又不变的话，撤销后画面上还盖着「变换之后」的描边投影，
            // 看起来就像这一笔撤不回去。8 位文档没有 16 位快照，原来这里不会 ++。
            p.layer.styleVer++
        }
        // 16 位缓冲跟着一起回退：有快照就还原，没快照（8 位文档）就不用管
        for (i in stroke.patches.indices.reversed()) {
            val p = stroke.patches[i]
            val snap = p.p16Before ?: continue
            val p16 = p.layer.pix16 ?: continue
            if (p16.width == p.p16W && p16.height == p.p16H) {
                runCatching { p16.restore(p.x, p.y, p.w, p.h, snap) }
            }
            p.layer.styleVer++
        }
        runCatching {
            com.reveal.editor.data.Diag.append(context, "p16undo", "patches=" + stroke.patches.size)
        }
        // 缩小时画布用的是 mip2/mip4（缩小过的副本）：不把它们作废，
        // 撤销后屏幕上还是「变换/滤镜之后」的样子，看起来就像撤销没生效
        dropMips()
        redoStack.add(stroke)
        invalidateFlatSnapshot()
        captureHistorySnap()
        invalidate()
    }

    fun redo() {
        clearPreview()
        var stroke = redoStack.removeLastOrNull() ?: return
        while (stroke.patches.isEmpty() && stroke.selW <= 0 && !stroke.isStruct()) {
            stroke = redoStack.removeLastOrNull() ?: return
        }
        // 重做批量结构步骤：换回「做完之后的图层表」
        stroke.orderAfter?.let { next ->
            if (layers.size != next.size || layers.indices.any { layers[it] !== next[it] }) {
                layers.clear()
                layers.addAll(next)
                activeIndex = activeIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
                onActiveLayerChanged?.invoke()
                invalidateFlatSnapshot()
                invalidate()
            }
        }
        // 重做「删层」：再把它摘掉
        stroke.removedLayer?.let { gone ->
            val at = layers.indexOfFirst { it === gone }
            if (at >= 0) {
                layers.removeAt(at)
                activeIndex = activeIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
                onActiveLayerChanged?.invoke()
                selHasPixels = false
                invalidateFlatSnapshot()
                invalidate()
            }
        }
        // 重做「新增层」：插回原位
        stroke.addedLayer?.let { added ->
            if (layers.none { it === added }) {
                val at = stroke.addedIndex.coerceIn(0, layers.size)
                layers.add(at, added)
                activeIndex = at
                onActiveLayerChanged?.invoke()
                invalidateFlatSnapshot()
                invalidate()
            }
        }
        compositePixelStamp++
        markDocDirty(strokeRect(stroke))
        if (stroke.selW > 0) {
            applySelectionSnapshot(stroke.selAfter, stroke.selW, stroke.selH)
            recycleSelEditBase()
        }
        for (p in stroke.patches) {
            patchTarget(p)?.setPixels(p.after.take(), 0, p.w, p.x, p.y, p.w, p.h)
        }
        // 重做时把 16 位也推回「这一笔之后」的状态
        for (p in stroke.patches) {
            val snap = p.p16After ?: continue
            val p16 = p.layer.pix16 ?: continue
            if (p16.width == p.p16W && p16.height == p.p16H) {
                runCatching { p16.restore(p.x, p.y, p.w, p.h, snap) }
            }
        }
        stroke.patches.forEach { it.layer.styleVer++ }
        dropMips()
        undoStack.add(stroke)
        invalidateFlatSnapshot()
        captureHistorySnap()
        invalidate()
    }

    // ================= 选区撤销快照 =================

    /** 只存 alpha（白=选中），比整张 ARGB 省 4 倍内存 */
    private fun packAlpha(mask: Bitmap): ByteArray {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in px.indices) out[i] = (px[i] ushr 24).toByte()
        return out
    }

    private fun unpackAlpha(data: ByteArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = (data[i].toInt() and 0xFF) shl 24
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 选区变一次就压一条快照进撤销栈；选区快照最多留最近 12 条 */
    private fun pushSelectionStroke(before: ByteArray?, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val s = Stroke().also { it.label = "选区" }
        s.selBefore = before
        s.selAfter = selection?.let { packAlpha(it) }
        s.selW = w
        s.selH = h
        pushUndo(s)
        var kept = 0
        for (i in undoStack.indices.reversed()) {
            val st = undoStack[i]
            if (st.selBefore != null || st.selAfter != null) {
                kept++
                if (kept > 12) {
                    st.selBefore = null
                    st.selAfter = null
                }
            }
        }
        redoStack.clear()
    }

    private fun applySelectionSnapshot(data: ByteArray?, w: Int, h: Int) {
        selection?.let { recycleLater(it) }
        selection = if (data == null) null else unpackAlpha(data, w, h)
        rebuildEdge()
        onSelectionChanged?.invoke(selection != null)
    }

    // ================= 选区 =================

    private fun normalizedSelRect(): RectF = RectF(
        min(selStart.x, selCurrent.x),
        min(selStart.y, selCurrent.y),
        max(selStart.x, selCurrent.x),
        max(selStart.y, selCurrent.y),
    )

    /** 缩放/平移期间被推迟掉的「边线位图 + 压暗图」，手势一结束补建一次 */
    private var edgeDeferred = false
    /** 缩放过程中选区被换掉（快速选择还原）：旧图不对，这时候先别画 */
    private var selStaleDuringZoom = false
    /** 后台重建的代数：回来的时候对不上号就整批扔掉 */
    private var edgeGen = 0
    /** 同一时间只跑一个重建任务，来新任务就排队等它做完再跑一次 */
    private var edgeBusy = false
    private var edgePending = false
    /** 后台重建还在读这张遮罩时先别回收，等它跑完再回收（否则 getPixels on recycled） */
    private val pendingRecycle = ArrayList<Bitmap>()

    private fun recycleLater(b: Bitmap?) {
        if (b == null || b.isRecycled) return
        if (edgeBusy) {
            if (!pendingRecycle.contains(b)) pendingRecycle.add(b)
        } else {
            b.recycle()
        }
    }

    private fun flushPendingRecycle() {
        if (edgeBusy) return
        for (b in pendingRecycle) if (!b.isRecycled) b.recycle()
        pendingRecycle.clear()
    }

    private fun rebuildEdge() {
        val mask = selection
        if (mask == null) {
            edgeGen++
            val oe = selectionEdge
            val od = selectionDim
            selectionEdge = null
            selectionDim = null
            selOutline = null
            selDimPath = null
            selHasPixels = false
            edgeDeferred = false
            selStaleDuringZoom = false
            if (oe != null && !oe.isRecycled) oe.recycle()
            if (od != null && !od.isRecycled) od.recycle()
            removeCallbacks(antsTicker)
            invalidate()
            return
        }
        // 双指缩放/平移期间先不算：描边 + 两张全幅位图在主线程就是几百毫秒，
        // 建了在缩放中也画不出来（见 drawSelection）。抬手时补一次。
        if (zooming) {
            edgeDeferred = true
            removeCallbacks(antsTicker)
            return
        }
        edgeDeferred = false
        selStaleDuringZoom = false
        if (edgeBusy) {
            edgePending = true
            return
        }
        edgeBusy = true
        val gen = ++edgeGen
        // 8000px 以上的文档，tracedOutline（全图描边）+ edgeBitmap + buildDimMask
        // 在主线程要几百毫秒，按一下选区就顿一下。整批扔后台，算完回来对号入座。
        Thread {
            var outline: Path? = null
            var has = false
            var edge: Bitmap? = null
            var dim: Bitmap? = null
            var dimPath: Path? = null
            var ok = true
            try {
                // 矢量轮廓：沿遮罩 50% 边界描一圈。魔棒/快速选择/羽化/反选/扩展收缩
                // 这些没有几何形状的操作，靠这一步也拿到清晰的一根线
                outline = tracedOutline(mask)
                // 有没有内容只算一次：以前每帧 onDraw 都整幅读一遍遮罩，缩放/绘制时掉帧
                has = maskHasContent(mask)
                edge = Selection.edgeBitmap(mask, 2)
                // 有矢量轮廓就用它做压暗，不再建全幅位图；轮廓算不出来才退回位图
                dimPath = if (has && outline != null) evenOddDimPath(outline, mask.width, mask.height) else null
                // 空选区不生成整幅压暗图：省一次全图运算，也不会出现「画面被关了灯」
                // 有矢量轮廓时压暗走 even-odd 路径，不再需要这张全幅位图
                dim = if (has && outline == null) buildDimMask(mask) else null
            } catch (e: Throwable) {
                // 遮罩在半路被回收（用户又换了选区）就整批作废，交给后面那次重建
                ok = false
            }
            post {
                edgeBusy = false
                flushPendingRecycle()
                if (!ok || gen != edgeGen) {
                    if (edge != null && !edge.isRecycled) edge.recycle()
                    if (dim != null && !dim.isRecycled) dim.recycle()
                } else {
                    selOutline = outline
                    selDimPath = dimPath
                    selHasPixels = has
                    val oldEdge = selectionEdge
                    selectionEdge = edge
                    if (oldEdge != null && !oldEdge.isRecycled && oldEdge !== edge) oldEdge.recycle()
                    val oldDim = selectionDim
                    selectionDim = dim
                    if (oldDim != null && !oldDim.isRecycled && oldDim !== dim) oldDim.recycle()
                    removeCallbacks(antsTicker)
                    if (selection != null) postDelayed(antsTicker, 90)
                    invalidate()
                }
                if (edgePending) {
                    edgePending = false
                    rebuildEdge()
                }
            }
        }.start()
    }

    /** 从遮罩描矢量轮廓；描不出来或者描歪了返回 null（退回位图边缘） */
    private fun tracedOutline(mask: Bitmap): Path? {
        val p = Selection.outlinePath(mask) ?: return null
        val b = RectF()
        p.computeBounds(b, true)
        if (b.isEmpty || b.width() < 0.5f || b.height() < 0.5f) return null
        if (b.right < -2f || b.bottom < -2f || b.left > mask.width + 2f || b.top > mask.height + 2f) return null
        return p
    }

    /** 压暗路径：整幅矩形 + 选区轮廓，偶奇填充得到的正好是「选区外」那一块 */
    private fun evenOddDimPath(outline: Path, w: Int, h: Int): Path {
        val p = Path()
        p.fillType = Path.FillType.EVEN_ODD
        p.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        p.addPath(outline)
        return p
    }

    /** 画一张「选区外压暗」的遮罩：整张铺半透明黑，再按选区挖洞 */
    private fun buildDimMask(mask: Bitmap): Bitmap? = try {
        val dim = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(dim)
        // 以前压 150（≈59%）：画面一暗就完全看不出选区在哪。90 在暗图上也能分辨
        c.drawColor(Color.argb(90, 0, 0, 0))
        val hole = Paint(Paint.FILTER_BITMAP_FLAG)
        hole.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        c.drawBitmap(mask, 0f, 0f, hole)
        dim
    } catch (e: OutOfMemoryError) {
        null
    }

    /** 把形状合进当前选区，然后通知界面 */
    /** 把上一次画出的形状存进路径面板 */
    fun storeLastPath(name: String = "路径 ${pathList.size + 1}"): Boolean {
        val p = lastShapePath ?: return false
        pathList.add(name to Path(p))
        return true
    }

    fun pathNames(): List<String> = pathList.map { it.first }

    fun deletePath(index: Int): Boolean {
        if (index !in pathList.indices) {
            return false
        }
        pathList.removeAt(index)
        return true
    }

    /** 把某条路径重新载入成选区 */
    fun loadPathAsSelection(index: Int): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        val path = pathList.getOrNull(index)?.second ?: return false
        commitShape(Selection.pathMask(bmp.width, bmp.height, path))
        return true
    }

    /** 选区转路径：沿选区蒙版的边界取轮廓，连成闭合环 */
    fun selectionToPath(name: String = "选区路径 ${pathList.size + 1}"): Boolean {
        val sel = selection ?: return false
        val path = Selection.outlinePath(sel) ?: return false
        pathList.add(name to path)
        return true
    }

    /** 把某条路径按当前前景色填进当前图层（可撤销） */
    fun paintPath(index: Int, fill: Boolean): Boolean {
        val path = pathList.getOrNull(index)?.second ?: return false
        return paintPathInto(path, fill, if (fill) "路径填充" else "路径描边")
    }

    private fun paintPathInto(path: Path, fill: Boolean, label: String): Boolean {
        if (isActiveLayerPaintingLocked()) return false
        val layer = activeLayer ?: return false
        val bmp = layer.bitmap ?: needBitmap(layer) ?: return false
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val pad = if (fill) 2 else (brushSize + 4f).toInt()
        val left = max(0, bounds.left.toInt() - pad)
        val top = max(0, bounds.top.toInt() - pad)
        val right = min(bmp.width, bounds.right.toInt() + pad)
        val bottom = min(bmp.height, bounds.bottom.toInt() + pad)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return false
        val before = IntArray(w * h)
        bmp.getPixels(before, 0, w, left, top, w, h)
        val c = Canvas(bmp)
        c.translate(-left.toFloat(), -top.toFloat())
        val p = shapePaint()
        p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        c.drawPath(path, p)
        val after = IntArray(w * h)
        bmp.getPixels(after, 0, w, left, top, w, h)
        layer.styleCache = null
        layer.topCache = null
        runCatching { layer.pix16?.applyFrom8Diff(left, top, w, h, before, after) }
        val st = Stroke().also { it.label = label }
        st.patches.add(Patch(layer, left, top, w, h, before, after))
        pushUndo(st)
        onDocChanged?.invoke()
        invalidate()
        return true
    }

    // ---------- 钢笔：锚点 → 贝塞尔路径 ----------

    fun penAddPoint(x: Float, y: Float) {
        penPoints.add(PointF(x, y))
        penClosed = false
        invalidate()
    }

    fun penUndoPoint() {
        if (penPoints.isNotEmpty()) penPoints.removeAt(penPoints.size - 1)
        invalidate()
    }

    fun penSetClosed(closed: Boolean) {
        penClosed = closed
        invalidate()
    }

    fun penClear() {
        penPoints.clear()
        penClosed = false
        invalidate()
    }

    fun penPointCount(): Int = penPoints.size

    /** 把锚点串成路径：平滑时用 Catmull-Rom 转三次贝塞尔，否则直接折线 */
    fun penPath(): Path {
        val p = Path()
        val pts = penPoints
        if (pts.isEmpty()) return p
        p.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) {
            p.lineTo(pts[0].x + 0.5f, pts[0].y + 0.5f)
            return p
        }
        if (!penSmooth) {
            for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        } else {
            for (i in 0 until pts.size - 1) {
                val p0 = pts[(i - 1).coerceAtLeast(0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
                val c1x = p1.x + (p2.x - p0.x) / 6f
                val c1y = p1.y + (p2.y - p0.y) / 6f
                val c2x = p2.x - (p3.x - p1.x) / 6f
                val c2y = p2.y - (p3.y - p1.y) / 6f
                p.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
            }
        }
        if (penClosed) p.close()
        return p
    }

    /** 钢笔路径 → 选区 */
    fun penToSelection(): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        if (penPoints.size < 2) return false
        val path = penPath()
        path.close()
        commitShape(Selection.pathMask(bmp.width, bmp.height, path))
        return true
    }

    /** 钢笔路径 → 填充 / 描边到当前图层 */
    fun penPaint(fill: Boolean): Boolean {
        if (penPoints.size < 2) return false
        val path = penPath()
        if (penClosed) path.close()
        val ok = paintPathInto(path, fill, if (fill) "钢笔填充" else "钢笔描边")
        if (ok) penClear()
        return ok
    }

    private fun drawPenPreview(canvas: Canvas) {
        if (tool != Tool.PEN || penPoints.isEmpty()) return
        val path = penPath()
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f / zoomScaleForUi()
            color = 0xFF3A7BFF.toInt()
        }
        cp.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(6f / zoomScaleForUi(), 5f / zoomScaleForUi()), 0f,
        )
        canvas.drawPath(path, cp)
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val dr = 3.5f / zoomScaleForUi()
        for (pt in penPoints) canvas.drawCircle(pt.x, pt.y, dr, dp)
        val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A7BFF.toInt() }
        for (pt in penPoints) canvas.drawCircle(pt.x, pt.y, dr * 0.55f, ap)
    }

    private fun zoomScaleForUi(): Float = runCatching { viewScale() }.getOrDefault(1f)

    // ---------- 消失点：四角平面 ----------

    fun vanishAddPoint(x: Float, y: Float) {
        if (vanishPoints.size >= 4) vanishPoints.clear()
        vanishPoints.add(PointF(x, y))
        invalidate()
    }

    fun vanishClear() {
        vanishPoints.clear()
        invalidate()
    }

    fun vanishUndoPoint() {
        if (vanishPoints.isNotEmpty()) vanishPoints.removeAt(vanishPoints.size - 1)
        invalidate()
    }

    fun vanishPointCount(): Int = vanishPoints.size

    private fun vanishQuad(): Path? {
        if (vanishPoints.size < 4) return null
        val p = Path()
        p.moveTo(vanishPoints[0].x, vanishPoints[0].y)
        for (i in 1 until 4) p.lineTo(vanishPoints[i].x, vanishPoints[i].y)
        p.close()
        return p
    }

    /** 四角平面 → 选区 */
    fun vanishToSelection(): Boolean {
        val path = vanishQuad() ?: return false
        val bmp = activeLayer?.bitmap ?: return false
        commitShape(Selection.pathMask(bmp.width, bmp.height, path))
        return true
    }

    /**
     * 把四角平面「拉正」成一个正向矩形，作为一个新图层贴回原位（等比缩放，不拉伸）。
     * 用的是 4 点透视矩阵（Matrix.setPolyToPoly），不是仿射近似。
     */
    fun vanishStraighten(): Boolean {
        val quad = vanishQuad() ?: return false
        val layer = activeLayer ?: return false
        val src = layer.bitmap ?: return false
        val p = vanishPoints
        val wTop = kotlin.math.hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val wBottom = kotlin.math.hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val hLeft = kotlin.math.hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val hRight = kotlin.math.hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        val outW = max(wTop, wBottom).toInt().coerceIn(8, 8192)
        val outH = max(hLeft, hRight).toInt().coerceIn(8, 8192)
        val flat = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val fc = Canvas(flat)
        val m = Matrix()
        val ok = m.setPolyToPoly(
            floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0,
            floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()), 0,
            4,
        )
        if (!ok) { flat.recycle(); return false }
        fc.concat(m)
        fc.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val bx = p.minOf { it.x }
        val by = p.minOf { it.y }
        val bw = (p.maxOf { it.x } - bx).coerceAtLeast(1f)
        val bh = (p.maxOf { it.y } - by).coerceAtLeast(1f)
        val fit = min(bw / outW, bh / outH)
        val dw = (outW * fit).toInt().coerceAtLeast(1)
        val dh = (outH * fit).toInt().coerceAtLeast(1)
        val scaled = if (dw == outW && dh == outH) flat else Bitmap.createScaledBitmap(flat, dw, dh, true)
        if (scaled !== flat) flat.recycle()
        val idx = addLayer("透视校正")
        if (idx < 0) { scaled.recycle(); return false }
        val dst = layers[idx].bitmap ?: return false
        Canvas(dst).drawBitmap(
            scaled,
            bx + (bw - dw) / 2f,
            by + (bh - dh) / 2f,
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        scaled.recycle()
        vanishPoints.clear()
        invalidate()
        return true
    }

    /** 画网格点和网格线（只在变形工具下画） */
    private fun drawWarpMesh(canvas: Canvas) {
        val layer = activeLayer ?: return
        val mesh = layer.warpPts ?: return
        val bmp = layer.bitmap ?: return
        val sc = viewScale().coerceAtLeast(0.05f)
        val pts = FloatArray(32)
        for (i in 0 until 16) {
            pts[i * 2] = mesh[i * 2] * bmp.width
            pts[i * 2 + 1] = mesh[i * 2 + 1] * bmp.height
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.style = Paint.Style.STROKE
        line.strokeWidth = 1.2f / sc
        line.color = 0x33000000.toInt()
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                val i = (r * 4 + c) * 2
                if (c < 3) canvas.drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], line)
                if (r < 3) canvas.drawLine(pts[i], pts[i + 1], pts[i + 8], pts[i + 9], line)
            }
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = 0xFF2F80ED.toInt()
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 1.5f / sc
        ring.color = 0xFFFFFFFF.toInt()
        val rad = 7f / sc
        for (i in 0 until 16) {
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], rad, dot)
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], rad, ring)
        }
    }
    private fun drawVanishPreview(canvas: Canvas) {
        if (tool != Tool.VANISH || vanishPoints.isEmpty()) return
        val s = zoomScaleForUi()
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f / s
            color = 0xFFFFB300.toInt()
        }
        lp.pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f / s, 5f / s), 0f)
        val path = Path()
        path.moveTo(vanishPoints[0].x, vanishPoints[0].y)
        for (i in 1 until vanishPoints.size) path.lineTo(vanishPoints[i].x, vanishPoints[i].y)
        if (vanishPoints.size == 4) path.close()
        canvas.drawPath(path, lp)
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt() }
        val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val r = 4.5f / s
        vanishPoints.forEachIndexed { i, pt ->
            canvas.drawCircle(pt.x, pt.y, r, wp)
            canvas.drawCircle(pt.x, pt.y, r * 0.6f, dp)
            if (i < 0) canvas.drawCircle(pt.x, pt.y, 1f, dp)
        }
    }

    // ---------- 智能对象 ----------

    fun isSmartLayer(index: Int): Boolean = layers.getOrNull(index)?.smartSrc != null

    /** 转为智能对象：把当前像素存成只读源 */
    fun makeSmartObject(index: Int): Boolean {
        val l = layers.getOrNull(index) ?: return false
        if (l.smartSrc != null) return true
        val bmp = l.bitmap ?: return false
        l.smartSrc = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        l.smartMatrix = Matrix()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 智能对象还原：丢掉所有变换，按原图重新放回原位 */
    fun resetSmartObject(index: Int): Boolean {
        val l = layers.getOrNull(index) ?: return false
        val src = l.smartSrc ?: return false
        val doc = layers.firstOrNull()?.bitmap ?: return false
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        replaceLayerAt(index, out)
        layers[index].smartSrc = src
        layers[index].smartMatrix = Matrix()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 换内容：把智能对象里的图换掉，尺寸不同就等比放进画布中央 */
    fun replaceSmartContent(index: Int, bmp: Bitmap): Boolean {
        val l = layers.getOrNull(index) ?: return false
        if (l.smartSrc == null) return false
        val doc = layers.firstOrNull()?.bitmap ?: return false
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val sc = min(doc.width.toFloat() / bmp.width, doc.height.toFloat() / bmp.height)
        val dw = bmp.width * sc
        val dh = bmp.height * sc
        val m = Matrix()
        m.postScale(sc, sc)
        m.postTranslate((doc.width - dw) / 2f, (doc.height - dh) / 2f)
        Canvas(out).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        replaceLayerAt(index, out)
        layers[index].smartSrc = bmp.copy(Bitmap.Config.ARGB_8888, false)
        layers[index].smartMatrix = Matrix()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 图层复合：记住「哪些层可见 + 当前是哪层」，一键切回去 */
    data class LayerComp(val name: String, val vis: List<Boolean>, val active: Int)

    val layerComps = mutableListOf<LayerComp>()

    fun saveLayerComp(name: String): Int {
        if (layers.isEmpty()) return 0
        layerComps.add(LayerComp(name, layers.map { it.visible }, activeIndex))
        return layerComps.size
    }

    fun applyLayerComp(i: Int): Boolean {
        val c = layerComps.getOrNull(i) ?: return false
        layers.forEachIndexed { idx, l -> l.visible = c.vis.getOrNull(idx) ?: l.visible }
        if (layers.isNotEmpty()) activeIndex = c.active.coerceIn(0, layers.size - 1)
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    fun deleteLayerComp(i: Int): Boolean = if (i in layerComps.indices) {
        layerComps.removeAt(i)
        true
    } else false

    fun layerCompNames(): List<String> = layerComps.map { it.name }

    /**
     * 透视裁剪：把已经点好的 4 个角拉正，整张文档换成拉正后的画面（等同于 桌面端的透视裁切）。
     * 与「拉正为新图层」不同：这里会丢掉四边形外面的内容，文档尺寸也改成拉正后的尺寸。
     */
    fun perspectiveCrop(): Boolean {
        val p = vanishPoints
        if (p.size < 4) return false
        val src = activeLayer?.bitmap ?: return false
        val wTop = kotlin.math.hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val wBottom = kotlin.math.hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val hLeft = kotlin.math.hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val hRight = kotlin.math.hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        val outW = max(wTop, wBottom).toInt().coerceIn(16, 8192)
        val outH = max(hLeft, hRight).toInt().coerceIn(16, 8192)
        val flat = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val fc = Canvas(flat)
        fc.drawColor(Color.WHITE)
        val m = Matrix()
        val ok = m.setPolyToPoly(
            floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0,
            floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()), 0,
            4,
        )
        if (!ok) {
            flat.recycle()
            return false
        }
        fc.concat(m)
        fc.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        newDocument(outW, outH, true)
        replaceLayerAt(0, flat, "透视裁剪")
        vanishClear()
        return true
    }

    /** 两个矢量形状按当前运算方式做路径布尔；做不出来返回 null */
    private fun mergeOutline(a: Path, b: Path): Path? {
        val pop = when (selectOp) {
            SelectOp.ADD -> Path.Op.UNION
            SelectOp.SUB -> Path.Op.DIFFERENCE
            SelectOp.INTERSECT -> Path.Op.INTERSECT
            else -> return null
        }
        val merged = Path()
        return if (merged.op(a, b, pop)) merged else null
    }

    private fun commitShape(shape: Bitmap, outline: Path? = null) {
        if (isActiveLayerPaintingLocked()) return
        // 活动层是调整层/组/空层时它没有 bitmap，以前直接 return —— 用户点了半天
        // 一点选区都不出来（「快速选择会消失」就是这么来的）。改用任意有像素的层取尺寸。
        val bmp = activeLayer?.bitmap
            ?: layers.firstNotNullOfOrNull { it.bitmap }?.let { it }
        if (bmp == null) {
            shape.recycle()
            onHint?.invoke("这一层没有像素，先选一个画过东西的图层")
            return
        }
        val current = selection
        val prevOutline = selOutline
        val selBefore = current?.let { packAlpha(it) }
        // 没有选区时「减选/交叉」没有可减的东西：直接忽略，别把整张画面反转成选区
        if (current == null && (selectOp == SelectOp.SUB || selectOp == SelectOp.INTERSECT)) {
            shape.recycle()
            return
        }
        // 已有选区时「新建」按加选处理：换工具不该把之前选好的区域冲掉，
        // 想从头开始就点「取消选区」。
        val effOp = if (current != null && selectOp == SelectOp.NEW) SelectOp.ADD else selectOp
        if (current == null) {
            val base = Selection.blank(bmp.width, bmp.height)
            Selection.apply(base, shape, SelectOp.NEW)
            selection = base
        } else {
            Selection.apply(current, shape, effOp)
        }
        shape.recycle()
        pushSelectionStroke(selBefore, bmp.width, bmp.height)
        rebuildEdge()
        val traced = selOutline
        // 优先用精确几何轮廓：新建用新形状，加减交两边都是矢量就做路径布尔运算；
        // 做不了（有一边是位图，或运算失败）就用刚描出来的遮罩轮廓
        selOutline = when {
            effOp == SelectOp.NEW -> outline ?: traced
            // 减选不走矢量布尔运算：两个路径相减会在原边界上留一条极细的缝，
            // 画出来就是用户看到的那条「多余的蚂蚁线」。直接用合并后的遮罩重新描。
            effOp == SelectOp.SUB -> traced
            prevOutline != null && outline != null -> mergeOutline(prevOutline, outline) ?: traced
            else -> traced
        }
        onSelectionChanged?.invoke(selection != null)
        invalidate()
    }

    /** 把某个颜色通道按阈值载入成选区（桌面端通道面板的「载入通道为选区」）。
     *  channel: 0=红 1=绿 2=蓝 3=Alpha；亮度 >= threshold 记为选中。 */
    fun channelToSelection(channel: Int, threshold: Int = 128): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        var hit = 0
        for (i in px.indices) {
            val p = px[i]
            val v = when (channel) {
                0 -> (p shr 16) and 0xFF
                1 -> (p shr 8) and 0xFF
                2 -> p and 0xFF
                else -> (p ushr 24) and 0xFF
            }
            if (v >= threshold) {
                out[i] = 0xFFFFFFFF.toInt()
                hit++
            }
        }
        if (hit == 0) return false
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(out, 0, w, 0, 0, w, h)
        val selBefore = selection?.let { packAlpha(it) }
        selection?.let { recycleLater(it) }
        selection = mask
        pushSelectionStroke(selBefore, w, h)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
        return true
    }

    fun selectAll() {
        val bmp = activeLayer?.bitmap ?: return
        val selBefore = selection?.let { packAlpha(it) }
        val base = Selection.blank(bmp.width, bmp.height)
        Selection.fill(base)
        selection?.let { recycleLater(it) }
        selection = base
        pushSelectionStroke(selBefore, bmp.width, bmp.height)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    fun invertSelection() {
        val bmp = activeLayer?.bitmap ?: return
        var mask = selection
        if (mask == null) {
            // 没选区时反选 = 选整张
            selectAll()
            return
        }
        val selBefore = packAlpha(mask)
        if (!mask.isMutable) {
            // 载入工程/存储选区/快速选择留下的都是不可变副本，Canvas(mask) 会直接抛异常
            val m2 = mask.copy(Bitmap.Config.ARGB_8888, true)
            if (m2 != null) {
                mask.recycle()
                selection = m2
            }
        }
        Selection.invert(selection ?: mask)
        pushSelectionStroke(selBefore, bmp.width, bmp.height)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 用户主动「取消选择」：可撤销 */
    fun clearSelectionUndoable() {
        val mask = selection ?: return
        val selBefore = packAlpha(mask)
        val w = mask.width
        val h = mask.height
        clearSelection()
        pushSelectionStroke(selBefore, w, h)
    }

    fun clearSelection() {
        removeCallbacks(antsTicker)
        selection?.let { recycleLater(it) }
        selection = null
        selOutline = null
        selectionEdge?.recycle()
        selectionEdge = null
        selectionDim = null
        polyPoints.clear()
        lassoPath = null
        selDragging = false
        wandPoint = null
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    /** 选区外接矩形（按 mask 的 alpha 判断），空选区返回 null */
    /** 色彩范围：整层里和采样色相近的像素都选中 */
    fun selectColorRange(tolerance: Float, target: Int = brushColor) {
        val bmp = sampleSource(false) ?: return
        val mask = Selection.colorRange(bmp, target, tolerance)
        if (mask == null) {
            onHint?.invoke("这个容差下没找到相近的颜色：把「容差」调大一点再点")
            return
        }
        commitShape(mask)
    }

    /**
     * 选择主体：图层大面积透明时，把不透明的部分当主体；
     * 有背景时把四条边上的颜色量化成一张 32k 的查表，再扫一遍像素。
     * （早先每个像素都要跟最多 96 个背景色算距离，2M 像素就是 2 亿次，点一下就卡住。）
     */
    /**
     * 兜底的主体抠图：把四条边上出现过的颜色收成一个集合（量化到 5 位，避免渐变散开），
     * 整张图里颜色不在这个集合中的像素当主体，最后只留最大一块。
     */
    private fun borderDistanceMask(px: IntArray, w: Int, h: Int): Bitmap? {
        val bag = HashSet<Int>()
        fun q(c: Int): Int {
            val r = (c and 0xFF) shr 3
            val g = ((c shr 8) and 0xFF) shr 3
            val b = ((c shr 16) and 0xFF) shr 3
            return r or (g shl 5) or (b shl 10)
        }
        var x = 0
        while (x < w) {
            bag.add(q(px[x]))
            bag.add(q(px[(h - 1) * w + x]))
            x += 4
        }
        var y = 0
        while (y < h) {
            bag.add(q(px[y * w]))
            bag.add(q(px[y * w + w - 1]))
            y += 4
        }
        if (bag.isEmpty()) return null
        val out = IntArray(w * h)
        for (i in px.indices) {
            if (Color.alpha(px[i]) <= 8) continue
            if (!bag.contains(q(px[i]))) out[i] = Color.WHITE
        }
        val cand = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val biggest = Selection.largestComponent(cand)
        if (biggest !== cand) cand.recycle()
        return if (maskPercent(biggest) <= 1) {
            biggest.recycle()
            null
        } else biggest
    }

    fun selectSubject() {
        val bmp = sampleSource(false) ?: return
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var opaque = 0
        for (i in px.indices) if (Color.alpha(px[i]) > 8) opaque++
        var mask: Bitmap
        if (opaque < w * h / 3) {
            // 主体是画在透明层上的：透明的就是背景
            val out = IntArray(w * h)
            for (i in px.indices) if (Color.alpha(px[i]) > 8) out[i] = Color.WHITE
            mask = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        } else {
            // 从四个角用魔棒漫水，连成一片的就是背景；取反剩下的当主体，再只留最大一块。
            // （早先是把上百个边缘色逐个算距离，2M 像素就是两亿次比较，点一下卡死；结果也不稳。）
            // 滑块 2%~60%，直接 /100 会变成 0.3 这种大容差，漫水一口把整张图吃掉，
            // 取反之后什么都不剩（用户看到「选中 0%」）。按 0.002 换算 → 最大 0.12。
            val tol = (subjectTolerance.coerceIn(2f, 60f) * 0.002f)
            // 沿整条四边同时播种漫水：只从四个角出发时，渐变或被栏杆切断的背景走不远，
            // 取反后大半张背景被当成了「主体」。多源 BFS 一次扫完，既准又快。
            val bg = Selection.borderWandMask(bmp, tol)
            val bgPx = IntArray(w * h)
            bg.getPixels(bgPx, 0, w, 0, 0, w, h)
            val out = IntArray(w * h)
            for (i in px.indices) {
                if (Color.alpha(bgPx[i]) < 128 && Color.alpha(px[i]) > 8) out[i] = Color.WHITE
            }
            bg.recycle()
            val cand = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            mask = Selection.largestComponent(cand)
            if (mask !== cand) cand.recycle()
            val pct0 = maskPercent(mask)
            if (pct0 <= 1 || pct0 >= 85) {
                // 四角漫水没成功（渐变/软边背景很常见）：退回「离四边颜色越远越像主体」
                mask.recycle()
                mask = borderDistanceMask(px, w, h) ?: Selection.blank(w, h)
            }
        }
        commitShape(mask)
    }

    /** 修改选区：0 羽化 / 1 平滑 / 2 扩展 / 3 收缩 / 4 边界 */
    /** 「选择并遮住」的基准图：面板打开/开始调时存一份，之后每次预览都从它算起，绝不就地叠加 */
    private var selEditBase: Bitmap? = null

    /** 撤销/重做换掉了当前选区：面板里留着的那份基准已经过期，作废掉，
     *  下一次预览会拿「现在的选区」重新取基准（否则预览会把撤销结果又盖回去） */
    private fun recycleSelEditBase() {
        selEditBase?.let { if (!it.isRecycled) it.recycle() }
        selEditBase = null
    }

    /** 「净化颜色」强度：输出新图层时把边缘半透像素往邻近实色去色，减少白边/彩边 */
    var selCleanAmount: Int = 0

    /** 智能半径的边缘阈值：越大越容易在弱边上停（手机上不同图片差别大，做成可调） */
    var selEdgeThr: Float = 0.22f

    /** 1/8 尺度图像梯度图：智能半径用它判断「走到真实边缘就停」 */
    private fun gradMap(guide: Bitmap?): FloatArray? {
        if (guide == null || guide.isRecycled) return null
        return try {
            val gw = (guide.width / 8).coerceAtLeast(2)
            val gh = (guide.height / 8).coerceAtLeast(2)
            val small = Bitmap.createScaledBitmap(guide, gw, gh, true)
            val p = IntArray(gw * gh)
            small.getPixels(p, 0, gw, 0, 0, gw, gh)
            if (small !== guide && !small.isRecycled) small.recycle()
            val lum = FloatArray(gw * gh) { i ->
                val c = p[i]
                (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
            }
            val g = FloatArray(gw * gh)
            for (y in 1 until gh - 1) for (x in 1 until gw - 1) {
                val i = y * gw + x
                val gx = (lum[i - gw + 1] + 2f * lum[i + 1] + lum[i + gw + 1]) -
                    (lum[i - gw - 1] + 2f * lum[i - 1] + lum[i + gw - 1])
                val gy = (lum[i + gw - 1] + 2f * lum[i + gw] + lum[i + gw + 1]) -
                    (lum[i - gw - 1] + 2f * lum[i - gw] + lum[i - gw + 1])
                g[i] = kotlin.math.sqrt(gx * gx + gy * gy).coerceIn(0f, 4f) / 4f
            }
            g
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 智能半径：扩张/收缩时遇到图像里的强边缘就停（桌面端「智能半径」的思路）。
     * 平坦的地方继续走，跨过头发/背景这种明显交界就不往下吃，边缘会「咬」在真实轮廓上。
     */
    private fun smartEdgeMask(mask: Bitmap, steps: Int, grow: Boolean): Bitmap {
        if (steps <= 0) return mask
        val guide = activeLayer?.bitmap
        val g = gradMap(guide)
        if (g == null || guide == null || guide.width < 16 || guide.height < 16) {
            return if (grow) Selection.grow(mask, steps) else Selection.shrink(mask, steps)
        }
        val w = mask.width
        val h = mask.height
        if (w < 4 || h < 4) return if (grow) Selection.grow(mask, steps) else Selection.shrink(mask, steps)
        val gw = (guide.width / 8).coerceAtLeast(2)
        val gh = (guide.height / 8).coerceAtLeast(2)
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val on = BooleanArray(w * h) { (px[it] ushr 24) > 127 }
        val dist = IntArray(w * h) { -1 }
        val q = IntArray(w * h)
        var head = 0
        var tail = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (on[i] == grow) continue
                val border = if (grow) {
                    on[i - 1] || on[i + 1] || on[i - w] || on[i + w]
                } else {
                    !on[i - 1] || !on[i + 1] || !on[i - w] || !on[i + w]
                }
                if (!border) continue
                dist[i] = 1
                q[tail++] = i
            }
        }
        val thr = selEdgeThr.coerceIn(0.03f, 0.8f)
        while (head < tail) {
            val i = q[head++]
            val d = dist[i]
            if (d >= steps) continue
            val x = i % w
            val y = i / w
            for (k in 0 until 4) {
                val nx = x + if (k == 0) -1 else if (k == 1) 1 else 0
                val ny = y + if (k == 2) -1 else if (k == 3) 1 else 0
                if (nx < 1 || nx >= w - 1 || ny < 1 || ny >= h - 1) continue
                val n = ny * w + nx
                if (on[n] == grow) continue
                if (dist[n] >= 0) continue
                val gx = (nx / 8).coerceIn(0, gw - 1)
                val gy = (ny / 8).coerceIn(0, gh - 1)
                if (g[gy * gw + gx] > thr) continue
                dist[n] = d + 1
                q[tail++] = n
            }
        }
        val outPx = IntArray(w * h)
        for (i in 0 until w * h) {
            val hit = dist[i] > 0
            val v = if (grow) (on[i] || hit) else (on[i] && !hit)
            outPx[i] = if (v) Color.argb(255, 255, 255, 255) else 0
        }
        return try {
            Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            if (grow) Selection.grow(mask, steps) else Selection.shrink(mask, steps)
        }
    }

    fun beginSelectionEdges() {
        selEditBase?.let { if (!it.isRecycled) it.recycle() }
        selEditBase = selection?.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun cancelSelectionEdges() {
        selEditBase?.let { if (!it.isRecycled) it.recycle() }
        selEditBase = null
    }

    /** 放弃这次预览：把选区还原成打开面板/切进工具时那一份（取消、关闭、切走工具都用它） */
    fun revertSelectionEdges() {
        val base = selEditBase ?: return
        selEditBase = null
        val prev = selection
        if (prev === base) return
        selection = base
        if (prev != null && !prev.isRecycled) prev.recycle()
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 过渡对比：把羽化过的软边收硬（0 = 不动，100 = 全硬） */
    private fun contrastMask(src: Bitmap, percent: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val k = (percent / 100f).coerceIn(0f, 1f)
        val span = ((1f - k) * (1f - k) * 0.5f + (1f - k) * 0.5f).coerceAtLeast(0.004f)
        for (i in 0 until w * h) {
            val a = Color.alpha(px[i]) / 255f
            val t = if (k <= 0f) a else (((a - 0.5f) / span) + 0.5f).coerceIn(0f, 1f)
            val na = (t * 255f).toInt().coerceIn(0, 255)
            px[i] = Color.argb(na, 255, 255, 255)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 预览/应用「选择并遮住」：羽化、平滑、扩张、收缩、边界、对比全部一次从基准图算完。
     * commit=false 只刷新画面；commit=true 表示抬手/点应用，落了这一次撤销。
     */
    fun applySelectionEdges(
        feather: Int, smooth: Int, grow: Int, shrink: Int, borderPx: Int, contrast: Int,
        commit: Boolean, smart: Boolean = false,
    ) {
        // 基准图万一被谁回收过（别处换选区时连它一起带走的可能），先兜一下
        if (selEditBase?.isRecycled == true) selEditBase = null
        val base = selEditBase ?: selection?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        if (selEditBase == null) selEditBase = base
        var m = base
        if (feather > 0) m = Selection.feather(m, feather)
        if (smooth > 0) m = Selection.smooth(m, smooth)
        if (grow > 0) m = if (smart) smartEdgeMask(m, grow, true) else Selection.grow(m, grow)
        if (shrink > 0) m = if (smart) smartEdgeMask(m, shrink, false) else Selection.shrink(m, shrink)
        if (borderPx > 0) m = Selection.border(m, borderPx)
        if (contrast > 0) m = contrastMask(m, contrast)
        // 参数全 0 时 m 就是基准图本身。不能把它直接交给 selection：两张指向同一张图之后，
        // 任何换选区时的 recycle 都会把基准图一起回收，下一次预览 getPixels 就崩（recycled bitmap）。
        // 这里复制一份，基准图永远只归 selEditBase 管。
        if (m === base) m = base.copy(Bitmap.Config.ARGB_8888, false)
        val prev = selection
        selection = m
        if (prev != null && !prev.isRecycled && prev !== base && prev !== m) prev.recycle()
        rebuildEdge()
        onSelectionChanged?.invoke(maskHasContent(m))
        invalidate()
        if (commit) {
            pushSelectionStroke(packAlpha(base), m.width, m.height)
            // m === base 时 base 就是现在的 selection，不能回收，否则后面画选区用的是一张已回收的位图
            selEditBase?.let { if (it !== m) it.recycle() }
            selEditBase = null
        }
    }

    /** 单项修改（老入口保留）：同样从基准图算，反复点不会越变越大 */
    fun modifySelection(kind: Int, px: Int) {
        if (selEditBase == null) selEditBase = selection?.copy(Bitmap.Config.ARGB_8888, false)
        when (kind) {
            0 -> applySelectionEdges(px, 0, 0, 0, 0, 0, true)
            1 -> applySelectionEdges(0, px, 0, 0, 0, 0, true)
            2 -> applySelectionEdges(0, 0, px, 0, 0, 0, true)
            3 -> applySelectionEdges(0, 0, 0, px, 0, 0, true)
            else -> applySelectionEdges(0, 0, 0, 0, px, 0, true)
        }
    }

    /** 变换选区：以中心缩放 + 平移 */
    fun transformSelection(scale: Float, dx: Float, dy: Float) {
        val mask = selection ?: return
        val before = packAlpha(mask)
        // 轴心必须用选区自己的包围盒中心：用画布中心的话，不居中的选区一放大就被推着跑
        val bb = maskBounds(mask)
        val cx = bb?.exactCenterX() ?: (mask.width / 2f)
        val cy = bb?.exactCenterY() ?: (mask.height / 2f)
        val m = Matrix()
        m.postTranslate(-cx, -cy)
        m.postScale(scale, scale)
        m.postTranslate(cx + dx, cy + dy)
        val prevOutline = selOutline
        val out = Selection.transformMask(mask, m)
        mask.recycle()
        selection = out
        pushSelectionStroke(before, out.width, out.height)
        rebuildEdge()
        // 轮廓直接跟着同一个矩阵走：比重描一遍更准，也省掉一次全图扫描
        if (prevOutline != null) {
            val moved = Path(prevOutline)
            moved.transform(m)
            val bb = RectF()
            moved.computeBounds(bb, true)
            if (!bb.isEmpty) selOutline = moved
        }
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 存储选区 */
    fun saveSelection() {
        val mask = selection ?: return
        savedSelections.add(mask.copy(Bitmap.Config.ARGB_8888, false))
    }

    /** 载入第 i 个存储的选区 */
    fun loadSelection(i: Int): Boolean {
        val m = savedSelections.getOrNull(i) ?: return false
        val cur = selection
        val before = cur?.let { packAlpha(it) }
        val copy = m.copy(Bitmap.Config.ARGB_8888, true)
        cur?.let { recycleLater(it) }
        selection = copy
        pushSelectionStroke(before, copy.width, copy.height)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
        return true
    }

    private fun maskBounds(mask: Bitmap): Rect? {
        val w = mask.width
        val h = mask.height
        val row = IntArray(w)
        var top = -1
        var bottom = -1
        var left = Int.MAX_VALUE
        var right = -1
        for (y in 0 until h) {
            mask.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1
            var last = -1
            for (x in 0 until w) {
                if ((row[x] ushr 24) != 0) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                if (top < 0) top = y
                bottom = y
                if (first < left) left = first
                if (last > right) right = last
            }
        }
        if (top < 0) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun pushPatch(before: IntArray, after: IntArray, r: Rect, label: String = "编辑") {
        pixelEpoch++
        val layer = activeLayer ?: return
        layer.styleVer++
        val stroke = Stroke().also { it.label = label }
        stroke.patches.add(Patch(layer, r.left, r.top, r.width(), r.height(), before, after))
        pushUndo(stroke)
    }

    /** 选区裁出的小图（白底+选区 alpha），用于填充/删除 */
    private fun maskedPatch(r: Rect, mask: Bitmap): Bitmap {
        val shape = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(shape)
        c.drawColor(Color.WHITE)
        val cut = Paint(Paint.FILTER_BITMAP_FLAG)
        cut.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(mask, -r.left.toFloat(), -r.top.toFloat(), cut)
        return shape
    }

    /** 用颜色填满选区 */
    fun fillSelection(color: Int): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        val mask = selection ?: return false
        val bounds = maskBounds(mask) ?: return false
        val r = Rect(
            max(0, bounds.left),
            max(0, bounds.top),
            min(bmp.width, bounds.right),
            min(bmp.height, bounds.bottom)
        )
        if (r.width() <= 0 || r.height() <= 0) return false
        val before = IntArray(r.width() * r.height())
        bmp.getPixels(before, 0, r.width(), r.left, r.top, r.width(), r.height())

        val shape = maskedPatch(r, mask)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        Canvas(bmp).drawBitmap(shape, r.left.toFloat(), r.top.toFloat(), paint)
        shape.recycle()

        val after = IntArray(r.width() * r.height())
        bmp.getPixels(after, 0, r.width(), r.left, r.top, r.width(), r.height())
        pushPatch(before, after, r)
        onDocChanged?.invoke()
        invalidate()
        return true
    }

    /** 删掉选区里的内容 */
    fun deleteSelection(): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        val mask = selection ?: return false
        val bounds = maskBounds(mask) ?: return false
        val r = Rect(
            max(0, bounds.left),
            max(0, bounds.top),
            min(bmp.width, bounds.right),
            min(bmp.height, bounds.bottom)
        )
        if (r.width() <= 0 || r.height() <= 0) return false
        val before = IntArray(r.width() * r.height())
        bmp.getPixels(before, 0, r.width(), r.left, r.top, r.width(), r.height())

        val shape = maskedPatch(r, mask)
        val clear = Paint(Paint.FILTER_BITMAP_FLAG)
        clear.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        Canvas(bmp).drawBitmap(shape, r.left.toFloat(), r.top.toFloat(), clear)
        shape.recycle()

        val after = IntArray(r.width() * r.height())
        bmp.getPixels(after, 0, r.width(), r.left, r.top, r.width(), r.height())
        pushPatch(before, after, r)
        onDocChanged?.invoke()
        invalidate()
        return true
    }

    /** 把选区内容复制到新图层 */
    fun duplicateSelectionLayer(): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        val mask = selection ?: return false
        val idx = addLayer("选区副本")
        if (idx < 0) return false
        val newBmp = layers[idx].bitmap ?: return false
        val tmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(tmp)
        c.drawBitmap(bmp, 0f, 0f, null)
        val cut = Paint(Paint.FILTER_BITMAP_FLAG)
        cut.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(mask, 0f, 0f, cut)
        Canvas(newBmp).drawBitmap(tmp, 0f, 0f, null)
        tmp.recycle()
        onDocChanged?.invoke()
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(antsTicker)
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /** 多边形：结束并闭合 */
    fun finishPolygon() {
        if (polyPoints.size < 3) {
            polyPoints.clear()
            invalidate()
            return
        }
        val path = Path()
        path.moveTo(polyPoints[0].x, polyPoints[0].y)
        for (i in 1 until polyPoints.size) path.lineTo(polyPoints[i].x, polyPoints[i].y)
        path.close()
        val bmp = activeLayer?.bitmap
        if (bmp != null) commitShape(Selection.pathMask(bmp.width, bmp.height, path), Path(path))
        polyPoints.clear()
        invalidate()
    }

    private fun commitLasso() {
        val path = lassoPath ?: return
        val bmp = activeLayer?.bitmap ?: return
        path.close()
        commitShape(Selection.pathMask(bmp.width, bmp.height, path), Path(path))
        lassoPath = null
    }

    private fun commitRectOrEllipse() {
        val bmp = activeLayer?.bitmap ?: return
        val rect = normalizedSelRect()
        if (rect.width() < 2f || rect.height() < 2f) return
        val shape = if (selectMode == SelectMode.ELLIPSE) {
            Selection.ellipseMask(bmp.width, bmp.height, rect)
        } else {
            Selection.rectMask(bmp.width, bmp.height, rect)
        }
        val outline = Path().apply {
            if (selectMode == SelectMode.ELLIPSE) addOval(rect, Path.Direction.CW)
            else addRect(rect, Path.Direction.CW)
        }
        commitShape(shape, outline)
    }

    /** 单行 / 单列选框 */
    private fun commitRowOrColumn() {
        val bmp = activeLayer?.bitmap ?: return
        val r = normalizedSelRect()
        val band = if (selectMode == SelectMode.ROW) {
            val y = r.centerY().coerceIn(0f, bmp.height - 1f)
            RectF(0f, y, bmp.width.toFloat(), y + 1f)
        } else {
            val x = r.centerX().coerceIn(0f, bmp.width - 1f)
            RectF(x, 0f, x + 1f, bmp.height.toFloat())
        }
        val shape = Selection.rectMask(bmp.width, bmp.height, band)
        val outline = Path().apply { addRect(band, Path.Direction.CW) }
        commitShape(shape, outline)
    }

    /** 磁性套索收尾：把吸附点连成闭合路径 */
    private fun commitMagnetic() {
        val pts = magnetPoints ?: return
        val bmp = activeLayer?.bitmap ?: return
        if (pts.size >= 3) {
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            path.close()
            commitShape(Selection.pathMask(bmp.width, bmp.height, path), Path(path))
        }
        magnetPoints = null
        magnetPixels = null
    }

    /** 采样统计遮罩选中比例（0~100），顺手把 16 进制颜色拼好 */
    /**
     * 选区里有没有内容。onDraw/预览每次都调，不能整幅 getPixels 分配一个几十兆的 IntArray：
     * 分块读、碰到第一个不透明像素就返回。
     */
    private fun maskHasContent(mask: Bitmap): Boolean {
        val w = mask.width
        if (w <= 0 || mask.height <= 0) return false
        val rows = 32
        val buf = IntArray(w * min(rows, mask.height))
        var y = 0
        while (y < mask.height) {
            val n = min(rows, mask.height - y)
            val ok = runCatching { mask.getPixels(buf, 0, w, 0, y, w, n) }.isSuccess
            if (!ok) return false
            val total = w * n
            for (i in 0 until total) {
                if ((buf[i] ushr 24) >= 32) return true
            }
            y += n
        }
        return false
    }

    private fun maskPercent(mask: Bitmap): Int {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0) return 0
        // 以前整幅 getPixels 再数一遍：4096 的图要分配 64MB，每次调用都卡一下。
        // 这个数只用来判断「选了多少」，不需要精确到像素，改成逐行读 + 大图隔行采样。
        val step = if (w.toLong() * h > 4_000_000L) 2 else 1
        val row = IntArray(w)
        var on = 0
        var total = 0
        var y = 0
        while (y < h) {
            mask.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                if ((row[x] ushr 24) >= 32) on++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0 else on * 100 / total
    }

    private fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    /** 整层都是透明的？（按步长抽样，2048² 也只查两万点，很快） */
    private fun layerIsBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        val n = w * h
        val px = try {
            IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        } catch (e: Throwable) {
            return false
        }
        val step = max(1, n / 20000)
        var i = 0
        while (i < n) {
            if (Color.alpha(px[i]) > 8) return false
            i += step
        }
        return true
    }

    /**
     * 选区取样用的底图。活动图层整层透明（新建的空层）时，取样落到整张画面的合成图上——
     * 否则魔棒会把整个画布当成同一种颜色全选、选择主体什么都找不到（用户 2026-11-07 报的症状）。
     */
    private fun sampleSource(all: Boolean): Bitmap? {
        val own = activeLayer?.bitmap ?: return null
        return if (all || layerIsBlank(own)) flatten() else own
    }

    private fun commitWand() {
        val p = wandPoint ?: return
        val own = activeLayer?.bitmap ?: return
        val sampled = if (wandSampleAll || layerIsBlank(own)) flatten() else null
        val bmp = sampled ?: own
        val mask = Selection.wandMask(
            bmp, p.x.toInt(), p.y.toInt(), wandTolerance, wandContiguous,
        ) ?: return
        // 注意：先把种子色/百分比读出来，再回收临时合成图，否则会在已回收的位图上取像素直接闪退
        val seed = bmp.getPixel(
            p.x.toInt().coerceIn(0, bmp.width - 1),
            p.y.toInt().coerceIn(0, bmp.height - 1),
        )
        val pct = maskPercent(mask)
        if (sampled != null && sampled !== own) sampled.recycle()
        // 已有选区时，魔棒点一下默认会「替换」掉它 —— 用户拿魔棒是来补的，所以这里改成加选，
        // 并在提示里说清怎么替换（先取消选区，或点「运算 → 新建」后清空再点）。
        val savedOp = selectOp
        if (selection != null && savedOp == SelectOp.NEW) {
            selectOp = SelectOp.ADD
            onHint?.invoke("已有选区：这次按加选处理；要替换就先点「取消选区」")
        }
        commitShape(mask)
        selectOp = savedOp
    }

    // ================= 触摸 =================

    // ================= 裁剪 =================

    private var cropActive = false
    private val cropRect = RectF()
    /** true = 只裁当前图层（画布尺寸不变）；false = 整张文档一起裁（桌面端行为，默认） */
    var cropOnlyActive: Boolean = false
    /** true = 图像大小只改当前图层的内容（画布尺寸不变）；false = 缩放整张文档（默认） */
    var resizeOnlyActive: Boolean = false
    private val cropStartRect = RectF()
    private var cropMode = 0
    private var cropDownX = 0f
    private var cropDownY = 0f
    var onCropChanged: ((Boolean) -> Unit)? = null

    private val cropDimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val cropFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }
    private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x24000000
    }
    private val cropHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val cropHandleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB3000000.toInt()
    }

    fun isCropping(): Boolean = cropActive

    fun beginCrop() {
        val bmp = activeLayer?.bitmap ?: return
        cropActive = true
        cropMode = 0
        cropRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        invalidate()
        onCropChanged?.invoke(true)
    }

    fun cancelCrop() {
        if (!cropActive) return
        cropActive = false
        cropMode = 0
        invalidate()
        onCropChanged?.invoke(false)
    }

    /** 按当前裁剪框真正裁掉，成功返回 true（所有图层一起裁，画布尺寸跟着变） */
    fun applyCrop(): Boolean {
        val bmp = activeLayer?.bitmap ?: return false
        if (!cropActive) return false
        var x = cropRect.left.toInt()
        var y = cropRect.top.toInt()
        x = x.coerceIn(0, max(0, bmp.width - 8))
        y = y.coerceIn(0, max(0, bmp.height - 8))
        val w = cropRect.width().toInt().coerceIn(8, bmp.width - x)
        val h = cropRect.height().toInt().coerceIn(8, bmp.height - y)
        cropActive = false
        cropMode = 0
        onCropChanged?.invoke(false)
        if (x == 0 && y == 0 && w == bmp.width && h == bmp.height) {
            invalidate()
            return false
        }
        if (cropOnlyActive) {
            // 只裁当前层：画布尺寸不动，别的图层一个像素都不碰
            val layer = layers.getOrNull(activeIndex)
            val src = layer?.let { needBitmap(it) }
            if (layer == null || src == null) {
                cropActive = false
                cropMode = 0
                onCropChanged?.invoke(false)
                invalidate()
                return false
            }
            val cx = x.coerceIn(0, max(0, src.width - 8))
            val cy = y.coerceIn(0, max(0, src.height - 8))
            val cw = cropRect.width().toInt().coerceIn(8, src.width - cx)
            val ch = cropRect.height().toInt().coerceIn(8, src.height - cy)
            val cutBmp = Bitmap.createBitmap(src, cx, cy, cw, ch)
            // 裁出来的小块贴到「画布尺寸」的透明层上原位置留着，画布不变、其它层不动
            val stage = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(stage).drawBitmap(cutBmp, cx.toFloat(), cy.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            layer.bitmap = stage
            layer.pix16 = null
            afterGeometryChange()
            return true
        }
        // 整张裁剪：先保证每层都是文档尺寸（小图先补到画布大小、贴左上），否则会被下面跳过，
        // 造成「有的层裁了有的没裁」和尺寸对不上
        val docW = docPixelWidth()
        val docH = docPixelHeight()
        if (docW > 0 && docH > 0) {
            for (layer in layers) {
                val src = needBitmap(layer) ?: continue
                if (src.width != docW || src.height != docH) {
                    val stage = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
                    Canvas(stage).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    layer.bitmap = stage
                    layer.pix16 = null
                }
            }
        }
        for (layer in layers) {
            val src = needBitmap(layer) ?: continue
            if (x + w > src.width || y + h > src.height) continue
            // 先按旧尺寸把 16 位缓冲备好（能从原始通道建就建），再裁
            val p16 = pix16ForGeometry(layer)
            val cut = Bitmap.createBitmap(src, x, y, w, h)
            layer.bitmap = if (cut === src) src else cut.copy(Bitmap.Config.ARGB_8888, true)
            // 裁剪是纯像素搬运，16 位缓冲跟着裁，别作废
            layer.pix16 = p16?.let { p ->
                if (p.width == src.width && p.height == src.height) p.cropRegion(x, y, w, h) else null
            }
            val mk = layer.mask
            if (mk != null && x + w <= mk.width && y + h <= mk.height) {
                layer.mask = Bitmap.createBitmap(mk, x, y, w, h).copy(Bitmap.Config.ARGB_8888, true)
            }
        }
        undoStack.clear()
        redoStack.clear()
        transformActive = false
        clearSelection()
        clearPreview()
        activeIndex = activeIndex.coerceIn(0, layers.lastIndex)
        fitToView()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    private fun cropHandles(): FloatArray = floatArrayOf(
        cropRect.left, cropRect.top,
        cropRect.right, cropRect.top,
        cropRect.right, cropRect.bottom,
        cropRect.left, cropRect.bottom,
        (cropRect.left + cropRect.right) / 2f, cropRect.top,
        cropRect.right, (cropRect.top + cropRect.bottom) / 2f,
        (cropRect.left + cropRect.right) / 2f, cropRect.bottom,
        cropRect.left, (cropRect.top + cropRect.bottom) / 2f
    )

    private fun cropBeginDrag(x: Float, y: Float) {
        cropStartRect.set(cropRect)
        cropDownX = x
        cropDownY = y
        val r = 34f / viewScale()
        val pts = cropHandles()
        cropMode = 0
        for (i in 0 until 8) {
            if (hypot(x - pts[i * 2], y - pts[i * 2 + 1]) <= r) {
                // 0..3 是四角：要给「两个方向一起改」的编号（10..13），
                // 否则拖角只动一条边，看着像拖不动 / 自己变成了正方形
                cropMode = if (i <= 3) 10 + i else 2 + i
                return
            }
        }
        if (cropRect.contains(x, y)) cropMode = 1
    }

    private fun cropDrag(x: Float, y: Float) {
        if (cropMode == 0) return
        val dx = x - cropDownX
        val dy = y - cropDownY
        val bmp = activeLayer?.bitmap ?: return
        val maxW = bmp.width.toFloat()
        val maxH = bmp.height.toFloat()
        var l = cropStartRect.left
        var t = cropStartRect.top
        var rgt = cropStartRect.right
        var bot = cropStartRect.bottom
        when (cropMode) {
            1 -> {
                val cw = cropStartRect.width()
                val ch = cropStartRect.height()
                l = (l + dx).coerceIn(0f, maxW - cw)
                t = (t + dy).coerceIn(0f, maxH - ch)
                rgt = l + cw
                bot = t + ch
            }
            2 -> l = (l + dx).coerceIn(0f, rgt - 24f)
            3 -> t = (t + dy).coerceIn(0f, bot - 24f)
            4 -> rgt = (rgt + dx).coerceIn(l + 24f, maxW)
            5 -> bot = (bot + dy).coerceIn(t + 24f, maxH)
            6 -> t = (t + dy).coerceIn(0f, bot - 24f)
            7 -> rgt = (rgt + dx).coerceIn(l + 24f, maxW)
            8 -> bot = (bot + dy).coerceIn(t + 24f, maxH)
            9 -> l = (l + dx).coerceIn(0f, rgt - 24f)
            // 四角：两个方向一起动（左上 / 右上 / 右下 / 左下）
            10 -> {
                l = (l + dx).coerceIn(0f, rgt - 24f)
                t = (t + dy).coerceIn(0f, bot - 24f)
            }
            11 -> {
                rgt = (rgt + dx).coerceIn(l + 24f, maxW)
                t = (t + dy).coerceIn(0f, bot - 24f)
            }
            12 -> {
                rgt = (rgt + dx).coerceIn(l + 24f, maxW)
                bot = (bot + dy).coerceIn(t + 24f, maxH)
            }
            13 -> {
                l = (l + dx).coerceIn(0f, rgt - 24f)
                bot = (bot + dy).coerceIn(t + 24f, maxH)
            }
        }
        cropRect.set(l, t, rgt, bot)
        invalidate()
    }

    private fun drawCropOverlay(canvas: Canvas) {
        if (!cropActive) return
        val bmp = activeLayer?.bitmap ?: return
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val r = cropRect
        canvas.drawRect(0f, 0f, w, r.top, cropDimPaint)
        canvas.drawRect(0f, r.bottom, w, h, cropDimPaint)
        canvas.drawRect(0f, r.top, r.left, r.bottom, cropDimPaint)
        canvas.drawRect(r.right, r.top, w, r.bottom, cropDimPaint)
        val s = 1.6f / viewScale()
        cropFramePaint.strokeWidth = s
        cropGridPaint.strokeWidth = s * 0.7f
        cropHandleEdgePaint.strokeWidth = s * 0.8f
        canvas.drawRect(r, cropFramePaint)
        for (i in 1..2) {
            val gx = r.left + r.width() * i / 3f
            val gy = r.top + r.height() * i / 3f
            canvas.drawLine(gx, r.top, gx, r.bottom, cropGridPaint)
            canvas.drawLine(r.left, gy, r.right, gy, cropGridPaint)
        }
        val pts = cropHandles()
        val hs = 9f / viewScale()
        for (i in 0 until 8) {
            val cx = pts[i * 2]
            val cy = pts[i * 2 + 1]
            canvas.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, cropHandlePaint)
            canvas.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, cropHandleEdgePaint)
        }
    }

    // ================= 图像 / 图层批量操作 =================

    private fun applyToAllLayers(op: (Bitmap) -> Bitmap?) {
        for (l in layers) {
            val src = needBitmap(l) ?: continue
            val out = op(src) ?: continue
            l.bitmap = if (out === src) src else (out.copy(Bitmap.Config.ARGB_8888, true) ?: out)
            val m = l.mask
            if (m != null) {
                val mo = op(m)
                l.mask = when {
                    mo == null -> null
                    mo === m -> m
                    else -> mo.copy(Bitmap.Config.ARGB_8888, true) ?: mo
                }
            }
        }
    }

    private fun afterGeometryChange() {
        undoStack.clear()
        redoStack.clear()
        transformActive = false
        // 换尺寸/旋转之后，合成缓存、金字塔、变换残留都得作废，
        // 不然屏幕上还是旧那张图，看着像「点了没反应」
        clearSelection()
        clearPreview()
        compositeDirty = true
        compositePixelStamp++
        dropMips()
        activeIndex = activeIndex.coerceIn(0, layers.lastIndex)
        fitToView()
        invalidate()
        onDocChanged?.invoke()
    }

    /** 旋转整张图（90 / 180 / 270），所有图层一起转 */
    fun rotateDocument(degrees: Int): Boolean {
        // 90 度的整数倍是纯像素搬运，16 位缓冲跟着转，不作废
        val quarters = (((degrees % 360) + 360) % 360) / 90
        var moved = 0
        var empty = 0
        layers.forEach { l ->
            val p = pix16ForGeometry(l)
            if (p == null) empty++ else {
                l.pix16 = p.rotateQuarters(quarters)
                moved++
            }
        }
        if (layers.isEmpty()) return false
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        applyToAllLayers { src -> Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) }
        afterGeometryChange()
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "p16geo", "op=rot" + degrees + " moved=" + moved + " empty=" + empty +
                    " dims=" + width + "x" + height
            )
        }
        return true
    }

    /** 水平 / 垂直翻转整张图 */
    fun flipDocument(horizontal: Boolean): Boolean {
        var moved = 0
        var empty = 0
        layers.forEach { l ->
            val p = pix16ForGeometry(l)
            if (p == null) empty++ else {
                l.pix16 = p.flipImage(horizontal)
                moved++
            }
        }
        if (layers.isEmpty()) return false
        val m = Matrix()
        if (horizontal) m.postScale(-1f, 1f) else m.postScale(1f, -1f)
        applyToAllLayers { src -> Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) }
        afterGeometryChange()
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "p16geo", "op=flip" + (if (horizontal) "H" else "V") + " moved=" + moved + " empty=" + empty
            )
        }
        return true
    }

    /** 只翻转当前图层（同尺寸，蒙版一起翻） */
    fun flipActiveLayer(horizontal: Boolean): Boolean {
        val layer = activeLayer ?: return false
        val src = needBitmap(layer) ?: return false
        val p16 = pix16ForGeometry(layer)
        val m = Matrix()
        if (horizontal) m.postScale(-1f, 1f) else m.postScale(1f, -1f)
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) ?: return false
        layer.bitmap = out
        layer.pix16 = p16?.flipImage(horizontal)
        layer.mask = layer.mask?.let { mk ->
            runCatching { Bitmap.createBitmap(mk, 0, 0, mk.width, mk.height, m, true) }.getOrNull()
        }
        layer.styleVer++
        clearSelection()
        clearPreview()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 只旋转当前图层（绕中心，画布大小不变，超出部分裁掉） */
    fun rotateActiveLayer(degrees: Float): Boolean {
        val layer = activeLayer ?: return false
        val src = needBitmap(layer) ?: return false
        if (src.width <= 0 || src.height <= 0) return false
        // 任意角度旋转要重采样：16 位缓冲按同一个矩阵一起转，不掉回 8 位
        val base16 = pix16ForGeometry(layer)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888) ?: return false
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rm = rotateMatrix(src.width, src.height, degrees)
        c.drawBitmap(src, rm, p)
        layer.bitmap = out
        if (base16 != null) layer.pix16 = base16.resampleMatrix(rm, out.width, out.height)
        val mk = layer.mask
        if (mk != null && mk.width == src.width && mk.height == src.height) {
            val mo = Bitmap.createBitmap(mk.width, mk.height, Bitmap.Config.ARGB_8888)
            if (mo != null) {
                Canvas(mo).drawBitmap(mk, rotateMatrix(mk.width, mk.height, degrees), p)
                layer.mask = mo
            }
        }
        layer.styleVer++
        clearSelection()
        clearPreview()
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    private fun rotateMatrix(w: Int, h: Int, degrees: Float): Matrix {
        val m = Matrix()
        m.postRotate(degrees, w / 2f, h / 2f)
        return m
    }

    /** 改图像大小（重采样所有图层） */
    /** 文档当前像素尺寸（取第一个有像素的图层；调整层/组没有像素） */
    fun docPixelWidth(): Int = layers.firstNotNullOfOrNull { needBitmap(it)?.width } ?: 0
    fun docPixelHeight(): Int = layers.firstNotNullOfOrNull { needBitmap(it)?.height } ?: 0

    fun resizeDocument(w: Int, h: Int): Boolean {
        if (layers.isEmpty()) return false
        val tw = w.coerceIn(16, 8192)
        val th = h.coerceIn(16, 8192)
        if (resizeOnlyActive) {
            // 只改当前图层：把这层缩放成用户填的 tw×th，画布尺寸不变、其它图层一个像素都不碰。
            // 原来算的是「等比装进 tw×th」，1024×1536 的层填 1080×1620 只放大 5.5%，
            // 用户看着像什么都没发生（他报的「当前图层看不出变化」就是这个）。
            val layer = layers.getOrNull(activeIndex) ?: return false
            val src = needBitmap(layer) ?: return false
            val docW = docPixelWidth().coerceAtLeast(1)
            val docH = docPixelHeight().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
            val stage = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
            Canvas(stage).drawBitmap(
                scaled,
                (docW - tw) / 2f,
                (docH - th) / 2f,
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            layer.bitmap = stage
            layer.pix16 = null
            afterGeometryChange()
            return true
        }
        // 先按旧尺寸把各层的 16 位缓冲备好，缩放后按同一比例重采样
        val bases = layers.map { pix16ForGeometry(it) }
        val srcBmp = layers.firstNotNullOfOrNull { needBitmap(it) }
        val srcW = srcBmp?.width ?: 0
        val srcH = srcBmp?.height ?: 0
        applyToAllLayers { src -> Bitmap.createScaledBitmap(src, tw, th, true) }
        if (srcW > 0 && srcH > 0) {
            val m = Matrix()
            m.postScale(tw.toFloat() / srcW, th.toFloat() / srcH)
            layers.forEachIndexed { i, l -> l.pix16 = bases.getOrNull(i)?.resampleMatrix(m, tw, th) }
        }
        afterGeometryChange()
        return true
    }

    /** 向下合并：当前层合到下一层（不透明度算进去，混合模式简化忽略） */
    /**
     * 合并图层/拼合图像时，把一层的 16 位颜色按「正常」混合叠进 acc。
     * 只接受 100% 不透明、正常混合、尺寸一致的层；别的层一律不动（调用方会退回 8 位）。
     */
    private fun overlay16(acc: com.reveal.editor.image.Pix16, layer: Layer) {
        if (layer.alpha != 255 || layer.blendMode != 0) return
        val p = layer.pix16 ?: return
        if (p.width != acc.width || p.height != acc.height) return
        val bmp = runCatching { needBitmap(layer) }.getOrNull() ?: return
        if (bmp.width != acc.width || bmp.height != acc.height) return
        val px = IntArray(acc.width * acc.height)
        bmp.getPixels(px, 0, acc.width, 0, 0, acc.width, acc.height)
        for (i in px.indices) {
            val a = px[i] ushr 24
            if (a == 0) continue
            if (a == 255) {
                acc.r[i] = p.r[i]
                acc.g[i] = p.g[i]
                acc.b[i] = p.b[i]
            } else {
                val f = a / 255f
                val k = 1f - f
                acc.r[i] = ((p.r[i].toInt() and 0xFFFF) * f + (acc.r[i].toInt() and 0xFFFF) * k).toInt().toShort()
                acc.g[i] = ((p.g[i].toInt() and 0xFFFF) * f + (acc.g[i].toInt() and 0xFFFF) * k).toInt().toShort()
                acc.b[i] = ((p.b[i].toInt() and 0xFFFF) * f + (acc.b[i].toInt() and 0xFFFF) * k).toInt().toShort()
            }
        }
    }

    /** 复制一份 16 位缓冲（合并且要保住精度时用） */
    private fun copy16(p: com.reveal.editor.image.Pix16): com.reveal.editor.image.Pix16 {
        val o = com.reveal.editor.image.Pix16(p.width, p.height)
        p.r.copyInto(o.r)
        p.g.copyInto(o.g)
        p.b.copyInto(o.b)
        return o
    }

    fun mergeDown(): Boolean {
        if (activeIndex <= 0) return false
        val cur = layers[activeIndex]
        val below = layers[activeIndex - 1]
        val up = bakedBitmap(cur) ?: return false
        val down = bakedBitmap(below) ?: return false
        val out = Bitmap.createBitmap(down.width, down.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val pb = Paint(Paint.FILTER_BITMAP_FLAG)
        pb.alpha = effectiveAlpha(below)
        applyBlendMode(pb, below.blendMode)
        c.drawBitmap(down, 0f, 0f, pb)
        resetBlend(pb)
        pb.alpha = effectiveAlpha(cur)
        applyBlendMode(pb, cur.blendMode)
        c.drawBitmap(up, 0f, 0f, pb)
        resetBlend(pb)
        val below16 = below.pix16
        val cur16 = cur.pix16
        below.bitmap = out
        below.mask = null
        cur.mask = null
        // 16 位：两层都齐、都是正常混合且满不透明时，把颜色也合到 16 位里，否则只能退回 8 位
        below.pix16 = if (below16 != null && cur16 != null &&
            below16.width == cur16.width && below16.height == cur16.height &&
            below.alpha == 255 && below.blendMode == 0
        ) {
            val acc = copy16(below16)
            overlay16(acc, cur)
            acc
        } else null
        // 合并后必须看得见：底下那层原本若隐藏/半透明，内容会「凭空消失」
        below.visible = true
        below.alpha = 255
        below.clipped = false
        layers.removeAt(activeIndex)
        activeIndex = (activeIndex - 1).coerceAtLeast(0)
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 拼合图像：所有可见图层合成一层 */
    fun flattenAll(): Boolean {
        if (layers.size <= 1) return false
        val base = layers.firstOrNull()?.let { needBitmap(it) } ?: return false
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (l in layers) {
            val b = bakedBitmap(l) ?: continue
            if (!l.visible) continue
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.alpha = effectiveAlpha(l)
            c.drawBitmap(b, 0f, 0f, p)
        }
        // 拼合也尽量保住 16 位：底层有 16 位缓冲就从它起步，再依次把上面各层的 16 位叠上去
        var flat16: com.reveal.editor.image.Pix16? = null
        val vis = layers.filter { it.visible }
        val bottom = vis.firstOrNull()
        val bottom16 = bottom?.pix16
        if (bottom != null && bottom16 != null && bottom.alpha == 255 && bottom.blendMode == 0 &&
            bottom16.width == out.width && bottom16.height == out.height
        ) {
            val acc = copy16(bottom16)
            for (l in vis.drop(1)) overlay16(acc, l)
            flat16 = acc
        }
        layers.clear()
        layers.add(Layer(out, "图层").also { it.pix16 = flat16 })
        activeIndex = 0
        undoStack.clear()
        redoStack.clear()
        clearSelection()
        clearPreview()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /** 油漆桶：填一块颜色相近的连通区域（有选区就只在选区内填） */
    fun bucketFill(x: Float, y: Float, tolerance: Float): Boolean {
        return try {
            bucketFillInner(x, y, tolerance)
        } catch (e: OutOfMemoryError) {
            undoStack.clear()
            redoStack.clear()
            invalidate()
            false
        }
    }

    private fun bucketFillInner(x: Float, y: Float, tolerance: Float): Boolean {
        if (isActiveLayerPaintingLocked()) return false
        val layer = activeLayer ?: return false
        val bmp = needBitmap(layer) ?: return false
        val ix = x.toInt()
        val iy = y.toInt()
        if (ix < 0 || iy < 0 || ix >= bmp.width || iy >= bmp.height) return false
        val w = bmp.width
        val h = bmp.height
        if (maskEditing && layer.mask != null) {
            return bucketFillMask(layer.mask!!, ix, iy, tolerance)
        }
        val mask = Flood.mask(bmp, ix, iy, tolerance)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var i = 0
        while (i < mask.size) {
            if (mask[i]) {
                val xx = i % w
                val yy = i / w
                if (xx < minX) minX = xx
                if (xx > maxX) maxX = xx
                if (yy < minY) minY = yy
                if (yy > maxY) maxY = yy
            }
            i++
        }
        if (maxX < 0) return false
        val color = brushColor
        val sel = selection
        val rect = Rect(minX, minY, maxX + 1, maxY + 1)
        val rw = rect.width()
        val rh = rect.height()
        val keepUndo = rw.toLong() * rh <= 6_000_000L
        val beforePart = if (keepUndo) IntArray(rw * rh) else null
        val afterPart = if (keepUndo) IntArray(rw * rh) else null
        val row = IntArray(w)
        val selRow = IntArray(w)
        var changed = false
        for (y in rect.top until rect.bottom) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            if (sel != null) sel.getPixels(selRow, 0, w, 0, y, w, 1)
            if (beforePart != null) System.arraycopy(row, rect.left, beforePart, (y - rect.top) * rw, rw)
            var rowChanged = false
            for (x in rect.left until rect.right) {
                if (!mask[y * w + x]) continue
                if (sel != null && Color.alpha(selRow[x]) <= 8) continue
                if (layer.alphaLock && Color.alpha(row[x]) == 0) continue
                if (row[x] == color) continue
                row[x] = color
                rowChanged = true
            }
            if (rowChanged) {
                bmp.setPixels(row, 0, w, 0, y, w, 1)
                if (afterPart != null) System.arraycopy(row, rect.left, afterPart, (y - rect.top) * rw, rw)
                changed = true
            }
        }
        if (!changed) return false
        if (beforePart != null && afterPart != null) {
            runCatching {
                if (docDepth == 32) ensurePix32(layer)?.applySolidDiff(rect.left, rect.top, rw, rh, beforePart, afterPart)
                else ensurePix16(layer)?.applySolidDiff(rect.left, rect.top, rw, rh, beforePart, afterPart)
            }.onFailure { com.reveal.editor.data.Diag.append(context, "fill16", it.toString()) }
            pushPatch(beforePart, afterPart, rect)
        } else {
            undoStack.clear()
            redoStack.clear()
            invalidate()
        }
        invalidate()
        onDocChanged?.invoke()
        return true
    }

    /** 当前有没有选区 */
    fun hasSelection(): Boolean = selection != null

    /** 选区的包围盒（文档像素）；没有选区或全透明返回 null */
    fun selectionBounds(): RectF? {
        val b = selection ?: return null
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 8) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > bt) bt = y
                }
            }
        }
        if (r < l || bt < t) return null
        return RectF(l.toFloat(), t.toFloat(), (r + 1).toFloat(), (bt + 1).toFloat())
    }

    /** 把当前选区映射成一个「范围」（用选区包围盒，选椭圆或矩形） */
    fun regionFromSelection(ellipse: Boolean = true): Boolean {
        val box = selectionBounds() ?: return false
        val spec = RegionSpec(
            kind = if (ellipse) 1 else 2,
            cx = (box.left + box.right) / 2f,
            cy = (box.top + box.bottom) / 2f,
            rx = (box.width() / 2f).coerceAtLeast(4f),
            ry = (box.height() / 2f).coerceAtLeast(4f),
            feather = regionFeather,
            name = "选区范围",
        )
        regionsS.add(spec)
        activeRegion = regionsS.lastIndex
        onDocChanged?.invoke()
        invalidate()
        return true
    }

    // ================= 网格变形 =================

    /** 一张 4×4 的标准网格（相对坐标 0..1） */
    private fun defaultWarpMesh(): FloatArray {
        val m = FloatArray(32)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                val i = (r * 4 + c) * 2
                m[i] = c / 3f
                m[i + 1] = r / 3f
            }
        }
        return m
    }

    /** 当前图层开始网格变形（没有网格就给一张标准的） */
    fun warpStart(): Boolean {
        val layer = activeLayer ?: return false
        if (layer.warpPts == null || layer.warpPts!!.size < 32) layer.warpPts = defaultWarpMesh()
        layer.warpOn = true
        invalidate()
        return true
    }

    /** 网格重置成标准田字格 */
    fun warpResetMesh() {
        val layer = activeLayer ?: return
        layer.warpPts = defaultWarpMesh()
        layer.warpOn = true
        invalidate()
        onDocChanged?.invoke()
    }

    /** 关掉网格变形（画面回到原样） */
    fun warpOff() {
        val layer = activeLayer ?: return
        layer.warpOn = false
        invalidate()
        onDocChanged?.invoke()
    }

    /** 找离图片坐标 (ix, iy) 最近的网格点，超过 tolerance 像素就不算 */
    fun warpPointNear(ix: Float, iy: Float, tolerance: Float): Int {
        val layer = activeLayer ?: return -1
        val mesh = layer.warpPts ?: return -1
        val bmp = needBitmap(layer) ?: return -1
        var best = -1
        var bestD = tolerance * tolerance
        for (i in 0 until 16) {
            val px = mesh[i * 2] * bmp.width
            val py = mesh[i * 2 + 1] * bmp.height
            val d = (px - ix) * (px - ix) + (py - iy) * (py - iy)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 把第 idx 个控制点移到图片坐标 (ix, iy) */
    fun warpMovePoint(idx: Int, ix: Float, iy: Float) {
        val layer = activeLayer ?: return
        val mesh = layer.warpPts ?: return
        if (idx < 0 || idx >= 16) return
        val bmp = needBitmap(layer) ?: return
        mesh[idx * 2] = (ix / bmp.width).coerceIn(-0.3f, 1.3f)
        mesh[idx * 2 + 1] = (iy / bmp.height).coerceIn(-0.3f, 1.3f)
        invalidate()
    }

    /** 网格点数（固定 16） */
    fun warpPointCount(): Int = 16

    /** 取当前网格（相对坐标），给面板画预览用 */
    fun warpMeshCopy(): FloatArray? = activeLayer?.warpPts?.copyOf()

    /** 把网格变形烘焙进图层像素（之后关掉 warpOn 也是变形后的样子） */
    fun warpBake(): Boolean {
        val layer = activeLayer ?: return false
        val mesh = layer.warpPts ?: return false
        val src = needBitmap(layer) ?: return false
        if (!layer.warpOn) return false
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val verts = FloatArray(32)
        for (i in 0 until 16) {
            verts[i * 2] = mesh[i * 2] * src.width
            verts[i * 2 + 1] = mesh[i * 2 + 1] * src.height
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmapMesh(src, 3, 3, verts, 0, null, 0, p)
        layer.bitmap = out
        layer.warpOn = false
        layer.warpPts = null
        layer.pix16 = null
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onDocChanged?.invoke()
        return true
    }
    /** 选区平滑：把参差的边缘磨圆 */
    fun smoothSelection(px: Int) {
        val m = selection ?: return
        selection = Selection.smooth(m, px.coerceIn(1, 60))
        rebuildEdge()
        onSelectionChanged?.invoke(!Selection.isEmpty(selection))
        invalidate()
    }

    /** 边缘位移：正数往外长，负数往里收 */
    fun shiftSelectionEdge(px: Int) {
        if (px == 0) return
        if (px > 0) growSelection(px) else shrinkSelection(-px)
    }

    /** 过渡对比：把羽化过的软边收硬（0 = 不动，100 = 全硬） */
    fun contrastSelection(percent: Int) {
        val m = selection ?: return
        val w = m.width
        val h = m.height
        val out = m.copy(Bitmap.Config.ARGB_8888, true)
        val src = IntArray(w * h)
        m.getPixels(src, 0, w, 0, 0, w, h)
        val k = (percent / 100f).coerceIn(0f, 1f)
        val span = ((1f - k) * (1f - k) * 0.5f + (1f - k) * 0.5f).coerceAtLeast(0.004f)
        for (i in 0 until w * h) {
            val a = Color.alpha(src[i]) / 255f
            val t = if (k <= 0f) a else (((a - 0.5f) / span) + 0.5f).coerceIn(0f, 1f)
            val na = (t * 255f).toInt().coerceIn(0, 255)
            src[i] = Color.argb(na, 255, 255, 255)
        }
        out.setPixels(src, 0, w, 0, 0, w, h)
        selection = out
        rebuildEdge()
        onSelectionChanged?.invoke(!Selection.isEmpty(out))
        invalidate()
    }

    /** 把当前选区变成当前图层的蒙版（选中处显示，没选的地方藏起来） */
    fun selectionAsMask(): Boolean {
        val layer = activeLayer ?: return false
        val sel = selection ?: return false
        val bmp = needBitmap(layer) ?: return false
        // 蒙版走的是 DST_IN（只看 alpha）：先铺不透明黑再叠选区，alpha 全是 255，等于没蒙版。
        // 直接拿选区的 alpha 当蒙版的 alpha，选中的地方露出、没选的遮住。
        val mw = bmp.width
        val mh = bmp.height
        val sw = sel.width
        val sh = sel.height
        val sp = IntArray(sw * sh)
        sel.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val mp = IntArray(mw * mh)
        val cw = min(sw, mw)
        val ch = min(sh, mh)
        for (y in 0 until ch) {
            val srcRow = y * sw
            val dstRow = y * mw
            for (x in 0 until cw) {
                val a = Color.alpha(sp[srcRow + x])
                if (a != 0) mp[dstRow + x] = Color.argb(a, 255, 255, 255)
            }
        }
        val m = Bitmap.createBitmap(mp, mw, mh, Bitmap.Config.ARGB_8888)
        layer.mask = m
        maskEditing = true
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return true
    }

    /**
     * 输出「新建带蒙版图层」：整层内容复制成新层，再挂一张选区蒙版。
     * 手机上没有图层叠层批注的需求，这个比「新建文档」有用得多，所以补这个、不补新建文档。
     */
    fun selectionToNewLayerWithMask(): Int {
        val layer = activeLayer ?: return -1
        val sel = selection ?: return -1
        val src = needBitmap(layer) ?: return -1
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val sw = sel.width
        val sh = sel.height
        val sp = IntArray(sw * sh)
        sel.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val mp = IntArray(w * h)
        val cw = min(sw, w)
        val ch = min(sh, h)
        for (y in 0 until ch) {
            val srcRow = y * sw
            val dstRow = y * w
            for (x in 0 until cw) {
                val a = Color.alpha(sp[srcRow + x])
                if (a != 0) mp[dstRow + x] = Color.argb(a, 255, 255, 255)
            }
        }
        val m = Bitmap.createBitmap(mp, w, h, Bitmap.Config.ARGB_8888)
        val insertAt = layers.size
        val nl = Layer(out, "选区蒙版层")
        nl.mask = m
        layers.add(insertAt, nl)
        activeIndex = insertAt
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return insertAt
    }

    /** 把选区内的画面抠成一个新图层 */
    fun selectionToNewLayer(): Int {
        val layer = activeLayer ?: return -1
        val sel = selection ?: return -1
        val src = needBitmap(layer) ?: return -1
        val w = src.width
        val h = src.height
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val mPx = IntArray(w * h)
        sel.getPixels(mPx, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        for (i in 0 until w * h) {
            val ma = Color.alpha(mPx[i])
            if (ma == 0) continue
            val c = srcPx[i]
            val a = (Color.alpha(c) * ma / 255f).toInt().coerceIn(0, 255)
            dst[i] = (c and 0x00FFFFFF) or (a shl 24)
        }
        // 净化颜色：把边缘的半透像素往邻近实色拉，抠图常见的白边/彩边就是这一步去掉的
        val clean = selCleanAmount.coerceIn(0, 100) / 100f
        if (clean > 0f) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val a = Color.alpha(dst[i])
                    if (a == 0 || a >= 250) continue
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var cn = 0
                    for (dy in -2..2) for (dx in -2..2) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        val j = yy * w + xx
                        if (Color.alpha(dst[j]) < 250) continue
                        val c2 = dst[j]
                        sr += Color.red(c2)
                        sg += Color.green(c2)
                        sb += Color.blue(c2)
                        cn++
                    }
                    if (cn == 0) continue
                    val c = dst[i]
                    val nr = (Color.red(c) + (sr / cn - Color.red(c)) * clean).toInt().coerceIn(0, 255)
                    val ng = (Color.green(c) + (sg / cn - Color.green(c)) * clean).toInt().coerceIn(0, 255)
                    val nb = (Color.blue(c) + (sb / cn - Color.blue(c)) * clean).toInt().coerceIn(0, 255)
                    dst[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        // 放到最上层：原先插在当前层上面，遇到上层是不透明的大图就完全看不见（用户以为没抠出来）
        val insertAt = layers.size
        layers.add(insertAt, Layer(out, "选区抠图"))
        activeIndex = insertAt
        undoStack.clear()
        redoStack.clear()
        invalidate()
        onActiveLayerChanged?.invoke()
        onDocChanged?.invoke()
        return insertAt
    }
    /** 选区羽化 */
    fun featherSelection(radius: Int) {
        val m = selection ?: return
        selection = Selection.feather(m, radius)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 扩大选区 */
    fun growSelection(px: Int) {
        val m = selection ?: return
        selection = Selection.grow(m, px)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    /** 收缩选区 */
    fun shrinkSelection(px: Int) {
        val m = selection ?: return
        selection = Selection.shrink(m, px)
        rebuildEdge()
        onSelectionChanged?.invoke(!Selection.isEmpty(selection))
        invalidate()
    }


    /**
     * 唯一的「带蒙版合成」实现（影视调色软件 / 桌面端用的都是这一条）：
     *
     *     out.rgb   = base + (corrected - base) * mask
     *     out.alpha = base.alpha          // 蒙版只是修正量的权重，永远不动不透明度
     *
     * maskA 里放的是每个像素的蒙版权重 0~255。allowAlpha 只有「本来就要改不透明度」的
     * 滤镜（色带抠图）才置 true。
     */
    private fun compositeWithMask(
        base: IntArray,
        corrected: IntArray,
        maskA: IntArray,
        allowAlpha: Boolean,
        out: IntArray,
    ) {
        for (i in base.indices) {
            val m = maskA[i]
            val cb = base[i]
            val ba = Color.alpha(cb)
            if (m <= 0) { out[i] = cb; continue }
            val ct = corrected[i]
            val ca = if (allowAlpha) Color.alpha(ct) else ba
            if (m >= 255) { out[i] = (ca shl 24) or (ct and 0x00FFFFFF); continue }
            val k = m / 255f
            val rr = (Color.red(ct) * k + Color.red(cb) * (1f - k)).toInt().coerceIn(0, 255)
            val gg = (Color.green(ct) * k + Color.green(cb) * (1f - k)).toInt().coerceIn(0, 255)
            val bb = (Color.blue(ct) * k + Color.blue(cb) * (1f - k)).toInt().coerceIn(0, 255)
            out[i] = (ca shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /** 把滤镜作用到当前图层；有选区就只改选区内 */
    fun applyFilterToActive(
        filter: (Bitmap) -> Bitmap?,
        label: String = "",
        /** 只有「本来就要改不透明度」的滤镜（色带抠图等）才置 true；调色永远不许改 alpha */
        allowAlpha: Boolean = false,
    ): Boolean {
        val layer = activeLayer ?: return false
        val src = needBitmap(layer) ?: return false
        val w = src.width
        val h = src.height
        val name = if (label.isEmpty()) "滤镜" else label
        // 有范围时：这次不是「烤进像素」，而是压进处理链 ——
        // 换工具再调会自然叠在上一次结果上，只有点「烧录」才真正落盘。
        if (regionsS.isNotEmpty() && !allowAlpha) {
            val ok = pushChainStep(name, filter)
            onHint?.invoke(if (ok) "已加到处理链（第 " + chainStepCount() + " 步）；点「烧录」才落像素" else "没有可用的底图")
            return ok
        }
        val heapMax = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        runCatching {
            com.reveal.editor.data.Diag.append(
                context, "filter", "name=$name depth=$docDepth size=" + w + "x" + h +
                    " 堆 " + heapUsed + "/" + heapMax + "MB"
            )
        }
        // 滤镜原来在主线程上整幅跑，1080×1620 就要几十秒，系统直接判「无响应」把 App 杀掉
        // （用户看到的「卡住然后闪退」就是 ANR，不是 Java 崩溃，所以崩溃记录里什么都没有）。
        // 现在：主线程只拍一张快照，重活全丢到后台线程，算完再回主线程换像素。
        val snapshot = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (snapshot == null) {
            onHint?.invoke("内存不够，先把别的图层隐藏或缩小画布再试")
            return false
        }
        val selCopy = selection?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        // 记住「这一刀是怎么切的」：范围参数（羽化/大小/强度/反转）改动时，
        // 用同一份原图 + 同一个滤镜 + 新的遮罩重算预览，而不是去改已经落层的像素。
        lastFilterBase = snapshot
        lastFilterFn = filter
        lastFilterLabel = name
        // 区域范围：有选中范围时，调色/滤镜只在它里面生效（和选区叠加：先区域后选区求交）
        // 只要画面上有范围就按范围裁（不能只看 activeRegion：没选中任何一条时它是 -1，
        // 那样会把范围整个忽略掉，调色直接作用于全图）
        val regionCopy = if (regionsS.isNotEmpty()) runCatching { regionMask() }.getOrNull() else null
        onHint?.invoke("正在应用$name…")
        Thread {
            val t0 = System.currentTimeMillis()
            try {
                val before = IntArray(w * h)
                snapshot.getPixels(before, 0, w, 0, 0, w, h)
                val filtered = filter(snapshot) ?: throw IllegalStateException("滤镜没有返回结果")
                val result = IntArray(w * h)
                if (selCopy != null || regionCopy != null) {
                    val selMaskSrc = selCopy
                    val top = IntArray(w * h)
                    val mask = IntArray(w * h)
                    filtered.getPixels(top, 0, w, 0, 0, w, h)
                    if (selMaskSrc != null) selMaskSrc.getPixels(mask, 0, w, 0, 0, w, h)
                    else java.util.Arrays.fill(mask, 0xFFFFFFFF.toInt())
                    if (regionCopy != null) {
                        // 与区域求交：区域外一律保留原像素
                        val rm = IntArray(w * h)
                        regionCopy.getPixels(rm, 0, w, 0, 0, w, h)
                        for (i in 0 until w * h) {
                            val ra = Color.alpha(rm[i])
                            if (ra < 255) {
                                val ma = Color.alpha(mask[i])
                                mask[i] = (ma * ra / 255) shl 24
                            }
                        }
                    }
                    // 统一走 compositeWithMask（蒙版只乘修正量，alpha 恒取原像素）
                    val maskA = IntArray(w * h)
                    for (i in 0 until w * h) maskA[i] = Color.alpha(mask[i])
                    compositeWithMask(before, top, maskA, allowAlpha, result)
                } else {
                    filtered.getPixels(result, 0, w, 0, 0, w, h)
                }
                // 自检：调色类滤镜一旦把不透明像素写成半透明，就是「圈外消失」在复发。
                // 记一笔到日志，出问题时能直接看到是哪一步漏了 alpha。
                if (!allowAlpha) {
                    var leak = 0
                    for (i in 0 until w * h) {
                        if ((before[i] ushr 24) == 255 && (result[i] ushr 24) < 255) leak++
                    }
                    if (leak > 0) {
                        runCatching {
                            com.reveal.editor.data.Diag.append(
                                context, "alpha",
                                "警告：$name 把 $leak 个不透明像素写成半透明（共 " + (w * h) + "）"
                            )
                        }
                    }
                }
                val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                outBmp.setPixels(result, 0, w, 0, 0, w, h)
                val ms = System.currentTimeMillis() - t0
                post {
                    // 回到主线程才换像素、记撤销；before 用快照时那份，撤销内容仍然正确
                    val stroke = Stroke().also { it.label = name }
                    stroke.patches.add(Patch(layer, 0, 0, w, h, before, result))
                    layer.bitmap = outBmp
                    syncPix16ByLut(layer, before, result, w, h)
                    pushUndo(stroke)
                    dropMips()
                    onDocChanged?.invoke()
                    invalidate()
                    // 以前只记了「开始」，日志里看不出到底跑完没有、花了多久 ——
                    // 现在和开始行成对出现，出问题一眼能对上。
                    runCatching {
                        val heap2 = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
                        com.reveal.editor.data.Diag.append(
                            context, "filter 完成",
                            "name=$name 耗时=" + ms + "ms size=" + w + "x" + h +
                                (if (selCopy != null) " 选区内" else "") +
                                " 堆 " + heap2 + "/" + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB"
                        )
                    }
                    onHint?.invoke("$name 已应用（" + ms + " ms）")
                }
            } catch (t: Throwable) {
                runCatching {
                    com.reveal.editor.data.Diag.append(
                        context, "filter 失败",
                        "name=$name size=" + w + "x" + h + " " + t.javaClass.name + ": " + t.message + "\n" +
                            android.util.Log.getStackTraceString(t)
                    )
                }
                post {
                    onHint?.invoke("$name 失败：" + t.javaClass.simpleName + "（详情看首页日志）")
                }
            }
        }.start()
        return true
    }

    /** 改画布大小：原图居中，四周补透明 */
    fun resizeCanvas(w: Int, h: Int): Boolean {
        if (layers.isEmpty()) return false
        val tw = w.coerceIn(16, 8192)
        val th = h.coerceIn(16, 8192)
        // 16 位缓冲跟着贴到新画布中间（不重采样，只搬位置）
        val bases = layers.map { pix16ForGeometry(it) }
        applyToAllLayers { src ->
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(src, ((tw - src.width) / 2f), ((th - src.height) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }
        layers.forEachIndexed { i, l ->
            val b = bases.getOrNull(i) ?: return@forEachIndexed
            l.pix16 = b.pasteInto(tw, th, (tw - b.width) / 2, (th - b.height) / 2)
        }
        afterGeometryChange()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasDocument) return false
        // 鼠标模式：单指拖动＝平移画面（指针由界面上那块白色鼠标控制，可以走到整个屏幕）
        if (mouseMode && !injectingTap && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mouseDownX = event.x
                    mouseDownY = event.y
                    mouseMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - mouseDownX
                    val dy = event.y - mouseDownY
                    if (abs(dx) > 1f || abs(dy) > 1f) {
                        viewMatrix.postTranslate(dx, dy)
                        mouseDownX = event.x
                        mouseDownY = event.y
                        mouseMoved = true
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
            }
            return true
        }
        updateTouchIndicator(event)
        // 曲线吸管：点一下画面取色阶，取完自动退出
        if (curvePickMode && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pickCurveTone(event.x, event.y)
                MotionEvent.ACTION_MOVE -> Unit
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                return true
            }
        }
        // 参考线：从标尺往画面里拖能拉出新的；移动工具下能直接拖已有参考线；拖回标尺就删掉
        if (guideDragIdx >= 0) {
            val band = 16f / viewScale().coerceAtLeast(0.05f)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (guideDragH) {
                        if (guideDragIdx < guideH.size) guideH[guideDragIdx] = event.y.coerceIn(0f, height.toFloat())
                    } else {
                        if (guideDragIdx < guideV.size) guideV[guideDragIdx] = event.x.coerceIn(0f, width.toFloat())
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val backInBand = if (guideDragH) event.y < band else event.x < band
                    if (backInBand) {
                        if (guideDragH) {
                            if (guideH.isNotEmpty()) guideH.removeAt(guideDragIdx.coerceIn(0, guideH.size - 1))
                        } else {
                            if (guideV.isNotEmpty()) guideV.removeAt(guideDragIdx.coerceIn(0, guideV.size - 1))
                        }
                    }
                    guideDragIdx = -1
                    invalidate()
                    return true
                }
            }
        }
        if (showRuler && event.actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount == 1) {
            val band = 16f / viewScale().coerceAtLeast(0.05f)
            if (event.y < band || event.x < band) {
                parent?.requestDisallowInterceptTouchEvent(true)
                guideDragH = event.y < band
                if (guideDragH) {
                    guideH.add(event.y.coerceIn(0f, height.toFloat()))
                    guideDragIdx = guideH.size - 1
                } else {
                    guideV.add(event.x.coerceIn(0f, width.toFloat()))
                    guideDragIdx = guideV.size - 1
                }
                invalidate()
                return true
            }
        }
        if (tool == Tool.MOVE && showGuides && event.actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount == 1) {
            // 移动工具下抓已有参考线：手指离某条线 14 像素内就抓住它
            var hi = -1
            var hd = 14f
            guideH.forEachIndexed { i, y ->
                val d = abs(y - event.y)
                if (d <= hd) { hd = d; hi = i }
            }
            if (hi >= 0) {
                parent?.requestDisallowInterceptTouchEvent(true)
                guideDragH = true
                guideDragIdx = hi
                return true
            }
            var vi = -1
            var vd = 14f
            guideV.forEachIndexed { i, x ->
                val d = abs(x - event.x)
                if (d <= vd) { vd = d; vi = i }
            }
            if (vi >= 0) {
                parent?.requestDisallowInterceptTouchEvent(true)
                guideDragH = false
                guideDragIdx = vi
                return true
            }
        }

        if (cropActive && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = toImage(event.x, event.y)
                    cropBeginDrag(p.x, p.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = toImage(event.x, event.y)
                    cropDrag(p.x, p.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cropMode = 0
                    return true
                }
            }
        }
        // 没选「范围」工具时，按在已有的圈里也能拖：调色面板开着（工具退成 MOVE）也能挪圈
        if (tool == Tool.MOVE && regionsS.isNotEmpty() && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = toImage(event.x, event.y)
                    if (regionHit(p.x, p.y) >= 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        regionPress(p.x, p.y)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (regionMoveMode != 0) {
                        val p = toImage(event.x, event.y)
                        regionMove(p.x, p.y)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (regionMoveMode != 0) {
                        regionRelease()
                        return true
                    }
                }
            }
        }
        if (tool == Tool.REGION && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val p = toImage(event.x, event.y)
                    regionPress(p.x, p.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = toImage(event.x, event.y)
                    regionMove(p.x, p.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    regionRelease()
                    return true
                }
            }
        }
        if (tool == Tool.GRADIENT && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val p = toImage(event.x, event.y)
                    gradX0 = p.x
                    gradY0 = p.y
                    gradX1 = p.x
                    gradY1 = p.y
                    gradActive = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gradActive) {
                        val p = toImage(event.x, event.y)
                        gradX1 = p.x
                        gradY1 = p.y
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = gradActive
                    gradActive = false
                    if (was && event.actionMasked == MotionEvent.ACTION_UP) applyGradient()
                    invalidate()
                    return true
                }
            }
        }
        if (tool == Tool.WARP && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val p = toImage(event.x, event.y)
                    val layer = activeLayer
                    if (layer != null && layer.warpPts == null) layer.warpPts = defaultWarpMesh()
                    layer?.warpOn = true
                    // 手指要够近才抓得住网格点，按屏幕像素换算成图片像素
                    val tol = 28f / viewScale().coerceAtLeast(0.05f)
                    warpDragIdx = warpPointNear(p.x, p.y, tol)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (warpDragIdx >= 0) {
                        val p = toImage(event.x, event.y)
                        warpMovePoint(warpDragIdx, p.x, p.y)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (warpDragIdx >= 0) onDocChanged?.invoke()
                    warpDragIdx = -1
                    invalidate()
                    return true
                }
            }
        }
        if (tool == Tool.SHAPE && event.pointerCount <= 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    shapeViewX0 = event.x
                    shapeViewY0 = event.y
                    shapeViewX = event.x
                    shapeViewY = event.y
                    val p = toImage(event.x, event.y)
                    shapeX0 = p.x
                    shapeY0 = p.y
                    shapeX1 = p.x
                    shapeY1 = p.y
                    shapeActive = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (shapeActive) {
                        var vx = event.x
                        var vy = event.y
                        // 直线吸附：45 度整数倍、误差 9 度以内就吸过去
                        shapeSnapDeg = -999f
                        if (shapeKind == 2 && shapeSnapAngle) {
                            val dxv = vx - shapeViewX0
                            val dyv = vy - shapeViewY0
                            val lenV = kotlin.math.hypot(dxv.toDouble(), dyv.toDouble()).toFloat()
                            if (lenV > 8f) {
                                val deg = Math.toDegrees(kotlin.math.atan2(dyv.toDouble(), dxv.toDouble())).toFloat()
                                val sn = Math.round(deg / 45f) * 45f
                                if (kotlin.math.abs(deg - sn) <= 9f) {
                                    val r = Math.toRadians(sn.toDouble())
                                    vx = shapeViewX0 + (kotlin.math.cos(r) * lenV).toFloat()
                                    vy = shapeViewY0 + (kotlin.math.sin(r) * lenV).toFloat()
                                    shapeSnapDeg = sn
                                }
                            }
                        }
                        shapeViewX = vx
                        shapeViewY = vy
                        val p = toImage(vx, vy)
                        shapeX1 = p.x
                        shapeY1 = p.y
                        // 按住第二根手指：等比（正方形/正圆）
                        shapeSquare = event.pointerCount >= 2
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (shapeActive && event.actionMasked == MotionEvent.ACTION_UP) commitShape()
                    shapeActive = false
                    invalidate()
                    return true
                }
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                runCatching { onCanvasDown?.invoke() }
                downTime = System.currentTimeMillis()
                maxPointers = 1
                movedDistance = 0f
                longPressFired = false
                removeCallbacks(longPressRunnable)
                transforming = false
                lastViewPoint = PointF(event.x, event.y)
                // 起笔就取一次笔压：不然第一小段用的是上一笔的压感，起笔处会突然粗一下
                strokePressure = if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    event.getPressure(0).coerceIn(0.02f, 1f)
                } else 1f
                lastImagePoint = toImage(event.x, event.y)
                // 画布外的空白处：不启动工具（否则选区工具会把手指当成在画布边缘起笔，
                // 一按就把选区改掉，还把长按菜单的定时器挤没了）
                downOutsideDoc = run {
                    val b = layers.firstOrNull()?.bitmap
                    b != null && (lastImagePoint.x < 0f || lastImagePoint.y < 0f ||
                        lastImagePoint.x >= b.width || lastImagePoint.y >= b.height)
                }
                // 长按弹「选区菜单」只在画布外面：画布里面长按是工具自己的事（画笔、吸管都不该被打断）
                if (selection != null && downOutsideDoc) postDelayed(longPressRunnable, 420)
                // 选区工具按下时先拍一张快照：万一第二根手指马上落下来（双指缩放），
                // 要把选区还原成按下之前的样子，不能因为缩放把选区改掉
                // 只有「快速选择」在按下的瞬间就会改选区，双指落下来才需要还原它。
                // 矩形/椭圆/行列/套索/多边形/魔棒拖动期间只记起点、抬手才提交，什么都不用还原；
                // 以前无条件 packAlpha 一张全幅（4096 就是 64MB）+ 起手/抬手各重建一次派生位图，
                // 这就是「有选区时双指缩放先卡住、松手才跳到目标比例」的原因。
                if (tool == Tool.SELECT && selectMode == SelectMode.QUICK) {
                    selSnapBefore = selection?.let { packAlpha(it) }
                    selSnapW = selection?.width ?: 0
                    selSnapH = selection?.height ?: 0
                } else if (tool == Tool.SELECT) {
                    selSnapBefore = null
                    selSnapW = 0
                    selSnapH = 0
                }
                // 双指缩放要做「无副作用」：按下时先记住会被改掉的东西
                prevCloneSource = cloneSource
                prevBrushColor = brushColor
                pinchAborted = false
                bucketPending = false
                if (downOutsideDoc) {
                    // 画布外按下：什么都不启动
                } else when (tool) {
                    Tool.REGION -> Unit
                    Tool.BRUSH, Tool.ERASER -> {
                        beginStroke()
                        drawSegment(lastImagePoint.x, lastImagePoint.y, lastImagePoint.x, lastImagePoint.y)
                    }
                    Tool.EYEDROPPER -> pickColor(event.x, event.y)
                    Tool.MOVE -> Unit
                    Tool.SELECT -> startSelection(lastImagePoint.x, lastImagePoint.y)
                    Tool.TEXT -> Unit
                    Tool.WARP -> Unit
                    Tool.TRANSFORM -> if (transformActive) {
                        // 双击画布 = 确认这次变换（桌面端里回车/双击都行，手机没有回车键）
                        val nowT = System.currentTimeMillis()
                        val sameSpot = kotlin.math.abs(lastImagePoint.x - lastTfTapX) < 24f / viewScale() &&
                            kotlin.math.abs(lastImagePoint.y - lastTfTapY) < 24f / viewScale()
                        if (nowT - lastTfTapAt < 340L && sameSpot) {
                            lastTfTapAt = 0L
                            if (applyTransform()) {
                                toastTransformApplied()
                            }
                        } else {
                            lastTfTapAt = nowT
                            lastTfTapX = lastImagePoint.x
                            lastTfTapY = lastImagePoint.y
                            if (tfPersp) tfPerspBeginDrag(lastImagePoint.x, lastImagePoint.y)
                            else tfBeginDrag(lastImagePoint.x, lastImagePoint.y)
                        }
                    }
                    // 油漆桶延到抬手再填：双指缩放时不该先把画面填了（桌面端Touch 也是抬起才填）
                    Tool.BUCKET -> { bucketPending = true }
                    Tool.DODGE, Tool.BURN, Tool.SMUDGE, Tool.MIXER, Tool.LIQUIFY -> {
                        beginStroke()
                        if (tool == Tool.SMUDGE || tool == Tool.MIXER) beginSmudge(lastImagePoint.x, lastImagePoint.y)
                        drawSegment(lastImagePoint.x, lastImagePoint.y, lastImagePoint.x, lastImagePoint.y)
                    }
                    Tool.GRADIENT -> Unit
                    Tool.SHAPE -> Unit
                    Tool.PEN -> Unit
                    Tool.VANISH -> Unit
                    Tool.ERASER_MAGIC -> Unit
                    Tool.REDEYE -> {
                        // 记下落点并点亮虚线框：拖动过程中要能看见自己框到哪了
                        redeyeStart = PointF(lastImagePoint.x, lastImagePoint.y)
                        redEyeDragging = true
                        invalidate()
                    }
                    Tool.REPLACE, Tool.SPONGE, Tool.ERASER_BG,
                    Tool.PATTERN, Tool.SPOT, Tool.HISTORY_BRUSH, Tool.ART_HISTORY -> {
                        beginStroke()
                        prepareMaskTool(lastImagePoint.x, lastImagePoint.y)
                        drawSegment(lastImagePoint.x, lastImagePoint.y, lastImagePoint.x, lastImagePoint.y)
                    }
                    Tool.CLONE, Tool.HEAL -> {
                        // 非对齐：每一笔都重新认取样点（桌面端取消「对齐」就是这个行为）
                        if (cloneSource != null && !cloneAligned) cloneSource = null
                        if (cloneSource == null) {
                            cloneSource = PointF(lastImagePoint.x, lastImagePoint.y)
                            onCloneSample?.invoke()
                        } else {
                            beginCloneStroke(lastImagePoint.x, lastImagePoint.y)
                            drawCloneSegment(lastImagePoint.x, lastImagePoint.y, lastImagePoint.x, lastImagePoint.y)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, event.pointerCount)
                if (event.pointerCount >= 2) {
                    zooming = true
                    pinchAborted = true
                    // 第二根手指落下 = 要缩放/平移：把这次按下触发的所有东西全部退回
                    gradActive = false
                    warpDragIdx = -1
                    shapeActive = false
                    bucketPending = false
                    cloneSource = prevCloneSource
                    if (tool == Tool.EYEDROPPER) brushColor = prevBrushColor
                    // 第二根手指落下 = 要缩放/平移，把这次按下刚加的多边形顶点撤掉
                    if (tool == Tool.SELECT && selectMode == SelectMode.POLY && polyAddedOnDown) {
                        if (polyPoints.isNotEmpty()) polyPoints.removeAt(polyPoints.lastIndex)
                        polyAddedOnDown = false
                        invalidate()
                    }
                    cancelStroke()
                    // 双指＝缩放/平移：把这次按下已经动过的选区还原回去
                    if (tool == Tool.SELECT && selSnapW > 0) {
                        quickMask?.recycle()
                        quickMask = null
                        applySelectionSnapshot(selSnapBefore, selSnapW, selSnapH)
                        selStaleDuringZoom = true
                        // 还原过了就清掉快照：抬手时不要再还原第二次、也不要再重建一遍派生位图
                        selSnapBefore = null
                        selSnapW = 0
                        selSnapH = 0
                        invalidate()
                    }
                    selDragging = false
                    lassoPath = null
                    // 任何工具（包括正在变换的）双指都是缩放/平移：原来的写法把变换工具排除在外，
                    // 结果「变换时不能两指缩放画布」（用户报的）
                    transforming = true
                    if (tool == Tool.TRANSFORM && transformActive && event.pointerCount == 2) tfRecordFinger(event)
                    tfPerspPanning = false
                    readGesture(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (tool == Tool.TRANSFORM && transformActive && tfPersp && event.pointerCount >= 2) {
                    tfPerspPan(event)
                } else if (event.pointerCount >= 3) {
                    applyTransform(event)
                } else if (transforming && event.pointerCount >= 2) {
                    applyTransform(event)
                } else if (event.pointerCount == 1) {
                    if (downOutsideDoc) {
                        lastViewPoint = PointF(event.x, event.y)
                        return true
                    }
                    val dx = event.x - lastViewPoint.x
                    val dy = event.y - lastViewPoint.y
                    movedDistance += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (movedDistance > 26f) removeCallbacks(longPressRunnable)
                    lastViewPoint = PointF(event.x, event.y)
                    when (tool) {
                        Tool.REGION -> Unit
                        // 画笔类：拖动时要把这一段真正画进缓冲层。之前这里写的是 Unit，
                        // 结果只有按下那一下的一个点，手指拖多远都不出笔迹（用户反馈「只有黑点」）
                        Tool.BRUSH, Tool.ERASER, Tool.DODGE, Tool.BURN, Tool.SMUDGE, Tool.MIXER, Tool.LIQUIFY,
                        Tool.REPLACE, Tool.SPONGE, Tool.ERASER_BG -> {
                            val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                            if (stylus) {
                                runCatching {
                                    strokeTilt = event.getAxisValue(MotionEvent.AXIS_TILT)
                                    strokeOrient = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
                                }
                            } else {
                                strokeTilt = 0f
                                strokeOrient = 0f
                            }
                            if (drawing) {
                                val __m0 = System.nanoTime()
                                deferFlush = true
                                deferRect = null
                                // 系统会把两次回调之间的点合并掉，historySize 里就是被合并掉的中间点。
                                // 全用上，快画时一段也是密的，不会一跳一跳、也不会粗细突变
                                val hs = event.historySize
                                for (h in 0 until hs) {
                                    val rh = if (stylus) event.getHistoricalPressure(0, h).coerceIn(0.02f, 1f) else 1f
                                    strokePressure = smoothPressure(stylus, rh)
                                    val hq = toImage(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
                                    val hsp = smoothStrokePoint(hq.x, hq.y)
                                    drawSegment(smoothLastX, smoothLastY, hsp.x, hsp.y)
                                    smoothLastX = hsp.x
                                    smoothLastY = hsp.y
                                    lastImagePoint = hq
                                }
                                val raw = if (stylus) event.getPressure(0).coerceIn(0.02f, 1f) else 1f
                                strokePressure = smoothPressure(stylus, raw)
                                val p = toImage(event.x, event.y)
                                val sp = smoothStrokePoint(p.x, p.y)
                                drawSegment(smoothLastX, smoothLastY, sp.x, sp.y)
                                smoothLastX = sp.x
                                smoothLastY = sp.y
                                lastImagePoint = p
                                deferFlush = false
                                val __f0 = System.nanoTime()
                                flushDeferred()
                                flushMsTotal += (System.nanoTime() - __f0) / 1_000_000L
                                moveEvents++
                                val __mms = (System.nanoTime() - __m0) / 1_000_000L
                                moveMsTotal += __mms
                                if (__mms >= 24L) moveSlowEvents++
                            }
                        }
                        Tool.WARP -> Unit
                        Tool.PATTERN, Tool.SPOT, Tool.HISTORY_BRUSH, Tool.ART_HISTORY -> {
                            if (drawing) {
                                val p = toImage(event.x, event.y)
                                val sp = smoothStrokePoint(p.x, p.y)
                                drawSegment(smoothLastX, smoothLastY, sp.x, sp.y)
                                smoothLastX = sp.x
                                smoothLastY = sp.y
                                lastImagePoint = p
                            }
                        }
                        Tool.MOVE -> {
                            viewMatrix.postTranslate(dx, dy)
                            invalidate()
                        }
                        Tool.EYEDROPPER -> pickColor(event.x, event.y)
                        Tool.SELECT -> {
                            val p = toImage(event.x, event.y)
                            updateSelection(p.x, p.y)
                        }
                        Tool.TRANSFORM -> {
                            val p = toImage(event.x, event.y)
                            if (tfPersp) {
                                // 透视模式：按下时没抓住控制点，就用当前点补抓一次
                                if (tfMode != 6 && tfMode != 7) tfPerspBeginDrag(p.x, p.y)
                                tfPerspDrag(p.x, p.y)
                            } else {
                                // 自由变换：按下那一刻没点中把手（离得远、或被双指打断），
                                // 这里用当前点补判一次，免得「有时能拖有时拖不动」
                                if (tfCorner < 0 && tfMode == 0) tfBeginDrag(p.x, p.y)
                                tfDrag(p.x, p.y)
                            }
                        }
                        Tool.TEXT -> Unit
                        Tool.BUCKET -> Unit
                        Tool.GRADIENT -> Unit
                        Tool.SHAPE -> Unit
                        Tool.PEN -> Unit
                        Tool.VANISH -> Unit
                        Tool.ERASER_MAGIC, Tool.REDEYE -> Unit
                        Tool.CLONE, Tool.HEAL -> {
                            if (drawing) {
                                val p = toImage(event.x, event.y)
                                drawCloneSegment(lastImagePoint.x, lastImagePoint.y, p.x, p.y)
                                lastImagePoint = p
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) transforming = false
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressFired) {
                    longPressFired = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (downOutsideDoc) {
                    downOutsideDoc = false
                    cancelStroke()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                polyAddedOnDown = false
                if (zooming) {
                    zooming = false
                    // 缩放期间省掉的派生位图在这里补一次（只补一次，不是每帧）
                    if (edgeDeferred || selStaleDuringZoom) rebuildEdge()
                    notifyZoom(true)
                    invalidate()
                }
                val elapsed = System.currentTimeMillis() - downTime
                // 双指（缩放/平移）抬起：不提交任何工具动作，这次按下就当没发生过
                if (maxPointers >= 2) {
                    cancelStroke()
                    if (tool == Tool.SELECT) {
                        selDragging = false
                        lassoPath = null
                        shotMode = 0
                        if (selSnapW > 0) {
                            applySelectionSnapshot(selSnapBefore, selSnapW, selSnapH)
                            selSnapBefore = null
                            selSnapW = 0
                            selSnapH = 0
                        }
                    }
                    gradActive = false
                    shapeActive = false
                    warpDragIdx = -1
                    bucketPending = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (tool == Tool.SELECT) {
                    // 双指是缩放/平移，不当成选区
                    if (maxPointers >= 2) {
                        selDragging = false
                        lassoPath = null
                    } else {
                        endSelection()
                    }
                } else if (tool == Tool.ERASER_MAGIC) {
                    magicEraseAt(lastImagePoint.x, lastImagePoint.y)
                } else if (tool == Tool.REDEYE) {
                    commitRedEye(lastImagePoint.x, lastImagePoint.y)
                } else if (tool == Tool.VANISH) {
                    if (maxPointers == 1 && movedDistance < 24f) {
                        vanishAddPoint(lastImagePoint.x, lastImagePoint.y)
                    }
                } else if (tool == Tool.PEN) {
                    if (maxPointers == 1 && movedDistance < 24f) {
                        penAddPoint(lastImagePoint.x, lastImagePoint.y)
                    }
                } else if (tool == Tool.TEXT) {
                    if (maxPointers == 1 && movedDistance < 24f) {
                        onTextTap?.invoke(lastImagePoint.x, lastImagePoint.y)
                    }
                } else if (tool == Tool.BUCKET) {
                    // 单指轻点才填色；移动过（拖）或者双指都不填
                    if (bucketPending && movedDistance < 24f) {
                        bucketFill(lastImagePoint.x, lastImagePoint.y, wandTolerance)
                    }
                    bucketPending = false
                } else {
                    endStroke()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                if (maxPointers == 2 && elapsed < 320 && movedDistance < 28f) {
                    undo()
                } else if (maxPointers >= 3 && elapsed < 360 && movedDistance < 60f) {
                    redo()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                zooming = false
                if (edgeDeferred) {
                    rebuildEdge()
                    invalidate()
                }
                bucketPending = false
                removeCallbacks(longPressRunnable)
                polyAddedOnDown = false
                longPressFired = false
                if (tool == Tool.SELECT) {
                    selDragging = false
                    lassoPath = null
                    shotMode = 0
                } else {
                    cancelStroke()
                }
                transforming = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun readGesture(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        lastMid = PointF((x0 + x1) / 2f, (y0 + y1) / 2f)
        lastDist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        lastAngle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
    }

    private fun applyTransform(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val mid = PointF((x0 + x1) / 2f, (y0 + y1) / 2f)
        val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        val angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

        movedDistance += hypot(
            (mid.x - lastMid.x).toDouble(),
            (mid.y - lastMid.y).toDouble()
        ).toFloat()

        if (lastDist > 8f && dist > 8f) {
            val scale = (dist / lastDist).coerceIn(0.5f, 2f)
            val values = FloatArray(9)
            viewMatrix.getValues(values)
            val current = values[Matrix.MSCALE_X]
            val factor = when {
                current * scale < 0.05f -> 0.05f / current
                current * scale > 32f -> 32f / current
                else -> scale
            }
            viewMatrix.postScale(factor, factor, mid.x, mid.y)
        notifyZoom()
            viewMatrix.postRotate(angle - lastAngle, mid.x, mid.y)
        }
        viewMatrix.postTranslate(mid.x - lastMid.x, mid.y - lastMid.y)
        lastMid = mid
        lastDist = dist
        lastAngle = angle
        invalidate()
    }

    // 点一下才算的两种（1 = 选择主体，2 = 色彩范围）：按下的位置和颜色先记着，
    // 抬手确认是「单指 + 基本没动」才真的算，双指缩放不会误触发
    private var shotMode = 0
    private var shotColor = 0

    private fun startSelection(ix: Float, iy: Float) {
        val bmp = activeLayer?.bitmap ?: return
        val x = ix.coerceIn(0f, bmp.width.toFloat())
        val y = iy.coerceIn(0f, bmp.height.toFloat())
        when (selectMode) {
            SelectMode.MAGNETIC -> {
                val bmp0 = activeLayer?.bitmap ?: return
                magnetPixels = IntArray(bmp0.width * bmp0.height).also {
                    bmp0.getPixels(it, 0, bmp0.width, 0, 0, bmp0.width, bmp0.height)
                }
                val snap = Selection.snapToEdge(magnetPixels!!, bmp0.width, bmp0.height, x.toInt(), y.toInt(), magnetWidth.toInt())
                magnetPoints = ArrayList<PointF>().also { it.add(PointF(snap[0], snap[1])) }
                selStart = PointF(x, y)
                selCurrent = PointF(x, y)
                selDragging = true
            }

            SelectMode.QUICK -> {
                val bmp0 = sampleSource(false) ?: return
                quickMask = Selection.blank(bmp0.width, bmp0.height)
                // 已有选区就当成底子：快速选择只往上补，不再把原来的整块替换掉
                selection?.let { sel ->
                    if (!sel.isRecycled) {
                        runCatching { Canvas(quickMask!!).drawBitmap(sel, 0f, 0f, Paint()) }
                    }
                }
                quickLastX = -1f
                quickLastY = -1f
                quickColor = bmp0.getPixel(x.toInt().coerceIn(0, bmp0.width - 1), y.toInt().coerceIn(0, bmp0.height - 1))
                quickAddAt(x, y)
                selDragging = true
                invalidate()
            }

            SelectMode.RECT, SelectMode.ELLIPSE, SelectMode.ROW, SelectMode.COLUMN -> {
                selStart = PointF(x, y)
                selCurrent = PointF(x, y)
                selDragging = true
            }

            SelectMode.LASSO -> {
                val path = Path()
                path.moveTo(x, y)
                lassoPath = path
                selStart = PointF(x, y)
                selCurrent = PointF(x, y)
                selDragging = true
            }

            SelectMode.POLY -> {
                val first = polyPoints.firstOrNull()
                if (first != null && polyPoints.size >= 3 && hypot(x - first.x, y - first.y) < 28f) {
                    finishPolygon()
                } else {
                    polyPoints.add(PointF(x, y))
                    polyAddedOnDown = true
                    selCurrent = PointF(x, y)
                    selDragging = true
                }
            }

            SelectMode.WAND -> {
                selStart = PointF(x, y)
                selCurrent = PointF(x, y)
                wandPoint = PointF(x, y)
                selDragging = true
            }

            // 这两个是「点一下就算」的工具，不需要拖；但也不能一按下就执行 ——
            // 想双指缩放时第一根手指落下去那一下就把选区换了。先挂起，抬手再说。
            SelectMode.SUBJECT -> {
                shotMode = 1
                shotColor = brushColor
                selStart = PointF(x, y)
                selCurrent = PointF(x, y)
                selDragging = true
            }

            SelectMode.RANGE -> {
                // 以前拿「前景色」当目标去全图找，前景色是黑的，所以永远把线稿选出来。
                // 现在按你点到的那个像素的颜色选。
                val bmp0 = sampleSource(false)
                val tx = x.toInt().coerceIn(0, (bmp0?.width ?: 1) - 1)
                val ty = y.toInt().coerceIn(0, (bmp0?.height ?: 1) - 1)
                val target = try {
                    bmp0?.getPixel(tx, ty) ?: brushColor
                } catch (e: Throwable) {
                    brushColor
                }
                shotMode = 2
                shotColor = target
                selStart = PointF(x, y)
                selCurrent = PointF(x, y)
                selDragging = true
            }
        }
        invalidate()
    }

    private fun updateSelection(ix: Float, iy: Float) {
        val bmp = activeLayer?.bitmap ?: return
        val x = ix.coerceIn(0f, bmp.width.toFloat())
        val y = iy.coerceIn(0f, bmp.height.toFloat())
        selCurrent = PointF(x, y)
        if (selectMode == SelectMode.LASSO) {
            lassoPath?.lineTo(x, y)
        }
        if (selectMode == SelectMode.MAGNETIC) {
            val pts = magnetPoints
            val px = magnetPixels
            val bmp0 = activeLayer?.bitmap
            if (pts != null && px != null && bmp0 != null) {
                val snap = Selection.snapToEdge(px, bmp0.width, bmp0.height, x.toInt(), y.toInt(), magnetWidth.toInt())
                val last = pts.last()
                if (hypot(snap[0] - last.x, snap[1] - last.y) >= 3f) pts.add(PointF(snap[0], snap[1]))
            }
        }
        if (selectMode == SelectMode.QUICK) quickAddAt(x, y)
        invalidate()
    }

    /**
     * 快速选择的落笔：从落点出发，沿着颜色相近的区域长出一整块（和魔棒同一套洪水填充），
     * 再并进笔迹遮罩。像画笔一样来回涂就能慢慢把一片区域圈全，而不是只印一个圆点。
     */
    private fun quickAddAt(x: Float, y: Float) {
        val bmp0 = sampleSource(false) ?: return
        val mask = quickMask ?: return
        // 节流：走够 18 个像素才重新长一次，免得每个移动事件都扫全图
        if (quickLastX >= 0f && hypot(x - quickLastX, y - quickLastY) < 18f) return
        val cx = x.toInt().coerceIn(0, bmp0.width - 1)
        val cy = y.toInt().coerceIn(0, bmp0.height - 1)
        val grown = Selection.wandMask(bmp0, cx, cy, quickTolerance.coerceIn(0.02f, 1f), true) ?: return
        quickLastX = x
        quickLastY = y
        val c = Canvas(mask)
        c.drawBitmap(grown, 0f, 0f, Paint())
        grown.recycle()
        selection?.let { recycleLater(it) }
        selection = mask.copy(Bitmap.Config.ARGB_8888, true)
        rebuildEdge()
        onSelectionChanged?.invoke(true)
    }

    private fun endSelection() {
        if (shotMode != 0) {
            val m = shotMode
            shotMode = 0
            if (maxPointers == 1 && movedDistance < 24f) {
                if (m == 1) selectSubject() else selectColorRange(wandTolerance, shotColor)
            }
            selDragging = false
            wandPoint = null
            invalidate()
            return
        }
        when (selectMode) {
            SelectMode.RECT, SelectMode.ELLIPSE -> commitRectOrEllipse()
            SelectMode.ROW, SelectMode.COLUMN -> commitRowOrColumn()
            SelectMode.LASSO -> commitLasso()
            SelectMode.WAND -> commitWand()
            SelectMode.MAGNETIC -> commitMagnetic()
            SelectMode.QUICK -> {
                quickMask?.let { commitShape(it) }
                quickMask = null
            }
            SelectMode.POLY, SelectMode.SUBJECT, SelectMode.RANGE -> Unit
        }
        selDragging = false
        wandPoint = null
        invalidate()
    }

    private fun pickColor(viewX: Float, viewY: Float) {
        val p = toImage(viewX, viewY)
        val bmp = activeLayer?.bitmap ?: return
        val x = p.x.toInt()
        val y = p.y.toInt()
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return
        val c = bmp.getPixel(x, y)
        if (Color.alpha(c) == 0) return
        brushColor = c or 0xFF000000.toInt()
        onColorPicked?.invoke(brushColor)
    }
}
