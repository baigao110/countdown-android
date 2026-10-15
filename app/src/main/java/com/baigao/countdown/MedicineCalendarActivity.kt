package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 吃药日历：按月显示哪天吃了药、哪天没吃。
 *
 * - 已吃药：青色实心圆；未吃药（今天之前的日子）：淡红底；今天未吃：青色描边；
 *   未来日期：淡灰、不可点。
 * - 点过去 / 今天的格子可以补记或撤销；底部按钮一键记录今天。
 * - 顶部 ‹ › 切换月份，统计行显示本月已吃 / 未吃天数。
 *
 * 风格沿用液态玻璃：glass_bg 底 + GlassBackdrop 光斑 + circle_btn 青色按钮。
 */
class MedicineCalendarActivity : Activity() {

    private lateinit var monthTv: TextView
    private lateinit var grid: LinearLayout
    private lateinit var statTv: TextView
    private lateinit var todayBtn: Button

    /** 药名库管理弹窗的引用（导入后需要关掉重开以刷新列表）。 */
    private var medLibDialog: AlertDialog? = null

    /** SAF 导入 / 导出药名库的请求码。 */
    private val REQ_EXPORT_MED = 9101
    private val REQ_IMPORT_MED = 9102

    /** 当前显示的月份（day 恒为 1）。 */
    private val shown = Calendar.getInstance()
    private val today = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine)
        // 点开通知本体进到吃药日历：常驻提醒的使命完成，清掉它（点「已吃药」也会清）
        MedicineReminder.cancelNotification(this)

        monthTv = findViewById(R.id.monthTv)
        grid = findViewById(R.id.gridContainer)
        statTv = findViewById(R.id.statTv)
        todayBtn = findViewById(R.id.todayBtn)

        shown.set(Calendar.DAY_OF_MONTH, 1)
        shown.firstDayOfWeek = Calendar.MONDAY

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.prevBtn).setOnClickListener {
            shown.add(Calendar.MONTH, -1)
            render()
        }
        findViewById<Button>(R.id.nextBtn).setOnClickListener {
            shown.add(Calendar.MONTH, 1)
            render()
        }
        todayBtn.setOnClickListener { tryRecordToday() }
        findViewById<Button>(R.id.timesBtn).setOnClickListener { showTimesPicker() }
        findViewById<Button>(R.id.doseEditBtn).setOnClickListener {
            DoseEditor.showDialog(this) { render() }
        }
        // 「药名库」：每天吃什么药都从这里选；支持添加 / 导入 / 导出 / 删除 / 批量删除
        findViewById<Button>(R.id.medLibBtn).setOnClickListener { showMedLibraryDialog() }
        // 「记一笔吃药」：再记一次吃药事件（同一天可记多次；超过每天设定次数会弹提示框）
        findViewById<Button>(R.id.addLogBtn).setOnClickListener { tryRecordToday() }
        // 「清空记录」：把全部吃药记录清掉（带同款玻璃确认框）
        findViewById<Button>(R.id.clearLogBtn).setOnClickListener { showClearLogConfirm() }
        render(); renderLog()
    }

    override fun onResume() {
        super.onResume()
        // 屏幕常亮开关：开启时本应用在前台保持屏幕常亮不锁屏
        ScreenKeepOn.apply(this)
        today.timeInMillis = System.currentTimeMillis()
        render(); renderLog()
    }

    override fun onPause() {
        super.onPause()
        // 离开前台就清掉常亮 Flag，避免关掉开关后 Flag 残留在已暂停的窗口上
        ScreenKeepOn.onPause(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 圆形背景：已吃药（青实心）/ 未吃药（淡红）/ 今天（青描边）/ 未来（淡灰）。 */
    private fun bubble(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun render() {
        val fmt = MedicineReminder.keyFormat()
        val taken = MedicineReminder.takenKeys(this)

        monthTv.text = String.format(
            Locale.getDefault(), "%d年%d月",
            shown.get(Calendar.YEAR), shown.get(Calendar.MONTH) + 1
        )

        // ---------- 日期格子 ----------
        val first = shown.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        first.firstDayOfWeek = Calendar.MONDAY
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        // 周一为第一列：Calendar 的 DAY_OF_WEEK 以周日=1 起算，(dow+5)%7 即周一起的偏移
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7

        val cells = ArrayList<Int>()
        repeat(lead) { cells.add(0) }
        for (d in 1..daysInMonth) cells.add(d)
        while (cells.size % 7 != 0) cells.add(0)

        grid.removeAllViews()
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            )
            for (k in 0 until 7) {
                row.addView(cellView(cells[i + k], fmt, taken))
            }
            grid.addView(row)
            i += 7
        }

        // ---------- 统计 ----------
        var took = 0
        var missed = 0
        for (d in 1..daysInMonth) {
            val c = shown.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, d)
            val key = fmt.format(c.time)
            val isFuture = c.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
                    (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            c.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))
            if (isFuture) continue
            if (taken.contains(key)) took++ else missed++
        }
        val medN = MedicineReminder.timesPerDay(this)
        val statBase = SpannableStringBuilder(
            "本月吃过 ${took} 天 · 还没吃 ${missed} 天" +
                    if (missed > 0) "（没吃药的日子可以在日历里补记哦）" else ""
        )
        if (medN > 1) {
            statBase.append("\n每天会提醒 ${medN} 次哦：")
                .append(MedicineReminder.doseScheduleSpannable(this))
        }
        statBase.append("\n服药时机：${MedicineReminder.DOSE_CONTEXTS.joinToString(" / ")}")
        statTv.text = statBase

        // ---------- 今日按钮 ----------
        val tCount = MedicineReminder.takeCountToday(this)
        todayBtn.text = if (tCount > 0) "今天已吃 $tCount 次 · 再记一笔" else "记一笔吃药（今天）"
    }

    private fun cellView(day: Int, fmt: java.text.SimpleDateFormat, taken: Set<String>): FrameLayout {
        val box = FrameLayout(this)
        box.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        if (day <= 0) return box

        val c = shown.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, day)
        val key = fmt.format(c.time)

        val sameYear = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val isToday = sameYear && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val isFuture = (c.get(Calendar.YEAR) > today.get(Calendar.YEAR)) ||
                (sameYear && c.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))
        val isTaken = taken.contains(key)

        val tv = TextView(this)
        tv.text = day.toString()
        tv.gravity = Gravity.CENTER
        tv.textSize = 14f
        tv.setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())

        when {
            isTaken -> {
                tv.background = bubble(0xFF00FFFF.toInt(), 0)
                tv.setTextColor(0xFF001018.toInt())
            }
            isFuture -> {
                tv.background = bubble(0x1AFFFFFF, 0)
                tv.setTextColor(0xFF7C86A3.toInt())
            }
            isToday -> {
                tv.background = bubble(0x00000000, 0xFF4DFBFF.toInt())
                tv.setTextColor(0xFF4DFBFF.toInt())
            }
            else -> {
                // 已经过去却没记录：淡红底，一眼看出漏了
                tv.background = bubble(0x3CFF5A6E.toInt(), 0)
                tv.setTextColor(0xFFFFD8DE.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(dp(34), dp(34))
        lp.gravity = Gravity.CENTER
        box.addView(tv, lp)

        // 过去与今天的格子可点：补记 / 撤销（补记会记下精确到秒的吃药时刻）
        if (!isFuture) {
            box.setOnClickListener {
                val nowTaken = MedicineReminder.isTaken(this, key)
                if (nowTaken) {
                    MedicineReminder.removeTakeDay(this, key)
                    Toast.makeText(
                        this,
                        "已经撤销 ${monthLabel(day)} 的记录啦",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pickAndRecordForDay(c, key, monthLabel(day))
                }
                render(); renderLog()
            }
        }
        return box
    }

    private fun monthLabel(day: Int): String =
        "${shown.get(Calendar.MONTH) + 1}月${day}日"

    /** 「每天次数」选择：1/2/3/4 次（与「关于」页同一逻辑）。 */
    private fun showTimesPicker() {
        if (isFinishing) return
        val opts = arrayOf("1 次 / 天", "2 次 / 天", "3 次 / 天", "4 次 / 天")
        val cur = MedicineReminder.timesPerDay(this) - 1
        AlertDialog.Builder(this)
            .setTitle("每天想提醒几次呀")
            .setSingleChoiceItems(opts, cur) { d, which ->
                MedicineReminder.setTimesPerDay(this, which + 1)
                render()
                d.dismiss()
                Toast.makeText(this, "已经设为每天 ${which + 1} 次啦", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("先不了", null)
            .show()
    }

    /** 吃药记录日志：按日期分组，每天每条显示「药名 · 年月日时分秒」，每条可单独删除。 */
    private fun renderLog() {
        val container = findViewById<LinearLayout>(R.id.logContainer)
        val emptyTv = findViewById<TextView>(R.id.logEmptyTv)
        container.removeAllViews()
        val groups = MedicineReminder.takeLogGroupedByDay(this)
        if (groups.isEmpty()) {
            emptyTv.visibility = View.VISIBLE
            return
        }
        emptyTv.visibility = View.GONE
        val todayKey = MedicineReminder.todayKey()
        for ((dayKey, events) in groups) {
            val w = MedicineReminder.dayWeekday(dayKey)
            val label = if (dayKey == todayKey) "$dayKey（今天 $w）" else "$dayKey（$w）"
            val head = TextView(this)
            head.text = "$label：已吃药 ${events.size} 次"
            head.setTextColor(0xFF00FFFF.toInt())
            head.textSize = 14f
            head.setTypeface(null, Typeface.BOLD)
            head.setPadding(0, dp(8), 0, dp(4))
            container.addView(head)

            for (ev in events) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(8), dp(4), dp(8), dp(4))
                row.background = pressBg()

                val med = if (ev.medName.isNotEmpty()) ev.medName else "（未指定药名）"
                val info = TextView(this)
                info.text = "· $med  ${MedicineReminder.formatTakeEvent(ev.ts)}"
                info.setTextColor(0xFFEAF6FF.toInt())
                info.textSize = 13f
                info.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )

                val del = Button(this)
                del.text = "×"
                del.textSize = 16f
                del.setTextColor(0xFF001018.toInt())
                del.setBackgroundResource(R.drawable.circle_btn)
                val dlp = LinearLayout.LayoutParams(dp(30), dp(30))
                dlp.leftMargin = dp(6)
                del.layoutParams = dlp
                del.setOnClickListener {
                    MedicineReminder.removeTakeEvent(this, ev.ts)
                    render(); renderLog()
                    Toast.makeText(this, "已删除这条记录", Toast.LENGTH_SHORT).show()
                }

                row.addView(info)
                row.addView(del)
                container.addView(row)
            }
        }
    }

    /** 记一笔今天的吃药：从药名库选药；超过当天设定次数时弹同款玻璃提示框「今天已吃药了，不要重复吃药哦！」。 */
    private fun tryRecordToday() {
        val count = MedicineReminder.takeCountToday(this)
        val limit = MedicineReminder.timesPerDay(this)
        val pick = {
            showDrugPicker { med ->
                val ts = MedicineReminder.recordTakeEvent(
                    this, System.currentTimeMillis(), MedicineReminder.todayKey(), med
                )
                Toast.makeText(
                    this,
                    "已记录「$med」：${MedicineReminder.formatTakeEvent(ts)}",
                    Toast.LENGTH_SHORT
                ).show()
                render(); renderLog()
            }
        }
        if (count >= limit) {
            if (isFinishing) return
            UpdateManager.showStyledDialog(
                activity = this,
                title = "今天已吃药了",
                positiveText = "仍要记录",
                negativeText = "取消",
                onPositive = { pick() }
            ) { host ->
                val tv = TextView(this).apply {
                    text = "不要重复吃药哦！今天已经记了 ${count} 次，确定还要再记一笔吗？"
                    setTextColor(0xFFE4EEFF.toInt())
                    textSize = 14f
                    setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                host.addView(tv)
            }
            return
        }
        pick()
    }

    /** 「清空记录」确认框：和其它提示框同款玻璃风格。 */
    private fun showClearLogConfirm() {
        if (isFinishing) return
        UpdateManager.showStyledDialog(
            activity = this,
            title = "清空吃药记录",
            positiveText = "清空",
            negativeText = "先不了",
            onPositive = {
                MedicineReminder.clearTakeLog(this)
                render(); renderLog()
                Toast.makeText(this, "已经清空全部吃药记录啦", Toast.LENGTH_SHORT).show()
            }
        ) { host ->
            val tv = TextView(this).apply {
                text = "确定要清空全部吃药记录吗？清空后没法恢复哦（当天「已吃」标记也会一起去掉）。"
                setTextColor(0xFFE4EEFF.toInt())
                textSize = 14f
                setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            host.addView(tv)
        }
    }

    // ---------------- 药名库 / 选药 / 导入导出 ----------------

    /** 带按压缩放高亮的青色胶囊按钮。 */
    private fun makeCircleButton(text: String, weight: Float = 0f, textSize: Float = 14f): Button {
        val b = Button(this)
        b.text = text
        b.textSize = textSize
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(0xFF001018.toInt())
        b.setBackgroundResource(R.drawable.circle_btn)
        // 自定义背景必须清掉系统 Button 默认的内边距 / 最小宽度，否则窄按钮里的文字会被裁掉。
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.includeFontPadding = false
        b.gravity = Gravity.CENTER
        b.stateListAnimator = null
        if (weight > 0f) b.isSingleLine = true
        val hPad = if (weight > 0f) dp(2) else dp(16)
        b.setPadding(hPad, 0, hPad, 0)
        val lp = LinearLayout.LayoutParams(
            if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)
        )
        if (weight > 0f) {
            lp.weight = weight
            lp.marginEnd = dp(4)
        }
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    /** 列表项按下高亮（复用主题色）。 */
    private fun pressBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply { setColor(0x33FFFFFF.toInt()) })
        addState(intArrayOf(), GradientDrawable().apply { setColor(0x00000000) })
    }

    /** 统一的「提示框」（同款玻璃风）。 */
    private fun showMsg(title: String, msg: String) {
        UpdateManager.showStyledDialog(
            activity = this, title = title, positiveText = "好的", negativeText = null, onPositive = null
        ) { host ->
            val tv = TextView(this).apply {
                text = msg
                setTextColor(0xFFEAF6FF.toInt()); textSize = 14f
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            host.addView(tv)
        }
    }

    /** 两个 Calendar 是否同一天。 */
    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** 从药名库选一种药：输入框支持模糊搜索（子串匹配），点药名即选中；无匹配可顺手加入药名库。 */
    private fun showDrugPicker(onPicked: (String) -> Unit) {
        if (isFinishing) return
        val act = this
        val all = MedicineReminder.getMedNames(act).toMutableList()
        val listContainer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(act).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (act.resources.displayMetrics.heightPixels * 0.38).toInt()
            )
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(listContainer)
        }
        val edit = EditText(act).apply {
            hint = "输入药名搜索（如「阿」）"
            setBackgroundResource(R.drawable.field_bg)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666680.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(edit)
            addView(scroll)
        }

        lateinit var dlg: AlertDialog

        fun refresh(q: String) {
            listContainer.removeAllViews()
            val q2 = q.trim()
            val filtered = if (q2.isEmpty()) all else all.filter { it.contains(q2, ignoreCase = true) }
            if (filtered.isEmpty()) {
                if (q2.isNotEmpty() && !all.any { it.equals(q2, true) }) {
                    val add = makeCircleButton("＋ 把「$q2」加入药名库")
                    add.setOnClickListener {
                        if (MedicineReminder.addMedName(act, q2)) {
                            all.add(q2)
                            Toast.makeText(act, "已加入药名库", Toast.LENGTH_SHORT).show()
                            refresh(q2)
                        } else {
                            showDupMedDialog(q2)
                        }
                    }
                    listContainer.addView(add)
                } else {
                    val t = TextView(act).apply {
                        text = if (all.isEmpty()) "药名库还是空的，先去「药名库」加几个吧" else "没有匹配「$q2」的药名"
                        setTextColor(0xFFA8B3CC.toInt())
                        textSize = 13f
                        setPadding(0, dp(8), 0, dp(8))
                    }
                    listContainer.addView(t)
                }
            } else {
                filtered.forEach { name ->
                    val item = TextView(act).apply {
                        text = name
                        setTextColor(0xFFEAF6FF.toInt())
                        textSize = 15f
                        setPadding(dp(10), dp(12), dp(10), dp(12))
                        background = pressBg()
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    item.setOnClickListener {
                        dlg.dismiss()
                        onPicked(name)
                    }
                    listContainer.addView(item)
                }
            }
        }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, p: Int, b: Int, c: Int) {
                refresh(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dlg = UpdateManager.showStyledDialog(
            activity = act,
            title = "选择今天吃的药",
            positiveText = "取消",
            negativeText = null,
            onPositive = {}
        ) { host -> host.addView(root) }
        refresh("")
    }

    /** 给某一天（过去或今天）补记一笔：选药后写入对应日期。 */
    private fun pickAndRecordForDay(cal: Calendar, dayKey: String, label: String) {
        showDrugPicker { med ->
            val now = Calendar.getInstance()
            val whenMillis = if (sameDay(cal, now)) System.currentTimeMillis()
            else (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val ts = MedicineReminder.recordTakeEvent(this, whenMillis, dayKey, med)
            Toast.makeText(
                this,
                "已经补记 $label 的「$med」啦（${MedicineReminder.formatTakeEvent(ts)}）",
                Toast.LENGTH_SHORT
            ).show()
            render(); renderLog()
        }
    }

    /** 重复药名提示（与其它提示框同款玻璃风格）。 */
    private fun showDupMedDialog(name: String) {
        if (isFinishing) return
        UpdateManager.showStyledDialog(
            activity = this,
            title = "无法添加",
            positiveText = "知道了",
            negativeText = null,
            onPositive = {}
        ) { host ->
            val tv = TextView(this).apply {
                text = "「$name」该药已添加，请勿重复添加！"
                setTextColor(0xFFE4EEFF.toInt())
                textSize = 14f
                setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            host.addView(tv)
        }
    }

    /** 在药名库里新增一个药名（重复则弹「该药已添加」）。 */
    private fun showAddMedDialog(onAdded: () -> Unit) {
        if (isFinishing) return
        val edit = EditText(this).apply {
            hint = "输入药名（如 阿司匹林）"
            setBackgroundResource(R.drawable.field_bg)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666680.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(edit)
        }
        UpdateManager.showStyledDialog(
            activity = this,
            title = "添加药名",
            positiveText = "添加",
            negativeText = "取消",
            onPositive = {
                val n = edit.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this, "药名不能为空", Toast.LENGTH_SHORT).show()
                    return@showStyledDialog
                }
                if (MedicineReminder.addMedName(this, n)) {
                    Toast.makeText(this, "已添加「$n」", Toast.LENGTH_SHORT).show()
                    onAdded()
                } else {
                    showDupMedDialog(n)
                }
            }
        ) { host -> host.addView(root) }
    }

    /** 药名库管理：列表 + 添加 / 导入 / 导出 / 删除 / 批量删除。 */
    private fun showMedLibraryDialog() {
        if (isFinishing) return
        val act = this
        val listContainer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val scroll = ScrollView(act).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (act.resources.displayMetrics.heightPixels * 0.38).toInt()
            )
            clipToPadding = false
            addView(listContainer)
        }
        val batchBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val selCountTv = TextView(act).apply {
            text = "已选 0 个"
            setTextColor(0xFF00FFFF.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val delSelBtn = makeCircleButton("删除选中")
        val cancelSelBtn = makeCircleButton("取消选择")
        batchBar.addView(selCountTv)
        batchBar.addView(delSelBtn)
        batchBar.addView(cancelSelBtn)

        val btnRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val addBtn = makeCircleButton("＋添加", 1f, 13f)
        val importBtn = makeCircleButton("导入", 1f, 13f)
        val exportBtn = makeCircleButton("导出", 1f, 13f)
        val batchBtn = makeCircleButton("批量删除", 1f, 13f)
        btnRow.addView(addBtn); btnRow.addView(importBtn)
        btnRow.addView(exportBtn); btnRow.addView(batchBtn)

        val syncRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        // 按钮按内容宽度、右侧信息占满剩余空间，避免把「上次云端同步：…」挤成两行
        val syncBtn = makeCircleButton("同步药监局", 0f, 13f)
        val syncInfo = TextView(act).apply {
            textSize = 12f
            setTextColor(0xFFA8B3CC.toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, 0, 0)
        }
        syncRow.addView(syncBtn); syncRow.addView(syncInfo)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(scroll)
            addView(batchBar)
            addView(syncRow)
            addView(btnRow)
        }

        val selected = LinkedHashSet<String>()
        var selecting = false

        fun updateSel() {
            selCountTv.text = "已选 ${selected.size} 个"
            delSelBtn.text = "删除选中(${selected.size})"
        }

        fun renderList() {
            listContainer.removeAllViews()
            val names = MedicineReminder.getMedNames(act)
            if (names.isEmpty()) {
                val t = TextView(act).apply {
                    text = "药名库还是空的，点下面「＋ 添加药名」加几个吧"
                    setTextColor(0xFFA8B3CC.toInt()); textSize = 13f
                    setPadding(0, dp(8), 0, dp(8))
                }
                listContainer.addView(t)
                return
            }
            names.forEach { name ->
                val row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(6), dp(8), dp(6), dp(8))
                if (selecting) row.background = pressBg()
                if (selected.contains(name)) row.setBackgroundColor(0x335AC8FA.toInt())

                val nameTv = TextView(act).apply {
                    text = name
                    setTextColor(0xFFEAF6FF.toInt()); textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val del = Button(act).apply {
                    text = "×"
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFF001018.toInt())
                    setBackgroundResource(R.drawable.circle_btn)
                    // 清掉系统 Button 默认内边距 / 最小宽度，否则 30dp 小圆钮里的「×」会被挤没。
                    isAllCaps = false
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    stateListAnimator = null
                    val dlp = LinearLayout.LayoutParams(dp(32), dp(32)); dlp.leftMargin = dp(8)
                    layoutParams = dlp
                }
                del.setOnClickListener {
                    MedicineReminder.removeMedName(act, name)
                    selected.remove(name)
                    renderList()
                }
                row.addView(nameTv); row.addView(del)
                if (selecting) {
                    row.setOnClickListener {
                        if (selected.contains(name)) selected.remove(name) else selected.add(name)
                        renderList()
                    }
                }
                listContainer.addView(row)
            }
        }

        fun updateSyncInfo() {
            val t = MedicineReminder.getMedSyncTime(act)
            val bt = MedicineReminder.getMedBundledTime(act)
            syncInfo.text = when {
                t != 0L -> "上次云端同步：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", t).toString()
                bt != 0L -> "已使用内置官方药名库（" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", bt).toString() + "）"
                else -> "尚未从药监局同步"
            }
        }

        fun syncFromNMPA(manual: Boolean) {
            if (!manual) {
                val last = MedicineReminder.getMedSyncTime(act)
                // 后台自动同步：仅在从未同步或距上次超过 7 天才执行一次
                if (last != 0L && System.currentTimeMillis() - last < 7L * 24 * 3600 * 1000) return
            }
            val progress = if (manual) UpdateManager.showStyledDialog(
                activity = act, title = "同步中", positiveText = "", negativeText = null, onPositive = null
            ) { host ->
                val tv = TextView(act).apply {
                    text = "正在从国家药监局同步药名库…"
                    setTextColor(0xFFEAF6FF.toInt()); textSize = 14f
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
                host.addView(tv)
            } else null
            Thread {
                val res = MedicineReminder.syncMedNamesFromNMPA(act)
                runOnUiThread {
                    progress?.dismiss()
                    updateSyncInfo()
                    if (res.ok) {
                        if (manual || res.added > 0) {
                            val msg = when {
                                res.source == "github" && res.added > 0 ->
                                    "已从国家药监局同步：新增 ${res.added} 个药名（共 ${res.total} 个）"
                                res.source == "github" ->
                                    "药名库已是最新（共 ${res.total} 个）"
                                res.added > 0 ->
                                    "已从内置官方药名库载入：新增 ${res.added} 个药名（共 ${res.total} 个）\n联网后可同步最新版本"
                                else ->
                                    "药名库已包含内置官方药名库（共 ${res.total} 个）\n联网后可同步最新版本"
                            }
                            showMsg("同步完成", msg)
                        }
                        if (res.added > 0) renderList()
                    } else if (manual) {
                        showMsg("同步失败", "无法从国家药监局获取药名库：${res.msg}\n请检查网络或稍后重试。")
                    }
                }
            }.start()
        }

        addBtn.setOnClickListener { showAddMedDialog { renderList() } }
        syncBtn.setOnClickListener { syncFromNMPA(manual = true) }
        importBtn.setOnClickListener {
            medLibDialog?.dismiss()
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT_MED)
        }
        exportBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "药名库.json")
            }
            startActivityForResult(i, REQ_EXPORT_MED)
        }
        batchBtn.setOnClickListener {
            selecting = !selecting
            if (!selecting) selected.clear()
            batchBar.visibility = if (selecting) View.VISIBLE else View.GONE
            batchBtn.text = if (selecting) "退出选择" else "批量删除"
            renderList(); updateSel()
        }
        delSelBtn.setOnClickListener {
            if (selected.isEmpty()) {
                Toast.makeText(act, "还没选要删的药名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cnt = selected.size
            selected.toList().forEach { MedicineReminder.removeMedName(act, it) }
            selected.clear(); selecting = false
            batchBar.visibility = View.GONE; batchBtn.text = "批量删除"
            renderList(); updateSel()
            Toast.makeText(act, "已删除 $cnt 个药名", Toast.LENGTH_SHORT).show()
        }
        cancelSelBtn.setOnClickListener {
            selected.clear(); selecting = false
            batchBar.visibility = View.GONE; batchBtn.text = "批量删除"
            renderList(); updateSel()
        }

        medLibDialog = UpdateManager.showStyledDialog(
            activity = act,
            title = "药名库",
            positiveText = "关闭",
            negativeText = null,
            onPositive = {}
        ) { host -> host.addView(root) }
        updateSyncInfo()
        syncFromNMPA(manual = false)
        renderList()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uri: Uri = data.data ?: return
        when (requestCode) {
            REQ_EXPORT_MED -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        OutputStreamWriter(os, "UTF-8").use { it.write(MedicineReminder.exportMedNamesJson(this)) }
                    }
                    Toast.makeText(this, "药名库已导出", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_IMPORT_MED -> {
                try {
                    val sb = StringBuilder()
                    contentResolver.openInputStream(uri)?.use { `in` ->
                        BufferedReader(InputStreamReader(`in`, "UTF-8")).use { r ->
                            r.forEachLine { sb.append(it) }
                        }
                    }
                    val added = MedicineReminder.importMedNames(this, sb.toString())
                    Toast.makeText(this, "导入完成，新增 $added 条", Toast.LENGTH_SHORT).show()
                    medLibDialog?.dismiss()
                    showMedLibraryDialog()
                } catch (e: Throwable) {
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
