package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.WeakHashMap

/** Adds a developer recovery dashboard to Settings -> Developer Tools and skins real recovery like App Settings. */
object HcfSafeModeSettings {
    private const val INJECTED_TAG = "hcf_safe_mode_developer_tools"
    private const val RECOVERY_SKIN_TAG = "hcf_recovery_settings_skin"
    private const val PREF_FILE = "hcf_app"
    private val LISTENERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val app = context?.applicationContext as? Application ?: return true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    when (activity) {
                        is HcfSubActivities.SettingsActivity -> attach(activity)
                        is HcfSafeMode.SafeModeActivity ->
                            activity.window?.decorView?.post { decorateRecoveryActivity(activity) }
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    when (activity) {
                        is HcfSubActivities.SettingsActivity -> {
                            attach(activity)
                            activity.window?.decorView?.let { root ->
                                root.post { inject(activity, root) }
                            }
                        }
                        is HcfSafeMode.SafeModeActivity ->
                            activity.window?.decorView?.post { decorateRecoveryActivity(activity) }
                    }
                }

                override fun onActivityDestroyed(activity: Activity) = detach(activity)
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            })
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun attach(activity: Activity) {
        synchronized(LISTENERS) {
            if (LISTENERS.containsKey(activity)) return
            val root = activity.window?.decorView ?: return
            val listener = ViewTreeObserver.OnGlobalLayoutListener { inject(activity, root) }
            LISTENERS[activity] = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            root.post { inject(activity, root) }
        }
    }

    private fun detach(activity: Activity) {
        val listener = synchronized(LISTENERS) { LISTENERS.remove(activity) } ?: return
        try {
            val observer = activity.window?.decorView?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnGlobalLayoutListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun inject(activity: Activity?, root: View?) {
        if (activity == null || activity.isFinishing || root == null) return
        val playgroundButton = findButton(root, "Open UI Playground") ?: return
        val card = playgroundButton.parent as? LinearLayout ?: return
        for (i in 0 until card.childCount) {
            if (INJECTED_TAG == card.getChildAt(i).tag) return
        }

        val playgroundIndex = card.indexOfChild(playgroundButton)
        var insertionIndex = playgroundIndex
        for (i in playgroundIndex - 1 downTo 0) {
            if (containsText(card.getChildAt(i), "UI Playground")) {
                insertionIndex = i
                break
            }
        }

        val section = buildRecoverySection(activity)
        section.tag = INJECTED_TAG
        card.addView(section, insertionIndex)
        AppLogger.info(activity, "developer_recovery_added", "developer_tools")
    }

    private fun buildRecoverySection(activity: Activity): LinearLayout {
        val section = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), dp(activity, 14), dp(activity, 4), dp(activity, 5))
        }

        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.fa_shield)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.hcf_cyan_bright))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)).apply {
            rightMargin = dp(activity, 10)
        })

        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(activity).apply {
            text = "Developer Recovery"
            setTextColor(activity.getColor(R.color.hcf_text))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        labels.addView(TextView(activity).apply {
            text = "Safe Mode status, crash-recovery preview, diagnostics, and Android recovery shortcuts."
            setTextColor(activity.getColor(R.color.hcf_muted))
            textSize = 10f
            setLineSpacing(0f, 1.08f)
        })
        header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        section.addView(header)

        val status = TextView(activity).apply {
            text = recoveryStatus(activity)
            setTextColor(activity.getColor(R.color.hcf_muted))
            textSize = 10.5f
            setLineSpacing(0f, 1.12f)
            setBackgroundResource(R.drawable.quick_action_background)
            setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
        }
        section.addView(status, spaced(activity, 7, ViewGroup.LayoutParams.WRAP_CONTENT))

        section.addView(
            subsectionLabel(activity, "Recovery screen"),
            spaced(activity, 12, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        section.addView(
            actionButton(activity, "Preview Normal Crash Recovery", R.drawable.fa_shield) {
                AppLogger.info(activity, "normal_recovery_preview", "developer_tools")
                Toast.makeText(
                    activity,
                    "This is the same recovery screen HCF shows after a crash.",
                    Toast.LENGTH_LONG
                ).show()
                activity.startActivity(Intent(activity, HcfSafeMode.SafeModeActivity::class.java))
            },
            spaced(activity, 7, dp(activity, 52))
        )

        section.addView(
            subsectionLabel(activity, "Diagnostics"),
            spaced(activity, 12, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        section.addView(
            actionButton(activity, "Open Logs & Diagnostics", R.drawable.fa_bug) {
                AppLogger.info(activity, "recovery_logs_open", "developer_tools")
                activity.startActivity(Intent(activity, HcfSubActivities.LogsActivity::class.java))
            },
            spaced(activity, 7, dp(activity, 52))
        )
        section.addView(
            actionButton(activity, "Open Android App Settings", R.drawable.fa_gear) {
                AppLogger.info(activity, "recovery_android_settings", "developer_tools")
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            },
            spaced(activity, 7, dp(activity, 52))
        )

        section.addView(TextView(activity).apply {
            text = "Normal recovery is normally automatic after an uncaught crash. The preview above lets Dev/Beta builds open that exact screen without crashing first."
            setTextColor(activity.getColor(R.color.hcf_hint))
            textSize = 9.5f
            setLineSpacing(0f, 1.08f)
            setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), 0)
        })
        return section
    }

    private fun decorateRecoveryActivity(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val androidContent = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (findTaggedView(androidContent, RECOVERY_SKIN_TAG) != null) return

        val scroll = findScrollView(androidContent) ?: return
        if (scroll.childCount == 0) return
        val recoveryContent = scroll.getChildAt(0) as? LinearLayout ?: return

        activity.window?.statusBarColor = activity.getColor(R.color.hcf_bg)
        activity.window?.navigationBarColor = activity.getColor(R.color.hcf_bg)
        scroll.setBackgroundColor(activity.getColor(R.color.hcf_bg))
        recoveryContent.setBackgroundColor(activity.getColor(R.color.hcf_bg))
        recoveryContent.setPadding(
            dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 28)
        )

        hideLegacyRecoveryHeading(recoveryContent)
        styleRecoveryTree(activity, recoveryContent)
        insertRecoverySectionHeaders(activity, recoveryContent)

        val parent = scroll.parent as? ViewGroup ?: return
        parent.removeView(scroll)

        val shell = LinearLayout(activity).apply {
            tag = RECOVERY_SKIN_TAG
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.hcf_bg))
        }
        shell.addView(
            buildRecoveryAppHeader(activity),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        shell.addView(View(activity).apply {
            setBackgroundColor(activity.getColor(R.color.hcf_border))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(activity, 1))))
        shell.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        activity.setContentView(shell)
        AppLogger.info(activity, "recovery_ui", "settings_subpage_skin")
    }

    private fun buildRecoveryAppHeader(activity: Activity): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 12), dp(activity, 9))
            setBackgroundColor(activity.getColor(R.color.hcf_bg))
        }

        header.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.fa_arrow_left)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.hcf_cyan_bright))
            setBackgroundResource(R.drawable.chrome_button_background)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            contentDescription = "Back"
            setOnClickListener { activity.finish() }
        }, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 52)))

        header.addView(ImageView(activity).apply {
            setImageResource(R.drawable.htg_app_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Harley's Clan Forum"
        }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply {
            leftMargin = dp(activity, 9)
            rightMargin = dp(activity, 10)
        })

        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(activity).apply {
            text = "Recovery & Safe Mode"
            setTextColor(activity.getColor(R.color.hcf_text))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        labels.addView(TextView(activity).apply {
            text = "Harley's Clan Forum v" + BuildInfo.VERSION + " | Development Build / Beta"
            setTextColor(activity.getColor(R.color.hcf_cyan_bright))
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 4) })

        header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return header
    }

    private fun hideLegacyRecoveryHeading(root: LinearLayout) {
        var hidden = 0
        for (i in 0 until root.childCount) {
            if (hidden >= 3) break
            val child = root.getChildAt(i) as? TextView ?: continue
            val value = child.text?.toString() ?: continue
            if (
                value.contains("HARLEY'S CLAN FORUM") ||
                value == "Safe Mode" ||
                value.startsWith("The previous app run ended unexpectedly")
            ) {
                child.visibility = View.GONE
                hidden++
            }
        }
    }

    private fun styleRecoveryTree(activity: Activity, view: View) {
        when (view) {
            is Button -> styleRecoveryButton(activity, view)
            is TextView -> styleRecoveryText(activity, view)
        }
        val group = view as? ViewGroup ?: return
        for (i in 0 until group.childCount) styleRecoveryTree(activity, group.getChildAt(i))
    }

    private fun styleRecoveryButton(activity: Activity, button: Button) {
        UiButtons.normalizeText(button)
        button.setBackgroundResource(R.drawable.quick_action_background)
        button.isAllCaps = false
        button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        button.setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
        button.minimumWidth = 0
        button.minHeight = dp(activity, 48)
        button.textSize = 12f

        val text = button.text?.toString().orEmpty()
        button.setTextColor(
            activity.getColor(
                if (text.contains("Test Crash Handler")) R.color.hcf_muted
                else R.color.hcf_accent_text
            )
        )
        FaIcons.applyStart(button, text)
        button.layoutParams?.let {
            it.height = dp(activity, 52)
            button.layoutParams = it
        }
    }

    private fun styleRecoveryText(activity: Activity, textView: TextView) {
        val text = textView.text?.toString() ?: return
        if (text == "Crash report" || text == "Recovery tools" || text == "Developer test") {
            textView.setTextColor(activity.getColor(R.color.hcf_cyan_bright))
            textView.textSize = 11f
            textView.setTypeface(null, Typeface.BOLD)
            textView.includeFontPadding = false
            textView.setPadding(dp(activity, 4), dp(activity, 7), dp(activity, 4), dp(activity, 2))
            return
        }
        if (text.contains("Safe Mode is intentionally temporary")) {
            textView.setTextColor(activity.getColor(R.color.hcf_hint))
            textView.textSize = 10f
            return
        }
        if (text.contains("Crash detected") || text.contains("Repeated crash detected")) {
            textView.setTextColor(activity.getColor(R.color.hcf_text))
            textView.textSize = 13f
            textView.setTypeface(null, Typeface.BOLD)
        }
    }

    private fun insertRecoverySectionHeaders(activity: Activity, root: LinearLayout) {
        if (containsDirectText(root, "Recovery status")) return
        findDirectChildContaining(root, "Crash detected", "Repeated crash detected")?.let { statusCard ->
            val index = root.indexOfChild(statusCard)
            root.addView(
                settingsSectionHeader(
                    activity,
                    "Recovery status",
                    "Crash state and the last recorded recovery event",
                    R.drawable.fa_shield
                ),
                index
            )
            statusCard.setBackgroundResource(R.drawable.quick_action_background)
            statusCard.setPadding(
                dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12)
            )
        }

        val safeStart = findButton(root, "Start in Safe Mode")
        if (safeStart != null && safeStart.parent === root) {
            root.addView(
                settingsSectionHeader(
                    activity,
                    "Startup mode",
                    "Choose a protected startup or retry the normal app runtime",
                    R.drawable.fa_shield
                ),
                root.indexOfChild(safeStart)
            )
        }
    }

    private fun settingsSectionHeader(
        activity: Activity,
        titleText: String,
        subtitleText: String,
        iconRes: Int
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), dp(activity, 13), dp(activity, 4), dp(activity, 5))
        }
        row.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.hcf_cyan_bright))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)).apply {
            rightMargin = dp(activity, 10)
        })

        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(activity).apply {
            text = titleText
            setTextColor(activity.getColor(R.color.hcf_text))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        labels.addView(TextView(activity).apply {
            text = subtitleText
            setTextColor(activity.getColor(R.color.hcf_muted))
            textSize = 9.5f
            includeFontPadding = false
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 2) })

        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun findScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findScrollView(group.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findDirectChildContaining(root: LinearLayout, first: String, second: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (containsText(child, first) || containsText(child, second)) return child
        }
        return null
    }

    private fun containsDirectText(root: LinearLayout, text: String): Boolean {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && text.contentEquals(child.text)) return true
            if (child is ViewGroup && containsText(child, text)) return true
        }
        return false
    }

    private fun recoveryStatus(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val active =
            prefs.getBoolean("safe_mode_active", false) &&
                prefs.getInt("safe_mode_session_pid", -1) == android.os.Process.myPid()
        val pending = prefs.getBoolean("safe_mode_pending", false)
        val crashes = prefs.getInt("safe_mode_crash_count", 0)
        val summary = prefs.getString("safe_mode_last_crash_summary", "")
        return buildString {
            append("Safe Mode: ").append(if (active) "ACTIVE" else "Inactive")
            append("\nCrash recovery pending: ").append(if (pending) "Yes" else "No")
            append("\nRecent crash count: ").append(crashes)
            if (!summary.isNullOrBlank()) {
                var clean = summary.replace('\n', ' ').replace('\r', ' ').trim()
                if (clean.length > 120) clean = clean.substring(0, 120) + "…"
                append("\nLast crash: ").append(clean)
            }
        }
    }

    private fun subsectionLabel(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.hcf_cyan_bright))
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        }

    private fun actionButton(
        activity: Activity,
        text: String,
        iconRes: Int,
        listener: View.OnClickListener
    ): Button = Button(activity).apply {
        UiButtons.normalizeText(this)
        this.text = text
        setTextColor(activity.getColor(R.color.hcf_accent_text))
        setBackgroundResource(R.drawable.quick_action_background)
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
        FaIcons.applyStart(this, iconRes)
        setOnClickListener(listener)
    }

    private fun spaced(context: Context, topDp: Int, height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(context, topDp)
        }

    private fun findButton(view: View, text: String): View? {
        if (view is Button && text.contentEquals(view.text)) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findButton(group.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun findTaggedView(view: View, tag: String): View? {
        if (tag == view.tag) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findTaggedView(group.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun containsText(view: View, text: String): Boolean {
        if (view is TextView && view.text?.toString()?.contains(text) == true) return true
        val group = view as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            if (containsText(group.getChildAt(i), text)) return true
        }
        return false
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}
