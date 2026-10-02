package com.baigao.countdown

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 「锁屏通知常亮」开关：只要锁屏倒计时通知还挂在通知栏 / 锁屏上，屏幕就一直亮着。
 *
 * 规则：
 * - 开关打开 → 屏幕一直亮着，锁屏界面上也能一直瞄那个倒计时；
 * - 开关关闭 → 立刻撤掉常亮，熄屏时间交还给手机系统设置。
 *
 * v66 / v67 为什么没生效（教训）：
 * 1) 官方文档写得很死：`FLAG_KEEP_SCREEN_ON` **只能在 Activity 里设**，服务 / 悬浮窗设了不算；
 *    而且「应用回到后台后系统会照常熄屏」——所以透明小窗那一路在后台等于白挂；
 * 2) Android 12+ 对后台应用持有的唤醒锁会强制释放 / 降权，屏幕级唤醒锁同样指望不上。
 *
 * 现在的主角是 [LockKeepActivity]：一个挂在锁屏之上、全透明、不抢焦点不拦触摸的全屏 Activity，
 * 带上 `FLAG_KEEP_SCREEN_ON` —— 系统闹钟能在锁屏上一直亮着，靠的就是这一手。
 *
 * 另外两路降级为保险（谁有用算谁）：
 * - 未锁屏时的透明小窗（FLAG_KEEP_SCREEN_ON）；
 * - 屏幕级唤醒锁（SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP）。
 *
 * 触发时机：服务每秒 tick、兜底闹钟每 10 秒、以及屏幕点亮 / 解锁广播（见 LockScreenRefreshReceiver）。
 */
object LockKeepOn {

    private const val PREF = "app_settings"
    /** 锁屏通知常亮开关（默认关：避免装好就一直亮屏耗电，想用再开）。 */
    private const val KEY_ON = "lockscreen_keep_on"
    private const val TAG = "LockKeepOn"

    /** 常亮小窗（未锁屏时的补充保险）。 */
    @Volatile
    private var keepView: View? = null
    @Volatile
    private var windowOn = false
    @Volatile
    private var retryAt = 0L
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    /** 屏幕级唤醒锁是否已到手（给界面显示状态用）。 */
    @Volatile
    private var holding = false
    /** 常亮页是不是正开着（由 [LockKeepActivity] 自己同步）。 */
    @Volatile
    private var activityOn = false
    /** 拉常亮页失败的下次重试时刻（后台启动被系统拦下时别每秒狂试）。 */
    @Volatile
    private var activityRetryAt = 0L
    /** 用户碰过锁屏（想解锁）→ 这一轮先别接管，等他解完锁 / 屏幕重新点亮再说。 */
    @Volatile
    private var backedOff = false

    // ---------------- 开关 ----------------

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
        if (on) backedOff = false else detach(ctx)
    }

    /** 该不该把屏幕按住：开关开 + 锁屏通知开 + 通知栏里确实还挂着锁屏倒计时。 */
    fun shouldHold(ctx: Context): Boolean =
        isOn(ctx) && LockScreenClock.isOn(ctx) && LockScreenClock.activeCount() > 0

    /** 系统现在是不是处在锁屏状态。 */
    fun isKeyguardShowing(ctx: Context): Boolean = try {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.isKeyguardLocked
    } catch (_: Throwable) {
        false
    }

    // ---------------- 按住屏幕 ----------------

    /**
     * 按当前状态决定屏幕要不要保持亮着。服务每秒调一次，兜底闹钟与屏幕广播也会调。
     * **不看屏幕当前亮不亮**：条件满足就一直持有，锁屏界面才不会到点熄掉。
     */
    fun apply(ctx: Context) {
        if (!shouldHold(ctx)) {
            detach(ctx)
            return
        }
        if (!holding) {
            holding = true
            acquireWakeLock(ctx)
        }
        // 保险一：未锁屏时挂个透明小窗（锁屏时系统不让挂，挂不上就 5 秒后再试）
        if (!windowOn && canOverlay(ctx) && System.currentTimeMillis() >= retryAt) {
            windowOn = tryAddWindow(ctx)
            if (!windowOn) retryAt = System.currentTimeMillis() + 5_000
        }
        // 主角：锁屏界面上的透明常亮页（FLAG_KEEP_SCREEN_ON 只认 Activity）
        if (isKeyguardShowing(ctx) && isScreenOn(ctx) && !backedOff && !activityOn &&
            System.currentTimeMillis() >= activityRetryAt
        ) {
            ensureActivity(ctx)
        }
    }

    /** 关开关 / 通知收掉 / 服务停掉时调用：把「按着屏幕」的效果全部撤掉。 */
    fun detach(ctx: Context) {
        holding = false
        windowOn = false
        retryAt = 0
        activityRetryAt = 0
        backedOff = false
        releaseWakeLock()
        try {
            val v = keepView ?: return
            keepView = null
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (e: Throwable) {
            Log.w(TAG, "detach: ${e.message}")
        }
    }

    /** 当前常亮有没有真的接上（唤醒锁在手或常亮页开着，给界面显示状态用）。 */
    fun isHolding(): Boolean = holding || activityOn

    /** 常亮页是否正挂在锁屏上（界面状态里最想看到的那个「已接上」）。 */
    fun isActivityOn(): Boolean = activityOn

    // ---------------- 常亮页的生命周期回调 ----------------

    internal fun onActivityShown() {
        activityOn = true
        activityRetryAt = 0
    }

    internal fun onActivityGone() {
        activityOn = false
    }

    /** 用户碰了锁屏（要解锁 / 要拉通知栏）→ 退开一轮，别挡着人家。 */
    internal fun onUserTouched() {
        backedOff = true
    }

    /** 解锁了 / 屏幕重新点亮 → 解除退避，下次锁屏还能接着接管。 */
    internal fun onUserPresent() {
        backedOff = false
    }

    private fun ensureActivity(ctx: Context) {
        try {
            val i = Intent(ctx, LockKeepActivity::class.java)
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            ctx.startActivity(i)
            // 起没起来由 Activity 自己回报（onActivityShown）；没回报就 3 秒后再试
            activityRetryAt = System.currentTimeMillis() + 3_000
        } catch (e: Throwable) {
            Log.w(TAG, "keep activity: ${e.message}")
            activityRetryAt = System.currentTimeMillis() + 3_000
        }
    }

    private fun canOverlay(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(ctx) else true

    private fun isScreenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (_: Throwable) {
        true
    }

    /** 挂一个 1dp 的透明小窗到右下角，只为了挂上 FLAG_KEEP_SCREEN_ON（未锁屏时才挂得上）。 */
    private fun tryAddWindow(ctx: Context): Boolean = try {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = View(ctx)
        val lp = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            alpha = 0f
        }
        wm.addView(v, lp)
        keepView = v
        true
    } catch (e: Throwable) {
        Log.w(TAG, "attach: ${e.message}")
        false
    }

    /**
     * 屏幕级唤醒锁：把屏幕按在亮着的状态，锁屏界面也有效；
     * ACQUIRE_CAUSES_WAKEUP 让接上的一瞬间顺便把屏幕点亮（Android 12+ 对后台锁定有降权，算保险）。
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock(ctx: Context) = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG
        )
        wl.setReferenceCounted(false)
        wl.acquire()
        wakeLock = wl
    } catch (e: Throwable) {
        Log.w(TAG, "wakeLock: ${e.message}")
    }

    private fun releaseWakeLock() = try {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    } catch (_: Throwable) {
        // 撤不掉就算了，别卡住界面
    }
}
