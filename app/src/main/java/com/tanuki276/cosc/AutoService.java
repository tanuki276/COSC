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
    private final ControlTiming timing = new ControlTiming();
    private long lastFrameAt;
    private volatile long lastImageAt;
    private float framePeriodMs=83;
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
    private final RgbaSampler sampler=new RgbaSampler();
    private int[] samplePixels;
    private int currentLane = 1;
    private int seenRunId;
    private int pendingLane=-1,laneVotes;
    private long lastTraceAt;
    private int observedDepth, previousHp=-1;
    private long lastResumeAt;
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
        timing.reset();
        RunTrace.start(this);
        lastFrameAt=0;lastImageAt=SystemClock.uptimeMillis();framePeriodMs=83;pendingLane=-1;laneVotes=0;
        currentLane = 1;
        observedDepth = 0;previousHp=-1;lastResumeAt=0;
        confirmedDrillFrames = 0;
        lastMoveAt = 0;
        lastOverlayTapAt = 0;
        launchTargetDirect();

        if (GameAccessService.instance == null) {
            notifyText("Stopped: enable COSC Accessibility feedback");
            stopAutomation();
            return START_NOT_STICKY;
        }
        GameAccessService.instance.beginRun();
        seenRunId=GameAccessService.instance.read().runId;
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

            captureThread = new HandlerThread("cosc-capture", android.os.Process.THREAD_PRIORITY_DEFAULT);
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

            mainHandler.postDelayed(captureWatchdog,500);
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

    private final Runnable captureWatchdog=new Runnable(){public void run(){
        if(!running.get())return;
        if(firstFrameSeen.get()&&SystemClock.uptimeMillis()-lastImageAt>1200){
            GameAccessService access=GameAccessService.instance;
            GameFeedback f=access==null?null:access.read();
            if(f!=null&&f.targetVisible&&!f.paused&&!f.gameOver){
                boolean paused=access.pauseForCaptureFailure();
                RunTrace.event("CAPTURE_TIMEOUT pauseAccepted="+paused);
                notifyText("Stopped: capture stalled. Pause requested; restart AUTO.");stopAutomation();return;
            }
        }
        mainHandler.postDelayed(this,500);
    }};

    private void onImageAvailable(ImageReader reader) {
        if (!running.get()) return;
        Image image;
        try{image=reader.acquireLatestImage();}catch(IllegalStateException closed){return;}
        if (image == null) return;
        firstFrameSeen.set(true);
        lastImageAt=SystemClock.uptimeMillis();
        if (!processingFrame.compareAndSet(false, true)) {
            image.close();
            return;
        }

        Bitmap frame = null;
        long acquiredAt=SystemClock.uptimeMillis();
        long ageNanos=System.nanoTime()-image.getTimestamp();
        long capturedAt=ageNanos>=0&&ageNanos<2_000_000_000L?acquiredAt-ageNanos/1_000_000L:acquiredAt;
        try {
            if(acquiredAt-capturedAt>250){RunTrace.event("DROP stale frame age="+(acquiredAt-capturedAt));return;}
            GameAccessService access=GameAccessService.instance;
            if(access==null)throw new IllegalStateException("Enable COSC Accessibility feedback");
            GameFeedback feedback=access.read();
            if(!feedback.targetVisible)return;
            if(feedback.runId!=seenRunId){
                seenRunId=feedback.runId;analyzer.reset();timing.reset();currentLane=1;pendingLane=-1;
                lastMoveAt=0;previousHp=-1;RunTrace.event("NEW_RUN id="+seenRunId);
            }
            if(feedback.gameOver){RunTrace.event("GAME_OVER depth="+feedback.depth+" hp="+feedback.hp+" mode="+feedback.mode+" heat="+feedback.heat);notifyText("Stopped: collision/game over at "+feedback.depth+"m");mainHandler.post(this::stopAutomation);return;}
            long now=SystemClock.uptimeMillis();
            observedDepth=feedback.depth;
            if(previousHp>=0&&feedback.hp>=0&&feedback.hp<previousHp)
                RunTrace.event("DAMAGE depth="+feedback.depth+" hp="+previousHp+"->"+feedback.hp+" lastScene="+analyzer.scene);
            if(feedback.hp>=0)previousHp=feedback.hp;
            // Menus and unavailable calibration need no screen-sized bitmap.
            if(feedback.paused)analyzer.pause(feedback,capturedAt);
            if(now-lastMenuCheckAt>400&&now-lastOverlayTapAt>800&&checkMenu(access,now)){
                lastOverlayTapAt=SystemClock.uptimeMillis();
                RunTrace.event("MENU touch dispatched depth="+feedback.depth);
                GameFeedback resumed=feedbackForFrame(access,capturedAt);
                if(resumed.resumeAt>lastResumeAt){
                    lastResumeAt=resumed.resumeAt;timing.resume(resumed.resumeAt);
                    GameAnalyzer.Decision escape=analyzer.afterMenuResume(currentLane,resumed,SystemClock.uptimeMillis());
                    applyDecision(escape,SystemClock.uptimeMillis());
                }
                return;
            }
            if(feedback.resumeAt>0&&capturedAt<feedback.resumeAt+32)return;
            if(feedback.cssScale<=0){
                if(now-lastTraceAt>1000){RunTrace.event("WAIT control calibration");lastTraceAt=now;}return;
            }
            if(feedback.paused)return;
            if(feedback.mode==GameFeedback.Mode.UNKNOWN||feedback.hp<0){
                if(now-lastTraceAt>1000){RunTrace.event("WAIT live HUD mode="+feedback.mode+" hp="+feedback.hp);lastTraceAt=now;}return;
            }
            if(lastFrameAt>0&&capturedAt-lastFrameAt<50)return; // at most 20 perception frames/s
            if(lastFrameAt>0&&capturedAt>lastFrameAt)
                framePeriodMs=framePeriodMs*.8f+Math.min(400,capturedAt-lastFrameAt)*.2f;
            lastFrameAt=capturedAt;
            long sampleStartedAt=SystemClock.uptimeMillis();
            CapturedFrame sampled=sampleImage(image,feedback);
            if(sampled==null)return;
            frame=sampled.bitmap;
            long sampleMs=SystemClock.uptimeMillis()-sampleStartedAt;
            float frameDensity=feedback.cssScale*sampled.scale;
            feedback=feedbackForFrame(access,capturedAt);
            long analysisStartedAt=SystemClock.uptimeMillis();
            GameAnalyzer.Decision decision=analyzer.analyze(frame,currentLane,frameDensity,feedback,analysisStartedAt,
                    SystemClock::uptimeMillis,()->feedbackForFrame(access,capturedAt));
            now=SystemClock.uptimeMillis();
            long visionMs=now-analysisStartedAt;
            feedback=analyzer.usedFeedback;
            observedDepth=feedback.depth;
            if(feedback.mode==GameFeedback.Mode.ACTIVE)confirmedDrillFrames++;
            if(analyzer.observedLane>=0&&capturedAt-lastMoveAt>(analyzer.observedLaneExact?16:220)){
                if(pendingLane<0)currentLane=analyzer.observedLane;
                else if(analyzer.observedLane==pendingLane){
                    if(++laneVotes>=1){currentLane=pendingLane;pendingLane=-1;RunTrace.event("MOVE confirmed lane="+currentLane);}
                }else{
                    laneVotes=0;
                    if(capturedAt-lastMoveAt>(analyzer.observedLaneExact?250:500)){
                        currentLane=analyzer.observedLane;pendingLane=-1;
                        RunTrace.event("MOVE not reflected actual="+currentLane);
                    }
                }
            }
            if(pendingLane>=0&&now-lastMoveAt>900){
                RunTrace.event("MOVE_UNCONFIRMED target="+pendingLane+" observed="+analyzer.observedLane);
                access.pauseForCaptureFailure();notifyText("Stopped: lane confirmation lost; pause requested");
                mainHandler.post(this::stopAutomation);return;
            }
            // Dispatch before writing/flushing the diagnostic record.
            applyDecision(decision,now);
            if(now-lastTraceAt>100||decision.type!=GameAnalyzer.Type.NONE){
                long at=analyzer.decisionAt;
                RunTrace.event("FRAME capture="+capturedAt+" decision="+at+" age="+(at-capturedAt)
                    +" sample="+frame.getWidth()+"x"+frame.getHeight()+" sampleMs="+sampleMs+" visionMs="+visionMs
                    +" depth="+feedback.depth+" hp="+feedback.hp
                    +" depthAge="+(at-feedback.depthAt)+" modeAge="+(at-feedback.modeAt)
                    +" mode="+feedback.mode+" heat="+feedback.heat+" css="+frameDensity
                    +" view="+feedback.viewWidth+"x"+feedback.viewHeight
                    +" lane="+currentLane+" pending="+pendingLane+" observed="+analyzer.observedLane
                    +" laneMarker="+analyzer.observedLaneExact+" action="+decision.type+" "+decision.reason);
                lastTraceAt=now;
            }

            if (decision.urgent || now - lastStatusAt > 650) {
                notifyText("VISION · L" + (currentLane + 1) + " · " + decision.reason);
                lastStatusAt = now;
            }
        } catch (Throwable e) {
            RunTrace.event("FRAME_ERROR "+e);
            if (SystemClock.uptimeMillis() - lastStatusAt > 1000) {
                notifyText("frame error: " + message(e));
                lastStatusAt = SystemClock.uptimeMillis();
            }
        } finally {
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
        GameFeedback current=access.read();
        if(!current.targetVisible || current.paused || current.gameOver)return;
        if(!timing.allow(d.type,current,SystemClock.uptimeMillis()))return;
        // Movement waits for confirmation. A protective pulse and thermal
        // cancellation can still act while either lane may be current.
        if(pendingLane>=0&&!d.allowDuringMove)return;
        if(d.type!=GameAnalyzer.Type.NONE&&SystemClock.uptimeMillis()-analyzer.decisionAt>180){RunTrace.event("BLOCK expired decision");return;}
        if((d.type==GameAnalyzer.Type.DRILL||d.type==GameAnalyzer.Type.CANCEL)
                &&SystemClock.uptimeMillis()-current.modeAt>450){RunTrace.event("BLOCK stale mode");return;}
        if(d.type==GameAnalyzer.Type.DRILL && (current.mode!=GameFeedback.Mode.READY||current.heat<0||current.heat>=48))return;
        if(d.type==GameAnalyzer.Type.CANCEL && current.mode!=GameFeedback.Mode.ACTIVE)return;
        switch (d.type) {
            case DRILL, CANCEL -> {
                long requestedAt=SystemClock.uptimeMillis();
                int rc = tapControl(CONTROL_DRILL);
                RunTrace.event("INPUT "+d.type+" rc="+rc+" elapsed="+(SystemClock.uptimeMillis()-requestedAt));
                if(rc>=0){lastDrillAt=SystemClock.uptimeMillis();timing.accepted(d.type,requestedAt);analyzer.accepted(d,lastDrillAt);}
            }
            case LEFT -> {if(pendingLane<0&&d.lane>=0)currentLane=d.lane;move(-1,d.urgent,now);}
            case RIGHT -> {if(pendingLane<0&&d.lane>=0)currentLane=d.lane;move(1,d.urgent,now);}
            default -> { }
        }
    }

    private void move(int direction, boolean urgent, long now) throws Exception {
        long minGap = urgent ? 138 : 150;
        if (now - lastMoveAt < minGap || pendingLane>=0) return;
        int next = Math.max(0, Math.min(2, currentLane + Integer.signum(direction)));
        if (next == currentLane) return;

        int rc = tapControl(direction < 0 ? CONTROL_LEFT : CONTROL_RIGHT);
        RunTrace.event("INPUT MOVE from="+currentLane+" to="+next+" rc="+rc);
        if (rc >= 0) {
            pendingLane=next;laneVotes=0;
            lastMoveAt = SystemClock.uptimeMillis();
        } else if (now - lastStatusAt > 350) {
            notifyText("MOVE tap rejected rc=" + rc);
            lastStatusAt = now;
        }
    }

    private int tapControl(int control) throws Exception {
        GameAccessService access = GameAccessService.instance;
        return access == null ? -3 : access.tapControl(control,analyzer.decisionAt+180);
    }

    private GameFeedback feedbackForFrame(GameAccessService access,long capturedAt){
        GameFeedback f=access.read();
        f.atFrame(capturedAt,SystemClock.uptimeMillis());f.lastMoveAt=lastMoveAt;f.pendingLane=pendingLane;
        f.inputLatencyMs=timing.latencyMs();f.framePeriodMs=framePeriodMs;return f;
    }

    private static final class CapturedFrame {
        final Bitmap bitmap;final float scale;
        CapturedFrame(Bitmap bitmap,float scale){this.bitmap=bitmap;this.scale=scale;}
    }
    private CapturedFrame sampleImage(Image image,GameFeedback f){
        Image.Plane[] planes=image.getPlanes();if(planes.length==0)return null;
        int imageWidth=image.getWidth(),imageHeight=image.getHeight();
        int left=0,top=0,width=imageWidth,height=imageHeight;
        if(f.viewWidth>imageWidth*.7f&&f.viewHeight>imageHeight*.6f
                &&f.viewLeft<imageWidth&&f.viewTop<imageHeight){
            left=Math.max(0,f.viewLeft);top=Math.max(0,f.viewTop);
            width=Math.min(f.viewWidth,imageWidth-left);height=Math.min(f.viewHeight,imageHeight-top);
        }
        int outWidth=Math.min(360,width);
        int outHeight=Math.max(1,Math.round(height*(outWidth/(float)width)));
        int count=outWidth*outHeight;
        if(samplePixels==null||samplePixels.length!=count)samplePixels=new int[count];
        Image.Plane plane=planes[0];
        sampler.sample(plane.getBuffer(),imageWidth,imageHeight,plane.getRowStride(),plane.getPixelStride(),
                left,top,width,height,outWidth,outHeight,samplePixels);
        Bitmap bitmap=Bitmap.createBitmap(samplePixels,outWidth,outHeight,Bitmap.Config.ARGB_8888);
        return new CapturedFrame(bitmap,outWidth/(float)width);
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
        mainHandler.removeCallbacks(captureWatchdog);
        RunTrace.event("STOP depth="+observedDepth);
        RunTrace.close();
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
