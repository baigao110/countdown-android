package com.baigao.countdown

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 后台定期检查更新（JobScheduler 版，纯框架实现，零第三方依赖）。
 *
 * 为什么光有 AlarmManager（v1.0.0.12）还是收不到通知：
 * `setAndAllowWhileIdle` 只能在 Doze 的维护窗口里给应用一小段 CPU 时间，而 **Doze 期间网络是被切断的**。
 * 于是唤醒了也拉不到 GitHub 的版本信息（请求直接超时被 catch 掉），永远查不到新版本，自然没有通知。
 * 国产 ROM 还会进一步限制后台闹钟 / 自启动，让这条路更不可靠。
 *
 * JobScheduler 由系统统一调度：任务声明「需要网络」后，系统会在有网络的时候才执行它，
 * 并且 `setPersisted(true)` 让它在重启后自动恢复。这里与 AlarmManager 双路并存，
 * 哪条路先跑通都能发出通知。
 */
class UpdateCheckJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // 返回 true：活儿还在后台线程里跑，跑完自己调 jobFinished
        Thread {
            var ok = true
            try {
                // 顺带给吃药提醒做一次巡检：多一路兜底，闹钟被清掉时补挂、错过就补发
                MedicineReminder.onPeriodicCheck(this)
                val info = UpdateManager.fetch()
                if (info == null) {
                    // 两次请求（Release / update.json）都没拿到，按失败处理让系统退避重排
                    ok = false
                    UpdateCheckState.record(this, false, "")
                } else {
                    UpdateCheckState.record(this, true, info.name)
                    if (UpdateManager.hasNewVersion(info)) {
                        UpdateNotifier.notifyUpdate(this, info)
                    }
                }
            } catch (e: Throwable) {
                ok = false
                UpdateCheckState.record(this, false, "")
                android.util.Log.w(TAG, "job check: ${e.message}")
            } finally {
                // 失败时回 true：让系统按退避策略再排一次，而不是干等下一个周期
                jobFinished(params, !ok)
            }
        }.start()
        return true
    }

    /** 被系统提前打断时请求重排（比如网络忽然断了）。 */
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "UpdateCheckJob"
        const val JOB_ID = 20320

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                // 已经排过就跳过：重复 schedule 会重置周期计时，反而把检查时间往后推
                val exist = js.allPendingJobs.firstOrNull { it.id == JOB_ID }
                if (exist != null) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
                    // 关键：声明需要网络，系统就不会在断网 / Doze 期间白跑一趟
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)          // 重启后由系统自动恢复，不依赖开机广播
                    .setRequiresCharging(false)
                    // 周期任务最小间隔是 15 分钟（系统硬限制，写更小也会被拉回 15 分钟）
                    .setPeriodic(15 * 60 * 1000L)
                    .build()
                js.schedule(info)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "schedule: ${e.message}")
            }
        }
    }
}

/**
 * 后台更新检查的「体检记录」：上次跑的时刻与结果。
 * 「关于」页把这行字显示出来，收不到通知时一眼能看出是「根本没跑」还是「跑了但没新版本」。
 */
object UpdateCheckState {
    private const val PREF = "update_check_state"
    private const val KEY_TIME = "time"     // 上次检查时刻（epoch 毫秒）
    private const val KEY_OK = "ok"         // 上次是否成功拉到远端版本
    private const val KEY_VERSION = "ver"   // 上次拉到的远端版本号

    fun record(context: Context, ok: Boolean, version: String) {
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putBoolean(KEY_OK, ok)
                .putString(KEY_VERSION, version)
                .apply()
        } catch (e: Throwable) {
            // 忽略：状态记录失败不影响检查本身
        }
    }

    private fun sp(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun lastTime(context: Context): Long = try {
        sp(context).getLong(KEY_TIME, 0L)
    } catch (e: Throwable) {
        0L
    }

    fun lastOk(context: Context): Boolean = try {
        sp(context).getBoolean(KEY_OK, false)
    } catch (e: Throwable) {
        false
    }

    fun lastVersion(context: Context): String = try {
        sp(context).getString(KEY_VERSION, "") ?: ""
    } catch (e: Throwable) {
        ""
    }

    /** 给「关于」页显示的一行说明。 */
    fun summary(context: Context): String {
        val t = lastTime(context)
        if (t <= 0L) return "后台检查：还没跑过（打开一次本应用后开始生效）"
        val min = Math.max(0L, (System.currentTimeMillis() - t) / 60000L)
        val ago = when {
            min < 1 -> "刚刚"
            min < 60 -> "${min} 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / 1440} 天前"
        }
        val result = if (lastOk(context)) {
            val v = lastVersion(context)
            if (v.isBlank()) "正常" else "正常（远端 v$v，暂无新版本）"
        } else {
            "失败（无网络或被系统限制后台）"
        }
        return "后台检查：$ago $result"
    }
}
