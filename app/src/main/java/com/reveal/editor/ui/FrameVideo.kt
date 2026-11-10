package com.reveal.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * 把一串位图压成 H.264 的 MP4（用手机自带的硬件编码器，不引第三方库）。
 * 用法：start() → addFrame(bmp) 逐张喂 → finish()，最后 muxer 收尾成文件。
 *
 * 注意：编码器在真机上才有，模拟器/纯 JVM 跑不了，所以这一块只能靠真机验证。
 */
internal class Mp4Writer(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val outFile: File,
) {
    companion object {
        private const val MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L

        /** 把位图缩到偶数尺寸（H.264 要求宽高都是偶数） */
        fun evenSize(w: Int, h: Int, maxSide: Int = 1920): Pair<Int, Int> {
            val scale = if (w > maxSide || h > maxSide) maxSide.toFloat() / maxOf(w, h) else 1f
            val nw = ((w * scale).toInt() / 2) * 2
            val nh = ((h * scale).toInt() / 2) * 2
            return nw.coerceAtLeast(2) to nh.coerceAtLeast(2)
        }
    }

    private val w = (width / 2) * 2
    private val h = (height / 2) * 2
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val info = MediaCodec.BufferInfo()
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0
    private var stopped = false

    private val yArr = ByteArray(w * h)
    private val uArr = ByteArray(w * h / 4)
    private val vArr = ByteArray(w * h / 4)

    /** 启动编码器。失败会抛异常，调用方接住并给用户一句人话。 */
    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, w, h)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        // 码率：约 0.12 bit / 像素 / 帧，夹在 1~20 Mbps 之间
        val bitrate = (w.toLong() * h * fps * 0.12).toInt().coerceIn(1_000_000, 20_000_000)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** 喂一帧（会自动缩到编码尺寸） */
    fun addFrame(src: Bitmap) {
        val bmp = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        argbToYuv(bmp)
        if (bmp !== src) bmp.recycle()

        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) return
        val image = runCatching { codec.getInputImage(inIndex) }.getOrNull()
        if (image != null) {
            writeImage(image)
            image.close()
        } else {
            val buf = codec.getInputBuffer(inIndex) ?: return
            buf.clear()
            buf.put(yArr)
            buf.put(uArr)
            buf.put(vArr)
        }
        val pts = frameIndex * 1_000_000L / fps
        codec.queueInputBuffer(inIndex, 0, yArr.size + uArr.size + vArr.size, pts, 0)
        frameIndex++
        drain(false)
    }

    /** 收尾：送 EOS，把剩下的帧写进文件 */
    fun finish() {
        if (stopped) return
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US * 10)
        if (inIndex >= 0) {
            codec.queueInputBuffer(inIndex, 0, 0, frameIndex * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)
        stopped = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxer == null) {
                        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        trackIndex = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0 && muxerStarted) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer?.writeSampleData(trackIndex, buf, info)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /** 把 ARGB 位图转成 YUV420（BT.601，和相机/播放器默认一致） */
    private fun argbToYuv(bmp: Bitmap) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var yi = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yArr[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
            }
        }
        var ci = 0
        for (y in 0 until h step 2) {
            val row0 = y * w
            val row1 = (y + 1) * w
            for (x in 0 until w step 2) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val idx = if (dy == 0) row0 + x + dx else row1 + x + dx
                        if (idx < px.size) {
                            val c = px[idx]
                            rSum += (c shr 16) and 0xFF
                            gSum += (c shr 8) and 0xFF
                            bSum += c and 0xFF
                        }
                    }
                }
                val r = rSum / 4
                val g = gSum / 4
                val b = bSum / 4
                uArr[ci] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                vArr[ci] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                ci++
            }
        }
        // 防止 uv 尺寸算偏（宽高都是偶数，正常正好填满）
        if (ci in 1 until uArr.size) {
            val fill = uArr[ci - 1]
            for (i in ci until uArr.size) {
                uArr[i] = fill
                vArr[i] = vArr[ci - 1]
            }
        }
    }

    /** 直接写进编码器给的 Image 平面（尊重 rowStride / pixelStride） */
    private fun writeImage(image: android.media.Image) {
        val planes = image.planes
        if (planes.size < 3) return
        // Y
        val yp = planes[0]
        val yBuf = yp.buffer
        val yRow = yp.rowStride
        val yPix = yp.pixelStride
        var si = 0
        for (y in 0 until h) {
            var off = y * yRow
            for (x in 0 until w) {
                yBuf.put(off, yArr[si++])
                off += yPix
            }
        }
        // U / V（2×2 下采样）
        val halfW = w / 2
        val halfH = h / 2
        for (p in 1..2) {
            val plane = planes[p]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val src = if (p == 1) uArr else vArr
            var idx = 0
            for (y in 0 until halfH) {
                var off = y * rowStride
                for (x in 0 until halfW) {
                    buf.put(off, src[idx++])
                    off += pixStride
                }
            }
        }
    }
}