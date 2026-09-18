package com.baigao.countdown

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

    private const val NOTIF_ID = 20331
    private const val REQ_ALARM = 4111
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

    // ---------------- 闹钟 ----------------

    /** 下一次响铃时刻：过了今天的设定时间就顺延到明天。 */
    fun nextTriggerMillis(ctx: Context): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(ctx))
            set(Calendar.MINUTE, minute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /**
     * 排下一次闹钟。只排「下一次」，响铃后由 [MedicineAlarmReceiver] 再排下一天 ——
     * 精确闹钟在国产 ROM 上比重复闹钟更容易被准确唤起。
     */
    fun schedule(ctx: Context) {
        if (!isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        val trigger = nextTriggerMillis(ctx)
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmPi(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (se: SecurityException) {
            // Android 14 起精确闹钟默认不授予：退化成每天重复的非精确闹钟，
            // 可能被系统延后几分钟，但不需要任何权限、一定能响。
            fallbackRepeating(ctx, trigger)
        } catch (e: Throwable) {
            fallbackRepeating(ctx, trigger)
        }
    }

    private fun fallbackRepeating(ctx: Context, trigger: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmPi(ctx)
            am.cancel(pi)
            am.setRepeating(AlarmManager.RTC_WAKEUP, trigger, AlarmManager.INTERVAL_DAY, pi)
        } catch (e: Throwable) {
            // 实在排不上就算了，下次打开 App 还会再排一次
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun alarmPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun takenPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_TAKEN,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_TAKEN),
        piFlags()
    )

    // ---------------- 通知 ----------------

    /** 到点发通知：点本体进吃药日历，点「已吃药」直接记录今天。 */
    fun notifyNow(ctx: Context) {
        if (!UpdateNotifier.hasPermission(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(nm)
            val openPi = PendingIntent.getActivity(
                ctx, REQ_OPEN,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags()
            )
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("该吃药了")
                .setContentText("点「已吃药」记录今天，也可进吃药日历查看往日记录")
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_stat, "已吃药", takenPi(ctx))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Throwable) {
            android.util.Log.w("MedicineReminder", "notifyNow: ${e.message}")
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
}
