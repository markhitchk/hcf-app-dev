package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * HCF_AUTHENTICATOR_TOP_LEVEL_SUBSETTINGS_V5_STATE_SPLIT
 *
 * Keeps HCF Authenticator as a top-level Account & Security panel and adapts
 * enrollment/live-code content to the local authenticator state.
 */
object HcfAuthenticatorAdaptiveSettingsUi {
    private const val AUTH_PANEL_TAG = "hcf_authenticator_top_level_subsetting_v5"
    private const val AUTH_SUMMARY_TAG = "$AUTH_PANEL_TAG:summary"
    private const val AUTH_NEW_BADGE_TAG = "$AUTH_PANEL_TAG:new_badge"
    private const val NEARATA_MANAGE_TAG = "$AUTH_PANEL_TAG:nearata_manage"
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"
    private val MAIN = Handler(Looper.getMainLooper())
    private val OBSERVERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private var installed = false

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
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (isSettings(activity)) {
                    observe(activity)
                    schedule(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (isSettings(activity)) {
                    observe(activity)
                    schedule(activity)
                }
            }

            override fun onActivityDestroyed(activity: Activity) = remove(activity)
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    private fun isSettings(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun observe(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        synchronized(OBSERVERS) {
            if (OBSERVERS.containsKey(activity)) return
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener { apply(activity) }
            observer.addOnGlobalLayoutListener(listener)
            OBSERVERS[activity] = listener
        }
    }

    private fun remove(activity: Activity?) {
        if (activity == null) return
        val listener = synchronized(OBSERVERS) { OBSERVERS.remove(activity) } ?: return
        try {
            val observer = activity.findViewById<View>(android.R.id.content)?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnGlobalLayoutListener(listener)
        } catch (_: Throwable) {}
    }

    private fun schedule(activity: Activity) {
        MAIN.postDelayed({ apply(activity) }, 70L)
        MAIN.postDelayed({ apply(activity) }, 180L)
        MAIN.postDelayed({ apply(activity) }, 360L)
        MAIN.postDelayed({ apply(activity) }, 650L)
    }

    private fun apply(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        findText(root, "CURRENT 6-DIGIT PASSCODE") ?: return
        ensureTopLevelPanel(activity, root)

        val configured = isConfigured(activity)
        val heading = findText(root, "HCF AUTHENTICATOR • FULL SETTINGS")
        var localStatus: View? = findTextStarting(root, "HCF Authenticator configured on this device")
        if (localStatus == null) {
            localStatus = findTextStarting(root, "HCF Authenticator not configured on this device")
        }
        val nearataStatus = parentOf(findText(root, "Nearata 2FA controls"))
        val currentCodePanel = parentOf(findText(root, "CURRENT 6-DIGIT PASSCODE"))
        val setupPanel = parentOf(findText(root, "SET UP HCF AUTHENTICATOR"))
        val finishPanel = parentOf(findText(root, "FINISH IN FORUM USER SETTINGS"))
        val managePanel = parentOf(findText(root, "LOCAL AUTHENTICATOR STORAGE"))
        val footer = findTextStarting(root, "RFC 6238 TOTP")

        setVisible(nearataStatus, true)
        if (configured) {
            setVisible(heading, false)
            setVisible(localStatus, false)
            setVisible(currentCodePanel, true)
            setVisible(setupPanel, false)
            setVisible(finishPanel, false)
            setVisible(footer, false)
            setVisible(managePanel, true)
            compactManagePanel(managePanel, activity)
        } else {
            setVisible(heading, true)
            setVisible(localStatus, true)
            setVisible(currentCodePanel, false)
            setVisible(setupPanel, true)
            setVisible(finishPanel, true)
            setVisible(footer, true)
            setVisible(managePanel, false)
        }

        setupPanel?.let {
            val button = findText(it, "Replace Setup key") ?: findText(it, "Save Setup key")
            button?.text = if (configured) "Replace Setup key" else "Save Setup key"
        }

        val twoFactorPanel = findConnectedPanel(findText(root, "Two-Factor Authentication"))
        if (twoFactorPanel is LinearLayout) {
            setTwoFactorSummary(twoFactorPanel, "Nearata TwoFactor • forum User Settings")
        }

        (root.findViewWithTag<View>(AUTH_SUMMARY_TAG) as? TextView)?.text =
            if (configured) "Configured • live 6-digit passcodes"
            else "Offline 2FA passcodes and setup tools"
    }

    private fun ensureTopLevelPanel(activity: Activity, root: ViewGroup) {
        if (root.findViewWithTag<View>(AUTH_PANEL_TAG) != null) return

        val twoFactorTitle = findText(root, "Two-Factor Authentication") ?: return
        findText(root, "CURRENT 6-DIGIT PASSCODE") ?: return
        val twoFactorPanel = findConnectedPanel(twoFactorTitle) as? LinearLayout ?: return

        val nearataStatus = parentOf(findText(root, "Nearata 2FA controls"))
        val nearataBody = nearataStatus?.parent as? View

        val authViews = ArrayList<View>()
        addIfPresent(authViews, findText(root, "HCF AUTHENTICATOR • FULL SETTINGS"))
        var status: View? = findTextStarting(root, "HCF Authenticator configured on this device")
        if (status == null) status = findTextStarting(root, "HCF Authenticator not configured on this device")
        addIfPresent(authViews, status)
        addIfPresent(authViews, parentOf(findText(root, "CURRENT 6-DIGIT PASSCODE")))
        addIfPresent(authViews, parentOf(findText(root, "SET UP HCF AUTHENTICATOR")))
        addIfPresent(authViews, parentOf(findText(root, "FINISH IN FORUM USER SETTINGS")))
        addIfPresent(authViews, parentOf(findText(root, "LOCAL AUTHENTICATOR STORAGE")))
        addIfPresent(authViews, findTextStarting(root, "RFC 6238 TOTP"))
        if (authViews.isEmpty()) return

        val accountControlsSubtitle =
            findText(root, "Profile, password, email and session security shortcuts") ?: return
        val accountControlsTopPanel = findConnectedPanel(accountControlsSubtitle) as? LinearLayout ?: return
        val settingsContent = accountControlsTopPanel.parent as? LinearLayout ?: return

        val authContent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (view in authViews) {
            detach(view)
            authContent.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (authContent.childCount > 0) topMargin = dp(activity, 9)
                }
            )
        }

        setTwoFactorSummary(twoFactorPanel, "Nearata TwoFactor • forum User Settings")
        addNearataManageAction(activity, nearataBody)

        val configured = isConfigured(activity)
        val summary =
            if (configured) "Configured • live 6-digit passcodes"
            else "Offline 2FA passcodes and setup tools"

        var authPanel = invokeConnectedSettingsPanel(
            activity,
            "HCF Authenticator",
            summary,
            authContent,
            false
        )
        if (authPanel == null) authPanel = buildFallbackPanel(activity, summary, authContent)
        authPanel.tag = AUTH_PANEL_TAG
        findText(authPanel, summary)?.tag = AUTH_SUMMARY_TAG
        findFirstImageView(authPanel)?.let {
            try { it.setImageResource(R.drawable.fa_lock) } catch (_: Throwable) {}
        }
        addNewBadge(activity, authPanel)

        val index = settingsContent.indexOfChild(accountControlsTopPanel)
        settingsContent.addView(authPanel, minOf(index + 1, settingsContent.childCount))
    }

    private fun addNearataManageAction(activity: Activity, nearataBody: View?) {
        val body = nearataBody as? LinearLayout ?: return
        if (body.findViewWithTag<View>(NEARATA_MANAGE_TAG) != null) return

        val action = text(
            activity,
            "Manage Nearata in Forum Settings",
            11f,
            color(activity, R.color.hcf_cyan_bright, Color.rgb(0, 184, 240))
        ).apply {
            tag = NEARATA_MANAGE_TAG
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            background = roundRect(
                activity,
                color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
                color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
                11
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    val identity = ForumIdentity.load(activity)
                    val host = if (ForumUrlRouter.isForumHost(identity.host)) {
                        identity.host
                    } else {
                        "forum.harleytg.com"
                    }
                    activity.startActivity(
                        android.content.Intent(activity, HcfForum.MainActivity::class.java).apply {
                            data = Uri.parse("https://$host/settings")
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                    )
                } catch (_: Throwable) {}
            }
        }
        body.addView(
            action,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 9) }
        )
    }

    private fun addNewBadge(activity: Activity, authPanel: View?) {
        if (authPanel == null || authPanel.findViewWithTag<View>(AUTH_NEW_BADGE_TAG) != null) return
        val heading = findText(authPanel, "HCF Authenticator") ?: return
        val parent = heading.parent as? LinearLayout ?: return

        val cyan = color(activity, R.color.hcf_cyan_bright, Color.rgb(0, 184, 240))
        val badge = text(activity, "NEW", 8f, color(activity, R.color.hcf_bg, Color.rgb(8, 13, 17))).apply {
            tag = AUTH_NEW_BADGE_TAG
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(activity, 7), dp(activity, 2), dp(activity, 7), dp(activity, 2))
            background = roundRect(activity, cyan, cyan, 8)
        }

        if (parent.orientation == LinearLayout.HORIZONTAL) {
            val index = parent.indexOfChild(heading)
            parent.addView(
                badge,
                minOf(index + 1, parent.childCount),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(activity, 20)
                ).apply { leftMargin = dp(activity, 7) }
            )
            return
        }

        val index = parent.indexOfChild(heading)
        if (index < 0) return
        parent.removeView(heading)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            heading,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            badge,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 20)
            ).apply { leftMargin = dp(activity, 7) }
        )
        parent.addView(
            row,
            index,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun invokeConnectedSettingsPanel(
        activity: Activity,
        title: String,
        subtitle: String,
        content: View,
        expanded: Boolean
    ): View? {
        var cursor: Class<*>? = activity.javaClass
        while (cursor != null) {
            try {
                val method: Method = cursor.getDeclaredMethod(
                    "connectedSettingsPanel",
                    String::class.java,
                    String::class.java,
                    View::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                return method.invoke(activity, title, subtitle, content, expanded) as? View
            } catch (_: NoSuchMethodException) {
                cursor = cursor.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun buildFallbackPanel(activity: Activity, summary: String, content: View): View {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(
                activity,
                color(activity, R.color.hcf_surface, Color.rgb(19, 28, 34)),
                color(activity, R.color.hcf_border, Color.rgb(41, 64, 75)),
                15
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 14), dp(activity, 14))
            isClickable = true
            isFocusable = true
        }
        header.addView(ImageView(activity).apply {
            setImageResource(R.drawable.fa_lock)
            try {
                setColorFilter(color(activity, R.color.hcf_cyan_bright, Color.rgb(0, 184, 240)))
            } catch (_: Throwable) {}
        }, LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32)))

        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val heading = text(
            activity,
            "HCF Authenticator",
            14f,
            color(activity, R.color.hcf_cyan_bright, Color.rgb(0, 184, 240))
        ).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }
        labels.addView(heading)

        labels.addView(
            text(activity, summary, 10f, color(activity, R.color.hcf_muted, Color.LTGRAY)).apply {
                tag = AUTH_SUMMARY_TAG
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 3) }
        )
        header.addView(
            labels,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(activity, 12)
            }
        )

        val arrow = text(
            activity,
            "›",
            22f,
            color(activity, R.color.hcf_cyan_bright, Color.rgb(0, 184, 240))
        ).apply { gravity = Gravity.CENTER }
        header.addView(arrow, LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 40)))

        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), 0, dp(activity, 14), dp(activity, 14))
            addView(content)
            visibility = View.GONE
        }
        header.setOnClickListener {
            val open = shell.visibility != View.VISIBLE
            shell.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "⌄" else "›"
        }

        panel.addView(header)
        panel.addView(shell)
        panel.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(activity, 12)
        }
        return panel
    }

    private fun setTwoFactorSummary(panel: LinearLayout, summary: String) {
        try {
            val header = panel.getChildAt(0) as? LinearLayout ?: return
            val labels = header.getChildAt(0) as? LinearLayout ?: return
            if (labels.childCount < 2) return
            (labels.getChildAt(1) as? TextView)?.text = summary
        } catch (_: Throwable) {}
    }

    private fun compactManagePanel(managePanel: View?, activity: Activity) {
        val panel = managePanel as? ViewGroup ?: return
        setVisible(findText(panel, "LOCAL AUTHENTICATOR STORAGE"), false)
        setVisible(findTextStarting(panel, "Only the TOTP setup secret is stored by HCF"), false)
        setVisible(findTextStarting(panel, "Removing the local key does not disable Nearata 2FA"), false)
        if (panel is LinearLayout) {
            val pad = dp(activity, 6)
            panel.setPadding(pad, pad, pad, pad)
        }
    }

    private fun findConnectedPanel(titleOrSubtitle: View?): View? {
        var current = titleOrSubtitle
        while (current?.parent is View) {
            current = current.parent as View
            val layout = current as? LinearLayout ?: continue
            if (
                layout.childCount == 2 &&
                layout.getChildAt(0) is LinearLayout &&
                layout.getChildAt(1) is LinearLayout
            ) return layout
        }
        return null
    }

    private fun isConfigured(context: Context): Boolean = try {
        val config = HcfAuthenticator.Vault.load(context)
        config?.secret?.isNotEmpty() == true
    } catch (_: Throwable) {
        false
    }

    private fun addIfPresent(views: MutableList<View>, view: View?) {
        if (view != null && !views.contains(view)) views.add(view)
    }

    private fun parentOf(view: View?): View? = view?.parent as? View

    private fun detach(view: View?) {
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    private fun setVisible(view: View?, visible: Boolean) {
        view?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun findText(view: View?, exact: String): TextView? {
        if (view is TextView && exact == view.text?.toString()?.trim()) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findText(group.getChildAt(i), exact)?.let { return it }
        }
        return null
    }

    private fun findTextStarting(view: View?, prefix: String): TextView? {
        if (view is TextView && view.text?.toString()?.trim()?.startsWith(prefix) == true) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findTextStarting(group.getChildAt(i), prefix)?.let { return it }
        }
        return null
    }

    private fun findFirstImageView(view: View?): ImageView? {
        if (view is ImageView) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findFirstImageView(group.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun text(context: Context, value: String, sp: Float, color: Int): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
        }

    private fun roundRect(context: Context, fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1), stroke)
        }

    private fun color(context: Context, resId: Int, fallback: Int): Int = try {
        context.getColor(resId)
    } catch (_: Throwable) {
        fallback
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}
