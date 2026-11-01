package com.tanuki276.cosc;

import java.util.Arrays;

/** Pixel fixture checks for border identity, without game or Android execution. */
public final class LaneMarkerCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static int[] fixture(int w,int h,float scale,int lane,int shift,boolean both){
        int[] p=new int[w*h];Arrays.fill(p,0xff11151b);
        float field=Math.min(w*.88f,480*scale),left=(w-field)/2,cell=field/3;
        int y0=Math.round(h*.32f-44*scale),y1=Math.round(h*.32f+64*scale);
        for(int edge=0;edge<(both?2:1);edge++){
            int x=Math.round(left+cell*(lane+edge)+(edge==0?3:-3)*scale)+shift;
            for(int y=y0;y<y1;y++)if(x>=0&&x<w&&y>=0&&y<h)p[y*w+x]=0xff625535;
        }
        return p;
    }
    public static void main(String[] args){
        for(float scale:new float[]{.7f,1,1.3f})for(int lane=0;lane<3;lane++)for(int shift:new int[]{-4,0,4}){
            int[] p=fixture(360,800,scale,lane,shift,true);
            check(LaneMarker.detect(p,360,800,scale)==lane,"border lane/scale/shake");
        }
        int[] antialiased=fixture(360,800,1,2,0,true);
        for(int i=0;i<antialiased.length;i++)if(antialiased[i]==0xff625535)antialiased[i]=0xff3a3428;
        check(LaneMarker.detect(antialiased,360,800,1)==2,"partially covered border pixels");
        check(LaneMarker.detect(fixture(360,800,1,1,0,false),360,800,1)==-1,"one border is insufficient");
        int[] warm=new int[360*800];Arrays.fill(warm,0xff625535);
        check(LaneMarker.detect(warm,360,800,1)==-1,"warm background is insufficient");
        System.out.println("Instantaneous lane-border pixel checks passed");
    }
}
