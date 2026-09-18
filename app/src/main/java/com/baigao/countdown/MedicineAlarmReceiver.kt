package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 吃药提醒的闹钟接收器（纯框架实现）。
 *
 * 两个动作：
 * - ACTION_ALARM：到点了 —— 先把明天的闹钟排上，再发通知；
 * - ACTION_TAKEN：通知上的「已吃药」—— 记下今天并收起通知。
 */
class MedicineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MedicineReminder.ACTION_TAKEN -> {
                MedicineReminder.setTaken(context, true)
                MedicineReminder.cancelNotification(context)
                try {
                    Toast.makeText(context, "已记录今天吃药", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    // 部分 ROM 不允许后台弹 Toast，忽略
                }
            }
            else -> {
                // 先排下一次（哪怕后面发通知失败，明天的提醒也不会丢）
                MedicineReminder.schedule(context)
                if (MedicineReminder.isEnabled(context)) MedicineReminder.notifyNow(context)
            }
        }
    }
}
