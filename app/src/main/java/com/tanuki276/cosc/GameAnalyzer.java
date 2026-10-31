package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

/** Palette/shape detector calibrated to the unmodified CO-DUG canvas renderer. */
final class GameAnalyzer {
    enum Type { NONE, LEFT, RIGHT, DRILL, CANCEL, TAP }
    static final class Decision {
        final Type type;
        final float xNorm, yNorm;
        final String reason;
        final boolean urgent;
        Decision(Type type, String reason) { this(type, 0, 0, reason, false); }
        Decision(Type type, float x, float y, String reason, boolean urgent) {
            this.type = type; xNorm = x; yNorm = y; this.reason = reason; this.urgent = urgent;
        }
        static Decision none(String reason) { return new Decision(Type.NONE, reason); }
    }
    // Include DARK FILLS. Thresholding on brightness alone misses most of each rock.
    private static final int[] ROCK = {0x29272e,0x3f3a40,0x4c4b50,0x20232c,
            0x382840,0x401c17,0x0f2529,0x2f1530};
    private static final int[] ORE = {0x9ba6b2,0xd77c52,0xccd8e4,0xf4c542,
            0xc07cff,0xff5e47,0x54f7e8,0xff62dc};
    private static final int[] PICKUPS = {0x67e8a5,0x64d8ff,0xffb86b,0xd28cff};
    private static final int[] POINT = {0xffd166};
    static final class ObjectHit {
        final float y;
        final boolean ore;
        ObjectHit(float y, boolean ore) { this.y = y; this.ore = ore; }
    }
    private int[] pixels;
    private int pixelWidth;
    private static final class RowTrack {
        final int index;
        final int[] seen = new int[3];
        final int[] frame = new int[3];
        final boolean[] cleared = new boolean[3];
        RowTrack(int index) { this.index = index; }
    }
    private final Map<Integer, RowTrack> tracks = new HashMap<>();
    private int frameNumber;
    private int previousDepth;
    int observedLane = -1;
    void reset() { observedLane = -1; tracks.clear(); frameNumber = 0; previousDepth = 0; }

    Decision analyze(Bitmap b, int lane, float scale, long ignored) {
        // No reliable readiness feedback => never toggle a drill speculatively.
        return Decision.none("HUD feedback required");
    }

    Decision analyze(Bitmap b, int lane, float scale, GameFeedback f, long now) {
        if (b == null || !f.targetVisible || now - f.observedAt > 300)
            return Decision.none("waiting for target/HUD");
        if (f.mode == GameFeedback.Mode.UNKNOWN || f.mode == GameFeedback.Mode.MENU)
            return Decision.none("waiting for menu/HUD");
        int size = b.getWidth() * b.getHeight();
        if (pixels == null || pixels.length != size) pixels = new int[size];
        pixelWidth = b.getWidth();
        b.getPixels(pixels, 0, pixelWidth, 0, 0, pixelWidth, b.getHeight());
        scale = Math.max(0.01f, scale);
        observedLane = detectPlayer(b, scale);
        List<ObjectHit>[] observed = detect(b, scale, lane);
        float body = b.getHeight() * .32f;
        float tip = body + (67 + f.tipReach) * scale;
        if (f.depth + 20 < previousDepth) reset();
        previousDepth = f.depth;
        frameNumber++;
        // Acquire obstacles BEFORE the player/drill/particles can cover them.
        // CO-DUG rows are fixed at index * 31 + 90 metres. Quantizing the
        // externally observed position to that grid removes shake/halo bias.
        for (int l = 0; l < 3; l++) for (ObjectHit hit : observed[l]) {
            float distance = (hit.y - body) / scale;
            if (distance < 140 || distance > 380) continue;
            float worldDepth = f.depth + distance / 3.25f;
            int index = Math.round((worldDepth - 90) / 31);
            if (index < 0 || Math.abs(worldDepth - (index * 31 + 90)) > 8) continue;
            RowTrack track = tracks.computeIfAbsent(index, RowTrack::new);
            if (track.frame[l] != frameNumber) { track.seen[l]++; track.frame[l] = frameNumber; }
        }
        tracks.values().removeIf(t -> t.index * 31 + 90 < f.depth - 20);
        @SuppressWarnings("unchecked")
        List<ObjectHit>[] objects = new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        for (RowTrack track : tracks.values()) {
            float y = body + (track.index * 31 + 90 - f.depth) * 3.25f * scale;
            if (f.mode == GameFeedback.Mode.ACTIVE && y <= tip + 14 * scale && y >= tip - 23 * scale)
                track.cleared[lane] = true;
            for (int l = 0; l < 3; l++) if (track.seen[l] >= 2 && !track.cleared[l])
                objects[l].add(new ObjectHit(y, false));
        }
        for (List<ObjectHit> hits : objects) hits.sort(Comparator.comparingDouble(t -> t.y));
        float speed = (float) Math.min(78, 16 + 62 * Math.log1p(f.depth / 5000.0) / Math.log1p(20));
        float velocity = speed * 3.25f * scale;
        ObjectHit here = next(objects[lane], body - 10 * scale);
        float y = here == null ? Float.POSITIVE_INFINITY : here.y;
        // Aim at the actual 39 CSS px destroy window, including input latency.
        // Start before the leading edge: one capture interval + gesture dispatch
        // + the next 60 Hz collision tick. A late command at the lower edge can
        // miss the entire collision window at deep-layer speed.
        float startLead = velocity * .20f;
        float arrivalY = y - velocity * .085f;
        if (f.mode == GameFeedback.Mode.ACTIVE) {
            if (f.heat >= 55 || y > tip + 30 * scale)
                return new Decision(Type.CANCEL, "confirmed DRILL: target cleared/heat guard");
            return Decision.none("confirmed DRILL: clearing object");
        }
        boolean ready = f.mode == GameFeedback.Mode.READY && f.heat < 48;
        if (ready && y <= tip + 12 * scale + startLead && arrivalY >= tip - 23 * scale)
            return new Decision(Type.DRILL, here.ore ? "ore in destroy window" : "rock in destroy window");

        if (f.mode == GameFeedback.Mode.COOLING
                && y > tip + velocity * (f.cooldownSeconds + .15f))
            return Decision.none("cooldown ends before next rock: " + f.cooldownSeconds);

        // Stay committed to a tracked row while the drill is ready.
        // During recovery, route away before body contact. Never switch through a
        // hazard which has already passed the drill tip but can still hit the body.
        float horizon = ready ? tip + velocity * .7f : body + velocity * .95f + 30 * scale;
        if (observedLane >= 0 && y < horizon && (!ready || y < tip - 23 * scale)) {
            int best = lane;
            float bestCost = laneCost(objects[lane], body, tip, horizon, ready, scale);
            for (int candidate = Math.max(0, lane - 1); candidate <= Math.min(2, lane + 1); candidate++) {
                float cost = laneCost(objects[candidate], body, tip, horizon, ready, scale)
                        + (candidate == lane ? 0 : .15f);
                if (cost < bestCost) { best = candidate; bestCost = cost; }
            }
            if (best != lane) return new Decision(best < lane ? Type.LEFT : Type.RIGHT,
                    ready ? "route ore/clear lane" : "cooling: avoid contact");
        }
        return Decision.none("HUD " + f.mode + " heat<=" + f.heat + " depth=" + f.depth + " y=" + y + " tip=" + tip);
    }

    private float laneCost(List<ObjectHit> hits, float body, float tip, float horizon, boolean ready, float scale) {
        float cost = 0;
        for (ObjectHit hit : hits) {
            if (hit.y < body - 10 * scale || hit.y > horizon) continue;
            if (hit.y < tip - 23 * scale) cost += 20;
            else if (!ready) cost += 5;
            else cost += hit.ore ? -1 : 1;
        }
        return cost;
    }
    private ObjectHit next(List<ObjectHit> hits, float minY) {
        for (ObjectHit hit : hits) if (hit.y > minY) return hit;
        return null;
    }

    @SuppressWarnings("unchecked")
    List<ObjectHit>[] detect(Bitmap b, float scale, int currentLane) {
        int w = b.getWidth(), h = b.getHeight();
        float fieldW = Math.min(w * .88f, 480 * scale);
        float left = (w - fieldW) / 2, laneW = fieldW / 3;
        int scanStart = Math.max(0, Math.round(h * .32f - 30 * scale));
        int end = Math.min(Math.round(h * .78f), Math.round(h * .32f + 390 * scale));
        List<ObjectHit>[] out = new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        for (int lane = 0; lane < 3; lane++) {
            int x0 = Math.max(0, Math.round(left + laneW * (lane + .22f)));
            int x1 = Math.min(w, Math.round(left + laneW * (lane + .78f)));
            int start = lane == currentLane ? Math.round(h * .32f + 48 * scale) : scanStart;
            int first = -1, last = -1, rock = 0, ore = 0;
            for (int y = start; y <= end; y++) {
                int r = 0, o = 0;
                if (y < end) for (int x = x0; x < x1; x += 2) {
                    int c = pixels[y * pixelWidth + x];
                    if (matches(c, ROCK, 12)) r++;
                    else if (matches(c, ORE, 8)) o++;
                }
                if (r + o >= Math.max(2, (x1 - x0) / 12)) {
                    if (first < 0) first = y;
                    last = y; rock += r; ore += o;
                }
                if (first >= 0 && (y - last > Math.max(2, 4 * scale) || y == end)) {
                    if (last - first >= Math.max(5, 22 * scale)) {
                        int pickupPixels = 0;
                        int middle = (first + last) / 2;
                        for (int yy = Math.max(first, Math.round(middle - 10 * scale));
                             yy <= Math.min(last, middle + 10 * scale); yy++)
                            for (int xx = x0; xx < x1; xx++)
                                if (matches(pixels[yy * pixelWidth + xx], PICKUPS, 8)
                                        || matches(pixels[yy * pixelWidth + xx], POINT, 2)) pickupPixels++;
                        if (pickupPixels < Math.max(4, 8 * scale * scale))
                            out[lane].add(new ObjectHit((first + last) * .5f, ore > rock));
                    }
                    first = -1; rock = 0; ore = 0;
                }
            }
        }
        return out;
    }
    private boolean matches(int c, int[] palette, int tolerance) {
        for (int p : palette)
            if (Math.abs(Color.red(c) - ((p >> 16) & 255)) <= tolerance
                    && Math.abs(Color.green(c) - ((p >> 8) & 255)) <= tolerance
                    && Math.abs(Color.blue(c) - (p & 255)) <= tolerance) return true;
        return false;
    }
    private int detectPlayer(Bitmap b, float s) {
        float fw = Math.min(b.getWidth() * .88f, 480 * s), left = (b.getWidth() - fw) / 2;
        int best = -1, count = 0;
        for (int lane = 0; lane < 3; lane++) {
            float cx = left + fw * (lane + .5f) / 3;
            int n = 0;
            for (int y = Math.round(b.getHeight() * .32f - 26 * s); y < b.getHeight() * .32f + 10 * s; y++)
                for (int x = Math.max(0, Math.round(cx - 39 * s)); x < Math.min(b.getWidth(), cx + 39 * s); x++) {
                    int c = pixels[y * pixelWidth + x];
                    if (Math.abs(Color.red(c) - 80) < 4 && Math.abs(Color.green(c) - 100) < 4
                            && Math.abs(Color.blue(c) - 119) < 4) n++;
                }
            if (n > count) { count = n; best = lane; }
        }
        return count >= 10 * s * s ? best : -1;
    }
}
