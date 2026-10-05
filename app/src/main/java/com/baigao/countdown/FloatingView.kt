package com.baigao.countdown

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单个悬浮倒计时窗体：用 WindowManager 添加到屏幕最上层，可拖动。
 *
 * 交互：
 * - 拖动窗体任意空白区域可移动；
 * - 展开时点空白区域循环切换到下一个显示模式；
 * - 收缩为小条后，点小条可“放开”恢复；
 * - 右上角“菜单”按钮滑出/收起抽屉（目标时间、透明度、上一个/下一个显示模式、编辑、收起悬浮窗）；
 * - 右上角“收/开”按钮收缩或放开窗体；
 * - 右上角“×”隐藏该悬浮窗。
 *
 * 所有窗口操作都做了异常兜底，任何一次触摸都不会导致进程崩溃。
 */
class FloatingView(
    private val context: Context,
    val data: Countdown,
    private val windowManager: WindowManager,
    private val onModeChange: (Countdown, Int) -> Unit,
    private val onEdit: (Countdown) -> Unit,
    private val onClose: (Countdown) -> Unit,
    private val onOpacityChange: (Countdown, Int) -> Unit,
    private val onCollapseChange: (Countdown, Boolean) -> Unit,
    private val onPositionChange: (Countdown, Int, Int) -> Unit
) {

    private val view: View = LayoutInflater.from(context).inflate(R.layout.floating_countdown, null)
    private val titleTv: TextView = view.findViewById(R.id.fTitle)
    private val timeRow: View = view.findViewById<View>(R.id.fTime)
    private val timeHead: TextView = view.findViewById(R.id.fTimeHead)
    private val timeLast: TextView = view.findViewById(R.id.fTimeLast)
    private val timeTail: TextView = view.findViewById(R.id.fTimeTail)
    /** 上一次显示的时间文本：仅文本变化时播放动画（天/周等模式并非每秒都变）。 */
    private var lastTimeText = ""
    /** 上一次展示的备注文本（内置项备注会随整点/日期变化，用于去重避免每秒重写视图）。 */
    private var lastRemark = ""
    /** 上一次展示的目标时间文本：内置项跨整点 / 跨天时目标会滚动，同样去重避免每秒重写视图。 */
    private var lastTarget = ""
    /** 上一次展示的显示模式名（切模式 / 换内置项后都要跟着变，用于去重避免每秒重写视图）。 */
    private var lastModeText = ""
    private val modeTv: TextView = view.findViewById(R.id.fMode)
    private val remarkMain: TextView = view.findViewById(R.id.fRemark)
    private val drawerBtn: Button = view.findViewById(R.id.fDrawer)
    private val closeBtn: Button = view.findViewById(R.id.fClose)
    private val collapseBtn: Button = view.findViewById(R.id.fCollapse)
    private val drawerPanel: View = view.findViewById(R.id.fDrawerPanel)
    private val targetTv: TextView = view.findViewById(R.id.fTargetTime)
    private val opacityBar: SeekBar = view.findViewById(R.id.fOpacity)
    private val prevBtn: Button = view.findViewById(R.id.fPrevMode)
    private val nextBtn: Button = view.findViewById(R.id.fNextMode)
    private val editBtn: Button = view.findViewById(R.id.fEdit)
    private val hideBtn: Button = view.findViewById(R.id.fHide)

    private val params: WindowManager.LayoutParams

    /** 不随开关变化的基础窗口标志。 */
    private val baseFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var moved = false
    private var drawerOpen = false

    init {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            baseFlags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 优先使用已保存的位置；未保存（<0）时依据 id 稳定错开，避免完全重叠
        if (data.posX >= 0 && data.posY >= 0) {
            params.x = data.posX
            params.y = data.posY
        } else {
            val seed = Math.abs(data.id.hashCode())
            params.x = 24 + seed % 140
            params.y = 110 + seed % 220
        }
        // 初始不透明度
        params.alpha = (data.opacity.coerceIn(20, 100)) / 100f

        closeBtn.setOnClickListener { safe("close") { onClose(data) } }
        hideBtn.setOnClickListener { safe("hide") { onClose(data) } }
        drawerBtn.setOnClickListener { safe("drawer") { if (!data.collapsed) toggleDrawer() } }
        collapseBtn.setOnClickListener { safe("collapse") { toggleCollapse() } }
        prevBtn.setOnClickListener { safe("prevMode") { shiftMode(-1) } }
        nextBtn.setOnClickListener { safe("nextMode") { shiftMode(1) } }
        editBtn.setOnClickListener { safe("edit") { onEdit(data) } }

        // 透明度调节：拖动时实时预览，松手后落盘
        opacityBar.max = 80
        opacityBar.progress = 100 - data.opacity.coerceIn(20, 100) // 进度 = 透明度%
        opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val op = (100 - progress).coerceIn(20, 100)
                data.opacity = op
                params.alpha = op / 100f
                safeUpdateLayout()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                onOpacityChange(data, data.opacity)
            }
        })

        view.setOnTouchListener { _, event -> handleTouch(event) }

        bindTexts()
        applyCollapsed()
        applyKeepScreenOn()
        update()
    }

    /** 把数据模型里的展示字段刷到界面上（标题/颜色/模式名/目标时间/备注）。 */
    private fun bindTexts() {
        try {
            titleTv.text = data.title
            titleTv.setTextColor(data.customColorArgb)
            timeHead.setTextColor(data.customColorArgb)
            timeLast.setTextColor(data.customColorArgb)
            timeTail.setTextColor(data.customColorArgb)
            applyModeText()
            applyTarget() // 内置项对齐目标时间，保证「目标:」显示当前周期
            applyRemark()
            opacityBar.progress = 100 - data.opacity.coerceIn(20, 100)
        } catch (e: Throwable) {
            Log.w(TAG, "bindTexts: ${e.message}")
        }
    }

    /**
     * 刷新「模式名 | 动画」这一行（悬浮窗顶部小字）。
     * 与倒计时数字、备注、目标时间一样，**每秒都要刷** —— 悬浮窗是常驻的，
     * 只靠 bindTexts() 写一次，切了显示模式 / 换成别的倒计时就会停在旧模式名。
     */
    private fun applyModeText() {
        try {
            val t = CountdownFormatter.modeName(data.displayMode, data.builtIn) +
                " | " + AnimStyle.name(data.animStyle)
            if (t == lastModeText) return
            lastModeText = t
            modeTv.text = t
        } catch (_: Throwable) { }
    }

    /**
     * 刷新备注行：内置项用随目标时间同步变化的实时备注（跨整点自动变成「距离18点整结束」），
     * 自建项沿用原备注。文本没变就不重设，避免每秒无谓改写视图。
     */
    private fun applyRemark() {
        val r = data.remarkText(TimeFormatPref.is24Hour(context))
        if (r == lastRemark) return
        lastRemark = r
        try {
            if (r.isNotEmpty()) {
                remarkMain.visibility = View.VISIBLE
                remarkMain.text = "备注: " + r.replace("\n", " ")
            } else {
                remarkMain.visibility = View.GONE
            }
        } catch (_: Throwable) { }
    }

    /**
     * 刷新「目标:」这一行（抽屉里的目标时间）。
     *
     * 内置项（每小时 / 每半小时 / 每 1·5·10 分钟 / 当日 / 每周 / 当月 …）的目标时间是**滚动**的：
     * 过了整点就跳到下一个周期、过了零点就跳到次日 —— 必须随每秒的跳秒一起刷新，
     * 否则退到后台之后「目标:」会一直停在建立时或建窗那一刻的旧值，
     * 而倒计时数字照样在走，看起来就像「目标时间没同步」。
     * 文本没变就不重设，避免每秒无谓改写视图。
     */
    private fun applyTarget() {
        try {
            data.refreshBuiltInTarget() // 内置项对齐目标时间
            val t = "目标: " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(data.targetTime))
            if (t == lastTarget) return
            lastTarget = t
            targetTv.text = t
        } catch (e: Throwable) {
            Log.w(TAG, "applyTarget: ${e.message}")
        }
    }

    /** 每秒调用：刷新倒计时数字 / 目标时间 / 备注。now 由调用方统一给定（多个悬浮窗同步跳秒）。 */
    fun update(now: Long = System.currentTimeMillis()) {
        try {
            val text = data.remainingText(now)
            if (text != lastTimeText) {
                lastTimeText = text
                applyTimeText(text)
                AnimStyle.play(timeLast, data.animStyle, data.customColorArgb)
            }
            // 整点 / 跨天时备注也要跟着换说法（悬浮窗是常驻的，不能只靠 bindTexts）
            applyRemark()
            // 目标时间同理：滚动型内置项跨周期后「目标:」必须跟着变
            applyTarget()
            // 显示模式名同理：悬浮窗是常驻的，切模式 / 换内置项后这一行也要跟着变
            applyModeText()
        } catch (e: Throwable) {
            Log.w(TAG, "update: ${e.message}")
        }
    }

    /** 把时间文本拆成三段，动画只作用于最后一位数字。 */
    private fun applyTimeText(text: String) {
        val (head, last, tail) = CountdownFormatter.splitLastDigit(text)
        timeHead.text = head
        timeLast.text = last
        timeTail.text = tail
    }

    /** 主界面编辑后同步数据（复用同一个对象，保证悬浮窗与列表一致）。 */
    fun syncFrom(c: Countdown) {
        try {
            data.title = c.title
            data.targetTime = c.targetTime
            data.builtIn = c.builtIn
            data.customColorArgb = c.customColorArgb
            data.displayMode = c.displayMode
            data.animStyle = c.animStyle
            data.remark = c.remark
            data.isVisible = c.isVisible
            data.opacity = c.opacity
            data.collapsed = c.collapsed
            bindTexts()
            params.alpha = (data.opacity.coerceIn(20, 100)) / 100f
            safeUpdateLayout()
            applyCollapsed()
            applyKeepScreenOn()
            update()
        } catch (e: Throwable) {
            Log.w(TAG, "syncFrom: ${e.message}")
        }
    }

    /** 切换显示模式并立即重绘（由 Service 回调前调用）。 */
    fun setMode(mode: Int) {
        try {
            data.displayMode = mode
            bindTexts()
            update()
        } catch (e: Throwable) {
            Log.w(TAG, "setMode: ${e.message}")
        }
    }

    fun isDrawerOpen(): Boolean = drawerOpen

    /**
     * 是否应当保持屏幕常亮：仅当「悬浮框常亮」开关开启、且悬浮窗处于**展开**状态时为真。
     * 关闭开关、或把悬浮窗收缩成小条时都为假 —— 此时不干预屏幕，
     * 时间完全跟随手机系统设置（按系统休眠时间正常熄屏）。
     */
    private fun wantKeepScreenOn(): Boolean =
        ScreenKeepOn.isFloatOn(context) && !data.collapsed

    /**
     * 把「是否需要保持常亮」同步到窗口参数上（只在有变化时更新，避免无谓刷新）。
     * 在初始化、展开/收起切换、以及设置变化（主界面/设置页触发服务刷新）时调用。
     */
    fun applyKeepScreenOn() {
        try {
            val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            val want = wantKeepScreenOn()
            val has = (params.flags and flag) != 0
            if (want == has) return
            params.flags = if (want) params.flags or flag else params.flags and flag.inv()
            safeUpdateLayout()
        } catch (e: Throwable) {
            Log.w(TAG, "applyKeepScreenOn: ${e.message}")
        }
    }

    /** 当前悬浮窗在屏幕上的位置（隐藏/持久化时由 Service 读取）。 */
    fun getPosition(): Pair<Int, Int> = params.x to params.y

    private fun shiftMode(delta: Int) {
        // 与主界面的模式按钮一致：只在可用模式里循环
        val modes = CountdownFormatter.availableModes(data.builtIn)
        val i = modes.indexOf(data.displayMode)
        val next = modes[((i + delta) % modes.size + modes.size) % modes.size]
        onModeChange(data, next)
    }

    /** 收缩 / 放开：收缩后仅保留标题栏小条。 */
    private fun toggleCollapse() {
        data.collapsed = !data.collapsed
        applyCollapsed()
        onCollapseChange(data, data.collapsed)
    }

    private fun applyCollapsed() {
        try {
            val collapsed = data.collapsed
            val showBody = !collapsed
            timeRow.visibility = if (showBody) View.VISIBLE else View.GONE
            modeTv.visibility = if (showBody) View.VISIBLE else View.GONE
            remarkMain.visibility =
                if (showBody && data.remark.isNotEmpty()) View.VISIBLE else View.GONE
            collapseBtn.text = if (collapsed) "开" else "收"
            if (collapsed && drawerOpen) toggleDrawer()
            applyKeepScreenOn()
            view.post { clampIntoScreen() }
        } catch (e: Throwable) {
            Log.w(TAG, "applyCollapsed: ${e.message}")
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        return try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true
                    if (moved) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        safeUpdateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        onPositionChange(data, params.x, params.y)
                    } else {
                        if (data.collapsed) toggleCollapse() // 收缩条点一下即“放开”
                        else if (!drawerOpen) shiftMode(1)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "touch: ${e.message}")
            false
        }
    }

    /** 抽屉展开/收起（滑入动画 + 自动收回屏幕内）。 */
    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        drawerBtn.text = if (drawerOpen) "收起" else "菜单"
        if (drawerOpen) {
            drawerPanel.visibility = View.VISIBLE
            drawerPanel.alpha = 0f
            drawerPanel.translationX = -56f
            drawerPanel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(200)
                .setListener(null)
                .start()
            view.post { clampIntoScreen() }
        } else {
            drawerPanel.animate()
                .alpha(0f)
                .translationX(-56f)
                .setDuration(160)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        drawerPanel.visibility = View.GONE
                        clampIntoScreen()
                    }
                })
                .start()
        }
    }

    /** 窗体尺寸变化后（展开抽屉、收缩/放开、切换模式导致文本变长）把它拉回屏幕可见范围内。 */
    private fun clampIntoScreen() {
        try {
            if (!view.isAttachedToWindow) return
            val dm = context.resources.displayMetrics
            view.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
            )
            val w = view.measuredWidth
            val h = view.measuredHeight
            if (w <= 0 || h <= 0) return
            var x = params.x
            var y = params.y
            val maxX = dm.widthPixels - w
            val maxY = dm.heightPixels - h
            if (x > maxX) x = maxX
            if (y > maxY) y = maxY
            if (x < 0) x = 0
            if (y < 0) y = 0
            if (x != params.x || y != params.y) {
                params.x = x
                params.y = y
                safeUpdateLayout()
                onPositionChange(data, params.x, params.y)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "clamp: ${e.message}")
        }
    }

    private fun safeUpdateLayout() {
        try {
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
        } catch (e: Throwable) {
            Log.w(TAG, "updateViewLayout: ${e.message}")
        }
    }

    fun show() {
        try {
            if (!view.isAttachedToWindow) {
                windowManager.addView(view, params)
                view.post { clampIntoScreen() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "addView: ${e.message}")
        }
    }

    fun remove() {
        try {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        } catch (e: Throwable) {
            Log.w(TAG, "removeView: ${e.message}")
        }
    }

    private inline fun safe(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "$tag: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FloatingView"
    }
}
