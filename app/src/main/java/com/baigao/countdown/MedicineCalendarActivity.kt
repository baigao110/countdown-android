package com.baigao.countdown

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
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

    /** 当前显示的月份（day 恒为 1）。 */
    private val shown = Calendar.getInstance()
    private val today = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine)

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
        todayBtn.setOnClickListener {
            val taken = MedicineReminder.isTaken(this)
            MedicineReminder.setTaken(this, !taken)
            Toast.makeText(
                this,
                if (taken) "已撤销今天的记录" else "已记录今天吃药",
                Toast.LENGTH_SHORT
            ).show()
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        today.timeInMillis = System.currentTimeMillis()
        render()
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
        statTv.text = "本月已吃药 ${took} 天 · 未吃药 ${missed} 天" +
                if (missed > 0) "（未吃药的日子可在日历里补记）" else ""

        // ---------- 今日按钮 ----------
        todayBtn.text = if (MedicineReminder.isTaken(this)) "撤销今日记录" else "今日已吃药"
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
                tv.setTextColor(0xFF7C86A0.toInt())
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

        // 过去与今天的格子可点：补记 / 撤销
        if (!isFuture) {
            box.setOnClickListener {
                val nowTaken = MedicineReminder.isTaken(this, key)
                MedicineReminder.setTaken(this, !nowTaken, key)
                Toast.makeText(
                    this,
                    if (nowTaken) "已撤销 ${monthLabel(day)} 的记录" else "已补记 ${monthLabel(day)}",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
        return box
    }

    private fun monthLabel(day: Int): String =
        "${shown.get(Calendar.MONTH) + 1}月${day}日"
}
