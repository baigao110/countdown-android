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
            for (i in 0 until arr.length()) list.add(parse(arr.getJSONObject(i)))
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /**
     * 解析单条倒计时。列表读取与「内置倒计时删除前的设置快照还原」共用同一套解析，
     * 保证快照里的字段和正常保存的完全一致（不会漏掉后来新增的字段）。
     *
     * 兼容历史数据：早期版本保存的目标时间带着秒 / 毫秒尾数，会让各条目的「秒」位数
     * 永远差那么几秒。读取时把普通倒计时对齐到整分（内置项由系统每帧重算，不在此处理），
     * 使列表里所有倒计时的秒数完全一致。
     */
    fun parse(o: JSONObject): Countdown {
        val rawTarget = o.optLong("targetTime", 0L)
        val builtIn = o.optInt("builtIn", BuiltIn.NONE)
        val target = if (rawTarget > 0 && builtIn == BuiltIn.NONE) {
            alignToMinute(rawTarget)
        } else rawTarget
        return Countdown(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", ""),
            targetTime = target,
            customColorArgb = o.optInt("customColorArgb", 0xFF00FFFF.toInt()),
            displayMode = CountdownFormatter.normalizeMode(o.optInt("displayMode", 0)),
            animStyle = o.optInt("animStyle", AnimStyle.NONE),
            isVisible = o.optBoolean("isVisible", true),
            remark = o.optString("remark", ""),
            soundUri = if (o.isNull("soundUri")) null else o.optString("soundUri"),
            isTopMost = o.optBoolean("isTopMost", false),
            finished = o.optBoolean("finished", false),
            opacity = o.optInt("opacity", 100).coerceIn(20, 100),
            collapsed = o.optBoolean("collapsed", false),
            posX = o.optInt("posX", -1),
            posY = o.optInt("posY", -1),
            builtIn = builtIn
        )
    }

    /** 序列化单条倒计时（落盘与「内置倒计时设置快照」共用）。 */
    fun toJson(c: Countdown): JSONObject {
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
        o.put("builtIn", c.builtIn)
        o.put("animStyle", c.animStyle)
        return o
    }

    fun save(context: Context, list: List<Countdown>) {
        val arr = JSONArray()
        for (c in list) arr.put(toJson(c))
        File(context.filesDir, FILE).writeText(arr.toString())
    }
}
