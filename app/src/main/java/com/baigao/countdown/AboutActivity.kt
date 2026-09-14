package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 关于页面：
 * - 中间靠上显示放大的倒计时图标；
 * - 图标下方显示版本号；
 * - 版本号下方为「更新」按钮，点击后直接读取 GitHub 仓库的「最新 Release」，
 *   自动获取最新版本号、更新说明与 APK 下载地址；若 GitHub 接口不可用，
 *   则回退读取仓库中的 update.json。
 *
 * 这样以后发布新版本，只需在 GitHub 上发一个新 Release（打 tag 如 v1.1.0
 * 并上传 APK），App 内「检查更新」即可自动识别，无需再手工维护 update.json。
 */
class AboutActivity : Activity() {

    private val CURRENT_VERSION_NAME = "1.0.0"
    /** 当前版本的可比较数值（主*10000 + 次*100 + 修订）。 */
    private val CURRENT_VERSION_NUM = versionToNumber(CURRENT_VERSION_NAME)

    // GitHub 最新 Release 接口（与仓库保持同步）
    private val GITHUB_OWNER = "baigao110"
    private val GITHUB_REPO = "countdown-android"
    private val GITHUB_LATEST_RELEASE =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    // 回退通道：仓库根目录的 update.json
    private val UPDATE_JSON_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/update.json"

    /** 解析出的远程版本信息。 */
    private class UpdateInfo(
        val num: Int,
        val name: String,
        val apkUrl: String,
        val note: String
    )

    companion object {
        /** 把 "1.2.3" / "v1.2.3" 转成可比较数值：1*10000 + 2*100 + 3。 */
        fun versionToNumber(version: String): Int {
            val v = version.trim().removePrefix("v").removePrefix("V")
            val parts = v.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return major * 10000 + minor * 100 + patch
        }
    }

    private lateinit var versionTv: TextView
    private lateinit var updateBtn: Button
    private lateinit var statusTv: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var latestApkUrl: String? = null
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        versionTv = findViewById(R.id.versionTv)
        updateBtn = findViewById(R.id.updateBtn)
        statusTv = findViewById(R.id.statusTv)
        val backBtn = findViewById<TextView>(R.id.backBtn)

        versionTv.text = "版本 v$CURRENT_VERSION_NAME"
        backBtn.setOnClickListener { finish() }
        updateBtn.setOnClickListener { onUpdateClick() }
    }

    private fun onUpdateClick() {
        if (checking) return
        if (!latestApkUrl.isNullOrEmpty()) openUpdate() else checkUpdate()
    }

    /** 联网检查远程版本信息：优先 GitHub 最新 Release，失败回退 update.json。 */
    private fun checkUpdate() {
        checking = true
        updateBtn.isEnabled = false
        statusTv.text = "正在检查更新..."

        Thread {
            var from = "GitHub Release"
            var info: UpdateInfo? = try {
                fetchFromGitHubRelease()
            } catch (e: Throwable) {
                null
            }
            if (info == null) {
                from = "update.json"
                info = try {
                    fetchFromUpdateJson()
                } catch (e: Throwable) {
                    null
                }
            }

            val result = info
            val source = from
            handler.post {
                checking = false
                updateBtn.isEnabled = true
                if (result == null) {
                    statusTv.text = "检查更新失败，请检查网络后重试"
                    return@post
                }
                if (result.num > CURRENT_VERSION_NUM) {
                    latestApkUrl = result.apkUrl
                    updateBtn.text = "下载更新 v${result.name}"
                    val noteText = if (result.note.isBlank()) "" else "\n${result.note}"
                    statusTv.text = "发现新版本 v${result.name}（来源：$source）$noteText"
                    if (result.apkUrl.isEmpty()) {
                        statusTv.text = "发现新版本 v${result.name}，但未提供下载地址"
                        updateBtn.isEnabled = false
                    } else {
                        Toast.makeText(this, "发现新版本 v${result.name}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    latestApkUrl = null
                    updateBtn.text = "已是最新版本"
                    statusTv.text = "已是最新版本 v$CURRENT_VERSION_NAME（来源：$source）"
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 读取 GitHub 仓库的最新 Release：版本号取 tag_name，下载地址取 APK 资产。 */
    private fun fetchFromGitHubRelease(): UpdateInfo? {
        val conn = URL(GITHUB_LATEST_RELEASE).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "$GITHUB_OWNER-$GITHUB_REPO")
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

            // 找到第一个 .apk 资产作为下载地址
            var apkUrl = ""
            var sizeText = ""
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val an = a.optString("name", "")
                    if (an.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url", "")
                        val size = a.optLong("size", 0L)
                        if (size > 0) sizeText = "（${size / 1024} KB）"
                        break
                    }
                }
            }
            // 没有 APK 资产时，退回到 Release 页面地址
            if (apkUrl.isEmpty()) apkUrl = o.optString("html_url", "")

            var note = o.optString("body", "").trim()
            if (note.length > 200) note = note.substring(0, 200) + "..."
            if (sizeText.isNotEmpty()) note = "$sizeText$note"
            return UpdateInfo(num, name, apkUrl, note)
        } finally {
            conn.disconnect()
        }
    }

    /** 回退通道：读取仓库根目录的 update.json。 */
    private fun fetchFromUpdateJson(): UpdateInfo? {
        val conn = URL(UPDATE_JSON_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val name = json.optString("versionName", "")
            val num = if (name.isNotBlank()) versionToNumber(name)
            else json.optInt("versionCode", 0)
            if (num <= 0) return null
            val apk = json.optString("apkUrl", "")
            val note = json.optString("note", "")
            return UpdateInfo(num, name.ifBlank { num.toString() }, apk, note)
        } finally {
            conn.disconnect()
        }
    }

    /** 打开远程下载链接（浏览器/下载器），用户下载安装即完成更新。 */
    private fun openUpdate() {
        val apk = latestApkUrl
        if (apk.isNullOrEmpty()) {
            checkUpdate()
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(apk))
            startActivity(i)
        } catch (e: Throwable) {
            Toast.makeText(this, "无法打开下载链接", Toast.LENGTH_SHORT).show()
        }
    }
}
