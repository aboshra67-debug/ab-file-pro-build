package com.abfilepro.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * P19: compact text form of review pages, so the scanner can survive an activity recreation
 * (theme/font/size change, split screen, Android 16 large-screen rotation, process restore).
 * Only page metadata and the path of the cached page image are stored; pages whose image no
 * longer exists are dropped on restore.
 */
object ScanDraftPageCodec {
    fun encode(pages: List<ScanDraftPage>): String = JSONArray().apply {
        pages.forEach { page ->
            put(JSONObject()
                .put("id", page.id)
                .put("file", page.file.absolutePath)
                .put("rotation", page.rotation)
                .put("filter", page.filter.name)
                .put("brightness", page.brightness)
                .put("contrast", page.contrast.toDouble())
                .put("sharpness", page.sharpness.toDouble())
                .put("cropLeft", page.cropLeft.toDouble())
                .put("cropTop", page.cropTop.toDouble())
                .put("cropRight", page.cropRight.toDouble())
                .put("cropBottom", page.cropBottom.toDouble())
                .apply {
                    page.perspective?.let { q ->
                        put("quad", JSONArray(listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)
                            .flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }))
                    }
                }
                .put("annotations", JSONArray().apply {
                    page.annotations.forEach { a ->
                        put(JSONObject()
                            .put("id", a.id).put("text", a.text).put("x", a.x.toDouble()).put("y", a.y.toDouble())
                            .put("size", a.sizeScale.toDouble()).put("color", a.colorArgb)
                            .apply { a.backgroundArgb?.let { put("bg", it) } })
                    }
                }))
        }
    }.toString()

    fun decode(value: String?, fileExists: (File) -> Boolean = { it.isFile && it.length() > 0L }): List<ScanDraftPage> {
        if (value.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val o = array.optJSONObject(index) ?: continue
                val path = o.optString("file")
                if (path.isBlank()) continue
                val file = File(path)
                if (!fileExists(file)) continue
                val quad = o.optJSONArray("quad")?.takeIf { it.length() == 8 }?.let { q ->
                    fun p(i: Int) = ScanPoint(q.optDouble(i * 2).toFloat(), q.optDouble(i * 2 + 1).toFloat())
                    ScanQuad(p(0), p(1), p(2), p(3))
                }
                val notes = o.optJSONArray("annotations")
                add(ScanDraftPage(
                    id = o.optLong("id", index.toLong()),
                    file = file,
                    rotation = o.optInt("rotation", 0),
                    filter = runCatching { ScanFilter.valueOf(o.optString("filter")) }.getOrDefault(ScanFilter.ORIGINAL),
                    brightness = o.optInt("brightness", 0),
                    contrast = o.optDouble("contrast", 1.0).toFloat(),
                    sharpness = o.optDouble("sharpness", 0.0).toFloat(),
                    cropLeft = o.optDouble("cropLeft", 0.0).toFloat(),
                    cropTop = o.optDouble("cropTop", 0.0).toFloat(),
                    cropRight = o.optDouble("cropRight", 0.0).toFloat(),
                    cropBottom = o.optDouble("cropBottom", 0.0).toFloat(),
                    perspective = quad,
                    annotations = buildList {
                        if (notes != null) for (n in 0 until notes.length()) {
                            val a = notes.optJSONObject(n) ?: continue
                            add(ScanTextAnnotation(
                                id = a.optLong("id"), text = a.optString("text"),
                                x = a.optDouble("x", .5).toFloat(), y = a.optDouble("y", .5).toFloat(),
                                sizeScale = a.optDouble("size", .055).toFloat(), colorArgb = a.optLong("color", 0xFF111111),
                                backgroundArgb = if (a.has("bg")) a.optLong("bg") else null,
                            ))
                        }
                    },
                ))
            }
        }
    }
}
