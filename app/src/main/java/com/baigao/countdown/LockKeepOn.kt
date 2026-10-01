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
 * 「锁屏通知常亮」开关对应的屏幕常亮能力。
 *
 * 规则（与「UI 界面常亮」「悬浮框常亮」完全同一套）：
 * - 开关打开：**只要锁屏倒计时的通知还挂在通知栏 / 锁屏上**，屏幕就一直亮着、不睡；
 * - 开关关闭：立刻撤掉常亮，熄屏时间交还给手机系统设置（手机设置里定的多久息屏就多久息屏）。
 *
 * 实现要点：
 * - 靠「看不见的 1dp 透明小窗 + FLAG_KEEP_SCREEN_ON」把屏幕按在亮着的状态；
 *   小窗贴在屏幕右下角、透明、不抢焦点不拦触摸，所以在锁屏界面上也点不到它、看不到它。
 * - 小窗只在**屏幕亮着**的时候挂着，一息屏就撤掉 —— 不然在兜兜里 / 锁屏上白耗电。
 * - 没给「显示在其他应用上层」权限时（锁屏通知本身不需要这个权限）退一步用
 *   「点亮屏幕」的唤醒锁兜底，至少屏幕亮着的时候不会被系统掐灭。
 * - 由倒计时服务每秒调一次 [apply]：所以按电源键重新点亮屏幕的那一刻能马上接上，
 *   息屏后也不会留下一条把屏幕永久按住的幽灵小窗。
 */
object LockKeepOn {

    private const val PREF = "app_settings"
    /** 锁屏通知常亮开关（默认关：避免装好就一直亮屏耗电，想用再开）。 */
    private const val KEY_ON = "lockscreen_keep_on"
    private const val TAG = "LockKeepOn"

    /** 常亮小窗（一个什么都不画的 1dp 透明角落）。 */
    @Volatile
    private var keepView: View? = null
    /** 小窗已经挂上去（挂窗失败时用唤醒锁兜底，两个都算「已按住」）。 */
    @Volatile
    private var holding = false
    /** 挂不上去时（锁屏 / 权限不够）的下次重试时刻，免得每秒狂刷日志。 */
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
     * 条件：开关开着 **且** 锁屏通知开关开着 **且** 通知栏里确实有锁屏倒计时 **且** 屏幕亮着。
     * 由 CountdownService 每秒调用；开关在「关于」页切换时也会立刻调一次。
     */
    fun apply(ctx: Context) {
        val want = isOn(ctx) && LockScreenClock.isOn(ctx) && LockScreenClock.activeCount() > 0
        if (!want || !isScreenOn(ctx)) {
            detach(ctx)
            return
        }
        if (holding) return
        val now = System.currentTimeMillis()
        if (now < retryAt) return
        // 优先用看不见的小窗（最稳）；没有悬浮窗权限时退到唤醒锁
        if (canOverlay(ctx) && tryAddWindow(ctx)) {
            holding = true
            return
        }
        acquireWakeLock(ctx)
        holding = true
        retryAt = now + 5_000
    }

    /** 息屏 / 关开关 / 服务停掉时调用：把「按着屏幕」的效果全部撤掉。 */
    fun detach(ctx: Context) {
        holding = false
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

    /** 屏幕现在是亮着的吗（息屏时为 false）。 */
    private fun isScreenOn(ctx: Context): Boolean =
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Throwable) {
            true
        }

    private fun canOverlay(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(ctx) else true

    /** 挂一个 1dp 的透明小窗到右下角，只为了挂上 FLAG_KEEP_SCREEN_ON。 */
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

    /** 兜底：拿一个「会把屏幕点亮」的唤醒锁（没悬浮窗权限时用）。 */
    private fun acquireWakeLock(ctx: Context) = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG
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
