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
                val c = parse(arr.getJSONObject(i))
                list.add(c)
            }
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
            displayMode = run {
                val m = CountdownFormatter.normalizeMode(o.optInt("displayMode", 0))
                // 当月 / 华都云境悦府 / GTA6 三档的「标准模式」自 v97 起改为「天时分秒模式」：
                // 老数据绕机器一电平级成 6，显示、下拉框选中项、悬浮窗标题三者一致。
                // 按类型已收掉的那些模式（当日倒计时的「天时分秒 / 天时分」、每小时与每半小时的
                // 「小时模式 / 天时分秒 / 天时分」等）对旧数据一样生效，不在可选列表里就退回标准模式
                val upgraded = if (m == CountdownFormatter.MODE_STANDARD && builtIn in intArrayOf(BuiltIn.MONTH, BuiltIn.HUADU, BuiltIn.GTA6, BuiltIn.HUADU_20, BuiltIn.HUADU_21, BuiltIn.HUADU_28, BuiltIn.HUADU_15, BuiltIn.HUADU_16, BuiltIn.HUADU_17, BuiltIn.HUADU_18, BuiltIn.HUADU_19, BuiltIn.HUADU_12, BuiltIn.HUADU_13, BuiltIn.HUADU_14)) {
                    CountdownFormatter.MODE_DAY_HMS
                } else m
                if (upgraded in CountdownFormatter.availableModes(builtIn)) upgraded
                    else CountdownFormatter.MODE_STANDARD
            },
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
            builtIn = builtIn,
            builtInManual = o.optBoolean("builtInManual", false),
            // v163：循环（归零重新计时）和内置项那颗「启用自定义提示音」开关。
            // v170：内置倒计时的「是否循环」由它的档位定死 —— 周期滚动型（每分钟 / 每小时 / 当日 /
            // 每周 …）归零自动接上下一格，恒为 true；华都云境悦府 / GTA6 这类固定目标恒为 false。
            // 只有普通倒计时才认它自己存着的 loop（旧数据缺省为 false）。
            loop = if (builtIn != BuiltIn.NONE) BuiltIn.isRolling(builtIn) else o.optBoolean("loop", false),
            loopSpan = o.optLong("loopSpan", 0L),
            soundEnabled = o.optBoolean("soundEnabled", !BuiltIn.isRolling(builtIn)),
            // v177：华都云境悦府购买正计时的「暂停 / 开始」状态
            paused = o.optBoolean("paused", false),
            pausedAt = o.optLong("pausedAt", 0L),
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
        o.put("builtInManual", c.builtInManual)
        o.put("loop", c.loop)
        o.put("loopSpan", c.loopSpan)
        o.put("soundEnabled", c.soundEnabled)
        o.put("paused", c.paused)
        o.put("pausedAt", c.pausedAt)
        return o
    }

    fun save(context: Context, list: List<Countdown>) {
        val arr = JSONArray()
        for (c in list) arr.put(toJson(c))
        File(context.filesDir, FILE).writeText(arr.toString())
    }

    // ---------------- 华都云境悦府12~20日倒计时：归零即删除（完成使命） ----------------

    private const val MISSION_DONE_PREFS = "builtin_removed"
    private const val MISSION_DONE_KEY = "mission_done_types"

    /** 某华都云境悦府 N 日内置档是否已经「完成使命」被删（持久化，重启不复发）。 */
    fun isMissionDone(context: Context, type: Int): Boolean {
        val prefs = context.getSharedPreferences(MISSION_DONE_PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(MISSION_DONE_KEY, emptySet())?.contains(type.toString()) ?: false
    }

    /** 记下某个华都云境悦府 N 日倒计时「已完成使命」被删除，之后不再自动补齐。 */
    private fun markMissionDone(context: Context, type: Int) {
        val prefs = context.getSharedPreferences(MISSION_DONE_PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(MISSION_DONE_KEY, emptySet()) ?: emptySet())
        if (!set.add(type.toString())) return
        prefs.edit().putStringSet(MISSION_DONE_KEY, set).apply()
    }

    /**
     * 收割所有已归零的「华都云境悦府12~20日倒计时」：从列表里移除、记下已完成使命、
     * 落盘，返回被删掉的条目（供弹提示框）。幂等：已删过的不会再进列表，故重复调用安全。
     */
    fun reapCompletedMissions(context: Context, list: MutableList<Countdown>): List<Countdown> {
        val now = System.currentTimeMillis()
        val removed = mutableListOf<Countdown>()
        val it = list.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val b = c.builtIn
            if (b != BuiltIn.HUADU_12 && b != BuiltIn.HUADU_13 && b != BuiltIn.HUADU_14 &&
                b != BuiltIn.HUADU_15 && b != BuiltIn.HUADU_16 && b != BuiltIn.HUADU_17 &&
                b != BuiltIn.HUADU_18 && b != BuiltIn.HUADU_19 && b != BuiltIn.HUADU_20) continue
            val t = BuiltIn.fixedTargetMillis(b) ?: continue
            if (now >= t) {
                markMissionDone(context, b)
                it.remove()
                removed.add(c)
            }
        }
        if (removed.isNotEmpty()) save(context, list)
        return removed
    }
}
