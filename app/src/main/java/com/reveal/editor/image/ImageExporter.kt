package com.reveal.editor.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * 导出格式。supportsAlpha 决定是否保留透明通道；
 * 不支持透明的格式导出时统一铺白底，避免透明区变黑。
 */
enum class ExportFormat(
    val displayName: String,
    val mime: String,
    val extension: String,
    val supportsAlpha: Boolean,
) {
    PNG("PNG 无损", "image/png", "png", true),
    WEBP_LOSSLESS("WebP 无损", "image/webp", "webp", true),
    WEBP_LOSSY("WebP 有损", "image/webp", "webp", true),
    JPEG("JPEG", "image/jpeg", "jpg", false),
    BMP("BMP", "image/bmp", "bmp", false),
    TIFF("TIFF 无损", "image/tiff", "tif", true),
    PDF("PDF 文档", "application/pdf", "pdf", false),
    ;

    companion object {
        /** WebP 无损编码要求 Android 11（API 30）以上。 */
        fun available(): List<ExportFormat> = buildList {
            add(PNG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(WEBP_LOSSLESS)
            add(WEBP_LOSSY)
            add(JPEG)
            add(BMP)
            add(TIFF)
            add(PDF)
        }
    }
}

object ImageExporter {

    fun export(
        context: Context,
        uri: Uri,
        source: Bitmap,
        format: ExportFormat,
        quality: Int = 95,
    ): Result<Unit> = runCatching {
        val raw = context.contentResolver.openOutputStream(uri)
            ?: error("无法打开目标文件")
        raw.use { stream ->
            val out = BufferedOutputStream(stream, 128 * 1024)
            when (format) {
                ExportFormat.PNG -> {
                    check(source.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 写入失败" }
                }

                ExportFormat.WEBP_LOSSLESS -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        error("当前系统不支持 WebP 无损编码")
                    }
                    check(source.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) {
                        "WebP 写入失败"
                    }
                }

                ExportFormat.WEBP_LOSSY -> {
                    val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    check(source.compress(compressFormat, quality, out)) { "WebP 写入失败" }
                }

                ExportFormat.JPEG -> {
                    val opaque = if (source.hasAlpha()) flattenOnWhite(source) else source
                    try {
                        check(opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                            "JPEG 写入失败"
                        }
                    } finally {
                        if (opaque !== source) opaque.recycle()
                    }
                }

                ExportFormat.BMP -> writeBmp(source, out)

                ExportFormat.TIFF -> writeTiff(source, out)

                ExportFormat.PDF -> writePdf(source, out)
            }
            out.flush()
        }
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return result
    }

    /** 24 位无压缩 BMP（BGR，自下而上），Android 没有内置编码器，这里手写。 */
    private fun writeBmp(bitmap: Bitmap, raw: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val rowStride = (width * 3 + 3) / 4 * 4
        val pixelBytes = rowStride * height
        val pixelOffset = 14 + 40

        val out = DataOutputStream(BufferedOutputStream(raw, 128 * 1024))

        // BITMAPFILEHEADER
        out.writeByte(0x42)
        out.writeByte(0x4D)
        out.writeIntLe(pixelOffset + pixelBytes)
        out.writeShortLe(0)
        out.writeShortLe(0)
        out.writeIntLe(pixelOffset)

        // BITMAPINFOHEADER
        out.writeIntLe(40)
        out.writeIntLe(width)
        out.writeIntLe(height)
        out.writeShortLe(1)
        out.writeShortLe(24)
        out.writeIntLe(0)
        out.writeIntLe(pixelBytes)
        out.writeIntLe(2835)
        out.writeIntLe(2835)
        out.writeIntLe(0)
        out.writeIntLe(0)

        val row = IntArray(width)
        val padding = ByteArray(rowStride - width * 3)
        for (y in height - 1 downTo 0) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val px = row[x]
                out.writeByte(px and 0xFF)
                out.writeByte((px ushr 8) and 0xFF)
                out.writeByte((px ushr 16) and 0xFF)
            }
            if (padding.isNotEmpty()) out.write(padding)
        }
        out.flush()
    }

    /** 基线 TIFF：8 位 RGBA、无压缩、单条带，桌面端/ 系统相册都能直接打开 */
    private fun writeTiff(bitmap: Bitmap, raw: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val out = DataOutputStream(BufferedOutputStream(raw, 256 * 1024))
        val entries = 12
        val ifdOffset = 8
        val bitsOffset = ifdOffset + 2 + entries * 12 + 4
        val fmtOffset = bitsOffset + 8
        val dataOffset = fmtOffset + 8
        val dataBytes = w * h * 4

        out.writeByte(0x49)
        out.writeByte(0x49)
        out.writeShortLe(42)
        out.writeIntLe(ifdOffset)

        out.writeShortLe(entries)
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            out.writeShortLe(tag)
            out.writeShortLe(type)
            out.writeIntLe(count)
            if (type == 3 && count == 1) {
                out.writeShortLe(value)
                out.writeShortLe(0)
            } else {
                out.writeIntLe(value)
            }
        }
        entry(256, 4, 1, w)
        entry(257, 4, 1, h)
        entry(258, 3, 4, bitsOffset)
        entry(259, 3, 1, 1)
        entry(262, 3, 1, 2)
        entry(273, 4, 1, dataOffset)
        entry(277, 3, 1, 4)
        entry(278, 4, 1, h)
        entry(279, 4, 1, dataBytes)
        entry(284, 3, 1, 1)
        entry(338, 3, 1, 2)
        entry(339, 3, 4, fmtOffset)
        out.writeIntLe(0)

        repeat(4) { out.writeShortLe(8) }
        repeat(4) { out.writeShortLe(1) }

        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val px = row[x]
                out.writeByte((px ushr 16) and 0xFF)
                out.writeByte((px ushr 8) and 0xFF)
                out.writeByte(px and 0xFF)
                out.writeByte((px ushr 24) and 0xFF)
            }
        }
        out.flush()
    }

    /** 单页 PDF：页尺寸按图片像素（72dpi）算，铺白底 */
    private fun writePdf(bitmap: Bitmap, raw: OutputStream) {
        val doc = android.graphics.pdf.PdfDocument()
        try {
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                bitmap.width,
                bitmap.height,
                1,
            ).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            doc.finishPage(page)
            doc.writeTo(raw)
        } finally {
            doc.close()
        }
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value ushr 8) and 0xFF)
        writeByte((value ushr 16) and 0xFF)
        writeByte((value ushr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value ushr 8) and 0xFF)
    }
}
