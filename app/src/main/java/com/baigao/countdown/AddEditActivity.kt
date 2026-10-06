package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
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
        //
        // 多出来一个讲得通的例外：「100年倒计时」也是内置项，可它的目标时刻（年 / 月 / 日 / 时 / 分 / 秒）
        // 和「要不要自定义提示音」都归用户自己定，所以界面上摆不摆那两颗换音按钮，只认 soundEditable()
        // ——也就是用户自己勾了没勾「启用自定义提示音」，开关见下面那一行。
        // 可以挑的内置类型（「倒计时类型」下拉框的条目顺序）：普通倒计时永远排最前，其后是
        //「100年倒计时」，再往后是其余滚动型内置项，固定目标的两个内置项垫底。
        val pickable = listOf(
            BuiltIn.NONE, BuiltIn.CENTURY, BuiltIn.HOUR, BuiltIn.HALF_HOUR, BuiltIn.MINUTE,
            BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN, BuiltIn.DAY, BuiltIn.WEEK, BuiltIn.MONTH,
            BuiltIn.HUADU, BuiltIn.GTA6
        )
        // 这条倒计时在编辑 / 准备生成的类型（显示模式清单、目标时刻六连框、提示音开关全按它走）。
        // 新建时默认「普通倒计时」；编辑内置项时就是它自己那一档，模式下拉框按它过滤。
        var pickedBuiltIn: Int = c?.builtIn ?: BuiltIn.NONE

        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colorNames)
        (colorSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val modeNameList = CountdownFormatter.modeNames(pickedBuiltIn)
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
            standardSpinner.setSelection(CountdownFormatter.modeIndex(c.displayMode, pickedBuiltIn))
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

        //「标准模式」那三行平时先按当前类型摆好；这条倒计时只剩一种显示时（例如「每分钟」：
        // 它的标准模式就是秒模式，再留一档「秒模式」只是同句话换个名字），applyModeBlock()
        // 会把标题、下拉框和那句说明一起收掉 —— 没有第二档可挑就不占地方。

        val builtInSpinner = findViewById<Spinner>(R.id.builtInSpinner)
        val yearSpinner = findViewById<Spinner>(R.id.yearSpinner)
        val monthSpinner = findViewById<Spinner>(R.id.monthSpinner)
        val daySpinner = findViewById<Spinner>(R.id.daySpinner)
        val hourSpinner = findViewById<Spinner>(R.id.hourSpinner)
        val minuteSpinner = findViewById<Spinner>(R.id.minuteSpinner)
        val secondSpinner = findViewById<Spinner>(R.id.secondSpinner)
        val timeOfDayBlock = findViewById<View>(R.id.timeOfDayBlock)
        val soundToggle = findViewById<Switch>(R.id.soundToggle)
        val soundBlock = findViewById<View>(R.id.soundBlock)

        //「倒计时类型」下拉框：普通倒计时 + 列表里还躺着的那些内置项（已经有的就不重复摆，
        // 免得实打实建出两条一模一样的内置倒计时）；编辑已有内置项时把它自己排在第一个。
        val existingAll = CountdownStore.load(this)
        val pool = mutableListOf<Int>()
        if (c != null) pool.add(c.builtIn)
        for (t in pickable) {
            if (t !in pool && (t == BuiltIn.NONE || existingAll.none { it.builtIn == t })) pool.add(t)
        }
        builtInSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, pool.map { BuiltIn.nameOf(it) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // 目标时刻六个下拉框（年 / 月 / 日 / 时 / 分 / 秒）：写法与页面上那几颗下拉框一模一样
        val baseYear = Calendar.getInstance().get(Calendar.YEAR)
        yearSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, List(100) { "${baseYear + it} 年" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        monthSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, List(12) { "${it + 1} 月" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        daySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, List(31) { "${it + 1} 日" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        hourSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, List(24) { "${it} 时" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        minuteSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, List(60) { "${it} 分" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        secondSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, List(60) { "${it} 秒" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 界面上摆不摆「挑提示音」那一整块：周期滚动型内置项（每分钟 / 每小时 / 当日 …）一律不摆，
        // 普通倒计时、华都云境悦府、GTA6 照旧摆着；「100年倒计时」这一档改由用户自己决定 ——
        // 页面顶上多一颗「启用自定义提示音」的开关，勾上才亮出下面那两颗换音按钮。
        var soundOn = if (BuiltIn.askSoundToggle(pickedBuiltIn)) {
            c?.soundEnabled ?: false
        } else {
            c?.soundEditable() ?: !BuiltIn.isRolling(pickedBuiltIn)
        }

        /** 提示音那一整块跟着 soundOn 显隐，顺手把按钮上的提示音名刷了。 */
        fun applySoundVisibility() {
            soundBlock.visibility = if (soundOn) View.VISIBLE else View.GONE
            refreshSoundLabel()
        }

        /** 这条倒计时只剩一种显示模式时，把「标准模式」标题 / 下拉框 / 那句说明一起收掉。 */
        fun applyModeBlock() {
            val single = !CountdownFormatter.hasModeSwitch(pickedBuiltIn)
            standardSpinner.visibility = if (single) View.GONE else View.VISIBLE
            findViewById<View>(R.id.standardModeLabel).visibility =
                if (single) View.GONE else View.VISIBLE
            findViewById<View>(R.id.standardModeHint).visibility =
                if (single) View.GONE else View.VISIBLE
        }

        /** 倒计时类型一换，下面这几样跟着全换一遍。 */
        fun applyTypeDependant() {
            val ask = BuiltIn.askSoundToggle(pickedBuiltIn)
            // 目标时刻六连框 +「启用自定义提示音」开关：只有「100年倒计时」才摆出来
            timeOfDayBlock.visibility = if (ask) View.VISIBLE else View.GONE
            soundToggle.visibility = if (ask) View.VISIBLE else View.GONE
            // 其余类型照旧：滚动型内置项（每分钟 / 每小时 / 当日 …）不摆提示音，
            // 普通倒计时与固定目标内置项（华都云境悦府 / GTA6）两边照旧摆着
            if (!ask) soundOn = !BuiltIn.isRolling(pickedBuiltIn)
            soundToggle.isChecked = soundOn
            applySoundVisibility()
            // 模式下拉框的清单按类型重新算（每5分钟没有「时分秒」，每天没有「天数模式」…）
            standardSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                CountdownFormatter.modeNames(pickedBuiltIn)
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            standardSpinner.setSelection(
                CountdownFormatter.modeIndex(
                    c?.displayMode ?: CountdownFormatter.MODE_STANDARD, pickedBuiltIn
                )
            )
            applyModeBlock()
        }

        soundBtn = findViewById<Button>(R.id.btnSound)
        val clearSoundBtn = findViewById<Button>(R.id.btnClearSound)
        soundBtn?.setOnClickListener { openRingtonePicker() }
        clearSoundBtn.setOnClickListener {
            draftSoundUri = null
            refreshSoundLabel()
        }
        var toggling = false
        soundToggle.setOnCheckedChangeListener { _, on ->
            if (toggling) return@setOnCheckedChangeListener
            soundOn = on
            applySoundVisibility()
        }
        // 类型换档：标题跟着换成这一型的默认名（原来那句还没改过就跟着换），下面这几块全刷一遍
        var prevType = pickedBuiltIn
        builtInSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val prev = BuiltIn.nameOf(prevType)
                pickedBuiltIn = pool[position]
                val t = titleEt.text.toString()
                if (t.isBlank() || t == prev) titleEt.setText(BuiltIn.nameOf(pickedBuiltIn))
                prevType = pickedBuiltIn
                applyTypeDependant()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        if (builtInEdit) {
            Toast.makeText(
                this,
                "内置小倒计时也能改：名字、颜色、模式、动画、备注都能改；时刻是系统自己往下跳的，" +
                    "它一直是内置小倒计时",
                Toast.LENGTH_LONG
            ).show()
        }
        // 初始值：类型落在它自己那档，目标时刻填进六个下拉框，开关就照存着的状态
        builtInSpinner.setSelection(pool.indexOf(pickedBuiltIn).coerceAtLeast(0))
        val bt = if (c != null && c.builtIn == BuiltIn.CENTURY) c.builtInTargetMillis else 0L
        if (bt > 0) {
            val bcal = Calendar.getInstance().apply { timeInMillis = bt }
            val maxYear = (yearSpinner.adapter as ArrayAdapter<*>).count - 1
            yearSpinner.setSelection((bcal.get(Calendar.YEAR) - baseYear).coerceIn(0, maxYear))
            monthSpinner.setSelection(bcal.get(Calendar.MONTH))
            daySpinner.setSelection(bcal.get(Calendar.DAY_OF_MONTH) - 1)
            hourSpinner.setSelection(bcal.get(Calendar.HOUR_OF_DAY))
            minuteSpinner.setSelection(bcal.get(Calendar.MINUTE))
            secondSpinner.setSelection(bcal.get(Calendar.SECOND))
        } else {
            yearSpinner.setSelection(0)
            monthSpinner.setSelection(0)
            daySpinner.setSelection(0)
        }
        toggling = true
        applyTypeDependant()
        toggling = false
        // 从卡片上的「提示音名称」按钮进来的：直接把系统铃声选择器顶上去，选完即存
        if (pickSoundOnly && c != null) {
            draftSoundUri = c.soundUri
            refreshSoundLabel()
            openRingtonePicker()
        }

        /** 六颗下拉框当前挑的是「目标时刻」的 epoch 毫秒（年 / 月 / 日 / 时 / 分 / 秒拼起来）。 */
        fun targetMillisFromPickers(): Long {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(Calendar.YEAR, baseYear + yearSpinner.selectedItemPosition)
            cal.set(Calendar.MONTH, monthSpinner.selectedItemPosition)
            cal.set(Calendar.DAY_OF_MONTH, daySpinner.selectedItemPosition + 1)
            cal.set(Calendar.HOUR_OF_DAY, hourSpinner.selectedItemPosition)
            cal.set(Calendar.MINUTE, minuteSpinner.selectedItemPosition)
            cal.set(Calendar.SECOND, secondSpinner.selectedItemPosition)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
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
                        CountdownFormatter.modeAt(standardSpinner.selectedItemPosition, pickedBuiltIn)
                    existing.animStyle = animSpinner.selectedItemPosition
                    existing.remark = remarkEt.text.toString()
                    // 倒计时类型本身也能改（普通 ↔ 内置随便换，换完它还是内置 / 还是普通倒计时）
                    existing.builtIn = pickedBuiltIn
                    // 提示音：周期滚动型内置项一律回到默认提示音（清掉可能存过的自定义音）；
                    //「100年倒计时」这一档听用户自己那颗「启用自定义提示音」开关
                    existing.soundEnabled = soundOn
                    existing.soundUri = if (soundOn) draftSoundUri else null
                    // 目标时刻改动立刻生效：内置项的时刻每帧由系统重算，改完这一下就按新日子走
                    if (existing.builtIn == BuiltIn.CENTURY) {
                        existing.builtInTargetMillis = targetMillisFromPickers()
                    }
                }
            } else {
                // 同类型内置项、或者同名的那条已经躺在列表里了：这一下不许再生成，
                // 弹一句「该倒计时已存在，生成失败！」，什么都不存、直接留在这页。
                val dupBuiltIn = list.any { it.builtIn == pickedBuiltIn }
                val dupTitle = list.any {
                    it.title == titleEt.text.toString().trim() && it.title.isNotEmpty()
                }
                if (pickedBuiltIn != BuiltIn.NONE && (dupBuiltIn || dupTitle)) {
                    Toast.makeText(this, "该倒计时已存在，生成失败！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                list.add(
                    Countdown(
                        title = titleEt.text.toString(),
                        targetTime = target,
                        customColorArgb = colors[colorSpinner.selectedItemPosition],
                        displayMode = CountdownFormatter.modeAt(
                            standardSpinner.selectedItemPosition, pickedBuiltIn
                        ),
                        animStyle = animSpinner.selectedItemPosition,
                        remark = remarkEt.text.toString(),
                        // 内置项的目标时刻由系统自己往下跳，这里填的日期时间只是个起点，
                        // 存下来也不会把它降级成普通倒计时
                        builtIn = pickedBuiltIn,
                        builtInTargetMillis = if (pickedBuiltIn == BuiltIn.CENTURY) {
                            targetMillisFromPickers()
                        } else 0L,
                        soundEnabled = soundOn,
                        soundUri = if (soundOn) draftSoundUri else null
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
