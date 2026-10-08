package com.baigao.countdown

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.view.accessibility.AccessibilityManager
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
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
    // ---------------- 更新提示方式 ----------------

    /**
     * 更新提示怎么出现。
     *
     * 背景：那些「跳过开屏广告 / 弹窗拦截 / 广告过滤」类工具是**靠无障碍服务盯着窗口**，
     * 一发现像弹窗的窗口就替用户点掉。对话框（v25 之前）会被盯上，独立页面也一样可能被盯上。
     * 但**通知栏通知它们关不掉** —— 所以给出「只发通知」这条路，并且能自动降级。
     */
    enum class UpdatePromptMode(val key: String, val label: String, val desc: String) {
        AUTO("auto", "自动", "要是发现弹窗拦截类工具，就自动改成只发通知"),
        PAGE("page", "弹出提示", "总是弹出更新提示页（可能会被拦截工具关掉哦）"),
        NOTIFY("notify", "只发通知", "不弹任何界面，只在通知栏发一条（最不容易被拦住）")
    }

    private const val PREF_PROMPT = "update_prompt"
    private const val KEY_PROMPT = "mode"

    fun promptMode(ctx: Context): UpdatePromptMode {
        val v = ctx.getSharedPreferences(PREF_PROMPT, Context.MODE_PRIVATE)
            .getString(KEY_PROMPT, UpdatePromptMode.AUTO.key) ?: UpdatePromptMode.AUTO.key
        return UpdatePromptMode.values().firstOrNull { it.key == v } ?: UpdatePromptMode.AUTO
    }

    fun setPromptMode(ctx: Context, mode: UpdatePromptMode) {
        ctx.getSharedPreferences(PREF_PROMPT, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROMPT, mode.key).apply()
    }

    /** 「关于」页按钮用：点一下切到下一个模式，返回新的模式。 */
    fun nextPromptMode(ctx: Context): UpdatePromptMode {
        val order = listOf(
            UpdatePromptMode.AUTO, UpdatePromptMode.PAGE, UpdatePromptMode.NOTIFY
        )
        val idx = order.indexOf(promptMode(ctx))
        val next = order[(idx + 1) % order.size]
        setPromptMode(ctx, next)
        return next
    }

    /**
     * 系统里是否开着「弹窗拦截类」无障碍服务。
     * 这类工具（跳过开屏广告等）必须开无障碍服务才能替用户点掉窗口，
     * 所以只要开着非系统/非输入法的无障碍服务，就按「有拦截风险」处理。
     */
    fun hasSuspiciousAccessibility(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            list.any { si ->
                val pkg = si.resolveInfo?.serviceInfo?.packageName ?: return@any false
                when {
                    pkg == ctx.packageName -> false
                    pkg == "android" -> false
                    pkg.startsWith("com.android.") -> false          // 系统自带
                    pkg.startsWith("com.google.") -> false           // TalkBack 等
                    pkg.startsWith("com.android.inputmethod") -> false
                    else -> true
                }
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 当前是否应该弹更新提示页（false 就只发通知栏通知）。 */
    fun shouldShowPage(ctx: Context): Boolean = when (promptMode(ctx)) {
        UpdatePromptMode.AUTO -> !hasSuspiciousAccessibility(ctx)
        UpdatePromptMode.PAGE -> true
        UpdatePromptMode.NOTIFY -> false
    }

    const val CURRENT_VERSION_NAME = "1.0.0.38"
    private val CURRENT_VERSION_NUM = versionToNumber(CURRENT_VERSION_NAME)

    private const val OWNER = "baigao110"
    private const val REPO = "countdown-android"
    private const val LATEST_RELEASE = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val UPDATE_JSON =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/update.json"

    private val handler = Handler(Looper.getMainLooper())

    /** 更新提示延后拉起的毫秒数：避开「一开 App 就弹窗」这个开屏广告判定特征。 */
    /** 延后再拉起更新提示页：避开「一开 App 就弹窗」这个开屏广告判定特征。 */
    private const val UPDATE_PROMPT_DELAY = 1500L
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
     * 「忽略更新」：非持久化的临时忽略。
     *
     * 用户在「发现新版本」提示框里点「忽略更新」后，调用 setIgnored 记下被忽略的版本，
     * 「关于」页回到前台时据此把状态显示为「已是最新版本」。
     *
     * 注意：这**不是**「永久忽略该版本」—— 下次手动点「检查更新」会重新拉取远程版本，
     * 仍然会发现新版本并再次弹出提示框，从而满足「忽略 → 已是最新 → 再查 → 再提示 → 再忽略」的循环。
     */
    private var ignoredVersionName: String? = null

    fun setIgnored(versionName: String) {
        ignoredVersionName = versionName
    }

    /** 取出并清空「被忽略的版本」（只消费一次，避免重复处理）。 */
    fun consumeIgnored(): String? {
        val v = ignoredVersionName
        ignoredVersionName = null
        return v
    }

    /**
     * 异步检查更新。
     * @param forceDialog 为真时，发现新版本直接弹出更新日志对话框（用于「强制提示」场景）。
     * @param notify 为真时，发现新版本同时在系统通知栏发一条更新提醒（同一版本每天一次）。
     */
    fun check(
        activity: Activity,
        forceDialog: Boolean,
        notify: Boolean = true,
        /** 用户主动要看（点了通知 / 点了「检查更新」）时传 true，此时不受拦截降级影响。
         *  注意：必须排在 onResult 之前 —— 调用方用尾随 lambda 传 onResult，
         *  尾随 lambda 只能落在形参列表最后一位。 */
        forcePage: Boolean = false,
        onResult: ((UpdateInfo?) -> Unit)? = null
    ) {
        Thread {
            val info = fetch()
            handler.post {
                val newest = if (info != null && info.num > CURRENT_VERSION_NUM) info else null
                onResult?.invoke(newest)
                if (newest != null && notify) UpdateNotifier.notifyUpdate(activity, newest)
                if (forceDialog && newest != null) showUpdateDialog(activity, newest, forcePage)
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
        text: CharSequence,
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
        val tv = bodyText(ctx, text, 15f, 0xFFFFA63D.toInt())
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

        if (positiveText.isEmpty()) {
            // 没有主按钮时（比如体检全部正常，没什么可修的）：隐藏它，
            // 并把唯一的「关闭」改成按内容宽度居中 —— 否则它会撑满整行，看着像主按钮。
            posBtn.visibility = View.GONE
            negBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            negBtn.minWidth = (150 * dm.density).toInt()
        } else {
            posBtn.text = positiveText
        }
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
    /**
     * 弹出更新提示（或只发通知）。
     *
     * @param forcePage true 表示是用户主动点进来的（比如点了通知），此时无论如何都弹页面。
     */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo, forcePage: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!forcePage && !shouldShowPage(activity)) {
            // 检测到弹窗拦截类工具（或用户选了「只发通知」）：改成通知栏通知。
            // 通知栏不在那些工具能关掉的窗口范围内，是最稳的一条路。
            UpdateNotifier.notifyUpdate(activity, info, force = true)
            return
        }
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
        host.addView(bodyText(ctx, if (info.note.isBlank()) "暂时没有更新说明哦" else info.note))
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
                title = "正在下载 v${info.name}…",
                positiveText = "先不了",
                negativeText = null,
                cancelable = false,
                onPositive = { canceled.set(true) }
            ) { host ->
                val tv = bodyText(activity, "准备中…")
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
                                        if (percent >= 0) "已经下载 $percent%（${mb} MB）"
                                        else "已经下载 ${mb} MB 啦"
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
                    Toast.makeText(activity, "下载没成功，检查下网络再试一次哦", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(activity, "请先允许安装未知应用哦", Toast.LENGTH_LONG).show()
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
            Toast.makeText(activity, "请在安装界面点一下「安装」哦", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(activity, "打不开下载链接呢", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 更新日志（离线可读，按发布日期分组） ----------------

    /** 单条更新日志：版本号 + 发布日期（yyyy-MM-dd）+ 内容。 */
    class ChangelogItem(val version: String, val date: String, val content: String,
        val obsolete: Boolean = false)

    /**
     * 各版本更新日志。新增版本时在**头部**追加一条，并填上真实发布日期：
     * 更新日志框会按日期合并分组，同一天发布多条时点日期后的下箭头展开明细，
     * 只有一条的那天直接铺开显示、不显示箭头。
     */
    private val CHANGELOG = listOf(
ChangelogItem("v1.0.0.38", "2026-10-08", "v163：添 / 改小倒计时那一页，「倒计时类型」下面多出一颗「是否循环」下拉框，跟页面上其它下拉框同一套样子 —— 挑「是（归零重新计时）」，这条倒计时归零之后会自己从头再倒一遍（照它自己这一轮的时长一轮一轮往下转，每一轮到点照样响提示音、照样出归零通知）；挑「否（归零就停住）」就是原来的跑法，归零停在那儿不动。另外内置倒计时（每小时 / 每5分钟 / 每10分钟 / 每半小时 / 当日 / 每周 /当月 / 华都云境悦府 / GTA6）也加了「启用自定义提示音」开关，勾上之后挑音那两颗按钮和主界面卡片上那颗提示音按钮都照普通倒计时的样子给着（功能、风格一模一样），勾着主界面就显示提示音按钮、不勾就不显示、归零只响默认提示音；挑「普通倒计时」这一颗开关不摆、那两颗按钮照旧直接给。老数据一律按老规矩落地，装上去跟以前一眼看不出差别。悬浮窗同步照旧"),
ChangelogItem("v1.0.0.38", "2026-10-08", "v162：把添加 / 编辑小倒计时那一页「倒计时类型」下面那一层「内置档位」整个收掉了 —— 那一级只有挑「内置倒计时」时才亮，用来挑这条倒计时要走哪一档内置（每小时 / 每5分钟 / 每10分钟 / 每半小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6）；现在它不再摆出来，「倒计时类型」只剩「普通倒计时 / 内置倒计时」两格，是不是内置由这一级定，内置这一档是哪一档由这条自己来 —— 编辑已有内置照旧是它自己原来那一档（名字、颜色、显示模式、跳秒动画、备注都照旧能改；在编辑页改成「普通倒计时」存下去它就不是内置了，「＋」菜单「复」字里也不再挂着它，连那份设置快照一起抹掉，同档还有别条的话那份照旧留着），新建的按「每小时」这一档走内置；类型这一级不做记忆，重新安装、首次装都不会被上一次的选择带偏。悬浮窗同步照旧"),
ChangelogItem("v1.0.0.38", "2026-10-08", "v161：添加小倒计时那一页的「倒计时类型」拆成两格 —— 「普通倒计时」和「内置倒计时」，不再是以前那一长串档名挤在一起；挑「内置倒计时」它就按下面「内置档位」挑的那一档走内置（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6），建出来的这条一直是内置，删了能在「＋」菜单「复」字里原样找回来（设置一个不少）；已经在列表里的那条内置，进编辑页照旧能改它的名字、颜色、显示模式、跳秒动画、备注，类型也照样能在普通和内置之间换；一条内置在编辑页改成「普通倒计时」存下去，它就不是内置了，「复」字里也不再挂着它，连它之前存下的那份设置快照一起抹掉（同档还有别条的话那份照旧留着）；类型这一级选的那一档不做记忆 —— 每次添加都从「普通倒计时」起，编辑已有条目时才带出它自己原来的类型，重新安装、首次装跟着一样，都不会被上一次的选择带偏。悬浮窗同步照旧"),
ChangelogItem("v1.0.0.38", "2026-10-08", "v160：当月倒计时、华都云境悦府倒计时、GTA6 倒计时，还有新建的那条普通倒计时的显示模式里，多出「周模式」和「周天时分秒模式」两档 —— 周模式只写 xx周（还剩几个整周），周天时分秒模式写 xx周xx天xx时xx分xx秒（周在最前头，跟原来标准模式一个写法）；编辑页那颗模式下拉框、卡片上的「显示模式」按钮、悬浮窗抽屉里那两颗「上一个 / 下一个显示模式」、锁屏通知看到的都是这一份列表，切完当场生效、不用重启；每周倒计时最长就 7 天（周那截恒 0），每5分钟 / 每小时 / 当日那几个更是到不了一周，这两档对它们收着不摆，免得冒出一个干巴巴的「0周」。悬浮窗同步照旧"),
ChangelogItem("v1.0.0.38", "2026-10-07", "v159：把「100年内倒计时」这一档连同它的相关代码一起删干净了 —— 「倒计时类型」里那一串收成十个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6），这一档不再摆在上面、新的一条都生成不了；跟着一起撤掉的还有添 / 改页那六颗「年 / 月 / 日 / 时 / 分 / 秒」下拉框、旁边那颗「启用自定义提示音」的开关、按这段时长自动生成名字和备注，以及归零后照着「生成那会儿离目标还有多久」重新起一轮的那套跑法；它自己那三个数据字段（目标时刻 / 每轮时长 / 是否启用自定义提示音）和存盘读写也一并清了，升级之后列表里以前挑日子生成的那几条（「20小时」「7小时」那些）连它们存的备份快照一起扫掉，「＋」菜单「复」字里那个逐条找回的区块也撤了，不会在点开时看见一个选不中的类型。那个常量编号留了个空位没回收（免得以后新档撞上老数据认不出）。其余照旧：跨过自动重新倒数、自动补齐、拖排序、「主界面默认排序」、「找回小内置」、挑提示音、悬浮窗同步就都还在"),
ChangelogItem("v1.0.0.38", "2026-10-07", "v158：把挑自定义提示音的路子补回来 —— 普通倒计时、华都云境悦府、GTA6 的新建页和编辑页上，「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮直接摆着，点一下就唤起系统铃声选择器，不用像「100年内倒计时」那样先去勾旁边那颗「启用自定义提示音」开关；挑过的铃声名就写在按钮上，点「清除自定义提示音」回到默认。只有每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月这八个自己往下跳的内置档照旧不摆（归零只响默认提示音），「100年内倒计时」仍是勾上那颗开关才摆。列表卡片上的提示音名称一点就改，悬浮窗同步照旧"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v157：修下拉框里只剩色块、看不见名字的问题。「显示模式 / 动画 / 颜色」三颗按钮弹出来的那一层里，动画那八种、颜色那七色全都没了文字，只剩一个圆点色块（动画的全是青色方格，颜色那颗只剩色块没有名字）。根因是名字那格没写 layoutParams，addView 拿到的默认参数宽度为 MATCH_PARENT，而横向 LinearLayout 在父容器宽度不精确时会把这类子项直接量成 0 宽 —— 名字被挤没了。这一轮名字显式 WRAP_CONTENT，面板宽度按最长的那条名字先量一遍再定，不再窄到把字裁掉；弹的位置还会自动避开屏幕左右边缘、下面放不下就翻到按钮上方。悬浮窗同步照旧。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v156：主界面倒计时卡片上的「显示模式」「动画」「颜色」三颗按钮，改成点一下就弹出下拉框来挑。显示模式只在这条倒计时真正能用的档位里列（每5分钟是 标准/分钟/秒 三档），点中即刻生效；动画一次挑一种跳秒效果（无动画 / 缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼）；另外新加了一颗「颜色」按钮，倒计时文字的主题色不用再进编辑页 —— 青 / 品红 / 绿 / 黄 / 橙 / 红 / 金 七色点一下就换，列表和悬浮窗同帧跟着换；编辑页那颗色板跟它共用同一份颜色。悬浮窗同步照旧。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v155：每5分钟、每10分钟、每小时这三档的「标准模式」统一改成 xx分xx秒。每5分钟、每10分钟原来只写 xx 分（看成「5分」，其实还剩 4 分 59 秒，秒那截藏在背后看不见），每小时原来写 xx时xx分xx秒，可它最多就 1 小时，「时」那截只会是 0，摆出来纯占地方；现在三档都跟每半小时、每2~9分钟一个写法，秒一眼就能看见，能切的档位仍是 标准 / 分钟 / 秒 三档，悬浮窗同步照旧。当日倒计时最多 24 小时，「时」那截有数，仍旧是 xx时xx分xx秒，不受影响。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v154：把上一轮（v151）新加的 29 个周期滚动型内置档整个下线 —— 每2分钟、每3分钟、每4分钟、每6分钟、每7分钟、每8分钟、每9分钟，还有每2小时一路到每23小时，一共 29 个，从「倒计时类型」下拉框和出厂顺序里一起摘掉，老数据里已经生成的那几十条也在打开时就清干净了（连同它们的备份快照、「复」字里挂着的条目一并撤掉，免得留下一堆再也选不中的档）。剩下的十档（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6）一个没动，归零响铃、可拖排序、「＋」菜单「复」字里找得回来，悬浮窗同步照旧。内置类型编号 13~41 照旧留着不回收，哪天再挂回来老数据还认得。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v153：修「每2~23小时倒计时」的目标时刻没按每 N 小时循环 —— 上一轮（v151）把这一批并进通用周期段时，算边界用的是「分钟数(0~59) 除以步长(120~1380)」，商恒为 0，结果 12~23 小时这 22 个档全被压成「下一个整点」，跟「每小时倒计时」一模一样，看着就像根本没按每 N 小时滚。这一轮改成老实对齐：目标 = 从当天 00:00 起算的下一个 N 小时边界 —— 每2小时就是 02:00 / 04:00 / 06:00 ……，每23小时就是当日 23:00 → 次日 22:00 → ……；每2~9 分钟那七个档不受影响（本来就算对了），老五档（每分钟 / 每5 / 每10分钟 / 每半小时 / 每小时）一行没动。归零自动接着往下跳、提示音照响、可拖排序、「＋」菜单「复」字里找得回来，悬浮窗同步照旧。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v152：每2分钟、每3分钟、每4分钟、每6分钟、每7分钟、每8分钟、每9分钟这七个新档的标准模式，显示格式改成了「xx分xx秒」—— 上一轮它们最长显示到 9 分，秒那截藏在背后看不见（每2分钟看成「2分」，实际还剩 1 分 59 秒），这一轮把秒摆出来：标准模式直接给分秒模式，跟「每半小时」一个写法。可选档位跟着顺过来：标准（=分秒）/ 分钟 / 秒，小时模式、时分秒模式、天数那三档照旧砍掉（时那截恒 0）。每2~23小时那批不受影响，仍然最长 23 小时、标准模式给「xx时xx分xx秒」。悬浮窗同步照旧。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v151：添一大批内置小倒计时 —— 短的有每2分钟、每3分钟、每4分钟、每6分钟、每7分钟、每8分钟、每9分钟，长的有每2小时一路到每23小时，一共 29 个新档，跟「每小时倒计时」一个路数：目标时刻由系统一格格往下跳（每2分钟就跳到下一个 2 分边界，每2小时就跳到下一个 2 小时边界），跨过自动重新倒数、自动补进列表、可拖排序、「＋」菜单「复」字里照样找得回来，悬浮窗同步照旧。它们都是「内置倒计时」，删了就在「复」字里勾一下捞回来；换音按钮照旧不给（归零只响默认提示音）。显示模式也按周期长短自动收：每2~9分钟那批「时」那截恒 0，标准模式直接给「分钟模式」；每2~23小时那批最长 23 小时，「时」有数，标准模式给「时分秒模式」，切档跟每小时一模一样。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v150：「100年内倒计时」这一档整个下线了 —— 添 / 改页「倒计时类型」那一串里不再有它（十一个内置项收成十个：每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6），新生成不了了；升级后列表里原来躺着的那几条（以前挑日子生成的「20小时」「7小时」那些）一并删掉，连它们存的备份快照也扫干净，「＋」菜单「复」字里那个逐条找回的区块一并撤掉，不会在点开时看见一个选不中的类型。其余照旧：跨过自动重新倒数、自动补齐、拖排序、「主界面默认排序」、「找回小内置」、「每半小时 / 每10分钟 / 每5分钟 / 每分钟」那几个新档、悬浮窗同步就都还在。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v149：「100年内倒计时」能一条一条单独找回了 —— 你生成的每一条（「20小时」「7小时」那些）删掉的时候都会各存一份快照，点「＋」菜单里的「复」字进找回框，下半截新开一块「你自己生成的『100年内倒计时』」，把删掉的每一条列出来（名字 + 从哪一年几月几日几时起 + 每轮多少时长），想找哪条就勾哪条、只把勾上的那些捞回来，也能「这些全选上」一把全找回，不用再把存过的备份一股脑全倒进列表。上半截那十一个小内置照旧勾哪个补哪个，十一个本来就在时也会给一句说明，不会白点。跨过按时间差重新倒计时、名字备注按时长自动生成、个数不限制、同时长才挡，都不变。悬浮窗同步照旧。"),
ChangelogItem("v1.0.0.38", "2026-10-07", "v148：修掉「100年内倒计时」挑完目标时刻日期自己多一天的老毛病 —— 添 / 改页那六颗下拉框里，「月 / 日」两颗原来是从 0 起填的（「0月 / 0日」那种），填值那一下又减一 / 加一抵消着，看着还行：初值显示「6日」、存下去的其实是 7 日；你真去点选「10日」，那个 10 被当成格子号、读出来又给加一，存下去就成了 11 日，六颗下拉框上写的和你挑的那个日子差一天。现在月 / 日两颗统一成「上头写几就是几」（1月..12月、1日..31日），点哪天就是哪天、自动生成的名字也跟挑的一模一样，时 / 分 / 秒三颗照旧从 0 起。顺手修的第二个：生成完一条「100年内倒计时」以后，「倒计时类型」那一串里就再也选不到它了（得先把生成的那条删掉才看得见）—— 因为它和「每小时 / 当日 / 每分钟」一样都是内置倒计时，能存好几条，早先却还按「列表里已经有一条就不摆」的老规矩；现在这一档恒在那儿，随时能再挑一条。跨过按时间差重新倒计时、名字备注按时长自动生成、个数不限制、同时长才挡、删了上「＋」菜单「找回小内置」勾回来，都不变。悬浮窗同步照旧。"),
ChangelogItem("v1.0.0.38", "2026-10-07", "v147：「100年内倒计时」还是内置那一档（跟「每小时 / 当日 / 每分钟」一个路数）—— 能存好几条、个数不限制，同时长的那条依然只留一条；跨过之后照着「生成那会儿离目标还有多久」重新倒计时（生成时差 20 小时就按 20 小时一轮轮转下去，差 7 小时就按 7 小时），名称和备注都按这个差自动生成；删掉之后上界面「＋」菜单里的「找回小内置」按名字勾回来就成。这一轮补的是：能存多条之后，删掉一条时列表里还躺着另一条同类型的，恢复就被挡下（勾半天没反应）—— 现在删哪条就替哪条单独存一份快照，删掉的条条都能找回来，找回来的时长、名字、备注、主题颜色、显示模式、跳秒动画一起还原，找回来之后照旧悬浮窗同步。"),
ChangelogItem("v1.0.0.38", "2026-10-07", "v146：「100年内倒计时」换个跑法 —— 跨过之后不再跳到一百年的下一个同刻，而是照着生成那会儿隔了多久重新倒计时（生成时离目标 20 小时，归零就接着按 20 小时往下跑；离 7 小时就按 7 小时跑），一轮一轮自动转下去；名称和备注也跟着这段时长自动生成（生成时差 20 小时，名字就是「20小时」、备注是「本轮还剩 20小时」）；顺带放开「100年内倒计时」可以存好几条（个数不限制），只要时长不撞车就都能生成，改目标时刻、改名字改备注都照旧，跨过之后悬浮窗同步跟着一起转。"),
ChangelogItem("v1.0.0.38", "2026-10-07", "v145：修好「100年内倒计时」的目标时间跟你挑的时刻对不上 —— 原来只要挑的那个日子到了（哪怕你挑的本来就是过去的一刻、或者就着默认值直接存的「现在」），它都会自己往后跳一整个 100 年到一百年的同一个时刻，生成完、改过之后看见的目标时间就不是你挑的那天了；现在挑过去的一律停在挑的那个时刻（归零就不动，跟华都云境悦府、GTA6 一个脾气），只有挑未来的日子才照常往下走、走到 0 之后才滚一百年。顺手修了月份下拉框（原来从 0 月起，挑「1月」会存成上一年 12 月、自动生成的名字也写成「2027年0月5日」这种，现在改回 1 月起，挑几月就是几月），改目标时刻保存也不再拿上一次的旧目标盖掉你新挑的时刻，编辑时六颗下拉框回到你当初挑的那天、挑的那一年不在「今年~今+100」里也会自动把年份那一串抻开。悬浮窗同步照旧：保存返回、或者改了目标时刻（那六颗下拉框挑完一点保存），这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v144：修掉刚挂回来的「100年内倒计时」带出来的一处闪退 —— 点「＋」进「添加小倒计时」那一页一闪就没了：页面里那串自动生成的名字（「2026年10月07日 12:00:00」这种）去读六颗下拉框的值时，下拉框还没填过，读到空值当场报错退出。现在这串名字改成真正要用的时候才去算，六连框也提前按当前时刻填好，一前一后的顺序对上了就再也不闪；顺手给读值的那道口子也上了兜底，万一哪颗下拉框还没值也不会炸。其它一切照旧：悬浮窗同步、跨过自动重新倒计时、查重、提示音开关都没动。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v143：把「100年内倒计时」这一档挂回「倒计时类型」下拉框里 —— 挑中它，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框（跟页面上其它下拉框同一个样子：年那颗从今年一路列到 100 年后，月 / 日 / 时 / 分 / 秒各一颗，日那颗跟着年月自动收天数），随便挑 100 年以内任意一个日子就是它的目标时刻；名字和备注都照挑好的时间自动写成「2026年10月7日 12:00:00」这种，手打过名字的照旧不动。旁边那颗「启用自定义提示音」开关也跟着亮，勾上才在下面摆出「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮（跟其它自定义提示音按钮一模一样），不勾就整块收着、归零只响默认提示音。挑「普通倒计时」，这六颗下拉框和那颗开关整块收着，目标时刻照旧由上面那块「目标日期 / 目标时间」给。它跟「每小时倒计时」「当月倒计时」一个路数：显示模式、颜色、跳秒动画、长按拖动、「＋」菜单里找回全一样，跨过那一刻自动往后推一整个 100 年周期重新倒数（跨过自动重新倒计时）；同名、或者同类型的那条已经躺在列表里了，会弹一句「该倒计时已存在，生成失败！」。内置倒计时凑成十一个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6 → 100年内倒计时），出厂顺序里也给它留了位。悬浮窗同步照旧：保存返回、或者改了目标时刻 / 提示音，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v142：把「100年以内（100年倒计时）」这一档整个下掉了 —— 「倒计时类型」下拉框里不再有它，「年 / 月 / 日 / 时 / 分 / 秒」那六颗下拉框和「启用自定义提示音」那颗开关一起收掉，保存时也不再按它记目标时刻；内置倒计时回到十个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6），出厂顺序里也把它腾了位；悬浮窗同步照旧：保存返回、或者改了目标时刻 / 提示音，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-07", "v141：「100年以内」这一档重新挂回「倒计时类型」下拉框里 —— 添 / 改页顶上那一排里多了它，挑中「100年以内」，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框（跟页面上那几颗一个样子），随便挑 100 年以内任意一个日子就是它的目标时刻，名字也照着挑好的时间自动写成「2056年10月7日 08:05:30」这种；挑「普通倒计时」，这六颗下拉框和那颗「启用自定义提示音」开关整块收着，目标时刻照旧由上面那块「目标日期 / 目标时间」给。它跟「每小时倒计时」「当月倒计时」一个路数：备注照着挑的时刻自动写，跨过那一刻自动往后推一整个 100 年周期重新倒数，显示模式、名字、颜色、跳秒动画全跟其它内置项一样，「＋」菜单里也能找回来；同名、或者同类型的那条已经躺在列表里了，会弹一句「该倒计时已存在，生成失败！」。内置倒计时凑成十一个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6 → 100年以内），出厂顺序里也给它留了位。悬浮窗同步照旧：保存返回、或者改了目标时刻 / 提示音，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v140：把「100年以内」这一档从「倒计时类型」里撤了 —— 内置倒计时回到十个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6）；要挑 100 年以内任意年月日时分秒，现在改在「普通倒计时」这一档里：类型停在「普通倒计时」，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框（跟页面上那几颗同一个样子），顶上那块「目标日期 / 目标时间」顺手收掉，随便挑 100 年以内任意一个日子就是它的目标时刻；挑的是将来的时刻，保存后就是一条内置的自定义倒计时，跟「每小时倒计时」「当月倒计时」一个路数（备注照着挑的时刻自动写，跨过那一刻自动往后推一整个 100 年周期重新倒数，显示模式、名字、颜色、跳秒动画全跟其它内置项一样，「＋」菜单里也能找回来；同名、或者同类型的那条已经躺在列表里了，会弹一句「该倒计时已存在，生成失败！」），挑的是当下 / 过去的时刻就还是普通倒计时。那颗「启用自定义提示音」开关也跟着亮，勾上才在下面摆出「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮（跟其它自定义提示音按钮一模一样），不勾就整块收着、归零只响默认提示音。悬浮窗同步照旧：保存返回、或者改了目标时刻 / 提示音，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v139：把上一轮下掉的「100年以内倒计时」请回来了 —「倒计时类型」下拉框里多出这一档，挑中它下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框（跟页面上那几颗同一个样子），随便挑 100 年以内任意一个日子就是它的目标时刻；旁边那颗「启用自定义提示音」开关也跟着亮，勾上才在下面摆出「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮（跟普通倒计时那两颗一模一样），不勾就整块收着、归零只响默认提示音。它跟「每小时倒计时」「当月倒计时」一个路数：备注照着挑的时刻自动写（距离2035年10月6日18点整结束），跨过那一刻自动往后推一整个 100 年周期重新倒数，显示模式、名字、颜色、跳秒动画、备注、「＋」菜单里找回、以及「该倒计时已存在，生成失败！」这一句查重提示，全跟其它内置项一样；内置项凑成十一个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6 → 100年以内）。悬浮窗同步照旧：保存返回、或者改了目标时刻 / 提示音，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v138：把上一轮新加的「100年倒计时」整个下掉了 —— 添加 / 修改页的「倒计时类型」里不再有这一档，「年 / 月 / 日 / 时 / 分 / 秒」那六颗下拉框和「启用自定义提示音」开关一起收掉，保存页也不再按它记目标时刻；现在内置倒计时是十个（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6），华都云境悦府、GTA6 到点就地停 0 没变，其余几个照旧自己往下跳；悬浮窗同步照旧：保存返回、或者改了内置项，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v137：把上一轮新加的「100年倒计时」改成跟「每小时倒计时」一个路数 —— 以前挑中的那个日子跨过去就停在 0，现在跨过那一刻会自动把整个日子往后推一整个 100 年周期（同一个月、日、时、分、秒落到下一个百年），倒计时数字当场从头再数一遍，永远不卡在 0 上不动；自己挑「年 / 月 / 日 / 时 / 分 / 秒」那六颗下拉框、显示模式、名字、颜色、跳秒动画、备注、以及那颗「启用自定义提示音」开关和「该倒计时已存在，生成失败！」的提示一个字没改，跟华都云境悦府、GTA6 这两个「固定日子」的差别就是它一格格自己往下滚。悬浮窗同步照旧：保存返回、或者改了目标日子，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v136：把上一轮那一位「24小时倒计时」换成了「100年倒计时」——添加 / 修改页把「倒计时类型」挑成它，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框（跟页面上那几颗下拉框同一个样子），随便挑一个日子就是它的目标时刻，到点之后就地停 0，跟华都云境悦府、GTA6 一个路数；显示模式跟其它倒计时一样八档全开，名字、颜色、跳秒动画、备注都能照常改，也不会被降级成普通倒计时。还是那一颗「启用自定义提示音」开关：勾上才在下面摆出「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮（跟普通倒计时那两颗一模一样），不勾就整块收着、归零只响默认提示音，卡片上的「提示音名称」也跟着收。这一条跟其它内置倒计时一样，「＋」→「恢复内置」随时能找回来；要是同名、或者同类型的那条已经躺在列表里了，会弹一句「该倒计时已存在，生成失败！」，这一下什么都不存。悬浮窗同步照旧：保存返回、或者改了目标时刻，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v134：把提示音这条线又往细里分了一层 —— 会自己一格格往下跳的那八个（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月）一律收掉换音入口，归零照旧响默认提示音；华都云境悦府、GTA6 这两个固定日子、以及所有你自己建的倒计时照旧能挑。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "v135：新增「24小时倒计时」——添加 / 修改倒计时页顶上多了一栏「倒计时类型」下拉框，普通倒计时和列表里还躺着的那些内置项都能挑，挑一个就照它把这条倒计时生出来（已经有的不会重复摆），挑「24小时倒计时」时下面立刻亮出「时 / 分 / 秒」三颗下拉框（跟页面上那几颗下拉框同一个样子），随便挑一个时刻，就是它每天归零的时刻，归零之后照旧一格格往下跳，功能和风格跟「每小时倒计时」一模一样；同一页还多了一颗「启用自定义提示音」的开关，只有挑了「24小时倒计时」才亮，勾上才在下面摆出「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮（跟普通倒计时那两颗一模一样，点一下同样唤起系统铃声选择器、选完当场存好），不勾就整块收着、归零只响那一声默认提示音，卡片上的「提示音名称」也跟着收起来。生成出来的内置倒计时跟其它倒计时一路数：名字、颜色、显示模式、跳秒动画、备注都能照常改，也不会被降级成普通倒计时。悬浮窗同步照旧：保存返回、或者改了归零时刻，这一下就把新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "把「挑提示音」这条线又往细里分了一层：这一回收掉的只有会自己一格格往下跳的那八个 —— 每分钟、每5分钟、每10分钟、每半小时、每小时、当日、每周、当月，编辑页里换提示音的那一整块（「用默认提示音（点一下选一个）」「清除自定义提示音」）直接收掉，卡片按钮行上也不会再冒出那颗「提示音名称」，它们归零照旧响那一声默认提示音。华都云境悦府、GTA6 这两个固定倒计时的日子早就定死了、不跟着日期一格格跳，所以挑提示音这扇门照旧给它们留着：编辑页「用默认提示音」「清除自定义提示音」照旧可点，卡片上「提示音名称」也照旧亮着，点一下直接唤起系统铃声选择器、选完当场存好。你自己建 / 改的普通倒计时跟它们一路，两边都是原样。悬浮窗同步照旧：挑完提示音、或者添加 / 修改保存返回那一瞬间，新数据就推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "修正上一版埋的一个坑：提示音那一块的代码被误塞进了「标准模式收起」的 if 里，普通倒计时根本走不到「显示提示音」那一行，等于自定义倒计时的挑音按钮一直藏着。现在分界清清楚楚 —— 每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月这八种内置周期倒计时，编辑页那块「提示音」整块收起、卡片上也不冒换音按钮，归零照旧响默认提示音；你自己建的普通倒计时两边都照旧，编辑页两颗按钮、卡片上的提示音名称点一下直接唤起系统铃声选择器，选完当场存好。保存返回 / 挑完音都直接推给悬浮窗和锁屏。"),


    ChangelogItem("v1.0.0.38", "2026-10-06", "这一版把「挑提示音」按倒计时类型分了家：每分钟、每5分钟、每10分钟、每半小时、每小时、当日（当天）、每周、当月这些内置周期倒计时，编辑页里换提示音的那一整块（「用默认提示音（点一下选一个）」「清除自定义提示音」）直接收掉了，卡片按钮行上也不会再冒出那颗「提示音名称」——它们归零时照旧响那一声默认提示音，之前挑过的自定义提示音一并作废、统一回到默认。你自己建 / 改的普通倒计时不受影响：编辑页那两颗提示音按钮还在，卡片上「提示音名称」也照旧亮着，点一下直接唤起系统铃声选择器，选完当场存好，不用再进保存页。悬浮窗同步照旧：选完提示音、或者添加 / 修改保存返回那一瞬间，新数据就推给悬浮窗和锁屏。"),

    ChangelogItem("v1.0.0.38", "2026-10-06", "把「挑提示音」这一整个入口撤掉了：添加 / 修改倒计时页底下原来那两颗「用默认提示音（点一下选一个）」「清除自定义提示音」按钮连同系统铃声选择器一起删干净，列表卡片按钮行上那颗显示当前提示音名称的按钮也不见了，界面上再不会出现任何选提示音的按钮。倒计时的铃声本身没动 —— 归零时照旧响默认提示音，之前挑过的自定义提示音原样留在每条里，只是以后没法再改。悬浮窗同步照旧：保存返回这一下就把最新数据推给悬浮窗和锁屏。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "「主题颜色」这排最后一颗从蓝色换成金色（#E8B94A）：添加 / 修改倒计时页选颜色那七块现在是 青色 / 品红 / 绿色 / 黄色 / 橙色 / 红色 / 金色，新建倒计时的默认色还是头一颗青色。另外把「添加」「修改」倒计时保存返回这一下的悬浮窗同步再夯实一遍：不管是从主界面那颗加号、长按菜单还是悬浮窗里点编辑进去，保存落盘那一瞬间新数据就推给悬浮窗和锁屏，新窗当场建好、当场刷上最新的数字和颜色，不用等下一跳，也不再卡「有没有悬浮窗权限」那一关；上次那版如果新加的可见倒计时没立刻出窗，这轮补上了。每个倒计时自己挑过的旧颜色原样保留，不会因为这次换色就被改写。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "修掉一个「倒计时文字全都看不见」的 BUG：上一版把颜色换成琥珀时，Kotlin 里写成了 6 位十六进制（0xFFA63D）—— 少写最前面那一对 alpha，实际值变成 00FFA63D，就是全透明，等于根本没画，所以列表里每条倒计时的标题和那串大数字都只剩空壳。这回把全工程这类 6 位色值一次性补成 8 位：琥珀 #FFA63D、珊瑚 #FF7040 挑出来是实心可见的，更新日志里的日期行、吃药那几页的小字也一起看得清。功能没加也没删，就是让数字重新看得见。"),
    ChangelogItem("v1.0.0.38", "2026-10-06", "全站把那抹蓝去掉换成暖橙琥珀啦：倒计时数字、列表标题、卡片上的时间和小字、编辑页每项标题、「关于」页、更新日志里的日期行、吃药那几页的标题、锁屏时钟、启动图标的外描边，连同「玻璃按钮 / 开关轨道 / 下拉框菜单」那几道渐变，一起从青蓝 #00FFFF 换成暖橙 #FFA63D，收尾那档深色 #00C2D9 换成深橙 #C25E00；文字也从冷白 #FFEAF6FF 换成暖白 #FFF2E4D2，卡片和浮窗上那几路冷灰蓝小字（#B9C3DA / #A2ADCB / #C7D0E8）也一起转成暖灰，悬浮窗「收」「菜单」「透明度」「上一模式」「下一模式」「编辑」六颗按钮的浅蓝浅绿字换成浅橙浅黄绿，吃药那行浅青换成浅橙。编辑页「选颜色」的色板也去蓝了：原来三颗含蓝的「青色 / 品红 / 蓝色」换成「琥珀 #FFA63D / 珊瑚 #FF7040 / 棕色 #FF8B4513」，绿色、黄色、橙色、红色四颗本来就不带蓝、原样留着；新建倒计时的默认色跟着变琥珀，你自己挑过的颜色一个没动。深色墨蓝底没动，玻璃质感照旧。另一头是「加 / 改完立刻出窗」：添一条可见倒计时、或改完某条，保存返回这一下就把最新数据推给悬浮窗，新窗当场建好、当场刷上最新的数字和备注，不再等服务下一跳才冒出来；锁屏通知也跟着这一下一起刷。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "多选横幅中间加了「全选」「全不选」两颗：一点就把整排倒计时全勾上，横幅上的「已经挑了 N 个」跟着往上跳；再点「全不选」全部取消勾、自动退出多选。列表里只有一条的时候这两颗会自己收起来，不用点了。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「多选删除」时右上角那颗对勾之前还是看不清 —— 这回是真找着根了：Android 画一个容器时，先把所有抬升为 0 的子视图画完，再把抬升大于 0 的「整组」后画，而前景玻璃卡片带着 4dp 的抬升、勾选框自己没有抬升，所以哪怕它写在卡片后面一行，也只能透过半透明卡片看见一层模糊暗影 —— 非得把卡片左滑挪开才露得全。这版给勾选框也加上 6dp 抬升（高过卡片那 4dp），它才稳稳坐到最上层；顺手从小青圈放大成 34 的大圆，没勾的是深底 + 一圈纯白亮圈，勾上的是满不透明亮青实心圆 + 加粗白勾，一进多选整排都看得清清楚楚，再不用先左滑一下才找得到勾了哪几条。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「主界面默认排序」这颗按钮现在只在你真把内置顺序拖过之后才摆出来：没拖过时列表本来就是出厂顺序（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6），这时候点它也是白点，所以「＋」→「恢复内置」里干脆不摆这颗按钮、直接提示一句「小内置现在排的就是出厂顺序，不用复位」；只有你长按拖出过跟出厂不一样的顺序，「恢复内置」对话框里才会多出这颗「主界面默认排序」，点一下就把拖出来的顺序整个推倒回出厂顺序，还会弹一句告诉你现在是怎么排的、顺手同步到悬浮窗通知"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「回到默认排序」这颗按钮改名叫「主界面默认排序」了 —— 十个内置都乖乖在列表里时，它会同时出现在「＋」→「恢复内置」的对话框里和多选栏底下；点它就把你长按拖出来的顺序整个推倒、回到出厂顺序：每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6，还会弹一句告诉你现在是怎么排的，顺手同步到悬浮窗通知"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "对勾之所以看着又暗又不明显，根因是这个勾选框被盖在前景玻璃卡片底下（排在前面，只能透过一层半透明卡片看到模糊影子）——v122 把它挪到卡片上层，并给卡片内容让出右边一条，保证不压住标题；未勾的是暗色圆底 + 纯白亮青圈，勾上是满不透明亮青实心圆 + 加粗白勾。长按也调快到 300ms，拖动不会被外层滚动抢走。", obsolete = false),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「长按拖动排序」这次把判定改得直白多了：长按满之后不再跟时间抢 -- 手指接着往上下滑就是拖排序（到账那一刻会轻轻震一下,告诉你「可以拖了」),手指原地抬手才进「多选删除」,两条路再不会互相抢,手慢也拖得动。右上角那颗对勾又亮了一档:没勾的是淡青底 + 更亮更粗的青白圈,勾上的是满不透明的亮青实心圆 + 一道加粗白勾,暗处也一眼看得出勾了哪几条。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「长按拖动排序」这轮把最后一道坎也刨掉了 -- 以前长按满之后，手指只要往下挪过 12 像素，这下子就先被列表的「纵向滚动」认领走（顺手把长按也掐了），拖排序那条路一次都轮不到，看着仍是「自定义排序点了没反应」。现在长按满之后手指往上下走就直接判成「拖排序」并立刻接管，不跟列表滚动抢；想进「多选删除」就按住别动，半秒之后它自己亮出来。顺手把右上角那颗对勾也提亮了：勾上的是满不透明的亮青实心圆 + 一道加粗的白勾，没勾的是半透明深底 + 一圈更亮的青边，暗处也一眼看出勾了哪几条。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「长按拖动排序这轮把手速放宽了：长按满之后先留半秒宽限期，这段时间手指还在原地就进「多选删除」，手指一挪开就只认「拖排序」—— 长按名称照样能把内置项和我自己建的倒计时拖着摆，拖完的顺序单独记着、不顶掉出厂顺序，想复位就去「＋」→「恢复内置」→「回到默认排序」（十个都在时才给这颗按钮）。顺手把多选删除时右上角那颗对勾做大做显眼了：从 22 的小青圈换成 30 的大圆，没勾的是半透明深色底加一圈淡青描边，勾上的是实心青底加一道白勾，一眼就看得出勾了哪几条。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "上一轮加的多选删除和内置自定义排序，这轮把露出来的两个坑一并填了：多选删除以前一下勾好几条再收起会闪退 —— 收的时候边遍历名单边删同一份列表，撞上了就弹回桌面；现在先照一张名单再挨个收，勾几条都稳，内置的照样存成快照、「找回小内置」原样捞得回来。更那个容易踩的是排序：长按之后手指慢慢往上下滑，会被「进多选」那半截抢先触发，拖动排序反而轮不到，看着就像自定义排序没反应 —— 现在手指一挪开就只认「拖排序」，「按住不动」才进多选，两条路各归各位，长按名称还是照旧能拖。「回到默认排序」这颗按钮也不再闷声干活了：点完会弹一句告诉你现在小内置排成什么样，顺手同步到悬浮窗通知；出厂顺序（每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6）跟你拖出来的自定义顺序各存各的，怎么拖都不串味、随时能一键复位。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「每半小时倒计时」的「标准模式」这轮换成了「分秒模式」—— 只显示 xx分xx秒，前面那截恒为 0 的「时」去掉了。顺手把显示模式做了一次大扫除：任何一项里，只要某一档显示出来的样子跟「标准模式」一模一样，这一档就自动删掉、只留标准模式（比如每半小时那个「分秒模式」现在跟标准写法重了，就不再单独摆出来，选项、下拉框、按钮都跟着收）。哪一项整体只剩一种显示方式，切换功能就整个收起来 —— 卡片上的「显示模式」按钮、悬浮窗抽屉里那两颗「上一个 / 下一个显示模式」、编辑页的「标准模式」下拉框全都不再出现，不会再留一颗点下去没反应的按钮。长按主界面上任意一条倒计时：按住不动就进「多选删除」（顶部亮出一条横幅，每行右上角出现勾选框，点一下这一行就是勾上 / 取消，勾好几个再点「收起选中」一次收掉，内置项照样存成快照，「找回小内置」能原样捞回来）；长按之后手指接着往上下滑，还是照旧那个拖动排序，松手即保存。内置倒计时的出厂顺序找回来了：每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 → 当月 → 华都云境悦府 → GTA6，新装或刚补齐的内置项就按这个排；想自己排，长按名称拖一拖就行，拖出来的顺序会单独记住，不会把出厂顺序顶掉，想复位就去「＋」→「恢复内置」，十个都在的时候会多给一颗「回到默认排序」，点一下就回到出厂顺序。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "「玻璃透明度」这根滑块以后只淡化玻璃底了 —— 以前拖它是整扇悬浮窗一起变淡，连中间那几个跳秒的数字也跟着被压暗，调到最透明时数字糊得根本看不清。这轮改成只把卡片、抽屉面板、按钮这些玻璃背景调淡 / 调实，标题、备注、目标时间还有倒计时数字始终是全亮清晰的，数字该跳的动画一个没少。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "上一轮给短周期内置项加的「分秒模式」（只显示 x分x秒）这轮从「每分钟倒计时」这里撤了 —— 它在一分钟里只能写成 0分30秒 这种，看着别扭，下拉框、主界面的模式按钮、悬浮窗、锁屏通知都不再出现这档；「每5分钟 / 每10分钟」和「每小时 / 每半小时」照旧能挑到「分秒模式」，没受影响。已经设成分秒模式的老数据会自动退到「秒模式」，看起来的样子跟以前一样；悬浮窗那行模式名还是每秒跟着刷，切档立刻就变，不用重启。"),
    ChangelogItem("v1.0.0.38", "2026-10-05", "显示模式又动了一轮，还是围着那几个短周期的内置项：新加了一档「分秒模式」（只显示 xx分xx秒，把前面那截恒 0 的「时」去掉）。「每分钟 / 每5分钟 / 每10分钟」这三个现在都能挑到「分秒模式」了（每分钟那个多了这档之后可以写成 0分30秒 这样，5 / 10 分钟那个直接是 x分x秒）；「每小时 / 每半小时」原来那档「时分秒模式」（x时x分x秒，前面恒 0 的时没意义）这轮换成了「分秒模式」，下拉框、主界面的模式按钮、悬浮窗跟锁屏通知看到的是同一份列表；悬浮窗顶部那行模式名这轮也改成跟着每秒一起刷，切了模式不会停在旧写法。其余倒计时的档位一个没动，只有新加的这档在八档全开的地方（普通 / 每周 / 每月那种）也顺带多出来一行."),
    ChangelogItem("v1.0.0.38", "2026-10-04", "悬浮窗里那颗按钮改叫「关闭悬浮窗」了 -- 它点下去是干脆把悬浮窗整个关掉（跟标题栏最右边那颗「关闭」完全一样的作用），以前写的「收起悬浮窗」容易让人误会成只是把它缩回小图标；主界面列表卡片上那颗按钮这轮没动，说法保持原样。倒计时数字、目标时间、显示模式这些功能一个字没改，退到后台照样跟着跳秒刷新."),
    ChangelogItem("v1.0.0.38", "2026-10-04", "悬浮窗和列表里的按钮说法这次都改清楚啦：抽屉里那两颗按钮由「上一模式 / 下一模式」改成「上一个显示模式 / 下一个显示模式」，列表卡片上那颗「模式」也写成「显示模式」，叫法跟下拉框里的一一对上；「展开 / 收起」统一说成「展开悬浮窗 / 收起悬浮窗」，不会再跟锁屏通知、更新日志里的展开弄混 —— 悬浮窗里那行目标时间照旧跟着跳秒一起刷新，跨了整点 / 半点 / 整分会自动换成下一个目标时刻，退到后台也不停在那儿。"),
    ChangelogItem("v1.0.0.38", "2026-10-04", "悬浮窗抽屉里的「目标:」以前只在开抽屉那一刻算一次，退到后台、跨过整点或日期之后就停在旧值（倒计时数字照样在跳，看着像目标时间没同步）—— 现在它跟跳秒一起刷新，内置项跨到下一个周期（下一个整点 / 半点 / 整分 / 次日零点）自动换成新的目标时刻，退到后台不动也跟着变，悬浮窗与主界面、锁屏通知看到的始终是同一个时刻。"),
    ChangelogItem("v1.0.0.38", "2026-10-04", "把上一轮新加的「每分钟倒计时」「每5分钟倒计时」「每10分钟倒计时」的显示模式再收一收：「时分秒模式」从可选里拿掉了（前面那截「时」恒为 0，摆出来没意义），「每分钟倒计时」顺带把「分钟模式」也一并拿掉 —— 现在每分钟那个只剩「标准 / 秒」两档（标准模式就是只显示 x 秒），每5分钟和每10分钟那两个剩「标准 / 分钟 / 秒」（标准模式就是只显示 x 分）；下拉框、主界面的模式按钮、悬浮窗看的都是同一份可选列表，三处完全一致。其余倒计时的显示模式一个没动。"),
    ChangelogItem("v1.0.0.38", "2026-10-04", "新增「每分钟倒计时」「每5分钟倒计时」「每10分钟倒计时」三个内置项，跟每小时倒计时一个模子：目标时刻是系统自己往下跳的 —— 分别跳到下一个整分、下一个 5 分、下一个 10 分边界（每5分钟就是 :05 / :10 / :15…，每10分钟就是 :10 / :20 / …），到点归零接着往下跳，不用你管；能改的东西也一模一样：名字、颜色、显示模式、跳秒动画、提示音、备注都能照常改，改完它还是内置倒计时。风格跟每小时严格对齐：显示模式上不摆「小时模式 / 天数模式 / 天时分秒模式」（前两档恒为 0 没有意义），标准模式直接就是「x时x分x秒」，下拉框里能切到 标准 / 分钟 / 秒 / 时分秒；备注也跟着变，会写「距离18点05分结束」「距离18点20分结束」「距离18点31分结束」这样的具体时刻，正好落在整点时退成跟每小时那句一样的「距离18点整结束」。位置排在「每半小时倒计时」下面一位（每小时 → 每半小时 → 每10分钟 → 每5分钟 → 每分钟），一眼看得出长短；悬浮窗、锁屏通知跟主界面共用同一份数据和同一个刷新节奏，三处完全同步，跳秒也不会错开半拍。"),
    ChangelogItem("v1.0.0.38", "2026-10-04", "把「当月倒计时」「华都云境悦府倒计时」「GTA6 倒计时」这三条的显示模式，从原来的「标准模式」（x周x天x时x分x秒）改成了「天时分秒模式」（x天x时x分x秒）—— 前面那截「周」去掉了，一眼扫过去剩下的天数最直接。其余倒计时的显示模式一个没动：当日、每周、每小时、每半小时、以及自己新建的通用倒计时，标准模式还是原来的样子。悬浮窗同步跟着改，这三条在悬浮窗里和列表里完全一致；编辑页的模式下拉框照旧能手动切到任何一档，老数据（还停在标准模式的）一打开就自动升级到天时分秒，不用手动改。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "倒计时卡片的背景调透了一点 —— 压在卡片上的那层白玻璃和深色底都降了透明度，背后的模糊光斑能隐约透上来，整体看着更通透、没那么“糊”。左滑露出来的“编辑 / 删除”照旧被卡片挡住，不会跟倒计时文字叠在一起。这轮颜色没动，还是上几轮还原回来的那抹青蓝，功能也一个字没改。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "主题颜色又换回原来那抹青蓝啦～上一轮改成暖橙琥珀，现在按你说的原样还原：全站强调色回到 #00FFFF，收尾那档深色回到 #00C2D9。列表顶上的标题、卡片上的时间和小字、编辑页每项标题、「关于」页、更新日志里的日期行、吃药那几页的标题、锁屏时钟、启动图标的外描边，连同「玻璃按钮 / 开关轨道 / 下拉框菜单」那几道渐变，全都跟着变回青蓝；高光那层还是原来的白色叠加，玻璃质感没变。悬浮窗也一并按原来的配色同步过来了：「收」「菜单」「透明度」「上一模式」「下一模式」「编辑」这六颗按钮的浅蓝浅绿字（#9FD8FF / #7FDBFF / #66FF99），吃药页那行浅青（#4DFBFF）也一起还原。「选颜色」那排第一块重新变回青色、名字也叫「青色」；新建倒计时的默认色、恢复内置时原样还原的默认色同样回到青色。每个倒计时挑过的自定义颜色一个没动，深色墨蓝渐变底也没动 —— 这一轮只是把强调色从橙换回蓝，功能依旧一个没改。顺手把上一版「倒计时文字全都看不见」的坑也填了：那轮改色时有几处 Kotlin 里的色值只写了 6 位十六进制，最前面那对 alpha 丢了（0xFFA63D 其实是 00FFA63D，等于全透明），列表里每条倒计时的标题和那串大数字就成了空壳 —— 这轮换色时把全工程这类色值统一补齐成 8 位，每个倒计时自己挑的自定义颜色也照着补了 alpha，数字重新看得见。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "主题颜色换成暖橙琥珀啦～这一轮动的只是“颜色”，功能一个没改。以前全站的强调色是那抹青蓝（#FFA63D）：列表顶上的标题、卡片上的时间和小字、编辑页每项标题、「关于」页、更新日志里的日期行、吃药那几页的标题、锁屏时钟、启动图标的外描边，全都是它；玻璃按钮、开关轨道、下拉框菜单那几道渐变（中间那档 + 收尾那档深一档的颜色）也是从青渐变过来的。现在统一换成暖橙琥珀 #FFA63D，收尾那档跟着换成偏深的琥珀 #C26A00，高光那层还是原来的白色叠加，所以亮面没变浑、玻璃质感照旧。悬浮窗这儿一并同步过来了：「收」「菜单」「透明度」「上一模式」「下一模式」「编辑」这六颗按钮原先印着浅蓝字（浅蓝 / 更浅的蓝 / 剩时间那行是浅绿），现在全改成跟主色一路的浅橙（#FFCFA0 / #FFB877 / #FFD9A0），吃药页那行浅青也一样跟着走。顺便把颜色取用默认的那几处也换掉了：新建倒计时的默认色、恢复内置时原样还原的默认色、“选颜色”那排第一个色块（原来第一块是青色，现在第一块是琥珀，名字也改成“琥珀”），都是同一个橙；但每个倒计时挑的自定义颜色照样各是各的、互不干扰，你选过的那些条目的颜色不会被这一轮改掉。深色的背景一层完全没动 —— 还是原来那套墨蓝渐变底，变的只是压在上面的那层强调色，所以整体还是那个熟悉的深色玻璃样子，只是从“蓝”变成了“橙”。", obsolete = true),
    ChangelogItem("v1.0.0.38", "2026-10-03", "更新日志再回头扫了一遍 —— 凡是跟「授权码」「意见反馈」「附带运行日志」这三类沾边的记录，一条不落地全在前面顶上 ✗ 整条划掉：授权码那几条讲邮箱、SMTP、465 / 587、公共通道的，「意见反馈」整块（反馈页、发信那套、附件共享器），「附带运行日志」挂邮件附件进邮箱的那几条，加起来一共 15 条，说的全是早拿掉的老功能 —— 现在想反馈就去「关于」页底下那个 GitHub 项目主页链接（仓库里开个 Issue 就行），运行日志也不再随反馈打包。没划掉的照旧能看、能照着用，本轮一个功能没动。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "更新日志里后来整个撤掉的功能，条目这回都标清楚啦：凡是前面带 ✗ 并且整条划掉的，说的都是已经拿掉的老功能 —— 比如「意见反馈」整块、左滑操作层里那颗「提示音」按钮、编辑页原来那个「显示模式」下拉框；划掉它，是让你一眼看出「这条为啥点不着」，免得照着老条目满屏幕找入口。功能本身早就换成别的样子了：挑提示音就在卡片按钮行上那颗「提示音名称」，编辑页现在只留一个「标准模式」框，反馈的去向是「关于」页底下那个 GitHub 项目主页链接（有想法去仓库里开个 Issue 就行）。剩下的没划掉的照旧能看、能照着用。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "左滑动画改成了「按钮淡入 + 倒计时淡出」这套交叉淡变：以前往左推，底下的「编辑 / 删除」冒出来、上面那块倒计时只是轻轻放大一点，看着像两层叠着没挪窝；现在手指往左推多少，两颗按钮就淡入多少、从右边滑出来，同时上面那块倒计时（标题、时间、按钮行）整片跟着淡掉，推到头几乎全透明，像把卡片掀开露出底下另一层；往右滑收回正好反过来 —— 倒计时一点点淡回来、两颗按钮倒着淡出，收完稳稳落位不会突然闪一下。两头的回弹和时长都没变（展开 280 毫秒、收回 240 毫秒），甩得特别快的手势依旧会被拽住，上手拖动也还是立刻交回手指接着当前进度走。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "左滑 / 右滑动画这次做明显啦（上一版有，但幅度太小一眨眼就过去了）：现在手指推多少，两颗按钮连同整块操作面板就跟着淡入并从右边滑出来，编辑先出场、删除稍跟上，各自带一点横向位移、从小放大回原位，前景卡片同时轻微放大做出被推开的层次感；松手吸到位那一下还是轻轻的橡皮筋回弹，展开用 280 毫秒、收回 240 毫秒稳稳减速就位。顺手修了三处会让动画看着像「没生效」的毛病：一是刚按下手指时露出门的宽度还没量到，跟手阶段会被整个跳过、松手也直接落位不做动画（现在每次用到都先兜底量一次，量不到就按两颗按钮的宽度估）；二是甩得特别快时进度已经到头，动画等于原地空转一帧（现在即使这样也确保按钮停在展开态，不会卡在半透明）；三是掐动画那下顺序不对，上一轮的终态会盖到刚恢复跟手的手指上（现在先摘引用再取消）。再上手、进拖动排序都会立刻交回手指接着当前进度走，不会跟手指打架。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "左滑动画 / 右滑动画都补上啦：以前卡片往左推的时候，底下的「编辑 / 删除」是一下子整层冒出来，松手吸附也只有一段干巴巴的直线位移；现在手指推多少，两颗按钮就淡入多少（编辑先出场、删除稍晚一点，各自带一点横向位移和轻微缩放，展开时像依次弹出来），松手吸到位的那一下还有个轻轻回弹的橡皮筋感，收得特别快的手势也能被拽住；往右滑收回时按钮按同样的顺序倒着淡回去、稳稳减速就位，也就是不再往右冒出来；拖动过程中如果又上手了，动画会立刻交回手指、接着当前进度走，不会打架。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "~~左滑操作层里的「提示音」按钮撤掉啦：以前把卡片往左一滑，底下会露出「编辑 / 提示音 / 删除」三颗，现在只剩「编辑 / 删除」两颗，摆得更松快，也少一个手指常点错的地方；换提示音不用再往左滑找了 —— 就在卡片按钮行上那颗「提示音名称」，点一下照样打开系统铃声选择器，原来那条左滑露出的入口连带按钮、绑定、监听一起删干净，挑提示音、存盘的规矩都没变。~~另外修了一处别扭的手感：从「编辑 / 删除」点开的那道确认提示框，只要点了取消（内置项那两句说明就是「先不了」／「先留着」，普通的删除框就是「先留着」，按返回、点框外也算），卡片会自己顺手滑回原位，不再敞着操作层晾在那儿，下次想滑开还得先往回拨一下。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "「显示模式」那个下拉框连同跟它联动的那套代码一起拿掉了，编辑页现在只留一个模式下拉框，就是原来摆在它上面的「标准模式」（选项、玻璃样式、能反复改都没有变化），只是从今往后它一个人说了算：在这儿选好，这个倒计时的显示方式就定下来，存盘的还是你选的那一档；以后想换成别的，回到编辑页随手再选一次就行，没有次数限制。底下那句小字也跟着改成「选好之后这个倒计时的显示方式就定啦，以后随时能回来改，没有次数限制」。列表卡片上的「模式」按钮、悬浮窗的循环切换、内置项按周期自动收档这些老规矩一点没变，只是编辑页里不再有两个一模一样的框摆着、谁说了算再也不会犯迷糊啦～, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-03", "编辑页加了「标准模式」下拉框，就摆在「显示模式」上面：它的选项跟显示模式完全一样（按这个倒计时的类型自动收掉装不下的那几档），选好之后下面的「显示模式」立刻跟着走到同一档；反过来在「显示模式」里选，上面的「标准模式」也会同步过去。两个框都能反复改，没有次数限制，最后存盘的还是「显示模式」选中的那一份；两个框用的都是跟颜色、动画那几个下拉框一样的玻璃样式。另外按周期把装不合理的模式档收掉了：每小时倒计时和每半小时倒计时（最多 1 小时）不再出现「小时模式」（恒显示 0 时）、「天时分秒模式」和「天时分模式」（前面那截恒 0 天），只剩 标准 / 分钟 / 秒 / 时分秒；当日倒计时（最多 24 小时）去掉「天时分秒模式」和「天时分模式」，「小时模式」留着，它能实实在在显示 x 时。已经设成这些模式的旧数据会自动退回标准模式，列表、悬浮窗、模式循环切换都跟着一起变。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-03", "新增「每半小时倒计时」，内置项凑成七个～它跟每小时倒计时一个模子：目标时刻是系统自己往下跳的 ——分钟不到 30 就走到本小时的 30 分，过了 30 分就直接跳下一个整点；备注也跟着变（半点前写「距离17点半结束」，过了半点自动变成「距离18点整结束」，跟每小时那条一字不差）；显示模式里同样不给它「天数」这一档，位置排在「每小时倒计时」下面一位，一眼能看见。另外把内置倒计时改 / 删的规矩定死了：以前改一改目标时刻就会把它降级成普通倒计时，现在不会了 ——内置项改完了还是内置倒计时。时间那栏填了也不存（时刻本来就是系统往下跳的，存了也被下一帧盖掉，索性不存，免得白改一场），想改的别的照旧：名字、颜色、显示模式、跳秒动画、提示音、备注。改之前和收起之前都会先弹一句说明（跟「关于」页、更新提示同一个玻璃框），点「继续」才往下走；收起的内置项还是能在「＋」菜单里点「恢复内置」找回来，颜色、模式、动画、提示音原样还原。"),
    ChangelogItem("v1.0.0.38", "2026-10-03", "「意见反馈」这个功能整个拿掉咯～ 这条反馈路从第一版一路做到现在，始终绕不开那几件事：授权码得先去邮箱里开一个 16 位的开关、各家邮箱的 SMTP 脾气不一样总有一家连不上、免授权码的公共通道在手机上又基本送不到 —— 修一圈不如算了，这版索性把「意见反馈」整块拆干净：反馈页、「意见反馈」按钮、发信那套（SMTP 与 HTTP 两条路一起）、附件共享器、清单里专门为它开的明文放行，全都不留；「关于」页底下原来放反馈按钮的位置，换成去 GitHub 项目主页的链接，有想法的话欢迎去仓库里开个 Issue 跟我说一声～ 别的功能一点没动，倒计时、悬浮窗、吃药提醒、锁屏常亮照旧。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "填了授权码还是发不出去？这回收拾的是「卡住不动」和「越点越不行」这两处硬伤。一是连接根本没有超时：SSL 那口 465 走的是无超时的 connect，手机上网络不通时不是报错、而是无限干等，看着就是「点了提交半天没动静」；现在 20 秒连不上就换下一条路（465 与 587 双端口挨个试），读写超时也从 60 秒缩到 25 秒，等不了那么久。二是认证一失败还要再撞一遍：服务器明明已经回「535 账号异常 / 密码错 / 登录太频繁」，代码却因为 PLAIN 也通告过，又拿同一个错码补发了一次 AUTH PLAIN —— 一次填错换来两条失败记录，像 QQ 那种会把登录失败记下来限流的服务器，越点越进不去；现在 5xx 这类硬伤一律不再重试，并且把服务器那句原文里的「登录频率受限」单独翻成人话告诉你。顺带修的几处：Android 9 起默认拦明文连接，而 587 那档开头几步是明文的，清单里补了放行；只是「没连上」时不再去撞那条注定失败的免授权码通道，直接把写好的信交到邮箱应用里一键发出去，不让你白等一场；失败框里也写透了「填的是授权码不是登录密码」和「别反复点，会被临时限流」。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "填了授权码还是发不出去？这回 SMTP 那几条路都捋直了。上一版有两个坑：一是 587 那档（Gmail / Outlook 等）升级加密的做法不对——STARTTLS 之后又另开了一条 TCP 去做握手，新连接上服务器照旧先回一句明文的「220」再要 EHLO，App 却还在等 EHLO 的回话，硬生生干等到超时，看着就是「点了半天没动静」；二是登录只认一种方式，各家邮箱脾性不一样，一被拒就整条路断掉。这版把明文连接就地升级成加密、握手后先把服务器那句 greeting 接住再 EHLO，登录改成优先挑服务器愿意的那一种（AUTH LOGIN 之外还认 PLAIN / CRAM-MD5），认不出的域名也会老老实实按 smtp.域名 + 端口去试，不再一上来就判失败。另外填了授权码以后点提交，**先走你自己那条路**再试免授权码的公共通道，不用再干等；反馈页也多了个「测一下能不能发」，发之前先探一探服务器肯不肯收、卡在第几步照实说给你听（是连不上、是授权码不对、还是服务器根本不放行），不用靠猜。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "修掉反馈「假发送成功」的问题。上一版只要网页回了 200 就算发出去了，可免费收件通道的回包里 success 的值是 false —— 里面正好也有 success 这四个字母，于是明明没送出去，屏幕上还是弹「反馈已发送，请耐心等待回复！」，收件箱却干干净净。这版改成只看回包里 success 的值：是 true 才算送到，回的是网页就一律当没送到，并且把站点给的原因翻译成中文摆到对话框里，不再替它编理由。配套的还有反馈页那行状态说明，也改成实话：免授权码那个公共通道在手机上发不出去（它只认网页表单），想后台直接发还是填你自己的邮箱 + 授权码；不填就点提交接着走邮箱应用，两条路都写清楚了。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "这版反馈**不用授权码也能直接发**啦～上一版点「提交反馈」要先填自己的邮箱、再填一串 16 位授权码，光是找那个开关就劝退了不少人。这版换了个更省事的路子：反馈页下面那两行（你的邮箱 + 授权码）降级成**备用通道**，平时什么都不用填，点「提交反馈」直接在后台把信投到 baigao110@qq.com —— 不打开邮箱应用，附件（运行日志 / 图片 / 视频）照旧跟着一起过去，屏幕上同步显示「反馈已发送，请耐心等待回复！」。万一默认这条通道当时网络不好没通上，会自动接着用你填的邮箱（授权码）那条路再发一次；两条都不行，才退到「打开邮箱应用发」，内容一模一样。实现还是零第三方依赖：新加的 HttpMailSender 用 HttpURLConnection 手工拼 multipart 投出去，你这边不装任何东西、也不用注册账号。, obsolete = true"),


    ChangelogItem("v1.0.0.38", "2026-10-02", "意见反馈这回点一下就发完，不用再被拽去邮箱应用咯～ 以前点「提交反馈」会打开你手机上那个邮箱 App，收件人、标题、正文、附件都替你备好了，可你还得自己再点一下「发送」，一不留神还容易被劝退。这版在反馈页下面加了「直接发送设置」两行：填上你的邮箱，再填上邮箱授权码（QQ 邮箱在「设置 → 账户 → 开启 IMAP / SMTP 服务」里会给你一串 16 位的码，那才是要填的，登录密码填这儿发不出去），在这一台手机上填一次就一直记着。以后点「提交反馈」，App 直接自己在后台把信发到 baigao110@qq.com —— 图片、视频、「附带运行日志」都原样打包过去，全程不离开本应用，屏幕上立刻弹一句「反馈已发送，请耐心等待回复！」，可以安心干别的去啦。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「附带运行日志」这回真成邮件附件啦～ 上一版我图省事，只勾日志的时候干脆不给附件、把全文贴在正文里，结果你反馈「日志加不进邮件附件」——这版把它重新挂回去，fb_log.txt 妥妥进撰写页的附件栏。做法也不再死盯一种类型：各家邮箱脾气不一样，同一个 txt，给文本类型它当你是「转发一段文字」（正文抄进去、附件直接丢），给通配类型它又不认这是封带附件的信，mailto 直投更是干脆无视附件。所以这版把「分享一个文件」「分享一段文字」「mailto 直投」三种样子按顺序摆出来挨个试：谁在这台手机上真接得住、真把 txt 挂进撰写页就用谁，走到哪一步就照实告诉你哪一步（是「日志已经挂上附件」还是「全文贴在正文」）；你选了图片 / 视频的时候，走的还是原来那条把图片视频稳稳挂上来的老路。顺手也收拾干净了：附件真挂上时正文只留一句说明，不把同一份日志抄两遍看着闹心；万一哪一家邮箱还是不认这个 txt 附件，同一份全文照样贴在正文里，信息一分不少，你也不用再回头自己挑一次图片或视频～, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」这回总算不让你再挑一次图片或视频了～前面把「具体文本类型」和「通配类型」两条路都走了一遍，进邮箱还是空空的、得你自己再添一次。回头看也就想明白了：一个 txt 附件，偏偏是各家邮箱最难肯挂上去的东西——给文本类型，它当你是「转发一段文字」，正文照抄进去、附件直接丢；给通配类型，它又不认这是封「带附件的信」，同样不搭理。这版干脆不跟它较劲：只勾运行日志的时候，把日志全文整整齐齐贴在邮件正文里，再用各家邮箱都老老实实接的 mailto 直投这条路打开邮箱（收件人已经填好、正文也写好了），一个附件都不带过去。你进邮箱看到的就是一封写好的邮件，没有那个挂不上来的空附件卡着，自然也就不会再让你回头挑一次图片或视频；没勾日志、或者日志和图片 / 视频一起发的时候，走的还是原来那条把图片视频稳稳挂上去的正规路，一点没变。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」进邮箱还是空的、要你自己再挑一次图片或视频，可只挑图片 / 视频的时候偏偏好好的？上一版虽然不再偷偷退回短路，可断点没堵干净：只勾日志的时候，分享出去的那份 Intent 给的是具体文本类型，邮箱一看就把它当成「转发一段文字」——正文照抄进去，附件那一份直接不理，撰写页照样空着，于是又只剩下你自己回头挑一次图片或视频。这版把分支选对：附件里只要全是纯文本（也就是只勾了运行日志），就先按「分享一个文件」的套路去开邮箱，走的正是各家邮箱认的「收附件」那条正规路，日志妥妥挂上；万一这家邮箱只认文本那一路，就再按文本的路开一次；两条都不成才逐级退到 mailto 直投、分享列表，最后退到复制内容备用。另外不管走到哪一步，运行日志都会同时贴在正文里，所以「不用再回头自己挑一次」这条底线这回是稳稳守住了～, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」进邮箱还是空的、要你自己再挑一次图片或视频，可只挑图片 / 视频的时候偏偏好好的？这回断点终于找着了：提交前有一步「先看看有没有应用接得住这份分享」的判断，可那个查法只认自己声明了 DEFAULT 的入口，而邮箱的撰写页偏偏没给「文本类」这种分享声明 DEFAULT（它盯的是邮件入口、图片、视频），于是只勾日志的时候，查出来的是微信、QQ、蓝牙那批「分享一段文字」的应用，邮箱压根不在表里，被判定成没人接，就悄悄退到了 mailto 直投那条短路，而 mailto 路上的附件正是各家邮箱不看的东西，进邮箱当然空空的。这版改成不靠「先猜有没有人接」，直接去开，接不上它自己会报错、我们才退下一层；提前把附件读取权限交给哪些应用的查法也放宽了，不再只把权限交给微信那几家。另外，运行日志这一份内容会同时贴进邮件正文：就算某家邮箱还是不肯把纯文本附件挂上去，这封反馈里也写得满满当当，你不用再回头自己挑一次图片或视频。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」反馈进了邮箱，附件栏却还是空的、要你再自己挑一次图片或视频～这回的毛病正好只在日志这一条路上，两个真断点一起修掉。第一，运行日志是个 txt，可附件共享器里 txt 压根没排在类型表里，日志被判成通用二进制文件，分享出去的 Intent 类型也就跟着掉进通配类；而国产邮箱只认 image/*、video/*、text/plain 这种写死的类型，看到通配类就当你是「分享个普通文件」而不是「写一封带附件的信」，撰写页照样打开、附件栏照样空。这版把 txt / log / json / csv 这些文本类型补进表里认成 text/plain；分享时的类型也不再轻易给通配——全是日志就给 text/plain，图片混日志就按图片给 image/*，视频混日志就给 video/*，保证交到邮箱手上的永远是一个它认得的写死类型。第二更隐蔽：写运行日志是把一整段包在一个兜底里的，读倒计时列表、读运行日志、读那几个开关任何一小步没成，整份日志就作废，而提交那边会把它当没附件安安静静跳过——你点提交进邮箱，信是开了、附件没了，就只剩让你自选图这一条路。这版把日志拆成几段各自兜底，哪一段读不出来就空那一段，日志本体一定还在、一定发得出去；万一实在写不出来，也会明明白白提示你先把勾去掉再提交，不再偷偷少发一样东西。, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「反馈附件在邮箱里要再选一次」这版继续往根上挖，两个真毛病一起修掉了～第一，附件递过去的时候「扩展名和真实内容对不上」：相册 / 微信 / QQ 转手过来的图，名义后缀可能是 heic、jfif，甚至干脆没有后缀，邮箱是靠扩展名和类型判断自己认不认得这个附件的，认不出就当这封信没有附件，于是你只能在邮箱里自己再添一次图片或视频。这版提交前先把附件全部整理一遍——按文件头（图片头、视频头）判断真实类型，再存成 jpg、png、webp、heic、mp4、mov、webm 这些标准名字，邮箱一眼就认得。第二，之前直接 startActivity 唤起邮箱，常常唤起来的是它上次没写完、还挂在后台的旧写信页，旧草稿上当然没有这次的附件；这版打开邮箱时带上「开新写信页」的标记，每次都是干干净净的新一页。另外提交前的自检也真刀真枪了：会顺着附件的通路真的读一遍，读不出来的附件直接剔掉并告诉你是哪个，不再塞一个空附件进去让你自己去补；扩展名一时认不出来的，也交给系统 MIME 表兜底，不会变成什么都不是的类型。还是没装邮箱的机型，依次退到分享列表、再到「复制内容备用」粘微信 / QQ 发，照样收得到～, obsolete = true"),

    ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」的附件这次走最稳的那条路送进邮箱啦～上一版虽然是把附件递过去了，可走的是「ActionSendTo」（mailto 直投）这条短路：Gmail 和不少手机自带邮箱压根不认这条路上的附件，它们只在「ActionSend」（收件人用 EXTRA_EMAIL 填好）这条正规路上把图片 / 视频挂进撰写页，所以你一进邮箱看到的是空空的附件栏，还得自己再添一次图片或视频。这版把顺序换了：优先开「ActionSend + 收件人已填好」，邮箱撰写页一打开，图片 / 视频 / 运行日志就都整整齐齐挂在上面，点一下发送就到啦；万一你这台手机上这份 Intent 没人接（没装邮箱），才退回 mailto 那条路；再不行给一个分享列表让你挑一个能收附件的发；最后还是不行就把正文复制到剪贴板，粘到微信 / QQ 发给我一样收得到。顺手也把入口看门加严了：这手机上没人接的 Intent 就不会硬着头皮去开，不会点一下提交就闪一下又退回我们自己的页面。, obsolete = true"),

        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」带图片 / 视频进邮箱这次真的带上了～以前反馈里选好图片、视频，一点提交跳进邮箱，附件栏却是空的，还得你自己再点一下「添加附件」重挑一次图片或视频——根子出在分享给邮箱的那份 Intent「没把附件喂明白」：（一）Intent 没带类型，不少邮箱是靠它判断自己接不接得住图片 / 视频，没类型它就当这封信没有附件；（二）附件是以「一串附件列表」的形式递过去的，只认单个附件的邮箱取不到东西，同样当没附件；（三）读取权限只在剪贴板数据上自动发过，光给附件列表的话，邮箱回头异步去取时常常还没权限可取，读不到也就当没附件。这版三处一起补齐：按内容自动认类型——清一色图片就是 image/*、清一色视频就是 video/*、混着日志就是 */*；只有一个附件就单发一个、好几种附件就同时发列表和剪贴板数据；再提前把读取权限挨个交给手机上能收这封信的邮箱应用，它什么时候去取都读得到。提交前还会先自检一遍：每个附件都读得出来、大小正常才会发，读不出的当场提示「先把它撤掉再提交」，不再让你白跑一趟。提交成功的那句提示也写明白了，会告诉你这次一共带上了几个附件（图片 / 视频 / 日志）。没装邮箱时的老路子照旧：「复制内容备用」把内容复制到剪贴板，粘到微信 / QQ 发给我一样收得到～, obsolete = true"),

        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」来啦，有话直接跟我说～「关于」页最下面现在有个「意见反馈」按钮，点进去就能给我写信：昵称和联系方式都可以留空，把问题现象写两句就行（方便的话填上，我好回复你）；还能一起加图片或视频，最多 9 个，已经选好的会排成一排小胶囊，点一下就撤掉。想让我更快找到原因就把下面的「附带运行日志」勾上，它会把机型、系统版本、应用版本、当前倒计时列表和那几个常亮开关的状态打包成一个 txt 一起发过来（不含你的备注内容）。点「提交反馈」会打开你手机上的邮箱应用，收件人已经填好是 baigao110@qq.com，标题正文附件都备齐了，你只要点一下发送就到啦～ 手机上没装邮箱的话，下面那个「复制内容备用」能把内容复制到剪贴板，粘到微信 / QQ 发给我，照样收得到。整个反馈页的样式跟「关于」页一模一样：同一个玻璃底、同一个输入框底板、同一个青色胶囊按钮，看着就熟～, obsolete = true"),

        ChangelogItem("v1.0.0.38", "2026-10-02", "「锁屏通知常亮」这次是真的接上了～ 前面两版都不灵，根子在系统的硬规矩：FLAG_KEEP_SCREEN_ON 只能在 Activity 里设，服务 / 悬浮窗设了不算，而且应用一退到后台系统照常让你熄屏；Android 12 往后，后台应用手里的唤醒锁还会被系统直接收走——所以「悬浮小窗」「屏幕级唤醒锁」两条路都没戏。这版换成正解：锁屏之上挂一个全透明的常亮页（系统闹钟能在锁屏上一直亮着，用的就是这一手），它只做一件事——把屏幕按住不睡；它不抢焦点、不拦触摸，锁屏照常能划、通知照常能点，你要解锁时摸到它身上它会立刻退开，绝不会把你挡在门外。屏幕一点亮、解锁、开关变化都会立刻接上或收掉，「关于」页那行状态也会实时告诉你「常亮已接上 / 还没接上」。第一次打开开关时会引导你给「显示在其他应用上层」权限（系统闹钟也是靠它），给上就生效～"),
        ChangelogItem("v1.0.0.38", "2026-10-01",
            "「锁屏通知常亮」这版对症修好啦～ 上一版只在屏幕亮着的时候接常亮，而且靠的是「透明小窗 + FLAG_KEEP_SCREEN_ON」，可锁屏状态下系统压根不让第三方 App 挂悬浮窗，小窗挂不上、退路的唤醒锁又只保 CPU 不保屏幕——所以锁屏之后到点照样熄屏。这版换成「屏幕级唤醒锁」（SCREEN_BRIGHT_WAKE_LOCK，只需本来就有的 WAKE_LOCK 权限）：它是系统电源层直接按住屏幕，不经过窗口层，锁屏界面照样有效；再叠加 ACQUIRE_CAUSES_WAKEUP，接上的一瞬间顺便把屏幕点亮，条件满足期间一直持有，屏幕压根走不到熄屏倒计时。锁屏上的倒计时通知还在，屏幕就一直亮着；按电源键仍然可以主动关屏，再按一下点亮后就一直亮着不睡啦。开关一关、通知一收、服务一停都会立刻撤掉，兜底闹钟每 10 秒也会接管一次，服务被杀后照样接得住～"),
        ChangelogItem("v1.0.0.38", "2026-10-01",
            "「关于」页新增「锁屏通知常亮」开关（和「UI 界面常亮」「悬浮框常亮」长得一模一样，都是左右滑动开关）：打开后，只要锁屏 / 通知栏上还挂着倒计时的通知，手机屏幕就一直亮着不睡，方便你在锁屏界面上一直瞄那个倒计时；关掉的一瞬间就撤掉常亮，屏幕熄多久完全交给手机系统设置里的时间来管（想省电就随手关掉啦）。它只在「屏幕亮着」的时候默默挂一个透明小角、息屏自动收掉，没展开任何倒计时时也不会偷偷常亮；就算没给悬浮窗权限也照样能用（会退一步用唤醒锁兜底）。下面那行状态会实时告诉你「屏幕正被按在亮着 / 已经熄了」，一眼就懂～"),
        ChangelogItem("v1.0.0.38", "2026-10-01",
            "「锁屏界面里看不到倒计时」这版对症修好啦～ 头号原因是锁屏通知以前用的是「静默通知」（低优先级），国产手机的系统默认只把重要通知摊在锁屏上，静默通知要么被折进「其它通知」要么干脆不显示——这版把锁屏通知换成高优先级渠道（锁屏必显、也不会再被折叠），同时保留「只在第一次提醒、不每秒响一声」；升级时会重建频道，新设置立刻生效。第二个原因：锁屏后系统会冻住后台，倒计时不再跳秒——这版新增了「唤醒刷新」兜底闹钟，息屏 / 锁屏状态下也能把锁屏通知刷成最新。「关于」页的锁屏设置区还加了「测试锁屏通知（发一条去锁屏看）」按钮和一行实时状态（开关 / 有几条在走秒 / 刷新服务在不在跑），点一下按电源键锁屏就能当场验货，看不到右上角那个「打开锁屏通知设置」按钮也照旧能跳过去对症打开～"),
        ChangelogItem("v1.0.0.38", "2026-10-01",
            "锁屏 / 通知栏倒计时这版又加固啦～「关于」页的「锁屏通知显示」开关打开后，服务一启动就立刻把倒计时挂到锁屏通知栏上，不再等下一秒才慢慢冒出来；就算没给悬浮窗权限（锁屏通知本来也不需要它）也照样能在锁屏上走秒，以前那种「给了锁屏开关却哑掉」的情况修好啦；没开悬浮窗权限也没关系啦～ 另外「关于」页新增了「锁屏上看不到？点我打开锁屏通知设置」按钮，一点就跳到本应用的应用信息页，国内手机的「锁屏显示 / 静默通知」开关都在那一页里，对症修完马上就有啦～ 归零提醒和锁屏倒计时也彻底分成两段通知 id，不会再互相顶掉变成一条啦。"),
        ChangelogItem("v1.0.0.38", "2026-10-01",
            "「关于」页新增「锁屏通知显示」开关（默认是开着的哦）：把列表里「展开」过的倒计时，以常驻通知的形式显示在通知栏和锁屏界面上——标题、倒计时数字、模式、备注还有主题配色都和悬浮窗里一模一样，每秒跟着跳秒，不用展开手机也能一眼瞄到还差多久；点一下通知就直接回主界面啦。它跟悬浮窗是同一批数据、同一个刷新节奏，两边永远一致，在列表里「收起」了，锁屏通知也会跟着一起收掉。想不想让锁屏上也有一个悬浮窗，交给「锁屏通知显示」就行啦～"),
        ChangelogItem("v1.0.0.38", "2026-10-01",
            "内置倒计时的备注改得更清楚啦：每小时倒计时会同步显示具体的整点小时（16 点多就写「距离17点整结束」，过整点自动变成「距离18点整结束」），当日 / 每周 / 当月倒计时也一起改成具体的结束时刻（如「距离10月2日0点整结束」「距离11月1日0点整结束」）；列表和悬浮窗都会实时跟着变，不用重启 App 哦～「关于」页还新增了「备注时间制式」下拉框（24 小时制 / 12 小时制），换成 12 小时制后备注会显示成「距离下午5点整结束」这样的说法，选完立刻生效，样式和其它设置项一样～"),
        ChangelogItem("v1.0.0.38", "2026-09-29",
            "界面文字都换成软乎乎的可爱说法啦～提示、按钮、对话框统统变温柔；「关于」页还新增了 GitHub 项目主页链接，点一下就能在浏览器里逛本仓库。版本还是 v1.0.0.38 哦。顺手把两处按钮文字改清楚啦：删除小倒计时显示「好呀，收起」，找回小内置倒计时显示「好呀，找回」，不再让人看不懂咯～ 另外呀～ 「+」号菜单的按钮也从长文字药丸换成了紧凑的青色圆形图标按钮（＋/药/?/复），四项沿弧线真正均匀排成扇形、整整齐齐包围加号，再也不会互相重叠或挤成一条斜线啦～ 这版再把扇形收紧啦：菜单圆钮缩到 48dp、半径从 160 收到 140，四项更贴着加号四周紧凑散开（靠近包围），聚拢更好点更好戳～ 这版再进一步贴近加号：圆钮缩到 40dp、半径 140→115，四项更紧凑地贴着加号四周散开（贴近包围），已是不重叠前提下最贴的距离咯～ 这版还新增了一个「每小时倒计时」小内置哦：目标就是本小时结束的那一刻（下一个整点 00:00），跨过整点会自动滚到下一小时重新计时，功能和风格和「当日倒计时 / 当月倒计时」一模一样，删掉了也能在「找回小内置」里找回来～ 这版再把设置项都换成更好用的样式啦：「关于」页的「屏幕常亮」拆成了「UI 界面常亮」和「悬浮框常亮」两个左右滑动开关——UI 界面常亮管打开 App 时屏幕不锁屏（和以前一样），悬浮框常亮则管悬浮倒计时展开时屏幕保持常亮、把它收缩成小条或关掉开关就乖乖跟随手机系统的时间熄屏；更新提示方式、吃药提醒的「每天次数」也都改成下拉框选择啦，开关统统换成左右滑动开关，风格和整体保持一致～"),
        ChangelogItem("v1.0.0.37", "2026-09-26",
            "吃药提醒新增「每天次数」选择（1/2/3/4 次 / 天），可逐次自定义时刻与「服药时机」（空腹服/餐前服/随餐服/餐后服/晨服/睡前服/间隔固定服/发作前服）；「关于」页与吃药日历同步展示各次彩色时刻与时机。版本升级至 v1.0.0.37。；通知栏不再常驻显示「每日吃药提醒 · 已开启」状态提示（到点提醒与「已吃药」记录不受影响）。；再次大幅增强跳秒动画表现（第二轮）：缩放/蒸发/坠落/像素化/碎片化/燃烧/震撼的幅度与抖动再加大、更醒目，效果非常明显。"),
        ChangelogItem("v1.0.0.36", "2026-09-25",
            "修复「免责声明」提示框换行显示异常（去掉字面换行符），版本升级至 v1.0.0.36。"),
        ChangelogItem("v1.0.0.35", "2026-09-25",
            "「关于」页新增「免责声明」按钮（位于「检查更新 / 说明 / 更新日志」下方），点开即弹出免责声明；首次安装或升级到新版本后，打开软件会自动弹出一次免责声明（看过即记录，不再重复打扰）"),
        ChangelogItem("v1.0.0.34", "2026-09-25",
            "检查更新新增「忽略更新」：在「发现新版本」提示框里点「忽略更新」后，当前显示回退为「已是最新版本」；下次再点「检查更新」仍会重新检测并再次弹出提示框（忽略不持久化，每次检查都重新拉取远程版本）"),
        ChangelogItem("v1.0.0.33", "2026-09-25",
            "吃药提醒通知：关闭状态下取消每日吃药提醒通知；开启状态下显示「已开启」状态通知，通知内容同步显示启动 / 关闭状态（已开启时显示「每日吃药提醒 · 已开启」+ 下次提醒时间）"),
        ChangelogItem("v1.0.0.32", "2026-09-25",
            "更新提示方式：在「关于」页新增三种方式的行为说明：\n" +
            "  自动：检测到弹窗拦截类工具时自动改成只发通知\n" +
            "  弹出提示：总是弹出更新提示页（可能被拦截工具关掉）\n" +
            "  只发通知：不弹任何界面，只在通知栏发一条（最不容易被拦）\n" +
            "吃药提醒：关闭状态下「关于」页隐藏提醒时间、状态等全部子项，仅保留开关"),
        ChangelogItem("v1.0.0.31", "2026-09-25",
            "吃药提醒默认改为关闭状态：\n" +
            "  关闭时「关于」页只保留开关与提醒时间，\n" +
            "    隐藏「吃药日历」「下次提醒」「测试提醒」「后台自检」等子功能\n" +
            "  打开开关后这些功能自动恢复显示"),
        ChangelogItem("v1.0.0.30", "2026-09-21",
            "通知全面对齐安卓系统内置闹钟 / 计时器：\n" +
            "  吃药提醒、版本更新、倒计时归零三类通知改为「常驻」—— 不会被随手划掉，\n" +
            "    退出或关闭软件后也一直在通知栏（由闹钟 / 广播 / 前台服务在后台发出）\n" +
            "  统一使用闹钟样式：CATEGORY_ALARM + 最高重要性 + 免打扰也响 + 锁屏可见\n" +
            "  吃药提醒点「已吃药」或点开吃药日历即消失；版本更新进 App 即消失；\n" +
            "    倒计时归零出现「停止」按钮，点一下清除"),
        ChangelogItem("v1.0.0.29", "2026-09-19",
            "内置倒计时的显示模式按各自周期定制，不再一律显示「周天时分秒」：\n" +
            "  当日倒计时（目标是次日 0 点，最多 24 小时）—— 标准模式改为「xx时xx分xx秒」，\n" +
            "    周数和天数永远是 0，摆在那里没意义\n" +
            "  每周倒计时（目标是下周一 0 点，最多 7 天）—— 标准模式改为「xx天xx时xx分xx秒」\n" +
            "  当日倒计时下线「天数模式」（永远显示 0 天）：列表、悬浮窗、编辑页都不再出现，\n" +
            "    已经设成该模式的旧数据自动退回标准模式\n" +
            "  其他倒计时、其他显示模式一律不变"),
        ChangelogItem("v1.0.0.28", "2026-09-19",
            "通知体检改成「照着做」而不是「看得懂」：\n" +
            "  底部主按钮直接写明先修哪一项 —— 哪一项不对勾，按钮就显示「去修第 N 项」，\n" +
            "    点一下直达那一项的对应设置位置\n" +
            "  体检每一项下的小按钮也标了序号（第 N 项·去打开），不用再数行\n" +
            "  全部正常时不显示「去修」按钮，只留一个居中的「关闭」\n" +
            "  序号按实际项数动态编排：不同安卓版本项数不同，以前写死的序号会对不上"),
        ChangelogItem("v1.0.0.27", "2026-09-19",
            "有新版本要尽快知道 + 更新提示不再被拦截软件关掉：\n" +
            "  后台检查间隔从 2 小时缩短到 20 分钟（与系统调度同频），开机 / 解锁 / 升级后\n" +
            "    1 分钟就先查一次 —— 发新版后基本能在半小时内收到通知\n" +
            "  新增「更新提示方式」（关于页按钮）：自动 / 弹出提示 / 只发通知\n" +
            "    自动模式会检查系统里是否开着「跳过开屏广告 / 弹窗拦截」类的无障碍服务，\n" +
            "    一旦开着就自动改成只发通知栏通知 —— 通知栏不在那些工具能关掉的窗口范围内\n" +
            "  更新提示页延后时间 800 毫秒 -> 1.5 秒，进一步避开开屏广告的判定特征\n" +
            "  点通知或手动点「检查更新」进来时照旧直接弹出提示页（用户主动要看，不降级）\n" +
            "  通知体检新增「后台巡检」一项：直接看出后台到底跑没跑"),
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
            "Android 13 及以上首次启动会申请通知权限，保证更新提醒能送达", obsolete = true),
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
            title = "使用小说明",
            positiveText = "知道啦",
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
                "可同时设置「标准模式」（显示方式）、跳秒动画、提示音与备注",
                "卡片左滑露出「编辑 / 删除」带动画：手指推多少，两颗按钮就跟着淡入、从右边滑出来，同时上面那块倒计时整片跟着淡出，推到头几乎全透明，像把卡片掀开露出底下另一层（编辑先出场、删除稍跟上，各自带一点横向位移、从小放大回原位）；松手吸到位还会轻轻回弹一下；右滑收回正好反过来 —— 倒计时淡回来、两颗按钮倒着淡出，上手就会立刻交回手指；\n" +
                "右滑收回、确认框点了「先不了」也都会顺滑滑回原位",
                "挑提示音不用左滑：直接在卡片按钮行上点「提示音名称」就行"
            )

            head("二、卡片上的按钮行")
            line(
                "显示：切换该倒计时是否在悬浮窗中显示",
                "模式：循环切换显示格式（标准 / 小时 / 分钟 / 秒 / 天 / 时分秒 / 天时分秒 / 天时分 / 分秒）",
                "  这一项只剩一种显示方式时，这颗按钮会自动收起，不再摆一颗点下去不动的按钮",
                "动画：循环切换 8 种跳秒动画（无 / 缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼）",
                "提示音：点名称即可打开系统铃声选择器，为每个倒计时单独指定"
            )

            head("三、排序与删除")
            line(
                "长按卡片：手指往上下滑 → 立刻就是拖动排序，长按到账会轻轻震一下告诉你「可以拖了」，松手即保存",
                "  （长按 300ms 就预备好，到点会挡住外层滚动别来抢手势，v122 起拖起来更跟手）",
                "  （拖出来的顺序单独记着，不顶掉出厂顺序；内置项照旧拖得动，拖完自动存成自定义排序）",
                "长按卡片：手指原地抬手 → 进「多选删除」（顶部亮出横幅，每行右上角出现勾选框，点这一行就是勾上 / 取消；",
                "  勾选几条再点「收起选中」一次收掉，内置项照样存成快照，「找回小内置」能原样捞回来）",
                "  横幅中间还多「全选」「全不选」两颗：一点就把整排都勾上，再点「全不选」全部取消并自动退出多选，",
                "  只有一条的时候这两颗自己收起来，省得点了个寂寞。",
                "  两种走法不再互相抢：想排序就往上 / 下拖，想删除就按住别动、抬手就进 —— 手慢也不被抢进别的模式",
                "  进多选后每行右上角那颗对勾稳稳画在卡片最上层（抬升高过卡片，不会再被半透明前景盖住）：",
                "  勾上的是满不透明的亮橙实心圆 + 加粗白勾，没勾的是深底 + 一圈更亮的纯白圈，34 的大圆一进多选就清清楚楚；",
                "  亮出来时给标题让出右边一条，绝不会压字，也不用先左滑看一眼才找得到勾了哪几条",
                "左滑卡片点「删除」即可移除（内置倒计时同样可删除）"
            )

            head("四、悬浮窗与锁屏通知")
            line(
                "在悬浮窗里可拖动位置、点标题栏收缩成小条、调「玻璃透明度」—— 它只淡化卡片 / 抽屉 / 按钮这些"
                + "玻璃背景，标题、备注、目标时间和倒计时数字始终保持全亮，跳秒动画不受影响。",
                "  添加或改完倒计时，保存返回这一下就把最新数据推过去：新窗当场建好、当场刷上最新的数字和备注，",
                "  不用再等服务下一跳才冒出来（锁屏通知也跟着这一下一起刷）",
                "「关于」页的「锁屏通知显示」：把「展开」过的倒计时以常驻通知显示在通知栏 / 锁屏上，",
                "  标题、倒计时数字、模式、备注与配色和悬浮窗完全一致，每秒跳秒；点通知直接回主界面",
                "锁屏上看不到的话，点「关于」页的「锁屏上看不到？点我打开锁屏通知设置」一键跳到应用信息页",
                "  把里面的「锁屏显示 / 静默通知」打开就行（它不需要悬浮窗权限，给不给都能显示）",
                "点「关于」页的「测试锁屏通知（发一条去锁屏看）」能当场验货：它会立刻发出一条真实倒计时，",
                "  按一下电源键锁屏就能看到；下面那行状态还会告诉你「开了没 / 有几条在走秒 / 刷新服务在不在跑」",
                "锁屏后系统会冻住后台，所以另有一个「唤醒刷新」闹钟兜底，息屏状态下锁屏通知也会接着跳秒",
                "打开「悬浮框常亮」后，悬浮倒计时展开时屏幕会一直亮着（收缩成小条则跟随系统熄屏时间）",
                "「关于」页的「锁屏通知常亮」：打开后只要锁屏 / 通知栏上还挂着倒计时，屏幕就一直亮着、",
                "  锁屏界面上也能一直瞄倒计时，不按手机熄屏时间睡去；关掉立刻恢复系统熄屏时间",
                "  做法：在锁屏上挂一个全透明的常亮页（系统闹钟同款），不抢焦点不拦触摸，锁屏照常能划能点",
                "  需要「显示在其他应用上层」权限才能在后台挂上锁屏，缺权限时开关会引导你去打开",
                "三档常亮各管一摊：UI 界面常亮管开 App 时、悬浮框常亮管悬浮窗展开时、锁屏通知常亮管锁屏通知在时",
                "系统「设置」界面处于前台时，悬浮窗会被系统临时隐藏（Android 安全机制），返回后自动恢复",
                "点悬浮窗右上角的「菜单」能拉出抽屉，里面写着这条倒计时的「目标:」时刻（内置项就是系统自己往下跳的那个时刻）",
                "  「目标:」跟着每秒跳秒一起刷新 —— 退到后台跨了整点 / 半点 / 整分也会自动换成下一个目标时刻，不会再停在旧值",
                "  抽屉里那两颗按钮也一并改清楚：「上一个显示模式 / 下一个显示模式」，卡片上那颗叫「显示模式」，" +
                "  「展开 / 收起」统一说成「展开悬浮窗 / 收起悬浮窗」，一眼就知道点的是悬浮窗",
                "若任意界面都不显示，请在系统设置中开启「显示在其他应用上层」，并在品牌权限管理中开启「后台弹出界面」"
            )

            head("五、内置倒计时")
            line(
                "这七个短档（每2分钟、每3分钟、每4分钟、每6分钟、每7分钟、每8分钟、每9分钟）的标准模式这轮换了个写法：",
                "  以前只有「xx分」——每2分钟看成「2分」，其实还剩 1 分 59 秒，秒那截藏在背后看不见；",
                "  现在标准模式直接给「xx分xx秒」（跟「每半小时」一个写法），每2分钟就写「1分59秒」这么显示，",
                "  可选档位跟着顺过来：标准（=分秒）/ 分钟 / 秒，小时模式、时分秒模式、天数那三档照旧砍掉"
            )
            line(
                "这一轮又添了一大批内置档：短的有每2分钟、每3分钟、每4分钟、每6分钟、每7分钟、",
                "  每8分钟、每9分钟，长的有每2小时一路到每23小时 —— 全都跟「每小时倒计时」一个路数：",
                "  目标时刻由系统一格格往下跳（每2分钟就跳到下一个 2 分边界，每2小时就跳到下一个 2 小时边界），",
                "  跨过自动重新倒数、自动补进列表、可拖排序、「＋」菜单里也找得回来，悬浮窗同步照旧"
            )
            line(
                "修了一处老毛病：每2~23小时那一批（每2小时 … 每23小时）的目标时刻，以前全被压成「下一个整点」，",
                "  跟每小时一模一样，等于根本没按每 N 小时滚 —— 上一轮把它们并进通用周期段时算错了一步。",
                "  现在老实按边界对齐：目标 = 从当天 00:00 起算的下一个 N 小时边界，",
                "  每2小时就是 02:00 / 04:00 / 06:00 …（每23小时就是当日 23:00 → 次日 22:00 → …），跨过自动接着往下跳"
            )
            line(
                "上一轮新加的那批又撤了：每2分钟、每3分钟、每4分钟、每6分钟、每7分钟、每8分钟、每9分钟，",
                "  还有每2小时一路到每23小时 —— 一共 29 个档，这一轮整个下线。它们从「倒计时类型」下拉框和出厂顺序里都摘掉了，",
                "  你之前已经生成的那几十条也一并清掉，「复」字里挂着的那一批跟着撤干净，免得留下一堆再也选不中的档。",
                "  剩下的还是那十档：每分钟、每5分钟、每10分钟、每半小时、每小时、当日、每周、每月、华都云境悦府、GTA6，",
                "  归零照旧响铃、可拖排序、「＋」菜单里找得回来，悬浮窗同步照旧"
            )
            line(
                "这一轮把三档的「标准模式」调成了 xx分xx秒：每5分钟、每10分钟、每小时。",
                "  每5分钟和每10分钟原来只写 xx 分 —— 看成「5分」，其实还剩 4 分 59 秒，秒那截藏在背后；",
                "  每小时原来写 xx时xx分xx秒，可它最多就 1 小时，「时」那截只会是 0，摆出来纯占地方。",
                "  现在三档跟每半小时、每2~9分钟一个写法，秒一眼就能看见；每档能切的还是 标准 / 分钟 / 秒 三档，",
                "  当日倒计时最多 24 小时，「时」那截有数，仍旧是 xx时xx分xx秒，不受这一轮影响；悬浮窗同步照旧"
            )
            line(
                "这一轮把卡片上的「显示模式」「动画」「颜色」三颗按钮，改成点一下就弹出下拉框来挑。",
                "  显示模式只在这条倒计时真正能用的档位里列（每5分钟是 标准 / 分钟 / 秒 三档），点中即刻生效；",
                "  动画一次挑一种跳秒效果：无动画 / 缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼；",
                "  颜色是新加的第三颗 —— 倒计时文字的主题色不用再进编辑页，青 / 品红 / 绿 / 黄 / 橙 / 红 / 金",
                "  七色点一下就换，列表和悬浮窗同帧跟着换；编辑页那颗色板跟它共用同一份颜色。悬浮窗同步照旧"
            )
            line(
                "这一轮修的是三颗按钮下拉框里的名字看不见：以前「显示模式 / 动画 / 颜色」点开只剩一个圆点色块，",
                "  动画那八种、颜色那七色的文字全被挤没了（动画的全是青色方格）。原因是文字那格没写宽度，",
                "  默认宽度会让它被量成 0；现在文字显式占自己的宽度、面板按最长那条名字先量一遍再定，",
                "  名字完整显示，顺带挑中那项后面还会跟一个对勾；弹的位置自动避开屏幕左右边缘，",
                "  下方放不下时会翻到按钮上方展开。悬浮窗同步照旧"
            )
            line(
                "这一轮把「100年内倒计时」这一档连代码一起删干净了 —— 不是下线，是整块拔掉：",
                "  它的显示名、归零后照着「生成那会儿离目标还有多久」重新起一轮的那套跑法（生成时差 20 小时就一直按 20 小时往下跑）、",
                "  添 / 改页那六颗「年 / 月 / 日 / 时 / 分 / 秒」下拉框、名字和备注按时长自动生成，",
                "  还有它自己那三个数据字段（目标时刻 / 每轮时长 / 是否启用自定义提示音）连同存盘读写，全都不见了；",
                "  「倒计时类型」里那一串收成十个：每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 →",
                "  每周 → 当月 → 华都云境悦府 → GTA6，新的一条都生成不了；以前挑日子生成的那几条（「20小时」",
                "  「7小时」）升级后照旧删掉，存的备份快照一并扫干净，「＋」菜单「复」字里那个逐条找回的区块也撤了，",
                "  不会再点开一个选不中的类型。那个常量编号留了个空位没回收（免得以后新档撞上老数据认不出）。悬浮窗同步照旧"
            )
            line(
                "当月倒计时、华都云境悦府、GTA6，还有新建的那条普通倒计时，显示模式里多出两档 ——",
                "  「周模式」只写 xx周（还剩几个整周），「周天时分秒模式」写 xx周xx天xx时xx分xx秒",
                "  （周在最前头，跟原来的标准模式一个写法）；编辑页那颗模式下拉框、卡片上的「显示模式」按钮、",
                "  悬浮窗抽屉里那两颗「上一个 / 下一个显示模式」、锁屏通知看到的都是这一份，切完当场生效、不用重启；",
                "  每周倒计时最长就 7 天（周那截恒 0），每5分钟 / 每小时 / 当日那几个更是到不了一周，",
                "  这两档对它们收着不摆，免得冒出一个干巴巴的「0周」；新建和改完都能挑，存下来照旧跟着一起跳秒。",
                "  悬浮窗同步照旧"
            )
            line(
                "这一轮给添 / 改那页的「倒计时类型」下面添了一颗「是否循环」下拉框，跟页面上其它下拉框同一套样子 ——",
                "  挑「是（归零重新计时）」，这条倒计时归零之后会自己从头再倒一遍（照它自己这一轮的时长",
                "  一轮一轮往下转，每一轮到点照样响提示音、照样出归零通知）；",
                "  挑「否（归零就停住）」就是原来那个跑法，归零就停在那儿不动。",
                "  另外内置倒计时也加了「启用自定义提示音」那颗开关：勾上之后，挑音那两颗按钮跟普通倒计时",
                "  一个样子（功能、风格都照普通倒计时来），主界面卡片上那颗提示音按钮也一起给着；",
                "  开关开着主界面就显示提示音按钮、关着就不显示，归零只响默认提示音；",
                "  挑「普通倒计时」时这颗开关不摆，那两颗挑音按钮照旧直接给。",
                "  老数据一律按老规矩落地 —— 循环都关着、提示音开关照以前那个能不能挑来定，",
                "  装上去跟以前一眼看不出差别。悬浮窗同步照旧"
            )
            line(
                "这一轮把添加 / 编辑小倒计时那一页「倒计时类型」下面那一层「内置档位」整个收掉了 ——",
                "  那一级只有挑「内置倒计时」时才亮，用来挑这条倒计时要走哪一档内置（每小时 / 每5分钟 /",
                "  每10分钟 / 每半小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6）；",
                "  现在这一级不再摆出来：「倒计时类型」只剩「普通倒计时 / 内置倒计时」两格，是不是内置",
                "  由这一级定，内置这一档是哪一档由这条自己来 —— 编辑已有内置照旧是它原来那一档（名字、",
                "  颜色、显示模式、跳秒动画、备注照旧能改），新建的按「每小时」这一档走内置；",
                "  已经在列表里的内置一样照旧：删了能在「＋」菜单「复」字里原样找回来；在编辑页把它",
                "  改成「普通倒计时」存下去，它就不是内置了，「复」字里也不再挂着它，连那份设置快照",
                "  一起抹掉（同档还有别条的话那份照旧留着）；类型不做记忆，重新安装、首次装都从原来",
                "  那个起手走，不会被上一次的选择带偏。悬浮窗同步照旧"
            )
            line(
                "这一轮把「倒计时类型」拆成两格 —— 普通倒计时 / 内置倒计时，不再是一长串档名挤在一起：",
                "  一级挑「普通倒计时」，就是原来那条自己定目标时刻的自定义倒计时；挑「内置倒计时」，",
                "  这条就按下面「内置档位」那一级挑的那一档走内置（每分钟 / 每5分钟 / 每10分钟 / 每半小时 /",
                "  每小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6）；",
                "  建出来的这条一直是内置，跟出厂那几个一模一样，删了能在「＋」菜单「复」字里原样找回来，",
                "  名字、颜色、显示模式、跳秒动画、备注一个不少；",
                "  已经躺在列表里的那条内置，进编辑页照旧能改它的名字、颜色、模式、动画、备注，类型也照样能换；",
                "  一条内置在编辑页被改成「普通倒计时」存下去，它就不再是内置了，「复」字里也不再挂着它，",
                "  连它之前存下的那份设置快照一起抹掉（同档还有别条的话，那份照旧留着给别人用）；",
                "  类型这一级选的那一档不做记忆 —— 每次添加都从「普通倒计时」起，只有编辑已有条目时",
                "  才带出它自己原来的类型；重新安装、首次装跟着一样，都不会被上一次的选择带偏。悬浮窗同步照旧"
            )
            line(
                "自带十个：每小时倒计时、每半小时倒计时、每10分钟倒计时、每5分钟倒计时、每分钟倒计时、",
                "  当日倒计时、每周倒计时、当月倒计时、华都云境悦府、GTA6（越靠前走得越慢，一眼看得出长短）",
                "这一档已经整个拿掉了：添 / 改页「倒计时类型」里不再有它，新生成不了；",
                "  以前挑日子生成的那些（「20小时」「7小时」）升级后一并删掉、存的快照也扫干净，",
                "  「＋」菜单「复」字里那个逐条找回的区块跟着撤掉；剩下的十个该怎么用还怎么用",
                "  （这段是它当初挂回来的来历，v150 这一档整个拿掉了）「100年内倒计时」也是内置的一档：添 / 改页「倒计时类型」里挑中它，下面亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框",
                "  （跟页面上其它下拉框同一个样子：年那颗从今年一路列到 100 年后，日那颗跟着年月自动收天数），",
                "  挑 100 年以内任意一个日子就是它的目标时刻；名字和备注都照挑好的时间自动写成「2026年10月7日 12:00:00」这种",
                "  它跟「每小时倒计时」「当月倒计时」一个路数：跨过那一刻自动往后推一整个 100 年周期重新倒数（跨过自动重新倒数），",
                "  显示模式、颜色、跳秒动画、长按拖动、「＋」菜单里找回全一样；同名、或者同类型的那条已经躺在列表里了，",
                "  会弹一句「该倒计时已存在，生成失败！」；旁边那颗「启用自定义提示音」开关勾上，才摆出",
                "  「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮；挑「普通倒计时」这六颗全部收着，",
                "  目标时刻照旧由上面那块「目标日期 / 目标时间」给",
                "刚顺手修掉的一处闪退：点「＋」进添 / 改页会一闪就没 —— 那串自动生成的名字去读六颗下拉框时，",
                "  下拉框还没填过值，读到空值当场报错退出；现在这串改成真要用时才算，",
                "  六连框也提前按当前时刻填好，一前一后的顺序对上了，进页面再点保存稳稳的。",
                "这一轮再改个跑法：跨过之后不再跳到一百年的下一个同刻，",
                "  而是照着生成那会儿隔了多久重新倒计时 —— 生成时离目标 20 小时，归零就按 20 小时接着跑，",
                "  离 7 小时就按 7 小时跑，一轮一轮自己转下去；名字和备注也照这段时长自动写：",
                "  「100年内倒计时」现在能存好几条了（个数不限制），同时长的那条才不会重复生成。",
                "这一轮补上「删了找不回来」的老毛病：100年内倒计时能存好几条以后，",
                "  你删掉其中一条时列表里往往还躺着另一条同类型的，找回就被挡在门外（勾了半天一条都没回来）。",
                "  现在删掉哪条就替哪条单独存一份快照，删了的条条都找得回来：时长、名字、备注、",
                "  主题颜色、显示模式、跳秒动画一起还原，找回来之后照旧按时长一轮轮往下转，悬浮窗同步。",
                "这轮修两处：一是挑完目标时刻日期自己多一天 —— 月 / 日两颗下拉框原来从 0 起填，",
                "  上头写「10日」那个 10 被当成了格子号、读出来又给加一，存下去就成了 11 日（初值那一下还写着「6日」、",
                "  存的其实是 7 日，前后对不上）；现在月 / 日两颗统一成「上头写几就是几」（1月..12月、1日..31日），",
                "  点哪天就是哪天；二是生成完一条以后「倒计时类型」里就选不到「100年内倒计时」了（非得删掉那条才看得见），",
                "  因为它和「每小时 / 当日 / 每分钟」一样是内置那一档、能存好几条，这回让它恒在那儿，随时能再挑一条。",
                "  跨过按时间差重新倒计时、名字备注按时长自动生成、个数不限制、同时长才挡、找回小内置，都不变。",
                "  这轮把「复」字找回框里的「100年内倒计时」做成一条一条单捡：以前它混在十一个内置项里只是一整项，",
                "  勾一下会把存过的备份一股脑全倒进列表，分不清哪条是哪条；现在下半截单开一块「你自己生成的」，",
                "  把删掉的每一条列出来（名字、从哪刻起、每轮多少时长），勾上哪条就只找回哪条，也管「这些全选上 / 全不选啦」。",
                "刚对好的一处：目标时间总跟你挑的时刻对不上 —— 挑的那个日子还在未来才照常往下走、走到 0 之后滚一百年；",
                "  挑过去的（包括就着默认值直接存的「现在」）一律停在挑的那个时刻不动了，不会再跳到一百年的下一个同刻；",
                "  另外月份那颗下拉框改回 1 月起（原来从 0 起，挑 1 月会存成上一年 12 月），改目标时刻保存也存你新挑的那个。",
                "每10分钟 / 每5分钟 / 每分钟倒计时是这一轮新加的，跟每小时那个一个模子：目标时刻是系统自己往下跳的",
                "  （分别跳到下一个 10 分、5 分、整分边界），到点归零后接着往下跳，风格、位置、显示方式全跟每小时一致",
                "  它们的显示模式不摆「小时模式 / 天数模式 / 天时分秒 / 天时分」：每分钟那个只剩「标准 / 秒」（标准模式就是 x 秒），",
                "  每5分钟 / 每10分钟那个剩「标准 / 分钟 / 秒」（标准模式就是 x 分），下拉框里切不到那几档恒为 0 的，",
                "  悬浮窗跟主界面、编辑页下拉框用的是同一份设置，三处完全同步",
                "  上一轮新加的「分秒模式」在「每分钟倒计时」上没留住 —— 一分钟里它只会写成 0分30秒，" +
                "  这档撤掉了，每分钟那个又回到「标准 / 秒」；每5分钟 / 每10分钟 那边照旧能选，三处同步",
                "内置项的备注会同步显示具体结束时刻：每小时倒计时写「距离17点整结束」（过整点自动变「距离18点整结束」），",
                "每半小时倒计时写「距离17点半结束」（过了半点自动变「距离18点整结束」），",
                "每5分钟 / 每10分钟 / 每分钟倒计时写「距离18点05分结束」「距离18点20分结束」「距离18点31分结束」这样，",
                "当日 / 每周 / 当月倒计时写「距离10月2日0点整结束」这样具体的日期和时刻；列表与悬浮窗都实时跟着变",
                "「100年内倒计时」的备注就是它挑的那个时刻（如「2026年10月7日 12:00:00」），跟名字一模一样；",
                "备注里的时间按「关于」页的「备注时间制式」显示：24 小时制是「距离17点整结束」，12 小时制是「距离下午5点整结束」",
                "内置项随时能改：名字、颜色、显示模式、跳秒动画、提示音、备注都能照常改，改完它还是内置倒计时",
                "  （时间那栏不改也罢——内置倒计时的时刻要么由系统自己往下跳、要么是你自己挑的，改了就按那个走，不会把它变成普通倒计时）",
                "  （提示音也一样分了两拨：每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月这八个自己往下跳的，归零只响默认提示音、不摆换音按钮；华都云境悦府、GTA6、100年内倒计时和所有你自己建的倒计时都照旧能挑）",
                "  这一轮把挑提示音的路子理顺：普通倒计时、华都云境悦府、GTA6 的新建页和编辑页里，",
                "  「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮直接摆着，点一下就唤起系统铃声选择器，",
                "  不用像「100年内倒计时」那样先去勾旁边那颗「启用自定义提示音」开关；挑过的铃声名就写在按钮上，",
                "  点「清除自定义提示音」就回到默认提示音，选完保存即生效；",
                "  只有每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月这八个自己往下跳的内置档照旧不摆（归零只响默认提示音），",
                "  「100年内倒计时」仍是勾上那颗「启用自定义提示音」开关才露出这两颗按钮。",
                "  列表卡片上的提示音名称照旧一点就改，改完悬浮窗、主界面、编辑页三处同帧跟着",
                "改内置项或收起内置项前都会先弹一句说明，点「继续」才往下走",
                "内置项的出厂顺序是固定的：每分钟 → 每5分钟 → 每10分钟 → 每半小时 → 每小时 → 当日 → 每周 →",
                "  当月 → 华都云境悦府 → GTA6（越靠前走得越少），新装或补齐的内置项都按这个排",
                "想自己排：长按名称拖一拖，拖出来的顺序单独记着，不会顶掉出厂顺序；",
                "  想复位去「＋」→「恢复内置」→「主界面默认排序」，点一下就回出厂顺序（",
                "  这颗按钮只在你真把内置顺序拖过之后才出现；没拖过时它不摆出来，只提示一句「现在排的就是出厂顺序」，",
                "  免得摆一颗点了没变化的按钮；点了会弹一句告诉你现在排成什么样，顺手同步到悬浮窗通知）",
                "「每半小时倒计时」的标准显示改成了「分秒模式」（xx分xx秒），比原来少一截恒 0 的「时」；",
                "  跟标准模式显示出一模一样的那几档已自动删掉；某一项只剩一种显示方式时，模式按钮整个收起",
                "收起的内置项可在「＋」菜单里点「恢复内置」找回，并会还原删除前的主题颜色、模式、动画、提示音等设置"
            )

            head("六、更新与通知")
            line(
                "打开 App 会自动检查更新，之后每 15 分钟后台检查一次",
                "「开启通知 / 测试通知 / 允许后台运行」三个按钮可自检通知是否真的收得到",
                "收不到更新通知时，先点「测试通知」验证，再点「允许后台运行」关闭电池优化"
            )

            head("七、吃药提醒")
            line(
                "从主界面右下角「＋」菜单点「吃药提醒」进入设置页",
                "点「吃药提醒」开关即可开 / 关；「首剂时间 / 每天次数」可设置每天提醒几次、从几点开始",
                "到点通知上点「已吃药」即记录当天；之后点「吃药日历」可查看哪天吃了、哪天没吃",
                "日历支持切换月份，点过去或今天的日期可补记 / 撤销，未来日期不可记录"
            )

            head("八、数据")
            line(
                "所有倒计时与吃药记录都保存在手机本地，卸载应用会一并清除，请先做好记录"
            )

            head("九、屏幕常亮与设置项")
            line(
                "设置项统一更好戳：开关都是「左右滑动开关」，多选项都是「下拉框」，点一下就能改",
                "UI 界面常亮：打开 App 时屏幕一直亮着、不锁屏（关掉就按手机系统的时间正常熄屏）",
                "悬浮框常亮：悬浮倒计时「展开」时屏幕保持常亮；把它收缩成小条或关掉开关时，屏幕完全跟随手机系统设置的熄屏时间",
                "备注时间制式：下拉框选「24 小时制 / 12 小时制」，只影响备注里的钟点说法，倒计时数字不受影响",
                "锁屏通知显示：列表里「展开」过的倒计时会常驻在通知栏 / 锁屏上（内容、配色与悬浮窗一致、每秒跳秒），关掉开关就一起收掉"
            )
        }
    }

    fun showChangelog(activity: Activity) {
        if (activity.isFinishing) return
        showStyledDialog(
            activity = activity,
            title = "更新日志",
            positiveText = "知道啦",
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

                val dateTv = bodyText(activity, date, 16f, 0xFFFFA63D.toInt())
                dateTv.setTypeface(dateTv.typeface, Typeface.BOLD)
                head.addView(dateTv)
                host.addView(head)

                // 同一天发布多条时才显示下箭头；只有一条的日期没有箭头，
                // 但点日期本身同样可以展开 / 收起。
                var arrow: TextView? = null
                if (items.size > 1) {
                    arrow = bodyText(activity, "  ▼", 14f, 0xFFFFA63D.toInt())
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
    /**
     * 日志里的一行：撤掉过的功能那几行前面顶一个 ✗ 并整行划掉；
     * 正文里用 ~~ 框起来的片段也照旧划掉（比如左滑那颗「提示音」按钮）。
     */
    private fun addChangelogLine(
        ctx: Context,
        host: LinearLayout,
        raw: String,
        obsolete: Boolean
    ) {
        val mark = if (obsolete) "✗" else "·"
        val text = "  " + mark + " " + raw
        val span = SpannableString(text)
        var from = 0
        while (true) {
            val s = raw.indexOf("~~", from)
            if (s < 0) break
            val e = raw.indexOf("~~", s + 2)
            if (e < 0) break
            span.setSpan(
                StrikethroughSpan(),
                3 + s,
                3 + e + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            from = e + 2
        }
        if (obsolete) {
            span.setSpan(
                StrikethroughSpan(),
                3,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val tv = bodyText(
            ctx,
            span,
            14f,
            if (obsolete) 0xFF8A8FA3.toInt() else 0xFFD8D8E6.toInt()
        )
        host.addView(tv)
    }

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
            addChangelogLine(ctx, host, line, item.obsolete)
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
