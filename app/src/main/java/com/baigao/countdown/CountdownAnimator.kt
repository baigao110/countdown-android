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
import kotlin.math.abs
import kotlin.math.sin

/**
 * 倒计时数字跳秒时的入场动画。
 *
 * 只使用框架自带的属性动画（scale / alpha / translation / rotation / 文字颜色 / 字距），
 * 不引入任何第三方依赖，也不改动视图层级 —— 这样在列表复用、悬浮窗、拖动排序等
 * 各种场景下都不会破坏布局。
 *
 * 动画时长统一控制在 700ms 以内：列表每秒刷新一次，留有足够余量避免叠加。
 * 每次播放前先取消上一次，结束时必定把属性复位，防止列表复用后属性残留。
 *
 * v1.0.0.37 增强（两轮）：把每一种效果的幅度显著加大、加入颜色/抖动等辅助表演，
 * 使「缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼」在视觉上非常醒目。
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
                PIXEL -> pixel(v, baseColor)
                SHATTER -> shatter(v)
                BURN -> burn(v, baseColor)
                QUAKE -> quake(v, baseColor)
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

    // ---------------- 缩放：新数字由极大缩小到位，带夸张回弹 ----------------
    private fun scale(v: View) {
        v.scaleX = 3.4f
        v.scaleY = 3.4f
        v.alpha = 0.12f
        v.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(560)
            .setInterpolator(OvershootInterpolator(3.6f))
            .start()
    }

    // ---------------- 蒸发：数字自下方像蒸汽一样凝结升起，带明显横向摇曳 ----------------
    private fun evaporate(v: View) {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 660 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            val ease = t * t * (3f - 2f * t) // smoothstep
            v.alpha = ease
            v.translationY = dp(v, 46f) * (1f - ease)            // 从下方升到原位
            v.translationX = (sin(t * Math.PI * 3.0)).toFloat() * dp(v, 12f) * (1f - t) // 上升时左右摇曳
            val s = 0.35f + 0.65f * ease
            v.scaleX = s
            v.scaleY = s
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                v.translationX = 0f
                v.translationY = 0f
                v.scaleX = 1f
                v.scaleY = 1f
                v.alpha = 1f
            }
        })
        bind(v, anim)
    }

    // ---------------- 坠落：从很高处掉落并触底回弹，落地带明显挤压 ----------------
    private fun fall(v: View) {
        val dist = if (v.height > 0) v.height * 2.3f + dp(v, 72f) else dp(v, 110f)
        v.translationY = -dist
        v.alpha = 0.18f
        v.rotation = -30f
        v.animate()
            .translationY(0f).alpha(1f).rotation(0f)
            .setDuration(680)
            .setInterpolator(BounceInterpolator())
            .withEndAction {
                // 落地再补一个明显的挤压，强化“砸下来”的实感
                v.scaleX = 1.3f
                v.scaleY = 0.7f
                v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            .start()
    }

    // ---------------- 像素化：分 5 档阶梯拼合 + 大幅像素抖动 + 中段高亮，像马赛克一格格点亮 ----------------
    private fun pixel(v: View, baseColor: Int) {
        val tv = v as? TextView
        val ampX = dp(v, 17f)
        val ampY = dp(v, 14f)
        val steps = 5
        val white = 0xFFFFFFFF.toInt()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 620 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            // 把连续进度量化成 5 档，视觉上像“色块一格格拼出来”
            val step = Math.floor((t * steps).toDouble()).toFloat() / steps
            v.alpha = 0.08f + 0.92f * step
            val s = 0.4f + 0.6f * step
            v.scaleX = s
            v.scaleY = s
            // 每帧随机吸附到“像素列/行”，制造马赛克错位感
            val jitter = 1f - step
            v.translationX = (Math.random().toFloat() - 0.5f) * 2f * ampX * jitter
            v.translationY = (Math.random().toFloat() - 0.5f) * 2f * ampY * jitter
            // 拼合中段用白色高亮，像数据在加载；末段落回主题色
            tv?.setTextColor(if (step < 0.7f) white else baseColor)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                tv?.setTextColor(baseColor)
                tv?.letterSpacing = 0f
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
                v.translationX = 0f
                v.translationY = 0f
            }
        })
        bind(v, anim)
    }

    // ---------------- 碎片化：大幅随机位移+旋转+拉伸，随后收敛归位 ----------------
    private fun shatter(v: View) {
        val amp = dp(v, 36f)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 620 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            val damp = 1f - t
            v.translationX = (Math.random().toFloat() - 0.5f) * 2f * amp * damp
            v.translationY = (Math.random().toFloat() - 0.5f) * 2f * amp * damp
            v.rotation = (Math.random().toFloat() - 0.5f) * 60f * damp
            v.scaleX = 1f + 0.5f * damp
            v.scaleY = 1f - 0.32f * damp
            v.alpha = 0.05f + 0.95f * t
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

    // ---------------- 燃烧：字色由炽白经黄/橙渐变回主题色，同时上浮并伴随火苗抖动 ----------------
    private fun burn(v: View, baseColor: Int) {
        if (v !is TextView) {
            scale(v)
            return
        }
        val white = 0xFFFFFFF0.toInt()
        val yellow = 0xFFFFD23A.toInt()
        val orange = 0xFFFF5A18.toInt()
        val rise = dp(v, 26f)
        val argb = ArgbEvaluator()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 700 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            // 多段颜色插值：炽白 → 黄 → 橙 → 主题色
            val color = when {
                t < 0.33f -> argb.evaluate(t / 0.33f, white, yellow) as Int
                t < 0.7f -> argb.evaluate((t - 0.33f) / 0.37f, yellow, orange) as Int
                else -> argb.evaluate((t - 0.7f) / 0.3f, orange, baseColor) as Int
            }
            v.setTextColor(color)
            v.translationY = -rise * t
            // 火苗抖动：整体先涨后缩，并叠加高频颤动
            val flicker = 1f + 0.2f * (1f - t) * (1f + 0.4f * sin(t * Math.PI * 8.0).toFloat())
            v.scaleX = flicker
            v.scaleY = flicker
            v.alpha = 0.25f + 0.75f * t
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

    // ---------------- 震撼：阻尼衰减的高频大幅左右上下晃动 + 冲击缩放 + 白光闪一下 ----------------
    private fun quake(v: View, baseColor: Int) {
        val tv = v as? TextView
        val amp = dp(v, 28f)
        val white = 0xFFFFFFFF.toInt()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 660 }
        anim.addUpdateListener { an ->
            val t = an.animatedFraction
            val damp = 1f - t
            v.translationX = (sin(t * Math.PI * 2.0 * 11.0)).toFloat() * amp * damp
            v.translationY = (sin(t * Math.PI * 2.0 * 6.0 + 1.0)).toFloat() * amp * 0.55f * damp
            val punch = abs(sin(t * Math.PI * 11.0)).toFloat()
            val sc = 1f + 0.3f * damp * punch
            v.scaleX = sc
            v.scaleY = sc
            // 起始与中段各闪一次白光，强化“震撼”冲击感
            val flash = t < 0.13f || (t > 0.4f && t < 0.5f)
            tv?.setTextColor(if (flash) white else baseColor)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                v.translationX = 0f
                v.translationY = 0f
                v.scaleX = 1f
                v.scaleY = 1f
                tv?.setTextColor(baseColor)
            }
        })
        bind(v, anim)
    }
}
