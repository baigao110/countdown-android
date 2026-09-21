package com.baigao.countdown

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 倒计时归零常驻通知上的「停止」按钮：点一下清掉那条通知。
 *
 * 归零通知是 ongoing（常驻、划不掉）的闹钟样式提醒，必须有个明确的出口让用户关掉它，
 * 否则它会一直挂在通知栏直到用户去设置里清。这里用广播接收器按通知 id 取消即可，
 * 不依赖前台服务是否还活着。
 */
class CountdownFinishReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CountdownService.ACTION_FINISH_DISMISS) return
        val id = intent.getStringExtra(CountdownService.EXTRA_FINISH_ID) ?: return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id.hashCode())
        } catch (e: Throwable) {
            // 忽略
        }
    }
}
