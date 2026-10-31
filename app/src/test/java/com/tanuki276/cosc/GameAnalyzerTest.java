package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class GameAnalyzerTest {
    private Bitmap blank(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.rgb(9, 8, 12));
        return b;
    }
    private void rect(Bitmap b, int x, int y, int w, int h, int color) {
        for (int yy = y; yy < y + h; yy++)
            for (int xx = x; xx < x + w; xx++) b.setPixel(xx, yy, color);
    }
    private GameAnalyzer.Decision settle(GameAnalyzer a, Bitmap b) {
        GameAnalyzer.Decision d = null;
        for (int i = 0; i < 5; i++) d = a.analyze(b, 1, 1, 2000);
        return d;
    }
    @Test public void yellowButtonWorksForBothScanParities() {
        for (int h : new int[]{800, 806}) {
            Bitmap b = blank(400, h);
            rect(b, 80, 500, 240, 42, Color.rgb(255, 209, 102));
            GameAnalyzer.Decision d = settle(new GameAnalyzer(), b);
            assertEquals(GameAnalyzer.Type.TAP, d.type);
            assertEquals(0.5f, d.xNorm, 0.02f);
            assertEquals(521f / h, d.yNorm, 0.02f);
        }
    }
    @Test public void staticGameplayNeverProducesBlindTap() {
        GameAnalyzer a = new GameAnalyzer();
        Bitmap b = blank(400, 800);
        for (int i = 0; i < 40; i++)
            assertNotEquals(GameAnalyzer.Type.TAP, a.analyze(b, 1, 1, 2000).type);
    }
    @Test public void yellowOreIsNotAButton() {
        Bitmap b = blank(400, 800);
        rect(b, 170, 470, 60, 60, Color.rgb(255, 209, 102));
        assertNotEquals(GameAnalyzer.Type.TAP, settle(new GameAnalyzer(), b).type);
    }
    @Test public void recognizesUpgradeCardInsteadOfFixedCoordinate() {
        Bitmap b = blank(400, 800);
        rect(b, 40, 300, 150, 130, Color.rgb(22, 32, 34));
        rect(b, 60, 320, 30, 10, Color.rgb(247, 242, 232));
        rect(b, 60, 390, 40, 6, Color.rgb(255, 209, 102));
        GameAnalyzer.Decision d = settle(new GameAnalyzer(), b);
        assertEquals(GameAnalyzer.Type.TAP, d.type);
        assertEquals("upgrade card", d.reason);
    }
    @Test public void resetDiscardsPreviousScreenHistory() {
        GameAnalyzer a = new GameAnalyzer();
        Bitmap b = blank(400, 800);
        settle(a, b);
        a.reset();
        rect(b, 80, 500, 240, 42, Color.rgb(255, 209, 102));
        assertNotEquals(GameAnalyzer.Type.TAP, a.analyze(b, 1, 1, 2000).type);
    }
}
