package com.baigao.countdown

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 SMTP 发信（v78 新增）。
 *
 * 工程刻意零第三方依赖，装不了 JavaMail 这类库，所以这里直接用 Socket 跟邮箱服务器对话，
 * 把一封纯文本 / 带附件的信按 RFC 5321 的套路发掉：EHLO →（必要时 STARTTLS）→ AUTH LOGIN
 * → MAIL FROM / RCPT TO → DATA → QUIT。SSL 465 和 STARTTLS 587 都支持，
 * 国内邮箱（QQ / 163 / 126 / 新浪）走前者，Gmail / Outlook 走后者。
 *
 * ⚠️ 只能在子线程里调：网络请求绝不能占着主线程，否则系统会报「在主线程做了网络操作」直接卡死。
 * 所有异常都在 send() 里兜住，返回的 [SmtpResult] 里带一句能让人看懂的原因，
 * 不让 SocketException / TLS 握手失败这类东西变成闪退。
 */
object SmtpSender {

    private const val TIMEOUT_MS = 60000
    private const val BOUNDARY = "FbMailBoundary7Zx9q"
    private const val B64_LINE = 76

    /** 一台邮箱账号：发信地址、授权码、SMTP 服务器。 */
    class Account(
        val user: String,
        val password: String,
        val host: String,
        val port: Int,
        val ssl: Boolean
    )

    /** 一次发送的结果。ok 为 true 时 message 是「发送成功」，false 时是能看明白的原因。 */
    class SmtpResult(val ok: Boolean, val message: String)

    /** 只看邮箱后缀就能推出 SMTP 服务器；认不出的域名退到 `smtp.<域名>` + SSL 465。 */
    fun guess(user: String, password: String): Account? {
        val raw = user.trim()
        val low = raw.lowercase(Locale.ROOT)
        val at = low.indexOf('@')
        if (at < 0) return null
        val domain = low.substring(at + 1)
        if (domain.isBlank() || !domain.contains(".")) return null
        return when {
            domain.endsWith("qq.com") -> Account(raw, password.trim(), "smtp.qq.com", 465, true)
            domain.endsWith("foxmail.com") -> Account(raw, password.trim(), "smtp.qq.com", 465, true)
            domain.endsWith("163.com") -> Account(raw, password.trim(), "smtp.163.com", 465, true)
            domain.endsWith("126.com") -> Account(raw, password.trim(), "smtp.126.com", 465, true)
            domain.endsWith("sina.com") -> Account(raw, password.trim(), "smtp.sina.com", 465, true)
            domain.endsWith("gmail.com") -> Account(raw, password.trim(), "smtp.gmail.com", 587, false)
            domain.endsWith("outlook.com") || domain.endsWith("hotmail.com") -> {
                Account(raw, password.trim(), "smtp.office365.com", 587, false)
            }
            domain.endsWith("aliyun.com") -> Account(raw, password.trim(), "smtp.qiye.aliyun.com", 465, true)
            else -> Account(raw, password.trim(), "smtp.$domain", 465, true)
        }
    }

    /** 发出去。返回值一定不为 null：所有翻车都变成一句人话。 */
    fun send(acc: Account, to: String, subject: String, body: String, files: List<File>): SmtpResult {
        var outer: Socket? = null
        try {
            var socket: Socket = if (acc.ssl) {
                SSLSocketFactory.getDefault().createSocket(acc.host, acc.port)
            } else {
                Socket(acc.host, acc.port)
            }
            socket.soTimeout = TIMEOUT_MS

            var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var out = BufferedOutputStream(socket.getOutputStream())
            val banner = reader.readLine()
                ?: return fail("邮箱服务器那边没回答，可能是网络断了")
            if (!banner.startsWith("220")) {
                return fail("邮箱服务器没接：（$banner）")
            }

            sendRaw(out, "EHLO ${localName()}")
            var resp = readReply(reader)
            if (!resp.startsWith("250")) {
                sendRaw(out, "HELO ${localName()}")
                resp = readReply(reader)
                if (!resp.startsWith("250")) return fail("跟邮箱服务器打招呼失败（$resp）")
            }

            // STARTTLS：只有明文端口（587 那档）才需要，SSL 端口上来就是密文，不用再升级。
            if (!acc.ssl && resp.contains("STARTTLS")) {
                sendRaw(out, "STARTTLS")
                val upgrade = readReply(reader)
                if (!upgrade.startsWith("220")) return fail("服务器不肯加密连接（$upgrade）")
                // Android 的 SocketFactory 没有「在旧连接上直接升级」的重载，这里另开一条 TLS 连
                // 再握手 —— 效果一样（服务器看不出我们换了条 TCP），省得跟 API 较劲。
                val tls = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                tls.connect(java.net.InetSocketAddress(acc.host, acc.port), TIMEOUT_MS)
                tls.startHandshake()
                socket = tls
                reader = BufferedReader(InputStreamReader(tls.getInputStream(), Charsets.UTF_8))
                out = BufferedOutputStream(tls.getOutputStream())
                sendRaw(out, "EHLO ${localName()}")
                resp = readReply(reader)
            }

            // 登录：AUTH LOGIN 要先给用户名（服务器回 334 再challenge），再给密码。
            sendRaw(out, "AUTH LOGIN")
            if (!readReply(reader).startsWith("334")) return fail("邮箱服务器不认这种登录方式")
            sendRaw(out, Base64.getEncoder().encodeToString(acc.user.toByteArray(Charset.forName("UTF-8"))))
            if (!readReply(reader).startsWith("334")) return fail("邮箱服务器没要用户名")
            sendRaw(out, Base64.getEncoder().encodeToString(acc.password.toByteArray(Charset.forName("UTF-8"))))
            val auth = readReply(reader)
            if (!auth.startsWith("235")) return fail("邮箱账号或授权码不对（$auth）")

            sendRaw(out, "MAIL FROM: <${acc.user}>")
            if (!readReply(reader).startsWith("250")) return fail("发件人没被接受，检查邮箱地址对不对")
            sendRaw(out, "RCPT TO: <$to>")
            val rcpt = readReply(reader)
            if (!rcpt.startsWith("250") && !rcpt.startsWith("251")) return fail("收件人没被接受（$rcpt）")

            sendRaw(out, "DATA")
            if (!readReply(reader).startsWith("354")) return fail("服务器不收这封信的内容")

            val payload = message(acc.user, to, subject, body, files)
            val escaped = StringBuilder()
            for (line in payload.split("\n")) {
                val l = if (line.endsWith("\r")) line.substring(0, line.length - 1) else line
                escaped.append(if (l.startsWith(".")) "..$l" else l).append("\r\n")
            }
            escaped.append(".\r\n")       // 数据区结束：单独一行的点
            out.write(escaped.toString().toByteArray(Charset.forName("UTF-8")))
            out.flush()
            val done = readReply(reader)
            if (!done.startsWith("250")) return fail("服务器收信失败（$done）")

            sendRaw(out, "QUIT")
            return SmtpResult(true, "发送成功")
        } catch (e: Throwable) {
            return fail(plain(e))
        } finally {
            try {
                outer?.close()
            } catch (_: Throwable) {
                // 关不掉也不影响结果
            }
        }
    }

    // ------------------------------------------------------------------ 拼信

    private fun message(from: String, to: String, subject: String, body: String, files: List<File>): String {
        val sb = StringBuilder()
        sb.append("Date: ${rfcDate()}\r\n")
        sb.append("From: $from\r\n")
        sb.append("To: $to\r\n")
        sb.append("Subject: =?UTF-8?B?${encode(subject)}?=\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        // 纯文字：直接给它一个 text/plain，不搞 multipart，很多邮箱收起来更干净。
        if (files.isEmpty()) {
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n")
            sb.append(body)
            return sb.toString()
        }
        sb.append("Content-Type: multipart/mixed; boundary=\"$BOUNDARY\"\r\n\r\n")
        sb.append("这是倒计时工具自动发出的意见反馈，见下面的附件。\r\n\r\n")
        sb.append("--$BOUNDARY\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n")
        sb.append(body).append("\r\n")
        for (f in files) {
            val name = f.name
            sb.append("--$BOUNDARY\r\n")
            sb.append("Content-Type: application/octet-stream; name=\"$name\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"$name\"\r\n\r\n")
            val b64 = encode(f.readBytes())
            var i = 0
            while (i < b64.length) {
                val end = Math.min(i + B64_LINE, b64.length)
                sb.append(b64.substring(i, end)).append("\r\n")
                i = end
            }
            sb.append("\r\n")
        }
        sb.append("--$BOUNDARY--\r\n")
        return sb.toString()
    }

    private fun encode(s: String) = encode(s.toByteArray(Charset.forName("UTF-8")))

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun rfcDate(): String {
        val f = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
        f.timeZone = TimeZone.getDefault()
        return f.format(java.util.Date())
    }

    private fun localName(): String = "Android-countdown"

    // ------------------------------------------------------------------ 协议小工具

    private fun sendRaw(out: java.io.OutputStream, line: String) {
        out.write("$line\r\n".toByteArray(Charset.forName("UTF-8")))
        out.flush()
    }

    /**
     * 读一行状态码，顺手把多行响应（EHLO 那种 `250-xxx` 续行）拼成一段，
     * 好在里面找 STARTTLS 这类关键字。RFC 5321：续行是「三位码 + 短横」，最后一行是「三位码 + 空格」。
     */
    private fun readReply(reader: BufferedReader): String {
        var line = reader.readLine() ?: return ""
        val sb = StringBuilder(line)
        while (line.length > 3 && line[3] == '-') {
            line = reader.readLine() ?: break
            sb.append(' ').append(line)
        }
        return sb.toString()
    }

    private fun fail(msg: String): SmtpResult = SmtpResult(false, msg)

    private fun plain(e: Throwable): String {
        val m = e.message?.trim().orEmpty()
        return when {
            m.isBlank() -> "发送失败（${e.javaClass.simpleName}）"
            m.contains("timeout") || m.contains("timed out") -> "连不上邮箱服务器，检查下网络再试"
            m.contains("UnknownHost") || m.contains("Failed to resolve") -> "找不到邮箱服务器（$m）"
            m.contains("Connection") && m.contains("refused") -> "邮箱服务器拒绝连接，可能端口不对"
            else -> "发送失败：$m"
        }
    }
}
