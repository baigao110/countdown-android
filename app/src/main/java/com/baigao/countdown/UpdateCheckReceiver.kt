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
        val result = goAsync()
        Thread {
            var ok = true
            try {
                val info = UpdateManager.fetch()
                if (info == null) {
                    // 两次请求都没拿到（多半是 Doze / 后台断网），按失败处理，稍后重试
                    ok = false
                    UpdateCheckState.record(context, false, "")
                } else {
                    UpdateCheckState.record(context, true, info.name)
                    if (UpdateManager.hasNewVersion(info)) {
                        UpdateNotifier.notifyUpdate(context, info)
                    }
                }
            } catch (e: Throwable) {
                ok = false
                UpdateCheckState.record(context, false, "")
                android.util.Log.w(TAG, "check: ${e.message}")
            } finally {
                // 失败就 30 分钟后再试，成功则按常规间隔排下一次
                schedule(context, if (ok) INTERVAL else RETRY_INTERVAL)
                result.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "UpdateCheckReceiver"
        private const val REQ = 20318
        const val ACTION = "com.baigao.countdown.CHECK_UPDATE"
        /**
         * 后台检查间隔：20 分钟。
         * 原来是 2 小时，发新版后用户可能要等两小时才知道；现在和 JobScheduler
         * （系统硬限制最小 15 分钟）基本同频，有新版本基本能在半小时内通知到。
         */
        const val INTERVAL = 20 * 60 * 1000L
        /** 拉取失败后的重试间隔：10 分钟。 */
        const val RETRY_INTERVAL = 10 * 60 * 1000L
        /** 开机 / 解锁 / 覆盖安装后先很快查一次（网络没就绪会失败重试，不影响）。 */
        const val FIRST_DELAY = 60 * 1000L

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
