package com.baigao.countdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews

/**
 * 锁屏 / 通知栏倒计时。
 *
 * 开启「锁屏通知显示」后，凡是**在列表里「展开」过（isVisible）**的倒计时，
 * 都会在系统通知栏（锁屏界面上）各占一条**常驻通知**，每秒跟着跳秒，
 * 标题、倒计时数字、模式、备注、主题配色一律与悬浮窗 / 主界面里的一致 ——
 * 同一条数据、同一套渲染，只是把"悬浮在别的 App 上面"换成了"躺在锁屏通知栏里"。
 *
 * 实现要点：
 * - 折叠态（锁屏上真正看到的）用 contentTitle = 倒计时标题、contentText = 剩余时间文本，
 *   所以锁屏不用展开也能一眼读到还差多久；展开态用自定义玻璃卡片（与悬浮窗同款配色）。
 * - 通知是常驻（Ongoing）+ 只在首次 alert、之后静默刷新（OnlyAlertOnce），
 *   避免每秒响一下、也不填「显示时间」（ShowWhen）让秒数在锁屏上跳得干净。
 * - 渠道用 IMPORTANCE_LOW（静默但锁屏可见；IMPORTANCE_MIN 在锁屏上是隐藏的），
 *   锁屏可见度设为 VISIBILITY_PUBLIC，并标记闹钟类型。
 * - 只给 isVisible 的倒计时发通知：谁在悬浮窗里显示，锁屏通知里就有谁，两边永远一致。
 * - 被隐藏 / 删除的倒计时，通知会被取消，不会留一条"永远停在 0 秒"的僵尸通知。
 */
object LockScreenClock {

    private const val PREF = "app_settings"
    /** 锁屏通知总开关（默认开启：装好就能在锁屏上看到展开过的倒计时）。 */
    private const val KEY_ON = "lockscreen_notify"

    private const val CHANNEL_ID = "countdown_lockscreen_v63"
    private const val CHANNEL_NAME = "锁屏倒计时"
    private const val CHANNEL_DESC = "把悬浮窗里的倒计时以常驻通知显示在通知栏与锁屏上"
    /** 通知 id 基数：与其它通知（归零提醒 / 更新 / 吃药）错开，别互相顶掉。 */
    private const val ID_BASE = 5300

    /** 已经发出通知的倒计时 id → 通知 id（用于把被隐藏 / 删除的那一条收掉）。 */
    private val posted = HashMap<String, Int>()

    // ---------------- 开关 ----------------

    @Synchronized
    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    @Synchronized
    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
        if (!on) clear(ctx)
    }

    // ---------------- 刷新 / 收起 ----------------

    /**
     * 把「该显示的通知」刷成最新：只发 isVisible 的倒计时，其余（隐藏 / 删除 / 关开关）一律收掉。
     * 由悬浮窗服务每秒调用（与主界面悬浮窗同一时刻跳秒），也可在设置里点开关时立刻调一次。
     */
    @Synchronized
    fun updateAll(ctx: Context, list: List<Countdown>, now: Long = AlignedClock.now()) {
        if (!isOn(ctx)) {
            clear(ctx)
            return
        }
        val live = HashSet<String>()
        for (c in list) {
            if (!c.isVisible) continue
            live.add(c.id)
            notifyOne(ctx, c, c.remainingText(now), c.remarkText(TimeFormatPref.is24Hour(ctx)))
        }
        // 这一轮没出现的（被隐藏或删掉了）→ 把通知收掉
        val stale = posted.keys.filter { it !in live }
        for (id in stale) {
            posted.remove(id)?.let { safeCancel(ctx, it) }
        }
    }

    /** 全部收掉（关开关、服务停掉、App 退出时调用）。 */
    @Synchronized
    fun clear(ctx: Context) {
        val ids = ArrayList(posted.values)
        posted.clear()
        for (id in ids) safeCancel(ctx, id)
    }

    private fun notifyOne(ctx: Context, c: Countdown, text: String, remark: String) {
        try {
            val id = notifyIdOf(c)
            nm(ctx).notify(id, buildNotification(ctx, c, text, remark))
            posted[c.id] = id
        } catch (e: Throwable) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }

    private fun safeCancel(ctx: Context, id: Int) {
        try {
            nm(ctx).cancel(id)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel: ${e.message}")
        }
    }

    /** 每个倒计时的通知 id 由 id 稳定算出：同一条永远同一条通知，重启后仍能接着用。 */
    private fun notifyIdOf(c: Countdown): Int =
        ID_BASE + ((c.id.hashCode() and 0x7FFFFFFF) % 500)

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun buildNotification(ctx: Context, c: Countdown, text: String, remark: String): Notification {
        ensureChannel(ctx)
        val color = c.customColorArgb
        // 点通知直接回主界面
        val pi = PendingIntent.getActivity(
            ctx, notifyIdOf(c),
            Intent(ctx, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        // 展开态：与悬浮窗同一套玻璃卡（标题色 / 大号时间 / 模式 / 备注）
        val rv = RemoteViews(ctx.packageName, R.layout.notification_lock_clock)
        rv.setTextViewText(R.id.lockTitle, c.title)
        rv.setTextColor(R.id.lockTitle, color)
        rv.setTextViewText(R.id.lockTime, text)
        rv.setTextViewText(R.id.lockMode, CountdownFormatter.modeName(c.displayMode, c.builtIn) +
                " | " + AnimStyle.name(c.animStyle))
        rv.setTextViewText(
            R.id.lockRemark,
            if (remark.isBlank()) "" else "备注: " + remark.replace("\n", " ")
        )

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(color)
            // 折叠态（锁屏上看到的这两行）：标题 + 剩余时间
            .setContentTitle(c.title)
            .setContentText(text)
            // 展开态：自定义玻璃卡片
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomBigContentView(rv)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 锁屏通知专用渠道：静默（不会每秒响一声）但**锁屏可见**。
     * 注意 IMPORTANCE_MIN 在锁屏上是隐藏的，所以这里必须是最低可显示的 LOW。
     */
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = nm(ctx)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        )
        ch.description = CHANNEL_DESC
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private const val TAG = "LockScreenClock"
}
