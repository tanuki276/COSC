package com.tanuki276.cosc;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

/** Palette/shape detector calibrated to the unmodified CO-DUG canvas renderer. */
final class GameAnalyzer {
    enum Type { NONE, LEFT, RIGHT, DRILL, CANCEL, TAP }
    static final class Decision {
        final Type type;
        final float xNorm, yNorm;
        final String reason;
        final boolean urgent;
        int rowIndex = -1;
        int lane = -1;
        Decision(Type type, String reason) { this(type, 0, 0, reason, false); }
        Decision(Type type, float x, float y, String reason, boolean urgent) {
            this.type = type; xNorm = x; yNorm = y; this.reason = reason; this.urgent = urgent;
        }
        static Decision none(String reason) { return new Decision(Type.NONE, reason); }
    }
    // Include DARK FILLS. Thresholding on brightness alone misses most of each rock.
    private static final int[] ROCK = {0x29272e,0x3f3a40,0x4c4b50,0x20232c,
            0x382840,0x401c17,0x0f2529,0x2f1530};
    private static final int[] ORE = {0x9ba6b2,0xd77c52,0xccd8e4,0xf4c542,
            0xc07cff,0xff5e47,0x54f7e8,0xff62dc};
    private static final int[] PICKUPS = {0x67e8a5,0x64d8ff,0xffb86b,0xd28cff};
    private static final int[] POINT = {0xffd166};
    static final class ObjectHit {
        final float y;
        final boolean ore;
        int rowIndex=-1;
        ObjectHit(float y, boolean ore) { this.y = y; this.ore = ore; }
    }
    private int[] pixels;
    private int pixelWidth;
    // Tracks live in camera coordinates, independent of rounded/asynchronous HUD depth.
    private static final class VisualTrack {
        int id,lane,seen,absent;
        float distance;
        long at,lastSeen;
        boolean ore,cleared,covered;
        float predict(long when,float velocity){return distance-velocity*Math.max(0,Math.min(2.5f,(when-at)/1000f));}
    }
    private final List<VisualTrack> tracks=new ArrayList<>();
    private int sequence,previousDepth,previousWidth;
    private long previousFrame,pulseAt;
    private float opticalVelocity;
    int observedLane=-1;
    String scene="";
    void reset(){tracks.clear();sequence=0;previousFrame=0;previousDepth=0;opticalVelocity=0;observedLane=-1;pulseAt=0;}
    void accepted(Decision d) { }
    void accepted(Decision d,long completedAt){
        if(d.type==Type.DRILL)pulseAt=completedAt;
        if(d.type==Type.CANCEL)pulseAt=0;
    }
    Decision analyze(Bitmap b,int lane,float scale,long ignored){return Decision.none("HUD feedback required");}

    Decision analyze(Bitmap b,int lane,float scale,GameFeedback f,long now){
        if(b==null||!f.targetVisible||now-f.observedAt>1200)return Decision.none("waiting for target");
        if(f.paused){tracks.clear();previousFrame=0;return Decision.none("menu");}
        long frame=f.frameAt>0?f.frameAt:now;
        if(f.depth+20<previousDepth || previousWidth!=b.getWidth() || frame-previousFrame>1500)reset();
        previousDepth=f.depth;previousWidth=b.getWidth();
        int size=b.getWidth()*b.getHeight();
        if(pixels==null||pixels.length!=size)pixels=new int[size];
        pixelWidth=b.getWidth();b.getPixels(pixels,0,pixelWidth,0,0,pixelWidth,b.getHeight());
        scale=Math.max(.01f,scale);
        observedLane=detectPlayer(b,scale);
        if(observedLane>=0&&frame-f.lastMoveAt>260)lane=observedLane;
        List<ObjectHit>[] observed=detect(b,scale,lane);
        float body=b.getHeight()*.32f,tip=67+f.tipReach;
        float fallback=f.speed()*3.25f;
        float velocity=opticalVelocity>0?opticalVelocity:fallback;
        List<Float> rates=new ArrayList<>();
        java.util.HashSet<Integer> matched=new java.util.HashSet<>();
        for(int l=0;l<3;l++)for(ObjectHit hit:observed[l]){
            float d=(hit.y-body)/scale;
            VisualTrack best=null;float error=34;
            for(VisualTrack t:tracks)if(t.lane==l&&!t.cleared&&!matched.contains(t.id)){
                float e=Math.abs(t.predict(frame,velocity)-d);
                if(e<error){best=t;error=e;}
            }
            if(best==null){
                // Don't create tracks from the player's drill/particles.
                if(d<120&&l==lane)continue;
                best=new VisualTrack();best.id=++sequence;best.lane=l;best.distance=d;best.at=frame;tracks.add(best);
            }else{
                float dt=(frame-best.at)/1000f;
                if(dt>=.025f&&dt<=.4f&&d>135&&best.distance>d){
                    float v=(best.distance-d)/dt;
                    if(v>fallback*.45f&&v<fallback*2.4f)rates.add(v);
                }
            }
            matched.add(best.id);best.seen++;best.absent=0;best.lastSeen=frame;best.ore=hit.ore;
            // Near the player a clipped silhouette is not a reliable centre.
            if(d>tip+35||l!=lane||best.seen==1){best.distance=d;best.at=frame;}
        }
        if(!rates.isEmpty()){
            rates.sort(Float::compare);float measured=rates.get(rates.size()/2);
            opticalVelocity=opticalVelocity>0?opticalVelocity*.8f+measured*.2f:measured;
            velocity=opticalVelocity;
        }
        final float v=velocity;
        tracks.removeIf(t->t.predict(frame,v)<-45||frame-t.lastSeen>2500);
        @SuppressWarnings("unchecked")
        List<ObjectHit>[] objects=new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        for(VisualTrack t:tracks){
            float imageD=t.predict(frame,velocity);
            if(!matched.contains(t.id))t.absent++;
            // Remember evidence that this pulse covered this specific row.
            // ACTIVE observed only after a late rock has passed the tip is not
            // evidence of destruction. Retain that rock as a collision threat.
            if(t.lane==lane&&f.mode==GameFeedback.Mode.ACTIVE&&now-f.modeAt<450
                    &&pulseAt>0&&frame>=pulseAt+16&&frame-pulseAt<1000
                    &&imageD>=tip-24&&imageD<=tip+15)t.covered=true;
            if(t.covered&&t.absent>=2&&imageD<tip-26)t.cleared=true;
            if(t.cleared)continue;
            float d=t.predict(now,velocity);
            ObjectHit h=new ObjectHit(body+d*scale,t.ore);h.rowIndex=t.id;objects[t.lane].add(h);
        }
        previousFrame=frame;
        for(List<ObjectHit> list:objects)list.sort(Comparator.comparingDouble(t->t.y));
        ObjectHit here=next(objects[lane],body-10*scale);
        float distance=here==null?Float.POSITIVE_INFINITY:(here.y-body)/scale;
        StringBuilder lanes=new StringBuilder();
        for(int l=0;l<3;l++){
            lanes.append(" L").append(l).append('[');
            for(int i=0;i<Math.min(4,objects[l].size());i++)lanes.append(Math.round((objects[l].get(i).y-body)/scale)).append(',');
            lanes.append(']');
        }
        scene="v="+Math.round(velocity)+" tracks="+tracks.size()+" lane="+lane+" next="+Math.round(distance)+" tip="+tip+lanes;
        boolean fresh=f.modeAt>0&&now-f.modeAt<450;
        float latency=Math.max(.04f,Math.min(.4f,f.inputLatencyMs/1000f));
        // Use one bounded pulse. Never add a whole slow capture interval to
        // its lead: the game's base pulse lasts only 0.5 seconds.
        float lead=velocity*Math.min(.30f,latency+Math.min(.10f,f.framePeriodMs/2000f)+.03f);
        float arrival=distance-velocity*latency;
        // A command that would arrive below the destroy window has already
        // missed, even if the rock is still above that boundary right now.
        boolean missed=arrival<tip-24;
        if(fresh&&f.mode==GameFeedback.Mode.ACTIVE){
            if(fresh&&f.heat>=74)return new Decision(Type.CANCEL,"heat guard");
            if(fresh&&distance>tip+velocity*.45f)return new Decision(Type.CANCEL,"clear ahead");
            if(!missed)return Decision.none("pulse: "+scene);
            // A missed rock below the tip still requires an escape while active.
        }
        boolean ready=fresh&&f.mode==GameFeedback.Mode.READY&&f.heat>=0&&f.heat<48;
        if(ready&&distance<=tip+12+lead&&!missed){
            Decision d=new Decision(Type.DRILL,"intercept: "+scene);d.rowIndex=here.rowIndex;d.lane=lane;return d;
        }
        // Don't switch lanes just because cooldown is nonzero. If recovery
        // completes with time to dispatch before the leading edge, stay put.
        float timeToWindow=(distance-tip-15)/velocity;
        if(fresh&&f.mode==GameFeedback.Mode.COOLING&&f.heat<48
                &&timeToWindow>f.cooldownSeconds+latency+.12f)
            return Decision.none("recover before target: "+scene);
        float lookahead=Math.max(.7f,latency+Math.min(.25f,f.framePeriodMs/1000f)+.30f);
        float horizon=body+(tip+velocity*lookahead)*scale;
        if(distance*scale+body<horizon&&(!ready||missed)){
            int best=lane;
            float bestCost=laneCost(objects[lane],body,body+tip*scale,horizon,false,scale);
            float landing=latency+.06f;
            for(int candidate=Math.max(0,lane-1);candidate<=Math.min(2,lane+1);candidate++){
                if(candidate!=lane&&!safeLanding(objects[candidate],body,tip,velocity,landing,scale))continue;
                float cost=laneCost(objects[candidate],body,body+tip*scale,horizon,false,scale)+(candidate==lane?0:.15f);
                if(cost<bestCost){best=candidate;bestCost=cost;}
            }
            if(best!=lane){
                Decision d=new Decision(best<lane?Type.LEFT:Type.RIGHT,0,0,"escape before contact: "+scene,missed);
                d.lane=lane;return d;
            }
        }
        return Decision.none(scene+" HUD="+f.mode+" fresh="+fresh);
    }

    private boolean safeLanding(List<ObjectHit> hits,float body,float tip,float velocity,float delay,float scale){
        for(ObjectHit hit:hits){
            float atArrival=(hit.y-body)/scale-velocity*delay;
            // Include the actual +/-13 body hit zone and a dispatch/capture
            // margin. Never enter a lane containing an already missed rock.
            if(atArrival>=-20&&atArrival<Math.max(tip-24,20+velocity*.18f))return false;
        }
        return true;
    }
    private float laneCost(List<ObjectHit> hits, float body, float tip, float horizon, boolean ready, float scale) {
        float cost = 0;
        for (ObjectHit hit : hits) {
            if (hit.y < body - 10 * scale || hit.y > horizon) continue;
            if (hit.y < tip - 23 * scale) cost += 20;
            else if (!ready) cost += 5;
            else cost += hit.ore ? -1 : 1;
        }
        return cost;
    }
    private ObjectHit next(List<ObjectHit> hits, float minY) {
        for (ObjectHit hit : hits) if (hit.y > minY) return hit;
        return null;
    }

    @SuppressWarnings("unchecked")
    List<ObjectHit>[] detect(Bitmap b, float scale, int currentLane) {
        int w = b.getWidth(), h = b.getHeight();
        float fieldW = Math.min(w * .88f, 480 * scale);
        float left = (w - fieldW) / 2, laneW = fieldW / 3;
        int scanStart = Math.max(0, Math.round(h * .32f - 30 * scale));
        int end = Math.min(Math.round(h * .78f), Math.round(h * .32f + 390 * scale));
        List<ObjectHit>[] out = new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
        for (int lane = 0; lane < 3; lane++) {
            int x0 = Math.max(0, Math.round(left + laneW * (lane + .22f)));
            int x1 = Math.min(w, Math.round(left + laneW * (lane + .78f)));
            int start = Math.max(0, lane == currentLane ? Math.round(h * .32f + 48 * scale) : scanStart);
            int first = -1, last = -1, rock = 0, ore = 0;
            for (int y = start; y <= end; y++) {
                int r = 0, o = 0;
                if (y < end) for (int x = x0; x < x1; x += 2) {
                    int c = pixels[y * pixelWidth + x];
                    if (matches(c, ROCK, 16)) r++;
                    else if (matches(c, ORE, 8)) o++;
                }
                if (r + o >= Math.max(2, (x1 - x0) / 12)) {
                    if (first < 0) first = y;
                    last = y; rock += r; ore += o;
                }
                if (first >= 0 && (y - last > Math.max(2, 4 * scale) || y == end)) {
                    if (last - first >= Math.max(5, 22 * scale)) {
                        int pickupPixels = 0;
                        int middle = (first + last) / 2;
                        for (int yy = Math.max(first, Math.round(middle - 10 * scale));
                             yy <= Math.min(last, middle + 10 * scale); yy++)
                            for (int xx = x0; xx < x1; xx++)
                                if (matches(pixels[yy * pixelWidth + xx], PICKUPS, 8)
                                        || matches(pixels[yy * pixelWidth + xx], POINT, 2)) pickupPixels++;
                        if (pickupPixels < Math.max(4, 8 * scale * scale))
                            out[lane].add(new ObjectHit((first + last) * .5f, ore > rock));
                    }
                    first = -1; rock = 0; ore = 0;
                }
            }
        }
        return out;
    }
    private boolean matches(int c, int[] palette, int tolerance) {
        for (int p : palette)
            if (Math.abs(Color.red(c) - ((p >> 16) & 255)) <= tolerance
                    && Math.abs(Color.green(c) - ((p >> 8) & 255)) <= tolerance
                    && Math.abs(Color.blue(c) - (p & 255)) <= tolerance) return true;
        return false;
    }
    private int detectPlayer(Bitmap b, float s) {
        float fw = Math.min(b.getWidth() * .88f, 480 * s), left = (b.getWidth() - fw) / 2;
        int best = -1, count = 0;
        for (int lane = 0; lane < 3; lane++) {
            float cx = left + fw * (lane + .5f) / 3;
            int n = 0;
            for (int y = Math.max(0, Math.round(b.getHeight() * .32f - 26 * s)); y < Math.min(b.getHeight(), b.getHeight() * .32f + 10 * s); y++)
                for (int x = Math.max(0, Math.round(cx - 39 * s)); x < Math.min(b.getWidth(), cx + 39 * s); x++) {
                    int c = pixels[y * pixelWidth + x];
                    if (Math.abs(Color.red(c) - 80) < 4 && Math.abs(Color.green(c) - 100) < 4
                            && Math.abs(Color.blue(c) - 119) < 4) n++;
                }
            if (n > count) { count = n; best = lane; }
        }
        return count >= 10 * s * s ? best : -1;
    }
}
