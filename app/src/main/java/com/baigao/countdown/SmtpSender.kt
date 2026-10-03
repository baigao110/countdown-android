package com.baigao.countdown

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 SMTP 发信（v78 新增，v82 修认证与超时）。
 *
 * 工程刻意零第三方依赖，装不了 JavaMail 这类库，所以这里直接用 Socket 跟邮箱服务器对话，
 * 把一封纯文本 / 带附件的信按 RFC 5321 的套路发掉：EHLO →（必要时 STARTTLS）→ AUTH LOGIN
 * → MAIL FROM / RCPT TO → DATA → QUIT。SSL 465 和 STARTTLS 587 都支持，
 * 国内邮箱（QQ / 163 / 126 / 新浪）走前者，Gmail / Outlook 走后者。
 *
 * ⚠️ 只能在子线程里调：网络请求绝不能占着主线程，否则系统会报「在主线程做了网络操作」直接卡死。
 * 所有异常都在 send() 里兜住，返回的 [SmtpResult] 里带一句能让人看懂的原因，
 * 不让 SocketException / TLS 握手失败这类东西变成闪退。
 *
 * ⚠️ v82 修的两处硬伤（都是「填了正确授权码还是发不出去」的真凶）：
 * 1. **连接没有超时** —— `SSLSocketFactory.createSocket(host, port)` 走的是无超时 connect，
 *    手机网络到 465 不通时不是报错而是无限干等，表现就是「点了提交半天没动静」。
 *    现在显式 `connect(InetSocketAddress(...), 20s)`，并且一条路不通就换备用端口再试。
 * 2. **认证失败还要再撞一遍** —— 服务器已经回 535「账号异常 / 密码错 / 登录频率受限」，
 *    代码却因为「PLAIN 也通告了」又拿同一个错码发了一次 AUTH PLAIN。
 *    等于一次填错换来两次失败记录，QQ 那句「login frequency limited」就是这么把自己撞出来的，
 *    越试越容易进限流。现在 5xx 这类硬伤一律不再重试。
 */
object SmtpSender {

    /** 读写超时：手机上 60 秒太久了，25 秒足够等到服务器的任何应答。 */
    private const val TIMEOUT_MS = 25000
    /** 连不上服务器的宽限：20 秒连不上基本就是这条路不通，换下一条。 */
    private const val CONNECT_MS = 20000
    /** 握手后等 greeting 的宽限时间：有就接走，没有也别为它耗掉整条流程。 */
    private const val GREETING_GRACE_MS = 2000
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

    /**
     * 一次发送的结果。
     *
     * `retryable` 为 true 表示「这属于连接没通上，换个端口可能就成了」（网络 / 超时 / 握手失败）；
     * false 表示「服务器明确认下了这次失败，再试也是同一个答案」（认证、收件人这些硬伤）——
     * 调用方拿它决定要不要继续换路，别让用户对着一句「账号或授权码不对」反复点。
     */
    class SmtpResult(val ok: Boolean, val message: String, val retryable: Boolean = false)

    /**
     * 一个邮箱账号可能有多条通路：主端口（国内多见 465 SSL）连不上时还有备用端口（587 STARTTLS）。
     * 状态行只显示 `routes[0]`（主那条），发送时按顺序试，网络类失败才往下走。
     */
    class Profile(val user: String, val password: String, val routes: List<Account>) {
        val host: String get() = routes[0].host
        val port: Int get() = routes[0].port
    }

    /**
     * 只看邮箱后缀就能推出该往哪儿发；认不出的域名退到 `smtp.<域名>`。
     * 每条路都带一个备用端口，主端口是网络不通时接着试的那条。
     */
    fun profileOf(user: String, password: String): Profile? {
        val raw = user.trim()
        val low = raw.lowercase(Locale.ROOT)
        val at = low.indexOf('@')
        if (at < 0) return null
        val domain = low.substring(at + 1)
        if (domain.isBlank() || !domain.contains(".")) return null
        val pass = password.trim()
        // 备用端口：587（明文 + STARTTLS）。国内服务商 465 和 587 一般同一个后端，
        // 所以只有「连不上」才值得换过去；认证失败不会走到这里（见 send 的判断）。
        val sslRoutes = listOf(
            Account(raw, pass, "smtp.$domain", 465, true),
            Account(raw, pass, "smtp.$domain", 587, false)
        )
        return when {
            domain.endsWith("qq.com") || domain.endsWith("foxmail.com") -> {
                Profile(raw, pass, listOf(
                    Account(raw, pass, "smtp.qq.com", 465, true),
                    Account(raw, pass, "smtp.qq.com", 587, false)
                ))
            }
            domain.endsWith("163.com") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.163.com", 465, true),
                Account(raw, pass, "smtp.163.com", 587, false)
            ))
            domain.endsWith("126.com") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.126.com", 465, true),
                Account(raw, pass, "smtp.126.com", 587, false)
            ))
            domain.endsWith("yeah.net") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.yeah.net", 465, true),
                Account(raw, pass, "smtp.yeah.net", 587, false)
            ))
            domain.endsWith("sina.com") || domain.endsWith("sina.cn") -> {
                Profile(raw, pass, listOf(
                    Account(raw, pass, "smtp.sina.com", 465, true),
                    Account(raw, pass, "smtp.sina.com", 587, false)
                ))
            }
            domain.endsWith("sohu.com") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.sohu.com", 465, true),
                Account(raw, pass, "smtp.sohu.com", 587, false)
            ))
            domain.endsWith("139.com") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.139.com", 465, true),
                Account(raw, pass, "smtp.139.com", 587, false)
            ))
            domain.endsWith("189.cn") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.189.cn", 465, true),
                Account(raw, pass, "smtp.189.cn", 587, false)
            ))
            domain.endsWith("aliyun.com") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.qiye.aliyun.com", 465, true),
                Account(raw, pass, "smtp.qiye.aliyun.com", 587, false)
            ))
            // 微软系：587 STARTTLS 是它们的主场，备用才试 465
            domain.endsWith("gmail.com") -> Profile(raw, pass, listOf(
                Account(raw, pass, "smtp.gmail.com", 587, false),
                Account(raw, pass, "smtp.gmail.com", 465, true)
            ))
            domain.endsWith("outlook.com") || domain.endsWith("hotmail.com") -> {
                Profile(raw, pass, listOf(
                    Account(raw, pass, "smtp.office365.com", 587, false),
                    Account(raw, pass, "smtp.office365.com", 465, true)
                ))
            }
            else -> Profile(raw, pass, sslRoutes)
        }
    }

    /**
     * 发出去。返回值一定不为 null：所有翻车都变成一句人话。
     *
     * 顺序试每一条通路：**只有「连接类」失败才换下一条**；服务器已经明确说「账号 / 授权码不对」
     * 这种，换端口也还是同一台服务器、同一个答案，那就把它的原话直接还给用户。
     */
    fun send(profile: Profile, to: String, subject: String, body: String, files: List<File>): SmtpResult {
        var last: SmtpResult? = null
        for (route in profile.routes) {
            val r = attempt(route, to, subject, body, files)
            if (r.ok) return r
            last = r
            if (!r.retryable) break
        }
        return last ?: SmtpResult(false, "发送失败，没可用的发信通路")
    }

    /** 单条通路的完整会话：连上 → 打招呼 →（升 TLS）→ 登录 → 发信头 → DATA → QUIT。 */
    private fun attempt(acc: Account, to: String, subject: String, body: String, files: List<File>): SmtpResult {
        // 整条会话里「当前这一层连接」：STARTTLS 换成 TLS 之后它会跟着换，finally 只关它这一把
        var liveSocket: Socket? = null
        try {
            var socket: Socket = if (acc.ssl) {
                val raw = SSLSocketFactory.getDefault().createSocket()
                val tls = raw as SSLSocket
                tls.connect(InetSocketAddress(acc.host, acc.port), CONNECT_MS)
                tls.startHandshake()
                tls
            } else {
                val plain = Socket()
                plain.connect(InetSocketAddress(acc.host, acc.port), CONNECT_MS)
                plain
            }
            liveSocket = socket
            socket.soTimeout = TIMEOUT_MS

            var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var out = BufferedOutputStream(socket.getOutputStream())
            val banner = reader.readLine()
                ?: return fail("邮箱服务器那边没回答，可能是网络断了")
            if (!banner.startsWith("220")) {
                return fail("邮箱服务器没接：（$banner）", true)
            }

            sendRaw(out, "EHLO ${localName()}")
            var resp = readReply(reader)
            if (!resp.startsWith("250")) {
                sendRaw(out, "HELO ${localName()}")
                resp = readReply(reader)
                if (!resp.startsWith("250")) return fail("跟邮箱服务器打招呼失败（$resp）", true)
            }

            // STARTTLS：只有明文端口（587 那档）才需要，SSL 端口上来就是密文，不用再升级。
            // ⚠️ 这里踩过大坑：早先是「STARTTLS 之后另开一条新 TCP 再握手」，看着省事，
            // 实际会坏 —— 新连接上服务器照旧先回一句 greeting，而我们握手完就直接 EHLO，
            // 那句 greeting 没人接、EHLO 又迟迟等不到应答，最后卡到超时。
            // 正解是换个 TLS 连接之后**先把那句 greeting 吞掉**再 EHLO。
            // 为什么不能「在旧连接上就地升级」：SocketFactory 里那个
            // createSocket(Socket, host, port, autoClose) 是 protected，App 根本调不到。
            if (!acc.ssl && resp.contains("STARTTLS")) {
                sendRaw(out, "STARTTLS")
                val upgrade = readReply(reader)
                if (!upgrade.startsWith("220")) return fail("服务器不肯加密连接（$upgrade）", true)
                val plainBeforeUpgrade = socket       // 升级时被换下的那层，收尾要自己来
                val fresh = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                fresh.connect(InetSocketAddress(acc.host, acc.port), CONNECT_MS)
                fresh.startHandshake()
                liveSocket = fresh                    // 从这一刻起，关连接就交给 TLS 层
                runCatching { plainBeforeUpgrade.close() }
                // 吞 greeting：短超时读一行，它要是压根没说话（有些服务器握手后不吭声），
                // 两秒就自己放弃，不会把整条发送流程拖到超时。
                val probe = BufferedReader(InputStreamReader(fresh.getInputStream(), Charsets.UTF_8))
                fresh.soTimeout = GREETING_GRACE_MS
                runCatching { probe.readLine() }
                fresh.soTimeout = TIMEOUT_MS
                socket = fresh
                runCatching { probe.close() }
                reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                out = BufferedOutputStream(socket.getOutputStream())
                sendRaw(out, "EHLO ${localName()}")
                resp = readReply(reader)
                if (!resp.startsWith("250")) return fail("加密后跟邮箱服务器打招呼失败（$resp）", true)
            }

            // 登录：国内邮箱普遍吃 AUTH LOGIN，微软系 / 少数企业邮箱只认 AUTH PLAIN。
            // ⚠️ 但 PLAIN 只在服务器 EHLO 里真的通告过、且刚才那次不是硬失败时才试：
            // 对没通告的方式硬发，服务器回 500 之后这条会话就脏了；更糟的是拿同一个错码
            // 连撞两次，像 QQ 那种会记「登录频率受限」的服务器，等于自己把自己限掉。
            var authErr = authLogin(reader, out, acc.user, acc.password)
            if (authErr != null && !hardAuth(authErr) && resp.contains("AUTH PLAIN")) {
                authErr = authPlain(reader, out, acc.user, acc.password)
            }
            if (authErr != null) {
                // 认证是硬伤：retryable = false，上层不会傻乎乎地再换一条路重试
                return fail(authErr, false)
            }

            sendRaw(out, "MAIL FROM: <${acc.user}>")
            if (!readReply(reader).startsWith("250")) return fail("发件人没被接受，检查邮箱地址对不对", false)
            sendRaw(out, "RCPT TO: <$to>")
            val rcpt = readReply(reader)
            if (!rcpt.startsWith("250") && !rcpt.startsWith("251")) return fail("收件人没被接受（$rcpt）", false)

            sendRaw(out, "DATA")
            if (!readReply(reader).startsWith("354")) return fail("服务器不收这封信的内容", true)

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
            if (!done.startsWith("250")) return fail("服务器收信失败（$done）", true)

            sendRaw(out, "QUIT")
            return SmtpResult(true, "发送成功")
        } catch (e: Throwable) {
            // 没连上 / 握手挂了 / 中途断线都属于「这条路不通，换一条可能就成了」
            return fail(plain(e), true)
        } finally {
            try {
                liveSocket?.close()
            } catch (_: Throwable) {
                // 关不掉也不影响结果
            }
        }
    }

    /** 服务器摆明了拒绝的认证失败：换端口重试也没意义。 */
    private fun hardAuth(msg: String): Boolean {
        return msg.contains("535") || msg.contains("534") || msg.contains("530") ||
                msg.contains("451") || msg.contains("5.7.")
    }

    // ------------------------------------------------------------------ 登录

    /** AUTH LOGIN：服务器先回 334 challenge 用户名，再回 334 challenge 密码。通过返回 null。 */
    private fun authLogin(
        reader: BufferedReader,
        out: java.io.OutputStream,
        user: String,
        pass: String
    ): String? {
        sendRaw(out, "AUTH LOGIN")
        if (!readReply(reader).startsWith("334")) return "邮箱服务器不认 AUTH LOGIN 这种登录方式"
        sendRaw(out, b64(user))
        if (!readReply(reader).startsWith("334")) return "邮箱服务器没要用户名"
        sendRaw(out, b64(pass))
        val auth = readReply(reader)
        return if (auth.startsWith("235")) null else explainAuth(auth)
    }

    /** AUTH PLAIN：一条 base64 里塞「用户名\0密码」，腾讯系不认、微软系认，作为 fallback。 */
    private fun authPlain(
        reader: BufferedReader,
        out: java.io.OutputStream,
        user: String,
        pass: String
    ): String? {
        sendRaw(out, "AUTH PLAIN " + b64("\u0000$user\u0000$pass"))
        val auth = readReply(reader)
        return if (auth.startsWith("235")) null else explainAuth(auth)
    }

    /**
     * 把服务器给的一串状态码翻成人话，顺带说清授权码到哪儿去拿。
     *
     * 这几条都是用户真会踩的：535 里 QQ 那句原文其实塞了四种可能（账号异常 / 没开服务 /
     * 密码错 / 登录太频繁 / 系统忙），只回「授权码不对」会让人一错再错 —— 尤其是**填错一次
     * 又连着点好几次提交**导致频率受限的那种，越点越进不去。
     */
    private fun explainAuth(raw: String): String {
        val r = raw.trim()
        return when {
            r.contains("535") ->
                "账号或授权码不对（服务器回：$r）。" +
                        "注意填的一定是**授权码而不是登录密码**：QQ 邮箱在「设置 → 账户 → POP3/IMAP/SMTP 服务」" +
                        "里开启后会给你一个 16 位码，粘到这儿来。"
            r.contains("534") || r.contains("Authentication failed") || r.contains("5.7.") ->
                "这个邮箱不许这样登录（服务器回：$r）。多半是没开 SMTP 服务，或者要开二次验证 —— " +
                        "去邮箱网页版「设置 → 账户」把 SMTP 打开，用它发你的那个授权码。"
            r.contains("login frequency") || r.contains("频率") || r.contains("frequency") ->
                "这个邮箱刚刚登录失败太多次，被临时限流了（服务器回：$r）。" +
                        "别再连着点了，等十来分钟再试；实在不行就先用邮箱应用发这封。"
            r.contains("451") -> "邮箱服务器这会儿忙（$r），过一两分钟再试一次。"
            r.contains("530") -> "服务器要求先加密再登录（$r）—— 多半是端口选错了。"
            else -> "登录没通过（服务器回：$r）"
        }
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charset.forName("UTF-8")))

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

    private fun fail(msg: String, retryable: Boolean = true) = SmtpResult(false, msg, retryable)

    private fun plain(e: Throwable): String {
        val m = e.message?.trim().orEmpty()
        return when {
            m.isBlank() -> "发送失败（${e.javaClass.simpleName}）"
            m.contains("timeout") || m.contains("timed out") -> "连不上邮箱服务器（$m），检查下网络再试"
            m.contains("UnknownHost") || m.contains("Failed to resolve") -> "找不到邮箱服务器（$m）"
            m.contains("Connection") && m.contains("refused") -> "邮箱服务器拒绝连接，可能端口不对"
            else -> "发送失败：$m"
        }
    }
}
