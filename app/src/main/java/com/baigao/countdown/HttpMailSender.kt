package com.baigao.countdown

import java.io.File
import java.io.OutputStream

/**
 * 意见反馈的「免授权码」后台投递通道（POST 表单接收站，站点代投到 baigao110@qq.com）。
 *
 * 目的是让不想折腾的人**什么都不用填**就能把反馈送出去。⚠️ 但实测下来：FormSubmit 这类公共
 * 表单站只认**真实网页表单提交的 POST**，从手机 App 里直发会被它挡回来（它照样给 200，只是
 * 回 `{"success":"false", ...}`，或者把错误页渲染成 HTML 的 200）。所以：
 *
 * 1. **判定必须看成败，不能看状态码**：见 `judge()` —— 早先按「body 里含 success 就算成」判，
 *    结果 `"success":"false"` 也被当成发送成功，用户这边弹「反馈已发送」、那边邮箱空空，这是
 *    这个通道目前最大的坑，别再犯；
 * 2. 回包是 JSON 就照 `success` 的值判、是页面就一律当没送到，**宁可如实报错也绝不假成功**；
 *    送不出去就让上一层退到「自己邮箱 + 授权码」或「打开邮箱应用」这两条真能走通的路。
 *
 * 另外两点讲究：多个端点挨个试（一个不通换下一个）；附件走 multipart 的文件域（同名可装多个），
 * 直接读原文件流不额外落盘。⚠️ 别在主线程调它：网络放主线程系统会直接判「网络在主线程」卡死。
 */
object HttpMailSender {

    /** 连接和读都给 20 秒：手机在电梯 / 地库里别让人干等太久。 */
    private const val TIMEOUT_MS = 20_000

    private const val CRLF = "\r\n"

    /** 表单接收端点（免 key、免注册）。第一个是 AJAX 版（回 JSON），第二个是普通表单版兜底。 */
    private val ENDPOINTS = listOf(
        "https://formsubmit.co/ajax/",
        "https://formsubmit.co/"
    )

    private const val FIELD_TO = "_to"
    private const val FIELD_SUBJECT = "_subject"
    private const val FIELD_MESSAGE = "message"
    private const val FIELD_REPLYTO = "_replyto"
    private const val FIELD_ATTACH = "attachment"

    private val UTF8 = java.nio.charset.Charset.forName("UTF-8")

    data class Result(val ok: Boolean, val message: String)

    /**
     * 把一封信发出去。
     *
     * @param to      收件人（反馈箱）
     * @param subject 标题（中文，站点那边会原样带上）
     * @param body    正文；附件真投出去了调用方就不必再把日志抄进正文
     * @param files   附件（图片 / 视频 / 运行日志），可以为空
     * @param replyTo 想让对方直接回复到的邮箱（没填就不设回复地址）
     */
    fun send(to: String, subject: String, body: String, files: List<File>, replyTo: String): Result {
        var lastMsg = "没有任何投递通道可用"
        for (ep in ENDPOINTS) {
            val url = if (ep.endsWith("/")) ep + to else ep
            try {
                val res = post(url, subject, body, files, replyTo)
                if (res.ok) return res
                lastMsg = res.message
            } catch (e: Throwable) {
                lastMsg = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return Result(false, lastMsg)
    }

    private fun post(url: String, subject: String, body: String, files: List<File>, replyTo: String): Result {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        val boundary = "----BaigaoCountdown" + System.currentTimeMillis()
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Win11倒计时工具BYbaigao110/1.0")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val out: OutputStream = conn.outputStream
            try {
                textPart(out, boundary, FIELD_TO, url)
                textPart(out, boundary, FIELD_SUBJECT, subject)
                if (replyTo.contains("@")) textPart(out, boundary, FIELD_REPLYTO, replyTo)
                textPart(out, boundary, FIELD_MESSAGE, body)
                for (f in files) {
                    if (f.exists() && f.length() > 0) filePart(out, boundary, f)
                }
                val tail = ("--" + boundary + "--" + CRLF).toByteArray(UTF8)
                out.write(tail)
                out.flush()
            } finally {
                try { out.close() } catch (_: Throwable) { }
            }
            val code = conn.responseCode
            val resp = readAll(conn, code)
            val verdict = judge(code, resp)
            return Result(verdict.first, verdict.second)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 判定这一趟到底送没送到。
     *
     * 这里踩过一次大坑：早先只按「2xx 且 body 里含 success」算成功，可 FormSubmit 回的是
     * `{"success":"false","message":"..."}` —— **里面正好也有 success 这四个字母**，于是被判成发送成功：
     * 用户这边弹「反馈已发送，请耐心等待回复！」，那边邮箱干干净净。所以判定必须看 **success 的值**，
     * 不能看有没有这串字；回包是页面（HTML）时更不能当成功 —— 那是站点把错误页渲染成了 200。
     */
    private fun judge(code: Int, resp: String): Pair<Boolean, String> {
        if (code !in 200..299) return false to ("HTTP $code。" + explain(snippet(resp)))
        val r = resp.trim()
        if (r.startsWith("{")) {
            val flag = Regex("\"success\"\\s*:\\s*\"?([A-Za-z]+)\"?").find(r)
            val ok = flag?.groupValues?.getOrNull(1)?.equals("true", true) ?: false
            val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(r)
                    ?.groupValues?.getOrNull(1).orEmpty()
            return if (ok) true to ("HTTP $code") else false to ("HTTP $code。" + explain(msg))
        }
        if (r.contains("<html", true)) return false to ("HTTP $code。" + explain(""))
        return true to ("HTTP $code")
    }

    /** 站点回的话（英文）翻成人话；翻不出来就原样带上，至少让用户知道不是「网络断了」。 */
    private fun explain(msg: String): String {
        val m = msg.trim()
        return when {
            m.contains("web server", true) ->
                "这个免费收件通道只认网页表单，手机里直接发会被它拦下来。它回的是：$m"
            m.contains("activation", true) || m.contains("actived", true) ->
                "这个通道要先激活一次：它已经往收件箱发了一封「激活」邮件，去邮箱里点开那个链接就通了。它回的是：$m"
            m.contains("captcha", true) ->
                "这个通道要填验证码，App 里没法自动填。它回的是：$m"
            m.isBlank() ->
                "站点给了一个网页回包（不是成功提示），多半是把错误页渲染成了成功"
            else ->
                "站点回的话是：$m"
        }
    }

    /** 回包压成一行短句，别把整坨 HTML 塞进提示里。 */
    private fun snippet(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(160)

    private fun textPart(out: OutputStream, boundary: String, name: String, value: String) {
        val head = (("--" + boundary + CRLF) +
                ("Content-Disposition: form-data; name=" + quote(name) + CRLF) +
                ("Content-Type: text/plain; charset=utf-8" + CRLF) +
                ("Content-Transfer-Encoding: 8bit" + CRLF) +
                (CRLF)).toByteArray(UTF8)
        out.write(head)
        out.write(value.toByteArray(UTF8))
        out.write(CRLF.toByteArray(UTF8))
    }

    private fun filePart(out: OutputStream, boundary: String, f: File) {
        val head = (("--" + boundary + CRLF) +
                ("Content-Disposition: form-data; name=" + quote(FIELD_ATTACH) + "; filename=" + quote(f.name) + CRLF) +
                ("Content-Type: application/octet-stream" + CRLF) +
                ("Content-Transfer-Encoding: binary" + CRLF) +
                (CRLF)).toByteArray(UTF8)
        out.write(head)
        java.io.FileInputStream(f).use { it.copyTo(out) }
        out.write(CRLF.toByteArray(UTF8))
    }

    /** 字段名 / 文件名统一用双引号包起来（名字里可能有中文和空格，这里按裸字符串发出去）。 */
    private fun quote(s: String): String = "\"" + s + "\""

    /** 响应的正文（出错时从 errorStream 读，成功时从 inputStream 读）。 */
    private fun readAll(conn: java.net.HttpURLConnection, code: Int): String {
        return try {
            val s = if (code in 200..299) conn.inputStream else conn.errorStream
            val ba = java.io.ByteArrayOutputStream()
            if (s != null) s.copyTo(ba) else return ""
            ba.toString("UTF-8")
        } catch (_: Throwable) {
            ""
        }
    }
}
