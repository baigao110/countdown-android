package com.baigao.countdown

import java.util.Calendar
import java.util.UUID

/**
 * 内置倒计时类型：系统自动维护目标时间、且不可删除（对应桌面版 BuiltInType 枚举）。
 */
object BuiltIn {
    const val NONE = 0   // 普通倒计时
    const val DAY = 1    // 当日倒计时：目标为今日 23:59:59
    const val MONTH = 2  // 当月倒计时：目标为本月最后一天 23:59:59
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
    var builtIn: Int = BuiltIn.NONE            // 内置倒计时类型（见 BuiltIn）；旧数据缺省为普通倒计时
) {
    /** 是否为系统内置倒计时（当日 / 当月）——内置项不可删除 */
    fun isBuiltIn(): Boolean = builtIn != BuiltIn.NONE

    /**
     * 内置倒计时的目标时间由系统动态计算：当日 → 今日 23:59:59，当月 → 本月最后一天 23:59:59。
     * @return 目标时间是否发生变化（跨天 / 跨月时为 true，调用方据此重建界面并复位提示音状态）
     */
    fun refreshBuiltInTarget(): Boolean {
        if (builtIn == BuiltIn.NONE) return false
        val cal = Calendar.getInstance()
        when (builtIn) {
            BuiltIn.DAY -> {
                // 当日：目标为今天 23:59:59
            }
            BuiltIn.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            }
            else -> return false
        }
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
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
    fun remainingText(now: Long = System.currentTimeMillis()): String {
        refreshBuiltInTarget()
        return CountdownFormatter.remaining(targetTime, displayMode, now)
    }
}

/**
 * 显示模式名称与倒计时段文本生成（移植自桌面版 TimerData，并补齐安卓所需的全部模式）。
 */
object CountdownFormatter {

    val MODE_NAMES = arrayOf(
        "标准模式",     // 0  xx周xx天xx时xx分xx秒
        "小时模式",     // 1  xx时
        "分钟模式",     // 2  xx分
        "秒模式",       // 3  xx秒
        "天数模式",     // 4  xx天
        "时分秒模式",   // 5  xx时xx分xx秒
        "天时分秒模式", // 6  xx天xx时xx分xx秒
        "天时分模式",   // 7  xx天xx时xx分
        "周模式",       // 8  ≥7天显示 xx周，否则智能
        "周天时分秒模式" // 9  xx周xx天xx时xx分xx秒
    )

    fun modeName(mode: Int): String =
        if (mode in MODE_NAMES.indices) MODE_NAMES[mode] else MODE_NAMES[0]

    fun remaining(targetTime: Long, mode: Int, now: Long = System.currentTimeMillis()): String {
        var diff = targetTime - now
        if (diff < 0) diff = 0
        val s = diff / 1000
        return when (mode) {
            0 -> weekDayHms(s)
            1 -> String.format("%d时", s / 3600)
            2 -> String.format("%d分", s / 60)
            3 -> String.format("%d秒", s)
            4 -> String.format("%d天", s / 86400)
            5 -> hms(s)
            6 -> dayHms(s)
            7 -> dayHm(s)
            8 -> if (s >= 7L * 86400) String.format("%02d周", s / (7L * 86400)) else smart(s)
            9 -> weekDayHms(s)
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

    private fun smart(s: Long): String {
        val dd = s / 86400
        val rem = s % 86400
        val hh = rem / 3600
        val mm = (rem % 3600) / 60
        val ss = rem % 60
        val parts = ArrayList<String>()
        if (dd > 0) parts.add("${dd}天")
        if (hh > 0) parts.add("${hh}时")
        if (mm > 0) parts.add("${mm}分")
        if (ss > 0 || parts.isEmpty()) parts.add("${ss}秒")
        return parts.joinToString("")
    }
}
