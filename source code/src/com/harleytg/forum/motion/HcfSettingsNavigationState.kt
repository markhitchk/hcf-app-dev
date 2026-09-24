package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Preserves the exact App Settings location across Activity recreation. */
object HcfSettingsNavigationState {
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"
    private const val STATE_SECTION = "hcf.settings.navigation.section.v1"
    private const val STATE_OPEN_PANEL = "hcf.settings.navigation.open_panel.v1"
    private const val STATE_PENDING_KEY = "hcf.settings.navigation.pending_key.v1"

    private val PENDING = WeakHashMap<Activity, RestoreState>()
    private var registered = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val app = context?.applicationContext as? Application ?: return true
            if (registered) return true
            registered = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (!isSettings(activity) || state == null) return
                    val section = clean(state.getString(STATE_SECTION))
                    if (section.isEmpty()) return
                    val restore = RestoreState(
                        section,
                        clean(state.getString(STATE_OPEN_PANEL)),
                        clean(state.getString(STATE_PENDING_KEY))
                    )
                    synchronized(PENDING) { PENDING[activity] = restore }
                    scheduleRestore(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    if (!isSettings(activity)) return
                    synchronized(PENDING) {
                        if (!PENDING.containsKey(activity)) return
                    }
                    scheduleRestore(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                    if (!isSettings(activity)) return
                    val section = clean(readStringField(activity, "currentSettingsSection"))
                    if (section.isEmpty()) return
                    outState.putString(STATE_SECTION, section)
                    val pendingKey = clean(readStringField(activity, "pendingSettingKey"))
                    if (pendingKey.isNotEmpty()) outState.putString(STATE_PENDING_KEY, pendingKey)
                    val openTitle = findOpenPanelTitle(readViewGroupField(activity, "settingsContent"))
                    if (openTitle.isNotEmpty()) outState.putString(STATE_OPEN_PANEL, openTitle)
                }

                override fun onActivityDestroyed(activity: Activity) {
                    synchronized(PENDING) { PENDING.remove(activity) }
                }
            })
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun scheduleRestore(activity: Activity?) {
        val root = activity?.window?.decorView ?: return
        root.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val state = synchronized(PENDING) { PENDING[activity] } ?: return@post
            val current = clean(readStringField(activity, "currentSettingsSection"))
            if (state.section != current) {
                if (state.pendingKey.isNotEmpty()) {
                    writeStringField(activity, "pendingSettingKey", state.pendingKey)
                }
                invokeShowSettingsSection(activity, state.section)
            }
            root.postOnAnimation {
                root.postOnAnimation { restoreOpenPanel(activity) }
            }
        }
    }

    private fun restoreOpenPanel(activity: Activity) {
        val state = synchronized(PENDING) { PENDING.remove(activity) } ?: return
        if (state.openPanelTitle.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val panel = findPanelByTitle(
            readViewGroupField(activity, "settingsContent"),
            state.openPanelTitle
        ) ?: return
        if (panel.childCount < 2) return
        val header = panel.getChildAt(0)
        val body = panel.getChildAt(1)
        if (body.visibility != View.VISIBLE && header.isClickable) header.performClick()
    }

    private fun invokeShowSettingsSection(activity: Activity, section: String) {
        try {
            val method: Method = activity.javaClass.getDeclaredMethod("showSettingsSection", String::class.java)
            method.isAccessible = true
            method.invoke(activity, section)
        } catch (_: Throwable) {}
    }

    private fun findOpenPanelTitle(content: ViewGroup?): String {
        if (content == null) return ""
        for (i in 0 until content.childCount) {
            val panel = content.getChildAt(i) as? ViewGroup ?: continue
            if (panel.childCount < 2) continue
            if (panel.getChildAt(1).visibility != View.VISIBLE) continue
            val title = firstMeaningfulText(panel.getChildAt(0))
            if (title.isNotEmpty()) return title
        }
        return ""
    }

    private fun findPanelByTitle(content: ViewGroup?, wanted: String?): ViewGroup? {
        if (content == null || wanted.isNullOrEmpty()) return null
        for (i in 0 until content.childCount) {
            val panel = content.getChildAt(i) as? ViewGroup ?: continue
            if (panel.childCount < 2) continue
            if (wanted == firstMeaningfulText(panel.getChildAt(0))) return panel
        }
        return null
    }

    private fun firstMeaningfulText(view: View?): String {
        if (view == null) return ""
        if (view is TextView) {
            val value = clean(view.text?.toString())
            if (value.isNotEmpty() && value != "›" && value != "⌄") return value
        }
        val group = view as? ViewGroup ?: return ""
        for (i in 0 until group.childCount) {
            val value = firstMeaningfulText(group.getChildAt(i))
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun readViewGroupField(activity: Activity, name: String): ViewGroup? =
        readViewField(activity, name) as? ViewGroup

    private fun readViewField(activity: Activity, name: String): View? =
        readField(activity, name) as? View

    private fun readStringField(activity: Activity, name: String): String =
        readField(activity, name) as? String ?: ""

    private fun readField(activity: Activity?, name: String): Any? {
        if (activity == null) return null
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(activity)
            } catch (_: Throwable) {
                type = type.superclass
            }
        }
        return null
    }

    private fun writeStringField(activity: Activity?, name: String, value: String?) {
        if (activity == null) return
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(name)
                field.isAccessible = true
                field.set(activity, value ?: "")
                return
            } catch (_: Throwable) {
                type = type.superclass
            }
        }
    }

    private fun isSettings(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun clean(value: String?): String = value?.trim().orEmpty()

    private class RestoreState(section: String?, openPanelTitle: String?, pendingKey: String?) {
        val section = clean(section)
        val openPanelTitle = clean(openPanelTitle)
        val pendingKey = clean(pendingKey)
    }
}
