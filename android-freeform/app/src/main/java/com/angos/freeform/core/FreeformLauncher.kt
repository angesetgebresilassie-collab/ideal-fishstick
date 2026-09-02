package com.angos.freeform.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Launches an arbitrary package into a FREEFORM window.
 *
 * How this actually works
 * ----------------------
 * `ActivityOptions.setLaunchWindowingMode(int)` is an @hide API. It exists on every
 * Android build since Oreo, but it is not in the public SDK, so we call it by
 * reflection. On Android 9+ the hidden-API blacklist can refuse the reflective
 * lookup, in which case the user must run once:
 *
 *     adb shell settings put global hidden_api_policy 1
 *
 * and the device must have freeform enabled:
 *
 *     adb shell settings put global enable_freeform_support 1
 *     adb shell settings put global force_resizable_activities 1
 *     adb shell settings put global development_force_resizable_activities 1
 *
 * (Developer Options -> "Enable freeform windows" + "Force activities to be
 * resizable" flips exactly these flags.)
 *
 * WINDOWING_MODE constants live in android.app.WindowConfiguration:
 *   0 = undefined, 1 = fullscreen, 2 = pinned, 3 = split-primary,
 *   4 = split-secondary, 5 = FREEFORM, 6 = multi-window
 */
object FreeformLauncher {

    const val WINDOWING_MODE_FREEFORM = 5
    private const val TAG = "FreeformLauncher"

    /** Cascade offset so successive windows don't stack perfectly. */
    private var cascadeIndex = 0

    fun isFreeformEnabledOnDevice(context: Context): Boolean {
        val cr = context.contentResolver
        val freeform = android.provider.Settings.Global.getInt(cr, "enable_freeform_support", 0)
        val resizable = android.provider.Settings.Global.getInt(cr, "force_resizable_activities", 0)
        val supportsPm = context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )
        return freeform == 1 || resizable == 1 || supportsPm
    }

    fun hiddenApiPolicyOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return runCatching {
            ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType
            )
            true
        }.getOrDefault(false)
    }

    /**
     * @param bounds optional explicit window rect in screen pixels. Null = auto cascade.
     * @return true if the activity was started with freeform options applied.
     */
    fun launch(context: Context, packageName: String, bounds: Rect? = null): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(context, "No launcher activity for $packageName", Toast.LENGTH_SHORT).show()
            return false
        }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        val options = ActivityOptions.makeBasic()
        val rect = bounds ?: nextCascadeBounds(context)

        var freeformApplied = false
        try {
            val setMode = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType
            )
            setMode.invoke(options, WINDOWING_MODE_FREEFORM)
            freeformApplied = true
        } catch (t: Throwable) {
            Log.w(TAG, "setLaunchWindowingMode unavailable: ${t.message}")
        }

        // Also set the activity type to standard (1) when available — some OEM
        // ROMs otherwise drop the launch back to fullscreen.
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchActivityType", Int::class.javaPrimitiveType)
                .invoke(options, 1)
        }

        options.launchBounds = rect

        return try {
            context.startActivity(intent, options.toBundle())
            if (!freeformApplied) {
                Toast.makeText(
                    context,
                    "Launched, but freeform mode could not be forced. " +
                        "Enable freeform in Developer Options and set hidden_api_policy=1.",
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startActivity failed", t)
            Toast.makeText(context, "Could not launch: ${t.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Moves an already-running task into freeform. Needs MANAGE_ACTIVITY_TASKS. */
    fun moveTaskToFreeform(context: Context, taskId: Int, bounds: Rect): Boolean = runCatching {
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        val getService = atmClass.getMethod("getService")
        val service = getService.invoke(null)
        service.javaClass
            .getMethod(
                "setTaskWindowingMode",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            .invoke(service, taskId, WINDOWING_MODE_FREEFORM, false)
        service.javaClass
            .getMethod("resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType)
            .invoke(service, taskId, bounds, 0)
        true
    }.getOrElse {
        Log.w(TAG, "moveTaskToFreeform failed: ${it.message}")
        false
    }

    private fun nextCascadeBounds(context: Context): Rect {
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.72f).roundToInt()
        val h = (dm.heightPixels * 0.62f).roundToInt()
        val step = (24 * dm.density).roundToInt()
        val i = cascadeIndex++ % 6
        val left = ((dm.widthPixels - w) / 2) + (i - 3) * step
        val top = ((dm.heightPixels - h) / 2) + (i - 3) * step
        return Rect(
            left.coerceAtLeast(0),
            top.coerceAtLeast(0),
            (left + w).coerceAtMost(dm.widthPixels),
            (top + h).coerceAtMost(dm.heightPixels)
        )
    }
}
