package com.baigao.countdown

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知体检（纯框架实现，零第三方依赖）。
 *
 * 为什么需要它：「退出 App 后收不到通知」在代码里是**完全没有异常**的失败 ——
 * 权限没给、渠道被关、后台被限制，三者的表现一模一样：什么都没发生。
 * 以前只能靠猜，现在把这道闸门拆成一项项可自查、可一键跳转修复的开关：
 * 吃药提醒与版本更新通知走的是同一条通路，所以这里一次性把两边都查了。
 */
object NotifyGuard {

    /** 一项体检结果。fix 为空表示这一项没法在应用内修（只能由系统决定）。 */
    class Item(
        val ok: Boolean,
        val title: String,
        val detail: String,
        val fixLabel: String? = null,
        val fix: (() -> Unit)? = null
    )

    private const val PREF = "notify_guard"
    private const val KEY_DATE = "warn_date"
    private const val KEY_COUNT = "warn_count"
    private const val MAX_WARN = 3

    /** 逐项检查，顺序即「最容易出问题」的顺序。 */
    fun items(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ① 系统通知总开关：关掉后 notify() 照样执行，但系统一条都不显示
        val master = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nm.areNotificationsEnabled()
        } else true
        out.add(
            Item(
                master, "① 系统通知总开关",
                if (master) "已开启" else "已关闭 —— 所有通知都不会显示（吃药提醒、版本更新都在此列）",
                if (master) null else "去打开", { openNotificationSettings(ctx) }
            )
        )

        // ② Android 13+ 运行时权限：没授予时代码直接跳过发通知，连日志都没有
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val p = UpdateNotifier.hasPermission(ctx)
            out.add(
                Item(
                    p, "② 通知权限（Android 13+）",
                    if (p) "已授予" else "未授予 —— 发通知会被直接跳过，且不会有任何报错",
                    if (p) null else "去授予", { openNotificationSettings(ctx) }
                )
            )
        }

        // ③④ 通知渠道：重要性一旦由系统/用户定下来就不能就地改（Android 限制），
        //     被降为「无声」或「关闭」后，通知会被静默丢弃 —— 这是国产 ROM 上最常见的一击。
        out.add(channelItem(ctx, nm, MedicineReminder.CHANNEL_ID, "③ 吃药提醒渠道"))
        out.add(channelItem(ctx, nm, UpdateNotifier.CHANNEL_ID, "④ 版本更新渠道"))

        // ⑤ 精确闹钟：拿不到也不致命（有系统闹钟 + 重复闹钟 + 巡检兜底），但到点会偏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val can = am.canScheduleExactAlarms()
            out.add(
                Item(
                    can, "⑤ 精确闹钟",
                    if (can) "已允许（到点准时）"
                    else "未允许 —— 已自动改用系统闹钟 + 每日重复闹钟 + 后台巡检兜底，仍能提醒",
                    if (can) null else "去打开", { openExactAlarmSettings(ctx) }
                )
            )
        }

        // ⑥ 电池优化：不关的话，后台可能被系统冻结，闹钟与巡检根本跑不起来
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } else true
        out.add(
            Item(
                ignoring, "⑥ 电池优化",
                if (ignoring) "已关闭（后台不被掐断）"
                else "未关闭 —— 后台可能被系统冻结，到点就没人去发通知",
                if (ignoring) null else "去关闭", { openBatterySettings(ctx) }
            )
        )

        // ⑦ 后台活动限制（Android 9+）：被限制后本应用几乎不能在后台做任何事
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am2 = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val r = am2.isBackgroundRestricted()
            out.add(
                Item(
                    !r, "⑦ 后台活动限制",
                    if (r) "被限制 —— 后台几乎跑不起来，通知自然发不出" else "未限制",
                    if (r) "去设置" else null, { openAppDetails(ctx) }
                )
            )
        }
        return out
    }

    /** 给文本型自检用的一行行描述。 */
    fun lines(ctx: Context): List<String> =
        items(ctx).map { "${if (it.ok) "✔" else "✖"} ${it.title}：${it.detail}" }

    /** 是否全部通过。 */
    fun allOk(ctx: Context): Boolean = items(ctx).all { it.ok }

    private fun channelItem(
        ctx: Context, nm: NotificationManager, id: String, title: String
    ): Item {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Item(true, title, "（本系统版本无通知渠道概念）")
        }
        val ch = try {
            nm.getNotificationChannel(id)
        } catch (e: Throwable) {
            null
        }
        return when {
            ch == null -> Item(true, title, "尚未创建，首次发通知时会自动建立")
            ch.importance == NotificationManager.IMPORTANCE_NONE ->
                Item(
                    false, title,
                    "已被关闭 —— 通知会被系统静默丢弃，请在系统设置里重新打开",
                    "去打开", { openChannelSettings(ctx, id) }
                )
            ch.importance < NotificationManager.IMPORTANCE_HIGH ->
                Item(
                    false, title,
                    "级别偏低（${impName(ch.importance)}）—— 可能被收进无声通知，不响铃、不出横幅",
                    "去调高", { openChannelSettings(ctx, id) }
                )
            else -> Item(true, title, "正常（${impName(ch.importance)}，会响铃并弹横幅）")
        }
    }

    private fun impName(v: Int): String = when (v) {
        NotificationManager.IMPORTANCE_MAX -> "紧急"
        NotificationManager.IMPORTANCE_HIGH -> "高"
        NotificationManager.IMPORTANCE_DEFAULT -> "中"
        NotificationManager.IMPORTANCE_LOW -> "低"
        NotificationManager.IMPORTANCE_MIN -> "最低"
        NotificationManager.IMPORTANCE_NONE -> "关闭"
        else -> "未知($v)"
    }

    // ---------------- 进应用时的自动提醒 ----------------

    /**
     * 打开 App 时若发现「硬阻断」（总开关 / 权限 / 渠道任一被关），弹一次体检报告。
     * 限频：每天最多一次、累计最多 [MAX_WARN] 次 —— 收不到通知的用户需要被提醒，
     * 但天天弹就成了骚扰。
     */
    fun warnIfBlocked(activity: Activity) {
        try {
            val sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (sp.getInt(KEY_COUNT, 0) >= MAX_WARN) return
            if (sp.getString(KEY_DATE, "") == today()) return
            val list = items(activity)
            // 只有「一定会让通知发不出」的项才自动弹；精确闹钟/电池优化属于「可能延迟」
            val hard = list.firstOrNull {
                !it.ok && (it.title.startsWith("①") || it.title.startsWith("②") ||
                        it.title.startsWith("③") || it.title.startsWith("④"))
            } ?: return
            sp.edit()
                .putString(KEY_DATE, today())
                .putInt(KEY_COUNT, sp.getInt(KEY_COUNT, 0) + 1)
                .apply()
            showReport(activity, list)
        } catch (e: Throwable) {
            android.util.Log.w("NotifyGuard", "warnIfBlocked: ${e.message}")
        }
    }

    /** 弹一份体检报告：每项一行，不合格的项下面带一个直达按钮。 */
    fun showReport(activity: Activity, list: List<Item> = items(activity)) {
        if (activity.isFinishing) return
        val dm = activity.resources.displayMetrics
        UpdateManager.showStyledDialog(
            activity = activity,
            title = "通知体检",
            positiveText = "去修第一项",
            negativeText = "关闭",
            cancelable = true,
            onPositive = { firstFix(activity, list) }
        ) { host ->
            for (one in list) {
                val row = LinearLayout(activity)
                row.orientation = LinearLayout.VERTICAL
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dm.density).toInt() }

                val tv = TextView(activity)
                tv.text = "${if (one.ok) "✔" else "✖"} ${one.title}\n    ${one.detail}"
                tv.setTextColor(
                    android.graphics.Color.parseColor(
                        if (one.ok) "#FFB9F6D0" else "#FFFFB4A8"
                    )
                )
                tv.textSize = 13f
                tv.setLineSpacing(3f, 1.15f)
                tv.setShadowLayer(2f, 0f, 1f, android.graphics.Color.parseColor("#CC000000"))
                row.addView(tv)

                val fix = one.fix
                if (!one.ok && fix != null) {
                    val btn = Button(activity)
                    btn.text = one.fixLabel ?: "去设置"
                    btn.textSize = 12f
                    btn.isAllCaps = false
                    btn.setTextColor(android.graphics.Color.parseColor("#FF001018"))
                    btn.setBackgroundResource(R.drawable.circle_btn)
                    btn.setPadding(
                        (14 * dm.density).toInt(), 0, (14 * dm.density).toInt(), 0
                    )
                    btn.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, (34 * dm.density).toInt()
                    ).apply { topMargin = (4 * dm.density).toInt() }
                    btn.setOnClickListener { fix.invoke() }
                    row.addView(btn)
                }
                host.addView(row)
            }
        }
    }

    private fun firstFix(ctx: Context, list: List<Item>) {
        val f = list.firstOrNull { !it.ok && it.fix != null }?.fix
        if (f != null) f.invoke() else openNotificationSettings(ctx)
    }

    // ---------------- 跳转 ----------------

    /** 系统「本应用的通知设置」页。 */
    fun openNotificationSettings(ctx: Context) {
        try {
            val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Throwable) {
            openAppDetails(ctx)
        }
    }

    /** 某个渠道的设置页（用户可以在这里把「无声通知」调回重要）。 */
    fun openChannelSettings(ctx: Context, channelId: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                openNotificationSettings(ctx)
            }
        } catch (e: Throwable) {
            openNotificationSettings(ctx)
        }
    }

    /** Android 12+ 精确闹钟授权页。 */
    fun openExactAlarmSettings(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                openAppDetails(ctx)
            }
        } catch (e: Throwable) {
            openAppDetails(ctx)
        }
    }

    /** 关闭电池优化。 */
    fun openBatterySettings(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                openAppDetails(ctx)
            }
        } catch (e: Throwable) {
            openAppDetails(ctx)
        }
    }

    /** 本应用详情页（自启动 / 后台运行 / 权限都在里面）。 */
    fun openAppDetails(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            android.util.Log.w("NotifyGuard", "openAppDetails: ${e.message}")
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}
