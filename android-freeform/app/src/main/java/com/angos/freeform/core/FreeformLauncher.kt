package com.angos.freeform.core

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Launches an arbitrary package into a FREEFORM window using farmerbb/Taskbar's
 * technique (see Taskbar's U.java):
 *
 * 1. `startFreeformHack` starts an invisible 1x1 px activity
 *    (InvisibleActivityFreeform) in the lower-right corner, with
 *    ActivityOptions.setLaunchWindowingMode(5) applied through reflection
 *    (setLaunchStackId(2) before Pie).
 * 2. Wait 300 ms (Android 11+) / 100 ms for that task to land in the freeform
 *    stack — 0 ms if the anchor is already alive.
 * 3. Start the target app with an *explicit* ACTION_MAIN/CATEGORY_LAUNCHER
 *    intent on the resolved ComponentName, flags NEW_TASK | SINGLE_TOP, and
 *    ActivityOptions carrying the freeform windowing mode + launch bounds.
 *
 * Every startActivity is wrapped: Android throws IllegalArgumentException or
 * SecurityException when launch bounds / windowing mode are refused, so we
 * degrade step by step (bounds+mode -> plain options -> LauncherApps -> no
 * options) instead of crashing with "unable to start intent".
 *
 * Device prerequisites:
 *     settings put global enable_freeform_support 1
 *     settings put global force_resizable_activities 1
 *     settings put global hidden_api_policy 1
 */
object FreeformLauncher {

    const val WINDOWING_MODE_FREEFORM = 5
    private const val FREEFORM_WORKSPACE_STACK_ID = 2
    private const val TAG = "FreeformLauncher"

    private val handler = Handler(Looper.getMainLooper())
    private var cascadeIndex = 0
    private var reflectionAllowed = false

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
        allowReflection()
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

    /** Taskbar's allowReflection(): lift the greylist so @hide methods resolve. */
    private fun allowReflection() {
        if (reflectionAllowed || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            val setExemptions = vmRuntime
                .getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .apply { isAccessible = true }
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        }.onFailure { Log.w(TAG, "hidden api exemption failed: ${it.message}") }
        reflectionAllowed = true
    }

    // ---------------------------------------------------------------- options

    private fun applyFreeformMode(options: ActivityOptions): Boolean {
        allowReflection()
        return runCatching {
            ActivityOptions::class.java
                .getMethod(windowingModeMethodName(), Int::class.javaPrimitiveType)
                .invoke(options, freeformModeId())
            true
        }.getOrElse {
            Log.w(TAG, "${windowingModeMethodName()} unavailable: ${it.message}")
            false
        }
    }

    /** Freeform options + bounds. Never sets launch activity type (rejected on 12+). */
    private fun optionsFor(bounds: Rect?): Pair<ActivityOptions, Boolean> {
        val options = ActivityOptions.makeBasic()
        val applied = applyFreeformMode(options)
        if (bounds != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { options.launchBounds = bounds }
        }
        return options to applied
    }

    // ------------------------------------------------------------- the hack

    /** Starts the invisible anchor in freeform, 1x1 px off the bottom-right corner. */
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
        val (options, applied) = optionsFor(bounds)
        return try {
            context.startActivity(intent, options.toBundle())
            applied
        } catch (t: Throwable) {
            // IllegalArgumentException / SecurityException are expected on some ROMs
            Log.w(TAG, "freeform hack failed: ${t.message}")
            false
        }
    }

    // ---------------------------------------------------------------- launch

    /** Resolves the real launcher component so we can use an explicit ACTION_MAIN intent. */
    private fun launcherComponent(context: Context, packageName: String): ComponentName? {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val info = pm.queryIntentActivities(query, 0).firstOrNull()
            ?: return pm.getLaunchIntentForPackage(packageName)?.component
        return ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**
     * @param bounds optional explicit window rect in screen pixels. Null = auto cascade.
     */
    fun launch(context: Context, packageName: String, bounds: Rect? = null): Boolean {
        val component = launcherComponent(context, packageName)
        if (component == null) {
            Toast.makeText(context, "No launcher activity for $packageName", Toast.LENGTH_SHORT).show()
            return false
        }

        // Explicit component + MAIN/LAUNCHER, exactly like Taskbar's continueLaunchingApp.
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val rect = bounds ?: nextCascadeBounds(context)
        val needsHack = !FreeformHackHelper.freeformHackActive
        var freeformApplied = true

        if (needsHack) freeformApplied = startFreeformHack(context)

        val start = Runnable { startWithFallbacks(context, intent, component, rect) }

        val delay = when {
            !needsHack -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> 300L
            else -> 100L
        }
        handler.postDelayed(start, delay)
        return freeformApplied
    }

    /** bounds+freeform -> freeform only -> LauncherApps -> plain start. */
    private fun startWithFallbacks(
        context: Context,
        intent: Intent,
        component: ComponentName,
        rect: Rect
    ) {
        val attempts = listOf<() -> Unit>(
            { context.startActivity(intent, optionsFor(rect).first.toBundle()) },
            { context.startActivity(intent, optionsFor(null).first.toBundle()) },
            {
                val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.startMainActivity(
                    component,
                    Process.myUserHandle(),
                    rect,
                    optionsFor(rect).first.toBundle()
                )
            },
            { context.startActivity(intent) }
        )

        for ((i, attempt) in attempts.withIndex()) {
            try {
                attempt()
                if (i > 0) Log.w(TAG, "started via fallback #$i")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "launch attempt #$i failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        Toast.makeText(
            context,
            "Could not start ${component.packageName}. Enable freeform in Developer Options and run:\n" +
                "adb shell settings put global hidden_api_policy 1",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Moves an already-running task into freeform. Needs MANAGE_ACTIVITY_TASKS. */
    fun moveTaskToFreeform(context: Context, taskId: Int, bounds: Rect): Boolean {
        allowReflection()
        return runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val service = getService.invoke(null)
            service!!.javaClass
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
