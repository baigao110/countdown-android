package com.baigao.countdown

import java.util.Calendar
import java.util.UUID

/**
 * 内置倒计时类型：系统自动维护目标时间、且不可删除（对应桌面版 BuiltInType 枚举）。
 */
object BuiltIn {
    const val NONE = 0   // 普通倒计时
    const val DAY = 1    // 当日倒计时：目标为「次日 00:00:00」（今日结束的那一刻）
    const val MONTH = 2  // 当月倒计时：目标为「次月 1 日 00:00:00」（本月结束的那一刻）
    const val HUADU = 3  // 华都云境悦府倒计时：固定目标 2026-10-31 00:00:00
    const val GTA6 = 4   // GTA6 倒计时：固定目标 2026-11-19 08:00:00
    const val WEEK = 5   // 每周倒计时：目标为「下周一 00:00:00」（本周结束的那一刻）

    /**
     * 固定目标时间的内置项（不随日期滚动）：返回 epoch 毫秒；滚动型内置项返回 null。
     * 按本地时区构造，与用户在日期时间选择器里选到的时刻一致；秒 / 毫秒恒为 0，
     * 保证与列表中其它倒计时一样对齐整秒，走秒完全同步。
     */
    fun fixedTargetMillis(type: Int): Long? {
        val cal = Calendar.getInstance()
        cal.clear()
        when (type) {
            HUADU -> cal.set(2026, Calendar.OCTOBER, 31, 0, 0, 0)
            GTA6 -> cal.set(2026, Calendar.NOVEMBER, 19, 8, 0, 0)
            else -> return null
        }
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

/**
 * 全局统一的「整秒时钟」。
 *
 * 任何刷新路径（列表每帧刷新、整表重建、悬浮窗、服务）都取这里的值：
 * 它把当前时刻向下对齐到整秒，因此**同一秒内无论被调用多少次，返回值完全相同**。
 * 这样即使两条目在不同的代码路径、相差几百毫秒被刷新，算出的秒数也必然一致。
 */
object AlignedClock {
    fun now(): Long = Math.floorDiv(System.currentTimeMillis(), 1000L) * 1000L
}

/**
 * 把时间戳对齐到「整分」（秒、毫秒归零）。
 *
 * 界面上的日期时间选择器只能选到分钟，理论上目标时间的秒/毫秒都应为 0；
 * 早期版本留下的历史数据却带着秒和毫秒尾数，会让各条目的秒数永远差那么几秒。
 * 这里按「四舍五入到最近的整分」抹平，最多偏移 30 秒，肉眼几乎无感。
 */
fun alignToMinute(timeMillis: Long): Long {
    if (timeMillis <= 0) return timeMillis
    return Math.floorDiv(timeMillis + 30000L, 60000L) * 60000L
}

/**
 * 单个倒计时数据模型。字段与 Win11 桌面版保持一致。
 */
data class Countdown(
    var id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var targetTime: Long = 0L,                 // 目标时间，epoch 毫秒
    var customColorArgb: Int = 0xFF00FFFF.toInt(), // 主题颜色
    var displayMode: Int = 0,                  // 显示模式（见 CountdownFormatter.MODE_NAMES）
    var isVisible: Boolean = true,             // 是否在悬浮窗显示
    var remark: String = "",                   // 备注
    var soundUri: String? = null,              // 自定义提示音 URI；null = 默认提示音
    var isTopMost: Boolean = false,            // 置顶（安卓层叠顺序由 WindowManager 决定，预留）
    var finished: Boolean = false,             // 是否已归零（用于只播放一次提示音）
    var opacity: Int = 100,                    // 悬浮窗不透明度百分比（20..100，100=完全不透明）
    var collapsed: Boolean = false,            // 是否已收缩为小条（仅显示标题栏）
    var posX: Int = -1,                        // 悬浮窗位置 X（<0 表示未保存，使用默认错开位置）
    var posY: Int = -1,                        // 悬浮窗位置 Y
    var builtIn: Int = BuiltIn.NONE,           // 内置倒计时类型（见 BuiltIn）；旧数据缺省为普通倒计时
    var animStyle: Int = AnimStyle.NONE        // 跳秒动画样式（见 AnimStyle）；旧数据缺省为无动画
) {
    /** 是否为系统内置倒计时（当日 / 当月 / 华都云境悦府 / GTA6）——内置项不可删除 */
    fun isBuiltIn(): Boolean = builtIn != BuiltIn.NONE

    /**
     * 内置倒计时的目标时间由系统动态计算：当日 → 次日 00:00:00，当月 → 次月 1 日 00:00:00；
     * 固定目标型（华都云境悦府 / GTA6）恒取预设时刻，跨过之后停在 0，不会自动滚动到下一周期。
     * @return 目标时间是否发生变化（跨天 / 跨月时为 true，调用方据此重建界面并复位提示音状态）
     */
    fun refreshBuiltInTarget(): Boolean {
        if (builtIn == BuiltIn.NONE) return false
        // 固定目标时间的内置项：直接取常量时刻
        val fixed = BuiltIn.fixedTargetMillis(builtIn)
        if (fixed != null) {
            if (fixed == targetTime) return false
            targetTime = fixed
            return true
        }
        val cal = Calendar.getInstance()
        when (builtIn) {
            BuiltIn.DAY -> {
                cal.add(Calendar.DAY_OF_MONTH, 1) // 次日
            }
            BuiltIn.MONTH -> {
                // 先回到 1 号再进一个月，避免 1/31 + 1 个月 这种溢出
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, 1)
            }
            BuiltIn.WEEK -> {
                // 以周一为一周的第一天：先定位到本周周一（可能是今天或过去某天），
                // 再 +7 天即为「下周一 00:00」，也就是本周结束的那一刻。
                // 不设 firstDayOfWeek 的话 Calendar 默认周日起算，周日当天会算出 8 天后。
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.add(Calendar.DAY_OF_MONTH, 7)
            }
            else -> return false
        }
        // 统一为 00:00:00：既与「今日/本月结束的那一刻」语义一致，
        // 也让内置倒计时与用户自建倒计时（选择器只能选到分钟）一样对齐到整分，
        // 从而保证列表里所有倒计时的「秒」位数完全相同。
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val newTarget = cal.timeInMillis
        if (newTarget == targetTime) return false
        targetTime = newTarget
        return true
    }

    /**
     * 剩余时间文本（内置项会先刷新目标时间，跨天 / 跨月自动进入下一周期）。
     *
     * @param now 由调用方一次性取好的“当前时刻”。**同一帧刷新多个倒计时必须共用同一个 now**，
     *            否则各行各自取值、刚好跨越秒边界时会相差 1 秒，看起来像“没同步走秒”。
     */
    /**
     * 系统此刻应为该内置项生成的目标时间（**不修改自身字段**）。
     * 编辑页用它作为「用户有没有改动过时间」的基准：内置项的 targetTime 可能是上一次
     * 刷新留下的旧值（例如昨天的次日零点），直接拿它比对会误判。
     */
    fun currentBuiltInTarget(): Long {
        if (builtIn == BuiltIn.NONE) return targetTime
        BuiltIn.fixedTargetMillis(builtIn)?.let { return it }
        val saved = targetTime
        refreshBuiltInTarget()
        val t = targetTime
        targetTime = saved
        return t
    }

    fun remainingText(now: Long = AlignedClock.now()): String {
        refreshBuiltInTarget()
        // 内置项的「标准模式」含义按类型定制（见 effectiveMode），所以必须把 builtIn 一起传下去
        return CountdownFormatter.remaining(targetTime, displayMode, now, builtIn)
    }
}

/**
 * 距离下一个「整秒」时刻还剩多少毫秒（结果恒在 16..1015ms）。
 *
 * 主界面与悬浮窗都按这个延时刷新，而不是固定 1000ms：
 * - 刷新点固定落在整秒之后 15ms，所有条目都在同一瞬间跳秒，且不会出现固定周期的累积漂移；
 * - 15ms 余量避免正好卡在整秒边界上、因时钟抖动取到“上一秒”的值。
 */
fun millisToNextSecond(now: Long = System.currentTimeMillis()): Long {
    return 1000L - (now % 1000L) + 15L
}

/**
 * 显示模式名称与倒计时段文本生成（移植自桌面版 TimerData，并补齐安卓所需的全部模式）。
 */
object CountdownFormatter {

    /** 模式号常量：MODE_NAMES 的索引即值，只能在末尾追加。 */
    const val MODE_STANDARD = 0
    const val MODE_DAY = 4      // 天数模式：当日倒计时永远 0 天，对它已下线

    val MODE_NAMES = arrayOf(
        "标准模式",     // 0  xx周xx天xx时xx分xx秒
        "小时模式",     // 1  xx时
        "分钟模式",     // 2  xx分
        "秒模式",       // 3  xx秒
        "天数模式",     // 4  xx天
        "时分秒模式",   // 5  xx时xx分xx秒
        "天时分秒模式", // 6  xx天xx时xx分xx秒
        "天时分模式"    // 7  xx天xx时xx分
    )

    /**
     * 把「3天5时20分8秒」拆成（前缀, 最后一位数字, 后缀）。
     * 为了让跳秒动画只作用在最后一位数字上：找到文本中最后一个数字字符，
     * 它前面的内容归前缀、它本身单独一段、后面的单位字样归后缀。
     * 找不到数字时整体归入前缀（后两段为空）。
     */
    fun splitLastDigit(text: String): Triple<String, String, String> {
        for (i in text.length - 1 downTo 0) {
            if (text[i] in '0'..'9') {
                return Triple(text.substring(0, i), text.substring(i, i + 1), text.substring(i + 1))
            }
        }
        return Triple(text, "", "")
    }

    /**
     * 模式号合法化：历史数据里可能残留已下线的模式号（例如原 9 = 周天时分秒模式），
     * 统一回退到 0（标准模式），其文本格式与下线模式相同，用户无感。
     */
    fun normalizeMode(mode: Int): Int = if (mode in MODE_NAMES.indices) mode else 0

    fun modeName(mode: Int, builtIn: Int = BuiltIn.NONE): String =
        MODE_NAMES[normalizeMode(mode).coerceIn(0, MODE_NAMES.size - 1)]

    /**
     * 内置「当日 / 每周」倒计时的周期长度决定哪个模式才有意义：
     *
     * - **当日倒计时**（目标 = 次日 00:00，最多 24 小时）：周、天永远是 0，
     *   所以「标准模式」直接给 **xx时xx分xx秒**；「天数模式」同样没有意义，一并作废。
     * - **每周倒计时**（目标 = 下周一 00:00，最多 7 天）：周永远是 0，
     *   所以「标准模式」给 **xx天xx时xx分xx秒**。
     *
     * 其余倒计时、其余模式**一律原样返回**，行为不变。
     */
    fun effectiveMode(mode: Int, builtIn: Int): Int = when (builtIn) {
        BuiltIn.DAY -> if (mode == 0 || mode == 4) 5 else mode  // 标准 / 天数 → 时分秒
        BuiltIn.WEEK -> if (mode == 0) 6 else mode              // 标准 → 天时分秒
        else -> mode
    }

    /** 该条目可选的模式号列表：当日倒计时不含「天数模式」。 */
    fun availableModes(builtIn: Int): List<Int> =
        MODE_NAMES.indices.filter { m -> builtIn != BuiltIn.DAY || m != MODE_DAY }

    /** 该条目可选模式的名称（编辑页下拉框用）。 */
    fun modeNames(builtIn: Int): Array<String> =
        availableModes(builtIn).map { MODE_NAMES[it] }.toTypedArray()

    /** 模式号在可选列表中的位置（编辑页下拉框用）；不在列表里时退回第 0 项。 */
    fun modeIndex(mode: Int, builtIn: Int): Int {
        val i = availableModes(builtIn).indexOf(normalizeMode(mode))
        return if (i >= 0) i else 0
    }

    /** 把下拉框选中位置还原成模式号。 */
    fun modeAt(index: Int, builtIn: Int): Int {
        val list = availableModes(builtIn)
        return list[index.coerceIn(0, list.size - 1)]
    }

    fun remaining(
        targetTime: Long,
        mode: Int,
        now: Long = System.currentTimeMillis(),
        builtIn: Int = BuiltIn.NONE
    ): String {
        // 关键：目标时间与当前时刻都对齐到「整秒」再相减。
        // 若直接用 (targetTime - now) / 1000，各条目目标时间的毫秒尾数不同（历史数据常见，
        // 例如 .237 / .881 / .512），同一瞬间算出的秒数会彼此错开 1 秒，且每个条目都在
        // 各自不同的时刻跳秒——表现为「有的 29 秒、有的 30 秒、有的 31 秒」。
        // 对齐整秒后，所有倒计时都在墙上时钟过整秒的同一瞬间一起跳秒。
        var s = Math.floorDiv(targetTime, 1000L) - Math.floorDiv(now, 1000L)
        if (s < 0) s = 0
        return when (effectiveMode(mode, builtIn)) {
            0 -> weekDayHms(s)
            1 -> String.format("%d时", s / 3600)
            2 -> String.format("%d分", s / 60)
            3 -> String.format("%d秒", s)
            4 -> String.format("%d天", s / 86400)
            5 -> hms(s)
            6 -> dayHms(s)
            7 -> dayHm(s)
            else -> weekDayHms(s)
        }
    }

    private fun weekDayHms(s: Long): String {
        val weekSec = 7L * 24 * 3600
        val wks = s / weekSec
        val rem = s % weekSec
        val dd = rem / 86400
        val rem2 = rem % 86400
        val hh = rem2 / 3600
        val mm = (rem2 % 3600) / 60
        val ss = rem2 % 60
        return String.format("%d周%d天%d时%d分%d秒", wks, dd, hh, mm, ss)
    }

    private fun hms(s: Long): String {
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return String.format("%d时%d分%d秒", hh, mm, ss)
    }

    private fun dayHms(s: Long): String {
        val dd = s / 86400
        val rem = s % 86400
        val hh = rem / 3600
        val mm = (rem % 3600) / 60
        val ss = rem % 60
        return String.format("%d天%d时%d分%d秒", dd, hh, mm, ss)
    }

    private fun dayHm(s: Long): String {
        val dd = s / 86400
        val rem = s % 86400
        val hh = rem / 3600
        val mm = (rem % 3600) / 60
        return String.format("%d天%d时%d分", dd, hh, mm)
    }

}
