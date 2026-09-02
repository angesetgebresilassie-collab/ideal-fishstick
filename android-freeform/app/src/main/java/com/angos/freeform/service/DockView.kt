package com.angos.freeform.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A macOS-style dock: rounded frosted slab, icons that magnify with a gaussian
 * falloff around the pointer, running-app indicator dots, and a bounce launch
 * animation.
 *
 * Real blur behind the dock comes from the window itself
 * (WindowManager.LayoutParams.setBlurBehindRadius on API 31+). This view only
 * paints the translucent slab, hairline border and specular top highlight so
 * the glass reads correctly on both light and dark wallpapers.
 */
class DockView(
    context: Context,
    private val onIconClick: (String) -> Unit,
    private val onIconLongClick: (String) -> Unit
) : View(context) {

    data class Item(val packageName: String, val label: String, val icon: Drawable, var running: Boolean = false)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var items: List<Item> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }

    var magnificationEnabled = true

    private val baseIcon = dp(52f)
    private val maxScale = 1.75f
    private val spread = dp(85f)          // gaussian sigma of the magnification bubble
    private val gap = dp(8f)
    private val padding = dp(10f)
    private val slabRadius = dp(26f)

    private var pointerX = -1f
    private var pointerActive = false
    private var pointerAnim: ValueAnimator? = null

    private val slabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 245, 245, 247)
    }
    private val slabPaintDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 28, 28, 30)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(70, 255, 255, 255)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(110, 255, 255, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 120, 120, 125)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 30, 30, 32) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private var hoveredIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var longFired = false

    private val bounce = HashMap<String, Float>()

    // ---------------------------------------------------------------- layout

    private fun scaleAt(centerX: Float): Float {
        if (!magnificationEnabled || !pointerActive || pointerX < 0) return 1f
        val d = abs(centerX - pointerX)
        val g = exp(-(d * d) / (2f * spread * spread))
        return 1f + (maxScale - 1f) * g
    }

    /** Two-pass layout: unscaled centers first, then scaled widths laid out around the pointer. */
    private fun computeGeometry(): List<RectF> {
        val out = ArrayList<RectF>(items.size)
        if (items.isEmpty()) return out

        val unscaledWidth = items.size * baseIcon + (items.size - 1) * gap
        var x = (width - unscaledWidth) / 2f
        val scales = FloatArray(items.size)
        for (i in items.indices) {
            val center = x + baseIcon / 2f
            scales[i] = scaleAt(center)
            x += baseIcon + gap
        }

        val totalScaled = scales.sumOf { (it * baseIcon).toDouble() }.toFloat() + (items.size - 1) * gap
        var cx = (width - totalScaled) / 2f
        val baseline = height - padding - dp(14f)   // leave room for indicator dots

        for (i in items.indices) {
            val s = scales[i]
            val size = baseIcon * s
            val lift = (size - baseIcon) * 0.5f
            val bounceOffset = bounce[items[i].packageName] ?: 0f
            val top = baseline - size - lift * 0.15f - bounceOffset
            out.add(RectF(cx, top, cx + size, top + size))
            cx += size + gap
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (baseIcon * maxScale + padding * 2 + dp(34f)).roundToInt()
        setMeasuredDimension(w, h)
    }

    // ----------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val geo = computeGeometry()

        val slabLeft = geo.first().left - padding
        val slabRight = geo.last().right + padding
        val slabBottom = height - dp(6f)
        val slabTop = slabBottom - (baseIcon + padding * 2 + dp(10f))
        val slab = RectF(slabLeft, slabTop, slabRight, slabBottom)

        canvas.drawRoundRect(slab, slabRadius, slabRadius, if (isDark) slabPaintDark else slabPaint)
        canvas.drawRoundRect(slab, slabRadius, slabRadius, borderPaint)
        // specular sheen along the top edge
        canvas.drawLine(
            slab.left + slabRadius, slab.top + dp(1f),
            slab.right - slabRadius, slab.top + dp(1f), highlightPaint
        )

        for (i in items.indices) {
            val item = items[i]
            val r = geo[i]
            item.icon.setBounds(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
            item.icon.draw(canvas)

            if (item.running) {
                canvas.drawCircle(r.centerX(), slab.bottom - dp(6f), dp(2.2f), dotPaint)
            }

            if (i == hoveredIndex && pointerActive) drawTooltip(canvas, item.label, r)
        }
    }

    private fun drawTooltip(canvas: Canvas, text: String, iconRect: RectF) {
        val padH = dp(10f); val padV = dp(6f)
        val tw = labelText.measureText(text)
        val boxW = tw + padH * 2
        val boxH = labelText.textSize + padV * 2
        val left = (iconRect.centerX() - boxW / 2).coerceIn(dp(4f), width - boxW - dp(4f))
        val top = iconRect.top - boxH - dp(10f)
        val box = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(box, dp(8f), dp(8f), labelBg)
        canvas.drawText(text, box.centerX(), box.bottom - padV - dp(1f), labelText)
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerActive = true
                pointerX = event.x
                downX = event.x; downY = event.y
                downTime = System.currentTimeMillis()
                longFired = false
                hoveredIndex = indexAt(event.x)
                postDelayed(longPress, 480)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                pointerX = event.x
                hoveredIndex = indexAt(event.x)
                if (abs(event.x - downX) > dp(12f) || abs(event.y - downY) > dp(12f)) {
                    removeCallbacks(longPress)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val idx = indexAt(event.x)
                if (!longFired && idx >= 0 && System.currentTimeMillis() - downTime < 480) {
                    bounceIcon(items[idx].packageName)
                    onIconClick(items[idx].packageName)
                }
                releasePointer()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                releasePointer()
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                pointerActive = true
                pointerX = event.x
                hoveredIndex = indexAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> releasePointer()
        }
        return true
    }

    private val longPress = Runnable {
        val idx = indexAt(pointerX)
        if (idx >= 0) {
            longFired = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onIconLongClick(items[idx].packageName)
        }
    }

    private fun releasePointer() {
        pointerAnim?.cancel()
        val start = pointerX
        pointerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                pointerX = start
                if (t >= 1f) { pointerActive = false; hoveredIndex = -1 }
                invalidate()
            }
            start()
        }
        pointerActive = false
        hoveredIndex = -1
        invalidate()
    }

    private fun indexAt(x: Float): Int {
        val geo = computeGeometry()
        for (i in geo.indices) if (x >= geo[i].left && x <= geo[i].right) return i
        return -1
    }

    private fun bounceIcon(pkg: String) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            addUpdateListener {
                val t = it.animatedFraction
                // two decaying hops
                val h = dp(20f) * kotlin.math.sin(t * Math.PI * 2).toFloat() * (1f - t)
                bounce[pkg] = abs(h)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bounce.remove(pkg); invalidate()
                }
            })
            start()
        }
    }
}
