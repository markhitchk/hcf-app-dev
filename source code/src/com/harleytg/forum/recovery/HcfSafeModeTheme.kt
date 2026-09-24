package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View

/** Applies the normal HCF appearance resolver to the crash-recovery activity. */
object HcfSafeModeTheme {
    private const val UI_MODE_NIGHT_MASK = 0x30
    private const val UI_MODE_NIGHT_NO = 0x10
    private const val UI_MODE_NIGHT_YES = 0x20
    private const val EXTRA_RECREATED = "hcf_recovery_theme_recreated"

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val context = context ?: return true
            val app = context.applicationContext as? Application ?: return true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                    if (activity is HcfSafeMode.SafeModeActivity) applyBeforeDraw(activity)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (activity !is HcfSafeMode.SafeModeActivity) return
                    if (ensureResolvedTheme(activity)) return
                    postSystemBarRefresh(activity)
                }
                override fun onActivityResumed(activity: Activity) {
                    if (activity !is HcfSafeMode.SafeModeActivity) return
                    if (ensureResolvedTheme(activity)) return
                    postSystemBarRefresh(activity)
                }
                override fun onActivityStarted(activity: Activity) = Unit
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

    private fun applyBeforeDraw(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        applyNightConfiguration(activity, desiredNightMode(activity))
        try { ThemeManager.apply(activity) } catch (_: Throwable) {}
    }

    private fun ensureResolvedTheme(activity: Activity?): Boolean {
        if (activity == null || activity.isFinishing) return false
        val desired = desiredNightMode(activity)
        val current = activity.resources.configuration.uiMode and UI_MODE_NIGHT_MASK
        if (current == desired) {
            try { ThemeManager.apply(activity) } catch (_: Throwable) {}
            return false
        }
        applyNightConfiguration(activity, desired)
        try { ThemeManager.apply(activity) } catch (_: Throwable) {}
        val intent = activity.intent
        val alreadyRecreated = intent?.getBooleanExtra(EXTRA_RECREATED, false) == true
        if (!alreadyRecreated) {
            intent?.putExtra(EXTRA_RECREATED, true)
            val decor: View? = activity.window?.decorView
            if (decor != null) decor.post(activity::recreate) else activity.recreate()
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun applyNightConfiguration(activity: Activity, desiredNight: Int) {
        try {
            val resources = activity.resources
            val configuration = Configuration(resources.configuration)
            configuration.uiMode = (configuration.uiMode and UI_MODE_NIGHT_MASK.inv()) or desiredNight
            resources.updateConfiguration(configuration, resources.displayMetrics)
        } catch (_: Throwable) {}
    }

    private fun desiredNightMode(context: Context): Int {
        val mode = try { ThemeManager.mode(context) } catch (_: Throwable) { ThemeManager.DARK }
        if (ThemeManager.DARK == mode || ThemeManager.AMOLED == mode) return UI_MODE_NIGHT_YES
        if (ThemeManager.LIGHT == mode) return UI_MODE_NIGHT_NO
        if (ThemeManager.AUTO_PHONE == mode) return phoneSystemNightMode()
        try {
            when (ThemeManager.forumAutoTheme(context)) {
                "dark" -> return UI_MODE_NIGHT_YES
                "light" -> return UI_MODE_NIGHT_NO
            }
        } catch (_: Throwable) {}
        return phoneSystemNightMode()
    }

    private fun phoneSystemNightMode(): Int = try {
        val system = Resources.getSystem().configuration.uiMode and UI_MODE_NIGHT_MASK
        if (system == UI_MODE_NIGHT_YES) UI_MODE_NIGHT_YES else UI_MODE_NIGHT_NO
    } catch (_: Throwable) {
        UI_MODE_NIGHT_NO
    }

    private fun postSystemBarRefresh(activity: Activity?) {
        val decor = activity?.window?.decorView ?: return
        decor.post {
            try {
                ThemeManager.applySystemBars(activity)
                AppLogger.info(
                    activity,
                    "recovery_theme",
                    ThemeManager.label(activity) + " | " + ThemeManager.webColorScheme(activity)
                )
            } catch (_: Throwable) {}
        }
    }
}
