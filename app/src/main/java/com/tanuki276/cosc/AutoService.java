package com.tanuki276.cosc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;


public final class AutoService extends Service {
    public static final String ACTION_START = "com.tanuki276.cosc.START";
    public static final String ACTION_STOP = "com.tanuki276.cosc.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TARGET = "com.tanuki276.codug";
    private static final String CHANNEL = "cosc_auto";
    private static final int NOTIFICATION_ID = 276;

    private static final int CONTROL_LEFT = 0;
    private static final int CONTROL_DRILL = 1;
    private static final int CONTROL_RIGHT = 2;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean processingFrame = new AtomicBoolean(false);
    private final AtomicBoolean captureStarted = new AtomicBoolean(false);
    private final AtomicBoolean firstFrameSeen = new AtomicBoolean(false);
    private final GameAnalyzer analyzer = new GameAnalyzer();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread captureThread;
    private Handler captureHandler;
    private ImageReader imageReader;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;

    private int pendingResultCode;
    private Intent pendingResultData;
    private int displayWidth;
    private int displayHeight;
    private int densityDpi;
    private float densityScale;
    private int currentLane = 1;
    private int observedDepth;
    private int confirmedDrillFrames;
    private long lastMoveAt;
    private long lastDrillAt;
    private long lastStatusAt;
    private long lastOverlayTapAt;
    private long lastMenuCheckAt;

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
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting screen capture + HUD feedback…"));
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY;

        pendingResultCode = resultCode;
        pendingResultData = resultData;
        firstFrameSeen.set(false);
        analyzer.reset();
        currentLane = 1;
        observedDepth = 0;
        confirmedDrillFrames = 0;
        lastMoveAt = 0;
        lastOverlayTapAt = 0;
        launchTargetDirect();

        if (GameAccessService.instance == null) {
            notifyText("Stopped: enable COSC Accessibility feedback");
            stopAutomation();
            return START_NOT_STICKY;
        }
        startCaptureAfterInputReady();

        return START_NOT_STICKY;
    }

    private void startCaptureAfterInputReady() {
        if (!running.get()) return;
        if (!captureStarted.compareAndSet(false, true)) return;

        try {
            DisplayMetrics dm = new DisplayMetrics();
            getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
            displayWidth = dm.widthPixels;
            displayHeight = dm.heightPixels;
            densityDpi = dm.densityDpi;
            densityScale = Math.max(0.5f, dm.density);

            captureThread = new HandlerThread("cosc-capture", Thread.MAX_PRIORITY);
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(pendingResultCode, pendingResultData);
            if (projection == null) throw new IllegalStateException("MediaProjection could not start");

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (running.get()) notifyText("Stopped: screen capture ended");
                    stopAutomation();
                }
            }, captureHandler);

            imageReader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
            virtualDisplay = projection.createVirtualDisplay(
                    "COSC-capture",
                    displayWidth,
                    displayHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler
            );
            if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay could not start");

            launchTargetDirect();
            notifyText("VISION+TOUCH READY · waiting for first frame");

            mainHandler.postDelayed(() -> {
                if (running.get() && captureStarted.get() && !firstFrameSeen.get()) {
                    notifyText("Stopped: MediaProjection produced no frames");
                    stopAutomation();
                }
            }, 2000);
        } catch (Throwable e) {
            notifyText("Stopped: " + message(e));
            stopAutomation();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        if (!running.get()) return;
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        firstFrameSeen.set(true);
        if (!processingFrame.compareAndSet(false, true)) {
            image.close();
            return;
        }

        Bitmap frame = null;
        Bitmap scaled = null;
        try {
            frame = imageToBitmap(image);
            if (frame == null) return;

            int targetWidth = Math.min(480, frame.getWidth());
            int targetHeight = Math.max(1, Math.round(frame.getHeight() * (targetWidth / (float) frame.getWidth())));
            scaled = frame.getWidth() == targetWidth
                    ? frame
                    : Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, false);

            float captureScale = scaled.getWidth() / (float) Math.max(1, displayWidth);
            float frameDensity = Math.max(0.01f, densityScale * captureScale);
            long now = SystemClock.uptimeMillis();
            GameAccessService access = GameAccessService.instance;
            if (access == null) throw new IllegalStateException("Enable COSC Accessibility feedback");
            GameFeedback feedback = access.read();
            if (!feedback.targetVisible) return;
            observedDepth = feedback.depth;
            if (feedback.mode == GameFeedback.Mode.ACTIVE) confirmedDrillFrames++;
            if (now - lastMenuCheckAt > 400 && now - lastOverlayTapAt > 800 && checkMenu(access, now)) {
                lastOverlayTapAt = now;
                lastDrillAt = now;
                return;
            }
            GameAnalyzer.Decision decision = analyzer.analyze(scaled, currentLane, frameDensity, feedback, now);
            if (analyzer.observedLane >= 0 && now - lastMoveAt > 280)
                currentLane = analyzer.observedLane;
            applyDecision(decision, now);

            if (decision.urgent || now - lastStatusAt > 650) {
                notifyText("VISION · L" + (currentLane + 1) + " · " + decision.reason);
                lastStatusAt = now;
            }
        } catch (Throwable e) {
            if (SystemClock.uptimeMillis() - lastStatusAt > 1000) {
                notifyText("frame error: " + message(e));
                lastStatusAt = SystemClock.uptimeMillis();
            }
        } finally {
            if (scaled != null && scaled != frame) scaled.recycle();
            if (frame != null) frame.recycle();
            image.close();
            processingFrame.set(false);
        }
    }

    private boolean checkMenu(GameAccessService access, long now) {
        lastMenuCheckAt = now;
        return access.handleMenu();
    }

    private void applyDecision(GameAnalyzer.Decision d, long now) throws Exception {
        GameAccessService access = GameAccessService.instance;
        if (access == null) throw new IllegalStateException("COSC accessibility feedback disconnected");
        // Rate-limit requests until a NEW HUD frame confirms the last toggle.
        if (now - lastDrillAt < 160) return;
        switch (d.type) {
            case DRILL, CANCEL -> {
                int rc = tapControl(CONTROL_DRILL);
                if (rc == 0) lastDrillAt = SystemClock.uptimeMillis();
            }
            case LEFT -> move(-1, d.urgent, now);
            case RIGHT -> move(1, d.urgent, now);
            default -> { }
        }
    }

    private void move(int direction, boolean urgent, long now) throws Exception {
        long minGap = urgent ? 138 : 150;
        if (now - lastMoveAt < minGap) return;
        int next = Math.max(0, Math.min(2, currentLane + Integer.signum(direction)));
        if (next == currentLane) return;

        int rc = tapControl(direction < 0 ? CONTROL_LEFT : CONTROL_RIGHT);
        if (rc == 0) {
            currentLane = next;
            lastMoveAt = now;
        } else if (now - lastStatusAt > 350) {
            notifyText("MOVE tap rejected rc=" + rc);
            lastStatusAt = now;
        }
    }

    private int tapControl(int control) throws Exception {
        float scale = Math.max(0.5f, densityScale);
        float cssW = displayWidth / scale;
        float cssH = displayHeight / scale;
        float boxW = Math.min(340f, cssW * 0.90f);
        float gap = 10f;
        float unit = Math.max(1f, (boxW - gap * 2f) / 4f);
        float left = (cssW - boxW) * 0.5f;

        float xCss;
        if (control == CONTROL_LEFT) {
            xCss = left + unit * 0.5f;
        } else if (control == CONTROL_DRILL) {
            xCss = left + unit + gap + unit;
        } else {
            xCss = left + unit + gap + unit * 2f + gap + unit * 0.5f;
        }

        float yCss = cssH - 37f;
        int x = Math.max(0, Math.min(displayWidth - 1, Math.round(xCss * scale)));
        int y = Math.max(0, Math.min(displayHeight - 1, Math.round(yCss * scale)));
        GameAccessService access = GameAccessService.instance;
        return access == null ? -3 : access.tap(x, y);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / Math.max(1, pixelStride);
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private void launchTargetDirect() {
        Intent i = new Intent().setClassName(TARGET, TARGET + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Throwable ignored) {}
    }

    private void stopAutomation() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        captureStarted.set(false);
        firstFrameSeen.set(false);
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        imageReader = null;
        virtualDisplay = null;
        projection = null;
        pendingResultData = null;
        if (captureThread != null) captureThread.quitSafely();
        captureThread = null;
        captureHandler = null;
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
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("COSC · external autopilot")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    private void notifyText(String text) {
        getSharedPreferences("cosc", MODE_PRIVATE)
                .edit()
                .putBoolean("running", running.get())
                .putInt("observed_depth", observedDepth)
                .putInt("confirmed_drill_frames", confirmedDrillFrames)
                .putString("status", text)
                .apply();
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopAutomation();
        super.onDestroy();
    }
}
