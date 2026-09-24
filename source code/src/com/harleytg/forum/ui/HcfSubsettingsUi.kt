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
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.WeakHashMap

/** Account & Security subsettings owner, including embedded HCF Authenticator. */
object HcfSubsettingsUi {
    private const val TAG = "hcf_account_controls_subsettings_ui_v5_nearata_settings"
    private const val TAG_2FA_SUMMARY = "$TAG:2fa_summary"
    private const val PREF_PREFIX = "account_controls_subsetting_"
    private val MAIN = Handler(Looper.getMainLooper())
    private val OBSERVERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val AUTH_PANES = WeakHashMap<Activity, AuthenticatorPane>()
    private var installed = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val app = context?.applicationContext as? Application ?: return true
            install(app)
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
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (isSettings(activity)) {
                    installObserver(activity)
                    scheduleRender(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (!isSettings(activity)) return
                installObserver(activity)
                scheduleRender(activity)
                AUTH_PANES[activity]?.apply {
                    reload()
                    start()
                }
            }

            override fun onActivityPaused(activity: Activity) {
                AUTH_PANES[activity]?.stop()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                removeObserver(activity)
                AUTH_PANES.remove(activity)?.stop()
            }
        })
    }

    private fun isSettings(activity: Activity?): Boolean =
        activity != null &&
            "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity" == activity.javaClass.name

    private fun installObserver(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        synchronized(OBSERVERS) {
            if (OBSERVERS.containsKey(activity)) return
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!activity.isFinishing) render(activity)
            }
            observer.addOnGlobalLayoutListener(listener)
            OBSERVERS[activity] = listener
        }
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
        MAIN.postDelayed({ render(activity) }, 60L)
        MAIN.postDelayed({ render(activity) }, 180L)
        MAIN.postDelayed({ render(activity) }, 420L)
    }

    private fun render(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return

        if (root.findViewWithTag<View>(TAG) != null) {
            AUTH_PANES[activity]?.refreshDisplay()
            return
        }

        val profileText = findText(root, "Open My Forum Profile")
        val securityText = findText(root, "Open Account Security")
        if (profileText == null || securityText == null) {
            AUTH_PANES.remove(activity)?.stop()
            return
        }

        val card = commonCardAncestor(profileText, securityText) as? LinearLayout ?: return
        val profileAction = clickableAncestor(profileText, card) ?: return
        val securityAction = clickableAncestor(securityText, card) ?: return
        if (profileAction === securityAction) return

        val identity = ForumIdentity.load(activity)
        val security = ForumSecurity.load(activity)
        if (!identity.loggedIn) return

        AUTH_PANES.remove(activity)?.stop()

        detach(profileAction)
        detach(securityAction)
        card.removeAllViews()
        card.tag = TAG
        card.setPadding(0, 0, 0, 0)

        val handle =
            if (identity.username.isNullOrBlank()) identity.identityLabel()
            else "@" + identity.username.trim()

        card.addView(
            buildProfileSubsetting(activity, profileAction, handle),
            lp(activity, -1, -2, 0, 9)
        )
        card.addView(
            buildSecuritySubsetting(activity, securityAction, security),
            lp(activity, -1, -2, 0, 9)
        )

        val pane = AuthenticatorPane(activity, identity, security)
        AUTH_PANES[activity] = pane
        val twoFactor = subsetting(
            activity,
            "two_factor",
            "Two-Factor Authentication",
            pane.summary(),
            pane.build(),
            true
        )
        findText(twoFactor, pane.summary())?.tag = TAG_2FA_SUMMARY
        card.addView(twoFactor, lp(activity, -1, -2, 0, 0))
        pane.start()
    }

    private fun buildProfileSubsetting(
        activity: Activity,
        profileAction: View,
        handle: String
    ): View {
        val content = body(activity)
        content.addView(detail(
            activity,
            "Open your public Harley's Clan Forum profile, activity and account identity."
        ))
        styleExistingAction(activity, profileAction)
        content.addView(profileAction, lp(activity, -1, dp(activity, 52), 10, 0))
        return subsetting(
            activity,
            "profile",
            "Forum Profile",
            "Signed in as $handle",
            content,
            true
        )
    }

    private fun buildSecuritySubsetting(
        activity: Activity,
        securityAction: View,
        security: ForumSecurity.Snapshot
    ): View {
        val content = body(activity)
        content.addView(securityStatusRow(
            activity,
            "Password",
            if (security.passwordControls) "Available" else forumState(security),
            security.passwordControls
        ))
        content.addView(securityStatusRow(
            activity,
            "Email",
            if (security.emailControls) "Available" else forumState(security),
            security.emailControls
        ), lp(activity, -1, -2, 7, 0))
        content.addView(securityStatusRow(
            activity,
            "Active sessions",
            if (security.sessionCount > 0) security.sessionCount.toString()
            else if (security.seen) "None synced" else "Sync needed",
            security.sessionCount > 0
        ), lp(activity, -1, -2, 7, 0))
        if (!security.seen) {
            content.addView(detail(
                activity,
                "Open Account Security once to sync the controls available for this forum account."
            ), lp(activity, -1, -2, 9, 0))
        }
        styleExistingAction(activity, securityAction)
        content.addView(securityAction, lp(activity, -1, dp(activity, 52), 11, 0))
        return subsetting(
            activity,
            "security",
            "Password, Email & Sessions",
            if (security.seen) "Password, email and session controls"
            else "Open once to sync forum security controls",
            content,
            false
        )
    }

    private class AuthenticatorPane(
        private val activity: Activity,
        private val identity: ForumIdentity.Snapshot,
        private val security: ForumSecurity.Snapshot
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var config: HcfAuthenticator.Config? = null
        private var lastCode = ""
        private var localStatus: TextView? = null
        private var code: TextView? = null
        private var countdown: TextView? = null
        private var progress: ProgressBar? = null
        private var accountInput: EditText? = null
        private var secretInput: EditText? = null
        private var removeButton: Button? = null
        private var running = false

        private val ticker = object : Runnable {
            override fun run() {
                if (!running) return
                renderCode()
                handler.postDelayed(this, 250L)
            }
        }

        init {
            reload()
        }

        fun summary(): String =
            if (ready()) "HCF Authenticator ready • Nearata forum 2FA"
            else if (security.twoFactorControls)
                "HCF Authenticator • set up from User Settings"
            else
                "HCF Authenticator • open forum User Settings"

        fun build(): View {
            val content = body(activity)
            content.addView(text(activity, "HCF AUTHENTICATOR • FULL SETTINGS", 9f, cyan(activity)).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            content.addView(securityStatusRow(
                activity,
                "Nearata 2FA controls",
                if (security.twoFactorControls) "Detected" else "Open forum",
                security.twoFactorControls
            ), lp(activity, -1, -2, 7, 0))

            localStatus = authStatus(activity, ready())
            content.addView(localStatus, lp(activity, -1, -2, 7, 0))
            content.addView(currentCodePanel(), lp(activity, -1, -2, 10, 0))
            content.addView(setupPanel(), lp(activity, -1, -2, 10, 0))
            content.addView(nearataPanel(), lp(activity, -1, -2, 10, 0))
            content.addView(managePanel(), lp(activity, -1, -2, 10, 0))
            content.addView(text(
                activity,
                "RFC 6238 TOTP • Android Keystore • codes work offline after setup",
                9f,
                color(activity, R.color.hcf_muted, Color.GRAY)
            ).apply { gravity = Gravity.CENTER }, lp(activity, -1, -2, 8, 0))
            return content
        }

        private fun currentCodePanel(): View {
            val panel = innerPanel(activity)
            panel.addView(sectionLabel(activity, "CURRENT 6-DIGIT PASSCODE"))
            code = text(
                activity,
                "--- ---",
                32f,
                color(activity, R.color.hcf_text, Color.WHITE)
            ).apply {
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
            }
            panel.addView(code, lp(activity, -1, -2, 5, 0))
            countdown = text(
                activity,
                "Add the Nearata QR code or Setup key to generate a passcode.",
                10f,
                color(activity, R.color.hcf_muted, Color.LTGRAY)
            ).apply { gravity = Gravity.CENTER }
            panel.addView(countdown, lp(activity, -1, -2, 3, 0))
            progress = ProgressBar(
                activity,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply { max = 30 }
            panel.addView(progress, lp(activity, -1, dp(activity, 5), 9, 0))
            panel.addView(actionButton(activity, "Copy current passcode", true).apply {
                setOnClickListener { copyCode() }
            }, lp(activity, -1, dp(activity, 48), 10, 0))
            return panel
        }

        private fun setupPanel(): View {
            val panel = innerPanel(activity)
            panel.addView(sectionLabel(activity, "SET UP HCF AUTHENTICATOR"))
            panel.addView(detail(
                activity,
                "Open forum User Settings at /settings, then Two-Factor Authentication. Nearata shows a QR code and a Setup key. Use either one here."
            ))

            panel.addView(actionButton(
                activity,
                "Scan or import QR code from User Settings",
                true
            ).apply {
                setOnClickListener {
                    activity.startActivity(
                        Intent(activity, HcfAuthenticatorSettingsQrActivity::class.java)
                    )
                }
            }, lp(activity, -1, dp(activity, 50), 11, 0))

            panel.addView(text(
                activity,
                "Nearata does not show a copyable otpauth link. The provisioning data is contained inside its QR code, so you only need the QR code or Setup key shown on the forum.",
                9f,
                color(activity, R.color.hcf_muted, Color.GRAY)
            ).apply { setLineSpacing(0f, 1.08f) }, lp(activity, -1, -2, 9, 0))

            panel.addView(text(activity, "MANUAL SETUP KEY", 9f, cyan(activity)).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, lp(activity, -1, -2, 13, 0))

            accountInput = input(activity, "Forum account name (optional)").apply {
                if (!identity.username.isNullOrBlank()) setText(identity.username.trim())
            }
            panel.addView(accountInput, lp(activity, -1, dp(activity, 50), 8, 0))
            secretInput = input(activity, "Setup key").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            panel.addView(secretInput, lp(activity, -1, dp(activity, 50), 8, 0))
            panel.addView(actionButton(
                activity,
                if (ready()) "Replace Setup key" else "Save Setup key",
                true
            ).apply {
                setOnClickListener { saveManual() }
            }, lp(activity, -1, dp(activity, 48), 9, 0))
            return panel
        }

        private fun nearataPanel(): View {
            val panel = innerPanel(activity)
            panel.addView(sectionLabel(activity, "FINISH IN FORUM USER SETTINGS"))
            panel.addView(detail(
                activity,
                "Nearata TwoFactor lives on the forum User Settings page. After adding the same QR/Setup key to HCF, Nearata requires your forum password and current 6-digit passcode before it enables 2FA."
            ))
            panel.addView(stepRow(
                activity,
                "1",
                "Open forum User Settings (/settings), then Two-Factor Authentication."
            ), lp(activity, -1, -2, 9, 0))
            panel.addView(stepRow(
                activity,
                "2",
                "Scan/import the QR code shown there or enter its Setup key in HCF."
            ), lp(activity, -1, -2, 7, 0))
            panel.addView(stepRow(
                activity,
                "3",
                "Copy the current 6-digit HCF passcode."
            ), lp(activity, -1, -2, 7, 0))
            panel.addView(stepRow(
                activity,
                "4",
                "Return to Nearata, enter your forum password + passcode, then press Enable."
            ), lp(activity, -1, -2, 7, 0))
            panel.addView(stepRow(
                activity,
                "5",
                "Save or copy the backup codes Nearata provides after activation."
            ), lp(activity, -1, -2, 7, 0))
            panel.addView(actionButton(activity, "Open Forum User Settings", false).apply {
                setOnClickListener { openForumSettings(activity) }
            }, lp(activity, -1, dp(activity, 48), 10, 0))
            return panel
        }

        private fun managePanel(): View {
            val panel = innerPanel(activity)
            panel.addView(sectionLabel(activity, "LOCAL AUTHENTICATOR STORAGE"))
            panel.addView(detail(
                activity,
                "Only the TOTP setup secret is stored by HCF, encrypted with Android Keystore. Your forum password is never stored here."
            ))
            removeButton = actionButton(
                activity,
                "Remove authenticator from this device",
                false
            ).apply {
                setTextColor(Color.rgb(255, 77, 87))
                isEnabled = ready()
                setOnClickListener { remove() }
            }
            panel.addView(removeButton, lp(activity, -1, dp(activity, 48), 10, 0))
            panel.addView(text(
                activity,
                "Removing the local key does not disable Nearata 2FA on the forum. Keep your Nearata backup codes safe.",
                9f,
                color(activity, R.color.hcf_muted, Color.GRAY)
            ).apply { gravity = Gravity.CENTER }, lp(activity, -1, -2, 8, 0))
            return panel
        }

        fun reload() {
            config = try {
                HcfAuthenticator.Vault.load(activity)
            } catch (_: Throwable) {
                null
            }
            refreshDisplay()
        }

        fun refreshDisplay() {
            localStatus?.let { status ->
                val ready = ready()
                val stateColor =
                    if (ready) cyan(activity)
                    else color(activity, R.color.hcf_muted, Color.LTGRAY)
                status.text =
                    if (ready) "HCF Authenticator configured on this device"
                    else "HCF Authenticator not configured on this device"
                status.setTextColor(stateColor)
                status.background = roundRect(
                    activity,
                    alpha(stateColor, 18),
                    alpha(stateColor, 100),
                    10
                )
                removeButton?.isEnabled = ready
            }

            activity.findViewById<View>(android.R.id.content)
                ?.findViewWithTag<View>(TAG_2FA_SUMMARY)
                ?.let { if (it is TextView) it.text = summary() }
            renderCode()
        }

        fun start() {
            running = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(ticker)
        }

        private fun ready(): Boolean = config?.secret?.isNotEmpty() == true

        private fun renderCode() {
            val codeView = code ?: return
            val countdownView = countdown ?: return
            val progressView = progress ?: return

            val active = config
            if (active == null || active.secret.isEmpty()) {
                lastCode = ""
                codeView.text = "--- ---"
                countdownView.text =
                    "Add the Nearata QR code or Setup key to generate a passcode."
                progressView.max = 30
                progressView.progress = 0
                return
            }
            try {
                val now = System.currentTimeMillis() / 1000L
                lastCode = HcfAuthenticator.Totp.generate(active, now)
                codeView.text = formatCode(lastCode)
                val elapsed = (now % active.period).toInt()
                val remaining = active.period - elapsed
                progressView.max = active.period
                progressView.progress = elapsed
                countdownView.text =
                    "New passcode in $remaining second" +
                    (if (remaining == 1) "" else "s") +
                    " • works offline"
            } catch (_: Throwable) {
                lastCode = ""
                codeView.text = "--- ---"
                countdownView.text =
                    "Unable to generate a passcode. Check the Setup key and device time."
            }
        }

        private fun copyCode() {
            renderCode()
            if (lastCode.isEmpty()) {
                Toast.makeText(
                    activity,
                    "No authentication passcode is available yet.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.let {
                it.setPrimaryClip(
                    ClipData.newPlainText("HCF authentication passcode", lastCode)
                )
                Toast.makeText(
                    activity,
                    "6-digit passcode copied.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun saveManual() {
            val field = secretInput ?: return
            val raw = field.text.toString().trim()
            if (raw.isEmpty()) {
                field.error = "Enter the Setup key shown by Nearata"
                return
            }
            try {
                val label = accountInput?.text?.toString()?.trim().orEmpty()
                val incoming = HcfAuthenticator.Config.manual(raw, label)
                HcfAuthenticator.Base32.decode(incoming.secret)
                confirmSave(incoming, "manual Setup key")
            } catch (_: Throwable) {
                field.error = "Invalid Setup key"
            }
        }

        private fun confirmSave(
            incoming: HcfAuthenticator.Config,
            source: String
        ) {
            AlertDialog.Builder(activity)
                .setTitle(
                    if (ready()) "Replace HCF Authenticator?"
                    else "Set up HCF Authenticator?"
                )
                .setMessage(
                    "Source: $source\n\nThe setup secret will be encrypted with Android Keystore and stored only on this device."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(if (ready()) "Replace" else "Save") { _, _ ->
                    save(incoming)
                }
                .show()
        }

        private fun save(incoming: HcfAuthenticator.Config) {
            try {
                HcfAuthenticator.Vault.save(activity, incoming)
                config = incoming
                secretInput?.setText("")
                refreshDisplay()
                Toast.makeText(
                    activity,
                    "HCF Authenticator configured.",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) {
                Toast.makeText(
                    activity,
                    "Could not securely save this authenticator.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun remove() {
            if (!ready()) return
            AlertDialog.Builder(activity)
                .setTitle("Remove HCF Authenticator from this device?")
                .setMessage(
                    "This deletes the encrypted Setup key from this device. It does not disable Nearata TwoFactor on the forum."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    HcfAuthenticator.Vault.clear(activity)
                    config = null
                    lastCode = ""
                    refreshDisplay()
                }
                .show()
        }
    }

    private fun subsetting(
        activity: Activity,
        key: String,
        title: String,
        summary: String,
        content: View,
        defaultExpanded: Boolean
    ): View {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(
                activity,
                color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
                color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
                13
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(activity, 14),
                dp(activity, 11),
                dp(activity, 11),
                dp(activity, 11)
            )
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $summary"
        }

        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(
            activity,
            title,
            13f,
            color(activity, R.color.hcf_text, Color.WHITE)
        ).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        val summaryView = text(
            activity,
            summary,
            10f,
            color(activity, R.color.hcf_muted, Color.LTGRAY)
        ).apply { maxLines = 2 }
        labels.addView(summaryView, lp(activity, -1, -2, 3, 0))
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        val arrow = text(
            activity,
            "›",
            22f,
            color(activity, R.color.hcf_accent_text, Color.rgb(0, 184, 240))
        ).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 36)))

        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), 0, dp(activity, 14), dp(activity, 13))
            addView(content)
        }

        val expanded = activity.getSharedPreferences(AppPrefs.FILE, 0)
            .getBoolean(PREF_PREFIX + key, defaultExpanded)
        applyExpanded(shell, arrow, expanded)
        header.setOnClickListener {
            val next = shell.visibility != View.VISIBLE
            applyExpanded(shell, arrow, next)
            activity.getSharedPreferences(AppPrefs.FILE, 0).edit()
                .putBoolean(PREF_PREFIX + key, next)
                .apply()
        }

        panel.addView(header)
        panel.addView(shell)
        return panel
    }

    private fun applyExpanded(body: View, arrow: TextView, expanded: Boolean) {
        body.animate().cancel()
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        body.alpha = 1f
        arrow.text = if (expanded) "⌄" else "›"
    }

    private fun body(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun innerPanel(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 12), dp(activity, 11), dp(activity, 12), dp(activity, 11))
        background = roundRect(
            activity,
            alpha(color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)), 242),
            color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
            12
        )
    }

    private fun sectionLabel(activity: Activity, value: String) =
        text(activity, value, 9f, cyan(activity)).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun securityStatusRow(
        activity: Activity,
        label: String,
        value: String,
        positive: Boolean
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(activity, 11), dp(activity, 9), dp(activity, 11), dp(activity, 9))
        background = roundRect(
            activity,
            alpha(color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)), 235),
            color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
            10
        )
        addView(text(
            activity,
            label,
            11f,
            color(activity, R.color.hcf_text, Color.WHITE)
        ).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(
            activity,
            value,
            10f,
            if (positive) cyan(activity)
            else color(activity, R.color.hcf_muted, Color.LTGRAY)
        ).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
    }

    private fun authStatus(activity: Activity, ready: Boolean): TextView {
        val stateColor =
            if (ready) cyan(activity)
            else color(activity, R.color.hcf_muted, Color.LTGRAY)
        return text(
            activity,
            if (ready) "HCF Authenticator configured on this device"
            else "HCF Authenticator not configured on this device",
            11f,
            stateColor
        ).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(activity, 11), dp(activity, 8), dp(activity, 11), dp(activity, 8))
            background = roundRect(
                activity,
                alpha(stateColor, 18),
                alpha(stateColor, 100),
                10
            )
        }
    }

    private fun stepRow(activity: Activity, number: String, message: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 9), dp(activity, 8), dp(activity, 9), dp(activity, 8))
            background = roundRect(
                activity,
                color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
                color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
                10
            )
            addView(text(activity, number, 11f, cyan(activity)).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundRect(
                    activity,
                    alpha(cyan(activity), 22),
                    alpha(cyan(activity), 110),
                    9
                )
            }, LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 30)))
            addView(text(
                activity,
                message,
                10f,
                color(activity, R.color.hcf_text, Color.WHITE)
            ).apply {
                setLineSpacing(0f, 1.08f)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(activity, 9)
            })
        }

    private fun input(activity: Activity, hint: String) =
        EditText(activity).apply {
            this.hint = hint
            setHintTextColor(color(activity, R.color.hcf_muted, Color.GRAY))
            setTextColor(color(activity, R.color.hcf_text, Color.WHITE))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            setPadding(dp(activity, 13), 0, dp(activity, 13), 0)
            background = roundRect(
                activity,
                color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
                color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
                11
            )
        }

    private fun actionButton(activity: Activity, label: String, primary: Boolean) =
        Button(activity).apply {
            isAllCaps = false
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            stateListAnimator = null
            if (primary) {
                setTextColor(Color.BLACK)
                background = roundRect(activity, cyan(activity), cyan(activity), 12)
            } else {
                setTextColor(cyan(activity))
                background = roundRect(
                    activity,
                    color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
                    color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
                    12
                )
            }
        }

    private fun openForumSettings(activity: Activity) {
        try {
            val identity = ForumIdentity.load(activity)
            val host =
                if (ForumUrlRouter.isForumHost(identity.host)) identity.host
                else "forum.harleytg.com"
            activity.startActivity(Intent(activity, HcfForum.MainActivity::class.java).apply {
                data = Uri.parse("https://$host/settings")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (_: Throwable) {}
    }

    private fun detail(activity: Activity, value: String) =
        text(
            activity,
            value,
            10f,
            color(activity, R.color.hcf_hint, Color.LTGRAY)
        ).apply { setLineSpacing(0f, 1.08f) }

    private fun styleExistingAction(activity: Activity, action: View) {
        action.minimumHeight = dp(activity, 52)
        action.setPadding(dp(activity, 13), 0, dp(activity, 13), 0)
        action.background = roundRect(
            activity,
            color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
            color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
            11
        )
    }

    private fun forumState(security: ForumSecurity.Snapshot): String =
        if (security.seen) "Forum managed" else "Sync needed"

    private fun formatCode(raw: String?): String = when (raw?.length) {
        null -> "--- ---"
        6 -> raw.substring(0, 3) + " " + raw.substring(3)
        8 -> raw.substring(0, 4) + " " + raw.substring(4)
        else -> raw
    }

    private fun detach(view: View?) {
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    private fun clickableAncestor(child: View, stop: ViewGroup): View? {
        var current: View? = child
        var candidate: View = child
        while (current != null && current !== stop) {
            if (current.isClickable) candidate = current
            current = current.parent as? View
        }
        return if (candidate === child && !child.isClickable) null else candidate
    }

    private fun commonCardAncestor(first: View, second: View): ViewGroup? {
        var cursor: View? = first
        while (cursor?.parent is View) {
            cursor = cursor.parent as View
            val group = cursor as? ViewGroup ?: continue
            if (isDescendant(group, second) && group.childCount >= 3) return group
        }
        return null
    }

    private fun isDescendant(parent: ViewGroup, target: View): Boolean {
        var cursor: View? = target
        while (cursor?.parent is View) {
            if (cursor.parent === parent) return true
            cursor = cursor.parent as View
        }
        return false
    }

    private fun findText(view: View?, exact: String): TextView? {
        if (view is TextView && exact == view.text?.toString()?.trim()) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findText(group.getChildAt(i), exact)?.let { return it }
        }
        return null
    }

    private fun text(context: Context, value: String, sp: Float, color: Int) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
        }

    private fun lp(
        context: Context,
        width: Int,
        height: Int,
        topDp: Int,
        bottomDp: Int
    ) = LinearLayout.LayoutParams(width, height).apply {
        topMargin = dp(context, topDp)
        bottomMargin = dp(context, bottomDp)
    }

    private fun roundRect(
        context: Context,
        fill: Int,
        stroke: Int,
        radiusDp: Int
    ) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1), stroke)
    }

    private fun alpha(color: Int, alpha: Int): Int =
        Color.argb(
            maxOf(0, minOf(255, alpha)),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun cyan(context: Context): Int =
        color(context, R.color.hcf_cyan_bright, Color.rgb(0, 184, 240))

    private fun color(context: Context, resId: Int, fallback: Int): Int =
        try { context.getColor(resId) } catch (_: Throwable) { fallback }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}
