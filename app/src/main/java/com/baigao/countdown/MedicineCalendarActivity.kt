package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.text.SpannableStringBuilder

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
                    val ts = MedicineReminder.recordTakeEvent(this, System.currentTimeMillis(), key)
                    Toast.makeText(
                        this,
                        "已经补记 ${monthLabel(day)} 啦（${MedicineReminder.formatTakeEvent(ts)}）",
                        Toast.LENGTH_SHORT
                    ).show()
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

    /** 吃药记录日志：按日期分组，每组显示「当天吃了几次」。 */
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
        for ((dayKey, times) in groups) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val w = MedicineReminder.dayWeekday(dayKey)
            val label = if (dayKey == todayKey) "$dayKey（今天 $w）" else "$dayKey（$w）"
            val head = TextView(this)
            head.text = "$label：已吃药 ${times.size} 次"
            head.setTextColor(0xFF00FFFF.toInt())
            head.textSize = 14f
            head.setTypeface(null, Typeface.BOLD)
            head.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val del = Button(this)
            del.text = "×"
            del.textSize = 18f
            del.setTextColor(0xFF001018.toInt())
            del.setBackgroundResource(R.drawable.circle_btn)
            val dlp = LinearLayout.LayoutParams(dp(34), dp(34))
            dlp.leftMargin = dp(8)
            del.layoutParams = dlp
            del.setOnClickListener {
                MedicineReminder.removeTakeDay(this, dayKey)
                render(); renderLog()
                Toast.makeText(this, "已经撤销 ${dayKey} 的记录了", Toast.LENGTH_SHORT).show()
            }

            row.addView(head)
            row.addView(del)
            container.addView(row)
        }
    }

    /** 记一笔今天的吃药：超过当天设定次数时弹同款玻璃提示框「今天已吃药了，不要重复吃药哦！」。 */
    private fun tryRecordToday() {
        val count = MedicineReminder.takeCountToday(this)
        val limit = MedicineReminder.timesPerDay(this)
        if (count >= limit) {
            if (isFinishing) return
            UpdateManager.showStyledDialog(
                activity = this,
                title = "今天已吃药了",
                positiveText = "仍要记录",
                negativeText = "取消",
                onPositive = {
                    val ts = MedicineReminder.recordTakeEvent(this)
                    Toast.makeText(
                        this,
                        "记下一笔啦（${MedicineReminder.formatTakeEvent(ts)}）",
                        Toast.LENGTH_SHORT
                    ).show()
                    render(); renderLog()
                }
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
        val ts = MedicineReminder.recordTakeEvent(this)
        Toast.makeText(
            this,
            "记下一笔啦（${MedicineReminder.formatTakeEvent(ts)}）",
            Toast.LENGTH_SHORT
        ).show()
        render(); renderLog()
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
}
