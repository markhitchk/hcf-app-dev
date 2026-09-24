package com.harleytg.forum.dev

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * Settings-only stability layer.
 *
 * Coordinates category rebuilds and accordion motion without alpha/scale flashing.
 */
object HcfSettingsStableUi {
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"

    private val OBSERVERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val PASS_PENDING = WeakHashMap<Activity, Boolean>()
    private val CONTENT_SIGNATURE = WeakHashMap<Activity, Int>()
    private val LAST_SECTION = WeakHashMap<Activity, String>()
    private val LAST_OPEN_PANEL = WeakHashMap<Activity, String>()
    private val PANEL_HEADERS = WeakHashMap<View, Boolean>()
    private val NAV_HIDE_ATTACHED = WeakHashMap<View, Boolean>()
    private val HEIGHT_ANIMATORS = WeakHashMap<View, ValueAnimator>()
    private val EASE: TimeInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)

    private var registered = false

    class BootstrapProvider : ContentProvider() {
        override fun onCreate(): Boolean {
            val app = context?.applicationContext as? Application ?: return true
            if (registered) return true
            registered = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = install(activity)
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = install(activity)
                override fun onActivityPaused(activity: Activity) = remove(activity, false)
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = remove(activity, true)
            })
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun install(activity: Activity?) {
        if (!isSettings(activity) || activity!!.isFinishing) return
        schedule(activity)
        synchronized(OBSERVERS) {
            if (OBSERVERS.containsKey(activity)) return
            val root = activity.window?.decorView ?: return
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!activity.isFinishing && !activity.isDestroyed) schedule(activity)
            }
            observer.addOnGlobalLayoutListener(listener)
            OBSERVERS[activity] = listener
        }
    }

    private fun schedule(activity: Activity?) {
        if (!isSettings(activity) || activity!!.window == null) return
        synchronized(PASS_PENDING) {
            if (PASS_PENDING[activity] == true) return
            PASS_PENDING[activity] = true
        }
        val root = activity.window?.decorView
        if (root == null) {
            synchronized(PASS_PENDING) { PASS_PENDING.remove(activity) }
            return
        }
        root.postOnAnimation {
            synchronized(PASS_PENDING) { PASS_PENDING.remove(activity) }
            if (!activity.isFinishing && !activity.isDestroyed) stabilize(activity)
        }
    }

    private fun remove(activity: Activity?, destroyed: Boolean) {
        if (activity == null) return
        val listener = synchronized(OBSERVERS) { OBSERVERS.remove(activity) }
        if (listener != null) {
            val observer = activity.window?.decorView?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnGlobalLayoutListener(listener)
        }
        synchronized(PASS_PENDING) { PASS_PENDING.remove(activity) }
        if (destroyed) {
            synchronized(CONTENT_SIGNATURE) { CONTENT_SIGNATURE.remove(activity) }
            synchronized(LAST_SECTION) { LAST_SECTION.remove(activity) }
            synchronized(LAST_OPEN_PANEL) { LAST_OPEN_PANEL.remove(activity) }
        }
    }

    private fun stabilize(activity: Activity) {
        val content = readViewGroupField(activity, "settingsContent") ?: return
        val section = safe(readStringField(activity, "currentSettingsSection"))
        val pendingKey = safe(readStringField(activity, "pendingSettingKey"))
        val previousSection = synchronized(LAST_SECTION) { safe(LAST_SECTION[activity]) }
        val previousOpenPanel = synchronized(LAST_OPEN_PANEL) { safe(LAST_OPEN_PANEL[activity]) }

        val signature = signature(content)
        val changed = synchronized(CONTENT_SIGNATURE) {
            val previous = CONTENT_SIGNATURE.put(activity, signature)
            previous == null || previous != signature
        }

        val sameCategoryRefresh =
            changed && section.isNotEmpty() && section == previousSection && pendingKey.isEmpty()

        normalize(content)

        if (section.isEmpty()) {
            configureHomeNavigation(content)
        } else if (changed) {
            configurePanels(
                content,
                pendingKey.isEmpty(),
                if (sameCategoryRefresh) previousOpenPanel else ""
            )
        }

        configureBackNavigation(activity, content)
        normalize(readViewField(activity, "headerTitleView"))
        normalize(readViewField(activity, "headerSubtitleView"))
        normalize(readViewField(activity, "headerBackButton"))

        synchronized(LAST_SECTION) { LAST_SECTION[activity] = section }
        synchronized(LAST_OPEN_PANEL) {
            LAST_OPEN_PANEL[activity] = if (section.isEmpty()) "" else findOpenPanelTitle(content)
        }
    }

    private fun configurePanels(content: ViewGroup?, forceClosed: Boolean, restorePanelTitle: String?) {
        if (content == null) return
        val wanted = safe(restorePanelTitle)
        for (i in 0 until content.childCount) {
            val panel = connectedPanel(content.getChildAt(i)) ?: continue
            val header = panel.getChildAt(0)
            val body = panel.getChildAt(1)

            cancelHeight(body)
            normalize(header)
            normalize(body)

            if (wanted.isNotEmpty() && wanted == panelTitle(header)) {
                settleOpen(header, body)
            } else if (forceClosed) {
                settleClosed(header, body)
            } else {
                settleCurrent(header, body)
            }

            header.setOnTouchListener(null)
            header.setOnClickListener {
                if (body.visibility == View.VISIBLE) closePanel(header, body, null)
                else openExclusively(content, panel)
            }
            synchronized(PANEL_HEADERS) { PANEL_HEADERS[header] = true }
        }
    }

    private fun openExclusively(content: ViewGroup?, target: ViewGroup?) {
        if (content == null || target == null) return
        var openSibling: ViewGroup? = null
        for (i in 0 until content.childCount) {
            val panel = connectedPanel(content.getChildAt(i)) ?: continue
            if (panel === target) continue
            if (panel.getChildAt(1).visibility == View.VISIBLE) {
                openSibling = panel
                break
            }
        }

        val targetHeader = target.getChildAt(0)
        val targetBody = target.getChildAt(1)
        if (openSibling == null) {
            openPanel(targetHeader, targetBody)
            return
        }
        closePanel(openSibling.getChildAt(0), openSibling.getChildAt(1)) {
            openPanel(targetHeader, targetBody)
        }
    }

    private fun openPanel(header: View?, body: View?) {
        if (header == null || body == null) return
        cancelHeight(body)
        normalize(header)
        normalize(body)

        val lp = body.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            body.visibility = View.VISIBLE
            header.setBackgroundResource(R.drawable.settings_section_header_expanded)
            rotateArrow(header, 90f, 140L)
            return
        }

        val parentWidth = (body.parent as? View)?.width ?: 0
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxOf(1, parentWidth), View.MeasureSpec.AT_MOST)
        body.visibility = View.INVISIBLE
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        body.layoutParams = lp
        body.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val targetHeight = maxOf(1, body.measuredHeight)

        lp.height = 0
        body.layoutParams = lp
        body.visibility = View.VISIBLE
        header.setBackgroundResource(R.drawable.settings_section_header_expanded)
        rotateArrow(header, 90f, 140L)

        val animator = ValueAnimator.ofInt(0, targetHeight)
        rememberHeight(body, animator)
        animator.duration = 165L
        animator.interpolator = EASE
        animator.addUpdateListener {
            lp.height = it.animatedValue as Int
            body.layoutParams = lp
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                forgetHeight(body, animator)
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.layoutParams = lp
            }

            override fun onAnimationCancel(animation: Animator) {
                forgetHeight(body, animator)
            }
        })
        animator.start()
    }

    private fun closePanel(header: View?, body: View?, endAction: Runnable?) {
        if (header == null || body == null) {
            endAction?.run()
            return
        }
        cancelHeight(body)
        normalize(header)
        normalize(body)

        val lp = body.layoutParams as? LinearLayout.LayoutParams
        if (lp == null || body.height <= 0) {
            settleClosed(header, body)
            endAction?.run()
            return
        }

        val startHeight = body.height
        rotateArrow(header, 0f, 120L)
        val animator = ValueAnimator.ofInt(startHeight, 0)
        rememberHeight(body, animator)
        animator.duration = 135L
        animator.interpolator = EASE
        animator.addUpdateListener {
            lp.height = it.animatedValue as Int
            body.layoutParams = lp
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var finished = false

            private fun finish() {
                if (finished) return
                finished = true
                forgetHeight(body, animator)
                settleClosed(header, body)
                endAction?.run()
            }

            override fun onAnimationEnd(animation: Animator) = finish()
            override fun onAnimationCancel(animation: Animator) = finish()
        })
        animator.start()
    }

    private fun settleCurrent(header: View, body: View) {
        if (body.visibility == View.VISIBLE) settleOpen(header, body) else settleClosed(header, body)
    }

    private fun settleOpen(header: View?, body: View?) {
        if (header == null || body == null) return
        normalize(header)
        normalize(body)
        body.visibility = View.VISIBLE
        body.layoutParams?.let {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            body.layoutParams = it
        }
        header.setBackgroundResource(R.drawable.settings_section_header_expanded)
        setArrow(header, 90f)
    }

    private fun settleClosed(header: View?, body: View?) {
        if (header == null || body == null) return
        cancelHeight(body)
        normalize(header)
        normalize(body)
        body.visibility = View.GONE
        body.layoutParams?.let {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            body.layoutParams = it
        }
        header.setBackgroundResource(R.drawable.settings_section_header_collapsed)
        setArrow(header, 0f)
    }

    private fun configureHomeNavigation(content: ViewGroup?) {
        if (content != null) attachPreHideToClickableDescendants(content, content, 0)
    }

    private fun configureBackNavigation(activity: Activity, content: ViewGroup) {
        val back = readViewField(activity, "headerBackButton") ?: return
        synchronized(NAV_HIDE_ATTACHED) {
            if (NAV_HIDE_ATTACHED.containsKey(back)) return
            NAV_HIDE_ATTACHED[back] = true
        }
        back.setOnTouchListener { _, event ->
            if (event?.actionMasked == MotionEvent.ACTION_UP) preHide(content)
            false
        }
    }

    private fun attachPreHideToClickableDescendants(view: View?, content: ViewGroup, depth: Int) {
        if (view == null || depth > 4) return
        if (view !== content && view.isClickable) {
            synchronized(NAV_HIDE_ATTACHED) {
                if (!NAV_HIDE_ATTACHED.containsKey(view)) {
                    NAV_HIDE_ATTACHED[view] = true
                    view.setOnTouchListener { _, event ->
                        if (event?.actionMasked == MotionEvent.ACTION_UP) preHide(content)
                        false
                    }
                }
            }
        }
        val group = view as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            attachPreHideToClickableDescendants(group.getChildAt(i), content, depth + 1)
        }
    }

    private fun preHide(content: ViewGroup?) {
        if (content == null) return
        content.animate().cancel()
        content.alpha = 0f
    }

    private fun normalize(view: View?) {
        if (view == null) return
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun rotateArrow(header: View, degrees: Float, durationMs: Long) {
        val arrow = trailingIndicator(header) ?: return
        arrow.animate().cancel()
        arrow.animate().rotation(degrees).setDuration(durationMs).setInterpolator(EASE).start()
    }

    private fun setArrow(header: View, degrees: Float) {
        val arrow = trailingIndicator(header) ?: return
        arrow.animate().cancel()
        arrow.rotation = degrees
        arrow.alpha = 1f
        arrow.scaleX = 1f
        arrow.scaleY = 1f
        arrow.translationX = 0f
        arrow.translationY = 0f
    }

    private fun trailingIndicator(root: View): View? {
        val group = root as? ViewGroup ?: return null
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val text = (child as? TextView)?.text?.toString()?.trim() ?: return null
            return if (
                text == "›" || text == ">" || text == "⌄" ||
                text == "⌃" || text == "∨" || text == "∧"
            ) child else null
        }
        return null
    }

    private fun connectedPanel(candidate: View): ViewGroup? {
        val panel = candidate as? ViewGroup ?: return null
        if (panel.childCount != 2) return null
        val header = panel.getChildAt(0)
        val body = panel.getChildAt(1)
        if (header !is LinearLayout || !header.isClickable) return null
        if (body !is LinearLayout) return null
        return panel
    }

    private fun findOpenPanelTitle(content: ViewGroup?): String {
        if (content == null) return ""
        for (i in 0 until content.childCount) {
            val panel = connectedPanel(content.getChildAt(i)) ?: continue
            if (panel.childCount < 2 || panel.getChildAt(1).visibility != View.VISIBLE) continue
            val title = panelTitle(panel.getChildAt(0))
            if (title.isNotEmpty()) return title
        }
        return ""
    }

    private fun panelTitle(view: View?): String {
        if (view == null) return ""
        if (view is TextView) {
            val value = safe(view.text?.toString())
            if (
                value.isNotEmpty() &&
                value != "›" && value != ">" && value != "⌄" &&
                value != "⌃" && value != "∨" && value != "∧"
            ) return value
        }
        val group = view as? ViewGroup ?: return ""
        for (i in 0 until group.childCount) {
            val value = panelTitle(group.getChildAt(i))
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun signature(content: ViewGroup): Int {
        var result = 17
        result = 31 * result + content.childCount
        for (i in 0 until content.childCount) {
            result = 31 * result + System.identityHashCode(content.getChildAt(i))
        }
        return result
    }

    private fun rememberHeight(body: View, animator: ValueAnimator) {
        synchronized(HEIGHT_ANIMATORS) { HEIGHT_ANIMATORS[body] = animator }
    }

    private fun forgetHeight(body: View, animator: ValueAnimator) {
        synchronized(HEIGHT_ANIMATORS) {
            if (HEIGHT_ANIMATORS[body] === animator) HEIGHT_ANIMATORS.remove(body)
        }
    }

    private fun cancelHeight(body: View?) {
        if (body == null) return
        synchronized(HEIGHT_ANIMATORS) { HEIGHT_ANIMATORS.remove(body) }?.cancel()
    }

    private fun isSettings(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun readViewField(activity: Activity, fieldName: String): View? =
        readField(activity, fieldName) as? View

    private fun readViewGroupField(activity: Activity, fieldName: String): ViewGroup? =
        readField(activity, fieldName) as? ViewGroup

    private fun readStringField(activity: Activity, fieldName: String): String =
        readField(activity, fieldName) as? String ?: ""

    private fun readField(activity: Activity?, fieldName: String): Any? {
        if (activity == null) return null
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            try {
                val field: Field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(activity)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun safe(value: String?): String = value?.trim().orEmpty()
}
