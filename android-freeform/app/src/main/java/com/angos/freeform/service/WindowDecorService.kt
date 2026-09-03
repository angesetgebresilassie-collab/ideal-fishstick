package com.angos.freeform.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Draws a macOS-style title bar (frosted strip + red/amber/green traffic lights)
 * on top of every FREEFORM window and lets you drag / close / zoom them.
 *
 * Why an accessibility service: an ordinary app cannot repaint another app's
 * system-drawn freeform caption. What it CAN do is enumerate on-screen windows
 * (AccessibilityWindowInfo) and paint its own caption overlay exactly on top of
 * each window's bounds. Combined with hiding the stock caption
 * (`adb shell settings put global freeform_caption_height 0` on ROMs that honour
 * it), the result reads as a native macOS window chrome.
 */
class WindowDecorService : AccessibilityService() {

    companion object {
        /** Live instance; DockService uses it to host the dock as an accessibility overlay. */
        @Volatile
        var instance: WindowDecorService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private val captions = HashMap<Int, CaptionView>()
    private var dockView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
        // Move the dock onto the accessibility overlay layer (always on top).
        DockService.current?.rehost()
        syncCaptions()
    }

    fun windowManagerOrNull(): WindowManager? = if (::wm.isInitialized) wm else null

    /** Adds the dock view on the accessibility overlay layer. Returns true on success. */
    fun attachDock(view: View, params: WindowManager.LayoutParams): Boolean {
        if (!::wm.isInitialized) return false
        detachDock()
        return runCatching {
            wm.addView(view, params)
            dockView = view
            true
        }.getOrDefault(false)
    }

    fun detachDock() {
        dockView?.let { runCatching { wm.removeView(it) } }
        dockView = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = syncCaptions()

    override fun onInterrupt() = Unit

    private fun syncCaptions() {
        if (!::wm.isInitialized) return
        val display = resources.displayMetrics
        val live = HashSet<Int>()

        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val bounds = Rect().also { w.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue

            // Heuristic for "this is a freeform window": it doesn't span the display.
            val isFreeform =
                bounds.width() < display.widthPixels - 8 || bounds.height() < display.heightPixels - 8
            if (!isFreeform) continue

            live.add(w.id)
            val caption = captions.getOrPut(w.id) { attachCaption(w.id) }
            caption.updateFor(bounds, w.title?.toString().orEmpty())
        }

        (captions.keys - live).forEach { id ->
            captions.remove(id)?.let { runCatching { wm.removeView(it) } }
        }
    }

    private fun attachCaption(windowId: Int): CaptionView {
        val view = CaptionView(this,
            onClose = { performGlobalAction(GLOBAL_ACTION_BACK) },
            onMinimize = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onZoom = { toggleZoom(windowId) },
            onDrag = { dx, dy -> moveCaption(windowId, dx, dy) }
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = (28 * resources.displayMetrics.density).toInt()
        }

        wm.addView(view, lp)
        return view
    }

    private fun moveCaption(windowId: Int, dx: Int, dy: Int) {
        val view = captions[windowId] ?: return
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.x += dx; lp.y += dy
        runCatching { wm.updateViewLayout(view, lp) }
        // Ask the system to actually move the task (requires MANAGE_ACTIVITY_TASKS).
        val b = view.windowBounds ?: return
        b.offset(dx, dy)
        com.angos.freeform.core.FreeformLauncher.moveTaskToFreeform(this, windowId, b)
    }

    private fun toggleZoom(windowId: Int) {
        val view = captions[windowId] ?: return
        val dm = resources.displayMetrics
        val current = view.windowBounds ?: return
        val target = if (current.width() > dm.widthPixels * 0.9f) {
            Rect(
                (dm.widthPixels * 0.14f).toInt(), (dm.heightPixels * 0.19f).toInt(),
                (dm.widthPixels * 0.86f).toInt(), (dm.heightPixels * 0.81f).toInt()
            )
        } else {
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        com.angos.freeform.core.FreeformLauncher.moveTaskToFreeform(this, windowId, target)
    }

    override fun onDestroy() {
        instance = null
        detachDock()
        captions.values.forEach { runCatching { wm.removeView(it) } }
        captions.clear()
        // Fall back to the plain overlay window so the dock survives.
        DockService.current?.rehost()
        super.onDestroy()
    }
}

/** The frosted caption bar itself: three traffic lights + centred title. */
class CaptionView(
    context: Context,
    private val onClose: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onZoom: () -> Unit,
    private val onDrag: (Int, Int) -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var windowBounds: Rect? = null
        private set
    private var title: String = ""

    private val barHeight = dp(30f)
    private val radius = dp(11f)
    private val lightRadius = dp(6.5f)
    private val lightGap = dp(20f)
    private val leftInset = dp(16f)

    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 240, 240, 244) }
    private val glassDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 40, 40, 44) }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(80, 255, 255, 255)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = dp(13f)
    }
    private val red = paint(0xFFFF5F57.toInt())
    private val amber = paint(0xFFFEBC2E.toInt())
    private val green = paint(0xFF28C840.toInt())
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.3f)
        color = Color.argb(140, 0, 0, 0); strokeCap = Paint.Cap.ROUND
    }

    private var hovered = false

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun updateFor(bounds: Rect, windowTitle: String) {
        windowBounds = Rect(bounds)
        title = windowTitle
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = bounds.left
        lp.y = bounds.top
        lp.width = bounds.width()
        lp.height = barHeight.toInt()
        runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(this, lp)
        }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), barHeight.toInt())

    override fun onDraw(canvas: Canvas) {
        val bar = RectF(0f, 0f, width.toFloat(), barHeight)
        canvas.save()
        canvas.clipRect(bar)
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), barHeight + radius),
            radius, radius, if (isDark) glassDark else glass
        )
        canvas.restore()
        canvas.drawLine(0f, barHeight - dp(0.5f), width.toFloat(), barHeight - dp(0.5f), hairline)

        val cy = barHeight / 2f
        drawLight(canvas, leftInset, cy, red, 0)
        drawLight(canvas, leftInset + lightGap, cy, amber, 1)
        drawLight(canvas, leftInset + lightGap * 2, cy, green, 2)

        titlePaint.color = if (isDark) Color.argb(230, 255, 255, 255) else Color.argb(220, 30, 30, 32)
        canvas.drawText(
            title.ifBlank { "Window" },
            width / 2f,
            cy + titlePaint.textSize / 3f,
            titlePaint
        )
    }

    private fun drawLight(canvas: Canvas, cx: Float, cy: Float, p: Paint, kind: Int) {
        canvas.drawCircle(cx, cy, lightRadius, p)
        if (!hovered) return
        val r = lightRadius * 0.45f
        when (kind) {
            0 -> { // close: ×
                canvas.drawLine(cx - r, cy - r, cx + r, cy + r, glyph)
                canvas.drawLine(cx + r, cy - r, cx - r, cy + r, glyph)
            }
            1 -> canvas.drawLine(cx - r, cy, cx + r, cy, glyph)  // minimise: −
            2 -> { // zoom: chevrons
                canvas.drawLine(cx - r, cy + r, cx + r * 0.2f, cy + r, glyph)
                canvas.drawLine(cx - r, cy + r, cx - r, cy - r * 0.2f, glyph)
                canvas.drawLine(cx + r, cy - r, cx - r * 0.2f, cy - r, glyph)
                canvas.drawLine(cx + r, cy - r, cx + r, cy + r * 0.2f, glyph)
            }
        }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cy = barHeight / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hovered = true; invalidate()
                lastX = event.rawX; lastY = event.rawY; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastX).toInt()
                val dy = (event.rawY - lastY).toInt()
                if (abs(dx) > 2 || abs(dy) > 2) {
                    dragging = true
                    onDrag(dx, dy)
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val x = event.x
                    when {
                        hit(x, event.y, leftInset, cy) -> onClose()
                        hit(x, event.y, leftInset + lightGap, cy) -> onMinimize()
                        hit(x, event.y, leftInset + lightGap * 2, cy) -> onZoom()
                    }
                }
                hovered = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { hovered = false; invalidate() }
        }
        return true
    }

    private fun hit(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        val slop = lightRadius * 2f
        return abs(x - cx) <= slop && abs(y - cy) <= slop
    }
}
