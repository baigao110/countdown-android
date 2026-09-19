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

    /**
     * 一项体检结果。
     *
     * @param no   显示序号（按实际出现的顺序编排：不同系统版本项数不同，写死的序号会错位）。
     * @param hard true 表示这一项一旦不过，通知**一定**发不出来（用来决定是否自动弹体检报告）。
     * @param fix  为空表示这一项没法在应用内跳转修复。
     */
    class Item(
        val ok: Boolean,
        val no: Int,
        val title: String,
        val detail: String,
        val hard: Boolean = false,
        val fixLabel: String? = null,
        val fix: (() -> Unit)? = null
    )

    private const val PREF = "notify_guard"
    private const val KEY_DATE = "warn_date"
    private const val KEY_COUNT = "warn_count"
    private const val MAX_WARN = 3

    /** 逐项检查，顺序即「最容易出问题」的顺序；序号按实际项数动态编排。 */
    fun items(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        var n = 0
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 系统通知总开关：关掉后 notify() 照样执行，但系统一条都不显示
        n++
        val master = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nm.areNotificationsEnabled()
        } else true
        out.add(
            Item(
                master, n, "系统通知总开关",
                if (master) "已开启"
                else "已关闭 —— 所有通知都不会显示（吃药提醒、版本更新都在此列）",
                hard = true,
                fixLabel = if (master) null else "去打开",
                fix = if (master) null else ({ openNotificationSettings(ctx) })
            )
        )

        // Android 13+ 运行时权限：没授予时代码直接跳过发通知，连日志都没有
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            n++
            val p = UpdateNotifier.hasPermission(ctx)
            out.add(
                Item(
                    p, n, "通知权限（Android 13+）",
                    if (p) "已授予" else "未授予 —— 发通知会被直接跳过，且不会有任何报错",
                    hard = true,
                    fixLabel = if (p) null else "去授予",
                    fix = if (p) null else ({ openNotificationSettings(ctx) })
                )
            )
        }

        // 通知渠道：重要性一旦由系统/用户定下来就不能就地改（Android 限制），
        //     被降为「无声」或「关闭」后，通知会被静默丢弃 —— 这是国产 ROM 上最常见的一击。
        n++; out.add(channelItem(ctx, nm, n, MedicineReminder.CHANNEL_ID, "吃药提醒渠道", hard = true))
        n++; out.add(channelItem(ctx, nm, n, UpdateNotifier.CHANNEL_ID, "版本更新渠道", hard = true))

        // 精确闹钟：拿不到也不致命（有系统闹钟 + 重复闹钟 + 巡检兜底），但到点会偏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            n++
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val can = am.canScheduleExactAlarms()
            out.add(
                Item(
                    can, n, "精确闹钟",
                    if (can) "已允许（到点准时）"
                    else "未允许 —— 已自动改用系统闹钟 + 每日重复闹钟 + 后台巡检兜底，仍能提醒",
                    fixLabel = if (can) null else "去打开",
                    fix = if (can) null else ({ openExactAlarmSettings(ctx) })
                )
            )
        }

        // 电池优化：不关的话，后台可能被系统冻结，闹钟与巡检根本跑不起来
        n++
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } else true
        out.add(
            Item(
                ignoring, n, "电池优化",
                if (ignoring) "已关闭（后台不被掐断）"
                else "未关闭 —— 后台可能被系统冻结，到点就没人去发通知",
                fixLabel = if (ignoring) null else "去关闭",
                fix = if (ignoring) null else ({ openBatterySettings(ctx) })
            )
        )

        // 后台活动限制（Android 9+）：被限制后本应用几乎不能在后台做任何事
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            n++
            val am2 = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val r = am2.isBackgroundRestricted()
            out.add(
                Item(
                    !r, n, "后台活动限制",
                    if (r) "被限制 —— 后台几乎跑不起来，通知自然发不出" else "未限制",
                    fixLabel = if (r) "去设置" else null,
                    fix = if (r) ({ openAppDetails(ctx) }) else null
                )
            )
        }
        // 后台巡检到底跑没跑：这是判断「触发链路活着吗」最直接的证据
        n++
        val t = UpdateCheckState.lastTime(ctx)
        val ago = if (t <= 0L) "从未" else agoText(t)
        val fresh = t > 0L && System.currentTimeMillis() - t < 3 * 60 * 60 * 1000L
        out.add(
            Item(
                fresh, n, "后台巡检",
                if (t <= 0L) "还没跑过 —— 打开一次应用后开始生效"
                else if (fresh) "正常（最近一次：$ago）"
                else "已经 $ago 没跑了 —— 多半是后台被限制，到点就没人去发通知",
                fixLabel = if (fresh) null else "去设置",
                fix = if (fresh) null else ({ openAppDetails(ctx) })
            )
        )
        return out
    }

    /** 给文本型自检用的一行行描述。 */
    fun lines(ctx: Context): List<String> =
        items(ctx).map {
            "${if (it.ok) "✔" else "✖"} ${circle(it.no)} ${it.title}：${it.detail}"
        }

    /** 第 n 项的圈号（①②③…），超出范围退化为数字。 */
    private fun circle(no: Int): String {
        val cs = "\u2460\u2461\u2462\u2463\u2464\u2465\u2466\u2467\u2468\u2469"
        return if (no >= 1 && no <= cs.length) cs[no - 1].toString() else "$no."
    }

    /** 是否全部通过。 */
    fun allOk(ctx: Context): Boolean = items(ctx).all { it.ok }

    private fun channelItem(
        ctx: Context, nm: NotificationManager, no: Int, id: String, title: String,
        hard: Boolean = false
    ): Item {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Item(true, no, title, "（本系统版本无通知渠道概念）")
        }
        val ch = try {
            nm.getNotificationChannel(id)
        } catch (e: Throwable) {
            null
        }
        return when {
            ch == null -> Item(true, no, title, "尚未创建，首次发通知时会自动建立")
            ch.importance == NotificationManager.IMPORTANCE_NONE ->
                Item(
                    false, no, title,
                    "已被关闭 —— 通知会被系统静默丢弃，请在系统设置里重新打开",
                    hard = hard,
                    fixLabel = "去打开", fix = { openChannelSettings(ctx, id) }
                )
            ch.importance < NotificationManager.IMPORTANCE_HIGH ->
                Item(
                    false, no, title,
                    "级别偏低（${impName(ch.importance)}）—— 可能被收进无声通知，不响铃、不出横幅",
                    hard = hard,
                    fixLabel = "去调高", fix = { openChannelSettings(ctx, id) }
                )
            else -> Item(true, no, title, "正常（${impName(ch.importance)}，会响铃并弹横幅）")
        }
    }

    private fun agoText(t: Long): String {
        val min = Math.max(0L, (System.currentTimeMillis() - t) / 60000L)
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / 1440} 天前"
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
            val hard = list.firstOrNull { !it.ok && it.hard } ?: return
            sp.edit()
                .putString(KEY_DATE, today())
                .putInt(KEY_COUNT, sp.getInt(KEY_COUNT, 0) + 1)
                .apply()
            showReport(activity, list)
        } catch (e: Throwable) {
            android.util.Log.w("NotifyGuard", "warnIfBlocked: ${e.message}")
        }
    }

    /**
     * 弹一份体检报告：每项一行，不合格的项下面带一个直达按钮。
     *
     * 底部主按钮**写明第一个待修项的序号**（「去修第 N 项」），点一下直达那一项的设置位置；
     * 全部正常时没有可修的，主按钮直接不显示，只留一个居中的「关闭」。
     */
    fun showReport(activity: Activity, list: List<Item> = items(activity)) {
        if (activity.isFinishing) return
        val dm = activity.resources.displayMetrics
        val bad = list.filter { !it.ok }
        val first = bad.firstOrNull()
        UpdateManager.showStyledDialog(
            activity = activity,
            title = "通知体检",
            positiveText = if (first == null) "" else "去修第 ${first.no} 项",
            negativeText = "关闭",
            cancelable = true,
            onPositive = { first?.let { jumpToFix(activity, it) } }
        ) { host ->
            val lead = TextView(activity)
            lead.text = if (first == null) {
                "全部正常 —— 通知能正常送达，无需处理。"
            } else {
                "共 ${bad.size} 项需要处理，先看第 ${first.no} 项：${first.title}。"
            }
            lead.setTextColor(
                android.graphics.Color.parseColor(
                    if (first == null) "#FFB9F6D0" else "#FFFFE0A8"
                )
            )
            lead.textSize = 13f
            lead.setLineSpacing(3f, 1.15f)
            lead.setShadowLayer(2f, 0f, 1f, android.graphics.Color.parseColor("#CC000000"))
            lead.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dm.density).toInt() }
            host.addView(lead)

            for (one in list) {
                val row = LinearLayout(activity)
                row.orientation = LinearLayout.VERTICAL
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dm.density).toInt() }

                val tv = TextView(activity)
                tv.text = "${if (one.ok) "✔" else "✖"} ${circle(one.no)} ${one.title}\n    ${one.detail}"
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
                    btn.text = "第 ${one.no} 项·${one.fixLabel ?: "去设置"}"
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

    /** 跳到某一项对应的设置位置；该项无法跳转时退到通知设置总页。 */
    private fun jumpToFix(ctx: Context, item: Item) {
        val f = item.fix
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
