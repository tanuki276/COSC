package com.tanuki276.cosc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HUD polling never runs on the image-processing thread. Coordinates come from actual buttons. */
public final class GameAccessService extends AccessibilityService {
    static volatile GameAccessService instance;
    private static final String TARGET="com.tanuki276.codug";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object lock=new Object();
    private final GameFeedback latest=new GameFeedback();
    private final AccessibilityNodeInfo[] controlNodes=new AccessibilityNodeInfo[3];
    private final Rect[] controls={new Rect(),new Rect(),new Rect()};
    private AccessibilityNodeInfo menu,pauseControl;
    private final List<AccessibilityNodeInfo> hudNodes=new ArrayList<>();
    private long lastTreeAt;
    private String menuLabel="";
    private HandlerThread workerThread;
    private Handler worker;
    private volatile boolean eventQueued;
    private final ArrayDeque<double[]> depthSamples=new ArrayDeque<>();
    private static final Pattern HEAT=Pattern.compile("(GREEN|YELLOW|ORANGE|RED)\\s+(READY|[0-9]+%|[0-9.]+s)");
    private static final Pattern HP=Pattern.compile("^([0-8])/([1-8])$");
    private static final Pattern DEPTH=Pattern.compile("^\\s*([0-9,]+)m\\s*$");

    @Override protected void onServiceConnected(){
        if(Build.VERSION.SDK_INT>=33)setCacheEnabled(false);
        instance=this;
        workerThread=new HandlerThread("cosc-hud");workerThread.start();worker=new Handler(workerThread.getLooper());
        worker.post(poll);
    }
    private final Runnable poll=new Runnable(){public void run(){
        try{
            long now=SystemClock.uptimeMillis();
            if(now-lastTreeAt>=700){sampleTree();lastTreeAt=SystemClock.uptimeMillis();}
            else refreshHud();
        }catch(Throwable ignored){}
        if(worker!=null)worker.postDelayed(this,70);
    }};
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!TARGET.equals(String.valueOf(event.getPackageName())))return;
        long at=event.getEventTime();
        synchronized(lock){latest.observedAt=SystemClock.uptimeMillis();}
        for(CharSequence value:event.getText()) if(value!=null)parse(value.toString(),at);
        if(event.getContentDescription()!=null)parse(event.getContentDescription().toString(),at);
        // Coalesce source queries; don't queue hundreds of old content events.
        Handler w=worker;
        if(w!=null&&!eventQueued){
            eventQueued=true;AccessibilityEvent copy=AccessibilityEvent.obtain(event);
            w.post(()->{try{AccessibilityNodeInfo n=copy.getSource();if(n!=null){
                try{if(n.refresh())parse(label(n),SystemClock.uptimeMillis());}finally{n.recycle();}
            }}finally{copy.recycle();eventQueued=false;}});
        }
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){
        instance=null;Handler w=worker;worker=null;if(w!=null)w.removeCallbacksAndMessages(null);
        if(workerThread!=null)workerThread.quitSafely();
        synchronized(lock){if(menu!=null)menu.recycle();menu=null;
            for(int i=0;i<3;i++){if(controlNodes[i]!=null)controlNodes[i].recycle();controlNodes[i]=null;}
        }
        super.onDestroy();
    }
    private AccessibilityNodeInfo root(boolean children){
        if(Build.VERSION.SDK_INT>=33)return getRootInActiveWindow(children
                ? AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID : 0);
        return getRootInActiveWindow();
    }
    private static String label(AccessibilityNodeInfo n){
        String t=n.getText()==null?"":n.getText().toString();
        String d=n.getContentDescription()==null?"":n.getContentDescription().toString();
        return (t+((d.isEmpty()||d.equals(t))?"":" "+d)).replace('\u00a0',' ').trim();
    }
    private void parse(String text,long at){
        text=text.replace('\u00a0',' ').trim();
        synchronized(lock){
            Matcher m=HEAT.matcher(text);
            if(m.find()&&at>=latest.modeAt){
                String state=m.group(2);
                latest.mode=state.equals("READY")?GameFeedback.Mode.READY:
                        state.endsWith("%")?GameFeedback.Mode.ACTIVE:GameFeedback.Mode.COOLING;
                latest.modeAt=at;
                if(state.endsWith("s"))latest.cooldownSeconds=Float.parseFloat(state.substring(0,state.length()-1));
                latest.heat=state.endsWith("%")?Integer.parseInt(state.substring(0,state.length()-1)):
                        m.group(1).equals("GREEN")?21:m.group(1).equals("YELLOW")?47:m.group(1).equals("ORANGE")?71:99;
            }
            m=HP.matcher(text);if(m.matches())latest.hp=Integer.parseInt(m.group(1));
            m=DEPTH.matcher(text);
            if(m.matches()&&at>=latest.depthAt){
                int depth=Integer.parseInt(m.group(1).replace(",",""));
                if(depth+20<latest.depth){depthSamples.clear();latest.speed=Float.NaN;}
                if(depth!=latest.depth||depthSamples.isEmpty()){
                    depthSamples.addLast(new double[]{at,depth});
                    while(depthSamples.size()>2&&at-depthSamples.peekFirst()[0]>700)depthSamples.removeFirst();
                    double[] first=depthSamples.peekFirst();double elapsed=at-first[0];
                    if(elapsed>=200&&depth>=first[1])latest.speed=(float)Math.max(1,Math.min(160,(depth-first[1])*1000/elapsed));
                }
                latest.depth=depth;latest.depthAt=at;
            }
            if(text.equals("DRILL BROKEN"))latest.gameOver=true;
        }
    }
    private void refreshHud(){
        for(AccessibilityNodeInfo n:hudNodes){
            long before=SystemClock.uptimeMillis();
            if(n.refresh()&&n.isVisibleToUser())parse(label(n),(before+SystemClock.uptimeMillis())/2);
        }
    }
    private void sampleTree(){
        long requested=SystemClock.uptimeMillis();AccessibilityNodeInfo r=root(true);long now=SystemClock.uptimeMillis();
        long sampledAt=(requested+now)/2;
        if(r==null){synchronized(lock){latest.targetVisible=false;}return;}
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try{
            boolean target=TARGET.equals(String.valueOf(r.getPackageName()));
            synchronized(lock){latest.targetVisible=target;latest.observedAt=now;}
            if(!target)return;
            collect(r,nodes);
            for(AccessibilityNodeInfo n:hudNodes)n.recycle();hudNodes.clear();
            AccessibilityNodeInfo best=null;int score=0;
            Rect[] found={new Rect(),new Rect(),new Rect()};Rect viewport=new Rect();
            AccessibilityNodeInfo[] buttons=new AccessibilityNodeInfo[3];
            for(AccessibilityNodeInfo n:nodes){
                String text=label(n);parse(text,sampledAt);
                if(HEAT.matcher(text).find()||DEPTH.matcher(text).matches()||HP.matcher(text).matches())
                    hudNodes.add(AccessibilityNodeInfo.obtain(n));
                String id=String.valueOf(n.getViewIdResourceName());
                if("android.webkit.WebView".equals(String.valueOf(n.getClassName())))n.getBoundsInScreen(viewport);
                if(n.isClickable()&&(id.endsWith("leftControl")||text.equals("←"))){n.getBoundsInScreen(found[0]);buttons[0]=n;}
                if(n.isClickable()&&(id.endsWith("drillControl")||(nodeText(n).contains("DRILL")&&nodeText(n).contains("SPACE")))){n.getBoundsInScreen(found[1]);buttons[1]=n;}
                if(n.isClickable()&&(id.endsWith("rightControl")||text.equals("→"))){n.getBoundsInScreen(found[2]);buttons[2]=n;}
                if(n.isClickable()&&(id.endsWith("pauseBtn")||text.equals("Ⅱ"))){
                    synchronized(lock){if(pauseControl!=null)pauseControl.recycle();pauseControl=AccessibilityNodeInfo.obtain(n);}
                }
                if(!n.isClickable())continue;
                String button=nodeText(n);int s=menuScore(button);
                if(s>score){best=n;score=s;menuLabel=button;}
            }
            synchronized(lock){
                if(menu!=null)menu.recycle();menu=best==null?null:AccessibilityNodeInfo.obtain(best);
                latest.paused=best!=null&&!menuLabel.contains("x2 ON");
                if(!viewport.isEmpty()){latest.viewLeft=viewport.left;latest.viewTop=viewport.top;
                    latest.viewWidth=viewport.width();latest.viewHeight=viewport.height();}
                if(!found[0].isEmpty()&&!found[1].isEmpty()&&!found[2].isEmpty()){
                    for(int i=0;i<3;i++){
                        controls[i].set(found[i]);
                        if(controlNodes[i]!=null)controlNodes[i].recycle();
                        controlNodes[i]=AccessibilityNodeInfo.obtain(buttons[i]);
                    }
                    float a=found[1].left-found[0].right,b=found[2].left-found[1].right;
                    if(a>0&&b>0&&Math.abs(a-b)<=Math.max(2,Math.max(a,b)*.3f))latest.cssScale=(a+b)/20f;
                }
            }
        }finally{for(AccessibilityNodeInfo n:nodes)n.recycle();r.recycle();}
    }
    private static int menuScore(String t){
        return t.contains("x2 ON")?210:t.contains("掘削を開始")||t.contains("もう一回")||t.contains("掘削を再開")?200:
                t.contains("冷却フィン")?100:t.contains("高速クラッチ")||t.contains("COOLDOWN")?95:
                t.contains("現地修理")?90:t.contains("増設フレーム")?80:t.contains("ロングビット")?70:
                t.contains("補給パック")?60:t.contains("高トルクモーター")?50:t.contains("鉱石粉砕ヘッド")?40:
                t.contains("ポイント磁石")?30:0;
    }
    GameFeedback read(){synchronized(lock){GameFeedback f=latest.copy();
        if(SystemClock.uptimeMillis()-f.observedAt>1500)f.targetVisible=false;return f;}}
    boolean handleMenu(){
        AccessibilityNodeInfo action;String chosen;
        synchronized(lock){if(menu==null)return false;action=AccessibilityNodeInfo.obtain(menu);chosen=menuLabel;}
        try{
            if(!action.refresh()||!action.isVisibleToUser())return false;
            if(!action.performAction(AccessibilityNodeInfo.ACTION_CLICK))return false;
            synchronized(lock){latest.paused=false;latest.depthAt=SystemClock.uptimeMillis();depthSamples.clear();latest.speed=Float.NaN;
                if(chosen.contains("ロングビット"))latest.tipReach=Math.min(34,latest.tipReach+8);
                if(chosen.contains("掘削を開始")||chosen.contains("もう一回")){
                    latest.tipReach=0;latest.depth=0;latest.depthAt=SystemClock.uptimeMillis();latest.gameOver=false;depthSamples.clear();
                }
                if(menu!=null)menu.recycle();menu=null;
            }
            return true;
        }finally{action.recycle();}
    }
    private static String nodeText(AccessibilityNodeInfo n){
        StringBuilder s=new StringBuilder(label(n));
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){try{s.append(' ').append(nodeText(c));}finally{c.recycle();}}}
        return s.toString().trim();
    }
    private static void collect(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out){
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){
            if(c.isVisibleToUser()){out.add(c);collect(c,out);}else c.recycle();
        }}
    }
    boolean pauseForCaptureFailure(){
        AccessibilityNodeInfo n;
        synchronized(lock){n=pauseControl==null?null:AccessibilityNodeInfo.obtain(pauseControl);}
        if(n==null)return false;
        try{return n.refresh()&&n.isVisibleToUser()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
        finally{n.recycle();}
    }
    int tapControl(int control)throws InterruptedException{
        AccessibilityNodeInfo active=root(false);
        if(active==null)return -5;
        boolean target=TARGET.equals(String.valueOf(active.getPackageName()));active.recycle();if(!target)return -5;
        Rect box;AccessibilityNodeInfo button;
        synchronized(lock){
            if(!latest.targetVisible||latest.paused||latest.gameOver)return -5;
            box=new Rect(controls[control]);button=controlNodes[control]==null?null:AccessibilityNodeInfo.obtain(controlNodes[control]);
        }
        // WebView's semantic click avoids a queued gesture hitting a moving or
        // resized control. Only fall back when the action itself was rejected.
        if(button!=null)try{
            if(button.refresh()&&button.isVisibleToUser()&&TARGET.equals(String.valueOf(button.getPackageName()))
                    &&button.performAction(AccessibilityNodeInfo.ACTION_CLICK))return 0;
        }finally{button.recycle();}
        return box.isEmpty()?-4:tap(box.centerX(),box.centerY());
    }
    int tap(int x,int y)throws InterruptedException{
        CountDownLatch done=new CountDownLatch(1);boolean[] ok={false};
        main.post(()->{
            AccessibilityNodeInfo r=root(false);if(r==null){done.countDown();return;}
            boolean target=TARGET.equals(String.valueOf(r.getPackageName()));r.recycle();if(!target){done.countDown();return;}
            Path p=new Path();p.moveTo(x,y);
            boolean dispatched=dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,20)).build(),new GestureResultCallback(){
                @Override public void onCompleted(GestureDescription g){ok[0]=true;done.countDown();}
                @Override public void onCancelled(GestureDescription g){done.countDown();}
            },main);
            if(!dispatched)done.countDown();
        });
        return done.await(500,TimeUnit.MILLISECONDS)&&ok[0]?0:-3;
    }
}
