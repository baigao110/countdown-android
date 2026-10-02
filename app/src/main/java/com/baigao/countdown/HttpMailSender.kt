package com.baigao.countdown

import java.io.File
import java.io.OutputStream

/**
 * 意见反馈的**免授权码**后台投递通道。
 *
 * 为什么还要它：SMTP 那条路（SmtpSender）要你自己在邮箱里开「IMAP/SMTP 服务」、再抄一个 16 位
 * 授权码过来 —— 对只想提个问题的人太折腾了。这个通道**什么都不用填**：直接把内容 POST 到
 * 表单接收站，站点代你把信投到 baigao110@qq.com，全在后台跑完，不打开邮箱应用。
 *
 * 两点讲究：
 * 1. **多个端点挨个试**：这类公共表单站点偶尔抽风（限流、网络），一个不通就换下一个，
 *    最后一个全挂才把原因原样带回去，交给上一层决定是退 SMTP 还是退邮箱应用；
 * 2. **附件走 multipart 的文件域**：日志、图片、视频都装在 attachment 这一格里（同名可以装多个），
 *    直接读原文件流，不额外落盘。
 *
 *  ⚠️ 别在主线程调它：网络这事儿放主线程系统会直接判「网络在主线程」卡死，调用方请丢进子线程。
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
            // 200~299 且回的是「送到了」才算成：公共站点偶尔给 200 但 body 里写着被拦了
            val ok = code in 200..299 && resp.contains("success", true)
            return Result(ok, if (ok) "HTTP $code" else "HTTP $code ${resp.take(160)}")
        } finally {
            conn.disconnect()
        }
    }

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
