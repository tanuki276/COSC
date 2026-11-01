package com.tanuki276.cosc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 277, SAVE_REPORT = 278;
    private java.io.File pendingReport;
    private TextView status;
    private Button start;
    private Button stop;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.setFillViewport(true);scroll.addView(makeUi());setContentView(scroll);
        if(state!=null){String path=state.getString("pendingReport");if(path!=null)pendingReport=new java.io.File(path);}
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
        sub.setText("CO-DUG external controller\nScreen vision + live HUD feedback + touch");
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

        Button access = button("Enable CO-DUG feedback (Accessibility)");
        access.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);
        start = button("Start external auto");
        stop = button("Stop auto");

        root.addView(start);
        root.addView(stop);
        Button report=button("実行記録を共有");
        report.setOnClickListener(v->new android.app.AlertDialog.Builder(this)
                .setTitle("実行記録")
                .setItems(new String[]{"ログファイルを共有", "ログをファイルに保存", "末尾のログをコピー"},
                    (dialog,which)->exportReport(which)).show());
        root.addView(report);

        TextView note = new TextView(this);
        note.setText("Enable COSC in Accessibility, then start AUTO and allow full-screen capture. COSC reads CO-DUG’s visible HUD and canvas. Shizuku is not required in this version. CO-DUG itself is not modified.");
        note.setTextColor(Color.rgb(150, 160, 170));
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

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

    private void reportMessage(String message){
        android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();
    }
    private void exportReport(int kind){
        new Thread(()->{
            try{
                if(kind==2){
                    String text=RunTrace.report(this);
                    runOnUiThread(()->{getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("COSC diagnostics",text));reportMessage("末尾のログをコピーしました");});
                    return;
                }
                java.io.File file=RunTrace.snapshot(this);
                runOnUiThread(()->{try{
                    if(kind==1){
                        pendingReport=file;
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain").putExtra(Intent.EXTRA_TITLE,file.getName()),SAVE_REPORT);
                    }else{
                        android.net.Uri uri=new android.net.Uri.Builder().scheme("content")
                            .authority(getPackageName()+".reports").appendPath(file.getName()).build();
                        Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT,"COSC diagnostics")
                            .putExtra(Intent.EXTRA_STREAM,uri)
                            .setClipData(android.content.ClipData.newRawUri("COSC diagnostics",uri))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share,"ログファイルを共有"));
                    }
                }catch(RuntimeException e){reportMessage("開けませんでした。ファイル保存またはコピーを使用してください: "+e.getMessage());}});
            }catch(Exception e){runOnUiThread(()->reportMessage("ログの出力に失敗しました: "+e.getMessage()));}
        },"cosc-log-export").start();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        if(pendingReport!=null)state.putString("pendingReport",pendingReport.getAbsolutePath());
        super.onSaveInstanceState(state);
    }

    private void startAuto() {
        if (GameAccessService.instance == null) {
            status.setText("Enable COSC in Accessibility first. HUD feedback is required for reliable drilling.");
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!targetInstalled()) {
            status.setText("CO-DUG (com.tanuki276.codug) is not installed.");
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult((Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent()), CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==SAVE_REPORT){
            java.io.File source=pendingReport;pendingReport=null;
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&source!=null){
                android.net.Uri destination=data.getData();
                new Thread(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(destination,"w")){
                    if(out==null)throw new java.io.IOException("保存先を開けません");
                    java.nio.file.Files.copy(source.toPath(),out);runOnUiThread(()->reportMessage("ログを保存しました"));
                }catch(Exception e){runOnUiThread(()->reportMessage("保存できませんでした: "+e.getMessage()));}},"cosc-log-save").start();
            }
            return;
        }
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Screen capture permission was not granted.");
            return;
        }
        Intent i = new Intent(this, AutoService.class)
                .setAction(AutoService.ACTION_START)
                .putExtra(AutoService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(AutoService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        getSharedPreferences("cosc", MODE_PRIVATE).edit().putBoolean("running", true).apply();
        status.setText("Starting external AUTO…");
        start.postDelayed(this::refresh, 250);
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
            boolean running = getSharedPreferences("cosc", MODE_PRIVATE).getBoolean("running", false);
            String live = getSharedPreferences("cosc", MODE_PRIVATE).getString("status", "");
            String text = "COSC " + BuildConfig.VERSION_NAME + "\nCO-DUG: " + (targetInstalled() ? "installed" : "missing")
                    + "\nAUTO: " + (running ? "running" : "stopped")
                    + "\nHUD feedback: " + (GameAccessService.instance != null ? "ready" : "enable Accessibility")
                    + (live.isEmpty() ? "" : "\n" + live);
            status.setText(text);
            start.setEnabled(targetInstalled() && !running);
            stop.setEnabled(running);
        });
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
