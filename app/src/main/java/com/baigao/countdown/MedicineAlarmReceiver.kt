package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 吃药提醒的闹钟接收器（纯框架实现）。
 *
 * 两个动作：
 * - ACTION_ALARM：到点了 —— 交给 MedicineReminder.onAlarm()：先把明天的闹钟排上（三路冗余），
 *   再按需发通知（同一天只会发一次，重复闹钟 / 巡检不会重复打扰）；
 * - ACTION_TAKEN：通知上的「已吃药」—— 记下今天并收起通知。
 */
class MedicineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MedicineReminder.ACTION_TAKEN -> {
                MedicineReminder.setTaken(context, true)
                MedicineReminder.postStatusNotification(context)
                try {
                    Toast.makeText(context, "已记录今天吃药", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    // 部分 ROM 不允许后台弹 Toast，忽略
                }
            }
            else -> MedicineReminder.onAlarm(context)
        }
    }
}
