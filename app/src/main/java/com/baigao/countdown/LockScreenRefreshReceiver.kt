package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 锁屏倒计时的「唤醒刷新」兜底 + 屏幕状态信号。
 *
 * 前台服务虽然优先级高，但手机一锁屏、屏幕一灭，国产 ROM 的 Doze / 后台冻结
 * 照样会把进程掐住，服务里那个每秒的 tick 就停了 —— 表现就是锁屏通知**不再跳秒**。
 * 所以服务里另外挂了一个 AlarmManager 精确闹钟（见 CountdownService.scheduleLockAlarm），
 * 到点由本接收器把锁屏通知刷成最新。
 *
 * 另外这里还接三件事：
 * - `SCREEN_ON`：屏幕一点亮就赶紧把「锁屏通知常亮」的常亮页挂上（不然要等下一跳 / 下一次闹钟）；
 * - `SCREEN_OFF`：顺手核一次状态（常亮条件没了就把东西全撤掉，不白耗电）；
 * - `USER_PRESENT`：用户解锁了 —— 常亮页自己会收，这里再撤一次并解除「退避」标记。
 *
 * 除了刷锁屏通知，这里不重建悬浮窗、不读网络、不响铃，唤醒成本尽量小。
 */
class LockScreenRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // 开机 / 时区变化等杂散广播也走同一通道时，只认这几个 action
        if (action != null &&
            action != CountdownService.ACTION_LOCK_TICK &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_USER_PRESENT &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_SCREEN_ON &&
            action != Intent.ACTION_SCREEN_OFF
        ) return
        try {
            if (action == Intent.ACTION_USER_PRESENT) {
                // 解锁了就解除「用户刚碰过锁屏」的退避，下次锁屏继续接管常亮
                LockKeepOn.onUserPresent()
            }
            val list = CountdownStore.load(context)
            LockScreenClock.updateAll(context, list)
            // 「锁屏通知常亮」也要在这里接手：服务被杀后进程里持有的东西会被系统释放，
            // 靠这个 10 秒一次的兜底闹钟 / 屏幕广播重新按住屏幕（条件不满足自动撤）
            LockKeepOn.apply(context)
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
