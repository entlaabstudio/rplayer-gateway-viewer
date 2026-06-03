package com.technotramp.unexpectedtracks;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps Android aware that RPlayer is actively playing media in the background.
 */
public class PlaybackForegroundService extends Service {
    public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".action.START_PLAYBACK_FOREGROUND";
    public static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".action.STOP_PLAYBACK_FOREGROUND";

    private static final String LOG_TAG = "RPlayerForeground";
    private static final int PLAYBACK_NOTIFICATION_ID = 2001;
    private static final String PLAYBACK_NOTIFICATION_CHANNEL_ID = "music_player";

    /**
     * Starts or stops the foreground service according to the received action.
     *
     * @param intent Intent containing the requested foreground service action.
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
            Log.i(LOG_TAG, "Playback foreground service stopped.");
            return START_NOT_STICKY;
        }

        startForeground(PLAYBACK_NOTIFICATION_ID, createFallbackNotification());
        Log.i(LOG_TAG, "Playback foreground service started.");
        return START_STICKY;
    }

    /**
     * Records foreground service shutdown for diagnostics.
     */
    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "Playback foreground service destroyed.");
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
     * Builds a minimal notification required before the richer media notification is refreshed.
     *
     * @return Notification that keeps the foreground service valid.
     */
    private Notification createFallbackNotification() {
        Notification.Builder builder = notificationBuilder()
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(BuildConfig.DEFAULT_MEDIA_TITLE)
            .setContentText(BuildConfig.DEFAULT_MEDIA_ARTIST)
            .setContentIntent(createContentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true);

        return builder.build();
    }

    /**
     * Opens the main viewer when the user taps the foreground notification.
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
     * Checks whether Android requires notification channels.
     *
     * Returns true when notifications must be assigned to a channel.
     */
    private boolean usesNotificationChannels() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * Creates a notification builder compatible with current and older Android versions.
     *
     * @return Notification builder using the existing media notification channel when needed.
     */
    private Notification.Builder notificationBuilder() {
        if (usesNotificationChannels()) {
            return new Notification.Builder(this, PLAYBACK_NOTIFICATION_CHANNEL_ID);
        }

        return new Notification.Builder(this);
    }
}
