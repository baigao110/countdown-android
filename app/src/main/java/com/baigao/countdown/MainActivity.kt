package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.widget.ImageView
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
            for (i in 0 until listContainer.childCount) {
                (listContainer.getChildAt(i) as? CountdownRow)?.refreshTime()
            }
            tickHandler.postDelayed(this, 1000)
        }
    }

    /** 接收服务的“数据已变化”广播（如悬浮窗内隐藏/显示），实时刷新列表。 */
    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            data = CountdownStore.load(this@MainActivity)
            rebuildList()
        }
    }

    companion object {
        const val REQ_OVERLAY = 1001
        const val REQ_EDIT = 1003
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
        data = CountdownStore.load(this)
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
            this.data = CountdownStore.load(this)
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
        val ghost: ImageView,
        val grabOffsetY: Int,
        val row: CountdownRow
    )

    /** 长按启动拖动：对原条目截图作“幽灵”，将原条目虚化（轮廓），幽灵跟随手指。 */
    fun beginDrag(row: CountdownRow, c: Countdown, pointerY: Int) {
        try {
            val front = row.front
            front.translationX = 0f // 还原滑动，确保截图干净
            val w = front.width
            val h = front.height
            if (w <= 0 || h <= 0) return
            // 用 Canvas 直接把前景画进 Bitmap（比弃用的 buildDrawingCache 可靠，不会抛异常）
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            front.draw(canvas)

            val ghost = ImageView(this)
            ghost.setImageBitmap(bmp)
            ghost.layoutParams = FrameLayout.LayoutParams(w, h)
            ghost.alpha = 0.92f

            val loc = IntArray(2)
            front.getLocationOnScreen(loc)
            ghost.x = loc[0].toFloat()
            ghost.y = loc[1].toFloat()

            dragLayer.removeAllViews()
            dragLayer.addView(ghost)
            dragLayer.visibility = View.VISIBLE

            val grabOffsetY = pointerY - loc[1]
            row.setGhosted(true)
            val fromIndex = data.indexOfFirst { it.id == c.id }
            dragInfo = DragInfo(c, fromIndex, fromIndex, ghost, grabOffsetY, row)
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "beginDrag: ${e.message}")
            dragInfo = null
        }
    }

    /** 拖动中：移动幽灵 + 计算落点；松手时按落点重排。 */
    fun dragMove(pointerY: Int) {
        val info = dragInfo ?: return
        try {
            info.ghost.y = (pointerY - info.grabOffsetY).toFloat()

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
                val child = listContainer.getChildAt(i) as CountdownRow
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
            info.row.setGhosted(false)
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
        lateinit var timeTv: TextView
        lateinit var remarkTv: TextView
        lateinit var showBtn: Button
        lateinit var modeBtn: Button
        lateinit var editBtn: Button
        lateinit var soundBtn: Button
        lateinit var deleteBtn: Button
        var boundId: String = ""
        private var bound: Countdown? = null

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
            timeTv = v.findViewById(R.id.itemTime)
            remarkTv = v.findViewById(R.id.itemRemark)
            showBtn = v.findViewById(R.id.itemShow)
            modeBtn = v.findViewById(R.id.itemMode)
            editBtn = v.findViewById(R.id.itemEdit)
            soundBtn = v.findViewById(R.id.itemSound)
            deleteBtn = v.findViewById(R.id.itemDelete)

            bound = c
            boundId = c.id

            showBtn.setOnClickListener { bound?.let { this@MainActivity.onShowToggle(it) } }
            modeBtn.setOnClickListener { bound?.let { this@MainActivity.onModeCycle(it) } }
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
        fun refreshAll() {
            val c = bound ?: return
            titleTv.text = c.title
            titleTv.setTextColor(c.customColorArgb)
            subTv.text = "目标: " + sdf.format(Date(c.targetTime)) + " | " + CountdownFormatter.modeName(c.displayMode)
            timeTv.text = CountdownFormatter.remaining(c.targetTime, c.displayMode)
            timeTv.setTextColor(c.customColorArgb)
            if (c.remark.isNotEmpty()) {
                remarkTv.visibility = View.VISIBLE
                remarkTv.text = "备注: " + c.remark.replace("\n", " ")
            } else {
                remarkTv.visibility = View.GONE
            }
            showBtn.text = if (c.isVisible) "隐藏" else "显示"
        }

        /** 每秒仅刷新时间文本（不重建视图，保留滑动/拖动状态）。 */
        fun refreshTime() {
            val c = bound ?: return
            timeTv.text = CountdownFormatter.remaining(c.targetTime, c.displayMode)
            timeTv.setTextColor(c.customColorArgb)
        }

        /** 拖动时把原条目虚化为半透明轮廓。 */
        fun setGhosted(on: Boolean) {
            if (on) {
                this.background = ColorDrawable(0x22454555)
                front.alpha = 0.3f
            } else {
                this.background = null
                front.alpha = 1f
            }
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
