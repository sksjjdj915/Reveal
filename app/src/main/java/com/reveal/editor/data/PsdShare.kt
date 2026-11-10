package com.reveal.editor.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.reveal.editor.image.Psd
import com.reveal.editor.image.PsdEffects
import com.reveal.editor.image.PsdTextInfo
import java.io.File

/**
 * 导出 / 分享 PSD。
 *
 * - 保存  = 存回软件里的工程（ProjectStore），改完还能接着改
 * - 另存  = 自己选位置，导出一份 .psd 到手机里
 * - 分享  = 把工程最新的那份导出来，交给系统分享面板（QQ / 微信 / 网盘…）
 */
object PsdShare {

    fun psdName(name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith(".psd")) return name
        if (lower.endsWith(".psb")) return name.substringBeforeLast(".") + ".psd"
        return if (name.contains(".")) name.substringBeforeLast(".") + ".psd" else "$name.psd"
    }

    /** 从软件里存的工程拼一份 PSD；一个像素都没改过就直接给原件字节 */
    fun bytesFromProject(context: Context, projectId: String): Pair<String, ByteArray>? {
        val t0 = System.currentTimeMillis()
        val doc = ProjectStore.load(context, projectId) ?: return null
        val rt = Runtime.getRuntime()
        android.util.Log.i("RevealPerf", "psd export start layers=" + doc.layers.size + " heapUsed=" + ((rt.totalMemory() - rt.freeMemory()) / 1048576) + "MB max=" + (rt.maxMemory() / 1048576) + "MB")
        if (!PsdBrowser.isEdited(context, projectId)) {
            val raw = PsdBrowser.original(context, projectId)
            if (raw != null) return psdName(doc.name) to raw
        }
        val layers = doc.layers.map { l ->
            Psd.PsdLayer(l.name, l.bitmap, l.visible, l.alpha, l.blendMode, clipped = l.clipped, alphaLock = l.alphaLock).also {
                val tc = l.textContent
                if (tc != null && tc.isNotEmpty()) {
                    it.text = PsdTextInfo(tc, if (l.textSize > 0f) l.textSize else 24f, l.textColor)
                }
                if (l.strokeOn || l.shadowOn) {
                    it.effects = PsdEffects(
                        strokeOn = l.strokeOn,
                        strokeColor = l.strokeColor,
                        strokeWidth = l.strokeWidth,
                        shadowOn = l.shadowOn,
                        shadowColor = l.shadowColor,
                        shadowDX = l.shadowDX,
                        shadowDY = l.shadowDY,
                        shadowBlur = l.shadowBlur,
                    )
                }
                it.mask = l.mask
                it.rawHead = l.rawHead?.let { b -> Psd.RawHead.unpack(b) }
                // 这层没被画过（像素指纹一致）才搬原始 16 位通道；画过只能按 8 位重建
                it.rawPix = if (l.rawPix != null && l.pixHash != 0 &&
                    l.pixHash == Psd.bitmapHash(l.bitmap, l.mask)
                ) l.rawPix else null
                it.pix16 = l.pix16?.let { b -> com.reveal.editor.image.Pix16.unpack(b) }
                it.pix32 = l.pix32?.let { b -> com.reveal.editor.image.Pix32.unpack(b) }
                it.sectionType = l.sectionType
                it.adjust = l.adjust
            }
        }
        if (layers.isEmpty()) return null
        runCatching {
            Diag.append(context, "EXPORT", "meta depth=" + (PsdBrowser.meta(context, projectId)?.depth ?: -1) + " edited=" + PsdBrowser.isEdited(context, projectId))
        }
        runCatching {
            val sb = StringBuilder()
            sb.append("分享导出：工程=").append(projectId).append(" 层数=").append(layers.size)
            doc.layers.forEachIndexed { i, l ->
                val now = Psd.bitmapHash(l.bitmap, l.mask)
                sb.append("\n#").append(i).append(" ").append(l.name)
                    .append(" rawPix=").append(if (l.rawPix == null) "null" else l.rawPix!!.size.toString() + "B")
                    .append(" rawHead=").append(if (l.rawHead == null) "null" else l.rawHead!!.size.toString() + "B")
                    .append(" pixHash=").append(l.pixHash).append(" nowHash=").append(now)
                    .append(" bitmap=").append(if (l.bitmap == null) "null" else l.bitmap!!.width.toString() + "x" + l.bitmap!!.height)
            }
            Diag.append(context, "EXPORT", sb.toString())
        }
        // 诊断：扁平导出（不写图层段）的每条条件都记下来，体积没降时看这条就知道卡在哪
        run {
            val l = doc.layers.firstOrNull()
            Diag.append(
                context, "EXPORT",
                "flat n=" + doc.layers.size +
                    " rawPix=" + (l?.rawPix?.size ?: -1) +
                    " pixDiff=" + (if (l == null || l.pixHash == 0) -1 else if (l.pixHash == Psd.bitmapHash(l.bitmap, l.mask)) 0 else 1) +
                    " adjust=" + (l?.adjust != null) +
                    " text=" + (l?.textContent != null) +
                    " mask=" + (l?.mask != null) +
                    " clipped=" + (l?.clipped ?: false) +
                    " visible=" + (l?.visible ?: false) +
                    " alpha=" + (l?.alpha ?: -1) +
                    " blend=" + (l?.blendMode ?: -1) +
                    " stroke=" + (l?.strokeOn ?: false) +
                    " shadow=" + (l?.shadowOn ?: false)
            )
        }
        val meta = PsdBrowser.meta(context, projectId)
        val psd = if (meta != null) {
            Psd.Document(
                doc.width, doc.height, layers,
                version = meta.version,
                depth = meta.depth,
                colorMode = meta.colorMode,
                channels = meta.channels,
                icc = meta.icc,
            )
        } else {
            Psd.Document(doc.width, doc.height, layers)
        }
        val bytes = Psd.encode(psd)
        runCatching {
            Diag.append(
                context, "EXPORT",
                "enc depth=" + psd.depth + " chans=" + psd.channels +
                    " p16=" + layers.count { it.pix16 != null } +
                    " rawPix=" + layers.count { it.rawPix != null } +
                    " note16=" + Psd.note16 + " size=" + bytes.size
            )
        }
        return psdName(doc.name) to bytes
    }

    /** 写进缓存目录再交给系统分享面板 */
    fun share(context: Context, fileName: String, bytes: ByteArray) {
        val rt = Runtime.getRuntime()
        android.util.Log.i("RevealPerf", "psd export done size=" + (bytes.size / 1024) + "KB heapUsed=" + ((rt.totalMemory() - rt.freeMemory()) / 1048576) + "MB")
        val dir = File(context.cacheDir, "share")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/vnd.adobe.photoshop"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享 $fileName")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
