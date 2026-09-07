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
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                rebuildFloaters()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                rebuildFloaters()
                if (!running) {
                    running = true
                    tick()
                }
            }
        }
        return START_STICKY
    }

    private fun tick() {
        handler.postDelayed({
            try {
                val list = CountdownStore.load(this)
                var changed = false
                for (c in list) {
                    val f = floaters[c.id]
                    if (c.isVisible) {
                        if (f == null && canDrawOverlay()) {
                            addFloater(c)
                        } else if (f != null) {
                            f.update()
                            if (c.targetTime <= System.currentTimeMillis() && !c.finished) {
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
            } catch (e: Exception) {
                Log.w(TAG, "tick error: ${e.message}")
            }
            tick()
        }, 1000)
    }

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

    private fun notifyFinished(c: Countdown) {
        createChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 2, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("倒计时归零")
            .setContentText("「${c.title}」已到达目标时间")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(c.id.hashCode(), n)
    }

    override fun onDestroy() {
        running = false
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
        private const val NOTIF_ID = 1001
        private const val TAG = "CountdownService"
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
