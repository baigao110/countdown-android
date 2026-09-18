package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast

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
    private lateinit var checkStateTv: TextView
    private lateinit var medToggleBtn: Button
    private lateinit var medTimeBtn: Button
    private lateinit var medCalendarBtn: Button
    private lateinit var medStateTv: TextView
    private lateinit var medTestBtn: Button
    private lateinit var medCheckBtn: Button

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
        notifyBtn = findViewById(R.id.notifyBtn)
        testBtn = findViewById(R.id.testBtn)
        batteryBtn = findViewById(R.id.batteryBtn)
        checkStateTv = findViewById(R.id.checkStateTv)
        medToggleBtn = findViewById(R.id.medToggleBtn)
        medTimeBtn = findViewById(R.id.medTimeBtn)
        medCalendarBtn = findViewById(R.id.medCalendarBtn)
        medTestBtn = findViewById(R.id.medTestBtn)
        medCheckBtn = findViewById(R.id.medCheckBtn)
        medStateTv = findViewById(R.id.medStateTv)

        versionTv.text = "版本 v${UpdateManager.CURRENT_VERSION_NAME}"
        backBtn.setOnClickListener { finish() }
        changelogBtn.setOnClickListener { UpdateManager.showChangelog(this) }
        helpBtn.setOnClickListener { UpdateManager.showHelp(this) }
        updateBtn.setOnClickListener { checkUpdate(forceDialog = true) }

        // 「开启通知」：没权限就申请（Android 13+），老版本直接跳通知设置页
        notifyBtn.setOnClickListener {
            if (UpdateNotifier.hasPermission(this)) {
                Toast.makeText(this, "通知权限已开启，可点「测试通知」验证", Toast.LENGTH_SHORT).show()
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
                if (ok) "已发出测试通知，若没看到请点「开启通知」检查权限"
                else "通知被拦截：请点「开启通知」在系统设置里打开通知权限",
                Toast.LENGTH_LONG
            ).show()
        }
        // 「允许后台运行」：关掉电池优化，后台检查才不会被系统掐断
        batteryBtn.setOnClickListener { requestIgnoreBattery() }

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
                Toast.makeText(this, "请允许通知权限，吃药提醒才会弹出", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    if (on) "吃药提醒已开启（${MedicineReminder.timeText(this)}）" else "吃药提醒已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshMedicine()
        }
        medTimeBtn.setOnClickListener { showMedicineTimePicker() }
        medCalendarBtn.setOnClickListener {
            startActivity(Intent(this, MedicineCalendarActivity::class.java))
        }
        // 「测试提醒」：立刻发一条吃药通知，用来确认通知链路到底通不通
        medTestBtn.setOnClickListener {
            val ok = MedicineReminder.notifyNow(this)
            Toast.makeText(
                this,
                if (ok) "已发出吃药提醒通知" else "通知被拦截：请先在上方点「开启通知」",
                Toast.LENGTH_LONG
            ).show()
        }
        // 「后台自检」：把影响「退出 App 后还能不能提醒」的每一项列出来
        medCheckBtn.setOnClickListener { showMedicineSelfCheck() }
        refreshMedicine()
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
            Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show()
        } else {
            UpdateNotifier.openSettings(this)
        }
        refreshState()
    }

    /** 引导关闭电池优化（不关的话系统会在后台限制网络与定时检查）。 */
    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "系统版本较旧，无需设置", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已允许后台运行", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "请在系统设置里手动关闭本应用的电池优化", Toast.LENGTH_LONG).show()
        }
    }

    /** 刷新「后台检查 / 通知权限」状态行。 */
    private fun refreshState() {
        val perm = if (UpdateNotifier.hasPermission(this)) "通知权限：已开启" else "通知权限：未开启"
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) "电池优化：已关闭" else "电池优化：未关闭"
        } else ""
        checkStateTv.text = listOf(UpdateCheckState.summary(this), perm, battery)
            .filter { it.isNotEmpty() }.joinToString("\n")
        refreshMedicine()
    }

    /** 刷新「吃药提醒」卡片：开关状态、提醒时间与今日是否已吃药。 */
    private fun refreshMedicine() {
        if (!::medToggleBtn.isInitialized) return
        val on = MedicineReminder.isEnabled(this)
        medToggleBtn.text = if (on) "吃药提醒：已开启" else "吃药提醒：已关闭"
        medTimeBtn.text = "提醒时间 ${MedicineReminder.timeText(this)}"
        medStateTv.text = MedicineReminder.statusText(this)
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
            Toast.makeText(this, "请在系统设置里找到本应用，允许自启动与后台运行", Toast.LENGTH_LONG).show()
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
        val lines = mutableListOf<String>()
        lines.add("下次提醒：${MedicineReminder.nextTriggerText(this)}")
        lines.add("闹钟挂载：${MedicineReminder.lastScheduleText(this)}")
        lines.add(
            if (UpdateNotifier.hasPermission(this)) "通知权限：已开启"
            else "通知权限：未开启（必须开启，否则根本弹不出）"
        )
        lines.add(
            if (MedicineReminder.canScheduleExact(this)) "精确闹钟：已允许"
            else "精确闹钟：未允许（已自动改用系统闹钟 + 重复闹钟，仍能提醒）"
        )
        lines.add(if (batteryOptimized()) "电池优化：未关闭（建议关掉）" else "电池优化：已关闭")
        lines.add("")
        lines.add("已经做了三重保障：系统闹钟 + 每日重复闹钟 + 后台巡检（错过会补发）。")
        lines.add("若仍然不提醒，多半是手机把本应用「强制停止」了：")
        lines.add("  1. 别从最近任务里划掉本应用的卡片（或在最近任务里给它加锁）；")
        lines.add("  2. 系统设置 → 应用管理 → 本应用，打开「自启动 / 后台运行」；")
        lines.add("  3. 重新打开一次本应用，错过的提醒会自动补发。")
        val needBattery = batteryOptimized()
        UpdateManager.showStyledDialog(
            activity = this,
            title = "吃药提醒自检",
            positiveText = if (needBattery) "关闭电池优化" else "去设置",
            negativeText = "关闭",
            onPositive = { if (needBattery) requestIgnoreBattery() else openAppDetails() }
        ) { host ->
            val tv = TextView(this).apply {
                text = lines.joinToString("\n")
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

    /** 修改吃药提醒时间：沿用「更新日志」那套深色玻璃对话框，里面放一个 24 小时制 TimePicker。 */
    private fun showMedicineTimePicker() {
        if (isFinishing) return
        var picker: TimePicker? = null
        UpdateManager.showStyledDialog(
            activity = this,
            title = "吃药提醒时间",
            positiveText = "确定",
            negativeText = "取消",
            onPositive = {
                val p = picker ?: return@showStyledDialog
                val (h, m) = readTime(p)
                MedicineReminder.setTime(this, h, m)
                refreshMedicine()
                Toast.makeText(this, "吃药提醒时间已设为 ${MedicineReminder.timeText(this)}",
                    Toast.LENGTH_SHORT).show()
            }
        ) { host ->
            val tp = TimePicker(this)
            tp.setIs24HourView(true)
            applyTime(
                tp,
                MedicineReminder.hour(this@AboutActivity),
                MedicineReminder.minute(this@AboutActivity)
            )
            picker = tp
            host.addView(tp)
            val tip = TextView(this).apply {
                text = "每天到点提醒一次；点通知上的「已吃药」即可记录当天。"
                setTextColor(android.graphics.Color.parseColor("#FFC6D5EF"))
                textSize = 13f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            host.addView(tip)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「允许安装未知应用」设置页返回后，继续之前挂起的安装
        if (UpdateManager.consumePendingInstall(this)) return
        refreshState()
        if (!autoChecked) {
            autoChecked = true
            checkUpdate(forceDialog = true)
        }
    }

    private fun checkUpdate(forceDialog: Boolean) {
        if (checking) return
        checking = true
        updateBtn.isEnabled = false
        statusTv.text = "正在检查更新..."

        UpdateManager.check(this, forceDialog) { info ->
            checking = false
            updateBtn.isEnabled = true
            if (info == null) {
                updateBtn.text = "检查更新"
                statusTv.text = "已是最新版本 v${UpdateManager.CURRENT_VERSION_NAME}"
                if (forceDialog) {
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            } else {
                statusTv.text = "发现新版本 v${info.name}"
                updateBtn.text = "下载并安装 v${info.name}"
                updateBtn.setOnClickListener { UpdateManager.downloadAndInstall(this, info) }
            }
        }
    }
}
