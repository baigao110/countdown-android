package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 意见反馈页（v69 新增）。
 *
 * 想做的三件事：
 * 1. 昵称、联系方式**可以不填**，把问题说清楚就行 —— 怎么方便怎么来；
 * 2. 能附图片 / 视频：从相册挑（支持多选，点「添加」反复挑也行），选中会在下面排成一排小胶囊，点一下撤掉；
 * 3. 可选的「附带运行日志」：把机型、系统版本、应用版本、当前倒计时列表、几类开关状态写成一个 txt 一起发，
 *    定位问题最快；里面不包含你的备注内容。
 *
 * 提交走「自己发」：工程刻意零第三方依赖，所以内置了一个极简 SMTP 发信器（SmtpSender），
 * 「提交」= 直接用你在页面上填的邮箱（账号 + 授权码，只存本机）把信发到 baigao110@qq.com，
 * **不会打开手机上的邮箱应用**，发完立刻提示「反馈已发送，请耐心等待回复」。
 * 邮箱服务器地址按后缀自动认（QQ 邮箱 smtp.qq.com 等），登录走授权码。
 * 授权码没填、或者网络 / 授权码不对发不出去时，才退回原来的老路：
 * 打开邮箱应用（首选 ACTION_SEND，各家邮箱只在这条路上老老实实挂图片 / 视频；
 * ACTION_SENDTO 那条路会被 Gmail 和不少手机自带邮箱直接丢掉附件），
 * 以及「复制内容备用」兜底，粘到微信 / QQ 发同样收得到。
 *
 * 附件用 FeedbackProvider（content:// + FLAG_GRANT_READ_URI_PERMISSION）交给邮箱读取，
 * 既不往公共存储里塞文件，也不需要任何存储权限。
 */
class FeedbackActivity : Activity() {

    /** 反馈收件人（与发版说明保持一致）。 */
    companion object {
        const val FEEDBACK_EMAIL = "baigao110@qq.com"
        private const val REQ_PICK_MEDIA = 1006
        private const val MAX_MEDIA = 9
        /** 运行日志附件的规范名：跟图片视频一样走同一套类型认得出来的通路。 */
        private const val LOG_ATTACH_NAME = "fb_log.txt"
        /** 发信设置（发件邮箱 + 授权码）只存本机，下次进页面自动回填。 */
        private const val PREFS_SMTP = "feedback_smtp"
        private const val KEY_SMTP_FROM = "from"
        private const val KEY_SMTP_PASS = "pass"
        /** 直发时一个附件超过这个数（1MB 的整数倍）就提醒一句，免得等太久。 */
        private const val WARN_TOTAL_BYTES = 12L * 1024 * 1024
    }

    private lateinit var nicknameEt: EditText
    private lateinit var contactEt: EditText
    private lateinit var problemEt: EditText
    private lateinit var addMediaBtn: Button
    private lateinit var attachChips: LinearLayout
    private lateinit var attachCountTv: TextView
    private lateinit var logSwitch: Switch
    private lateinit var logDescTv: TextView
    private lateinit var submitBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var fromEt: EditText
    private lateinit var passEt: EditText
    private lateinit var smtpStatusTv: TextView

    /** 已经挑好的图片 / 视频（放到缓存目录里，等提交时作为附件发出去）。 */
    private val attachments = ArrayList<File>()

    /** 上一次写出来的运行日志原文（贴进邮件正文兜底用，见 submit()）。 */
    private var logTextCache = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        nicknameEt = findViewById(R.id.nicknameEt)
        contactEt = findViewById(R.id.contactEt)
        problemEt = findViewById(R.id.problemEt)
        addMediaBtn = findViewById(R.id.addMediaBtn)
        attachChips = findViewById(R.id.attachChips)
        attachCountTv = findViewById(R.id.attachCountTv)
        logSwitch = findViewById(R.id.logSwitch)
        logDescTv = findViewById(R.id.logDescTv)
        submitBtn = findViewById(R.id.submitBtn)
        copyBtn = findViewById(R.id.copyBtn)
        fromEt = findViewById(R.id.fromEt)
        passEt = findViewById(R.id.passEt)
        smtpStatusTv = findViewById(R.id.smtpStatusTv)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        // 添加附件：挑图片还是挑视频，弹个小菜单让用户说了算
        addMediaBtn.setOnClickListener {
            if (attachments.size >= MAX_MEDIA) {
                Toast.makeText(this, "一次最多带 $MAX_MEDIA 个附件哦，先发这批～", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("要发哪一类？")
                .setMessage("可以同时加好几个，选完还能接着加")
                .setPositiveButton("图片") { _, _ -> pickMedia(video = false) }
                .setNegativeButton("视频") { _, _ -> pickMedia(video = true) }
                .show()
        }

        logSwitch.setOnCheckedChangeListener { _, _ -> refreshLogDesc() }
        submitBtn.setOnClickListener { submit() }
        copyBtn.setOnClickListener { copyToClipboard() }

        refreshLogDesc()
        refreshAttachUi()
        // 上次填过的发信邮箱回填出来（授权码不回显，免得旁边有人看见）
        restoreSmtpCreds()
        refreshSmtpStatus()
    }

    // ---------------------------------------------------------------- 发信设置

    /** 本地偏好里读出上次的发信邮箱，回填到输入框。 */
    private fun restoreSmtpCreds() {
        val prefs = getSharedPreferences(PREFS_SMTP, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SMTP_FROM, "") ?: ""
        fromEt.setText(saved)
        fromEt.setSelection(saved.length)
    }

    private fun saveSmtpCreds(from: String, pass: String) {
        getSharedPreferences(PREFS_SMTP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SMTP_FROM, from)
            .putString(KEY_SMTP_PASS, pass)
            .apply()
    }

    private fun smtpFrom(): String = fromEt.text?.toString()?.trim().orEmpty()
    private fun smtpPass(): String = passEt.text?.toString()?.trim().orEmpty()

    /** 状态行：已经记住了 / 还没填，以及发到哪儿去。 */
    private fun refreshSmtpStatus() {
        val from = smtpFrom()
        val pass = smtpPass()
        val server = if (from.contains("@")) SmtpSender.guess(from, "")?.host else null
        smtpStatusTv.text = when {
            from.isBlank() || pass.isBlank() ->
                "还没填邮箱：填完上面两项，点「提交反馈」就能后台直接发（不打开邮箱应用）"
            else -> "已记住：$from（$server） → $FEEDBACK_EMAIL，点提交后台直接发"
        }
    }

    // ---------------------------------------------------------------- 选附件

    private fun pickMedia(video: Boolean) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = if (video) "video/*" else "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQ_PICK_MEDIA)
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                "打不开相册呢：请在系统设置里给本应用放行「照片 / 视频」访问权限",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_MEDIA || resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        val name = displayName(uri) ?: (uri.lastPathSegment ?: "attachment")
        val f = copyToCache(uri, name)
        if (f == null) {
            Toast.makeText(this, "这个文件读不出来呢（可能来自特殊应用），换一张试试", Toast.LENGTH_LONG).show()
            return
        }
        if (attachments.any { it.name == f.name }) {
            Toast.makeText(this, "这个已经加过啦～", Toast.LENGTH_SHORT).show()
            return
        }
        if (attachments.size >= MAX_MEDIA) {
            Toast.makeText(this, "一次最多带 $MAX_MEDIA 个附件哦", Toast.LENGTH_SHORT).show()
            return
        }
        attachments.add(f)
        refreshAttachUi()
        Toast.makeText(this, "加好啦：${f.name}", Toast.LENGTH_SHORT).show()
    }

    /** 从系统相册读一个 content://，复制进缓存目录（这样后面分享不需要任何存储权限）。 */
    private fun copyToCache(uri: Uri, rawName: String): File? {
        return try {
            val dir = FeedbackProvider.attachDir(this)
            dir.mkdirs()
            var name = rawName.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            if (name.isBlank()) name = "attachment"
            var f = File(dir, name)
            var i = 1
            while (f.exists() && f.length() > 0) {
                val dot = name.lastIndexOf('.')
                f = if (dot > 0) {
                    File(dir, "${name.substring(0, dot)}_$i${name.substring(dot)}")
                } else {
                    File(dir, "${name}_$i")
                }
                i++
            }
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(f).use { out -> input.copyTo(out) }
            }
            if (!f.exists() || f.length() == 0L) null else f
        } catch (e: Throwable) {
            null
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 已选附件排成小胶囊，点一下撤掉那一个。 */
    private fun refreshAttachUi() {
        attachChips.removeAllViews()
        for (i in attachments.indices) {
            val f = attachments[i]
            val chip = TextView(this).apply {
                text = "${if (isVideoFile(f.name)) "视频" else "图片"} ${i + 1} ✕"
                textSize = 12f
                setTextColor(resources.getColor(android.R.color.white))
                background = resources.getDrawable(R.drawable.pill_btn_gray, null)
                gravity = Gravity.CENTER
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            chip.setOnClickListener {
                attachments.removeAt(i)
                refreshAttachUi()
            }
            attachChips.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 8 }
            )
        }
        val n = attachments.size
        attachCountTv.text = if (n == 0) "" else "已经带了 $n 个附件（点上面的小胶囊可以撤掉）"
        addMediaBtn.isEnabled = n < MAX_MEDIA
    }

    private fun isVideoFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".3gp") ||
            n.endsWith(".mkv") || n.endsWith(".avi")
    }

    // ---------------------------------------------------------------- 文案与日志

    private fun refreshLogDesc() {
        logDescTv.text = if (logSwitch.isChecked) {
            "日志已勾选：提交时会把手机型号、系统版本、应用版本和当前倒计时列表一起附上，定位问题最快"
        } else {
            "不勾也行：只发你写的文字和图片；勾上能帮助我更快找到原因（不含你的备注内容）"
        }
    }

    /** 正文：把昵称、联系方式、问题描述、环境信息拼成一份能直接看明白的邮件正文。 */
    private fun buildBody(): String {
        val sb = StringBuilder()
        sb.append("【倒计时工具 · 意见反馈】\n")
        val nick = nicknameEt.text?.toString()?.trim().orEmpty()
        val contact = contactEt.text?.toString()?.trim().orEmpty()
        if (nick.isNotBlank()) sb.append("昵称：").append(nick).append('\n')
        if (contact.isNotBlank()) sb.append("联系方式：").append(contact).append('\n')
        sb.append("提交时间：${nowText()}\n")
        sb.append("设备：${Build.MANUFACTURER} ${Build.MODEL} ／ Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
        sb.append("应用版本：v${UpdateManager.CURRENT_VERSION_NAME}\n\n")
        val problem = problemEt.text?.toString()?.trim().orEmpty()
        sb.append("问题描述：\n").append(problem).append("\n")
        return sb.toString()
    }

    /**
     * 「附带运行日志」那份 txt：真正能帮我定位的东西。
     *
     * ⚠️ 每一段都自己兜住：以前整段包在一个 try 里，等于「读倒计时列表 / 读 logcat / 读开关」
     * 任何一小步没成功，整份日志就作废 —— 而调用方会把它当「附件没了」静默跳过，
     * 最后你点提交进邮箱，附件栏空空的，还得自己再挑一次图片或视频。这种「悄悄少一个附件」
     * 是最难查的，所以现在各段互不影响，丢就丢那一段，日志本身一定还在。
     */
    private fun buildLogText(): String {
        val sb = StringBuilder()
        sb.append("倒计时工具 · 运行信息\n==============\n")
        // ① 基础信息
        try {
            sb.append("提交时间：${nowText()}\n")
            sb.append("应用版本：v${UpdateManager.CURRENT_VERSION_NAME}（versionCode ${appVersionCode()}）\n")
            sb.append("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
            sb.append("设备：${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE} / ${Build.DISPLAY}\n")
            val dm = resources.displayMetrics
            sb.append("屏幕：${dm.widthPixels}x${dm.heightPixels}（${dm.densityDpi}dpi）\n")
            sb.append("语言：${Locale.getDefault()}  时区：${zoneText()}\n\n")
        } catch (_: Throwable) {
            sb.append("（基础信息读不出来）\n\n")
        }
        // ② 开关状态
        try {
            sb.append("长按开关状态：\n")
            sb.append("  UI 界面常亮：${yesNo(ScreenKeepOn.isUiOn(this))}\n")
            sb.append("  悬浮框常亮：${yesNo(ScreenKeepOn.isFloatOn(this))}\n")
            sb.append("  锁屏通知显示：${yesNo(LockScreenClock.isOn(this))}\n")
            sb.append("  锁屏通知常亮：${yesNo(LockKeepOn.isOn(this))}\n\n")
        } catch (_: Throwable) {
            sb.append("（开关状态读不出来）\n\n")
        }
        // ③ 当前倒计时列表
        try {
            val list = CountdownStore.load(this)
            sb.append("当前倒计时（${list.size} 条）：\n")
            list.forEachIndexed { i, c ->
                val mode = CountdownFormatter.modeName(c.displayMode, c.builtIn)
                sb.append("  ${i + 1}. ${c.title} ｜ 模式：$mode ｜ 剩余：${c.remainingText()} ｜ 已展开：${yesNo(c.isVisible)}\n")
            }
            sb.append('\n')
        } catch (_: Throwable) {
            sb.append("（当前倒计时列表读不出来）\n\n")
        }
        // ④ logcat 片段
        try {
            sb.append("运行日志（logcat 最近片段）：\n")
            sb.append(tailLogcat())
        } catch (_: Throwable) {
            sb.append("（运行日志读不出来）")
        }
        return sb.toString()
    }

    private fun writeLogFile(): File? {
        return try {
            val text = buildLogText()
            // 原文留一份：只有「运行日志」这一个文本附件时，邮箱不一定肯把它挂成附件，
            // 那就同一份内容再贴进邮件正文，保证这封反馈里一定有东西，不会逼你回头自己再挑图。
            logTextCache = text
            val dir = FeedbackProvider.attachDir(this)
            dir.mkdirs()
            val f = File(dir, LOG_ATTACH_NAME)
            FileOutputStream(f).use { out ->
                out.write(text.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }
            f
        } catch (_: Throwable) {
            null
        }
    }

    /** logcat 普通应用读不到系统日志，这里能拿到多少算多少（拿不到就留一句提示，不影响提交）。 */
    private fun tailLogcat(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200", "-v", "time"))
            proc.inputStream.bufferedReader().useLines { seq ->
                seq.filter { it.isNotBlank() }.take(200).joinToString("\n")
            }
        } catch (_: Throwable) {
            "（无权限读取系统日志，跳过）"
        }
    }

    private fun appVersionCode(): Int {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
        } catch (_: Throwable) {
            -1
        }
    }

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun yesNo(b: Boolean) = if (b) "开" else "关"

    private fun zoneText(): String = java.util.TimeZone.getDefault().id

    // ---------------------------------------------------------------- 提交

    private fun submit() {
        val problem = problemEt.text?.toString()?.trim().orEmpty()
        if (problem.isEmpty()) {
            Toast.makeText(
                this,
                "写两句问题吧～（昵称和联系方式可以不填，但问题得留一句给我）",
                Toast.LENGTH_LONG
            ).show()
            problemEt.requestFocus()
            return
        }

        // ① 附件先在缓存里整理成「扩展名跟真实内容对得上」的干净副本（日志和图片视频同一条通路）：
        //    邮箱靠扩展名和类型判断自己认不认得这个附件，认不出（比如 HEIC、没后缀、后缀被改名过，
        //    或者像运行日志那样被判成「通用二进制件」）就当这封信没附件，反过来让你自己再挑一次图。
        val files = ArrayList<File>()
        if (logSwitch.isChecked) {
            val log = writeLogFile()
            if (log == null) {
                // 以前这里会静默跳过：日志没写成就当「没附件」直接发，你进邮箱看到空附件栏，
                // 还以为附件没递过去。写不出来就得说清楚，别让人白跑一趟。
                Toast.makeText(
                    this,
                    "运行日志这次没写出来呢，先把勾去掉再提交（正文一样发得出去）～",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                files.add(log)
            }
        }
        files.addAll(canonicalAttachments())
        // ② 再走一遍真正的 content 通路自检查：读不出来的直接剔掉，绝不半个附件混进去。
        val uris = ArrayList<Uri>()
        val dropped = ArrayList<String>()
        for (f in files) {
            val u = uriOf(f)
            if (readableViaUri(u)) uris.add(u) else dropped.add(f.name)
        }
        if (dropped.isNotEmpty()) {
            Toast.makeText(
                this,
                "「${dropped.first()}」读不出来了呢，先把这个撤掉再提交～",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (uris.isEmpty() && files.isNotEmpty()) {
            Toast.makeText(this, "这几个附件读不出来了呢，先撤掉它们再提交～", Toast.LENGTH_LONG).show()
            return
        }

        // 提交方式先定下来：填了发信邮箱就自己发（直接返回，不再走下面「打开邮箱应用」那几条路），
        // 没填 / 授权码空着时才用老路兜底 —— 老路仍然按「谁在这台手机上真把附件接住」挨个试。
        val from = smtpFrom()
        val pass = smtpPass()
        saveSmtpCreds(from, pass)
        refreshSmtpStatus()
        if (from.contains("@") && pass.isNotBlank()) {
            sendDirect(from, pass, uris, files)
            return
        }
        showSmtpHelpDialog(uris, files)
    }

    // ---------------------------------------------------------------- 直发（不打开邮箱应用）

    /**
     * 自己把信发出去：子线程跑 SMTP，全程不开邮箱应用；发完给用户一句准话。
     *
     * 三点讲究：
     * 1. **一定在子线程**：Socket 连服务器这事儿放主线程上系统会直接判「网络在主线程」卡死；
     * 2. 附件过一遍 content 通路自检（跟给邮箱发时同一套标准）：读不出来的剔掉，宁可少一个
     *    也别发个空壳附件出去，那只会变成「信发出去了，东西没到」；
     * 3. 日志那一份如果在自检里被剔掉了，就把同一份全文贴进正文 —— 信息一定送到。
     */
    private fun sendDirect(from: String, pass: String, uris: List<Uri>, files: List<File>) {
        val acc = SmtpSender.guess(from, pass)
        if (acc == null) {
            Toast.makeText(this, "邮箱地址看着不太对呢，检查一下上面填的那个～", Toast.LENGTH_LONG).show()
            fromEt.requestFocus()
            return
        }
        val sendFiles = ArrayList<File>()
        var total = 0L
        for (f in files) {
            if (f.exists() && f.length() > 0 && readableViaUri(uriOf(f))) {
                sendFiles.add(f)
                total += f.length()
            }
        }
        if (files.isNotEmpty() && sendFiles.isEmpty()) {
            Toast.makeText(this, "这几个附件读不出来了呢，先撤掉它们再提交～", Toast.LENGTH_LONG).show()
            return
        }
        if (total > WARN_TOTAL_BYTES) {
            Toast.makeText(
                this,
                "附件有点大（${total / 1024 / 1024}MB），发过去可能慢一点，我先发，等等看～",
                Toast.LENGTH_LONG
            ).show()
        }
        // 正文：日志勾了就顺手把全文带上（附件真发出去了就在附件里，没发出去就补在正文）。
        val body = buildBody()
        val logOk = sendFiles.any { it.name == LOG_ATTACH_NAME }
        val bodyFull = if (logSwitch.isChecked && logTextCache.isNotBlank()) {
            val tail = if (logOk) "" else "\n\n【运行日志（附件没发成，全文在这里）】\n"
            body + tail + logTextCache + "\n"
        } else {
            body
        }

        submitBtn.isEnabled = false
        submitBtn.text = "正在发送…"
        val progress = UpdateManager.showStyledDialog(
            this, "正在发送…", "", "等一下", true, null
        ) { host ->
            host.addView(noteTv("正在把你的反馈发出去…\n这一小会儿别退出本页面，发完马上告诉你结果"))
        }
        Thread {
            val result = SmtpSender.send(acc, FEEDBACK_EMAIL, "倒计时工具 意见反馈", bodyFull, sendFiles)
            runOnUiThread {
                runCatching { progress.dismiss() }
                submitBtn.isEnabled = true
                submitBtn.text = "提交反馈（直接发到我的邮箱）"
                if (result.ok) {
                    Toast.makeText(applicationContext, "反馈已发送，请耐心等待回复！", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    showSendFailed(result.message, uris, files)
                }
            }
        }.start()
    }

    /** 没填发信邮箱时的引导：说清楚授权码是什么，顺手给一条「还是用邮箱应用」的出口。 */
    private fun showSmtpHelpDialog(uris: List<Uri>, files: List<File>) {
        UpdateManager.showStyledDialog(
            this, "要直接发到我邮箱，先填一下你的邮箱", "就按这个发", "改用邮箱应用", true,
            onPositive = {
                // 关掉对话框后回读输入框：里面就是用户刚填的邮箱和授权码
                val from = smtpFrom()
                val pass = smtpPass()
                saveSmtpCreds(from, pass)
                refreshSmtpStatus()
                if (from.contains("@") && pass.isNotBlank()) {
                    sendDirect(from, pass, uris, files)
                } else {
                    Toast.makeText(this, "上面两处都填上才行：邮箱 + 授权码～", Toast.LENGTH_LONG).show()
                }
            }
        ) { host ->
            host.addView(noteTv("第 1 步：在这页上面填你的邮箱，比如 123456@qq.com（QQ / 163 / 126 都认得）。"))
            host.addView(noteTv("第 2 步：再填邮箱授权码 —— 它不是登录密码。QQ 邮箱在「设置 → 账户 → 开启 IMAP / SMTP 服务」里会给你一个 16 位码，抄过来就行。"))
            host.addView(noteTv("填一次就一直记着，以后点提交都在后台直接发，不再打开邮箱应用。"))
        }
    }

    /** 发失败：说清原因 + 一条自救出口，顺手把空格的授权码清掉让人直接重填。 */
    private fun showSendFailed(reason: String, uris: List<Uri>, files: List<File>) {
        // 摸到失败多半是授权码不对，这里顺手清空输入框、把光标挪过去，改起来不折腾。
        passEt.text = null
        passEt.post { passEt.requestFocus() }
        UpdateManager.showStyledDialog(
            this, "反馈没发出去", "还是用邮箱应用发", "知道了", true,
            onPositive = { openEmailApp(uris) }
        ) { host ->
            host.addView(noteTv(reason))
            host.addView(noteTv("常见两处：① 授权码填成了登录密码（要用那串 16 位授权码）；② 当前没联网或者开了省流量。"))
            host.addView(noteTv("授权码已经帮你清空了，重新填一遍再提交就行；不想折腾就点左边那个按钮接着发，内容一模一样。"))
        }
    }

    /** 老路：打开邮箱应用（附件真的挂进撰写页那条路优先）。 */
    private fun openEmailApp(uris: List<Uri>) {
        val logOnly = logSwitch.isChecked && sharedType(uris).startsWith("text")
        val bodyShort = buildBody() + "\n\n运行日志见附件 fb_log.txt（同名全文也贴在正文，方便直接看）\n"
        val bodyFull = buildBody() + "\n\n【运行日志】\n" + logTextCache + "\n"
        val fileIntent = buildSendIntent(bodyShort, uris, mail = false, asFile = true)
        val textIntent = buildSendIntent(bodyFull, uris, mail = false)
        val mailIntent = buildSendIntent(bodyFull, uris, mail = true)
        val typedIntent = buildSendIntent(bodyFull, uris, mail = false)
        val routes = if (logOnly) listOf(fileIntent, textIntent, mailIntent) else listOf(typedIntent, textIntent, mailIntent)
        val routeExtra = arrayOf(
            "，日志已经当成附件挂上了（fb_log.txt），点一下发送就到啦～",
            "，日志没挂成附件也没关系，同一份全文都贴在正文里，点一下发送就到啦～",
            "，日志全文写在正文里了，点一下发送就到啦～"
        )
        for (i in routes.indices) {
            if (i == 0 && "*/*" == routes[i].type &&
                packageManager.queryIntentActivities(routes[i], PackageManager.MATCH_DEFAULT_ONLY).isEmpty()
            ) {
                continue
            }
            if (openEmail(routes[i], uris)) {
                Toast.makeText(this, okMsg(routeExtra[i]), Toast.LENGTH_LONG).show()
                return
            }
        }
        try {
            grantAll(fileIntent, uris)
            startActivity(Intent.createChooser(fileIntent, "用哪个发给我？"))
            Toast.makeText(this, "没找到邮箱应用，挑一个能收附件的发给我就行～", Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            Toast.makeText(
                this,
                "这台手机上没找到邮箱应用：先点「复制内容备用」，粘到微信 / QQ 发给我也一样收得到～",
                Toast.LENGTH_LONG
            ).show()
            copyToClipboard()
        }
    }

    /** 一小段说明文字：跟页面上其它提示同一套颜色和字号。 */
    private fun noteTv(text: String): TextView =
        TextView(this).apply {
            setText(text)
            setLineSpacing(4f, 1.15f)
            textSize = 13f
            setTextColor(resources.getColor(android.R.color.white))
            setPadding(4, 6, 4, 6)
        }

    /** 提交提示的统一话术：收件人、标题正文都备好，只差点一下发送。 */
    private fun okMsg(extra: String): String =
        "邮箱撰写页开好啦：收件人填成 $FEEDBACK_EMAIL，标题正文都备好$extra"

    /**
     * 打开邮箱写这封信，前提是这台手机上**真有应用接得住**这份 Intent。
     *
     * 返回 false 时调用方继续往下退一层，而不是丢一个闪退给用户看。
     */
    private fun openEmail(intent: Intent, uris: List<Uri>): Boolean {
        return try {
            grantAll(intent, uris)
            startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 拼一个「附件会真的跟过去」的分享 Intent（v70 修邮箱里要再选一次图片的毛病）。
     *
     * 三件事缺一不可：
     * 1. `type`：不少邮箱靠 Intent 的 MIME 判断自己接得住图片类还是视频类，
     *    空类型时它会把附件当作没有 → 于是让你自己再添一次；
     * 2. 单个附件直接 `putExtra(EXTRA_STREAM, uri)`：只认单值的邮箱取不到 ArrayList 就当空；
     * 3. `setClipData`：系统只对 ClipData 与显式 Uri 发读取权限，没它附件读出来是「无权限」。
     */
    private fun buildSendIntent(
        body: String,
        uris: List<Uri>,
        mail: Boolean,
        asFile: Boolean = false
    ): Intent {
        val subject = "倒计时工具 意见反馈"
        val intent = if (mail) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL"))
        } else {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            }
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, body)
        if (uris.isEmpty()) {
            if (!mail) intent.type = "text/plain"
            return intent
        }
        // 纯文本附件（也就是只勾「附带运行日志」）时走「分享一个文件」的分支：
        // 给具体类型会让邮箱把它当成「转发一段文字」，正文照抄、附件直接丢 → 撰写页空着，
        // 又要你自己回头再挑一次图片或视频。给通配类才是各家邮箱认的「收附件」正规路。
        // mailto 那条路（ACTION_SENDTO）刻意不给 type：它靠 mailto 就够接得住了，
        // 再挂一个类型上去反而把候选面搅乱。
        if (!mail) intent.type = if (asFile) "*/*" else sharedType(uris)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        // 顺手确保落到邮箱的「新写信页」：不带这两个 flag 时，startActivity 常常把邮箱里
        // 上次没写完、还挂在后台的旧写信页直接唤起来，那一页上自然没有这次的附件。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris[0])
        else intent.putExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        val clip = ClipData.newUri(contentResolver, subject, uris[0])
        for (i in 1 until uris.size) {
            clip.addItem(ClipData.Item(uris[i]))
        }
        intent.setClipData(clip)
        return intent
    }

    /**
     * 分享的 MIME：永远交给邮箱一个**它认得的具体类型**，绝不轻易丢通配类。
     *
     * 只勾「附带运行日志」时，附件的真实类型是文本类；可 Type 表里没 txt 这一档时
     * 会被判成通用二进制件，这里就落到通配类 —— 而国产邮箱 App 基本只认「图片类」「视频类」
     * 「文本类」这些写死的类型，看到通配就当你是「分享一个普通文件」
     * 而不是「写一封带附件的邮件」，于是撰写页打开、附件栏空着，又要你自选一次图。
     * 所以混装时宁可按「主角」（有图按图、有视频按视频）给具体类型，也别退到通配。
     * ⚠️ 注释里千万别出现斜杠星号连着的样子，Kotlin 会当成嵌套注释的开头报一堆假错。
     */
    private fun sharedType(uris: List<Uri>): String {
        val types = uris.map { runCatching { contentResolver.getType(it) }.getOrNull() }
        return when {
            types.isNotEmpty() && types.all { it?.startsWith("image/") == true } -> "image/*"
            types.isNotEmpty() && types.all { it?.startsWith("video/") == true } -> "video/*"
            types.isNotEmpty() && types.all { it?.startsWith("text/") == true } -> "text/plain"
            types.any { it?.startsWith("image/") == true } -> "image/*"
            types.any { it?.startsWith("video/") == true } -> "video/*"
            types.any { it?.startsWith("text/") == true } -> "text/plain"
            else -> "*/*"
        }
    }

    /**
     * 逐个把附件读权限显式授予候选邮箱包。
     *
     * Intent 上的 flag 只保证「这一跳」；邮箱往往先落到它的撰写页、隔一阵子才异步取附件，
     * 提前把权限落到具体包名上最稳，免得它回头读的时候说「没权限」→ 又让你自己选图。
     */
    private fun grantAll(intent: Intent, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            // 这里刻意不用 MATCH_DEFAULT_ONLY：只勾运行日志时，能接 ACTION_SEND + 文本类的
            // 往往只有微信 / QQ / 蓝牙这类「分享文本」的应用，授权给它们没用，
            // 真正要读附件的是邮箱。用 0 把候选面放宽，挨个包把权限交到，多授几个无害。
            val targets = packageManager.queryIntentActivities(intent, 0)
            for (ri in targets) {
                val pkg = ri.activityInfo?.packageName ?: continue
                for (u in uris) {
                    runCatching { grantUriPermission(pkg, u, flags) }
                }
            }
        } catch (_: Throwable) {
            // 授权失败也不打断发信：Intent 上的 flag 还有一层兜底
        }
    }

    /** 缓存目录里的附件转成 content://，交给 FeedbackProvider 供邮箱读取。 */
    private fun uriOf(f: File): Uri =
        Uri.parse("content://$packageName.feedback/entry/${f.name}")

    /**
     * 把已选附件整理成「扩展名跟真实文件头一致」的干净副本（fb_1.jpg / fb_2.mp4 …）。
     *
     * 邮箱 / 短信这类应用基本是照着扩展名和 MIME 判断自己认不认得这个附件的：
     * 相册给的名义后缀可能是 .heic、.jfif，甚至没有后缀（微信、QQ 转手过来的图常见），
     * 认不出的附件它就不挂进写信页，于是你只能在邮箱里自己再挑一次图 —— 毛病就出在这儿。
     */
    private fun canonicalAttachments(): List<File> {
        val kept = ArrayList<File>()
        for (i in attachments.indices) {
            val src = attachments[i]
            canonicalCopy(src, "fb_${i + 1}${detectExt(src)}")?.let { kept.add(it) }
        }
        return kept
    }

    /** 在附件目录里存一份指定名字的干净副本（同名直接覆盖，每次提交前的产物都长得一样）。 */
    private fun canonicalCopy(src: File, name: String): File? {
        return try {
            val dir = FeedbackProvider.attachDir(this)
            dir.mkdirs()
            val f = File(dir, name)
            src.inputStream().use { it.copyTo(FileOutputStream(f)) }
            if (f.exists() && f.length() > 0) f else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 按文件头猜真实类型，返回带点的扩展名；认不出来就沿用原来的后缀。 */
    private fun detectExt(f: File): String {
        val head = try {
            f.inputStream().use { it.readBytes().copyOf(16) }
        } catch (_: Throwable) {
            ByteArray(0)
        }
        val ext = when {
            startsWith(head, 0xFF, 0xD8, 0xFF) -> ".jpg"                       // JPEG
            startsWith(head, 0x89, 0x50, 0x4E, 0x47) -> ".png"                 // PNG
            startsWith(head, 0x47, 0x49, 0x46, 0x38) -> ".gif"                 // GIF
            asText(head, 0, "RIFF") && asText(head, 8, "WEBP") -> ".webp"      // WEBP
            asText(head, 4, "ftyp") && asText(head, 8, "heic") -> ".heic"      // HEIC
            asText(head, 4, "ftyp") && asText(head, 8, "heif") -> ".heif"      // HEIF
            asText(head, 4, "ftyp") && asText(head, 8, "heix") -> ".heic"      // HEIC
            asText(head, 4, "ftyp") && asText(head, 8, "qt  ") -> ".mov"       // QuickTime
            asText(head, 4, "ftyp") && asText(head, 8, "3gp") -> ".3gp"        // 3GPP
            asText(head, 4, "ftyp") -> ".mp4"                                  // MP4 通用
            startsWith(head, 0x1A, 0x45, 0xDF, 0xA3) && asText(head, 8, "webm") -> ".webm"
            startsWith(head, 0x1A, 0x45, 0xDF, 0xA3) -> ".mkv"                // Matroska
            else -> {
                val old = f.name.substringAfterLast('.', "").lowercase()
                if (old.isNotBlank() && old.all { it.isLetterOrDigit() }) ".$old" else ""
            }
        }
        return if (ext.isBlank()) oldExtOrEmpty(f) else ext
    }

    private fun oldExtOrEmpty(f: File): String {
        val old = f.name.substringAfterLast('.', "").lowercase()
        return if (old.isNotBlank() && old.all { it.isLetterOrDigit() }) ".$old" else ".bin"
    }

    private fun startsWith(head: ByteArray, vararg bytes: Int): Boolean {
        if (head.size < bytes.size) return false
        for (i in bytes.indices) if ((head[i].toInt() and 0xFF) != bytes[i]) return false
        return true
    }

    private fun asText(head: ByteArray, from: Int, s: String): Boolean {
        if (head.size < from + s.length) return false
        for (i in s.indices) if (head[from + i].toInt() and 0xFF != s[i].code) return false
        return true
    }

    /**
     * 真的把附件通过 content 通路读一遍，确认邮箱那头取得到。
     *
     * 只查文件大小是不够的：文件在、但 provider 那条路没通时，邮箱拿到的是个空附件，
     * 表现就是「没附件，你自己再挑一个」。
     */
    private fun readableViaUri(u: Uri): Boolean {
        return try {
            contentResolver.openInputStream(u)?.use { it.readBytes().size > 0 } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /** 兜底：把正文放进剪贴板（没装邮箱时用得上）。 */
    private fun copyToClipboard() {
        val body = buildBody()
        return try {
            val mgr = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            mgr.setPrimaryClip(ClipData.newPlainText("倒计时工具 意见反馈", body))
            Toast.makeText(this, "内容已复制，粘到微信 / QQ 发给我就行～", Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "复制没成功，重新点一下试试", Toast.LENGTH_SHORT).show()
        }
    }
}
