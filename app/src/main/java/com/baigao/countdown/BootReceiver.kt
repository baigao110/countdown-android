package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自动恢复悬浮倒计时（前提是用户已授予悬浮窗权限）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
}
