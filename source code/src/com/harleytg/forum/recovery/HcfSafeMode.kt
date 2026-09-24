package com.harleytg.forum.dev

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HcfSafeMode {
    private const val PREF_FILE = "hcf_app"
    private const val KEY_PENDING = "safe_mode_pending"
    private const val KEY_ACTIVE = "safe_mode_active"
    private const val KEY_SESSION_PID = "safe_mode_session_pid"
    private const val KEY_CRASH_COUNT = "safe_mode_crash_count"
    private const val KEY_LAST_CRASH_AT = "safe_mode_last_crash_at"
    private const val KEY_LAST_CRASH_SUMMARY = "safe_mode_last_crash_summary"
    private const val KEY_CRASHED_WHILE_SAFE = "safe_mode_crashed_while_active"
    private const val KEY_PREV_AGGRESSIVE_PRESENT = "safe_mode_prev_aggressive_present"
    private const val KEY_PREV_AGGRESSIVE = "safe_mode_prev_aggressive"
    private const val KEY_PREV_PROFILE_PRESENT = "safe_mode_prev_profile_present"
    private const val KEY_PREV_PROFILE = "safe_mode_prev_profile"
    private const val KEY_PREV_PERFORMANCE_MODE_PRESENT = "safe_mode_prev_performance_mode_present"
    private const val KEY_PREV_PERFORMANCE_MODE = "safe_mode_prev_performance_mode"
    private const val KEY_PREV_AUTO_DOWNLOAD_PRESENT = "safe_mode_prev_auto_download_present"
    private const val KEY_PREV_AUTO_DOWNLOAD = "safe_mode_prev_auto_download"
    private const val CRASH_WINDOW_MS = 10L * 60L * 1000L
    private const val REPORT_LIMIT = 96 * 1024
    private const val RECOVERY_DIR = "hcf-recovery"
    private const val LAST_CRASH_FILE = "last-crash.txt"
    private var installed = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val base = context ?: return true
            val appContext = base.applicationContext ?: base
            restoreStaleSafeModeSession(appContext)
            installCrashHandler()
            (appContext as? Application)?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) {
                        if (activity is SafeModeActivity || activity is EntryActivity) return
                        if (isCurrentProcessSafeMode(appContext)) {
                            applySafeOverrides(appContext)
                            return
                        }
                        if (prefs(appContext).getBoolean(KEY_PENDING, false)) {
                            activity.startActivity(
                                Intent(activity, SafeModeActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                            )
                            activity.finish()
                        }
                    }
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                }
            )
            return true
        }

        @Synchronized
        private fun installCrashHandler() {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous !is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, previous))
            }
            installed = true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private class CrashHandler(
        context: Context?,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        private val context: Context? = context?.applicationContext ?: context
        private var handling = false

        @Synchronized
        override fun uncaughtException(thread: Thread, error: Throwable) {
            if (!handling) {
                handling = true
                try { recordCrash(context, thread, error) } catch (_: Throwable) {}
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun recordCrash(context: Context?, thread: Thread?, error: Throwable?) {
        if (context == null) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_CRASH_AT, 0L)
        val count = if (now - last <= CRASH_WINDOW_MS) p.getInt(KEY_CRASH_COUNT, 0) + 1 else 1
        val summary = summarize(error)
        p.edit()
            .putBoolean(KEY_PENDING, true)
            .putInt(KEY_CRASH_COUNT, count)
            .putLong(KEY_LAST_CRASH_AT, now)
            .putString(KEY_LAST_CRASH_SUMMARY, summary)
            .putBoolean(KEY_CRASHED_WHILE_SAFE, isCurrentProcessSafeMode(context))
            .commit()

        val dir = File(context.filesDir, RECOVERY_DIR)
        if (!dir.exists() && !dir.mkdirs()) return
        val bytes = buildCrashReport(context, thread, error, count, now)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            FileOutputStream(File(dir, LAST_CRASH_FILE), false).use {
                it.write(bytes, 0, minOf(bytes.size, REPORT_LIMIT))
                it.flush()
            }
        } catch (_: Throwable) {}
    }

    private fun buildCrashReport(
        context: Context,
        thread: Thread?,
        error: Throwable?,
        count: Int,
        whenCaptured: Long
    ): String = buildString {
        append("Harley's Clan Forum — Dev/Beta crash report\n")
        append("Generated: ").append(formatTime(whenCaptured)).append('\n')
        append("Version: ").append(BuildInfo.VERSION).append(" (").append(BuildInfo.VERSION_CODE).append(")\n")
        append("Channel: ").append(BuildInfo.CHANNEL).append('\n')
        append("Crash count in 10 min window: ").append(count).append('\n')
        append("Safe Mode active: ").append(isCurrentProcessSafeMode(context)).append('\n')
        append("Android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Thread: ").append(thread?.name ?: "unknown").append('\n')
        append("Exception: ").append(summarize(error)).append("\n\n")
        if (error != null) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            error.printStackTrace(pw)
            pw.flush()
            append(sw.toString())
        }
    }

    private fun summarize(error: Throwable?): String {
        if (error == null) return "Unknown uncaught exception"
        var message = error.message
        if (message.isNullOrBlank()) return error.javaClass.name
        message = message.replace('\n', ' ').replace('\r', ' ').trim()
        if (message.length > 220) message = message.substring(0, 220) + "…"
        return error.javaClass.name + ": " + message
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun isCurrentProcessSafeMode(context: Context?): Boolean {
        if (context == null) return false
        val p = prefs(context)
        return p.getBoolean(KEY_ACTIVE, false) &&
            p.getInt(KEY_SESSION_PID, -1) == android.os.Process.myPid()
    }

    private fun beginSafeMode(context: Context) {
        val p = prefs(context)
        snapshotRuntimePrefs(p)
        p.edit()
            .putBoolean(KEY_PENDING, false)
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_SESSION_PID, android.os.Process.myPid())
            .commit()
        applySafeOverrides(context)
    }

    private fun applySafeOverrides(context: Context) {
        prefs(context).edit()
            .putBoolean("aggressive_realtime", false)
            .putString("performance_profile", PerformanceProfile.AUTO)
            .putBoolean("performance_mode", false)
            .putBoolean("update_auto_download", false)
            .commit()
    }

    private fun snapshotRuntimePrefs(p: SharedPreferences) {
        if (p.getBoolean(KEY_ACTIVE, false)) return
        val e = p.edit()
        e.putBoolean(KEY_PREV_AGGRESSIVE_PRESENT, p.contains("aggressive_realtime"))
        if (p.contains("aggressive_realtime")) e.putBoolean(KEY_PREV_AGGRESSIVE, p.getBoolean("aggressive_realtime", true))
        e.putBoolean(KEY_PREV_PROFILE_PRESENT, p.contains("performance_profile"))
        if (p.contains("performance_profile")) e.putString(KEY_PREV_PROFILE, p.getString("performance_profile", PerformanceProfile.AUTO))
        e.putBoolean(KEY_PREV_PERFORMANCE_MODE_PRESENT, p.contains("performance_mode"))
        if (p.contains("performance_mode")) e.putBoolean(KEY_PREV_PERFORMANCE_MODE, p.getBoolean("performance_mode", false))
        e.putBoolean(KEY_PREV_AUTO_DOWNLOAD_PRESENT, p.contains("update_auto_download"))
        if (p.contains("update_auto_download")) e.putBoolean(KEY_PREV_AUTO_DOWNLOAD, p.getBoolean("update_auto_download", false))
        e.commit()
    }

    private fun restoreStaleSafeModeSession(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return
        if (p.getInt(KEY_SESSION_PID, -1) == android.os.Process.myPid()) return
        restoreRuntimePrefs(p)
    }

    private fun restoreRuntimePrefs(p: SharedPreferences) {
        val e = p.edit()
        restoreBoolean(p, e, KEY_PREV_AGGRESSIVE_PRESENT, KEY_PREV_AGGRESSIVE, "aggressive_realtime")
        restoreString(p, e, KEY_PREV_PROFILE_PRESENT, KEY_PREV_PROFILE, "performance_profile")
        restoreBoolean(p, e, KEY_PREV_PERFORMANCE_MODE_PRESENT, KEY_PREV_PERFORMANCE_MODE, "performance_mode")
        restoreBoolean(p, e, KEY_PREV_AUTO_DOWNLOAD_PRESENT, KEY_PREV_AUTO_DOWNLOAD, "update_auto_download")
        e.putBoolean(KEY_ACTIVE, false)
        e.remove(KEY_SESSION_PID)
        e.remove(KEY_PREV_AGGRESSIVE_PRESENT).remove(KEY_PREV_AGGRESSIVE)
        e.remove(KEY_PREV_PROFILE_PRESENT).remove(KEY_PREV_PROFILE)
        e.remove(KEY_PREV_PERFORMANCE_MODE_PRESENT).remove(KEY_PREV_PERFORMANCE_MODE)
        e.remove(KEY_PREV_AUTO_DOWNLOAD_PRESENT).remove(KEY_PREV_AUTO_DOWNLOAD)
        e.commit()
    }

    private fun restoreBoolean(
        p: SharedPreferences,
        e: SharedPreferences.Editor,
        presentKey: String,
        valueKey: String,
        targetKey: String
    ) {
        if (p.getBoolean(presentKey, false)) e.putBoolean(targetKey, p.getBoolean(valueKey, false))
        else e.remove(targetKey)
    }

    private fun restoreString(
        p: SharedPreferences,
        e: SharedPreferences.Editor,
        presentKey: String,
        valueKey: String,
        targetKey: String
    ) {
        if (p.getBoolean(presentKey, false)) e.putString(targetKey, p.getString(valueKey, PerformanceProfile.AUTO))
        else e.remove(targetKey)
    }

    private fun normalStart(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_ACTIVE, false)) restoreRuntimePrefs(p)
        p.edit().putBoolean(KEY_PENDING, false).commit()
    }

    private fun readCrashReport(context: Context): String {
        val report = File(File(context.filesDir, RECOVERY_DIR), LAST_CRASH_FILE)
        if (!report.isFile) {
            return "No saved crash report file.\n\nLast crash: " +
                prefs(context).getString(KEY_LAST_CRASH_SUMMARY, "Unknown")
        }
        return try {
            val bytes = ByteArray(minOf(report.length(), REPORT_LIMIT.toLong()).toInt())
            var offset = 0
            report.inputStream().use { input ->
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
            }
            String(bytes, 0, offset, StandardCharsets.UTF_8)
        } catch (error: Throwable) {
            "Crash report could not be read: " + error.javaClass.simpleName
        }
    }

    private fun clearCrashHistory(context: Context) {
        prefs(context).edit()
            .remove(KEY_CRASH_COUNT)
            .remove(KEY_LAST_CRASH_AT)
            .remove(KEY_LAST_CRASH_SUMMARY)
            .remove(KEY_CRASHED_WHILE_SAFE)
            .commit()
        val report = File(File(context.filesDir, RECOVERY_DIR), LAST_CRASH_FILE)
        if (report.isFile) try { report.delete() } catch (_: Throwable) {}
    }

    private fun resetRecoveryCounters(context: Context) {
        prefs(context).edit()
            .remove("renderer_recovery_count")
            .remove("startup_last_good_at")
            .remove("startup_last_good_host")
            .remove("startup_loader_verbose")
            .commit()
    }

    private fun clearTemporaryCache(context: Context): Int {
        val root = context.cacheDir
        if (!root.isDirectory) return 0
        var removed = 0
        root.listFiles()?.forEach { removed += deleteRecursively(it) }
        return removed
    }

    private fun deleteRecursively(file: File?): Int {
        if (file == null || !file.exists()) return 0
        var removed = 0
        if (file.isDirectory) file.listFiles()?.forEach { removed += deleteRecursively(it) }
        try { if (file.delete()) removed++ } catch (_: Throwable) {}
        return removed
    }

    private fun formatTime(whenCaptured: Long): String =
        if (whenCaptured <= 0L) "Unknown"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(whenCaptured))

    class EntryActivity : Activity() {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            val p = prefs(this)
            if (p.getBoolean(KEY_PENDING, false)) {
                startActivity(Intent(this, SafeModeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
                return
            }
            if (isCurrentProcessSafeMode(this)) applySafeOverrides(this)
            val source = intent
            val target = Intent(this, HcfUI.StartupActivity::class.java)
            source?.let {
                target.action = it.action
                target.data = it.data
                it.extras?.let(target::putExtras)
            }
            startActivity(target)
            finish()
        }

        override fun onBackPressed() = finish()
    }

    class SafeModeActivity : Activity() {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            window.statusBarColor = BG
            window.navigationBarColor = BG
            title = "HCF Safe Mode"
            setContentView(buildContent())
        }

        private fun buildContent(): View {
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(BG)
            }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(24), dp(18), dp(28))
            }
            scroll.addView(root, ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            root.addView(label("HARLEY'S CLAN FORUM • RECOVERY", 11, CYAN, true))
            root.addView(label("Safe Mode", 28, TEXT, true), wrap().apply { topMargin = dp(4) })
            root.addView(
                label(
                    "The previous app run ended unexpectedly. Your forum account, cookies, notification channels, and downloaded files have not been cleared.",
                    14, MUTED, false
                ).apply { setLineSpacing(0f, 1.12f) },
                wrap().apply { topMargin = dp(8) }
            )

            root.addView(statusCard(), spaced(16))
            root.addView(primaryButton("Start in Safe Mode") {
                beginSafeMode(this)
                toast("Safe Mode enabled for this app process")
                launchForum()
            }, spaced(16))
            root.addView(secondaryButton("Try Normal Start") {
                normalStart(this)
                launchForum()
            }, spaced(8))

            root.addView(sectionTitle("Crash report"), spaced(22))
            root.addView(secondaryButton("View Crash Details") { showCrashDetails() }, spaced(8))
            root.addView(secondaryButton("Copy Crash Report") { copyCrashReport() }, spaced(8))
            root.addView(secondaryButton("Share Crash Report") { shareCrashReport() }, spaced(8))

            root.addView(sectionTitle("Recovery tools"), spaced(22))
            root.addView(secondaryButton("Clear Temporary Cache") {
                val count = clearTemporaryCache(this)
                toast("Removed $count temporary cache item" + if (count == 1) "" else "s")
            }, spaced(8))
            root.addView(secondaryButton("Reset Startup / Renderer Recovery State") {
                resetRecoveryCounters(this)
                toast("Startup and renderer recovery counters reset")
            }, spaced(8))
            root.addView(secondaryButton("Open Android App Settings") { openAndroidAppSettings() }, spaced(8))
            root.addView(secondaryButton("Clear Crash History") {
                clearCrashHistory(this)
                toast("Crash history cleared")
                recreate()
            }, spaced(8))

            if (BuildInfo.ENABLE_DEV_TEST_MENU) {
                root.addView(sectionTitle("Developer test"), spaced(22))
                root.addView(dangerButton("Test Crash Handler") { confirmCrashTest() }, spaced(8))
            }

            root.addView(
                label(
                    "Safe Mode is intentionally temporary. It lowers realtime polling pressure, uses Auto performance mode, and pauses automatic APK downloads for this process. Original preferences are restored on the next process.",
                    12, MUTED, false
                ).apply { setLineSpacing(0f, 1.12f) },
                spaced(22)
            )
            return scroll
        }

        private fun statusCard(): View {
            val p = prefs(this)
            val count = p.getInt(KEY_CRASH_COUNT, 0)
            val whenCaptured = p.getLong(KEY_LAST_CRASH_AT, 0L)
            val summary = p.getString(KEY_LAST_CRASH_SUMMARY, "No crash summary available.")
                ?: "No crash summary available."
            val safeCrash = p.getBoolean(KEY_CRASHED_WHILE_SAFE, false)

            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(PANEL, BORDER)
                addView(label(
                    if (count >= 2) "Repeated crash detected" else "Crash detected",
                    15,
                    if (count >= 2) WARNING else TEXT,
                    true
                ))
                addView(
                    label(
                        "Crashes in 10-minute window: $count\nLast crash: " +
                            formatTime(whenCaptured) +
                            if (safeCrash) "\nThe crash happened while Safe Mode was active." else "",
                        12, MUTED, false
                    ).apply { setLineSpacing(0f, 1.15f) },
                    spaced(7)
                )
                addView(label(summary, 12, TEXT, false).apply {
                    setLineSpacing(0f, 1.12f)
                }, spaced(10))
            }
        }

        private fun launchForum() {
            startActivity(Intent(this, HcfUI.StartupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }

        private fun showCrashDetails() {
            val details = label(readCrashReport(this), 12, TEXT, false).apply {
                setTextIsSelectable(true)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val scroll = ScrollView(this).apply {
                setBackgroundColor(PANEL)
                addView(details)
            }
            AlertDialog.Builder(this)
                .setTitle("HCF Crash Report")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show()
        }

        private fun copyCrashReport() {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                toast("Clipboard unavailable")
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("HCF crash report", readCrashReport(this)))
            toast("Crash report copied")
        }

        private fun shareCrashReport() {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "HCF Dev/Beta crash report")
                putExtra(Intent.EXTRA_TEXT, readCrashReport(this@SafeModeActivity))
            }
            try {
                startActivity(Intent.createChooser(share, "Share crash report"))
            } catch (_: Throwable) {
                toast("No compatible share app available")
            }
        }

        private fun openAndroidAppSettings() {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Throwable) {
                toast("Android app settings unavailable")
            }
        }

        private fun confirmCrashTest() {
            AlertDialog.Builder(this)
                .setTitle("Test crash recovery?")
                .setMessage("This intentionally crashes the Dev/Beta app. Reopen HCF afterward to verify Safe Mode.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Crash App") { _, _ ->
                    throw RuntimeException("HCF Safe Mode crash-handler test")
                }
                .show()
        }

        private fun primaryButton(text: String, listener: View.OnClickListener): Button =
            button(text, CYAN, Color.rgb(2, 18, 24), CYAN, listener)

        private fun secondaryButton(text: String, listener: View.OnClickListener): Button =
            button(text, PANEL, TEXT, BORDER, listener)

        private fun dangerButton(text: String, listener: View.OnClickListener): Button =
            button(text, PANEL, WARNING, WARNING, listener)

        private fun button(
            text: String,
            bg: Int,
            fg: Int,
            stroke: Int,
            listener: View.OnClickListener
        ): Button = Button(this).apply {
            this.text = text
            setTextColor(fg)
            textSize = 14f
            isAllCaps = false
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(bg, stroke)
            setOnClickListener(listener)
        }

        private fun sectionTitle(text: String): TextView = label(text, 15, CYAN, true)

        private fun label(text: String, sp: Int, color: Int, bold: Boolean): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(color)
                textSize = sp.toFloat()
                if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }

        private fun rounded(fill: Int, stroke: Int): GradientDrawable =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), stroke)
            }

        private fun wrap() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        private fun spaced(topDp: Int) = wrap().apply { topMargin = dp(topDp) }

        private fun dp(value: Int): Int =
            Math.round(value * resources.displayMetrics.density)

        private fun toast(text: String) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        override fun onBackPressed() {
            moveTaskToBack(true)
        }

        companion object {
            private val BG = Color.rgb(13, 16, 20)
            private val PANEL = Color.rgb(20, 28, 34)
            private val CYAN = Color.rgb(0, 184, 240)
            private val TEXT = Color.rgb(235, 247, 251)
            private val MUTED = Color.rgb(157, 176, 186)
            private val WARNING = Color.rgb(255, 183, 77)
            private val BORDER = Color.rgb(47, 72, 84)
        }
    }
}
