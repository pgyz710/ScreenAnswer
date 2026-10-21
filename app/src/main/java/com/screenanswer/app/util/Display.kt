package com.screenanswer.app.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** Full display size in pixels, including system bars. Used for both capture and overlay layout. */
fun displaySize(context: Context): Point {
    return try {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    } catch (_: Throwable) {
        // Some OEM service contexts do not expose window metrics; resources are a safe fallback.
        val metrics = context.resources.displayMetrics
        Point(metrics.widthPixels, metrics.heightPixels)
    }
}
