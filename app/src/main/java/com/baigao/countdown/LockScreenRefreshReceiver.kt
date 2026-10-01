package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 锁屏倒计时的「唤醒刷新」兜底。
 *
 * 前台服务虽然优先级高，但手机一锁屏、屏幕一灭，国产 ROM 的 Doze / 后台冻结
 * 照样会把进程掐住，服务里那个每秒的 tick 就停了 —— 表现就是锁屏通知**不再跳秒**。
 * 所以服务里另外挂了一个 AlarmManager 精确闹钟（见 CountdownService.scheduleLockAlarm），
 * 到点由本接收器把锁屏通知刷成最新。
 *
 * 这里刻意只做「刷锁屏通知」这一件事：不重建悬浮窗、不读网络、不响铃，
 * 唤醒成本尽量小；Doze 深度休眠时会把闹钟推迟到下一个维护窗口（几分钟一次），
 * 插电 / 加了电池优化白名单时才能保持接近每秒的刷新。
 */
class LockScreenRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 开机 / 时区变化等杂散广播也走同一通道时，只认带这个 action 的
        if (intent.action != null &&
            intent.action != CountdownService.ACTION_LOCK_TICK &&
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_PRESENT &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        try {
            val list = CountdownStore.load(context)
            LockScreenClock.updateAll(context, list)
            // 顺手把下一次唤醒挂上（服务被杀时闹钟仍存在，这里续上即可）
            CountdownService.rescheduleLockAlarm(context)
        } catch (e: Throwable) {
            Log.w(TAG, "lock refresh: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "LockScreenRefresh"
    }
}
