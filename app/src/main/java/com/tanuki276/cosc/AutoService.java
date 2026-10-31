package com.tanuki276.cosc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutoService extends Service {
    public static final String ACTION_START = "com.tanuki276.cosc.START";
    public static final String ACTION_STOP = "com.tanuki276.cosc.STOP";
    private static final String TARGET = "com.tanuki276.codug";
    private static final String CONTROL = "com.tanuki276.codug.COSC_CONTROL";
    private static final String STATE = "com.tanuki276.cosc.CODUG_STATE";
    private static final String CHANNEL = "cosc_auto";
    private static final int NOTIFICATION_ID = 276;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String latestState = "";
    private volatile long latestStateAt = 0;
    private long lastActionAt = 0;
    private long lastStatusAt = 0;
    private String lastProcessed = "";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra("state");
            if (state == null || state.length() > 64_000) return;
            latestState = state;
            latestStateAt = System.currentTimeMillis();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        IntentFilter filter = new IntentFilter(STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAutomation();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting direct controller…"));
        if (running.compareAndSet(false, true)) executor.execute(this::runController);
        return START_STICKY;
    }

    private void runController() {
        try {
            launchTarget();
            sleep(220);
            send("safe-mode");
            send("state");
            notifyText("DIRECT · waiting for CO-DUG state");

            long deadline = System.currentTimeMillis() + 3500;
            while (running.get() && latestStateAt == 0 && System.currentTimeMillis() < deadline) {
                send("state");
                sleep(80);
            }
            if (latestStateAt == 0) throw new IllegalStateException("CO-DUG bridge unavailable. Update CO-DUG APK too.");

            while (running.get()) {
                long now = System.currentTimeMillis();
                if (now - latestStateAt > 800) {
                    send("state");
                    if (now - latestStateAt > 2200) launchTarget();
                    sleep(60);
                    continue;
                }

                String raw = latestState;
                if (raw.equals(lastProcessed)) { sleep(18); continue; }
                lastProcessed = raw;

                JSONObject s = new JSONObject(raw);
                Plan p = plan(s);
                if (p.action != null && now - lastActionAt >= p.minGapMs) {
                    send(p.action);
                    lastActionAt = now;
                }

                if (p.urgent || now - lastStatusAt > 650) {
                    int lane = s.optInt("currentLane", 1) + 1;
                    int depth = (int) s.optDouble("depth", 0);
                    int hp = s.optInt("hp", 0);
                    int maxHp = s.optInt("maxHp", 0);
                    int heat = (int) Math.round(s.optDouble("drillHeat", 0) * 100);
                    notifyText("DIRECT · " + depth + "m · L" + lane + " · HP " + hp + "/" + maxHp + " · H" + heat + "% · " + p.reason);
                    lastStatusAt = now;
                }
                sleep(p.urgent ? 12 : 24);
            }
        } catch (Throwable e) {
            notifyText("Stopped: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            getSharedPreferences("cosc", MODE_PRIVATE).edit().putString("last_error", String.valueOf(e)).apply();
            sleep(700);
        } finally {
            stopAutomation();
        }
    }

    private Plan plan(JSONObject s) {
        if (s.optBoolean("gameover") || s.optBoolean("gameoverVisible")) return Plan.action("retry", "retry", true, 180);
        if (s.optBoolean("upgradeVisible")) return Plan.action("upgrade", "upgrade", true, 120);
        if (s.optBoolean("introVisible") || !s.optBoolean("started")) return Plan.action("start", "start", true, 140);
        if (s.optBoolean("paused") || s.optBoolean("manualPause")) return Plan.action("resume", "resume", true, 120);
        if (s.optBoolean("sessionStarting")) return Plan.none("session", false);

        int lane = clamp(s.optInt("currentLane", 1), 0, 2);
        double heat = s.optDouble("drillHeat", 0);
        double drill = s.optDouble("drill", 0);
        boolean drillReady = s.optBoolean("drillReady");
        double moveCooldown = s.optDouble("moveCooldown", 0);
        int hp = s.optInt("hp", 3), maxHp = s.optInt("maxHp", 3);
        JSONArray rows = s.optJSONArray("upcoming");
        if (rows == null || rows.length() == 0) {
            if (drill > 0) return Plan.action("drill", "stop idle drill", true, 70);
            return Plan.none("clear", false);
        }

        JSONObject barrier = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && r.optBoolean("barrier") && r.optDouble("distance", 999) < 70) { barrier = r; break; }
        }
        if (barrier != null) {
            int target = bestBarrierLane(barrier);
            double d = barrier.optDouble("distance", 999);
            if (lane != target && moveCooldown <= 0.001 && d > 5) {
                return Plan.action(target < lane ? "left" : "right", "align barrier L" + (target + 1), d < 38, 125);
            }
            if (lane == target && d <= 29) {
                if (drill <= 0 && drillReady && heat < 0.88) return Plan.action("drill", "break barrier", true, 70);
                if (drill > 0 && d < -1) return Plan.action("drill", "stop barrier drill", true, 60);
                return Plan.none("barrier heat " + pct(heat), true);
            }
        }

        JSONObject first = firstRelevant(rows);
        if (first != null) {
            double d = first.optDouble("distance", 999);
            JSONObject here = cell(first, lane);
            if (here != null) {
                String kind = here.optString("kind");
                if (("rock".equals(kind) || "ore".equals(kind)) && d <= 31) {
                    int safe = safestLaneForRow(first, lane, heat, hp, maxHp);
                    boolean canDrill = drillReady && heat < ("ore".equals(kind) ? 0.78 : 0.72);
                    if (drill > 0) {
                        if (d < -1 || here == null) return Plan.action("drill", "stop drill", true, 55);
                        return Plan.none("drilling " + kind, true);
                    }
                    if (safe != lane && moveCooldown <= 0.001 && !first.optBoolean("barrier")) {
                        return Plan.action(safe < lane ? "left" : "right", "evade " + kind + " -> L" + (safe + 1), true, 120);
                    }
                    if (canDrill && d <= 26) return Plan.action("drill", "drill " + kind, true, 65);
                }
            } else if (drill > 0) {
                return Plan.action("drill", "stop empty drill", true, 55);
            }
        }

        int best = bestLane(rows, lane, heat, hp, maxHp);
        double currentScore = laneScore(rows, lane, heat, hp, maxHp);
        double bestScore = laneScore(rows, best, heat, hp, maxHp);
        if (best != lane && moveCooldown <= 0.001 && bestScore + 2.2 < currentScore) {
            return Plan.action(best < lane ? "left" : "right", "route L" + (best + 1), false, 125);
        }
        if (drill > 0) return Plan.action("drill", "stop drill", true, 55);
        return Plan.none("hold L" + (lane + 1), false);
    }

    private JSONObject firstRelevant(JSONArray rows) {
        JSONObject best = null;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            double d = r.optDouble("distance", 999);
            if (d >= -2 && d < distance) { distance = d; best = r; }
        }
        return best;
    }

    private int bestBarrierLane(JSONObject row) {
        JSONArray cells = row.optJSONArray("cells");
        if (cells != null) for (int l = 0; l < 3; l++) {
            JSONObject c = cells.optJSONObject(l);
            if (c != null && "ore".equals(c.optString("kind"))) return l;
        }
        return 1;
    }

    private int safestLaneForRow(JSONObject row, int current, double heat, int hp, int maxHp) {
        int best = current;
        double score = Double.MAX_VALUE;
        for (int l = 0; l < 3; l++) {
            JSONObject c = cell(row, l);
            double s = cellCost(c, heat, hp, maxHp) + Math.abs(l - current) * 0.8;
            if (s < score) { score = s; best = l; }
        }
        return best;
    }

    private int bestLane(JSONArray rows, int current, double heat, int hp, int maxHp) {
        int best = current;
        double bestScore = laneScore(rows, current, heat, hp, maxHp);
        for (int l = 0; l < 3; l++) {
            double s = laneScore(rows, l, heat, hp, maxHp) + Math.abs(l - current) * 1.2;
            if (s < bestScore) { bestScore = s; best = l; }
        }
        return best;
    }

    private double laneScore(JSONArray rows, int lane, double heat, int hp, int maxHp) {
        double score = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            double d = r.optDouble("distance", 999);
            if (d < -2 || d > 95) continue;
            double weight = 1.0 / (1.0 + d / 24.0);
            JSONObject c = cell(r, lane);
            double cost = cellCost(c, heat, hp, maxHp);
            if (r.optBoolean("barrier")) cost *= 0.7;
            score += cost * weight;
        }
        return score;
    }

    private double cellCost(JSONObject c, double heat, int hp, int maxHp) {
        if (c == null) return 0;
        String kind = c.optString("kind");
        if ("rock".equals(kind)) return (12 + c.optInt("damage", 1) * 10) * (heat > 0.68 ? 1.6 : 1.0);
        if ("ore".equals(kind)) return heat < 0.62 ? -5.5 : 7.0;
        if ("point".equals(kind)) return -1.8;
        if ("item".equals(kind)) {
            String effect = c.optString("effect");
            if ("repair".equals(effect)) return hp < maxHp ? -18 : -2;
            if ("battery".equals(effect)) return heat > 0.30 ? -20 : -4;
            if ("shield".equals(effect)) return -11;
            if ("prototype".equals(effect)) return -9;
            if ("cache".equals(effect)) return -3;
        }
        return 0;
    }

    private JSONObject cell(JSONObject row, int lane) {
        JSONArray cells = row.optJSONArray("cells");
        return cells == null ? null : cells.optJSONObject(lane);
    }

    private void launchTarget() {
        Intent i = new Intent().setClassName(TARGET, TARGET + ".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Throwable ignored) {}
    }

    private void send(String action) {
        Intent i = new Intent(CONTROL).setPackage(TARGET).putExtra("action", action);
        sendBroadcast(i);
    }

    private void stopAutomation() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
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
                .setContentTitle("COSC · direct autopilot")
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

    private static String pct(double v) { return Math.round(v * 100) + "%"; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running.set(false);
        executor.shutdownNow();
        try { unregisterReceiver(stateReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private static final class Plan {
        final String action, reason;
        final boolean urgent;
        final long minGapMs;
        Plan(String action, String reason, boolean urgent, long minGapMs) {
            this.action = action; this.reason = reason; this.urgent = urgent; this.minGapMs = minGapMs;
        }
        static Plan action(String action, String reason, boolean urgent, long minGapMs) { return new Plan(action, reason, urgent, minGapMs); }
        static Plan none(String reason, boolean urgent) { return new Plan(null, reason, urgent, 0); }
    }
}
