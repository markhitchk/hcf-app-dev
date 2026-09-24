package com.harleytg.forum.dev

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

object HcfWidget {
    private const val ACTION_RELOAD = "com.harleytg.forum.dev.action.HCF_WIDGET_RELOAD"
    private const val ACTION_SCHEDULED_REFRESH = "com.harleytg.forum.dev.action.HCF_WIDGET_SCHEDULED_REFRESH"
    private const val ACTION_NOTIFICATION_EVENT = "com.harleytg.forum.dev.NOTIFICATION_EVENT"
    private const val EXTRA_EVENT_TITLE = "event_title"
    private const val EXTRA_EVENT_BODY = "event_body"
    private const val EXTRA_EVENT_URL = "event_url"
    private const val EXTRA_EVENT_COUNT = "event_count"

    const val EXTRA_WIDGET_TARGET = "com.harleytg.forum.dev.extra.HCF_WIDGET_TARGET"
    const val TARGET_FORUM = "forum"
    const val TARGET_NOTIFICATIONS = "notifications"
    @JvmField val PREF_SHOW_CONNECTED_USERNAME: String = AppPrefs.WIDGET_SHOW_CONNECTED_USERNAME
    @JvmField val PREF_SHOW_UNREAD_COUNT: String = AppPrefs.WIDGET_SHOW_UNREAD_COUNT
    @JvmField val PREF_COMPACT_MODE: String = AppPrefs.WIDGET_COMPACT_MODE
    @JvmField val PREF_SHOW_LAST_UPDATED: String = AppPrefs.WIDGET_SHOW_LAST_UPDATED
    @JvmField val PREF_DEFAULT_TAP_ACTION: String = AppPrefs.WIDGET_DEFAULT_TAP_ACTION
    const val PREF_LAST_REALTIME_SYNC_MS = "widget_last_realtime_sync_ms"
    const val PREF_BACKGROUND_ALPHA = "widget_background_alpha"
    const val PREF_TEXT_SIZE_SP = "widget_text_size_sp"
    const val PREF_REFRESH_INTERVAL_MIN = "widget_refresh_interval_min"
    const val PREF_SHOW_LAST_NOTIFICATION_PREVIEW = "widget_show_last_notification_preview"
    const val PREF_HISTORY_MODE = "native_notification_history_mode"
    const val PREF_HISTORY_LIMIT = "native_notification_history_limit"
    const val HISTORY_MODE_OFF = "off"
    const val HISTORY_MODE_TITLE = "title"
    const val HISTORY_MODE_FULL = "full"
    private const val PREF_HISTORY_JSON = "native_notification_history_json"
    private const val PREF_LAST_TITLE = "widget_last_notification_title"
    private const val PREF_LAST_BODY = "widget_last_notification_body"
    private const val PREF_LAST_URL = "widget_last_notification_url"
    private const val PREF_LAST_EVENT_MS = "widget_last_notification_event_ms"

    const val TAP_FORUM = "forum"
    const val TAP_NOTIFICATIONS = "notifications"
    const val TAP_SETTINGS = "settings"
    const val TAP_LATEST = "latest"
    const val TAP_PROFILE = "profile"
    private const val ROUTE_EXTRA = "hcf_native_route"
    private const val ROUTE_LATEST = "latest"
    private const val ROUTE_PROFILE = "profile"

    private const val HISTORY_LIMIT = 60
    private const val REQUEST_OPEN_BODY = 42100
    private const val REQUEST_OPEN_FORUM = 42101
    private const val REQUEST_OPEN_NOTIFICATIONS = 42102
    private const val REQUEST_RELOAD = 42103
    private const val REQUEST_SETTINGS = 42104
    private const val REQUEST_LATEST = 42105
    private const val REQUEST_PROFILE = 42106
    private const val REQUEST_SCHEDULED_REFRESH = 42108
    private const val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private var monitoredPreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    class NotificationsProvider : AppWidgetProvider() {
        override fun onUpdate(context: Context?, manager: AppWidgetManager?, appWidgetIds: IntArray?) {
            if (context == null || manager == null || appWidgetIds == null) return
            appWidgetIds.forEach { updateWidget(context, manager, it, false) }
            scheduleAutomaticRefresh(context)
        }
        override fun onEnabled(context: Context) {
            super.onEnabled(context)
            scheduleAutomaticRefresh(context)
        }
        override fun onDisabled(context: Context) {
            super.onDisabled(context)
            cancelAutomaticRefreshIfNoWidgets(context)
        }
        override fun onAppWidgetOptionsChanged(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle
        ) {
            super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
            updateWidget(context, manager, appWidgetId, false)
        }
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_RELOAD == intent.action) {
                forceRefresh(context, "manual")
                return
            }
            super.onReceive(context, intent)
        }
    }

    class UnreadProvider : AppWidgetProvider() {
        override fun onUpdate(context: Context?, manager: AppWidgetManager?, appWidgetIds: IntArray?) {
            if (context == null || manager == null || appWidgetIds == null) return
            appWidgetIds.forEach { updateWidget(context, manager, it, true) }
            scheduleAutomaticRefresh(context)
        }
        override fun onEnabled(context: Context) {
            super.onEnabled(context)
            scheduleAutomaticRefresh(context)
        }
        override fun onDisabled(context: Context) {
            super.onDisabled(context)
            cancelAutomaticRefreshIfNoWidgets(context)
        }
        override fun onAppWidgetOptionsChanged(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle
        ) {
            super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
            updateWidget(context, manager, appWidgetId, true)
        }
    }

    class RefreshReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null || ACTION_SCHEDULED_REFRESH != intent.action) return
            forceRefresh(context, "scheduled")
        }
    }

    class NotificationEventReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null || ACTION_NOTIFICATION_EVENT != intent.action) return
            val title = safe(intent.getStringExtra(EXTRA_EVENT_TITLE))
            val body = safe(intent.getStringExtra(EXTRA_EVENT_BODY))
            val url = safe(intent.getStringExtra(EXTRA_EVENT_URL))
            val count = intent.getIntExtra(EXTRA_EVENT_COUNT, -1)
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            val mode = historyMode(prefs)
            val limit = historyLimit(prefs)
            val editor = prefs.edit()
                .putString(PREF_LAST_TITLE, title)
                .putString(PREF_LAST_BODY, body)
                .putString(PREF_LAST_URL, url)
                .putLong(PREF_LAST_EVENT_MS, now)

            if (HISTORY_MODE_OFF == mode) {
                editor.remove(PREF_HISTORY_JSON)
            } else {
                val history = parseHistory(prefs.getString(PREF_HISTORY_JSON, "[]"))
                val next = JSONArray()
                try {
                    next.put(historyItem(title, body, url, count, now, mode))
                    for (i in 0 until history.length()) {
                        if (next.length() >= limit) break
                        val existing = history.optJSONObject(i) ?: continue
                        next.put(historyItem(
                            existing.optString("title", ""),
                            existing.optString("body", ""),
                            existing.optString("url", ""),
                            existing.optInt("count", -1),
                            existing.optLong("time", 0L),
                            mode
                        ))
                    }
                    editor.putString(PREF_HISTORY_JSON, next.toString())
                } catch (_: Throwable) {}
            }
            editor.apply()
            refreshAll(context)
        }
    }

    class SettingsActivity : Activity() {
        private lateinit var prefs: SharedPreferences
        private lateinit var alphaValue: TextView
        private lateinit var sizeValue: TextView
        private lateinit var refreshStatus: TextView

        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            prefs = getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            title = "Home-screen Widget"
            setContentView(buildUi())
        }

        private fun buildUi(): View {
            val scroll = ScrollView(this)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(28))
                setBackgroundColor(Color.rgb(13, 16, 20))
            }
            scroll.addView(root, ScrollView.LayoutParams(-1, -2))
            root.addView(label("Home-screen Widget", 22, true), matchWrap())
            root.addView(label(
                "Appearance, refresh, preview and tap behavior for HCF widgets.",
                13,
                false
            ).apply { setTextColor(Color.rgb(174, 187, 194)) }, spaced(4))

            root.addView(section("Background transparency"), spaced(18))
            alphaValue = label("", 13, false).apply { setTextColor(Color.rgb(174, 187, 194)) }
            root.addView(alphaValue, spaced(2))
            val alpha = SeekBar(this).apply {
                max = 80
                val current = clamp(prefs.getInt(PREF_BACKGROUND_ALPHA, 96), 20, 100)
                progress = current - 20
                updateAlphaText(current)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + 20
                        updateAlphaText(value)
                        if (fromUser) prefs.edit().putInt(PREF_BACKGROUND_ALPHA, value).apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        refreshAll(this@SettingsActivity)
                    }
                })
            }
            root.addView(alpha, matchWrap())

            root.addView(section("Widget text size"), spaced(16))
            sizeValue = label("", 13, false).apply { setTextColor(Color.rgb(174, 187, 194)) }
            root.addView(sizeValue, spaced(2))
            val size = SeekBar(this).apply {
                max = 8
                val current = clamp(prefs.getInt(PREF_TEXT_SIZE_SP, 12), 10, 18)
                progress = current - 10
                updateSizeText(current)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + 10
                        updateSizeText(value)
                        if (fromUser) prefs.edit().putInt(PREF_TEXT_SIZE_SP, value).apply()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        refreshAll(this@SettingsActivity)
                    }
                })
            }
            root.addView(size, matchWrap())

            val preview = checkbox(
                "Show last notification preview",
                prefs.getBoolean(PREF_SHOW_LAST_NOTIFICATION_PREVIEW, true)
            )
            root.addView(preview, spaced(14))
            preview.setOnClickListener {
                prefs.edit().putBoolean(PREF_SHOW_LAST_NOTIFICATION_PREVIEW, preview.isChecked).apply()
                refreshAll(this)
            }

            root.addView(section("Notification history privacy"), spaced(16))
            root.addView(label(
                "Choose what HCF keeps in the local notification history. Widget preview storage is controlled separately above.",
                12,
                false
            ).apply { setTextColor(Color.rgb(174, 187, 194)) }, spaced(2))

            val historyModes = arrayOf(HISTORY_MODE_OFF, HISTORY_MODE_TITLE, HISTORY_MODE_FULL)
            val historyNames = arrayOf("Off", "Titles only", "Titles + message")
            val historyGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val selectedMode = historyMode(prefs)
            historyModes.indices.forEach { i ->
                historyGroup.addView(RadioButton(this).apply {
                    id = 47200 + i
                    text = historyNames[i]
                    setTextColor(Color.rgb(232, 248, 255))
                    tag = historyModes[i]
                    isChecked = historyModes[i] == selectedMode
                })
            }
            root.addView(historyGroup, matchWrap())
            historyGroup.setOnCheckedChangeListener { group, checkedId ->
                val mode = group.findViewById<View>(checkedId)?.tag as? String
                    ?: return@setOnCheckedChangeListener
                setHistoryPrivacy(prefs, mode, historyLimit(prefs))
            }

            root.addView(label("History retention", 14, true).apply {
                setTextColor(Color.rgb(0, 184, 240))
            }, spaced(12))
            val retentionValues = intArrayOf(10, 30, 60)
            val retentionNames = arrayOf("Keep 10 events", "Keep 30 events", "Keep 60 events")
            val retentionGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val selectedRetention = historyLimit(prefs)
            retentionValues.indices.forEach { i ->
                retentionGroup.addView(RadioButton(this).apply {
                    id = 47300 + i
                    text = retentionNames[i]
                    setTextColor(Color.rgb(232, 248, 255))
                    tag = retentionValues[i]
                    isChecked = retentionValues[i] == selectedRetention
                })
            }
            root.addView(retentionGroup, matchWrap())
            retentionGroup.setOnCheckedChangeListener { group, checkedId ->
                val limit = group.findViewById<View>(checkedId)?.tag as? Int
                    ?: return@setOnCheckedChangeListener
                setHistoryPrivacy(prefs, historyMode(prefs), limit)
            }

            root.addView(section("Automatic widget refresh"), spaced(16))
            refreshStatus = label(refreshStatusText(), 13, false).apply {
                setTextColor(Color.rgb(174, 187, 194))
            }
            root.addView(refreshStatus, spaced(2))
            val intervals = intArrayOf(0, 15, 30, 60, 120)
            val intervalNames = arrayOf(
                "Off", "Every 15 minutes", "Every 30 minutes", "Every hour", "Every 2 hours"
            )
            val refreshGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val selectedInterval = prefs.getInt(PREF_REFRESH_INTERVAL_MIN, 30)
            intervals.indices.forEach { i ->
                refreshGroup.addView(RadioButton(this).apply {
                    id = 47000 + i
                    text = intervalNames[i]
                    setTextColor(Color.rgb(232, 248, 255))
                    tag = intervals[i]
                    isChecked = intervals[i] == selectedInterval
                })
            }
            root.addView(refreshGroup, matchWrap())
            refreshGroup.setOnCheckedChangeListener { group, checkedId ->
                val minutes = group.findViewById<View>(checkedId)?.tag as? Int
                    ?: return@setOnCheckedChangeListener
                prefs.edit().putInt(PREF_REFRESH_INTERVAL_MIN, minutes).apply()
                scheduleAutomaticRefresh(this)
                refreshAll(this)
                refreshStatus.text = refreshStatusText()
            }

            root.addView(section("Default widget tap action"), spaced(16))
            val tapValues = arrayOf(TAP_FORUM, TAP_NOTIFICATIONS, TAP_LATEST, TAP_PROFILE, TAP_SETTINGS)
            val tapNames = arrayOf(
                "Forum home", "Notifications", "Latest Discussions", "Profile", "Widget settings"
            )
            val tapGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val selectedTap = prefs.getString(PREF_DEFAULT_TAP_ACTION, TAP_FORUM)
            tapValues.indices.forEach { i ->
                tapGroup.addView(RadioButton(this).apply {
                    id = 47100 + i
                    text = tapNames[i]
                    setTextColor(Color.rgb(232, 248, 255))
                    tag = tapValues[i]
                    isChecked = tapValues[i] == selectedTap
                })
            }
            root.addView(tapGroup, matchWrap())
            tapGroup.setOnCheckedChangeListener { group, checkedId ->
                val action = group.findViewById<View>(checkedId)?.tag as? String
                    ?: return@setOnCheckedChangeListener
                prefs.edit().putString(PREF_DEFAULT_TAP_ACTION, action).apply()
                refreshAll(this)
            }

            root.addView(button("Open notification history").apply {
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, NotificationHistoryActivity::class.java))
                }
            }, spaced(18))
            root.addView(button("Refresh widget now").apply {
                setOnClickListener {
                    forceRefresh(this@SettingsActivity, "settings")
                    refreshStatus.text = refreshStatusText()
                }
            }, spaced(8))
            return scroll
        }

        private fun refreshStatusText(): String {
            val minutes = prefs.getInt(PREF_REFRESH_INTERVAL_MIN, 30)
            val last = prefs.getLong(PREF_LAST_REALTIME_SYNC_MS, 0L)
            val interval = if (minutes <= 0) "Auto refresh off" else "Auto refresh every " + minutes + " min"
            if (last <= 0L) return interval + " • waiting for first sync"
            return interval + " • last sync " +
                android.text.format.DateFormat.getTimeFormat(this).format(Date(last))
        }

        private fun updateAlphaText(value: Int) { alphaValue.text = value.toString() + "% background opacity" }
        private fun updateSizeText(value: Int) { sizeValue.text = value.toString() + " sp base text size" }
        private fun section(text: String) = label(text, 16, true).apply { setTextColor(Color.rgb(0, 184, 240)) }
        private fun label(text: String, sp: Int, bold: Boolean) = TextView(this).apply {
            this.text = text
            textSize = sp.toFloat()
            setTextColor(Color.rgb(232, 248, 255))
            if (bold) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        private fun checkbox(text: String, checked: Boolean) = CheckBox(this).apply {
            this.text = text
            isChecked = checked
            setTextColor(Color.rgb(232, 248, 255))
        }
        private fun button(text: String) = Button(this).apply { isAllCaps = false; this.text = text }
        private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
        private fun spaced(topDp: Int) = matchWrap().apply { topMargin = dp(topDp) }
        private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)
    }

    class NotificationHistoryActivity : Activity() {
        private var list: LinearLayout? = null
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            title = "Notification history"
            setContentView(buildUi())
            renderHistory()
        }
        override fun onResume() { super.onResume(); renderHistory() }

        private fun buildUi(): View {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(18))
                setBackgroundColor(Color.rgb(13, 16, 20))
            }
            root.addView(text("Notification history", 22, true), matchWrap())
            root.addView(text(
                "Recent HCF notification events stored locally on this device.", 13, false
            ).apply { setTextColor(Color.rgb(174, 187, 194)) }, matchWrap().apply { topMargin = dp(4) })
            root.addView(Button(this).apply {
                isAllCaps = false
                text = "Clear history"
                setOnClickListener {
                    getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE).edit()
                        .remove(PREF_HISTORY_JSON).apply()
                    refreshAll(this@NotificationHistoryActivity)
                    renderHistory()
                }
            }, matchWrap().apply { topMargin = dp(10) })
            val scroll = ScrollView(this)
            list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(20))
            }
            scroll.addView(list, ScrollView.LayoutParams(-1, -2))
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            return root
        }

        private fun renderHistory() {
            val target = list ?: return
            target.removeAllViews()
            val prefs = getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            val mode = historyMode(prefs)
            val history = parseHistory(prefs.getString(PREF_HISTORY_JSON, "[]"))
            if (history.length() == 0) {
                target.addView(text(
                    if (HISTORY_MODE_OFF == mode) "Notification history is turned off."
                    else "No notification history yet.", 15, false
                ).apply { setTextColor(Color.rgb(174, 187, 194)) }, matchWrap())
                return
            }
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                val title = item.optString("title", "Harley's Clan Forum")
                val body = item.optString("body", "")
                val time = item.optLong("time", 0L)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(Color.rgb(24, 31, 37))
                    addView(text(if (TextUtils.isEmpty(title)) "Harley's Clan Forum" else title, 15, true), matchWrap())
                }
                if (!TextUtils.isEmpty(body)) {
                    card.addView(text(body, 13, false).apply {
                        setTextColor(Color.rgb(214, 225, 231))
                    }, matchWrap().apply { topMargin = dp(3) })
                }
                if (time > 0L) {
                    card.addView(text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time)),
                        11, false
                    ).apply { setTextColor(Color.rgb(174, 187, 194)) }, matchWrap().apply { topMargin = dp(5) })
                }
                if (!TextUtils.isEmpty(url)) {
                    card.isClickable = true
                    card.setOnClickListener {
                        startActivity(Intent(this, RouteActivity::class.java).apply { data = Uri.parse(url) })
                    }
                }
                target.addView(card, matchWrap().apply { topMargin = dp(8) })
            }
        }

        private fun text(value: String, sp: Int, bold: Boolean) = TextView(this).apply {
            text = value
            setTextColor(Color.rgb(232, 248, 255))
            textSize = sp.toFloat()
            if (bold) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
        private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)
    }

    class RouteActivity : Activity() {
        override fun onCreate(state: Bundle?) { super.onCreate(state); route(intent); finish() }
        override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); route(intent); finish() }

        private fun route(source: Intent?) {
            val direct = source?.data?.toString().orEmpty()
            if (direct.startsWith("http://") || direct.startsWith("https://")) {
                openForumUri(Uri.parse(direct))
                return
            }
            val route = safe(source?.getStringExtra(ROUTE_EXTRA))
            val prefs = getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            var host = prefs.getString("active_host", "forum.harleytg.com")
            if (host.isNullOrEmpty()) host = "forum.harleytg.com"
            var path = "/"
            if (ROUTE_PROFILE == route) {
                val username = safe(prefs.getString(AppPrefs.IDENTITY_USERNAME, "")).trim()
                path = if (TextUtils.isEmpty(username)) "/settings" else "/u/" + Uri.encode(stripAt(username))
            }
            openForumUri(Uri.parse("https://" + host + path))
        }

        private fun openForumUri(uri: Uri) {
            try {
                startActivity(Intent(this, HcfSafeMode.EntryActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            } catch (_: Throwable) {
                try {
                    startActivity(Intent(this, HcfForum.MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = uri
                    })
                } catch (_: Throwable) {}
            }
        }
    }

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            installPreferenceRefresh(context)
            scheduleAutomaticRefresh(context)
            return true
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Synchronized
    private fun installPreferenceRefresh(context: Context?) {
        if (context == null || preferenceListener != null) return
        val app = context.applicationContext ?: context
        monitoredPreferences = app.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == AppPrefs.LAST_NOTIFICATION_COUNT ||
                key == AppPrefs.SESSION_USER_ID ||
                key == AppPrefs.IDENTITY_USERNAME ||
                key == PREF_SHOW_CONNECTED_USERNAME ||
                key == PREF_SHOW_UNREAD_COUNT ||
                key == PREF_COMPACT_MODE ||
                key == PREF_SHOW_LAST_UPDATED ||
                key == PREF_DEFAULT_TAP_ACTION ||
                key == PREF_LAST_REALTIME_SYNC_MS ||
                key == PREF_BACKGROUND_ALPHA ||
                key == PREF_TEXT_SIZE_SP ||
                key == PREF_SHOW_LAST_NOTIFICATION_PREVIEW ||
                key == PREF_LAST_TITLE ||
                key == PREF_LAST_BODY ||
                key == PREF_LAST_EVENT_MS ||
                key == AppPrefs.WIDGET_FOLLOW_APP_THEME ||
                key == AppPrefs.APP_THEME ||
                key == AppPrefs.FORUM_AUTO_THEME
            ) refreshAll(app)
        }
        monitoredPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    @JvmStatic
    fun refreshAll(context: Context?) {
        if (context == null) return
        val app = context.applicationContext ?: context
        val manager = AppWidgetManager.getInstance(app)
        refreshProvider(app, manager, NotificationsProvider::class.java, false)
        refreshProvider(app, manager, UnreadProvider::class.java, true)
    }

    private fun refreshProvider(context: Context, manager: AppWidgetManager, providerClass: Class<*>, unreadFocused: Boolean) {
        manager.getAppWidgetIds(ComponentName(context, providerClass))?.forEach {
            updateWidget(context, manager, it, unreadFocused)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int, unreadFocused: Boolean) {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val signedIn = !prefs.getString(AppPrefs.SESSION_USER_ID, "").isNullOrBlank()
        val unreadCount = maxOf(0, prefs.getInt(AppPrefs.LAST_NOTIFICATION_COUNT, 0))
        val showConnectedUsername = prefs.getBoolean(PREF_SHOW_CONNECTED_USERNAME, true)
        val showUnreadCount = prefs.getBoolean(PREF_SHOW_UNREAD_COUNT, true)
        val compactMode = prefs.getBoolean(PREF_COMPACT_MODE, false)
        val showLastUpdated = prefs.getBoolean(PREF_SHOW_LAST_UPDATED, false)
        val showPreview = prefs.getBoolean(PREF_SHOW_LAST_NOTIFICATION_PREVIEW, true)
        val backgroundAlpha = clamp(prefs.getInt(PREF_BACKGROUND_ALPHA, 96), 20, 100)
        val textSize = clamp(prefs.getInt(PREF_TEXT_SIZE_SP, 12), 10, 18)
        val refreshMinutes = maxOf(0, prefs.getInt(PREF_REFRESH_INTERVAL_MIN, 30))
        val lastRealtimeSyncMs = maxOf(0L, prefs.getLong(PREF_LAST_REALTIME_SYNC_MS, 0L))
        var connectedHandle = prefs.getString(AppPrefs.IDENTITY_USERNAME, "")?.trim().orEmpty()
        if (connectedHandle.isNotEmpty() && !connectedHandle.startsWith("@")) connectedHandle = "@" + connectedHandle

        val status: CharSequence = if (!signedIn) {
            context.getString(R.string.widget_hcf_signed_out)
        } else {
            val identityState = if (showConnectedUsername && connectedHandle.isNotEmpty()) connectedHandle else ""
            val notificationState = if (showUnreadCount || unreadFocused) {
                if (unreadCount == 0) context.getString(R.string.widget_hcf_no_notifications)
                else context.getString(R.string.widget_hcf_unread_count, unreadCount)
            } else ""
            when {
                identityState.isNotEmpty() && notificationState.isNotEmpty() && !unreadFocused ->
                    identityState + " • " + notificationState
                notificationState.isNotEmpty() -> notificationState
                identityState.isNotEmpty() -> identityState
                else -> "Connected to forum"
            }
        }

        var updatedText = if (lastRealtimeSyncMs > 0L) {
            "Synced " + android.text.format.DateFormat.getTimeFormat(context).format(Date(lastRealtimeSyncMs))
        } else "Waiting for live sync"
        updatedText += if (refreshMinutes > 0) " • auto " + refreshMinutes + "m" else " • auto off"

        val previewText = buildPreview(prefs.getString(PREF_LAST_TITLE, ""), prefs.getString(PREF_LAST_BODY, ""))
        val layout = if (unreadFocused) R.layout.widget_hcf_unread else R.layout.widget_hcf_notifications
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.widget_hcf_title, if (unreadFocused) "Unread notifications" else context.getString(R.string.widget_hcf_title))
        views.setTextViewText(R.id.widget_hcf_status, status)
        views.setTextViewText(R.id.widget_hcf_updated, updatedText)
        views.setTextViewText(R.id.widget_hcf_preview, previewText)
        views.setViewVisibility(R.id.widget_hcf_preview, if (showPreview && !TextUtils.isEmpty(previewText)) View.VISIBLE else View.GONE)

        if (!unreadFocused) {
            views.setViewVisibility(R.id.widget_hcf_logo, if (compactMode) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_hcf_title, if (compactMode) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_hcf_updated, if (showLastUpdated && !compactMode) View.VISIBLE else View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_hcf_updated, if (showLastUpdated) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_hcf_unread_number, if (signedIn) unreadCount.toString() else "—")
        }

        applyWidgetTheme(context, prefs, views, backgroundAlpha, textSize, unreadFocused)
        views.setOnClickPendingIntent(R.id.widget_hcf_body, bodyPendingIntent(context, prefs))
        views.setOnClickPendingIntent(R.id.widget_hcf_notifications, startupPendingIntent(context, REQUEST_OPEN_NOTIFICATIONS, TARGET_NOTIFICATIONS))
        views.setOnClickPendingIntent(R.id.widget_hcf_reload, reloadPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_hcf_settings, settingsPendingIntent(context))
        if (!unreadFocused) {
            views.setOnClickPendingIntent(R.id.widget_hcf_forum, startupPendingIntent(context, REQUEST_OPEN_FORUM, TARGET_FORUM))
        } else {
            views.setOnClickPendingIntent(R.id.widget_hcf_latest, routePendingIntent(context, REQUEST_LATEST, ROUTE_LATEST))
            views.setOnClickPendingIntent(R.id.widget_hcf_profile, routePendingIntent(context, REQUEST_PROFILE, ROUTE_PROFILE))
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun applyWidgetTheme(
        context: Context,
        prefs: SharedPreferences,
        views: RemoteViews,
        backgroundAlpha: Int,
        textSize: Int,
        unreadFocused: Boolean
    ) {
        val followAppTheme = prefs.getBoolean(AppPrefs.WIDGET_FOLLOW_APP_THEME, true)
        val amoled = followAppTheme && ThemeManager.isAmoled(context)
        val dark = if (followAppTheme) ThemeManager.webColorScheme(context) == "dark" else systemPhoneDark()
        val rootBackground = if (amoled) R.drawable.widget_hcf_background_amoled else if (dark) R.drawable.widget_hcf_background_dark else R.drawable.widget_hcf_background_light
        val actionBackground = if (amoled) R.drawable.widget_hcf_action_background_amoled else if (dark) R.drawable.widget_hcf_action_background_dark else R.drawable.widget_hcf_action_background_light
        val titleColor = if (amoled || dark) 0xFFE8F8FF.toInt() else 0xFF10232B.toInt()
        val mutedColor = if (amoled || dark) 0xFFAEBBC2.toInt() else 0xFF53666F.toInt()
        val accentColor = 0xFF00B8F0.toInt()

        views.setInt(R.id.widget_hcf_background_layer, "setBackgroundResource", rootBackground)
        views.setFloat(R.id.widget_hcf_background_layer, "setAlpha", backgroundAlpha / 100f)
        views.setTextColor(R.id.widget_hcf_title, titleColor)
        views.setTextColor(R.id.widget_hcf_status, mutedColor)
        views.setTextColor(R.id.widget_hcf_preview, titleColor)
        views.setTextColor(R.id.widget_hcf_updated, mutedColor)
        views.setTextViewTextSize(R.id.widget_hcf_title, TypedValue.COMPLEX_UNIT_SP, textSize + 3f)
        views.setTextViewTextSize(R.id.widget_hcf_status, TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
        views.setTextViewTextSize(R.id.widget_hcf_preview, TypedValue.COMPLEX_UNIT_SP, maxOf(10f, textSize - 1f))
        views.setTextViewTextSize(R.id.widget_hcf_updated, TypedValue.COMPLEX_UNIT_SP, maxOf(9f, textSize - 2f))

        val actions = if (unreadFocused) intArrayOf(
            R.id.widget_hcf_notifications, R.id.widget_hcf_latest, R.id.widget_hcf_profile,
            R.id.widget_hcf_reload, R.id.widget_hcf_settings
        ) else intArrayOf(
            R.id.widget_hcf_forum, R.id.widget_hcf_notifications, R.id.widget_hcf_reload, R.id.widget_hcf_settings
        )
        actions.forEach { action ->
            views.setInt(action, "setBackgroundResource", actionBackground)
            views.setTextColor(action, accentColor)
            views.setTextViewTextSize(action, TypedValue.COMPLEX_UNIT_SP, maxOf(9f, textSize - 1f))
        }
        if (unreadFocused) {
            views.setTextColor(R.id.widget_hcf_unread_number, accentColor)
            views.setTextViewTextSize(R.id.widget_hcf_unread_number, TypedValue.COMPLEX_UNIT_SP, textSize + 14f)
        }
    }

    private fun buildPreview(title: String?, body: String?): String {
        val cleanTitle = title?.trim().orEmpty()
        val cleanBody = body?.trim().orEmpty()
        return when {
            cleanTitle.isEmpty() -> cleanBody
            cleanBody.isEmpty() -> cleanTitle
            else -> cleanTitle + " — " + cleanBody
        }
    }

    private fun systemPhoneDark(): Boolean = try {
        val uiMode = android.content.res.Resources.getSystem().configuration.uiMode
        uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) { false }

    private fun bodyPendingIntent(context: Context, prefs: SharedPreferences?): PendingIntent {
        return when (prefs?.getString(PREF_DEFAULT_TAP_ACTION, TAP_FORUM) ?: TAP_FORUM) {
            TAP_SETTINGS -> settingsPendingIntent(context)
            TAP_NOTIFICATIONS -> startupPendingIntent(context, REQUEST_OPEN_BODY, TARGET_NOTIFICATIONS)
            TAP_LATEST -> routePendingIntent(context, REQUEST_OPEN_BODY, ROUTE_LATEST)
            TAP_PROFILE -> routePendingIntent(context, REQUEST_OPEN_BODY, ROUTE_PROFILE)
            else -> startupPendingIntent(context, REQUEST_OPEN_BODY, TARGET_FORUM)
        }
    }

    private fun startupPendingIntent(context: Context, requestCode: Int, target: String): PendingIntent =
        PendingIntent.getActivity(context, requestCode, Intent(context, HcfUI.StartupActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_WIDGET_TARGET, target)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PENDING_INTENT_FLAGS)

    private fun routePendingIntent(context: Context, requestCode: Int, route: String): PendingIntent =
        PendingIntent.getActivity(context, requestCode, Intent(context, RouteActivity::class.java).apply {
            action = "com.harleytg.forum.dev.action.HCF_ROUTE." + route
            putExtra(ROUTE_EXTRA, route)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PENDING_INTENT_FLAGS)

    private fun settingsPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, REQUEST_SETTINGS, Intent(context, HcfSubActivities.SettingsActivity::class.java).apply {
            action = "com.harleytg.forum.dev.action.HCF_WIDGET_SETTINGS"
            putExtra(HcfSubActivities.SettingsActivity.EXTRA_SETTINGS_SECTION, "widget")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PENDING_INTENT_FLAGS)

    private fun reloadPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_RELOAD, Intent(context, NotificationsProvider::class.java).apply {
            action = ACTION_RELOAD
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }, PENDING_INTENT_FLAGS)

    private fun scheduledRefreshPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_SCHEDULED_REFRESH, Intent(context, RefreshReceiver::class.java).apply {
            action = ACTION_SCHEDULED_REFRESH
        }, PENDING_INTENT_FLAGS)

    private fun forceRefresh(context: Context?, source: String) {
        if (context == null) return
        refreshAll(context)
        try {
            HcfNotifications.InstantNotificationService.requestImmediateSync(context)
        } catch (error: Throwable) {
            try {
                AppLogger.warn(context, "hcf_widget_reload", source + " • " + error.javaClass.simpleName)
            } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun scheduleAutomaticRefresh(context: Context?) {
        if (context == null) return
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val minutes = maxOf(0, prefs.getInt(PREF_REFRESH_INTERVAL_MIN, 30))
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = scheduledRefreshPendingIntent(app)
        alarm.cancel(pending)
        if (minutes <= 0 || !hasAnyPlacedWidgets(app)) return
        val interval = maxOf(15L * 60L * 1000L, minutes * 60L * 1000L)
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + interval,
            interval,
            pending
        )
    }

    private fun cancelAutomaticRefreshIfNoWidgets(context: Context?) {
        if (context == null || hasAnyPlacedWidgets(context)) return
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
            ?.cancel(scheduledRefreshPendingIntent(context))
    }

    private fun hasAnyPlacedWidgets(context: Context): Boolean = try {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, NotificationsProvider::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(context, UnreadProvider::class.java)).isNotEmpty()
    } catch (_: Throwable) { false }

    @JvmStatic
    fun historyMode(prefs: SharedPreferences?): String =
        if (prefs == null) HISTORY_MODE_FULL
        else normalizeHistoryMode(prefs.getString(PREF_HISTORY_MODE, HISTORY_MODE_FULL))

    @JvmStatic
    fun historyLimit(prefs: SharedPreferences?): Int =
        if (prefs == null) HISTORY_LIMIT
        else normalizeHistoryLimit(prefs.getInt(PREF_HISTORY_LIMIT, HISTORY_LIMIT))

    @JvmStatic
    fun historyModeLabel(mode: String?): String =
        when (normalizeHistoryMode(mode)) {
            HISTORY_MODE_OFF -> "Off"
            HISTORY_MODE_TITLE -> "Titles only"
            else -> "Titles + message"
        }

    @JvmStatic
    fun setHistoryPrivacy(prefs: SharedPreferences?, mode: String?, limit: Int) {
        if (prefs == null) return
        val normalizedMode = normalizeHistoryMode(mode)
        val normalizedLimit = normalizeHistoryLimit(limit)
        val editor = prefs.edit()
            .putString(PREF_HISTORY_MODE, normalizedMode)
            .putInt(PREF_HISTORY_LIMIT, normalizedLimit)
        if (HISTORY_MODE_OFF == normalizedMode) {
            editor.remove(PREF_HISTORY_JSON).apply()
            return
        }
        val current = parseHistory(prefs.getString(PREF_HISTORY_JSON, "[]"))
        val sanitized = JSONArray()
        try {
            for (i in 0 until current.length()) {
                if (sanitized.length() >= normalizedLimit) break
                val item = current.optJSONObject(i) ?: continue
                sanitized.put(historyItem(
                    item.optString("title", ""),
                    item.optString("body", ""),
                    item.optString("url", ""),
                    item.optInt("count", -1),
                    item.optLong("time", 0L),
                    normalizedMode
                ))
            }
            editor.putString(PREF_HISTORY_JSON, sanitized.toString())
        } catch (_: Throwable) {}
        editor.apply()
    }

    @Throws(Exception::class)
    private fun historyItem(
        title: String?, body: String?, url: String?, count: Int, time: Long, mode: String?
    ): JSONObject = JSONObject().apply {
        put("title", safe(title))
        if (HISTORY_MODE_FULL == normalizeHistoryMode(mode)) {
            put("body", safe(body)); put("url", safe(url))
        } else {
            put("body", ""); put("url", "")
        }
        put("count", count); put("time", time)
    }

    private fun normalizeHistoryMode(mode: String?): String =
        if (mode == HISTORY_MODE_OFF || mode == HISTORY_MODE_TITLE) mode else HISTORY_MODE_FULL
    private fun normalizeHistoryLimit(limit: Int): Int =
        when { limit <= 10 -> 10; limit <= 30 -> 30; else -> 60 }
    private fun parseHistory(raw: String?): JSONArray = try {
        JSONArray(if (TextUtils.isEmpty(raw)) "[]" else raw)
    } catch (_: Throwable) { JSONArray() }
    private fun safe(value: String?): String = value ?: ""
    private fun stripAt(value: String): String = if (value.startsWith("@")) value.substring(1) else value
    private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))
}
