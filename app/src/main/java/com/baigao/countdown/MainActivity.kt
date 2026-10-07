package com.baigao.countdown

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var scrollView: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var dragLayer: FrameLayout
    private var data = mutableListOf<Countdown>()
    private var overlayDialog: AlertDialog? = null
    private var dragInfo: DragInfo? = null

    /** 已被用户删除的内置倒计时类型（存 SharedPreferences，避免下次启动又被自动补齐）。 */
    private lateinit var removedPrefs: SharedPreferences
    private val removedBuiltIns = mutableSetOf<Int>()

    /** 内置倒计时的「自定义排序」快照（用户长按拖过之后记住；没拖过就是空，走默认顺序）。 */
    private lateinit var orderPrefs: SharedPreferences

    /**
     * 十一个内置小倒计时的出厂顺序：每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 →
     * 当日 → 每周 → 当月 → 华都云境悦府 → GTA6 → 100年内倒计时（越靠前走得越少）。
     * 这份常量永远不动：用户拖出来的自定义顺序另存一份（见 orderPrefs），
     * 所以「自定义排序」怎么拖都不会污染默认顺序 —— 想回到出厂顺序，
     * 在「找回小内置」里点「主界面默认排序」即可。
     */
    private val defaultBuiltInOrder = listOf(
        BuiltIn.MINUTE, BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN, BuiltIn.HALF_HOUR, BuiltIn.HOUR,
        BuiltIn.DAY, BuiltIn.WEEK, BuiltIn.MONTH, BuiltIn.HUADU, BuiltIn.GTA6, BuiltIn.CENTURY
    )

    /**
     * 内置项的名字 / 默认备注，按类型索引（顺序单独由 defaultBuiltInOrder 管）。
     * 名字一律取 BuiltIn.nameOf() —— 卡片、下拉框、恢复内置对话框三处不会各写一份编错。
     */
    private val builtInInfo = listOf(
        BuiltIn.MINUTE to "距离本分钟结束",
        BuiltIn.FIVE_MIN to "距离本5分钟结束",
        BuiltIn.TEN_MIN to "距离本10分钟结束",
        BuiltIn.HALF_HOUR to "距离本半小时结束",
        BuiltIn.HOUR to "距离本小时结束",
        BuiltIn.DAY to "距离今日结束",
        BuiltIn.WEEK to "距离本周结束",
        BuiltIn.MONTH to "距离本月结束",
        BuiltIn.HUADU to "距离华都云境悦府交付（2026-10-31 00:00）",
        BuiltIn.GTA6 to "距离 GTA6 发售（2026-11-19 08:00）",
        BuiltIn.CENTURY to "距离你挑的那个日子结束"
    ).associate { it.first to Pair(BuiltIn.nameOf(it.first), it.second) }

    // 多选删除
    private var multiSelectOn = false
    private val selectedIds = mutableSetOf<String>()
    private lateinit var selBar: View
    private lateinit var selCountTv: TextView

    // 扇形菜单相关
    private lateinit var addBtn: Button
    private lateinit var menuBackdrop: View
    private lateinit var menuItemAdd: View
    private lateinit var menuItemAbout: View
    private lateinit var menuItemRestore: View
    private lateinit var menuItemMedicine: View
    private var menuOpen = false

    /** 主界面每秒刷新一次，让列表里的倒计时数字实时跳动（风格与悬浮窗一致）。 */
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            // 内置倒计时（当日 / 当月）跨天、跨月后目标时间会变，需要整表重建；
            // 拖动排序过程中不重建，避免打断操作。
            if (refreshBuiltInTargets() && dragInfo == null) {
                CountdownStore.save(this@MainActivity, data)
                rebuildList()
                syncService()
            } else {
                // 一帧内共用一个 now，保证所有倒计时的秒数同时跳变（原先各行各自取时间，
                // 跨秒边界时彼此差 1 秒，看起来像不同步）
                val now = AlignedClock.now()
                for (i in 0 until listContainer.childCount) {
                    (listContainer.getChildAt(i) as? CountdownRow)?.run {
                        refreshTime(now)
                        // 内置项备注（如「距离17点整结束」）也要跟着整点/日期同步刷新
                        refreshRemark()
                    }
                }
                // 拖动中的浮层不在 listContainer 里，单独刷新，保证手上的卡片也在走秒
                dragInfo?.ghost?.refreshTimeQuiet(now)
            }
            // 对齐到整秒：每次刷新都落在整秒之后 15ms，所有条目同一瞬间跳秒，
            // 也不会像固定 1000ms 定时那样累积漂移（漂移会造成停顿、跳 2 秒）
            tickHandler.postDelayed(this, millisToNextSecond())
        }
    }

    /** 接收服务的“数据已变化”广播（如悬浮窗内隐藏/显示），实时刷新列表。 */
    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadData()
            rebuildList()
        }
    }

    /** 读取本地数据，并确保十一个内置项（每10分钟 / 每5分钟 / 每分钟 / 每小时 / 每半小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6 / 100年内倒计时）始终存在。 */
    private fun loadData() {
        data = CountdownStore.load(this)
        if (ensureBuiltInTimers()) CountdownStore.save(this, data)
        // 内置小倒计时的顺序：拖过就按拖出来的顺序，没拖过就按出厂顺序（每分钟 → … → GTA6）
        applyBuiltInOrder()
    }

    /** 补齐全部内置倒计时；返回是否新建（新建后才需要落盘）。 */
    private fun ensureBuiltInTimers(): Boolean {
        // 新补的内置项一律插到列表最前面，所以按出厂顺序「倒着」补：
        // 最后一个 100年内倒计时 先插、第一个每分钟最后插，补完列表里内置项的顺序正好是
        // 每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6 → 100年内倒计时
        var added = false
        for (t in defaultBuiltInOrder.reversed()) {
            val info = builtInInfo[t] ?: continue
            if (ensureBuiltIn(t, info.first, info.second)) added = true
        }
        return added
    }

    /** 补齐单个内置倒计时；已存在则保留用户的位置、颜色、模式等配置。 */
    private fun ensureBuiltIn(type: Int, title: String, remark: String): Boolean {
        // 被用户删除过的内置项不再自动补齐，否则删完一重启又回来了
        if (type in removedBuiltIns) return false
        if (data.any { it.builtIn == type }) return false
        // 之前被删过：把删除时的设置整份还原（主题颜色、显示模式、跳秒动画、提示音、
        // 悬浮窗显隐 / 透明度 / 位置、备注等），只有目标时间由系统重新计算，
        // 于是恢复出来的内置项和删掉之前一模一样，只是重新开始计时。
        val backup = loadBuiltInBackup(type)
        val c = if (backup != null) {
            backup.builtIn = type
            if (backup.title.isBlank()) backup.title = title
            if (backup.remark.isBlank()) backup.remark = remark
            backup.finished = false      // 重新计时，允许再次响铃
            backup.builtInManual = false // 恢复出来的内置项回到系统周期（不再沿用用户指定的旧时刻）
            backup
        } else {
            Countdown(
                title = title,
                remark = remark,
                builtIn = type,
                // 当月 / 华都云境悦府 / GTA6 的「标准模式」就是「天时分秒模式」（与记录读取时的数据升级保持一致）
                displayMode = if (type in intArrayOf(BuiltIn.MONTH, BuiltIn.HUADU, BuiltIn.GTA6)) {
                    CountdownFormatter.MODE_DAY_HMS
                } else 0,
                isVisible = false   // 默认不强制弹出悬浮窗，可在列表中点「显示」
            )
        }
        c.refreshBuiltInTarget()
        data.add(0, c)
        return true
    }

    /** 刷新所有内置倒计时的目标时间，返回是否有变化。 */
    private fun refreshBuiltInTargets(): Boolean {
        var changed = false
        for (c in data) {
            if (c.refreshBuiltInTarget()) {
                c.finished = false // 进入新的周期，允许再次响铃
                changed = true
            }
        }
        return changed
    }

    companion object {
        const val REQ_OVERLAY = 1001
        const val REQ_EDIT = 1003
        const val REQ_NOTIFY = 1004
        const val REQ_SOUND = 1005  // 卡片提示音按钮：只挑提示音、选完即存
        /** 通知点击：进主界面后弹出更新日志。 */
        const val EXTRA_SHOW_UPDATE = "show_update"
        /** 通知上的「立即更新」：进主界面后直接下载安装。 */
        const val EXTRA_UPDATE_NOW = "update_now"
        /** 进程存活期间只自动检查一次更新，避免每次 onResume 都弹窗。 */
        private var updateCheckedOnce = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        removedPrefs = getSharedPreferences("builtin_removed", MODE_PRIVATE)
        removedBuiltIns.clear()
        orderPrefs = getSharedPreferences("builtin_order", MODE_PRIVATE)

        // 多选删除那条横幅：选中几项、一起收起、退出多选
        selBar = findViewById(R.id.selectBar)
        selCountTv = findViewById(R.id.selCountTv)
        findViewById<View>(R.id.selOkBtn).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.selCancelBtn).setOnClickListener { exitMultiSelect() }
        findViewById<View>(R.id.selAllBtn).setOnClickListener { selectAllRows() }
        findViewById<View>(R.id.selNoneBtn).setOnClickListener { clearAllRows() }
        removedPrefs.getStringSet("types", emptySet())?.forEach { removedBuiltIns.add(it.toInt()) }

        scrollView = findViewById(R.id.scroll)
        listContainer = findViewById(R.id.listContainer)
        dragLayer = findViewById(R.id.dragLayer)

        // 扇形菜单：加号 -> 弹出“添加倒计时 / 吃药提醒 / 关于”；点空白收起
        addBtn = findViewById(R.id.addBtn)
        menuBackdrop = findViewById(R.id.menuBackdrop)
        menuItemAdd = findViewById(R.id.menuItemAdd)
        menuItemAbout = findViewById(R.id.menuItemAbout)
        menuItemRestore = findViewById(R.id.menuItemRestore)
        menuItemMedicine = findViewById(R.id.menuItemMedicine)

        addBtn.setOnClickListener { toggleMenu() }
        menuBackdrop.setOnClickListener { closeMenu() }
        menuItemAdd.setOnClickListener {
            if (!menuOpen) return@setOnClickListener
            closeMenu()
            startActivityForResult(Intent(this, AddEditActivity::class.java), REQ_EDIT)
        }
        menuItemAbout.setOnClickListener {
            if (!menuOpen) return@setOnClickListener
            closeMenu()
            startActivity(Intent(this, AboutActivity::class.java))
        }
        menuItemRestore.setOnClickListener {
            if (!menuOpen) return@setOnClickListener
            closeMenu()
            showRestoreBuiltInDialog()
        }

        menuItemMedicine.setOnClickListener {
            if (!menuOpen) return@setOnClickListener
            closeMenu()
            startActivity(Intent(this, MedicineReminderActivity::class.java))
        }

        // 挂上后台定期检查：App 不打开也能知道有新版本、收到通知。
        // 两条路并行 —— JobScheduler（系统统一调度、有网才跑，重启后自动恢复）
        // 与 AlarmManager（2 小时一次兜底），任一条跑通都会发通知。
        UpdateCheckJobService.schedule(this)
        UpdateCheckReceiver.schedule(this)
        // 每日吃药提醒：默认 09:00，开关与时间都在「吃药提醒」里改（点 + 号 → 吃药提醒）。
        // catchUp() = 三路冗余挂载 + 补发（今天该提醒的时刻已过却没响过，打开就立刻补一条）
        MedicineReminder.catchUp(this)
        // 启动即检查更新：发现新版本会强制弹出更新日志对话框，并发送一条系统通知
        handleUpdateIntent(intent)
        if (!updateCheckedOnce) {
            updateCheckedOnce = true
            // 首次申请通知权限时，权限弹窗还没走完，晚一点再检查，
            // 免得权限刚授权但通知判断仍按「未授权」跳过。
            val delay = if (ensureNotifyPermission()) 1500L else 0L
            window.decorView.postDelayed({
                if (!isFinishing) UpdateManager.check(this, forceDialog = true)
            }, delay)
        }
        // 首次安装或升级到新版本后，打开软件自动弹出一次免责声明（看过即记录，不再重复打扰）
        window.decorView.postDelayed({ DisclaimerManager.showOnLaunchIfNeeded(this) }, 300)
    }

    /**
     * Android 13+ 需要运行时授权才能发通知。
     * @return 是否刚刚发起了授权申请（此时更新检查应延后）。
     */
    private fun ensureNotifyPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY
        )
        return true
    }

    /**
     * 通知权限被拒后给一次明确引导：否则「收不到通知」会变成一个无解又无声的状态
     * （用户不知道自己拒过权限，应用也不知道该不该再弹）。
     */
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFY) return
        if (grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        showNotice(
            "需要先开个通知权限呀",
            "还没开通知权限呢，有新版本时就收不到系统通知啦。\n可以在系统设置里把本应用的通知权限打开哦。",
            "去设置看看"
        ) { UpdateNotifier.openSettings(this) }
    }

    /** 一行文字的提示对话框（风格与「关于」页一致）。 */
    private fun showNotice(title: String, message: String, positive: String, onOk: () -> Unit) {
        UpdateManager.showStyledDialog(this, title, positive, "先不了", true, onOk) { host ->
            val tv = TextView(this)
            tv.text = message
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.setPadding(dp(4), dp(6), dp(4), dp(6))
            host.addView(tv)
        }
    }

    /** 处理从更新通知进来的意图：弹更新日志 / 直接下载安装。 */
    private fun handleUpdateIntent(i: Intent?) {
        val show = i?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) ?: false
        val now = i?.getBooleanExtra(EXTRA_UPDATE_NOW, false) ?: false
        if (!show && !now) return
        i?.removeExtra(EXTRA_SHOW_UPDATE)
        i?.removeExtra(EXTRA_UPDATE_NOW)
        updateCheckedOnce = true
        // 用户是主动点通知进来的：无论如何都把更新提示页弹出来（不受拦截降级影响）
        UpdateManager.check(
            this, forceDialog = show, notify = false, forcePage = true
        ) { info ->
            if (info != null && now) UpdateManager.downloadAndInstall(this, info)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    // ---------------- 扇形弹出菜单 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toggleMenu() {
        if (menuOpen) closeMenu() else openMenu()
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        menuBackdrop.visibility = View.VISIBLE
        menuBackdrop.alpha = 0f
        menuBackdrop.animate().alpha(1f).setDuration(160).start()

        // 扇形菜单（speed-dial 风格）：四个项都是 40dp 青色玻璃圆钮，锚定在右下角「+」按钮处，
        // 打开时沿同一半径(R=115dp)的弧线、按 15°→93° 均匀张成扇形，紧紧贴着「+」按钮四周散开（贴近包围）。
        // 圆钮更小（40dp）所以能把半径收到 115dp，四项真正紧凑地贴近加号四周排成扇形，互不重叠；
        // 「找回小内置」是条件项（仅当确有内置不在列表时出现）。
        // 角度映射：About(正上偏左15°) → Medicine(41°) → Add(67°) → Restore(水平左93°，条件项)
        animateItemOut(menuItemAbout, -dp(38), -dp(119), 130)
        animateItemOut(menuItemMedicine, -dp(83), -dp(95), 65)
        animateItemOut(menuItemAdd, -dp(114), -dp(53), 0)
        // 「恢复内置」只在确实有内置倒计时不在列表里时才出现；
        // 四个都在列表时整项隐藏，点了加号也不会出现这一项。
        if (missingBuiltIns().isNotEmpty()) {
            animateItemOut(menuItemRestore, -dp(123), -dp(2), 195)
        } else {
            menuItemRestore.visibility = View.GONE
            menuItemRestore.alpha = 0f
        }
        addBtn.animate().rotation(45f).setDuration(200).start()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        menuBackdrop.animate().alpha(0f).setDuration(160)
            .withEndAction { menuBackdrop.visibility = View.GONE }.start()
        animateItemIn(menuItemAdd, 0)
        animateItemIn(menuItemMedicine, 65)
        animateItemIn(menuItemAbout, 130)
        animateItemIn(menuItemRestore, 195)
        addBtn.animate().rotation(0f).setDuration(200).start()
    }

    private fun animateItemOut(v: View, dx: Int, dy: Int, delay: Long) {
        v.visibility = View.VISIBLE
        v.animate().translationX(dx.toFloat()).translationY(dy.toFloat())
            .alpha(1f).setStartDelay(delay).setDuration(220).start()
    }

    private fun animateItemIn(v: View, delay: Long) {
        v.animate().translationX(0f).translationY(0f).alpha(0f)
            .setStartDelay(delay).setDuration(220)
            .withEndAction { v.visibility = View.INVISIBLE }.start()
    }

    override fun onResume() {
        super.onResume()
        // 屏幕常亮开关：开启时本应用在前台保持屏幕常亮不锁屏
        ScreenKeepOn.apply(this)
        // 从「允许安装未知应用」设置页返回后，继续之前挂起的安装
        UpdateManager.consumePendingInstall(this)
        // 已经进到 App 了，清掉通知栏上的更新提醒
        UpdateNotifier.cancel(this)
        loadData()
        rebuildList()
        try {
            registerReceiver(dataChangedReceiver, IntentFilter(CountdownService.ACTION_DATA_CHANGED))
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "registerReceiver: ${e.message}")
        }
        tickHandler.post(tickRunnable)
        // 首次使用（或权限缺失且存在可见倒计时时）引导开启悬浮窗权限；
        // 无论有没有悬浮窗权限都要走一遍 syncService：锁屏通知不受悬浮窗权限限制，也得照常维护。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this) && data.any { it.isVisible }
        ) {
            assistOverlayPermission()
        }
        syncService()
        // 回到前台也补一次：漏掉的吃药提醒立刻补发（同一天只会发一次）
        MedicineReminder.catchUp(this)
        // 通知收不到时，用户往往根本不知道是哪一环被关了（权限 / 渠道 / 后台限制都是静默失败）。
        // 进 App 就悄悄查一次，有问题才弹，且一天最多一次、累计最多三次。
        NotifyGuard.warnIfBlocked(this)
    }

    override fun onPause() {
        try {
            unregisterReceiver(dataChangedReceiver)
        } catch (e: Throwable) {
            // 未注册时忽略
        }
        tickHandler.removeCallbacks(tickRunnable)
        // 离开前台就清掉常亮 Flag，避免关掉开关后 Flag 残留在已暂停的窗口上
        ScreenKeepOn.onPause(this)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val mgr = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (s in mgr.getRunningServices(Int.MAX_VALUE)) {
            if (CountdownService::class.java.name == s.service.className) return true
        }
        return false
    }

    /** 首次使用协助：引导用户开启“显示在其他应用上层”权限。 */
    private fun assistOverlayPermission() {
        if (overlayDialog?.isShowing == true) return
        overlayDialog = AlertDialog.Builder(this)
            .setTitle("需要先开「显示在其他应用上层」这个权限呀")
            .setMessage("悬浮窗需要「显示在其他应用上层」这个权限，才能在屏幕最上层显示哦。\n\n请点「去设置看看」，在列表里找到本应用「倒计时」打开开关，返回就会自动显示悬浮窗啦。")
            .setPositiveButton("去设置看看") { _, _ ->
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(i, REQ_OVERLAY)
            }
            .setNegativeButton("等会儿", null)
            .show()
    }

    /**
     * 按当前数据自动管理悬浮窗服务（无需手动启动/停止按钮）：
     * - 有权限且有可见倒计时 → 未运行则启动，已运行则刷新最新数据；
     * - 只开着「锁屏通知显示」、没有可见倒计时 → 服务也得起，否则退到后台没人每秒刷新锁屏通知；
     * - 两者都没有 → 停止服务，避免常驻通知。
     *
     * 悬浮窗服务顺带负责刷新锁屏通知（都在同一个 tick 里），所以这里只要把服务拉起来就行。
     *
     * @param force 添加 / 编辑返回时传 true：不等「有没有悬浮窗权限」这一关也直接推一次刷新
     *               —— 服务里的 rebuildFloaters() 自己会判 canDrawOverlay()，不会白建窗；
     *               这样新加的可见倒计时在保存返回这一瞬间就出窗，改过的条目当场刷新。
     */
    private fun syncService(force: Boolean = false) {
        val hasVisible = data.any { it.isVisible }
        val lockOn = LockScreenClock.isOn(this)
        if (!hasVisible && !lockOn) {
            if (isServiceRunning()) stopService(Intent(this, CountdownService::class.java))
            return
        }
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        // 悬浮窗缺权限会先引导，但**锁屏通知不吃悬浮窗权限**：
        // 没悬浮窗权限 + 有可见倒计时 + 锁屏开关开 → 服务照样得起，锁屏那边照常走秒。
        if (hasVisible && !canOverlay && !lockOn) return
        if (!isServiceRunning()) {
            val i = Intent(this, CountdownService::class.java)
            i.action = CountdownService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } else if (force || (hasVisible && canOverlay)) {
            val ri = Intent(this, CountdownService::class.java)
            ri.action = CountdownService.ACTION_REFRESH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(ri) else startService(ri)
        }
        // 不管走哪条路都补刷一次锁屏通知（幂等，下一跳会重新对齐整秒）
        try {
            LockScreenClock.updateAll(this, data)
        } catch (_: Throwable) {
            // 通知发不出去也不该卡住主界面
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                syncService()
            }
        } else if (requestCode == REQ_SOUND) {
            // 提示音选完即存：数据、列表、悬浮窗 / 锁屏三样一起跟上
            loadData()
            rebuildList()
            syncService(true)
        } else if (requestCode == REQ_EDIT) {
            loadData()
            rebuildList()
            // 保存返回就把最新数据推给悬浮窗 / 锁屏：新窗当场建好、当场刷最新数字，不等下一跳
            syncService(true)
        }
    }

    /** 用数据重建整个列表（数据变化/进入编辑返回后调用；每秒刷新仅更新时间文本，不重建）。 */
    fun rebuildList() {
        listContainer.removeAllViews()
        for (c in data) {
            val row = CountdownRow(this)
            row.bind(c)
            listContainer.addView(row)
        }
    }

    // ---------------- 拖动排序编排 ----------------

    data class DragInfo(
        val c: Countdown,
        var fromIndex: Int,
        var targetIndex: Int,
        /** 跟随手指的「浮层」：内容与原条目完全相同的整层卡片。 */
        val ghost: CountdownRow,
        val grabOffsetY: Int,
        val row: CountdownRow
    )

    /**
     * 长按启动拖动：整层跟随手指。
     *
     * 以前是「对条目截图 + 原地留一个虚化副本」，结果看起来条目被劈成了两层。
     * 现在改为新建一个内容完全相同的 CountdownRow 放到最上层的 dragLayer 上，
     * 原条目转为不可见但仍占位（列表不跳动）——手指上的就是完整的一层卡片，
     * 而且它是真实视图、仍在走秒，不是一张静止的截图。
     *
     * 注意：浮层必须在 ACTION_DOWN 之后才加入。ViewGroup 的触摸目标在 DOWN 时就已确定，
     * 之后再加 View 不会抢走原条目正在进行的触摸序列（不会因为 removeView 被 ACTION_CANCEL 打断）。
     */
    fun beginDrag(row: CountdownRow, c: Countdown, pointerY: Int) {
        try {
            row.front.translationX = 0f // 还原横向滑动，保证浮层从正位出发
            row.setActionsRevealed(false) // 拖动时底层按钮不参与绘制
            val w = row.width
            val h = row.height
            if (w <= 0 || h <= 0) return

            val layerLoc = IntArray(2)
            dragLayer.getLocationOnScreen(layerLoc)
            val rowLoc = IntArray(2)
            row.getLocationOnScreen(rowLoc)

            val ghost = CountdownRow(this)
            ghost.bind(c)
            dragLayer.removeAllViews()
            dragLayer.addView(ghost, FrameLayout.LayoutParams(w, h))
            dragLayer.visibility = View.VISIBLE
            // x/y 用相对 dragLayer 的坐标（dragLayer 原点未必是屏幕原点）
            ghost.x = (rowLoc[0] - layerLoc[0]).toFloat()
            ghost.y = (rowLoc[1] - layerLoc[1]).toFloat()
            ghost.alpha = 0.96f
            ghost.scaleX = 1.03f
            ghost.scaleY = 1.03f

            row.setDragging(true) // 原位隐藏但仍占位
            val grabOffsetY = pointerY - rowLoc[1]
            val fromIndex = data.indexOfFirst { it.id == c.id }
            dragInfo = DragInfo(c, fromIndex, fromIndex, ghost, grabOffsetY, row)
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "beginDrag: ${e.message}")
            dragInfo = null
        }
    }

    /** 拖动中：移动浮层 + 计算落点；松手时按落点重排。 */
    fun dragMove(pointerY: Int) {
        val info = dragInfo ?: return
        try {
            val layerLoc = IntArray(2)
            dragLayer.getLocationOnScreen(layerLoc)
            info.ghost.y = (pointerY - layerLoc[1] - info.grabOffsetY).toFloat()

            // 接近列表上/下边缘时自动滚动
            val svLoc = IntArray(2)
            scrollView.getLocationOnScreen(svLoc)
            val top = svLoc[1]
            val bottom = top + scrollView.height
            if (pointerY < top + 80) scrollView.scrollBy(0, -24)
            else if (pointerY > bottom - 80) scrollView.scrollBy(0, 24)

            // 计算落点 index（按各行中线）
            val contLoc = IntArray(2)
            listContainer.getLocationOnScreen(contLoc)
            val relY = pointerY - contLoc[1]
            var target = 0
            for (i in 0 until listContainer.childCount) {
                val child = listContainer.getChildAt(i) ?: continue
                val center = child.top + child.height / 2
                if (relY > center) target = i + 1
            }
            target = target.coerceIn(0, data.size - 1)
            info.targetIndex = target
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "dragMove: ${e.message}")
        }
    }

    /** 松手：按落点把拖动项移动到目标位置并落盘。 */
    fun endDrag() {
        val info = dragInfo ?: return
        try {
            dragLayer.removeAllViews()
            dragLayer.visibility = View.GONE

            val from = info.fromIndex
            val to = info.targetIndex.coerceIn(0, maxOf(0, data.size - 1))
            if (from in data.indices && from != to) {
                val removed = data.removeAt(from)
                val insertAt = if (to > from) to - 1 else to
                data.add(insertAt.coerceIn(0, data.size), removed)
            }
            // 拖的是内置项：把拖完的内置顺序记成「自定义排序」，
            // 这样下次打开还是这个顺序，而出厂顺序（defaultBuiltInOrder）一个字没动
            if (info.c.isBuiltIn()) saveCustomBuiltInOrder()
            // 拖的只是普通倒计时：内置顺序照旧（applyBuiltInOrder 里比对着，不会白动）
            applyBuiltInOrder()
            CountdownStore.save(this, data)
            // 关键：触摸事件派发过程中改动视图树（removeAllViews/addView）会让
            // 框架层在遍历子 View 时崩溃。故延到下一帧再重建列表。
            info.row.setDragging(false)
            info.row.resetDragState()
            listContainer.post { rebuildList() }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "endDrag: ${e.message}")
        }
        dragInfo = null
    }

    // ---------------- 各按钮动作（供列表项回调） ----------------

    fun onShowToggle(c: Countdown) {
        c.isVisible = !c.isVisible
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }

    fun onModeCycle(c: Countdown) {
        // 只剩一种显示模式时（例如每分钟），切换按钮已经收起来了，点它什么都不做
        if (!CountdownFormatter.hasModeSwitch(c.builtIn)) return
        // 只在「该条目可用的模式」里循环：当日倒计时跳过已下线的天数模式
        val modes = CountdownFormatter.availableModes(c.builtIn)
        val i = modes.indexOf(c.displayMode)
        c.displayMode = modes[((i + 1) % modes.size + modes.size) % modes.size]
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }

    /** 列表上点「动画」按钮：循环切换该倒计时的跳秒动画效果。 */
    fun onAnimCycle(c: Countdown) {
        val n = AnimStyle.NAMES.size
        c.animStyle = ((c.animStyle + 1) % n + n) % n
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }

    /**
     * 点「编辑」：内置项先弹一句说明。
     *
     * @param row 被点开的这一行（左滑操作层上的按钮才会传），提示框关掉时它要自己滑回原位。
     */
    fun onEdit(c: Countdown, row: CountdownRow? = null) {
        if (c.isBuiltIn()) {
            // 内置倒计时也能改（v1.0.0.9 起），只是先问一句：点「继续」才进编辑页。
            showBuiltInNotice(
                title = "要改内置小倒计时吗",
                message = "「${c.title}」是系统自带的小内置倒计时，名字、颜色、显示模式、跳秒动画、提示音、备注、时刻都能照常改。\n改完它还是内置倒计时，只是开始按你选的那个时刻走，不再自己跳到下一个整点。\n点「继续」就进编辑页，想放一放就点「先不了」。",
                okText = "继续",
                onOk = { openEditor(c) },
                swipeRow = row
            )
        } else openEditor(c)
    }

    /** 点卡片上的提示音名称：直接开系统铃声选择器（只有普通倒计时和「固定目标」内置项才有这一颗）。 */
    fun onSound(c: Countdown) {
        val i = Intent(this, AddEditActivity::class.java)
        i.putExtra("id", c.id)
        i.putExtra("pickSoundOnly", true)
        startActivityForResult(i, REQ_SOUND)
    }

    /** 真正拉起编辑页（内置项会先弹上面的提示）。 */
    private fun openEditor(c: Countdown) {
        val i = Intent(this, AddEditActivity::class.java)
        i.putExtra("id", c.id)
        startActivityForResult(i, REQ_EDIT)
    }


    /**
     * 点「删除」：内置项先弹一句说明，普通项弹系统确认框。
     *
     * @param row 被点开的这一行（左滑操作层上的按钮才会传），确认框关掉时它要自己滑回原位。
     */
    fun onDelete(c: Countdown, row: CountdownRow? = null) {
        // 内置倒计时同样可以删除（v1.0.0.9 起），但删之前先跟用户说清楚：
        // 它一直是内置倒计时，收回列表后随时能「找回小内置」原样回来，点「继续」才收。
        if (c.isBuiltIn()) {
            showBuiltInNotice(
                title = "要收起内置小倒计时吗",
                message = "「${c.title}」是系统自带的小内置倒计时，收起来只是暂时从列表里拿掉，它的内置身份一直都在。\n收起前会把你配好的东西（主题颜色、显示模式、跳秒动画、提示音、悬浮窗显隐这些）一起存成快照，之后点加号菜单里的「找回小内置」就能原样找回来。\n点「继续」就收起它，舍不得就点「先留着」。",
                okText = "继续",
                onOk = { removeBuiltInEntry(c) },
                swipeRow = row
            )
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("要和小倒计时说拜拜吗")
            .setMessage("要把「${c.title}」这个小倒计时收起来吗？它会舍不得你呢～\n（收起后就不显示在列表里啦）")
            .setPositiveButton("好呀，收起") { _, _ -> removeEntry(c) }
            .setNegativeButton("先留着", null)
            .show()
        // 左滑点出来的删除框：点了「先留着」/按返回/点框外，这一行就滑回原位
        if (row != null) dlg.setOnDismissListener { row.closeIfOpen() }
    }

    /** 普通倒计时的删除（用户自己建的，无需额外确认）。 */
    private fun removeEntry(c: Countdown) {
        removeEntryQuiet(c)
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }

    /** 只从内存里划掉一条，不落盘、不刷新（批量删完一次搞定，避免逐个重建列表）。 */
    private fun removeEntryQuiet(c: Countdown) {
        data.removeAll { it.id == c.id }
    }

    /** 内置倒计时的删除：存快照 + 记下类型不再自动补齐，之后「找回小内置」可原样还原。 */
    private fun removeBuiltInEntry(c: Countdown) {
        removeBuiltInEntryQuiet(c)
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }

    /** 内置项收起（不落盘不刷新）：给批量删除用。 */
    private fun removeBuiltInEntryQuiet(c: Countdown) {
        saveBuiltInBackup(c)
        markBuiltInRemoved(c.builtIn)
        removeEntryQuiet(c)
    }

    /**
     * 内置倒计时「改 / 删」前的说明提示：与「关于」页、更新提示同一套玻璃风格，
     * 只有点了「继续」才继续往下走（见 onEdit / onDelete）。
     *
     * @param swipeRow 从「左滑露出的按钮」触发时才传这一行：提示框一关（点「先不了」/按返回/
     *                 点框外），这一行就自己滑回原位，不留着敞着的操作层挡在下面。
     */
    private fun showBuiltInNotice(
        title: String,
        message: String,
        okText: String,
        onOk: () -> Unit,
        swipeRow: CountdownRow? = null
    ): AlertDialog {
        val dialog = UpdateManager.showStyledDialog(
            activity = this,
            title = title,
            positiveText = okText,
            negativeText = "先不了",
            onPositive = onOk
        ) { host ->
            val tv = TextView(this)
            tv.text = message
            tv.setTextColor(Color.parseColor("#FFE4EEFF"))
            tv.textSize = 14f
            tv.setLineSpacing(0f, 1.45f)
            tv.setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
            tv.setPadding(dp(4), dp(6), dp(4), dp(6))
            host.addView(tv)
        }
        if (swipeRow != null) dialog.setOnDismissListener { swipeRow.closeIfOpen() }
        return dialog
    }

    // ---------------- 内置倒计时的删除 / 恢复 ----------------

    /**
     * 内置倒计时的默认定义（顺序即列表里的展示顺序 = defaultBuiltInOrder）。
     * 「找回小内置」 dialogues 与恢复对话框的清单都按它排，不另存一份顺序。
     */
    private val builtInDefs: List<Triple<Int, String, String>> = defaultBuiltInOrder.map { t ->
        val info = builtInInfo[t]
        Triple(t, info?.first ?: "", info?.second ?: "")
    }

    /**
     * 删除内置倒计时时，把它的完整设置存成快照（JSON 字符串，存在 builtin_removed 里）。
     *
     * ⚠️ v147：100年内倒计时从 v146 起能存在好几条（个数不限制），再共用一个
     * 「backup_{类型}」的坑位，后删的那条就会把先删的那份快照顶掉 —— 想找回前一条时发现
     * 压根没存过。所以这种多出来的类型按「backup_{类型}_{id}」一条一条存；
     * 只可能有单条的内置类型照旧，顺手把老 key 也写一份，老设备上的快照照样认。
     */
    private fun backupKey(type: Int, id: String): String =
        if (type == BuiltIn.CENTURY) "backup_${type}_${id}" else "backup_${type}"

    /**
     * 删除内置倒计时时，把它的完整设置存成快照（JSON 字符串，存在 builtin_removed 里）。
     * 只存「设置」，不存目标时间——目标时间由系统按当日 / 当月 / 固定日期重算。
     */
    private fun saveBuiltInBackup(c: Countdown) {
        val json = CountdownStore.toJson(c).toString()
        removedPrefs.edit()
            .putString(backupKey(c.builtIn, c.id), json)
            .apply()
        // 普通内置项永远只有一条，老 key 照写一份：
        // 老版本（以及备份读取时的回落）认的就是「backup_{类型}」这一份
        if (c.builtIn != BuiltIn.CENTURY) {
            removedPrefs.edit().putString("backup_${c.builtIn}", json).apply()
        }
    }

    /** 读出某个内置倒计时被删除时的设置快照；老版本删除的没有快照，返回 null。 */
    private fun loadBuiltInBackup(type: Int): Countdown? = loadBuiltInBackup(type, "")

    /**
     * 读出某条内置倒计时被删除时的设置快照。
     *
     * @param id 指定条目 id 就按「backup_{类型}_{id}」取（100年内倒计时能存多条，每条各存一份）；
     *           0 表示只认老式的「backup_{类型}」那一份。老版本删的没有分 key，回落一下也能读出来。
     */
    private fun loadBuiltInBackup(type: Int, id: String): Countdown? {
        val json = if (id.isNotEmpty()) {
            removedPrefs.getString("backup_${type}_$id", null)
                ?: removedPrefs.getString("backup_$type", null)
        } else {
            removedPrefs.getString("backup_$type", null)
        } ?: return null
        return try {
            CountdownStore.parse(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    /** 记录「某个内置倒计时已被删除」，写盘持久化。 */
    private fun markBuiltInRemoved(type: Int) {
        if (!removedBuiltIns.add(type)) return
        removedPrefs.edit()
            .putStringSet("types", removedBuiltIns.map { it.toString() }.toSet())
            .apply()
    }

    /** 恢复已被删除的内置倒计时（重新纳入自动补齐），可一次恢复多个。 */
    private fun restoreBuiltIn(vararg types: Int) {
        var changed = false
        for (t in types) {
            if (removedBuiltIns.remove(t)) changed = true
        }
        if (changed) {
            removedPrefs.edit()
                .putStringSet("types", removedBuiltIns.map { it.toString() }.toSet())
                .apply()
        }
        // ⚠️ v147：100年内倒计时能存好几条，光把「12 号类型」从已删除里划掉还不够 ——
        // 下面 ensureBuiltIn() 那句「这个类型已经在列表里了就别补」会把它们全挡在门外
        // （列表里还躺着另一条同类型 => 勾了「找回小内置」却一条都没回来）。
        // 所以这里改成按 id 一条条照快照还原：时长（builtInSpanMillis）、名称、备注、
        // 目标时刻连同主题 / 模式 / 动画一起回来，重启之后照旧按时长一轮一轮往下转。
        if (types.contains(BuiltIn.CENTURY)) {
            for (bak in centuryBackups()) {
                if (data.any { it.id == bak.id }) continue // 已经在列表里了，别重复造一条
                bak.finished = false          // 重新计时，允许再响一次铃
                bak.builtInManual = false     // 回到系统周期，不沿用过期的旧时刻
                data.add(0, bak)
                changed = true
            }
        }
        // 兜底：某些项可能既不在列表里、也没有被标记删除（例如被改成普通倒计时），
        // 这里统一补齐，保证点「确定」之后十个内置项真的回到列表里
        if (ensureBuiltInTimers()) changed = true
        applyBuiltInOrder()
        if (changed) CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }

    /**
     * 捞出所有被删掉的「100年内倒计时」的快照（v147：可能不止一条，按 id 分开存着）。
     *
     * 只认「backup_{类型}_{id}」这种新 key —— 老 key 底下那一份年代太久、也不一定对应
     * 现在想找回的这一条，交给 loadBuiltInBackup() 的老形式去兜底就好。
     */
    private fun centuryBackups(): List<Countdown> {
        val out = ArrayList<Countdown>()
        val seen = HashSet<String>() // ⚠️ Countdown.id 是 UUID 字符串，不是数字，别写成 HashSet<Long>
        for (key in removedPrefs.all.keys) {
            if (!key.startsWith("backup_${BuiltIn.CENTURY}_")) continue
            val json = removedPrefs.getString(key, null) ?: continue
            try {
                val c = CountdownStore.parse(JSONObject(json))
                if (c.id.isNotEmpty() && seen.add(c.id)) out.add(c)
            } catch (e: Exception) {
                // 存烂了的那一份直接跳过，别让整页恢复跟着炸
            }
        }
        return out
    }

    /**
     * 当前「不在列表里」的内置倒计时：
     * 既包括用户删掉的（记在 removedBuiltIns 里），也包括列表里查不到该类型的。
     * 为空表示十一个内置倒计时都在列表里，此时不需要显示「恢复内置」。
     */
    private fun missingBuiltIns(): List<Triple<Int, String, String>> =
        builtInDefs.filter { def ->
            val t = def.first
            t in removedBuiltIns
                || data.none { it.builtIn == t }
                // ⚠️ v147：100年内倒计时能存多条，删掉的那条未必是列表里唯一的一条 ——
                // 光看「这个类型还在不在」够不着，得按 id 看那条快照有没有躺在列表里
                || (t == BuiltIn.CENTURY && centuryBackups().any { bak -> data.none { it.id == bak.id } })
        }

    /**
     * 弹出「恢复内置倒计时」对话框（点加号菜单里的「恢复内置」）。
     *
     * 注意：这里不能用原生 AlertDialog 的 setMessage(...) + setMultiChoiceItems(...) ——
     * 系统实现里两者互斥：只要设了说明文字，选项列表就不会被加进视图，
     * 对话框里于是只剩一段话、一个可勾选项都没有（v1.0.0.13 / 14 就是这个毛病）。
     * 因此改成自定义内容：说明文字 + 自带勾选框的选项行，默认全部勾选，
     * 另有「全选 / 全不选」，想恢复哪几个就勾哪几个，最后点确定生效。
     */
    private fun showRestoreBuiltInDialog() {
        val missing = missingBuiltIns()
        if (missing.isEmpty()) {
            // v124：只有真把内置顺序拖过才摆复位入口。排序没动过时列表本来就是出厂顺序，
            // 这时候弹一个「十个都在 + 一颗回默认」的对话框，点了多半是白点。
            if (!builtInOrderChanged()) {
                Toast.makeText(
                    this,
                    "小内置现在排的就是出厂顺序，不用复位",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("小内置都在")
                .setMessage("十一个内置小倒计时都乖乖在列表里啦。\n想让它们回到出厂顺序（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6 → 100年内倒计时）就点「主界面默认排序」。")
                .setPositiveButton("主界面默认排序") { _, _ -> restoreDefaultOrder() }
                .setNegativeButton("先留着", null)
                .show()
            return
        }
        val checked = BooleanArray(missing.size) { true }
        val boxes = ArrayList<CheckBox>()

        UpdateManager.showStyledDialog(
            activity = this,
            title = "把小内置倒计时找回来",
            positiveText = "好呀，找回",
            negativeText = "先留着",
            onPositive = {
                val types = missing.filterIndexed { i, _ -> checked[i] }.map { it.first }
                if (types.isEmpty()) {
                    Toast.makeText(this, "还没勾选任何小内置倒计时呢", Toast.LENGTH_SHORT).show()
                } else {
                    val restored = types.count { t ->
                        if (t == BuiltIn.CENTURY) centuryBackups().isNotEmpty()
                        else loadBuiltInBackup(t) != null
                    }
                    restoreBuiltIn(*types.toIntArray())
                    val msg = if (restored > 0)
                        "已经把 ${types.size} 个小内置倒计时找回来啦（其中 $restored 个还原了原来的设置）"
                    else
                        "已经把 ${types.size} 个小内置倒计时找回来啦"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        ) { host ->
            host.addView(TextView(this).apply {
                text = "勾选想找回的小倒计时，确定后按删掉之前的设置（主题、模式、动画、提示音这些）还原哦。"
                setTextColor(Color.parseColor("#FFE4EEFF"))
                textSize = 13f
                setLineSpacing(0f, 1.3f)
                setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            // 「全选 / 全不选」快捷操作
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(8), 0, dp(2))
            }
            bar.addView(selectAllBtn("全选上") { boxes.forEach { it.isChecked = true } })
            bar.addView(selectAllBtn("全不选啦") { boxes.forEach { it.isChecked = false } })
            // v124：只有真把内置顺序拖过才摆这颗复位按钮，没拖过本来就走的出厂顺序
            if (builtInOrderChanged()) {
                bar.addView(selectAllBtn("主界面默认排序") { restoreDefaultOrder() })
            }
            host.addView(bar)

            for ((i, def) in missing.withIndex()) {
                val cb = CheckBox(this).apply {
                    isChecked = true
                    minWidth = 0
                    minimumWidth = 0
                    includeFontPadding = false
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FFFF"))
                    setOnCheckedChangeListener { _, b -> checked[i] = b }
                }
                boxes.add(cb)
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), 0, 0, 0)
                }
                texts.addView(TextView(this).apply {
                    text = def.second
                    setTextColor(Color.parseColor("#FFFFFFFF"))
                    textSize = 15f
                    setLineSpacing(0f, 1.15f)
                    setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                texts.addView(TextView(this).apply {
                    text = def.third
                    setTextColor(Color.parseColor("#FFC6D5EF"))
                    textSize = 12.5f
                    setLineSpacing(0f, 1.15f)
                    setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                    isClickable = true
                    addView(cb)
                    addView(texts, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener { cb.isChecked = !cb.isChecked }
                }
                host.addView(row)
            }
        }
    }

    /** 对话框里的「全选 / 全不选」文字按钮。 */
    private fun selectAllBtn(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#FF4DFBFF"))
            textSize = 13f
            setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // ---------------- 内置倒计时的默认排序 / 自定义排序 ----------------

    /** 用户拖出来的内置顺序（逗号串，空表示没拖过、走出厂顺序）。 */
    private fun customBuiltInOrder(): List<Int> =
        orderPrefs.getString("builtin_order", null)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()

    /**
     * 内置顺序有没有被用户自己动过（拖过内置项）。
     *
     * 拖出来的顺序跟出厂顺序一模一样也算「没动过」—— 那种情况下给一颗
     * 「回默认排序」的按钮纯属多余，摆着点了也不会有任何变化（v124）。
     */
    private fun builtInOrderChanged(): Boolean {
        val custom = customBuiltInOrder()
        return custom.isNotEmpty() && custom != defaultBuiltInOrder
    }

    /** 把「当前列表里内置项的前后顺序」记成自定义排序（拖完内置项时调用）。 */
    private fun saveCustomBuiltInOrder() {
        val types = data.mapNotNull { c -> if (c.isBuiltIn()) c.builtIn else null }
        orderPrefs.edit().putString("builtin_order", types.joinToString(",")).apply()
    }

    /** 目标顺序：拖出来的（自定义）排前面，出厂顺序把剩下的补齐。 */
    private fun mergedBuiltInOrder(): List<Int> {
        val m = ArrayList<Int>()
        for (t in customBuiltInOrder()) if (t in defaultBuiltInOrder && t !in m) m.add(t)
        for (t in defaultBuiltInOrder) if (t !in m) m.add(t)
        return m
    }

    /**
     * 把列表里的内置项按「自定义排序（没拖过就是出厂顺序）」排好。
     *
     * 只动内置项之间的先后，普通倒计时的位置一个都不挪：先记住内置项原来占的下标，
     * 把它们整段抠出来，再按同样下标插回去 —— 于是非内置项始终待在原位。
     * 顺序本来就对时直接返回（不做任何事），避免每次刷新都白白重排列表。
     */
    private fun applyBuiltInOrder() {
        val built = data.filter { it.isBuiltIn() }
        if (built.size < 2) return
        val want = ArrayList<Countdown>()
        for (t in mergedBuiltInOrder()) {
            val c = built.firstOrNull { it.builtIn == t }
            if (c != null && c !in want) want.add(c)
        }
        for (c in built) if (c !in want) want.add(c)
        if (want == built) return
        val slots = ArrayList<Int>()
        for (i in data.indices) if (data[i].isBuiltIn()) slots.add(i)
        val out = ArrayList<Countdown>(data)
        for (i in slots.reversed()) out.removeAt(i)
        for (k in slots.indices) out.add(slots[k], want[k])
        data.clear()
        data.addAll(out)
    }

    /** 推倒自定义排序、回到出厂顺序（每分钟 → … → GTA6）。 */
    private fun restoreDefaultOrder() {
        orderPrefs.edit().remove("builtin_order").apply()
        applyBuiltInOrder()
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
        // 回显真实结果：点了没变化就别让用户以为这颗按钮坏了
        val nowTypes = data.mapNotNull { if (it.isBuiltIn()) it.builtIn else null }
        val names = nowTypes.joinToString(" → ") { t -> builtInInfo[t]?.first ?: "$t" }
        val isDefault = nowTypes == defaultBuiltInOrder
        Toast.makeText(
            this,
            if (isDefault)
                "小内置已经回到出厂顺序（每分钟开头，GTA6 收尾）"
            else
                "小内置现在是：\n$names",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------------- 长按多选删除 ----------------

    /** 长按（按住不动）进入多选：这一条先勾上，顶部横幅亮出来，每行都露出勾选框。 */
    fun enterMultiSelect(c: Countdown) {
        if (dragInfo != null) return
        // 兜底：这条已经被收掉了（比如批量删完、或数据刚被别处换过）就别再勾它
        if (!data.any { it.id == c.id }) return
        multiSelectOn = true
        selectedIds.clear()
        selectedIds.add(c.id)
        showSelectBar()
        rebuildList()
    }

    /** 退出多选（点「取消」/ 勾空了 / 删完）。 */
    fun exitMultiSelect() {
        if (!multiSelectOn) return
        multiSelectOn = false
        selectedIds.clear()
        hideSelectBar()
        rebuildList()
    }

    /** v126：横幅上的「全选」——一次把列表里所有倒计时都勾上。 */
    private fun selectAllRows() {
        selectedIds.addAll(data.map { it.id })
        updateSelectBar()
        rebuildList()
    }

    /** v126：横幅上的「全不选」——全部取消勾，空手退出多选。 */
    private fun clearAllRows() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        exitMultiSelect()
    }

    /** 多选模式下点一下这一行 = 勾上 / 取消勾上。 */
    fun toggleSelect(c: Countdown) {
        if (!selectedIds.add(c.id)) selectedIds.remove(c.id)
        updateSelectBar()
        rebuildList()
        if (selectedIds.isEmpty()) exitMultiSelect()
    }

    private fun showSelectBar() {
        selBar.visibility = View.VISIBLE
        // v126：只有一条的时候「全选 / 全不选」没什么可切的，收起来免得点了个寂寞
        val multi = data.size > 1
        findViewById<View>(R.id.selAllBtn).visibility = if (multi) View.VISIBLE else View.GONE
        findViewById<View>(R.id.selNoneBtn).visibility = if (multi) View.VISIBLE else View.GONE
        updateSelectBar()
    }

    private fun hideSelectBar() {
        selBar.visibility = View.GONE
    }

    /** 当前列表里所有倒计时 id 的集合（回调里判断「这一条还在不在」用，避免每次都遍历）。 */
    private fun hasIds(): Set<String> = data.map { it.id }.toSet()

    /** 横幅上的「已选 N 个」。 */
    private fun updateSelectBar() {
        selCountTv.text = "已经挑了 ${selectedIds.size} 个"
    }

    /** 把勾着的都收起来（内置项照样存快照，「找回小内置」能原样捞回来）。 */
    private fun deleteSelected() {
        val picked = data.filter { it.id in selectedIds }
        if (picked.isEmpty()) {
            exitMultiSelect()
            return
        }
        val builtInCount = picked.count { it.isBuiltIn() }
        val what = if (picked.size == 1) "「${picked[0].title}」" else "挑好的这 ${picked.size} 个"
        val tip = if (builtInCount > 0)
            "\n（里面有 $builtInCount 个是内置小倒计时，会一并存成快照，「找回小内置」还能原样捞回来）"
        else ""
        AlertDialog.Builder(this)
            .setTitle("要收起选中的吗")
            .setMessage("要把 $what 都收起来吗？它们会舍不得你呢～$tip")
            .setPositiveButton("好呀，收起") { _, _ -> removeSelected() }
            .setNegativeButton("先留着", null)
            .show()
    }

    /** 执行批量删除：内置项走「收起」流程（存快照 + 记不再自动补齐），普通项直接划掉。 */
    private fun removeSelected() {
        // ⚠️ 必须遍历「快照」：下面要一边删一边改 data，直接 for (c in data) 会抛
        // ConcurrentModificationException（批量删两条以上必闪退）
        val picked = data.filter { it.id in selectedIds }
        if (picked.isEmpty()) {
            exitMultiSelect()
            return
        }
        try {
            for (c in picked) {
                if (c.isBuiltIn()) removeBuiltInEntryQuiet(c) else removeEntryQuiet(c)
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "removeSelected: ${e.message}")
        }
        selectedIds.clear()
        multiSelectOn = false
        hideSelectBar()
        CountdownStore.save(this, data)
        rebuildList()
        syncService()
    }


    // ---------------- 单条倒计时行（含滑动/拖动手势） ----------------

    inner class CountdownRow(context: Context) : FrameLayout(context) {

        lateinit var front: LinearLayout
        lateinit var actions: LinearLayout
        lateinit var titleTv: TextView
        lateinit var subTv: TextView
        lateinit var timeHead: TextView   // 最后一位数字之前的文本（动画时不参与）
        lateinit var timeLast: TextView   // 最后一位数字 —— 跳秒动画只作用在它身上
        lateinit var timeTail: TextView   // 最后一位数字之后的单位字样（如「秒」）
        lateinit var remarkTv: TextView
        lateinit var showBtn: Button
        lateinit var modeBtn: Button
        lateinit var animBtn: Button
        lateinit var editBtn: Button
        lateinit var deleteBtn: Button
        lateinit var soundLabelBtn: Button // 按钮行上的「提示音名称」（只有自定义倒计时才亮）
        lateinit var checkBox: CheckBox // 多选删除用的勾选框（平时藏着）
        var boundId: String = ""
        private var bound: Countdown? = null
        /** 上一次显示的时间文本：只有文本真的变了才播放动画（天/周等模式并非每秒都变）。 */
        private var lastTimeText = ""

        private var actionsWidth = 0
        private var downX = 0f
        private var downY = 0f
        private var downRawY = 0f
        private var startTx = 0f
        private var mode = 0 // 0 无 / 1 横向滑动 / 2 纵向滚动 / 3 拖动
        /** 这一行当前是否还挂在窗口上（rebuildList 会把旧行摘掉，摘掉后不能再回调它）。 */
        private var attached = false
        private val LONG_PRESS = 300L
        /** 长按已到时：接下来按住不动 = 进多选，接着往上下滑 = 拖动排序。 */
        private var pressArmed = false
        /** 最近一次触摸点（判断「原地抬手」还是「手指挪开了」用，见 ACTION_UP 分支）。 */
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private val SLOP = 12

        private val longPress = Runnable {
            if (mode == 0 && bound != null) {
                // v121 定型：长按满 350ms 只做「预备」，不再自动跳进多选。
                // 预备之后两种走向二选一 ——
                //   手指接着往上下滑 = 拖排序（见 ACTION_MOVE 分支）
                //   手指原地抬手      = 进多选删除（见 ACTION_UP 分支）
                // ⚠️ 旧写法是「到账后再挂 420ms 宽限期，期内不动就进多选」，手慢一点必然被
                //    「进多选」抢走，想拖排序的用户看着就是"自定义排序点了没反应"（v118~v120 连报三次）。
                pressArmed = true
                // v122：长按到点的瞬间就告诉外层「别再抢这串手势」——
                // 外层 ScrollView 只要看到纵向漂移超过 slop 就会收走事件，
                // 手指还没拖起来整串就 CANCEL 了（这就是「拖不动」的第二个原因）
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }

        fun bind(c: Countdown) {
            removeAllViews()
            val v = LayoutInflater.from(context).inflate(R.layout.item_countdown, this, false)
            addView(v)

            front = v.findViewById(R.id.itemFront)
            actions = v.findViewById(R.id.itemActions)
            titleTv = v.findViewById(R.id.itemTitle)
            subTv = v.findViewById(R.id.itemSub)
            timeHead = v.findViewById(R.id.itemTimeHead)
            timeLast = v.findViewById(R.id.itemTimeLast)
            timeTail = v.findViewById(R.id.itemTimeTail)
            remarkTv = v.findViewById(R.id.itemRemark)
            showBtn = v.findViewById(R.id.itemShow)
            modeBtn = v.findViewById(R.id.itemMode)
            animBtn = v.findViewById(R.id.itemAnim)
            soundLabelBtn = v.findViewById(R.id.itemSoundLabel)
            editBtn = v.findViewById(R.id.itemEdit)
            deleteBtn = v.findViewById(R.id.itemDelete)

            bound = c
            boundId = c.id

            // 只剩一种显示模式的条目（例如每分钟，标准模式就是秒模式）不摆切换按钮，
            // 免得留一颗点下去什么也不动的按钮占地方
            modeBtn.visibility =
                if (CountdownFormatter.hasModeSwitch(c.builtIn)) View.VISIBLE else View.GONE
            // 多选删除的勾选框：平时藏着，进了多选才每行都亮出来
            checkBox = v.findViewById(R.id.itemCheck)
            checkBox.visibility = if (this@MainActivity.multiSelectOn) View.VISIBLE else View.GONE
            checkBox.isChecked = c.id in this@MainActivity.selectedIds
            // v122/v125：勾选框现在浮在卡片最上层（见 item_countdown.xml，抬升 6dp 高过卡片的 4dp），
            // 亮出来时给卡片内容让出右边一条（勾选框 34dp + 外边距 10dp），
            // 否则它会盖在标题文字上面；退出多选再原样收回去
            val d = resources.displayMetrics.density
            val side = (12 * d).toInt()
            val rightPad = if (checkBox.visibility == View.VISIBLE) {
                side + (44 * d).toInt()
            } else {
                side
            }
            front.setPadding(side, side, rightPad, side)

            showBtn.setOnClickListener { bound?.let { this@MainActivity.onShowToggle(it) } }
            modeBtn.setOnClickListener { bound?.let { this@MainActivity.onModeCycle(it) } }
            animBtn.setOnClickListener { bound?.let { this@MainActivity.onAnimCycle(it) } }
            // 提示音名称按钮：只给普通倒计时和「固定目标」内置项亮（周期滚动型内置倒计时
            // 没有提示音可选，soundEditable() 一行就判定完，整颗按它收起）
            soundLabelBtn.visibility = if (c.soundEditable()) View.VISIBLE else View.GONE
            soundLabelBtn.text = SoundNames.name(context, c.soundUri)
            soundLabelBtn.setOnClickListener { bound?.let { this@MainActivity.onSound(it) } }
            // 把「这一行」一起交给下面两个动作：提示框被取消（或点「先不了」）时，
            // 这一行要自己滑回原位，别一直敞着操作层留在那儿。
            editBtn.setOnClickListener { bound?.let { this@MainActivity.onEdit(it, this@CountdownRow) } }
            deleteBtn.setOnClickListener { bound?.let { this@MainActivity.onDelete(it, this@CountdownRow) } }

            // 展开（滑开）状态下：点一下前景主内容区、或点一下操作面板空白处，都能收回。
            // 多选模式下这两下都让位给「勾上 / 取消勾上这一条」。
            front.setOnClickListener {
                if (this@MainActivity.multiSelectOn) {
                    bound?.let { this@MainActivity.toggleSelect(it) }
                } else if (isOpen()) {
                    closeIfOpen()
                }
            }
            actions.setOnClickListener { if (isOpen()) closeIfOpen() }

            refreshAll()
            // 未滑动时不绘制操作层（invisible 仍会测量，宽度照常可测），
            // 同时掐掉可能还在跑的滑动动画、把按钮透明度/缩放复位，免得复用视图时带着旧状态
            stopRevealAnim()
            setActionsRevealed(false)
            applyReveal(0f)
            // 布局完成后获取“露出门宽度”（取操作层宽度，右滑即露出编辑/删除按钮）
            post { actionsWidth = actions.measuredWidth }
        }

        private var velX = 0f
        private var lastX = 0f

        /** 当前是否已滑开（露出操作按钮）。 */
        private fun isOpen(): Boolean {
            ensureActionsWidth()
            return front.translationX < -actionsWidth / 2f
        }

        /**
         * 是否绘制底层操作按钮（编辑 / 删除）。
         * 未滑开时整层不绘制，避免它被半透明前景透出来、与倒计时文字重叠。
         */
        fun setActionsRevealed(revealed: Boolean) {
            actions.visibility = if (revealed) View.VISIBLE else View.INVISIBLE
        }

        /**
         * 量「露出门」的宽度。
         *
         * bind() 里是在 post 回调里量操作层的宽度，刚按下手指那一下它可能还是 0
         * ——那样跟手阶段会被整个跳过、松手也会走「直接落位不做动画」的分支，
         * 看着就像「左滑没反应 / 动画没生效」。这里每次用到都先兜底量一次，
         * 量不到就按两颗按钮的宽度估一个，保证手势一开始就正常。
         */
        private fun ensureActionsWidth() {
            if (actionsWidth > 0) return
            val w = actions.measuredWidth
            if (w > 0) {
                actionsWidth = w
                return
            }
            actionsWidth = editBtn.width + deleteBtn.width + dp(24)
            if (actionsWidth < dp(72)) actionsWidth = dp(72) // 至少能露出一颗按钮
        }

        /** 当前滑动露出进度（0 完全收起 / 1 完全展开）。 */
        private fun revealProgress(): Float {
            ensureActionsWidth()
            if (actionsWidth <= 0) return 0f
            return (-front.translationX / actionsWidth).coerceIn(0f, 1f)
        }

        /**
         * 把「露出进度」落到画面上（左滑动画的核心）：
         * 整块操作面板跟着淡入、并从右侧轻轻滑进来；两颗按钮再错开一点出场 ——
         * 编辑稍早、删除稍晚，各自带横向位移 + 从小幅放大回弹到原尺寸，
         * 展开时看着像依次弹出来；收回时按同样的顺序倒着淡回去。
         * 另一半是「倒计时淡出」：左滑时上面那块倒计时整片慢慢淡掉（推到头几乎全透明，
         * 看着像卡片被掀开露出底下两层）；往右滑收回正好反过来 —— 倒计时淡回来、
         * 两颗按钮倒着淡出。前景同时轻微放大一点点，做出「卡片被推开」的层次感。
         */
        private fun applyReveal(p: Float) {
            ensureActionsWidth()
            if (actionsWidth <= 0) return
            val e = p.coerceIn(0f, 1f)
            if (e <= 0f) {
                // 完全收起时把面板属性擦干净，免得下次滑开带着半透明的旧状态
                actions.alpha = 1f
                actions.translationX = 0f
                front.alpha = 1f
                front.scaleX = 1f
                front.scaleY = 1f
                resetButtons()
                return
            }
            actions.alpha = 0.3f + 0.7f * e
            actions.translationX = (1f - e) * dp(40).toFloat()
            val ea = (e * 1.55f).coerceIn(0f, 1f)
            editBtn.alpha = ea
            editBtn.translationX = (1f - ea) * dp(32).toFloat()
            editBtn.translationY = (1f - ea) * (-dp(14)).toFloat()
            editBtn.scaleX = 0.7f + 0.3f * ea
            editBtn.scaleY = 0.7f + 0.3f * ea
            val da = ((e - 0.32f) * 1.55f).coerceIn(0f, 1f)
            deleteBtn.alpha = da
            deleteBtn.translationX = (1f - da) * dp(32).toFloat()
            deleteBtn.translationY = (1f - da) * (-dp(14)).toFloat()
            deleteBtn.scaleX = 0.7f + 0.3f * da
            deleteBtn.scaleY = 0.7f + 0.3f * da
            // 左滑：操作层往里淡入的同时，上面那块倒计时整片淡出（推到头几乎全透明）；
            // 往右滑收回时 p 变小，这一行自然反过来 —— 倒计时淡回来、按钮倒着淡出。
            front.alpha = 1f - e
            front.scaleX = 1f + 0.03f * e
            front.scaleY = 1f + 0.03f * e
        }

        /** 把两颗按钮的属性复位到「没滑开」的样子。 */
        private fun resetButtons() {
            editBtn.alpha = 0f
            deleteBtn.alpha = 0f
            editBtn.translationX = 0f
            editBtn.translationY = 0f
            editBtn.scaleX = 1f
            editBtn.scaleY = 1f
            deleteBtn.translationX = 0f
            deleteBtn.translationY = 0f
            deleteBtn.scaleX = 1f
            deleteBtn.scaleY = 1f
        }

        /** 跟手滑动时立刻按当前位移画一遍（手指走多少，按钮就淡入多少）。 */
        private fun syncRevealFromDrag() {
            ensureActionsWidth()
            if (actionsWidth <= 0) return
            if (front.translationX < -1f) setActionsRevealed(true)
            applyReveal(revealProgress())
        }

        /** 当前正在跑的滑动动画（用户重新上手 / 开始拖排序时要掐掉它）。 */
        private var revealAnim: ValueAnimator? = null

        /**
         * 掐掉正在跑的滑动动画，保持画面停在当前进度。
         *
         * 先摘掉引用再 cancel：cancel() 会顺带触发 onAnimationEnd，
         * 那里靠「引用是否还是自己」判断是不是自己那份收尾 —— 先置空就不会
         * 把上一轮动画的终态（比如收起时的归零）盖到刚恢复跟手的手指上。
         */
        private fun stopRevealAnim() {
            val a = revealAnim
            revealAnim = null
            a?.cancel()
        }

        /**
         * 滑到指定 translationX：左滑展开（target 为负）与右滑收回（target = 0）都走这里。
         * 用 ValueAnimator 一条动画同时驱动「前景位移」和「按钮淡入淡出」，两者永远同步。
         */
        private fun animateTo(target: Float) {
            ensureActionsWidth()
            val open = target < -1f
            stopRevealAnim()
            if (open) setActionsRevealed(true)
            if (actionsWidth <= 0) { // 实在量不到宽度：直接落位，不做动画
                front.translationX = target
                applyReveal(if (open) 1f else 0f)
                if (!open) setActionsRevealed(false)
                return
            }
            val from = revealProgress()
            val anim = ValueAnimator.ofFloat(from, if (open) 1f else 0f)
            anim.addUpdateListener { va ->
                val p = va.animatedValue as Float
                front.translationX = target * p
                applyReveal(p)
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (revealAnim !== animation) return
                    revealAnim = null
                    if (open) {
                        front.translationX = target
                        applyReveal(1f)
                    } else {
                        front.translationX = 0f
                        applyReveal(0f)
                        setActionsRevealed(false)
                    }
                }
            })
            anim.duration = if (open) 280L else 240L
            // 展开来一下小回弹（橡皮筋感），收回则稳稳减速就位，不向右过冲
            anim.interpolator = if (open) OvershootInterpolator(1.4f) else DecelerateInterpolator(1.6f)
            revealAnim = anim
            anim.start()
        }

        /** 若已滑开则收起（右滑动画走的是上面同一套）。 */
        fun closeIfOpen() {
            if (isOpen()) animateTo(0f)
        }

        /** 全量刷新（模式/显隐变化后）。 */
        fun refreshAll(now: Long = AlignedClock.now()) {
            val c = bound ?: return
            c.refreshBuiltInTarget() // 内置项先对齐目标时间，保证「目标:」行显示的是当前周期
            titleTv.text = c.title
            titleTv.setTextColor(c.customColorArgb)
            subTv.text = "目标： " + sdf.format(Date(c.targetTime)) +
                    " | " + CountdownFormatter.modeName(c.displayMode, c.builtIn)
            lastTimeText = c.remainingText(now)
            applyTimeText(lastTimeText, c.customColorArgb)
            // 整表重建不播动画（否则一进界面所有条目一起乱动），但要确保属性干净
            AnimStyle.stop(timeLast)
            AnimStyle.reset(timeLast, c.customColorArgb)
            // 备注：内置项用随目标时间同步变化的实时备注（跨整点 / 跨天立刻跟着变）
            refreshRemark()
            showBtn.text = if (c.isVisible) "收起悬浮窗" else "展开悬浮窗"
            // 动画效果名称显示在「显示 / 显示模式」按钮之后
            animBtn.text = AnimStyle.name(c.animStyle)
        }

        /** 刷新备注行：内置项显示随目标时间同步变化的实时备注，其它项沿用原备注。 */
        fun refreshRemark() {
            val c = bound ?: return
            showRemark(c.remarkText(TimeFormatPref.is24Hour(context)))
        }

        /** 按备注文本显示 / 隐藏备注行；文本没变就不重设，避免每秒无谓改写视图。 */
        private fun showRemark(text: String) {
            if (text.isBlank()) {
                if (remarkTv.visibility != View.GONE) remarkTv.visibility = View.GONE
                return
            }
            val full = "备注： " + text.replace("\n", " ")
            val tv = remarkTv
            if (tv.text.toString() != full) tv.text = full
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
        }

        /** 仅刷新时间文本（不重建视图，保留滑动/拖动状态）；now 由调用方统一给定。 */
        fun refreshTime(now: Long = AlignedClock.now()) {
            val c = bound ?: return
            val text = c.remainingText(now)
            if (text == lastTimeText) return
            lastTimeText = text
            applyTimeText(text, c.customColorArgb)
            AnimStyle.play(timeLast, c.animStyle, c.customColorArgb)
        }

        /**
         * 给拖动浮层用的时间刷新：跟随列表一起走秒，但不播跳秒动画
         *（拖动过程中播放缩放/坠落等动画会让手指下的卡片发抖）。
         */
        fun refreshTimeQuiet(now: Long = AlignedClock.now()) {
            val c = bound ?: return
            val text = c.remainingText(now)
            if (text == lastTimeText) return
            lastTimeText = text
            applyTimeText(text, c.customColorArgb)
        }

        /** 把时间文本拆成三段显示。 */
        private fun applyTimeText(text: String, color: Int) {
            val (head, last, tail) = CountdownFormatter.splitLastDigit(text)
            timeHead.text = head
            timeLast.text = last
            timeTail.text = tail
            timeHead.setTextColor(color)
            timeLast.setTextColor(color)
            timeTail.setTextColor(color)
        }

        /**
         * 拖动状态：原位条目隐藏但仍占据布局（visibility = INVISIBLE），
         * 这样列表其它条目不会跳动，也不会出现「原地虚影 + 手指上的卡片」两层并存的观感。
         */
        fun setDragging(on: Boolean) {
            visibility = if (on) View.INVISIBLE else View.VISIBLE
        }

        /** 拖动结束后复位手势状态（复位到静止、未滑动）。 */
        fun resetDragState() {
            mode = 0
            stopRevealAnim()
            front.translationX = 0f
            applyReveal(0f)
            setActionsRevealed(false)
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (bound == null) return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    downRawY = e.rawY
                    startTx = front.translationX
                    lastX = e.x
                    lastTouchX = e.x
                    lastTouchY = e.y
                    velX = 0f
                    mode = 0
                    pressArmed = false
                    stopRevealAnim() // 重新上手：掐掉未跑完的滑动动画，交回手指控制
                    if (actionsWidth == 0) actionsWidth = actions.measuredWidth
                    removeCallbacks(longPress)
                    postDelayed(longPress, LONG_PRESS)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 3) return true
                    lastTouchX = e.x
                    lastTouchY = e.y
                    if (mode == 0) {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        // 长按满 350ms 之后手指接着往上下滑 —— 这时还是「拖动排序」
                        // 长按预备这条只保留「往上下滑 = 拖排序」的走法，
                        // 进多选那一半改由 ACTION_UP 的「原地抬手」负责（见下）。
                        // ⚠️ 阈值必须 <= SLOP(12)：原来写 24f 时，手指刚滑过 12px 就先被下面
                        //    的「纵向滚动」分支（mode=2）抢走并 removeCallbacks(longPress)，
                        //    pressArmed 永远置不上位，beginDrag 一次也走不到 —— 这就是
                        //    「自定义排序点了没反应」的真根因（v118~v120 连报三次）。
                        //    另外这一段必须排在下面的横向/纵向滑动分支之前，否则长按根本没机会生效。
                        if (pressArmed && Math.abs(dy) > SLOP) {
                            pressArmed = false
                            beginDrag(this@CountdownRow, bound!!, downRawY.toInt())
                            // 拖拽成功（已生成幽灵）才进入拖动模式；失败则回退，避免卡在 mode=3
                            mode = if (dragInfo != null) 3 else 0
                            return true
                        }
                        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > SLOP) {
                            mode = 1
                            removeCallbacks(longPress)
                            return true
                        } else if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > SLOP) {
                            mode = 2
                            removeCallbacks(longPress)
                            return false
                        }
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (mode == 3) {
                        this@MainActivity.endDrag()
                        mode = 0
                        pressArmed = false
                        return true
                    }
                    // v121：长按预备中、手指全程没挪出 SLOP、这一行还挂着 —— 原地抬手 = 进多选删除。
                    // attached / hasIds() 两道门必须留着：进多选会整表重建把本行摘下来，
                    // 摘掉之后这个回调还可能再跑一次，不挡就会对着旧行反复重建列表。
                    if (e.action == MotionEvent.ACTION_UP && pressArmed && mode == 0
                        && bound != null && attached && boundId in this@MainActivity.hasIds()) {
                        val movedFar = Math.abs(lastTouchX - downX) > SLOP ||
                                Math.abs(lastTouchY - downY) > SLOP
                        pressArmed = false
                        if (!movedFar) {
                            this@MainActivity.enterMultiSelect(bound!!)
                            return true
                        }
                    }
                    pressArmed = false
                    return false
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bound == null) return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 不抢占手势：交给 ScrollView 处理纵向滚动；
                    // 横向滑动/长按由 onInterceptTouchEvent 截获后转交到这里。
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (mode == 3) {
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        this@MainActivity.dragMove(e.rawY.toInt())
                        return true
                    }
                    if (mode == 0) {
                        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > SLOP) {
                            mode = 1
                            (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        } else if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > SLOP) {
                            mode = 2
                            return false // 纵向：让 ScrollView 滚动
                        }
                    }
                    if (mode == 1) {
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        velX = e.x - lastX
                        lastX = e.x
                        if (actionsWidth <= 0) ensureActionsWidth()
                        var tx = startTx + dx
                        tx = tx.coerceIn(-actionsWidth.toFloat(), 0f)
                        front.translationX = tx
                        // 左滑跟手动画：手指走多少，下面的编辑/删除就淡入多少
                        syncRevealFromDrag()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == 3) {
                        this@MainActivity.endDrag()
                        mode = 0
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    if (mode == 1) {
                        // 回收更宽松：展开后向右回滑约 40% 即收起，也支持快速右滑甩回收；
                        // 收起状态下向左滑过半（或快速左滑）则展开。
                        val wasOpen = startTx < -actionsWidth / 2f
                        val pos = front.translationX
                        val target = if (wasOpen) {
                            // 展开后回滑超过 40%（或快速右甩）即收起，阈值比打开更宽松
                            if (pos > -actionsWidth * 0.6f || velX > 6f) 0f else -actionsWidth.toFloat()
                        } else {
                            if (pos < -actionsWidth * 0.5f || velX < -6f) -actionsWidth.toFloat() else 0f
                        }
                        animateTo(target)
                        mode = 0
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    mode = 0
                    return false
                }
            }
            return false
        }

        override fun onAttachedToWindow() {
            attached = true
            super.onAttachedToWindow()
        }

        override fun onDetachedFromWindow() {
            attached = false
            // 长按回调也要掐：它会在条目已经被 rebuildList() 摘掉之后还跑一遍，对着旧行重建列表
            removeCallbacks(longPress)
            pressArmed = false
            super.onDetachedFromWindow()
        }
    }
}
