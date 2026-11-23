package com.canchasonora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Servicio en primer plano con notificación de reproducción (anterior, play/pausa, siguiente).
 * El audio lo reproduce el WebView; este servicio mantiene viva la app y devuelve los botones a la página.
 */
public class MediaService extends Service {

    public interface Listener {
        void onAction(String action);
    }

    private static volatile MediaService instance;
    private static volatile Listener listener;

    static final String CHANNEL_ID = "reproduccion";
    static final int NOTIF_ID = 4242;
    static final String A_UPDATE = "com.canchasonora.app.action.UPDATE";
    static final String A_PLAYPAUSE = "com.canchasonora.app.action.PLAYPAUSE";
    static final String A_NEXT = "com.canchasonora.app.action.NEXT";
    static final String A_PREV = "com.canchasonora.app.action.PREV";
    static final String A_CLOSE = "com.canchasonora.app.action.CLOSE";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionCompat session;
    private PowerManager.WakeLock wakeLock;
    private Bitmap largeIcon;
    private boolean foreground = false;
    private String title = "";
    private String artist = "";
    private boolean playing = false;
    private long durMs = 0;
    private long posMs = 0;

    public static void setListener(Listener l) {
        listener = l;
    }

    private static void dispatch(String action) {
        Listener l = listener;
        if (l != null) l.onAction(action);
    }

    /** Se llama desde el WebView cada vez que cambia la canción o el estado. */
    public static void update(Context ctx, String title, String artist, boolean playing, long durMs, long posMs) {
        MediaService s = instance;
        if (s != null) {
            s.apply(title, artist, playing, durMs, posMs);
            return;
        }
        try {
            Intent i = new Intent(ctx, MediaService.class)
                    .setAction(A_UPDATE)
                    .putExtra("title", title)
                    .putExtra("artist", artist)
                    .putExtra("playing", playing)
                    .putExtra("dur", durMs)
                    .putExtra("pos", posMs);
            ContextCompat.startForegroundService(ctx, i);
        } catch (Exception ignored) {
            // Android no permite iniciar el servicio desde segundo plano en este momento
        }
    }

    public static void stop(Context ctx) {
        final MediaService s = instance;
        if (s != null) s.main.post(s::shutdown);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        largeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.notif_large);

        session = new MediaSessionCompat(this, "CanchaSonora");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { dispatch("play"); }
            @Override public void onPause() { dispatch("pause"); }
            @Override public void onSkipToNext() { dispatch("next"); }
            @Override public void onSkipToPrevious() { dispatch("prev"); }
            @Override public void onSeekTo(long pos) { dispatch("seek:" + pos); }
            @Override public void onStop() { dispatch("pause"); }
        });
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CanchaSonora:reproduccion");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_UPDATE.equals(a)) {
            apply(intent.getStringExtra("title"), intent.getStringExtra("artist"),
                    intent.getBooleanExtra("playing", false),
                    intent.getLongExtra("dur", 0), intent.getLongExtra("pos", 0));
        } else if (A_PLAYPAUSE.equals(a)) {
            dispatch(playing ? "pause" : "play");
        } else if (A_NEXT.equals(a)) {
            dispatch("next");
        } else if (A_PREV.equals(a)) {
            dispatch("prev");
        } else if (A_CLOSE.equals(a)) {
            dispatch("pause");
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void apply(String t, String a, boolean p, long d, long ps) {
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        playing = p;
        durMs = d;
        posMs = ps;
        main.post(this::render);
    }

    private void render() {
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs)
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        posMs, playing ? 1f : 0f)
                .build());
        session.setActive(true);

        if (playing) acquireWake(); else releaseWake();

        Notification n = buildNotification();
        if (!foreground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                foreground = true;
            } catch (Exception e) {
                notifyManager().notify(NOTIF_ID, n);
            }
        } else {
            notifyManager().notify(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setLargeIcon(largeIcon)
                .setContentTitle(title.isEmpty() ? "Cancha Sonora" : title)
                .setContentText(artist)
                .setContentIntent(content)
                .setDeleteIntent(action(A_CLOSE, 4))
                .setColor(0xFFF26522)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(playing)
                .addAction(R.drawable.ic_prev, "Anterior", action(A_PREV, 1))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "Pausa" : "Play", action(A_PLAYPAUSE, 2))
                .addAction(R.drawable.ic_next, "Siguiente", action(A_NEXT, 3))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(a);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private NotificationManager notifyManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Reproducción",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Controles de la música en segundo plano");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notifyManager().createNotificationChannel(ch);
        }
    }

    private void acquireWake() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void shutdown() {
        releaseWake();
        foreground = false;
        stopForeground(true);
        notifyManager().cancel(NOTIF_ID);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        dispatch("pause");
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        releaseWake();
        if (session != null) session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
