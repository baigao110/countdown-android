package com.baigao.countdown

import android.app.Activity
import android.content.Context
import android.view.WindowManager

/**
 * 屏幕常亮设置（拆成两个独立开关）：
 *
 * 1) UI 界面常亮 `ui`：开启后，本应用任意界面处于前台时屏幕保持常亮、不锁屏；
 *    关闭后恢复手机默认的熄屏时间。（功能与旧版「屏幕常亮」完全一致。）
 *
 * 2) 悬浮框常亮 `float`：开启后，当倒计时悬浮窗处于**展开**状态时，屏幕保持常亮；
 *    关闭（或悬浮窗处于收缩小条状态）时不做任何干预，屏幕时间完全跟随手机系统设置
 *    （即按系统「休眠 / 自动锁屏」时间正常熄屏）。
 *
 * 两者都用 `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` 实现，无需任何额外权限。
 * 该 Flag 只在对应窗口处于前台 / 可见时生效：UI 开关由各 Activity 在 onResume/onPause 里
 * 应用与清除；悬浮框开关由 FloatingView 按「是否展开」动态挂到悬浮窗的 LayoutParams 上。
 */
object ScreenKeepOn {
    private const val PREF = "app_settings"
    /** UI 界面常亮：沿用旧键，老用户的原设置可无缝升级过来。 */
    private const val KEY_UI = "screen_keep_on"
    /** 悬浮框常亮（新增）。 */
    private const val KEY_FLOAT = "float_keep_on"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- UI 界面常亮 ----------------

    fun isUiOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UI, false)

    fun setUiOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UI, on).apply()
    }

    // ---------------- 悬浮框常亮 ----------------

    fun isFloatOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FLOAT, false)

    fun setFloatOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FLOAT, on).apply()
    }

    /**
     * 在 Activity.onResume（或 UI 开关切换时）调用：
     * 按「UI 界面常亮」设置决定当前窗口是否保持常亮。悬浮框开关与此处无关。
     */
    fun apply(activity: Activity) {
        if (isUiOn(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 在 Activity.onPause 调用：只要窗口离开前台就清掉常亮 Flag。
     * 这样即便开关状态有残留、或 Activity 之间切换，Flag 也不会滞留在某个已暂停的窗口上，
     * 避免「关掉屏幕常亮后手机仍不按默认时间熄屏」的问题。回到前台时 onResume 会按需重新加上。
     */
    fun onPause(activity: Activity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
