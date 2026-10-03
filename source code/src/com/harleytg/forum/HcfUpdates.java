package com.harleytg.forum.dev;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;


// ---- Consolidated from HcfUpdateEngine.java ----
public final class HcfUpdates {
    private HcfUpdates() {}

    // ---- UpdateCheckJobService.java ----
    /* loaded from: classes.dex */
    public static final class UpdateCheckJobService extends JobService {
        @Override // android.app.job.JobService
        public boolean onStopJob(JobParameters jobParameters) {
            return true;
        }

        /* renamed from: lambda$onStartJob$0$com-harleytg-forum-dev-UpdateCheckJobService, reason: not valid java name */
        /* synthetic */ void m211lambda$onStartJob$0$comharleytgforumdevUpdateCheckJobService(JobParameters jobParameters, PlayStoreUpdateChecker.Result result, boolean z, String str) {
            jobFinished(jobParameters, false);
        }

        @Override // android.app.job.JobService
        public boolean onStartJob(final JobParameters jobParameters) {
            UpdateAutomation.maybeCheck(this, true, new UpdateAutomation.Listener() { // from class: com.harleytg.forum.dev.UpdateCheckJobService$$ExternalSyntheticLambda0
                @Override // com.harleytg.forum.dev.UpdateAutomation.Listener
                public final void onFinished(PlayStoreUpdateChecker.Result result, boolean z, String str) {
                    UpdateCheckJobService.this.m211lambda$onStartJob$0$comharleytgforumdevUpdateCheckJobService(jobParameters, result, z, str);
                }
            });
            return true;
        }
    }

}

// ---- PlayStoreUpdateChecker.java ----
final class PlayStoreUpdateChecker {
    interface Callback { void onResult(Result result); }

    static final class Result {
        final boolean available;
        final long availableVersionCode;
        final boolean installedFromPlay;
        final boolean querySucceeded;
        final String message;

        Result(boolean available, long availableVersionCode, boolean installedFromPlay,
               boolean querySucceeded, String message) {
            this.available = available;
            this.availableVersionCode = availableVersionCode;
            this.installedFromPlay = installedFromPlay;
            this.querySucceeded = querySucceeded;
            this.message = message == null ? "" : message;
        }
    }

    static void check(Context context, final Callback callback) {
        if (context == null) {
            deliver(callback, new Result(false, -1L, false, false, "Context unavailable."));
            return;
        }
        final Context app = context.getApplicationContext();

        final boolean fromPlay = installedFromPlay(app);
        if (!fromPlay) {
            deliver(callback, new Result(false, -1L, false, false,
                    "This install was not installed by Google Play. Install the Play test/release build from Google Play to query update availability."));
            return;
        }

        try {
            queryWithPlayCore(app, callback);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            deliver(callback, new Result(false, -1L, true, false,
                    "Google Play update status is unavailable • " + cause.getClass().getSimpleName()));
        }
    }

    private static void queryWithPlayCore(final Context app, final Callback callback) throws Exception {
        final Class<?> factoryClass = Class.forName(
                "com.google.android.play.core.appupdate.AppUpdateManagerFactory");
        final Class<?> infoClass = Class.forName(
                "com.google.android.play.core.appupdate.AppUpdateInfo");
        final Class<?> availabilityClass = Class.forName(
                "com.google.android.play.core.install.model.UpdateAvailability");
        final Class<?> successClass = Class.forName(
                "com.google.android.gms.tasks.OnSuccessListener");
        final Class<?> failureClass = Class.forName(
                "com.google.android.gms.tasks.OnFailureListener");

        Object manager = factoryClass.getMethod("create", Context.class).invoke(null, app);
        Object task = manager.getClass().getMethod("getAppUpdateInfo").invoke(manager);

        Object success = java.lang.reflect.Proxy.newProxyInstance(
                successClass.getClassLoader(),
                new Class<?>[]{successClass},
                (proxy, method, args) -> {
                    if ("onSuccess".equals(method.getName()) && args != null && args.length > 0 && args[0] != null) {
                        try {
                            Object info = args[0];
                            int state = ((Number) infoClass.getMethod("updateAvailability").invoke(info)).intValue();
                            long availableCode = ((Number) infoClass.getMethod("availableVersionCode").invoke(info)).longValue();
                            int updateAvailable = availabilityClass.getField("UPDATE_AVAILABLE").getInt(null);
                            int triggered = availabilityClass.getField("DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS").getInt(null);
                            boolean available = state == updateAvailable || state == triggered;
                            deliver(callback, new Result(
                                    available,
                                    availableCode,
                                    true,
                                    true,
                                    available ? "Update available in Google Play." : "Up to date in Google Play."));
                        } catch (Throwable inner) {
                            Throwable cause = inner.getCause() == null ? inner : inner.getCause();
                            deliver(callback, new Result(false, -1L, true, false,
                                    "Google Play update status is unavailable • " + cause.getClass().getSimpleName()));
                        }
                    }
                    return null;
                });

        Object failure = java.lang.reflect.Proxy.newProxyInstance(
                failureClass.getClassLoader(),
                new Class<?>[]{failureClass},
                (proxy, method, args) -> {
                    if ("onFailure".equals(method.getName())) {
                        Throwable error = args != null && args.length > 0 && args[0] instanceof Throwable
                                ? (Throwable) args[0] : null;
                        String detail = error == null ? "Unknown Play Core error"
                                : error.getClass().getSimpleName()
                                + (error.getMessage() == null || error.getMessage().trim().isEmpty()
                                ? "" : ": " + error.getMessage().trim());
                        deliver(callback, new Result(false, -1L, true, false,
                                "Google Play update status is temporarily unavailable • " + detail));
                    }
                    return null;
                });

        task.getClass().getMethod("addOnSuccessListener", successClass).invoke(task, success);
        task.getClass().getMethod("addOnFailureListener", failureClass).invoke(task, failure);
    }

    static boolean installedFromPlay(Context context) {
        if (context == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            String installer;
            if (Build.VERSION.SDK_INT >= 30) {
                installer = pm.getInstallSourceInfo(context.getPackageName()).getInstallingPackageName();
            } else {
                installer = pm.getInstallerPackageName(context.getPackageName());
            }
            return "com.android.vending".equals(installer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void deliver(final Callback callback, final Result result) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
    }

    private PlayStoreUpdateChecker() {}
}


// ---- UpdateScheduler.java ----
/* loaded from: classes.dex */
final class UpdateScheduler {
    private static final int JOB_ID = 41072;
    private static final long PERIOD_MS = 21600000;

    static void apply(Context context) {
        if (context == null) {
            return;
        }
        if (!context.getSharedPreferences("hcf_app", 0).getBoolean("update_auto_check", true)) {
            cancel(context);
        } else {
            schedule(context);
        }
    }

    static void schedule(Context context) {
        try {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService("jobscheduler");
            if (jobScheduler == null) {
                return;
            }
            AppLogger.info(context, "update_schedule", jobScheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, (Class<?>) HcfUpdates.UpdateCheckJobService.class)).setRequiredNetworkType(1).setPeriodic(PERIOD_MS).setPersisted(true).build()) == 1 ? "scheduled_6h" : "failed");
        } catch (Throwable th) {
            AppLogger.error(context, "update_schedule", th.getClass().getSimpleName() + ": " + String.valueOf(th.getMessage()));
        }
    }

    static void cancel(Context context) {
        try {
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService("jobscheduler");
            if (jobScheduler != null) {
                jobScheduler.cancel(JOB_ID);
            }
            AppLogger.info(context, "update_schedule", "cancelled");
        } catch (Throwable th) {
            AppLogger.error(context, "update_schedule_cancel", th.getClass().getSimpleName());
        }
    }

    private UpdateScheduler() {
    }
}


// ---- UpdateAutomation.java ----
/* loaded from: classes.dex */
final class UpdateAutomation {
    private static final long FOREGROUND_MIN_INTERVAL_MS = 1800000;

    interface Listener {
        void onFinished(PlayStoreUpdateChecker.Result result, boolean z, String str);
    }

    static void maybeCheck(Context context, boolean z, final Listener listener) {
        if (context == null) return;
        final Context applicationContext = context.getApplicationContext();
        final SharedPreferences sharedPreferences = applicationContext.getSharedPreferences("hcf_app", 0);

        if (!z && !sharedPreferences.getBoolean("update_auto_check", true)) {
            finish(listener, null, false, "Automatic update checks are off.");
            return;
        }

        long now = System.currentTimeMillis();
        long lastCheck = sharedPreferences.getLong("update_last_check", 0L);
        if (!z && lastCheck > 0 && now - lastCheck < FOREGROUND_MIN_INTERVAL_MS) {
            finish(listener, null, false, null);
            return;
        }

        PlayStoreUpdateChecker.check(applicationContext, result -> {
            sharedPreferences.edit().putLong("update_last_check", System.currentTimeMillis()).apply();
            if (result.querySucceeded && result.available) {
                long lastNotified = sharedPreferences.getLong("play_store_last_notified_version", -1L);
                if (result.availableVersionCode <= 0L || result.availableVersionCode != lastNotified) {
                    NotificationHelper.postPlayStoreUpdateAvailable(applicationContext, result.availableVersionCode);
                    sharedPreferences.edit()
                            .putLong("play_store_last_notified_version", result.availableVersionCode)
                            .apply();
                }
            }
            AppLogger.info(applicationContext, "play_store_update_check",
                    "available=" + result.available
                            + " | availableVersionCode=" + result.availableVersionCode
                            + " | installedFromPlay=" + result.installedFromPlay
                            + " | success=" + result.querySucceeded);
            UpdateAutomation.finish(listener, result, result.available,
                    result.querySucceeded ? null : result.message);
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void finish(Listener listener, PlayStoreUpdateChecker.Result result, boolean z, String str) {
        if (listener != null) {
            listener.onFinished(result, z, str);
        }
    }

    private UpdateAutomation() {
    }
}


// ---- ReleaseNotes.java ----
/* loaded from: classes.dex */
final class ReleaseNotes {
    static final String NOTES = "Harley's Clan Forum • v" + BuildInfo.VERSION + " (" + BuildInfo.VERSION_CODE + ") • " + BuildInfo.BUILD_TAG + "\n"
            + "• Build identity now shows version, versionCode, and the Development Build / Beta tag in App Settings and the forum drawer.\n"
            + "• What's New now uses the live BuildInfo version/build and build tag instead of stale fixed version text.\n"
            + "• Android 14 foreground-service crash fix is retained: network and screen callbacks sync the already-running notification service instead of self-restarting it.\n"
            + "• Safe Mode, crash recovery, diagnostics, and sanitized crash reporting remain available.\n"
            + "• Home-screen widget controls include theme following, compact mode, unread count, last-updated status, refresh behavior, and tap actions.\n"
            + "• Theme selection includes Forum Auto, Phone Auto, Light, Dark, and AMOLED.\n"
            + "• Developer notification/runtime tools and secure same-version APK hash updates remain enabled for the Dev/Beta channel.\n\n"
            + "Stable remains separate; this feature set is scoped to com.harleytg.forum.dev.";
    static final String SUMMARY = "v" + BuildInfo.VERSION + " (" + BuildInfo.VERSION_CODE + ") • " + BuildInfo.BUILD_TAG;
    private static final String NOTES_REVISION = "build-identity-v2";

    static void seedForFreshInstall(SharedPreferences sharedPreferences) {
        markSeen(sharedPreferences);
    }

    private static String releaseId() {
        return BuildInfo.VERSION + "-" + BuildInfo.VERSION_CODE + "-" + NOTES_REVISION;
    }

    static boolean shouldNotify(SharedPreferences sharedPreferences) {
        if (sharedPreferences == null) {
            return false;
        }
        return !releaseId().equals(sharedPreferences.getString("last_seen_whats_new_version", ""));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void markSeen(SharedPreferences sharedPreferences) {
        if (sharedPreferences == null) {
            return;
        }
        sharedPreferences.edit().putString("last_seen_whats_new_version", releaseId()).apply();
    }

    static void show(Activity activity, SharedPreferences sharedPreferences, boolean z) {
        showCustom(activity, sharedPreferences, z);
    }

    static void showCustom(Activity activity, final SharedPreferences sharedPreferences, boolean z) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(1);
        dialog.setCancelable(true);
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundResource(R.drawable.card_background);
        int dp = dp(activity, 18);
        linearLayout.setPadding(dp, dp, dp, dp);
        LinearLayout linearLayout2 = new LinearLayout(activity);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        ImageView imageView = new ImageView(activity);
        imageView.setImageResource(R.drawable.htg_app_logo);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        linearLayout2.addView(imageView, new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 58)));
        LinearLayout linearLayout3 = new LinearLayout(activity);
        linearLayout3.setOrientation(1);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams.leftMargin = dp(activity, 12);
        linearLayout3.addView(label(activity, "Harley's Clan Forum • Release Notes", 9, R.color.hcf_meta, true));
        TextView label = label(activity, "What's New", 24, R.color.hcf_text, true);
        label.setPadding(0, dp(activity, 2), 0, 0);
        linearLayout3.addView(label);
        TextView label2 = label(activity, "v" + BuildInfo.VERSION + "  •  " + BuildInfo.VERSION_CODE + "  •  " + BuildInfo.BUILD_TAG, 11, R.color.hcf_cyan_bright, true);
        label2.setPadding(0, dp(activity, 4), 0, 0);
        linearLayout3.addView(label2);
        linearLayout2.addView(linearLayout3, layoutParams);
        linearLayout.addView(linearLayout2);
        TextView label3 = label(activity, SUMMARY, 13, R.color.hcf_text, false);
        label3.setPadding(0, dp(activity, 14), 0, dp(activity, 10));
        linearLayout.addView(label3);
        View view = new View(activity);
        view.setBackgroundColor(activity.getColor(R.color.hcf_divider));
        linearLayout.addView(view, new LinearLayout.LayoutParams(-1, dp(activity, 1)));
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(1);
        LinearLayout linearLayout4 = new LinearLayout(activity);
        linearLayout4.setOrientation(1);
        linearLayout4.setPadding(0, dp(activity, 10), 0, dp(activity, 6));
        addSection(activity, linearLayout4, "Current Dev build", "Harley's Clan Forum v" + BuildInfo.VERSION + " (versionCode " + BuildInfo.VERSION_CODE + ") • " + BuildInfo.BUILD_TAG + ".");
        addSection(activity, linearLayout4, "Updated • Build identity", "The forum drawer and App Settings now show the full version, Android versionCode, and Development Build / Beta tag instead of only the channel label.");
        addSection(activity, linearLayout4, "Updated • What's New", "The banner, release-notes header, summary and accessibility text now read the live BuildInfo version/build so old fixed v1.0 text cannot drift out of date.");
        addSection(activity, linearLayout4, "Updated • Play-compliant notification sync", "Background alerts use scheduled jobs and one-shot sync requests without a special-use foreground service.");
        addSection(activity, linearLayout4, "Recovery • Safe Mode and crash tools", "Safe Mode, crash recovery, diagnostics and sanitized crash reporting remain available to recover from startup or runtime failures.");
        addSection(activity, linearLayout4, "Updated • Home-screen Widget", "Widget settings cover app-theme following, compact mode, unread count, last-updated status, refresh behavior and configurable tap actions.");
        addSection(activity, linearLayout4, "Updated • Google Play delivery", "Update checks can report newer builds, while installation and signing delivery are handled by Google Play.");
        addSection(activity, linearLayout4, "Updated • Appearance and performance", "Forum Auto, Phone Auto, Light, Dark and AMOLED themes remain available together with the app's performance profiles and runtime tools.");
        scrollView.addView(linearLayout4, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        layoutParams2.topMargin = dp(activity, 4);
        linearLayout.addView(scrollView, layoutParams2);
        Button button = new Button(activity);
        UiButtons.normalizeText(button);
        button.setText("Done");
        button.setTextColor(activity.getColor(R.color.hcf_cyan_bright));
        button.setTextSize(14.0f);
        button.setBackgroundResource(R.drawable.button_background);
        button.setGravity(17);
        button.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        button.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, dp(activity, 50));
        layoutParams3.topMargin = dp(activity, 10);
        linearLayout.addView(button, layoutParams3);
        button.setOnClickListener(new View.OnClickListener() { // from class: com.harleytg.forum.dev.ReleaseNotes$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                dialog.dismiss();
            }
        });
        dialog.setContentView(linearLayout);
        if (z && sharedPreferences != null) {
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.harleytg.forum.dev.ReleaseNotes$$ExternalSyntheticLambda1
                @Override // android.content.DialogInterface.OnDismissListener
                public final void onDismiss(DialogInterface dialogInterface) {
                    ReleaseNotes.markSeen(sharedPreferences);
                }
            });
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.addFlags(2);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.62f;
            window.setAttributes(attributes);
            window.setLayout(Math.max(dp(activity, 280), activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 24)), Math.max(dp(activity, 420), Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.84f)));
            window.setGravity(17);
        }
        AppLogger.info(activity, "whats_new_open", "1.0 custom_ui");
    }

    private static void addSection(Activity activity, LinearLayout linearLayout, String str, String str2) {
        LinearLayout linearLayout2 = new LinearLayout(activity);
        linearLayout2.setOrientation(1);
        linearLayout2.setBackgroundResource(R.drawable.identity_card_background);
        int dp = dp(activity, 12);
        linearLayout2.setPadding(dp, dp, dp, dp);
        linearLayout2.addView(label(activity, str, 11, R.color.hcf_cyan_bright, true));
        TextView label = label(activity, str2, 12, R.color.hcf_muted, false);
        label.setLineSpacing(0.0f, 1.08f);
        label.setPadding(0, dp(activity, 6), 0, 0);
        linearLayout2.addView(label);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.bottomMargin = dp(activity, 9);
        linearLayout.addView(linearLayout2, layoutParams);
    }

    private static TextView label(Activity activity, String str, int i, int i2, boolean z) {
        TextView textView = new TextView(activity);
        textView.setText(str);
        textView.setTextSize(i);
        textView.setTextColor(activity.getColor(i2));
        if (z) {
            textView.setTypeface(null, 1);
        }
        return textView;
    }

    private static int dp(Activity activity, int i) {
        return Math.round(i * activity.getResources().getDisplayMetrics().density);
    }

    private ReleaseNotes() {
    }
}
