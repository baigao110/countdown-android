package com.baigao.countdown

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.os.Bundle
import android.provider.Settings
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
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
 *
 * 设置项统一采用「左右滑动开关」（开 / 关）与「下拉框」（多选一）：
 * - 屏幕常亮拆成「UI 界面常亮」「悬浮框常亮」两个开关；
 * - 更新提示方式（自动 / 弹出提示 / 只发通知）改为下拉框选择。
 */
class AboutActivity : Activity() {

    private lateinit var versionTv: TextView
    private lateinit var updateBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var notifyBtn: Button
    private lateinit var testBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var notifyCheckBtn: Button
    private lateinit var disclaimerBtn: Button
    private lateinit var checkStateTv: TextView
    private lateinit var updateModeDescTv: TextView
    private lateinit var updateModeSpinner: Spinner
    private lateinit var uiKeepSwitch: Switch
    private lateinit var floatKeepSwitch: Switch
    private lateinit var lockNotifySwitch: Switch
    private lateinit var lockNotifyDescTv: TextView
    private lateinit var lockNotifyOpenBtn: Button
    private lateinit var lockTestBtn: Button
    private lateinit var lockStateTv: TextView
    private lateinit var lockKeepSwitch: Switch
    private lateinit var lockKeepDescTv: TextView
    private lateinit var timeFormatSpinner: Spinner
    private lateinit var timeFormatDescTv: TextView

    /** 备注时间制式下拉框的两个候选项（下标即取值）。 */
    private val timeFormatOptions = arrayOf("24 小时制", "12 小时制")

    /** 程序化回填开关状态时抑制回调，避免 onResume 刷新时误触发保存与 Toast。 */
    private var suppressSwitch = false
    /** 下拉框初始化完成前忽略选中回调（避免铺适配器时的默认选中覆盖用户设置）。 */
    private var spinnerReady = false
    /** 备注时间制式下拉框的初始化完成标记（与「更新提示方式」各自独立）。 */
    private var formatSpinnerReady = false

    private var checking = false
    /** 本次进入页面是否已自动检查过（避免 onResume 反复弹窗）。 */
    private var autoChecked = false
    private val REQ_NOTIFY = 1004
    /** 锁屏常亮页需要「显示在其他应用上层」权限，去设置页的请求码。 */
    private val REQ_OVERLAY = 1005

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
        updateModeSpinner = findViewById(R.id.updateModeSpinner)
        updateModeDescTv = findViewById(R.id.updateModeDescTv)
        checkStateTv = findViewById(R.id.checkStateTv)
        uiKeepSwitch = findViewById(R.id.uiKeepSwitch)
        floatKeepSwitch = findViewById(R.id.floatKeepSwitch)
        lockNotifySwitch = findViewById(R.id.lockNotifySwitch)
        lockNotifyDescTv = findViewById(R.id.lockNotifyDescTv)
        lockNotifyOpenBtn = findViewById(R.id.lockNotifyOpenBtn)
        lockTestBtn = findViewById(R.id.lockTestBtn)
        lockStateTv = findViewById(R.id.lockStateTv)
        lockKeepSwitch = findViewById(R.id.lockKeepSwitch)
        lockKeepDescTv = findViewById(R.id.lockKeepDescTv)
        // 锁屏上看不到倒计时时的一键排障：国内 ROM 的「锁屏显示 / 静默通知」开关
        // 基本都藏在应用信息里，直接跳到本应用的应用信息页最省事。
        lockNotifyOpenBtn.setOnClickListener { openLockScreenNotifySettings() }
        // 「测试锁屏通知」：立刻发一条真实数据通知，锁屏上马上就能看出到底显不显示
        lockTestBtn.setOnClickListener {
            if (!LockScreenClock.isOn(this@AboutActivity)) {
                Toast.makeText(
                    this@AboutActivity,
                    "先把上面的「锁屏通知显示」打开，再点测试哦",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val ok = try {
                LockScreenClock.testOne(this@AboutActivity)
            } catch (e: Throwable) {
                false
            }
            Toast.makeText(
                this@AboutActivity,
                if (ok) "已经发出去啦～ 现在按一下电源键锁屏，看锁屏上有没有这条倒计时"
                else "发不出去呢：先点「锁屏上看不到？点我打开锁屏通知设置」检查下系统设置",
                Toast.LENGTH_LONG
            ).show()
            refreshLockState()
        }
        timeFormatSpinner = findViewById(R.id.timeFormatSpinner)
        timeFormatDescTv = findViewById(R.id.timeFormatDescTv)
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

        // ---- 屏幕常亮：两个左右滑动开关 ----
        // UI 界面常亮：打开 App 时（任意界面）屏幕保持常亮、不锁屏（功能与旧版一致）
        uiKeepSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            ScreenKeepOn.setUiOn(this, checked)
            ScreenKeepOn.apply(this)
            Toast.makeText(
                this,
                if (checked) "UI 界面常亮开啦：打开 App 时屏幕一直亮着、不会锁屏"
                else "UI 界面常亮关啦：恢复手机默认的熄屏时间",
                Toast.LENGTH_SHORT
            ).show()
        }
        // 悬浮框常亮：悬浮倒计时「展开」时屏幕保持常亮；关掉或收缩成小条时跟随系统熄屏时间
        floatKeepSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            ScreenKeepOn.setFloatOn(this, checked)
            // 立刻让已经显示的悬浮窗按新设置调整常亮标志（没在跑就什么都不用做）
            refreshFloatingKeepOn()
            Toast.makeText(
                this,
                if (checked) "悬浮框常亮开啦：悬浮倒计时展开时屏幕会一直亮着"
                else "悬浮框常亮关啦：悬浮窗不再干预屏幕，按手机系统的时间正常熄屏",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ---- 锁屏 / 通知栏倒计时：左右滑动开关 ----
        // 开启后，列表里「展开」过的倒计时以常驻通知显示在通知栏与锁屏上，
        // 标题、倒计时数字、模式、备注、主题配色与悬浮窗完全一致，并且每秒跟着跳秒。
        lockNotifySwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            LockScreenClock.setOn(this, checked)
            if (checked) {
                // 立刻刷一次，并把刷新服务拉起来（退到后台也要有人每秒更新锁屏通知）
                try {
                    LockScreenClock.updateAll(this, CountdownStore.load(this))
                } catch (_: Throwable) {
                    // 通知发不出去也不该卡住界面设置
                }
                startCountdownServiceIfStopped()
                Toast.makeText(
                    this,
                    "锁屏通知开啦：展开过的倒计时会常驻显示在通知栏 / 锁屏上，\n和悬浮窗里一模一样、每秒跟着跳秒",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "锁屏通知关啦：通知栏上的锁屏倒计时都收掉啦",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ---- 锁屏通知常亮：只要锁屏上还挂着倒计时，屏幕就一直亮着 ----
        lockKeepSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            LockKeepOn.setOn(this, checked)
            if (checked) {
                // 立刻接上（不用等服务下一跳），并把屏幕按在亮着的状态
                LockKeepOn.apply(this)
                if (canOverlayForKeep()) {
                    Toast.makeText(
                        this,
                        "锁屏通知常亮开啦：只要锁屏 / 通知栏上还有倒计时，屏幕就一直亮着，\n不会按手机的熄屏时间睡去（有点费电哦）",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // 没这个权限的话，系统不让 App 在后台把常亮页挂到锁屏上（系统闹钟也是靠它）
                    askOverlayForKeep()
                }
            } else {
                Toast.makeText(
                    this,
                    "锁屏通知常亮关啦：屏幕熄屏时间交还给手机系统设置",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshLockKeepDesc()
        }

        // ---- 更新提示方式：下拉框（多选一） ----
        val modes = UpdateManager.UpdatePromptMode.values()
        updateModeSpinner.adapter = ArrayAdapter(
            this, R.layout.spinner_item, modes.map { it.label }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        updateModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                if (position !in modes.indices) return
                val want = modes[position]
                if (want == UpdateManager.promptMode(this@AboutActivity)) return
                UpdateManager.setPromptMode(this@AboutActivity, want)
                refreshUpdateMode()
                Toast.makeText(
                    this@AboutActivity, "更新提示方式：${want.label}", Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        refreshUpdateMode()
        refreshKeepSwitches()
        // ---- 备注时间制式：下拉框（24 小时制 / 12 小时制）----
        timeFormatSpinner.adapter = ArrayAdapter(
            this, R.layout.spinner_item, timeFormatOptions
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        timeFormatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!formatSpinnerReady) return
                if (position !in timeFormatOptions.indices) return
                val want24 = position == 0
                if (want24 == TimeFormatPref.is24Hour(this@AboutActivity)) return
                TimeFormatPref.set24Hour(this@AboutActivity, want24)
                refreshTimeFormat()
                Toast.makeText(
                    this@AboutActivity, "备注时间：${timeFormatOptions[position]}", Toast.LENGTH_SHORT
                ).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        refreshTimeFormat()
        // 适配器铺好后的默认选中回调跑完再允许响应，避免覆盖已保存的设置
        updateModeSpinner.post { spinnerReady = true }
        timeFormatSpinner.post { formatSpinnerReady = true }
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

    /** 跳到本应用的应用信息页（系统「锁屏通知 / 静默通知」开关就在这一页里）。 */
    private fun openLockScreenNotifySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "打不开系统设置呢，请在手机「设置 → 应用 → 倒计时」里找「锁屏通知」哦", Toast.LENGTH_LONG).show()
        }
    }

    /** 引导关闭电池优化（不关的话系统会在后台限制网络与定时检查）。 */
    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "系统版本比较旧，这个不用设置哦", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
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

    /** 下拉框回填 + 下方说明：当前选中的用 ● 标出。 */
    private fun refreshUpdateMode() {
        if (!::updateModeSpinner.isInitialized) return
        val m = UpdateManager.promptMode(this)
        updateModeSpinner.setSelection(m.ordinal)
        // 三种更新提示方式各自的行为含义
        val sb = StringBuilder()
        sb.append("更新提示方式（点上面的下拉框选择哦）：\n")
        for (mode in UpdateManager.UpdatePromptMode.values()) {
            val mark = if (mode == m) "● " else "○ "
            sb.append(mark).append(mode.label).append("：").append(mode.desc).append("\n")
        }
        if (m == UpdateManager.UpdatePromptMode.AUTO && UpdateManager.hasSuspiciousAccessibility(this)) {
            sb.append("（已检测到弹窗拦截类工具，自动模式当前按「只发通知」执行）")
        }
        updateModeDescTv.text = sb.toString().trimEnd()
    }

    /** 回填「备注时间制式」下拉框 + 下方两种制式各自的效果示例。 */
    private fun refreshTimeFormat() {
        if (!::timeFormatSpinner.isInitialized) return
        formatSpinnerReady = false
        timeFormatSpinner.setSelection(if (TimeFormatPref.is24Hour(this)) 0 else 1)
        formatSpinnerReady = true
        timeFormatDescTv.text =
            "倒计时备注里的时间按这里的制式显示（列表与悬浮窗都会跟着变）：\n" +
                "24 小时制 → 距离17点整结束 / 距离10月2日0点整结束\n" +
                "12 小时制 → 距离下午5点整结束 / 距离10月2日凌晨12点整结束"
    }

    /** 刷新「后台检查 / 通知权限」状态行。 */
    private fun refreshState() {
        val perm = if (UpdateNotifier.hasPermission(this)) "通知权限：已经开啦" else "通知权限：还没开"
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) "电池优化：已经关掉啦" else "电池优化：还没关"
        } else ""
        checkStateTv.text = listOf(UpdateCheckState.summary(this), perm, battery)
            .filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** 按偏好回填屏幕常亮 / 锁屏通知开关（程序化设置时抑制回调）。 */
    private fun refreshKeepSwitches() {
        if (!::uiKeepSwitch.isInitialized) return
        suppressSwitch = true
        uiKeepSwitch.isChecked = ScreenKeepOn.isUiOn(this)
        floatKeepSwitch.isChecked = ScreenKeepOn.isFloatOn(this)
        lockNotifySwitch.isChecked = LockScreenClock.isOn(this)
        lockKeepSwitch.isChecked = LockKeepOn.isOn(this)
        suppressSwitch = false
        refreshLockKeepDesc()
        lockNotifyDescTv.text =
            "锁屏通知显示：开启后，列表里「展开」过的倒计时会以常驻通知显示在通知栏 / 锁屏上\n" +
                "（标题、倒计时数字、模式、备注和悬浮窗里一模一样，颜色也跟着主题走，每秒跳秒）；\n" +
                "把开关关掉，这些锁屏通知就一起收掉啦。\n" +
                "锁屏上看不到的活，点下面的「锁屏上看不到？点我打开锁屏通知设置」就行啦。"
        refreshLockState()
    }

    /** 锁屏通知的实时状态：开关状态 + 当前有几条在走秒 + 刷新服务是不是在跑。 */
    private fun refreshLockState() {
        if (!::lockStateTv.isInitialized) return
        val on = LockScreenClock.isOn(this)
        val live = try { CountdownStore.load(this).count { it.isVisible } } catch (_: Throwable) { 0 }
        val svc = isCountdownServiceRunning()
        val sb = StringBuilder()
        sb.append("锁屏通知：").append(if (on) "已开启" else "已关闭")
        sb.append(" · 通知栏当前 ").append(LockScreenClock.activeCount()).append(" 条在走秒")
        sb.append("\n")
        sb.append("展开中的倒计时 ").append(live).append(" 条 · 刷新服务：")
        sb.append(if (svc) "运行中（锁屏后靠它跳秒）" else "没在跑（回主界面会自动拉起）")
        if (on && live == 0) {
            sb.append("\n（还没展开过任何倒计时，先在列表里把要看的那条「展开」一下）")
        }
        lockStateTv.text = sb.toString()
    }

    /** 「锁屏通知常亮」开关的说明 + 当前到底接上没有。 */
    private fun refreshLockKeepDesc() {
        if (!::lockKeepDescTv.isInitialized) return
        val on = LockKeepOn.isOn(this)
        val holding = LockKeepOn.isHolding()
        val pageOn = LockKeepOn.isActivityOn()
        val perm = canOverlayForKeep()
        val sb = StringBuilder()
        sb.append("锁屏通知常亮：打开后，只要锁屏 / 通知栏上还挂着倒计时，屏幕就一直亮着、\n")
        sb.append("锁屏界面上也能一直瞄那个倒计时，不会按手机的熄屏时间睡去；\n")
        sb.append("关掉就立刻恢复手机里设的熄屏时间（想省电随手关掉就行；按电源键仍可主动关屏）。\n")
        sb.append("做法：在锁屏之上挂一个全透明的常亮页（系统闹钟也是这么干的），\n")
        sb.append("它不抢焦点、不拦触摸，锁屏照常能划能点；摸到它身上会自动退开，绝不会挡着你解锁。\n")
        sb.append("（没展开任何倒计时、或者把「锁屏通知显示」关掉时，这个开关不会亮屏，不会白耗电哦）\n")
        when {
            !on -> sb.append("当前：已关掉，屏幕熄屏时间跟随手机系统设置")
            !perm -> sb.append("当前：还差「显示在其他应用上层」权限，点上面的开关按提示开一下就好啦")
            pageOn -> sb.append("当前：常亮已接上（锁屏界面上也会一直亮着哦）")
            holding -> sb.append("当前：已开始接管，锁屏后屏幕一亮就会一直亮着")
            else -> sb.append("当前：还没接上（锁屏上还没有倒计时通知，通知一挂出来就会自动常亮）")
        }
        lockKeepDescTv.text = sb.toString()
    }

    /** 有没有「显示在其他应用上层」权限（锁屏常亮页要靠它才能在后台挂上锁屏）。 */
    private fun canOverlayForKeep(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /** 缺权限时给个明白话 + 一键跳过去打开。 */
    private fun askOverlayForKeep() {
        AlertDialog.Builder(this)
            .setTitle("还差一个权限呀")
            .setMessage(
                "想让它锁屏上也一直亮着，需要「显示在其他应用上层」这个权限哦" +
                    "（系统闹钟能在锁屏上常亮，靠的也是它）。\n\n" +
                    "点「去设置看看」打开开关，返回后它会立刻接上；不想现在弄也没关系，" +
                    "开关会一直开着，等权限给上了自动生效。"
            )
            .setPositiveButton("去设置看看") { _, _ ->
                try {
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ),
                        REQ_OVERLAY
                    )
                } catch (_: Throwable) {
                    Toast.makeText(
                        this,
                        "打不开系统设置呢，请在「设置 → 应用 → 倒计时」里打开「显示在其他应用上层」哦",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("等会儿", null)
            .show()
    }

    /** 从权限页回来：立刻按新权限重试一次，并刷新状态与说明。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OVERLAY) return
        LockKeepOn.apply(this)
        refreshKeepSwitches()
    }

    /** 倒计时刷新服务是不是在跑（锁屏通知与悬浮窗都由它驱动）。 */
    private fun isCountdownServiceRunning(): Boolean {
        return try {
            val mgr = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            mgr.getRunningServices(Int.MAX_VALUE)
                .any { CountdownService::class.java.name == it.service.className }
        } catch (_: Throwable) {
            false
        }
    }

    /** 锁屏通知开着但刷新服务没跑时，把服务拉起来（退到后台也要有人每秒更新锁屏通知）。 */
    private fun startCountdownServiceIfStopped() {
        try {
            if (isCountdownServiceRunning()) return
            val i = Intent(this, CountdownService::class.java)
            i.action = CountdownService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Throwable) {
            // 服务拉不起来也不该卡住设置：下次进主界面会自动补上
        }
    }

    /** 悬浮框常亮设置变化后，通知正在运行的服务重建悬浮窗以套用新的常亮标志。 */
    private fun refreshFloatingKeepOn() {
        try {
            val mgr = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val running = mgr.getRunningServices(Int.MAX_VALUE).any {
                CountdownService::class.java.name == it.service.className
            }
            if (!running) return   // 没在显示悬浮窗，无需处理
            val i = Intent(this, CountdownService::class.java)
            i.action = CountdownService.ACTION_REFRESH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Throwable) {
            // 服务未运行 / 权限不足时忽略即可
        }
    }

    override fun onResume() {
        super.onResume()
        // 屏幕常亮（UI 界面）：进入本页也按开关状态决定是否常亮
        ScreenKeepOn.apply(this)
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
        refreshUpdateMode()
        refreshKeepSwitches()
        refreshTimeFormat()
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
