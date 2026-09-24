package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * HCF_DRAWER_NEW_SHORTCUTS_V2_STARTUP_HOST
 *
 * Adds two native hamburger-menu shortcuts using the existing HCF drawer style.
 */
object HcfDrawerNewItemsUi {
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"
    private const val TAG_AUTH = "hcf_drawer_auth_new_v2"
    private const val TAG_EVENTS = "hcf_drawer_events_new_v2"
    private const val EXTRA_OPEN_AUTH = "hcf_open_authenticator_subsettings"
    private val MAIN = Handler(Looper.getMainLooper())
    private var installed = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val context = context ?: return true
            (context.applicationContext as? Application)?.let(::install)
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
                if (isMain(activity)) scheduleDrawerInstall(activity)
                if (isSettings(activity)) scheduleRequestedAuth(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                if (isMain(activity)) scheduleDrawerInstall(activity)
                if (isSettings(activity)) scheduleRequestedAuth(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun isMain(activity: Activity?): Boolean = activity is HcfForum.MainActivity
    private fun isSettings(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun scheduleDrawerInstall(activity: Activity) {
        MAIN.postDelayed({ installDrawerRows(activity) }, 60L)
        MAIN.postDelayed({ installDrawerRows(activity) }, 180L)
        MAIN.postDelayed({ installDrawerRows(activity) }, 420L)
        MAIN.postDelayed({ installDrawerRows(activity) }, 900L)
    }

    private fun installDrawerRows(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val drawer = findId(activity, "drawerPanel") as? ViewGroup ?: return
        val settings = findId(activity, "drawerSettings") ?: return
        if (drawer.findViewWithTag<View>(TAG_AUTH) != null ||
            drawer.findViewWithTag<View>(TAG_EVENTS) != null) return

        val list = nearestLinearParent(settings, drawer) as? LinearLayout ?: return
        val auth = shortcutRow(activity, "HCF Auth", R.drawable.fa_lock, TAG_AUTH) {
            openAuthenticatorSettings(activity)
        }
        val events = shortcutRow(activity, "HCF Events", R.drawable.fa_calendar, TAG_EVENTS) {
            openEvents(activity)
        }

        var settingsIndex = list.indexOfChild(settings)
        if (settingsIndex < 0) settingsIndex = list.childCount
        list.addView(auth, settingsIndex)
        list.addView(events, minOf(settingsIndex + 1, list.childCount))
    }

    private fun shortcutRow(
        activity: Activity,
        title: String,
        iconRes: Int,
        tag: String,
        listener: View.OnClickListener
    ): View {
        val shell = FrameLayout(activity).apply { this.tag = tag }
        val button = Button(activity, null, 0, R.style.HcfDrawerItem).apply {
            text = title
            isAllCaps = false
            contentDescription = "$title, new feature"
            setOnClickListener(listener)
        }
        try {
            FaIcons.applyStart(button, iconRes)
        } catch (_: Throwable) {
            button.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            button.compoundDrawablePadding = dp(activity, 10)
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                button.compoundDrawableTintList = ColorStateList.valueOf(cyan(activity))
            }
        }
        shell.addView(
            button,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, drawerItemHeight(activity))
        )

        val badge = TextView(activity).apply {
            text = "NEW"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cyan(activity))
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(activity, 7), 0, dp(activity, 7), 0)
            background = badgeBackground(activity)
            contentDescription = "New"
        }
        shell.addView(
            badge,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 20),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = dp(activity, 12) }
        )
        shell.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            drawerItemHeight(activity)
        ).apply { bottomMargin = dp(activity, 5) }
        return shell
    }

    private fun openAuthenticatorSettings(activity: Activity) {
        hideDrawer(activity)
        try {
            activity.startActivity(
                Intent(activity, HcfSubActivities.SettingsActivity::class.java).apply {
                    putExtra(EXTRA_OPEN_AUTH, true)
                }
            )
        } catch (_: Throwable) {}
    }

    private fun openEvents(activity: Activity) {
        hideDrawer(activity)
        val url = "https://forum.harleytg.com/events"
        try {
            mainWebView(activity)?.let {
                it.loadUrl(url)
                return
            }
        } catch (_: Throwable) {}

        try {
            activity.startActivity(
                Intent(activity, HcfForum.MainActivity::class.java).apply {
                    data = Uri.parse(url)
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hideDrawer(activity: Activity) {
        findId(activity, "drawerPanel")?.visibility = View.GONE
        findId(activity, "drawerScrim")?.visibility = View.GONE
    }

    private fun mainWebView(activity: Activity): WebView? {
        try {
            var cursor: Class<*>? = activity.javaClass
            while (cursor != null) {
                try {
                    val field: Field = cursor.getDeclaredField("webView")
                    field.isAccessible = true
                    (field.get(activity) as? WebView)?.let { return it }
                    break
                } catch (_: NoSuchFieldException) {
                    cursor = cursor.superclass
                }
            }
        } catch (_: Throwable) {}
        return findId(activity, "webView") as? WebView
    }

    private fun scheduleRequestedAuth(activity: Activity?) {
        val intent = activity?.intent ?: return
        if (!intent.getBooleanExtra(EXTRA_OPEN_AUTH, false)) return
        intent.removeExtra(EXTRA_OPEN_AUTH)
        MAIN.postDelayed({ openAccountSecurity(activity) }, 70L)
        MAIN.postDelayed({ openAccountSecurity(activity) }, 210L)
        MAIN.postDelayed({ expandAuthenticatorPanel(activity) }, 520L)
        MAIN.postDelayed({ expandAuthenticatorPanel(activity) }, 850L)
    }

    private fun openAccountSecurity(activity: Activity) {
        invokeOneString(activity, "showSettingsSection", "account_security")
    }

    private fun expandAuthenticatorPanel(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val title = findText(activity.findViewById(android.R.id.content), "HCF Authenticator") ?: return
        val group = connectedPanel(title) as? ViewGroup ?: return
        if (group.childCount < 2) return
        val body = group.getChildAt(1)
        if (body.visibility != View.VISIBLE) group.getChildAt(0)?.performClick()
    }

    private fun invokeOneString(activity: Activity, methodName: String, argument: String): Boolean {
        var cursor: Class<*>? = activity.javaClass
        while (cursor != null) {
            try {
                val method: Method = cursor.getDeclaredMethod(methodName, String::class.java)
                method.isAccessible = true
                method.invoke(activity, argument)
                return true
            } catch (_: NoSuchMethodException) {
                cursor = cursor.superclass
            } catch (_: Throwable) {
                return false
            }
        }
        return false
    }

    private fun connectedPanel(title: View): View? {
        var current: View? = title
        while (current?.parent is View) {
            current = current.parent as View
            val layout = current as? LinearLayout ?: continue
            if (layout.childCount == 2 &&
                layout.getChildAt(0) is LinearLayout &&
                layout.getChildAt(1) is LinearLayout) return layout
        }
        return null
    }

    private fun nearestLinearParent(child: View, stop: View): ViewGroup? {
        var current: View? = child
        while (current != null && current !== stop && current.parent is View) {
            val parent = current.parent as View
            if (parent is LinearLayout) return parent
            current = parent
        }
        return stop as? ViewGroup
    }

    private fun findId(activity: Activity?, name: String): View? {
        if (activity == null) return null
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id == 0) null else activity.findViewById(id)
    }

    private fun findText(view: View?, exact: String): TextView? {
        if (view is TextView && exact == view.text?.toString()?.trim()) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), exact)?.let { return it }
            }
        }
        return null
    }

    private fun badgeBackground(context: Context): GradientDrawable {
        val value = cyan(context)
        return GradientDrawable().apply {
            setColor(Color.argb(24, Color.red(value), Color.green(value), Color.blue(value)))
            setStroke(dp(context, 1), Color.argb(170, Color.red(value), Color.green(value), Color.blue(value)))
            cornerRadius = dp(context, 10).toFloat()
        }
    }

    private fun drawerItemHeight(context: Context): Int = try {
        context.resources.getDimensionPixelSize(R.dimen.drawer_item_height)
    } catch (_: Throwable) {
        dp(context, 48)
    }

    private fun cyan(context: Context): Int = try {
        context.getColor(R.color.hcf_cyan_bright)
    } catch (_: Throwable) {
        Color.rgb(0, 184, 240)
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}
