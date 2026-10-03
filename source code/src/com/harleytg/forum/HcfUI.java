package com.harleytg.forum.dev;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TextView;

import android.widget.Toast;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;


// ---- Consolidated from HcfUITheme.java ----
public final class HcfUI {
    private HcfUI() {}

    /**
     * Native startup gate for Harley's Clan Forum.
     *
     * Startup order:
     * Welcome -> App Setup -> access check -> system checks -> header/URL handoff -> forum WebView.
     */
    public static final class StartupActivity extends ThemedActivity {
        private static final int REQUEST_WELCOME = 4101;
        private static final int REQUEST_SETUP = 4102;

        private static final long QUICK_PATH_WINDOW_MS = 6L * 60L * 60L * 1000L;
        private static final long LOADER_FIRST_FRAME_HOLD_MS = 420L;
        private static final long STAGE_MIN_DWELL_MS = 110L;
        private static final long FULL_MIN_VISIBLE_MS = 1400L;
        private static final long FULL_EXTRA_WAIT_CAP_MS = 450L;
        private static final long HEADER_FADE_MS = 200L;
        private static final long URL_FADE_MS = 180L;
        private static final long LOADER_FADE_MS = 220L;
        private static final long WEBVIEW_HANDOFF_DELAY_MS = 90L;

        private static final String PREF_STARTUP_LAST_GOOD_AT = "startup_last_good_at";
        private static final String PREF_STARTUP_LAST_GOOD_HOST = "startup_last_good_host";
        private static final String PREF_STARTUP_LOADER_VERBOSE = "startup_loader_verbose";

        private static final int W_ACCESS = 110;
        private static final int W_PREFS = 55;
        private static final int W_WEBVIEW = 90;
        private static final int W_COOKIES = 55;
        private static final int W_IDENTITY = 90;
        private static final int W_DOMAINS = 75;
        private static final int W_INTEGRATION = 80;
        private static final int W_NOTIFICATIONS = 90;
        private static final int W_UPDATES = 75;
        private static final int W_RECOVERY = 60;
        private static final int W_STORAGE = 70;
        private static final int W_HOSTS = 100;
        private static final int W_READY = 50;
        private static final int FULL_WEIGHT_TOTAL = 1000;
        private static final int QUICK_WEIGHT_TOTAL = FULL_WEIGHT_TOTAL - W_DOMAINS - W_UPDATES - W_RECOVERY;
        private static final int FULL_STAGE_COUNT = 13;
        private static final int QUICK_STAGE_COUNT = 10;

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final java.util.ArrayList<String> completedSteps = new java.util.ArrayList<String>();

        private SharedPreferences prefs;
        private View topAppBar;
        private View urlBar;
        private View loaderBackdrop;
        private View loaderOverlay;
        private LinearLayout loaderPanel;
        private LinearLayout loaderStepRow;
        private ImageView loaderLogo;
        private TextView loaderTitle;
        private TextView loaderWelcomeSubtitle;
        private TextView loaderStep;
        private TextView loaderStatus;
        private TextView loaderDetail;
        private TextView loaderPercent;
        private TextView completedLabel;
        private TextView completedTicker;
        private ProgressBar loaderProgress;
        private Button retryButton;
        private WebView startupWebView;
        private android.animation.ValueAnimator progressAnimator;
        private android.animation.ValueAnimator logoPulseAnimator;

        private boolean gateInProgress;
        private boolean loaderStarted;
        private boolean handoffStarted;
        private boolean handoffPending;
        private boolean hardFailure;
        private boolean destroyed;
        private boolean resumed;
        private boolean quickPath;
        private boolean verboseLoader = false;
        private int runGeneration;
        private int completedWeight;
        private int totalWeight;
        private int totalStages;
        private long loaderVisibleAt;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            ThemeManager.apply(this);
            prefs = getSharedPreferences(AppPrefs.FILE, 0);

            int bg = ThemeManager.isAmoled(this) ? Color.BLACK : getColor(R.color.hcf_bg);
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);

            try {
                setContentView(R.layout.activity_main);
                prepareNativeChrome();
                installStartupOverlay();
            } catch (Throwable error) {
                AppLogger.crash(this, error);
                showEmergencyStartupFailure(error);
                return;
            }

            if (state == null && (SetupCenter.shouldShowWelcome(this) || SetupCenter.shouldAutoLaunch(this))) {
                getWindow().getDecorView().setAlpha(0.0f);
            }

            AppLogger.info(this, "startup_gate", "created | " + BuildInfo.VERSION_BUILD_LINE);
        }

        @Override
        protected void onResume() {
            super.onResume();
            resumed = true;
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (handoffPending) {
                        handoffPending = false;
                        beginChromeHandoff();
                    } else {
                        advanceStartupGate();
                    }
                }
            });
        }

        @Override
        protected void onPause() {
            resumed = false;
            HcfSessionPersistence.flushCookies();
            super.onPause();
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == UPDATE_INSTALL_PERMISSION_REQUEST) {
                prefs.edit().remove("update_resume_after_permission").apply();
            }
        }

        private View diagnosticsCard() {
            LinearLayout card = card();
            card.addView(sectionTitle("Error & Recovery Center", "Troubleshooting, runtime state and smart-recovery tools"));
            TextView recoveryState = target(settingsInfoText("Runtime & WebView", runtimeRecoverySummary()), "renderer_recovery");
            card.addView(recoveryState);
            card.addView(target(actionButton("Run Error & Recovery Check", v -> showRecoveryDiagnostics()), "error_recovery_check"));
            card.addView(target(actionButton("Runtime Snapshot", v -> showRuntimeSnapshot()), "runtime_snapshot"));
            card.addView(target(actionButton("Copy Sanitized Diagnostic Report", v -> copyDiagnosticReport()), "copy_diagnostic_report"));
            card.addView(target(actionButton("View App & Crash Logs", v -> startActivity(new Intent(this, HcfSubActivities.LogsActivity.class))), "view_crash_logs"));
            card.addView(target(actionButton("Clear WebView Cache", v -> clearWebViewCache()), "clear_webview_cache"));
            return card;
        }

        private TextView settingsInfoText(String title, String body) {
            TextView view = text(title + "\n" + body, 10, getColor(R.color.hcf_muted));
            view.setBackgroundResource(R.drawable.quick_action_background);
            view.setPadding(dp(14), dp(12), dp(14), dp(12));
            return view;
        }

        private String runtimeRecoverySummary() {
            String webView = "Unknown";
            try {
                PackageInfo current = WebView.getCurrentWebViewPackage();
                if (current != null) webView = current.packageName + " • " + current.versionName;
            } catch (Throwable ignored) {}
            String last = prefs.getString("last_recoverable_url", "");
            if (last == null || last.trim().isEmpty()) last = "Not recorded yet";
            else last = AppLogger.safeUrl(last);
            return "WebView: " + webView + "\nRenderer recovery: Enabled (HCF-WV-001)\nNative error state: Enabled • SSL fail-closed\nLast recoverable route: " + last;
        }

        private void showRecoveryDiagnostics() {
            String active = prefs.getString("active_host", "forum.harleytg.com");
            String host = ForumUrlRouter.isForumHost(active) ? active : "forum.harleytg.com";
            String message = "Network: " + (isValidatedNetworkAvailable() ? "✓ Connected" : "✕ Offline")
                    + "\nCurrent server: " + host
                    + "\nAutomatic failover: " + (prefs.getBoolean("auto_failover", true) ? "✓ Enabled" : "Disabled")
                    + "\n" + runtimeRecoverySummary();
            new AlertDialog.Builder(this).setTitle("Error & Recovery Check").setMessage(message).setPositiveButton("OK", null)
                    .setNeutralButton("View logs", (dialog, which) -> startActivity(new Intent(this, HcfSubActivities.LogsActivity.class))).show();
            AppLogger.info(this, "recovery_diagnostics", isValidatedNetworkAvailable() ? "network-ok" : "offline");
        }

        private void showRuntimeSnapshot() {
            final String snapshot = "HCF Runtime Snapshot\n\nApp: " + BuildInfo.VERSION + " (" + installedVersionCode() + ")"
                    + "\nPackage: " + getPackageName()
                    + "\nChannel: " + BuildInfo.CHANNEL
                    + "\nAndroid SDK: " + Build.VERSION.SDK_INT
                    + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nNetwork: " + RuntimeState.networkType(this)
                    + "\nTheme: " + ThemeManager.label(this)
                    + "\nPerformance: " + PerformanceProfile.settingLabel(this, prefs)
                    + "\nNotifications: " + NotificationHelper.status(this)
                    + "\nPrimary: forum.harleytg.com\nBackup: harleysclan.freeflarum.com\nRenderer recovery: Enabled (HCF-WV-001)";
            new AlertDialog.Builder(this).setTitle("Runtime Snapshot").setMessage(snapshot).setPositiveButton("Close", null)
                    .setNeutralButton("Copy", (dialog, which) -> copyText("HCF runtime snapshot", snapshot, "Runtime snapshot copied.")).show();
        }

        private void copyDiagnosticReport() {
            String active = prefs.getString("active_host", "forum.harleytg.com");
            String sync = prefs.getString("notification_last_sync_status", "not synced yet");
            long latency = prefs.getLong("notification_last_sync_latency_ms", 0L);
            String report = "Harley's Clan Forum • Sanitized Diagnostic Report"
                    + "\nApp: " + BuildInfo.VERSION + " (" + installedVersionCode() + ")"
                    + "\nPackage: " + getPackageName()
                    + "\nChannel: " + BuildInfo.CHANNEL
                    + "\nAndroid SDK: " + Build.VERSION.SDK_INT
                    + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nForum host: " + active
                    + "\nTheme: " + ThemeManager.label(this)
                    + "\nPerformance profile: " + PerformanceProfile.settingLabel(this, prefs)
                    + "\nNotifications: " + NotificationHelper.status(this)
                    + "\nLive sync: " + sync + (latency > 0 ? " • " + latency + " ms" : "")
                    + "\nAuto failover: " + (prefs.getBoolean("auto_failover", true) ? "On" : "Off")
                    + "\nRenderer recovery: Enabled (HCF-WV-001)"
                    + "\nLast route: " + AppLogger.safeUrl(prefs.getString("last_recoverable_url", ""))
                    + "\nCookies/tokens/passwords/email: Not included";
            copyText("HCF diagnostic report", report, "Sanitized diagnostic report copied.");
            AppLogger.info(this, "diagnostic_report_copy", "sanitized");
        }

        private void clearWebViewCache() {
            try {
                WebView webView = new WebView(this);
                webView.clearCache(true);
                webView.clearHistory();
                webView.destroy();
                AppLogger.info(this, "webview_cache_cleared", "settings");
                Toast.makeText(this, "WebView cache cleared.", Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                AppLogger.error(this, "webview_cache_clear", error.getClass().getSimpleName());
                Toast.makeText(this, "WebView cache could not be cleared on this device.", Toast.LENGTH_SHORT).show();
            }
        }

        private boolean isValidatedNetworkAvailable() {
            try {
                ConnectivityManager manager = (ConnectivityManager) getSystemService("connectivity");
                Network network = manager == null ? null : manager.getActiveNetwork();
                NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private View telemetryCard() {
            LinearLayout card = card();
            card.addView(sectionTitle("Telemetry & Diagnostics", "Crash reports, app health and privacy controls"));
            telemetryStatus = text(TelemetryService.status(this), 11, getColor(R.color.hcf_meta));
            telemetryStatus.setTypeface(null, 1);
            card.addView(telemetryStatus);
            Switch enabled = target(toggle("Enable Telemetry Services", prefs.getBoolean("telemetry_enabled", false)), "telemetry_enabled");
            enabled.setOnCheckedChangeListener((button, checked) -> {
                prefs.edit().putBoolean("telemetry_enabled", checked).apply();
                AppLogger.info(this, "setting_telemetry", Boolean.toString(checked));
                if (checked) {
                    TelemetryService.sendEvent(this, "telemetry_enabled", "User enabled Telemetry Services in App Settings");
                    Toast.makeText(this, "Telemetry Services enabled.", Toast.LENGTH_SHORT).show();
                } else Toast.makeText(this, "Telemetry disabled. No reports will be sent.", Toast.LENGTH_SHORT).show();
                refreshStatusLabels();
            });
            card.addView(enabled);
            Button level = target(actionButton("Telemetry level: " + TelemetryService.levelLabel(this), null), "telemetry_level");
            level.setOnClickListener(v -> {
                String next = "diagnostics".equals(TelemetryService.level(this)) ? "basic" : "diagnostics";
                prefs.edit().putString("telemetry_level", next).apply();
                level.setText("Telemetry level: " + TelemetryService.levelLabel(this));
                refreshStatusLabels();
            });
            card.addView(level);
            card.addView(text("Basic: coarse app health only. Diagnostics: adds crashes, sanitized stack traces, recent app events, and optional WebView/update errors.", 10, getColor(R.color.hcf_muted)));
            Switch crashes = target(toggle("Automatically send crash reports", prefs.getBoolean("telemetry_auto_crash_reports", false)), "auto_crash_reports");
            crashes.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("telemetry_auto_crash_reports", checked).apply());
            card.addView(crashes);
            Switch ask = target(toggle("Ask me before every crash report", prefs.getBoolean("telemetry_ask_before_crash_report", true)), "ask_before_crash_report");
            ask.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("telemetry_ask_before_crash_report", checked).apply());
            card.addView(ask);
            Switch errors = target(toggle("Automatically send WebView/update errors", prefs.getBoolean("telemetry_auto_error_reports", false)), "auto_error_reports");
            errors.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("telemetry_auto_error_reports", checked).apply());
            card.addView(errors);
            TextView privacyHeader = target(text("Report Privacy", 10, getColor(R.color.hcf_cyan)), "report_privacy");
            privacyHeader.setTypeface(null, 1);
            privacyHeader.setPadding(0, dp(8), 0, 0);
            card.addView(privacyHeader);
            card.addView(telemetryPrivacyToggle("Include my forum identity with reports", "telemetry_include_identity"));
            card.addView(telemetryPrivacyToggle("Include my email when identity is included", "telemetry_include_email"));
            card.addView(telemetryPrivacyToggle("Include device manufacturer/model", "telemetry_include_device_model"));
            card.addView(telemetryPrivacyToggle("Include sanitized forum route", "telemetry_include_route"));
            card.addView(target(actionButton("Send Diagnostic Feedback", v -> TelemetryService.showManualFeedbackDialog(this)), "diagnostic_feedback"));
            card.addView(target(actionButton("Preview Telemetry Report", v -> TelemetryService.showPreview(this)), "preview_telemetry"));
            card.addView(target(actionButton("View Report History", v -> TelemetryService.showHistory(this)), "telemetry_history"));
            card.addView(target(actionButton("Clear Local Telemetry Reports", v -> {
                TelemetryService.clearLocalReports(this);
                Toast.makeText(this, "Local telemetry reports and breadcrumbs cleared.", Toast.LENGTH_SHORT).show();
                refreshStatusLabels();
            }), "clear_telemetry"));
            card.addView(text("Crash reports can include a sanitized stack trace plus recent app events. Identity, email, device model and page route are separate opt-ins. Passwords, cookies, access/session tokens, recovery codes, posts, messages and page contents are never sent.", 10, getColor(R.color.hcf_muted)));
            return card;
        }

        private Switch telemetryPrivacyToggle(String title, String prefKey) {
            Switch toggle = toggle(title, prefs.getBoolean(prefKey, false));
            toggle.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(prefKey, checked).apply());
            return toggle;
        }

        private View developerToolsCard() {
            LinearLayout card = card();
            String environmentTitle = "stable".equals(effectiveUpdateChannel()) ? "Stable environment" : "Dev/Beta environment";
            String toolLabel = "stable".equals(effectiveUpdateChannel()) ? "Stable test tools" : "Development/Beta test tools";
            View environment = target(settingsInfoCard(environmentTitle,
                    toolLabel + "\nPackage: " + getPackageName()
                            + "\nBuild " + installedVersionCode()
                            + "\nChannel: " + BuildInfo.CHANNEL
                            + "\nUpdate feed: " + effectiveUpdateChannel(), R.drawable.fa_bug), "developer_environment");
            card.addView(environment);
            card.addView(settingsSubsectionHeader("Notification Lab", "Test HCF alert types, delivery and background synchronization", R.drawable.fa_bell));
            card.addView(target(actionButton("Notification Test Console", v -> showNotificationTestConsole()), "notification_test_console"));
            card.addView(target(actionButton("Test Notification Service", v -> testNotificationService()), "test_notification_service"));
            card.addView(target(actionButton("Force Notification Sync", v -> {
                HcfNotifications.InstantNotificationService.requestImmediateSync(this);
                Toast.makeText(this, "Immediate notification sync requested.", Toast.LENGTH_SHORT).show();
            }), "force_notification_sync"));

            card.addView(settingsSubsectionHeader(
                    "UI Playground",
                    "Preview newly designed HCF app screens",
                    R.drawable.fa_gear
            ));
            card.addView(target(actionButton("Open UI Playground", v -> showUiPlayground()), "ui_playground"));
            return card;
        }

        private String developerToolsSubtitle() {
            return "stable".equals(effectiveUpdateChannel()) ? "Stable test tools" : "Dev/Beta test controls";
        }

        private void showUiPlayground() {
            AppLogger.info(this, "ui_playground_open", BuildInfo.VERSION);

            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(14), dp(8), dp(14), dp(16));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

            content.addView(settingsInfoCard(
                    "Screen preview lab",
                    "Preview the newest HCF app screens without changing their normal layout.",
                    R.drawable.fa_bug
            ));

            content.addView(settingsSubsectionHeader(
                    "Screen previews",
                    "Open the newly designed app screens",
                    R.drawable.fa_circle_info
            ));
            content.addView(actionButton("Preview Welcome Screen", v ->
                    startActivity(new Intent(this, HcfForum.WelcomeActivity.class))));
            content.addView(actionButton("Preview App Settings", v ->
                    startActivity(new Intent(this, HcfSubActivities.SettingsActivity.class))));

            new AlertDialog.Builder(this)
                    .setTitle("UI Playground")
                    .setView(scroll)
                    .setNegativeButton("Close", null)
                    .show();
        }

        private void showNotificationTestConsole() {
            new AlertDialog.Builder(this).setTitle("Notification Test Console")
                    .setItems(new String[]{"Direct message", "Mention", "Discussion reply", "General HCF alert"}, (dialog, which) -> {
                        if (which == 0) postTestAlert("Direct message", "New private message test", "/notifications");
                        else if (which == 1) postTestAlert("Mention", "@you were mentioned in a forum post", "/notifications");
                        else if (which == 2) postTestAlert("Discussion reply", "New reply test notification", "/notifications");
                        else postTestAlert("Forum alert", "General Harley's Clan Forum notification test", "/notifications");
                    }).setNegativeButton("Cancel", null).show();
        }

        private void testNotificationService() {
            HcfNotifications.InstantNotificationService.requestImmediateSync(this);
            String message = NotificationHelper.postNotificationServiceTest(this)
                    ? "Notification service test sent • background sync requested."
                    : "Notification service test could not post. Check Android notification permission.";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }

        private void postTestAlert(String title, String body, String route) {
            String active = prefs.getString("active_host", "forum.harleytg.com");
            String host = ForumUrlRouter.isForumHost(active) ? active : "forum.harleytg.com";
            NotificationHelper.postTest(this, title, body, "https://" + host + (route == null ? "/notifications" : route));
            AppLogger.info(this, "notification_test", title);
        }

        private View aboutCard() {
            LinearLayout card = card();
            card.addView(target(settingsInfoCard("App identity",
                    getString(R.string.app_name) + "\nVersion " + BuildInfo.BASE_VERSION + "\nDev build " + BuildInfo.VERSION_TAG + " • Android build " + installedVersionCode() + "\n" + BuildInfo.DEVELOPMENT_BUILD_LABEL,
                    R.drawable.fa_circle_info), "app_identity"));
            card.addView(target(settingsInfoCard("Build & channel",
                    "Channel: " + BuildInfo.CHANNEL + " • Update feed: " + effectiveUpdateChannel()
                            + "\nPackage: " + getPackageName() + "\nAPK: " + BuildInfo.APK_FILE_NAME,
                    R.drawable.fa_download), "build_channel"));
            card.addView(target(settingsInfoCard("Device & runtime",
                    "Android SDK " + Build.VERSION.SDK_INT + " • " + Build.MANUFACTURER + " " + Build.MODEL
                            + "\nTheme: " + ThemeManager.label(this)
                            + "\n" + PerformanceProfile.settingLabel(this, prefs) + " • Network: " + RuntimeState.networkType(this),
                    R.drawable.fa_gear), "device_runtime"));
            card.addView(target(settingsInfoCard("Forum endpoints", "Primary: forum.harleytg.com\nBackup: harleysclan.freeflarum.com", R.drawable.fa_globe), "forum_endpoints"));
            card.addView(settingsSubsectionHeader("Release & support", "Release notes, support and portable build information", R.drawable.fa_circle_info));
            card.addView(target(actionButton("View What's New • v" + BuildInfo.VERSION, v -> ReleaseNotes.showCustom(this, prefs, true)), "whats_new"));
            card.addView(target(actionButton("Release & Build Details", v -> showBuildDetails()), "release_build_details"));
            card.addView(target(actionButton("Copy App Information", v -> copyAboutInformation()), "copy_app_information"));
            card.addView(target(actionButton("Contact Support", v -> openSupport()), "contact_support"));
            card.addView(target(settingsInfoCard("Privacy", "Sanitized reports never include passwords, cookies, access/session tokens, recovery codes, posts, messages or page contents.", R.drawable.fa_shield), "privacy"));
            return card;
        }

        private void showBuildDetails() {
            new AlertDialog.Builder(this).setTitle("Release & Build Details")
                    .setMessage(getString(R.string.app_name) + " Android app"
                            + "\n\nVersion: " + BuildInfo.VERSION
                            + "\nVersion code: " + installedVersionCode()
                            + "\nChannel: " + BuildInfo.CHANNEL
                            + "\nUpdate feed: " + effectiveUpdateChannel()
                            + "\nPackage: " + getPackageName()
                            + "\nAPK: " + BuildInfo.APK_FILE_NAME
                            + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL)
                    .setPositiveButton("Close", null).show();
        }

        private void copyAboutInformation() {
            String info = getString(R.string.app_name)
                    + "\nVersion: " + BuildInfo.VERSION + " (" + installedVersionCode() + ")"
                    + "\nChannel: " + BuildInfo.CHANNEL + " / " + effectiveUpdateChannel()
                    + "\nPackage: " + getPackageName()
                    + "\nAndroid SDK: " + Build.VERSION.SDK_INT
                    + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nTheme: " + ThemeManager.label(this)
                    + "\nPerformance: " + PerformanceProfile.settingLabel(this, prefs)
                    + "\nPrimary forum: forum.harleytg.com\nBackup forum: harleysclan.freeflarum.com";
            copyText("HCF app information", info, "App information copied.");
        }

        private void openSupport() {
            try {
                startActivity(new Intent(this, HcfSubActivities.SupportContactActivity.class));
                AppLogger.info(this, "support_contact_open", "about");
            } catch (Throwable error) {
                Toast.makeText(this, "Unable to open Contact Support.", Toast.LENGTH_LONG).show();
                AppLogger.error(this, "support_contact_open", error.getClass().getSimpleName());
            }
        }

        private void copyText(String label, String value, String toast) {
            try {
                ClipboardManager manager = (ClipboardManager) getSystemService("clipboard");
                if (manager == null) throw new IllegalStateException("Clipboard unavailable");
                manager.setPrimaryClip(ClipData.newPlainText(label, value));
                Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, "Could not copy this information.", Toast.LENGTH_SHORT).show();
            }
        }

        private long installedVersionCode() {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            } catch (Throwable ignored) {
                return BuildInfo.VERSION_CODE;
            }
        }

        private String ageLabel(long timestamp) {
            long age = Math.max(0L, System.currentTimeMillis() - timestamp);
            long seconds = age / 1000L;
            if (seconds < 2) return "just now";
            if (seconds < 60) return seconds + " seconds ago";
            long minutes = seconds / 60L;
            if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
            long hours = minutes / 60L;
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }

        public void refreshStatusLabels() {
            if (notificationStatus != null) {
                NotificationHelper.createChannel(this);
                boolean ready = NotificationHelper.canPost(this) && NotificationHelper.channelImportance(this) >= 4;
                notificationStatus.setText(NotificationHelper.status(this));
                notificationStatus.setTextColor(getColor(ready ? R.color.hcf_accent_text : R.color.hcf_warning));
            }
            if (cookieStatus != null) cookieStatus.setText(cookieSummary());
            if (securityStatus != null) securityStatus.setText(permissionSecuritySummary());
            if (telemetryStatus != null) telemetryStatus.setText(TelemetryService.status(this));
            if (updateChannelStatus != null) updateChannelStatus.setText(updateChannelLine(effectiveUpdateChannel()));
            if (updateInstallButton != null) updateInstallButton.setVisibility(AppUpdateDownloader.isDownloaded(this) ? View.VISIBLE : View.GONE);
            if (serverStatus != null) {
                String host = prefs.getString("active_host", "forum.harleytg.com");
                boolean primary = "forum.harleytg.com".equalsIgnoreCase(host);
                serverStatus.setText("Current server: " + (primary ? "Primary • " : "Backup • ") + host);
                serverStatus.setTextColor(getColor(primary ? R.color.hcf_cyan : R.color.hcf_warning));
            }
            if (hostHealthStatus != null) hostHealthStatus.setText(hostHealthSummary());
        }

        private String cookieSummary() {
            CookieManager manager = CookieManager.getInstance();
            int primary = countCookies(manager.getCookie("https://forum.harleytg.com/"));
            int backup = countCookies(manager.getCookie("https://harleysclan.freeflarum.com/"));
            return "Cookie data: " + (primary + backup) + " visible • Primary " + primary + " • Backup " + backup;
        }

        private int countCookies(String value) {
            if (value == null || value.trim().isEmpty()) return 0;
            int count = 0;
            for (String part : value.split(";")) if (!part.trim().isEmpty()) count++;
            return count;
        }

        private View connectedSettingsPanel(String title, String subtitle, View content, boolean expanded) {
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(-1, -2);
            panelLp.bottomMargin = dp(compact() ? 8 : 12);
            panel.setLayoutParams(panelLp);
            final LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(16);
            header.setClickable(true);
            header.setFocusable(true);
            header.setPadding(dp(15), dp(compact() ? 10 : 13), dp(12), dp(compact() ? 10 : 13));
            header.setBackgroundResource(expanded ? R.drawable.settings_section_header_expanded : R.drawable.settings_section_header_collapsed);
            ImageView icon = settingsSectionIcon(settingsIconForTitle(title));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconLp.rightMargin = dp(11);
            header.addView(icon, iconLp);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = text(title, 14, getColor(R.color.hcf_accent_text));
            titleView.setTypeface(null, 1);
            labels.addView(titleView);
            if (subtitle != null && !subtitle.trim().isEmpty()) labels.addView(text(subtitle, 10, getColor(R.color.hcf_muted)));
            header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
            final TextView arrow = text("›", 22, getColor(R.color.hcf_cyan_bright));
            arrow.setGravity(17);
            arrow.setRotation(expanded ? 90.0f : 0.0f);
            header.addView(arrow, new LinearLayout.LayoutParams(dp(28), -1));
            panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setBackgroundResource(R.drawable.settings_section_body);
            body.setPadding(dp(14), dp(12), dp(14), dp(14));
            body.setPivotY(0.0f);
            if (content != null) {
                if (content instanceof LinearLayout) {
                    LinearLayout layout = (LinearLayout) content;
                    if (layout.getChildCount() > 0 && "hcf_section_title".equals(layout.getChildAt(0).getTag())) layout.removeViewAt(0);
                }
                content.setBackgroundColor(Color.TRANSPARENT);
                content.setPadding(0, 0, 0, 0);
                content.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                body.addView(content);
            }
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            body.setAlpha(expanded ? 1.0f : 0.0f);
            body.setScaleY(expanded ? 1.0f : 0.96f);
            panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
            final boolean[] isExpanded = {expanded};
            header.setOnClickListener(v -> {
                if (isExpanded[0]) {
                    isExpanded[0] = false;
                    arrow.animate().rotation(0.0f).setDuration(150L).start();
                    body.animate().alpha(0.0f).scaleY(0.96f).setDuration(150L).withEndAction(() -> {
                        body.setVisibility(View.GONE);
                        header.setBackgroundResource(R.drawable.settings_section_header_collapsed);
                    }).start();
                } else {
                    isExpanded[0] = true;
                    header.setBackgroundResource(R.drawable.settings_section_header_expanded);
                    body.setVisibility(View.VISIBLE);
                    body.setAlpha(0.0f);
                    body.setScaleY(0.94f);
                    arrow.animate().rotation(90.0f).setDuration(170L).start();
                    body.animate().alpha(1.0f).scaleY(1.0f).setDuration(180L).start();
                }
            });
            return panel;
        }

        private View settingsSubsectionHeader(String title, String subtitle, int iconRes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(16);
            row.setPadding(dp(4), dp(14), dp(4), dp(5));
            ImageView icon = settingsSectionIcon(iconRes);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(19), dp(19));
            iconLp.rightMargin = dp(10);
            row.addView(icon, iconLp);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = text(title, 13, getColor(R.color.hcf_accent_text));
            titleView.setTypeface(null, 1);
            labels.addView(titleView);
            labels.addView(text(subtitle, 10, getColor(R.color.hcf_muted)));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
            return row;
        }

        private View settingsInfoCard(String title, String body, int iconRes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(48);
            row.setBackgroundResource(R.drawable.quick_action_background);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            ImageView icon = settingsSectionIcon(iconRes);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.rightMargin = dp(11);
            iconLp.topMargin = dp(2);
            row.addView(icon, iconLp);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = text(title, 13, getColor(R.color.hcf_text));
            titleView.setTypeface(null, 1);
            labels.addView(titleView);
            TextView bodyView = text(body, 10, getColor(R.color.hcf_muted));
            bodyView.setSingleLine(false);
            bodyView.setMaxLines(Integer.MAX_VALUE);
            labels.addView(bodyView);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(7);
            row.setLayoutParams(lp);
            return row;
        }

        private LinearLayout card() {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            if (ThemeManager.isAmoled(this)) card.setBackgroundColor(Color.rgb(3, 5, 7));
            else card.setBackgroundResource(R.drawable.card_background);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(compact() ? 8 : 12);
            card.setLayoutParams(lp);
            return card;
        }

        private View notificationChannelStatusRow(String title, String subtitle, String channelId) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(16);
            row.setBackgroundResource(R.drawable.quick_action_background);
            row.setPadding(dp(14), dp(8), dp(12), dp(8));
            ImageView icon = settingsSectionIcon(R.drawable.fa_lock);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.rightMargin = dp(10);
            row.addView(icon, iconLp);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = text(title, 14, getColor(R.color.hcf_text));
            titleView.setTypeface(null, 1);
            labels.addView(titleView);
            String status = NotificationHelper.channelStatus(this, channelId);
            labels.addView(text(subtitle + " • " + status, 10, getColor(NotificationHelper.channelImportance(this, channelId) == 0 ? R.color.hcf_warning : R.color.hcf_muted)));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
            TextView chip = text("REQUIRED", 9, getColor(R.color.hcf_accent_text));
            chip.setTypeface(null, 1);
            chip.setGravity(17);
            chip.setPadding(dp(8), dp(4), dp(8), dp(4));
            chip.setBackgroundResource(R.drawable.status_chip_background);
            row.addView(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(compact() ? 58 : 66));
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            return row;
        }

        private View notificationChannelRow(String title, String subtitle, String channelId) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(16);
            row.setBackgroundResource(R.drawable.quick_action_background);
            row.setPadding(dp(14), dp(9), dp(14), dp(9));
            ImageView icon = settingsSectionIcon(R.drawable.fa_bell);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.rightMargin = dp(10);
            row.addView(icon, iconLp);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = text(title, 14, getColor(R.color.hcf_text));
            titleView.setTypeface(null, 1);
            labels.addView(titleView);
            String status = NotificationHelper.channelStatus(this, channelId);
            labels.addView(text(subtitle + " • " + status, 10, getColor(NotificationHelper.channelImportance(this, channelId) == 0 ? R.color.hcf_warning : R.color.hcf_muted)));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            return row;
        }

        private View sectionTitle(String title, String subtitle) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setTag("hcf_section_title");
            block.setPadding(0, 0, 0, dp(8));
            TextView titleView = text(title, 16, getColor(R.color.hcf_accent_text));
            titleView.setTypeface(null, 1);
            block.addView(titleView);
            block.addView(text(subtitle, 11, getColor(R.color.hcf_muted)));
            return block;
        }

        private TextView text(String value, int size, int color) {
            TextView view = new TextView(this);
            view.setText(value);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setLineSpacing(0.0f, 1.12f);
            view.setPadding(0, dp(3), 0, dp(3));
            return view;
        }

        private Switch toggle(String title, boolean checked) {
            Switch view = new Switch(this);
            view.setText(title);
            view.setTextColor(getColor(R.color.hcf_text));
            view.setTextSize(14.0f);
            view.setChecked(checked);
            view.setPadding(0, dp(7), 0, dp(7));
            return view;
        }

        private Button actionButton(String title, View.OnClickListener listener) {
            Button button = new Button(this);
            UiButtons.normalizeText(button);
            button.setText(cleanIconPrefix(title));
            button.setTextColor(getColor(R.color.hcf_accent_text));
            button.setBackgroundResource(R.drawable.quick_action_background);
            button.setAllCaps(false);
            button.setGravity(8388627);
            button.setPadding(dp(14), 0, dp(14), 0);
            FaIcons.applyStart(button, title);
            button.setOnClickListener(listener);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(compact() ? 44 : 52));
            lp.topMargin = dp(7);
            button.setLayoutParams(lp);
            return button;
        }

        private ImageButton chromeButton(String description) {
            return UiButtons.iconButton(this, R.drawable.fa_arrow_left, R.drawable.chrome_button_background, compact() ? 9 : 11,
                    description == null || description.trim().isEmpty() ? "Back" : description);
        }

        private String cleanIconPrefix(String value) {
            return value == null ? "" : value.replaceFirst("^[^A-Za-z0-9]+", "").trim();
        }

        private ImageView settingsSectionIcon(int resId) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(resId);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setColorFilter(getColor(R.color.hcf_cyan));
            icon.setContentDescription(null);
            return icon;
        }

        private int settingsIconForKey(String key) {
            if ("account_security".equals(key)) return R.drawable.fa_shield;
            if ("notifications".equals(key)) return R.drawable.fa_bell;
            if ("appearance".equals(key)) return R.drawable.fa_gear;
            if ("widget".equals(key)) return R.drawable.fa_gear;
            if ("forum_data".equals(key)) return R.drawable.fa_globe;
            return R.drawable.fa_circle_info;
        }

        private int settingsIconForTitle(String title) {
            String lower = title == null ? "" : title.toLowerCase(Locale.US);
            if (lower.contains("account") || lower.contains("identity")) return R.drawable.fa_user;
            if (lower.contains("permission") || lower.contains("security")) return R.drawable.fa_shield;
            if (lower.contains("notification") || lower.contains("alert")) return R.drawable.fa_bell;
            if (lower.contains("appearance") || lower.contains("performance") || lower.contains("runtime") || lower.contains("widget")) return R.drawable.fa_gear;
            if (lower.contains("connection") || lower.contains("routing") || lower.contains("endpoint") || lower.contains("host")) return R.drawable.fa_globe;
            if (lower.contains("cookie") || lower.contains("site data")) return R.drawable.fa_lock;
            if (lower.contains("update")) return R.drawable.fa_download;
            if (lower.contains("error") || lower.contains("recovery")) return R.drawable.fa_triangle_exclamation;
            if (lower.contains("developer")) return R.drawable.fa_bug;
            return R.drawable.fa_circle_info;
        }

        private void openForumLinkSettings() {
            try {
                startActivity(new Intent("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS", Uri.parse("package:" + getPackageName())));
                AppLogger.info(this, "forum_link_settings", "open-by-default");
            } catch (Throwable first) {
                try {
                    startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + getPackageName())));
                } catch (Throwable second) {
                    Toast.makeText(this, "Android link settings are unavailable on this device.", Toast.LENGTH_SHORT).show();
                }
            }
        }

        private void requestNotificationPermissionIfNeeded() {
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0) {
                Toast.makeText(this, "Notification permission is already allowed.", Toast.LENGTH_SHORT).show();
                return;
            }
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATIONS);
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode != REQUEST_NOTIFICATIONS) return;
            boolean granted = grantResults.length > 0 && grantResults[0] == 0;
            AppLogger.info(this, "notification_permission", granted ? "granted" : "denied | " + NotificationHelper.status(this));
            Toast.makeText(this, granted ? "Notifications allowed. Sending a heads-up test…" : "Notifications not allowed.", Toast.LENGTH_SHORT).show();
            NotificationSyncScheduler.apply(this);
            refreshStatusLabels();
            if (granted) NotificationHelper.post(this, "Harley's Clan Forum", "Heads-up notifications are enabled. This is a test alert.", ForumUrlRouter.home("forum.harleytg.com"));
        }

        private boolean compact() {
            return getResources().getConfiguration().orientation == 2;
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }

    // ---- SupportContactActivity.java ----
    /* loaded from: classes.dex */
    public static final class SupportContactActivity extends ThemedActivity {
        private static final String SUPPORT_EMAIL = "harleytg.hq@gmail.com";
        private Spinner categoryField;
        private EditText expectedField;
        private EditText guestNameField;
        private EditText guestReplyEmailField;
        private ForumIdentity.Snapshot identity;
        private CheckBox includeDiagnostics;
        private CheckBox includeIdentity;
        private CheckBox includeRoute;
        private EditText messageField;
        private SharedPreferences prefs;
        private EditText stepsField;
        private EditText subjectField;

        @Override // com.harleytg.forum.dev.ThemedActivity, android.content.SharedPreferences.OnSharedPreferenceChangeListener
        public /* bridge */ /* synthetic */ void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String str) {
            super.onSharedPreferenceChanged(sharedPreferences, str);
        }

        @Override // com.harleytg.forum.dev.ThemedActivity, android.app.Activity
        protected void onCreate(Bundle bundle) {
            super.onCreate(bundle);
            ThemeManager.apply(this);
            this.identity = ForumIdentity.load(this);
            this.prefs = getSharedPreferences("hcf_app", 0);
            setTitle("Contact Support");
            buildUi();
        }

        private void buildUi() {
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(1);
            linearLayout.setBackgroundColor(getColor(R.color.hcf_bg));
            LinearLayout linearLayout2 = new LinearLayout(this);
            linearLayout2.setOrientation(0);
            linearLayout2.setGravity(16);
            linearLayout2.setPadding(dp(12), dp(10), dp(12), dp(10));
            linearLayout2.setBackgroundColor(getColor(R.color.hcf_app_bar));
            ImageButton iconButton = UiButtons.iconButton(this, R.drawable.fa_arrow_left, R.drawable.chrome_button_background, 11, "Back");
            iconButton.setOnClickListener(new View.OnClickListener() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda3
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    SupportContactActivity.this.m204lambda$buildUi$0$comharleytgforumdevSupportContactActivity(view);
                }
            });
            linearLayout2.addView(iconButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
            ImageView imageView = new ImageView(this);
            imageView.setImageResource(R.drawable.htg_app_logo);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setContentDescription("Harley's Clan Forum");
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            layoutParams.leftMargin = dp(4);
            linearLayout2.addView(imageView, layoutParams);
            LinearLayout linearLayout3 = new LinearLayout(this);
            linearLayout3.setOrientation(1);
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(0, -2, 1.0f);
            layoutParams2.leftMargin = dp(10);
            linearLayout3.addView(label("Contact Support", 19, R.color.hcf_text, true));
            linearLayout3.addView(label("Forum help, app support & report tools", 10, R.color.hcf_cyan_bright, true));
            linearLayout2.addView(linearLayout3, layoutParams2);
            linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(-1, -2));
            ScrollView scrollView = new ScrollView(this);
            scrollView.setFillViewport(true);
            LinearLayout linearLayout4 = new LinearLayout(this);
            linearLayout4.setOrientation(1);
            linearLayout4.setPadding(dp(12), dp(12), dp(12), dp(24));
            linearLayout4.addView(supportPanel("Your account", "Locked forum identity and reply information", R.drawable.fa_user, accountBody(), false));
            linearLayout4.addView(supportPanel("Support request", "Tell us what happened and what you expected", R.drawable.fa_envelope, requestBody(), false));
            linearLayout4.addView(supportPanel("Report context", "Read-only app and device information", R.drawable.fa_circle_info, contextBody(), false));
            linearLayout4.addView(supportPanel("Privacy & send", "Choose what to include, preview, then send", R.drawable.fa_shield, privacyBody(), false));
            TextView label = label("Harley's Clan Forum • Contact Support v2 • v1.0", 9, R.color.hcf_hint, false);
            label.setGravity(17);
            label.setPadding(0, dp(6), 0, dp(4));
            linearLayout4.addView(label);
            scrollView.addView(linearLayout4, new FrameLayout.LayoutParams(-1, -2));
            linearLayout.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            setContentView(linearLayout);
        }

        /* renamed from: lambda$buildUi$0$com-harleytg-forum-dev-SupportContactActivity, reason: not valid java name */
        /* synthetic */ void m204lambda$buildUi$0$comharleytgforumdevSupportContactActivity(View view) {
            finish();
        }

        private View accountBody() {
            String str;
            LinearLayout bodyContainer = bodyContainer();
            if (this.identity.loggedIn) {
                String str2 = "Not exposed";
                addLockedRow(bodyContainer, "Display name", nonEmpty(this.identity.displayName, "Not exposed"));
                if (this.identity.username.isEmpty()) {
                    str = "Not exposed";
                } else {
                    str = "@" + this.identity.username;
                }
                addLockedRow(bodyContainer, "Username", str);
                if (!this.identity.email.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(this.identity.email);
                    sb.append(this.identity.emailConfirmed ? " • verified" : " • unverified");
                    str2 = sb.toString();
                }
                addLockedRow(bodyContainer, "Forum email", str2);
                addLockedRow(bodyContainer, "Role", this.identity.identityMetaLabel());
                addLockedRow(bodyContainer, "Forum host", nonEmpty(this.identity.host, "forum.harleytg.com"));
                TextView label = label("These identity fields are synced from the signed-in forum session and cannot be edited here.", 10, R.color.hcf_muted, false);
                label.setPadding(0, dp(4), 0, 0);
                bodyContainer.addView(label);
            } else {
                TextView label2 = label("No signed-in forum identity was detected. Enter a name and reply email for this support request.", 11, R.color.hcf_muted, false);
                label2.setPadding(0, 0, 0, dp(10));
                bodyContainer.addView(label2);
                EditText input = input("Name or display name", 1, false);
                this.guestNameField = input;
                addField(bodyContainer, "Name", input);
                EditText input2 = input("Email support can reply to", 33, false);
                this.guestReplyEmailField = input2;
                addField(bodyContainer, "Reply email", input2);
            }
            return bodyContainer;
        }

        private View requestBody() {
            LinearLayout bodyContainer = bodyContainer();
            addLockedRow(bodyContainer, "Support destination", SUPPORT_EMAIL);
            bodyContainer.addView(fieldLabel("Support type"));
            this.categoryField = new Spinner(this);
            this.categoryField.setAdapter((SpinnerAdapter) new ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"App issue", "Account issue", "Notifications", "Login / identity", "Bug report", "Feature request", "Update / install", "Forum / WebView", "Privacy / security", "Other"}));
            this.categoryField.setBackgroundResource(R.drawable.identity_card_background);
            this.categoryField.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, dp(50));
            layoutParams.bottomMargin = dp(11);
            bodyContainer.addView(this.categoryField, layoutParams);
            EditText input = input("Short description of the issue", 16385, false);
            this.subjectField = input;
            addField(bodyContainer, "Subject", input);
            EditText input2 = input("What happened? Include any error message you saw.", 147457, true);
            this.messageField = input2;
            input2.setMinLines(5);
            this.messageField.setGravity(8388659);
            addField(bodyContainer, "What happened", this.messageField);
            EditText input3 = input("Optional: steps that reproduce the problem", 147457, true);
            this.stepsField = input3;
            input3.setMinLines(3);
            this.stepsField.setGravity(8388659);
            addField(bodyContainer, "Steps to reproduce", this.stepsField);
            EditText input4 = input("Optional: what should have happened instead", 147457, true);
            this.expectedField = input4;
            input4.setMinLines(3);
            this.expectedField.setGravity(8388659);
            addField(bodyContainer, "Expected behavior", this.expectedField);
            return bodyContainer;
        }

        private View contextBody() {
            LinearLayout bodyContainer = bodyContainer();
            String activeHost = activeHost();
            String currentRoute = currentRoute();
            addLockedRow(bodyContainer, "App", "Harley's Clan Forum v" + BuildInfo.VERSION + " • build " + BuildInfo.VERSION_CODE);
            addLockedRow(bodyContainer, "Package", getPackageName());
            addLockedRow(bodyContainer, "Android", Build.VERSION.RELEASE + " • API " + Build.VERSION.SDK_INT);
            addLockedRow(bodyContainer, "Device", Build.MANUFACTURER + " " + Build.MODEL);
            addLockedRow(bodyContainer, "Forum host", activeHost);
            addLockedRow(bodyContainer, "Current route", currentRoute);
            addLockedRow(bodyContainer, "Theme", this.prefs.getString("app_theme", "system"));
            TextView label = label("Context is shown here for transparency. Only the items selected under Privacy & send are added to the email.", 10, R.color.hcf_muted, false);
            label.setPadding(0, dp(4), 0, 0);
            bodyContainer.addView(label);
            return bodyContainer;
        }

        private View privacyBody() {
            LinearLayout bodyContainer = bodyContainer();
            CheckBox check = check("Include forum identity", this.identity.loggedIn, this.identity.loggedIn);
            this.includeIdentity = check;
            bodyContainer.addView(check);
            CheckBox check2 = check("Include sanitized app/device diagnostics", false, true);
            this.includeDiagnostics = check2;
            bodyContainer.addView(check2);
            CheckBox check3 = check("Include current forum route", false, true);
            this.includeRoute = check3;
            bodyContainer.addView(check3);
            TextView label = label("Passwords, cookies, authentication tokens and private message contents are never included. The app opens your email client so you can review or cancel before sending.", 10, R.color.hcf_muted, false);
            label.setPadding(dp(2), dp(2), dp(2), dp(10));
            bodyContainer.addView(label);
            bodyContainer.addView(actionButton("Preview Report", R.drawable.fa_list, new View.OnClickListener() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda0
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    SupportContactActivity.this.m206x8f236721(view);
                }
            }));
            View actionButton = actionButton("Continue to Email", R.drawable.fa_envelope, new View.OnClickListener() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda1
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    SupportContactActivity.this.m207x49d8d62(view);
                }
            });
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) actionButton.getLayoutParams();
            layoutParams.bottomMargin = 0;
            actionButton.setLayoutParams(layoutParams);
            bodyContainer.addView(actionButton);
            TextView label2 = label("Support email: harleytg.hq@gmail.com  •  tap to copy", 10, R.color.hcf_cyan_bright, true);
            label2.setGravity(17);
            label2.setPadding(0, dp(10), 0, 0);
            label2.setClickable(true);
            label2.setOnClickListener(new View.OnClickListener() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda2
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    SupportContactActivity.this.m208x7a17b3a3(view);
                }
            });
            bodyContainer.addView(label2);
            return bodyContainer;
        }

        /* renamed from: lambda$privacyBody$1$com-harleytg-forum-dev-SupportContactActivity, reason: not valid java name */
        /* synthetic */ void m206x8f236721(View view) {
            previewReport();
        }

        /* renamed from: lambda$privacyBody$2$com-harleytg-forum-dev-SupportContactActivity, reason: not valid java name */
        /* synthetic */ void m207x49d8d62(View view) {
            composeEmail();
        }

        /* renamed from: lambda$privacyBody$3$com-harleytg-forum-dev-SupportContactActivity, reason: not valid java name */
        /* synthetic */ void m208x7a17b3a3(View view) {
            copySupportEmail();
        }

        private View supportPanel(String str, String str2, int i, View view, boolean z) {
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(1);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.bottomMargin = dp(12);
            linearLayout.setLayoutParams(layoutParams);
            final LinearLayout linearLayout2 = new LinearLayout(this);
            linearLayout2.setOrientation(0);
            linearLayout2.setGravity(16);
            linearLayout2.setClickable(true);
            linearLayout2.setFocusable(true);
            linearLayout2.setPadding(dp(15), dp(13), dp(12), dp(13));
            linearLayout2.setBackgroundResource(z ? R.drawable.settings_section_header_expanded : R.drawable.settings_section_header_collapsed);
            ImageView imageView = new ImageView(this);
            imageView.setImageResource(i);
            imageView.setColorFilter(getColor(R.color.hcf_cyan_bright));
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(dp(24), dp(24));
            layoutParams2.rightMargin = dp(11);
            linearLayout2.addView(imageView, layoutParams2);
            LinearLayout linearLayout3 = new LinearLayout(this);
            linearLayout3.setOrientation(1);
            linearLayout3.addView(label(str, 14, R.color.hcf_accent_text, true));
            linearLayout3.addView(label(str2, 10, R.color.hcf_muted, false));
            linearLayout2.addView(linearLayout3, new LinearLayout.LayoutParams(0, -2, 1.0f));
            final TextView label = label("›", 22, R.color.hcf_cyan_bright, false);
            label.setGravity(17);
            label.setRotation(z ? 90.0f : 0.0f);
            linearLayout2.addView(label, new LinearLayout.LayoutParams(dp(28), -1));
            linearLayout.addView(linearLayout2);
            final LinearLayout linearLayout4 = new LinearLayout(this);
            linearLayout4.setOrientation(1);
            linearLayout4.setBackgroundResource(R.drawable.settings_section_body);
            linearLayout4.setPadding(dp(14), dp(14), dp(14), dp(14));
            if (view != null) {
                linearLayout4.addView(view, new LinearLayout.LayoutParams(-1, -2));
            }
            linearLayout4.setVisibility(z ? 0 : 8);
            linearLayout4.setAlpha(z ? 1.0f : 0.0f);
            linearLayout4.setTranslationY(z ? 0.0f : -dp(6));
            linearLayout.addView(linearLayout4);
            final boolean[] zArr = {z};
            linearLayout2.setOnClickListener(new View.OnClickListener() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda4
                @Override // android.view.View.OnClickListener
                public final void onClick(View view2) {
                    SupportContactActivity.this.m209xae9de76a(zArr, label, linearLayout4, linearLayout2, view2);
                }
            });
            return linearLayout;
        }

        /* renamed from: lambda$supportPanel$5$com-harleytg-forum-dev-SupportContactActivity, reason: not valid java name */
        /* synthetic */ void m209xae9de76a(boolean[] zArr, TextView textView, final LinearLayout linearLayout, final LinearLayout linearLayout2, View view) {
            if (zArr[0]) {
                zArr[0] = false;
                textView.animate().rotation(0.0f).setDuration(150L).start();
                linearLayout.animate().alpha(0.0f).translationY(-dp(6)).setDuration(150L).withEndAction(new Runnable() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda5
                    @Override // java.lang.Runnable
                    public final void run() {
                        SupportContactActivity.lambda$supportPanel$4(linearLayout, linearLayout2);
                    }
                }).start();
                return;
            }
            zArr[0] = true;
            linearLayout2.setBackgroundResource(R.drawable.settings_section_header_expanded);
            linearLayout.setVisibility(0);
            linearLayout.setAlpha(0.0f);
            linearLayout.setTranslationY(-dp(6));
            textView.animate().rotation(90.0f).setDuration(170L).start();
            linearLayout.animate().alpha(1.0f).translationY(0.0f).setDuration(180L).start();
        }

        static /* synthetic */ void lambda$supportPanel$4(LinearLayout linearLayout, LinearLayout linearLayout2) {
            linearLayout.setVisibility(8);
            linearLayout2.setBackgroundResource(R.drawable.settings_section_header_collapsed);
        }

        private LinearLayout bodyContainer() {
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(1);
            linearLayout.setBackgroundColor(0);
            return linearLayout;
        }

        private void addLockedRow(LinearLayout linearLayout, String str, String str2) {
            LinearLayout linearLayout2 = new LinearLayout(this);
            linearLayout2.setOrientation(1);
            linearLayout2.setBackgroundResource(R.drawable.identity_card_background);
            linearLayout2.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.bottomMargin = dp(8);
            linearLayout2.setLayoutParams(layoutParams);
            TextView label = label(str, 9, R.color.hcf_meta, true);
            TextView label2 = label(str2, 12, R.color.hcf_text, false);
            label2.setPadding(0, dp(2), 0, 0);
            linearLayout2.addView(label);
            linearLayout2.addView(label2);
            linearLayout.addView(linearLayout2);
        }

        private void addField(LinearLayout linearLayout, String str, EditText editText) {
            linearLayout.addView(fieldLabel(str));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.bottomMargin = dp(11);
            linearLayout.addView(editText, layoutParams);
        }

        private TextView fieldLabel(String str) {
            TextView label = label(str, 10, R.color.hcf_meta, true);
            label.setPadding(dp(2), 0, 0, dp(4));
            return label;
        }

        private EditText input(String str, int i, boolean z) {
            EditText editText = new EditText(this);
            editText.setHint(str);
            editText.setHintTextColor(getColor(R.color.hcf_hint));
            editText.setTextColor(getColor(R.color.hcf_text));
            editText.setTextSize(13.0f);
            editText.setInputType(i);
            editText.setSingleLine(!z);
            if (!z) {
                editText.setImeOptions(5);
            }
            editText.setBackgroundResource(R.drawable.identity_card_background);
            editText.setPadding(dp(12), dp(10), dp(12), dp(10));
            return editText;
        }

        private CheckBox check(String str, boolean z, boolean z2) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(str);
            checkBox.setTextColor(getColor(R.color.hcf_text));
            checkBox.setTextSize(12.0f);
            checkBox.setChecked(z);
            checkBox.setEnabled(z2);
            checkBox.setPadding(0, dp(1), 0, dp(1));
            return checkBox;
        }

        private View actionButton(String str, int i, View.OnClickListener onClickListener) {
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(0);
            linearLayout.setGravity(17);
            linearLayout.setBackgroundResource(R.drawable.button_background);
            linearLayout.setClickable(true);
            linearLayout.setFocusable(true);
            linearLayout.setPadding(dp(16), 0, dp(16), 0);
            linearLayout.setContentDescription(str);
            ImageView imageView = new ImageView(this);
            imageView.setImageResource(i);
            imageView.setColorFilter(getColor(R.color.hcf_cyan_bright));
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            layoutParams.rightMargin = dp(10);
            linearLayout.addView(imageView, layoutParams);
            TextView label = label(str, 13, R.color.hcf_cyan_bright, true);
            label.setGravity(17);
            label.setIncludeFontPadding(false);
            linearLayout.addView(label, new LinearLayout.LayoutParams(-2, -2));
            linearLayout.setOnClickListener(onClickListener);
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, dp(52));
            layoutParams2.bottomMargin = dp(9);
            linearLayout.setLayoutParams(layoutParams2);
            return linearLayout;
        }

        private void previewReport() {
            final String buildReportBody = buildReportBody();
            if (buildReportBody == null) {
                return;
            }
            new AlertDialog.Builder(this).setTitle("Support report preview").setMessage(buildReportBody).setNegativeButton("Close", (DialogInterface.OnClickListener) null).setPositiveButton("Continue to Email", new DialogInterface.OnClickListener() { // from class: com.harleytg.forum.dev.SupportContactActivity$$ExternalSyntheticLambda6
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    SupportContactActivity.this.m205xc9453f78(buildReportBody, dialogInterface, i);
                }
            }).show();
        }

        /* renamed from: lambda$previewReport$6$com-harleytg-forum-dev-SupportContactActivity, reason: not valid java name */
        /* synthetic */ void m205xc9453f78(String str, DialogInterface dialogInterface, int i) {
            openEmail(str);
        }

        private void composeEmail() {
            String buildReportBody = buildReportBody();
            if (buildReportBody != null) {
                openEmail(buildReportBody);
            }
        }

        private String buildReportBody() {
            String clean;
            String clean2;
            String obj = this.categoryField.getSelectedItem() == null ? "App issue" : this.categoryField.getSelectedItem().toString();
            String clean3 = clean(this.subjectField.getText().toString());
            String clean4 = clean(this.messageField.getText().toString());
            String clean5 = clean(this.stepsField.getText().toString());
            String clean6 = clean(this.expectedField.getText().toString());
            if (clean4.isEmpty()) {
                Toast.makeText(this, "Please describe what happened.", 0).show();
                this.messageField.requestFocus();
                return null;
            }
            if (this.identity.loggedIn) {
                clean = nonEmpty(this.identity.displayName, this.identity.username);
            } else {
                EditText editText = this.guestNameField;
                clean = clean(editText == null ? "" : editText.getText().toString());
            }
            if (this.identity.loggedIn) {
                clean2 = this.identity.email;
            } else {
                EditText editText2 = this.guestReplyEmailField;
                clean2 = clean(editText2 == null ? "" : editText2.getText().toString());
            }
            StringBuilder sb = new StringBuilder("Hello Harley's Clan Forum Support,\n\nSupport type: ");
            sb.append(obj);
            sb.append("\nSubject: ");
            if (!clean3.isEmpty()) {
                obj = clean3;
            }
            sb.append(obj);
            sb.append("\n\n--- What happened ---\n");
            sb.append(clean4);
            sb.append("\n\n");
            if (!clean5.isEmpty()) {
                sb.append("--- Steps to reproduce ---\n");
                sb.append(clean5);
                sb.append("\n\n");
            }
            if (!clean6.isEmpty()) {
                sb.append("--- Expected behavior ---\n");
                sb.append(clean6);
                sb.append("\n\n");
            }
            sb.append("--- Contact ---\nName: ");
            if (clean.isEmpty()) {
                clean = "Not provided";
            }
            sb.append(clean);
            sb.append("\nReply email: ");
            if (clean2.isEmpty()) {
                clean2 = "Not provided";
            }
            sb.append(clean2);
            sb.append('\n');
            CheckBox checkBox = this.includeIdentity;
            if (checkBox != null && checkBox.isChecked() && this.identity.loggedIn) {
                sb.append("\n--- Forum Identity ---\nDisplay name: ");
                String str = "Not exposed";
                sb.append(nonEmpty(this.identity.displayName, "Not exposed"));
                sb.append("\nUsername: ");
                if (!this.identity.username.isEmpty()) {
                    str = "@" + this.identity.username;
                }
                sb.append(str);
                sb.append('\n');
                if (!this.identity.email.isEmpty()) {
                    sb.append("Forum email: ");
                    sb.append(this.identity.email);
                    sb.append(this.identity.emailConfirmed ? " (verified)" : "");
                    sb.append('\n');
                }
                sb.append("Role: ");
                sb.append(this.identity.identityMetaLabel());
                sb.append('\n');
            }
            CheckBox checkBox2 = this.includeDiagnostics;
            if (checkBox2 != null && checkBox2.isChecked()) {
                sb.append("\n--- Sanitized Diagnostics ---\nApp: Harley's Clan Forum v" + BuildInfo.VERSION + "\nVersion code: " + BuildInfo.VERSION_CODE + "\nPackage: ");
                sb.append(getPackageName());
                sb.append("\nAndroid: ");
                sb.append(Build.VERSION.RELEASE);
                sb.append(" (API ");
                sb.append(Build.VERSION.SDK_INT);
                sb.append(")\nDevice: ");
                sb.append(Build.MANUFACTURER);
                sb.append(' ');
                sb.append(Build.MODEL);
                sb.append("\nForum host: ");
                sb.append(activeHost());
                sb.append("\nTheme: ");
                sb.append(this.prefs.getString("app_theme", "system"));
                sb.append("\nNotifications: ");
                sb.append(NotificationHelper.status(this));
                sb.append('\n');
            }
            CheckBox checkBox3 = this.includeRoute;
            if (checkBox3 != null && checkBox3.isChecked()) {
                sb.append("\n--- Current Route ---\n");
                sb.append(currentRoute());
                sb.append('\n');
            }
            sb.append("\nPrivacy: passwords, cookies, tokens and private-message content are not included.\nSent from Harley's Clan Forum Contact Support v2.");
            return sb.toString();
        }

        private void openEmail(String str) {
            String obj = this.categoryField.getSelectedItem() == null ? "App issue" : this.categoryField.getSelectedItem().toString();
            String clean = clean(this.subjectField.getText().toString());
            if (clean.isEmpty()) {
                clean = obj;
            }
            String str2 = "HCF Support • " + obj + " • v1.0 • " + clean;
            try {
                String str3 = "mailto:harleytg.hq@gmail.com?subject=" + Uri.encode(str2) + "&body=" + Uri.encode(str);
                Intent intent = new Intent("android.intent.action.SENDTO");
                intent.setData(Uri.parse(str3));
                intent.putExtra("android.intent.extra.EMAIL", new String[]{SUPPORT_EMAIL});
                intent.putExtra("android.intent.extra.SUBJECT", str2);
                intent.putExtra("android.intent.extra.TEXT", str);
                startActivity(Intent.createChooser(intent, "Send support email"));
                AppLogger.info(this, "support_contact_v2", "mailto recipient=harleytg.hq@gmail.com");
            } catch (Throwable th) {
                Toast.makeText(this, "No email app is available. Email harleytg.hq@gmail.com", 1).show();
                AppLogger.error(this, "support_contact_v2", th.getClass().getSimpleName());
            }
        }

        private void copySupportEmail() {
            try {
                ClipboardManager clipboardManager = (ClipboardManager) getSystemService("clipboard");
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("HCF support email", SUPPORT_EMAIL));
                }
                Toast.makeText(this, "Support email copied.", 0).show();
            } catch (Throwable unused) {
                Toast.makeText(this, SUPPORT_EMAIL, 1).show();
            }
        }

        private String activeHost() {
            String string = this.prefs.getString("active_host", "forum.harleytg.com");
            return ForumUrlRouter.isForumHost(string) ? string : "forum.harleytg.com";
        }

        private String currentRoute() {
            String string = this.prefs.getString("last_recoverable_url", "");
            if (string == null || string.trim().isEmpty()) {
                return "https://" + activeHost() + "/";
            }
            return AppLogger.safeUrl(string);
        }

        private TextView label(String str, int i, int i2, boolean z) {
            TextView textView = new TextView(this);
            if (str == null) {
                str = "";
            }
            textView.setText(str);
            textView.setTextSize(i);
            textView.setTextColor(getColor(i2));
            if (z) {
                textView.setTypeface(null, 1);
            }
            return textView;
        }

        private static String clean(String str) {
            return str == null ? "" : str.trim();
        }

        private static String nonEmpty(String str, String str2) {
            return (str == null || str.trim().isEmpty()) ? str2 : str.trim();
        }

        private int dp(int i) {
            return Math.round(i * getResources().getDisplayMetrics().density);
        }
    }
}

// ---- Consolidated from HcfSettingsImportUi.java ----
/**
 * Adds HCF settings backup/import controls and account-scoped App Settings profiles.
 *
 * Every signed-in forum username keeps an independent set of user-facing App Settings.
 * Guest has a separate profile. Account/session data is never copied between profiles.
 */
final class HcfSettingsImportUi {
    private static final String SETUP_TAG = "hcf_setup_import_settings";
    private static final String SETTINGS_TAG = "hcf_settings_backup_transfer";
    private static final String REFRESH_PREF = "settings_transfer_refresh_ui";
    private static final WeakHashMap<Activity, Boolean> SETTINGS_OBSERVERS = new WeakHashMap<>();
    private static boolean registered;

    private HcfSettingsImportUi() {}

    /** Starts the account-scoped settings system as soon as the application process starts. */
    public static final class BootstrapProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            Context context = getContext();
            if (context == null) return true;
            Context appContext = context.getApplicationContext();
            UserSettingsProfiles.install(appContext);
            if (registered || !(appContext instanceof Application)) return true;
            registered = true;
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}

                @Override
                public void onActivityResumed(Activity activity) {
                    try {
                        boolean profileChanged = UserSettingsProfiles.ensureActiveProfile(activity);
                        if (profileChanged && activity instanceof HcfSubActivities.SettingsActivity) {
                            activity.recreate();
                            return;
                        }
                        if (activity instanceof HcfForum.SetupActivity) {
                            if (consumeRefresh(activity)) {
                                activity.recreate();
                                return;
                            }
                            injectSetupImport(activity);
                        } else if (activity instanceof HcfSubActivities.SettingsActivity) {
                            if (consumeRefresh(activity)) {
                                activity.recreate();
                                return;
                            }
                            installSettingsObserver(activity);
                            injectAdvancedSettingsTransfer(activity);
                        }
                    } catch (Throwable error) {
                        AppLogger.warn(activity, "settings_transfer_ui", error.getClass().getSimpleName());
                    }
                }

                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

                @Override
                public void onActivityDestroyed(Activity activity) {
                    synchronized (SETTINGS_OBSERVERS) {
                        SETTINGS_OBSERVERS.remove(activity);
                    }
                }
            });
            return true;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }

    public static final class TransferActivity extends Activity {
        private static final String EXTRA_MODE = "mode";
        private static final String EXTRA_FROM_SETUP = "from_setup";
        private static final String MODE_IMPORT = "import";
        private static final String MODE_EXPORT = "export";
        private static final int REQUEST_IMPORT = 2911;
        private static final int REQUEST_EXPORT = 2912;
        private String mode;

        static void startImport(Activity activity, boolean fromSetup) {
            Intent intent = new Intent(activity, TransferActivity.class);
            intent.putExtra(EXTRA_MODE, MODE_IMPORT);
            intent.putExtra(EXTRA_FROM_SETUP, fromSetup);
            activity.startActivity(intent);
        }

        static void startExport(Activity activity) {
            Intent intent = new Intent(activity, TransferActivity.class);
            intent.putExtra(EXTRA_MODE, MODE_EXPORT);
            activity.startActivity(intent);
        }

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            ThemeManager.apply(this);
            UserSettingsProfiles.ensureActiveProfile(this);
            mode = getIntent() == null ? MODE_IMPORT : getIntent().getStringExtra(EXTRA_MODE);
            if (!MODE_EXPORT.equals(mode)) mode = MODE_IMPORT;
            if (state == null) launchPicker();
        }

        private void launchPicker() {
            try {
                Intent intent;
                if (MODE_EXPORT.equals(mode)) {
                    intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, suggestedExportFileName());
                    startActivityForResult(intent, REQUEST_EXPORT);
                } else {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/json", "text/json", "text/plain", "application/octet-stream"
                    });
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_IMPORT);
                }
            } catch (Throwable error) {
                Toast.makeText(this, "No compatible file picker is available.", Toast.LENGTH_SHORT).show();
                AppLogger.warn(this, "settings_transfer_picker", error.getClass().getSimpleName());
                finish();
            }
        }

        private String suggestedExportFileName() {
            SharedPreferences prefs = getSharedPreferences(AppPrefs.FILE, 0);
            String username = readStringPreference(prefs, AppPrefs.IDENTITY_USERNAME);
            boolean signedIn = isSignedIn(prefs);
            String accountPart = signedIn && !username.isEmpty()
                    ? "@" + safeFilePart(username, "User")
                    : "Guest";

            String channel = BuildInfo.DEFAULT_UPDATE_CHANNEL == null
                    ? ""
                    : BuildInfo.DEFAULT_UPDATE_CHANNEL.trim();
            String channelPart = ("dev".equalsIgnoreCase(channel) || "beta".equalsIgnoreCase(channel))
                    ? "-Beta"
                    : "";
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());

            return "HCF-Settings-" + accountPart
                    + channelPart
                    + "-v" + shortVersionCode()
                    + "-" + stamp
                    + ".json";
        }

        private static boolean isSignedIn(SharedPreferences prefs) {
            try {
                if (prefs.getBoolean(AppPrefs.IDENTITY_LOGGED_IN, false)) return true;
            } catch (Throwable ignored) {}
            return !readStringPreference(prefs, AppPrefs.SESSION_USER_ID).isEmpty();
        }

        private static String readStringPreference(SharedPreferences prefs, String key) {
            try {
                String value = prefs.getString(key, "");
                return value == null ? "" : value.trim();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static String safeFilePart(String value, String fallback) {
            String part = value == null ? "" : value.trim();
            part = part.replaceAll("[^A-Za-z0-9._-]+", "-");
            part = part.replaceAll("^-+|-+$", "");
            return part.isEmpty() ? fallback : part;
        }

        private static long shortVersionCode() {
            long code = BuildInfo.VERSION_CODE;
            if (code >= 10000000L && code < 20000000L) {
                return 100L + (code - 10000000L);
            }
            return code;
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                finish();
                return;
            }
            Uri uri = data.getData();
            if (requestCode == REQUEST_IMPORT) {
                HcfSettingsTransfer.Result result = HcfSettingsTransfer.importFromUri(this, uri);
                Toast.makeText(this, result.summary(), Toast.LENGTH_LONG).show();
                if (result.ok) {
                    UserSettingsProfiles.captureActiveProfile(this);
                    try {
                        NotificationSyncScheduler.apply(this);
                    } catch (Throwable ignored) {}
                    getSharedPreferences(AppPrefs.FILE, 0).edit().putBoolean(REFRESH_PREF, true).apply();
                    AppLogger.info(this, "settings_transfer", "import_ok | " + result.summary());
                } else {
                    AppLogger.warn(this, "settings_transfer", "import_failed | " + result.message);
                }
            } else if (requestCode == REQUEST_EXPORT) {
                try {
                    UserSettingsProfiles.captureActiveProfile(this);
                    HcfSettingsTransfer.exportToUri(this, uri);
                    Toast.makeText(this, "HCF settings backup exported.", Toast.LENGTH_SHORT).show();
                    AppLogger.info(this, "settings_transfer", "export_ok | " + UserSettingsProfiles.displayLabel(this));
                } catch (Throwable error) {
                    Toast.makeText(this, "HCF could not export the settings backup.", Toast.LENGTH_LONG).show();
                    AppLogger.warn(this, "settings_transfer", "export_failed | " + error.getClass().getSimpleName());
                }
            }
            finish();
        }
    }

    private static void injectSetupImport(Activity activity) {
        ViewGroup content = findScrollContent(activity);
        if (content == null || findTagged(content, SETUP_TAG) != null) return;

        LinearLayout card = card(activity, SETUP_TAG);
        addTitle(activity, card, "Import Settings", "Restore App Settings into the current account profile.");
        TextView detail = text(activity,
                "Current settings profile: " + UserSettingsProfiles.displayLabel(activity)
                        + "\nChoose an HCF settings backup to restore the user-configurable settings for this profile.",
                11,
                activity.getColor(R.color.hcf_muted));
        detail.setPadding(0, 0, 0, dp(activity, 10));
        card.addView(detail);

        Button importButton = actionButton(activity, "Import Settings   ›");
        importButton.setOnClickListener(v -> TransferActivity.startImport(activity, true));
        card.addView(importButton, new LinearLayout.LayoutParams(-1, dp(activity, 44)));

        int index = Math.min(1, content.getChildCount());
        content.addView(card, index);
        AppLogger.info(activity, "settings_transfer_ui", "setup_control_added");
    }

    private static void installSettingsObserver(final Activity activity) {
        synchronized (SETTINGS_OBSERVERS) {
            if (SETTINGS_OBSERVERS.containsKey(activity)) return;
            SETTINGS_OBSERVERS.put(activity, Boolean.TRUE);
        }
        final View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    injectAdvancedSettingsTransfer(activity);
                } catch (Throwable error) {
                    AppLogger.warn(activity, "settings_transfer_advanced", error.getClass().getSimpleName());
                }
            }
        });
    }

    /** Adds Backup & Transfer only inside Advanced & About using the native settings panel style. */
    private static void injectAdvancedSettingsTransfer(Activity activity) {
        if (!"advanced".equals(readStringField(activity, "currentSettingsSection"))) return;
        ViewGroup content = readViewGroupField(activity, "settingsContent");
        if (content == null || findTagged(content, SETTINGS_TAG) != null) return;

        LinearLayout inner = nativeCard(activity);
        inner.setTag(SETTINGS_TAG + "_content");

        View nativeTitle = nativeSectionTitle(activity,
                "Backup & Transfer",
                "Per-account App Settings backup and restore");
        if (nativeTitle != null) inner.addView(nativeTitle);
        else addTitle(activity, inner, "Backup & Transfer", "Per-account App Settings backup and restore.");

        TextView profile = text(activity,
                "Settings profile: " + UserSettingsProfiles.displayLabel(activity),
                11,
                activity.getColor(R.color.hcf_accent_text));
        profile.setTypeface(null, 1);
        profile.setPadding(0, 0, 0, dp(activity, 9));
        inner.addView(profile);

        Button exportButton = nativeActionButton(activity, "Export Settings", v -> TransferActivity.startExport(activity));
        inner.addView(exportButton, new LinearLayout.LayoutParams(-1, dp(activity, 44)));

        Button importButton = nativeActionButton(activity, "Import Settings", v -> TransferActivity.startImport(activity, false));
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-1, dp(activity, 44));
        importLp.topMargin = dp(activity, 8);
        inner.addView(importButton, importLp);

        TextView note = text(activity,
                "Each signed-in forum username has its own App Settings profile. Guest has a separate profile. Switching accounts automatically saves the previous profile and restores the new one. Login/session data is never transferred.",
                10,
                activity.getColor(R.color.hcf_hint));
        note.setPadding(0, dp(activity, 9), 0, 0);
        inner.addView(note);

        View panel = nativeConnectedSettingsPanel(activity,
                "Backup & Transfer",
                "Per-user settings • " + UserSettingsProfiles.displayLabel(activity),
                inner,
                false);
        panel.setTag(SETTINGS_TAG);

        int aboutIndex = directChildContainingText(content, "About Harley's Clan Forum");
        if (aboutIndex < 0) aboutIndex = content.getChildCount();
        content.addView(panel, aboutIndex);
        AppLogger.info(activity, "settings_transfer_ui", "advanced_control_added | " + UserSettingsProfiles.displayLabel(activity));
    }

    /**
     * Keeps the existing global AppPrefs contract intact while making user-facing settings
     * account-scoped. This avoids changing every settings consumer in the app.
     */
    private static final class UserSettingsProfiles implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final String ACTIVE_PROFILE_KEY = "settings_profile_active";
        private static final String PROFILE_FILE_PREFIX = "hcf_user_settings_profile_";
        private static final String PROFILE_INITIALIZED = "__initialized";
        private static final String PROFILE_LABEL = "__label";

        private static final Set<String> BOOLEAN_KEYS = new LinkedHashSet<>(Arrays.asList(
                AppPrefs.AUTO_FAILOVER,
                AppPrefs.BACKGROUND_NOTIFICATION_SYNC,
                AppPrefs.COMPACT_HEADER,
                AppPrefs.EXTERNAL_LINKS,
                AppPrefs.LIVE_FORUM_UPDATES,
                AppPrefs.NOTIFICATIONS_ENABLED,
                AppPrefs.PERFORMANCE_MODE,
                AppPrefs.SHOW_BOTTOM_NAV,
                AppPrefs.SHOW_STARTUP_SCREEN,
                AppPrefs.WIDGET_FOLLOW_APP_THEME,
                HcfWidget.PREF_SHOW_CONNECTED_USERNAME,
                AppPrefs.SHOW_URL_BAR,
                AppPrefs.SILENCE_BACKGROUND_SERVICE_NOTIFICATION,
                AppPrefs.TELEMETRY_ASK_BEFORE_CRASH_REPORT,
                AppPrefs.TELEMETRY_AUTO_CRASH_REPORTS,
                AppPrefs.TELEMETRY_AUTO_ERROR_REPORTS,
                AppPrefs.TELEMETRY_ENABLED,
                AppPrefs.TELEMETRY_INCLUDE_DEVICE_MODEL,
                AppPrefs.TELEMETRY_INCLUDE_EMAIL,
                AppPrefs.TELEMETRY_INCLUDE_IDENTITY,
                AppPrefs.TELEMETRY_INCLUDE_ROUTE,
                AppPrefs.UPDATE_AUTO_CHECK,
                AppPrefs.UPDATE_AUTO_DOWNLOAD,
                AppPrefs.UPDATE_AUTO_INSTALL
        ));

        private static final Set<String> STRING_KEYS = new LinkedHashSet<>(Arrays.asList(
                AppPrefs.APP_THEME,
                AppPrefs.NATIVE_ACCENT,
                AppPrefs.PERFORMANCE_PROFILE,
                AppPrefs.TELEMETRY_LEVEL,
                AppPrefs.FIREBASE_CONFIG_URL
        ));

        private static UserSettingsProfiles instance;
        private final Context appContext;
        private final SharedPreferences global;
        private boolean switching;

        private UserSettingsProfiles(Context context) {
            appContext = context.getApplicationContext();
            global = appContext.getSharedPreferences(AppPrefs.FILE, 0);
        }

        static synchronized void install(Context context) {
            if (context == null) return;
            if (instance == null) {
                instance = new UserSettingsProfiles(context);
                instance.global.registerOnSharedPreferenceChangeListener(instance);
            }
            instance.ensureProfile();
        }

        static boolean ensureActiveProfile(Context context) {
            install(context);
            return instance != null && instance.ensureProfile();
        }

        static void captureActiveProfile(Context context) {
            install(context);
            if (instance != null) instance.captureActive();
        }

        static String displayLabel(Context context) {
            install(context);
            if (instance == null) return "Guest";
            String username = readString(instance.global, AppPrefs.IDENTITY_USERNAME);
            if (instance.signedIn() && !username.isEmpty()) return "@" + username;
            return "Guest";
        }

        private synchronized boolean ensureProfile() {
            if (switching) return false;
            String target = desiredProfileKey();
            if (target.isEmpty()) return false; // Identity is currently syncing; keep the existing profile.
            String active = readString(global, ACTIVE_PROFILE_KEY);

            if (active.isEmpty()) {
                switching = true;
                try {
                    SharedPreferences targetPrefs = profilePrefs(target);
                    if (targetPrefs.getBoolean(PROFILE_INITIALIZED, false)) {
                        loadProfile(target);
                    } else {
                        saveGlobalToProfile(target);
                    }
                    global.edit().putString(ACTIVE_PROFILE_KEY, target).commit();
                    AppLogger.info(appContext, "settings_profile_init", displayLabelNoInstall());
                } finally {
                    switching = false;
                }
                return false;
            }

            if (active.equals(target)) return false;

            switching = true;
            try {
                saveGlobalToProfile(active);
                SharedPreferences targetPrefs = profilePrefs(target);
                if (targetPrefs.getBoolean(PROFILE_INITIALIZED, false)) {
                    loadProfile(target);
                } else {
                    clearGlobalUserSettings();
                    targetPrefs.edit()
                            .putBoolean(PROFILE_INITIALIZED, true)
                            .putString(PROFILE_LABEL, targetLabel())
                            .commit();
                }
                global.edit().putString(ACTIVE_PROFILE_KEY, target).commit();
                try {
                    NotificationSyncScheduler.apply(appContext);
                } catch (Throwable ignored) {}
                AppLogger.info(appContext, "settings_profile_switch", active + " -> " + target);
                return true;
            } finally {
                switching = false;
            }
        }

        private synchronized void captureActive() {
            if (switching) return;
            String active = readString(global, ACTIVE_PROFILE_KEY);
            if (active.isEmpty()) {
                ensureProfile();
                active = readString(global, ACTIVE_PROFILE_KEY);
            }
            if (!active.isEmpty()) saveGlobalToProfile(active);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (switching || key == null) return;
            if (AppPrefs.IDENTITY_USERNAME.equals(key)
                    || AppPrefs.IDENTITY_LOGGED_IN.equals(key)
                    || AppPrefs.SESSION_USER_ID.equals(key)) {
                ensureProfile();
                return;
            }
            if (!isUserSettingKey(key)) return;
            String active = readString(global, ACTIVE_PROFILE_KEY);
            if (active.isEmpty()) {
                ensureProfile();
                active = readString(global, ACTIVE_PROFILE_KEY);
            }
            if (!active.isEmpty()) saveSingleSetting(active, key);
        }

        private String desiredProfileKey() {
            boolean signedIn = signedIn();
            String username = readString(global, AppPrefs.IDENTITY_USERNAME);
            if (signedIn) {
                if (username.isEmpty()) return "";
                return "user:" + username.toLowerCase(Locale.US);
            }
            return "guest";
        }

        private boolean signedIn() {
            try {
                if (global.getBoolean(AppPrefs.IDENTITY_LOGGED_IN, false)) return true;
            } catch (Throwable ignored) {}
            return !readString(global, AppPrefs.SESSION_USER_ID).isEmpty();
        }

        private String targetLabel() {
            String username = readString(global, AppPrefs.IDENTITY_USERNAME);
            return signedIn() && !username.isEmpty() ? "@" + username : "Guest";
        }

        private String displayLabelNoInstall() {
            return targetLabel();
        }

        private SharedPreferences profilePrefs(String profileKey) {
            String safe = profileKey.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "_");
            if (safe.isEmpty()) safe = "profile";
            safe = safe + "_" + Integer.toHexString(profileKey.hashCode());
            return appContext.getSharedPreferences(PROFILE_FILE_PREFIX + safe, 0);
        }

        private void saveGlobalToProfile(String profileKey) {
            if (profileKey == null || profileKey.isEmpty()) return;
            Map<String, ?> all = global.getAll();
            SharedPreferences.Editor out = profilePrefs(profileKey).edit().clear();
            out.putBoolean(PROFILE_INITIALIZED, true);
            out.putString(PROFILE_LABEL, "guest".equals(profileKey) ? "Guest" : profileKey.substring(profileKey.indexOf(':') + 1));
            for (String key : BOOLEAN_KEYS) {
                Object value = all.get(key);
                if (value instanceof Boolean) out.putBoolean(key, ((Boolean) value).booleanValue());
            }
            for (String key : STRING_KEYS) {
                Object value = all.get(key);
                if (value instanceof String) out.putString(key, (String) value);
            }
            out.commit();
        }

        private void saveSingleSetting(String profileKey, String key) {
            Object value = global.getAll().get(key);
            SharedPreferences.Editor out = profilePrefs(profileKey).edit();
            out.putBoolean(PROFILE_INITIALIZED, true);
            if (value instanceof Boolean) out.putBoolean(key, ((Boolean) value).booleanValue());
            else if (value instanceof String) out.putString(key, (String) value);
            else out.remove(key);
            out.apply();
        }

        private void loadProfile(String profileKey) {
            SharedPreferences source = profilePrefs(profileKey);
            Map<String, ?> saved = source.getAll();
            SharedPreferences.Editor edit = global.edit();
            for (String key : BOOLEAN_KEYS) edit.remove(key);
            for (String key : STRING_KEYS) edit.remove(key);
            for (String key : BOOLEAN_KEYS) {
                Object value = saved.get(key);
                if (value instanceof Boolean) edit.putBoolean(key, ((Boolean) value).booleanValue());
            }
            for (String key : STRING_KEYS) {
                Object value = saved.get(key);
                if (value instanceof String) edit.putString(key, (String) value);
            }
            edit.commit();
        }

        private void clearGlobalUserSettings() {
            SharedPreferences.Editor edit = global.edit();
            for (String key : BOOLEAN_KEYS) edit.remove(key);
            for (String key : STRING_KEYS) edit.remove(key);
            edit.commit();
        }

        private static boolean isUserSettingKey(String key) {
            return BOOLEAN_KEYS.contains(key) || STRING_KEYS.contains(key);
        }

        private static String readString(SharedPreferences prefs, String key) {
            try {
                String value = prefs.getString(key, "");
                return value == null ? "" : value.trim();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private static String readStringField(Activity activity, String fieldName) {
        try {
            Field field = activity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static ViewGroup readViewGroupField(Activity activity, String fieldName) {
        try {
            Field field = activity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static LinearLayout nativeCard(Activity activity) {
        try {
            Method method = activity.getClass().getDeclaredMethod("card");
            method.setAccessible(true);
            Object value = method.invoke(activity);
            if (value instanceof LinearLayout) return (LinearLayout) value;
        } catch (Throwable ignored) {}
        return card(activity, SETTINGS_TAG + "_fallback");
    }

    private static View nativeSectionTitle(Activity activity, String title, String subtitle) {
        try {
            Method method = activity.getClass().getDeclaredMethod("sectionTitle", String.class, String.class);
            method.setAccessible(true);
            Object value = method.invoke(activity, title, subtitle);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Button nativeActionButton(Activity activity, String title, View.OnClickListener listener) {
        try {
            Method method = activity.getClass().getDeclaredMethod("actionButton", String.class, View.OnClickListener.class);
            method.setAccessible(true);
            Object value = method.invoke(activity, title, listener);
            if (value instanceof Button) return (Button) value;
        } catch (Throwable ignored) {}
        Button fallback = actionButton(activity, title + "   ›");
        fallback.setOnClickListener(listener);
        return fallback;
    }

    private static View nativeConnectedSettingsPanel(Activity activity, String title, String subtitle, View inner, boolean expanded) {
        try {
            Method method = activity.getClass().getDeclaredMethod(
                    "connectedSettingsPanel", String.class, String.class, View.class, boolean.class);
            method.setAccessible(true);
            Object value = method.invoke(activity, title, subtitle, inner, expanded);
            if (value instanceof View) return (View) value;
        } catch (Throwable ignored) {}
        return inner;
    }

    private static int directChildContainingText(ViewGroup parent, String expected) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (containsText(parent.getChildAt(i), expected)) return i;
        }
        return -1;
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && expected.contentEquals(value)) return true;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private static boolean consumeRefresh(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(AppPrefs.FILE, 0);
        if (!prefs.getBoolean(REFRESH_PREF, false)) return false;
        prefs.edit().remove(REFRESH_PREF).apply();
        return true;
    }

    private static ViewGroup findScrollContent(Activity activity) {
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        ScrollView scroll = findFirstScroll(root);
        if (scroll == null || scroll.getChildCount() == 0) return null;
        View child = scroll.getChildAt(0);
        return child instanceof ViewGroup ? (ViewGroup) child : null;
    }

    private static ScrollView findFirstScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findFirstScroll(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static View findTagged(View view, String tag) {
        if (view == null) return null;
        if (tag.equals(view.getTag())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTagged(group.getChildAt(i), tag);
            if (found != null) return found;
        }
        return null;
    }

    private static LinearLayout card(Activity activity, String tag) {
        LinearLayout card = new LinearLayout(activity);
        card.setTag(tag);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_background);
        card.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(activity, 12);
        card.setLayoutParams(lp);
        return card;
    }

    private static void addTitle(Activity activity, LinearLayout card, String title, String subtitle) {
        TextView titleView = text(activity, title, 15, activity.getColor(R.color.hcf_cyan_bright));
        titleView.setTypeface(null, 1);
        card.addView(titleView);
        TextView subtitleView = text(activity, subtitle, 11, activity.getColor(R.color.hcf_muted));
        subtitleView.setPadding(0, dp(activity, 2), 0, dp(activity, 10));
        card.addView(subtitleView);
    }

    private static Button actionButton(Activity activity, String label) {
        Button button = new Button(activity);
        UiButtons.normalizeText(button);
        button.setText(label);
        button.setTextSize(12.0f);
        button.setTextColor(activity.getColor(R.color.hcf_cyan_bright));
        button.setBackgroundResource(R.drawable.error_secondary_button_background);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        return button;
    }

    private static TextView text(Activity activity, String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
