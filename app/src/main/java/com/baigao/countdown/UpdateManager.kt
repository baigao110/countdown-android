package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内更新管理器（纯框架实现，零第三方依赖）。
 *
 * 职责：
 * 1. 读取 GitHub 最新 Release（失败回退仓库根目录的 update.json）判断是否有新版本；
 * 2. 有新版本时**强制弹窗**展示更新日志（Release 的 body），用户点「立即更新」即开始下载；
 * 3. 下载完成后通过 ApkProvider 把 APK 交给系统安装器完成安装；
 * 4. Android 8.0+ 未授权「允许安装未知应用」时跳设置页，授权返回后自动继续安装。
 *
 * 所有弹窗（强制更新 / 下载进度 / 更新日志）统一走 showStyledDialog，
 * 视觉风格与「关于」页一致：深色圆角底 + 青色标题 + 青色胶囊按钮。
 *
 * AboutActivity 与 MainActivity 共用本管理器，保证两处行为一致。
 */
object UpdateManager {

    /** 当前版本号，发版时与 app/build.gradle 的 versionName 保持一致。 */
    const val CURRENT_VERSION_NAME = "1.0.0.26"
    private val CURRENT_VERSION_NUM = versionToNumber(CURRENT_VERSION_NAME)

    private const val OWNER = "baigao110"
    private const val REPO = "countdown-android"
    private const val LATEST_RELEASE = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val UPDATE_JSON =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/update.json"

    private val handler = Handler(Looper.getMainLooper())

    /** 更新提示延后拉起的毫秒数：避开「一开 App 就弹窗」这个开屏广告判定特征。 */
    private const val UPDATE_PROMPT_DELAY = 800L
    private const val EXTRA_UPD_NUM = "upd_num"
    private const val EXTRA_UPD_NAME = "upd_name"
    private const val EXTRA_UPD_APK = "upd_apk"
    private const val EXTRA_UPD_NOTE = "upd_note"
    private const val EXTRA_UPD_HTML = "upd_html"
    private const val EXTRA_UPD_DATE = "upd_date"

    /** 已下载、等待用户授予安装权限的 APK。 */
    private var pendingApk: File? = null

    class UpdateInfo(
        val num: Int,
        val name: String,
        val apkUrl: String,
        val note: String,
        val htmlUrl: String,
        /** 发布日期 yyyy-MM-dd（已转为本地时区）；取不到时为空串。 */
        val date: String = ""
    )

    /**
     * "1.2.3" / "v1.2.3" / "1.2.3.4" → 便于数值比较的整数。
     * 支持四段版本号（末段为修订号）：1.0.3.1 必须大于 1.0.3、小于 1.0.4。
     */
    fun versionToNumber(version: String): Int {
        val v = version.trim().removePrefix("v").removePrefix("V")
        val parts = v.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val build = parts.getOrNull(3)?.toIntOrNull() ?: 0
        return major * 1000000 + minor * 10000 + patch * 100 + build
    }

    /** 该版本是否比当前 App 新（后台定期检查用，避免外部拿到私有的版本数值）。 */
    fun hasNewVersion(info: UpdateInfo?): Boolean =
        info != null && info.num > CURRENT_VERSION_NUM

    /**
     * 异步检查更新。
     * @param forceDialog 为真时，发现新版本直接弹出更新日志对话框（用于「强制提示」场景）。
     * @param notify 为真时，发现新版本同时在系统通知栏发一条更新提醒（同一版本每天一次）。
     */
    fun check(
        activity: Activity,
        forceDialog: Boolean,
        notify: Boolean = true,
        onResult: ((UpdateInfo?) -> Unit)? = null
    ) {
        Thread {
            val info = fetch()
            handler.post {
                val newest = if (info != null && info.num > CURRENT_VERSION_NUM) info else null
                onResult?.invoke(newest)
                if (newest != null && notify) UpdateNotifier.notifyUpdate(activity, newest)
                if (forceDialog && newest != null) showUpdateDialog(activity, newest)
            }
        }.start()
    }

    /** 同步拉取远程版本信息（主线程勿调）：GitHub Release 优先，失败回退 update.json。 */
    fun fetch(): UpdateInfo? {
        return try {
            fetchFromGitHubRelease()
        } catch (e: Throwable) {
            null
        } ?: try {
            fetchFromUpdateJson()
        } catch (e: Throwable) {
            null
        }
    }

    private fun fetchFromGitHubRelease(): UpdateInfo? {
        val conn = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "$OWNER-$REPO")
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val tag = o.optString("tag_name", "")
            val name = tag.trim().removePrefix("v").removePrefix("V")
            if (name.isEmpty()) return null
            val num = versionToNumber(name)
            if (num <= 0) return null

            var apkUrl = ""
            var sizeText = ""
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url", "")
                        val size = a.optLong("size", 0L)
                        if (size > 0) sizeText = "（${size / 1024} KB）"
                        break
                    }
                }
            }
            if (apkUrl.isEmpty()) apkUrl = o.optString("html_url", "")

            var note = o.optString("body", "").trim()
            if (note.length > 800) note = note.substring(0, 800) + "..."
            if (sizeText.isNotEmpty()) note = "$sizeText\n$note"
            // 发布日期：GitHub 给的是 UTC，转成本地年月日（否则跨时区会差一天）
            val date = formatReleaseDate(o.optString("published_at", "")).ifEmpty {
                changelogDateOf(name)
            }
            return UpdateInfo(num, name, apkUrl, note, o.optString("html_url", ""), date)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFromUpdateJson(): UpdateInfo? {
        val conn = URL(UPDATE_JSON).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val name = json.optString("versionName", "")
            val num = if (name.isNotBlank()) versionToNumber(name) else json.optInt("versionCode", 0)
            if (num <= 0) return null
            val date = formatReleaseDate(json.optString("date", "")).ifEmpty {
                changelogDateOf(name)
            }
            return UpdateInfo(
                num,
                name.ifBlank { num.toString() },
                json.optString("apkUrl", ""),
                json.optString("note", ""),
                json.optString("htmlUrl", ""),
                date
            )
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 日期处理 ----------------

    private val UTC_INPUT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val DATE_OUT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** GitHub 的 published_at 是 UTC，转成本地时区的年月日；解析失败时退回前 10 位。 */
    private fun formatReleaseDate(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            DATE_OUT.format(Date(UTC_INPUT.parse(raw)!!.time))
        } catch (e: Throwable) {
            if (raw.length >= 10) raw.substring(0, 10) else ""
        }
    }

    /** 取某个版本在内置更新日志里的发布日期（远程拿不到日期时用本地这份兜底）。 */
    fun changelogDateOf(version: String): String {
        val v = "v" + version.trim().removePrefix("v").removePrefix("V")
        return CHANGELOG.find { it.version.equals(v, ignoreCase = true) }?.date ?: ""
    }

    // ---------------- 风格化对话框（与「关于」页一致） ----------------

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun bodyText(
        ctx: Context,
        text: String,
        size: Float = 14f,
        color: Int = 0xFFE6E6F0.toInt()
    ): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = size
        tv.setTextColor(color)
        tv.setLineSpacing(4f, 1.15f)
        return tv
    }

    /** 「标签：值」一行（标签灰、值白）。 */
    private fun kvRow(ctx: Context, label: String, value: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(bodyText(ctx, "$label：", 14f, 0xFF9AA0B5.toInt()))
        row.addView(bodyText(ctx, value, 14f, 0xFFE6E6F0.toInt()))
        return row
    }

    /** 青色小标题（如「更新日志」）。 */
    private fun sectionTitle(ctx: Context, text: String): TextView {
        val tv = bodyText(ctx, text, 15f, 0xFF00FFFF.toInt())
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 10)
        tv.layoutParams = lp
        return tv
    }

    /**
     * 与「关于」页同风格的对话框：深色圆角底 + 青色标题 + 青色胶囊按钮。
     *
     * @param content 往内容容器里填视图（容器本身可滚动，内容过高时自动限高）。
     * @return 已显示的对话框，便于调用方关闭或更新内容。
     */
    /** 一张玻璃卡片的句柄：拿到就能改标题、换内容、动按钮。 */
    class StyledCard(
        val root: View,
        val title: TextView,
        val host: LinearLayout,
        val positive: Button,
        val negative: Button
    )

    /**
     * 生成「深色圆角底 + 青色标题 + 青色胶囊按钮」的卡片视图本身。
     *
     * 对话框（showStyledDialog）与更新页面（UpdateActivity）共用这一段，外观完全一致。
     * 后者之所以不用对话框：一批「跳过开屏广告 / 弹窗拦截 / 广告过滤」工具靠无障碍服务
     * 盯 Dialog 窗口，一开 App 就弹出的那种尤其容易被当成弹窗广告替用户点掉；
     * 换成普通 Activity 页面之后，它们就无从下手了。
     *
     * @param content 往内容容器里填视图（容器本身可滚动，内容过高时自动限高）。
     */
    fun buildStyledCard(
        activity: Activity,
        title: String,
        positiveText: String,
        negativeText: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        content: (LinearLayout) -> Unit
    ): StyledCard {
        val root = activity.layoutInflater.inflate(R.layout.dialog_styled, null)
        val titleTv = root.findViewById<TextView>(R.id.dialogTitle)
        val host = root.findViewById<LinearLayout>(R.id.dialogContent)
        val scroll = root.findViewById<ScrollView>(R.id.dialogScroll)
        val posBtn = root.findViewById<Button>(R.id.dialogPositive)
        val negBtn = root.findViewById<Button>(R.id.dialogNegative)

        titleTv.text = title
        content(host)

        val dm = activity.resources.displayMetrics
        val winW = (dm.widthPixels * 0.92).toInt()
        // 内容区最多占屏幕高度的 45%：加上标题、按钮和内外边距，整框稳稳落在屏幕内。
        // 关键是必须在显示「之前」离屏量一次内容高度，超了就直接把滚动区定高 ——
        // 这样窗口生成时拿到的就是受限后的尺寸。等到显示之后再去补救是没用的：
        // 高度在那一刻就按完整内容定死了，居中显示会把上下都切掉。
        val padH = (20 * dm.density).toInt() * 2   // dialog_styled 左右各 20dp 内边距
        host.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                winW - padH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val maxScrollH = (dm.heightPixels * 0.45).toInt()
        if (host.measuredHeight > maxScrollH) {
            scroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxScrollH
            )
        }

        posBtn.text = positiveText
        if (negativeText.isNullOrEmpty()) {
            negBtn.visibility = View.GONE
        } else {
            negBtn.text = negativeText
        }
        negBtn.setOnClickListener { onNegative?.invoke() }
        posBtn.setOnClickListener { onPositive?.invoke() }

        return StyledCard(root, titleTv, host, posBtn, negBtn)
    }

    /**
     * 与「关于」页同风格的对话框：深色圆角底 + 青色标题 + 青色胶囊按钮。
     *
     * @param content 往内容容器里填视图（容器本身可滚动，内容过高时自动限高）。
     * @return 已显示的对话框，便于调用方关闭或更新内容。
     */
    fun showStyledDialog(
        activity: Activity,
        title: String,
        positiveText: String,
        negativeText: String? = null,
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null,
        content: (LinearLayout) -> Unit
    ): AlertDialog {
        var ref: AlertDialog? = null
        val card = buildStyledCard(
            activity = activity,
            title = title,
            positiveText = positiveText,
            negativeText = negativeText,
            onPositive = { ref?.dismiss(); onPositive?.invoke() },
            onNegative = { ref?.dismiss() },
            content = content
        )
        val root = card.root
        val dm = activity.resources.displayMetrics
        val winW = (dm.widthPixels * 0.92).toInt()

        val dialog = AlertDialog.Builder(activity).setView(root).setCancelable(cancelable).create()
        ref = dialog
        // 去掉系统默认的白色面板底，露出布局自带的深色圆角背景
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 对话框撑到屏幕宽度的 92%：系统默认宽度偏窄，长一点的说明文字会被挤成竖条。
        dialog.window?.setLayout(winW, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        // 兜底：万一仍然超高（比如系统字体被放得很大），再收一次窗口高度，
        // 并让滚动区按权重吃掉剩余空间，标题与「确定 / 取消」始终露在外面。
        root.post {
            val maxH = (dm.heightPixels * 0.8).toInt()
            if (root.height > maxH) {
                dialog.window?.setLayout(winW, maxH)
                val scroll = root.findViewById<ScrollView>(R.id.dialogScroll)
                scroll.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                root.requestLayout()
            }
        }
        return dialog
    }

    // ---------------- 强制更新弹窗 ----------------

    /**
     * 弹出更新提示，用户点「立即更新」开始下载安装。
     *
     * ---------- v1.0.0.25：为什么不再用对话框 ----------
     * 原先是一个 AlertDialog，被一批「跳过开屏广告 / 弹窗拦截 / 广告过滤」工具当成弹窗广告
     * 自动关掉了 —— 它们靠无障碍服务盯 Dialog 窗口，而「一打开 App 就弹窗」正是开屏广告的
     * 典型特征，于是更新提示根本没机会被看见。
     * 这里改成**启动一个普通的 Activity 页面**（透明底 + 同一张玻璃卡片，外观不变），
     * 并延后 800 毫秒再拉起，既躲开 Dialog 窗口这一层，也不再命中开屏广告的判定。
     */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        handler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            activity.startActivity(updateIntent(activity, info))
        }, UPDATE_PROMPT_DELAY)
    }

    /** 更新提示页的 Intent（UpdateActivity 用）。 */
    fun updateIntent(ctx: Context, info: UpdateInfo): Intent =
        Intent(ctx, UpdateActivity::class.java)
            .putExtra(EXTRA_UPD_NUM, info.num)
            .putExtra(EXTRA_UPD_NAME, info.name)
            .putExtra(EXTRA_UPD_APK, info.apkUrl)
            .putExtra(EXTRA_UPD_NOTE, info.note)
            .putExtra(EXTRA_UPD_HTML, info.htmlUrl)
            .putExtra(EXTRA_UPD_DATE, info.date)

    /** 从 Intent 还原版本信息（UpdateActivity 用）；缺字段返回 null。 */
    fun infoFromIntent(intent: Intent): UpdateInfo? {
        val name = intent.getStringExtra(EXTRA_UPD_NAME) ?: return null
        return UpdateInfo(
            num = intent.getIntExtra(EXTRA_UPD_NUM, 0),
            name = name,
            apkUrl = intent.getStringExtra(EXTRA_UPD_APK) ?: "",
            note = intent.getStringExtra(EXTRA_UPD_NOTE) ?: "",
            htmlUrl = intent.getStringExtra(EXTRA_UPD_HTML) ?: "",
            date = intent.getStringExtra(EXTRA_UPD_DATE) ?: ""
        )
    }

    /** 更新提示的内容：发布日期 / 当前版本 / 更新日志（对话框与更新页共用）。 */
    fun fillUpdateContent(ctx: Context, host: LinearLayout, info: UpdateInfo) {
        host.addView(kvRow(ctx, "发布日期", info.date.ifEmpty { "未知" }))
        host.addView(kvRow(ctx, "当前版本", "v$CURRENT_VERSION_NAME"))
        host.addView(sectionTitle(ctx, "更新日志"))
        host.addView(bodyText(ctx, if (info.note.isBlank()) "暂无更新说明" else info.note))
    }

    /** 下载 APK 并在下载完成后拉起安装界面，下载期间显示进度对话框。 */
    /**
     * 下载 APK 并拉起安装。
     *
     * @param onProgress 传了就走「页面内进度」（不另弹对话框，避免再被当成广告弹窗关掉）；
     *                   不传则沿用原来的进度对话框。
     * @param canceled   页面内进度模式下的取消开关。
     * @param onDone     下载收尾（成功拉起安装 / 失败）后的回调。
     */
    fun downloadAndInstall(
        activity: Activity,
        info: UpdateInfo,
        onProgress: ((String) -> Unit)? = null,
        canceled: AtomicBoolean = AtomicBoolean(false),
        onDone: (() -> Unit)? = null
    ) {
        var progressTv: TextView? = null
        var dialog: AlertDialog? = null
        if (onProgress == null) {
            dialog = showStyledDialog(
                activity = activity,
                title = "正在下载 v${info.name}",
                positiveText = "取消",
                negativeText = null,
                cancelable = false,
                onPositive = { canceled.set(true) }
            ) { host ->
                val tv = bodyText(activity, "准备中...")
                progressTv = tv
                host.addView(tv)
            }
        }

        Thread {
            try {
                val dir = File(activity.filesDir, "update")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "app.apk")
                if (file.exists()) file.delete()

                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "$OWNER-$REPO")
                conn.connect()
                if (conn.responseCode >= 400) throw java.io.IOException("服务器返回 ${conn.responseCode}")
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        var sum = 0L
                        var lastPost = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            if (canceled.get()) {
                                handler.post {
                                    dialog?.let { if (it.isShowing) it.dismiss() }
                                    onDone?.invoke()
                                }
                                return@Thread
                            }
                            out.write(buf, 0, read)
                            sum += read
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 200) {
                                lastPost = now
                                val mb = "%.1f".format(sum / 1024.0 / 1024.0)
                                val percent = if (total > 0) sum * 100 / total else -1
                                handler.post {
                                    val txt =
                                        if (percent >= 0) "已下载 $percent%（$mb MB）"
                                        else "已下载 $mb MB"
                                    if (onProgress != null) onProgress(txt)
                                    else progressTv?.text = txt
                                }
                            }
                        }
                    }
                }
                handler.post {
                    dialog?.let { if (it.isShowing) it.dismiss() }
                    installApk(activity, file)
                    onDone?.invoke()
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                handler.post {
                    dialog?.let { if (it.isShowing) it.dismiss() }
                    Toast.makeText(activity, "下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    openInBrowser(activity, info)
                    onDone?.invoke()
                }
            }
        }.start()
    }

    private fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    private fun installApk(activity: Activity, file: File) {
        if (!canInstall(activity)) {
            pendingApk = file
            Toast.makeText(activity, "请先允许安装未知应用", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (e: Throwable) {
                openInBrowser(activity, null)
            }
            return
        }
        doInstall(activity, file)
    }

    private fun doInstall(activity: Activity, file: File) {
        try {
            val uri = Uri.parse("content://${activity.packageName}.apkprovider/update/app.apk")
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
            Toast.makeText(activity, "请在安装界面点击「安装」", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            openInBrowser(activity, null)
        }
    }

    private fun openInBrowser(activity: Activity, info: UpdateInfo?) {
        val url = info?.apkUrl
        if (url.isNullOrEmpty()) return
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        } catch (e: Throwable) {
            Toast.makeText(activity, "无法打开下载链接", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 更新日志（离线可读，按发布日期分组） ----------------

    /** 单条更新日志：版本号 + 发布日期（yyyy-MM-dd）+ 内容。 */
    class ChangelogItem(val version: String, val date: String, val content: String)

    /**
     * 各版本更新日志。新增版本时在**头部**追加一条，并填上真实发布日期：
     * 更新日志框会按日期合并分组，同一天发布多条时点日期后的下箭头展开明细，
     * 只有一条的那天直接铺开显示、不显示箭头。
     */
    private val CHANGELOG = listOf(
        ChangelogItem("v1.0.0.26", "2026-09-19",
            "修复「退出 / 关闭软件后就收不到通知」（吃药提醒、版本更新通知都在此列）：\n" +
            "  新增「通知体检」（关于页）：逐项检查通知总开关、通知权限、吃药提醒渠道、\n" +
            "    版本更新渠道、精确闹钟、电池优化、后台限制 —— 这些都是静默失败，\n" +
            "    以前根本无从判断断在哪一环；现在每项不合格都带一个直达设置的按钮\n" +
            "  通知渠道换新并提到最高级：免打扰时也会响、锁屏可见，不再被收进「无声通知」；\n" +
            "    渠道重要性一旦定下来就不能就地修改，被关掉时会自动删掉重建，不再是无解死局\n" +
            "  吃药提醒新增全屏提醒：息屏或锁屏时直接把吃药页弹到眼前\n" +
            "  开机、覆盖安装（升级）、解锁、改时间、改时区、跨天——更多时机自动重挂任务与闹钟\n" +
            "  进入应用时若发现通知被挡住，会自动提示一次（一天最多一次，累计最多三次）"),
        ChangelogItem("v1.0.0.25", "2026-09-19",
            "更新提示不再用对话框，改成独立页面（外观完全不变）——\n" +
            "  原先一打开 App 就弹的对话框，被「跳过开屏广告 / 弹窗拦截 / 广告过滤」类工具\n" +
            "    当成弹窗广告自动点掉，更新提示根本没机会被看见\n" +
            "  现在改成一个透明底的普通页面 + 同一张玻璃卡片，并延后 800 毫秒再拉起，\n" +
            "    既躲开对话框窗口这一层，也不再命中「一开 App 就弹窗」的开屏广告特征\n" +
            "  下载进度同样直接显示在这张卡片上，不再另弹一个对话框"),
        ChangelogItem("v1.0.0.24", "2026-09-18",
            "继续加固「改了下次提醒时间之后，退出 / 关闭软件就收不到提醒」：\n" +
            "  到点后 2 分钟、30 分钟各补响一次 —— 主路被系统推迟或清掉也能兜住\n" +
            "  精确闹钟与系统闹钟在同一时刻再挂一路，一共四条闹钟路线\n" +
            "  后台巡检再加一个入口（检查更新的任务里也跑一趟），并记录「上次真正发出提醒」的时刻\n" +
            "  改完时间会弹出引导并可一键去「允许后台运行」；回到前台也会立刻补发漏掉的提醒"),
        ChangelogItem("v1.0.0.23", "2026-09-18",
            "修复「改了下次提醒时间之后，退出 / 关闭软件就收不到提醒」：\n" +
            "  根因是手动指定的那一次被去重逻辑拦下了 —— 今天已经提醒过、或已记过「已吃药」就不发，\n" +
            "    而改时间本来就是为了再收一次，于是表现出来就是改完反倒不提醒了\n" +
            "  现在手动改的时间**一定会提醒一次**，响过之后才回到每天固定时刻\n" +
            "  顺带修掉推算「下次时间」时提前作废手动设定的副作用：\n" +
            "    后台巡检与打开 App 时的补发因此也能覆盖手动改过的那一次"),
        ChangelogItem("v1.0.0.22", "2026-09-18",
            "吃药提醒的「下次提醒时间」可以自己改了：\n" +
            "  「关于」页吃药卡片新增「下次提醒」按钮（按钮上直接显示当前的下次提醒时间）\n" +
            "  点开可指定具体的年 / 月 / 日 + 时刻 —— 比如今天错过了 09:00，就把这一次改到今晚 20:30\n" +
            "  改的只是即将到来的那一次：这次响过之后自动恢复为每天固定时刻\n" +
            "  同一个对话框里有「恢复常规」按钮，一键回到每天固定时刻；\n" +
            "    「后台自检」与状态行会标明当前的下次提醒是不是手动改过的"),
        ChangelogItem("v1.0.0.21", "2026-09-18",
            "修复「退出 / 关闭软件后收不到吃药提醒」：\n" +
            "  改为三重保障 —— 系统闹钟（能穿透省电策略）+ 每日重复闹钟 + 后台巡检（15 分钟一趟、重启自动恢复）\n" +
            "  手机把应用「强制停止」（比如从最近任务划掉卡片）会清掉闹钟，巡检发现后会自动补挂\n" +
            "  真错过了也会补发：今天该提醒的时刻已过却没响过，打开应用或巡检时立刻补一条（标注「补发提醒」）\n" +
            "  同一天只会提醒一次，三重保障不会重复打扰\n" +
            "「关于」页吃药卡片新增「测试提醒」（立刻验证通知通不通）与「后台自检」\n" +
            "  （列出下次提醒时间、通知权限、精确闹钟、电池优化状态，并给出处理办法）"),
        ChangelogItem("v1.0.0.20", "2026-09-18",
            "新增「每日吃药提醒」：\n" +
            "  默认每天 09:00 弹通知，通知上直接点「已吃药」即可记录当天\n" +
            "    提醒时间可随时改 —— 「关于」页点「提醒时间」选一个时刻即可\n" +
            "  「关于」页可一键开启 / 关闭该功能；关掉后不再提醒，已记录的日期仍然保留\n" +
            "  新增「吃药日历」：按月查看哪天吃了、哪天没吃（青色=已吃，淡红=漏了），\n" +
            "    可切换月份，点过去或今天的日期还能补记 / 撤销\n" +
            "内置倒计时新增「每周倒计时」：距离本周结束，与其它内置项一样可删除、可修改、可恢复"),
        ChangelogItem("v1.0.0.19", "2026-09-17",
            "「关于」页新增「说明」按钮（在「检查更新」与「更新日志」中间，同款青色玻璃按钮）：\n" +
            "  点开即看到完整使用说明 —— 添加 / 编辑倒计时、卡片按钮行、左滑操作、长按排序、\n" +
            "    悬浮窗、内置倒计时的删除与恢复、更新与通知自检，全部一次讲清\n" +
            "  同时重写了项目 README.md"),
        ChangelogItem("v1.0.0.18", "2026-09-17",
            "再修「恢复内置」对话框：\n" +
            "  上半部分仍看不全 —— 改为在弹出「之前」先量一次内容高度，超过屏幕 45% 就把内容区定高，\n" +
            "    窗口生成时就是受限尺寸；之前只在弹出后补救，而窗口高度那时早已定死\n" +
            "  文字看不清 —— 说明、选项标题 / 备注、「全选 / 全不选」全部提亮并加了描边阴影，\n" +
            "    在深色玻璃面板上不再糊成一片"),
        ChangelogItem("v1.0.0.17", "2026-09-17",
            "再修「恢复内置」对话框：选项多了之后上半部分文字看不到 —— 窗口高度是按完整内容\n" +
            "  在弹出那一刻定下来的，只压缩内容区不重设窗口，窗口依旧超高、居中后上下被裁\n" +
            "  现在超过屏幕 70% 时连窗口高度一起收，滚动区按权重吃掉剩余空间，\n" +
            "  标题、说明与「确定 / 取消」始终露在外面，中间内容可上下滚动"),
        ChangelogItem("v1.0.0.16", "2026-09-17",
            "修复「恢复内置」对话框文字看不全：对话框撑到屏幕宽度的 92%，长句子不再被挤截断；\n" +
            "  选项多时内容区可滚动（原先限高代码因在布局完成前测量而从未生效，\n" +
            "  内容一多就顶出屏幕、下半截看不见）\n" +
            "  选项文字改为占满整行自动换行，行距收紧，备注也更清楚"),
        ChangelogItem("v1.0.0.15", "2026-09-17",
            "修复「恢复内置」对话框里看不到可勾选项：原生对话框的说明文字和选项列表互斥，\n" +
            "  设了说明文字，列表就不会显示，于是只剩一段话、没法选恢复哪几个\n" +
            "  现在把缺少的内置倒计时逐条列成可勾选列表，默认全选，可只勾其中几个，\n" +
            "  另配「全选 / 全不选」，确定后只恢复勾选的那些"),
        ChangelogItem("v1.0.0.14", "2026-09-16",
            "再修「有版本更新却收不到系统通知」：\n" +
            "  新增 JobScheduler 后台检查（系统统一调度，**有网络才执行**、每 15 分钟一趟、重启自动恢复）；\n" +
            "  之前只靠闹钟唤醒，而息屏省电模式会切断网络，唤醒了也拉不到版本信息\n" +
            "  闹钟检查缩短为 2 小时一次，拉取失败再 30 分钟后重试\n" +
            "「关于」页新增「开启通知 / 测试通知 / 允许后台运行」三个入口，并显示后台检查状态，\n" +
            "  点一下就能确认通知到底收不收得到"),
        ChangelogItem("v1.0.0.13", "2026-09-16",
            "「恢复内置」现在会连同设置一起还原：删除内置倒计时时会自动保存一份设置快照，\n" +
            "  再点「恢复内置」把主题颜色、显示模式、跳秒动画、提示音、悬浮窗显隐 / 透明度 / 位置、备注等\n" +
            "  原样恢复，不用重新配置一遍（目标时间仍由系统按当日 / 当月 / 固定日期重新计算）\n" +
            "恢复对话框补上说明文字，恢复后会提示实际还原了几个"),
        ChangelogItem("v1.0.0.12", "2026-09-16",
            "修复「有版本更新却收不到通知」：之前只有打开 App 才会去查版本，\n" +
            "  现在新增后台定时检查（AlarmManager，每 6 小时一次），不打开 App 也能收到更新通知\n" +
            "  开机和应用更新后会自动把定时任务重新挂上\n" +
            "更新通知渠道提升为高优先级：有提示音 + 横幅，不再静默躺在通知抽屉里"),
        ChangelogItem("v1.0.0.11", "2026-09-16",
            "「恢复内置」改为智能显示：四个内置倒计时都在列表里时，加号菜单不再显示该按钮\n" +
            "只要有一个内置倒计时不在列表（被删除或已转为普通倒计时），菜单里就会出现「恢复内置」\n" +
            "点了「恢复内置」的确定后，缺的那几个会直接补回列表"),
        ChangelogItem("v1.0.0.10", "2026-09-16",
            "「恢复内置」对话框改为带勾选框的多选列表，并补上「确定」按钮（原先只有「取消」）\n" +
            "默认全部勾选：想一次恢复全部就直接点确定，只想恢复其中几个就取消勾选再确定"),
        ChangelogItem("v1.0.0.9", "2026-09-16",
            "内置倒计时改为可删除：当天内置项也能长按（左滑）删除，删掉后不再自动补回来\n" +
            "内置倒计时改为可修改：标题、颜色、模式、动画、备注都能改，日期时间也能改\n" +
            "  改动了日期或时间的内置项会自动转为普通倒计时，不再被系统每天 / 每月覆盖\n" +
            "加号菜单新增「恢复内置」，删掉的内置倒计时可以随时一键恢复"),
        ChangelogItem("v1.0.0.8", "2026-09-15",
            "新增两个内置倒计时，新版本安装后自带四个：当日倒计时、当月倒计时、\n" +
            "  华都云境悦府倒计时（2026-10-31 00:00:00）、GTA6倒计时（2026-11-19 08:00:00）\n" +
            "内置倒计时目标时间由系统维护（编辑页不可改），不可删除，可在列表中「隐藏」\n" +
            "四个内置项与自建倒计时一样对齐整秒，走秒完全同步"),
        ChangelogItem("v1.0.0.7", "2026-09-15",
            "修复主界面「编辑 / 提示音 / 删除」按钮被倒计时文字遮挡的问题：\n" +
            "  列表卡片改为不透底的玻璃卡，未左滑时操作按钮整层不绘制，不再透上来压住倒计时\n" +
            "检测到新版本时新增系统通知提醒：点通知进 App 查看更新日志，\n" +
            "  通知上的「立即更新」可直接下载安装（同一版本每天只提示一次，进 App 后自动清除）\n" +
            "Android 13 及以上首次启动会申请通知权限，保证更新提醒能送达"),
        ChangelogItem("v1.0.0.6", "2026-09-15",
            "整体风格升级为「液态玻璃（Liquid Glass）」：卡片、按钮、对话框、悬浮窗统一改为半透明玻璃质感\n" +
            "  玻璃面带镜面高光与亮边，能透出背后内容，层次更轻盈通透\n" +
            "页面背景新增模糊光斑层：Android 12+ 用系统 RenderEffect 做真模糊，低版本退回柔和渐变\n" +
            "底色由纯深色改为深蓝紫渐变，按钮由实心改为玻璃渐变（青色主操作保留高对比）\n" +
            "列表卡片改为大圆角玻璃卡并带投影，条目间距微调"),
        ChangelogItem("v1.0.0.5", "2026-09-14",
            "更新日志默认收起所有日期：只有点击展开的那一天才显示日志\n" +
            "未展开的日期连「日期行」本身都不显示，界面上只剩被展开那一天的日期与日志\n" +
            "再点一次已展开的日期即可收起，收起后所有日期行重新显示出来"),
        ChangelogItem("v1.0.0.4", "2026-09-14",
            "添加 / 编辑倒计时页改为与「关于」页一致的风格：深色底 + 青色返回栏与标题 + 胶囊按钮\n" +
            "各输入项改为深色圆角卡片，字段标题统一为青色小标题\n" +
            "更新日志按日期分组后改为「一次只展开一个日期」：点开新日期会自动收起上一个"),
        ChangelogItem("v1.0.0.3", "2026-09-14",
            "更新弹窗、下载进度框、更新日志框统一为与「关于」页一致的深色风格\n" +
            "强制更新弹窗新增「发布日期（年月日）」\n" +
            "更新日志按发布日期分组：同一天发布多条时，点日期后的下箭头展开当天各版本明细\n" +
            "同一天只发布一条时，日期后不显示下箭头，直接列出日志内容"),
        ChangelogItem("v1.0.0.2", "2026-09-14",
            "1. 每个倒计时的按钮行新增「提示音名称」，一眼看出这条倒计时响什么\n" +
            "2. 没有设置提示音的条目显示「未设置提示音」\n" +
            "3. 名称按钮可直接点击更换提示音\n" +
            "4. 修复挑选提示音时取消会停在空白页的问题"),
        ChangelogItem("v1.0.0.1", "2026-09-14",
            "修复长按拖动排序时条目「分成两层」的问题：\n" +
            "  现在拖动的是完整的一层卡片（真实视图而非截图），不再额外留一份虚影在原位\n" +
            "  原位条目占位隐藏，列表不会跳动，浮层上的倒计时也会持续走秒"),
        ChangelogItem("v1.0.3.5", "2026-09-14",
            "当前动画效果名称从「目标 / 模式」一行移到按钮区，显示在「显示」「模式」按钮之后\n" +
            "该按钮可直接点击循环切换动画效果，改完立即生效\n" +
            "「目标 / 模式」行恢复只显示目标时间与显示模式"),
        ChangelogItem("v1.0.3.4", "2026-09-14",
            "跳秒动画改为只作用在最后一位数字上，其余数字保持静止，视觉更聚焦\n" +
            "列表每行「目标 / 模式」之后显示当前动画效果名称，悬浮窗同步显示\n" +
            "删除「周天时分秒模式」，显示模式精简为 8 种"),
        ChangelogItem("v1.0.3.3", "2026-09-14",
            "新增跳秒动画：编辑倒计时可在「缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼」中选择，\n" +
            "  主界面列表与悬浮窗每次跳秒都会播放，默认「无动画」\n" +
            "删除重复的「周天时分秒模式」，显示模式由 10 种精简为 9 种\n" +
            "修复周模式下「xx时xx分xx秒」的显示问题：现在恒定显示「xx周xx天xx时xx分xx秒」，\n" +
            "  当日倒计时切到该模式也会显示完整的周、天"),
        ChangelogItem("v1.0.3.2", "2026-09-14",
            "彻底解决多个倒计时「秒」位数不一致（29 秒 / 30 秒 / 31 秒并存）：\n" +
            "  所有目标时间统一对齐到整分，列表里每个倒计时的秒数必然相同\n" +
            "  「当日/当月倒计时」改为在次日 / 次月 1 日 00:00:00 归零，与自建倒计时同相位\n" +
            "  读取本地数据时自动把历史目标时间的秒、毫秒尾数抹平（最多偏移 30 秒）\n" +
            "新增全局整秒时钟 AlignedClock：任何刷新路径取到的时刻都一致，杜绝跨秒错位"),
        ChangelogItem("v1.0.3.1", "2026-09-14",
            "修复主界面多个倒计时秒数不一致（有的 29 秒、有的 30/31 秒）的问题：\n" +
            "  目标时间与当前时刻统一对齐到整秒后再相减，所有倒计时在同一瞬间跳秒\n" +
            "刷新节奏改为对齐墙上时钟的整秒边界，消除固定周期定时累积漂移造成的停顿、跳秒\n" +
            "读取本地数据时自动抹平历史目标时间的毫秒尾数，避免旧数据让各条目秒数错开"),
        ChangelogItem("v1.0.3", "2026-09-14",
            "修复主界面多个倒计时秒数刷新不同步的问题：同一帧统一取一次当前时刻，所有倒计时同时跳秒\n" +
            "刷新频率提高到每秒 4~5 次，避免定时漂移造成的停顿、跳秒\n" +
            "关于页「检查更新」按钮旁新增「更新日志」按钮，可随时查看各版本改动"),
        ChangelogItem("v1.0.2", "2026-09-14",
            "检测到新版本后强制弹窗展示更新日志，不再只显示一行状态文字\n" +
            "弹窗内点「立即更新」即在 App 内下载并显示进度，下载完自动拉起安装\n" +
            "启动 App 时自动检查一次更新"),
        ChangelogItem("v1.0.1", "2026-09-14",
            "检查更新支持在 App 内直接下载并安装（不再跳浏览器）\n" +
            "新增「当日倒计时 / 当月倒计时」内置项（不可删除）"),
        ChangelogItem("v1.0.0", "2026-09-07",
            "首发版本：多倒计时管理、悬浮窗显示、归零提示音、10 种显示模式")
    )

    /**
     * 显示更新日志：按发布日期分组，默认全部收起，只有被展开（或点击）的那一天显示日志。
     *
     * 展开某一天时，其余日期的「日期行 + 明细」整体隐藏，界面上只剩被展开那天的内容；
     * 全部收起时再把日期行显示回来，方便继续点选其它日期。
     */
    /**
     * 使用说明对话框：与「更新日志」「发现新版本」同一套深色玻璃风格，
     * 内容按小节组织，过长时自动限高并可上下滚动。
     */
    fun showHelp(activity: Activity) {
        if (activity.isFinishing) return
        showStyledDialog(
            activity = activity,
            title = "使用说明",
            positiveText = "关闭",
            negativeText = null
        ) { host ->

            /** 青色小标题。 */
            fun head(t: String) {
                host.addView(sectionTitle(activity, t))
            }

            /** 正文行（可传多条）。 */
            fun line(vararg ts: String) {
                for (t in ts) {
                    host.addView(bodyText(activity, "  · $t", 14f, 0xFFE4EEFF.toInt()))
                }
            }

            host.addView(
                bodyText(
                    activity,
                    "一个轻量、无广告、纯原生的倒计时工具。添加好倒计时后，可以在主界面查看，" +
                        "也可以让它悬浮在其它应用之上随时瞄一眼。",
                    14f, 0xFFD8D8E6.toInt()
                )
            )

            head("一、添加与编辑")
            line(
                "点右下角「＋」→ 添加，填写标题、选择目标日期与时间",
                "可同时设置显示模式、跳秒动画、提示音与备注",
                "卡片左滑可露出「编辑 / 提示音 / 删除」；点卡片上的标题也能进入编辑"
            )

            head("二、卡片上的按钮行")
            line(
                "显示：切换该倒计时是否在悬浮窗中显示",
                "模式：循环切换 8 种显示格式（标准 / 小时 / 分钟 / 秒 / 天 / 时分秒 / 天时分秒 / 天时分）",
                "动画：循环切换 8 种跳秒动画（无 / 缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼）",
                "提示音：点名称即可打开系统铃声选择器，为每个倒计时单独指定"
            )

            head("三、排序与删除")
            line(
                "长按卡片可上下拖动排序，松手即保存",
                "左滑卡片点「删除」即可移除（内置倒计时同样可删除）"
            )

            head("四、悬浮窗")
            line(
                "在悬浮窗里可拖动位置、点标题栏收缩成小条、调节不透明度",
                "系统「设置」界面处于前台时，悬浮窗会被系统临时隐藏（Android 安全机制），返回后自动恢复",
                "若任意界面都不显示，请在系统设置中开启「显示在其他应用上层」，并在品牌权限管理中开启「后台弹出界面」"
            )

            head("五、内置倒计时")
            line(
                "自带五个：当日倒计时、每周倒计时、当月倒计时、华都云境悦府、GTA6",
                "内置项可以修改：一旦改了目标时间，它就变成普通倒计时",
                "删掉的内置项可在「＋」菜单里点「恢复内置」找回，并会还原删除前的主题颜色、模式、动画、提示音等设置"
            )

            head("六、更新与通知")
            line(
                "打开 App 会自动检查更新，之后每 15 分钟后台检查一次",
                "「开启通知 / 测试通知 / 允许后台运行」三个按钮可自检通知是否真的收得到",
                "收不到更新通知时，先点「测试通知」验证，再点「允许后台运行」关闭电池优化"
            )

            head("七、吃药提醒")
            line(
                "默认每天 09:00 提醒；「关于」页点「提醒时间」可改成任意时刻",
                "「关于」页点「吃药提醒：已开启」即可一键关闭，关掉后不再打扰，记录仍保留",
                "到点通知上点「已吃药」即记录当天；之后点「吃药日历」可查看哪天吃了、哪天没吃",
                "日历支持切换月份，点过去或今天的日期可补记 / 撤销，未来日期不可记录"
            )

            head("八、数据")
            line(
                "所有倒计时与吃药记录都保存在手机本地，卸载应用会一并清除，请先做好记录"
            )
        }
    }

    fun showChangelog(activity: Activity) {
        if (activity.isFinishing) return
        showStyledDialog(
            activity = activity,
            title = "更新日志",
            positiveText = "关闭",
            negativeText = null
        ) { host ->
            // 按日期分组，同时保持 CHANGELOG 里「新→旧」的顺序
            val groups = LinkedHashMap<String, MutableList<ChangelogItem>>()
            for (item in CHANGELOG) {
                val list = groups[item.date]
                if (list == null) groups[item.date] = mutableListOf(item) else list.add(item)
            }

            val heads = ArrayList<LinearLayout>()
            val details = ArrayList<LinearLayout>()
            val arrows = ArrayList<TextView?>()

            /**
             * 切换展开状态：idx 为要展开的分组下标，-1 表示全部收起。
             * 只要有一天是展开的，其它日期的行（含日期本身）就不显示。
             */
            fun applyState(idx: Int) {
                for (i in heads.indices) {
                    val expandThis = i == idx
                    heads[i].visibility = if (idx < 0 || expandThis) View.VISIBLE else View.GONE
                    details[i].visibility = if (expandThis) View.VISIBLE else View.GONE
                    arrows[i]?.text = if (expandThis) "  ▲" else "  ▼"
                }
                if (idx >= 0) {
                    heads[idx].post {
                        (host.parent as? ScrollView)?.smoothScrollTo(0, heads[idx].top)
                    }
                }
            }
            var expanded = -1

            for ((date, items) in groups) {
                val head = LinearLayout(activity)
                head.orientation = LinearLayout.HORIZONTAL
                head.gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(activity, 12)
                head.layoutParams = lp

                val dateTv = bodyText(activity, date, 16f, 0xFF00FFFF.toInt())
                dateTv.setTypeface(dateTv.typeface, Typeface.BOLD)
                head.addView(dateTv)
                host.addView(head)

                // 同一天发布多条时才显示下箭头；只有一条的日期没有箭头，
                // 但点日期本身同样可以展开 / 收起。
                var arrow: TextView? = null
                if (items.size > 1) {
                    arrow = bodyText(activity, "  ▼", 14f, 0xFF00FFFF.toInt())
                    head.addView(arrow)
                }

                val detail = LinearLayout(activity)
                detail.orientation = LinearLayout.VERTICAL
                detail.visibility = View.GONE
                detail.setPadding(dp(activity, 12), dp(activity, 4), 0, 0)
                for (item in items) addVersionBlock(activity, detail, item)
                host.addView(detail)

                val index = heads.size
                heads.add(head)
                details.add(detail)
                arrows.add(arrow)

                head.setOnClickListener {
                    expanded = if (expanded == index) -1 else index
                    applyState(expanded)
                }
            }
        }
    }

    /** 一个版本的日志块：版本号 + 逐条内容。 */
    private fun addVersionBlock(ctx: Context, host: LinearLayout, item: ChangelogItem) {
        val tv = bodyText(ctx, item.version, 15f, 0xFFFFFFFF.toInt())
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 8)
        tv.layoutParams = lp
        host.addView(tv)
        for (line in item.content.split("\n")) {
            if (line.isBlank()) continue
            host.addView(bodyText(ctx, "  · $line", 14f, 0xFFD8D8E6.toInt()))
        }
    }

    /**
     * 在 Activity 的 onResume 中调用：若此前因未授权而挂起安装，授权返回后自动继续安装。
     * @return 是否消费了挂起的安装任务。
     */
    fun consumePendingInstall(activity: Activity): Boolean {
        val f = pendingApk
        if (f != null && f.exists() && canInstall(activity)) {
            pendingApk = null
            doInstall(activity, f)
            return true
        }
        return false
    }
}
