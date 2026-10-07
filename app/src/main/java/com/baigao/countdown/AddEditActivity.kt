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
        val dateLabel = findViewById<TextView>(R.id.dateLabel)
        val timeLabel = findViewById<TextView>(R.id.timeLabel)
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
        // 可以挑的内置类型（「倒计时类型」下拉框的条目顺序）：普通倒计时永远排最前，
        // 其后是其余滚动型内置项（每小时 / 每半小时 / 每分钟 …），固定目标的两个内置项和「100年内倒计时」垫底。
        val pickable = listOf(
            BuiltIn.NONE, BuiltIn.HOUR, BuiltIn.HALF_HOUR, BuiltIn.MINUTE,
            BuiltIn.FIVE_MIN, BuiltIn.TEN_MIN, BuiltIn.DAY, BuiltIn.WEEK, BuiltIn.MONTH,
            BuiltIn.HUADU, BuiltIn.GTA6, BuiltIn.CENTURY
        )
        // 这条倒计时在编辑 / 准备生成的类型（显示模式清单、提示音开关全按它走）。
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
        val soundBlock = findViewById<View>(R.id.soundBlock)

        // ---------- 「100年内倒计时」这一档的两样东西 ----------
        // 六颗下拉框（年 / 月 / 日 / 时 / 分 / 秒）：与页面上其它下拉框同一套 GlassSpinner 样式，
        // 排成一行六等分，随便挑 100 年以内任意一个日子 + 时 + 分 + 秒就是这条倒计时的目标时刻。
        val timeOfDayBlock = findViewById<View>(R.id.timeOfDayBlock)
        val yearSpinner = findViewById<Spinner>(R.id.yearSpinner)
        val monthSpinner = findViewById<Spinner>(R.id.monthSpinner)
        val daySpinner = findViewById<Spinner>(R.id.daySpinner)
        val hourSpinner = findViewById<Spinner>(R.id.hourSpinner)
        val minuteSpinner = findViewById<Spinner>(R.id.minuteSpinner)
        val secondSpinner = findViewById<Spinner>(R.id.secondSpinner)
        // 「启用自定义提示音」开关：勾上才接着摆出下面「提示音」那两颗按钮
        val soundToggle = findViewById<Switch>(R.id.soundToggle)



        //「倒计时类型」下拉框：普通倒计时 + 列表里还躺着的那些内置项（已经有的就不重复摆，
        // 免得实打实建出两条一模一样的内置倒计时）；编辑已有内置项时把它自己排在第一个。
        val existingAll = CountdownStore.load(this)
        val pool = mutableListOf<Int>()
        if (c != null) pool.add(c.builtIn)
        for (t in pickable) {
            // ⚠️ v148：「100年内倒计时」v146 起可以存好几条（删除后还能在「找回小内置」里捞回来），
            // 所以它不管列表里躺着几条都一样要摆出来 —— 早先跟着「已经有一条就不摆」的老规矩走，
            // 于是刚生成完一条就再也选不到这一档，非得把那条删了才见得着。除它以外仍是「已有就不重复摆」。
            val always = t == BuiltIn.NONE || t == BuiltIn.CENTURY
            if (t !in pool && (always || existingAll.none { it.builtIn == t })) pool.add(t)
        }
        builtInSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, pool.map { BuiltIn.nameOf(it) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 界面上摆不摆「挑提示音」那一整块：周期滚动型内置项（每分钟 / 每小时 / 当日 …）一律不摆，
        // 界面上摆不摆「挑提示音」那一整块：周期滚动型内置项（每分钟 / 每小时 / 当日 …）
        // 一律不摆，普通倒计时、华都云境悦府、GTA6 照旧摆着；就这一行 soundEditable() 说话。
        var soundOn = c?.soundEditable() ?: !BuiltIn.isRolling(pickedBuiltIn)
        // 「100年内倒计时」那颗「启用自定义提示音」开关：勾上才摆出换音那两颗按钮
        var soundToggleOn: Boolean = c?.soundEnabled ?: false

        /** 提示音那一整块跟着 soundOn（类型不是周期滚动内置项）和那颗开关显隐，顺手把按钮上的提示音名刷了。 */
        fun applySoundVisibility() {
            soundBlock.visibility = if (soundOn && soundToggleOn) View.VISIBLE else View.GONE
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
            soundOn = !BuiltIn.isRolling(pickedBuiltIn)
            // 那颗「启用自定义提示音」开关只在「100年内倒计时」这一档摆出来
            soundToggle.visibility = if (pickedBuiltIn == BuiltIn.CENTURY) View.VISIBLE else View.GONE
            if (pickedBuiltIn != BuiltIn.CENTURY) soundToggleOn = false
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

        /**
         * 把一颗整值下拉框从 from 填到 max（末尾带单位，如「10月」），填完那颗下拉框的选中位不动。
         * ⚠️ v148 起「月 / 日」两颗传 from = 1（显示 1月..12月、1日..31日，显示值就是真实值），
         * 时 / 分 / 秒三颗仍从 0 起（0 时 .. 23 时）。早先三颗都是从 0 起填的，读取时又给「月」减一、
         * 给「日」加一，两套口径打架：初值那一下（selInt 里减 / 加一抵消）看着还行，
         * 用户真去点选「10日」时那个 10 被当成了索引，读出来又 +1，存下去就成了 11 日，
         * 六连框上写着的「10日」和存下去的目标差一天 —— 这就是报的「挑完时间自动加了 1 天」。
         */
        fun fillInt(sp: Spinner, max: Int, suffix: String, from: Int = 0) {
            sp.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                (from..max).map { "$it$suffix" }.toTypedArray()
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

        /** 下拉框当前选中的整值：把「2026年」这种文本里的数字抠出来。 */
        fun intOf(sp: Spinner): Int =
            sp.selectedItem?.toString()?.replace(Regex("\\D"), "")?.toIntOrNull() ?: 0

        /** 把一颗下拉框挪到第 v 格（越界就贴边）。 */
        fun selInt(sp: Spinner, v: Int) {
            sp.setSelection(v.coerceIn(0, (sp.count - 1).coerceAtLeast(0)))
        }

        /** 年份下拉框（今年..今 + 100）没盖住 y 时把它抻开到能选到 y（编辑一条老 / 跨过的「100年内倒计时」时用）。 */
        fun ensureYearRange(y: Int) {
            val from = minOf(Calendar.getInstance().get(Calendar.YEAR), y)
            val to = maxOf(Calendar.getInstance().get(Calendar.YEAR) + 100, y)
            yearSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                (from..to).map { "${it}年" }.toTypedArray()
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

        /** 六连框填值 / 天数跟着年月校正一遍；initial = true 时按当前时刻给出初值。 */
        fun setupTimeOfDayPickers(initial: Boolean) {
            val now = Calendar.getInstance()
            if (initial) {
                val y = now.get(Calendar.YEAR)
                // 年：今年到 100 年后（「100年内倒计时」挑的是 100 年以内的那天）
                yearSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item,
                    (y..y + 100).map { "${it}年" }.toTypedArray()
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                // ⚠️ 月份下拉框给的是 1..12、日是 1..31（不是 0 起）：早先写成 0 起，
                // 抠出来的 0 会被当成 0 月 / 0 日，存下去就成了上一年的 12 月或者当月 1 号，
                // 自动生成的名字也写成「2027年0月5日」这种，看着就是「目标时间与挑的不一样」。
                fillInt(monthSpinner, 12, "月", 1)
                fillInt(daySpinner, 31, "日", 1)
                fillInt(hourSpinner, 23, "时")
                fillInt(minuteSpinner, 59, "分")
                fillInt(secondSpinner, 59, "秒")
                selInt(yearSpinner, 0)
                // 显示值就是真实值：月份要 +1（Calendar 的 MONTH 是 0..11），日子直接用
                selInt(monthSpinner, now.get(Calendar.MONTH) + 1)
                selInt(daySpinner, now.get(Calendar.DAY_OF_MONTH))
                selInt(hourSpinner, now.get(Calendar.HOUR_OF_DAY))
                selInt(minuteSpinner, now.get(Calendar.MINUTE))
                selInt(secondSpinner, now.get(Calendar.SECOND))
            }
            // 日那颗跟着年月走：4 / 6 / 9 / 11 月 30 天，2 月平年 28 天、闰年 29 天，
            // 别让用户挑出一个并不存在的日子（Calendar 会因为溢出自动进到下一个月去）
            val y = intOf(yearSpinner)
            val m = intOf(monthSpinner) - 1        // 下拉框给的是 1..12，Calendar 的月份是 0..11
            // ↑ 只有算「这个月有几天」要走 Calendar 的 0..11，别处一律认「显示值就是真实值」
            val dim = when (m) {
                0, 2, 4, 6, 7, 9, 11 -> 31
                3, 5, 8, 10 -> 30
                1 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
                else -> 31
            }
            if (daySpinner.count != dim) {
                // 「日」那颗从 1 起填，填出来正好 dim 格（1 日 .. dim 日），选中位不动
                val keep = intOf(daySpinner)
                fillInt(daySpinner, dim, "日", 1)
                selInt(daySpinner, keep.coerceIn(1, dim))
            }
        }

        /** 六连框当下凑出来的目标时刻（epoch 毫秒）。 */
        fun targetMillisFromPickers(): Long = Calendar.getInstance().apply {
            set(Calendar.YEAR, intOf(yearSpinner))
            // v148 起「月 / 日」两颗的显示值就是真实值，这里不再减一 / 加一
            // （只有上面算「本月几天」那处走 Calendar 的 0..11）
            set(Calendar.MONTH, intOf(monthSpinner) - 1)
            set(Calendar.DAY_OF_MONTH, intOf(daySpinner))
            set(Calendar.HOUR_OF_DAY, intOf(hourSpinner))
            set(Calendar.MINUTE, intOf(minuteSpinner))
            set(Calendar.SECOND, intOf(secondSpinner))
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** 六连框亮起来时，顶部那套「目标日期 / 目标时间」让位给它们 —— 两套时刻同时摆着会打架。 */
        fun applyTimeOfDayVisibility() {
            val on = pickedBuiltIn == BuiltIn.CENTURY
            timeOfDayBlock.visibility = if (on) View.VISIBLE else View.GONE
            dateLabel.visibility = if (on) View.GONE else View.VISIBLE
            datePicker.visibility = if (on) View.GONE else View.VISIBLE
            timeLabel.visibility = if (on) View.GONE else View.VISIBLE
            timePicker.visibility = if (on) View.GONE else View.VISIBLE
        }

        /** 自动生成的那串名字：照六连框挑好的时间写成的「2026年10月7日 12:00:00」。 */
        val TIME_AUTO = Regex("\\d{4}年\\d{1,2}月\\d{1,2}日 \\d{2}:\\d{2}:\\d{2}")
        // ⚠️ 这串必须写成函数、不能写成 val：val 会在声明这一行当场求值，而那时六连框还没填过值
        // （setupTimeOfDayPickers(true) 在下面第 340 多行才跑），spinner.selectedItem 是 null，
        // "null" 抠掉非数字之后是空串，toInt() 当场抛 NumberFormatException —— 一进这个页面
        // （点「＋」新建倒计时）就闪退，Edit 形式的同类代码也曾因此炸过。
        fun titleAuto(): String = "${intOf(yearSpinner)}年${intOf(monthSpinner)}月${intOf(daySpinner)}日 " + String.format("%02d:%02d:%02d", intOf(hourSpinner), intOf(minuteSpinner), intOf(secondSpinner))

        /** 名字自动生成：标题还是空的、还是这一型的默认名、还是上一次自动生成的那串，就照挑好的时间写；手打过的名字不动。 */
        // 本次编辑里自动写进名字的那一串（用户自己改过名之后就不再覆盖）
        var lastCenturyName: String = ""

        // ⚠️ v146 起「100年内倒计时」的名字 / 备注按「生成那会儿隔了多久」自动生成：
        // 离目标 20 小时 -> 名字「20小时」、备注「本轮还剩 20小时」；差 7 小时 ->「7小时」。
        // 跨过之后每一轮起算时，备注由 Countdown 那边照新目标再刷一次「本轮还剩 …」；
        // 名字是生成那一刻定下来的，一轮一轮转下去名字不动（否则列表里名字老跳，认不出是哪条）。
        fun centurySpanMillis(): Long = targetMillisFromPickers() - System.currentTimeMillis()

        fun autoTitleFromTime() {
            if (timeOfDayBlock.visibility != View.VISIBLE) return
            if (pickedBuiltIn != BuiltIn.CENTURY) return
            val span = centurySpanMillis()
            val name = spanText(span)
            val t = titleEt.text.toString().trim()
            // 只有「还是自动生成的那串」才覆盖：空着、是类型名、旧版写进去的时间戳、
            // 或上一次自己按时长写进去的那串（用户手打的名字一律留着）
            val nameAuto = t.isBlank() || t == BuiltIn.nameOf(BuiltIn.CENTURY) || TIME_AUTO.matches(t) || t == lastCenturyName
            if (nameAuto) {
                titleEt.setText(name)
                lastCenturyName = name
            }
            val r = remarkEt.text.toString().trim()
            val remarkAuto = r.isBlank() || TIME_AUTO.matches(r) || r.startsWith("本轮还剩")
            if (remarkAuto) remarkEt.setText("本轮还剩 ${spanText(span)}")
        }

        // 六颗下拉框任意一颗一变：天数跟着校正，名字也照挑好的时间自动写好
        for (sp in listOf(yearSpinner, monthSpinner, daySpinner, hourSpinner, minuteSpinner, secondSpinner)) {
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    setupTimeOfDayPickers(false)
                    autoTitleFromTime()
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
        // 「启用自定义提示音」开关：勾上才摆出「提示音」那两颗按钮，不勾就整块收着、归零只响默认提示音
        soundToggle.setOnCheckedChangeListener { _, on ->
            soundToggleOn = on
            applySoundVisibility()
        }

        soundBtn = findViewById<Button>(R.id.btnSound)
        val clearSoundBtn = findViewById<Button>(R.id.btnClearSound)
        soundBtn?.setOnClickListener { openRingtonePicker() }
        clearSoundBtn.setOnClickListener {
            draftSoundUri = null
            refreshSoundLabel()
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
                applyTimeOfDayVisibility()
                autoTitleFromTime()
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
        // 六连框先按当前时刻填好（要先于下面「类型」下拉框那句 setSelection：
        // 那句会当场回调一次 autoTitleFromTime()，那时六连框得已经有值，否则写进去的是空串）
        setupTimeOfDayPickers(true)
        // 初始值：类型落在它自己那档
        builtInSpinner.setSelection(pool.indexOf(pickedBuiltIn).coerceAtLeast(0))
        // 编辑一条「100年内倒计时」时，六连框再跳回它自己存的那个时刻
        if (c != null && c.builtIn == BuiltIn.CENTURY) {
            // ⚠️ 这里必须拿「用户当初挑的那个时刻」(builtInTargetMillis) 来回填六连框，不能拿
            // 「系统此刻算出来的目标」(currentBuiltInTarget)：那个值一旦跨过就自己滚到了一百年后，
            // 拿它回填等于把用户挑的那天悄悄换掉，用户点保存就把一个自己根本没挑的年份写进去了。
            // 挑的那一年不在「今年..今 + 100」这一档里时，先抻开年份下拉框，免得 selInt 把它顶到边上。
            val baseMillis = if (c.builtInTargetMillis > 0) c.builtInTargetMillis else c.currentBuiltInTarget()
            ensureYearRange(Calendar.getInstance().apply { timeInMillis = baseMillis }.get(Calendar.YEAR))
            val cal = Calendar.getInstance().apply { timeInMillis = baseMillis }
            selInt(yearSpinner, cal.get(Calendar.YEAR))
            selInt(monthSpinner, cal.get(Calendar.MONTH) + 1)   // 显示值是 1..12，Calendar 的 MONTH 是 0..11
            selInt(daySpinner, cal.get(Calendar.DAY_OF_MONTH))  // 下拉框显示的就是几号
            selInt(hourSpinner, cal.get(Calendar.HOUR_OF_DAY))
            selInt(minuteSpinner, cal.get(Calendar.MINUTE))
            selInt(secondSpinner, cal.get(Calendar.SECOND))
            setupTimeOfDayPickers(false)
        }
        soundToggle.isChecked = soundToggleOn
        applyTimeOfDayVisibility()
        autoTitleFromTime()
        applyTypeDependant()
        // 从卡片上的「提示音名称」按钮进来的：直接把系统铃声选择器顶上去，选完即存
        if (pickSoundOnly && c != null) {
            draftSoundUri = c.soundUri
            refreshSoundLabel()
            openRingtonePicker()
        }


        saveBtn.setOnClickListener {
            val list = CountdownStore.load(this)
            // 「100年内倒计时」的目标时刻由六连框给（归零后自动滚到下一个 100 年周期），
            // 其余各档还用顶部那套「目标日期 / 目标时间」
            val target = if (pickedBuiltIn == BuiltIn.CENTURY) targetMillisFromPickers() else Calendar.getInstance().apply {
                set(Calendar.YEAR, datePicker.year)
                set(Calendar.MONTH, datePicker.month)
                set(Calendar.DAY_OF_MONTH, datePicker.dayOfMonth)
                set(Calendar.HOUR_OF_DAY, timePicker.hour)
                set(Calendar.MINUTE, timePicker.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            // 名字 / 备注按「生成那会儿隔了多久」自动生成（20 小时 -> 「20小时」/「本轮还剩 20小时」）；
            // 跨过之后每一轮起算时，备注由 Countdown 那边照新目标再刷一次「本轮还剩 …」
            val centurySpan = if (pickedBuiltIn == BuiltIn.CENTURY) centurySpanMillis() else 0L
            if (pickedBuiltIn == BuiltIn.CENTURY) autoTitleFromTime()

            // 存哪一档就是哪一档：停在「普通倒计时」就是普通倒计时，挑了某个内置档就按那个内置类型存。
            val finalBuiltIn = pickedBuiltIn

            if (c != null) {
                // 关键修复：必须修改“即将保存的 list”里的同一个对象，否则改的是
                // onCreate 里另一份旧列表的副本，磁盘上不会被更新（改名/改其它字段都无效）。
                val existing = list.find { it.id == c.id }
                if (existing != null) {
                    existing.title = titleEt.text.toString()
                    // 内置项：目标时刻由系统自己往下跳（每小时 / 每半小时 / 当日 / 每周 / 当月），
                    // 用户在这里改的日期时间不会存盘，免得下一帧就被系统算出来的目标覆盖掉、看着像「白改」。
                    // 无论怎么改，它都还是内置倒计时（ BuiltIn 保持原样，绝不降级成普通倒计时）。
                    // 「100年内倒计时」的目标时刻就是六连框里刚挑的那一刻（它自己就是一个 100 年
                    // 大周期的起点）：绝不能拿「系统此刻算出来的目标」去覆盖 —— 那是上一帧刷新留下的
                    // 旧值，用户明明改了六连框，存下去却是动之前那个时刻，看着像「改了没保存」。
                    val centuryBase = if (finalBuiltIn == BuiltIn.CENTURY) targetMillisFromPickers() else 0L
                    val sysTarget = when {
                        centuryBase != 0L -> centuryBase
                        existing.builtIn != BuiltIn.NONE -> existing.currentBuiltInTarget()
                        else -> target
                    }
                    existing.targetTime = sysTarget
                    existing.customColorArgb = colors[colorSpinner.selectedItemPosition]
                    existing.displayMode =
                        CountdownFormatter.modeAt(standardSpinner.selectedItemPosition, pickedBuiltIn)
                    existing.animStyle = animSpinner.selectedItemPosition
                    existing.remark = remarkEt.text.toString()
                    // 倒计时类型本身也能改（普通 ↔ 内置随便换，换完它还是内置 / 还是普通倒计时）
                    existing.builtIn = finalBuiltIn
                    // 「100年内倒计时」：用户挑的那个时刻（一个 100 年大周期的起点）和提示音开关一起记下来
                    if (finalBuiltIn == BuiltIn.CENTURY) {
                        existing.builtInTargetMillis = if (centuryBase != 0L) centuryBase else targetMillisFromPickers()
                        // 跨过后要照着这段时长重新起一轮，所以生成那一刻的「目标 − 现在」必须记下来
                        existing.builtInSpanMillis = centurySpan
                        existing.soundEnabled = soundToggleOn
                    }
                    // 提示音：周期滚动型内置项一律回到默认提示音（清掉可能存过的自定义音）
                    existing.soundUri = if (soundOn) draftSoundUri else null
                }
            } else {
                // 同类型内置项、或者同名的那条已经躺在列表里了：这一下不许再生成，
                // 弹一句「该倒计时已存在，生成失败！」，什么都不存、直接留在这页。
                // ⚠️ v146：「100年内倒计时」可以存多条（个数不限制），但同时长只能有一条 ——
                // 名字是按时长自动生成的，两条同时长会撞成一个名字、分不清谁是谁，所以挡一下；
                // 其余内置类型仍旧一条（原来就是这个规矩，别动）。
                val dupBuiltIn = if (finalBuiltIn == BuiltIn.CENTURY) {
                    list.any {
                        it.builtIn == BuiltIn.CENTURY && spanText(it.builtInSpanMillis) == spanText(centurySpan)
                    }
                } else {
                    list.any { it.builtIn == finalBuiltIn }
                }
                val dupTitle = list.any {
                    it.title == titleEt.text.toString().trim() && it.title.isNotEmpty()
                }
                if (finalBuiltIn != BuiltIn.NONE && (dupBuiltIn || dupTitle)) {
                    Toast.makeText(
                        this,
                        if (dupBuiltIn && finalBuiltIn == BuiltIn.CENTURY) "已存在相同时长的100年内倒计时，生成失败！"
                        else "该倒计时已存在，生成失败！",
                        Toast.LENGTH_SHORT
                    ).show()
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
                        builtIn = finalBuiltIn,
                        // 「100年内倒计时」：挑的那个时刻是一个 100 年大周期的起点，归零后自动滚到下一个百年
                        builtInTargetMillis = if (finalBuiltIn == BuiltIn.CENTURY) targetMillisFromPickers() else 0L,
                        builtInSpanMillis = centurySpan,
                        soundEnabled = if (finalBuiltIn == BuiltIn.CENTURY) soundToggleOn else false,
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
