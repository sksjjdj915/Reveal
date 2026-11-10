package com.reveal.editor.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.min

/**
 * 图层分块存储。
 *
 * 一层切成 256×256 的小块，压缩后写到磁盘；绘制时只解出看得见的那几块（块缓存有上限），
 * 这样大画布 / 多图层时内存不再随图层数量线性增长 —— 只跟「屏幕上看得到多少」有关。
 *
 * 文件格式（小端）：
 * ```
 * magic(4) | width(4) | height(4) | tile(4) | count(4)
 * count × ( offset(4) len(4) )   // len=0 表示整块全透明，不占数据
 * 数据区：每块 = Deflate( 256×256 个 ARGB int，按 y 行优先、小端 )
 * ```
 */
internal class LayerTiles private constructor(
    private val file: File,
    val width: Int,
    val height: Int,
    private val cols: Int,
    private val rows: Int,
    private val offsets: IntArray,
    private val lens: IntArray
) {
    /** 访问顺序 LRU：块缓存上限，避免一次平移就把整层都解到内存里 */
    private val cache = LinkedHashMap<Int, Bitmap>(16, 0.75f, true)
    private var cachedTiles = 0

    private fun tileW(tx: Int): Int = min(TILE, width - tx * TILE)
    private fun tileH(ty: Int): Int = min(TILE, height - ty * TILE)

    private fun tile(tx: Int, ty: Int): Bitmap? {
        val key = ty * cols + tx
        cache[key]?.let { return it }
        val len = lens[key]
        if (len == 0) return null
        return try {
            val n0 = if (len < 0) -len else len
            val packed = ByteArray(n0)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offsets[key].toLong())
                raf.readFully(packed)
            }
            val px = IntArray(TILE * TILE)
            if (len < 0) {
                var o = 0
                for (i in px.indices) {
                    if (o + 3 >= packed.size) break
                    px[i] = (packed[o].toInt() and 0xFF) or
                        ((packed[o + 1].toInt() and 0xFF) shl 8) or
                        ((packed[o + 2].toInt() and 0xFF) shl 16) or
                        ((packed[o + 3].toInt() and 0xFF) shl 24)
                    o += 4
                }
            } else {
                val raw = ByteArray(TILE * TILE * 4)
                val inf = Inflater()
                inf.setInput(packed)
                var got = 0
                try {
                    while (got < raw.size) {
                        val n = inf.inflate(raw, got, raw.size - got)
                        if (n == 0) break
                        got += n
                    }
                } finally {
                    inf.end()
                }
                if (got < raw.size) {
                    android.util.Log.e("RevealPerf", "tile 解压不完整 " + got + "/" + raw.size)
                    return null
                }
                var o = 0
                for (i in px.indices) {
                    px[i] = (raw[o].toInt() and 0xFF) or
                        ((raw[o + 1].toInt() and 0xFF) shl 8) or
                        ((raw[o + 2].toInt() and 0xFF) shl 16) or
                        ((raw[o + 3].toInt() and 0xFF) shl 24)
                    o += 4
                }
            }
            putTile(key, px)
        } catch (e: Exception) {
            android.util.Log.e("RevealPerf", "tile 读取失败", e)
            null
        }
    }

    private fun putTile(key: Int, px: IntArray): Bitmap? {
        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, TILE, 0, 0, TILE, TILE)
        if (cachedTiles >= MAX_CACHE_TILES) {
            val it = cache.entries.iterator()
            if (it.hasNext()) {
                val e = it.next()
                e.value.recycle()
                it.remove()
                cachedTiles--
            }
        }
        cache[key] = bmp
        cachedTiles++
        return bmp
    }

    /** 把整层解回一张完整位图（保存、滤镜、合并这类必须整层像素的操作才用） */
    fun materialize(): Bitmap? {
        return try {
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint()
            for (ty in 0 until rows) {
                for (tx in 0 until cols) {
                    val b = tile(tx, ty) ?: continue
                    val w = tileW(tx)
                    val h = tileH(ty)
                    c.drawBitmap(
                        b,
                        Rect(0, 0, w, h),
                        RectF((tx * TILE).toFloat(), (ty * TILE).toFloat(), (tx * TILE + w).toFloat(), (ty * TILE + h).toFloat()),
                        p
                    )
                }
            }
            releaseCache()
            out
        } catch (e: Exception) {
            android.util.Log.e("RevealPerf", "整层解回失败", e)
            null
        }
    }

    /** 只画看得见的那几块；region 是文档坐标里的可见矩形 */
    fun drawVisible(canvas: Canvas, region: RectF?, paint: Paint) {
        val left = region?.left ?: 0f
        val top = region?.top ?: 0f
        val right = region?.right ?: width.toFloat()
        val bottom = region?.bottom ?: height.toFloat()
        for (ty in 0 until rows) {
            val y0 = ty * TILE
            if (y0 >= bottom || y0 + TILE <= top) continue
            for (tx in 0 until cols) {
                val x0 = tx * TILE
                if (x0 >= right || x0 + TILE <= left) continue
                val b = tile(tx, ty) ?: continue
                val w = tileW(tx)
                val h = tileH(ty)
                canvas.drawBitmap(
                    b,
                    Rect(0, 0, w, h),
                    RectF(x0.toFloat(), y0.toFloat(), (x0 + w).toFloat(), (y0 + h).toFloat()),
                    paint
                )
            }
        }
    }

    fun releaseCache() {
        for (b in cache.values) b.recycle()
        cache.clear()
        cachedTiles = 0
    }

    fun delete() {
        releaseCache()
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val TILE = 256
        private const val MAGIC = 0x544C5931 // "TLY1"
        private const val MAX_CACHE_TILES = 96

        /** 把整层写成分块文件；失败返回 null（调用方保持原样即可） */
        fun write(bmp: Bitmap, file: File): LayerTiles? {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val cols = (w + TILE - 1) / TILE
            val rows = (h + TILE - 1) / TILE
            val count = cols * rows
            val offsets = IntArray(count)
            val lens = IntArray(count)
            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(file, "rw")
                raf.setLength(0)
                raf.writeInt(MAGIC)
                raf.writeInt(w)
                raf.writeInt(h)
                raf.writeInt(TILE)
                raf.writeInt(count)
                val indexPos = raf.filePointer
                raf.write(ByteArray(count * 8))
                val px = IntArray(TILE * TILE)
                val raw = ByteArray(TILE * TILE * 4)
                val outBuf = ByteArray(TILE * TILE * 4 + 64)
                val def = Deflater(Deflater.BEST_SPEED)
                for (ty in 0 until rows) {
                    for (tx in 0 until cols) {
                        val idx = ty * cols + tx
                        val tw = min(TILE, w - tx * TILE)
                        val th = min(TILE, h - ty * TILE)
                        java.util.Arrays.fill(px, 0)
                        bmp.getPixels(px, 0, TILE, tx * TILE, ty * TILE, tw, th)
                        var empty = true
                        var i = 0
                        while (i < px.size) {
                            if (px[i] != 0) {
                                empty = false
                                break
                            }
                            i++
                        }
                        if (empty) continue
                        var o = 0
                        for (v in px) {
                            raw[o] = (v and 0xFF).toByte()
                            raw[o + 1] = ((v ushr 8) and 0xFF).toByte()
                            raw[o + 2] = ((v ushr 16) and 0xFF).toByte()
                            raw[o + 3] = ((v ushr 24) and 0xFF).toByte()
                            o += 4
                        }
                        def.reset()
                        def.setInput(raw)
                        def.finish()
                        val bos = java.io.ByteArrayOutputStream(raw.size / 2 + 64)
                        val cp = ByteArray(16384)
                        while (!def.finished()) {
                            val k = def.deflate(cp, 0, cp.size)
                            if (k <= 0) break
                            bos.write(cp, 0, k)
                        }
                        val comp = bos.toByteArray()
                        offsets[idx] = raf.filePointer.toInt()
                        if (comp.size < raw.size) {
                            lens[idx] = comp.size
                            raf.write(comp, 0, comp.size)
                        } else {
                            // 压不动（照片类）：直接存原始像素，负数长度标记
                            lens[idx] = -raw.size
                            raf.write(raw, 0, raw.size)
                        }
                    }
                }
                def.end()
                raf.seek(indexPos)
                for (i in 0 until count) {
                    raf.writeInt(offsets[i])
                    raf.writeInt(lens[i])
                }
            } catch (e: Exception) {
                android.util.Log.e("RevealPerf", "分块写入失败", e)
                return null
            } finally {
                try {
                    raf?.close()
                } catch (_: Exception) {
                }
            }
            return LayerTiles(file, w, h, cols, rows, offsets, lens)
        }
    }
}