package com.reveal.editor.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * 从导入的图片里读拍摄信息，用于「镜头校正 → 自动」。
 * 关键字段：TAG_FOCAL_LENGTH_IN_35MM_FILM（等效焦距）、TAG_LENS_MODEL（镜头型号）。
 * 广角畸变大小的经验估计：等效焦距越短，桶形畸变越明显。
 */
object ExifInfo {
    var focal35: Float? = null
        private set
    var lensModel: String? = null
        private set

    fun read(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val ex = ExifInterface(ins)
                focal35 = ex.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.toFloatOrNull()
                lensModel = ex.getAttribute(ExifInterface.TAG_LENS_MODEL)
            }
        }
    }

    /** 估一个「移去扭曲」值：等效 35mm 越短，正值（枕形校正）越大 */
    fun autoDistort(): Float {
        val f = focal35 ?: return 0f
        val k = ((35f - f) / 35f).coerceIn(-0.6f, 0.9f)
        return k * 100f
    }

    fun summary(): String {
        val f = focal35
        val l = lensModel
        return when {
            f == null && l == null -> "这张图没有拍摄信息（可能是截图或已剥离 EXIF）"
            f == null -> "镜头：$l"
            l == null -> "等效焦距：${f.toInt()}mm"
            else -> "镜头：$l · 等效 ${f.toInt()}mm"
        }
    }
}
