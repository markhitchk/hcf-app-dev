package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.DateFormat
import java.util.Date
import java.util.WeakHashMap

/**
 * Keeps local notification history inside App Settings > Notifications instead of
 * sending users to the legacy standalone history activity.
 */
object HcfNotificationHistoryUi {
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"
    private const val HISTORY_PREF = "native_notification_history_json"
    private const val TARGET_HISTORY = "hcf_setting:open_notification_history"
    private const val INLINE_MARKER = "hcf_notification_history_inline"
    private const val CLEAR_TAG = "hcf_notification_history_clear"
    private const val ROUTE_TAG = "hcf_notification_history_route"
    private val OBSERVERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private var registered = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val app = context?.applicationContext as? Application ?: return true
            if (registered) return true
            registered = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = install(activity)
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = install(activity)
                override fun onActivityPaused(activity: Activity) = removeObserver(activity)
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = removeObserver(activity)
            })
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun install(activity: Activity?) {
        if (!isSettingsActivity(activity) || activity!!.isFinishing) return
        enhance(activity)
        synchronized(OBSERVERS) {
            if (OBSERVERS.containsKey(activity)) return
            val root = activity.window?.decorView ?: return
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!activity.isFinishing && !activity.isDestroyed) enhance(activity)
            }
            observer.addOnGlobalLayoutListener(listener)
            OBSERVERS[activity] = listener
        }
    }

    private fun removeObserver(activity: Activity?) {
        if (activity == null) return
        val listener = synchronized(OBSERVERS) { OBSERVERS.remove(activity) } ?: return
        val root = activity.window?.decorView ?: return
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
    }

    private fun enhance(activity: Activity) {
        val content = readViewGroupField(activity, "settingsContent") ?: return
        val historyPanel = findConnectedPanel(content, "Notification History")
        if (historyPanel != null && historyPanel.childCount == 2) {
            (historyPanel.getChildAt(1) as? ViewGroup)?.let { embedHistory(activity, it) }
        }
        rerouteStandaloneButtons(activity, content, historyPanel)
    }

    private fun embedHistory(activity: Activity, body: ViewGroup) {
        if (findContentDescription(body, INLINE_MARKER) != null) return
        val openButton = findButton(body, "Open Notification History") ?: return
        val parent = openButton.parent as? ViewGroup ?: return

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = INLINE_MARKER
            tag = TARGET_HISTORY
        }
        val buttonIndex = parent.indexOfChild(openButton)
        parent.addView(
            list,
            minOf(parent.childCount, buttonIndex + 1),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(activity, 8)
                bottomMargin = dp(activity, 6)
            }
        )

        openButton.tag = CLEAR_TAG
        openButton.text = "Clear history"
        openButton.contentDescription = "Clear notification history"
        openButton.setOnClickListener { view ->
            val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            prefs.edit().remove(HISTORY_PREF).apply()
            try { HcfWidget.refreshAll(activity) } catch (_: Throwable) {}
            renderHistory(activity, list, view as Button)
        }
        renderHistory(activity, list, openButton)
    }

    private fun renderHistory(activity: Activity?, list: LinearLayout?, clear: Button?) {
        if (activity == null || list == null) return
        list.removeAllViews()
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val mode = HcfWidget.historyMode(prefs)
        val history = parseHistory(prefs.getString(HISTORY_PREF, "[]"))

        list.addView(
            text(activity, "Recent notifications", 12, activity.getColor(R.color.hcf_cyan), true).apply {
                setPadding(dp(activity, 3), dp(activity, 3), 0, dp(activity, 5))
            },
            matchWrap()
        )

        val hasHistory = history.length() > 0
        clear?.apply {
            isEnabled = hasHistory
            alpha = if (hasHistory) 1f else 0.55f
        }

        if (!hasHistory) {
            val emptyText = if (HcfWidget.HISTORY_MODE_OFF == mode) {
                "Notification history is turned off."
            } else {
                "No notification history yet."
            }
            list.addView(
                text(activity, emptyText, 12, activity.getColor(R.color.hcf_muted), false).apply {
                    setBackgroundResource(R.drawable.quick_action_background)
                    setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
                },
                matchWrap()
            )
            return
        }

        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val url = safe(item.optString("url", ""))
            val title = safe(item.optString("title", "Harley's Clan Forum"))
            val body = safe(item.optString("body", ""))
            val time = item.optLong("time", 0L)

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.quick_action_background)
                setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
            }
            card.addView(
                text(
                    activity,
                    if (TextUtils.isEmpty(title)) "Harley's Clan Forum" else title,
                    14,
                    activity.getColor(R.color.hcf_text),
                    true
                ),
                matchWrap()
            )

            if (!TextUtils.isEmpty(body)) {
                card.addView(
                    text(activity, body, 11, activity.getColor(R.color.hcf_muted), false),
                    matchWrap().apply { topMargin = dp(activity, 3) }
                )
            }

            if (time > 0L) {
                card.addView(
                    text(
                        activity,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(time)),
                        10,
                        activity.getColor(R.color.hcf_meta),
                        false
                    ),
                    matchWrap().apply { topMargin = dp(activity, 5) }
                )
            }

            if (!TextUtils.isEmpty(url)) {
                card.addView(
                    text(activity, "Tap to open", 10, activity.getColor(R.color.hcf_cyan), true),
                    matchWrap().apply { topMargin = dp(activity, 5) }
                )
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    activity.startActivity(
                        Intent(activity, HcfWidget.RouteActivity::class.java).apply {
                            data = Uri.parse(url)
                        }
                    )
                }
            }

            list.addView(card, matchWrap().apply { topMargin = dp(activity, 7) })
        }
    }

    private fun rerouteStandaloneButtons(activity: Activity, root: View?, historyPanel: ViewGroup?) {
        if (root == null) return
        if (root is Button) {
            val label = root.text
            if (label != null && "Open Notification History".equals(label.toString().trim(), true)) {
                if (historyPanel != null && isDescendantOf(root, historyPanel)) return
                if (ROUTE_TAG == root.tag) return
                root.tag = ROUTE_TAG
                root.text = "Notification History"
                root.contentDescription = "Open notification history settings"
                root.setOnClickListener { navigateToInlineHistory(activity) }
            }
        }
        val group = root as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            rerouteStandaloneButtons(activity, group.getChildAt(i), historyPanel)
        }
    }

    private fun navigateToInlineHistory(activity: Activity) {
        try {
            findMethod(activity.javaClass, "navigateToSettingKey", String::class.java)?.let {
                it.isAccessible = true
                it.invoke(activity, "open_notification_history")
                return
            }
        } catch (_: Throwable) {}

        try {
            writeStringField(activity, "pendingSettingKey", "open_notification_history")
            writeStringField(activity, "pendingSettingSection", "notification_history")
            findMethod(activity.javaClass, "showSettingsSection", String::class.java)?.let {
                it.isAccessible = true
                it.invoke(activity, "notifications")
            }
        } catch (_: Throwable) {}
    }

    private fun findConnectedPanel(content: ViewGroup?, title: String): ViewGroup? {
        if (content == null) return null
        for (i in 0 until content.childCount) {
            val panel = content.getChildAt(i) as? ViewGroup ?: continue
            if (panel.childCount != 2) continue
            val header = panel.getChildAt(0)
            val body = panel.getChildAt(1)
            if (header !is LinearLayout || body !is LinearLayout) continue
            if (containsText(header, title)) return panel
        }
        return null
    }

    private fun containsText(root: View?, expected: String?): Boolean {
        if (root == null || expected == null) return false
        if (root is TextView && expected.equals(root.text?.toString()?.trim(), true)) return true
        val group = root as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            if (containsText(group.getChildAt(i), expected)) return true
        }
        return false
    }

    private fun findButton(root: View?, label: String): Button? {
        if (root == null) return null
        if (root is Button && label.equals(root.text?.toString()?.trim(), true)) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findButton(group.getChildAt(i), label)?.let { return it }
        }
        return null
    }

    private fun findContentDescription(root: View?, value: String?): View? {
        if (root == null || value == null) return null
        if (value == root.contentDescription?.toString()) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findContentDescription(group.getChildAt(i), value)?.let { return it }
        }
        return null
    }

    private fun isDescendantOf(child: View, ancestor: ViewGroup): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun parseHistory(raw: String?): JSONArray = try {
        JSONArray(if (TextUtils.isEmpty(raw)) "[]" else raw)
    } catch (_: Throwable) {
        JSONArray()
    }

    private fun text(activity: Activity, value: String, sp: Int, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            text = value
            textSize = sp.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(context: Context?, value: Int): Int =
        if (context == null) value else Math.round(value * context.resources.displayMetrics.density)

    private fun isSettingsActivity(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun readViewGroupField(activity: Activity, fieldName: String): ViewGroup? =
        readField(activity, fieldName) as? ViewGroup

    private fun readField(activity: Activity?, fieldName: String?): Any? {
        if (activity == null || fieldName == null) return null
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(activity)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun writeStringField(activity: Activity?, fieldName: String?, value: String?) {
        if (activity == null || fieldName == null) return
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(activity, value ?: "")
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }

    private fun findMethod(type: Class<*>?, name: String, vararg params: Class<*>): Method? {
        var current = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *params)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun safe(value: String?): String = value?.trim().orEmpty()
}
