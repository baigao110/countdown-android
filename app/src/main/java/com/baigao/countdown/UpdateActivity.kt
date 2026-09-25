package com.baigao.countdown

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新提示页（v1.0.0.25 新增）。
 *
 * 为什么要单独开一个页面，而不用对话框：
 * 一批「跳过开屏广告 / 弹窗拦截 / 广告过滤」工具靠无障碍服务盯 Dialog 窗口，
 * 「一打开 App 就弹出」的对话框尤其容易被判成开屏广告，直接替用户点掉 ——
 * 表现出来就是「更新提示从来没出现过」。
 *
 * 这里用一个**普通的 Activity**：窗口背景半透明、只画中间那张玻璃卡片
 * （与原来的对话框同一套布局，外观完全一样），不再属于 Dialog 窗口，
 * 那些工具也就无从下手。下载进度同样显示在这张卡片上，不再另弹对话框。
 */
class UpdateActivity : Activity() {

    private val canceled = AtomicBoolean(false)
    private lateinit var card: UpdateManager.StyledCard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = UpdateManager.infoFromIntent(intent)
        if (info == null) {
            finish()
            return
        }

        val dm = resources.displayMetrics
        val root = FrameLayout(this)
        root.setBackgroundColor(DIM)
        // 点空白处 = 「稍后再说」
        root.setOnClickListener { finish() }

        card = UpdateManager.buildStyledCard(
            activity = this,
            title = "发现新版本 v${info.name}",
            positiveText = "立即更新",
            negativeText = "稍后再说",
            onPositive = { startDownload(info) },
            onNegative = { finish() }
        ) { host -> UpdateManager.fillUpdateContent(this, host, info) }

        // 「忽略更新」：点一下把这次提示当成「已是最新版本」处理，但**不**永久忽略——
        // 下次手动「检查更新」会重新拉取远程版本，依旧会发现新版本并再次弹出提示框。
        val btnRow = card.negative.parent as LinearLayout
        val ignoreBtn = Button(this).apply {
            text = "忽略更新"
            textSize = 15f
            setTextColor(card.negative.currentTextColor)
            background = card.negative.background
            setTypeface(card.negative.typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.marginStart = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener {
                UpdateManager.setIgnored(info.name)
                finish()
            }
        }
        btnRow.addView(ignoreBtn, 1)  // 插在「稍后再说」与「立即更新」之间

        // 卡片自己吃掉点击，别穿透到空白处把页面关掉
        card.root.isClickable = true
        root.addView(
            card.root,
            FrameLayout.LayoutParams(
                (dm.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
        setContentView(root)
    }

    /** 原地切成下载进度态：不另弹对话框，省得再被当成广告弹窗。 */
    private fun startDownload(info: UpdateManager.UpdateInfo) {
        val tv = TextView(this).apply {
            text = "准备中..."
            setTextColor(0xFFE6E6F0.toInt())
            textSize = 14f
            setLineSpacing(4f, 1.15f)
        }
        card.host.removeAllViews()
        card.host.addView(tv)
        card.title.text = "正在下载 v${info.name}"
        card.negative.visibility = View.GONE
        card.positive.text = "取消"
        card.positive.setOnClickListener {
            canceled.set(true)
            finish()
        }
        UpdateManager.downloadAndInstall(
            activity = this,
            info = info,
            onProgress = { text -> tv.text = text },
            canceled = canceled,
            onDone = { finish() }   // 让主界面回到前台，接上「允许安装未知应用」的后续
        )
    }

    companion object {
        private const val DIM = 0xB3000000.toInt()
    }
}
