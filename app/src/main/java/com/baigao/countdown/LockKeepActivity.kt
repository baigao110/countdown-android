package com.baigao.countdown

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 「锁屏通知常亮」用的透明常亮页。
 *
 * 为什么非要一个 Activity：官方文档写得很死 —— `FLAG_KEEP_SCREEN_ON` **只能在 Activity 里设，
 * 服务 / 悬浮窗这类组件设了不算数**，而且「应用回到后台后系统会照常熄屏」。
 * 之前两版（悬浮小窗、屏幕级唤醒锁）都是应用在后台，所以系统该熄还是熄。
 * 系统闹钟能在锁屏上一直亮着，靠的也正是「一个挂在锁屏之上的全屏 Activity + FLAG_KEEP_SCREEN_ON」。
 *
 * 所以这一页：
 * - 全屏、**全透明**：锁屏界面、锁屏通知栏（倒计时就在那儿）照常看得见，一点不挡；
 * - **不抢焦点、不拦触摸**（FLAG_NOT_FOCUSABLE / FLAG_NOT_TOUCHABLE）：锁屏该划还能划、
 *   通知该点还能点，摸到我们身上时立刻自己退开（见 [dispatchTouchEvent]），绝不会把人锁在门外；
 * - 只做一件事：把屏幕按在亮着的状态；每秒检查一次条件，开关关了 / 通知收掉了 /
 *   用户解锁了，自己 `finish()` 走人。
 */
class LockKeepActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var closing = false

    /** 每秒核对一次：条件没了就自己收掉，不留一个透明页在锁屏上占着。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (closing) return
            if (!LockKeepOn.shouldHold(this@LockKeepActivity) ||
                !LockKeepOn.isKeyguardShowing(this@LockKeepActivity)
            ) {
                finishKeep()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 允许在锁屏之上显示（Android 8.1+ 用新 API，8.0 退回老 Flag）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 不抢焦点、不拦触摸：下面的锁屏照常能用（这是我们敢挂在锁屏上的前提）
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        // 全透明、什么都不画
        setContentView(View(this))
        LockKeepOn.onActivityShown()
        handler.postDelayed(ticker, 1000)
    }

    /**
     * 万一系统忽略了 FLAG_NOT_TOUCHABLE（用户想划锁屏却点到了我们），立刻退开：
     * 收掉常亮页 + 标记「先别接管」，让用户正常解锁；解锁后再由服务重新接管。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        LockKeepOn.onUserTouched()
        finishKeep()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        LockKeepOn.onActivityGone()
        super.onDestroy()
    }

    private fun finishKeep() {
        if (closing) return
        closing = true
        handler.removeCallbacks(ticker)
        LockKeepOn.onActivityGone()
        try {
            finish()
        } catch (_: Throwable) {
            // 收不掉就算了，别崩
        }
    }
}
