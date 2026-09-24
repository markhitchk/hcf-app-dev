package com.harleytg.forum.dev

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * Single motion policy for every native HCF animation path.
 *
 * The CI motion-normalizer routes explicit ViewPropertyAnimator, ObjectAnimator,
 * ValueAnimator and AnimatorSet durations/interpolators through this class. The
 * runtime provider also applies the shared window transition style to every native
 * Activity. The forum WebView remains a separate web surface and is intentionally
 * not modified here.
 */
object HcfMotionSystem {
    const val MARKER: String = "HCF_MOTION_SYSTEM_V4_ALL_NATIVE"

    private val STANDARD: TimeInterpolator = PathInterpolator(0.20f, 0.0f, 0.20f, 1.0f)
    private val EMPHASIZED: TimeInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)
    private val ACCELERATE: TimeInterpolator = PathInterpolator(0.30f, 0.0f, 0.80f, 0.15f)
    private val LINEAR: TimeInterpolator = LinearInterpolator()

    @Volatile
    private var appContext: Context? = null
    private var registered = false

    @JvmStatic
    fun duration(baseMs: Long): Long {
        if (baseMs <= 0L) return 0L
        if (!systemAnimatorsEnabled()) return 1L
        val context = appContext ?: return clampDuration(baseMs)
        val prefs = context.getSharedPreferences("hcf_app", 0)
        var resolved = PerformanceProfile.motionDuration(context, prefs, baseMs)
        if (resolved <= 0L) return 1L
        if (powerSaver(context)) resolved = Math.round(resolved * 0.78)
        if (lowRam(context)) resolved = Math.round(resolved * 0.86)
        return clampDuration(resolved)
    }

    @JvmStatic
    fun delay(baseMs: Long): Long {
        if (baseMs <= 0L || !systemAnimatorsEnabled() || performanceMotionDisabled()) return 0L
        val context = appContext
        var result = baseMs
        if (context != null) {
            if (powerSaver(context)) result = Math.round(result * 0.70)
            if (lowRam(context)) result = Math.round(result * 0.80)
        }
        return maxOf(0L, minOf(result, 240L))
    }

    @JvmStatic
    fun repeatCount(requested: Int): Int {
        if (requested >= 0) return requested
        if (!systemAnimatorsEnabled() || performanceMotionDisabled()) return 0
        val context = appContext
        if (context != null && (powerSaver(context) || lowRam(context))) return 0
        return requested
    }

    @JvmStatic fun standard(): TimeInterpolator = STANDARD
    @JvmStatic fun decelerate(): TimeInterpolator = EMPHASIZED
    @JvmStatic fun emphasized(): TimeInterpolator = EMPHASIZED
    @JvmStatic fun accelerate(): TimeInterpolator = ACCELERATE
    @JvmStatic fun linear(): TimeInterpolator = LINEAR

    @JvmStatic
    fun systemAnimatorsEnabled(): Boolean = try {
        ValueAnimator.areAnimatorsEnabled()
    } catch (_: Throwable) {
        true
    }

    @JvmStatic
    fun fullMotionEnabled(): Boolean = systemAnimatorsEnabled() && !performanceMotionDisabled()

    @JvmStatic
    fun configureWindow(activity: Activity?) {
        if (activity?.window == null) return
        try {
            val window = activity.window
            val params = window.attributes
            params.windowAnimations = if (fullMotionEnabled()) R.style.HcfWindowAnimation else 0
            window.attributes = params
        } catch (_: Throwable) {
        }
    }

    private fun performanceMotionDisabled(): Boolean {
        val context = appContext ?: return false
        return try {
            val prefs = context.getSharedPreferences("hcf_app", 0)
            PerformanceProfile.PERFORMANCE == PerformanceProfile.resolve(context, prefs)
        } catch (_: Throwable) {
            false
        }
    }

    private fun powerSaver(context: Context): Boolean = try {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isPowerSaveMode == true
    } catch (_: Throwable) {
        false
    }

    private fun lowRam(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true
    } catch (_: Throwable) {
        false
    }

    private fun clampDuration(value: Long): Long = maxOf(1L, minOf(value, 2600L))

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val context = context ?: return true
            appContext = context.applicationContext
            val application = appContext as? Application ?: return true
            if (registered) return true
            registered = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = configureWindow(activity)
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = configureWindow(activity)
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
