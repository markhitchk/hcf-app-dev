package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Shows a subtle Safe Mode text overlay below the native HCF URL bar. */
object HcfSafeModeBadge {
    private const val PREF_FILE = "hcf_app"
    private const val KEY_ACTIVE = "safe_mode_active"
    private const val KEY_SESSION_PID = "safe_mode_session_pid"
    private const val BADGE_TAG = "hcf_safe_mode_overlay_text"

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val context = context ?: return true
            val app = context.applicationContext as? Application ?: return true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = update(activity)
                override fun onActivityResumed(activity: Activity) = update(activity)
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun update(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        val root = activity.findViewById<View>(R.id.rootFrame) as? FrameLayout ?: return
        val urlBar = activity.findViewById<View>(R.id.urlBar) ?: return
        var badge = findTaggedView(root, BADGE_TAG)
        if (!isSafeModeActive(activity)) {
            badge?.visibility = View.GONE
            return
        }

        if (badge == null) {
            badge = createBadge(activity)
            root.addView(badge)
            val overlayBadge = badge
            urlBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                positionBelowUrlBar(root, urlBar, overlayBadge)
            }
        }

        val overlayBadge = badge
        badge.visibility = View.VISIBLE
        badge.bringToFront()
        root.post { positionBelowUrlBar(root, urlBar, overlayBadge) }
        AppLogger.info(activity, "safe_mode_badge", "gray_plain_text_overlay")
    }

    private fun positionBelowUrlBar(root: FrameLayout?, urlBar: View?, badge: View?) {
        if (root == null || urlBar == null || badge == null) return
        val rootLocation = IntArray(2)
        val urlLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        urlBar.getLocationOnScreen(urlLocation)
        val topMargin = urlLocation[1] - rootLocation[1] + urlBar.height + dp(root.context, 6)
        val lp = (badge.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(root.context, 20))
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = dp(root.context, 20)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = maxOf(0, topMargin)
        lp.rightMargin = dp(root.context, 12)
        badge.layoutParams = lp
        badge.bringToFront()
    }

    private fun isSafeModeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ACTIVE, false) &&
            prefs.getInt(KEY_SESSION_PID, -1) == android.os.Process.myPid()
    }

    private fun createBadge(context: Context): TextView = TextView(context).apply {
        tag = BADGE_TAG
        text = "Safe Mode"
        textSize = 9f
        setTextColor(context.getColor(R.color.hcf_muted))
        alpha = 0.72f
        setTypeface(null, Typeface.NORMAL)
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        contentDescription = "Safe Mode active"
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(dp(context, 4), 0, dp(context, 4), 0)
        minHeight = dp(context, 20)
        isClickable = false
        isFocusable = false
        elevation = dp(context, 6).toFloat()
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(context, 20),
            Gravity.TOP or Gravity.END
        ).apply { rightMargin = dp(context, 12) }
    }

    private fun findTaggedView(view: View, tag: String): View? {
        if (tag == view.tag) return view
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            findTaggedView(group.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)
}
