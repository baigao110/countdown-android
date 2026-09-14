package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
 * AboutActivity 与 MainActivity 共用本管理器，保证两处行为一致。
 */
object UpdateManager {

    /** 当前版本号，发版时与 app/build.gradle 的 versionName 保持一致。 */
    const val CURRENT_VERSION_NAME = "1.0.0.1"
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
        val htmlUrl: String
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
     */
    fun check(activity: Activity, forceDialog: Boolean, onResult: ((UpdateInfo?) -> Unit)? = null) {
        Thread {
            val info = fetch()
            handler.post {
                val newest = if (info != null && info.num > CURRENT_VERSION_NUM) info else null
                onResult?.invoke(newest)
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
            return UpdateInfo(num, name, apkUrl, note, o.optString("html_url", ""))
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
            return UpdateInfo(
                num,
                name.ifBlank { num.toString() },
                json.optString("apkUrl", ""),
                json.optString("note", ""),
                json.optString("htmlUrl", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 强制弹出更新日志对话框，用户点「立即更新」开始下载安装。 */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing) return
        val note = if (info.note.isBlank()) "暂无更新说明" else info.note
        val dialog = AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.name}")
            .setMessage("当前版本：v$CURRENT_VERSION_NAME\n\n更新日志：\n$note")
            .setPositiveButton("立即更新", null)
            .setNegativeButton("稍后再说", null)
            .setCancelable(true)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                downloadAndInstall(activity, info)
            }
        }
        dialog.show()
    }

    /** 下载 APK 并在下载完成后拉起安装界面，下载期间显示进度对话框。 */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo) {
        val canceled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v${info.name}")
            .setMessage("准备中...")
            .setCancelable(false)
            .setNegativeButton("取消") { d, _ -> canceled.set(true); d.dismiss() }
            .show()

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
                                handler.post { dialog.dismiss() }
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
                                        dialog.setMessage(
                                            if (percent >= 0) "已下载 $percent%（$mb MB）"
                                            else "已下载 $mb MB"
                                        )
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

    /** 各版本更新日志（离线可读，新增版本时在头部追加一条即可）。 */
    private val CHANGELOG = listOf(
        "v1.0.0.1" to
            "修复长按拖动排序时条目「分成两层」的问题：\n" +
            "  现在拖动的是完整的一层卡片（真实视图而非截图），不再额外留一份虚影在原位\n" +
            "  原位条目占位隐藏，列表不会跳动，浮层上的倒计时也会持续走秒",
        "v1.0.3.5" to
            "当前动画效果名称从「目标 / 模式」一行移到按钮区，显示在「显示」「模式」按钮之后\n" +
            "该按钮可直接点击循环切换动画效果，改完立即生效\n" +
            "「目标 / 模式」行恢复只显示目标时间与显示模式",
        "v1.0.3.4" to
            "跳秒动画改为只作用在最后一位数字上，其余数字保持静止，视觉更聚焦\n" +
            "列表每行「目标 / 模式」之后显示当前动画效果名称，悬浮窗同步显示\n" +
            "删除「周天时分秒模式」，显示模式精简为 8 种",
        "v1.0.3.3" to
            "新增跳秒动画：编辑倒计时可在「缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼」中选择，\n" +
            "  主界面列表与悬浮窗每次跳秒都会播放，默认「无动画」\n" +
            "删除重复的「周天时分秒模式」，显示模式由 10 种精简为 9 种\n" +
            "修复周模式下「xx时xx分xx秒」的显示问题：现在恒定显示「xx周xx天xx时xx分xx秒」，\n" +
            "  当日倒计时切到该模式也会显示完整的周、天",
        "v1.0.3.2" to
            "彻底解决多个倒计时「秒」位数不一致（29 秒 / 30 秒 / 31 秒并存）：\n" +
            "  所有目标时间统一对齐到整分，列表里每个倒计时的秒数必然相同\n" +
            "  「当日/当月倒计时」改为在次日 / 次月 1 日 00:00:00 归零，与自建倒计时同相位\n" +
            "  读取本地数据时自动把历史目标时间的秒、毫秒尾数抹平（最多偏移 30 秒）\n" +
            "新增全局整秒时钟 AlignedClock：任何刷新路径取到的时刻都一致，杜绝跨秒错位",
        "v1.0.3.1" to
            "修复主界面多个倒计时秒数不一致（有的 29 秒、有的 30/31 秒）的问题：\n" +
            "  目标时间与当前时刻统一对齐到整秒后再相减，所有倒计时在同一瞬间跳秒\n" +
            "刷新节奏改为对齐墙上时钟的整秒边界，消除固定周期定时累积漂移造成的停顿、跳秒\n" +
            "读取本地数据时自动抹平历史目标时间的毫秒尾数，避免旧数据让各条目秒数错开",
        "v1.0.3" to
            "修复主界面多个倒计时秒数刷新不同步的问题：同一帧统一取一次当前时刻，所有倒计时同时跳秒\n" +
            "刷新频率提高到每秒 4~5 次，避免定时漂移造成的停顿、跳秒\n" +
            "关于页「检查更新」按钮旁新增「更新日志」按钮，可随时查看各版本改动",
        "v1.0.2" to
            "检测到新版本后强制弹窗展示更新日志，不再只显示一行状态文字\n" +
            "弹窗内点「立即更新」即在 App 内下载并显示进度，下载完自动拉起安装\n" +
            "启动 App 时自动检查一次更新",
        "v1.0.1" to
            "检查更新支持在 App 内直接下载并安装（不再跳浏览器）\n" +
            "新增「当日倒计时 / 当月倒计时」内置项（不可删除）",
        "v1.0.0" to
            "首发版本：多倒计时管理、悬浮窗显示、归零提示音、10 种显示模式"
    )

    /** 显示更新日志（可滚动查看全文）。 */
    fun showChangelog(activity: Activity) {
        if (activity.isFinishing) return
        val tv = TextView(activity)
        tv.setPadding(40, 24, 40, 16)
        tv.textSize = 14f
        tv.setTextColor(0xFFE6E6F0.toInt())
        tv.setLineSpacing(4f, 1.15f)
        val sb = StringBuilder()
        for ((version, content) in CHANGELOG) {
            sb.append(version).append("\n")
            for (line in content.split("\n")) {
                sb.append("  · ").append(line).append("\n")
            }
            sb.append("\n")
        }
        tv.text = sb.toString()
        val scroll = ScrollView(activity)
        scroll.addView(tv)
        AlertDialog.Builder(activity)
            .setTitle("更新日志")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
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
