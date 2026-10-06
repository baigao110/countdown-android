package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
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
    private var draftSoundUri: String? = null   // 添加模式下暂存的提示音 URI，保存时写入新建条目

    private val colors = intArrayOf(
        0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FF00.toInt(),
        0xFFFFFF00.toInt(), 0xFFFF8000.toInt(), 0xFFFF0000.toInt(), 0xFFE8B94A.toInt()
    )
    private val colorNames = arrayOf("青色", "品红", "绿色", "黄色", "橙色", "红色", "金色")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftSoundUri = savedInstanceState?.getString("draftSoundUri")
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

        // 顶部返回栏与标题：风格、交互与「关于」页一致
        findViewById<TextView>(R.id.pageTitle).text =
            if (editId != null) "编辑小倒计时" else "添加小倒计时"
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        val titleEt = findViewById<EditText>(R.id.etTitle)
        val datePicker = findViewById<DatePicker>(R.id.datePicker)
        val timePicker = findViewById<TimePicker>(R.id.timePicker)
        val colorSpinner = findViewById<Spinner>(R.id.colorSpinner)
        val standardSpinner = findViewById<Spinner>(R.id.standardSpinner)
        val animSpinner = findViewById<Spinner>(R.id.animSpinner)
        val remarkEt = findViewById<EditText>(R.id.etRemark)
        val soundBtn = findViewById<Button>(R.id.btnSound)
        this.soundBtn = soundBtn
        val clearSoundBtn = findViewById<Button>(R.id.btnClearSound)
        val saveBtn = findViewById<Button>(R.id.btnSave)
        val cancelBtn = findViewById<Button>(R.id.btnCancel)

        // 内置倒计时同样可以编辑：名字 / 颜色 / 显示模式 / 跳秒动画 / 提示音 / 备注随便改。
        // 日期时间那栏填了也不会生效 —— 内置倒计时的时刻是系统自己往下跳的（每小时 / 每半小时 / 当日 /
        // 每周 / 每月），保存时仍写回系统此刻的目标，它永远是内置倒计时，不会被降级成普通倒计时。
        val builtInEdit = c != null && c.isBuiltIn()
        // 进入本页时的内置类型：模式下拉框按它过滤（当日 / 每小时 / 每半小时倒计时没有天数模式）。
        // 保存时仍用同一个列表还原模式号，两者不会错位。
        val startBuiltIn = c?.builtIn ?: BuiltIn.NONE

        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colorNames)
        (colorSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val modeNameList = CountdownFormatter.modeNames(startBuiltIn)
        // 「标准模式」下拉框：唯一挑显示方式的地方，选项与该倒计时的可选模式一致
        // （按类型自动收掉装不下的那几档），随时能改，没有次数限制。
        standardSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, modeNameList
        )
        (standardSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        animSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AnimStyle.NAMES)
        (animSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        if (c != null) {
            titleEt.setText(c.title)
            // 内置项显示的是「系统此刻算出来的目标时间」，而不是磁盘上可能已过期的旧值
            val shownTarget = if (c.isBuiltIn()) c.currentBuiltInTarget() else c.targetTime
            val cal = Calendar.getInstance().apply { timeInMillis = shownTarget }
            datePicker.updateDate(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            timePicker.hour = cal.get(Calendar.HOUR_OF_DAY)
            timePicker.minute = cal.get(Calendar.MINUTE)
            var ci = colors.indexOfFirst { it == c.customColorArgb }
            if (ci < 0) ci = 0
            colorSpinner.setSelection(ci)
            standardSpinner.setSelection(CountdownFormatter.modeIndex(c.displayMode, startBuiltIn))
            animSpinner.setSelection(c.animStyle.coerceIn(0, AnimStyle.NAMES.size - 1))
            remarkEt.setText(c.remark)
            soundBtn.text = if (c.soundUri != null) {
                "已经选好啦：${ringtoneName(Uri.parse(c.soundUri)) ?: "自定义提示音"}（点一下换一个）"
            } else {
                "用默认提示音（点一下选一个）"
            }
        } else {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
            datePicker.updateDate(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            timePicker.hour = cal.get(Calendar.HOUR_OF_DAY)
            timePicker.minute = cal.get(Calendar.MINUTE)
            // 添加模式下若已选过提示音，回显到按钮（避免旋转屏幕后文案丢失）
            soundBtn.text = if (draftSoundUri != null) {
                "已经选好啦：${ringtoneName(Uri.parse(draftSoundUri)) ?: "自定义提示音"}（点一下换一个）"
            } else {
                "用默认提示音（点一下选一个）"
            }
        }

        // 这条倒计时只剩「标准模式」一种显示时（例如「每分钟」：
        // 它的标准模式就是秒模式，再留一档「秒模式」只是同句话换个名字），
        // 把编辑页的「标准模式」标题、下拉框和那句说明一起收掉 —— 没有第二档可挑就不占地方。
        if (!CountdownFormatter.hasModeSwitch(startBuiltIn)) {
            standardSpinner.visibility = View.GONE
            findViewById<View>(R.id.standardModeLabel).visibility = View.GONE
            findViewById<View>(R.id.standardModeHint).visibility = View.GONE
        }

        if (builtInEdit) {
            Toast.makeText(
                this,
                "内置小倒计时也能改：名字、颜色、模式、动画、提示音、备注都能改；时刻是系统自己往下跳的，" +
                    "它一直是内置小倒计时",
                Toast.LENGTH_LONG
            ).show()
        }

        soundBtn.setOnClickListener {
            if (c != null) pickSound(c)
            else pickSoundDraft()
        }
        clearSoundBtn.setOnClickListener {
            if (c != null) {
                val list = CountdownStore.load(this)
                list.find { it.id == c.id }?.soundUri = null
                CountdownStore.save(this, list)
                c.soundUri = null
            } else {
                draftSoundUri = null
            }
            soundBtn.text = "用默认提示音（点一下选一个）"
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
                    // 内置项：目标时刻由系统自己往下跳（每小时 / 每半小时 / 当日 / 每周 / 当月），
                    // 用户在这里改的日期时间不会存盘，免得下一帧就被系统算出来的目标覆盖掉、看着像「白改」。
                    // 无论怎么改，它都还是内置倒计时（ BuiltIn 保持原样，绝不降级成普通倒计时）。
                    val sysTarget = if (existing.builtIn != BuiltIn.NONE) existing.currentBuiltInTarget() else target
                    existing.targetTime = if (existing.builtIn != BuiltIn.NONE) sysTarget else target
                    existing.customColorArgb = colors[colorSpinner.selectedItemPosition]
                    existing.displayMode =
                        CountdownFormatter.modeAt(standardSpinner.selectedItemPosition, startBuiltIn)
                    existing.animStyle = animSpinner.selectedItemPosition
                    existing.remark = remarkEt.text.toString()
                }
            } else {
                list.add(
                    Countdown(
                        title = titleEt.text.toString(),
                        targetTime = target,
                        customColorArgb = colors[colorSpinner.selectedItemPosition],
                        displayMode = CountdownFormatter.modeAt(
                            standardSpinner.selectedItemPosition, startBuiltIn
                        ),
                        animStyle = animSpinner.selectedItemPosition,
                        remark = remarkEt.text.toString(),
                        soundUri = draftSoundUri
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draftSoundUri", draftSoundUri)
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
        val existing = if (c.soundUri != null) Uri.parse(c.soundUri) else null
        openRingtonePicker(existing)
    }

    /** 添加模式下还没有保存的条目：用草稿承载提示音选择，保存时再写入新建的 Countdown。 */
    private fun pickSoundDraft() {
        pendingSoundId = null   // 标记当前处于“添加草稿”模式
        val existing = if (draftSoundUri != null) Uri.parse(draftSoundUri) else null
        openRingtonePicker(existing)
    }

    private fun openRingtonePicker(existing: Uri?) {
        // 使用系统“铃声”选择器，直接列出手机铃声/通知音等作为倒计时提示音选项
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
        if (requestCode != 200) return
        // 「仅挑选提示音」模式没有加载布局（onCreate 里提前 return），
        // 若用户在选择器里按返回取消，必须自己收尾，否则会停在一个空白页上。
        if (resultCode != Activity.RESULT_OK) {
            pendingSoundId = null
            if (pickSoundOnly) {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            return
        }
        if (resultCode == Activity.RESULT_OK) {
            // 选中“默认”时返回 null（约定为默认提示音，即内置 beep）
            @Suppress("DEPRECATION")
            val picked = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
            if (pendingSoundId != null) {
                // 编辑已有条目：直接写回存储
                val list = CountdownStore.load(this)
                list.find { it.id == pendingSoundId }?.soundUri = picked?.toString()
                CountdownStore.save(this, list)
                editTarget?.soundUri = picked?.toString()
            } else {
                // 添加草稿模式：暂存，保存时写入新建条目
                draftSoundUri = picked?.toString()
            }
            pendingSoundId = null
            if (pickSoundOnly) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                soundBtn?.text = if (picked != null) {
                    "已选择：${ringtoneName(picked) ?: "自定义提示音"}（点击更换）"
                } else {
                    "用默认提示音（点一下选一个）"
                }
            }
        }
    }
}
