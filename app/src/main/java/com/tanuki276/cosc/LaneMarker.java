package com.tanuki276.cosc;

/** Reads the instantaneous selected-cell border, independently of rig animation. */
final class LaneMarker {
    static int detect(int[] pixels,int width,int height,float scale){
        if(scale<=0||width<1||height<1||pixels.length<width*height)return -1;
        float field=Math.min(width*.88f,480*scale),left=(width-field)/2,cell=field/3;
        int top=Math.max(0,Math.round(height*.32f-35*scale));
        int bottom=Math.min(height,Math.round(height*.32f+46*scale));
        int search=Math.max(1,Math.round(8*scale)),offset=Math.max(2,Math.round(3*scale));
        int best=-1,score=0,runner=0;
        for(int lane=0;lane<3;lane++){
            int a=edge(pixels,width,top,bottom,Math.round(left+cell*lane+3*scale),-offset,search);
            int b=edge(pixels,width,top,bottom,Math.round(left+cell*(lane+1)-3*scale),offset,search);
            int n=Math.min(a,b); // require both long, warm borders
            if(n>score){runner=score;score=n;best=lane;}else runner=Math.max(runner,n);
        }
        return score>=Math.max(10,(bottom-top)*.4f)&&score>runner*1.35f?best:-1;
    }
    private static int edge(int[] p,int w,int top,int bottom,int centre,int offset,int search){
        int best=0;
        for(int x=Math.max(0,centre-search);x<=Math.min(w-1,centre+search);x++){
            int neighbour=x+offset;if(neighbour<0||neighbour>=w)continue;
            int n=0;
            for(int y=top;y<bottom;y++){
                int c=p[y*w+x],base=p[y*w+neighbour];
                int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
                int br=(base>>16)&255,bg=(base>>8)&255,bb=base&255;
                if(r-b>14&&g-b>8&&r-g>2&&r-g<45
                        &&(r-b)-(br-bb)>10&&(g-b)-(bg-bb)>6)n++;
            }
            best=Math.max(best,n);
        }
        return best;
    }
}
