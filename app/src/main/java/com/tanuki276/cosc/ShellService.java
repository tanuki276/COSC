package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.RemoteException;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ShellService extends ICoscService.Stub {
    private static final String TARGET = "com.tanuki276.codug";

    public ShellService() {}

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String ping() {
        return "COSC shell pid=" + Os.getpid() + " uid=" + Os.getuid();
    }

    @Override
    public byte[] captureJpeg(int maxWidth, int quality) throws RemoteException {
        maxWidth = Math.max(240, Math.min(960, maxWidth));
        quality = Math.max(35, Math.min(90, quality));
        try {
            Process process = new ProcessBuilder("screencap", "-p").redirectErrorStream(true).start();
            byte[] png = readAll(process.getInputStream());
            int code = process.waitFor();
            if (code != 0 || png.length == 0) return new byte[0];

            Bitmap source = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (source == null) return new byte[0];
            Bitmap output = source;
            if (source.getWidth() > maxWidth) {
                int height = Math.max(1, Math.round(source.getHeight() * (maxWidth / (float) source.getWidth())));
                output = Bitmap.createScaledBitmap(source, maxWidth, height, true);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(180_000);
            output.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
            if (output != source) output.recycle();
            source.recycle();
            return bytes.toByteArray();
        } catch (Throwable e) {
            throw new RemoteException("capture failed: " + e.getMessage());
        }
    }

    @Override
    public String displayInfo() throws RemoteException {
        try {
            return runForText("sh", "-c", "wm size; wm density");
        } catch (Throwable e) {
            throw new RemoteException("display info failed: " + e.getMessage());
        }
    }

    @Override
    public int keyEvent(int keyCode) throws RemoteException {
        if (keyCode != 21 && keyCode != 22 && keyCode != 61 && keyCode != 62 && keyCode != 66) return -2;
        if (!targetIsForeground()) return -3;
        return runForCode("input", "keyevent", Integer.toString(keyCode));
    }

    @Override
    public int tap(int x, int y) throws RemoteException {
        if (!targetIsForeground()) return -3;
        x = Math.max(0, x);
        y = Math.max(0, y);
        return runForCode("input", "tap", Integer.toString(x), Integer.toString(y));
    }

    @Override
    public int launchTarget() throws RemoteException {
        return runForCode("am", "start", "-n", TARGET + "/.MainActivity");
    }

    private boolean targetIsForeground() throws RemoteException {
        try {
            // Android/OEM/virtual environments expose the focused app through different dumpsys
            // fields. Keep the guard, but accept any authoritative foreground/resumed signal.
            String windows = runForText(
                    "sh", "-c",
                    "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|mObscuringWindow' | head -n 12"
            );
            if (windows.contains(TARGET)) return true;

            String activities = runForText(
                    "sh", "-c",
                    "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 12"
            );
            if (activities.contains(TARGET)) return true;

            String top = runForText(
                    "sh", "-c",
                    "dumpsys activity top 2>/dev/null | head -n 80"
            );
            return top.contains(TARGET);
        } catch (Throwable e) {
            throw new RemoteException("foreground check failed: " + e.getMessage());
        }
    }

    private static int runForCode(String... command) throws RemoteException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            readAll(process.getInputStream());
            return process.waitFor();
        } catch (Throwable e) {
            throw new RemoteException("command failed: " + e.getMessage());
        }
    }

    private static String runForText(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] data = readAll(process.getInputStream());
        process.waitFor();
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
