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
 * The overlay uses TYPE_APPLICATION_OVERLAY with FLAG_NOT_FOCUSABLE so it never
 * steals input focus from the app underneath, and on Android 12+ it requests a
 * real backdrop blur via setBlurBehindRadius — that is what makes the dock read
 * as frosted glass rather than plain translucency.
 */
class DockService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.angos.freeform.REFRESH_DOCK"
        private const val CHANNEL_ID = "dock"
        private const val NOTIF_ID = 42

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

        fun isRunning(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == DockService::class.java.name }
        }
    }

    private lateinit var windowManager: WindowManager
    private var dock: DockView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant \"Display over other apps\" first", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, buildNotification())
        addDock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) reload()
        return START_STICKY
    }

    private fun addDock() {
        val view = DockView(
            this,
            onIconClick = { pkg ->
                FreeformLauncher.launch(this, pkg)
            },
            onIconLongClick = { pkg ->
                // Long press = launch fullscreen instead of freeform
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            }
        )
        view.magnificationEnabled = DockPrefs.magnify(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (10 * resources.displayMetrics.density).toInt()
        }

        // Real frosted glass on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = (40 * resources.displayMetrics.density).toInt()
        }

        windowManager.addView(view, params)
        dock = view
        reload()
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
        dock?.let { runCatching { windowManager.removeView(it) } }
        dock = null
        super.onDestroy()
    }
}
