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
                "本应用为个人用途的轻量倒计时工具，按「现状」提供，不提供任何明示或暗示的担保。",
                "",
                "1. 倒计时的准确性依赖设备系统时钟与运行状态。因设备关机、系统休眠、应用被强制停止、通知权限或电池优化被关闭、厂商定制系统限制等原因，可能导致提醒未响、显示不准或延迟，开发者不为此承担任何责任。",
                "",
                "2. 本应用不会收集、上传或共享您的任何个人数据；所有倒计时与吃药记录仅保存在您手机本地，卸载后一并清除。",
                "",
                "3. 请勿将本应用用于任何关乎人身、医疗、法律或财产安全的严肃提醒场景；重要事项请另行设置多重提醒。",
                "",
                "4. 使用本应用即表示您已阅读、理解并同意上述声明，并自行承担使用风险。"
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
