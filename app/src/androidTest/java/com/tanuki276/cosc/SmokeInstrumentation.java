package com.tanuki276.cosc;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Android integration test: real WebView HUD + actual accessibility gestures. */
public final class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            startActivitySync(new Intent().setClassName("com.tanuki276.cosc", "com.tanuki276.cosc.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long accessDeadline = SystemClock.uptimeMillis() + 10000;
            while (GameAccessService.instance == null && SystemClock.uptimeMillis() < accessDeadline)
                SystemClock.sleep(250);
            if (!click(ui, "Start external auto", 10000)) throw new AssertionError("Start button missing");
            // Full-display MediaProjectionConfig eliminates the app selection picker.
            long consentDeadline = SystemClock.uptimeMillis() + 15000;
            while (SystemClock.uptimeMillis() < consentDeadline) {
                if (click(ui, "Start now", 500) || click(ui, "Start recording", 500)
                        || click(ui, "Share screen", 500)) break;
                if (getTargetContext().getSharedPreferences("cosc", 0).getBoolean("running", false)) break;
            }
            SharedPreferences prefs = getTargetContext().getSharedPreferences("cosc", 0);
            long deadline = SystemClock.uptimeMillis() + 150000;
            int maxDepth = 0, activeFrames = 0;
            while (SystemClock.uptimeMillis() < deadline) {
                maxDepth = Math.max(maxDepth, prefs.getInt("observed_depth", 0));
                activeFrames = Math.max(activeFrames, prefs.getInt("confirmed_drill_frames", 0));
                if (maxDepth >= 600 && activeFrames > 0) break;
                SystemClock.sleep(1000);
            }
            getTargetContext().startService(new Intent(getTargetContext(), AutoService.class).setAction(AutoService.ACTION_STOP));
            if (maxDepth < 600 || activeFrames == 0)
                throw new AssertionError("HUD/input integration failed: depth=" + maxDepth + " activeFrames=" + activeFrames
                        + " status=" + prefs.getString("status", ""));
            result.putString("stream", "SMOKE_OK depth=" + maxDepth + " confirmedDrillFrames=" + activeFrames + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "SMOKE_FAILED " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private boolean click(UiAutomation ui, String text, long timeout) {
        long until = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) {
                try { if (clickNode(root, text)) return true; }
                finally { root.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < until);
        return false;
    }
    private boolean clickNode(AccessibilityNodeInfo node, String text) {
        if (node.isVisibleToUser() && text.equals(String.valueOf(node.getText()))
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (clickNode(child, text)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
}
