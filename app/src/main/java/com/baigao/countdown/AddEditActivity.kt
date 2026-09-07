package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.Spinner
import android.widget.TimePicker
import android.widget.Toast
import java.util.Calendar

class AddEditActivity : Activity() {

    private var editId: String? = null
    private var pickSoundOnly = false
    private var fromFloating = false
    private var pendingSoundId: String? = null
    private var editTarget: Countdown? = null
    private var soundBtn: Button? = null

    private val colors = intArrayOf(
        0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FF00.toInt(),
        0xFFFFFF00.toInt(), 0xFFFF8000.toInt(), 0xFFFF0000.toInt(), 0xFF0080FF.toInt()
    )
    private val colorNames = arrayOf("青色", "品红", "绿色", "黄色", "橙色", "红色", "蓝色")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editId = intent.getStringExtra("id")
        pickSoundOnly = intent.getBooleanExtra("pickSoundOnly", false)
        fromFloating = intent.getBooleanExtra("fromFloating", false)

        val c = if (editId != null) CountdownStore.load(this).find { it.id == editId } else null
        editTarget = c

        if (pickSoundOnly && c != null) {
            pickSound(c)
            return
        }

        setContentView(R.layout.activity_add_edit)

        val titleEt = findViewById<EditText>(R.id.etTitle)
        val datePicker = findViewById<DatePicker>(R.id.datePicker)
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        val colorSpinner = findViewById<Spinner>(R.id.colorSpinner)
        val modeSpinner = findViewById<Spinner>(R.id.modeSpinner)
        val remarkEt = findViewById<EditText>(R.id.etRemark)
        val soundBtn = findViewById<Button>(R.id.btnSound)
        this.soundBtn = soundBtn
        val clearSoundBtn = findViewById<Button>(R.id.btnClearSound)
        val saveBtn = findViewById<Button>(R.id.btnSave)
        val cancelBtn = findViewById<Button>(R.id.btnCancel)

        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colorNames)
        (colorSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, CountdownFormatter.MODE_NAMES)
        (modeSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        if (c != null) {
            titleEt.setText(c.title)
            val cal = Calendar.getInstance().apply { timeInMillis = c.targetTime }
            datePicker.updateDate(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            timePicker.hour = cal.get(Calendar.HOUR_OF_DAY)
            timePicker.minute = cal.get(Calendar.MINUTE)
            var ci = colors.indexOfFirst { it == c.customColorArgb }
            if (ci < 0) ci = 0
            colorSpinner.setSelection(ci)
            modeSpinner.setSelection(c.displayMode.coerceIn(0, CountdownFormatter.MODE_NAMES.size - 1))
            remarkEt.setText(c.remark)
            soundBtn.text = if (c.soundUri != null) {
                "已选择：${ringtoneName(Uri.parse(c.soundUri)) ?: "自定义提示音"}（点击更换）"
            } else {
                "使用默认提示音（点击选择）"
            }
        } else {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
            datePicker.updateDate(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            timePicker.hour = cal.get(Calendar.HOUR_OF_DAY)
            timePicker.minute = cal.get(Calendar.MINUTE)
        }

        soundBtn.setOnClickListener {
            if (c != null) pickSound(c)
            else Toast.makeText(this, "请先保存后再设置提示音", Toast.LENGTH_SHORT).show()
        }
        clearSoundBtn.setOnClickListener {
            if (c != null) {
                val list = CountdownStore.load(this)
                list.find { it.id == c.id }?.soundUri = null
                CountdownStore.save(this, list)
                c.soundUri = null
            }
            soundBtn.text = "使用默认提示音（点击选择）"
        }
        saveBtn.setOnClickListener {
            val list = CountdownStore.load(this)
            val target = Calendar.getInstance().apply {
                set(Calendar.YEAR, datePicker.year)
                set(Calendar.MONTH, datePicker.month)
                set(Calendar.DAY_OF_MONTH, datePicker.dayOfMonth)
                set(Calendar.HOUR_OF_DAY, timePicker.hour)
                set(Calendar.MINUTE, timePicker.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            if (c != null) {
                // 关键修复：必须修改“即将保存的 list”里的同一个对象，否则改的是
                // onCreate 里另一份旧列表的副本，磁盘上不会被更新（改名/改其它字段都无效）。
                val existing = list.find { it.id == c.id }
                if (existing != null) {
                    existing.title = titleEt.text.toString()
                    existing.targetTime = target
                    existing.customColorArgb = colors[colorSpinner.selectedItemPosition]
                    existing.displayMode = modeSpinner.selectedItemPosition
                    existing.remark = remarkEt.text.toString()
                }
            } else {
                list.add(
                    Countdown(
                        title = titleEt.text.toString(),
                        targetTime = target,
                        customColorArgb = colors[colorSpinner.selectedItemPosition],
                        displayMode = modeSpinner.selectedItemPosition,
                        remark = remarkEt.text.toString()
                    )
                )
            }
            CountdownStore.save(this, list)
            // 若是从悬浮窗进入的编辑，保存后刷新后台服务里的悬浮窗
            if (fromFloating) {
                val ri = Intent(this, CountdownService::class.java)
                ri.action = CountdownService.ACTION_START
                startForegroundService(ri)
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
        cancelBtn.setOnClickListener { finish() }
    }

    /** 取铃声 URI 对应的显示名称（用于选择后回显到按钮）。 */
    private fun ringtoneName(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this)
        } catch (e: Throwable) {
            null
        }
    }

    private fun pickSound(c: Countdown) {
        pendingSoundId = c.id
        // 使用系统“铃声”选择器，直接列出手机铃声/通知音等作为倒计时提示音选项
        val existing = if (c.soundUri != null) Uri.parse(c.soundUri) else null
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择倒计时提示音")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        }
        startActivityForResult(i, 200)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK) {
            // 选中“默认”时返回 null（约定为默认提示音，即内置 beep）
            @Suppress("DEPRECATION")
            val picked = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
            if (pendingSoundId != null) {
                val list = CountdownStore.load(this)
                list.find { it.id == pendingSoundId }?.soundUri = picked?.toString()
                CountdownStore.save(this, list)
                editTarget?.soundUri = picked?.toString()
            }
            pendingSoundId = null
            if (pickSoundOnly) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                soundBtn?.text = if (picked != null) {
                    "已选择：${ringtoneName(picked) ?: "自定义提示音"}（点击更换）"
                } else {
                    "使用默认提示音（点击选择）"
                }
            }
        }
    }
}
