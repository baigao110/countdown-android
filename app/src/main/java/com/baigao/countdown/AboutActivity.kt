package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.os.Bundle
import android.provider.Settings
import android.app.AlertDialog
import android.widget.Button
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 关于页面：
 * - 中间靠上显示放大的倒计时图标，下方显示版本号与「检查更新」按钮；
 * - 进入页面即自动检查 GitHub 最新 Release，**发现新版本会强制弹出更新日志对话框**，
 *   点「立即更新」即在 App 内下载并拉起安装界面；
 * - 具体逻辑全部交给 UpdateManager（MainActivity 启动时也复用同一套逻辑）。
 */
class AboutActivity : Activity() {

    private lateinit var versionTv: TextView
    private lateinit var updateBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var notifyBtn: Button
    private lateinit var testBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var notifyCheckBtn: Button
    private lateinit var updateModeBtn: Button
    private lateinit var disclaimerBtn: Button
    private lateinit var checkStateTv: TextView
    private lateinit var updateModeDescTv: TextView
    private lateinit var medToggleBtn: Button
    private lateinit var medTimeBtn: Button
    private lateinit var medTimesBtn: Button
    private lateinit var medCalendarBtn: Button
    private lateinit var medStateTv: TextView
    private lateinit var medTestBtn: Button
    private lateinit var medCheckBtn: Button
    private lateinit var medNextBtn: Button
    private lateinit var medScheduleTv: TextView
    private lateinit var screenKeepBtn: Button
    private lateinit var medRowCalendar: LinearLayout
    private lateinit var medRowTools: LinearLayout

    private var checking = false
    /** 本次进入页面是否已自动检查过（避免 onResume 反复弹窗）。 */
    private var autoChecked = false
    private val REQ_NOTIFY = 1004

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        versionTv = findViewById(R.id.versionTv)
        updateBtn = findViewById(R.id.updateBtn)
        statusTv = findViewById(R.id.statusTv)
        val backBtn = findViewById<TextView>(R.id.backBtn)
        val changelogBtn = findViewById<Button>(R.id.changelogBtn)
        val helpBtn = findViewById<Button>(R.id.helpBtn)
        disclaimerBtn = findViewById<Button>(R.id.disclaimerBtn)
        notifyBtn = findViewById(R.id.notifyBtn)
        testBtn = findViewById(R.id.testBtn)
        batteryBtn = findViewById(R.id.batteryBtn)
        notifyCheckBtn = findViewById(R.id.notifyCheckBtn)
        updateModeBtn = findViewById(R.id.updateModeBtn)
        updateModeDescTv = findViewById(R.id.updateModeDescTv)
        checkStateTv = findViewById(R.id.checkStateTv)
        medToggleBtn = findViewById(R.id.medToggleBtn)
        medTimeBtn = findViewById(R.id.medTimeBtn)
        medTimesBtn = findViewById(R.id.medTimesBtn)
        medCalendarBtn = findViewById(R.id.medCalendarBtn)
        medTestBtn = findViewById(R.id.medTestBtn)
        medCheckBtn = findViewById(R.id.medCheckBtn)
        medNextBtn = findViewById(R.id.medNextBtn)
        medRowCalendar = findViewById(R.id.medRowCalendar)
        medRowTools = findViewById(R.id.medRowTools)
        medStateTv = findViewById(R.id.medStateTv)
        medScheduleTv = findViewById(R.id.medScheduleTv)
        screenKeepBtn = findViewById(R.id.screenKeepBtn)

        versionTv.text = "版本 v${UpdateManager.CURRENT_VERSION_NAME}"
        findViewById<TextView>(R.id.githubLinkTv).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/baigao110/countdown-android")))
            } catch (_: Exception) {
                Toast.makeText(this, "打不开链接呢", Toast.LENGTH_SHORT).show()
            }
        }
        backBtn.setOnClickListener { finish() }
        changelogBtn.setOnClickListener { UpdateManager.showChangelog(this) }
        helpBtn.setOnClickListener { UpdateManager.showHelp(this) }
        disclaimerBtn.setOnClickListener { DisclaimerManager.show(this) }
        // 手动点「检查更新」属于用户主动要看，强制弹页面
        updateBtn.setOnClickListener { checkUpdate(forceDialog = true, forcePage = true) }

        // 「开启通知」：没权限就申请（Android 13+），老版本直接跳通知设置页
        notifyBtn.setOnClickListener {
            if (UpdateNotifier.hasPermission(this)) {
                Toast.makeText(this, "通知权限已经开啦，可以点「测试通知」看看通不通", Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY
                )
            } else {
                UpdateNotifier.openSettings(this)
            }
        }
        // 「测试通知」：立刻发一条，用户能马上确认通知到底收不收得到
        testBtn.setOnClickListener {
            val ok = UpdateNotifier.sendTest(this)
            Toast.makeText(
                this,
                if (ok) "已经发出测试通知啦，要是没看到，请点「开启通知」检查下权限哦"
                else "通知被拦住啦：请点「开启通知」在系统设置里把通知权限打开哦",
                Toast.LENGTH_LONG
            ).show()
        }
        // 「允许后台运行」：关掉电池优化，后台检查才不会被系统掐断
        batteryBtn.setOnClickListener { requestIgnoreBattery() }
        // 「通知体检」：把「退出后收不到通知」拆成一项项可查、可一键修的开关
        notifyCheckBtn.setOnClickListener { NotifyGuard.showReport(this@AboutActivity) }
        // 「屏幕常亮」：开关开启后，本应用在前台时屏幕保持常亮不锁屏
        screenKeepBtn.setOnClickListener {
            val on = !ScreenKeepOn.isOn(this)
            ScreenKeepOn.setOn(this, on)
            ScreenKeepOn.apply(this)
            refreshScreenKeep()
            Toast.makeText(
                this,
                if (on) "屏幕常亮开啦：应用在前台时屏幕一直亮着、不会锁屏"
                else "屏幕常亮关啦：恢复手机默认的熄屏时间",
                Toast.LENGTH_SHORT
            ).show()
        }
        // 「更新提示方式」：弹窗被拦截类软件关掉时，可以改成只在通知栏提醒
        updateModeBtn.setOnClickListener {
            val m = UpdateManager.nextPromptMode(this@AboutActivity)
            refreshUpdateMode()
            Toast.makeText(
                this@AboutActivity,
                "更新提示方式：${m.label}\n${m.desc}",
                Toast.LENGTH_LONG
            ).show()
        }

        // ---- 吃药提醒：开关 / 提醒时间 / 吃药日历 ----
        medToggleBtn.setOnClickListener {
            val on = !MedicineReminder.isEnabled(this)
            MedicineReminder.setEnabled(this, on)
            if (on && !UpdateNotifier.hasPermission(this)) {
                // 没通知权限的话提醒根本弹不出来，顺手引导一次
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY
                    )
                } else {
                    UpdateNotifier.openSettings(this)
                }
                Toast.makeText(this, "先允许通知权限，吃药小提醒才能弹出来哦", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    if (on) "吃药小提醒开啦（${MedicineReminder.timeText(this)}）" else "吃药小提醒关啦",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshMedicine()
        }
        medTimeBtn.setOnClickListener { showDoseEditor() }
        medTimesBtn.setOnClickListener { showTimesPerDayPicker() }
        medCalendarBtn.setOnClickListener {
            startActivity(Intent(this, MedicineCalendarActivity::class.java))
        }
        // 「测试提醒」：立刻发一条吃药通知，用来确认通知链路到底通不通
        medTestBtn.setOnClickListener {
            val ok = MedicineReminder.notifyNow(this)
            Toast.makeText(
                this,
                if (ok) "已经发出吃药小提醒测试通知啦（每天 ${MedicineReminder.timesPerDay(this)} 次：${MedicineReminder.doseScheduleText(this)}）" else "通知被拦截：请先在上方点「开启通知」",
                Toast.LENGTH_LONG
            ).show()
        }
        // 「后台自检」：把影响「退出 App 后还能不能提醒」的每一项列出来
        medCheckBtn.setOnClickListener { showMedicineSelfCheck() }
        // 「下次提醒」：手动指定下一次提醒的具体日期与时刻
        medNextBtn.setOnClickListener { showNextReminderPicker() }
        refreshMedicine()
        refreshUpdateMode()
        refreshScreenKeep()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFY) return
        if (grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "通知权限已经开啦", Toast.LENGTH_SHORT).show()
        } else {
            UpdateNotifier.openSettings(this)
        }
        refreshState()
        refreshUpdateMode()
    }

    /** 引导关闭电池优化（不关的话系统会在后台限制网络与定时检查）。 */
    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "系统版本比较旧，这个不用设置哦", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已经允许后台运行啦", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "请在系统设置里手动把本应用的电池优化关掉哦", Toast.LENGTH_LONG).show()
        }
    }

    /** 「更新提示方式」按钮的文字：自动模式下若检测到拦截工具，直接把结论写上去。 */
    private fun refreshUpdateMode() {
        val m = UpdateManager.promptMode(this)
        updateModeBtn.text = if (m == UpdateManager.UpdatePromptMode.AUTO) {
            if (UpdateManager.hasSuspiciousAccessibility(this)) {
                "更新提示：自动（已改只发通知）"
            } else {
                "更新提示：自动"
            }
        } else {
            "更新提示：${m.label}"
        }
        // 在按钮下方显示三种更新提示方式各自的行为含义，当前选中的用 ● 标出
        val sb = StringBuilder()
        sb.append("更新提示方式（点上面按钮切换哦）：\n")
        for (mode in UpdateManager.UpdatePromptMode.values()) {
            val mark = if (mode == m) "● " else "○ "
            sb.append(mark).append(mode.label).append("：").append(mode.desc).append("\n")
        }
        updateModeDescTv.text = sb.toString().trimEnd()
    }

    /** 刷新「后台检查 / 通知权限」状态行。 */
    private fun refreshState() {
        val perm = if (UpdateNotifier.hasPermission(this)) "通知权限：已经开啦" else "通知权限：还没开"
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) "电池优化：已经关掉啦" else "电池优化：还没关"
        } else ""
        checkStateTv.text = listOf(UpdateCheckState.summary(this), perm, battery)
            .filter { it.isNotEmpty() }.joinToString("\n")
        refreshMedicine()
    }

    /** 刷新「吃药提醒」卡片：开关状态、提醒时间与今日是否已吃药。 */
    private fun refreshMedicine() {
        if (!::medToggleBtn.isInitialized) return
        val on = MedicineReminder.isEnabled(this)
        medToggleBtn.text = if (on) "吃药提醒：开啦" else "吃药提醒：关啦"
        val medVis = if (on) android.view.View.VISIBLE else android.view.View.GONE
        // 关闭状态下隐藏提醒时间与状态等全部子项，仅保留开关
        medTimeBtn.visibility = medVis
        medStateTv.visibility = medVis
        medRowCalendar.visibility = medVis
        medRowTools.visibility = medVis
        val n = MedicineReminder.timesPerDay(this)
        medTimeBtn.text = if (n >= 2) "吃药时间（每天 $n 次哦）" else "首剂时间 ${MedicineReminder.timeText(this)}"
        medTimesBtn.text = "每天 $n 次"
        medTimesBtn.visibility = medVis
        medScheduleTv.text = SpannableStringBuilder("每天 $n 次：")
            .append(MedicineReminder.doseScheduleSpannable(this))
        medScheduleTv.visibility = medVis
        medStateTv.text = MedicineReminder.statusText(this)
        medNextBtn.text = "下次提醒\n${MedicineReminder.nextTriggerText(this)}"
    }

    /** 刷新「屏幕常亮」开关按钮文案。 */
    private fun refreshScreenKeep() {
        if (!::screenKeepBtn.isInitialized) return
        val on = ScreenKeepOn.isOn(this)
        screenKeepBtn.text = if (on) "屏幕常亮：开啦" else "屏幕常亮：关啦"
    }

    /** 读取 TimePicker：API 23+ 用 hour / minute，老版本用已废弃的 currentHour / currentMinute。 */
    @Suppress("DEPRECATION")
    private fun readTime(p: TimePicker): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) p.hour to p.minute
        else p.currentHour to p.currentMinute

    /** 写入 TimePicker（同上的版本分支）。 */
    @Suppress("DEPRECATION")
    private fun applyTime(p: TimePicker, hour: Int, minute: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.hour = hour
            p.minute = minute
        } else {
            p.currentHour = hour
            p.currentMinute = minute
        }
    }

    /** 跳到本应用的系统设置页（自启动 / 后台运行都在这一片）。 */
    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "请在系统设置里找到本应用，把自启动和后台运行打开哦", Toast.LENGTH_LONG).show()
        }
    }

    /** 电池优化是否已关闭（未关闭时后台容易被系统掐断）。 */
    private fun batteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 吃药提醒自检：退出 / 关闭 App 后不提醒，基本都能在这里看出卡在哪一环。 */
    private fun showMedicineSelfCheck() {
        if (isFinishing) return
        val lines = mutableListOf<CharSequence>()
        lines.add(
            if (MedicineReminder.nextIsCustom(this))
                "下次提醒：${MedicineReminder.nextTriggerText(this)}（手动改过啦，这次响过就回到常规）"
            else "下次提醒：${MedicineReminder.nextTriggerText(this)}"
        )
        val schedN = MedicineReminder.timesPerDay(this)
        lines.add(
            SpannableStringBuilder("每天次数：${schedN} 次（")
                .append(MedicineReminder.doseScheduleSpannable(this)).append("）")
        )
        lines.add("闹钟挂好啦：${MedicineReminder.lastScheduleText(this)}")
        lines.add("上一次发出提醒：${MedicineReminder.lastNotifyText(this)}")
        lines.add(
            if (UpdateNotifier.hasPermission(this)) "通知权限：已经开啦"
            else "通知权限：还没开（一定要开，不然根本弹不出来）"
        )
        lines.add(
            if (MedicineReminder.canScheduleExact(this)) "精确闹钟：已经允许啦"
            else "精确闹钟：还没开哦（已自动改用系统闹钟 + 重复闹钟，一样能提醒你～）"
        )
        lines.add(if (batteryOptimized()) "电池优化：还没关（建议关掉哦）" else "电池优化：已经关掉啦")
        lines.add("")
        lines.add("已经做了四重保障哦：系统闹钟 + 精确闹钟 + 每日重复闹钟 +")
        lines.add("两路后台巡检（到点后 2 分钟 / 30 分钟还会各补响一次）。")
        // 通知体检：把「权限 / 渠道 / 后台」这些看不见的闸门一并告知，
        // 否则用户只能猜——而这些环节任何一个断了，代码层面都是彻底静默的。
        val bad = NotifyGuard.lines(this).filter { it.startsWith("✖") }
        if (bad.isEmpty()) {
            lines.add("通知体检：都正常啦（权限、渠道、后台都通）")
        } else {
            lines.add("通知体检：有 ${bad.size} 项会挡住通知——")
            bad.forEach { lines.add("  $it") }
        }
        lines.add("要是还是不提醒，多半是手机把本应用「强制停止」了：")
        lines.add("  1. 别从最近任务里划掉本应用的卡片（或在最近任务里给它加个锁哦）；")
        lines.add("  2. 系统设置 → 应用管理 → 本应用，把「自启动 / 后台运行」打开哦；")
        lines.add("  3. 重新打开一次本应用，错过的提醒会自动补上哒。")
        val needBattery = batteryOptimized()
        UpdateManager.showStyledDialog(
            activity = this,
            title = "吃药小提醒自检",
            positiveText = if (needBattery) "关掉电池优化" else "去设置看看",
            negativeText = "关闭",
            onPositive = { if (needBattery) requestIgnoreBattery() else openAppDetails() }
        ) { host ->
            val tv = TextView(this).apply {
                text = SpannableStringBuilder().apply {
                    lines.forEach { append(it); append("\n") }
                }
                setTextColor(android.graphics.Color.parseColor("#FFE4EEFF"))
                textSize = 13f
                setLineSpacing(4f, 1.2f)
                setShadowLayer(2f, 0f, 1f, android.graphics.Color.parseColor("#CC000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            host.addView(tv)
        }
    }

    /** DatePicker 用滚轮模式（日历模式太高，会把对话框撑爆）。 */
    @Suppress("DEPRECATION")
    private fun makeDatePicker(cal: Calendar): DatePicker {
        val dp = DatePicker(this)
        dp.calendarViewShown = false
        dp.spinnersShown = true
        dp.init(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), null)
        return dp
    }

    /**
     * 改完「下次提醒」后的引导：到点收不到，绝大多数是手机把本应用强制停止了，
     * 这里把时间、已经做的保障、以及用户自己能做的一步都讲清楚，并给一个直达按钮。
     */
    private fun showNextReminderGuide() {
        if (isFinishing) return
        val time = MedicineReminder.nextTriggerText(this)
        val text = listOf(
            "下次提醒已经设为 $time（只影响这一次，响过就自动回到常规）",
            "",
            "为了到点一定能收到，已经做了这些：",
            "  1. 系统闹钟 + 精确闹钟（同一时刻双路，能穿透省电模式哦）",
            "  2. 到点后 2 分钟、30 分钟各补响一次",
            "  3. 后台每 15 分钟巡检一次，闹钟没了会补挂、错过会补上",
            "  4. 重新打开本应用时，漏掉的提醒立刻补上哒",
            "",
            "要是这样还是收不到，一般是手机把本应用「强制停止」了：",
            "  · 别从最近任务里划掉本应用的卡片（或在最近任务里给它加个锁）",
            "  · 系统设置 → 应用管理 → 本应用，把「自启动 / 后台运行」打开哦",
            "  · 把本应用的电池优化关掉哦"
        ).joinToString("\n")
        val needBattery = batteryOptimized()
        UpdateManager.showStyledDialog(
            activity = this,
            title = "下次提醒设好啦",
            positiveText = if (needBattery) "允许后台运行" else "去应用设置看看",
            negativeText = "知道啦",
            onPositive = { if (needBattery) requestIgnoreBattery() else openAppDetails() }
        ) { host ->
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(android.graphics.Color.parseColor("#FFE4EEFF"))
                textSize = 13f
                setLineSpacing(4f, 1.2f)
                setShadowLayer(2f, 0f, 1f, android.graphics.Color.parseColor("#CC000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            host.addView(tv)
        }
    }

    /**
     * 手动改「下一次提醒」：弹出日期 + 时刻选择器。
     * 改完只影响即将到来的那一次，响过之后自动回到每天固定时刻（也可一键恢复常规）。
     */
    private fun showNextReminderPicker() {
        if (isFinishing) return
        var datePicker: DatePicker? = null
        var timePicker: TimePicker? = null
        var dialog: AlertDialog? = null
        val cal = Calendar.getInstance().apply {
            timeInMillis = MedicineReminder.nextTriggerMillis(this@AboutActivity)
        }
        dialog = UpdateManager.showStyledDialog(
            activity = this,
            title = "设一下下次提醒时间",
            positiveText = "确定",
            negativeText = "取消",
            onPositive = {
                val dp = datePicker ?: return@showStyledDialog
                val tp = timePicker ?: return@showStyledDialog
                val (h, m) = readTime(tp)
                val target = Calendar.getInstance().apply {
                    set(dp.year, dp.month, dp.dayOfMonth, h, m, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (target.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "请选一个比现在晚一点的时间哦", Toast.LENGTH_LONG).show()
                    return@showStyledDialog
                }
                MedicineReminder.setNextCustom(this, target.timeInMillis)
                refreshMedicine()
                showNextReminderGuide()
            }
        ) { host ->
            val dp = makeDatePicker(cal)
            datePicker = dp
            host.addView(dp)

            val tp = TimePicker(this)
            tp.setIs24HourView(true)
            applyTime(tp, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * resources.displayMetrics.density).toInt()
            tp.layoutParams = lp
            timePicker = tp
            host.addView(tp)

            val tip = TextView(this).apply {
                text = "改的是「下一次」提醒的时间，响过这一次就自动回到每天固定时刻（每天 " +
                    MedicineReminder.timesPerDay(this@AboutActivity) + " 次：" +
                    MedicineReminder.doseScheduleText(this@AboutActivity) + "）；\n" +
                    "想改每天的固定时刻和次数，请用上面的「提醒时间 / 每天次数」按钮哦。"
                setTextColor(android.graphics.Color.parseColor("#FFC6D5EF"))
                textSize = 13f
                setShadowLayer(2f, 0f, 1f, android.graphics.Color.parseColor("#CC000000"))
                val tlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tlp.topMargin = (10 * resources.displayMetrics.density).toInt()
                layoutParams = tlp
            }
            host.addView(tip)

            val reset = Button(this).apply {
                text = "回到常规（每天 ${MedicineReminder.timeText(this@AboutActivity)}）"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#FF001018"))
                setBackgroundResource(R.drawable.circle_btn)
                val blp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt()
                )
                blp.topMargin = (10 * resources.displayMetrics.density).toInt()
                layoutParams = blp
            }
            reset.setOnClickListener {
                MedicineReminder.clearNextCustom(this@AboutActivity)
                MedicineReminder.schedule(this@AboutActivity)
                refreshMedicine()
                dialog?.dismiss()
                Toast.makeText(this@AboutActivity, "已经回到每天固定时刻提醒啦", Toast.LENGTH_SHORT).show()
            }
            host.addView(reset)
        }
        dialog
    }

    /** 吃药时间面板：编辑每天各次的时刻与「服药时机」；2 次及以上时首剂时间按钮即变为它。 */
    private fun showDoseEditor() {
        if (isFinishing) return
        DoseEditor.showDialog(this) { refreshMedicine() }
    }

    /** 「每天次数」选择：1/2/3/4 次，首剂时间不变、其余均匀分布在当天。 */
    private fun showTimesPerDayPicker() {
        if (isFinishing) return
        val opts = arrayOf("1 次 / 天", "2 次 / 天", "3 次 / 天", "4 次 / 天")
        val cur = MedicineReminder.timesPerDay(this) - 1
        AlertDialog.Builder(this)
            .setTitle("每天想提醒几次呀")
            .setSingleChoiceItems(opts, cur) { d, which ->
                MedicineReminder.setTimesPerDay(this, which + 1)
                refreshMedicine()
                d.dismiss()
                Toast.makeText(
                    this,
                    "已经设为每天 ${which + 1} 次啦（首剂 ${MedicineReminder.timeText(this)}，其余均匀分布在当天）",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回后，继续之前挂起的安装
        if (UpdateManager.consumePendingInstall(this)) return
        // 若用户在此前弹出的「发现新版本」提示框里点了「忽略更新」，
        // 回到「关于」页就显示「已是最新版本」（临时忽略，下次手动检查仍会重新提示）。
        val ignored = UpdateManager.consumeIgnored()
        if (ignored != null) {
            statusTv.text = "已经是最新版本啦 v${UpdateManager.CURRENT_VERSION_NAME}"
            updateBtn.text = "检查更新"
            updateBtn.setOnClickListener { checkUpdate(forceDialog = true, forcePage = true) }
            Toast.makeText(this, "已经是最新版本啦", Toast.LENGTH_SHORT).show()
        }
        refreshState()
        refreshScreenKeep()
        if (!autoChecked) {
            autoChecked = true
            checkUpdate(forceDialog = true)
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开前台就清掉常亮 Flag，避免关掉开关后 Flag 残留在已暂停的窗口上
        ScreenKeepOn.onPause(this)
    }

    private fun checkUpdate(forceDialog: Boolean, forcePage: Boolean = false) {
        if (checking) return
        checking = true
        updateBtn.isEnabled = false
        statusTv.text = "正在帮你看看有没有新版本..."

        UpdateManager.check(this, forceDialog, forcePage = forcePage) { info ->
            checking = false
            updateBtn.isEnabled = true
            if (info == null) {
                updateBtn.text = "检查更新"
                statusTv.text = "已经是最新版本啦 v${UpdateManager.CURRENT_VERSION_NAME}"
                if (forceDialog) {
                    Toast.makeText(this, "已经是最新版本啦", Toast.LENGTH_SHORT).show()
                }
            } else {
                statusTv.text = "发现新版本啦 v${info.name}"
                updateBtn.text = "下载并安装 v${info.name}"
                updateBtn.setOnClickListener { UpdateManager.downloadAndInstall(this, info) }
            }
        }
    }
}
