package com.reveal.editor.data

import android.content.Context
import com.reveal.editor.ui.SketchView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 区域范围随工程保存：单独存一个 regions.json，
 * 不动 project.rvl 的格式（那个文件是图层像素包，改结构风险大）。
 */
object RegionStore {
    private fun file(context: Context, id: String): File =
        File(File(context.filesDir, "projects"), id).apply { mkdirs() }.let { File(it, "regions.json") }

    fun save(context: Context, id: String, regions: List<SketchView.RegionSpec>, active: Int) {
        runCatching {
            val arr = JSONArray()
            regions.forEach { r ->
                arr.put(JSONObject().apply {
                    put("kind", r.kind)
                    put("cx", r.cx.toDouble())
                    put("cy", r.cy.toDouble())
                    put("rx", r.rx.toDouble())
                    put("ry", r.ry.toDouble())
                    put("feather", r.feather)
                    put("name", r.name)
                    put("invert", r.invert)
                    put("opacity", r.opacity)
                })
            }
            val root = JSONObject()
            root.put("active", active)
            root.put("items", arr)
            file(context, id).writeText(root.toString())
        }
    }

    fun load(context: Context, id: String): Pair<List<SketchView.RegionSpec>, Int> {
        val f = file(context, id)
        if (!f.exists()) return emptyList<SketchView.RegionSpec>() to -1
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<SketchView.RegionSpec>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SketchView.RegionSpec(
                        kind = o.optInt("kind", 0),
                        cx = o.optDouble("cx", 0.0).toFloat(),
                        cy = o.optDouble("cy", 0.0).toFloat(),
                        rx = o.optDouble("rx", 0.0).toFloat(),
                        ry = o.optDouble("ry", 0.0).toFloat(),
                        feather = o.optInt("feather", 0),
                        name = o.optString("name", "范围"),
                        invert = o.optBoolean("invert", false),
                        opacity = o.optInt("opacity", 255),
                    )
                )
            }
            out to root.optInt("active", -1)
        }.getOrDefault(emptyList<SketchView.RegionSpec>() to -1)
    }
}
