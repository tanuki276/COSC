package com.tanuki276.cosc;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.xmlpull.v1.XmlPullParser;

/** Self-targeting helper APK: never force-stops the controller's accessibility process. */
public final class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            shell(ui, "appops set com.tanuki276.cosc ACCESS_RESTRICTED_SETTINGS allow");
            shell(ui, "settings delete secure enabled_accessibility_services");
            shell(ui, "settings put secure enabled_accessibility_services com.tanuki276.cosc/com.tanuki276.cosc.GameAccessService");
            shell(ui, "settings put secure accessibility_enabled 1");
            SystemClock.sleep(1500);
            click(ui, "Close app", 300);
            shell(ui, "am start -n com.tanuki276.cosc/.MainActivity");
            if (!click(ui, "Start external auto", 15000)) throw new AssertionError("Start button missing");
            long consentDeadline = SystemClock.uptimeMillis() + 15000;
            while (SystemClock.uptimeMillis() < consentDeadline) {
                if (click(ui, "Start", 500) || click(ui, "Start now", 500)
                        || click(ui, "Start recording", 500) || click(ui, "Share screen", 500)) break;
                if (!state(ui).status.isEmpty()) break;
            }
            long deadline = SystemClock.uptimeMillis() + 150000;
            int maxDepth = 0, activeFrames = 0;
            long lastReport = 0;
            State state = new State();
            while (SystemClock.uptimeMillis() < deadline) {
                if (maxDepth == 0) click(ui, "Got it", 250);
                state = state(ui);
                maxDepth = Math.max(maxDepth, state.depth);
                activeFrames = Math.max(activeFrames, state.active);
                if (SystemClock.uptimeMillis() - lastReport > 10000) {
                    Bundle report = new Bundle();
                    report.putString("stream", "SMOKE_PROGRESS depth=" + maxDepth + " activeFrames=" + activeFrames
                            + " status=" + state.status + "\n");
                    sendStatus(0, report); lastReport = SystemClock.uptimeMillis();
                }
                if (maxDepth >= 600 && activeFrames > 0) break;
                SystemClock.sleep(1000);
            }
            shell(ui, "am startservice -n com.tanuki276.cosc/.AutoService -a com.tanuki276.cosc.STOP");
            if (maxDepth < 600 || activeFrames == 0)
                throw new AssertionError("HUD/input integration failed: depth=" + maxDepth
                        + " activeFrames=" + activeFrames + " status=" + state.status);
            result.putString("stream", "SMOKE_OK depth=" + maxDepth + " confirmedDrillFrames=" + activeFrames + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "SMOKE_FAILED " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static final class State { int depth, active; String status = ""; }
    private State state(UiAutomation ui) throws Exception {
        // Read-only test diagnostics from our own debuggable APK, not CO-DUG's state.
        String xml = shell(ui, "run-as com.tanuki276.cosc cat shared_prefs/cosc.xml");
        State s = new State();
        if (!xml.trim().startsWith("<?xml")) return s;
        XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new java.io.StringReader(xml));
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.getEventType() != XmlPullParser.START_TAG) continue;
            String key = p.getAttributeValue(null, "name");
            if ("observed_depth".equals(key)) s.depth = Integer.parseInt(p.getAttributeValue(null, "value"));
            if ("confirmed_drill_frames".equals(key)) s.active = Integer.parseInt(p.getAttributeValue(null, "value"));
            if ("status".equals(key)) s.status = p.nextText();
        }
        return s;
    }
    private String shell(UiAutomation ui, String command) throws java.io.IOException {
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                ui.executeShellCommand(command))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
        if (node.isVisibleToUser() && text.equalsIgnoreCase(String.valueOf(node.getText()))
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
