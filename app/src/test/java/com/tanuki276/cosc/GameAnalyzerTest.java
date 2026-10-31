package com.tanuki276.cosc;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class GameAnalyzerTest {
    private Bitmap frame(int rockColor, int lane, int centerY) {
        Bitmap b=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.rgb(9,8,12));
        int cx=Math.round(21.6f+105.6f*(lane+.5f));
        for(int y=centerY-24;y<centerY+24;y++)
            for(int x=cx-25;x<cx+25;x++) b.setPixel(x,y,0xff000000|rockColor);
        return b;
    }
    private GameFeedback hud(GameFeedback.Mode mode,int heat) {
        GameFeedback f=new GameFeedback();f.targetVisible=true;f.observedAt=10000;f.mode=mode;f.heat=heat;return f;
    }
    private GameAnalyzer prime(int color) {
        GameAnalyzer a = new GameAnalyzer();
        GameFeedback f = hud(GameFeedback.Mode.READY,21);
        a.analyze(frame(color,1,542),1,1,f,10000);
        a.analyze(frame(color,1,542),1,1,f,10083);
        return a;
    }
    private GameFeedback near(GameFeedback.Mode mode,int heat) {
        GameFeedback f=hud(mode,heat);f.depth=65;return f;
    }
    @Test public void detectsEveryDarkRockFill() {
        int[] fills={0x29272e,0x3f3a40,0x4c4b50,0x20232c,0x382840,0x401c17,0x0f2529,0x2f1530};
        for(int fill:fills){
            GameAnalyzer.Decision d=prime(fill).analyze(frame(fill,1,328),1,1,near(GameFeedback.Mode.READY,21),10167);
            assertEquals(Integer.toHexString(fill),GameAnalyzer.Type.DRILL,d.type);
        }
    }
    @Test public void coolingNeverTogglesDrill() {
        assertNotEquals(GameAnalyzer.Type.DRILL,prime(0x29272e).analyze(frame(0x29272e,1,328),1,1,near(GameFeedback.Mode.COOLING,47),10167).type);
    }
    @Test public void highHeatPreventsActivation() {
        assertNotEquals(GameAnalyzer.Type.DRILL,prime(0x29272e).analyze(frame(0x29272e,1,328),1,1,near(GameFeedback.Mode.READY,71),10167).type);
    }
    @Test public void cancelOnlyWhenHudConfirmsActive() {
        GameAnalyzer a=new GameAnalyzer();Bitmap b=frame(0x29272e,1,500);
        assertEquals(GameAnalyzer.Type.CANCEL,a.analyze(b,1,1,hud(GameFeedback.Mode.ACTIVE,30),10000).type);
        assertNotEquals(GameAnalyzer.Type.CANCEL,a.analyze(b,1,1,hud(GameFeedback.Mode.READY,21),10000).type);
    }
    @Test public void startsBeforeDeepLayerWindowToCoverInputLatency() {
        GameFeedback f=hud(GameFeedback.Mode.READY,21);f.depth=74940;f.tipReach=34;
        GameAnalyzer a=new GameAnalyzer();
        a.analyze(frame(0x29272e,1,601),1,1,f,10000);
        a.analyze(frame(0x29272e,1,601),1,1,f,10083);
        f.depth=75007;
        assertEquals(GameAnalyzer.Type.DRILL,a.analyze(frame(0x29272e,1,390),1,1,f,10167).type);
    }
    @Test public void tracksPreviouslySeenRockThroughDrillOcclusion() {
        GameAnalyzer a=prime(0x401c17);
        Bitmap b=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(9,8,12));
        assertEquals(GameAnalyzer.Type.DRILL,a.analyze(b,1,1,near(GameFeedback.Mode.READY,21),10167).type);
    }
    @Test public void floatingTextDoesNotBecomeAnObstacle() {
        Bitmap b=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(9,8,12));
        for(int y=325;y<335;y++)for(int x=155;x<205;x++)b.setPixel(x,y,Color.rgb(155,166,178));
        assertEquals(GameAnalyzer.Type.NONE,new GameAnalyzer().analyze(b,1,1,hud(GameFeedback.Mode.READY,21),10000).type);
    }
    @Test public void staleOrForeignFeedbackPreventsInput() {
        GameFeedback f=hud(GameFeedback.Mode.READY,21);f.targetVisible=false;
        GameAnalyzer a=new GameAnalyzer();Bitmap b=frame(0x29272e,1,328);
        assertEquals(GameAnalyzer.Type.NONE,a.analyze(b,1,1,f,10000).type);
        f.targetVisible=true;assertEquals(GameAnalyzer.Type.NONE,a.analyze(b,1,1,f,10500).type);
    }
}
