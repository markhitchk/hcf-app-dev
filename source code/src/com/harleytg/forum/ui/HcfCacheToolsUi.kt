package com.harleytg.forum.dev

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URL
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection

/** Dev/Beta Content Cache tools. */
object HcfCacheToolsUi {
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"
    private const val PANEL_TAG = "hcf_content_cache_tools"
    private const val PREF_FILE = "hcf_cache_tools"
    private const val PREF_LAST_PURGE = "last_jsdelivr_purge_ms"
    private const val PURGE_COOLDOWN_MS = 60_000L
    private val MAIN = Handler(Looper.getMainLooper())
    private val OBSERVERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val FOF_ROUTE = Pattern.compile("^/p/(\\d+-[A-Za-z0-9-]+)$")
    @Volatile private var forumActivity = WeakReference<Activity>(null)
    private var registered = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            (context?.applicationContext as? Application)?.let(::install)
            return true
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Synchronized
    private fun install(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                rememberForum(activity)
                if (isSettings(activity)) installObserver(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                rememberForum(activity)
                if (isSettings(activity)) {
                    installObserver(activity)
                    scheduleRender(activity)
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                removeObserver(activity)
                if (forumActivity.get() === activity) forumActivity = WeakReference(null)
            }
        })
    }

    private fun rememberForum(activity: Activity) {
        if (activity is HcfForum.MainActivity) forumActivity = WeakReference(activity)
    }

    private fun isSettings(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun isDevBuild(activity: Activity?): Boolean =
        activity?.packageName?.endsWith(".dev") == true

    private fun installObserver(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        synchronized(OBSERVERS) {
            if (OBSERVERS.containsKey(activity)) return
            val root = activity.findViewById<View>(android.R.id.content) ?: return
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!activity.isFinishing && !activity.isDestroyed) render(activity)
            }
            observer.addOnGlobalLayoutListener(listener)
            OBSERVERS[activity] = listener
        }
        scheduleRender(activity)
    }

    private fun removeObserver(activity: Activity?) {
        if (activity == null) return
        val listener = synchronized(OBSERVERS) { OBSERVERS.remove(activity) } ?: return
        try {
            val observer = activity.findViewById<View>(android.R.id.content)?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnGlobalLayoutListener(listener)
        } catch (_: Throwable) {}
    }

    private fun scheduleRender(activity: Activity) {
        MAIN.postDelayed({ render(activity) }, 70L)
        MAIN.postDelayed({ render(activity) }, 220L)
        MAIN.postDelayed({ render(activity) }, 500L)
    }

    private fun render(activity: Activity) {
        if (!isSettings(activity) || activity.isFinishing) return
        if (readStringField(activity, "currentSettingsSection") != "advanced") return
        val content = readViewGroupField(activity, "settingsContent") ?: return
        if (findTagged(content, PANEL_TAG) != null) return

        val body = nativeCard(activity).apply { tag = "$PANEL_TAG:body" }
        nativeSectionTitle(activity, "Content Cache", "Refresh local forum content and Dev CDN cache")
            ?.let(body::addView)

        body.addView(text(
            activity,
            "These tools do not clear forum sign-in cookies or account data. Local refresh affects this app only; CDN purge requests a fresh copy from jsDelivr.",
            10,
            color(activity, R.color.hcf_muted, Color.LTGRAY)
        ).apply { setLineSpacing(0f, 1.08f) }, lp(activity, -1, -2, 0, 10))

        body.addView(actionButton(activity, "Refresh Forum Content").apply {
            setOnClickListener { refreshForum(activity, true) }
        }, lp(activity, -1, dp(activity, 50), 0, 8))

        body.addView(actionButton(activity, "Force Fresh Page Load").apply {
            setOnClickListener { refreshForum(activity, false) }
        }, lp(activity, -1, dp(activity, 50), 0, 8))

        if (isDevBuild(activity)) {
            val purge = actionButton(activity, "Purge HCF CDN Cache").apply {
                setOnClickListener { confirmPurge(activity, this) }
            }
            body.addView(purge, lp(activity, -1, dp(activity, 50), 0, 6))
            body.addView(text(
                activity,
                "Dev only • purges the current FoF page, /p/31-hcf-app, and shared HCF page-loader assets. A 60-second cooldown prevents accidental repeat requests.",
                9,
                color(activity, R.color.hcf_hint, Color.GRAY)
            ).apply { setLineSpacing(0f, 1.08f) }, lp(activity, -1, -2, 0, 0))
        }

        val panel = nativeConnectedSettingsPanel(
            activity,
            "Content Cache",
            if (isDevBuild(activity))
                "Local WebView refresh • force fresh load • Dev CDN purge"
            else
                "Local WebView refresh • force fresh load",
            body,
            false
        ).apply { tag = PANEL_TAG }

        var aboutIndex = directChildContainingText(content, "About Harley's Clan Forum")
        if (aboutIndex < 0) aboutIndex = content.childCount
        content.addView(panel, aboutIndex)
        AppLogger.info(activity, "cache_tools_ui", "advanced_control_added")
    }

    private fun refreshForum(settingsActivity: Activity, clearCache: Boolean) {
        val main = forumActivity.get()
        val webView = main?.findViewById<WebView>(R.id.webView)

        if (clearCache) {
            try {
                if (webView != null) {
                    webView.clearCache(true)
                } else {
                    WebView(settingsActivity).apply {
                        clearCache(true)
                        destroy()
                    }
                }
            } catch (error: Throwable) {
                AppLogger.warn(settingsActivity, "cache_tools_clear", error.javaClass.simpleName)
            }
        }

        if (main != null && webView != null) {
            main.runOnUiThread {
                try {
                    var current = webView.url
                    if (current.isNullOrBlank()) current = "https://forum.harleytg.com/"
                    val previousMode = webView.settings.cacheMode
                    webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    webView.loadUrl(withCacheBuster(current))
                    webView.postDelayed({
                        try { webView.settings.cacheMode = previousMode } catch (_: Throwable) {}
                    }, 2500L)
                } catch (error: Throwable) {
                    AppLogger.warn(settingsActivity, "cache_tools_reload", error.javaClass.simpleName)
                }
            }
            Toast.makeText(
                settingsActivity,
                if (clearCache) "Forum cache cleared and fresh reload requested."
                else "Fresh page load requested.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            settingsActivity.startActivity(Intent(settingsActivity, HcfForum.MainActivity::class.java).apply {
                data = Uri.parse(withCacheBuster("https://forum.harleytg.com/"))
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            Toast.makeText(
                settingsActivity,
                if (clearCache) "Forum cache cleared. Opening a fresh forum load."
                else "Opening a fresh forum load.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (error: Throwable) {
            AppLogger.error(settingsActivity, "cache_tools_open", error.javaClass.simpleName)
            Toast.makeText(
                settingsActivity,
                "Fresh forum load could not be started.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun withCacheBuster(rawUrl: String?): String = try {
        Uri.parse(rawUrl).buildUpon()
            .appendQueryParameter("hcf_refresh", System.currentTimeMillis().toString())
            .build()
            .toString()
    } catch (_: Throwable) {
        val join = if (rawUrl?.contains("?") == true) "&" else "?"
        rawUrl.toString() + join + "hcf_refresh=" + System.currentTimeMillis()
    }

    private fun confirmPurge(activity: Activity, button: Button) {
        if (!isDevBuild(activity)) return
        val last = activity.getSharedPreferences(PREF_FILE, 0).getLong(PREF_LAST_PURGE, 0L)
        val remaining = PURGE_COOLDOWN_MS - (System.currentTimeMillis() - last)
        if (remaining > 0L) {
            Toast.makeText(
                activity,
                "CDN purge cooldown: " + maxOf(1L, (remaining + 999L) / 1000L) + "s",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Purge HCF CDN Cache?")
            .setMessage("This asks jsDelivr to invalidate the current HCF FoF page and shared page-loader assets. Use this after publishing a page update that is still showing stale content.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Purge Cache") { _, _ -> purgeCdn(activity, button) }
            .show()
    }

    private fun purgeCdn(activity: Activity, button: Button) {
        if (!isDevBuild(activity)) return
        activity.getSharedPreferences(PREF_FILE, 0).edit()
            .putLong(PREF_LAST_PURGE, System.currentTimeMillis())
            .apply()

        button.isEnabled = false
        button.text = "Purging HCF CDN…"

        AppExecutors.network().execute {
            val paths = purgePaths()
            fofFileForUrl(currentForumUrl())?.let(paths::add)
            var ok = 0
            var failed = 0
            for (path in paths) {
                if (purgeOne(path)) ok++ else failed++
            }
            MAIN.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                button.isEnabled = true
                button.text = "Purge HCF CDN Cache"
                if (failed == 0) {
                    Toast.makeText(
                        activity,
                        "HCF CDN purge requested for $ok files.",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshForum(activity, true)
                } else {
                    Toast.makeText(
                        activity,
                        "CDN purge finished: $ok succeeded, $failed failed.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun purgePaths(): MutableSet<String> = LinkedHashSet<String>().apply {
        add("v1.x/pages/fof-pages/31-hcf-app.html")
        add("v1.x/pages/fof-pages/hcf-page-entry.js")
        add("v1.x/pages/fof-pages/hcf-page-bootstrap.js")
        add("v1.x/pages/fof-pages/hcf-page.js")
        add("v1.x/pages/fof-pages/hcf-page-v2.1.css")
        add("v1.x/pages/fof-pages/hcf-page-runtime.css")
        add("v1.x/pages/fof-pages/hcf-fof-loader.js")
        add("v1.x/pages/fof-pages/hcf-domain-router.js")
    }

    private fun purgeOne(path: String): Boolean {
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL("https://purge.jsdelivr.net/gh/markhitchk/hcf@main/$path")
                .openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 9000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            connection.setRequestProperty("User-Agent", BuildInfo.USER_AGENT_MARKER + " CacheTools")
            connection.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun currentForumUrl(): String? = try {
        forumActivity.get()?.findViewById<WebView>(R.id.webView)?.url
    } catch (_: Throwable) {
        null
    }

    private fun fofFileForUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return try {
            val path = Uri.parse(rawUrl).path ?: return null
            val matcher = FOF_ROUTE.matcher(path)
            if (!matcher.matches()) return null
            "v1.x/pages/fof-pages/" + matcher.group(1) + ".html"
        } catch (_: Throwable) {
            null
        }
    }

    private fun nativeCard(activity: Activity): LinearLayout {
        try {
            val method = activity.javaClass.getDeclaredMethod("card").apply { isAccessible = true }
            (method.invoke(activity) as? LinearLayout)?.let { return it }
        } catch (_: Throwable) {}
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
            try { setBackgroundResource(R.drawable.settings_section_body) } catch (_: Throwable) {}
        }
    }

    private fun nativeSectionTitle(activity: Activity, title: String, subtitle: String): View? {
        try {
            val method = activity.javaClass
                .getDeclaredMethod("sectionTitle", String::class.java, String::class.java)
                .apply { isAccessible = true }
            (method.invoke(activity, title, subtitle) as? View)?.let { return it }
        } catch (_: Throwable) {}

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(activity, title, 15, color(activity, R.color.hcf_text, Color.WHITE)).apply {
                setTypeface(null, 1)
            })
            addView(text(activity, subtitle, 10, color(activity, R.color.hcf_muted, Color.LTGRAY)))
        }
    }

    private fun nativeConnectedSettingsPanel(
        activity: Activity,
        title: String,
        subtitle: String,
        inner: View,
        expanded: Boolean
    ): View {
        try {
            val method = activity.javaClass.getDeclaredMethod(
                "connectedSettingsPanel",
                String::class.java,
                String::class.java,
                View::class.java,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            (method.invoke(activity, title, subtitle, inner, expanded) as? View)?.let { return it }
        } catch (_: Throwable) {}

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            nativeSectionTitle(activity, title, subtitle)?.let(::addView)
            addView(inner)
        }
    }

    private fun actionButton(activity: Activity, label: String): Button =
        Button(activity).apply {
            try { UiButtons.normalizeText(this) } catch (_: Throwable) { isAllCaps = false }
            text = label
            textSize = 12f
            setTextColor(color(activity, R.color.hcf_cyan_bright, Color.CYAN))
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
            try { setBackgroundResource(R.drawable.button_background) } catch (_: Throwable) {}
        }

    private fun text(activity: Activity, value: String, sp: Int, color: Int): TextView =
        TextView(activity).apply {
            text = value
            textSize = sp.toFloat()
            setTextColor(color)
        }

    private fun lp(activity: Activity, width: Int, height: Int, top: Int, bottom: Int) =
        LinearLayout.LayoutParams(width, height).apply {
            topMargin = dp(activity, top)
            bottomMargin = dp(activity, bottom)
        }

    private fun color(activity: Activity, id: Int, fallback: Int): Int = try {
        activity.getColor(id)
    } catch (_: Throwable) {
        fallback
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)

    private fun readViewGroupField(activity: Activity, name: String): ViewGroup? = try {
        val field: Field = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field.get(activity) as? ViewGroup
    } catch (_: Throwable) {
        null
    }

    private fun readStringField(activity: Activity, name: String): String = try {
        val field: Field = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field.get(activity)?.toString().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun findTagged(root: View?, tag: String): View? {
        if (root == null) return null
        if (tag == root.tag) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findTagged(group.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun directChildContainingText(group: ViewGroup?, needle: String?): Int {
        if (group == null || needle == null) return -1
        for (i in 0 until group.childCount) {
            if (containsText(group.getChildAt(i), needle)) return i
        }
        return -1
    }

    private fun containsText(view: View?, needle: String): Boolean {
        if (view is TextView && view.text?.toString()?.contains(needle) == true) return true
        val group = view as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            if (containsText(group.getChildAt(i), needle)) return true
        }
        return false
    }
}
