package com.baigao.countdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View

/**
 * 液态玻璃（Liquid Glass）的背景光斑层，纯框架实现、零第三方依赖。
 *
 * 做法：在深色渐变底上画几团青 / 蓝 / 紫的柔和光斑，再整体做一次高斯模糊
 * （Android 12+ 用 RenderEffect，低版本靠径向渐变本身的柔和边缘兜底），
 * 上层的半透明玻璃卡片透过去就有折射、通透的液态玻璃观感。
 *
 * 用法：放在布局最底层（FrameLayout 的第一个子 View），宽度高度 match_parent。
 */
class GlassBackdrop @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一团光斑：相对坐标（0~1）+ 相对半径 + 颜色。 */
    private class Blob(val x: Float, val y: Float, val r: Float, val color: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val blobs = listOf(
        Blob(0.18f, 0.14f, 0.52f, Color.parseColor("#CC00D8FF")), // 左上：青
        Blob(0.86f, 0.24f, 0.46f, Color.parseColor("#B37A5CFF")), // 右上：紫
        Blob(0.30f, 0.72f, 0.58f, Color.parseColor("#993367FF")), // 左下：蓝
        Blob(0.92f, 0.86f, 0.42f, Color.parseColor("#8000FFC8"))  // 右下：青绿
    )

    init {
        // 光斑整体压暗一点，避免颜色过艳抢掉前景文字
        alpha = 0.9f
        applyBlur()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyBlur()
    }

    /** Android 12（API 31）起可用 RenderEffect 做真模糊；低版本保持渐变原样。 */
    @SuppressLint("NewApi")
    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= 31 && width > 0 && height > 0) {
            setRenderEffect(
                RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (b in blobs) {
            val cx = b.x * w
            val cy = b.y * h
            val radius = b.r * (if (w > h) w else h) * 0.7f
            paint.shader = RadialGradient(
                cx, cy, radius,
                b.color, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null
    }
}
