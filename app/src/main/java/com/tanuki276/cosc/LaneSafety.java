package com.tanuki276.cosc;

/** Bounded reachability of observed obstacle intervals; no game runtime. */
final class LaneSafety {
    private static final float STEP=.05f, BODY=22f;
    // Check the whole occupancy interval, including a rock crossing before arrival.
    static boolean clear(float[] rocks,float velocity,float from,float until){
        for(float d:rocks)if(d-velocity*from>=-BODY && d-velocity*until<=BODY)return false;
        return true;
    }
    static int choose(float[][] rocks,int lane,float velocity,float delay,float cadence){
        if(lane<0||lane>2||!(velocity>0))return lane;
        float lock=Math.max(.32f,delay+Math.min(.45f,2*cadence));
        int moveTicks=(int)Math.ceil(lock/STEP), total=40;
        int best=lane,bestReach=reach(rocks,lane,lane,velocity,delay,moveTicks,total);
        for(int next=Math.max(0,lane-1);next<=Math.min(2,lane+1);next++){
            if(next==lane)continue;
            int reach=reach(rocks,lane,next,velocity,delay,moveTicks,total);
            if(reach>bestReach){best=next;bestReach=reach;}
        }
        return best;
    }
    private static int reach(float[][] rocks,int source,int target,float v,float delay,int moveTicks,int total){
        float hold=moveTicks*STEP;
        if(source!=target&&!clear(rocks[source],v,0,delay))return -1;
        if(!clear(rocks[target],v,0,hold))return -1;
        boolean[][] reachable=new boolean[total+1][3];
        if(moveTicks>total)return total;
        reachable[moveTicks][target]=true;
        int survived=moveTicks;
        for(int t=moveTicks;t<total;t++)for(int lane=0;lane<3;lane++)if(reachable[t][lane]){
            float now=t*STEP;
            if(clear(rocks[lane],v,now,(t+1)*STEP)){
                reachable[t+1][lane]=true;survived=Math.max(survived,t+1);
            }
            int end=Math.min(total,t+moveTicks);
            for(int next=Math.max(0,lane-1);next<=Math.min(2,lane+1);next++)if(next!=lane
                    &&clear(rocks[lane],v,now,now+delay)
                    &&clear(rocks[next],v,now,end*STEP)){
                reachable[end][next]=true;survived=Math.max(survived,end);
            }
        }
        return survived;
    }
}
