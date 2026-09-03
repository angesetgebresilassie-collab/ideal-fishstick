package com.angos.freeform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.angos.freeform.R
import com.angos.freeform.core.AppRepository
import com.angos.freeform.core.DockPrefs
import com.angos.freeform.core.FreeformLauncher

/**
 * Foreground service that keeps the macOS dock floating above every app.
 *
 * Hosting strategy (in order of reliability):
 *  1. If the WindowDecorService accessibility service is running, the dock is
 *     added through *its* WindowManager as a TYPE_ACCESSIBILITY_OVERLAY. That
 *     window type sits above every application window — including freeform
 *     windows and OEM skins that hide ordinary app overlays.
 *  2. Otherwise it falls back to TYPE_APPLICATION_OVERLAY with the
 *     "Display over other apps" permission.
 *
 * Either way the window is FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL so it
 * never steals input from the app underneath, and on Android 12+ it requests a
 * real backdrop blur via blurBehindRadius for the frosted-glass look.
 */
class DockService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.angos.freeform.REFRESH_DOCK"
        private const val CHANNEL_ID = "dock"
        private const val NOTIF_ID = 42

        /** Live instance, so the accessibility service can re-host the dock when it connects. */
        @Volatile
        var current: DockService? = null
            private set

        fun start(context: android.content.Context) {
            val i = Intent(context, DockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, DockService::class.java))
        }

        fun refresh(context: android.content.Context) {
            context.startService(Intent(context, DockService::class.java).setAction(ACTION_REFRESH))
        }

        fun isRunning(context: android.content.Context): Boolean = current != null
    }

    private lateinit var windowManager: WindowManager
    private var dock: DockView? = null
    /** WindowManager that actually owns the dock view (ours, or the a11y service's). */
    private var hostWm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        startForeground(NOTIF_ID, buildNotification())

        if (!Settings.canDrawOverlays(this) && WindowDecorService.instance == null) {
            Toast.makeText(
                this,
                "Grant \"Display over other apps\" (or enable the MacFreeform accessibility service) first",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        addDock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) reload()
        return START_STICKY
    }

    /** Called when the accessibility service connects/disconnects: re-attach on the best host. */
    fun rehost() {
        removeDock()
        addDock()
    }

    private fun buildDockView(): DockView = DockView(
        this,
        onIconClick = { pkg -> FreeformLauncher.launch(this, pkg) },
        onIconLongClick = { pkg ->
            // Long press = launch fullscreen instead of freeform
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(it) }
            }
        }
    ).also { it.magnificationEnabled = DockPrefs.magnify(this) }

    private fun layoutParams(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (10 * resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Real frosted glass on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = (40 * resources.displayMetrics.density).toInt()
        }
    }

    private fun addDock() {
        val view = buildDockView()

        // 1) Accessibility overlay — always on top of every app window.
        val a11y = WindowDecorService.instance
        if (a11y != null) {
            val ok = a11y.attachDock(
                view,
                layoutParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            )
            if (ok) {
                dock = view
                hostWm = a11y.windowManagerOrNull()
                reload()
                return
            }
        }

        // 2) Plain app overlay.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant \"Display over other apps\" first", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        runCatching { windowManager.addView(view, layoutParams(type)) }
            .onFailure {
                Toast.makeText(this, "Could not show dock: ${it.message}", Toast.LENGTH_LONG).show()
                return
            }
        dock = view
        hostWm = windowManager
        reload()
    }

    private fun removeDock() {
        dock?.let { v -> runCatching { hostWm?.removeView(v) } }
        dock = null
        hostWm = null
    }

    private fun reload() {
        val view = dock ?: return
        val packages = DockPrefs.items(this)
        view.magnificationEnabled = DockPrefs.magnify(this)
        view.items = packages.mapNotNull { pkg ->
            AppRepository.iconFor(this, pkg)?.let { icon ->
                DockView.Item(pkg, AppRepository.labelFor(this, pkg), icon)
            }
        }
        if (view.items.isEmpty()) {
            Toast.makeText(this, "Dock is empty — add apps in MacFreeform", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dock", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Dock is running")
            .setContentText("Tap an icon to open an app in a freeform window")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        removeDock()
        if (current === this) current = null
        super.onDestroy()
    }
}
