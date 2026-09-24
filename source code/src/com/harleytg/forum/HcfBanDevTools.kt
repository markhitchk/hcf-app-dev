package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * Dev/Beta-only diagnostic UI for the app's native IP-ban enforcement path.
 *
 * Privacy rules for this diagnostic:
 * - never render or log a repository owner/name;
 * - never render or log the ban-list/config source URL;
 * - never render or log the device's raw public IP address;
 * - never render or log a username, ban id, reason, or ban-list entry;
 * - logs contain only coarse health state and exception class names.
 */
object HcfBanDevTools {
    private const val VIEW_TAG = "hcf_ip_ban_devtools_v1"
    private val MAIN = Handler(Looper.getMainLooper())
    private val INSTALLED = AtomicBoolean(false)
    private var resumedSettings = WeakReference<Activity>(null)

    private fun install(context: Context?) {
        if (context == null || !INSTALLED.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (activity is HcfSubActivities.SettingsActivity) {
                    resumedSettings = WeakReference(activity)
                    MAIN.removeCallbacks(POLL)
                    MAIN.post(POLL)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedSettings.get() === activity) {
                    resumedSettings.clear()
                    MAIN.removeCallbacks(POLL)
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (resumedSettings.get() === activity) {
                    resumedSettings.clear()
                    MAIN.removeCallbacks(POLL)
                }
            }
        })
    }

    private val POLL: Runnable = object : Runnable {
        override fun run() {
            val activity = resumedSettings.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            try {
                injectIntoDeveloperTools(activity)
            } catch (error: Throwable) {
                AppLogger.warn(activity, "ip_ban_devtools_ui", error.javaClass.simpleName)
            }
            MAIN.postDelayed(this, 650L)
        }
    }

    private fun injectIntoDeveloperTools(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(VIEW_TAG) != null) return
        val title = findText(decor, "Developer Tools") ?: return
        val body = findPanelBody(title) ?: return

        var host = body
        if (body.childCount > 0 && body.getChildAt(0) is LinearLayout) {
            host = body.getChildAt(0) as LinearLayout
        }
        if (host.findViewWithTag<View>(VIEW_TAG) != null) return

        val status = TextView(activity)
        val check = Button(activity)
        val block = buildBlock(activity, status, check)
        block.tag = VIEW_TAG
        host.addView(
            block,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        check.setOnClickListener { runCheck(activity, status, check) }
    }

    private fun buildBlock(activity: Activity, status: TextView, check: Button): LinearLayout {
        val density = maxOf(1, Math.round(activity.resources.displayMetrics.density))
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12 * density, 0, 0)

            addView(TextView(activity).apply {
                text = "IP Ban System"
                textSize = 12f
                setTypeface(null, 1)
                setTextColor(activity.getColor(R.color.hcf_cyan_bright))
            }, LinearLayout.LayoutParams(-1, -2))

            addView(TextView(activity).apply {
                text = "Tests the app's live ban enforcement path without displaying source details or your public IP."
                textSize = 10f
                setTextColor(activity.getColor(R.color.hcf_muted))
                setPadding(0, 4 * density, 0, 8 * density)
            }, LinearLayout.LayoutParams(-1, -2))

            status.text = "Status: Not checked yet\nSource details: Hidden"
            status.textSize = 11f
            status.setTextColor(activity.getColor(R.color.hcf_meta))
            status.setPadding(12 * density, 10 * density, 12 * density, 10 * density)
            status.setBackgroundResource(R.drawable.quick_action_background)
            addView(status, LinearLayout.LayoutParams(-1, -2))

            try { UiButtons.normalizeText(check) } catch (_: Throwable) {}
            check.isAllCaps = false
            check.text = "Check IP Ban System"
            check.textSize = 13f
            check.setTextColor(activity.getColor(R.color.hcf_text))
            check.gravity = Gravity.CENTER
            check.minHeight = 0
            check.minimumHeight = 0
            check.setBackgroundResource(R.drawable.quick_action_background)
            addView(check, LinearLayout.LayoutParams(-1, 48 * density).apply {
                topMargin = 8 * density
            })
        }
    }

    private fun runCheck(activity: Activity, status: TextView, check: Button) {
        if (activity.isFinishing || activity.isDestroyed) return
        check.isEnabled = false
        check.text = "Checking IP Ban System…"
        status.text = "Status: Checking…\nConfiguration: Checking\nBan list: Checking\nNetwork lookup: Checking\nSource details: Hidden"
        status.setTextColor(activity.getColor(R.color.hcf_cyan_bright))
        AppLogger.info(activity, "ip_ban_diagnostic", "started")

        val app = activity.applicationContext
        AppExecutors.network().execute {
            val result = diagnose(app)
            MAIN.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                status.text = result.displayText()
                status.setTextColor(activity.getColor(result.colorRes()))
                check.isEnabled = true
                check.text = "Check IP Ban System Again"
                AppLogger.info(activity, "ip_ban_diagnostic", result.logState)
            }
        }
    }

    private fun diagnose(context: Context): DiagnosticResult = try {
        val config = loadRuntimeConfigForDiagnostic(context)
            ?: return DiagnosticResult.unavailable("Configuration could not be loaded")
        if (!config.ready()) return DiagnosticResult.inactive()

        val root = JSONObject(downloadJson(config.banListUrl))
        if (root.optInt("schema_version", 0) != 1) {
            return DiagnosticResult.unavailable("Ban-list schema is invalid")
        }
        if (root.optJSONObject("users") == null || root.optJSONObject("ip_sha256") == null) {
            return DiagnosticResult.unavailable("Ban-list structure is incomplete")
        }

        val networkAvailable =
            lookupPublicIpAvailable(config.ipPrimary) || lookupPublicIpAvailable(config.ipFallback)
        if (!networkAvailable) DiagnosticResult.degraded() else DiagnosticResult.working()
    } catch (error: Throwable) {
        val clean = unwrap(error)
        DiagnosticResult.unavailable(clean?.javaClass?.simpleName ?: "UnknownError")
    }

    @Throws(Exception::class)
    private fun loadRuntimeConfigForDiagnostic(context: Context): HcfBanSystem.RuntimeConfig? {
        val method = HcfBanSystem::class.java.getDeclaredMethod("loadRuntimeConfig", Context::class.java)
        method.isAccessible = true
        return method.invoke(null, context) as? HcfBanSystem.RuntimeConfig
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    @Throws(Exception::class)
    private fun downloadJson(source: String?): String {
        if (source == null || !source.startsWith("https://")) throw IllegalStateException("InvalidSource")
        var connection: HttpsURLConnection? = null
        try {
            connection = URL(source).openConnection() as HttpsURLConnection
            connection.connectTimeout = 4500
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", BuildInfo.USER_AGENT_MARKER + " BanDiagnostic/1")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HttpError")
            return readAll(connection.inputStream, 131072)
        } finally {
            connection?.disconnect()
        }
    }

    private fun lookupPublicIpAvailable(source: String?): Boolean {
        if (source == null || !source.startsWith("https://")) return false
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL(source).openConnection() as HttpsURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json,text/plain;q=0.9")
            connection.setRequestProperty("User-Agent", BuildInfo.USER_AGENT_MARKER + " BanDiagnosticIp/1")
            val code = connection.responseCode
            if (code !in 200..299) return false
            val body = readAll(connection.inputStream, 8192).trim()
            val ip = try { JSONObject(body).optString("ip", "") } catch (_: Throwable) { body }
            looksLikeIp(ip)
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun looksLikeIp(value: String?): Boolean {
        var raw = value?.trim().orEmpty()
        if (raw.startsWith("::ffff:")) raw = raw.substring(7)
        if (Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(raw)) {
            for (part in raw.split('.')) {
                val number = part.toIntOrNull() ?: return false
                if (number !in 0..255) return false
            }
            return true
        }
        return ':' in raw && raw.length <= 64 && Regex("^[0-9a-fA-F:]+$").matches(raw)
    }

    @Throws(Exception::class)
    private fun readAll(stream: InputStream, max: Int): String {
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val out = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
                if (out.length > max) throw IllegalStateException("ResponseTooLarge")
            }
            return out.toString()
        }
    }

    private fun findText(root: ViewGroup, exact: String): TextView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && exact == child.text?.toString()?.trim()) return child
            if (child is ViewGroup) findText(child, exact)?.let { return it }
        }
        return null
    }

    private fun containsText(root: View, exact: String): Boolean {
        if (root is TextView && exact == root.text?.toString()?.trim()) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsText(root.getChildAt(i), exact)) return true
            }
        }
        return false
    }

    private fun findPanelBody(title: TextView): LinearLayout? {
        var current: View? = title
        repeat(7) {
            val parent: ViewParent = current?.parent ?: return null
            val group = parent as? ViewGroup ?: return null
            if (group is LinearLayout && group.childCount >= 2) {
                val header = group.getChildAt(0)
                val body = group.getChildAt(1)
                if (body is LinearLayout && containsText(header, "Developer Tools")) return body
            }
            current = parent as? View
        }
        return null
    }

    private class DiagnosticResult(
        val state: String,
        val config: String,
        val list: String,
        val network: String,
        val logState: String,
        val color: Int
    ) {
        fun displayText(): String {
            val extra = if (state == "Degraded") {
                "\nNote: account-ban data is reachable, but network/IP bans cannot be reliably evaluated until public-IP lookup recovers."
            } else ""
            return "Status: $state\nConfiguration: $config\nBan list: $list\nNetwork lookup: $network\nSource details: Hidden$extra"
        }

        fun colorRes(): Int = color

        companion object {
            fun working() = DiagnosticResult(
                "Working", "Active", "Reachable • schema v1", "Available",
                "working", R.color.hcf_accent_text
            )

            fun degraded() = DiagnosticResult(
                "Degraded", "Active", "Reachable • schema v1", "Unavailable",
                "degraded_network_lookup", R.color.hcf_warning
            )

            fun inactive() = DiagnosticResult(
                "Inactive", "Not enabled", "Not tested", "Not tested",
                "inactive", R.color.hcf_warning
            )

            fun unavailable(reason: String?): DiagnosticResult {
                val safeReason = sanitizeReason(reason)
                return DiagnosticResult(
                    "Unavailable", "Could not verify", "Could not verify", "Not verified",
                    "unavailable_" + safeReason.lowercase(Locale.US), R.color.hcf_error
                )
            }

            private fun sanitizeReason(value: String?): String {
                var out = (value ?: "error").replace(Regex("[^A-Za-z0-9_]"), "_")
                if (out.isEmpty()) out = "error"
                if (out.length > 32) out = out.substring(0, 32)
                return out
            }
        }
    }

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            install(context)
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
