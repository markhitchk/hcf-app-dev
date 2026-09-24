package com.harleytg.forum.dev

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Advanced native motion engine for Harley's Clan Forum. */
object HcfUiMotion {
    private const val SETTINGS_ACTIVITY = "com.harleytg.forum.dev.HcfSubActivities\$SettingsActivity"
    private const val MAIN_ACTIVITY = "com.harleytg.forum.dev.HcfForum\$MainActivity"
    private const val STARTUP_ACTIVITY = "com.harleytg.forum.dev.HcfUI\$StartupActivity"
    private const val STARTUP_MAIN_ACTIVITY = "com.harleytg.forum.dev.HcfUI\$StartupMainActivity"

    private val OBSERVERS = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val PASS_PENDING = WeakHashMap<Activity, Boolean>()
    private val ACTIVITY_ENTERED = WeakHashMap<Activity, Boolean>()
    private val PROFILES = WeakHashMap<Activity, MotionProfile>()
    private val TREE_SIGNATURES = WeakHashMap<ViewGroup, Int>()
    private val PRESS_ATTACHED = WeakHashMap<View, Boolean>()
    private val LAST_VISIBLE = WeakHashMap<View, Boolean>()
    private val LAST_TEXT_HASH = WeakHashMap<View, Int>()
    private val SEEN_ROOT_CHILDREN = WeakHashMap<View, Boolean>()
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
        if (activity == null || activity.isFinishing || isSettings(activity)) return
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
        if (activity == null || activity.isFinishing || activity.window == null || isSettings(activity)) return
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
            if (!activity.isFinishing && !activity.isDestroyed && !isSettings(activity)) apply(activity)
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
            synchronized(ACTIVITY_ENTERED) { ACTIVITY_ENTERED.remove(activity) }
            synchronized(PROFILES) { PROFILES.remove(activity) }
        }
    }

    private fun apply(activity: Activity) {
        if (activity.window == null || isSettings(activity)) return
        val profile = profile(activity)
        val contentRoot = activity.findViewById<View>(android.R.id.content)
        if (contentRoot != null) attachInteractiveTree(contentRoot, profile)
        attachChrome(activity, profile)

        val firstEntrance = synchronized(ACTIVITY_ENTERED) {
            val first = ACTIVITY_ENTERED[activity] != true
            if (first) ACTIVITY_ENTERED[activity] = true
            first
        }
        if (firstEntrance) animateActivityEntrance(activity, contentRoot, profile)
        animateNewNativeRootChildren(contentRoot, firstEntrance, profile)
        animateNamedSurfaces(activity, profile)
        animateDrawerContents(activity, profile)
        animateStatusTextChanges(activity, profile)
    }

    private fun animateActivityEntrance(activity: Activity, contentRoot: View?, profile: MotionProfile) {
        if (!profile.enabled || isStartup(activity)) return
        if (MAIN_ACTIVITY == activity.javaClass.name) {
            val topBar = findNamedView(activity, "topAppBar")
            val urlBar = findNamedView(activity, "urlBar")
            if (isEffectivelyVisible(topBar)) {
                UiMotion.enterView(topBar, -profile.dpDistance(4), profile.duration(210L), 0L)
            }
            if (isEffectivelyVisible(urlBar)) {
                UiMotion.enterView(urlBar, -profile.dpDistance(3), profile.duration(225L), profile.duration(28L))
            }
            return
        }

        val root = contentRoot as? ViewGroup ?: return
        if (root.childCount == 0) return
        val page = root.getChildAt(0)
        if (!isEffectivelyVisible(page) || containsWebView(page)) return
        UiMotion.enterView(page, profile.dpDistance(7), profile.duration(235L), 0L)
    }

    private fun animateNewNativeRootChildren(contentRoot: View?, firstPass: Boolean, profile: MotionProfile) {
        val root = contentRoot as? ViewGroup ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val seen = synchronized(SEEN_ROOT_CHILDREN) {
                SEEN_ROOT_CHILDREN.put(child, true) == true
            }
            if (
                seen || firstPass || !profile.enabled || !isEffectivelyVisible(child) ||
                child is WebView || containsWebView(child)
            ) continue
            UiMotion.enterView(child, profile.dpDistance(5), profile.duration(205L), 0L)
        }
    }

    private fun attachChrome(activity: Activity, profile: MotionProfile) {
        attachPressState(activity.findViewById(R.id.drawerButton), 0.972f, profile)
        attachPressState(activity.findViewById(R.id.headerNotificationsButton), 0.972f, profile)
        attachPressState(activity.findViewById(R.id.urlBackButton), 0.972f, profile)
        attachPressState(activity.findViewById(R.id.reloadButton), 0.972f, profile)
        attachPressState(activity.findViewById(R.id.copyUrlButton), 0.972f, profile)
        attachPressState(activity.findViewById(R.id.urlHomeButton), 0.972f, profile)
        val logo = activity.findViewById<View>(R.id.appHeaderLogo)
        if (logo?.isClickable == true) attachPressState(logo, 0.986f, profile)
    }

    private fun animateNamedSurfaces(activity: Activity, profile: MotionProfile) {
        reveal(activity, "welcomeBanner", Effect.SLIDE_FROM_TOP, 190L, 4, profile)
        reveal(activity, "connectionBanner", Effect.SLIDE_FROM_TOP, 190L, 4, profile)
        reveal(activity, "safeModeBanner", Effect.SLIDE_FROM_TOP, 195L, 4, profile)
        reveal(activity, "errorShell", Effect.RISE, 235L, 8, profile)
        reveal(activity, "statusOverlay", Effect.FADE, 180L, 0, profile)
        reveal(activity, "bottomNav", Effect.RISE, 205L, 5, profile)
        reveal(activity, "pageProgress", Effect.FADE, 135L, 0, profile)
        reveal(activity, "liveStatusBadge", Effect.POP, 180L, 0, profile)
        reveal(activity, "headerNotificationCountBadge", Effect.POP, 175L, 0, profile)
        reveal(activity, "hostBadge", Effect.POP, 165L, 0, profile)
    }

    private fun reveal(
        activity: Activity,
        idName: String,
        effect: Effect,
        baseDurationMs: Long,
        distanceDp: Int,
        profile: MotionProfile
    ) {
        val view = findNamedView(activity, idName) ?: return
        if (!transitionedToShown(view, isEffectivelyVisible(view)) || !profile.enabled) return
        val duration = profile.duration(baseDurationMs)
        when (effect) {
            Effect.FADE -> UiMotion.fadeIn(view, duration, 0L)
            Effect.POP -> if (!view.isClickable) UiMotion.popIn(view, duration) else UiMotion.fadeIn(view, duration, 0L)
            Effect.SLIDE_FROM_TOP -> UiMotion.enterView(view, -profile.dpDistance(distanceDp), duration, 0L)
            Effect.RISE -> UiMotion.enterView(view, profile.dpDistance(distanceDp), duration, 0L)
        }
    }

    private fun animateDrawerContents(activity: Activity, profile: MotionProfile) {
        val drawer = findNamedView(activity, "drawerPanel") as? ViewGroup ?: return
        if (!transitionedToShown(drawer, isEffectivelyVisible(drawer)) || !profile.enabled) return
        UiMotion.staggerChildren(
            drawer,
            profile.dpDistance(3),
            profile.duration(185L),
            profile.duration(13L),
            profile.maxStagger(95L)
        )
    }

    private fun animateStatusTextChanges(activity: Activity, profile: MotionProfile) {
        emphasizeTextChange(activity, "headerNotificationCountBadge", true, profile)
        emphasizeTextChange(activity, "liveStatusBadge", true, profile)
        emphasizeTextChange(activity, "hostBadge", true, profile)
        if (!isStartup(activity)) {
            emphasizeTextChange(activity, "statusTitle", false, profile)
            emphasizeTextChange(activity, "statusSubtitle", false, profile)
            emphasizeTextChange(activity, "errorStatusText", false, profile)
        }
    }

    private fun emphasizeTextChange(activity: Activity, idName: String, pop: Boolean, profile: MotionProfile) {
        val view = findNamedView(activity, idName) as? TextView ?: return
        if (!isEffectivelyVisible(view)) return
        val hash = view.text?.toString()?.hashCode() ?: 0
        val previous = synchronized(LAST_TEXT_HASH) { LAST_TEXT_HASH.put(view, hash) }
        if (previous == null || previous == hash || !profile.enabled) return
        if (pop && !view.isClickable) UiMotion.pulse(view, profile.duration(220L))
        else UiMotion.softEmphasis(view, profile.duration(185L))
    }

    private fun attachInteractiveTree(view: View?, profile: MotionProfile) {
        if (view == null || view is WebView) return
        when {
            view is ImageButton -> attachPressState(view, 0.972f, profile)
            view is CompoundButton -> attachPressState(view, 0.988f, profile)
            view is Button -> attachPressState(view, 0.985f, profile)
            view !is EditText && view is TextView && view.isClickable -> attachPressState(view, 0.992f, profile)
            view is LinearLayout && view.isClickable -> attachPressState(view, 0.994f, profile)
        }

        val group = view as? ViewGroup ?: return
        val signature = childSignature(group)
        synchronized(TREE_SIGNATURES) {
            if (TREE_SIGNATURES[group] == signature) return
            TREE_SIGNATURES[group] = signature
        }
        for (i in 0 until group.childCount) attachInteractiveTree(group.getChildAt(i), profile)
    }

    private fun attachPressState(view: View?, fullScale: Float, profile: MotionProfile) {
        if (view == null || !view.isClickable) return
        synchronized(PRESS_ATTACHED) {
            if (PRESS_ATTACHED.containsKey(view)) return
            PRESS_ATTACHED[view] = true
        }
        if (!profile.enabled || view.stateListAnimator != null) return
        UiMotion.attachPressStateAnimator(
            view,
            profile.pressScale(fullScale),
            profile.duration(78L),
            profile.duration(145L)
        )
    }

    private fun childSignature(group: ViewGroup?): Int {
        if (group == null) return 0
        var result = 31 * 17 + group.childCount
        for (i in 0 until group.childCount) {
            result = 31 * result + System.identityHashCode(group.getChildAt(i))
        }
        return result
    }

    private fun transitionedToShown(view: View?, shown: Boolean): Boolean {
        if (view == null) return false
        val previous = synchronized(LAST_VISIBLE) { LAST_VISIBLE.put(view, shown) }
        return shown && previous != true
    }

    private fun isEffectivelyVisible(view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun containsWebView(view: View?): Boolean {
        if (view == null) return false
        if (view is WebView) return true
        val group = view as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            if (containsWebView(group.getChildAt(i))) return true
        }
        return false
    }

    private fun findNamedView(activity: Activity?, idName: String?): View? {
        if (activity == null || idName.isNullOrEmpty()) return null
        val id = activity.resources.getIdentifier(idName, "id", activity.packageName)
        return if (id == 0) null else activity.findViewById(id)
    }

    private fun isSettings(activity: Activity?): Boolean =
        activity != null && SETTINGS_ACTIVITY == activity.javaClass.name

    private fun isStartup(activity: Activity?): Boolean {
        val name = activity?.javaClass?.name ?: return false
        return name == STARTUP_ACTIVITY || name == STARTUP_MAIN_ACTIVITY
    }

    private fun profile(activity: Activity): MotionProfile {
        synchronized(PROFILES) {
            PROFILES[activity]?.let { if (!it.shouldRefresh()) return it }
        }
        val created = MotionProfile.resolve(activity)
        synchronized(PROFILES) { PROFILES[activity] = created }
        return created
    }

    private enum class Effect { FADE, RISE, SLIDE_FROM_TOP, POP }

    private class MotionProfile(
        val enabled: Boolean,
        val durationScale: Float,
        val distanceScale: Float,
        val pressScaleStrength: Float
    ) {
        private val resolvedAt = android.os.SystemClock.uptimeMillis()

        fun shouldRefresh(): Boolean =
            android.os.SystemClock.uptimeMillis() - resolvedAt > 4000L

        fun duration(base: Long): Long =
            if (!enabled) 0L else maxOf(1L, Math.round(base * durationScale))

        fun dpDistance(dp: Int): Int {
            if (!enabled || dp == 0) return 0
            val sign = if (dp < 0) -1 else 1
            return sign * maxOf(1, Math.round(kotlin.math.abs(dp) * distanceScale))
        }

        fun maxStagger(base: Long): Long = maxOf(1L, Math.round(base * durationScale))

        fun pressScale(requested: Float): Float =
            1f - ((1f - requested) * pressScaleStrength)

        companion object {
            fun resolve(activity: Activity): MotionProfile {
                if (!UiMotion.animationsEnabled()) return MotionProfile(false, 0f, 0f, 0f)

                var lowRam = false
                var powerSaver = false
                try {
                    lowRam = (activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                        ?.isLowRamDevice == true
                } catch (_: Throwable) {}
                try {
                    powerSaver = (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.isPowerSaveMode == true
                } catch (_: Throwable) {}

                val saved = try {
                    activity.getSharedPreferences("hcf_app", 0)
                        .getString("performance_profile", "").orEmpty()
                } catch (_: Throwable) {
                    ""
                }

                var duration = 1f
                var distance = 1f
                var press = 1f
                when (saved) {
                    "balanced" -> {
                        duration = 0.90f
                        distance = 0.85f
                        press = 0.85f
                    }
                    "performance" -> {
                        duration = 0.76f
                        distance = 0.64f
                        press = 0.70f
                    }
                }

                if (lowRam || powerSaver) {
                    duration = minOf(duration, 0.68f)
                    distance = minOf(distance, 0.55f)
                    press = minOf(press, 0.62f)
                }
                return MotionProfile(true, duration, distance, press)
            }
        }
    }
}

/** Shared property-animation primitives retained for compatibility with runtime helpers. */
object UiMotion {
    private val EMPHASIZED_DECELERATE: TimeInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)
    private val FAST_OUT: TimeInterpolator = PathInterpolator(0.40f, 0.0f, 1.0f, 1.0f)
    private val STANDARD: TimeInterpolator = PathInterpolator(0.20f, 0.0f, 0.20f, 1.0f)
    private val RUNNING = WeakHashMap<View, WeakReference<Animator>>()

    @JvmStatic
    fun animationsEnabled(): Boolean = try {
        ValueAnimator.areAnimatorsEnabled()
    } catch (_: Throwable) {
        true
    }

    @JvmStatic
    fun attachPressScale(view: View?, scale: Float, pressInMs: Long, releaseMs: Long) {
        if (view == null) return
        view.setOnTouchListener { v, event ->
            if (v == null || event == null || !v.isEnabled || !animationsEnabled()) {
                false
            } else {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        v.animate().scaleX(scale).scaleY(scale)
                            .setDuration(maxOf(1L, pressInMs))
                            .setInterpolator(FAST_OUT).start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL,
                    android.view.MotionEvent.ACTION_OUTSIDE -> {
                        v.animate().cancel()
                        v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(maxOf(1L, releaseMs))
                            .setInterpolator(EMPHASIZED_DECELERATE).start()
                    }
                }
                false
            }
        }
    }

    @JvmStatic
    fun attachPressStateAnimator(view: View?, pressedScale: Float, pressMs: Long, releaseMs: Long) {
        if (view == null || !animationsEnabled()) return
        val safeScale = maxOf(0.94f, minOf(1f, pressedScale))

        val pressed = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, safeScale),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, safeScale)
            )
            duration = maxOf(1L, pressMs)
            interpolator = FAST_OUT
        }
        val released = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f)
            )
            duration = maxOf(1L, releaseMs)
            interpolator = EMPHASIZED_DECELERATE
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), released)
        }
    }

    @JvmStatic
    fun enterView(view: View?, offsetDp: Int, durationMs: Long, delayMs: Long) {
        if (view == null || view.visibility != View.VISIBLE) return
        if (!animationsEnabled()) {
            normalize(view)
            return
        }
        cancelEntrance(view)
        val offset = dp(view.context, offsetDp)
        view.alpha = 0f
        view.translationY = offset.toFloat()
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, offset.toFloat(), 0f)
            )
            startDelay = maxOf(0L, delayMs)
            duration = maxOf(1L, durationMs)
            interpolator = EMPHASIZED_DECELERATE
        }
        rememberAndStart(view, set, true)
    }

    @JvmStatic
    fun fadeIn(view: View?, durationMs: Long, delayMs: Long) {
        if (view == null || view.visibility != View.VISIBLE) return
        if (!animationsEnabled()) {
            view.alpha = 1f
            return
        }
        cancelEntrance(view)
        view.translationY = 0f
        view.alpha = 0f
        val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
            startDelay = maxOf(0L, delayMs)
            duration = maxOf(1L, durationMs)
            interpolator = EMPHASIZED_DECELERATE
        }
        rememberAndStart(view, alpha, true)
    }

    @JvmStatic
    fun popIn(view: View?) = popIn(view, 165L)

    @JvmStatic
    fun popIn(view: View?, durationMs: Long) {
        if (view == null || view.visibility != View.VISIBLE) return
        if (!animationsEnabled()) {
            normalize(view)
            return
        }
        cancelEntrance(view)
        view.alpha = 0f
        view.scaleX = 0.955f
        view.scaleY = 0.955f
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.955f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.955f, 1f)
            )
            duration = maxOf(1L, durationMs)
            interpolator = EMPHASIZED_DECELERATE
        }
        rememberAndStart(view, set, true)
    }

    @JvmStatic
    fun pulse(view: View?, durationMs: Long) {
        if (view == null || !animationsEnabled()) return
        cancelEntrance(view)
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.055f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.055f, 1f)
            )
            duration = maxOf(1L, durationMs)
            interpolator = STANDARD
        }
        rememberAndStart(view, set, false)
    }

    @JvmStatic
    fun softEmphasis(view: View?, durationMs: Long) {
        if (view == null || !animationsEnabled()) return
        cancelEntrance(view)
        val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.72f, 1f).apply {
            duration = maxOf(1L, durationMs)
            interpolator = STANDARD
        }
        rememberAndStart(view, alpha, false)
    }

    @JvmStatic
    fun staggerChildren(parent: ViewGroup?, riseDp: Int, durationMs: Long, staggerMs: Long, maxDelayMs: Long) {
        if (parent == null || !animationsEnabled()) return
        var animatedIndex = 0
        val rise = dp(parent.context, riseDp)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (
                child.visibility != View.VISIBLE ||
                child is WebView ||
                containsWebViewChild(child)
            ) continue
            val delay = minOf(maxDelayMs, maxOf(0L, staggerMs) * animatedIndex)
            cancelEntrance(child)
            child.alpha = 0f
            child.translationY = rise.toFloat()
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(child, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, rise.toFloat(), 0f)
                )
                startDelay = delay
                duration = maxOf(1L, durationMs)
                interpolator = EMPHASIZED_DECELERATE
            }
            rememberAndStart(child, set, true)
            animatedIndex++
        }
    }

    @JvmStatic
    fun fadeInChildren(parent: ViewGroup?, staggerMs: Long) =
        staggerChildren(parent, 3, 180L, staggerMs, 90L)

    @JvmStatic
    fun fadeInChildren(parent: ViewGroup?, staggerMs: Long, durationMs: Long, riseDp: Int) =
        staggerChildren(parent, riseDp, durationMs, staggerMs, 90L)

    @JvmStatic
    fun fadeInChildrenAlpha(parent: ViewGroup?, staggerMs: Long, durationMs: Long) {
        if (parent == null) return
        var index = 0
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (
                child.visibility != View.VISIBLE ||
                child is WebView ||
                containsWebViewChild(child)
            ) continue
            fadeIn(child, durationMs, minOf(90L, maxOf(0L, staggerMs) * index))
            index++
        }
    }

    @JvmStatic
    fun cancelEntrance(view: View?) {
        if (view == null) return
        val reference = synchronized(RUNNING) { RUNNING.remove(view) }
        reference?.get()?.cancel()
    }

    private fun rememberAndStart(view: View?, animator: Animator?, useHardwareLayer: Boolean) {
        if (view == null || animator == null) return
        synchronized(RUNNING) { RUNNING[view] = WeakReference(animator) }
        val oldLayerType = view.layerType
        val promoted = useHardwareLayer && view.isAttachedToWindow && oldLayerType == View.LAYER_TYPE_NONE
        if (promoted) {
            try { view.setLayerType(View.LAYER_TYPE_HARDWARE, null) } catch (_: Throwable) {}
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            private var finished = false
            private fun finish() {
                if (finished) return
                finished = true
                synchronized(RUNNING) {
                    val ref = RUNNING[view]
                    if (ref?.get() === animator) RUNNING.remove(view)
                }
                if (promoted && view.layerType == View.LAYER_TYPE_HARDWARE) {
                    try { view.setLayerType(oldLayerType, null) } catch (_: Throwable) {}
                }
            }
            override fun onAnimationEnd(animation: Animator) = finish()
            override fun onAnimationCancel(animation: Animator) = finish()
        })
        animator.start()
    }

    private fun normalize(view: View) {
        cancelEntrance(view)
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun containsWebViewChild(view: View): Boolean {
        if (view is WebView) return true
        val group = view as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            if (containsWebViewChild(group.getChildAt(i))) return true
        }
        return false
    }

    private fun dp(context: Context?, value: Int): Int =
        if (context == null) value else Math.round(value * context.resources.displayMetrics.density)
}
