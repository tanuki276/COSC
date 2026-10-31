package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;
import java.util.Locale;

final class GameAnalyzer {
    enum Type { NONE, LEFT, RIGHT, DRILL, TAP }

    static final class Decision {
        final Type type;
        final float xNorm;
        final float yNorm;
        final String reason;
        final boolean urgent;

        Decision(Type type, float xNorm, float yNorm, String reason, boolean urgent) {
            this.type = type;
            this.xNorm = xNorm;
            this.yNorm = yNorm;
            this.reason = reason;
            this.urgent = urgent;
        }

        static Decision none(String reason) { return new Decision(Type.NONE, 0, 0, reason, false); }
        static Decision of(Type type, String reason) { return new Decision(type, 0, 0, reason, false); }
        static Decision urgent(Type type, String reason) { return new Decision(type, 0, 0, reason, true); }
        static Decision tap(float x, float y, String reason) { return new Decision(Type.TAP, x, y, reason, true); }
    }

    private static final int LANES = 3;
    private int[] previousSample;
    private int stillFrames;
    private int lastPreferredLane = 1;
    private int stablePreferenceFrames;

    Decision analyze(Bitmap bitmap, int currentLane, float densityScale, long sinceLastDrillMs) {
        if (bitmap == null || bitmap.getWidth() < 120 || bitmap.getHeight() < 120) {
            return Decision.none("bad frame");
        }

        float motion = frameMotion(bitmap);
        if (motion < 4.2f) stillFrames++; else stillFrames = 0;

        if (stillFrames >= 3) {
            float[] yellow = findLargeYellowButton(bitmap);
            if (yellow != null) {
                stillFrames = 0;
                return Decision.tap(yellow[0], yellow[1], "primary/retry");
            }
            if (stillFrames >= 5) {
                stillFrames = 0;
                return Decision.tap(0.36f, bitmap.getWidth() > bitmap.getHeight() ? 0.56f : 0.52f,
                        "upgrade choice");
            }
        }

        LaneState[] lanes = scanLanes(bitmap, densityScale);
        LaneState here = lanes[currentLane];
        int best = chooseBestLane(lanes, currentLane);

        if (best == lastPreferredLane) stablePreferenceFrames++;
        else {
            lastPreferredLane = best;
            stablePreferenceFrames = 1;
        }

        // Emergency: collision zone is occupied. Prefer moving to a clearly safer neighbor;
        // if there is no safe route, pulse the drill.
        if (here.nearHazard > 0.055f) {
            if (best != currentLane && lanes[best].nearHazard + 0.018f < here.nearHazard) {
                return Decision.urgent(best < currentLane ? Type.LEFT : Type.RIGHT,
                        "EMERGENCY " + laneSummary(lanes));
            }
            if (sinceLastDrillMs > 360) {
                return Decision.urgent(Type.DRILL, "forced drill " + fmt(here.nearHazard));
            }
        }

        // Ore is worth drilling, but only when it is close enough. This avoids holding the drill
        // through empty space and dramatically lowers heat buildup.
        if (here.nearOre > 0.018f && here.nearHazard < 0.050f && sinceLastDrillMs > 420) {
            return Decision.of(Type.DRILL, "ore pulse " + fmt(here.nearOre));
        }

        // Mid-range obstacle: plan one lane change early. Require two consistent frames to avoid
        // oscillating left/right because of particles and camera shake.
        if (best != currentLane && stablePreferenceFrames >= 2) {
            float improvement = lanes[currentLane].cost - lanes[best].cost;
            if (improvement > 0.035f) {
                return Decision.of(best < currentLane ? Type.LEFT : Type.RIGHT,
                        "route " + (currentLane + 1) + "->" + (best + 1) + " Δ" + fmt(improvement));
            }
        }

        // Barrier-like pattern: all lanes are occupied near the drill tip. Commit to current lane
        // and pulse the drill instead of wasting move cooldown.
        int blocked = 0;
        for (LaneState l : lanes) if (l.nearHazard + l.nearOre > 0.045f) blocked++;
        if (blocked == LANES && sinceLastDrillMs > 340) {
            return Decision.urgent(Type.DRILL, "barrier pattern");
        }

        // Favor supply/point lanes when safety is comparable.
        if (best != currentLane && stablePreferenceFrames >= 3 &&
                lanes[best].reward > lanes[currentLane].reward + 0.020f &&
                lanes[best].cost <= lanes[currentLane].cost + 0.010f) {
            return Decision.of(best < currentLane ? Type.LEFT : Type.RIGHT,
                    "reward lane " + (best + 1));
        }

        return Decision.none("L" + (currentLane + 1) + " " + laneSummary(lanes));
    }

    private int chooseBestLane(LaneState[] lanes, int currentLane) {
        int best = currentLane;
        float bestScore = objective(lanes[currentLane], currentLane, currentLane);
        for (int i = 0; i < LANES; i++) {
            float score = objective(lanes[i], currentLane, i);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private float objective(LaneState s, int currentLane, int candidate) {
        float movePenalty = Math.abs(candidate - currentLane) * 0.018f;
        // Survival dominates reward.
        return s.cost + movePenalty - Math.min(0.045f, s.reward * 0.35f);
    }

    private LaneState[] scanLanes(Bitmap b, float densityScale) {
        int w = b.getWidth(), h = b.getHeight();
        densityScale = Math.max(1f, densityScale);
        float cssScreenW = w / densityScale;
        float cssFieldW = Math.min(cssScreenW * 0.88f, 480f);
        float fieldW = Math.min(w * 0.92f, cssFieldW * densityScale);
        float left = (w - fieldW) * 0.5f;
        float laneW = fieldW / 3f;

        LaneState[] out = {new LaneState(), new LaneState(), new LaneState()};
        int nearY0 = Math.round(h * 0.31f);
        int nearY1 = Math.round(h * 0.49f);
        int farY0 = Math.round(h * 0.49f);
        int farY1 = Math.round(h * 0.70f);

        for (int lane = 0; lane < LANES; lane++) {
            int x0 = Math.round(left + laneW * lane + laneW * 0.17f);
            int x1 = Math.round(left + laneW * (lane + 1) - laneW * 0.17f);
            sampleBand(b, out[lane], x0, x1, nearY0, nearY1, true);
            sampleBand(b, out[lane], x0, x1, farY0, farY1, false);
            out[lane].finish();
        }
        return out;
    }

    private void sampleBand(Bitmap b, LaneState s, int x0, int x1, int y0, int y1, boolean near) {
        int w = b.getWidth(), h = b.getHeight();
        x0 = clamp(x0, 0, w - 1); x1 = clamp(x1, x0 + 1, w);
        y0 = clamp(y0, 0, h - 1); y1 = clamp(y1, y0 + 1, h);
        for (int y = y0; y < y1; y += 3) {
            for (int x = x0; x < x1; x += 3) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                PixelKind k = classify(r, g, bl);
                if (near) s.nearTotal++; else s.farTotal++;
                switch (k) {
                    case HAZARD -> { if (near) s.nearHazardCount++; else s.farHazardCount++; }
                    case ORE -> { if (near) s.nearOreCount++; else s.farOreCount++; }
                    case SUPPLY -> { if (near) s.nearSupplyCount++; else s.farSupplyCount++; }
                    case POINT -> { if (near) s.nearPointCount++; else s.farPointCount++; }
                    default -> { }
                }
            }
        }
    }

    private PixelKind classify(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int sat = max - min;
        int sum = r + g + b;
        if (max < 65 || sum < 150) return PixelKind.BG;

        // Point / battery yellow.
        if (r > 190 && g > 150 && b < 125 && r - b > 70) return PixelKind.POINT;
        // Repair green, shield cyan, cache orange, prototype violet.
        if (g > 145 && g > r + 18 && g > b + 5) return PixelKind.SUPPLY;
        if (b > 150 && g > 120 && b >= r + 15) return PixelKind.SUPPLY;
        if (r > 190 && g > 100 && g < 190 && b < 135) return PixelKind.SUPPLY;

        // Ore cores are bright/saturated; rocks tend to be darker, flatter and broad.
        if (sum > 485 || (max > 175 && sat > 55)) return PixelKind.ORE;
        if (max > 78 && (sat > 18 || sum > 290)) return PixelKind.HAZARD;
        return PixelKind.BG;
    }

    private float frameMotion(Bitmap b) {
        final int cols = 18, rows = 12;
        int[] sample = new int[cols * rows];
        int index = 0;
        for (int gy = 0; gy < rows; gy++) {
            int y = clamp((gy * b.getHeight() + b.getHeight() / 2) / rows, 0, b.getHeight() - 1);
            for (int gx = 0; gx < cols; gx++) {
                int x = clamp((gx * b.getWidth() + b.getWidth() / 2) / cols, 0, b.getWidth() - 1);
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
        for (int y = Math.round(h * 0.16f); y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                if (r > 185 && g > 135 && g < 245 && bl < 165 && r - bl > 55) yellow[y * w + x] = true;
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
        if (bestCount > 58 && bestMaxX - bestMinX > w * 0.10f && bestMaxY - bestMinY > 7) {
            return new float[]{((bestMinX + bestMaxX) * 0.5f) / w, ((bestMinY + bestMaxY) * 0.5f) / h};
        }
        return null;
    }

    private String laneSummary(LaneState[] lanes) {
        return fmt(lanes[0].cost) + "/" + fmt(lanes[1].cost) + "/" + fmt(lanes[2].cost);
    }

    private static final class LaneState {
        int nearHazardCount, nearOreCount, nearSupplyCount, nearPointCount, nearTotal;
        int farHazardCount, farOreCount, farSupplyCount, farPointCount, farTotal;
        float nearHazard, nearOre, reward, cost;

        void finish() {
            nearHazard = ratio(nearHazardCount, nearTotal);
            nearOre = ratio(nearOreCount, nearTotal);
            float farHazard = ratio(farHazardCount, farTotal);
            float supply = ratio(nearSupplyCount, nearTotal) + ratio(farSupplyCount, farTotal) * 0.7f;
            float points = ratio(nearPointCount, nearTotal) + ratio(farPointCount, farTotal) * 0.5f;
            reward = supply * 1.8f + points * 0.45f + nearOre * 0.35f;
            cost = nearHazard * 2.6f + farHazard * 1.05f + nearOre * 0.30f;
        }

        private static float ratio(int n, int d) { return d == 0 ? 0f : n / (float) d; }
    }

    private enum PixelKind { BG, HAZARD, ORE, SUPPLY, POINT }

    private static String fmt(float v) { return String.format(Locale.US, "%.3f", v); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
