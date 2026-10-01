package com.baigao.countdown

import android.content.Context

/**
 * 备注里「钟点」的显示制式偏好：24 小时制 / 12 小时制。
 *
 * 只影响备注文案（如「距离17点整结束」↔「距离下午5点整结束」），
 * 不影响倒计时数字本身（倒计时永远是 0-23 的阿拉伯数字）。
 * 存的是普通应用偏好，零权限。
 */
object TimeFormatPref {

    private const val FILE = "countdown_prefs"
    private const val KEY_24H = "remark_time_24h"

    /** 当前是否按 24 小时制显示（默认 24 小时制）。 */
    fun is24Hour(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_24H, true)

    /** 保存 24 小时制开关。 */
    fun set24Hour(ctx: Context, use24: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_24H, use24).apply()
    }

    /**
     * 把 0..23 的小时数渲染成备注里用的中文说法。
     * - 24 小时制：17 → 「17点整」
     * - 12 小时制：17 → 「下午5点整」；0 → 「凌晨12点整」；12 → 「中午12点整」
     */
    fun clockText(hour24: Int, use24: Boolean): String {
        if (use24) return "${hour24}点整"
        val h = Math.abs(hour24) % 12
        val n = if (h == 0) 12 else h   // 0 点、12 点在 12 小时制里读作 12 点
        val period = when {
            hour24 in 0..5 -> "凌晨"
            hour24 in 6..11 -> "上午"
            hour24 == 12 -> "中午"
            hour24 in 13..17 -> "下午"
            else -> "晚上"
        }
        return "$period${n}点整"
    }
}
