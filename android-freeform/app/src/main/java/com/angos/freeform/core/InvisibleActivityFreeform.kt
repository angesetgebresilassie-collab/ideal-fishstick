package com.angos.freeform.core

import android.app.Activity
import android.os.Bundle

/**
 * The freeform "anchor". This activity is started with
 * ActivityOptions.setLaunchWindowingMode(FREEFORM) into a 1x1 px rect just off
 * the bottom-right of the display, so it is invisible but genuinely lives in the
 * freeform stack.
 *
 * Once a freeform task exists, AOSP's ActivityStarter places subsequently
 * launched activities into freeform as well — which is exactly the trick
 * farmerbb/Taskbar uses to launch arbitrary apps into freeform without root.
 */
class InvisibleActivityFreeform : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        FreeformHackHelper.freeformHackActive = true
        FreeformHackHelper.inFreeformWorkspace = true
    }

    override fun onDestroy() {
        FreeformHackHelper.freeformHackActive = false
        FreeformHackHelper.inFreeformWorkspace = false
        super.onDestroy()
    }
}
