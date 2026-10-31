package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;

final class GameAnalyzer {
    enum Type { NONE, LEFT, RIGHT, DRILL, TAP }

    static final class Decision {
        final Type type;
        final float xNorm;
        final float yNorm;
        final String reason;

        Decision(Type type, float xNorm, float yNorm, String reason) {
            this.type = type;
            this.xNorm = xNorm;
            this.yNorm = yNorm;
            this.reason = reason;
        }

        static Decision none(String reason) { return new Decision(Type.NONE, 0, 0, reason); }
        static Decision of(Type type, String reason) { return new Decision(type, 0, 0, reason); }
        static Decision tap(float x, float y, String reason) { return new Decision(Type.TAP, x, y, reason); }
    }

    private int[] previousSample;
    private int stillFrames;

    Decision analyze(Bitmap bitmap, int currentLane, float densityScale, long sinceLastDrillMs) {
        if (bitmap == null || bitmap.getWidth() < 120 || bitmap.getHeight() < 120) {
            return Decision.none("bad frame");
        }

        float motion = frameMotion(bitmap);
        if (motion < 5.0f) stillFrames++; else stillFrames = 0;

        if (stillFrames >= 3) {
            float[] yellow = findLargeYellowButton(bitmap);
            if (yellow != null) {
                stillFrames = 0;
                return Decision.tap(yellow[0], yellow[1], "static primary button");
            }
            if (stillFrames >= 5) {
                stillFrames = 0;
                // CO-DUG's upgrade dialog has its first choice in the left/upper part of the centered card.
                return Decision.tap(0.36f, bitmap.getWidth() > bitmap.getHeight() ? 0.56f : 0.52f, "static upgrade panel");
            }
        }

        float[] scores = laneScores(bitmap, densityScale);
        float current = scores[currentLane];
        int best = 0;
        for (int i = 1; i < 3; i++) if (scores[i] < scores[best]) best = i;

        // A nearby large blob in the current lane is either rock or ore. Drill if the thermal timer
        // has had time to recover; otherwise route toward the clearest lane.
        if (current > 0.040f) {
            if (sinceLastDrillMs > 1450) {
                return Decision.of(Type.DRILL, "object ahead " + fmt(current));
            }
            if (scores[best] + 0.012f < current && best != currentLane) {
                return Decision.of(best < currentLane ? Type.LEFT : Type.RIGHT,
                        "cooling dodge " + fmt(current) + " -> " + best);
            }
        }

        // When the current lane gets crowded, prefer a clearly quieter neighbor even before impact.
        if (current > 0.026f && scores[best] + 0.020f < current && best != currentLane) {
            return Decision.of(best < currentLane ? Type.LEFT : Type.RIGHT,
                    "preemptive dodge " + fmt(current));
        }
        return Decision.none("lane " + currentLane + " scores " + fmt(scores[0]) + "/" + fmt(scores[1]) + "/" + fmt(scores[2]));
    }

    private float[] laneScores(Bitmap b, float densityScale) {
        int w = b.getWidth(), h = b.getHeight();
        densityScale = Math.max(1f, densityScale);
        // CSS field width is min(88vw, 480px). Convert it to screenshot pixels.
        float cssScreenW = w / densityScale;
        float cssFieldW = Math.min(cssScreenW * 0.88f, 480f);
        float fieldW = Math.min(w * 0.92f, cssFieldW * densityScale);
        float left = (w - fieldW) * 0.5f;
        float laneW = fieldW / 3f;
        int y0 = Math.round(h * 0.385f);
        int y1 = Math.round(h * 0.60f);
        float[] out = new float[3];

        for (int lane = 0; lane < 3; lane++) {
            int x0 = Math.round(left + laneW * lane + laneW * 0.14f);
            int x1 = Math.round(left + laneW * (lane + 1) - laneW * 0.14f);
            int active = 0, total = 0;
            for (int y = y0; y < y1; y += 3) {
                for (int x = x0; x < x1; x += 3) {
                    int c = b.getPixel(clamp(x, 0, w - 1), clamp(y, 0, h - 1));
                    int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                    int max = Math.max(r, Math.max(g, bl));
                    int min = Math.min(r, Math.min(g, bl));
                    // Ignore the near-black tunnel; count colored/bright gameplay objects.
                    if (max > 78 && (max - min > 24 || (r + g + bl) > 330)) active++;
                    total++;
                }
            }
            out[lane] = total == 0 ? 0 : active / (float) total;
        }
        return out;
    }

    private float frameMotion(Bitmap b) {
        final int cols = 18, rows = 12;
        int[] sample = new int[cols * rows];
        int index = 0;
        for (int gy = 0; gy < rows; gy++) {
            int y = (gy * b.getHeight() + b.getHeight() / 2) / rows;
            y = clamp(y, 0, b.getHeight() - 1);
            for (int gx = 0; gx < cols; gx++) {
                int x = (gx * b.getWidth() + b.getWidth() / 2) / cols;
                x = clamp(x, 0, b.getWidth() - 1);
                int c = b.getPixel(x, y);
                sample[index++] = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
            }
        }
        if (previousSample == null) {
            previousSample = sample;
            return 99;
        }
        long diff = 0;
        for (int i = 0; i < sample.length; i++) diff += Math.abs(sample[i] - previousSample[i]);
        previousSample = sample;
        return diff / (float) sample.length;
    }

    private float[] findLargeYellowButton(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        boolean[] yellow = new boolean[w * h];
        for (int y = Math.round(h * 0.18f); y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                if (r > 185 && g > 135 && g < 240 && bl < 155 && r - bl > 65) {
                    yellow[y * w + x] = true;
                }
            }
        }

        boolean[] seen = new boolean[yellow.length];
        int bestCount = 0, bestMinX = 0, bestMaxX = 0, bestMinY = 0, bestMaxY = 0;
        int[] dx = {2, -2, 0, 0}, dy = {0, 0, 2, -2};
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int start = y * w + x;
                if (!yellow[start] || seen[start]) continue;
                seen[start] = true;
                q.add(start);
                int count = 0, minX = x, maxX = x, minY = y, maxY = y;
                while (!q.isEmpty()) {
                    int p = q.removeFirst();
                    int px = p % w, py = p / w;
                    count++;
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                    for (int i = 0; i < 4; i++) {
                        int nx = px + dx[i], ny = py + dy[i];
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int np = ny * w + nx;
                        if (yellow[np] && !seen[np]) { seen[np] = true; q.add(np); }
                    }
                }
                if (count > bestCount) {
                    bestCount = count; bestMinX = minX; bestMaxX = maxX; bestMinY = minY; bestMaxY = maxY;
                }
            }
        }
        if (bestCount > 70 && bestMaxX - bestMinX > w * 0.12f && bestMaxY - bestMinY > 8) {
            return new float[]{((bestMinX + bestMaxX) * 0.5f) / w, ((bestMinY + bestMaxY) * 0.5f) / h};
        }
        return null;
    }

    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.3f", v); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
