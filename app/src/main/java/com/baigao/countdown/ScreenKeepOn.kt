package com.baigao.countdown

import android.app.Activity
import android.content.Context
import android.view.WindowManager

/**
 * 屏幕常亮开关：开启后，本应用在前台时屏幕保持常亮、不锁屏；
 * 关闭后恢复手机默认的熄屏时间。
 *
 * 用 WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON 实现，
 * 无需任何额外权限（不新增 dangerous permission / manifest 声明）。
 * 该 Flag 只在对应 Activity 的窗口处于前台时生效，
 * 因此每个会停留显示的界面（主界面 / 关于页 / 吃药日历）都在 onResume 里 apply 一次。
 */
object ScreenKeepOn {
    private const val PREF = "app_settings"
    private const val KEY = "screen_keep_on"

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }

    /** 在 Activity.onResume（或开关切换时）调用，按当前设置决定窗口是否保持常亮。 */
    fun apply(activity: Activity) {
        if (isOn(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
