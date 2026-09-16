package com.baigao.countdown

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 后台定期检查更新的接收器（纯框架 AlarmManager 实现，零第三方依赖）。
 *
 * 之前的问题：只有打开 App（MainActivity / AboutActivity 启动那一刻）才会去拉远端版本，
 * 手机放着几天不打开就永远不知道有新版本 —— 自然也收不到任何更新通知。
 *
 * 现在的做法：由 AlarmManager 每 INTERVAL 唤起一次本接收器，在后台拉取远程版本，
 * 发现新版本就直接发一条系统通知（**不弹任何界面**，后台唤起 Activity 会被系统拦截）。
 * 每次触发顺便排下一次，开机 / 覆盖安装后由 BootReceiver 重新挂上。
 */
class UpdateCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 先排下一次：即便本次网络失败，下一轮也还会继续检查
        schedule(context, INTERVAL)

        val result = goAsync()
        Thread {
            try {
                val info = UpdateManager.fetch()
                if (UpdateManager.hasNewVersion(info)) {
                    UpdateNotifier.notifyUpdate(context, info!!)
                }
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "check: ${e.message}")
            } finally {
                result.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "UpdateCheckReceiver"
        private const val REQ = 20318
        const val ACTION = "com.baigao.countdown.CHECK_UPDATE"
        /** 后台检查间隔：6 小时。 */
        const val INTERVAL = 6 * 60 * 60 * 1000L
        /** 首次排程时留一点缓冲（刚装 / 刚开机网络可能还没就绪）。 */
        const val FIRST_DELAY = 10 * 60 * 1000L

        private fun pendingIntent(context: Context): PendingIntent {
            val i = Intent(context, UpdateCheckReceiver::class.java)
                .setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, REQ, i, flags)
        }

        /**
         * 排一次后台检查。同名 PendingIntent 会覆盖旧闹钟，重复调用不会产生多个闹钟。
         * Android 6+ 用 setAndAllowWhileIdle，息屏后也能被唤起（不必 WAKE_LOCK）。
         */
        fun schedule(context: Context, delayMillis: Long = FIRST_DELAY) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val at = SystemClock.elapsedRealtime() + delayMillis
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
                }
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "schedule: ${e.message}")
            }
        }
    }
}
