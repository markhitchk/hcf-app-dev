package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Process-wide crash context watchdog for HCF Dev/Beta.
 *
 * This is deliberately not a Service and does not create a notification. It lives with
 * the app process, follows Activity lifecycle state before/during/after startup, keeps a
 * light main-thread heartbeat, and wraps the process uncaught-exception chain so worker,
 * service/job, WebView-host, and UI-thread crashes can preserve useful last-known context
 * before the existing HcfSafeMode crash handler performs recovery bookkeeping.
 */
object HcfCrashWatchdog {
    private const val PREF_FILE = "hcf_app"
    private const val RECOVERY_DIR = "hcf-recovery"
    private const val CONTEXT_FILE = "watchdog-context.txt"
    private const val HEARTBEAT_MS = 5000L
    private const val CONTEXT_LIMIT = 16 * 1024

    private const val KEY_ACTIVE = "crash_watchdog_active"
    private const val KEY_PID = "crash_watchdog_pid"
    private const val KEY_PROCESS_STARTED_AT = "crash_watchdog_process_started_at"
    private const val KEY_PROCESS_STARTED_ELAPSED = "crash_watchdog_process_started_elapsed"
    private const val KEY_FOREGROUND = "crash_watchdog_foreground"
    private const val KEY_STAGE = "crash_watchdog_stage"
    private const val KEY_ACTIVITY = "crash_watchdog_activity"
    private const val KEY_LIFECYCLE = "crash_watchdog_lifecycle"
    private const val KEY_TRANSITION_AT = "crash_watchdog_transition_at"
    private const val KEY_HEARTBEAT_AT = "crash_watchdog_heartbeat_at"
    private const val KEY_HEARTBEAT_ELAPSED = "crash_watchdog_heartbeat_elapsed"
    private const val KEY_HEARTBEAT_SEQ = "crash_watchdog_heartbeat_seq"
    private const val KEY_LAST_CRASH_AT = "crash_watchdog_last_crash_at"
    private const val KEY_LAST_CRASH_THREAD = "crash_watchdog_last_crash_thread"
    private const val KEY_LAST_CRASH_STAGE = "crash_watchdog_last_crash_stage"
    private const val KEY_LAST_CRASH_ACTIVITY = "crash_watchdog_last_crash_activity"
    private const val KEY_LAST_CRASH_FOREGROUND = "crash_watchdog_last_crash_foreground"
    private const val KEY_LAST_CRASH_SUMMARY = "crash_watchdog_last_crash_summary"
    private const val KEY_LAST_CRASH_HEARTBEAT_AGE = "crash_watchdog_last_crash_heartbeat_age_ms"
    private const val KEY_LAST_CRASH_UPTIME = "crash_watchdog_last_crash_process_uptime_ms"

    private var installed = false
    private var startedActivities = 0
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val context = context ?: return true
            val app = context.applicationContext ?: context
            initializeProcessState(app)
            installContextHandler(app)
            startHeartbeat(app)
            (app as? Application)?.let(::registerLifecycle)
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Synchronized
    private fun installContextHandler(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is ContextHandler) {
            Thread.setDefaultUncaughtExceptionHandler(ContextHandler(context, previous))
        }
        installed = true
    }

    private class ContextHandler(
        context: Context?,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        private val context: Context? = context?.applicationContext ?: context
        private var handling = false

        @Synchronized
        override fun uncaughtException(thread: Thread, error: Throwable) {
            if (!handling) {
                handling = true
                try {
                    recordCrashContext(context, thread, error)
                } catch (_: Throwable) {
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun initializeProcessState(context: Context) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_PID, android.os.Process.myPid())
            .putLong(KEY_PROCESS_STARTED_AT, now)
            .putLong(KEY_PROCESS_STARTED_ELAPSED, elapsed)
            .putBoolean(KEY_FOREGROUND, false)
            .putString(KEY_STAGE, "process-start")
            .putString(KEY_ACTIVITY, "none")
            .putString(KEY_LIFECYCLE, "provider-created")
            .putLong(KEY_TRANSITION_AT, now)
            .putLong(KEY_HEARTBEAT_AT, now)
            .putLong(KEY_HEARTBEAT_ELAPSED, elapsed)
            .putInt(KEY_HEARTBEAT_SEQ, 0)
            .commit()
    }

    private fun registerLifecycle(app: Application) {
        val context: Context = app.applicationContext ?: app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) =
                updateActivityState(context, activity, "created", false, null)

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                updateActivityState(context, activity, "started", true, null)
            }

            override fun onActivityResumed(activity: Activity) =
                updateActivityState(context, activity, "resumed", true, classifyStage(activity))

            override fun onActivityPaused(activity: Activity) =
                updateActivityState(context, activity, "paused", startedActivities > 0, null)

            override fun onActivityStopped(activity: Activity) {
                startedActivities = maxOf(0, startedActivities - 1)
                val foreground = startedActivities > 0
                updateActivityState(
                    context,
                    activity,
                    if (foreground) "stopped-transition" else "background",
                    foreground,
                    if (foreground) null else "background"
                )
            }

            override fun onActivityDestroyed(activity: Activity) =
                updateActivityState(context, activity, "destroyed", startedActivities > 0, null)

            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    private fun updateActivityState(
        context: Context?,
        activity: Activity?,
        lifecycle: String,
        foreground: Boolean,
        explicitStage: String?
    ) {
        if (context == null) return
        val activityName = activity?.javaClass?.name ?: "none"
        val p = prefs(context)
        val stage = explicitStage ?: p.getString(KEY_STAGE, "process-start")
        p.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_PID, android.os.Process.myPid())
            .putBoolean(KEY_FOREGROUND, foreground)
            .putString(KEY_STAGE, stage ?: "unknown")
            .putString(KEY_ACTIVITY, activityName)
            .putString(KEY_LIFECYCLE, lifecycle)
            .putLong(KEY_TRANSITION_AT, System.currentTimeMillis())
            .apply()
    }

    private fun classifyStage(activity: Activity?): String = when (activity) {
        null -> "unknown"
        is HcfForum.WelcomeActivity -> "welcome"
        is HcfForum.SetupActivity -> "onboarding"
        is HcfUI.StartupActivity, is HcfUI.StartupMainActivity -> "startup-loading"
        is HcfForum.MainActivity -> "forum-loaded"
        is HcfSubActivities.SettingsActivity -> "settings"
        is HcfSubActivities.LogsActivity -> "logs-diagnostics"
        is HcfSafeMode.SafeModeActivity -> "recovery"
        is HcfSafeMode.EntryActivity -> "launcher-route"
        else -> "activity:" + activity.javaClass.simpleName
    }

    @Synchronized
    private fun startHeartbeat(context: Context) {
        if (heartbeatHandler != null) return
        val handler = Handler(Looper.getMainLooper())
        heartbeatHandler = handler
        heartbeatRunnable = object : Runnable {
            override fun run() {
                try {
                    val p = prefs(context)
                    val seq = p.getInt(KEY_HEARTBEAT_SEQ, 0) + 1
                    p.edit()
                        .putBoolean(KEY_ACTIVE, true)
                        .putInt(KEY_PID, android.os.Process.myPid())
                        .putLong(KEY_HEARTBEAT_AT, System.currentTimeMillis())
                        .putLong(KEY_HEARTBEAT_ELAPSED, SystemClock.elapsedRealtime())
                        .putInt(KEY_HEARTBEAT_SEQ, seq)
                        .apply()
                } catch (_: Throwable) {
                }
                heartbeatHandler?.postDelayed(this, HEARTBEAT_MS)
            }
        }
        handler.post(heartbeatRunnable!!)
    }

    private fun recordCrashContext(context: Context?, thread: Thread?, error: Throwable?) {
        if (context == null) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val processStarted = p.getLong(KEY_PROCESS_STARTED_ELAPSED, elapsed)
        val heartbeat = p.getLong(KEY_HEARTBEAT_ELAPSED, elapsed)
        val uptime = maxOf(0L, elapsed - processStarted)
        val heartbeatAge = maxOf(0L, elapsed - heartbeat)
        val stage = p.getString(KEY_STAGE, "unknown")
        val activity = p.getString(KEY_ACTIVITY, "none")
        val foreground = p.getBoolean(KEY_FOREGROUND, false)
        val summary = summarize(error)
        val threadName = thread?.name ?: "unknown"

        p.edit()
            .putLong(KEY_LAST_CRASH_AT, now)
            .putString(KEY_LAST_CRASH_THREAD, threadName)
            .putString(KEY_LAST_CRASH_STAGE, stage)
            .putString(KEY_LAST_CRASH_ACTIVITY, activity)
            .putBoolean(KEY_LAST_CRASH_FOREGROUND, foreground)
            .putString(KEY_LAST_CRASH_SUMMARY, summary)
            .putLong(KEY_LAST_CRASH_HEARTBEAT_AGE, heartbeatAge)
            .putLong(KEY_LAST_CRASH_UPTIME, uptime)
            .commit()

        writeContextFile(
            context, now, stage, activity, foreground, threadName, summary,
            heartbeatAge, uptime, p.getString(KEY_LIFECYCLE, "unknown")
        )
    }

    private fun writeContextFile(
        context: Context,
        whenCaptured: Long,
        stage: String?,
        activity: String?,
        foreground: Boolean,
        thread: String,
        summary: String,
        heartbeatAge: Long,
        uptime: Long,
        lifecycle: String?
    ) {
        val dir = File(context.filesDir, RECOVERY_DIR)
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, CONTEXT_FILE)
        val text = buildString {
            append("HCF Persistent Crash Watchdog\n")
            append("Captured: ").append(whenCaptured).append('\n')
            append("Process PID: ").append(android.os.Process.myPid()).append('\n')
            append("Process uptime ms: ").append(uptime).append('\n')
            append("App state: ").append(if (foreground) "foreground" else "background").append('\n')
            append("Stage: ").append(stage).append('\n')
            append("Activity: ").append(activity).append('\n')
            append("Lifecycle: ").append(lifecycle).append('\n')
            append("Thread: ").append(thread).append('\n')
            append("Main heartbeat age ms: ").append(heartbeatAge).append('\n')
            append("Exception: ").append(summary).append('\n')
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val length = minOf(bytes.size, CONTEXT_LIMIT)
        try {
            FileOutputStream(file, false).use {
                it.write(bytes, 0, length)
                it.flush()
            }
        } catch (_: Throwable) {
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
}
