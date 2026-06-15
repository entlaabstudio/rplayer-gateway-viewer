package com.technotramp.unexpectedtracks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps Android aware that a user-started album export is actively running.
 */
public class ExportForegroundService extends Service {
    public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".action.START_EXPORT_FOREGROUND";
    public static final String ACTION_UPDATE = BuildConfig.APPLICATION_ID + ".action.UPDATE_EXPORT_FOREGROUND";
    public static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".action.STOP_EXPORT_FOREGROUND";
    public static final String EXTRA_TITLE = BuildConfig.APPLICATION_ID + ".extra.EXPORT_TITLE";
    public static final String EXTRA_COMPLETED_ENTRIES = BuildConfig.APPLICATION_ID + ".extra.EXPORT_COMPLETED_ENTRIES";
    public static final String EXTRA_EXPECTED_ENTRIES = BuildConfig.APPLICATION_ID + ".extra.EXPORT_EXPECTED_ENTRIES";
    public static final String EXTRA_CURRENT_ENTRY = BuildConfig.APPLICATION_ID + ".extra.EXPORT_CURRENT_ENTRY";

    private static final String LOG_TAG = "RPlayerExport";
    private static final int EXPORT_NOTIFICATION_ID = 3001;
    private static final String EXPORT_NOTIFICATION_CHANNEL_ID = "album_export";
    private static final String DEFAULT_EXPORT_TITLE = "Exporting album";

    private String exportTitle = DEFAULT_EXPORT_TITLE;
    private int completedEntries;
    private int expectedEntries;
    private String currentEntry = "Preparing export";

    /**
     * Starts, updates, or stops the foreground export service.
     *
     * @param intent Intent containing the requested export service action.
     * @param flags Start flags supplied by Android.
     * @param startId Unique start request id supplied by Android.
     * @return Service restart mode used by Android if the process is later recreated.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            Log.i(LOG_TAG, "Export foreground service stopped.");
            return START_NOT_STICKY;
        }

        applyIntentState(intent);
        ensureNotificationChannel();
        startForeground(EXPORT_NOTIFICATION_ID, createNotification());
        Log.i(LOG_TAG, "Export foreground service active: " + exportTitle);
        return START_STICKY;
    }

    /**
     * Records foreground service shutdown for diagnostics.
     */
    @Override
    public void onDestroy() {
        stopForeground(true);
        Log.i(LOG_TAG, "Export foreground service destroyed.");
        super.onDestroy();
    }

    /**
     * Foreground services are controlled only through start commands here.
     *
     * @param intent Bind request supplied by Android.
     * @return Always null because this service does not expose a binder API.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Applies export notification state from a start or update intent.
     *
     * @param intent Intent carrying export title and progress values.
     */
    private void applyIntentState(Intent intent) {
        if (intent == null) {
            return;
        }

        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title != null && !title.trim().isEmpty()) {
            exportTitle = title.trim();
        }

        completedEntries = Math.max(0, intent.getIntExtra(EXTRA_COMPLETED_ENTRIES, completedEntries));
        expectedEntries = Math.max(0, intent.getIntExtra(EXTRA_EXPECTED_ENTRIES, expectedEntries));

        String entry = intent.getStringExtra(EXTRA_CURRENT_ENTRY);
        if (entry != null && !entry.trim().isEmpty()) {
            currentEntry = entry.trim();
        }
    }

    /**
     * Builds the current foreground export notification.
     *
     * @return Notification shown while the native export is active.
     */
    private Notification createNotification() {
        Notification.Builder builder = notificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(exportTitle)
            .setContentText(notificationText())
            .setContentIntent(createContentIntent())
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true);

        if (expectedEntries > 0) {
            builder.setProgress(expectedEntries, Math.min(completedEntries, expectedEntries), false);
        } else {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    /**
     * Creates compact text for the foreground export notification.
     *
     * @return readable progress text.
     */
    private String notificationText() {
        if (expectedEntries > 0) {
            return completedEntries + " / " + expectedEntries + " - " + currentEntry;
        }

        return currentEntry;
    }

    /**
     * Opens the main viewer when the user taps the export notification.
     *
     * @return PendingIntent that brings MainActivity to the foreground.
     */
    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    /**
     * Creates the export notification channel on Android versions that require one.
     */
    private void ensureNotificationChannel() {
        if (!usesNotificationChannels()) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
            EXPORT_NOTIFICATION_CHANNEL_ID,
            "Album export",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows active RPlayer album exports.");
        channel.setShowBadge(false);
        channel.setSound(null, null);

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Checks whether Android requires notification channels.
     *
     * @return true when notifications must be assigned to a channel.
     */
    private boolean usesNotificationChannels() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * Creates a notification builder compatible with current and older Android versions.
     *
     * @return Notification builder using the export notification channel when needed.
     */
    private Notification.Builder notificationBuilder() {
        if (usesNotificationChannels()) {
            return new Notification.Builder(this, EXPORT_NOTIFICATION_CHANNEL_ID);
        }

        return new Notification.Builder(this);
    }
}
