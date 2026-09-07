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
 * - 版本号下方为「更新」按钮，点击后联网比对远程 update.json，
 *   若远程版本号更高则打开下载链接完成更新。
 *
 * 部署到 GitHub 后，请把 UPDATE_URL 中的 USER / REPO 改为实际仓库路径，
 * 并在仓库根目录放置 update.json（见仓库根目录示例）。
 */
class AboutActivity : Activity() {

    private val CURRENT_VERSION_CODE = 1
    private val CURRENT_VERSION_NAME = "1.0.0"

    // 远程版本信息地址（GitHub Raw）。
    private val UPDATE_URL = "https://raw.githubusercontent.com/baigao110/countdown-android/main/update.json"

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

    /** 联网检查远程版本信息。 */
    private fun checkUpdate() {
        checking = true
        updateBtn.isEnabled = false
        statusTv.text = "正在检查更新..."

        // 未配置真实地址时，直接提示已是最新，避免无谓的网络失败
        if (UPDATE_URL.contains("USER/REPO")) {
            handler.post {
                checking = false
                updateBtn.isEnabled = true
                updateBtn.text = "已是最新版本"
                statusTv.text = "已是最新版本 v$CURRENT_VERSION_NAME"
            }
            return
        }

        Thread {
            try {
                val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(text)
                val remoteCode = json.optInt("versionCode", CURRENT_VERSION_CODE)
                val remoteName = json.optString("versionName", "")
                val apk = json.optString("apkUrl", "")
                val note = json.optString("note", "")

                handler.post {
                    checking = false
                    updateBtn.isEnabled = true
                    if (remoteCode > CURRENT_VERSION_CODE) {
                        latestApkUrl = apk
                        updateBtn.text = "下载更新 v$remoteName"
                        statusTv.text = "发现新版本 v$remoteName\n$note"
                        if (apk.isNotEmpty()) {
                            Toast.makeText(this, "发现新版本 v$remoteName", Toast.LENGTH_SHORT).show()
                        } else {
                            statusTv.text = "发现新版本 v$remoteName，但未提供下载地址"
                            updateBtn.isEnabled = false
                        }
                    } else {
                        latestApkUrl = null
                        updateBtn.text = "已是最新版本"
                        statusTv.text = "已是最新版本 v$CURRENT_VERSION_NAME"
                        Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                handler.post {
                    checking = false
                    updateBtn.isEnabled = true
                    statusTv.text = "检查更新失败，请检查网络后重试"
                }
            }
        }.start()
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
