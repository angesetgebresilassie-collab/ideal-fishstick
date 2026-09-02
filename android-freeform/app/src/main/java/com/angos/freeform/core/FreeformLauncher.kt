package com.angos.freeform.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Launches an arbitrary package into a FREEFORM window, using the same technique
 * as farmerbb/Taskbar:
 *
 * 1. Start an invisible 1x1 px activity (InvisibleActivityFreeform) off-screen,
 *    explicitly requesting the freeform windowing mode through
 *    ActivityOptions.setLaunchWindowingMode(5) (reflection — @hide) or the
 *    pre-Pie setLaunchStackId(2).
 * 2. Wait a beat for that task to settle in the freeform stack.
 * 3. Start the real app with plain FLAG_ACTIVITY_NEW_TASK | SINGLE_TOP and
 *    explicit launchBounds. Because a freeform task is already active, the
 *    framework creates the new activity in freeform too.
 *
 * Requirements on the device (Developer Options or adb):
 *     settings put global enable_freeform_support 1
 *     settings put global force_resizable_activities 1
 *     settings put global hidden_api_policy 1     # lets the reflection through
 *
 * Draw-over-other-apps must be granted so the anchor activity can be started.
 */
object FreeformLauncher {

    const val WINDOWING_MODE_FREEFORM = 5
    private const val FREEFORM_WORKSPACE_STACK_ID = 2
    private const val TAG = "FreeformLauncher"

    private val handler = Handler(Looper.getMainLooper())
    private var cascadeIndex = 0

    // ---------------------------------------------------------------- checks

    fun isFreeformEnabledOnDevice(context: Context): Boolean {
        val cr = context.contentResolver
        val freeform = Settings.Global.getInt(cr, "enable_freeform_support", 0)
        val resizable = Settings.Global.getInt(cr, "force_resizable_activities", 0)
        val supportsPm = context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )
        return freeform == 1 || resizable == 1 || supportsPm
    }

    fun hiddenApiPolicyOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return runCatching {
            ActivityOptions::class.java.getMethod(windowingModeMethodName(), Int::class.javaPrimitiveType)
            true
        }.getOrDefault(false)
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun windowingModeMethodName() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "setLaunchWindowingMode" else "setLaunchStackId"

    private fun freeformModeId() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WINDOWING_MODE_FREEFORM
        else FREEFORM_WORKSPACE_STACK_ID

    // ---------------------------------------------------------------- options

    /** Applies the (hidden) freeform windowing mode to the options. */
    private fun applyFreeformMode(options: ActivityOptions): Boolean = runCatching {
        ActivityOptions::class.java
            .getMethod(windowingModeMethodName(), Int::class.javaPrimitiveType)
            .invoke(options, freeformModeId())
        true
    }.getOrElse {
        Log.w(TAG, "${windowingModeMethodName()} unavailable: ${it.message}")
        false
    }

    private fun optionsFor(bounds: Rect, freeform: Boolean): Pair<ActivityOptions, Boolean> {
        val options = ActivityOptions.makeBasic()
        val applied = if (freeform) applyFreeformMode(options) else false
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchActivityType", Int::class.javaPrimitiveType)
                .invoke(options, 1)
        }
        options.launchBounds = bounds
        return options to applied
    }

    // ------------------------------------------------------------- the hack

    /**
     * Starts the invisible anchor activity in freeform, 1x1 px off the bottom
     * right corner of the display — Taskbar's `startActivityLowerRight`.
     */
    fun startFreeformHack(context: Context): Boolean {
        if (!canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Grant \"Display over other apps\" so freeform windows can be anchored.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        val dm = context.resources.displayMetrics
        val intent = Intent(context, InvisibleActivityFreeform::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        val bounds = Rect(dm.widthPixels, dm.heightPixels, dm.widthPixels + 1, dm.heightPixels + 1)
        val (options, applied) = optionsFor(bounds, freeform = true)
        return try {
            context.startActivity(intent, options.toBundle())
            applied
        } catch (t: Throwable) {
            Log.w(TAG, "freeform hack failed: ${t.message}")
            false
        }
    }

    // ---------------------------------------------------------------- launch

    /**
     * @param bounds optional explicit window rect in screen pixels. Null = auto cascade.
     */
    fun launch(context: Context, packageName: String, bounds: Rect? = null): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(context, "No launcher activity for $packageName", Toast.LENGTH_SHORT).show()
            return false
        }

        // Plain flags — no MULTIPLE_TASK / LAUNCH_ADJACENT here; those push the
        // launch out of the freeform stack we just created.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val rect = bounds ?: nextCascadeBounds(context)
        val needsHack = !FreeformHackHelper.freeformHackActive
        var freeformApplied = true

        if (needsHack) freeformApplied = startFreeformHack(context)

        val start = Runnable {
            val (options, applied) = optionsFor(rect, freeform = true)
            try {
                context.startActivity(intent, options.toBundle())
                if (!applied && !FreeformHackHelper.freeformHackActive) {
                    Toast.makeText(
                        context,
                        "Launched, but freeform could not be forced. Enable freeform in " +
                            "Developer Options and run: adb shell settings put global hidden_api_policy 1",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startActivity failed", t)
                Toast.makeText(context, "Could not launch: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Give the anchor task time to land in the freeform stack first.
        val delay = if (!needsHack) 0L else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 300L else 100L
        handler.postDelayed(start, delay)
        return freeformApplied
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
