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
    private lateinit var screenKeepBtn: Button

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
    }



    /** 刷新「屏幕常亮」开关按钮文案。 */
    private fun refreshScreenKeep() {
        if (!::screenKeepBtn.isInitialized) return
        val on = ScreenKeepOn.isOn(this)
        screenKeepBtn.text = if (on) "屏幕常亮：开啦" else "屏幕常亮：关啦"
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
