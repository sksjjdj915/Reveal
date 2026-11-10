package com.reveal.editor.image

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * 随包内置的开源字体，全部 SIL OFL 1.1，可免费商用（每款的授权原文都在 assets/fonts/license/ 下，
 * 面板里能直接查看）。
 *
 * 字体在安装包里是压缩存放的（APK 里 16 款合计约 22MB，解压后 37MB）。为了不白占手机存储，
 * 采用「点哪款解压哪款」：第一次选中某款字体时才把它解到 filesDir/fonts/ 下，没点过的继续留在
 * 包里保持压缩状态。已解压的文件可以在「管理字体」里一键清理。
 *
 * key 会存进 TextSpec.font / Layer.textFont / 工程文件；psName 用于导出 PSD 时写 桌面端
 * 认识的字体名（电脑上没装这个字体时 桌面端会替换，但名字至少是对的）。
 */
object BundledFonts {

    data class Entry(
        val key: String,
        val label: String,
        val asset: String,
        val psName: String,
        /** 「中」/「英」，面板里分组用 */
        val lang: String,
        /** 字体原始文件大小，给用户看个大概 */
        val sizeText: String,
        /** 该字体的 SIL OFL 1.1 授权原文（assets 路径） */
        val license: String,
    )

    private const val PREFS = "reveal_fonts"
    private const val KEY_HIDDEN = "hidden"

    val all: List<Entry> = listOf(
        // ---------------- 中文 ----------------
        Entry("zcool-kuaile", "快乐体", "fonts/ZCOOLKuaiLe-Regular.ttf", "ZCOOL KuaiLe", "中", "1.4MB", "fonts/license/zcoolkuaile-OFL.txt"),
        Entry("zcool-huangyou", "黄油体", "fonts/ZCOOLQingKeHuangYou-Regular.ttf", "ZCOOL QingKe HuangYou", "中", "7.9MB", "fonts/license/zcoolqingkehuangyou-OFL.txt"),
        Entry("zcool-xiaowei", "小薇体", "fonts/ZCOOLXiaoWei-Regular.ttf", "ZCOOL XiaoWei", "中", "6.0MB", "fonts/license/zcoolxiaowei-OFL.txt"),
        Entry("mashanzheng", "毛笔行书", "fonts/MaShanZheng-Regular.ttf", "Ma Shan Zheng", "中", "5.6MB", "fonts/license/mashanzheng-OFL.txt"),
        Entry("longcang", "龙藏体", "fonts/LongCang-Regular.ttf", "Long Cang", "中", "4.9MB", "fonts/license/longcang-OFL.txt"),
        Entry("liujianmaocao", "毛草", "fonts/LiuJianMaoCao-Regular.ttf", "Liu Jian Mao Cao", "中", "4.7MB", "fonts/license/liujianmaocao-OFL.txt"),
        Entry("zhimangxing", "志莽行书", "fonts/ZhiMangXing-Regular.ttf", "Zhi Mang Xing", "中", "3.9MB", "fonts/license/zhimangxing-OFL.txt"),
        // ---------------- 英文 ----------------
        Entry("playfair", "优雅衬线", "fonts/PlayfairDisplay.ttf", "Playfair Display", "英", "0.3MB", "fonts/license/playfairdisplay-OFL.txt"),
        Entry("oswald", "窄体标题", "fonts/Oswald.ttf", "Oswald", "英", "0.2MB", "fonts/license/oswald-OFL.txt"),
        Entry("montserrat", "无衬线", "fonts/Montserrat.ttf", "Montserrat", "英", "0.7MB", "fonts/license/montserrat-OFL.txt"),
        Entry("pacifico", "圆润手写", "fonts/Pacifico-Regular.ttf", "Pacifico", "英", "0.3MB", "fonts/license/pacifico-OFL.txt"),
        Entry("bebasneue", "大写标题", "fonts/BebasNeue-Regular.ttf", "Bebas Neue", "英", "0.1MB", "fonts/license/bebasneue-OFL.txt"),
        Entry("lobster", "复古招牌", "fonts/Lobster-Regular.ttf", "Lobster", "英", "0.4MB", "fonts/license/lobster-OFL.txt"),
        Entry("caveat", "手写笔迹", "fonts/Caveat.ttf", "Caveat", "英", "0.4MB", "fonts/license/caveat-OFL.txt"),
        Entry("abril", "粗衬线", "fonts/AbrilFatface-Regular.ttf", "Abril Fatface", "英", "0.1MB", "fonts/license/abrilfatface-OFL.txt"),
        Entry("righteous", "几何标题", "fonts/Righteous-Regular.ttf", "Righteous", "英", "0.1MB", "fonts/license/righteous-OFL.txt"),
    )

    private val cache = HashMap<String, Typeface?>()
    /** 只给预览用的缓存：直接从 assets 读，不解压到手机存储 */
    private val previewCache = HashMap<String, Typeface?>()

    fun find(key: String): Entry? = all.firstOrNull { it.key == key }

    fun psNameOf(key: String): String? = find(key)?.psName

    /** 桌面端字体表里的名字反查回内置 key（打开 PSD 时用） */
    fun keyForPsName(ps: String): String? {
        if (ps.isEmpty()) return null
        val flat = ps.replace(" ", "").lowercase()
        return all.firstOrNull { it.psName.replace(" ", "").lowercase() == flat }?.key
    }

    // ---------------- 解压（点哪款解哪款） ----------------

    private fun dir(ctx: Context): File = File(ctx.filesDir, "fonts").also { if (!it.exists()) it.mkdirs() }

    private fun fileOf(ctx: Context, e: Entry): File = File(dir(ctx), e.asset.substringAfterLast('/'))

    fun isExtracted(ctx: Context, key: String): Boolean {
        val e = find(key) ?: return false
        return fileOf(ctx, e).let { it.exists() && it.length() > 1024 }
    }

    fun extractedSize(ctx: Context): Long =
        dir(ctx).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun extractedCount(ctx: Context): Int = all.count { isExtracted(ctx, it.key) }

    /** 只有第一次选这款字体时才真的解压；返回 true 表示这次做了复制 */
    fun ensureExtracted(ctx: Context, key: String): Boolean {
        val e = find(key) ?: return false
        val f = fileOf(ctx, e)
        if (f.exists() && f.length() > 1024) return false
        return synchronized(this) {
            if (f.exists() && f.length() > 1024) return false
            runCatching {
                ctx.assets.open(e.asset).use { input ->
                    f.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                true
            }.getOrElse {
                runCatching { f.delete() }
                false
            }
        }
    }

    /** 删掉已解压的文件，回到「包里压缩」状态（不影响字体可用性，下次用会再解一次） */
    fun clearExtracted(ctx: Context) {
        dir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
        cache.clear()
    }

    // ---------------- 删除 / 恢复（列表里隐藏） ----------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hidden(ctx: Context): Set<String> =
        (prefs(ctx).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toSet()

    fun isHidden(ctx: Context, key: String): Boolean = hidden(ctx).contains(key)

    fun setHidden(ctx: Context, key: String, hide: Boolean) {
        val cur = hidden(ctx).toMutableSet()
        if (hide) {
            cur.add(key)
            // 删字体顺手把解压出来的副本也删了，省空间
            find(key)?.let { runCatching { fileOf(ctx, it).delete() } }
            cache.remove(key)
        } else {
            cur.remove(key)
        }
        prefs(ctx).edit().putStringSet(KEY_HIDDEN, cur).apply()
    }

    fun restoreAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HIDDEN).apply()
        cache.clear()
    }

    /** 字体选择器里该显示哪些（按用户的删除记录过滤） */
    fun visible(ctx: Context): List<Entry> {
        val h = hidden(ctx)
        return all.filter { !h.contains(it.key) }
    }

    // ---------------- 加载 ----------------

    /**
     * 内置字体的 Typeface。第一次调用会把该字体解压到 filesDir；
     * 被用户删掉、key 不是内置字体或加载失败都返回 null，调用方回退系统字体。
     */
    fun typeface(ctx: Context, key: String): Typeface? {
        val entry = find(key) ?: return null
        if (isHidden(ctx, entry.key)) return null
        cache[entry.key]?.let { return it }
        // 先走「解压到 filesDir 再读文件」；解压失败（存储满/权限问题）就直接从安装包里读，
        // 保证「点了字体却毫无变化」这种情况不会再出现
        var tf: Typeface? = null
        if (ensureExtracted(ctx, entry.key)) {
            tf = runCatching { Typeface.createFromFile(fileOf(ctx, entry)) }.getOrNull()
        }
        if (tf == null) {
            tf = runCatching { Typeface.createFromAsset(ctx.assets, entry.asset) }.getOrNull()
        }
        if (tf != null) {
            // 中文字体一个字库就十几 MB，全留住会把内存吃光，只保留最近 4 款
            if (cache.size >= 4) {
                cache.keys.firstOrNull { it != entry.key && it != key }?.let { cache.remove(it) }
            }
            cache[entry.key] = tf
        }
        return tf
    }

    /**
     * 字体选择器里做预览用的 Typeface：直接从安装包里读，不往手机存储写副本。
     * 加载失败返回 null（预览会退回系统字体，但不会报错）。
     */
    fun previewTypeface(ctx: Context, key: String): Typeface? {
        val entry = find(key) ?: return null
        if (previewCache.containsKey(entry.key)) return previewCache[entry.key]
        val tf = runCatching { Typeface.createFromAsset(ctx.assets, entry.asset) }.getOrNull()
        previewCache[entry.key] = tf
        return tf
    }
}