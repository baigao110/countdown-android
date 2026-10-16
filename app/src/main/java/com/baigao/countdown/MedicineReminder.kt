package com.baigao.countdown

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import org.json.JSONArray
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 每日吃药提醒（纯框架实现，零第三方依赖）。
 *
 * 三件事：
 * 1. 每天在用户设定的时刻（默认 09:00）发一条系统通知，通知上带「已吃药」按钮；
 * 2. 点「已吃药」把当天记进吃药记录，通知自动消失；
 * 3. 记录以「yyyy-MM-dd」为键存在 SharedPreferences 里，供「吃药日历」按天显示。
 *
 * 开关与提醒时间都在「关于」页里改（默认关闭）；关掉后闹钟一并取消。
 *
 * ---------- v1.0.0.21：为什么退出 App 后就不提醒了 ----------
 * 只挂一个 AlarmManager 精确闹钟，在下面几种情况里会「静默失效」：
 *   - 国产 ROM 从最近任务划掉卡片 = 强制停止，系统会**把这个应用的所有闹钟清掉**，
 *     而且停止状态下的包连开机广播都收不到，于是再也不会重排；
 *   - Doze 期间 `setExactAndAllowWhileIdle` 会被推迟到维护窗口，早上那种长时间静置
 *     的场景能晚几十分钟。
 * 所以这里改成**三路冗余 + 同一天只提醒一次**：
 *   ① `setAlarmClock`（系统级闹钟，能穿透 Doze，不需要精确闹钟权限）；
 *   ② 每天重复的非精确闹钟（主路被 ROM 清掉时兜底，提前/延后触发都不会重复打扰）；
 *   ③ JobScheduler 周期巡检（15 分钟一趟、重启自动恢复）：发现闹钟没了就补挂，
 *      发现今天该提醒的时刻已过却还没提醒过就**补发**通知。
 * 另外每次打开 App 都会 `catchUp()`：如果今天的提醒点已经过了却没响过，立刻补一条。
 *
 * ---------- v1.0.0.24：改了「下次提醒」之后仍然收不到 ----------
 * 前面三路在「改过时间」这一档上还是会漏：主路只挂一个点，ROM 一旦把闹钟清掉或推迟，
 * 当天就再没机会。于是再加两档：
 *   - 到点后 2 分钟、30 分钟各补响一次（REQ 4116 / 4117）；
 *   - 精确闹钟（REQ 4118）与系统闹钟同一时刻再挂一路；
 *   - 巡检再加一个入口（更新检查的 JobService 里也跑一次），并记下「上次真正发出提醒」的时刻，
 *     自检页一眼能分清是「压根没发」还是「发了但被系统拦下」。
 */
object MedicineReminder {

    /**
     * 渠道 id 带版本后缀：渠道重要性一旦定下来就不能就地修改，被系统/用户降成
     * 「无声通知」后 notify() 会静默失败（不报错、不显示）。换新 id = 重置回最高级。
     */
    const val CHANNEL_ID = "medicine_reminder_v26"
    /** 历代旧渠道，一律清掉。 */
    private val OLD_CHANNEL_IDS = listOf("medicine_reminder", "medicine_reminder_high")
    /** 常驻状态通知渠道：开启吃药提醒时显示「已开启」状态，不响铃（避免每次刷新都响）。 */
    const val STATUS_CHANNEL_ID = "medicine_reminder_status_v33"
    /** 闹钟通知当前是否正在展示：存展示当天日期，非空=展示中；跨天即视为过期失效。 */
    private const val KEY_ALARM_DATE = "alarm_showing_date"
    const val ACTION_ALARM = "com.baigao.countdown.action.MEDICINE_ALARM"
    const val ACTION_TAKEN = "com.baigao.countdown.action.MEDICINE_TAKEN"

    private const val PREF = "medicine_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_TIMES_PER_DAY = "times_per_day"
    private const val KEY_DOSE_TIMES = "dose_times"
    private const val KEY_DOSE_CONTEXTS = "dose_contexts"
    private const val KEY_TAKEN = "taken"

    /** 8 种「服药时机」选项（在吃药时间面板里 8 选 1）。 */
    val DOSE_CONTEXTS = arrayOf("空腹服", "餐前服", "随餐服", "餐后服", "晨服", "睡前服", "间隔固定服", "发作前服")
    val DOSE_CONTEXT_DESC = arrayOf(
        "空腹服：餐前1小时或餐后2小时，适合左甲状腺素钠、阿仑膦酸钠等受食物干扰大的药物。",
        "餐前服：餐前30分钟，适合胃黏膜保护药、促胃动力药，能让药物提前接触起效部位。",
        "随餐服：吃饭的同时服药，适合部分降糖药、脂溶性维生素，可增强吸收或降低胃肠刺激。",
        "餐后服：餐后15-30分钟，适合阿司匹林、布洛芬等刺激性药物，减少胃肠道不适。",
        "晨服：早晨6-8点，适合激素类药物，模拟人体自然分泌节律，大幅降低副作用。",
        "睡前服：入睡前15-30分钟，适合安眠药、助眠类抗过敏药、他汀类降脂药，匹配夜间药效需求。",
        "间隔固定服：按每6/8/12小时均匀间隔服用，适合抗生素、部分降压药，维持稳定血药浓度。",
        "发作前服：晕车药上车前半小时、止喘药哮喘易发时段前服用，提前覆盖症状高峰。"
    )
    /** 各剂次已提醒记录：set of "yyyy-MM-dd#i"，保证同一剂一天只发一次。 */
    private const val KEY_NOTIFIED_DOSES = "notified_doses"
    /** 漏服超过此窗口（默认 4 小时）视为已漏，不再补发，避免补发骚扰。 */
    private const val LATE_WINDOW = 4L * 60 * 60 * 1000
    /** 上次挂载闹钟的时刻（自检页显示用）。 */
    private const val KEY_LAST_SCHEDULE = "last_schedule"
    /** 用户手动指定的「下一次提醒」时刻（毫秒；0 = 未指定，按每天固定时刻推算）。 */
    private const val KEY_NEXT_CUSTOM = "next_custom"
    /** 上次真正发出通知的时刻（自检页显示，用来判断「压根没发」还是「发了被拦」）。 */
    private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"

    private const val NOTIF_ID = 20331
    private const val REQ_ALARM = 4111
    private const val REQ_ALARM_BACKUP = 4114
    /** 补响 1：到点后 2 分钟再试一次（主路被系统推迟 / 丢掉时兜住）。 */
    private const val REQ_ALARM_RETRY1 = 4116
    /** 补响 2：到点后 30 分钟再试一次。 */
    private const val REQ_ALARM_RETRY2 = 4117
    /** 精确闹钟：与系统闹钟同一时刻，多一路就有多一分准时（没权限会被 catch 掉）。 */
    private const val REQ_ALARM_EXACT = 4118
    private const val REQ_ALARM_SHOW = 4115
    private const val REQ_TAKEN = 4112
    private const val REQ_OPEN = 4113
    /** 全屏意图（息屏时直接弹吃药页）用的请求码。 */
    private const val REQ_FULL = 4119

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前闹钟通知展示的是哪一天（非空=正在展示；跨天即过期）。 */
    private fun alarmShowingDate(ctx: Context): String =
        prefs(ctx).getString(KEY_ALARM_DATE, "") ?: ""

    /** 标记闹钟通知是否正在展示（展示中记当天日期，false 则清空）。 */
    private fun setAlarmShowing(ctx: Context, showing: Boolean) {
        prefs(ctx).edit().putString(KEY_ALARM_DATE, if (showing) todayKey() else "").apply()
    }

    // ---------------- 开关与提醒时间 ----------------

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun hour(ctx: Context): Int = prefs(ctx).getInt(KEY_HOUR, 9)
    fun minute(ctx: Context): Int = prefs(ctx).getInt(KEY_MINUTE, 0)

    fun timeText(ctx: Context): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour(ctx), minute(ctx))

    fun timesPerDay(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TIMES_PER_DAY, 1).coerceIn(1, 4)

    fun setTimesPerDay(ctx: Context, n: Int) {
        val nn = n.coerceIn(1, 4)
        prefs(ctx).edit().putInt(KEY_TIMES_PER_DAY, nn).apply()
        regenerateDosePlan(ctx, nn)
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 重新生成「每天 N 次」的均匀时刻表（以首剂时间铺底）与默认服药时机（间隔固定服）。 */
    private fun regenerateDosePlan(ctx: Context, n: Int) {
        val base = hour(ctx) * 60 + minute(ctx)
        val gen = (0 until n).map { i ->
            val tot = (base + i * (1440 / n)) % 1440
            (tot / 60) to (tot % 60)
        }
        setDoseTimes(ctx, gen)
        prefs(ctx).edit().putString(KEY_DOSE_CONTEXTS, (0 until n).joinToString(",") { "6" }).apply()
    }

    /** 读取各次服药时刻（存储优先；缺失则按均匀铺开生成并落盘）。 */
    fun getDoseTimesList(ctx: Context): List<Pair<Int, Int>> {
        val n = timesPerDay(ctx)
        val raw = prefs(ctx).getString(KEY_DOSE_TIMES, "") ?: ""
        val list = if (raw.isBlank()) emptyList() else raw.split(",").mapNotNull { seg ->
            val parts = seg.split(":")
            if (parts.size == 2) parts[0].toIntOrNull() to parts[1].toIntOrNull() else null
        }.filter { it.first != null && it.second != null }.map { (h, m) -> (h ?: 0) to (m ?: 0) }
        if (list.size == n) return list
        val base = hour(ctx) * 60 + minute(ctx)
        val gen = (0 until n).map { i ->
            val tot = (base + i * (1440 / n)) % 1440
            (tot / 60) to (tot % 60)
        }
        setDoseTimes(ctx, gen)
        return gen
    }

    private fun setDoseTimes(ctx: Context, list: List<Pair<Int, Int>>) {
        val str = list.joinToString(",") { "%02d:%02d".format(Locale.getDefault(), it.first, it.second) }
        prefs(ctx).edit().putString(KEY_DOSE_TIMES, str).apply()
    }

    /** 修改某一次（index）的服药时刻，并同步首剂时间（index=0 时）。 */
    fun setDoseTime(ctx: Context, index: Int, h: Int, m: Int) {
        val list = getDoseTimesList(ctx).toMutableList()
        while (list.size <= index) list.add(0 to 0)
        list[index] = h.coerceIn(0, 23) to m.coerceIn(0, 59)
        setDoseTimes(ctx, list)
        if (index == 0) prefs(ctx).edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 读取某次服药时机索引（默认间隔固定服=6）。 */
    fun doseContextOf(ctx: Context, index: Int): Int {
        val raw = prefs(ctx).getString(KEY_DOSE_CONTEXTS, "") ?: ""
        return raw.split(",").getOrNull(index)?.toIntOrNull() ?: 6
    }

    fun doseContextLabel(ctx: Context, index: Int): String =
        DOSE_CONTEXTS.getOrElse(doseContextOf(ctx, index)) { DOSE_CONTEXTS[6] }

    /** 修改某次服药时机索引。 */
    fun setDoseContext(ctx: Context, index: Int, ctxIndex: Int) {
        val n = timesPerDay(ctx)
        val arr = (0 until n).map { doseContextOf(ctx, it) }.toMutableList()
        while (arr.size <= index) arr.add(6)
        arr[index] = ctxIndex.coerceIn(0, DOSE_CONTEXTS.size - 1)
        prefs(ctx).edit().putString(KEY_DOSE_CONTEXTS, arr.joinToString(",")).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 一次性保存全部各次的时刻与服药时机，并刷新闹钟与常驻通知。 */
    fun applyDosePlan(ctx: Context, times: List<Pair<Int, Int>>, ctxIdx: List<Int>) {
        setDoseTimes(ctx, times)
        if (times.isNotEmpty())
            prefs(ctx).edit().putInt(KEY_HOUR, times[0].first).putInt(KEY_MINUTE, times[0].second).apply()
        prefs(ctx).edit().putString(KEY_DOSE_CONTEXTS, ctxIdx.joinToString(",")).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 今天各次服药时刻，按服药次序（第 1 次、第 2 次……）排列；取自存储，可逐次自行调整。 */
    fun doseTimes(ctx: Context): List<Pair<Int, Int>> = getDoseTimesList(ctx)

    /** 今天各次服药的毫秒值（与 doseTimes 顺序一致）。 */
    private fun todayDoseMillis(ctx: Context): List<Long> {
        val baseDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return doseTimes(ctx).map { (h, m) -> baseDay + (h * 60 + m) * 60_000L }
    }

    /** 第 i 次服药的标记（① / ② / ③ / ④），在日历与提醒里区分不同次数。 */
    fun doseMarker(i: Int): String = arrayOf("①", "②", "③", "④").getOrElse(i) { "${i + 1}." }

    /** 第 i 次服药的颜色：亮蓝 / 橙 / 绿 / 紫，肉眼可区分。 */
    fun doseColor(i: Int): Int =
        intArrayOf(0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt(), 0xFFBF5AF2.toInt())
            .getOrElse(i) { 0xFF9AA0B5.toInt() }

    /** 把「每天 N 次」的时刻拼成可读串，含次数标记与服药时机，如「① 09:00 空腹服 ② 21:00 睡前服」。 */
    fun doseScheduleText(ctx: Context): String =
        doseTimes(ctx).mapIndexed { i, (h, m) ->
            "${doseMarker(i)} ${String.format(Locale.getDefault(), "%02d:%02d", h, m)} ${doseContextLabel(ctx, i)}"
        }.joinToString(" ")

    /** 同上，但每个时刻按次数上色、其后附上服药时机（浅灰），用于 TextView / 对话框富文本。 */
    fun doseScheduleSpannable(ctx: Context): SpannableString {
        val times = doseTimes(ctx)
        val sb = StringBuilder()
        val spans = ArrayList<Pair<Int, Int>>()
        val ctxSpans = ArrayList<Pair<Int, Int>>()
        times.forEachIndexed { i, (h, m) ->
            if (i > 0) sb.append("   ")
            val start = sb.length
            sb.append(doseMarker(i)).append(' ')
                .append(String.format(Locale.getDefault(), "%02d:%02d", h, m))
            spans.add(start to sb.length)
            val ctxStart = sb.length + 1
            sb.append(' ').append(doseContextLabel(ctx, i))
            ctxSpans.add(ctxStart to sb.length)
        }
        val ss = SpannableString(sb.toString())
        spans.forEachIndexed { i, (s, e) ->
            ss.setSpan(
                ForegroundColorSpan(doseColor(i)), s, e,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        ctxSpans.forEach { (s, e) ->
            ss.setSpan(
                ForegroundColorSpan(0xFFC6D5EF.toInt()), s, e,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return ss
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) { schedule(ctx); syncMedicineNotification(ctx) } else cancel(ctx)
    }

    fun setTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    // ---------------- 吃药记录 ----------------

    /** 记录键统一为「yyyy-MM-dd」。 */
    fun keyFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun keyOf(cal: Calendar): String = keyFormat().format(cal.time)
    fun todayKey(): String = keyFormat().format(Date())

    /** getStringSet 返回的是内部实例，必须复制后再改，否则改动不会落盘。 */
    fun takenKeys(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_TAKEN, emptySet()) ?: emptySet()

    fun isTaken(ctx: Context, key: String = todayKey()): Boolean = takenKeys(ctx).contains(key)
    fun takenCount(ctx: Context): Int = takenKeys(ctx).size

    /** 写入 / 撤销某一天的记录（默认今天）。 */
    fun setTaken(ctx: Context, taken: Boolean, key: String = todayKey()) {
        val set = LinkedHashSet(takenKeys(ctx))
        if (taken) set.add(key) else set.remove(key)
        prefs(ctx).edit().putStringSet(KEY_TAKEN, set).apply()
    }

    // ---------------- 吃药记录（精确到「秒」的每次事件） ----------------

    /** 每次「吃药」事件的精确时刻（毫秒）日志：逗号分隔的字符串，按时间升序保存。 */
    private const val KEY_TAKE_LOG = "take_log"

    /** 药名库：JSON 数组字符串，按添加顺序保存（如 ["阿司匹林","布洛芬"]）。 */
    private const val KEY_MED_NAMES = "med_names"

    // ---------------- 药名库（每次吃什么药从这里选） ----------------

    /** 药名库全部药名（按添加顺序）。 */
    fun getMedNames(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_MED_NAMES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
        } catch (e: Throwable) {
            // 解析失败就当空库，不让脏数据卡死
        }
        return out
    }

    /** 加一个药名：空或已存在（忽略大小写与空白）→ 返回 false（调用方弹「该药已添加」）。 */
    fun addMedName(ctx: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val cur = getMedNames(ctx).toMutableList()
        if (cur.any { it.equals(n, true) }) return false
        cur.add(n)
        saveMedNames(ctx, cur)
        return true
    }

    /** 删除一个药名（忽略大小写）。返回是否真的删了。 */
    fun removeMedName(ctx: Context, name: String): Boolean {
        val cur = getMedNames(ctx).toMutableList()
        val before = cur.size
        cur.removeAll { it.equals(name, true) }
        return if (cur.size != before) {
            saveMedNames(ctx, cur)
            true
        } else false
    }

    /** 清空整个药名库。 */
    fun clearMedNames(ctx: Context) {
        prefs(ctx).edit().remove(KEY_MED_NAMES).apply()
    }

    /**
     * 导入药名：把 json 数组里的药名合并进药名库，跳过空串与已存在的（忽略大小写）。
     * @return 实际新增条数。
     */
    fun importMedNames(ctx: Context, json: String): Int {
        val arr = try {
            JSONArray(json)
        } catch (e: Throwable) {
            return 0
        }
        val cur = getMedNames(ctx).toMutableList()
        var added = 0
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isEmpty()) continue
            if (cur.any { it.equals(s, true) }) continue
            cur.add(s)
            added++
        }
        if (added > 0) saveMedNames(ctx, cur)
        return added
    }

    /** 导出药名库为 JSON 数组字符串。 */
    fun exportMedNamesJson(ctx: Context): String {
        val arr = JSONArray()
        getMedNames(ctx).forEach { arr.put(it) }
        return arr.toString()
    }

    private fun saveMedNames(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_MED_NAMES, arr.toString()).apply()
    }

    // ---------------- 药名库：官方药名库（国家药监局药品编码本位码·全量 + 2024 医保目录）同步 ----------------
    /** 官方药名来源：国家药监局《药品编码本位码信息》（国产 + 进口）全量上市药品（约 2.1 万种产品名）与 2024 年版《国家基本医疗保险药品目录》并集去重，由维护者整理为药名清单后随仓库发布，App 内「同步药监局」拉取合并。 */
    const val NMPA_SYNC_URL = "https://raw.githubusercontent.com/baigao110/daojishi/main/nmpa_med_names.json"
    private const val KEY_MED_SYNC_TIME = "med_sync_time"
    private const val KEY_MED_BUNDLED_TIME = "med_bundled_time"

    /** 上次成功从云端同步药名库的时间戳（毫秒）；0 表示从未云端同步（可能只用了内置官方药名库）。 */
    fun getMedSyncTime(ctx: Context): Long = prefs(ctx).getLong(KEY_MED_SYNC_TIME, 0L)

    /** 上次使用内置官方药名库的时间戳（毫秒）；0 表示从未使用。 */
    fun getMedBundledTime(ctx: Context): Long = prefs(ctx).getLong(KEY_MED_BUNDLED_TIME, 0L)

    private fun setMedSyncTime(ctx: Context, t: Long) =
        prefs(ctx).edit().putLong(KEY_MED_SYNC_TIME, t).apply()

    private fun setMedBundledTime(ctx: Context, t: Long) =
        prefs(ctx).edit().putLong(KEY_MED_BUNDLED_TIME, t).apply()

    /** 读取 App 内置的官方药名库（assets/nmpa_med_names.json，国家药监局药品编码本位码全量 + 2024 医保目录并集，约 2.1 万种），解析失败返回空列表。 */
    fun getBundledMedNames(ctx: Context): List<String> {
        return try {
            val text = InputStreamReader(ctx.assets.open("nmpa_med_names.json"), "UTF-8").use { it.readText() }
            val arr = JSONArray(text)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * 把一批药名合并进本地药名库（忽略大小写与空白去重，幂等）。
     * 用 HashSet 做去重，时间复杂度 O(n) 而非 O(n^2)，即使 2 万条也不会卡死主线程。
     * @return 本次新增条数。
     */
    private fun mergeNames(ctx: Context, incoming: List<String>): Int {
        if (incoming.isEmpty()) return 0
        val cur = getMedNames(ctx).toMutableList()
        val seen = HashSet<String>(cur.size + incoming.size)
        cur.forEach { seen.add(it.lowercase().trim()) }
        var added = 0
        for (raw in incoming) {
            val key = raw.lowercase().trim()
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key); cur.add(raw); added++
        }
        if (added > 0) saveMedNames(ctx, cur)
        return added
    }

    /**
     * 打开药名库时立即保证内置的官方药名库（国家药监局药品编码本位码全量 + 2024 医保目录并集，约 2.1 万种，随 App 打包在 assets/nmpa_med_names.json）
     * 已并入本地药名库。离线、未联网、未手动同步都能用：直接读内置 JSON 与现有库合并
     * （忽略大小写去重），幂等。这样用户一打开药名库就能看到全部官方药名，
     * 不必依赖网络或 7 天静默同步定时器。
     * 必须在后台线程调用（内部会读 assets 大文件 / 写 SharedPreferences，2 万条数据量大）。
     * @return 本次新增条数。
     */
    fun ensureOfficialMedNames(ctx: Context): Int {
        val added = mergeNames(ctx, getBundledMedNames(ctx))
        if (added > 0) setMedBundledTime(ctx, System.currentTimeMillis())
        return added
    }

    /**
     * 从国家药监局上市药品数据库（官方药名镜像）同步药名库：
     * 优先拉取云端最新镜像并合并；若云端不可达（404/网络异常/格式错误），自动回退到 App 内置的官方药名库，保证离线可用。
     * 必须在后台线程调用（内部做网络请求）。
     */
    fun syncMedNamesFromNMPA(ctx: Context): MedSyncResult {
        // 1) 先尝试云端镜像
        val cloud = try {
            val conn = URL(NMPA_SYNC_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "countdown-android")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                conn.disconnect()
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                text
            }
        } catch (e: Throwable) {
            null
        }

        if (cloud != null) {
            val arr = try {
                JSONArray(cloud)
            } catch (e: Throwable) {
                null
            }
            if (arr != null) {
                val incoming = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) incoming.add(s)
                }
                val added = mergeNames(ctx, incoming)
                setMedSyncTime(ctx, System.currentTimeMillis())
                return MedSyncResult(true, added, getMedNames(ctx).size, "", "github")
            }
        }

        // 2) 云端不可达 → 回退到内置官方药名库（保证离线也能用）
        val bundled = getBundledMedNames(ctx)
        if (bundled.isNotEmpty()) {
            val added = mergeNames(ctx, bundled)
            setMedBundledTime(ctx, System.currentTimeMillis())
            return MedSyncResult(true, added, getMedNames(ctx).size, "", "bundled")
        }

        // 3) 云端与内置均不可用（极少见）
        return MedSyncResult(false, 0, getMedNames(ctx).size, "无法读取官方药名库", "error")
    }

    /**
     * 同步结果。
     * @param ok     是否成功（含成功回退到内置官方药名库）
     * @param added  本次新增药名数
     * @param total  同步后药名库总数
     * @param msg    失败时的错误信息
     * @param source 数据来源："github"（云端最新）/ "bundled"（内置官方药名库回退）/ "error"（彻底失败）
     */
    data class MedSyncResult(
        val ok: Boolean, val added: Int, val total: Int,
        val msg: String, val source: String = "github"
    )

    /** 一条吃药记录：精确时刻 + 药名（空串表示旧记录或未指定药名）。 */
    data class TakeEvent(val ts: Long, val medName: String)

    /**
     * 记录一次吃药事件：写入精确时刻与药名（默认现在），并确保当天标记为已吃。
     * @param whenMillis 吃药时刻（默认当前），补记历史时传入
     * @param dayKey 对应的日期键（默认按 whenMillis 当日），给过去某天补记时传入
     * @param medName 这次吃的药名（从药名库选；空串 = 旧记录 / 未指定）
     * @return 实际写入日志的时刻毫秒值
     */
    fun recordTakeEvent(ctx: Context, whenMillis: Long = System.currentTimeMillis(),
                         dayKey: String = keyFormat().format(Date(whenMillis)),
                         medName: String = ""): Long {
        val list = takeLog(ctx).toMutableList()
        list.add(TakeEvent(whenMillis, medName))
        list.sortBy { it.ts }
        prefs(ctx).edit()
            .putString(KEY_TAKE_LOG, list.joinToString(",") { "${it.ts}|${it.medName}" })
            .apply()
        setTaken(ctx, true, dayKey)
        return whenMillis
    }

    /** 全部吃药事件（升序）。老格式（纯毫秒）自动补成 medName=""。 */
    fun takeLog(ctx: Context): List<TakeEvent> {
        val raw = prefs(ctx).getString(KEY_TAKE_LOG, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<TakeEvent>()
        for (seg in raw.split(",")) {
            if (seg.isBlank()) continue
            val idx = seg.indexOf('|')
            if (idx < 0) {
                seg.toLongOrNull()?.let { out.add(TakeEvent(it, "")) }
            } else {
                val ts = seg.substring(0, idx).toLongOrNull() ?: continue
                out.add(TakeEvent(ts, seg.substring(idx + 1)))
            }
        }
        out.sortBy { it.ts }
        return out
    }

    fun takeLogCount(ctx: Context): Int = takeLog(ctx).size

    /** 按「yyyy-MM-dd」分组（同 takeLog 升序），返回 LinkedHashMap 保序（展示时再倒序）。 */
    fun takeLogGroupedByDay(ctx: Context): LinkedHashMap<String, List<TakeEvent>> {
        val map = LinkedHashMap<String, MutableList<TakeEvent>>()
        for (ev in takeLog(ctx)) {
            val key = keyFormat().format(Date(ev.ts))
            map.getOrPut(key) { mutableListOf() }.add(ev)
        }
        val out = LinkedHashMap<String, List<TakeEvent>>()
        // 日期降序展示（最近的在最上面）
        map.entries.sortedByDescending { it.key }.forEach { (k, v) -> out[k] = v }
        return out
    }

    /** 删除某一条吃药记录（精确时刻）。若该日再无记录，顺手把当天「已吃」取消。 */
    fun removeTakeEvent(ctx: Context, whenMillis: Long) {
        val list = takeLog(ctx).toMutableList()
        val idx = list.indexOfFirst { it.ts == whenMillis }
        if (idx >= 0) list.removeAt(idx)
        prefs(ctx).edit()
            .putString(KEY_TAKE_LOG, list.joinToString(",") { "${it.ts}|${it.medName}" })
            .apply()
        val key = keyFormat().format(Date(whenMillis))
        if (list.none { keyFormat().format(Date(it.ts)) == key }) {
            setTaken(ctx, false, key)
        }
    }

    /** 清空全部吃药记录（当天「已吃」也会一并去掉）。 */
    fun clearTakeLog(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TAKE_LOG).apply()
    }

    /** 单条记录格式：yyyy-MM-dd HH:mm:ss（含秒）。 */
    fun formatTakeEvent(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    /** 日期键对应的星期汉字（用于日志分组头）。 */
    fun dayWeekday(key: String): String {
        return try {
            val cal = Calendar.getInstance()
            cal.time = keyFormat().parse(key) ?: return ""
            arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        } catch (e: Throwable) {
            ""
        }
    }

    /** 某一天吃了几次（事件日志的条数）。 */
    fun takeCountForDay(ctx: Context, dayKey: String): Int =
        takeLog(ctx).count { keyFormat().format(Date(it.ts)) == dayKey }

    /** 今天吃了几次。 */
    fun takeCountToday(ctx: Context): Int = takeCountForDay(ctx, todayKey())

    /** 删除某一天的全部吃药记录（撤销当天），并取消当天「已吃」标记。 */
    fun removeTakeDay(ctx: Context, dayKey: String) {
        val list = takeLog(ctx).filter { keyFormat().format(Date(it.ts)) != dayKey }
        prefs(ctx).edit()
            .putString(KEY_TAKE_LOG, list.joinToString(",") { "${it.ts}|${it.medName}" })
            .apply()
        setTaken(ctx, false, dayKey)
    }

    private fun notifiedDoses(ctx: Context): MutableSet<String> =
        LinkedHashSet(prefs(ctx).getStringSet(KEY_NOTIFIED_DOSES, emptySet()) ?: emptySet())

    private fun isDoseNotified(ctx: Context, date: String, i: Int): Boolean =
        notifiedDoses(ctx).contains("$date#$i")

    private fun markDoseNotified(ctx: Context, date: String, i: Int) {
        val set = notifiedDoses(ctx)
        set.add("$date#$i")
        prefs(ctx).edit()
            .putStringSet(KEY_NOTIFIED_DOSES, set)
            .putLong(KEY_LAST_NOTIFY_TIME, System.currentTimeMillis())
            .apply()
    }

    // ---------------- 闹钟：三路冗余 ----------------

    /**
     * 下一次响铃时刻：
     * - 用户手动改过「下次提醒」且还没到 → 用那个时刻；
     * - 否则按每天固定时刻推算（过了今天的设定时间就顺延到明天）。
     *
     * 注意：**这里必须保持纯推算、不能有副作用**。以前「手动时刻已过就顺手清掉」写在
     * 这儿，而后台巡检每趟都会先调它重挂闹钟 —— 手动设定的时间被提前作废，
     * 后面的补发判定就再也认不出「用户手动改的那一次到期了」，于是整条兜底链都失效。
     * 作废统一交给 maybeNotify()：提醒真的发出去（或彻底没戏）之后才清。
     */
    fun nextTriggerMillis(ctx: Context): Long {
        val custom = nextCustomMillis(ctx)
        val now = System.currentTimeMillis()
        if (custom > now) return custom
        var best = Long.MAX_VALUE
        for (t in todayDoseMillis(ctx)) {
            val cand = if (t > now) t else t + 24 * 60 * 60 * 1000L
            if (cand < best) best = cand
        }
        return best
    }

    // ---------------- 手动指定「下一次提醒」 ----------------

    /** 手动设定的下一次提醒时刻（0 = 未设定）。 */
    fun nextCustomMillis(ctx: Context): Long = prefs(ctx).getLong(KEY_NEXT_CUSTOM, 0L)

    /** 当前「下次提醒」是不是用户手动改出来的。 */
    fun nextIsCustom(ctx: Context): Boolean = nextCustomMillis(ctx) > System.currentTimeMillis()

    /** 手动指定下一次提醒时刻，立刻按新时间重新挂载闹钟。 */
    fun setNextCustom(ctx: Context, millis: Long) {
        prefs(ctx).edit().putLong(KEY_NEXT_CUSTOM, millis).apply()
        schedule(ctx)
        syncMedicineNotification(ctx)
    }

    /** 取消手动设定，恢复「每天固定时刻」。 */
    fun clearNextCustom(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_NEXT_CUSTOM, 0L).apply()
    }

    /** 今天设定时刻的毫秒值（不管过没过）。 */
    private fun todayTriggerMillis(ctx: Context): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(ctx))
            set(Calendar.MINUTE, minute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 排下一次提醒（对外入口）。 */
    fun schedule(ctx: Context) {
        if (!isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        ensureAlarms(ctx)
    }

    /**
     * 真正挂载：系统闹钟（主）+ 每天重复闹钟（备）+ JobScheduler 巡检（兜底）。
     * 重复调用是安全的 —— 只是用同一个 PendingIntent 覆盖旧闹钟。
     */
    private fun ensureAlarms(ctx: Context) {
        val trigger = nextTriggerMillis(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPi(ctx)

        // ① 系统闹钟：优先级最高，Doze 也能按时唤起，且不需要精确闹钟权限
        var ok = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showPi(ctx)), pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            ok = true
        } catch (e: Throwable) {
            try {
                // 退而求其次：精确闹钟（Android 12+ 需要权限，没权限会抛异常）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
                ok = true
            } catch (e2: Throwable) {
                // 两条精确路都走不通，下面那条重复闹钟一定还在
            }
        }

        // ② 精确闹钟：与 ① 同一时刻，走 allowWhileIdle，Doze 下也能在维护窗口里被唤起
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger, exactPi(ctx)
                )
            }
        } catch (e: Throwable) {
            // 没有精确闹钟权限会被拦下，还有另外三路
        }

        // ③ 每天重复的非精确闹钟：不依赖精确闹钟权限，也不容易被 ROM 当成「精确闹钟」拦下。
        //    它与 ①② 同一天都会触发，但 maybeNotify() 保证一天只发一次通知。
        try {
            if (ok) am.cancel(backupPi(ctx))
            am.setRepeating(
                AlarmManager.RTC_WAKEUP, trigger, AlarmManager.INTERVAL_DAY, backupPi(ctx)
            )
        } catch (e: Throwable) {
            // 忽略
        }

        // ④ 补响：主路万一被系统推迟或丢掉，到点后 2 分钟、30 分钟各再试一次。
        //    走的是同一个 action，maybeNotify() 保证一天只发一条通知，不会重复打扰。
        try {
            am.cancel(retry1Pi(ctx))
            am.set(AlarmManager.RTC_WAKEUP, trigger + 2 * 60 * 1000L, retry1Pi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }
        try {
            am.cancel(retry2Pi(ctx))
            am.set(AlarmManager.RTC_WAKEUP, trigger + 30 * 60 * 1000L, retry2Pi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }

        // ⑤ JobScheduler 巡检：闹钟被清掉时补挂、错过就补发
        MedicineCheckJobService.schedule(ctx)

        try {
            prefs(ctx).edit().putLong(KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /** 闹钟响了：先把下一次排上（幂等），再发通知；手动改的那次由 maybeNotify 兑现后再作废。 */
    fun onAlarm(ctx: Context) {
        if (!isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        schedule(ctx)
        maybeNotify(ctx)
        syncMedicineNotification(ctx)
    }

    /** JobScheduler 巡检：补挂闹钟 + 错过就补发。 */
    fun onPeriodicCheck(ctx: Context) {
        if (!isEnabled(ctx)) return
        ensureAlarms(ctx)
        maybeNotify(ctx)
        syncMedicineNotification(ctx)
    }

    /**
     * 打开 App 时补一次：今天该提醒的时刻已经过了、却又没提醒过（关机、闹钟被清、
     * 系统推迟等），立刻补一条通知，免得整天都不响。
     */
    fun catchUp(ctx: Context) {
        if (!isEnabled(ctx)) return
        schedule(ctx)
        maybeNotify(ctx)
        syncMedicineNotification(ctx)
    }

    /**
     * 到点才发通知，每天按设定次数分别提醒（每剂一天最多一次）。
     *
     * 唯一例外：**用户手动指定的「下一次提醒」到期时无条件提醒一次** ——
     * 「今天已提醒过」「今天已记过「已吃药」」都不拦。改时间就是为了再收一次，
     * 若被去重逻辑吞掉，表现出来就是「改了下次提醒时间，退出软件后反倒收不到了」。
     *
     * @param force 无视一切直接发（「测试提醒」用，不写去重标记）。
     */
    fun maybeNotify(ctx: Context, force: Boolean = false) {
        if (!force && !isEnabled(ctx)) return
        val now = System.currentTimeMillis()
        val today = todayKey()
        val custom = nextCustomMillis(ctx)
        val customDue = custom > 0L && now >= custom   // 手动指定的那一次到期了
        var late = false
        var doseIndex = -1
        if (!force) {
            if (customDue) {
                late = now - custom > 5 * 60 * 1000L
            } else {
                if (isTaken(ctx, today)) return          // 今天已经点了「已吃药」，不再打扰
                doseIndex = dueDoseIndex(ctx, now)
                if (doseIndex < 0) return                // 现在没有该响的剂次
                late = now - todayDoseMillis(ctx)[doseIndex] > 5 * 60 * 1000L
            }
        } else {
            // 测试提醒：展示最近一次还没过的剂次，找不到就用第 1 剂
            val doses = todayDoseMillis(ctx)
            doseIndex = doses.indexOfFirst { it > now }
            if (doseIndex < 0) doseIndex = 0
        }
        if (!UpdateNotifier.hasPermission(ctx)) {
            if (customDue) {
                clearNextCustom(ctx)
                schedule(ctx)
            }
            return
        }
        if (notifyNow(ctx, doseIndex, late)) {
            if (!force) {
                if (customDue || (custom > 0L && custom <= now)) {
                    clearNextCustom(ctx)                 // 手动指定的那次已兑现，回到每天固定时刻
                } else if (doseIndex >= 0) {
                    markDoseNotified(ctx, today, doseIndex)
                }
                schedule(ctx)
            } else {
                prefs(ctx).edit().putLong(KEY_LAST_NOTIFY_TIME, now).apply()
            }
        }
    }

    /**
     * 找出「现在应当响」的那一剂：今天该时刻已过、今天尚未提醒过、且在迟到窗口内。
     * 太早（未来）不响；漏掉太久（超过窗口）先静默标记为已提醒，避免补发骚扰。
     */
    private fun dueDoseIndex(ctx: Context, now: Long): Int {
        val date = todayKey()
        val doses = todayDoseMillis(ctx)
        for (i in doses.indices) {
            if (isDoseNotified(ctx, date, i)) continue
            val t = doses[i]
            if (now >= t) {
                if (now - t <= LATE_WINDOW) return i
                markDoseNotified(ctx, date, i)   // 漏掉的太早一剂：静默记掉，不再补
            }
        }
        return -1
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPi(ctx))
            am.cancel(backupPi(ctx))
            am.cancel(exactPi(ctx))
            am.cancel(retry1Pi(ctx))
            am.cancel(retry2Pi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }
        MedicineCheckJobService.cancel(ctx)
        cancelNotification(ctx)
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun alarmPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun backupPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_BACKUP,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun exactPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_EXACT,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun retry1Pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_RETRY1,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    private fun retry2Pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM_RETRY2,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_ALARM),
        piFlags()
    )

    /** 状态栏闹钟图标被点时的去向（回主界面）。 */
    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, REQ_ALARM_SHOW,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        piFlags()
    )

    private fun takenPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_TAKEN,
        Intent(ctx, MedicineAlarmReceiver::class.java).setAction(ACTION_TAKEN),
        piFlags()
    )

    // ---------------- 通知 ----------------

    /** 到点发通知：点本体进吃药日历，点「已吃药」直接记录今天。 */
    fun notifyNow(ctx: Context, doseIndex: Int = -1, late: Boolean = false): Boolean {
        if (!UpdateNotifier.hasPermission(ctx)) return false
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(nm)
            val openPi = PendingIntent.getActivity(
                ctx, REQ_OPEN,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags()
            )
            val doses = timesPerDay(ctx)
            // 现在 x 点了，该吃 x 药了！（x = 当前服药时刻的小时 / 药名库里的药）
            val cal = Calendar.getInstance()
            val hh = if (doseIndex >= 0) {
                val d = todayDoseMillis(ctx)
                if (doseIndex in d.indices)
                    cal.apply { timeInMillis = d[doseIndex] }.get(Calendar.HOUR_OF_DAY)
                else cal.get(Calendar.HOUR_OF_DAY)
            } else cal.get(Calendar.HOUR_OF_DAY)
            val names = getMedNames(ctx)
            val medLabel = when {
                names.isEmpty() -> "药"
                names.size == 1 -> names[0]
                else -> "多种药"
            }
            val title = "现在${hh}点了，该吃${medLabel}了！" + if (late) "（补发提醒）" else ""
            val text = if (late) {
                "刚才没按时弹出来，现在补上啦；点「已吃药」记一下今天"
            } else {
                "每天 $doses 次小提醒（${doseScheduleText(ctx)}）；点「已吃药」记一下今天"
            }
            // 全屏意图：息屏 / 锁屏时把吃药页直接弹到眼前（闹钟类通知才有的待遇）。
            // 普通通知在国产 ROM 上很容易被收进「无声通知」，这个能真正把人叫到。
            val fullPi = PendingIntent.getActivity(
                ctx, REQ_FULL,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ),
                piFlags()
            )
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(R.drawable.ic_stat, "已吃药", takenPi(ctx))
                .setCategory(Notification.CATEGORY_ALARM)
                // 与系统闹钟一致：常驻通知，直到点「已吃药」才消失；退出 / 关闭软件也一直在
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .build()
            nm.notify(NOTIF_ID, n)
            setAlarmShowing(ctx, true)
            true
        } catch (e: Throwable) {
            android.util.Log.w("MedicineReminder", "notifyNow: ${e.message}")
            false
        }
    }

    fun cancelNotification(ctx: Context) {
        setAlarmShowing(ctx, false)
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /**
     * 常驻状态通知：吃药提醒开启时显示「每日吃药提醒 · 已开启」+ 下次提醒时间；
     * 关闭时由 [syncMedicineNotification] 取消。不响铃、不震动、不弹全屏，仅作状态常驻。
     */
    fun postStatusNotification(ctx: Context) {
        if (!UpdateNotifier.hasPermission(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createStatusChannel(nm)
            val openPi = PendingIntent.getActivity(
                ctx, REQ_OPEN,
                Intent(ctx, MedicineCalendarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags()
            )
            val taken = isTaken(ctx)
            val title = "每日吃药提醒 · 已开启"
            val text = if (taken) {
                "今天吃过啦 ✓ · 下次提醒 ${nextTriggerText(ctx)}"
            } else {
                "下次提醒 ${nextTriggerText(ctx)}（点开能看吃药日历）"
            }
            val n = Notification.Builder(ctx, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
            setAlarmShowing(ctx, false)
            nm.notify(NOTIF_ID, n)
        } catch (e: Throwable) {
            android.util.Log.w("MedicineReminder", "postStatusNotification: ${e.message}")
        }
    }

    /** 状态渠道：不响铃、不震动，仅常驻显示状态（避免每次刷新都响铃）。 */
    private fun createStatusChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            if (nm.getNotificationChannel(STATUS_CHANNEL_ID) != null) return
        } catch (e: Throwable) {
            return
        }
        val ch = NotificationChannel(
            STATUS_CHANNEL_ID, "吃药提醒状态", NotificationManager.IMPORTANCE_DEFAULT
        )
        ch.description = "吃药提醒开启时的常驻状态提示（不响铃）"
        ch.enableVibration(false)
        try {
            ch.setSound(null, null)
        } catch (e: Throwable) {
            // 忽略
        }
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try {
            nm.createNotificationChannel(ch)
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /**
     * 根据开关与提醒状态同步「每日吃药提醒」通知：
     * - 关闭 → 取消通知；
     * - 开启 → 不再常驻显示「已开启」状态通知（v1.0.0.37 起取消），仅清掉旧版残留；
     *   真正到点时由 notifyNow() 弹出「该吃药了」闹钟通知，点「已吃药」后取消。
     */
    fun syncMedicineNotification(ctx: Context) {
        // 昨天的闹钟通知已过期：清掉标记，让下方按今天的状态重新刷新
        val ad = alarmShowingDate(ctx)
        if (ad.isNotEmpty() && ad != todayKey()) setAlarmShowing(ctx, false)
        if (!isEnabled(ctx)) {
            cancelNotification(ctx)   // 关闭 → 取消每日吃药提醒通知
            return
        }
        if (alarmShowingDate(ctx).isNotEmpty()) return  // 当前闹钟通知正在展示，先不动它
        cancelNotification(ctx)  // 已取消常驻「已开启」状态通知：清掉旧版残留，不再常驻显示
    }

    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        for (oldId in OLD_CHANNEL_IDS) {
            try {
                if (nm.getNotificationChannel(oldId) != null) {
                    nm.deleteNotificationChannel(oldId)
                }
            } catch (e: Throwable) {
                // 部分 ROM 不允许删除渠道，忽略即可
            }
        }
        val exist = try {
            nm.getNotificationChannel(CHANNEL_ID)
        } catch (e: Throwable) {
            null
        }
        if (exist != null) {
            if (exist.importance == NotificationManager.IMPORTANCE_NONE) {
                try {
                    nm.deleteNotificationChannel(CHANNEL_ID)
                } catch (e: Throwable) {
                    return
                }
            } else {
                return
            }
        }
        val ch = NotificationChannel(
            CHANNEL_ID, "吃药提醒", NotificationManager.IMPORTANCE_MAX
        )
        ch.description = "每天到点提醒吃药，可点通知上的「已吃药」记录"
        ch.enableVibration(true)
        ch.setShowBadge(true)
        ch.setBypassDnd(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        try {
            ch.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        } catch (e: Throwable) {
            // 拿不到默认铃声就交给系统默认行为
        }
        nm.createNotificationChannel(ch)
    }

    // ---------------- 自检（「关于」页用） ----------------

    /** 下次提醒时间，形如「09-19 09:00」。 */
    fun nextTriggerText(ctx: Context): String {
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return f.format(Date(nextTriggerMillis(ctx)))
    }

    /** Android 12+ 起精确闹钟是「特殊权限」，被关掉时精确路会失效（我们还有后两路）。 */
    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } catch (e: Throwable) {
            false
        }
    }

    /** 跳到系统的「闹钟与提醒」授权页（Android 12+ 才有）。 */
    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Throwable) {
                // 忽略
            }
        }
    }

    /** 上次成功挂载闹钟的时间描述（自检用）。 */
    fun lastScheduleText(ctx: Context): String {
        val t = prefs(ctx).getLong(KEY_LAST_SCHEDULE, 0L)
        if (t <= 0L) return "未挂载"
        val min = Math.max(0L, (System.currentTimeMillis() - t) / 60000L)
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "${min} 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / 1440} 天前"
        }
    }

    /** 上次真正发出提醒通知的时刻描述（自检用：「没发过」说明确实没发出来）。 */
    fun lastNotifyText(ctx: Context): String {
        val t = prefs(ctx).getLong(KEY_LAST_NOTIFY_TIME, 0L)
        if (t <= 0L) return "还没发过呢"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
    }

    /** 「关于」页状态行：一眼看出提醒到底挂上没有、卡在哪一环。 */
    fun statusText(ctx: Context): String {
        val on = isEnabled(ctx)
        if (!on) return "提醒关啦 · 已记录 ${takenCount(ctx)} 天 · 共吃药 ${takeLogCount(ctx)} 次"
        val today = if (isTaken(ctx)) "吃过啦 ✓" else "还没吃"
        val n = timesPerDay(ctx)
        val sched = if (n > 1) "\n每天 $n 次哦：${doseScheduleText(ctx)}" else ""
        val tail = if (nextIsCustom(ctx)) "（手动改过啦）" else ""
        return "今天：$today · 已记录 ${takenCount(ctx)} 天 · 共吃药 ${takeLogCount(ctx)} 次\n下次提醒 ${nextTriggerText(ctx)}$tail$sched"
    }
}
