package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;

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
    static final class ObjectHit {
        final float y;
        final boolean ore;
        ObjectHit(float y, boolean ore) { this.y = y; this.ore = ore; }
    }
    int observedLane = -1;
    void reset() { observedLane = -1; }

    Decision analyze(Bitmap b, int lane, float scale, long ignored) {
        // No reliable readiness feedback => never toggle a drill speculatively.
        return Decision.none("HUD feedback required");
    }

    Decision analyze(Bitmap b, int lane, float scale, GameFeedback f, long now) {
        if (b == null || !f.targetVisible || now - f.observedAt > 300)
            return Decision.none("waiting for target/HUD");
        if (f.mode == GameFeedback.Mode.UNKNOWN || f.mode == GameFeedback.Mode.MENU)
            return Decision.none("waiting for menu/HUD");
        scale = Math.max(0.01f, scale);
        observedLane = detectPlayer(b, scale);
        List<ObjectHit>[] objects = detect(b, scale);
        float body = b.getHeight() * .32f;
        float tip = body + (67 + f.tipReach) * scale;
        float speed = (float) Math.min(78, 16 + 62 * Math.log1p(f.depth / 5000.0) / Math.log1p(20));
        float velocity = speed * 3.25f * scale;
        ObjectHit here = next(objects[lane], body - 10 * scale);
        float y = here == null ? Float.POSITIVE_INFINITY : here.y;
        // Aim at the actual 39 CSS px destroy window, including input latency.
        float arrivalY = y - velocity * .065f;
        if (f.mode == GameFeedback.Mode.ACTIVE) {
            if (f.heat >= 55 || y > tip + 30 * scale)
                return new Decision(Type.CANCEL, "confirmed DRILL: target cleared/heat guard");
            return Decision.none("confirmed DRILL: clearing object");
        }
        boolean ready = f.mode == GameFeedback.Mode.READY && f.heat < 48;
        if (ready && arrivalY <= tip + 14 * scale && arrivalY >= tip - 23 * scale)
            return new Decision(Type.DRILL, here.ore ? "ore in destroy window" : "rock in destroy window");

        // Before committing to a row, prefer ore (cooling bonus), then an empty lane.
        // During recovery, route away before body contact. Never switch through a
        // hazard which has already passed the drill tip but can still hit the body.
        float horizon = ready ? tip + velocity * .7f : body + velocity * .95f + 30 * scale;
        if (y < horizon) {
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
        return Decision.none("HUD " + f.mode + " heat<=" + f.heat + " depth=" + f.depth);
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
    List<ObjectHit>[] detect(Bitmap b, float scale) {
        int w = b.getWidth(), h = b.getHeight();
        float fieldW = Math.min(w * .88f, 480 * scale);
        float left = (w - fieldW) / 2, laneW = fieldW / 3;
        int start = Math.max(0, Math.round(h * .32f + 48 * scale));
        int end = Math.min(Math.round(h * .78f), Math.round(h * .32f + 390 * scale));
        List<ObjectHit>[] out = new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        for (int lane = 0; lane < 3; lane++) {
            int x0 = Math.max(0, Math.round(left + laneW * (lane + .22f)));
            int x1 = Math.min(w, Math.round(left + laneW * (lane + .78f)));
            int first = -1, last = -1, rock = 0, ore = 0;
            for (int y = start; y <= end; y++) {
                int r = 0, o = 0;
                if (y < end) for (int x = x0; x < x1; x += 2) {
                    int c = b.getPixel(x, y);
                    if (matches(c, ROCK)) r++;
                    else if (matches(c, ORE)) o++;
                }
                if (r + o >= Math.max(2, (x1 - x0) / 12)) {
                    if (first < 0) first = y;
                    last = y; rock += r; ore += o;
                }
                if (first >= 0 && (y - last > Math.max(2, 4 * scale) || y == end)) {
                    if (last - first >= Math.max(4, 9 * scale))
                        out[lane].add(new ObjectHit((first + last) * .5f, ore > rock));
                    first = -1; rock = 0; ore = 0;
                }
            }
        }
        return out;
    }
    private boolean matches(int c, int[] palette) {
        for (int p : palette)
            if (Math.abs(Color.red(c) - ((p >> 16) & 255)) <= 4
                    && Math.abs(Color.green(c) - ((p >> 8) & 255)) <= 4
                    && Math.abs(Color.blue(c) - (p & 255)) <= 4) return true;
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
                    int c = b.getPixel(x, y);
                    if (Math.abs(Color.red(c) - 80) < 4 && Math.abs(Color.green(c) - 100) < 4
                            && Math.abs(Color.blue(c) - 119) < 4) n++;
                }
            if (n > count) { count = n; best = lane; }
        }
        return count >= 10 * s * s ? best : -1;
    }
}
