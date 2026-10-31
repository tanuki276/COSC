package com.tanuki276.cosc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Target-scoped HUD feedback and low-latency touch injection. */
public final class GameAccessService extends AccessibilityService {
    static volatile GameAccessService instance;
    private static final String TARGET = "com.tanuki276.codug";
    private int tipReach;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final Pattern HEAT = Pattern.compile("(GREEN|YELLOW|ORANGE|RED)\\s+(READY|[0-9]+%|[0-9.]+s)");
    private static final Pattern HP = Pattern.compile("([0-8])/([1-8])");
    private static final Pattern DEPTH = Pattern.compile("([0-9,]+)m");

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    GameFeedback read() {
        GameFeedback f = new GameFeedback();
        f.observedAt = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return f;
        try {
            if (!TARGET.equals(String.valueOf(root.getPackageName()))) return f;
            f.targetVisible = true;
            f.tipReach = tipReach;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collect(root, nodes);
            try {
                for (AccessibilityNodeInfo n : nodes) {
                    String text = String.valueOf(n.getText());
                    Matcher m = HEAT.matcher(text);
                    if (m.matches()) {
                        String state = m.group(2);
                        f.mode = state.equals("READY") ? GameFeedback.Mode.READY
                                : state.endsWith("%") ? GameFeedback.Mode.ACTIVE : GameFeedback.Mode.COOLING;
                        f.heat = state.endsWith("%") ? Integer.parseInt(state.substring(0, state.length() - 1))
                                : m.group(1).equals("GREEN") ? 21 : m.group(1).equals("YELLOW") ? 47
                                : m.group(1).equals("ORANGE") ? 71 : 99;
                    }
                    m = HP.matcher(text);
                    if (m.matches()) f.hp = Integer.parseInt(m.group(1));
                    m = DEPTH.matcher(text);
                    if (m.matches()) f.depth = Math.max(f.depth, Integer.parseInt(m.group(1).replace(",", "")));
                }
            } finally { for (AccessibilityNodeInfo n : nodes) n.recycle(); }
        } finally { root.recycle(); }
        return f;
    }

    // Select the actual visible button, not a guessed point on a static screen.
    boolean handleMenu() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        try {
            if (!TARGET.equals(String.valueOf(root.getPackageName()))) return false;
            collect(root, nodes);
            AccessibilityNodeInfo best = null;
            int bestScore = 0;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isClickable() || !n.isVisibleToUser()) continue;
                String text = nodeText(n);
                int score = text.contains("x2 ON") ? 210 : text.contains("掘削を開始") || text.equals("もう一回") || text.contains("掘削を再開") ? 200
                        : text.contains("冷却フィン") ? 100
                        : text.contains("現地修理") ? 90
                        : text.contains("増設フレーム") ? 80
                        : text.contains("ロングビット") ? 70
                        : text.contains("補給パック") ? 60
                        : text.contains("高速クラッチ") || text.contains("COOLDOWN") ? 95
                        : text.contains("ドリル") && text.contains("時間") ? 50
                        : text.contains("鉱石粉砕ヘッド") ? 40
                        : text.contains("ポイント磁石") ? 30 : 0;
                if (score > bestScore) { best = n; bestScore = score; }
            }
            if (best == null) return false;
            String chosen = nodeText(best);
            boolean clicked = best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (clicked) {
                if (chosen.contains("ロングビット")) tipReach = Math.min(34, tipReach + 8);
                if (chosen.contains("掘削を開始") || chosen.equals("もう一回")) tipReach = 0;
            }
            return clicked;
        } finally { for (AccessibilityNodeInfo n : nodes) n.recycle(); root.recycle(); }
    }

    private static String nodeText(AccessibilityNodeInfo n) {
        StringBuilder text = new StringBuilder();
        if (n.getText() != null) text.append(n.getText());
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if (child != null) { text.append(' ').append(nodeText(child)); child.recycle(); }
        }
        return text.toString().trim();
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if (child != null) {
                if (child.isVisibleToUser()) { out.add(child); collect(child, out); }
                else child.recycle();
            }
        }
    }

    int tap(int x, int y) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] accepted = {false};
        main.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { done.countDown(); return; }
            boolean target = TARGET.equals(String.valueOf(root.getPackageName()));
            root.recycle();
            if (!target) { done.countDown(); return; }
            Path path = new Path(); path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 20)).build();
            boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { accepted[0] = true; done.countDown(); }
                @Override public void onCancelled(GestureDescription g) { done.countDown(); }
            }, main);
            if (!dispatched) done.countDown();
        });
        return done.await(350, TimeUnit.MILLISECONDS) && accepted[0] ? 0 : -3;
    }
}
