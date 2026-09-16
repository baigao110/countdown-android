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
    const val CURRENT_VERSION_NAME = "1.0.0.10"
    private val CURRENT_VERSION_NUM = versionToNumber(CURRENT_VERSION_NAME)

    private const val OWNER = "baigao110"
    private const val REPO = "countdown-android"
    private const val LATEST_RELEASE = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val UPDATE_JSON =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/update.json"

    private val handler = Handler(Looper.getMainLooper())

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
    private fun showStyledDialog(
        activity: Activity,
        title: String,
        positiveText: String,
        negativeText: String? = null,
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null,
        content: (LinearLayout) -> Unit
    ): AlertDialog {
        val root = activity.layoutInflater.inflate(R.layout.dialog_styled, null)
        val titleTv = root.findViewById<TextView>(R.id.dialogTitle)
        val host = root.findViewById<LinearLayout>(R.id.dialogContent)
        val scroll = root.findViewById<ScrollView>(R.id.dialogScroll)
        val posBtn = root.findViewById<Button>(R.id.dialogPositive)
        val negBtn = root.findViewById<Button>(R.id.dialogNegative)

        titleTv.text = title
        content(host)

        posBtn.text = positiveText
        if (negativeText.isNullOrEmpty()) {
            negBtn.visibility = View.GONE
        } else {
            negBtn.text = negativeText
        }

        val dialog = AlertDialog.Builder(activity).setView(root).setCancelable(cancelable).create()
        negBtn.setOnClickListener { dialog.dismiss() }
        posBtn.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }
        // 去掉系统默认的白色面板底，露出布局自带的深色圆角背景
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 内容太高时把滚动区压到屏幕高度的 70%，避免对话框顶出屏幕
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.7).toInt()
        if (scroll.height > maxH) {
            scroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH
            )
        }
        return dialog
    }

    // ---------------- 强制更新弹窗 ----------------

    /** 强制弹出更新日志对话框，用户点「立即更新」开始下载安装。 */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing) return
        showStyledDialog(
            activity = activity,
            title = "发现新版本 v${info.name}",
            positiveText = "立即更新",
            negativeText = "稍后再说",
            onPositive = { downloadAndInstall(activity, info) }
        ) { host ->
            host.addView(kvRow(activity, "发布日期", info.date.ifEmpty { "未知" }))
            host.addView(kvRow(activity, "当前版本", "v$CURRENT_VERSION_NAME"))
            host.addView(sectionTitle(activity, "更新日志"))
            host.addView(
                bodyText(
                    activity,
                    if (info.note.isBlank()) "暂无更新说明" else info.note
                )
            )
        }
    }

    /** 下载 APK 并在下载完成后拉起安装界面，下载期间显示进度对话框。 */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo) {
        val canceled = AtomicBoolean(false)
        var progressTv: TextView? = null
        val dialog = showStyledDialog(
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
                                handler.post { if (dialog.isShowing) dialog.dismiss() }
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
                                    if (dialog.isShowing) {
                                        progressTv?.text =
                                            if (percent >= 0) "已下载 $percent%（$mb MB）"
                                            else "已下载 $mb MB"
                                    }
                                }
                            }
                        }
                    }
                }
                handler.post {
                    if (dialog.isShowing) dialog.dismiss()
                    installApk(activity, file)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                handler.post {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(activity, "下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    openInBrowser(activity, info)
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
