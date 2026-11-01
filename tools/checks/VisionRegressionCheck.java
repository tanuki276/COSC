package com.tanuki276.cosc;

import android.graphics.Bitmap;
import java.util.Arrays;

/** Static pixel fixtures only. No game loop, emulator or simulated gameplay. */
public final class VisionRegressionCheck {
    private static final int W=360,H=800;
    private static final float SCALE=.8757397f;
    private static final int[] ROCK={0x29272e,0x3f3a40,0x4c4b50,0x20232c,0x382840,0x401c17,0x0f2529,0x2f1530};
    private static Bitmap fixture(int colour,float distance,boolean visible){
        int[] p=new int[W*H];Arrays.fill(p,0xff0c0d0f);
        float field=W*.88f,left=(W-field)/2,cell=field/3;
        if(colour!=0){
            int cx=W/2,cy=Math.round(H*.32f+distance*SCALE);
            for(int y=cy-Math.round(25*SCALE);y<=cy+Math.round(25*SCALE);y++)
                for(int x=cx-Math.round(cell*.26f);x<=cx+Math.round(cell*.26f);x++)p[y*W+x]=0xff000000|colour;
        }
        if(visible){
            int x0=Math.round(left+cell+3*SCALE),x1=Math.round(left+2*cell-3*SCALE);
            for(int y=Math.round(H*.32f-44*SCALE);y<Math.round(H*.32f+64*SCALE);y++){
                for(int x=x0+1;x<x1;x++)p[y*W+x]=0xff000000|GameAnalyzer.blend(p[y*W+x],0xffffff,.055f);
                p[y*W+x0]=p[y*W+x1]=0xff625535;
            }
        }
        return new Bitmap(W,H,p);
    }
    private static GameFeedback feedback(long at){
        GameFeedback f=new GameFeedback();f.targetVisible=true;f.hp=3;f.depth=1400;f.speed=40;
        f.mode=GameFeedback.Mode.READY;f.heat=21;f.frameAt=f.observedAt=f.depthAt=f.modeAt=at;return f;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        GameAnalyzer a=new GameAnalyzer();
        for(long at=1000;at<=1600;at+=100){
            a.analyze(fixture(0,0,true),1,SCALE,feedback(at),at);
            check(a.scene.contains("tracks=0"),"selected empty cell must not emit the recurring 45px phantom: "+a.scene);
        }
        for(int rock:ROCK)for(float distance:new float[]{55,200}){
            a=new GameAnalyzer();a.analyze(fixture(rock,distance,true),1,SCALE,feedback(1000),1000);
            check(a.scene.contains("tracks=1"),"retain real rock "+Integer.toHexString(rock)+" distance="+distance+": "+a.scene);
        }
        Bitmap first=fixture(0x29272e,60,true);
        int[] pair=new int[W*H];first.getPixels(pair,0,W,0,0,W,H);
        int secondY=Math.round(H*.32f+160*SCALE);
        for(int y=secondY-20;y<=secondY+20;y++)for(int x=W/2-27;x<=W/2+27;x++)pair[y*W+x]=0xff29272e;
        a=new GameAnalyzer();
        GameAnalyzer.Decision pairDecision=a.analyze(new Bitmap(W,H,pair),1,SCALE,feedback(1000),1000);
        check(pairDecision.type==GameAnalyzer.Type.DRILL,"break an interceptable first row instead of reversing for a two-row layout: "+pairDecision.reason);
        a=new GameAnalyzer();a.analyze(fixture(0,0,true),1,SCALE,feedback(1000),1000);
        for(long at=1100;at<2600;at+=100){
            GameAnalyzer.Decision d=a.analyze(fixture(0x3f3a40,140,false),1,SCALE,feedback(at),at);
            check(d.type==GameAnalyzer.Type.NONE&&a.needsMenuScan,"obscured board must not dispatch or track modal pixels");
        }
        System.out.println("Selected-background phantom, eight rock palettes and obscured-board fixtures passed");
    }
}
