package com.baigao.countdown

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 版本更新的系统通知（纯框架实现，零第三方依赖）。
 *
 * 检测到新版本时，除了在 App 内弹更新日志，还会在系统通知栏发一条通知：
 * - 点通知 → 打开主界面并弹出更新日志（可点「立即更新」下载安装）；
 * - 通知上的「立即更新」按钮 → 打开主界面后直接开始下载安装。
 *
 * 同一个版本每天最多提示一次，避免每次启动都弹通知打扰。
 */
object UpdateNotifier {

    /** 渠道 id 带版本后缀：旧渠道以普通优先级建过，重要性一旦建立就不能就地修改。 */
    const val CHANNEL_ID = "countdown_update_high"
    private const val OLD_CHANNEL_ID = "countdown_update"
    private const val NOTIF_ID = 20317
    private const val PREF = "update_notify"
    private const val KEY_VERSION = "version"
    private const val KEY_DATE = "date"

    /** 通知栏是否需要申请运行时权限（Android 13+）。 */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** 发出「发现新版本」通知；无权限或今天已经提示过该版本则跳过。 */
    fun notifyUpdate(context: Context, info: UpdateManager.UpdateInfo) {
        if (!hasPermission(context)) return
        if (!shouldNotify(context, info.name)) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(nm)
            nm.notify(NOTIF_ID, build(context, info))
            markNotified(context, info.name)
        } catch (e: Throwable) {
            android.util.Log.w("UpdateNotifier", "notifyUpdate: ${e.message}")
        }
    }

    /** 进入 App 后清掉更新通知，避免升级完了通知栏还挂着旧提示。 */
    fun cancel(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 旧渠道（IMPORTANCE_DEFAULT）不会弹横幅、容易被用户忽略，删掉重建为高优先级
        try {
            if (nm.getNotificationChannel(OLD_CHANNEL_ID) != null) {
                nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            }
        } catch (e: Throwable) {
            // 部分 ROM 不允许删除渠道，忽略即可
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "版本更新",
            NotificationManager.IMPORTANCE_HIGH
        )
        ch.description = "检测到新版本时提醒更新"
        ch.enableVibration(true)
        ch.setShowBadge(true)
        nm.createNotificationChannel(ch)
    }

    private fun build(context: Context, info: UpdateManager.UpdateInfo): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        // 点通知本体：进主界面弹更新日志
        val contentIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(context, 1, contentIntent, flags)

        // 通知上的「立即更新」：进主界面后直接下载安装
        val nowIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_UPDATE_NOW, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val nowPi = PendingIntent.getActivity(context, 2, nowIntent, flags)

        val note = info.note.trim()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("发现新版本 v${info.name}")
            .setContentText("当前 v${UpdateManager.CURRENT_VERSION_NAME}，点击查看更新日志")
            .setContentIntent(contentPi)
            .addAction(R.drawable.ic_stat, "立即更新", nowPi)
            .setAutoCancel(true)
            // 高优先级：有声音 + 横幅，息屏后台检查到更新时用户才真的能看到
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        if (note.isNotEmpty()) {
            builder.setStyle(Notification.BigTextStyle().bigText(note))
        }
        return builder.build()
    }

    // ---------------- 去重：同一版本每天最多一次 ----------------

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun shouldNotify(context: Context, version: String): Boolean {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return !(sp.getString(KEY_VERSION, "") == version &&
                sp.getString(KEY_DATE, "") == today())
    }

    private fun markNotified(context: Context, version: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERSION, version)
            .putString(KEY_DATE, today())
            .apply()
    }
}
