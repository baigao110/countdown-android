package com.baigao.countdown

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 「锁屏通知常亮」开关对应的屏幕常亮能力（v67 重做锁屏这一段）。
 *
 * 规则（与「UI 界面常亮」「悬浮框常亮」同一套）：
 * - 开关打开：**只要锁屏倒计时的通知还挂在通知栏 / 锁屏上**，屏幕就一直亮着、不睡，
 *   锁屏界面上也能一直瞄那个倒计时；
 * - 开关关闭：立刻撤掉常亮，熄屏时间交还给手机系统设置。
 *
 * v66 只靠「看不见的透明小窗 + FLAG_KEEP_SCREEN_ON」，而**锁屏状态下系统不允许
 * 第三方 App 挂悬浮窗**——小窗挂不上，退路的 PARTIAL 唤醒锁又只保 CPU 不保屏幕，
 * 这就是「锁屏界面上没保持常亮」的原因。v67 改成两手抓：
 *
 * 1) **屏幕级唤醒锁**（SCREEN_BRIGHT_WAKE_LOCK，仅需要已声明的 WAKE_LOCK 权限）：
 *    它是 PowerManager 级别的"按住屏幕"，不经过窗口层，**锁屏状态下照样有效**；
 *    再叠加 ACQUIRE_CAUSES_WAKEUP，接上的一瞬间顺便把屏幕点亮。
 *    条件满足期间**一直持有**（不看屏幕当前亮不亮）——这样屏幕压根走不到系统的
 *    熄屏倒计时；用户按电源键主动关掉仍然会关（电源键最大），再按一下点亮后就
 *    一直亮着不睡了。
 * 2) 透明 1dp 小窗（FLAG_KEEP_SCREEN_ON）：作为未锁屏时的补充保险，锁屏时挂不上
 *    就算了，不影响第 1 条。
 *
 * 服务每秒调 [apply]、兜底闹钟每 10s 调 [apply]：开关关掉 / 通知被收掉 / 服务停掉
 * 都会走到 [detach] 把唤醒锁和小窗全撤掉，不会留下按住屏幕的幽灵。
 */
object LockKeepOn {

    private const val PREF = "app_settings"
    /** 锁屏通知常亮开关（默认关：避免装好就一直亮屏耗电，想用再开）。 */
    private const val KEY_ON = "lockscreen_keep_on"
    private const val TAG = "LockKeepOn"

    /** 常亮小窗（一个什么都不画的 1dp 透明角落）。 */
    @Volatile
    private var keepView: View? = null
    /** 小窗挂上了没有（挂窗失败时只靠唤醒锁，也算「已按住」）。 */
    @Volatile
    private var windowOn = false
    /** 已按住屏幕（唤醒锁在手，或小窗挂上）。 */
    @Volatile
    private var holding = false
    /** 挂小窗失败的下次重试时刻（锁屏时系统不让挂，别每秒狂刷日志）。 */
    @Volatile
    private var retryAt = 0L
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    // ---------------- 开关 ----------------

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
        if (!on) detach(ctx)
    }

    // ---------------- 按住屏幕 ----------------

    /**
     * 按当前状态决定屏幕要不要保持亮着。
     * 条件：开关开着 **且** 锁屏通知开关开着 **且** 通知栏里确实有锁屏倒计时。
     * **不看屏幕当前亮不亮**——条件满足期间一直持有屏幕级唤醒锁，
     * 锁屏界面才会一直亮着（v66 的教训：只在屏幕亮着时接，锁屏后照样被系统熄掉）。
     */
    fun apply(ctx: Context) {
        val want = isOn(ctx) && LockScreenClock.isOn(ctx) && LockScreenClock.activeCount() > 0
        if (!want) {
            detach(ctx)
            return
        }
        if (!holding) {
            holding = true
            acquireWakeLock(ctx)
        }
        // 小窗只是未锁屏时的补充保险：锁屏挂不上就 5 秒后再试，失败也不影响唤醒锁
        if (!windowOn && canOverlay(ctx) && System.currentTimeMillis() >= retryAt) {
            windowOn = tryAddWindow(ctx)
            if (!windowOn) retryAt = System.currentTimeMillis() + 5_000
        }
    }

    /** 关开关 / 通知收掉 / 服务停掉时调用：把「按着屏幕」的效果全部撤掉。 */
    fun detach(ctx: Context) {
        holding = false
        windowOn = false
        retryAt = 0
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

    /** 当前是不是正把屏幕按在亮着（给「关于」页显示实时状态用）。 */
    fun isHolding(): Boolean = holding

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun canOverlay(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(ctx) else true

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
     * ACQUIRE_CAUSES_WAKEUP 让接上的一瞬间顺便把屏幕点亮。
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
