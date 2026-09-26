package com.baigao.countdown

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日吃药提醒（纯框架实现，零第三方依赖）。
 *
 * 三件事：
 * 1. 每天在用户设定的时刻（默认 09:00）发一条系统通知，通知上带「已吃药」按钮；
 * 2. 点「已吃药」把当天记进吃药记录，通知自动消失；
 * 3. 记录以「yyyy-MM-dd」为键存在 SharedPreferences 里，供「吃药日历」按天显示。
 *
 * 开关与提醒时间都在「关于」页里改（默认关闭）；关掉后闹钟一并取消。
 *
 * ---------- v1.0.0.21：为什么退出 App 后就不提醒了 ----------
 * 只挂一个 AlarmManager 精确闹钟，在下面几种情况里会「静默失效」：
 *   - 国产 ROM 从最近任务划掉卡片 = 强制停止，系统会**把这个应用的所有闹钟清掉**，
 *     而且停止状态下的包连开机广播都收不到，于是再也不会重排；
 *   - Doze 期间 `setExactAndAllowWhileIdle` 会被推迟到维护窗口，早上那种长时间静置
 *     的场景能晚几十分钟。
 * 所以这里改成**三路冗余 + 同一天只提醒一次**：
 *   ① `setAlarmClock`（系统级闹钟，能穿透 Doze，不需要精确闹钟权限）；
 *   ② 每天重复的非精确闹钟（主路被 ROM 清掉时兜底，提前/延后触发都不会重复打扰）；
 *   ③ JobScheduler 周期巡检（15 分钟一趟、重启自动恢复）：发现闹钟没了就补挂，
 *      发现今天该提醒的时刻已过却还没提醒过就**补发**通知。
 * 另外每次打开 App 都会 `catchUp()`：如果今天的提醒点已经过了却没响过，立刻补一条。
 *
 * ---------- v1.0.0.24：改了「下次提醒」之后仍然收不到 ----------
 * 前面三路在「改过时间」这一档上还是会漏：主路只挂一个点，ROM 一旦把闹钟清掉或推迟，
 * 当天就再没机会。于是再加两档：
 *   - 到点后 2 分钟、30 分钟各补响一次（REQ 4116 / 4117）；
 *   - 精确闹钟（REQ 4118）与系统闹钟同一时刻再挂一路；
 *   - 巡检再加一个入口（更新检查的 JobService 里也跑一次），并记下「上次真正发出提醒」的时刻，
 *     自检页一眼能分清是「压根没发」还是「发了但被系统拦下」。
 */
object MedicineReminder {

    /**
     * 渠道 id 带版本后缀：渠道重要性一旦定下来就不能就地修改，被系统/用户降成
     * 「无声通知」后 notify() 会静默失败（不报错、不显示）。换新 id = 重置回最高级。
     */
    const val CHANNEL_ID = "medicine_reminder_v26"
    /** 历代旧渠道，一律清掉。 */
    private val OLD_CHANNEL_IDS = listOf("medicine_reminder", "medicine_reminder_high")
    /** 常驻状态通知渠道：开启吃药提醒时显示「已开启」状态，不响铃（避免每次刷新都响）。 */
    const val STATUS_CHANNEL_ID = "medicine_reminder_status_v33"
    /** 闹钟通知当前是否正在展示：存展示当天日期，非空=展示中；跨天即视为过期失效。 */
    private const val KEY_ALARM_DATE = "alarm_showing_date"
    const val ACTION_ALARM = "com.baigao.countdown.action.MEDICINE_ALARM"
    const val ACTION_TAKEN = "com.baigao.countdown.action.MEDICINE_TAKEN"

    private const val PREF = "medicine_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_TIMES_PER_DAY = "times_per_day"
    private const val KEY_TAKEN = "taken"
    /** 各剂次已提醒记录：set of "yyyy-MM-dd#i"，保证同一剂一天只发一次。 */
    private const val KEY_NOTIFIED_DOSES = "notified_doses"
    /** 漏服超过此窗口（默认 4 小时）视为已漏，不再补发，避免补发骚扰。 */
    private const val LATE_WINDOW = 4L * 60 * 60 * 1000
    /** 上次挂载闹钟的时刻（自检页显示用）。 */
    private const val KEY_LAST_SCHEDULE = "last_schedule"
    /** 用户手动指定的「下一次提醒」时刻（毫秒；0 = 未指定，按每天固定时刻推算）。 */
    private const val KEY_NEXT_CUSTOM = "next_custom"
    /** 上次真正发出通知的时刻（自检页显示，用来判断「压根没发」还是「发了被拦」）。 */
    private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"

    private const val NOTIF_ID = 20331
    private const val REQ_ALARM = 4111
    private const val REQ_ALARM_BACKUP = 4114
    /** 补响 1：到点后 2 分钟再试一次（主路被系统推迟 / 丢掉时兜住）。 */
    private const val REQ_ALARM_RETRY1 = 4116
    /** 补响 2：到点后 30 分钟再试一次。 */
    private const val REQ_ALARM_RETRY2 = 4117
    /** 精确闹钟：与系统闹钟同一时刻，多一路就有多一分准时（没权限会被 catch 掉）。 */
    private const val REQ_ALARM_EXACT = 4118
    private const val REQ_ALARM_SHOW = 4115
    private const val REQ_TAKEN = 4112
    private const val REQ_OPEN = 4113
    /** 全屏意图（息屏时直接弹吃药页）用的请求码。 */
    private const val REQ_FULL = 4119

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前闹钟通知展示的是哪一天（非空=正在展示；跨天即过期）。 */
    private fun alarmShowingDate(ctx: Context): String =
        prefs(ctx).getString(KEY_ALARM_DATE, "") ?: ""

    /** 标记闹钟通知是否正在展示（展示中记当天日期，false 则清空）。 */
    private fun setAlarmShowing(ctx: Context, showing: Boolean) {
        prefs(ctx).edit().putString(KEY_ALARM_DATE, if (showing) todayKey() else "").apply()
    }

    // ---------------- 开关与提醒时间 ----------------

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun hour(ctx: Context): Int = prefs(ctx).getInt(KEY_HOUR, 9)
    fun minute(ctx: Context): Int = prefs(ctx).getInt(KEY_MINUTE, 0)

    fun timeText(ctx: Context): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour(ctx), minute(ctx))

    fun timesPerDay(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TIMES_PER_DAY, 1).coerceIn(1, 4)

    fun setTimesPerDay(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt(KEY_TIMES_PER_DAY, n.coerceIn(1, 4)).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 今天各次服药时刻（按一天内时间先后排序）。首剂 = 用户在「提醒时间」设的时间，
     *  之后每剂间隔 24h/N 均匀铺开（环绕到次日不影响当天触发计算）。 */
    fun doseTimes(ctx: Context): List<Pair<Int, Int>> {
        val n = timesPerDay(ctx)
        val base = hour(ctx) * 60 + minute(ctx)
        return (0 until n).map { i ->
            val tot = (base + i * (1440 / n)) % 1440
            (tot / 60) to (tot % 60)
        }.sortedBy { it.first * 60 + it.second }
    }

    /** 今天各次服药的毫秒值（与 doseTimes 顺序一致）。 */
    private fun todayDoseMillis(ctx: Context): List<Long> {
        val baseDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return doseTimes(ctx).map { (h, m) -> baseDay + (h * 60 + m) * 60_000L }
    }

    /** 把「每天 N 次」的时刻拼成可读串，如「09:00 15:00 21:00」。 */
    fun doseScheduleText(ctx: Context): String =
        doseTimes(ctx).joinToString(" ") { (h, m) ->
            String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) { schedule(ctx); syncMedicineNotification(ctx) } else cancel(ctx)
    }

    fun setTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    // ---------------- 吃药记录 ----------------

    /** 记录键统一为「yyyy-MM-dd」。 */
    fun keyFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun keyOf(cal: Calendar): String = keyFormat().format(cal.time)
    fun todayKey(): String = keyFormat().format(Date())

    /** getStringSet 返回的是内部实例，必须复制后再改，否则改动不会落盘。 */
    fun takenKeys(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_TAKEN, emptySet()) ?: emptySet()

    fun isTaken(ctx: Context, key: String = todayKey()): Boolean = takenKeys(ctx).contains(key)
    fun takenCount(ctx: Context): Int = takenKeys(ctx).size

    /** 写入 / 撤销某一天的记录（默认今天）。 */
    fun setTaken(ctx: Context, taken: Boolean, key: String = todayKey()) {
        val set = LinkedHashSet(takenKeys(ctx))
        if (taken) set.add(key) else set.remove(key)
        prefs(ctx).edit().putStringSet(KEY_TAKEN, set).apply()
    }

    private fun notifiedDoses(ctx: Context): MutableSet<String> =
        LinkedHashSet(prefs(ctx).getStringSet(KEY_NOTIFIED_DOSES, emptySet()) ?: emptySet())

    private fun isDoseNotified(ctx: Context, date: String, i: Int): Boolean =
        notifiedDoses(ctx).contains("$date#$i")

    private fun markDoseNotified(ctx: Context, date: String, i: Int) {
        val set = notifiedDoses(ctx)
        set.add("$date#$i")
        prefs(ctx).edit()
            .putStringSet(KEY_NOTIFIED_DOSES, set)
            .putLong(KEY_LAST_NOTIFY_TIME, System.currentTimeMillis())
            .apply()
    }

    // ---------------- 闹钟：三路冗余 ----------------

    /**
     * 下一次响铃时刻：
     * - 用户手动改过「下次提醒」且还没到 → 用那个时刻；
     * - 否则按每天固定时刻推算（过了今天的设定时间就顺延到明天）。
     *
     * 注意：**这里必须保持纯推算、不能有副作用**。以前「手动时刻已过就顺手清掉」写在
     * 这儿，而后台巡检每趟都会先调它重挂闹钟 —— 手动设定的时间被提前作废，
     * 后面的补发判定就再也认不出「用户手动改的那一次到期了」，于是整条兜底链都失效。
     * 作废统一交给 maybeNotify()：提醒真的发出去（或彻底没戏）之后才清。
     */
    fun nextTriggerMillis(ctx: Context): Long {
        val custom = nextCustomMillis(ctx)
        val now = System.currentTimeMillis()
        if (custom > now) return custom
        var best = Long.MAX_VALUE
        for (t in todayDoseMillis(ctx)) {
            val cand = if (t > now) t else t + 24 * 60 * 60 * 1000L
            if (cand < best) best = cand
        }
        return best
    }

    // ---------------- 手动指定「下一次提醒」 ----------------

    /** 手动设定的下一次提醒时刻（0 = 未设定）。 */
    fun nextCustomMillis(ctx: Context): Long = prefs(ctx).getLong(KEY_NEXT_CUSTOM, 0L)

    /** 当前「下次提醒」是不是用户手动改出来的。 */
    fun nextIsCustom(ctx: Context): Boolean = nextCustomMillis(ctx) > System.currentTimeMillis()

    /** 手动指定下一次提醒时刻，立刻按新时间重新挂载闹钟。 */
    fun setNextCustom(ctx: Context, millis: Long) {
        prefs(ctx).edit().putLong(KEY_NEXT_CUSTOM, millis).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 取消手动设定，恢复「每天固定时刻」。 */
    fun clearNextCustom(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_NEXT_CUSTOM, 0L).apply()
    }

    /** 今天设定时刻的毫秒值（不管过没过）。 */
    private fun todayTriggerMillis(ctx: Context): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(ctx))
            set(Calendar.MINUTE, minute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 排下一次提醒（对外入口）。 */
    fun schedule(ctx: Context) {
        if (!isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        ensureAlarms(ctx)
    }

    /**
     * 真正挂载：系统闹钟（主）+ 每天重复闹钟（备）+ JobScheduler 巡检（兜底）。
     * 重复调用是安全的 —— 只是用同一个 PendingIntent 覆盖旧闹钟。
     */
    private fun ensureAlarms(ctx: Context) {
        val trigger = nextTriggerMillis(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPi(ctx)

        // ① 系统闹钟：优先级最高，Doze 也能按时唤起，且不需要精确闹钟权限
        var ok = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showPi(ctx)), pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            ok = true
        } catch (e: Throwable) {
            try {
                // 退而求其次：精确闹钟（Android 12+ 需要权限，没权限会抛异常）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
                ok = true
            } catch (e2: Throwable) {
                // 两条精确路都走不通，下面那条重复闹钟一定还在
            }
        }

        // ② 精确闹钟：与 ① 同一时刻，走 allowWhileIdle，Doze 下也能在维护窗口里被唤起
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger, exactPi(ctx)
                )
            }
        } catch (e: Throwable) {
            // 没有精确闹钟权限会被拦下，还有另外三路
        }

        // ③ 每天重复的非精确闹钟：不依赖精确闹钟权限，也不容易被 ROM 当成「精确闹钟」拦下。
        //    它与 ①② 同一天都会触发，但 maybeNotify() 保证一天只发一次通知。
        try {
            if (ok) am.cancel(backupPi(ctx))
            am.setRepeating(
                AlarmManager.RTC_WAKEUP, trigger, AlarmManager.INTERVAL_DAY, backupPi(ctx)
            )
        } catch (e: Throwable) {
            // 忽略
        }

        // ④ 补响：主路万一被系统推迟或丢掉，到点后 2 分钟、30 分钟各再试一次。
        //    走的是同一个 action，maybeNotify() 保证一天只发一条通知，不会重复打扰。
        try {
            am.cancel(retry1Pi(ctx))
            am.set(AlarmManager.RTC_WAKEUP, trigger + 2 * 60 * 1000L, retry1Pi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }
        try {
            am.cancel(retry2Pi(ctx))
            am.set(AlarmManager.RTC_WAKEUP, trigger + 30 * 60 * 1000L, retry2Pi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }

        // ⑤ JobScheduler 巡检：闹钟被清掉时补挂、错过就补发
        MedicineCheckJobService.schedule(ctx)

        try {
            prefs(ctx).edit().putLong(KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /** 闹钟响了：先把下一次排上（幂等），再发通知；手动改的那次由 maybeNotify 兑现后再作废。 */
    fun onAlarm(ctx: Context) {
        if (!isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        schedule(ctx)
        maybeNotify(ctx)
        syncMedicineNotification(ctx)
    }

    /** JobScheduler 巡检：补挂闹钟 + 错过就补发。 */
    fun onPeriodicCheck(ctx: Context) {
        if (!isEnabled(ctx)) return
        ensureAlarms(ctx)
        maybeNotify(ctx)
        syncMedicineNotification(ctx)
    }

    /**
     * 打开 App 时补一次：今天该提醒的时刻已经过了、却又没提醒过（关机、闹钟被清、
     * 系统推迟等），立刻补一条通知，免得整天都不响。
     */
    fun catchUp(ctx: Context) {
        if (!isEnabled(ctx)) return
        schedule(ctx)
        maybeNotify(ctx)
        syncMedicineNotification(ctx)
    }

    /**
     * 到点才发通知，每天按设定次数分别提醒（每剂一天最多一次）。
     *
     * 唯一例外：**用户手动指定的「下一次提醒」到期时无条件提醒一次** ——
     * 「今天已提醒过」「今天已记过「已吃药」」都不拦。改时间就是为了再收一次，
     * 若被去重逻辑吞掉，表现出来就是「改了下次提醒时间，退出软件后反倒收不到了」。
     *
     * @param force 无视一切直接发（「测试提醒」用，不写去重标记）。
     */
    fun maybeNotify(ctx: Context, force: Boolean = false) {
        if (!force && !isEnabled(ctx)) return
        val now = System.currentTimeMillis()
        val today = todayKey()
        val custom = nextCustomMillis(ctx)
        val customDue = custom > 0L && now >= custom   // 手动指定的那一次到期了
        var late = false
        var doseIndex = -1
        if (!force) {
            if (customDue) {
                late = now - custom > 5 * 60 * 1000L
            } else {
                if (isTaken(ctx, today)) return          // 今天已经点了「已吃药」，不再打扰
                doseIndex = dueDoseIndex(ctx, now)
                if (doseIndex < 0) return                // 现在没有该响的剂次
                late = now - todayDoseMillis(ctx)[doseIndex] > 5 * 60 * 1000L
            }
        } else {
            // 测试提醒：展示最近一次还没过的剂次，找不到就用第 1 剂
            val doses = todayDoseMillis(ctx)
            doseIndex = doses.indexOfFirst { it > now }
            if (doseIndex < 0) doseIndex = 0
        }
        if (!UpdateNotifier.hasPermission(ctx)) {
            if (customDue) {
                clearNextCustom(ctx)
                schedule(ctx)
            }
            return
        }
        if (notifyNow(ctx, doseIndex, late)) {
            if (!force) {
                if (customDue || (custom > 0L && custom <= now)) {
                    clearNextCustom(ctx)                 // 手动指定的那次已兑现，回到每天固定时刻
                } else if (doseIndex >= 0) {
                    markDoseNotified(ctx, today, doseIndex)
                }
                schedule(ctx)
            } else {
                prefs(ctx).edit().putLong(KEY_LAST_NOTIFY_TIME, now).apply()
            }
        }
    }

    /**
     * 找出「现在应当响」的那一剂：今天该时刻已过、今天尚未提醒过、且在迟到窗口内。
     * 太早（未来）不响；漏掉太久（超过窗口）先静默标记为已提醒，避免补发骚扰。
     */
    private fun dueDoseIndex(ctx: Context, now: Long): Int {
        val date = todayKey()
        val doses = todayDoseMillis(ctx)
        for (i in doses.indices) {
            if (isDoseNotified(ctx, date, i)) continue
            val t = doses[i]
            if (now >= t) {
                if (now - t <= LATE_WINDOW) return i
                markDoseNotified(ctx, date, i)   // 漏掉的太早一剂：静默记掉，不再补
            }
        }
        return -1
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPi(ctx))
            am.cancel(backupPi(ctx))
            am.cancel(exactPi(ctx))
            am.cancel(retry1Pi(ctx))
            am.cancel(retry2Pi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }
        MedicineCheckJobService.cancel(ctx)
        cancelNotification(ctx)
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun alarmPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun backupPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_BACKUP,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun exactPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_EXACT,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun retry1Pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_RETRY1,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun retry2Pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_RETRY2,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    /** 状态栏闹钟图标被点时的去向（回主界面）。 */
    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, REQ_ALARM_SHOW,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        piFlags()
    )

    private fun takenPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_TAKEN,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_TAKEN),
        piFlags()
    )

    // ---------------- 通知 ----------------

    /** 到点发通知：点本体进吃药日历，点「已吃药」直接记录今天。 */
    fun notifyNow(ctx: Context, doseIndex: Int = -1, late: Boolean = false): Boolean {
        if (!UpdateNotifier.hasPermission(ctx)) return false
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(nm)
            val openPi = PendingIntent.getActivity(
                ctx, REQ_OPEN,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags()
            )
            val doses = timesPerDay(ctx)
            val doseLabel = if (doseIndex >= 0 && doses > 1) "（第 ${doseIndex + 1}/$doses 次）" else ""
            val title = (if (late) "该吃药了（补发提醒）" else "该吃药了") + doseLabel
            val text = if (late) {
                "刚才没能按时弹出，现在补上；点「已吃药」记录今天"
            } else {
                "每天 $doses 次提醒（${doseScheduleText(ctx)}）；点「已吃药」记录今天"
            }
            // 全屏意图：息屏 / 锁屏时把吃药页直接弹到眼前（闹钟类通知才有的待遇）。
            // 普通通知在国产 ROM 上很容易被收进「无声通知」，这个能真正把人叫到。
            val fullPi = PendingIntent.getActivity(
                ctx, REQ_FULL,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ),
                piFlags()
            )
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(R.drawable.ic_stat, "已吃药", takenPi(ctx))
                .setCategory(Notification.CATEGORY_ALARM)
                // 与系统闹钟一致：常驻通知，直到点「已吃药」才消失；退出 / 关闭软件也一直在
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
            nm.notify(NOTIF_ID, n)
            setAlarmShowing(ctx, true)
            true
        } catch (e: Throwable) {
            android.util.Log.w("MedicineReminder", "notifyNow: ${e.message}")
            false
        }
    }

    fun cancelNotification(ctx: Context) {
        setAlarmShowing(ctx, false)
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /**
     * 常驻状态通知：吃药提醒开启时显示「每日吃药提醒 · 已开启」+ 下次提醒时间；
     * 关闭时由 [syncMedicineNotification] 取消。不响铃、不震动、不弹全屏，仅作状态常驻。
     */
    fun postStatusNotification(ctx: Context) {
        if (!UpdateNotifier.hasPermission(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createStatusChannel(nm)
            val openPi = PendingIntent.getActivity(
                ctx, REQ_OPEN,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags()
            )
            val taken = isTaken(ctx)
            val title = "每日吃药提醒 · 已开启"
            val text = if (taken) {
                "今日已吃药 ✓ · 下次提醒 ${nextTriggerText(ctx)}"
            } else {
                "下次提醒 ${nextTriggerText(ctx)}（点开可看吃药日历）"
            }
            val n = Notification.Builder(ctx, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
            setAlarmShowing(ctx, false)
            nm.notify(NOTIF_ID, n)
        } catch (e: Throwable) {
            android.util.Log.w("MedicineReminder", "postStatusNotification: ${e.message}")
        }
    }

    /** 状态渠道：不响铃、不震动，仅常驻显示状态（避免每次刷新都响铃）。 */
    private fun createStatusChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            if (nm.getNotificationChannel(STATUS_CHANNEL_ID) != null) return
        } catch (e: Throwable) {
            return
        }
        val ch = NotificationChannel(
            STATUS_CHANNEL_ID, "吃药提醒状态", NotificationManager.IMPORTANCE_DEFAULT
        )
        ch.description = "吃药提醒开启时的常驻状态提示（不响铃）"
        ch.enableVibration(false)
        try {
            ch.setSound(null, null)
        } catch (e: Throwable) {
            // 忽略
        }
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try {
            nm.createNotificationChannel(ch)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /**
     * 根据开关与提醒状态同步「每日吃药提醒」通知：
     * - 关闭 → 取消通知（需求①）；
     * - 开启 → 显示「已开启」状态通知（需求②③，通知内容同步显示启动 / 关闭状态）。
     * 已点「已吃药」或当天已发过提醒时，展示状态通知而非闹钟通知。
     */
    fun syncMedicineNotification(ctx: Context) {
        // 昨天的闹钟通知已过期：清掉标记，让下方按今天的状态重新刷新
        val ad = alarmShowingDate(ctx)
        if (ad.isNotEmpty() && ad != todayKey()) setAlarmShowing(ctx, false)
        if (!isEnabled(ctx)) {
            cancelNotification(ctx)   // 关闭 → 取消每日吃药提醒通知
            return
        }
        if (alarmShowingDate(ctx).isNotEmpty()) return  // 当前闹钟通知正在展示，先不动它
        postStatusNotification(ctx)  // 开启 → 启动并显示「已开启」状态通知
    }

    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        for (oldId in OLD_CHANNEL_IDS) {
            try {
                if (nm.getNotificationChannel(oldId) != null) {
                    nm.deleteNotificationChannel(oldId)
                }
            } catch (e: Throwable) {
                // 部分 ROM 不允许删除渠道，忽略即可
            }
        }
        val exist = try {
            nm.getNotificationChannel(CHANNEL_ID)
        } catch (e: Throwable) {
            null
        }
        if (exist != null) {
            if (exist.importance == NotificationManager.IMPORTANCE_NONE) {
                try {
                    nm.deleteNotificationChannel(CHANNEL_ID)
                } catch (e: Throwable) {
                    return
                }
            } else {
                return
            }
        }
        val ch = NotificationChannel(
            CHANNEL_ID, "吃药提醒", NotificationManager.IMPORTANCE_MAX
        )
        ch.description = "每天到点提醒吃药，可点通知上的「已吃药」记录"
        ch.enableVibration(true)
        ch.setShowBadge(true)
        ch.setBypassDnd(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try {
            ch.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        } catch (e: Throwable) {
            // 拿不到默认铃声就交给系统默认行为
        }
        nm.createNotificationChannel(ch)
    }

    // ---------------- 自检（「关于」页用） ----------------

    /** 下次提醒时间，形如「09-19 09:00」。 */
    fun nextTriggerText(ctx: Context): String {
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return f.format(Date(nextTriggerMillis(ctx)))
    }

    /** Android 12+ 起精确闹钟是「特殊权限」，被关掉时精确路会失效（我们还有后两路）。 */
    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } catch (e: Throwable) {
            false
        }
    }

    /** 跳到系统的「闹钟与提醒」授权页（Android 12+ 才有）。 */
    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Throwable) {
                // 忽略
            }
        }
    }

    /** 上次成功挂载闹钟的时间描述（自检用）。 */
    fun lastScheduleText(ctx: Context): String {
        val t = prefs(ctx).getLong(KEY_LAST_SCHEDULE, 0L)
        if (t <= 0L) return "未挂载"
        val min = Math.max(0L, (System.currentTimeMillis() - t) / 60000L)
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "${min} 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / 1440} 天前"
        }
    }

    /** 上次真正发出提醒通知的时刻描述（自检用：「没发过」说明确实没发出来）。 */
    fun lastNotifyText(ctx: Context): String {
        val t = prefs(ctx).getLong(KEY_LAST_NOTIFY_TIME, 0L)
        if (t <= 0L) return "还没发过"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }

    /** 「关于」页状态行：一眼看出提醒到底挂上没有、卡在哪一环。 */
    fun statusText(ctx: Context): String {
        val on = isEnabled(ctx)
        if (!on) return "提醒已关闭 · 已记录 ${takenCount(ctx)} 天"
        val today = if (isTaken(ctx)) "已吃药 ✓" else "未吃药"
        val n = timesPerDay(ctx)
        val sched = if (n > 1) "\n每日 $n 次：${doseScheduleText(ctx)}" else ""
        val tail = if (nextIsCustom(ctx)) "（已手动改）" else ""
        return "今日：$today · 已记录 ${takenCount(ctx)} 天\n下次提醒 ${nextTriggerText(ctx)}$tail$sched"
    }
}
