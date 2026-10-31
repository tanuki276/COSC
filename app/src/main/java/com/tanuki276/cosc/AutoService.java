package com.tanuki276.cosc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public final class AutoService extends Service {
    public static final String ACTION_START = "com.tanuki276.cosc.START";
    public static final String ACTION_STOP = "com.tanuki276.cosc.STOP";
    private static final String CHANNEL = "cosc_auto";
    private static final int NOTIFICATION_ID = 276;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final GameAnalyzer analyzer = new GameAnalyzer();
    private volatile ICoscService shell;
    private int currentLane = 1;
    private long lastDrillAt = 0;
    private long lastMoveAt = 0;
    private long lastStatusAt = 0;
    private float densityScale = 1f;
    private int physicalWidth = 1, physicalHeight = 1;

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName("com.tanuki276.cosc", ShellService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            shell = ICoscService.Stub.asInterface(binder);
        }
        @Override public void onServiceDisconnected(ComponentName name) { shell = null; }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAutomation();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to Shizuku…"));
        if (running.compareAndSet(false, true)) executor.execute(this::runController);
        return START_STICKY;
    }

    private void runController() {
        try {
            if (!waitForShizuku()) throw new IllegalStateException("Shizuku is not running or permission is missing");
            Shizuku.bindUserService(userServiceArgs, connection);
            long bindDeadline = System.currentTimeMillis() + 7000;
            while (running.get() && shell == null && System.currentTimeMillis() < bindDeadline) sleep(80);
            if (shell == null) throw new IllegalStateException("Could not bind Shizuku UserService");

            parseDisplayInfo(shell.displayInfo());
            shell.launchTarget();
            notifyText("CO-DUG launched · predictive controller");
            sleep(1200);
            shell.keyEvent(62);
            sleep(550);

            int emptyFrames = 0;
            while (running.get()) {
                byte[] jpeg = shell.captureJpeg(600, 68);
                if (jpeg == null || jpeg.length == 0) {
                    emptyFrames++;
                    if (emptyFrames > 8) {
                        shell.launchTarget();
                        emptyFrames = 0;
                        sleep(750);
                    } else sleep(180);
                    continue;
                }
                emptyFrames = 0;

                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (frame == null) { sleep(160); continue; }

                boolean frameLandscape = frame.getWidth() > frame.getHeight();
                boolean displayLandscape = physicalWidth > physicalHeight;
                if (frameLandscape != displayLandscape) {
                    int swap = physicalWidth;
                    physicalWidth = physicalHeight;
                    physicalHeight = swap;
                }

                float captureScale = physicalWidth > 0 ? frame.getWidth() / (float) physicalWidth : 1f;
                float frameDensity = Math.max(1f, densityScale * captureScale);
                long now = System.currentTimeMillis();
                GameAnalyzer.Decision d = analyzer.analyze(frame, currentLane, frameDensity, now - lastDrillAt);
                frame.recycle();

                switch (d.type) {
                    case LEFT -> move(-1, d.urgent);
                    case RIGHT -> move(1, d.urgent);
                    case DRILL -> drillPulse(d.urgent ? 205 : 155);
                    case TAP -> {
                        int x = Math.round(d.xNorm * physicalWidth);
                        int y = Math.round(d.yNorm * physicalHeight);
                        shell.tap(x, y);
                        // Dialog/retry taps can reset lane position on a fresh run.
                        if (d.reason.contains("retry") || d.reason.contains("primary")) currentLane = 1;
                        sleep(260);
                    }
                    case NONE -> { }
                }

                now = System.currentTimeMillis();
                if (d.urgent || now - lastStatusAt > 900) {
                    notifyText("AUTO+ · lane " + (currentLane + 1) + " · " + d.reason);
                    lastStatusAt = now;
                }
                sleep(d.urgent ? 85 : 125);
            }
        } catch (Throwable e) {
            notifyText("Stopped: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            getSharedPreferences("cosc", MODE_PRIVATE).edit().putString("last_error", String.valueOf(e)).apply();
            sleep(1000);
        } finally {
            stopAutomation();
        }
    }

    private void move(int direction, boolean urgent) throws Exception {
        long now = System.currentTimeMillis();
        // CO-DUG itself has a 140 ms move cooldown. Keep our command stream just above it.
        if (!urgent && now - lastMoveAt < 155) return;
        int key = direction < 0 ? 21 : 22;
        if (shell.keyEvent(key) == 0) {
            currentLane = Math.max(0, Math.min(2, currentLane + Integer.signum(direction)));
            lastMoveAt = now;
        }
    }

    private void drillPulse(long durationMs) throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastDrillAt < 330) return;
        if (shell.keyEvent(62) != 0) return;
        lastDrillAt = now;
        // activateDrill() toggles off on the second SPACE. A short pulse destroys objects in the
        // tip window while avoiding the full 0.5-0.9 s heat exposure of a normal drill cycle.
        sleep(Math.max(110, Math.min(240, durationMs)));
        shell.keyEvent(62);
    }

    private boolean waitForShizuku() {
        long deadline = System.currentTimeMillis() + 5000;
        while (running.get() && System.currentTimeMillis() < deadline) {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            } catch (Throwable ignored) {}
            sleep(150);
        }
        return false;
    }

    private void parseDisplayInfo(String text) {
        try {
            java.util.regex.Matcher size = java.util.regex.Pattern.compile("(?:Physical|Override) size: (\\d+)x(\\d+)").matcher(text);
            while (size.find()) {
                physicalWidth = Integer.parseInt(size.group(1));
                physicalHeight = Integer.parseInt(size.group(2));
            }
            java.util.regex.Matcher density = java.util.regex.Pattern.compile("(?:Physical|Override) density: (\\d+)").matcher(text);
            int dpi = 160;
            while (density.find()) dpi = Integer.parseInt(density.group(1));
            densityScale = Math.max(1f, dpi / 160f);
        } catch (Throwable ignored) {}
    }

    private void stopAutomation() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        try { Shizuku.unbindUserService(userServiceArgs, connection, true); } catch (Throwable ignored) {}
        shell = null;
        getSharedPreferences("cosc", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "COSC automation", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, AutoService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("COSC · CO-DUG auto+")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    private void notifyText(String text) {
        getSharedPreferences("cosc", MODE_PRIVATE).edit().putBoolean("running", running.get()).putString("status", text).apply();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running.set(false);
        executor.shutdownNow();
        super.onDestroy();
    }
}
