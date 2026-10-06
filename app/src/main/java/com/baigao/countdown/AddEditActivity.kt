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
    private var fromFloating = false
    private var editTarget: Countdown? = null
    private var pickSoundOnly = false
    private var draftSoundUri: String? = null   // 待写入的自定义提示音 URI（null = 默认提示音）
    private var soundBtn: Button? = null

    private val colors = intArrayOf(
        0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FF00.toInt(),
        0xFFFFFF00.toInt(), 0xFFFF8000.toInt(), 0xFFFF0000.toInt(), 0xFFE8B94A.toInt()
    )
    private val colorNames = arrayOf("青色", "品红", "绿色", "黄色", "橙色", "红色", "金色")

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draftSoundUri", draftSoundUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editId = intent.getStringExtra("id")
        fromFloating = intent.getBooleanExtra("fromFloating", false)
        pickSoundOnly = intent.getBooleanExtra("pickSoundOnly", false)
        draftSoundUri = savedInstanceState?.getString("draftSoundUri")

        val c = if (editId != null) CountdownStore.load(this).find { it.id == editId } else null
        editTarget = c


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
        val saveBtn = findViewById<Button>(R.id.btnSave)
        val cancelBtn = findViewById<Button>(R.id.btnCancel)

        // 内置倒计时同样可以编辑：名字 / 颜色 / 显示模式 / 跳秒动画 / 备注随便改。
        // 日期时间那栏填了也不会生效 —— 内置倒计时的时刻是系统自己往下跳的（每小时 / 每半小时 / 当日 /
        // 每周 / 每月），保存时仍写回系统此刻的目标，它永远是内置倒计时，不会被降级成普通倒计时。
        val builtInEdit = c != null && c.isBuiltIn()
        // 提示音这扇门只关给「周期滚动型」内置项：每分钟 / 每5分钟 / 每10分钟 / 每半小时 /
        // 每小时 / 当日 / 每周 / 每月，它们的目标时刻是系统一格格往下跳的，归零照旧响默认提示音，
        // 界面上干脆不摆换音按钮。华都云境悦府、GTA6 这两个「固定目标」内置项不跟着日期跳，
        // 挑提示音照旧给它们留着，跟普通倒计时一路。
        val hideSoundEdit = c != null && c.isPeriodicBuiltIn()
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
        } else {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
            datePicker.updateDate(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            timePicker.hour = cal.get(Calendar.HOUR_OF_DAY)
            timePicker.minute = cal.get(Calendar.MINUTE)
        }

        // 这条倒计时只剩「标准模式」一种显示时（例如「每分钟」：
        // 它的标准模式就是秒模式，再留一档「秒模式」只是同句话换个名字），
        // 把编辑页的「标准模式」标题、下拉框和那句说明一起收掉 —— 没有第二档可挑就不占地方。
        if (!CountdownFormatter.hasModeSwitch(startBuiltIn)) {
            standardSpinner.visibility = View.GONE
            findViewById<View>(R.id.standardModeLabel).visibility = View.GONE
            findViewById<View>(R.id.standardModeHint).visibility = View.GONE
        }

        // 提示音选择只收给「周期滚动型」内置倒计时（每分钟 / 每5分钟 / 每10分钟 /
        // 每半小时 / 每小时 / 当日 / 每周 / 当月 …）——它们归零照旧响默认提示音，
        // 界面上不摆换音按钮；华都云境悦府、GTA6 和普通倒计时这一整块照旧可点可改。
        val soundBlock = findViewById<View>(R.id.soundBlock)
        soundBtn = findViewById<Button>(R.id.btnSound)
        val clearSoundBtn = findViewById<Button>(R.id.btnClearSound)
        soundBtn?.setOnClickListener { openRingtonePicker() }
        clearSoundBtn.setOnClickListener {
            draftSoundUri = null
            refreshSoundLabel()
        }
        if (builtInEdit) {
            Toast.makeText(
                this,
                "内置小倒计时也能改：名字、颜色、模式、动画、备注都能改；时刻是系统自己往下跳的，" +
                    "它一直是内置小倒计时",
                Toast.LENGTH_LONG
            ).show()
        }
        if (hideSoundEdit) {
            // 周期滚动型内置项：整块收掉，它身上可能存过的自定义提示音也一并作废（统一用默认提示音）
            soundBlock.visibility = View.GONE
            draftSoundUri = null
        } else {
            soundBlock.visibility = View.VISIBLE
        }
        refreshSoundLabel()
        // 从卡片上的「提示音名称」按钮进来的：直接把系统铃声选择器顶上去，选完即存
        if (pickSoundOnly && c != null) {
            draftSoundUri = c.soundUri
            refreshSoundLabel()
            openRingtonePicker()
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
                    // 提示音只有普通倒计时和「固定目标」内置项能挑：周期滚动型内置项一律回到默认提示音（清掉可能存过的自定义音）
                    existing.soundUri = if (hideSoundEdit) null else draftSoundUri
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







    // ---------- 提示音选择（只给自定义倒计时） ----------

    /** 按钮上的提示音文案：选过就显示铃声名，没选过显示「用默认提示音」。 */
    private fun refreshSoundLabel() {
        soundBtn?.text = if (draftSoundUri != null) SoundNames.name(this, draftSoundUri)
            else "用默认提示音（点一下选一个）"
    }

    @Suppress("DEPRECATION")
    private fun openRingtonePicker() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        i.putExtra(
            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            if (draftSoundUri != null) Uri.parse(draftSoundUri)
            else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        startActivityForResult(i, 200)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK && data != null) {
            val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                draftSoundUri = uri.toString()
                refreshSoundLabel()
                // 从卡片提示音按钮进来的：选完直接落盘，不用再进保存页
                if (pickSoundOnly) {
                    val list = CountdownStore.load(this)
                    val _id = editId
                    val e = if (_id != null) list.find { it.id == _id } else null
                    if (e != null) {
                        e.soundUri = draftSoundUri
                        CountdownStore.save(this, list)
                        setResult(Activity.RESULT_OK)
                        if (fromFloating) {
                            val ri = Intent(this, CountdownService::class.java)
                            ri.action = CountdownService.ACTION_START
                            startForegroundService(ri)
                        }
                        finish()
                    } else {
                        Toast.makeText(this, "这条记录找不到了，提示音没改成", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
