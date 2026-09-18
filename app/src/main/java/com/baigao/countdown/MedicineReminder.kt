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
 * 开关与提醒时间都在「关于」页里改（默认开启、09:00）；关掉后闹钟一并取消。
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
 */
object MedicineReminder {

    const val CHANNEL_ID = "medicine_reminder_high"
    const val ACTION_ALARM = "com.baigao.countdown.action.MEDICINE_ALARM"
    const val ACTION_TAKEN = "com.baigao.countdown.action.MEDICINE_TAKEN"

    private const val PREF = "medicine_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_TAKEN = "taken"
    /** 已经提醒过的日期（yyyy-MM-dd）：三路冗余同时触发时靠它保证一天只打扰一次。 */
    private const val KEY_LAST_NOTIFY = "last_notify"
    /** 上次挂载闹钟的时刻（自检页显示用）。 */
    private const val KEY_LAST_SCHEDULE = "last_schedule"
    /** 用户手动指定的「下一次提醒」时刻（毫秒；0 = 未指定，按每天固定时刻推算）。 */
    private const val KEY_NEXT_CUSTOM = "next_custom"

    private const val NOTIF_ID = 20331
    private const val REQ_ALARM = 4111
    private const val REQ_ALARM_BACKUP = 4114
    private const val REQ_ALARM_SHOW = 4115
    private const val REQ_TAKEN = 4112
    private const val REQ_OPEN = 4113

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 开关与提醒时间 ----------------

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)
    fun hour(ctx: Context): Int = prefs(ctx).getInt(KEY_HOUR, 9)
    fun minute(ctx: Context): Int = prefs(ctx).getInt(KEY_MINUTE, 0)

    fun timeText(ctx: Context): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour(ctx), minute(ctx))

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(ctx) else cancel(ctx)
    }

    fun setTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        schedule(ctx)
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

    private fun lastNotifyKey(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_NOTIFY, "") ?: ""

    private fun setLastNotifyKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_LAST_NOTIFY, key).apply()
    }

    // ---------------- 闹钟：三路冗余 ----------------

    /**
     * 下一次响铃时刻：
     * - 用户手动改过「下次提醒」且还没到 → 用那个时刻；
     * - 否则按每天固定时刻推算（过了今天的设定时间就顺延到明天）。
     */
    fun nextTriggerMillis(ctx: Context): Long {
        val custom = nextCustomMillis(ctx)
        val now = System.currentTimeMillis()
        if (custom > now) return custom
        if (custom > 0L) clearNextCustom(ctx)   // 手动改的时刻已经过去，回到每天固定时刻
        val today = todayTriggerMillis(ctx)
        return if (today > now) today else today + 24 * 60 * 60 * 1000L
    }

    /** 本次「该提醒的时刻」：手动改过就用改后的，否则用今天的固定时刻。 */
    private fun dueMillis(ctx: Context): Long {
        val custom = nextCustomMillis(ctx)
        val now = System.currentTimeMillis()
        return if (custom > now) custom else todayTriggerMillis(ctx)
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

        // ② 每天重复的非精确闹钟：不依赖精确闹钟权限，也不容易被 ROM 当成「精确闹钟」拦下。
        //    它与 ① 同一天都会触发，但 maybeNotify() 保证一天只发一次通知。
        try {
            if (ok) am.cancel(backupPi(ctx))
            am.setRepeating(
                AlarmManager.RTC_WAKEUP, trigger, AlarmManager.INTERVAL_DAY, backupPi(ctx)
            )
        } catch (e: Throwable) {
            // 忽略
        }

        // ③ JobScheduler 巡检：闹钟被清掉时补挂、错过提醒时补发
        MedicineCheckJobService.schedule(ctx)

        try {
            prefs(ctx).edit().putLong(KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /** 闹钟响了：这次的「手动改时间」已兑现，清掉后按常规排下一次，再（按需）发通知。 */
    fun onAlarm(ctx: Context) {
        if (!isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        clearNextCustom(ctx)
        schedule(ctx)
        maybeNotify(ctx)
    }

    /** JobScheduler 巡检：补挂闹钟 + 错过就补发。 */
    fun onPeriodicCheck(ctx: Context) {
        if (!isEnabled(ctx)) return
        ensureAlarms(ctx)
        maybeNotify(ctx)
    }

    /**
     * 打开 App 时补一次：今天该提醒的时刻已经过了、却又没提醒过（关机、闹钟被清、
     * 系统推迟等），立刻补一条通知，免得整天都不响。
     */
    fun catchUp(ctx: Context) {
        if (!isEnabled(ctx)) return
        schedule(ctx)
        maybeNotify(ctx)
    }

    /**
     * 到点才发通知，一天最多一次。
     * @param force 无视「今天已提醒过 / 未到点」直接发（「测试提醒」用，不写去重标记）。
     */
    fun maybeNotify(ctx: Context, force: Boolean = false) {
        if (!force && !isEnabled(ctx)) return
        val today = todayKey()
        var late = false
        if (!force) {
            if (isTaken(ctx, today)) return          // 今天已经点了「已吃药」，不再打扰
            if (lastNotifyKey(ctx) == today) return  // 今天已经提醒过（三路冗余去重）
            val due = dueMillis(ctx)
            val now = System.currentTimeMillis()
            if (now < due) return                    // 还没到点（改过时间就等改后的时刻）
            late = now - due > 5 * 60 * 1000L         // 晚了 5 分钟以上算补发
        }
        if (!UpdateNotifier.hasPermission(ctx)) return
        if (notifyNow(ctx, late)) setLastNotifyKey(ctx, today)
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPi(ctx))
            am.cancel(backupPi(ctx))
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
    fun notifyNow(ctx: Context, late: Boolean = false): Boolean {
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
            val title = if (late) "该吃药了（补发提醒）" else "该吃药了"
            val text = if (late) {
                "刚才没能按时弹出，现在补上；点「已吃药」记录今天"
            } else {
                "点「已吃药」记录今天，也可进吃药日历查看往日记录"
            }
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_stat, "已吃药", takenPi(ctx))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
            nm.notify(NOTIF_ID, n)
            true
        } catch (e: Throwable) {
            android.util.Log.w("MedicineReminder", "notifyNow: ${e.message}")
            false
        }
    }

    fun cancelNotification(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "吃药提醒", NotificationManager.IMPORTANCE_HIGH
        )
        ch.description = "每天到点提醒吃药，可点通知上的「已吃药」记录"
        ch.enableVibration(true)
        ch.setShowBadge(true)
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

    /** 「关于」页状态行：一眼看出提醒到底挂上没有、卡在哪一环。 */
    fun statusText(ctx: Context): String {
        val on = isEnabled(ctx)
        if (!on) return "提醒已关闭 · 已记录 ${takenCount(ctx)} 天"
        val today = if (isTaken(ctx)) "已吃药 ✓" else "未吃药"
        val tail = if (nextIsCustom(ctx)) "（已手动改）" else ""
        return "今日：$today · 已记录 ${takenCount(ctx)} 天\n下次提醒 ${nextTriggerText(ctx)}$tail"
    }
}
