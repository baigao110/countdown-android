package com.baigao.countdown

import android.app.Activity
import android.app.TimePickerDialog
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

/**
 * 吃药时间面板：编辑「每天 N 次」里每一次的服药时刻与「服药时机」（8 选 1）。
 * 「关于」页的吃药时间按钮与「吃药日历」共用，保证两处完全一致。
 */
object DoseEditor {

    fun showDialog(activity: Activity, onSaved: () -> Unit) {
        if (activity.isFinishing) return
        val density = activity.resources.displayMetrics.density

        val n = MedicineReminder.timesPerDay(activity)
        val times = MedicineReminder.doseTimes(activity).toMutableList()
        while (times.size < n) times.add(0 to 0)
        val ctxIdx = (0 until n).map { MedicineReminder.doseContextOf(activity, it) }.toMutableList()

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(
            (8 * density).toInt(), (8 * density).toInt(),
            (8 * density).toInt(), (8 * density).toInt()
        )

        val tip = TextView(activity).apply {
            text = "每天 ${MedicineReminder.timesPerDay(activity)} 次，可分别改时刻与服药时机；" +
                "点「保存」即生效并覆盖全天 24 小时。"
            setTextColor(0xFFC6D5EF.toInt())
            textSize = 13f
        }
        root.addView(tip)

        for (i in 0 until MedicineReminder.timesPerDay(activity)) {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val rlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rlp.topMargin = (8 * density).toInt()
            row.layoutParams = rlp

            val mark = TextView(activity).apply {
                text = MedicineReminder.doseMarker(i)
                setTextColor(MedicineReminder.doseColor(i))
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (28 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(mark)

            val timeBtn = Button(activity).apply {
                text = String.format(Locale.getDefault(), "%02d:%02d", times[i].first, times[i].second)
                textSize = 14f
                setTextColor(0xFF001018.toInt())
                setBackgroundResource(R.drawable.circle_btn)
                val blp = LinearLayout.LayoutParams(
                    (96 * density).toInt(), (40 * density).toInt()
                )
                blp.marginStart = (8 * density).toInt()
                layoutParams = blp
            }
            timeBtn.setOnClickListener {
                TimePickerDialog(
                    activity, { _, hh, mm ->
                        times[i] = hh to mm
                        timeBtn.text = String.format(Locale.getDefault(), "%02d:%02d", hh, mm)
                    }, times[i].first, times[i].second, true
                ).show()
            }
            row.addView(timeBtn)

            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(
                    activity, android.R.layout.simple_spinner_item,
                    MedicineReminder.DOSE_CONTEXTS.asList()
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(ctxIdx[i])
                val slp = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
                slp.marginStart = (8 * density).toInt()
                layoutParams = slp
            }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>, v: View?, pos: Int, id: Long
                ) {
                    ctxIdx[i] = pos
                }

                override fun onNothingSelected(p: AdapterView<*>) {}
            }
            row.addView(spinner)

            root.addView(row)
        }

        val desc = TextView(activity).apply {
            val sb = SpannableStringBuilder()
            MedicineReminder.DOSE_CONTEXT_DESC.forEachIndexed { idx, d ->
                sb.append(MedicineReminder.doseMarker(idx)).append(' ').append(d).append("\n")
            }
            text = sb.toString().trimEnd()
            setTextColor(0xFFC6D5EF.toInt())
            textSize = 12f
            setLineSpacing(4f, 1.1f)
            val dlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            dlp.topMargin = (10 * density).toInt()
            layoutParams = dlp
        }
        root.addView(desc)

        UpdateManager.showStyledDialog(
            activity = activity,
            title = "吃药时间设置",
            positiveText = "保存",
            negativeText = "取消",
            onPositive = {
                MedicineReminder.applyDosePlan(activity, times, ctxIdx)
                Toast.makeText(activity, "已保存并同步 24 小时", Toast.LENGTH_SHORT).show()
                onSaved()
            }
        ) { host: LinearLayout ->
            host.addView(root)
        }
    }
}
