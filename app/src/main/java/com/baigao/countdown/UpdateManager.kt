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

    const val CURRENT_VERSION_NAME = "1.0.0.39"
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
        ChangelogItem("v1.0.0.39", "2026-10-10", "这次带来一批贴心小功能，挑喜欢的用就好啦 ✨ —— ① 用药提醒：在「＋」菜单「吃药提醒」里轻轻一点就能开关，可以设每天提醒 N 次、每次的吃药时刻和时机（8 选 1），到点会弹系统通知，通知上还有「已吃药」小按钮，点一下就记进吃药日历（按天看喔）；用了三路冗余闹钟 + 巡检补发，就算退出 App 或被系统清掉闹钟也会按时提醒，打开 App 还会把当天漏掉的补上～ ② 锁屏时钟：列表里展开过的倒计时，会在锁屏 / 通知栏各占一条常驻通知，每秒跟着跳秒，标题 / 数字 / 模式 / 备注 / 配色都跟悬浮窗一模一样；③ 屏幕常亮：挂着锁屏倒计时通知时屏幕可以一直亮着（锁屏上也能一直瞄倒计时），默认关着，想用再开就好；④ 应用内更新检查：会自动查新版本、还有应用内更新提示页（普通 Activity，避开开屏广告拦截）和后台周期巡检；⑤ 通知体检：一键自查「吃药提醒 / 版本更新」的通知权限、渠道、后台限制，不过关就一键跳去修；⑥ 跳秒动画：缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼好多种跳秒效果，列表和悬浮窗同帧生效；⑦ 备注里的钟点可以在 24 小时制 / 12 小时制之间切换（设置项里喔）；⑧ 免责声明：首次安装或升级时弹一次，「关于」页随时能再看。悬浮窗同步照旧哒 💙"),
        ChangelogItem("v1.0.0.38", "2026-10-10", "一大波小功能上线啦 🎉 —— ① 用药提醒：在「＋」菜单「吃药提醒」里开关，可设每天 N 次、每次的服药时刻与时机（8 选 1），到点发系统通知、通知上带「已吃药」按钮，点一下就记进吃药日历（按天显示）；用三路冗余闹钟 + 巡检补发，退出 App 或被清掉闹钟也按时提醒，打开 App 还会补发当天漏掉的；② 锁屏时钟：展开过的倒计时在锁屏 / 通知栏各占一条常驻通知，每秒跳秒，标题 / 数字 / 模式 / 备注 / 配色与悬浮窗一致；③ 屏幕常亮：锁屏倒计时通知挂着时屏幕可一直亮着，默认关、想用再开；④ 更新检查：App 内自动查新版本、应用内更新提示页（普通 Activity，避开开屏广告拦截）与后台周期巡检，更新日志 / 使用说明 / 免责声明同款玻璃风；⑤ 通知体检：一键自查「吃药提醒 / 版本更新」的权限、渠道、后台限制，不过关一键跳去修；⑥ 跳秒动画：缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼多种效果，列表与悬浮窗同帧生效；⑦ 备注钟点可在 24 / 12 小时制切换（设置项）；⑧ 免责声明：首次安装或升级弹一次，「关于」页随时再看。悬浮窗同步照旧～"),
        ChangelogItem("v1.0.0.38", "2026-10-10", "把「华都云境悦府购买正计时」这档内置正计时又请回来啦 🏠 —— 它和「华都云境悦府 / GTA6」同款卡片、同款玻璃质感，只是方向相反：显示「自购买至今已经过去多久」，起点固定在 2025-04-20 17:30，全部 11 档显示模式都照常可用，名字 / 颜色 / 跳秒动画 / 备注 / 提示音都能照常改。还给它加了「暂停 / 开始」按钮：点「暂停」正计时当场冻结、数字停住不动，按钮翻成「开始」；再点「开始」就从冻结那一刻接着走，暂停期间流失的墙钟时间会自动补齐（暂停 3 分钟，开始正好多走 3 分钟），来回循环都行。按钮样式跟 GTA6 卡片上那颗「展开 / 收起悬浮窗」一个路数（玻璃小按钮、文字按需翻转）；主界面卡片和悬浮窗里都有这颗按钮，两边状态互相同步、点哪边另一边都跟着变。悬浮窗同步照旧哦。"),
        ChangelogItem("v1.0.0.38", "2026-10-10", "上一轮加的「华都云境悦府购买正计时」整档又下线啦～ 连同它的正计时「暂停 / 开始」按钮、GTA6 同款玻璃按钮、还有主界面卡片与悬浮窗双向同步那一套，全部收走。App 打开会自动把旧数据里残留的这一档清掉；其余倒计时（每日 / 每周 / 每月 / 华都云境悦府 / GTA6 / 今年 …）一个不少、照常工作，悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "给「华都云境悦府购买正计时」这档内置正计时加了「暂停 / 开始」按钮 💡 —— 点「暂停」正计时当场停住、数字不再往前走，按钮翻成「开始」；再点「开始」就从停住那一刻接着走，暂停期间流失的时间会自动补齐（不计入流逝），「暂停 → 开始」可以一直来回循环。按钮样式跟 GTA6 卡片上那颗「展开 / 收起悬浮窗」一个路数（玻璃小按钮、文字按需翻转）；主界面卡片和悬浮窗里都有这颗按钮，两边状态互相同步、点哪边另一边都跟着变。其它倒计时不受影响哦。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "新增内置「华都云境悦府购买正计时」啦 🏠 —— 这是一档内置正计时（跟华都云境悦府 / GTA6 倒计时同款卡片、同款风格，只是方向相反）：起点固定在 2025-04-20 17:30（购房时刻），显示「自购买至今已过去多久」（如「537天12时34分56秒」），全部 11 档显示模式都照常可用（标准模式就是「天时分秒」），名字、颜色、跳秒动画、备注、提示音都能照常改，归零风格跟 GTA6 一样（日子定死、不跟着日期滚动）。出厂顺序排在华都云境悦府 / GTA6 之后、今年之前，「＋」菜单「恢复内置」里能原样找回；悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "卡片上的「显示模式」按钮变得更聪明啦～ 以前写死显示「显示模式」四个字，现在会动态显示当前选中的那一档名字：选「毫秒模式」就显示「毫秒模式」、选「天时分秒」就显示「天时分秒」、选「标准模式」就显示「标准模式」，一眼看清这条倒计时现在走的哪一档；点它照样弹下拉框挑模式，功能与风格不变。内置 / 普通 / 新建倒计时都生效，悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "把「毫秒模式」修好啦 🐛 —— 之前它只显示「秒×1000」的假毫秒（尾数永远 000，看着跟没有毫秒一样），现在改成真正的剩余毫秒数：按当前真实时刻算整段倒计时还剩多少毫秒，并以约 30 帧/秒实时递减（比如还剩 3 分 5 秒 432 毫秒就显示「185432毫秒」）。内置倒计时（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6 / 今年）、普通倒计时、新建条目，这档都生效，功能与风格和其它档一致；悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "给所有倒计时都加了一档「毫秒模式」⏱️ —— 它跟天 / 时 / 分 / 秒那几档一个路数，把整段倒计时以毫秒为单位一口气写出来（格式就是「xx毫秒」，比如还剩 3 分 5 秒就显示「185000毫秒」）。内置倒计时（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6 / 今年 …）、普通倒计时、还有新建的条目，显示模式里都多了这一档，挑哪个都一样按整毫秒落地、跳秒动画与其它档完全一致。另外把「今年倒计时」的标准模式从原来的「xx周xx天xx时xx分xx秒」改成「xx天xx时xx分xx秒」，不再把那截永远在涨的「周」摆出来。悬浮窗同步照旧～"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "把「是否循环」这一格彻底改成「如实显示」啦 💡 —— 你看到的那一格，就是这条倒计时真正会不会「归零重新计时」的样子。内置倒计时由它的档位说话：每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 今年这些「自己往下跳」的档，归零就接着跑下一格，这一格恒显示「是（归零重新计时）」；华都云境悦府 / GTA6 这两档日子是定死的，归零就停在那一刻，这一格恒显示「否（归零就停住）」—— 内置项这一格只是如实显示、点也点不动（它本来就不是能挑的）；只有普通倒计时才由你自己挑「是 / 否」。挑「是」的照样归零自己接着跑下一轮，悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "把「是否循环」这一格从「显示」和「落盘」两头一起钉死 🔒 —— 一、显示侧：这一格的「被用户动过」改用「真在它上面抬手（ACTION_UP）」才算，滑一下取消的假触碰不认；打开编辑页后连发六记延迟复核（0 / 120 / 300 / 600 / 1000 / 1500 毫秒），盯住这一格一旦被更晚一帧冲回第 0 项「否」就当场按这条自己存的属性钉回去。二、落盘侧：保存时不再看这一格「此刻显示什么」，而是以「这条自己存着的循环属性」为准 —— 用户没动过就一律沿用原来存的 loop，只有亲手动过才以挑的那一格为准。这就把「明明存的是是、打开甚至保存后都变成否」的根子从两头堵死啦。悬浮窗同步照旧～"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "再把「是否循环」这一格钉牢一层 🔒 —— 存「是（归零重新计时）」的条目打开编辑页稳稳停在「是」，存「否（归零就停住）」的稳稳停在「否」，跟这条自己存的属性永远一致。上一轮改成「等这一帧画完再选」之后，个别机型还会在更晚一帧把选中态冲回第 0 项「否」，这轮又补了几记延迟复核：页面刚打开那一下盯住这一格，发现被冲走就当场按存着的属性再选回去；用户自己动过下拉框之后就不再插手。保存时也改成一律以「下拉框此刻显示的」为准 —— 看到什么就存什么。挑「是」的照样归零自己接着跑下一轮，悬浮窗同步照旧，老数据一行没动。"),
        ChangelogItem("v1.0.0.38", "2026-10-09", "把「是否循环」这一格的显示彻底钉死 🔒 —— 存「是」的条目打开编辑页直接停在「是」。上一轮修的读回写还漏了一处：下拉框在界面第一次画完之前做的选中会被适配器首装冲掉，显示自己回落到第 0 项「否」，首装冒出来的第一记回调还会把存着的「是（归零重新计时）」写回假 —— 这就是存了「是」打开还显示「否」的根因。现在改成等这一帧画完再选（post 里 setSelected 才稳得住），手挑的回调加了把门：选上之前来的任何回调一律不认。打开编辑页那格显示的就是这条自己存的属性：存「是（归零重新计时）」停在「是」，存「否（归零就停住）」停在「否」。悬浮窗同步照旧，老数据一行没动。"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "修「是否循环」这一格读属性的时机 ⏱️ —— 打开小倒计时那一页时界面还没量完高，它读到的是「没选中」，一读就把这条存着的「是（归零重新计时）」当场抹成「否」，保存落盘后再打开又显示成「否」，怎么打开都对不上、还把存着的属性改坏了。现在等这一帧过去再量，量到 0 / 1 才回写、量到别的取值一律不动，显示与这条存的属性永远一致；挑「是」的照样归零自己接着跑下一轮。悬浮窗同步照旧，老数据一行没动。"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "添了一档内置小倒计时 —— 「今年倒计时」🎊 它就是内置项，跟「每小时」那一脉一个路数：目标时刻是今年结束的那一刻（下一年 1 月 1 日 00:00:00），这一轮走完归零之后，系统自己把目标推到下一年 1 月 1 日接着往下倒，不会停在 0 上，跨年自动接上、一轮一轮自己转；显示方式照这条自己的格式落地（最长 365 天，「周」那截有数，所以「周模式」「周天时分秒模式」也照常给着），名字、颜色、跳秒动画、备注照旧能改，归零照旧只响默认提示音。它已经排在出厂顺序的最后一个，删了能在「＋」菜单「恢复内置」里原样找回来；老条目、老数据一行没动。悬浮窗同步照旧～"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "添 / 改小倒计时那一页顺手补了三处 🛠️ —— 一是「倒计时名称」那栏默认就是空的，以前一进「添加」页就先填着一个「每小时」的名字（想改得先删一次），现在进页面干干净净；二是「是否循环」那颗下拉框会照这条自己存下来的属性显示；三是「内置倒计时」这一档的「显示模式」下拉框把全部显示模式一列到底（原来只摆它自己用得上的那几档），11 档全在。老条目、老数据一行没动。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "添 / 改小倒计时那一页，「倒计时类型」下面多出一颗「是否循环」下拉框 🔁 —— 跟页面上其它下拉框同一套样子：挑「是（归零重新计时）」，这条倒计时归零之后会自己从头再倒一遍；挑「否（归零就停住）」就是原来的跑法。另外内置倒计时也加了「启用自定义提示音」开关，勾上之后挑音那两颗按钮和主界面卡片上那颗提示音按钮都照普通倒计时的样子给着。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "把添加 / 编辑小倒计时那一页「倒计时类型」下面那一层「内置档位」整个收掉啦 🧹 —— 那一级只有挑「内置倒计时」时才亮，现在它不再摆出来，「倒计时类型」只剩「普通倒计时 / 内置倒计时」两格，是不是内置由这一级定，内置这一档是哪一档由这条自己来。编辑已有内置照旧是它自己原来那一档；类型这一级不做记忆，重新安装、首次装都不会被上一次的选择带偏。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "添加小倒计时那一页的「倒计时类型」拆成两格啦 🧩 —— 「普通倒计时」和「内置倒计时」，不再是以前那一长串档名挤在一起；挑「内置倒计时」它就按下面「内置档位」挑的那一档走内置，建出来的这条一直是内置，删了能在「＋」菜单「恢复内置」里原样找回来；一条内置在编辑页改成「普通倒计时」存下去，它就不是内置了，「恢复内置」里也不再挂着它。类型这一级选的那一档不做记忆 —— 每次添加都从「普通倒计时」起。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-08", "当月 / 华都云境悦府 / GTA6 倒计时，还有新建普通倒计时的显示模式里，多出「周模式」和「周天时分秒模式」两档 📅 —— 周模式只写 xx周，周天时分秒模式写 xx周xx天xx时xx分xx秒；编辑页那颗模式下拉框、卡片上的「显示模式」按钮、悬浮窗抽屉里那两颗「上一个 / 下一个显示模式」、锁屏通知看到的都是这一份列表，切完当场生效。每周倒计时最长就 7 天（周那截恒 0），这两档对它们收着不摆。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "把「100年内倒计时」这一档连同它的相关代码一起删干净啦 🧹 —— 「倒计时类型」里那一串收成十个，这一档不再摆在上面、新的一条都生成不了；跟着一起撤掉的还有添 / 改页那六颗「年 / 月 / 日 / 时 / 分 / 秒」下拉框、旁边那颗「启用自定义提示音」开关、按这段时长自动生成名字和备注，以及归零后重新起一轮的那套跑法；升级之后列表里以前挑日子生成的那几条连备份快照一起扫掉，「恢复内置」里那个逐条找回的区块也撤了。那个常量编号留了个空位没回收（免得以后新档撞上老数据认不出）。其余照旧～"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "把挑自定义提示音的路子补回来啦 🔔 —— 普通倒计时、华都云境悦府、GTA6 的新建页和编辑页上，「用默认提示音（点一下选一个）」「清除自定义提示音」那两颗按钮直接摆着，点一下就唤起系统铃声选择器；挑过的铃声名就写在按钮上。只有每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月这八个自己往下跳的内置档照旧不摆（归零只响默认提示音）。列表卡片上的提示音名称一点就改，悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "修下拉框里只剩色块、看不见名字的问题 🎨 —— 「显示模式 / 动画 / 颜色」三颗按钮弹出来的那一层里，动画那八种、颜色那七色全都没了文字，只剩一个圆点色块。根因是名字那格没写 layoutParams，addView 拿到的默认宽度把名字挤没了。这一轮名字显式 WRAP_CONTENT，面板宽度按最长的那条名字先量一遍再定；弹的位置还会自动避开屏幕左右边缘。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "主界面倒计时卡片上的「显示模式」「动画」「颜色」三颗按钮，改成点一下就弹出下拉框来挑 💡 —— 显示模式只在这条倒计时真正能用的档位里列，点中即刻生效；动画一次挑一种跳秒效果；新加了一颗「颜色」按钮，倒计时文字的主题色不用再进编辑页，青 / 品红 / 绿 / 黄 / 橙 / 红 / 金 七色点一下就换，列表和悬浮窗同帧跟着换。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "每5分钟、每10分钟、每小时这三档的「标准模式」统一改成 xx分xx秒 ⏲️ —— 每5分钟、每10分钟原来只写 xx 分（看成「5分」，其实还剩 4 分 59 秒），每小时原来写 xx时xx分xx秒，可它最多就 1 小时，「时」那截只会是 0。现在三档都跟每半小时一个写法，秒一眼就能看见。当日倒计时最多 24 小时，仍是 xx时xx分xx秒，不受影响。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "把上一轮（v151）新加的 29 个周期滚动型内置档整个下线啦 🧹 —— 每2分钟一路到每9分钟，还有每2小时一路到每23小时，一共 29 个，从「倒计时类型」下拉框和出厂顺序里一起摘掉，老数据里已经生成的那几十条也在打开时就清干净了。剩下的十档一个没动，归零响铃、可拖排序、「恢复内置」里找得回来，悬浮窗同步照旧。内置类型编号留着不回收。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "修「每2~23小时倒计时」的目标时刻没按每 N 小时循环 🐛 —— 上一轮把这一批并进通用周期段时，算边界用的是「分钟数除以步长」，商恒为 0，结果全被压成「下一个整点」。这一轮改成老实对齐：目标 = 从当天 00:00 起算的下一个 N 小时边界。每2~9 分钟那七个档不受影响，老五档一行没动。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "每2~9分钟这七个新档的标准模式，显示格式改成了「xx分xx秒」⏲️ —— 上一轮它们最长显示到 9 分，秒那截藏在背后看不见。这一轮把秒摆出来：标准模式直接给分秒模式。可选档位跟着顺过来：标准（=分秒）/ 分钟 / 秒，小时模式、时分秒模式、天数那三档照旧砍掉。每2~23小时那批不受影响。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "添一大批内置小倒计时啦 🎉 —— 短的有每2~9分钟，长的有每2~23小时，一共 29 个新档，跟「每小时倒计时」一个路数：目标时刻由系统一格格往下跳，跨过自动重新倒数、可拖排序、「恢复内置」里照样找得回来。它们都是「内置倒计时」，删了就在「恢复内置」里勾一下捞回来；换音按钮照旧不给（归零只响默认提示音）。显示模式也按周期长短自动收。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "「100年内倒计时」这一档整个下线啦 🧹 —— 添 / 改页「倒计时类型」那一串里不再有它，新生成不了了；升级后列表里原来躺着的那几条（以前挑日子生成的「20小时」「7小时」那些）一并删掉，连备份快照也扫干净，「恢复内置」里那个逐条找回的区块一并撤掉。其余照旧：跨过自动重新倒数、拖排序、「找回小内置」都还在。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "「100年内倒计时」能一条一条单独找回啦 🔍 —— 你生成的每一条删掉的时候都会各存一份快照，点「＋」菜单里的「恢复内置」进找回框，下半截新开一块「你自己生成的『100年内倒计时』」，把删掉的每一条列出来（名字 + 从哪一年几月几日几时起 + 每轮多少时长），想找哪条就勾哪条、只把勾上的捞回来，也能「全选」一把全找回。上半截那十一个小内置照旧勾哪个补哪个。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "修掉「100年内倒计时」挑完目标时刻日期自己多一天的老毛病 🐛 —— 添 / 改页那六颗下拉框里，「月 / 日」两颗原来是从 0 起填的，填值那一下又减一 / 加一抵消着，看着还行实则差一天。现在月 / 日两颗统一成「上头写几就是几」，点哪天就是哪天、自动生成的名字也跟挑的一模一样。顺手修的第二个：生成完一条之后，「倒计时类型」那一串里就再也选不到它了——现在这一档恒在那儿，随时能再挑一条。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "「100年内倒计时」还是内置那一档 💡 —— 能存好几条、个数不限制，同时长的那条依然只留一条；跨过之后照着「生成那会儿离目标还有多久」重新倒计时，名称和备注都按这个差自动生成；删掉之后上界面「恢复内置」按名字勾回来就成。这一轮补的是：能存多条之后，删掉一条时列表里还躺着另一条同类型的，恢复就被挡下——现在删哪条就替哪条单独存一份快照，删掉的条条都能找回来。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "「100年内倒计时」换个跑法 🔄 —— 跨过之后不再跳到一百年的下一个同刻，而是照着生成那会儿隔了多久重新倒计时（生成时离目标 20 小时，归零就接着按 20 小时往下跑），一轮一轮自动转下去；名称和备注也跟着这段时长自动生成。顺带放开可以存好几条（个数不限制），只要时长不撞车就都能生成。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "修好「100年内倒计时」的目标时间跟你挑的时刻对不上 🐛 —— 原来只要挑的那个日子到了（哪怕挑的本来就是过去的一刻），它都会自己往后跳一整个 100 年，生成完看见的目标时间就不是你挑的那天了；现在挑过去的一律停在挑的那个时刻（归零就不动），只有挑未来的日子才照常往下走。顺手修了月份下拉框（原来从 0 月起），改目标时刻保存也不再拿上一次的旧目标盖掉你新挑的时刻。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "修掉刚挂回来的「100年内倒计时」带出来的一处闪退 🐛 —— 点「＋」进「添加小倒计时」那一页一闪就没了：页面里那串自动生成的名字去读六颗下拉框的值时，下拉框还没填过，读到空值当场报错退出。现在这串名字改成真正要用的时候才去算，六连框也提前按当前时刻填好，再也不闪；顺手给读值的那道口子也上了兜底。其它一切照旧。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "把「100年内倒计时」这一档挂回「倒计时类型」下拉框里啦 🎯 —— 挑中它，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框，随便挑 100 年以内任意一个日子就是它的目标时刻；名字和备注都照挑好的时间自动写成「2026年10月7日 12:00:00」这种。旁边那颗「启用自定义提示音」开关也跟着亮，勾上才在下面摆出挑音按钮。它跟「每小时倒计时」「当月倒计时」一个路数，跨过自动重新倒数；同名或同类型已存在会弹「该倒计时已存在，生成失败！」。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "把「100年以内（100年倒计时）」这一档整个下掉啦 🧹 —— 「倒计时类型」下拉框里不再有它，「年 / 月 / 日 / 时 / 分 / 秒」那六颗下拉框和「启用自定义提示音」那颗开关一起收掉；内置倒计时回到十个。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-07", "「100年以内」这一档重新挂回「倒计时类型」下拉框里 🎯 —— 添 / 改页顶上那一排里多了它，挑中「100年以内」，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框，随便挑 100 年以内任意一个日子就是它的目标时刻，名字也照着挑好的时间自动写；挑「普通倒计时」，这六颗下拉框和那颗开关整块收着，目标时刻照旧由「目标日期 / 目标时间」给。它跟「每小时倒计时」「当月倒计时」一个路数，跨过自动重新倒数，同名或同类型已存在会弹「该倒计时已存在，生成失败！」。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把「100年以内」这一档从「倒计时类型」里撤了 🧹 —— 内置倒计时回到十个；要挑 100 年以内任意年月日时分秒，现在改在「普通倒计时」这一档里：类型停在「普通倒计时」，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框，随便挑 100 年以内任意一个日子就是它的目标时刻；那颗「启用自定义提示音」开关也跟着亮。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把上一轮下掉的「100年以内倒计时」请回来了 🎯 —— 「倒计时类型」下拉框里多出这一档，挑中它下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框，随便挑 100 年以内任意一个日子就是它的目标时刻；旁边那颗「启用自定义提示音」开关也跟着亮，勾上才在下面摆出挑音按钮。它跟「每小时倒计时」「当月倒计时」一个路数，备注照着挑的时刻自动写，跨过自动重新倒数。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把上一轮新加的「100年倒计时」整个下掉了 🧹 —— 添加 / 修改页的「倒计时类型」里不再有这一档，「年 / 月 / 日 / 时 / 分 / 秒」那六颗下拉框和「启用自定义提示音」开关一起收掉；现在内置倒计时是十个，华都云境悦府、GTA6 到点就地停 0 没变，其余几个照旧自己往下跳。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把上一轮新加的「100年倒计时」改成跟「每小时倒计时」一个路数 🔄 —— 以前挑中的那个日子跨过去就停在 0，现在跨过那一刻会自动把整个日子往后推一整个 100 年周期（同一个月、日、时、分、秒落到下一个百年），倒计时数字当场从头再数一遍，永远不卡在 0 上；自己挑那六颗下拉框、显示模式、名字、颜色、跳秒动画、备注、以及那颗「启用自定义提示音」开关都照旧。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把上一轮那一位「24小时倒计时」换成了「100年倒计时」🎯 —— 添加 / 修改页把「倒计时类型」挑成它，下面立刻亮出「年 / 月 / 日 / 时 / 分 / 秒」六颗下拉框，随便挑一个日子就是它的目标时刻，到点之后就地停 0，跟华都云境悦府、GTA6 一个路数；还是那一颗「启用自定义提示音」开关，勾上才摆出挑音按钮。这一条跟其它内置倒计时一样，「恢复内置」随时能找回来；同名或同类型已存在会弹「该倒计时已存在，生成失败！」。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把提示音这条线又往细里分了一层 🔔 —— 会自己一格格往下跳的那八个（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月）一律收掉换音入口，归零照旧响默认提示音；华都云境悦府、GTA6 这两个固定日子、以及所有你自己建的倒计时照旧能挑。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "新增「24小时倒计时」啦 ⏰ —— 添加 / 修改倒计时页顶上多了一栏「倒计时类型」下拉框，挑「24小时倒计时」时下面立刻亮出「时 / 分 / 秒」三颗下拉框，随便挑一个时刻，就是它每天归零的时刻，归零之后照旧一格格往下跳，功能和风格跟「每小时倒计时」一模一样；同一页还多了一颗「启用自定义提示音」的开关，只有挑了「24小时倒计时」才亮。生成出来的内置倒计时跟其它倒计时一路数。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把「挑提示音」这条线又往细里分了一层 🔔 —— 这一回收掉的只有会自己一格格往下跳的那八个（每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月），编辑页里换提示音的那一整块直接收掉；华都云境悦府、GTA6 这两个固定倒计时的日子定死了、不跟着日期跳，所以挑提示音这扇门照旧给它们留着。你自己建 / 改的普通倒计时跟它们一路，两边都是原样。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "修正上一版埋的一个坑 🐛 —— 提示音那一块的代码被误塞进了「标准模式收起」的 if 里，普通倒计时根本走不到「显示提示音」那一行，等于自定义倒计时的挑音按钮一直藏着。现在分界清清楚楚：八种内置周期倒计时收起提示音，你自己建的普通倒计时两边都照旧。保存返回 / 挑完音都直接推给悬浮窗和锁屏。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "这一版把「挑提示音」按倒计时类型分了家 🔔 —— 每分钟、每5分钟、每10分钟、每半小时、每小时、当日、每周、当月这些内置周期倒计时，编辑页里换提示音的那一整块直接收掉了，它们归零时照旧响默认提示音，之前挑过的自定义提示音一并作废、统一回到默认；你自己建 / 改的普通倒计时不受影响，编辑页那两颗提示音按钮还在。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "把「挑提示音」这一整个入口撤掉了 🔕 —— 添加 / 修改倒计时页底下原来那两颗「用默认提示音」「清除自定义提示音」按钮连同系统铃声选择器一起删干净，列表卡片按钮行上那颗显示当前提示音名称的按钮也不见了，界面上再不会出现任何选提示音的按钮。倒计时的铃声本身没动，归零时照旧响默认提示音。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "「主题颜色」这排最后一颗从蓝色换成金色啦 🌟 —— 添加 / 修改倒计时页选颜色那七块现在是 青 / 品红 / 绿 / 黄 / 橙 / 红 / 金，新建倒计时的默认色还是头一颗青色。另外把「添加」「修改」倒计时保存返回这一下的悬浮窗同步再夯实一遍：不管从哪进编辑，保存落盘那一瞬间新数据就推给悬浮窗和锁屏，新窗当场建好、当场刷上最新的数字和颜色。每个倒计时自己挑过的旧颜色原样保留。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "修掉一个「倒计时文字全都看不见」的 BUG 🐛 —— 上一版把颜色换成琥珀时，Kotlin 里写成了 6 位十六进制（少写最前面那一对 alpha，实际值变成全透明），列表里每条倒计时的标题和那串大数字都只剩空壳。这回把全工程这类 6 位色值一次性补成 8 位，更新日志里的日期行、吃药那几页的小字也一起看得清。功能没加也没删，就是让数字重新看得见。"),
        ChangelogItem("v1.0.0.38", "2026-10-06", "全站把那抹蓝去掉换成暖橙琥珀啦 🧡 —— 倒计时数字、列表标题、卡片上的时间和小字、编辑页每项标题、「关于」页、更新日志里的日期行、吃药那几页的标题、锁屏时钟、启动图标的外描边，连同「玻璃按钮 / 开关轨道 / 下拉框菜单」那几道渐变，一起从青蓝换成暖橙，文字也从冷白换成暖白，卡片和浮窗上的小字也一起转成暖灰，悬浮窗六颗按钮的浅蓝浅绿字换成浅橙浅黄绿。编辑页「选颜色」的色板也去蓝了。深色墨蓝底没动，玻璃质感照旧。另一头是「加 / 改完立刻出窗」：添一条可见倒计时、或改完某条，保存返回这一下就把最新数据推给悬浮窗，新窗当场建好、当场刷新，不再等服务下一跳。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "多选横幅中间加了「全选」「全不选」两颗 🔘 —— 一点就把整排倒计时全勾上，横幅上的「已经挑了 N 个」跟着往上跳；再点「全不选」全部取消勾、自动退出多选。列表里只有一条的时候这两颗会自己收起来。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「多选删除」时右上角那颗对勾之前还是看不清 🐛 —— 这回是真找着根了：勾选框被盖在前景玻璃卡片底下，哪怕写在卡片后面一行，也只能透过半透明卡片看见模糊暗影。这版给勾选框也加上 6dp 抬升（高过卡片那 4dp），它才稳稳坐到最上层；顺手从小青圈放大成 34 的大圆，没勾的是深底 + 一圈纯白亮圈，勾上的是满不透明亮青实心圆 + 加粗白勾，一进多选整排都看得清清楚楚。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「主界面默认排序」这颗按钮现在只在你真把内置顺序拖过之后才摆出来 🧩 —— 没拖过时列表本来就是出厂顺序，这时候点它也是白点，所以「恢复内置」里干脆不摆这颗按钮、直接提示一句「小内置现在排的就是出厂顺序，不用复位」；只有你长按拖出过跟出厂不一样的顺序，「恢复内置」对话框里才会多出这颗「主界面默认排序」。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「回到默认排序」这颗按钮改名叫「主界面默认排序」啦 🔄 —— 十个内置都乖乖在列表里时，它会同时出现在「恢复内置」对话框里和多选栏底下；点它就把你长按拖出来的顺序整个推倒、回到出厂顺序，还会弹一句告诉你现在是怎么排的，顺手同步到悬浮窗通知。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "对勾之所以看着又暗又不明显，根因是这个勾选框被盖在前景玻璃卡片底下 🐛 —— v122 把它挪到卡片上层，并给卡片内容让出右边一条，保证不压住标题；未勾的是暗色圆底 + 纯白亮青圈，勾上是满不透明亮青实心圆 + 加粗白勾。长按也调快到 300ms，拖动不会被外层滚动抢走。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「长按拖动排序」这次把判定改得直白多了 🤏 —— 长按满之后不再跟时间抢，手指接着往上下滑就是拖排序（到账那一刻会轻轻震一下告诉你「可以拖了」），手指原地抬手才进「多选删除」，两条路再不会互相抢，手慢也拖得动。右上角那颗对勾又亮了一档。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「长按拖动排序」这轮把最后一道坎也刨掉了 🤏 —— 以前长按满之后，手指只要往下挪过 12 像素，就先被列表的「纵向滚动」认领走，拖排序那条路一次都轮不到。现在长按满之后手指往上下走就直接判成「拖排序」并立刻接管；想进「多选删除」就按住别动，半秒之后它自己亮出来。顺手把右上角那颗对勾也提亮了。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「长按拖动排序」这轮把手速放宽了 🤏 —— 长按满之后先留半秒宽限期，这段时间手指还在原地就进「多选删除」，手指一挪开就只认「拖排序」；长按名称照样能把内置项和自己建的倒计时拖着摆，拖完的顺序单独记着、不顶掉出厂顺序。顺手把多选删除时右上角那颗对勾做大做显眼了。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "上一轮加的多选删除和内置自定义排序，这轮把露出来的两个坑一并填了 🐛 —— 多选删除以前一下勾好几条再收起会闪退，现在先照一张名单再挨个收，勾几条都稳；更那个容易踩的是排序：长按之后手指慢慢往上下滑，会被「进多选」那半截抢先触发，拖动排序反而轮不到——现在手指一挪开就只认「拖排序」，「按住不动」才进多选。「回到默认排序」这颗按钮也不再闷声干活了，点完会弹一句告诉你现在小内置排成什么样。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「每半小时倒计时」的「标准模式」这轮换成了「分秒模式」⏲️ —— 只显示 xx分xx秒，前面那截恒为 0 的「时」去掉了。顺手把显示模式做了一次大扫除：任何一项里，只要某一档显示出来的样子跟「标准模式」一模一样，这一档就自动删掉、只留标准模式。哪一项整体只剩一种显示方式，切换功能就整个收起来。长按主界面上任意一条倒计时：按住不动就进「多选删除」，长按之后手指接着往上下滑还是拖动排序。内置倒计时的出厂顺序找回来啦～"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "「玻璃透明度」这根滑块以后只淡化玻璃底啦 💎 —— 以前拖它是整扇悬浮窗一起变淡，连中间那几个跳秒的数字也跟着被压暗。这轮改成只把卡片、抽屉面板、按钮这些玻璃背景调淡 / 调实，标题、备注、目标时间还有倒计时数字始终是全亮清晰的。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "上一轮给短周期内置项加的「分秒模式」这轮从「每分钟倒计时」这里撤了 🧹 —— 它在一分钟里只能写成 0分30秒 这种，看着别扭，下拉框、主界面的模式按钮、悬浮窗、锁屏通知都不再出现这档；「每5分钟 / 每10分钟」和「每小时 / 每半小时」照旧能挑到「分秒模式」。已经设成分秒模式的老数据会自动退到「秒模式」。悬浮窗同步照旧。"),
        ChangelogItem("v1.0.0.38", "2026-10-05", "显示模式又动了一轮，还是围着那几个短周期的内置项 🔄 —— 新加了一档「分秒模式」（只显示 xx分xx秒）。「每分钟 / 每5分钟 / 每10分钟」这三个现在都能挑到「分秒模式」了；「每小时 / 每半小时」原来那档「时分秒模式」这轮换成了「分秒模式」。悬浮窗顶部那行模式名这轮也改成跟着每秒一起刷。其余倒计时的档位一个没动。"),
        ChangelogItem("v1.0.0.38", "2026-10-04", "悬浮窗里那颗按钮改叫「关闭悬浮窗」啦 🔘 —— 它点下去是干脆把悬浮窗整个关掉（跟标题栏最右边那颗「关闭」完全一样的作用），以前写的「收起悬浮窗」容易让人误会成只是把它缩回小图标；主界面列表卡片上那颗按钮这轮没动。倒计时数字、目标时间、显示模式这些功能一个字没改。"),
        ChangelogItem("v1.0.0.38", "2026-10-04", "悬浮窗和列表里的按钮说法这次都改清楚啦 🏷️ —— 抽屉里那两颗按钮由「上一模式 / 下一模式」改成「上一个显示模式 / 下一个显示模式」，列表卡片上那颗「模式」也写成「显示模式」，叫法跟下拉框里的一一对上；「展开 / 收起」统一说成「展开悬浮窗 / 收起悬浮窗」，不会再跟锁屏通知、更新日志里的展开弄混。"),
        ChangelogItem("v1.0.0.38", "2026-10-04", "悬浮窗抽屉里的「目标:」以前只在开抽屉那一刻算一次，退到后台、跨过整点或日期之后就停在旧值 🐛 —— 现在它跟跳秒一起刷新，内置项跨到下一个周期自动换成新的目标时刻，退到后台不动也跟着变，悬浮窗与主界面、锁屏通知看到的始终是同一个时刻。"),
        ChangelogItem("v1.0.0.38", "2026-10-04", "把上一轮新加的「每分钟 / 每5分钟 / 每10分钟」的显示模式再收一收 🔄 —— 「时分秒模式」从可选里拿掉了（前面那截「时」恒为 0），「每分钟倒计时」顺带把「分钟模式」也一并拿掉，现在每分钟那个只剩「标准 / 秒」两档；每5分钟和每10分钟那两个剩「标准 / 分钟 / 秒」。其余倒计时的显示模式一个没动。"),
        ChangelogItem("v1.0.0.38", "2026-10-04", "新增「每分钟 / 每5分钟 / 每10分钟」三个内置项啦 ⏱️ —— 跟每小时倒计时一个模子：目标时刻是系统自己往下跳的，到点归零接着往下跳，不用你管；能改的东西也一模一样：名字、颜色、显示模式、跳秒动画、提示音、备注都能照常改。风格跟每小时严格对齐，位置排在「每半小时倒计时」下面一位，悬浮窗、锁屏通知跟主界面共用同一份数据和同一个刷新节奏，三处完全同步。"),
        ChangelogItem("v1.0.0.38", "2026-10-04", "把「当月 / 华都云境悦府 / GTA6」这三条的显示模式，从「标准模式」（x周x天x时x分x秒）改成了「天时分秒模式」（x天x时x分x秒）📅 —— 前面那截「周」去掉了，一眼扫过去剩下的天数最直接。其余倒计时的显示模式一个没动。悬浮窗同步跟着改，编辑页的模式下拉框照旧能手动切到任何一档，老数据一打开就自动升级到天时分秒。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "倒计时卡片的背景调透了一点 💎 —— 压在卡片上的那层白玻璃和深色底都降了透明度，背后的模糊光斑能隐约透上来，整体看着更通透、没那么「糊」。左滑露出来的「编辑 / 删除」照旧被卡片挡住，不会跟倒计时文字叠在一起。这轮颜色没动，还是那抹青蓝，功能也一个字没改。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "主题颜色又换回原来那抹青蓝啦～ 💙 上一轮改成暖橙琥珀，现在按你说的原样还原：全站强调色回到 #00FFFF，收尾那档深色回到 #00C2D9。列表顶上的标题、卡片上的时间和小字、编辑页每项标题、「关于」页、更新日志里的日期行、吃药那几页的标题、锁屏时钟、启动图标的外描边，连同玻璃按钮 / 开关轨道 / 下拉框菜单那几道渐变，全都跟着变回青蓝。悬浮窗也一并按原来的配色同步过来了。每个倒计时挑过的自定义颜色一个没动，深色墨蓝渐变底也没动。顺手把上一版「倒计时文字全都看不见」的坑也填了：那轮改色时有几处色值只写了 6 位十六进制，最前面那对 alpha 丢了，这轮换色时把全工程这类色值统一补齐成 8 位，数字重新看得见。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "主题颜色换成暖橙琥珀啦～ 🧡 这一轮动的只是「颜色」，功能一个没改。以前全站的强调色是那抹青蓝，现在统一换成暖橙琥珀 #FFA63D，收尾那档跟着换成偏深的琥珀，高光那层还是原来的白色叠加，所以亮面没变浑、玻璃质感照旧。悬浮窗这儿一并同步过来了：六颗按钮的浅蓝字全改成跟主色一路的浅橙，吃药页那行浅青也一样跟着走。每个倒计时挑的自定义颜色照样各是各的、互不干扰，深色的背景一层完全没动。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-03", "更新日志再回头扫了一遍 🧹 —— 凡是跟「授权码」「意见反馈」「附带运行日志」这三类沾边的记录，一条不落地全在前面顶上整条划掉：说的全是早拿掉的老功能，现在想反馈就去「关于」页底下那个 GitHub 项目主页链接，运行日志也不再随反馈打包。没划掉的照旧能看、能照着用，本轮一个功能没动。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "更新日志里后来整个撤掉的功能，条目这回都标清楚啦 💡 —— 凡是前面带 ✗ 并且整条划掉的，说的都是已经拿掉的老功能，划掉它是让你一眼看出「这条为啥点不着」。功能本身早就换成别的样子了。剩下的没划掉的照旧能看、能照着用。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "左滑动画改成了「按钮淡入 + 倒计时淡出」这套交叉淡变 💫 —— 以前往左推，底下的「编辑 / 删除」冒出来、上面那块倒计时只是轻轻放大一点，看着像两层叠着没挪窝；现在手指往左推多少，两颗按钮就淡入多少、从右边滑出来，同时上面那块倒计时整片跟着淡掉，推到头几乎全透明；往右滑收回正好反过来，收完稳稳落位不会突然闪一下。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "左滑 / 右滑动画这次做明显啦 💫 —— 现在手指推多少，两颗按钮连同整块操作面板就跟着淡入并从右边滑出来，编辑先出场、删除稍跟上，各自带一点横向位移、从小放大回原位，前景卡片同时轻微放大做出被推开的层次感；松手吸到位那一下还是轻轻的橡皮筋回弹。顺手修了三处会让动画看着像「没生效」的毛病。再上手、进拖动排序都会立刻交回手指接着当前进度走。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "左滑动画 / 右滑动画都补上啦 💫 —— 以前卡片往左推的时候，底下的「编辑 / 删除」是一下子整层冒出来，松手吸附也只有一段干巴巴的直线位移；现在手指推多少，两颗按钮就淡入多少，松手吸到位的那一下还有个轻轻回弹的橡皮筋感，收回时按钮按同样的顺序倒着淡回去、稳稳减速就位。拖动过程中如果又上手了，动画会立刻交回手指、接着当前进度走。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "~~左滑操作层里的「提示音」按钮撤掉啦 🧹 —— 以前把卡片往左一滑，底下会露出「编辑 / 提示音 / 删除」三颗，现在只剩「编辑 / 删除」两颗，摆得更松快，也少一个手指常点错的地方；换提示音不用再往左滑找了，就在卡片按钮行上那颗「提示音名称」，点一下照样打开系统铃声选择器～~~另外修了一处别扭的手感：从「编辑 / 删除」点开的那道确认提示框，只要点了取消，卡片会自己顺手滑回原位，不再敞着操作层晾在那儿，下次想滑开还得先往回拨一下～"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "「显示模式」那个下拉框连同跟它联动的那套代码一起拿掉了 🧹 —— 编辑页现在只留一个模式下拉框，就是原来摆在它上面的「标准模式」，只是从今往后它一个人说了算；底下那句小字也跟着改成「选好之后这个倒计时的显示方式就定啦，以后随时能回来改，没有次数限制」。列表卡片上的「模式」按钮、悬浮窗的循环切换、内置项按周期自动收档这些老规矩一点没变。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-03", "编辑页多了一颗「标准模式」下拉框，就摆在「显示模式」上面哟 🧩 —— 它的选项跟显示模式一模一样（会按这条倒计时的类型自动把装不下的那几档收掉），选好之后下面那颗「显示模式」立刻跟着跳到同一档；反过在「显示模式」里选，上面这颗也会同步过去。两颗框都能反复改、没有次数限制，最后存盘的还是「显示模式」挑中的那一份；两颗用的都是跟颜色、动画那几个下拉框同款的玻璃样式。另外按周期把不太合理的模式档收掉了：每小时 / 每半小时倒计时（最多 1 小时）不再摆「小时模式」（恒显 0 时）、「天时分秒模式」和「天时分模式」（前面那截恒 0 天），只剩 标准 / 分钟 / 秒 / 时分秒；当日倒计时（最多 24 小时）去掉「天时分秒模式」和「天时分模式」、「小时模式」留着，它能实实在在显示 x 时。已经设成这些模式的旧数据会自动退回标准模式，列表、悬浮窗、模式循环切换都跟着一起变～", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-03", "新增「每半小时倒计时」，内置项凑成七个～ 🕐 它跟每小时倒计时一个模子：目标时刻是系统自己往下跳的，备注也跟着变（半点前写「距离17点半结束」，过了半点自动变成「距离18点整结束」）；显示模式里同样不给它「天数」这一档，位置排在「每小时倒计时」下面一位。另外把内置倒计时改 / 删的规矩定死了：以前改一改目标时刻就会把它降级成普通倒计时，现在不会了。时间那栏填了也不存，想改的别的照旧。改之前和收起之前都会先弹一句说明，点「继续」才往下走；收起的内置项还是能在「恢复内置」里找回来。"),
        ChangelogItem("v1.0.0.38", "2026-10-03", "「意见反馈」这个功能整个拿掉咯～ 🧹 这条反馈路从第一版一路做到现在，始终绕不开那几件事：授权码得先去邮箱里开一个 16 位的开关、各家邮箱的 SMTP 脾气不一样总有一家连不上、免授权码的公共通道在手机上又基本送不到——修一圈不如算了，这版索性把「意见反馈」整块拆干净：反馈页、「意见反馈」按钮、发信那套、附件共享器、清单里专门为它开的明文放行，全都不留；「关于」页底下原来放反馈按钮的位置，换成去 GitHub 项目主页的链接，有想法欢迎去仓库里开个 Issue 跟我说一声～ 别的功能一点没动。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "填了授权码还是发不出去？这回收拾的是「卡住不动」和「越点越不行」这两处硬伤 🔧 —— 一是连接根本没有超时，现在 20 秒连不上就换下一条路；二是认证一失败还要再撞一遍，现在 5xx 这类硬伤一律不再重试，并且把服务器那句原文里的「登录频率受限」单独翻成人话告诉你。顺带修的几处：Android 9 起默认拦明文连接，清单里补了放行；只是「没连上」时不再去撞那条注定失败的免授权码通道；失败框里也写透了「填的是授权码不是登录密码」和「别反复点，会被临时限流」。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "填了授权码还是发不出去？这回 SMTP 那几条路都捋直了 🔧 —— 上一版有两个坑：一是 587 那档升级加密的做法不对，新连接上服务器照旧先回一句明文的「220」再要 EHLO，App 却还在等 EHLO 的回话，硬生生干等到超时；二是登录只认一种方式，一被拒就整条路断掉。这版把明文连接就地升级成加密、握手后先把 greeting 接住再 EHLO，登录改成优先挑服务器愿意的那一种，认不出的域名也会按 smtp.域名 + 端口去试。另外填了授权码以后点提交，先走你自己那条路再试免授权码的公共通道；反馈页也多了个「测一下能不能发」。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "修掉反馈「假发送成功」的问题 🐛 —— 上一版只要网页回了 200 就算发出去了，可免费收件通道的回包里 success 的值是 false，里面正好也有 success 这四个字母，于是明明没送出去，屏幕上还是弹「反馈已发送」。这版改成只看回包里 success 的值：是 true 才算送到，回的是网页就一律当没送到，并且把站点给的原因翻译成中文摆到对话框里。配套的还有反馈页那行状态说明，也改成实话。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "这版反馈不用授权码也能直接发啦～ 💌 上一版点「提交反馈」要先填自己的邮箱、再填一串 16 位授权码，光是找那个开关就劝退了不少人。这版换了个更省事的路子：反馈页下面那两行降级成备用通道，平时什么都不用填，点「提交反馈」直接在后台把信投到 baigao110@qq.com，不打开邮箱应用，附件照旧跟着过去，屏幕上同步显示「反馈已发送，请耐心等待回复！」。万一默认这条通道当时网络不好没通上，会自动接着用你填的邮箱那条路再发一次。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "意见反馈这回点一下就发完，不用再被拽去邮箱应用咯～ 💌 以前点「提交反馈」会打开你手机上那个邮箱 App，收件人、标题、正文、附件都替你备好了，可你还得自己再点一下「发送」。这版在反馈页下面加了「直接发送设置」两行：填上你的邮箱，再填上邮箱授权码，在这一台手机上填一次就一直记着。以后点「提交反馈」，App 直接自己在后台把信发到 baigao110@qq.com，屏幕上立刻弹一句「反馈已发送，请耐心等待回复！」。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「附带运行日志」这回真成邮件附件啦～ 📎 上一版只勾日志的时候干脆不给附件、把全文贴在正文里，结果你反馈「日志加不进邮件附件」。这版把它重新挂回去，fb_log.txt 妥妥进撰写页的附件栏。做法也不再死盯一种类型，把「分享一个文件」「分享一段文字」「mailto 直投」三种样子按顺序摆出来挨个试，谁在这台手机上真接得住、真把 txt 挂进撰写页就用谁。顺手也收拾干净了：附件真挂上时正文只留一句说明；万一哪一家邮箱还是不认这个 txt 附件，同一份全文照样贴在正文里。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」这回总算不让你再挑一次图片或视频了～ 📎 前面把「具体文本类型」和「通配类型」两条路都走了一遍，进邮箱还是空空的、得你自己再添一次。回头看也就想明白了：一个 txt 附件，偏偏是各家邮箱最难肯挂上去的东西。这版干脆不跟它较劲：只勾运行日志的时候，把日志全文整整齐齐贴在邮件正文里，再用各家邮箱都老老实实接的 mailto 直投这条路打开邮箱，一个附件都不带过去。没勾日志、或者日志和图片 / 视频一起发的时候，走的还是原来那条把图片视频稳稳挂上去的正规路。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」进邮箱还是空的、要你自己再挑一次图片或视频，可只挑图片 / 视频的时候偏偏好好的？ 🐛 上一版虽然不再偷偷退回短路，可断点没堵干净：只勾日志的时候，分享出去的那份 Intent 给的是具体文本类型，邮箱一看就把它当成「转发一段文字」，附件那一份直接不理。这版把分支选对：附件里只要全是纯文本，就先按「分享一个文件」的套路去开邮箱；万一这家邮箱只认文本那一路，就再按文本的路开一次；两条都不成才逐级退到 mailto 直投、分享列表，最后退到复制内容备用。另外不管走到哪一步，运行日志都会同时贴在正文里。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」进邮箱还是空的、要你自己再挑一次图片或视频，可只挑图片 / 视频的时候偏偏好好的？这回断点终于找着了 🐛 —— 提交前有一步「先看看有没有应用接得住这份分享」的判断，可那个查法只认自己声明了 DEFAULT 的入口，而邮箱的撰写页偏偏没给「文本类」这种分享声明 DEFAULT，于是只勾日志的时候，查出来的是微信、QQ、蓝牙那批，邮箱压根不在表里，被判定成没人接，就悄悄退到了 mailto 直投那条短路。这版改成不靠「先猜有没有人接」，直接去开，接不上它自己会报错、我们才退下一层；提前把附件读取权限交给哪些应用的查法也放宽了。另外，运行日志这一份内容会同时贴进邮件正文。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」反馈进了邮箱，附件栏却还是空的、要你再自己挑一次图片或视频～ 这回的毛病正好只在日志这一条路上，两个真断点一起修掉 🐛 —— 第一，运行日志是个 txt，可附件共享器里 txt 压根没排在类型表里，日志被判成通用二进制文件，分享出去的 Intent 类型也就跟着掉进通配类；而国产邮箱只认写死的类型，看到通配类就当没附件。这版把 txt / log / json / csv 这些文本类型补进表里认成 text/plain。第二更隐蔽：写运行日志是把一整段包在一个兜底里的，读任何一小步没成，整份日志就作废，而提交那边会把它当没附件安安静静跳过。这版把日志拆成几段各自兜底。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「反馈附件在邮箱里要再选一次」这版继续往根上挖，两个真毛病一起修掉了 🐛 —— 第一，附件递过去的时候「扩展名和真实内容对不上」：相册 / 微信 / QQ 转手过来的图，名义后缀可能是 heic、jfif，甚至干脆没有后缀，邮箱是靠扩展名判断自己认不认得这个附件的，认不出就当没附件。这版提交前先把附件全部整理一遍——按文件头判断真实类型，再存成标准名字。第二，之前直接 startActivity 唤起邮箱，常常唤起来的是它上次没写完、还挂在后台的旧写信页；这版打开邮箱时带上「开新写信页」的标记。另外提交前的自检也真刀真枪了。还是没装邮箱的机型，依次退到分享列表、再到「复制内容备用」。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」的附件这次走最稳的那条路送进邮箱啦～ 📎 上一版虽然是把附件递过去了，可走的是「ActionSendTo」（mailto 直投）这条短路：Gmail 和不少手机自带邮箱压根不认这条路上的附件。这版把顺序换了：优先开「ActionSend + 收件人已填好」，邮箱撰写页一打开，图片 / 视频 / 运行日志就都整整齐齐挂在上面；万一这台手机上这份 Intent 没人接，才退回 mailto 那条路；再不行给一个分享列表让你挑一个能收附件的发。顺手也把入口看门加严了。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」带图片 / 视频进邮箱这次真的带上了～ 📎 以前反馈里选好图片、视频，一点提交跳进邮箱，附件栏却是空的，还得你自己再点一下「添加附件」重挑一次——根子出在分享给邮箱的那份 Intent「没把附件喂明白」：（一）Intent 没带类型；（二）附件是以「一串附件列表」的形式递过去的，只认单个附件的邮箱取不到；（三）读取权限只在剪贴板数据上自动发过。这版三处一起补齐，提交前还会先自检一遍。提交成功的那句提示也写明白了，会告诉你这次一共带上了几个附件。没装邮箱时的老路子照旧。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」来啦，有话直接跟我说～ 💌「关于」页最下面现在有个「意见反馈」按钮，点进去就能给我写信：昵称和联系方式都可以留空，把问题现象写两句就行；还能一起加图片或视频，最多 9 个，已经选好的会排成一排小胶囊，点一下就撤掉。想让我更快找到原因就把下面的「附带运行日志」勾上，它会把机型、系统版本、应用版本、当前倒计时列表和那几个常亮开关的状态打包成一个 txt 一起发过来。点「提交反馈」会打开你手机上的邮箱应用，收件人已经填好是 baigao110@qq.com，你只要点一下发送就到啦～ 手机上没装邮箱的话，下面那个「复制内容备用」能把内容复制到剪贴板，粘到微信 / QQ 发给我，照样收得到。", obsolete = true),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「锁屏通知常亮」这次是真的接上了～ 🔆 前面两版都不灵，根子在系统的硬规矩：FLAG_KEEP_SCREEN_ON 只能在 Activity 里设，服务 / 悬浮窗设了不算；Android 12 往后，后台应用手里的唤醒锁还会被系统直接收走。这版换成正解：锁屏之上挂一个全透明的常亮页，它只做一件事——把屏幕按住不睡；它不抢焦点、不拦触摸，锁屏照常能划、通知照常能点。屏幕一点亮、解锁、开关变化都会立刻接上或收掉，「关于」页那行状态也会实时告诉你「常亮已接上 / 还没接上」。第一次打开开关时会引导你给「显示在其他应用上层」权限。"),
        ChangelogItem("v1.0.0.38", "2026-10-01", "「锁屏通知常亮」这版对症修好啦～ 🔆 上一版只在屏幕亮着的时候接常亮，而且靠的是「透明小窗 + FLAG_KEEP_SCREEN_ON」，可锁屏状态下系统压根不让第三方 App 挂悬浮窗，小窗挂不上、退路的唤醒锁又只保 CPU 不保屏幕——所以锁屏之后到点照样熄屏。这版换成「屏幕级唤醒锁」（SCREEN_BRIGHT_WAKE_LOCK），它是系统电源层直接按住屏幕，不经过窗口层，锁屏界面照样有效；再叠加 ACQUIRE_CAUSES_WAKEUP，接上的一瞬间顺便把屏幕点亮。开关一关、通知一收、服务一停都会立刻撤掉，兜底闹钟每 10 秒也会接管一次。"),
        ChangelogItem("v1.0.0.38", "2026-10-01", "「关于」页新增「锁屏通知常亮」开关 🔆（和「UI 界面常亮」「悬浮框常亮」长得一模一样，都是左右滑动开关）：打开后，只要锁屏 / 通知栏上还挂着倒计时的通知，手机屏幕就一直亮着不睡，方便你在锁屏界面上一直瞄那个倒计时；关掉的一瞬间就撤掉常亮，屏幕熄多久完全交给手机系统设置里的时间来管。它只在「屏幕亮着」的时候默默挂一个透明小角、息屏自动收掉，没展开任何倒计时时也不会偷偷常亮；就算没给悬浮窗权限也照样能用。下面那行状态会实时告诉你「屏幕正被按在亮着 / 已经熄了」。"),
        ChangelogItem("v1.0.0.38", "2026-10-01", "「锁屏界面里看不到倒计时」这版对症修好啦～ 🔔 头号原因是锁屏通知以前用的是「静默通知」（低优先级），国产手机的系统默认只把重要通知摊在锁屏上，静默通知要么被折进「其它通知」要么干脆不显示——这版把锁屏通知换成高优先级渠道（锁屏必显、也不会再被折叠），同时保留「只在第一次提醒、不每秒响一声」；升级时会重建频道，新设置立刻生效。第二个原因：锁屏后系统会冻住后台，倒计时不再跳秒——这版新增了「唤醒刷新」兜底闹钟，息屏 / 锁屏状态下也能把锁屏通知刷成最新。「关于」页的锁屏设置区还加了「测试锁屏通知」按钮和一行实时状态。"),
        ChangelogItem("v1.0.0.38", "2026-10-01", "锁屏 / 通知栏倒计时这版又加固啦～ 🔔「关于」页的「锁屏通知显示」开关打开后，服务一启动就立刻把倒计时挂到锁屏通知栏上，不再等下一秒才慢慢冒出来；就算没给悬浮窗权限也照样能在锁屏上走秒。另外「关于」页新增了「锁屏上看不到？点我打开锁屏通知设置」按钮，一点就跳到本应用的应用信息页。归零提醒和锁屏倒计时也彻底分成两段通知 id，不会再互相顶掉变成一条啦。"),
        ChangelogItem("v1.0.0.38", "2026-10-01", "「关于」页新增「锁屏通知显示」开关（默认是开着的哦）🔔 —— 把列表里「展开」过的倒计时，以常驻通知的形式显示在通知栏和锁屏界面上——标题、倒计时数字、模式、备注还有主题配色都和悬浮窗里一模一样，每秒跟着跳秒，不用展开手机也能一眼瞄到还差多久；点一下通知就直接回主界面啦。它跟悬浮窗是同一批数据、同一个刷新节奏，两边永远一致。想不想让锁屏上也有一个悬浮窗，交给「锁屏通知显示」就行啦～"),
        ChangelogItem("v1.0.0.38", "2026-10-01", "内置倒计时的备注改得更清楚啦 📝 —— 每小时倒计时会同步显示具体的整点小时（16 点多就写「距离17点整结束」，过整点自动变成「距离18点整结束」），当日 / 每周 / 当月倒计时也一起改成具体的结束时刻；列表和悬浮窗都会实时跟着变，不用重启 App 哦～「关于」页还新增了「备注时间制式」下拉框（24 小时制 / 12 小时制），换成 12 小时制后备注会显示成「距离下午5点整结束」这样的说法，选完立刻生效。"),
        ChangelogItem("v1.0.0.38", "2026-09-29", "界面文字都换成软乎乎的可爱说法啦～ 💕 提示、按钮、对话框统统变温柔；「关于」页还新增了 GitHub 项目主页链接，点一下就能在浏览器里逛本仓库。版本还是 v1.0.0.38 哦。顺手把两处按钮文字改清楚啦：删除小倒计时显示「好呀，收起」，找回小内置倒计时显示「好呀，找回」，不再让人看不懂咯～ 另外呀～「+」号菜单的按钮也从长文字药丸换成了紧凑的青色圆形图标按钮（＋/药/?/复），四项沿弧线真正均匀排成扇形、整整齐齐包围加号。这版再把设置项都换成更好用的样式啦：「关于」页的「屏幕常亮」拆成了「UI 界面常亮」和「悬浮框常亮」两个左右滑动开关；更新提示方式、吃药提醒的「每天次数」也都改成下拉框选择啦，开关统统换成左右滑动开关。"),
        ChangelogItem("v1.0.0.37", "2026-09-26", "吃药提醒新增「每天次数」选择（1/2/3/4 次 / 天），可逐次自定义时刻与「服药时机」（空腹服/餐前服/随餐服/餐后服/晨服/睡前服/间隔固定服/发作前服）💊；「关于」页与吃药日历同步展示各次彩色时刻与时机。版本升级至 v1.0.0.37。通知栏不再常驻显示「每日吃药提醒 · 已开启」状态提示；再次大幅增强跳秒动画表现（第二轮）：缩放/蒸发/坠落/像素化/碎片化/燃烧/震撼的幅度与抖动再加大、更醒目。"),
        ChangelogItem("v1.0.0.36", "2026-09-25", "修复「免责声明」提示框换行显示异常（去掉字面换行符）🐛，版本升级至 v1.0.0.36。"),
        ChangelogItem("v1.0.0.35", "2026-09-25", "「关于」页新增「免责声明」按钮（位于「检查更新 / 说明 / 更新日志」下方），点开即弹出免责声明 📄；首次安装或升级到新版本后，打开软件会自动弹出一次免责声明（看过即记录，不再重复打扰）。"),
        ChangelogItem("v1.0.0.34", "2026-09-25", "检查更新新增「忽略更新」🔕：在「发现新版本」提示框里点「忽略更新」后，当前显示回退为「已是最新版本」；下次再点「检查更新」仍会重新检测并再次弹出提示框（忽略不持久化，每次检查都重新拉取远程版本）。"),
        ChangelogItem("v1.0.0.33", "2026-09-25", "吃药提醒通知：关闭状态下取消每日吃药提醒通知 💊；开启状态下显示「已开启」状态通知，通知内容同步显示启动 / 关闭状态（已开启时显示「每日吃药提醒 · 已开启」+ 下次提醒时间）。"),
        ChangelogItem("v1.0.0.32", "2026-09-25", "更新提示方式：在「关于」页新增三种方式的行为说明 💡 —— 自动：检测到弹窗拦截类工具时自动改成只发通知；弹出提示：总是弹出更新提示页；只发通知：不弹任何界面，只在通知栏发一条（最不容易被拦）。吃药提醒：关闭状态下「关于」页隐藏提醒时间、状态等全部子项，仅保留开关。"),
        ChangelogItem("v1.0.0.31", "2026-09-25", "吃药提醒默认改为关闭状态 💊 —— 关闭时「关于」页只保留开关与提醒时间，隐藏「吃药日历」「下次提醒」「测试提醒」「后台自检」等子功能；打开开关后这些功能自动恢复显示。"),
        ChangelogItem("v1.0.0.30", "2026-09-21", "通知全面对齐安卓系统内置闹钟 / 计时器 🔔 —— 吃药提醒、版本更新、倒计时归零三类通知改为「常驻」—— 不会被随手划掉，退出或关闭软件后也一直在通知栏；统一使用闹钟样式：锁屏可见、免打扰也响；吃药提醒点「已吃药」或点开吃药日历即消失；版本更新进 App 即消失；倒计时归零出现「停止」按钮，点一下清除。"),
        ChangelogItem("v1.0.0.29", "2026-09-19", "内置倒计时的显示模式按各自周期定制，不再一律显示「周天时分秒」📅 —— 当日倒计时标准模式改为「xx时xx分xx秒」（周数和天数永远是 0）；每周倒计时标准模式改为「xx天xx时xx分xx秒」；当日倒计时下线「天数模式」（永远显示 0 天）。其他倒计时、其他显示模式一律不变。"),
        ChangelogItem("v1.0.0.28", "2026-09-19", "通知体检改成「照着做」而不是「看得懂」🩺 —— 底部主按钮直接写明先修哪一项，哪一项不对勾，按钮就显示「去修第 N 项」，点一下直达那一项的对应设置位置；体检每一项下的小按钮也标了序号；全部正常时不显示「去修」按钮，只留一个居中的「关闭」；序号按实际项数动态编排。"),
        ChangelogItem("v1.0.0.27", "2026-09-19", "有新版本要尽快知道 + 更新提示不再被拦截软件关掉 🔔 —— 后台检查间隔从 2 小时缩短到 20 分钟，开机 / 解锁 / 升级后 1 分钟就先查一次；新增「更新提示方式」（关于页按钮）：自动 / 弹出提示 / 只发通知，自动模式会检查系统里是否开着「跳过开屏广告 / 弹窗拦截」类的无障碍服务，一旦开着就自动改成只发通知栏通知；更新提示页延后时间 800 毫秒 -> 1.5 秒；通知体检新增「后台巡检」一项。"),
        ChangelogItem("v1.0.0.26", "2026-09-19", "修复「退出 / 关闭软件后就收不到通知」🔔 —— 新增「通知体检」（关于页）：逐项检查通知总开关、通知权限、吃药提醒渠道、版本更新渠道、精确闹钟、电池优化、后台限制；通知渠道换新并提到最高级：免打扰时也会响、锁屏可见；吃药提醒新增全屏提醒；更多时机自动重挂任务与闹钟；进入应用时若发现通知被挡住，会自动提示一次。"),
        ChangelogItem("v1.0.0.25", "2026-09-19", "更新提示不再用对话框，改成独立页面 📄 —— 原先一打开 App 就弹的对话框，被「跳过开屏广告 / 弹窗拦截 / 广告过滤」类工具当成弹窗广告自动点掉；现在改成一个透明底的普通页面 + 同一张玻璃卡片，并延后 800 毫秒再拉起，下载进度同样直接显示在这张卡片上。"),
        ChangelogItem("v1.0.0.24", "2026-09-18", "继续加固「改了下次提醒时间之后，退出 / 关闭软件就收不到提醒」💊 —— 到点后 2 分钟、30 分钟各补响一次；精确闹钟与系统闹钟在同一时刻再挂一路，一共四条闹钟路线；后台巡检再加一个入口；改完时间会弹出引导并可一键去「允许后台运行」。"),
        ChangelogItem("v1.0.0.23", "2026-09-18", "修复「改了下次提醒时间之后，退出 / 关闭软件就收不到提醒」💊 —— 根因是手动指定的那一次被去重逻辑拦下了，现在手动改的时间一定会提醒一次，响过之后才回到每天固定时刻；顺带修掉推算「下次时间」时提前作废手动设定的副作用。"),
        ChangelogItem("v1.0.0.22", "2026-09-18", "吃药提醒的「下次提醒时间」可以自己改了 ⏰ ——「关于」页吃药卡片新增「下次提醒」按钮，点开可指定具体的年 / 月 / 日 + 时刻；改的只是即将到来的那一次，这次响过之后自动恢复为每天固定时刻；同一个对话框里有「恢复常规」按钮，「后台自检」与状态行会标明当前的下次提醒是不是手动改过的。"),
        ChangelogItem("v1.0.0.21", "2026-09-18", "修复「退出 / 关闭软件后收不到吃药提醒」💊 —— 改为三重保障：系统闹钟 + 每日重复闹钟 + 后台巡检（15 分钟一趟、重启自动恢复）；真错过了也会补发；同一天只会提醒一次。「关于」页吃药卡片新增「测试提醒」与「后台自检」。"),
        ChangelogItem("v1.0.0.20", "2026-09-18", "新增「每日吃药提醒」💊 —— 默认每天 09:00 弹通知，通知上直接点「已吃药」即可记录当天；提醒时间可随时改；「关于」页可一键开启 / 关闭；新增「吃药日历」：按月查看哪天吃了、哪天没吃（青色=已吃，淡红=漏了），可切换月份，点过去或今天的日期还能补记 / 撤销。内置倒计时新增「每周倒计时」。"),
        ChangelogItem("v1.0.0.19", "2026-09-17", "「关于」页新增「说明」按钮 📖 —— 点开即看到完整使用说明，同时重写了项目 README.md。"),
        ChangelogItem("v1.0.0.18", "2026-09-17", "再修「恢复内置」对话框 🛠️ —— 上半部分仍看不全，改为在弹出「之前」先量一次内容高度，超过屏幕 45% 就把内容区定高；文字看不清，说明、选项标题 / 备注、「全选 / 全不选」全部提亮并加了描边阴影。"),
        ChangelogItem("v1.0.0.17", "2026-09-17", "再修「恢复内置」对话框 🛠️ —— 选项多了之后上半部分文字看不到，窗口高度是按完整内容在弹出那一刻定下来的，只压缩内容区不重设窗口，窗口依旧超高、居中后上下被裁；现在超过屏幕 70% 时连窗口高度一起收，滚动区按权重吃掉剩余空间，标题、说明与「确定 / 取消」始终露在外面，中间内容可上下滚动。"),
        ChangelogItem("v1.0.0.16", "2026-09-17", "修复「恢复内置」对话框文字看不全 🛠️ —— 对话框撑到屏幕宽度的 92%，长句子不再被挤截断；选项多时内容区可滚动；选项文字改为占满整行自动换行，行距收紧，备注也更清楚。"),
        ChangelogItem("v1.0.0.15", "2026-09-17", "修复「恢复内置」对话框里看不到可勾选项 🛠️ —— 原生对话框的说明文字和选项列表互斥，设了说明文字，列表就不会显示；现在把缺少的内置倒计时逐条列成可勾选列表，默认全选，可只勾其中几个，另配「全选 / 全不选」，确定后只恢复勾选的那些。"),
        ChangelogItem("v1.0.0.14", "2026-09-16", "再修「有版本更新却收不到系统通知」🔔 —— 新增 JobScheduler 后台检查（系统统一调度，有网络才执行、每 15 分钟一趟、重启自动恢复）；之前只靠闹钟唤醒，而息屏省电模式会切断网络；「关于」页新增「开启通知 / 测试通知 / 允许后台运行」三个入口，并显示后台检查状态。"),
        ChangelogItem("v1.0.0.13", "2026-09-16", "「恢复内置」现在会连同设置一起还原 🔄 —— 删除内置倒计时时会自动保存一份设置快照，再点「恢复内置」把主题颜色、显示模式、跳秒动画、提示音、悬浮窗显隐 / 透明度 / 位置、备注等原样恢复，不用重新配置一遍；恢复对话框补上说明文字，恢复后会提示实际还原了几个。"),
        ChangelogItem("v1.0.0.12", "2026-09-16", "修复「有版本更新却收不到通知」🔔 —— 之前只有打开 App 才会去查版本，现在新增后台定时检查（AlarmManager，每 6 小时一次），不打开 App 也能收到更新通知；开机和应用更新后会自动把定时任务重新挂上；更新通知渠道提升为高优先级：有提示音 + 横幅，不再静默躺在通知抽屉里。"),
        ChangelogItem("v1.0.0.11", "2026-09-16", "「恢复内置」改为智能显示 🔄 —— 四个内置倒计时都在列表里时，加号菜单不再显示该按钮；只要有一个内置倒计时不在列表（被删除或已转为普通倒计时），菜单里就会出现「恢复内置」；点了「恢复内置」的确定后，缺的那几个会直接补回列表。"),
        ChangelogItem("v1.0.0.10", "2026-09-16", "「恢复内置」对话框改为带勾选框的多选列表，并补上「确定」按钮（原先只有「取消」）✅ —— 默认全部勾选：想一次恢复全部就直接点确定，只想恢复其中几个就取消勾选再确定。"),
        ChangelogItem("v1.0.0.9", "2026-09-16", "内置倒计时改为可删除 🗑️ —— 当天内置项也能长按（左滑）删除，删掉后不再自动补回来；内置倒计时改为可修改：标题、颜色、模式、动画、备注都能改，日期时间也能改；改动了日期或时间的内置项会自动转为普通倒计时；加号菜单新增「恢复内置」，删掉的内置倒计时可以随时一键恢复。"),
        ChangelogItem("v1.0.0.8", "2026-09-15", "新增两个内置倒计时，新版本安装后自带四个 🎉 —— 当日倒计时、当月倒计时、华都云境悦府倒计时（2026-10-31 00:00:00）、GTA6倒计时（2026-11-19 08:00:00）；内置倒计时目标时间由系统维护（编辑页不可改），不可删除，可在列表中「隐藏」；四个内置项与自建倒计时一样对齐整秒，走秒完全同步。"),
        ChangelogItem("v1.0.0.7", "2026-09-15", "修复主界面「编辑 / 提示音 / 删除」按钮被倒计时文字遮挡的问题 🛠️ —— 列表卡片改为不透底的玻璃卡，未左滑时操作按钮整层不绘制，不再透上来压住倒计时；检测到新版本时新增系统通知提醒；Android 13 及以上首次启动会申请通知权限，保证更新提醒能送达。", obsolete = true),
        ChangelogItem("v1.0.0.6", "2026-09-15", "整体风格升级为「液态玻璃（Liquid Glass）」💎 —— 卡片、按钮、对话框、悬浮窗统一改为半透明玻璃质感，玻璃面带镜面高光与亮边，能透出背后内容；页面背景新增模糊光斑层；底色由纯深色改为深蓝紫渐变，按钮由实心改为玻璃渐变；列表卡片改为大圆角玻璃卡并带投影。"),
        ChangelogItem("v1.0.0.5", "2026-09-14", "更新日志默认收起所有日期 📂 —— 只有点击展开的那一天才显示日志；未展开的日期连「日期行」本身都不显示；再点一次已展开的日期即可收起，收起后所有日期行重新显示出来。"),
        ChangelogItem("v1.0.0.4", "2026-09-14", "添加 / 编辑倒计时页改为与「关于」页一致的风格 🎨 —— 深色底 + 青色返回栏与标题 + 胶囊按钮；各输入项改为深色圆角卡片，字段标题统一为青色小标题；更新日志按日期分组后改为「一次只展开一个日期」。"),
        ChangelogItem("v1.0.0.3", "2026-09-14", "更新弹窗、下载进度框、更新日志框统一为与「关于」页一致的深色风格 🎨 —— 强制更新弹窗新增「发布日期（年月日）」；更新日志按发布日期分组：同一天发布多条时，点日期后的下箭头展开当天各版本明细；同一天只发布一条时，日期后不显示下箭头，直接列出日志内容。"),
        ChangelogItem("v1.0.0.2", "2026-09-14", "1. 每个倒计时的按钮行新增「提示音名称」，一眼看出这条倒计时响什么 🔔；2. 没有设置提示音的条目显示「未设置提示音」；3. 名称按钮可直接点击更换提示音；4. 修复挑选提示音时取消会停在空白页的问题。"),
        ChangelogItem("v1.0.0.1", "2026-09-14", "修复长按拖动排序时条目「分成两层」的问题 🛠️ —— 现在拖动的是完整的一层卡片（真实视图而非截图），不再额外留一份虚影在原位；原位条目占位隐藏，列表不会跳动，浮层上的倒计时也会持续走秒。"),
        ChangelogItem("v1.0.3.5", "2026-09-14", "当前动画效果名称从「目标 / 模式」一行移到按钮区，显示在「显示」「模式」按钮之后 🎬；该按钮可直接点击循环切换动画效果，改完立即生效；「目标 / 模式」行恢复只显示目标时间与显示模式。"),
        ChangelogItem("v1.0.3.4", "2026-09-14", "跳秒动画改为只作用在最后一位数字上，其余数字保持静止，视觉更聚焦 🎬；列表每行「目标 / 模式」之后显示当前动画效果名称，悬浮窗同步显示；删除「周天时分秒模式」，显示模式精简为 8 种。"),
        ChangelogItem("v1.0.3.3", "2026-09-14", "新增跳秒动画 🎬 —— 编辑倒计时可在「缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼」中选择，主界面列表与悬浮窗每次跳秒都会播放，默认「无动画」；删除重复的「周天时分秒模式」，显示模式由 10 种精简为 9 种；修复周模式下「xx时xx分xx秒」的显示问题：现在恒定显示「xx周xx天xx时xx分xx秒」。"),
        ChangelogItem("v1.0.3.2", "2026-09-14", "彻底解决多个倒计时「秒」位数不一致（29 秒 / 30 秒 / 31 秒并存）⏱️ —— 所有目标时间统一对齐到整分；「当日/当月倒计时」改为在次日 / 次月 1 日 00:00:00 归零；读取本地数据时自动把历史目标时间的秒、毫秒尾数抹平；新增全局整秒时钟 AlignedClock，任何刷新路径取到的时刻都一致。"),
        ChangelogItem("v1.0.3.1", "2026-09-14", "修复主界面多个倒计时秒数不一致（有的 29 秒、有的 30/31 秒）的问题 ⏱️ —— 目标时间与当前时刻统一对齐到整秒后再相减，所有倒计时在同一瞬间跳秒；刷新节奏改为对齐墙上时钟的整秒边界；读取本地数据时自动抹平历史目标时间的毫秒尾数。"),
        ChangelogItem("v1.0.3", "2026-09-14", "修复主界面多个倒计时秒数刷新不同步的问题 ⏱️ —— 同一帧统一取一次当前时刻，所有倒计时同时跳秒；刷新频率提高到每秒 4~5 次；关于页「检查更新」按钮旁新增「更新日志」按钮，可随时查看各版本改动。"),
        ChangelogItem("v1.0.2", "2026-09-14", "检测到新版本后强制弹窗展示更新日志，不再只显示一行状态文字 📄；弹窗内点「立即更新」即在 App 内下载并显示进度，下载完自动拉起安装；启动 App 时自动检查一次更新。"),
        ChangelogItem("v1.0.1", "2026-09-14", "检查更新支持在 App 内直接下载并安装（不再跳浏览器）⬇️；新增「当日倒计时 / 当月倒计时」内置项（不可删除）。"),
        ChangelogItem("v1.0.0", "2026-09-07", "首发版本 🎉：多倒计时管理、悬浮窗显示、归零提示音、10 种显示模式。"),
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

            head("v1.0.0.39 新增功能")
            line(
                "用药提醒：在「＋」菜单「吃药提醒」里开关，可设每天 N 次、每次服药时刻与时机（8 选 1），到点发系统通知、通知上带「已吃药」按钮，点一下即记进吃药日历（按天显示）；三路冗余闹钟 + 巡检补发，退出 App 后仍按时提醒",
                "锁屏时钟：列表里「展开」过的倒计时会在锁屏 / 通知栏各占一条常驻通知，每秒跳秒，标题 / 数字 / 模式 / 备注 / 配色与悬浮窗一致",
                "屏幕常亮：锁屏倒计时通知挂着时屏幕可一直亮着（锁屏上也能一直瞄倒计时），默认关、想用再开",
                "更新检查：App 内自动检查新版本、应用内更新提示页（普通 Activity，规避开屏广告拦截）与后台周期巡检",
                "通知体检：一键自查「吃药 / 更新」通知的权限、渠道、后台限制，不过关可一键跳转修复",
                "跳秒动画：缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼多种效果，列表与悬浮窗同帧生效",
                "备注钟点可在 24 小时制 / 12 小时制之间切换（设置项）",
                "免责声明：首次安装或升级时弹一次，「关于」页可随时再看"
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
            line(
                "毫秒模式：v171 新增的一档显示格式，v172 修正为「真正的剩余毫秒」—— 之前只显示「秒×1000」（尾数永远 000），"
                + "现在按真实时刻算整段剩余毫秒数并以约 30 帧/秒实时递减（如还剩 3 分 5 秒 432 毫秒就显示「185432毫秒」），内置 / 普通 / 新建倒计时都生效"
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
                "「华都云境悦府购买正计时」是一档内置正计时（跟华都云境悦府 / GTA6 同款卡片、同款玻璃质感，只是方向相反）：起点固定在 2025-04-20 17:30（购房时刻），",
                "  显示「自购买至今已过去多久」（如「537天12时34分56秒」），全部 11 档显示模式都照常可用，名字 / 颜色 / 跳秒动画 / 备注 / 提示音都能照常改；",
                "  它带一颗「暂停 / 开始」按钮（玻璃质感，跟 GTA6 展开/收起悬浮窗同款）：点「暂停」当场冻结、再点「开始」从冻结点接着走、暂停期间流失的时间自动补齐，「暂停 → 开始」可来回循环；",
                "  主界面卡片和悬浮窗两边按钮状态互相同步。出厂顺序排在 GTA6 之后、今年之前，「＋」菜单「恢复内置」里能原样找回"
            )
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
                "这一轮给所有倒计时（内置的每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 华都云境悦府 / GTA6 / 今年，还有普通倒计时、新建的条目）的显示模式都加了一档「毫秒模式」：",
                "  它跟天 / 时 / 分 / 秒那几档一个路数，把整段倒计时以毫秒为单位一口气写出来（格式就是「xx毫秒」，比如还剩 3 分 5 秒就显示「185000毫秒」）；",
                "  挑哪个都一样按整毫秒落地、跳秒动画与其它档完全一致，卡片上的「显示模式」按钮、悬浮窗抽屉里那两颗「上 / 下一个显示模式」、锁屏通知看到的都是这一档。悬浮窗同步照旧"
            )
            line(
                "这一轮把卡片上的「显示模式」按钮从原来写死的「显示模式」四个字，改成动态显示当前选中的那一档名字 ——",
                "  比如选了「毫秒模式」它就显示「毫秒模式」、选了「天时分秒」就显示「天时分秒」、选了「标准模式」就显示「标准模式」，一眼就能看清这条倒计时现在走的哪一档；",
                "  点它照样弹出下拉框挑模式，功能和风格一概不变。内置 / 普通 / 新建倒计时都生效，悬浮窗同步照旧"
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

            line(
                "「是否循环」这一格又加固了一层：存「是（归零重新计时）」的条目打开编辑页稳稳停在「是」，",
                "  存「否（归零就停住）」的稳稳停在「否」，跟这条自己存的属性永远一致。上一轮改成「等界面画完再选」之后，",
                "  个别机型还会在更晚一帧把这一格冲回「否」，现在又补了几记延迟复核 —— 页面刚打开那一下盯住它，",
                "  发现被冲走就当场按存着的属性选回去；你自己动过这颗下拉框之后就不再插手，挑的那一格不会被扳回去。",
                "  保存也改成一律以「下拉框此刻显示的」为准：看到什么就存什么，再不会挑的是是、存下去成了否"
            )
            line(
                "「是否循环」这一格这次彻底钉死：存「是（归零重新计时）」的条目打开编辑页直接停在「是」，",
                "  存「否（归零就停住）」的就停在「否」，不用再点一下它才对上。上一轮修完还漏了个地方：那一格在界面第一次",
                "  画完之前做的选中，会被下拉框首装冲掉、显示自己回落到「否」，首装冒出来的第一记回调还会把存着的「是」",
                "  写成假；这轮改成等这一帧画完再选、选上之前来的回调一律不认，显示与这条存的属性永远一致，存「是」的照样",
                "  归零自己接着跑下一轮。悬浮窗同步照旧，老数据一行没动"
            )
            line(
                "添加 / 编辑小倒计时这一页：「是否循环」这一格现在稳稳跟着这条自己存的属性走 —— 存「是（归零重新计时）」",
                "  页面就停在「是」，存「否（归零就停住）」页面就停在「否」，打开编辑页不用先点一次它才对得上",
                "修的是那一格读属性的时机：打开页面那一下界面还没量完高，它读到的是「没选中」，一读就把这条存着的「是」抹成",
                "  「否」，保存落盘后再打开又显示成「否」，怎么打开都对不上、还把存着的属性改坏了；现在等这一帧过去再读，",
                "  读到 0 / 1 才回写、读到别的取值一律不动，显示与这条存的属性永远一致；挑「是」的照样归零自己接着跑下一轮，",
                "  挑「否」保存就回到归零停住的老样子。「＋」菜单「复」字、主界面卡片、悬浮窗那一格照旧同步。",
            )

            line(
                "添加 / 编辑小倒计时这一页：名字那栏默认就是空的，进页面不用先删一次自带的名字，想写就写、不写就空着存",
                "「是否循环」下拉框照这条自己存的属性显示：存「是（归零重新计时）」页面就停在「是」，存「否（归零就停住）」就停在「否」",
                "「内置倒计时」这一档的「显示模式」下拉框把全部模式一列到底，挑哪一档都当场按这条自己的格式落地",
                "「今年倒计时」是内置项，跟「每小时」一个路数：跑到今年结束那一刻（下一年 1 月 1 日 0 点）就归零，",
                "  之后系统自己把目标推到下一年接着倒，跨年不用管、一轮一轮自己转；归零只响默认提示音，不摆挑音入口",
                "它最长 365 天，「周」那截有数，所以「周模式」「周天时分秒模式」也照常给着，挑哪一档都按这条自己的格式落地",
                "「＋」菜单「复」字里能把它找回来，出厂顺序排在「GTA6」后面（… → 当月 → 华都云境悦府 → GTA6 → 今年）"
            )

            line(
                "「是否循环」这一格从此「如实显示」：你看到的那一格，就是这条倒计时真正会不会归零重新计时的样子",
                "内置倒计时由档位定死 —— 每分钟 / 每5分钟 / 每10分钟 / 每半小时 / 每小时 / 当日 / 每周 / 当月 / 今年",
                "  这些自己往下跳的档，归零就接着跑下一格，这一格恒显示「是（归零重新计时）」",
                "  （比如每10分钟倒计时就显示「是（归零重新计时）」）；华都云境悦府 / GTA6 这两档日子定死，",
                "  归零就停在那一刻，这一格恒显示「否（归零就停住）」（比如 GTA6 倒计时就显示「否（归零就停住）」）",
                "内置项这一格只如实显示、点也点不动（它本来就不是能挑的）；只有普通倒计时才由你自己挑「是 / 否」",
                "新建、编辑、老数据一律按这个来，再不会统统显示「否（归零就停住）」，悬浮窗同步照旧"
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
