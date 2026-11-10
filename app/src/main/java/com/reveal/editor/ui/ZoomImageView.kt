package com.reveal.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/**
 * v0 画布：负责原图显示、双指缩放、拖动，以及透明底的棋盘格背景。
 * 放大到足够大时关闭双线性滤波，可以直接看到像素格（后续 GL 引擎保留同样的语义）。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private var baseScale = 1f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val checkerPaint = Paint().apply {
        shader = buildCheckerShader()
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                if (current <= 0f) return true
                val target = (current * detector.scaleFactor)
                    .coerceIn(baseScale * MIN_ZOOM, baseScale * MAX_ZOOM)
                val factor = target / current
                drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                bitmapPaint.isFilterBitmap = target <= baseScale * SHARP_THRESHOLD
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                drawMatrix.postTranslate(-distanceX, -distanceY)
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current <= 0f) return true
                val target = if (current > baseScale * 2f) baseScale else baseScale * 4f
                val factor = target / current
                drawMatrix.postScale(factor, factor, e.x, e.y)
                bitmapPaint.isFilterBitmap = target <= baseScale * SHARP_THRESHOLD
                invalidate()
                return true
            }
        },
    )

    init {
        isClickable = true
    }

    fun setBitmap(value: Bitmap?) {
        if (value === bitmap) return
        bitmap = value
        if (value != null && width > 0 && height > 0) {
            fitCenter()
        }
        invalidate()
    }

    fun resetToFit() {
        fitCenter()
        invalidate()
    }

    /** 1:1 像素显示，对应 桌面端里 100% 缩放。 */
    fun zoomToActualSize() {
        val bmp = bitmap ?: return
        drawMatrix.reset()
        drawMatrix.postTranslate(
            (width - bmp.width) / 2f,
            (height - bmp.height) / 2f,
        )
        bitmapPaint.isFilterBitmap = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) fitCenter()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), checkerPaint)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun fitCenter() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0 || bmp.width == 0 || bmp.height == 0) return
        val scale = min(
            width.toFloat() / bmp.width.toFloat(),
            height.toFloat() / bmp.height.toFloat(),
        )
        baseScale = scale
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(
            (width - bmp.width * scale) / 2f,
            (height - bmp.height * scale) / 2f,
        )
        bitmapPaint.isFilterBitmap = true
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun buildCheckerShader(): BitmapShader {
        val cell = 16
        val size = cell * 2
        val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val light = Paint().apply { color = 0xFFE5E8EC.toInt() }
        val dark = Paint().apply { color = 0xFFEFF1F4.toInt() }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), light)
        canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), dark)
        canvas.drawRect(cell.toFloat(), cell.toFloat(), size.toFloat(), size.toFloat(), dark)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    companion object {
        private const val MIN_ZOOM = 0.2f
        private const val MAX_ZOOM = 40f
        private const val SHARP_THRESHOLD = 4f
    }
}
