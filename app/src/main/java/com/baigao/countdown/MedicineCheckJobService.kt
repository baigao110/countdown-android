package com.baigao.countdown

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 吃药提醒的后台巡检（JobScheduler 版，纯框架实现）。
 *
 * 解决「退出 / 关闭 App 后就不提醒」：
 * 国产 ROM 从最近任务划掉卡片时会强制停止应用，**系统会把这个应用挂的闹钟全部清掉**，
 * 停止状态下的包连开机广播都收不到，于是再也不会重排，提醒就此失效。
 * JobScheduler 的周期任务由系统统一调度，`setPersisted(true)` 让它重启后自动恢复；
 * 每一趟都会：① 把闹钟补挂回去；② 若今天该提醒的时刻已过却还没提醒过，直接补发通知。
 *
 * 与 AlarmManager 双路并存，哪条路先跑通都会提醒，且同一天只会打扰一次。
 */
class MedicineCheckJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        try {
            MedicineReminder.onPeriodicCheck(this)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "periodic check: ${e.message}")
        }
        // 活儿是同步干完的，直接收工；不需要系统再保持 wakelock
        jobFinished(params, false)
        return false
    }

    /** 被系统提前打断时请求重排。 */
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "MedicineCheckJob"
        const val JOB_ID = 20441

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                // 已经排过就跳过：重复 schedule 会重置周期计时
                if (js.allPendingJobs.any { it.id == JOB_ID }) return
                val info = JobInfo.Builder(
                    JOB_ID, ComponentName(context, MedicineCheckJobService::class.java)
                )
                    .setPersisted(true)              // 重启后由系统自动恢复，不依赖开机广播
                    .setRequiresCharging(false)
                    // 周期任务最小间隔是 15 分钟（系统硬限制，写更小也会被拉回）
                    .setPeriodic(15 * 60 * 1000L)
                    .build()
                js.schedule(info)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "schedule: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            try {
                (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler)
                    .cancel(JOB_ID)
            } catch (e: Throwable) {
                // 忽略
            }
        }
    }
}
