package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
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

// v161：「倒计时类型」这一级的第二项文案；第一项取 BuiltIn.nameOf(NONE)（普通倒计时），
// 两处名字不会各写一份编错。
private const val BUILT_IN_LABEL = "内置倒计时"
// v163：「是否循环」下拉框那两格文案（选中位 0 = 归零就停住 / 1 = 归零重新计时）
private const val LOOP_NO = "否（归零就停住）"
private const val LOOP_YES = "是（归零重新计时）"

class AddEditActivity : Activity() {

    private var editId: String? = null
    private var fromFloating = false
    private var editTarget: Countdown? = null
    private var pickSoundOnly = false
    private var draftSoundUri: String? = null   // 待写入的自定义提示音 URI（null = 默认提示音）
    private var soundBtn: Button? = null

    // v156：色板挪到 Countdown.kt 的 ThemeColors，跟卡片上那颗「颜色」按钮共用同一份
    private val colors = ThemeColors.ARGS
    private val colorNames = ThemeColors.NAMES

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
        // v162：「倒计时类型」这一级只摆两项 —— 普通倒计时 / 内置倒计时。
        // 底下那一层「内置档位」v162 整个收掉了：是不是内置由这一级定，
        // 内置这一档是哪一档由这条自己来 —— 编辑已有内置就是它原来那一档，新建是「每小时」兜底。
        // 这条倒计时在编辑 / 准备生成的类型（显示模式清单、提示音开关全按它走）。
        // v162：界面上这一级只分「普通 / 内置」两格 —— 新建、重装、首次装一律默认普通，
        // 不记上次挑的那一档；只有编辑已有条目时才带出它自己原来的类型。
        // 新建时先给个「每小时」兜底：用户下一步真去点「内置倒计时」，就落在这一档上。
        var pickedBuiltIn: Int = c?.builtIn ?: BuiltIn.HOUR

        colorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colorNames)
        (colorSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // v164：内置这一档把「全部显示模式」一列到底（第几格就是几号模式，
        // 挑哪个都按这条自己的格式落地）；普通倒计时照旧只摆它用得上的那几档
        val modeNameList = if (pickedBuiltIn != BuiltIn.NONE) CountdownFormatter.allModeNames()
            else CountdownFormatter.modeNames(pickedBuiltIn)
        // 「显示模式」下拉框：唯一挑显示方式的地方，选项与该倒计时的可选模式一致
        // （普通倒计时按类型自动收掉装不下的那几档；内置这一档整份模式都列着），随时能改，没次数限制。
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
        // v163：「是否循环」下拉框 —— 挑「是」归零就重新起一轮，挑「否」归零就停住
        val loopSpinner = findViewById<Spinner>(R.id.loopSpinner)
        val soundBlock = findViewById<View>(R.id.soundBlock)
        // ⚠️ v159：「100年内倒计时」那一档整个删了，v158 补的那颗「启用自定义提示音」
        // v159：「100年内倒计时」连带的那颗「启用自定义提示音」开关（它外面那一行 + 里头的 Switch）
        // 跟着这一档一起收掉，现在只剩 soundToggleBlock 这一层壳。
        // 它跟 soundBlock 一起管着提示音那扇门：光设里头的 soundBlock 会被这层 gone 吞掉。
        val soundToggleBlock = findViewById<View>(R.id.soundToggleBlock)

        // ---------- 六连框（年 / 月 / 日 / 时 / 分 / 秒）：v159 起整块恒收着 ----------
        // 六颗下拉框（年 / 月 / 日 / 时 / 分 / 秒）：与页面上其它下拉框同一套 GlassSpinner 样式，
        // 排成一行六等分，随便挑 100 年以内任意一个日子 + 时 + 分 + 秒就是这条倒计时的目标时刻。
        val timeOfDayBlock = findViewById<View>(R.id.timeOfDayBlock)
        val yearSpinner = findViewById<Spinner>(R.id.yearSpinner)
        val monthSpinner = findViewById<Spinner>(R.id.monthSpinner)
        val daySpinner = findViewById<Spinner>(R.id.daySpinner)
        val hourSpinner = findViewById<Spinner>(R.id.hourSpinner)
        val minuteSpinner = findViewById<Spinner>(R.id.minuteSpinner)
        val secondSpinner = findViewById<Spinner>(R.id.secondSpinner)



        // v162：「倒计时类型」这一级就两项 —— 普通倒计时 / 内置倒计时，不再有下面那一层「内置档位」。
        // 一级只管「是不是内置」：是内置的话，它这一档就用这条自己原来的类型（编辑已有内置）
        // 或者新建时的「每小时」兜底，都不必再挑一遍档位。
        builtInSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(BuiltIn.nameOf(BuiltIn.NONE), BUILT_IN_LABEL)
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // v163：「是否循环」下拉框（跟页面上其它下拉框同一套 GlassSpinner 样式）：
        // 挑「是」这条归零后自己重新起一轮，挑「否」归零就停在 0
        // v170：这一格从此只「如实显示」—— 内置倒计时由它的档位定死（每分钟 / 每小时 / 当日 /
        // 每周 … 这些自己往下跳的档恒「是」；华都云境悦府 / GTA6 日子定死、归零停住，恒「否」），
        // 点也点不动；只有普通倒计时才存自己那份 loop、由用户挑。
        var loop = if (c != null && c.builtIn == BuiltIn.NONE) c.loop else false
        loopSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(LOOP_NO, LOOP_YES)
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // v167 / v168 / v169 / v170：显示必须跟这条真正会不会循环对齐 —— 该「是」的打开就停在「是」，
        // 该「否」的停在「否」，绝不统一落第 0 项「否」。
        // ⚠️ Spinner 在第一次 layout 之前 setSelection 会被适配器首装冲掉（显示回落到第 0 项「否」），
        // 首装冒出来的第一记回调还会把 loop 写回 false —— 这就是该「是」的条目打开显示「否」的根因。
        // 三道保险：① post 里选（等这一帧画完再选才稳得住）；② 再补几记延迟复核，一直盯到 1.5 秒
        // （个别机型会在更晚一帧又把选中态冲回第 0 格）；③ 落盘压根不看这一格「此刻显示什么」——
        // 内置项一律按档位落、普通项用户没动过就沿用它自己存的 loop（见 saveBtn 里的 loopNow）。
        var loopTouched = false
        // 这一格此刻「该」停在第几格：内置看档位（周期滚动型=是，华都 / GTA6=否），普通看它存着的 loop。
        fun wantLoop(): Int =
            if (pickedBuiltIn != BuiltIn.NONE) (if (BuiltIn.isRolling(pickedBuiltIn)) 1 else 0)
            else (if (loop) 1 else 0)
        // v170：内置这一格只显示、不许改 —— 手指落上去直接把事件吃掉（下拉不弹、值也不动）；
        // 普通倒计时照旧：抬手那一记才算「用户动过」，复核就此收手、以他挑的那一格为准。
        loopSpinner.setOnTouchListener { _, ev ->
            if (pickedBuiltIn != BuiltIn.NONE) return@setOnTouchListener true
            if (ev.actionMasked == MotionEvent.ACTION_UP) loopTouched = true
            false
        }
        // v169 / v170：复核六记、一直盯到 1.5 秒（0/120/300/600/1000/1500 毫秒）。
        // 有些机型要到打开页面后更晚的一帧才把这一格冲回第 0 项「否」，前几记都赶不上；
        // 一律「只要那一格没停在该停的格子上，就当场按档位 / 存值选回去」。
        // 内置项永远复核（它天生不该被改）；普通项用户动过之后就不再插手。
        for (delay in longArrayOf(0L, 120L, 300L, 600L, 1000L, 1500L)) {
            loopSpinner.postDelayed({
                if (pickedBuiltIn != BuiltIn.NONE || !loopTouched) {
                    val w = wantLoop()
                    if (loopSpinner.selectedItemPosition != w) loopSpinner.setSelection(w)
                }
            }, delay)
        }

        // 界面上摆不摆「挑提示音」那一整块：周期滚动型内置项（每分钟 / 每小时 / 当日 …）一律不摆，
        // 普通倒计时、华都云境悦府、GTA6 这些能挑的照旧摆着；就这一行 soundEditable() 说话。
        var soundOn = if (pickedBuiltIn != BuiltIn.NONE) (c?.soundEnabled ?: false) else true
        // v163：那颗「启用自定义提示音」的开关现在只给内置倒计时摆 —— 普通倒计时照旧
        // 直接给那两颗按钮，内置这一档开 / 关由它说话（关着主界面卡片上也不摆提示音按钮）
        val soundToggleRow = findViewById<View>(R.id.soundToggleRow)
        val soundToggle = findViewById<Switch>(R.id.soundToggle)
        soundToggle.isChecked = soundOn

        /** 提示音那两颗按钮跟着 soundOn（内置这一档由那颗开关定）显隐；顺手把按钮上的提示音名刷了。 */
        fun applySoundVisibility() {
            soundBlock.visibility = if (soundOn) View.VISIBLE else View.GONE
            refreshSoundLabel()
        }

        soundToggle.setOnCheckedChangeListener { _, on ->
            // 只有内置这一档摆着这颗开关；普通倒计时那层壳是全展开的，不跟着它动
            if (pickedBuiltIn != BuiltIn.NONE) {
                soundOn = on
                applySoundVisibility()
            }
        }

        /** 这条倒计时只剩一种显示模式时，把「标准模式」标题 / 下拉框 / 那句说明一起收掉。 */
        fun applyModeBlock() {
            // v164：内置这一档下拉框恒展开（它摆的是整份模式，永远有得挑）；
            // 普通倒计时照旧 —— 只剩一种显示时（例如「每分钟」）把标题 / 下拉框 / 说明一起收掉
            val single = pickedBuiltIn != BuiltIn.NONE &&
                !CountdownFormatter.hasModeSwitch(pickedBuiltIn)
            standardSpinner.visibility = if (single) View.GONE else View.VISIBLE
            findViewById<View>(R.id.standardModeLabel).visibility =
                if (single) View.GONE else View.VISIBLE
            findViewById<View>(R.id.standardModeHint).visibility =
                if (single) View.GONE else View.VISIBLE
        }

        /** 倒计时类型一换，下面这几样跟着全换一遍。 */
        fun applyTypeDependant() {
            // v163：内置这一档由那条自己那颗「启用自定义提示音」开关定；
            // 普通倒计时那两颗按钮直接摆着，不用先勾开关
            soundOn = if (pickedBuiltIn != BuiltIn.NONE) soundToggle.isChecked else true
            soundToggle.isChecked = soundOn
            // ⚠️ v146 只把那颗 Switch 摆出来，忘了它外面还套着一层 gone 的 soundToggleBlock、
            // v159：以及装着它的那一行，开关一直被父层挡着，看不见也点不到。
            // v158 把这两层壳一起管起来：整块（开关那一行 + 下面那两颗按钮）只在「100年内倒计时」
            // 亮出来；其余能挑提示音的几档（普通 / 华都云境悦府 / GTA6）那两颗按钮直接摆，
            // 不用先勾开关；周期滚动型内置项整块收着。
            soundToggleBlock.visibility = if (soundOn) View.VISIBLE else View.GONE
            // v163：那颗开关在内置这一档摆着（普通倒计时抽掉），下面那两颗按钮照 soundOn 走；
            // 这一层壳恒展开，不然开关会被它自己的父层收掉（v158 那个 gone 父层的老坑）
            soundToggleRow.visibility =
                if (pickedBuiltIn != BuiltIn.NONE) View.VISIBLE else View.GONE
            soundToggleBlock.visibility = View.VISIBLE
            applySoundVisibility()
            // 模式下拉框的清单按类型重新算（每5分钟没有「时分秒」，每天没有「天数模式」…）
            // v164：内置这一档整份模式都列着，用户一眼看得到全部显示模式；普通倒计时照旧
            if (pickedBuiltIn != BuiltIn.NONE) {
                standardSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item,
                    CountdownFormatter.allModeNames()
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                standardSpinner.setSelection(
                    CountdownFormatter.normalizeMode(
                        c?.displayMode ?: CountdownFormatter.MODE_STANDARD
                    )
                )
            } else {
                standardSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_item,
                    CountdownFormatter.modeNames(pickedBuiltIn)
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                standardSpinner.setSelection(
                    CountdownFormatter.modeIndex(
                        c?.displayMode ?: CountdownFormatter.MODE_STANDARD, pickedBuiltIn
                    )
                )
            }
            applyModeBlock()
            // v170：类型一换，这一格也跟着换成新类型的循环值（内置按档位、普通按存着的）——
            // 挑「内置倒计时」就按它那一档显示，换回「普通倒计时」就按普通那份 loop 显示。
            loopTouched = false
            loopSpinner.setSelection(wantLoop())
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

        /** 六连框填值 / 天数跟着年月校正一遍；initial = true 时按当前时刻给出初值。 */
        fun setupTimeOfDayPickers(initial: Boolean) {
            val now = Calendar.getInstance()
            if (initial) {
                val y = now.get(Calendar.YEAR)
                // 年：今年到 100 年后（v159 起整块恒收着，范围照老规矩留着）
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

        /** 六连框恒收着（v159：「100年内倒计时」整个删掉，再没有哪一档用得上这六颗下拉框）。 */
        fun applyTimeOfDayVisibility() {
            timeOfDayBlock.visibility = View.GONE
            dateLabel.visibility = View.VISIBLE
            datePicker.visibility = View.VISIBLE
            timeLabel.visibility = View.VISIBLE
            timePicker.visibility = View.VISIBLE
        }

        // 六颗下拉框任意一颗一变：天数跟着校正（v159：名字自动那套随「100年内倒计时」一并删了）
        for (sp in listOf(yearSpinner, monthSpinner, daySpinner, hourSpinner, minuteSpinner, secondSpinner)) {
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    setupTimeOfDayPickers(false)
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
        soundBtn = findViewById<Button>(R.id.btnSound)
        val clearSoundBtn = findViewById<Button>(R.id.btnClearSound)
        soundBtn?.setOnClickListener { openRingtonePicker() }
        clearSoundBtn.setOnClickListener {
            draftSoundUri = null
            refreshSoundLabel()
        }
        // 类型换档：标题跟着换成这一型的默认名（原来那句还没改过就跟着换），下面这几块全刷一遍。
        // v162：一级只分「普通 / 内置」两格 —— 下面那一层「内置档位」已经收掉了，
        // 挑「内置倒计时」就按这条自己原来的类型（编辑已有内置）或新建时的「每小时」兜底走，
        // 这里不另挑档位。
        var prevType = pickedBuiltIn
        builtInSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val prev = BuiltIn.nameOf(prevType)
                if (position == 0) {
                    pickedBuiltIn = BuiltIn.NONE
                } else {
                    if (pickedBuiltIn == BuiltIn.NONE) pickedBuiltIn = BuiltIn.HOUR
                }
                val t = titleEt.text.toString()
                // v164：添加倒计时的名字默认就是空的 —— 只有编辑已有条目时才拿这一型的默认名兜底，
                // 新建进这页不许顺手填一个「每小时」上去，用户想写就自己写
                if (c != null && (t.isBlank() || t == prev)) titleEt.setText(BuiltIn.nameOf(pickedBuiltIn))
                prevType = pickedBuiltIn
                applyTypeDependant()
                applyTimeOfDayVisibility()
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
        // 六连框先按当前时刻填好（要先于下面「类型」下拉框那句 setSelection：那句会当场回调一次，
        // 那时六连框得已经有值；v159 这一整套现在恒收着，留着只是给老数据编辑页一个说得通的路）
        setupTimeOfDayPickers(true)
        // 初始值：一级落在它自己那一格（普通 / 内置）
        builtInSpinner.setSelection(if (pickedBuiltIn == BuiltIn.NONE) 0 else 1)
        applyTimeOfDayVisibility()
        applyTypeDependant()
        // v167：「是否循环」那格的选中态由初始化块里的 post 统一钉住，这里不再兜底读 ——
        // 早到的 position=0 会把存着的「是」污染成「否」，读这个动作本身就是坑
        // 从卡片上的「提示音名称」按钮进来的：直接把系统铃声选择器顶上去，选完即存
        if (pickSoundOnly && c != null) {
            draftSoundUri = c.soundUri
            refreshSoundLabel()
            openRingtonePicker()
        }


        saveBtn.setOnClickListener {
            val list = CountdownStore.load(this)
            // 目标时刻一律取顶部那套「目标日期 / 目标时间」
            val target = Calendar.getInstance().apply {
                set(Calendar.YEAR, datePicker.year)
                set(Calendar.MONTH, datePicker.month)
                set(Calendar.DAY_OF_MONTH, datePicker.dayOfMonth)
                set(Calendar.HOUR_OF_DAY, timePicker.hour)
                set(Calendar.MINUTE, timePicker.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // 存哪一档就是哪一档：停在「普通倒计时」就是普通倒计时，挑了某个内置档就按那个内置类型存。
            val finalBuiltIn = pickedBuiltIn
            // v163：记下「这一轮有多长」（循环重新计时就照它一轮轮往下转）。
            // 内置项那一刻是系统自己算的，得先问它；目标已经过去了就把这一轮顺延到
            // 「现在 + 这一轮的时长」，免得刚存下去立刻又归零、一下接一下地响
            val saveNow = AlignedClock.now()
            // v170：「是否循环」落盘 —— 内置倒计时一律按它的档位算（周期滚动型=是，华都 / GTA6=否），
            // 这一格改不动它；普通倒计时才认这一格：用户动过就以他挑的为准，没动过沿用存着的 loop。
            // （同样不看这一格「此刻显示什么」，显示万一被系统冲回第 0 项「否」也绝不会把「是」写坏。）
            val loopNow = if (finalBuiltIn != BuiltIn.NONE) BuiltIn.isRolling(finalBuiltIn)
                else if (loopTouched) (loopSpinner.selectedItemPosition == 1) else loop
            val loopRef = if (finalBuiltIn != BuiltIn.NONE) (c?.currentBuiltInTarget() ?: target) else target
            val loopSpan = (loopRef - saveNow).coerceAtLeast(1000L)
            val finalTarget = if (loopNow && target <= saveNow) saveNow + loopSpan else target

            if (c != null) {
                // 关键修复：必须修改“即将保存的 list”里的同一个对象，否则改的是
                // onCreate 里另一份旧列表的副本，磁盘上不会被更新（改名/改其它字段都无效）。
                val existing = list.find { it.id == c.id }
                if (existing != null) {
                    existing.title = titleEt.text.toString()
                    // 内置项：目标时刻由系统自己往下跳（每小时 / 每半小时 / 当日 / 每周 / 当月），
                    // 用户在这里改的日期时间不会存盘，免得下一帧就被系统算出来的目标覆盖掉、看着像「白改」。
                    // 无论怎么改，它都还是内置倒计时（ BuiltIn 保持原样，绝不降级成普通倒计时）。
                    val sysTarget = if (existing.builtIn != BuiltIn.NONE) existing.currentBuiltInTarget()
                        else finalTarget
                    existing.targetTime = sysTarget
                    existing.customColorArgb = colors[colorSpinner.selectedItemPosition]
                    // v164：内置这一档摆的是整份模式，第几格就是几号，直接按格号取
                    existing.displayMode = if (pickedBuiltIn != BuiltIn.NONE) {
                        CountdownFormatter.rawModeAt(standardSpinner.selectedItemPosition)
                    } else {
                        CountdownFormatter.modeAt(standardSpinner.selectedItemPosition, pickedBuiltIn)
                    }
                    existing.animStyle = animSpinner.selectedItemPosition
                    existing.remark = remarkEt.text.toString()
                    // 倒计时类型本身也能改（普通 ↔ 内置随便换，换完它还是内置 / 还是普通倒计时）
                    val oldType = existing.builtIn
                    existing.builtIn = finalBuiltIn
                    // v161：内置改成普通以后，这一条就不再算内置了 —— 「复」字里也别再挂着它，
                    // 连它之前被删时存下的那份设置快照一起拿掉（同档还有别条的话就留着那份）。
                    if (oldType != BuiltIn.NONE && finalBuiltIn == BuiltIn.NONE &&
                        list.none { it.builtIn == oldType && it.id != existing.id }
                    ) dropBuiltInMarkAndBackup(oldType)
                    // 提示音：开关关着就回到默认提示音（清掉可能存过的自定义音），
                    // 开着挑的那个就是挑的那个
                    existing.soundEnabled = soundOn
                    existing.soundUri = if (soundOn) draftSoundUri else null
                    // v163：循环 —— 归零要不要重新起一轮，以及这一轮的时长
                    existing.loop = loopNow
                    existing.loopSpan = loopSpan
                }
            } else {
                // 同类型内置项、或者同名的那条已经躺在列表里了：这一下不许再生成，
                // 弹一句「该倒计时已存在，生成失败！」，什么都不存、直接留在这页。
                // ⚠️ 内置类型一律一条：同类型的那条已经躺在列表里就别再造第二条
                val dupBuiltIn = list.any { it.builtIn == finalBuiltIn }
                val dupTitle = list.any {
                    it.title == titleEt.text.toString().trim() && it.title.isNotEmpty()
                }
                if (finalBuiltIn != BuiltIn.NONE && (dupBuiltIn || dupTitle)) {
                    Toast.makeText(
                        this,
                        "该倒计时已存在，生成失败！",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                list.add(
                    Countdown(
                        title = titleEt.text.toString(),
                        targetTime = target,
                        customColorArgb = colors[colorSpinner.selectedItemPosition],
                        // v164：内置这一档摆的是整份模式，第几格就是几号模式
                        displayMode = if (pickedBuiltIn != BuiltIn.NONE) {
                            CountdownFormatter.rawModeAt(standardSpinner.selectedItemPosition)
                        } else {
                            CountdownFormatter.modeAt(
                                standardSpinner.selectedItemPosition, pickedBuiltIn
                            )
                        },
                        animStyle = animSpinner.selectedItemPosition,
                        remark = remarkEt.text.toString(),
                        // 内置项的目标时刻由系统自己往下跳，这里填的日期时间只是个起点，
                        // 存下来也不会把它降级成普通倒计时
                        builtIn = finalBuiltIn,
                        soundUri = if (soundOn) draftSoundUri else null,
                        // v163：循环（归零重新计时）与内置项那颗「启用自定义提示音」开关
                        loop = loopNow,
                        loopSpan = loopSpan,
                        soundEnabled = soundOn
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







    // ---------- v161：内置改成普通以后，把这条在「复」字里留的记号与快照抹掉 ----------

    /**
     * 这条内置倒计时在编辑页被改成普通倒计时以后，把「复」字里留着它的那点痕迹一并抹掉：
     * 一是「哪些内置已被删」的记号（types 里那一条），二是它那份设置快照（backup_{档位}）。
     * 「复」字的找回清单按记号走、按快照还原设置，记号一抹，它就既列不出来也还原不了。
     */
    private fun dropBuiltInMarkAndBackup(type: Int) {
        val prefs = getSharedPreferences("builtin_removed", MODE_PRIVATE)
        val types = (prefs.getStringSet("types", emptySet()) ?: emptySet()).toMutableSet()
        if (types.remove(type.toString())) {
            prefs.edit().putStringSet("types", types).apply()
        }
        prefs.edit().remove("backup_$type").apply()
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
