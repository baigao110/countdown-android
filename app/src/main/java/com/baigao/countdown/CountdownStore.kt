package com.baigao.countdown

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 倒计时数据的本地持久化（JSON 文件，存于应用私有目录）。
 * 与桌面版 countdown_data.json 字段保持一致，便于将来互通。
 */
object CountdownStore {
    private const val FILE = "countdowns.json"

    fun load(context: Context): MutableList<Countdown> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Countdown>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Countdown(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = o.optString("title", ""),
                        targetTime = o.optLong("targetTime", 0L),
                        customColorArgb = o.optInt("customColorArgb", 0xFF00FFFF.toInt()),
                        displayMode = o.optInt("displayMode", 0),
                        isVisible = o.optBoolean("isVisible", true),
                        remark = o.optString("remark", ""),
                        soundUri = if (o.isNull("soundUri")) null else o.optString("soundUri"),
                        isTopMost = o.optBoolean("isTopMost", false),
                        finished = o.optBoolean("finished", false),
                        posX = o.optInt("posX", -1),
                        posY = o.optInt("posY", -1)
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, list: List<Countdown>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("title", c.title)
            o.put("targetTime", c.targetTime)
            o.put("customColorArgb", c.customColorArgb)
            o.put("displayMode", c.displayMode)
            o.put("isVisible", c.isVisible)
            o.put("remark", c.remark)
            if (c.soundUri != null) o.put("soundUri", c.soundUri) else o.put("soundUri", JSONObject.NULL)
            o.put("isTopMost", c.isTopMost)
            o.put("finished", c.finished)
            o.put("opacity", c.opacity.coerceIn(20, 100))
            o.put("collapsed", c.collapsed)
            o.put("posX", c.posX)
            o.put("posY", c.posY)
            arr.put(o)
        }
        File(context.filesDir, FILE).writeText(arr.toString())
    }
}
