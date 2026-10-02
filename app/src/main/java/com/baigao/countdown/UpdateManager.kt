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
    class ChangelogItem(val version: String, val date: String, val content: String)

    /**
     * 各版本更新日志。新增版本时在**头部**追加一条，并填上真实发布日期：
     * 更新日志框会按日期合并分组，同一天发布多条时点日期后的下箭头展开明细，
     * 只有一条的那天直接铺开显示、不显示箭头。
     */
    private val CHANGELOG = listOf(
    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」进邮箱还是空的、要你自己再挑一次图片或视频，可只挑图片 / 视频的时候偏偏好好的？上一版虽然不再偷偷退回短路，可断点没堵干净：只勾日志的时候，分享出去的那份 Intent 给的是具体文本类型，邮箱一看就把它当成「转发一段文字」——正文照抄进去，附件那一份直接不理，撰写页照样空着，于是又只剩下你自己回头挑一次图片或视频。这版把分支选对：附件里只要全是纯文本（也就是只勾了运行日志），就先按「分享一个文件」的套路去开邮箱，走的正是各家邮箱认的「收附件」那条正规路，日志妥妥挂上；万一这家邮箱只认文本那一路，就再按文本的路开一次；两条都不成才逐级退到 mailto 直投、分享列表，最后退到复制内容备用。另外不管走到哪一步，运行日志都会同时贴在正文里，所以「不用再回头自己挑一次」这条底线这回是稳稳守住了～"),
    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」进邮箱还是空的、要你自己再挑一次图片或视频，可只挑图片 / 视频的时候偏偏好好的？这回断点终于找着了：提交前有一步「先看看有没有应用接得住这份分享」的判断，可那个查法只认自己声明了 DEFAULT 的入口，而邮箱的撰写页偏偏没给「文本类」这种分享声明 DEFAULT（它盯的是邮件入口、图片、视频），于是只勾日志的时候，查出来的是微信、QQ、蓝牙那批「分享一段文字」的应用，邮箱压根不在表里，被判定成没人接，就悄悄退到了 mailto 直投那条短路，而 mailto 路上的附件正是各家邮箱不看的东西，进邮箱当然空空的。这版改成不靠「先猜有没有人接」，直接去开，接不上它自己会报错、我们才退下一层；提前把附件读取权限交给哪些应用的查法也放宽了，不再只把权限交给微信那几家。另外，运行日志这一份内容会同时贴进邮件正文：就算某家邮箱还是不肯把纯文本附件挂上去，这封反馈里也写得满满当当，你不用再回头自己挑一次图片或视频。"),
    ChangelogItem("v1.0.0.38", "2026-10-02", "「只勾附带运行日志」反馈进了邮箱，附件栏却还是空的、要你再自己挑一次图片或视频～这回的毛病正好只在日志这一条路上，两个真断点一起修掉。第一，运行日志是个 txt，可附件共享器里 txt 压根没排在类型表里，日志被判成通用二进制文件，分享出去的 Intent 类型也就跟着掉进通配类；而国产邮箱只认 image/*、video/*、text/plain 这种写死的类型，看到通配类就当你是「分享个普通文件」而不是「写一封带附件的信」，撰写页照样打开、附件栏照样空。这版把 txt / log / json / csv 这些文本类型补进表里认成 text/plain；分享时的类型也不再轻易给通配——全是日志就给 text/plain，图片混日志就按图片给 image/*，视频混日志就给 video/*，保证交到邮箱手上的永远是一个它认得的写死类型。第二更隐蔽：写运行日志是把一整段包在一个兜底里的，读倒计时列表、读运行日志、读那几个开关任何一小步没成，整份日志就作废，而提交那边会把它当没附件安安静静跳过——你点提交进邮箱，信是开了、附件没了，就只剩让你自选图这一条路。这版把日志拆成几段各自兜底，哪一段读不出来就空那一段，日志本体一定还在、一定发得出去；万一实在写不出来，也会明明白白提示你先把勾去掉再提交，不再偷偷少发一样东西。"),
    ChangelogItem("v1.0.0.38", "2026-10-02", "「反馈附件在邮箱里要再选一次」这版继续往根上挖，两个真毛病一起修掉了～第一，附件递过去的时候「扩展名和真实内容对不上」：相册 / 微信 / QQ 转手过来的图，名义后缀可能是 heic、jfif，甚至干脆没有后缀，邮箱是靠扩展名和类型判断自己认不认得这个附件的，认不出就当这封信没有附件，于是你只能在邮箱里自己再添一次图片或视频。这版提交前先把附件全部整理一遍——按文件头（图片头、视频头）判断真实类型，再存成 jpg、png、webp、heic、mp4、mov、webm 这些标准名字，邮箱一眼就认得。第二，之前直接 startActivity 唤起邮箱，常常唤起来的是它上次没写完、还挂在后台的旧写信页，旧草稿上当然没有这次的附件；这版打开邮箱时带上「开新写信页」的标记，每次都是干干净净的新一页。另外提交前的自检也真刀真枪了：会顺着附件的通路真的读一遍，读不出来的附件直接剔掉并告诉你是哪个，不再塞一个空附件进去让你自己去补；扩展名一时认不出来的，也交给系统 MIME 表兜底，不会变成什么都不是的类型。还是没装邮箱的机型，依次退到分享列表、再到「复制内容备用」粘微信 / QQ 发，照样收得到～"),
    ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」的附件这次走最稳的那条路送进邮箱啦～上一版虽然是把附件递过去了，可走的是「ActionSendTo」（mailto 直投）这条短路：Gmail 和不少手机自带邮箱压根不认这条路上的附件，它们只在「ActionSend」（收件人用 EXTRA_EMAIL 填好）这条正规路上把图片 / 视频挂进撰写页，所以你一进邮箱看到的是空空的附件栏，还得自己再添一次图片或视频。这版把顺序换了：优先开「ActionSend + 收件人已填好」，邮箱撰写页一打开，图片 / 视频 / 运行日志就都整整齐齐挂在上面，点一下发送就到啦；万一你这台手机上这份 Intent 没人接（没装邮箱），才退回 mailto 那条路；再不行给一个分享列表让你挑一个能收附件的发；最后还是不行就把正文复制到剪贴板，粘到微信 / QQ 发给我一样收得到。顺手也把入口看门加严了：这手机上没人接的 Intent 就不会硬着头皮去开，不会点一下提交就闪一下又退回我们自己的页面。"),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」带图片 / 视频进邮箱这次真的带上了～以前反馈里选好图片、视频，一点提交跳进邮箱，附件栏却是空的，还得你自己再点一下「添加附件」重挑一次图片或视频——根子出在分享给邮箱的那份 Intent「没把附件喂明白」：（一）Intent 没带类型，不少邮箱是靠它判断自己接不接得住图片 / 视频，没类型它就当这封信没有附件；（二）附件是以「一串附件列表」的形式递过去的，只认单个附件的邮箱取不到东西，同样当没附件；（三）读取权限只在剪贴板数据上自动发过，光给附件列表的话，邮箱回头异步去取时常常还没权限可取，读不到也就当没附件。这版三处一起补齐：按内容自动认类型——清一色图片就是 image/*、清一色视频就是 video/*、混着日志就是 */*；只有一个附件就单发一个、好几种附件就同时发列表和剪贴板数据；再提前把读取权限挨个交给手机上能收这封信的邮箱应用，它什么时候去取都读得到。提交前还会先自检一遍：每个附件都读得出来、大小正常才会发，读不出的当场提示「先把它撤掉再提交」，不再让你白跑一趟。提交成功的那句提示也写明白了，会告诉你这次一共带上了几个附件（图片 / 视频 / 日志）。没装邮箱时的老路子照旧：「复制内容备用」把内容复制到剪贴板，粘到微信 / QQ 发给我一样收得到～"),
        ChangelogItem("v1.0.0.38", "2026-10-02", "「意见反馈」来啦，有话直接跟我说～「关于」页最下面现在有个「意见反馈」按钮，点进去就能给我写信：昵称和联系方式都可以留空，把问题现象写两句就行（方便的话填上，我好回复你）；还能一起加图片或视频，最多 9 个，已经选好的会排成一排小胶囊，点一下就撤掉。想让我更快找到原因就把下面的「附带运行日志」勾上，它会把机型、系统版本、应用版本、当前倒计时列表和那几个常亮开关的状态打包成一个 txt 一起发过来（不含你的备注内容）。点「提交反馈」会打开你手机上的邮箱应用，收件人已经填好是 baigao110@qq.com，标题正文附件都备齐了，你只要点一下发送就到啦～ 手机上没装邮箱的话，下面那个「复制内容备用」能把内容复制到剪贴板，粘到微信 / QQ 发给我，照样收得到。整个反馈页的样式跟「关于」页一模一样：同一个玻璃底、同一个输入框底板、同一个青色胶囊按钮，看着就熟～"),
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

            head("四、悬浮窗与锁屏通知")
            line(
                "在悬浮窗里可拖动位置、点标题栏收缩成小条、调节不透明度",
                "「关于」页的「锁屏通知显示」：把「展开」过的倒计时以常驻通知显示在通知栏 / 锁屏上，",
                "  标题、倒计时数字、模式、备注与配色和悬浮窗完全一致，每秒跳秒；点通知直接回主界面",
                "锁屏上看不到的话，点「关于」页的「锁屏上看不到？点我打开锁屏通知设置」一键跳到应用信息页，\",",
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
                "若任意界面都不显示，请在系统设置中开启「显示在其他应用上层」，并在品牌权限管理中开启「后台弹出界面」"
            )

            head("五、内置倒计时")
            line(
                "自带六个：每小时倒计时、当日倒计时、每周倒计时、当月倒计时、华都云境悦府、GTA6",
                "内置项的备注会同步显示具体结束时刻：每小时倒计时写「距离17点整结束」（过整点自动变「距离18点整结束」），",
                "当日 / 每周 / 当月倒计时写「距离10月2日0点整结束」这样具体的日期和时刻；列表与悬浮窗都实时跟着变",
                "备注里的时间按「关于」页的「备注时间制式」显示：24 小时制是「距离17点整结束」，12 小时制是「距离下午5点整结束」",
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
                "锁屏通知显示：列表里「展开」过的倒计时会常驻在通知栏 / 锁屏上（内容、配色与悬浮窗一致、每秒跳秒），关掉开关就一起收掉",
                "意见反馈：「关于」页最下面点「意见反馈」就能给我写信——昵称和联系方式可以不填，",
                "  写几句问题现象就好，还能加图片 / 视频（最多 9 个，已选的排成小胶囊，点一下就撤掉）",
                "「附带运行日志」勾上会一起发一份运行信息（机型、系统、版本、倒计时列表、常亮开关状态），定位问题最快",
                "提交后会打开邮箱应用、收件人已经填成 baigao110@qq.com，点一下发送就到啦；手机上没装邮箱，",
                "  点「复制内容备用」粘到微信 / QQ 发给我，一样收得到～"
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
