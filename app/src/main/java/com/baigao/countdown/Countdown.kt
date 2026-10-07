package com.baigao.countdown

import java.util.Calendar
import java.util.UUID

/**
 * v156：倒计时文字的「主题颜色」色板 —— 编辑页那颗颜色下拉框、列表卡片上新增的
 * 「颜色」按钮两处共用同一份（颜色只写一处，免得两边各改一半）。
 */
object ThemeColors {
    /** 七种可选颜色（ARGB 高字节为不透明；别只写 6 位，否则整块直接变透明）。 */
    val ARGS = intArrayOf(
        0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FF00.toInt(),
        0xFFFFFF00.toInt(), 0xFFFF8000.toInt(), 0xFFFF0000.toInt(), 0xFFE8B94A.toInt()
    )
    val NAMES = arrayOf("青色", "品红", "绿色", "黄色", "橙色", "红色", "金色")
    /** 当前色值对应的名字（挑不到就退回第一种，按钮上永远有字）。 */
    fun nameOf(argb: Int): String {
        val i = ARGS.indexOfFirst { it == argb }
        return if (i >= 0) NAMES[i] else NAMES[0]
    }
}

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
    const val HOUR = 6   // 每小时倒计时：目标为「下一个整点 00:00」（本小时结束的那一刻）
    const val HALF_HOUR = 7 // 每半小时倒计时：目标为「下一个半点 30 分」（本半小时结束的那一刻）
    // 以下四条短周期倒计时（8 / 9 / 10 / 11 只能末尾追加，改小会顶掉旧数据里的类型号）
    const val MINUTE = 8    // 每分钟倒计时：目标为「下一个整分」（本分钟结束的那一刻）
    const val FIVE_MIN = 9  // 每 5 分钟倒计时：目标为下一个 5 分边界（:05 / :10 / …）
    const val TEN_MIN = 10  // 每 10 分钟倒计时：目标为下一个 10 分边界（:10 / :20 / …）
    // ⚠️ v159：「100年内倒计时」整个档位连同它的代码一起删了（展示名、数据字段、
    // 跨过重新起一轮那套逻辑全撤）。这里只留一个空号常量占位 —— 编号不回收、
    // 也不再有任何代码认它；老数据（builtIn = 12 那几条）启动时由
    // MainActivity.dropRetiredCentury() 清掉。
    const val CENTURY = 12
    // ---- v151：新加的一大批周期滚动型内置项（编号只能末尾追加，绝不回收） ----
    // 每 2 / 3 / 4 / 6 / 7 / 8 / 9 分钟：目标为「下一个 N 分边界」（从 00:00 起算的 N 分钟倍数），
    //   与「每5分钟 / 每10分钟 / 每半小时 / 每小时」一个路数（每2分钟 → 下一个 :02 / :04 …）
    // 每 2 / 3 / … / 23 小时：目标为「下一个 N 小时边界」（从 00:00 起算的 N 小时倍数，恒为整点），
    //   等价于把「每小时」那一档的步长从 1 小时换到 N 小时；逐条与每小时倒计时同款（跨过自动重新倒数）
    const val MIN_2 = 13   // 每2分钟倒计时：目标为下一个 2 分边界（:02 / :04…）
    const val MIN_3 = 14   // 每3分钟倒计时：目标为下一个 3 分边界（:03 / :06…）
    const val MIN_4 = 15   // 每4分钟倒计时：目标为下一个 4 分边界（:04 / :08…）
    const val MIN_6 = 16   // 每6分钟倒计时：目标为下一个 6 分边界（:06 / :012…）
    const val MIN_7 = 17   // 每7分钟倒计时：目标为下一个 7 分边界（:07 / :014…）
    const val MIN_8 = 18   // 每8分钟倒计时：目标为下一个 8 分边界（:08 / :016…）
    const val MIN_9 = 19   // 每9分钟倒计时：目标为下一个 9 分边界（:09 / :018…）
    const val HOUR_2 = 20   // 每2小时倒计时：目标为下一个 2 小时边界（整点）
    const val HOUR_3 = 21   // 每3小时倒计时：目标为下一个 3 小时边界（整点）
    const val HOUR_4 = 22   // 每4小时倒计时：目标为下一个 4 小时边界（整点）
    const val HOUR_5 = 23   // 每5小时倒计时：目标为下一个 5 小时边界（整点）
    const val HOUR_6 = 24   // 每6小时倒计时：目标为下一个 6 小时边界（整点）
    const val HOUR_7 = 25   // 每7小时倒计时：目标为下一个 7 小时边界（整点）
    const val HOUR_8 = 26   // 每8小时倒计时：目标为下一个 8 小时边界（整点）
    const val HOUR_9 = 27   // 每9小时倒计时：目标为下一个 9 小时边界（整点）
    const val HOUR_10 = 28   // 每10小时倒计时：目标为下一个 10 小时边界（整点）
    const val HOUR_11 = 29   // 每11小时倒计时：目标为下一个 11 小时边界（整点）
    const val HOUR_12 = 30   // 每12小时倒计时：目标为下一个 12 小时边界（整点）
    const val HOUR_13 = 31   // 每13小时倒计时：目标为下一个 13 小时边界（整点）
    const val HOUR_14 = 32   // 每14小时倒计时：目标为下一个 14 小时边界（整点）
    const val HOUR_15 = 33   // 每15小时倒计时：目标为下一个 15 小时边界（整点）
    const val HOUR_16 = 34   // 每16小时倒计时：目标为下一个 16 小时边界（整点）
    const val HOUR_17 = 35   // 每17小时倒计时：目标为下一个 17 小时边界（整点）
    const val HOUR_18 = 36   // 每18小时倒计时：目标为下一个 18 小时边界（整点）
    const val HOUR_19 = 37   // 每19小时倒计时：目标为下一个 19 小时边界（整点）
    const val HOUR_20 = 38   // 每20小时倒计时：目标为下一个 20 小时边界（整点）
    const val HOUR_21 = 39   // 每21小时倒计时：目标为下一个 21 小时边界（整点）
    const val HOUR_22 = 40   // 每22小时倒计时：目标为下一个 22 小时边界（整点）
    const val HOUR_23 = 41   // 每23小时倒计时：目标为下一个 23 小时边界（整点）

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

    /**
     * 该类型是不是「周期滚动型」内置项：目标时刻由系统一格格往下跳（每小时 / 每半小时 /
     * 每分钟 …），而不是像华都云境悦府、GTA6 那样日子早就定死不动。
     */
    fun isRolling(type: Int): Boolean =
        type != NONE && type != HUADU && type != GTA6

    /**
     * 周期滚动型内置项的那「一格」有多长（分钟）：非周期滚动型恒返回 0。
     * 每分钟 1 分、每5分钟 5 分、每10分钟 10 分、每半小时 30 分、每小时 60 分；
     * v151 新加的每 2~9 分钟就是 2~9 分、每 2~23 小时就是 120~1380 分。
     * 目标时刻一律是「向上取整到从 00:00 起算的边界」——
     * 「每2分钟」就是 :02 / :04 / …，「每2小时」就是 02:00 / 04:00 / …，
     * 「每23小时」就是当日 23:00 → 次日 22:00 → …（v153 修：小时档以前被错压成下一个整点）。
     * 与「每5分钟」「每半小时」那一脉的规矩一模一样。
     */
    fun stepMinutes(type: Int): Int = when (type) {
        MINUTE -> 1
        FIVE_MIN -> 5
        TEN_MIN -> 10
        HALF_HOUR -> 30
        HOUR -> 60
        in MIN_2..MIN_9 -> type - MIN_2 + 2            // 每2分钟 2 分 … 每9分钟 9 分
        in HOUR_2..HOUR_23 -> (type - HOUR_2 + 2) * 60 // 每2小时 120 分 … 每23小时 1380 分
        else -> 0
    }

    /** 内置类型的展示名：卡片标题、下拉框、恢复内置对话框共用这一份，别在各处各写一遍。 */
    fun nameOf(type: Int): String = when (type) {
        NONE -> "普通倒计时"
        DAY -> "当日倒计时"
        MONTH -> "当月倒计时"
        HUADU -> "华都云境悦府倒计时"
        GTA6 -> "GTA6倒计时"
        WEEK -> "每周倒计时"
        HOUR -> "每小时倒计时"
        HALF_HOUR -> "每半小时倒计时"
        MINUTE -> "每分钟倒计时"
        FIVE_MIN -> "每5分钟倒计时"
        TEN_MIN -> "每10分钟倒计时"
        MIN_2 -> "每2分钟倒计时"
        MIN_3 -> "每3分钟倒计时"
        MIN_4 -> "每4分钟倒计时"
        MIN_6 -> "每6分钟倒计时"
        MIN_7 -> "每7分钟倒计时"
        MIN_8 -> "每8分钟倒计时"
        MIN_9 -> "每9分钟倒计时"
        HOUR_2 -> "每2小时倒计时"
        HOUR_3 -> "每3小时倒计时"
        HOUR_4 -> "每4小时倒计时"
        HOUR_5 -> "每5小时倒计时"
        HOUR_6 -> "每6小时倒计时"
        HOUR_7 -> "每7小时倒计时"
        HOUR_8 -> "每8小时倒计时"
        HOUR_9 -> "每9小时倒计时"
        HOUR_10 -> "每10小时倒计时"
        HOUR_11 -> "每11小时倒计时"
        HOUR_12 -> "每12小时倒计时"
        HOUR_13 -> "每13小时倒计时"
        HOUR_14 -> "每14小时倒计时"
        HOUR_15 -> "每15小时倒计时"
        HOUR_16 -> "每16小时倒计时"
        HOUR_17 -> "每17小时倒计时"
        HOUR_18 -> "每18小时倒计时"
        HOUR_19 -> "每19小时倒计时"
        HOUR_20 -> "每20小时倒计时"
        HOUR_21 -> "每21小时倒计时"
        HOUR_22 -> "每22小时倒计时"
        HOUR_23 -> "每23小时倒计时"
        else -> "内置倒计时"
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
    var opacity: Int = 100,                    // 悬浮窗玻璃底不透明度百分比（20..100，100=完全不透明）；只淡化玻璃，倒计时数字不受影响
    var collapsed: Boolean = false,            // 是否已收缩为小条（仅显示标题栏）
    var posX: Int = -1,                        // 悬浮窗位置 X（<0 表示未保存，使用默认错开位置）
    var posY: Int = -1,                        // 悬浮窗位置 Y
    var builtIn: Int = BuiltIn.NONE,           // 内置倒计时类型（见 BuiltIn）；旧数据缺省为普通倒计时
    var builtInManual: Boolean = false,        // 内置项被用户自己指定了时刻：不再自动滚动，但仍是内置项
    var animStyle: Int = AnimStyle.NONE        // 跳秒动画样式（见 AnimStyle）；旧数据缺省为无动画
) {
    /** 是否为系统内置倒计时（当日 / 当月 / 华都云境悦府 / GTA6）——内置项不可删除 */
    fun isBuiltIn(): Boolean = builtIn != BuiltIn.NONE
    /**
     * 是否为「周期滚动型」内置倒计时（每分钟 / 每5分钟 / 每10分钟 / 每半小时 /
     * 每小时 / 当日 / 每周 / 当月）——这一类的目标时刻由系统一格格往下跳，
     * 归零时只响默认提示音，界面上不提供换音入口。
     * 华都云境悦府 / GTA6 这类「固定目标」内置项：日子是早就定死的，
     * 不跟着日期一格格跳，它们的提示音照旧可挑（见 MainActivity / AddEditActivity）。
     */
    fun isPeriodicBuiltIn(): Boolean = builtIn in intArrayOf(
        BuiltIn.MINUTE, BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN, BuiltIn.HALF_HOUR,
        BuiltIn.HOUR, BuiltIn.DAY, BuiltIn.WEEK, BuiltIn.MONTH
    ) || builtIn in BuiltIn.MIN_2..BuiltIn.HOUR_23  // v151：每2~9分钟 / 每2~23小时，一律同款不给换音


    /**
     * 界面上摆不摆「挑自定义提示音」的那两颗按钮、以及卡片上那颗「提示音名称」：
     * 周期滚动型内置项（每分钟 / 每小时 / 当日 …）一律不给挑 —— 它的目标时刻是系统
     * 一格格往下跳的，归零照旧响默认提示音；固定目标内置项（华都云境悦府 / GTA6）
     * 和普通倒计时两边照旧给着。
     */
    fun soundEditable(): Boolean = !isPeriodicBuiltIn()


    /**
     * 内置倒计时的目标时间由系统动态计算：当日 → 次日 00:00:00，当月 → 次月 1 日 00:00:00，
     * 固定目标型（华都云境悦府 / GTA6）恒取预设时刻，跨过之后停在 0，不会自动滚动到下一周期。
     * @return 目标时间是否发生变化（跨天 / 跨月时为 true，调用方据此重建界面并复位提示音状态）
     */
    fun refreshBuiltInTarget(): Boolean {
        if (builtIn == BuiltIn.NONE) return false
        // 用户自己给内置项指定了时刻（builtInManual）：从这一刻起不再由系统滚动覆盖，
        // 但它的内置身份（builtIn 类型）保持不变——内置倒计时永远是内置倒计时。
        if (builtInManual) return false
        // 固定目标时间的内置项：直接取常量时刻
        val fixed = BuiltIn.fixedTargetMillis(builtIn)
        if (fixed != null) {
            if (fixed == targetTime) return false
            targetTime = fixed
            return true
        }
        val cal = Calendar.getInstance()
        // v151：每 2~9 分钟 / 每 2~23 小时这两批新内置项，一律照「按 N 分钟边界向上取整」办，
        // 与每5分钟 / 每10分钟 / 每半小时 / 每小时之前那几档是一个路数（1 / 5 / 10 / 30 / 60 分
        // 这五档在下面 when 里各自写死了一套，这里一动它们，免得老逻辑回归）。
        val v151Step = BuiltIn.stepMinutes(builtIn)
        if (v151Step > 0 && v151Step != 1 && v151Step != 5 &&
            v151Step != 10 && v151Step != 30 && v151Step != 60
        ) {
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (v151Step % 60 == 0) {
                // 每 2~23 小时这一批（v153 修）：目标 = 从当天 00:00 起算的下一个 N 小时边界
                // 每2小时就是 02:00 / 04:00 / 06:00 …，每23小时就是当日 23:00 → 次日 22:00 → …
                // 以前这里拿「分钟数 0~59」去除以步长，商恒为 0，于是 2~23 小时全被压成
                // 「下一个整点」，跟每小时一模一样，等于没按每 N 小时滚。
                val passed = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val next = (passed / v151Step + 1) * v151Step  // 当天 00:00 起算的绝对分钟
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.add(Calendar.MINUTE, next)  // 进位跨天交给 Calendar 自己算
            } else {
                // 每 2~9 分钟这一批：目标 = 从当天 00:00 起算的下一个 N 分钟边界
                val m = cal.get(Calendar.MINUTE)
                var nm = (m / v151Step + 1) * v151Step
                if (nm >= 60) { nm = 0; cal.add(Calendar.HOUR_OF_DAY, 1) }
                cal.set(Calendar.MINUTE, nm)
            }
            val t = cal.timeInMillis
            if (t == targetTime) return false
            targetTime = t
            return true
        }
        when (builtIn) {
            BuiltIn.HOUR -> {
                // 本小时结束的那一刻：把分/秒/毫秒归零后 +1 小时 = 下一个整点 00:00。
                // 注意 HOUR 不能走下面那段「统一归零到 00:00:00」——那会把小时也清零。
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.HOUR_OF_DAY, 1)
                val t = cal.timeInMillis
                if (t == targetTime) return false
                targetTime = t
                return true
            }
            BuiltIn.HALF_HOUR -> {
                // 本半小时结束的那一刻：分钟不到 30 就走到「本小时 30 分」，
                // 过了 30 分则归零分钟再进一小时，即「下一个整点」。
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.get(Calendar.MINUTE) < 30) {
                    cal.set(Calendar.MINUTE, 30)
                } else {
                    cal.set(Calendar.MINUTE, 0)
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                }
                val t = cal.timeInMillis
                if (t == targetTime) return false
                targetTime = t
                return true
            }
            BuiltIn.MINUTE -> {
                // 本分钟结束的那一刻：秒/毫秒归零后 +1 分钟 = 下一个整分 00 秒。
                // 与每小时同一路数：必须走这段单独分支，不能落进下面「统一归零到 00:00:00」。
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.MINUTE, 1)
                val t = cal.timeInMillis
                if (t == targetTime) return false
                targetTime = t
                return true
            }
            BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN -> {
                // 本 5 分钟（或 10 分钟）段结束的那一刻：秒/毫秒归零后跳到下一个 5 分（10 分）边界，
                // 边界分钟数为 60 时回绕到「下 0 分」，效果与每小时跳整点完全一致。
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val step = if (builtIn == BuiltIn.FIVE_MIN) 5 else 10
                val m = cal.get(Calendar.MINUTE)
                var nm = (m / step + 1) * step
                if (nm >= 60) {
                    nm = 0
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                }
                cal.set(Calendar.MINUTE, nm)
                val t = cal.timeInMillis
                if (t == targetTime) return false
                targetTime = t
                return true
            }
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

    /**
     * 本条目此刻应该显示的备注文本。
     *
     * 滚动型内置项（每小时 / 当日 / 每周 / 当月）的备注**随目标时间同步变化**，
     * 跨整点自动变成「距离18点整结束」、跨天变成「距离10月3日0点整结束」，
     * 而不是一直停在建立时那句静态文案；
     * 固定目标型（华都 / GTA6）与自建项沿用原备注。
     */
    fun remarkText(use24Hour: Boolean): String {
        if (!isBuiltIn()) return remark
        if (targetTime <= 0) return remark
        val cal = Calendar.getInstance().apply { timeInMillis = targetTime }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val midnight = TimeFormatPref.clockText(0, use24Hour)   // 「0点整」/「凌晨12点整」
        return when (builtIn) {
            BuiltIn.HOUR ->
                "距离${TimeFormatPref.clockText(cal.get(Calendar.HOUR_OF_DAY), use24Hour)}结束"
            BuiltIn.HALF_HOUR -> {
                val h = cal.get(Calendar.HOUR_OF_DAY)
                if (cal.get(Calendar.MINUTE) == 0) {
                    "距离${TimeFormatPref.clockText(h, use24Hour)}结束"
                } else {
                    // 「17点整」去掉「整」字再补个「半」：距离 17 点半结束（与每小时那句同一路数）
                    val half = TimeFormatPref.clockText(h, use24Hour).replace("点整", "点")
                    "距离${half}半结束"
                }
            }
            BuiltIn.MINUTE -> {
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val mm = cal.get(Calendar.MINUTE)
                if (mm == 0) "距离${TimeFormatPref.clockText(h, use24Hour)}结束"
                else "距离${h}点${String.format("%02d", mm)}分结束"
            }
            // v151：每 2~9 分钟这一批（不足 1 小时，边界落在整分上）备注同「每5分钟」那句；
            // 每 2~23 小时这一批（边界恒为整点）备注同「每小时」那句
            in BuiltIn.MIN_2..BuiltIn.MIN_9 -> {
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val mm = cal.get(Calendar.MINUTE)
                if (mm == 0) "距离${TimeFormatPref.clockText(h, use24Hour)}结束"
                else "距离${h}点${String.format("%02d", mm)}分结束"
            }
            in BuiltIn.HOUR_2..BuiltIn.HOUR_23 ->
                "距离${TimeFormatPref.clockText(cal.get(Calendar.HOUR_OF_DAY), use24Hour)}结束"
            BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN -> {
                // 「距离18点05分结束」（5 分档）、「距离18点20分结束」（10 分档）；
                // 正好落在整点时退成跟每小时同一句「距离18点整结束」
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val mm = cal.get(Calendar.MINUTE)
                if (mm == 0) "距离${TimeFormatPref.clockText(h, use24Hour)}结束"
                else "距离${h}点${String.format("%02d", mm)}分结束"
            }
            BuiltIn.DAY -> "距离${month}月${day}日${midnight}结束"
            BuiltIn.WEEK -> "距离${month}月${day}日${midnight}结束"
            BuiltIn.MONTH -> "距离${month}月1日${midnight}结束"
            else -> remark
        }
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
    const val MODE_HOUR = 1     // 小时模式：恒显示「N时」（当日倒计时最多 24 小时，有意义）
    const val MODE_MINUTE = 2
    const val MODE_SECOND = 3
    const val MODE_DAY = 4      // 天数模式：当日倒计时永远 0 天，对它已下线
    const val MODE_HMS = 5      // 时分秒模式
    const val MODE_DAY_HMS = 6  // 天时分秒模式：前面那截恒 0 天，短周期内置项用不上
    const val MODE_DAY_HM = 7   // 天时分模式：同「天时分秒模式」
    const val MODE_MINUTE_SECOND = 8  // 分秒模式：xx分xx秒（隐藏恒 0 的「时」那截）

    val MODE_NAMES = arrayOf(
        "标准模式",     // 0  xx周xx天xx时xx分xx秒
        "小时模式",     // 1  xx时
        "分钟模式",     // 2  xx分
        "秒模式",       // 3  xx秒
        "天数模式",     // 4  xx天
        "时分秒模式",   // 5  xx时xx分xx秒
        "天时分秒模式", // 6  xx天xx时xx分xx秒
        "天时分模式",   // 7  xx天xx时xx分
        "分秒模式"     // 8  xx分xx秒
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
     * 内置倒计时的周期长度决定哪个模式才有意义：
     *
     * - **每分钟倒计时**（最多 1 分钟）：「时 / 分」两截永远是 0，
     *   所以「标准模式」直接给 **xx秒**；「分钟模式」同理没有意义。
     * - **每 5 / 10 分钟倒计时**（最多 10 分钟）：「时」那截永远是 0，
     *   v155 起「标准模式」直接给 **xx分xx秒**（原来是 **xx分**，秒那截藏在背后看不见）；
     *   「时分秒模式」里恒 0 的「时」没意义。
     * - **每小时 / 每半小时**（最多 1 小时）：v155 起「标准模式」给 **xx分xx秒**
     *   （原来给 **xx时xx分xx秒**，1 小时里「时」那截只会是 0，摆出来纯占地方）；
     *   「天数模式」同样没有意义，一并作废。
     * - **当日倒计时**（最多 24 小时）：「标准模式」仍旧给 **xx时xx分xx秒**，不受上面那一条影响。
     * - **每周倒计时**（目标 = 下周一 00:00，最多 7 天）：周永远是 0，
     *   所以「标准模式」给 **xx天xx时xx分xx秒**。
     *
     * 其余倒计时、其余模式**一律原样返回**，行为不变（新增的「分秒模式」8 号同样原样返回）。
     */
    fun effectiveMode(mode: Int, builtIn: Int): Int = when (builtIn) {
        // 短周期内置项：周与天那两截永远是 0，所以「标准模式」不能直接给「时分秒」摆出恒 0 的「时」；
        // 「天数模式」同样没有意义，一并作废
        BuiltIn.MINUTE ->
            if (mode == 0 || mode == 4) MODE_SECOND else mode   // 标准 / 天数 → 秒模式（最多 1 分钟）
        BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN ->
            // v155：最多 10 分钟，「时」那截恒 0，秒得看得见 —— 原来走「分钟模式」只写 xx 分，
            // 秒藏在背后（每5分钟看成「5分」，实际还剩 4 分 59 秒）。标准 / 天数 → 分秒模式（xx分xx秒）
            if (mode == 0 || mode == 4) MODE_MINUTE_SECOND else mode
        // 每半小时（最多 1 小时）：「标准模式」(0) 直接给「分秒模式」（xx分xx秒），
        // 前面那截恒 0 的「时」去掉，看着干净；这条要排在 DAY / HOUR 那条前面
        BuiltIn.HALF_HOUR ->
            if (mode == 0 || mode == 4) MODE_MINUTE_SECOND else mode
        BuiltIn.DAY ->
            if (mode == 0 || mode == 4) MODE_HMS else mode   // 标准 / 天数 → 时分秒（当日最多 24 小时，「时」那截有数）
        BuiltIn.HOUR ->
            // v155：每小时（最多 1 小时）「时」那截只会是 0，标准模式从「时分秒」改成「分秒」（xx分xx秒），
            // 与每 5 / 10 分钟、每半小时、每 2~9 分钟一个写法；
            // 当日倒计时（最多 24 小时）「时」有数，仍归上面那一档走「时分秒」。
            if (mode == 0 || mode == 4) MODE_MINUTE_SECOND else mode
        BuiltIn.WEEK, BuiltIn.MONTH, BuiltIn.HUADU, BuiltIn.GTA6 ->
            if (mode == 0) 6 else mode   // 标准 / 天时分秒 → 天时分秒模式
        else -> {
            // v151 起每 2~9 分钟（最多 9 分钟）「时」那截恒 0，v151 那会儿标准模式给「分钟模式」；
            // v152 改成「分秒模式」（xx分xx秒），跟「每半小时」一个写法：秒要看得见。
            // 每 2~23 小时（最多 23 小时）「时」有数，标准模式给「时分秒模式」—— 与每小时那档一致。
            val st = BuiltIn.stepMinutes(builtIn)
            when {
                st <= 0 -> mode
                // v152：这七个档（每2~9分钟）最多 9 分钟，「时」恒 0，
                // 标准模式给「分秒模式」把秒摆出来 —— 原来是「分钟模式」，只写 xx 分，
                // 秒那截藏在背后看不见（每2分钟看成「2分」，实际还剩 1 分 59 秒）。
                st < 60 -> if (mode == 0 || mode == 4) MODE_MINUTE_SECOND else mode
                else -> if (mode == 0 || mode == 4) MODE_HMS else mode
            }
        }
    }

    /**
     * 该条目可选的模式号列表（编辑页的模式下拉框、悬浮窗与主界面的模式循环都按它走）。
     * 短周期的内置项把「前面那截永远是 0」的几档收掉：
     *
     * - **每 2~9 分钟倒计时**（v151 新加的那七个，最多 9 分钟）：「时」恒 0，标准模式直接给「分秒模式」
     *   （xx分xx秒）；可选档位是 标准（=分秒）/ 分钟 / 秒，跟「每半小时」一样。
     * - **每分钟倒计时**（最多 1 分钟）：砍「分钟模式」（恒显示 0 分）、「时分秒模式」（恒 0 时）、
     *   「分秒模式」（上一轮加过，这一轮撤掉了，1 分钟里它只会写成 0 分 30 秒这种）
     *   以及「小时模式 / 天数模式 / 天时分秒 / 天时分模式」，只留 标准（= 秒）/ 秒。
     * - **每 5 / 10 分钟倒计时**（最多 10 分钟）：砍「时分秒模式」（恒 0 时），
     *   再叠上「小时模式 / 天数模式 / 天时分秒 / 天时分模式」，v155 起剩 标准（= 分秒）/ 分钟 / 秒。
     * - **每半小时倒计时**（最多 1 小时）：砍「小时模式」（恒显示 0 时）、「天数模式」
     *   （恒 0 天）以及「天时分秒 / 天时分模式」（开头那截恒 0 天），剩 标准（= 分秒）/ 分钟 / 秒。
     * - **每小时倒计时**（最多 1 小时）：砍的与每半小时那三条一模一样，v155 起标准模式也是「分秒模式」，
     *   于是 每 5 / 10 分钟、每半小时、每小时三档的可选档位完全一样：标准 / 分钟 / 秒。
     * - **当日倒计时**（最多 24 小时）：砍「天数模式」加上「天时分秒 / 天时分模式」，
     *   「小时模式」可以留（它能实实在在显示 x 时）。
     * - 其余倒计时（每周 / 每月 / 华都云境悦府 / GTA6 / 普通）八档全开。
     */
    fun availableModes(builtIn: Int): List<Int> {
        val blocked = HashSet<Int>()
        if (builtIn == BuiltIn.MINUTE) {
            // 每分钟倒计时：分钟与时分秒那两档只会显示成 0 分 / 0 时，全都砍掉
            blocked.add(MODE_MINUTE)
            blocked.add(MODE_HMS)
            // 上一轮加的「分秒模式」（x分x秒）在 1 分钟里只会写成 0 分 30 秒这种，看着别扭，撤掉
            blocked.add(MODE_MINUTE_SECOND)
        } else if (builtIn == BuiltIn.FIVE_MIN || builtIn == BuiltIn.TEN_MIN) {
            // 每 5 / 10 分钟倒计时：时分秒那档的「时」恒 0，砍掉
            blocked.add(MODE_HMS)
        } else if (builtIn == BuiltIn.HOUR || builtIn == BuiltIn.HALF_HOUR) {
            // 每小时 / 每半小时（最多 1 小时）：「时分秒模式」里恒 0 的「时」没意义，
            // 换成「分秒模式」（xx分xx秒），少一层看不出用的显示
            blocked.add(MODE_HMS)
        }
        if (builtIn == BuiltIn.HOUR || builtIn == BuiltIn.HALF_HOUR ||
            builtIn == BuiltIn.MINUTE || builtIn == BuiltIn.FIVE_MIN || builtIn == BuiltIn.TEN_MIN
        ) {
            blocked.add(MODE_HOUR)
            blocked.add(MODE_DAY)
            blocked.add(MODE_DAY_HMS)
            blocked.add(MODE_DAY_HM)
        } else if (builtIn == BuiltIn.DAY) {
            blocked.add(MODE_DAY)
            blocked.add(MODE_DAY_HMS)
            blocked.add(MODE_DAY_HM)
        }
        // v151 新加的这两批：不足 1 小时的（每 2~9 分钟）把「小时模式 / 时分秒模式」一并砍掉
        // （时那截恒 0）；每 2~23 小时最长 23 小时，「时」有数要留着，砍的只是「天」那三档。
        // 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 每分钟这五档上面各自砍过一遍，这里再砍一遍也不碍事。
        val v151Step = BuiltIn.stepMinutes(builtIn)
        if (v151Step > 0) {
            if (v151Step < 60) {
                blocked.add(MODE_HOUR)
                blocked.add(MODE_HMS)
            }
            blocked.add(MODE_DAY)
            blocked.add(MODE_DAY_HMS)
            blocked.add(MODE_DAY_HM)
        }
        val list = MODE_NAMES.indices.filter { m -> m !in blocked }
        // 去重：某档渲染出来的格式和「标准模式」一模一样时，它就没存在必要了
        // （标准模式本身永远保留），留着只会让两个名字不同、长得一样的档位互相顶替。
        // 例如「每分钟」的标准模式就是秒模式，那一档「秒模式」必须撤掉，
        // 于是每分钟只剩一种显示，按钮也就跟着收起来了。
        val stdKey = formatKey(effectiveMode(MODE_STANDARD, builtIn))
        return list.filter { m -> m == MODE_STANDARD || formatKey(effectiveMode(m, builtIn)) != stdKey }
    }

    /** 该条目可选模式的名称（编辑页下拉框用）。 */
    fun modeNames(builtIn: Int): Array<String> =
        availableModes(builtIn).map { MODE_NAMES[it] }.toTypedArray()

    /** 模式号在可选列表中的位置（编辑页下拉框用）；不在列表里时退回第 0 项。 */
    fun modeIndex(mode: Int, builtIn: Int): Int {
        val i = availableModes(builtIn).indexOf(normalizeMode(mode))
        return if (i >= 0) i else 0
    }

    /**
     * 显示格式的「指纹」：effectiveMode 之后落进 remaining() 的哪个分支。
     * 两个档位指纹相同 = 显示出来长得一模一样（用来判断是不是重复档位）。
     */
    private fun formatKey(m: Int): Int = when (m) {
        0 -> 0
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        5 -> 5
        6 -> 6
        7 -> 7
        else -> 8
    }

    /**
     * 这条倒计时还剩「两种以上」可以切的显示模式吗。
     * 只剩一种（例如「每分钟」只有标准模式）时，卡片上的模式按钮、悬浮窗抽屉里
     * 那两颗「上一个 / 下一个显示模式」、以及编辑页的模式下拉框都要收起来，
     * 免得点一下切不动还白占地方。
     */
    fun hasModeSwitch(builtIn: Int): Boolean = availableModes(builtIn).size > 1

    /**
     * 显示格式的「指纹」：effectiveMode 之后落进 remaining() 的哪个分支。
     * 两个档位指纹相同 = 显示出来长得一模一样（用来判断是不是重复档位）。
     */
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
            8 -> ms(s)
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

    private fun ms(s: Long): String {
        val mm = s / 60
        val ss = s % 60
        return String.format("%d分%d秒", mm, ss)
    }

    private fun dayHm(s: Long): String {
        val dd = s / 86400
        val rem = s % 86400
        val hh = rem / 3600
        val mm = (rem % 3600) / 60
        return String.format("%d天%d时%d分", dd, hh, mm)
    }

}
