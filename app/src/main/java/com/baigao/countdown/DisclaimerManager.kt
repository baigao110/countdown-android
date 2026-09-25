package com.baigao.countdown

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 免责声明：与「关于」页 / 更新日志 / 使用说明同一套深色玻璃风格。
 *
 * 弹出时机：
 *  1. 首次安装（从未记录过看过声明）；
 *  2. 老版本升级到新版本（记录的版本号低于当前）。
 * 看过即记录当前版本号，后续启动不再弹出（不阻断使用，只是看一眼）。
 * 「关于」页里的「免责声明」按钮任何时候都能手动再打开看一遍，风格与此完全一致。
 */
object DisclaimerManager {

    private const val PREF = "disclaimer"
    private const val KEY_AGREED = "agreed_version_code"

    /** 是否应在本次启动弹出：首次使用或老版本升级上来。 */
    fun shouldShowOnLaunch(ctx: Context): Boolean {
        val cur = UpdateManager.versionToNumber(UpdateManager.CURRENT_VERSION_NAME)
        val agreed = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_AGREED, -1)
        return agreed < cur
    }

    /** 记录当前版本已看过免责声明，后续启动不再弹出。 */
    fun markAgreed(ctx: Context) {
        val cur = UpdateManager.versionToNumber(UpdateManager.CURRENT_VERSION_NAME)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AGREED, cur).apply()
    }

    /** 弹出免责声明（与「关于」页同款深色玻璃卡片）。 */
    fun show(ctx: Context) {
        val activity = ctx as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        UpdateManager.showStyledDialog(
            activity = activity,
            title = "免责声明",
            positiveText = "我已知晓",
            negativeText = null
        ) { host ->
            val lines = listOf(
                "本应用尊重并保护知识产权。若本应用内使用的任何内容（包括但不限于文字、图片、音频、视频、字体、图标、商标等）侵犯了您的合法权益，请及时与我们联系，并提供以下材料：",
                "· 权利人的身份证明、营业执照或相关权属证明；",
                "· 涉嫌侵权内容的名称、位置及截图；",
                "· 您的联系方式（姓名、电话、邮箱）；",
                "· 您对内容拥有权利的权属证明；",
                "· 您要求处理的具体方式（删除、下架、替换等）。",
                "本应用制作者将在收到有效通知后【1-3 个工作日】内进行核实，并依法采取删除、屏蔽、断开链接等必要措施。",
                "联系方式：",
                "邮箱：【baigao110@qq.com】",
                "因不可抗力或第三方原因导致的内容问题，本应用不承担直接责任，但会积极配合处理。"
            )
            val tv = TextView(activity).apply {
                text = lines.joinToString("\\n")
                setTextColor(Color.parseColor("#FFE4EEFF"))
                textSize = 14f
                setLineSpacing(0f, 1.25f)
                setShadowLayer(2f, 0f, 1f, Color.parseColor("#CC000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            host.addView(tv)
        }
    }

    /** 启动后按需弹出：首次使用或升级上来时弹一次（弹即视为已看，避免重复打扰）。 */
    fun showOnLaunchIfNeeded(ctx: Context) {
        if (shouldShowOnLaunch(ctx)) {
            markAgreed(ctx)
            show(ctx)
        }
    }
}
