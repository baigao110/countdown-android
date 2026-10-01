package com.baigao.countdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.media.AudioAttributes
import android.view.WindowManager
import java.util.ArrayList

/**
 * 悬浮倒计时前台服务：
 * - 以 TYPE_APPLICATION_OVERLAY 在屏幕最上层显示动态倒计时；
 * - 每秒刷新文本，归零时播放自定义/默认提示音并发送通知；
 * - 在后台（应用退到后台甚至被杀前）依靠前台服务保活。
 */
class CountdownService : Service() {

    private lateinit var wm: WindowManager
    private val floaters = HashMap<String, FloatingView>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelLockAlarm()
                LockScreenClock.clear(this)
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                rebuildFloaters()
                refreshLockScreenNow()
                scheduleLockAlarm()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                rebuildFloaters()
                // 锁屏通知立刻出一条：不等下一跳，开机 / 解锁后马上就能在锁屏上看到
                refreshLockScreenNow()
                scheduleLockAlarm()
                if (!running) {
                    running = true
                    tick()
                }
            }
        }
        return START_STICKY
    }

    private fun tick() {
        // 对齐整秒刷新：多个悬浮窗与列表在同一瞬间跳秒（见 millisToNextSecond 说明）
        handler.postDelayed({
            try {
                val list = CountdownStore.load(this)
                val now = AlignedClock.now() // 本次 tick 统一时刻（已对齐整秒），多个悬浮窗同步走秒
                var changed = false
                for (c in list) {
                    // 内置倒计时（当日 / 当月）跨天、跨月后自动进入下一周期，并复位响铃状态
                    if (c.refreshBuiltInTarget()) {
                        c.finished = false
                        changed = true
                    }
                    val f = floaters[c.id]
                    if (c.isVisible) {
                        if (f == null && canDrawOverlay()) {
                            addFloater(c)
                        } else if (f != null) {
                            f.update(now)
                            if (c.targetTime <= now && !c.finished) {
                                c.finished = true
                                changed = true
                                SoundPlayer.play(this, c.soundUri)
                                notifyFinished(c)
                            }
                        }
                    } else {
                        if (f != null) removeFloater(c.id)
                    }
                }
                if (changed) CountdownStore.save(this, list)
                // 锁屏 / 通知栏倒计时：只给「已展开」的倒计时发常驻通知，
                // 与主界面悬浮窗同一时刻跳秒（now 已经是上面统一取好的那个）。
                LockScreenClock.updateAll(this, list, now)
                // 每次跳秒顺带把「唤醒刷新」闹钟往后推一格：
                // 前台服务被 Doze / 后台冻结掐住时，就靠这个闹钟接上，锁屏通知不会停住不动。
                scheduleLockAlarm()
            } catch (e: Exception) {
                Log.w(TAG, "tick error: ${e.message}")
            }
            tick()
        }, millisToNextSecond())
    }

    /**
     * 立刻把锁屏 / 通知栏倒计时刷成最新（不用等下一跳）。
     * 服务刚起来时先发一条，用户锁屏那一瞬间就能看到，而不是"等一秒才冒出来"。
     */
    private fun refreshLockScreenNow() {
        try {
            LockScreenClock.updateAll(this, CountdownStore.load(this))
        } catch (e: Throwable) {
            Log.w(TAG, "refreshLockScreenNow: ${e.message}")
        }
    }

    // ---------------- 锁屏通知的「唤醒刷新」兜底闹钟 ----------------

    /**
     * 挂下一次唤醒刷新：到点由 [LockScreenRefreshReceiver] 把锁屏通知刷成最新。
     * 用 setExactAndAllowWhileIdle —— 息屏 / Doze 状态下也能把 App 唤起（只是会被系统
     * 推迟到维护窗口），这正是「锁屏后倒计时不再跳秒」的解药。
     */
    private fun scheduleLockAlarm() {
        if (!LockScreenClock.isOn(this)) return
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val at = System.currentTimeMillis() + LOCK_ALARM_INTERVAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, lockAlarmPi())
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, at, lockAlarmPi())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "scheduleLockAlarm: ${e.message}")
        }
    }

    private fun cancelLockAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.cancel(lockAlarmPi())
        } catch (e: Throwable) {
            Log.w(TAG, "cancelLockAlarm: ${e.message}")
        }
    }

    private fun lockAlarmPi(): PendingIntent =
        PendingIntent.getBroadcast(
            this, LOCK_ALARM_REQ,
            Intent(this, LockScreenRefreshReceiver::class.java)
                .setAction(ACTION_LOCK_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or piFlags()
        )

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true

    /**
     * 以磁盘数据为准，增量同步所有悬浮窗：
     * - 新增的可见倒计时 → 添加窗体；
     * - 已存在的 → 用最新数据刷新（保留抽屉状态与位置，不会闪烁/复位）；
     * - 被隐藏或已删除的 → 移除窗体。
     */
    private fun rebuildFloaters() {
        if (!canDrawOverlay()) return
        val list = CountdownStore.load(this)
        val liveIds = HashSet<String>()
        for (c in list) {
            liveIds.add(c.id)
            val f = floaters[c.id]
            if (c.isVisible) {
                if (f == null) addFloater(c) else f.syncFrom(c)
            } else {
                if (f != null) removeFloater(c.id)
            }
        }
        val ids = ArrayList(floaters.keys)
        for (id in ids) if (!liveIds.contains(id)) removeFloater(id)
    }

    private fun addFloater(c: Countdown) {
        if (floaters.containsKey(c.id)) return
        val f = FloatingView(
            this, c, wm,
            onModeChange = { cd, mode ->
                try {
                    val list = CountdownStore.load(this)
                    list.find { it.id == cd.id }?.let { it.displayMode = mode }
                    CountdownStore.save(this, list)
                    floaters[cd.id]?.setMode(mode)
                    // 悬浮窗切模式后广播，让主界面列表实时同步（显示模式/剩余时间文本一致）
                    sendDataChanged()
                } catch (e: Throwable) {
                    Log.w(TAG, "onModeChange: ${e.message}")
                }
            },
            onEdit = { cd ->
                try {
                    val i = Intent(this, AddEditActivity::class.java)
                    i.putExtra("id", cd.id)
                    i.putExtra("fromFloating", true)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Throwable) {
                    Log.w(TAG, "onEdit: ${e.message}")
                }
            },
            onClose = { cd ->
                try {
                    val f = floaters[cd.id]
                    val pos = f?.getPosition() ?: (0 to 0)
                    val list = CountdownStore.load(this)
                    list.find { it.id == cd.id }?.let {
                        it.isVisible = false
                        it.posX = pos.first
                        it.posY = pos.second
                    }
                    CountdownStore.save(this, list)
                    f?.remove()
                    floaters.remove(cd.id)
                    sendDataChanged() // 通知主界面刷新（隐藏按钮 → 显示）
                } catch (e: Throwable) {
                    Log.w(TAG, "onClose: ${e.message}")
                }
            },
            onOpacityChange = { cd, v ->
                try {
                    val list = CountdownStore.load(this)
                    list.find { it.id == cd.id }?.let { it.opacity = v.coerceIn(20, 100) }
                    CountdownStore.save(this, list)
                } catch (e: Throwable) {
                    Log.w(TAG, "onOpacityChange: ${e.message}")
                }
            },
            onCollapseChange = { cd, col ->
                try {
                    val list = CountdownStore.load(this)
                    list.find { it.id == cd.id }?.let { it.collapsed = col }
                    CountdownStore.save(this, list)
                } catch (e: Throwable) {
                    Log.w(TAG, "onCollapseChange: ${e.message}")
                }
            },
            onPositionChange = { cd, x, y ->
                try {
                    val list = CountdownStore.load(this)
                    list.find { it.id == cd.id }?.let { it.posX = x; it.posY = y }
                    CountdownStore.save(this, list)
                } catch (e: Throwable) {
                    Log.w(TAG, "onPositionChange: ${e.message}")
                }
            }
        )
        f.show()
        f.update()
        floaters[c.id] = f
    }

    private fun removeFloater(id: String) {
        floaters.remove(id)?.remove()
    }

    private fun buildNotification(): Notification {
        createChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            )
            ch.description = getString(R.string.channel_desc)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    /** 倒计时归零：以系统闹钟 / 计时器的方式常驻提醒，直到手动「停止」。 */
    private fun notifyFinished(c: Countdown) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createFinishChannel(nm)
        val contentPi = PendingIntent.getActivity(
            this, 5,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags()
        )
        val dismissPi = PendingIntent.getBroadcast(
            this, 6,
            Intent(this, CountdownFinishReceiver::class.java)
                .setAction(ACTION_FINISH_DISMISS).putExtra(EXTRA_FINISH_ID, c.id),
            piFlags()
        )
        val n = Notification.Builder(this, CHANNEL_FINISH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("倒计时结束 · ${c.title}")
            .setContentText("「${c.title}」已到达目标时间")
            .setContentIntent(contentPi)
            .addAction(R.drawable.ic_stat, "停止", dismissPi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .build()
        // 归零提醒单独占 7000 起的一段 id：避开锁屏通知使用的 5300 起那段，
        // 免得两条通知互相顶掉（同一条倒计时下，一个 id 只能体现最后发的那条）。
        nm.notify(FINISH_ID_BASE + (c.id.hashCode() and 0x7FFFFFFF) % 800, n)
    }

    /** 归零提醒专用渠道：最高级 + 免打扰也响 + 锁屏可见 + 闹钟铃声。 */
    private fun createFinishChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_FINISH) != null) return
        val ch = NotificationChannel(
            CHANNEL_FINISH, "倒计时结束", NotificationManager.IMPORTANCE_MAX
        )
        ch.description = "倒计时归零时以闹钟方式常驻提醒"
        ch.enableVibration(true)
        ch.setShowBadge(true)
        ch.setBypassDnd(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try {
            ch.setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        } catch (e: Throwable) {
            // 拿不到闹钟铃声就交给系统默认
        }
        nm.createNotificationChannel(ch)
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    override fun onDestroy() {
        running = false
        // 服务停了就顺手收掉锁屏通知：否则通知栏会留一条不再走秒的倒计时
        if (LockScreenClock.isOn(this)) {
            // 锁屏通知还开着 → 保留唤醒闹钟（服务被杀后依然有人刷），只是这里不能刷前台服务
            rescheduleLockAlarm(this)
        } else {
            cancelLockAlarm()
        }
        LockScreenClock.clear(this)
        handler.removeCallbacksAndMessages(null)
        val ids = ArrayList(floaters.keys)
        for (id in ids) removeFloater(id)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.baigao.countdown.START"
        const val ACTION_STOP = "com.baigao.countdown.STOP"
        const val ACTION_REFRESH = "com.baigao.countdown.REFRESH"
        const val ACTION_DATA_CHANGED = "com.baigao.countdown.DATA_CHANGED"
        private const val CHANNEL_ID = "countdown_channel"
        private const val CHANNEL_FINISH = "countdown_finish_v30"
        private const val NOTIF_ID = 1001
        /** 归零提醒的通知 id 基数（与锁屏通知 5300 起那段错开，避免互相顶掉）。 */
        private const val FINISH_ID_BASE = 7000
        internal const val ACTION_FINISH_DISMISS = "com.baigao.countdown.FINISH_DISMISS"
        internal const val EXTRA_FINISH_ID = "finish_id"
        /** 锁屏通知「唤醒刷新」闹钟的 action（与开机 / 解锁等广播区分开）。 */
        internal const val ACTION_LOCK_TICK = "com.baigao.countdown.LOCK_TICK"
        /** 兜底闹钟的请求码与间隔：10 秒一次（屏幕亮着时跟得上跳秒，息屏时会被系统合并）。 */
        private const val LOCK_ALARM_REQ = 4231
        private const val LOCK_ALARM_INTERVAL = 10_000L
        internal const val TAG = "CountdownService"

        /**
         * 在应用进程之外挂起「锁屏通知唤醒刷新」闹钟（静态，服务被杀后仍有效）。
         * 这里不用 Toast 也与界面无关，直接构造一次性的唤醒闹钟即可。
         */
        fun rescheduleLockAlarm(ctx: android.content.Context) {
            try {
                if (!LockScreenClock.isOn(ctx)) return
                // 只挂一个：广播由 LockScreenRefreshReceiver 续上下一次，避免重复条目
                val pi = PendingIntent.getBroadcast(
                    ctx, LOCK_ALARM_REQ,
                    android.content.Intent(ctx, LockScreenRefreshReceiver::class.java)
                        .setAction(ACTION_LOCK_TICK),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                                PendingIntent.FLAG_IMMUTABLE else 0)
                )
                val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE)
                        as android.app.AlarmManager
                val at = System.currentTimeMillis() + LOCK_ALARM_INTERVAL
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "rescheduleLockAlarm: ${e.message}")
            }
        }
    }

    /** 通知主界面数据已变化（如悬浮窗内隐藏/显示），触发列表刷新。 */
    private fun sendDataChanged() {
        try {
            sendBroadcast(Intent(ACTION_DATA_CHANGED))
        } catch (e: Throwable) {
            Log.w(TAG, "sendDataChanged: ${e.message}")
        }
    }
}
