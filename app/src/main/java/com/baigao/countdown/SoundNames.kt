package com.baigao.countdown

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * 提示音 URI → 显示名称。
 *
 * 列表每一行都会显示自己设置的提示音名称，而把 URI 解析成人能读懂的标题
 * （RingtoneManager.getRingtone().getTitle()）涉及跨进程查询，开销不可忽略。
 * 这里做一层内存缓存：同一个 URI 只解析一次，列表每秒刷新时间文本时不会再查。
 */
object SoundNames {

    /** 未设置提示音时的显示文案（Countdown.soundUri == null 表示使用默认内置提示音）。 */
    const val NONE = "未设置提示音"

    private val cache = HashMap<String, String>()

    /**
     * @param uri 提示音 URI 字符串；null / 空串 → 返回「未设置提示音」
     * @return 铃声标题；取不到标题时返回「自定义提示音」
     */
    fun name(ctx: Context, uri: String?): String {
        if (uri.isNullOrEmpty()) return NONE
        cache[uri]?.let { return it }
        val title = try {
            RingtoneManager.getRingtone(ctx, Uri.parse(uri))?.getTitle(ctx)
        } catch (e: Throwable) {
            null
        }
        val result = if (title.isNullOrEmpty()) "自定义提示音" else title
        cache[uri] = result
        return result
    }
}
