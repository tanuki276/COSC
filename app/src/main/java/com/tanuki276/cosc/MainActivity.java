package com.tanuki276.cosc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 276;
    private TextView status;
    private Button grant;
    private Button start;
    private Button stop;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::refresh;
    private final Shizuku.OnBinderDeadListener binderDead = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> refresh();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(makeUi());
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
        }
        refresh();
    }

    private LinearLayout makeUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(16, 20, 24));

        TextView title = new TextView(this);
        title.setText("COSC");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("CO-DUG Direct Smart Controller\n50 ms native state bridge · deterministic planner");
        sub.setTextColor(Color.rgb(180, 190, 198));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        status = new TextView(this);
        status.setTextColor(Color.rgb(242, 184, 75));
        status.setTextSize(15);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackgroundColor(Color.rgb(29, 35, 40));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grant = button("Grant Shizuku (optional)");
        start = button("Start auto");
        stop = button("Stop auto");
        root.addView(grant);
        root.addView(start);
        root.addView(stop);

        TextView note = new TextView(this);
        note.setText("AUTO now uses CO-DUG's direct local state bridge instead of screenshot guessing. Shizuku is kept available for diagnostics/fallback work, but Start does not wait for it.\n\nBoth COSC and CO-DUG must be updated to the matching bridge version.");
        note.setTextColor(Color.rgb(150, 160, 170));
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        grant.setOnClickListener(v -> requestShizuku());
        start.setOnClickListener(v -> startAuto());
        stop.setOnClickListener(v -> stopAuto());
        return root;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) { refresh(); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(SHIZUKU_REQUEST);
            else refresh();
        } catch (Throwable e) {
            status.setText("Shizuku error: " + e.getMessage());
        }
    }

    private void startAuto() {
        if (!targetInstalled()) {
            status.setText("CO-DUG (com.tanuki276.codug) is not installed.");
            return;
        }
        Intent i = new Intent(this, AutoService.class).setAction(AutoService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        getSharedPreferences("cosc", MODE_PRIVATE).edit().putBoolean("running", true).apply();
        status.setText("Starting DIRECT AUTO…");
        start.postDelayed(this::refresh, 180);
    }

    private void stopAuto() {
        startService(new Intent(this, AutoService.class).setAction(AutoService.ACTION_STOP));
        getSharedPreferences("cosc", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        refresh();
    }

    private boolean targetInstalled() {
        try { getPackageManager().getPackageInfo("com.tanuki276.codug", 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void refresh() {
        runOnUiThread(() -> {
            boolean binder = false, permission = false;
            try {
                binder = Shizuku.pingBinder();
                if (binder) permission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {}
            boolean running = getSharedPreferences("cosc", MODE_PRIVATE).getBoolean("running", false);
            String live = getSharedPreferences("cosc", MODE_PRIVATE).getString("status", "");
            String text = "CO-DUG: " + (targetInstalled() ? "installed" : "missing")
                    + "\nAUTO: " + (running ? "running" : "stopped")
                    + "\nShizuku: " + (binder ? (permission ? "ready (optional)" : "connected / no permission") : "not running (optional)")
                    + (live.isEmpty() ? "" : "\n" + live);
            status.setText(text);
            grant.setEnabled(binder && !permission);
            start.setEnabled(targetInstalled() && !running);
            stop.setEnabled(running);
        });
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
