package com.harleytg.forum.dev

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Small quality-of-life layer for the native HCF drawer. */
object HcfDrawerQol {
    private const val QUICK_ROW_TAG = "hcf_drawer_quick_row_v1"
    private const val ADMIN_BUTTON_TAG = "hcf_drawer_admin_v1"
    private const val AUTH_BADGE_WRAP_TAG = "hcf_drawer_auth_badge_wrap_v1"
    private const val AUTH_BADGE_TAG = "hcf_drawer_auth_badge_v2"
    private val MAIN = Handler(Looper.getMainLooper())
    private val INSTALLED = AtomicBoolean(false)
    private var resumedMain = WeakReference<Activity>(null)

    private fun install(context: Context?) {
        if (context == null || !INSTALLED.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (activity is HcfForum.MainActivity) {
                    resumedMain = WeakReference(activity)
                    MAIN.removeCallbacks(POLL)
                    MAIN.post(POLL)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedMain.get() === activity) {
                    resumedMain.clear()
                    MAIN.removeCallbacks(POLL)
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (resumedMain.get() === activity) {
                    resumedMain.clear()
                    MAIN.removeCallbacks(POLL)
                }
            }
        })
    }

    private val POLL: Runnable = object : Runnable {
        override fun run() {
            val activity = resumedMain.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            try {
                apply(activity)
            } catch (error: Throwable) {
                AppLogger.warn(activity, "drawer_qol", error.javaClass.simpleName)
            }
            MAIN.postDelayed(this, 500L)
        }
    }

    private fun apply(activity: Activity) {
        repairHeaderChrome(activity)
        val drawerRoot = activity.findViewById<View>(R.id.drawerPanel) as? ViewGroup ?: return
        val forumLabel = findTextExact(drawerRoot, "Forum controls") ?: return
        val menu = forumLabel.parent as? LinearLayout ?: return
        ensureQuickRow(activity, menu, forumLabel)
        rehomeHcfEvents(menu)
        ensureAdminEntry(activity, menu)
        repairAuthNewBadge(activity, menu)
    }

    private fun repairHeaderChrome(activity: Activity) {
        val accent = activity.getColor(R.color.hcf_cyan_bright)
        val tint = ColorStateList.valueOf(accent)
        val buttonIds = intArrayOf(
            R.id.drawerButton,
            R.id.headerNotificationsButton,
            R.id.urlBackButton,
            R.id.reloadButton,
            R.id.copyUrlButton,
            R.id.urlHomeButton
        )
        for (id in buttonIds) {
            val button = activity.findViewById<View>(id) as? ImageButton ?: continue
            button.imageTintList = tint
            if (button.isEnabled) button.alpha = 1f
        }
        (activity.findViewById<View>(R.id.hostBadge) as? TextView)?.setTextColor(accent)
    }

    @Suppress("unused")
    private fun readableChromeAccent(activity: Activity, requested: Int): Int {
        val background = activity.getColor(R.color.hcf_bg)
        val urlBackground = activity.getColor(R.color.hcf_url_bar)
        if (minimumContrast(requested, background, urlBackground) >= 3.0) return requested
        val cyan = activity.getColor(R.color.hcf_cyan_bright)
        val text = activity.getColor(R.color.hcf_text)
        return if (
            minimumContrast(text, background, urlBackground) >
            minimumContrast(cyan, background, urlBackground)
        ) text else cyan
    }

    private fun minimumContrast(foreground: Int, firstBackground: Int, secondBackground: Int): Double =
        min(contrastRatio(foreground, firstBackground), contrastRatio(foreground, secondBackground))

    private fun contrastRatio(first: Int, second: Int): Double {
        val l1 = relativeLuminance(first)
        val l2 = relativeLuminance(second)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        val r = linearChannel(Color.red(color) / 255.0)
        val g = linearChannel(Color.green(color) / 255.0)
        val b = linearChannel(Color.blue(color) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearChannel(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    private fun ensureQuickRow(activity: Activity, menu: LinearLayout, forumLabel: TextView) {
        if (menu.findViewWithTag<View>(QUICK_ROW_TAG) is LinearLayout) return

        val home = activity.findViewById<Button>(R.id.drawerHome) ?: return
        val notifications = activity.findViewById<Button>(R.id.drawerNotifications) ?: return
        val badge = activity.findViewById<TextView>(R.id.drawerNotificationCountBadge)

        val forumIndex = menu.indexOfChild(forumLabel)
        if (forumIndex < 0 || forumIndex + 1 >= menu.childCount) return
        val insertAt = menu.indexOfChild(menu.getChildAt(forumIndex + 1)) + 1

        removeFromParent(home)
        removeFromParent(notifications)
        badge?.let(::removeFromParent)

        val row = LinearLayout(activity).apply {
            tag = QUICK_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 48)
        ).apply { topMargin = dp(activity, 8) }

        configureQuickButton(home, "Home")
        row.addView(
            home,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dp(activity, 5)
            }
        )

        val notificationCell = FrameLayout(activity)
        row.addView(
            notificationCell,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(activity, 5)
            }
        )
        configureQuickButton(notifications, "Notifications")
        notificationCell.addView(
            notifications,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        badge?.let {
            styleUnreadBadge(activity, it)
            notificationCell.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(activity, 20),
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dp(activity, 3)
                    rightMargin = dp(activity, 4)
                }
            )
        }

        menu.addView(row, min(insertAt, menu.childCount), rowLp)
        AppLogger.info(activity, "drawer_qol", "forum_quick_row_ready")
    }

    private fun configureQuickButton(button: Button, label: String) {
        button.visibility = View.VISIBLE
        button.text = label
        button.maxLines = 1
        button.isAllCaps = false
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(button.paddingLeft, 0, button.paddingRight, 0)
    }

    private fun styleUnreadBadge(activity: Activity, badge: TextView) {
        badge.textSize = 9f
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        badge.setTextColor(activity.getColor(R.color.hcf_on_accent))
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(activity, 20)
        badge.minimumWidth = dp(activity, 20)
        badge.setPadding(dp(activity, 5), 0, dp(activity, 5), 0)
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(activity.getColor(R.color.hcf_cyan))
            cornerRadius = dp(activity, 10).toFloat()
            setStroke(dp(activity, 1), activity.getColor(R.color.hcf_bg))
        }
    }

    private fun rehomeHcfEvents(menu: LinearLayout) {
        val appLabel = findDirectText(menu, "App") ?: return
        val events = findTextExact(menu, "HCF Events") ?: return

        if (events.parent === menu) {
            val eventsIndex = menu.indexOfChild(events)
            val appIndex = menu.indexOfChild(appLabel)
            if (eventsIndex >= 0 && appIndex >= 0 && eventsIndex < appIndex) return
        }

        val parent = events.parent as? ViewGroup ?: return
        val original = events.layoutParams
        parent.removeView(events)

        val appIndex = menu.indexOfChild(appLabel)
        if (appIndex < 0) return
        events.visibility = View.VISIBLE
        val lp = if (original is LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams(original).apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }.apply { topMargin = dp(menu.context, 8) }
        menu.addView(events, appIndex, lp)
    }

    private fun ensureAdminEntry(activity: Activity, menu: LinearLayout) {
        val identity = ForumIdentity.load(activity)
        val allowed = identity != null && identity.loggedIn && identity.admin
        val existing = menu.findViewWithTag<View>(ADMIN_BUTTON_TAG)

        if (!allowed) {
            existing?.let(::removeFromParent)
            return
        }

        if (existing is Button) {
            normalizeAdminButton(activity, existing)
            return
        }

        val appLabel = findDirectText(menu, "App") ?: return
        val admin = Button(activity, null, 0, R.style.HcfDrawerItem).apply {
            tag = ADMIN_BUTTON_TAG
            text = "Admin"
            isAllCaps = false
            maxLines = 1
            contentDescription = "Open forum administrator panel"
        }
        normalizeAdminButton(activity, admin)
        try { FaIcons.applyStart(admin, R.drawable.fa_shield) } catch (_: Throwable) {}

        admin.setOnClickListener {
            val current = ForumIdentity.load(activity)
            if (current == null || !current.loggedIn || !current.admin) {
                removeFromParent(admin)
                AppLogger.warn(activity, "drawer_admin", "identity_no_longer_admin")
                return@setOnClickListener
            }

            var host = current.host
            if (!ForumUrlRouter.isForumHost(host)) {
                host = activity.getSharedPreferences("hcf_app", Context.MODE_PRIVATE)
                    .getString("active_host", "forum.harleytg.com")
            }
            if (!ForumUrlRouter.isForumHost(host)) host = "forum.harleytg.com"

            val adminUrl = "https://$host/admin"
            val target = activity.findViewById<View>(R.id.webView) as? WebView
            if (target == null) {
                AppLogger.warn(activity, "drawer_admin", "webview_missing")
                return@setOnClickListener
            }
            activity.onBackPressed()
            target.loadUrl(adminUrl)
            AppLogger.info(activity, "drawer_admin", AppLogger.safeUrl(adminUrl))
        }

        val appIndex = menu.indexOfChild(appLabel)
        if (appIndex < 0) return
        menu.addView(
            admin,
            appIndex,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 6) }
        )
        AppLogger.info(activity, "drawer_qol", "admin_identity_action_ready")
    }

    private fun normalizeAdminButton(activity: Activity, admin: Button) {
        admin.visibility = View.VISIBLE
        admin.minWidth = 0
        admin.minimumWidth = 0
        admin.textSize = 14f
        val cardHeight = dp(activity, 52)
        admin.minHeight = cardHeight
        admin.minimumHeight = cardHeight
        admin.layoutParams?.let {
            if (it.height > 0 && it.height != cardHeight) {
                it.height = cardHeight
                admin.layoutParams = it
            }
        }
    }

    private fun repairAuthNewBadge(activity: Activity, menu: LinearLayout) {
        var wrapper = menu.findViewWithTag<View>(AUTH_BADGE_WRAP_TAG) as? FrameLayout
        if (wrapper == null) {
            val auth = findTextExact(menu, "HCF Auth") ?: return
            val authRoot = topLevelChild(menu, auth) ?: return
            val authIndex = menu.indexOfChild(authRoot)
            if (authIndex < 0) return
            val original = authRoot.layoutParams
            removeFromParent(authRoot)

            wrapper = FrameLayout(activity).apply {
                tag = AUTH_BADGE_WRAP_TAG
                minimumHeight = dp(activity, 64)
                addView(
                    authRoot,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val wrapperLp = if (original is LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams(original).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            menu.addView(wrapper, min(authIndex, menu.childCount), wrapperLp)
        }

        var anchored = wrapper.findViewWithTag<View>(AUTH_BADGE_TAG) as? TextView
        if (anchored == null) {
            anchored = findTextExact(wrapper, "NEW")?.apply { tag = AUTH_BADGE_TAG }
        }
        if (anchored == null) {
            anchored = TextView(activity).apply { tag = AUTH_BADGE_TAG }
            wrapper.addView(anchored)
        }

        styleNewBadge(activity, anchored)
        anchored.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, 24),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(activity, 8)
            rightMargin = dp(activity, 10)
        }
        anchored.bringToFront()
        removeStrayNewBadges(menu, wrapper, anchored)
    }

    private fun removeStrayNewBadges(
        menu: LinearLayout,
        wrapper: FrameLayout,
        anchored: TextView
    ) {
        val badges = ArrayList<TextView>()
        collectTextExact(menu, "NEW", badges)
        for (badge in badges) {
            if (badge === anchored || isDescendantOf(badge, wrapper)) continue
            val top = topLevelChild(menu, badge)
            removeFromParent(badge)
            if (top == null) continue
            when {
                top === badge -> removeFromParent(top)
                top is ViewGroup && top.childCount == 0 -> removeFromParent(top)
                top.parent === menu && top is ViewGroup && !hasVisibleText(top) -> removeFromParent(top)
            }
        }
    }

    private fun hasVisibleText(root: ViewGroup): Boolean {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && child.visibility == View.VISIBLE) {
                if (!child.text?.toString()?.trim().isNullOrEmpty()) return true
            }
            if (child is ViewGroup && hasVisibleText(child)) return true
        }
        return false
    }

    private fun isDescendantOf(child: View?, ancestor: ViewGroup?): Boolean {
        if (child == null || ancestor == null) return false
        var parent: ViewParent? = child.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun collectTextExact(root: ViewGroup, exact: String, out: MutableList<TextView>) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && exact == child.text?.toString()?.trim()) out.add(child)
            if (child is ViewGroup) collectTextExact(child, exact, out)
        }
    }

    private fun styleNewBadge(activity: Activity, badge: TextView) {
        badge.text = "NEW"
        badge.textSize = 9f
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        badge.setTextColor(activity.getColor(R.color.hcf_cyan_bright))
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(activity, 38)
        badge.minimumWidth = dp(activity, 38)
        badge.setPadding(dp(activity, 7), 0, dp(activity, 7), 0)
        badge.isClickable = false
        badge.isFocusable = false
        badge.visibility = View.VISIBLE
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(activity.getColor(R.color.hcf_bg))
            cornerRadius = dp(activity, 12).toFloat()
            setStroke(dp(activity, 1), activity.getColor(R.color.hcf_cyan))
        }
    }

    private fun topLevelChild(menu: LinearLayout?, descendant: View?): View? {
        if (menu == null || descendant == null) return null
        var current = descendant
        var parent: ViewParent? = current.parent
        while (parent is View && parent !== menu) {
            current = parent
            parent = current.parent
        }
        return if (parent === menu) current else null
    }

    private fun findDirectText(root: LinearLayout, exact: String): TextView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? TextView ?: continue
            if (exact == child.text?.toString()?.trim()) return child
        }
        return null
    }

    private fun findTextExact(root: ViewGroup, exact: String): TextView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && exact == child.text?.toString()?.trim()) return child
            if (child is ViewGroup) findTextExact(child, exact)?.let { return it }
        }
        return null
    }

    private fun removeFromParent(view: View?) {
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    private fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)

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
