package com.baigao.countdown

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.sin

/**
 * 倒计时数字跳秒时的入场动画。
 *
 * 只使用框架自带的属性动画（scale / alpha / translation / rotation / 文字颜色 / 字距），
 * 不引入任何第三方依赖，也不改动视图层级 —— 这样在列表复用、悬浮窗、拖动排序等
 * 各种场景下都不会破坏布局。
 *
 * 动画时长统一控制在 500ms 以内：列表每秒刷新一次，留有足够余量避免叠加。
 * 每次播放前先取消上一次，结束时必定把属性复位，防止列表复用后属性残留。
 */
object AnimStyle {

    const val NONE = 0
    const val SCALE = 1      // 缩放
    const val EVAPORATE = 2  // 蒸发
    const val FALL = 3       // 坠落
    const val PIXEL = 4      // 像素化
    const val SHATTER = 5    // 碎片化
    const val BURN = 6       // 燃烧
    const val QUAKE = 7      // 震撼

    val NAMES = arrayOf(
        "无动画",   // 0
        "缩放",     // 1
        "蒸发",     // 2
        "坠落",     // 3
        "像素化",   // 4
        "碎片化",   // 5
        "燃烧",     // 6
        "震撼"      // 7
    )

    fun name(style: Int): String = if (style in NAMES.indices) NAMES[style] else NAMES[0]

    /** 记录每个视图上正在跑的 ValueAnimator，便于下次播放前取消，避免相互打架。 */
    private val running = WeakHashMap<View, Animator>()

    /**
     * 播放一次刷新动画。
     * @param v 倒计时数字 TextView
     * @param style 动画样式（见本类常量）
     * @param baseColor 动画结束后要恢复的文字颜色
     */
    fun play(v: View, style: Int, baseColor: Int) {
        if (style == NONE) return
        stop(v)
        reset(v, baseColor)
        try {
            when (style) {
                SCALE -> scale(v)
                EVAPORATE -> evaporate(v)
                FALL -> fall(v)
                PIXEL -> pixel(v)
                SHATTER -> shatter(v)
                BURN -> burn(v, baseColor)
                QUAKE -> quake(v)
                else -> scale(v)
            }
        } catch (e: Throwable) {
            // 动画属于锦上添花，任何异常都不该影响倒计时本身
            reset(v, baseColor)
        }
    }

    /** 停止正在播放的动画（视图被复用 / 列表重建时调用）。 */
    fun stop(v: View) {
        running.remove(v)?.cancel()
        v.animate().cancel()
    }

    /** 把所有可能被动画改动的属性复位。 */
    fun reset(v: View, baseColor: Int) {
        v.alpha = 1f
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.rotation = 0f
        if (v is TextView) {
            v.setTextColor(baseColor)
            v.letterSpacing = 0f
        }
    }

    private fun dp(v: View, value: Float): Float = value * v.resources.displayMetrics.density

    private fun bind(v: View, anim: ValueAnimator) {
        running[v] = anim
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (running[v] === a) running.remove(v)
            }
        })
        anim.start()
    }

    // ---------------- 缩放：新数字由大缩小到位，带轻微回弹 ----------------
    private fun scale(v: View) {
        v.scaleX = 1.28f
        v.scaleY = 1.28f
        v.alpha = 0.35f
        v.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    // ---------------- 蒸发：旧数字上浮消散，新数字自下方凝结 ----------------
    private fun evaporate(v: View) {
        val rise = dp(v, 16f)
        v.alpha = 1f
        v.animate()
            .alpha(0f)
            .translationY(-rise)
            .scaleX(1.16f)
            .scaleY(1.16f)
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                v.translationY = dp(v, 14f)
                v.scaleX = 0.82f
                v.scaleY = 0.82f
                v.animate()
                    .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    // ---------------- 坠落：从上方掉落并触底回弹 ----------------
    private fun fall(v: View) {
        v.translationY = -(v.height.toFloat() + dp(v, 10f))
        v.alpha = 0.25f
        v.rotation = -6f
        v.animate()
            .translationY(0f).alpha(1f).rotation(0f)
            .setDuration(520)
            .setInterpolator(BounceInterpolator())
            .start()
    }

    // ---------------- 像素化：分 6 档阶梯聚合，配合字距由散到紧 ----------------
    private fun pixel(v: View) {
        val tv = v as? TextView
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 420 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            // 关键：把连续进度量化成 6 档，视觉上像“色块一格格拼出来”
            val step = Math.floor((t * 6.0)).toFloat() / 6f
            v.alpha = 0.2f + 0.8f * step
            val s = 0.72f + 0.28f * step
            v.scaleX = s
            v.scaleY = s
            tv?.letterSpacing = (1f - step) * 0.4f
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                tv?.letterSpacing = 0f
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
            }
        })
        bind(v, anim)
    }

    // ---------------- 碎片化：高频随机位移+旋转+拉伸，随后收敛归位 ----------------
    private fun shatter(v: View) {
        val amp = dp(v, 9f)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 460 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            val damp = 1f - t
            v.translationX = (Math.random().toFloat() - 0.5f) * 2f * amp * damp
            v.translationY = (Math.random().toFloat() - 0.5f) * 2f * amp * damp
            v.rotation = (Math.random().toFloat() - 0.5f) * 10f * damp
            v.scaleX = 1f + 0.14f * damp
            v.scaleY = 1f - 0.10f * damp
            v.alpha = 0.35f + 0.65f * t
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                v.translationX = 0f
                v.translationY = 0f
                v.rotation = 0f
                v.scaleX = 1f
                v.scaleY = 1f
                v.alpha = 1f
            }
        })
        bind(v, anim)
    }

    // ---------------- 燃烧：字色由炽热橙渐变回主题色，同时上浮淡入 ----------------
    private fun burn(v: View, baseColor: Int) {
        if (v !is TextView) {
            scale(v)
            return
        }
        val hot = 0xFFFF7A18.toInt()
        val rise = dp(v, 14f)
        val argb = ArgbEvaluator()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 480 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            v.translationY = -rise * t
            v.alpha = 0.25f + 0.75f * t
            v.scaleX = 1f + 0.08f * (1f - t)
            v.scaleY = 1f + 0.08f * (1f - t)
            v.setTextColor((argb.evaluate(t, hot, baseColor) as Int))
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                v.translationY = 0f
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
                v.setTextColor(baseColor)
            }
        })
        bind(v, anim)
    }

    // ---------------- 震撼：阻尼衰减的高频左右晃动 ----------------
    private fun quake(v: View) {
        val amp = dp(v, 7f)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 460 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            val damp = 1f - t
            v.translationX = (sin(t * Math.PI * 2.0 * 5.0)).toFloat() * amp * damp
            v.translationY = (sin(t * Math.PI * 2.0 * 3.0)).toFloat() * amp * 0.35f * damp
            v.scaleX = 1f + 0.07f * damp
            v.scaleY = 1f + 0.07f * damp
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                v.translationX = 0f
                v.translationY = 0f
                v.scaleX = 1f
                v.scaleY = 1f
            }
        })
        bind(v, anim)
    }
}
