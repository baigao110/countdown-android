package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机 / 应用更新后自动恢复悬浮倒计时（前提是用户已授予悬浮窗权限）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 每一步单独 try：某一步因系统限制失败（比如锁屏状态下读不到偏好设置），
        // 不能把后面几条恢复链一起带崩 —— 以前是「一环断、整链断」。
        // 纯系统 API 的两条（JobScheduler）在任何状态下都安全，先挂上。
        try {
            UpdateCheckJobService.schedule(context)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "job update: ${e.message}")
        }
        try {
            MedicineCheckJobService.schedule(context)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "job medicine: ${e.message}")
        }
        // 还没解锁（direct boot）：读写偏好设置会抛异常，直接到此为止，
        // 等解锁后的 ACTION_USER_PRESENT 再补做剩下的。
        if (!userUnlocked(context)) return
        try {
            UpdateCheckReceiver.schedule(context, UpdateCheckReceiver.FIRST_DELAY)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "alarm update: ${e.message}")
        }
        // 重启后把每日吃药提醒重新挂上（闹钟在关机后不会保留）
        try {
            MedicineReminder.schedule(context)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "medicine: ${e.message}")
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val list = CountdownStore.load(context)
            if (list.any { it.isVisible }) {
                val i = Intent(context, CountdownService::class.java)
                i.action = CountdownService.ACTION_START
                context.startForegroundService(i)
            }
        }
    }

    /** 用户是否已解锁（锁屏直启阶段读偏好设置会崩，必须避开）。 */
    private fun userUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return try {
            val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            um.isUserUnlocked
        } catch (e: Throwable) {
            true
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
