package com.reveal.editor.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * 图片加载：按 EXIF 纠正方向，超大图按 2 的幂降采样到 [MAX_DIMENSION] 以内。
 * 这是手机内存的安全上限，不是"压缩画质"—— 原图尺寸内的图一律按原始像素解码。
 *
 * 注意：`BitmapFactory.decodeStream` 在 inJustDecodeBounds=true 时按设计返回 null，
 * 不能把它的返回值当成"打开失败"的信号（v0.1.0 就是这么写崩的）。
 */
object BitmapLoader {

    const val MAX_DIMENSION = 4096

    /** 最近一次失败的原因，用于把真实错误显示出来。 */
    @Volatile
    var lastError: String? = null
        private set

    fun load(context: Context, uri: Uri): Bitmap? {
        lastError = null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 只读尺寸，返回值一定是 null，这里只看 bounds
        decodeAny(context, uri, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (lastError == null) lastError = "读不出图片尺寸（可能不是图片，或文件没有读取权限）"
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = decodeAny(context, uri, options)
        if (decoded == null) {
            if (lastError == null) lastError = "解码失败"
            return null
        }

        val oriented = applyExifOrientation(context, uri, decoded)
        return downscaleIfNeeded(oriented)
    }

    /**
     * 三级回退解码：直接开流 → 文件描述符 → 复制到缓存目录再解。
     * 只读尺寸时三级都返回 null 属于正常，不记录错误。
     */
    private fun decodeAny(context: Context, uri: Uri, options: BitmapFactory.Options): Bitmap? {
        val errors = StringBuilder()

        val byStream = runCatching {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                errors.append("流打不开; ")
                null
            } else {
                input.use { BitmapFactory.decodeStream(it, null, options) }
            }
        }.onFailure { errors.append("流:").append(it.message).append("; ") }.getOrNull()
        if (byStream != null) return byStream

        val byFd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
            }
        }.onFailure { errors.append("fd:").append(it.message).append("; ") }.getOrNull()
        if (byFd != null) return byFd

        val byTemp = runCatching {
            val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.bin")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out -> input.copyTo(out) }
                }
                BitmapFactory.decodeFile(temp.absolutePath, options)
            } finally {
                temp.delete()
            }
        }.onFailure { errors.append("缓存:").append(it.message).append("; ") }.getOrNull()
        if (byTemp != null) return byTemp

        // 只读尺寸时没有错误信息，不算失败
        if (!options.inJustDecodeBounds && errors.isNotEmpty()) {
            lastError = errors.toString()
        }
        return null
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= MAX_DIMENSION) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
        val matrix = Matrix().apply { postScale(scale, scale) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
