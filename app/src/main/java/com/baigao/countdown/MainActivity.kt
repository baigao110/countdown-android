package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    // 扇形菜单相关
    private lateinit var addBtn: Button
    private lateinit var menuBackdrop: View
    private lateinit var menuItemAdd: View
    private lateinit var menuItemAbout: View
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
                    (listContainer.getChildAt(i) as? CountdownRow)?.refreshTime(now)
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

    /** 读取本地数据，并确保「当日倒计时」「当月倒计时」两个内置项始终存在。 */
    private fun loadData() {
        data = CountdownStore.load(this)
        if (ensureBuiltInTimers()) CountdownStore.save(this, data)
    }

    /** 补齐两个内置倒计时；返回是否新建（新建后才需要落盘）。 */
    private fun ensureBuiltInTimers(): Boolean {
        // 先补当月再补当日，保证列表中「当日倒计时」排在「当月倒计时」之前
        var added = ensureBuiltIn(BuiltIn.MONTH, "当月倒计时", "距离本月结束")
        added = ensureBuiltIn(BuiltIn.DAY, "当日倒计时", "距离今日结束") || added
        return added
    }

    /** 补齐单个内置倒计时；已存在则保留用户的位置、颜色、模式等配置。 */
    private fun ensureBuiltIn(type: Int, title: String, remark: String): Boolean {
        if (data.any { it.builtIn == type }) return false
        val c = Countdown(
            title = title,
            remark = remark,
            builtIn = type,
            displayMode = 0,
            isVisible = false   // 默认不强制弹出悬浮窗，可在列表中点「显示」
        )
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
        /** 进程存活期间只自动检查一次更新，避免每次 onResume 都弹窗。 */
        private var updateCheckedOnce = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scroll)
        listContainer = findViewById(R.id.listContainer)
        dragLayer = findViewById(R.id.dragLayer)

        // 扇形菜单：加号 -> 弹出“关于 / 添加倒计时”；点空白收起
        addBtn = findViewById(R.id.addBtn)
        menuBackdrop = findViewById(R.id.menuBackdrop)
        menuItemAdd = findViewById(R.id.menuItemAdd)
        menuItemAbout = findViewById(R.id.menuItemAbout)

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

        // 启动即检查更新：发现新版本会强制弹出更新日志对话框
        if (!updateCheckedOnce) {
            updateCheckedOnce = true
            UpdateManager.check(this, forceDialog = true)
        }
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

        // 两个菜单项沿右下角向上的弧线“扇形”弹出
        animateItemOut(menuItemAdd, -dp(132), -dp(60), 0)
        animateItemOut(menuItemAbout, -dp(60), -dp(132), 70)
        addBtn.animate().rotation(45f).setDuration(200).start()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        menuBackdrop.animate().alpha(0f).setDuration(160)
            .withEndAction { menuBackdrop.visibility = View.GONE }.start()
        animateItemIn(menuItemAdd, 0)
        animateItemIn(menuItemAbout, 70)
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
        // 从「允许安装未知应用」设置页返回后，继续之前挂起的安装
        UpdateManager.consumePendingInstall(this)
        loadData()
        rebuildList()
        try {
            registerReceiver(dataChangedReceiver, IntentFilter(CountdownService.ACTION_DATA_CHANGED))
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "registerReceiver: ${e.message}")
        }
        tickHandler.post(tickRunnable)
        // 首次使用（或权限缺失且存在可见倒计时时）引导开启悬浮窗权限；
        // 权限已授予则按可见性自动拉起/刷新悬浮窗。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (data.any { it.isVisible }) assistOverlayPermission()
        } else {
            syncService()
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(dataChangedReceiver)
        } catch (e: Throwable) {
            // 未注册时忽略
        }
        tickHandler.removeCallbacks(tickRunnable)
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
            .setTitle("需要“显示在其他应用上层”权限")
            .setMessage("倒计时悬浮窗需要“显示在其他应用上层”权限，才能在屏幕最上层显示。\n\n请点击「去设置」，在列表中找到本应用「倒计时」并开启开关，然后返回即可自动显示悬浮窗。")
            .setPositiveButton("去设置") { _, _ ->
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(i, REQ_OVERLAY)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /**
     * 按当前数据与权限自动管理悬浮窗服务（无需手动启动/停止按钮）：
     * - 有权限且有可见倒计时 → 未运行则启动，已运行则刷新最新数据；
     * - 无可见倒计时 → 停止服务，避免常驻通知。
     */
    private fun syncService() {
        val hasVisible = data.any { it.isVisible }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (hasVisible) {
            if (!isServiceRunning()) {
                val i = Intent(this, CountdownService::class.java)
                i.action = CountdownService.ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            } else {
                val ri = Intent(this, CountdownService::class.java)
                ri.action = CountdownService.ACTION_REFRESH
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(ri) else startService(ri)
            }
        } else if (isServiceRunning()) {
            stopService(Intent(this, CountdownService::class.java))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                syncService()
            }
        } else if (requestCode == REQ_EDIT) {
            loadData()
            rebuildList()
            syncService()
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
        val n = CountdownFormatter.MODE_NAMES.size
        c.displayMode = ((c.displayMode + 1) % n + n) % n
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

    fun onEdit(c: Countdown) {
        val i = Intent(this, AddEditActivity::class.java)
        i.putExtra("id", c.id)
        startActivityForResult(i, REQ_EDIT)
    }

    fun onSound(c: Countdown) {
        val i = Intent(this, AddEditActivity::class.java)
        i.putExtra("id", c.id)
        i.putExtra("pickSoundOnly", true)
        startActivityForResult(i, REQ_EDIT)
    }

    fun onDelete(c: Countdown) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定删除「${c.title}」吗？\n（确定将关闭该倒计时显示）")
            .setPositiveButton("确定") { _, _ ->
                // 内置倒计时（当日 / 当月）：确认后提示不可删除，且不执行删除
                if (c.isBuiltIn()) {
                    AlertDialog.Builder(this)
                        .setTitle("无法删除")
                        .setMessage("该倒计时不可删除\n\n「${c.title}」为系统内置倒计时，如不想看到它可在列表中点击「隐藏」。")
                        .setPositiveButton("确定", null)
                        .show()
                    return@setPositiveButton
                }
                data.removeAll { it.id == c.id }
                CountdownStore.save(this, data)
                rebuildList()
                syncService()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
        lateinit var soundLabelBtn: Button // 按钮行上的「提示音名称」
        lateinit var editBtn: Button
        lateinit var soundBtn: Button
        lateinit var deleteBtn: Button
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
        private val LONG_PRESS = 350L
        private val SLOP = 12

        private val longPress = Runnable {
            if (mode == 0 && bound != null) {
                beginDrag(this@CountdownRow, bound!!, downRawY.toInt())
                // 拖拽成功（已生成幽灵）才进入拖动模式；截图失败则回退，避免卡在 mode=3
                mode = if (dragInfo != null) 3 else 0
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
            editBtn = v.findViewById(R.id.itemEdit)
            soundBtn = v.findViewById(R.id.itemSound)
            deleteBtn = v.findViewById(R.id.itemDelete)
            soundLabelBtn = v.findViewById(R.id.itemSoundLabel)

            bound = c
            boundId = c.id

            showBtn.setOnClickListener { bound?.let { this@MainActivity.onShowToggle(it) } }
            modeBtn.setOnClickListener { bound?.let { this@MainActivity.onModeCycle(it) } }
            animBtn.setOnClickListener { bound?.let { this@MainActivity.onAnimCycle(it) } }
            // 提示音名称按钮：直接打开系统铃声选择器（与左滑露出的「提示音」按钮一致）
            soundLabelBtn.setOnClickListener { bound?.let { this@MainActivity.onSound(it) } }
            editBtn.setOnClickListener { bound?.let { this@MainActivity.onEdit(it) } }
            soundBtn.setOnClickListener { bound?.let { this@MainActivity.onSound(it) } }
            deleteBtn.setOnClickListener { bound?.let { this@MainActivity.onDelete(it) } }

            // 展开（滑开）状态下：点一下前景主内容区、或点一下操作面板空白处，都能收回
            front.setOnClickListener { if (isOpen()) closeIfOpen() }
            actions.setOnClickListener { if (isOpen()) closeIfOpen() }

            refreshAll()
            // 布局完成后获取“露出门宽度”（取操作层宽度，右滑即露出编辑/提示音/删除按钮）
            post { actionsWidth = actions.measuredWidth }
        }

        private var velX = 0f
        private var lastX = 0f

        /** 当前是否已滑开（露出操作按钮）。 */
        private fun isOpen(): Boolean = front.translationX < -actionsWidth / 2f

        /** 动画移动到指定 translationX。 */
        private fun animateTo(target: Float) {
            front.animate().translationX(target).setDuration(160).start()
        }

        /** 若已滑开则收起。 */
        fun closeIfOpen() {
            if (isOpen()) animateTo(0f)
        }

        /** 全量刷新（模式/显隐变化后）。 */
        fun refreshAll(now: Long = AlignedClock.now()) {
            val c = bound ?: return
            c.refreshBuiltInTarget() // 内置项先对齐目标时间，保证「目标:」行显示的是当前周期
            titleTv.text = c.title
            titleTv.setTextColor(c.customColorArgb)
            subTv.text = "目标: " + sdf.format(Date(c.targetTime)) +
                    " | " + CountdownFormatter.modeName(c.displayMode)
            lastTimeText = c.remainingText(now)
            applyTimeText(lastTimeText, c.customColorArgb)
            // 整表重建不播动画（否则一进界面所有条目一起乱动），但要确保属性干净
            AnimStyle.stop(timeLast)
            AnimStyle.reset(timeLast, c.customColorArgb)
            if (c.remark.isNotEmpty()) {
                remarkTv.visibility = View.VISIBLE
                remarkTv.text = "备注: " + c.remark.replace("\n", " ")
            } else {
                remarkTv.visibility = View.GONE
            }
            showBtn.text = if (c.isVisible) "隐藏" else "显示"
            // 动画效果名称显示在「显示 / 模式」按钮之后
            animBtn.text = AnimStyle.name(c.animStyle)
            // 再后面显示本条倒计时设置的提示音名称；没设过就显示「未设置提示音」
            soundLabelBtn.text = SoundNames.name(context, c.soundUri)
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
            front.translationX = 0f
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
                    velX = 0f
                    mode = 0
                    if (actionsWidth == 0) actionsWidth = actions.measuredWidth
                    removeCallbacks(longPress)
                    postDelayed(longPress, LONG_PRESS)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 3) return true
                    if (mode == 0) {
                        val dx = e.x - downX
                        val dy = e.y - downY
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
                        return true
                    }
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
                        var tx = startTx + dx
                        tx = tx.coerceIn(-actionsWidth.toFloat(), 0f)
                        front.translationX = tx
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

        override fun onDetachedFromWindow() {
            removeCallbacks(longPress)
            super.onDetachedFromWindow()
        }
    }
}
