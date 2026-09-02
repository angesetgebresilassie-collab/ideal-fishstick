package com.angos.freeform.core

import android.content.Context

/** Persists which packages sit in the dock, in order. */
object DockPrefs {
    private const val FILE = "dock_prefs"
    private const val KEY_ITEMS = "items"
    private const val KEY_MAGNIFY = "magnify"
    private const val KEY_AUTOHIDE = "autohide"

    fun items(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "")
            .orEmpty()
            .split(",")
            .filter { it.isNotBlank() }

    fun setItems(context: Context, packages: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, packages.joinToString(",")).apply()
    }

    fun toggle(context: Context, pkg: String) {
        val current = items(context).toMutableList()
        if (!current.remove(pkg)) current.add(pkg)
        setItems(context, current)
    }

    fun magnify(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_MAGNIFY, true)

    fun setMagnify(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MAGNIFY, value).apply()
    }

    fun autoHide(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTOHIDE, false)

    fun setAutoHide(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOHIDE, value).apply()
    }
}
