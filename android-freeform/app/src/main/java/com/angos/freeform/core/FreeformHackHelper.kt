package com.angos.freeform.core

/**
 * Shared flag telling us whether the invisible freeform "anchor" activity is
 * currently alive. Mirrors Taskbar's FreeformHackHelper singleton.
 */
object FreeformHackHelper {
    @Volatile var freeformHackActive: Boolean = false
    @Volatile var inFreeformWorkspace: Boolean = false
}
