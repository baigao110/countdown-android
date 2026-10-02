package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
 * 提交走系统邮箱（ACTION_SENDTO）：工程刻意零第三方依赖，没法在 App 里自带发信，
 * 所以「提交」= 打开邮箱应用、收件人已经填成 baigao110@qq.com、标题正文附件都备好，
 * 你点一下发送就到了。手机没装邮箱时，下面还有一个「复制内容备用」，粘到微信 / QQ 发同样收得到。
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

    /** 已经挑好的图片 / 视频（放到缓存目录里，等提交时作为附件发出去）。 */
    private val attachments = ArrayList<File>()

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

    /** 「附带运行日志」那份 txt：真正能帮我定位的东西。 */
    private fun buildLogText(): String {
        val sb = StringBuilder()
        sb.append("倒计时工具 · 运行信息\n==============\n")
        sb.append("提交时间：${nowText()}\n")
        sb.append("应用版本：v${UpdateManager.CURRENT_VERSION_NAME}（versionCode ${appVersionCode()}）\n")
        sb.append("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
        sb.append("设备：${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE} / ${Build.DISPLAY}\n")
        val dm = resources.displayMetrics
        sb.append("屏幕：${dm.widthPixels}x${dm.heightPixels}（${dm.densityDpi}dpi）\n")
        sb.append("语言：${Locale.getDefault()}  时区：${zoneText()}\n\n")
        sb.append("长按开关状态：\n")
        sb.append("  UI 界面常亮：${yesNo(ScreenKeepOn.isUiOn(this))}\n")
        sb.append("  悬浮框常亮：${yesNo(ScreenKeepOn.isFloatOn(this))}\n")
        sb.append("  锁屏通知显示：${yesNo(LockScreenClock.isOn(this))}\n")
        sb.append("  锁屏通知常亮：${yesNo(LockKeepOn.isOn(this))}\n\n")
        val list = try {
            CountdownStore.load(this)
        } catch (_: Throwable) {
            emptyList()
        }
        sb.append("当前倒计时（${list.size} 条）：\n")
        list.forEachIndexed { i, c ->
            val mode = CountdownFormatter.modeName(c.displayMode, c.builtIn)
            sb.append("  ${i + 1}. ${c.title} ｜ 模式：$mode ｜ 剩余：${c.remainingText()} ｜ 已展开：${yesNo(c.isVisible)}\n")
        }
        sb.append("\n运行日志（logcat 最近片段）：\n")
        sb.append(tailLogcat())
        return sb.toString()
    }

    private fun writeLogFile(): File? {
        return try {
            val dir = FeedbackProvider.attachDir(this)
            dir.mkdirs()
            val f = File(dir, "countdown-feedback-log.txt")
            FileOutputStream(f).use { out ->
                out.write(buildLogText().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
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

        val body = buildBody()
        val uris = ArrayList<Uri>()
        if (logSwitch.isChecked) {
            val f = writeLogFile()
            if (f != null) uris.add(uriOf(f))
        }
        attachments.forEach { uris.add(uriOf(it)) }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL"))
        intent.putExtra(Intent.EXTRA_SUBJECT, "倒计时工具 意见反馈")
        intent.putExtra(Intent.EXTRA_TEXT, body)
        if (uris.isNotEmpty()) {
            intent.putExtra(Intent.EXTRA_STREAM, uris)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
            Toast.makeText(
                this,
                "已交给邮箱应用：收件人已经填好啦，你点一下「发送」就到 $FEEDBACK_EMAIL 了",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
            // 没装邮箱：退到「分享」列表（同样带上附件），再不行就让用户复制
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "倒计时工具 意见反馈")
                putExtra(Intent.EXTRA_TEXT, body)
                if (uris.isNotEmpty()) {
                    putExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                startActivity(Intent.createChooser(fallback, "用哪个发给我？"))
            } catch (_: Throwable) {
                Toast.makeText(
                    this,
                    "这台手机上没找到邮箱应用：先点「复制内容备用」，粘到微信 / QQ 发给我也一样收得到～",
                    Toast.LENGTH_LONG
                ).show()
                copyToClipboard()
            }
        }
    }

    /** 缓存目录里的附件转成 content://，交给 FeedbackProvider 供邮箱读取。 */
    private fun uriOf(f: File): Uri =
        Uri.parse("content://$packageName.feedback/entry/${f.name}")

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
