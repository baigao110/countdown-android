package com.baigao.countdown

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

    /**
     * 渠道 id 带版本后缀：渠道重要性一旦由系统或用户定下来就**不能就地修改**，
     * 被降成「无声通知」或「关闭」后 notify() 会静默失败 —— 这在国产 ROM 上很常见。
     * 所以每次大改通知策略就换一个新 id，等于把被改坏的设置重置回最高级。
     */
    const val CHANNEL_ID = "countdown_update_v26"
    /** 历代旧渠道：一律清掉，免得系统里留着一堆同名不同级的历史包袱。 */
    private val OLD_CHANNEL_IDS = listOf("countdown_update", "countdown_update_high")
    private const val NOTIF_ID = 20317
    private const val TEST_ID = 20321
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
    fun notifyUpdate(
        context: Context,
        info: UpdateManager.UpdateInfo,
        /** 传 true 时跳过「同一版本每天一次」的去重：用户主动要看 / 弹窗被拦截时的补救。 */
        force: Boolean = false
    ) {
        if (!hasPermission(context)) {
            // 没权限时 notify() 压根不会被调用，这里留个记号，自检时能一眼看出
            android.util.Log.w("UpdateNotifier", "notifyUpdate: 没有通知权限，已跳过")
            return
        }
        if (!force && !shouldNotify(context, info.name)) return
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

    /**
     * 建渠道：最高级 + 免打扰也能响 + 锁屏可见。
     * 为什么要 MAX 而不是 HIGH：部分 ROM（华为/荣耀等）会把 HIGH 的通知收进「静默通知」，
     * 不响铃、不弹横幅、只在下拉栏折叠区里躺着，用户就会觉得「通知根本没来」。
     * 若渠道已被用户关掉（IMPORTANCE_NONE），先删再建 —— 否则只能等用户自己去设置里改。
     */
    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        for (old in OLD_CHANNEL_IDS) {
            try {
                if (nm.getNotificationChannel(old) != null) {
                    nm.deleteNotificationChannel(old)
                }
            } catch (e: Throwable) {
                // 部分 ROM 不允许删除渠道，忽略即可
            }
        }
        val exist = try {
            nm.getNotificationChannel(CHANNEL_ID)
        } catch (e: Throwable) {
            null
        }
        if (exist != null) {
            // 被关掉的话删掉重建，让重要性回到 MAX（否则通知会被静默丢弃）
            if (exist.importance == NotificationManager.IMPORTANCE_NONE) {
                try {
                    nm.deleteNotificationChannel(CHANNEL_ID)
                } catch (e: Throwable) {
                    return
                }
            } else {
                return
            }
        }
        val ch = NotificationChannel(
            CHANNEL_ID,
            "版本更新",
            NotificationManager.IMPORTANCE_MAX
        )
        ch.description = "检测到新版本时提醒更新"
        ch.enableVibration(true)
        ch.setShowBadge(true)
        ch.setBypassDnd(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try {
            ch.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        } catch (e: Throwable) {
            // 拿不到默认铃声就交给系统默认行为
        }
        nm.createNotificationChannel(ch)
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    /**
     * 通知渠道是否被用户在系统设置里关掉。关掉后 notify 会静默失败 —— 这类「收不到通知」
     * 在应用内看不出任何异常，只能主动查渠道重要性。
     */
    fun channelEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = nm.getNotificationChannel(CHANNEL_ID)
            ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
        } catch (e: Throwable) {
            true
        }
    }

    /** 「关于」页的「测试通知」：立刻发一条，用来验证权限与渠道到底通不通。 */
    fun sendTest(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(nm)
            if (!channelEnabled(context)) return false
            val pi = PendingIntent.getActivity(
                context, 3,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags()
            )
            nm.notify(
                TEST_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("通知测试成功")
                    .setContentText("有新版本时，更新提醒会出现在这里")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                    .build()
            )
            true
        } catch (e: Throwable) {
            android.util.Log.w("UpdateNotifier", "sendTest: ${e.message}")
            false
        }
    }

    /** 跳系统的「本应用通知设置」页，让用户可以自己把通知打开。 */
    fun openSettings(context: Context) {
        try {
            val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Throwable) {
            // 部分 ROM 没有这个页面，忽略即可
        }
    }

    private fun build(context: Context, info: UpdateManager.UpdateInfo): Notification {
        val flags = piFlags()

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
            .setPriority(Notification.PRIORITY_MAX)
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
