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
    private long lastTreeAt,controlsAt,observationFloor,hpAt,menuAt;
    private long pauseObservedAt,visualResumeAt;
    private String lastUiSummary="";
    private String pendingMenu="";
    private boolean pendingMenuPaused;
    private long pendingMenuAt;
    private long lastNodeTrace;
    private String menuLabel="";
    private HandlerThread workerThread,hudThread;
    private Handler hudWorker;
    private Handler worker;
    private volatile boolean eventQueued,treeRefreshQueued;
    private final ArrayDeque<double[]> depthSamples=new ArrayDeque<>();
    private static final Pattern HEAT=Pattern.compile("(GREEN|YELLOW|ORANGE|RED)\\s+(READY|[0-9]+%|[0-9.]+s)");
    private static final Pattern HP=Pattern.compile("^([0-8])/([1-8])$");
    private static final Pattern DEPTH=Pattern.compile("^\\s*([0-9,]+)m\\s*$");

    @Override protected void onServiceConnected(){
        if(Build.VERSION.SDK_INT>=33)setCacheEnabled(false);
        instance=this;
        workerThread=new HandlerThread("cosc-hud");workerThread.start();worker=new Handler(workerThread.getLooper());
        worker.post(poll);
        hudThread=new HandlerThread("cosc-live-hud");hudThread.start();hudWorker=new Handler(hudThread.getLooper());
        hudWorker.post(hudPoll);
    }
    private final Runnable poll=new Runnable(){public void run(){
        try{
            long now=SystemClock.uptimeMillis();
            if(now-lastTreeAt>=700){sampleTree();lastTreeAt=SystemClock.uptimeMillis();}

        }catch(Throwable ignored){}
        if(worker!=null)worker.postDelayed(this,70);
    }};
    private final Runnable hudPoll=new Runnable(){public void run(){
        try{refreshHud();}catch(Throwable ignored){}
        Handler h=hudWorker;if(h!=null)h.postDelayed(this,60);
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
                try{if(n.refresh()&&n.isVisibleToUser())parse(label(n),SystemClock.uptimeMillis());}finally{n.recycle();}
            }}finally{copy.recycle();eventQueued=false;}});
        }
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){
        instance=null;Handler w=worker;worker=null;if(w!=null)w.removeCallbacksAndMessages(null);
        if(workerThread!=null)workerThread.quitSafely();
        Handler h=hudWorker;hudWorker=null;if(h!=null)h.removeCallbacksAndMessages(null);
        if(hudThread!=null)hudThread.quitSafely();
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
            if(at<observationFloor)return;
            Matcher m=HEAT.matcher(text);
            if(m.matches()&&at>=latest.modeAt){
                String state=m.group(2);
                latest.mode=state.equals("READY")?GameFeedback.Mode.READY:
                        state.endsWith("%")?GameFeedback.Mode.ACTIVE:GameFeedback.Mode.COOLING;
                latest.modeAt=at;
                if(state.endsWith("s"))latest.cooldownSeconds=Float.parseFloat(state.substring(0,state.length()-1));
                latest.heat=state.endsWith("%")?Integer.parseInt(state.substring(0,state.length()-1)):
                        m.group(1).equals("GREEN")?21:m.group(1).equals("YELLOW")?47:m.group(1).equals("ORANGE")?71:99;
            }
            m=HP.matcher(text);if(m.matches()&&at>=hpAt){latest.hp=Integer.parseInt(m.group(1));hpAt=at;}
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
            if(isPauseHeading(text)&&at>=pauseObservedAt){latest.paused=true;pauseObservedAt=at;}
        }
    }
    private void refreshHud(){
        List<AccessibilityNodeInfo> snapshot=new ArrayList<>();
        synchronized(lock){for(AccessibilityNodeInfo n:hudNodes)snapshot.add(AccessibilityNodeInfo.obtain(n));}
        boolean modeRead=false;
        try{for(AccessibilityNodeInfo n:snapshot){
            long before=SystemClock.uptimeMillis();
            if(n.refresh()&&n.isVisibleToUser()){
                String text=label(n);parse(text,(before+SystemClock.uptimeMillis())/2);
                if(HEAT.matcher(text).matches())modeRead=true;
            }
        }}finally{for(AccessibilityNodeInfo n:snapshot)n.recycle();}
        // WebView can retire virtual node identities on a DOM update. Reacquire
        // the HUD promptly instead of waiting through the 700 ms tree period.
        boolean live;
        synchronized(lock){live=latest.targetVisible&&!latest.paused&&!latest.gameOver&&latest.mode!=GameFeedback.Mode.UNKNOWN;}
        if(!modeRead&&live)requestFreshTree();
    }
    void requestFreshTree(){
        Handler w=worker;if(w==null||treeRefreshQueued)return;
        treeRefreshQueued=true;
        w.postDelayed(()->{
            try{if(worker!=null&&SystemClock.uptimeMillis()-lastTreeAt>=140){sampleTree();lastTreeAt=SystemClock.uptimeMillis();}}
            finally{treeRefreshQueued=false;}
        },Math.max(0,150-(SystemClock.uptimeMillis()-lastTreeAt)));
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
            synchronized(lock){
                if(requested<visualResumeAt)return;
                for(AccessibilityNodeInfo n:hudNodes)n.recycle();hudNodes.clear();
            }
            AccessibilityNodeInfo best=null;int score=0;String bestLabel="";boolean overlay=false;
            Rect[] found={new Rect(),new Rect(),new Rect()};Rect viewport=new Rect();
            AccessibilityNodeInfo[] buttons=new AccessibilityNodeInfo[3];
            for(AccessibilityNodeInfo n:nodes){
                String text=label(n);parse(text,sampledAt);
                if(isPauseHeading(text))overlay=true;
                if(HEAT.matcher(text).matches()||DEPTH.matcher(text).matches()||HP.matcher(text).matches())
                    synchronized(lock){hudNodes.add(AccessibilityNodeInfo.obtain(n));}
                String id=String.valueOf(n.getViewIdResourceName());
                boolean isButton=n.isClickable()&&"android.widget.Button".equals(String.valueOf(n.getClassName()));
                if("android.webkit.WebView".equals(String.valueOf(n.getClassName())))n.getBoundsInScreen(viewport);
                if(isButton&&(id.endsWith("leftControl")||text.equals("←"))){n.getBoundsInScreen(found[0]);buttons[0]=n;}
                if(isButton&&(id.endsWith("drillControl")||(text.contains("DRILL")||nodeText(n).startsWith("DRILL")))){n.getBoundsInScreen(found[1]);buttons[1]=n;}
                if(isButton&&(id.endsWith("rightControl")||text.equals("→"))){n.getBoundsInScreen(found[2]);buttons[2]=n;}
                if(isButton&&(id.endsWith("pauseBtn")||text.equals("Ⅱ"))){
                    synchronized(lock){if(pauseControl!=null)pauseControl.recycle();pauseControl=AccessibilityNodeInfo.obtain(n);}
                }
                if(isButton&&now-lastNodeTrace>5000){
                    Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                    RunTrace.event("BUTTON label="+nodeText(n)+" class="+n.getClassName()+" bounds="+bounds);
                }
                if(!isButton&&!n.isClickable()&&(n.getActions()&AccessibilityNodeInfo.ACTION_CLICK)==0)continue;
                if("android.webkit.WebView".equals(String.valueOf(n.getClassName())))continue;
                String button=nodeText(n);int s=menuScore(button);
                if(s>score&&isMenuAction(n,button)){best=n;score=s;bestLabel=button;}
            }
            if(now-lastNodeTrace>5000)lastNodeTrace=now;
            boolean allControls=!found[0].isEmpty()&&!found[1].isEmpty()&&!found[2].isEmpty();
            boolean anyControls=!found[0].isEmpty()||!found[1].isEmpty()||!found[2].isEmpty();
            synchronized(lock){
                // This tree may have started before visual resume and finished
                // a second later. It cannot reinstate the dismissed modal.
                if(requested<visualResumeAt)return;
                boolean wasPaused=latest.paused;
                if(menu!=null)menu.recycle();menu=best==null?null:AccessibilityNodeInfo.obtain(best);menuLabel=bestLabel;menuAt=now;
                if(sampledAt>=pauseObservedAt){
                    latest.paused=overlay||(best!=null&&!menuLabel.contains("x2 ON"))||!anyControls;
                }
                if(!pendingMenu.isEmpty()&&sampledAt>pendingMenuAt){
                    if((allControls&&!overlay)||(best!=null&&!menuLabel.equals(pendingMenu))){
                        if(pendingMenu.contains("ロングビット"))latest.tipReach=Math.min(34,latest.tipReach+8);
                        if(pendingMenuPaused&&!latest.paused){latest.resumeAt=pendingMenuAt;depthSamples.clear();}
                        RunTrace.event("MENU_CONFIRMED label="+pendingMenu);
                        pendingMenu="";latest.menuTapAt=0;
                    }else if(now-pendingMenuAt>1200){
                        RunTrace.event("MENU_NOT_REFLECTED label="+pendingMenu);pendingMenu="";latest.menuTapAt=0;
                    }
                }
                if(wasPaused&&!latest.paused&&latest.resumeAt<pauseObservedAt){latest.resumeAt=sampledAt;depthSamples.clear();}
                String ui="paused="+latest.paused+" overlay="+overlay+" controls="+allControls+" choice="+bestLabel;
                if(!ui.equals(lastUiSummary)){RunTrace.event("UI "+ui);lastUiSummary=ui;}
                if(!viewport.isEmpty()){latest.viewLeft=viewport.left;latest.viewTop=viewport.top;
                    latest.viewWidth=viewport.width();latest.viewHeight=viewport.height();}
                if(!found[0].isEmpty()&&!found[1].isEmpty()&&!found[2].isEmpty()){
                    for(int i=0;i<3;i++){
                        controls[i].set(found[i]);
                        if(controlNodes[i]!=null)controlNodes[i].recycle();
                        controlNodes[i]=AccessibilityNodeInfo.obtain(buttons[i]);
                    }
                    controlsAt=SystemClock.uptimeMillis();
                    float span=found[2].exactCenterX()-found[0].exactCenterX();
                    float width=viewport.isEmpty()?latest.viewWidth:viewport.width();
                    // AX expands button hit rectangles, shrinking the reported
                    // gap. Centres are invariant. For the 340 CSS-px group,
                    // flex 1:2:1 + padding/borders gives a 253.5 CSS-px span.
                    float calibrated=width>0&&span/width<.65f?span/253.5f:getResources().getDisplayMetrics().density;
                    if(calibrated>=.5f&&calibrated<=6)latest.cssScale=calibrated;
                }
            }
        }finally{for(AccessibilityNodeInfo n:nodes)n.recycle();r.recycle();}
    }
    private static boolean isPauseHeading(String text){
        return text.equals("DRILL UPGRADE")||text.equals("DRILL BROKEN")||text.equals("SYSTEM STANDBY")
                ||text.equals("ひと休み。");
    }
    private boolean isMenuAction(AccessibilityNodeInfo n,String text){
        String cls=String.valueOf(n.getClassName());
        if(!n.isVisibleToUser()||!n.isEnabled()||cls.equals("android.webkit.WebView"))return false;
        boolean actionable=cls.equals("android.widget.Button")||n.isClickable()
                ||(n.getActions()&AccessibilityNodeInfo.ACTION_CLICK)!=0;
        if(!actionable)return false;
        int names=0;
        for(String name:new String[]{"高トルクモーター","高速クラッチ","冷却フィン","ロングビット","鉱石粉砕ヘッド",
                "増設フレーム","ポイント磁石","現地修理","補給パック","掘削を開始","もう一回","掘削を再開","x2 ON"})
            if(text.contains(name))names++;
        if(names!=1)return false;
        Rect box=new Rect();n.getBoundsInScreen(box);
        int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
        return !box.isEmpty()&&box.width()<=width&&box.height()<height*.5f;
    }
    private int menuScore(String t){
        synchronized(lock){if(latest.hp>=0&&latest.hp<=2&&t.contains("現地修理"))return 150;}
        return t.contains("x2 ON")?10:t.contains("掘削を開始")||t.contains("もう一回")||t.contains("掘削を再開")?200:
                t.contains("冷却フィン")?100:t.contains("高速クラッチ")?95:
                t.contains("現地修理")?90:t.contains("増設フレーム")?80:t.contains("ロングビット")?70:
                t.contains("補給パック")?60:t.contains("高トルクモーター")?50:t.contains("鉱石粉砕ヘッド")?40:
                t.contains("ポイント磁石")?30:0;
    }
    GameFeedback read(){synchronized(lock){GameFeedback f=latest.copy();
        if(SystemClock.uptimeMillis()-f.observedAt>1500)f.targetVisible=false;return f;}}
    // Clear only observations that can belong to an earlier run. Geometry is
    // retained, but fresh HUD readings are required before the first toggle.
    void beginRun(){synchronized(lock){
        observationFloor=SystemClock.uptimeMillis();hpAt=0;
        latest.mode=GameFeedback.Mode.UNKNOWN;latest.modeAt=0;latest.depthAt=0;latest.heat=-1;
        latest.hp=-1;latest.depth=0;latest.gameOver=false;latest.tipReach=0;
        latest.speed=Float.NaN;depthSamples.clear();pendingMenu="";latest.resumeAt=0;latest.menuTapAt=0;visualResumeAt=0;
    }}
    boolean handleMenu(){
        AccessibilityNodeInfo action;String chosen;boolean wasPaused;
        synchronized(lock){
            if(menu==null||!pendingMenu.isEmpty()||SystemClock.uptimeMillis()-menuAt>1200)return false;
            action=AccessibilityNodeInfo.obtain(menu);chosen=menuLabel;wasPaused=latest.paused;
        }
        try{
            // Use the recently observed concrete menu bounds. WebView retires
            // node identities, so refresh() must not discard a valid choice.
            Rect box=new Rect();action.getBoundsInScreen(box);if(box.isEmpty())return false;
            int rc=tap(box.centerX(),box.centerY());
            RunTrace.event("MENU_TOUCH label="+chosen+" bounds="+box+" rc="+rc);
            if(rc<0)return false;
            synchronized(lock){
                pendingMenu=chosen;pendingMenuAt=SystemClock.uptimeMillis();pendingMenuPaused=wasPaused;
                latest.menuTapAt=wasPaused&&rc==0?pendingMenuAt:0;
                if(rc==0&&(chosen.contains("掘削を開始")||chosen.contains("もう一回"))){
                    observationFloor=SystemClock.uptimeMillis();hpAt=0;
                    latest.runId++;latest.depthAt=0;latest.depth=0;latest.tipReach=0;latest.gameOver=false;
                    latest.mode=GameFeedback.Mode.UNKNOWN;latest.modeAt=0;latest.hp=-1;
                    latest.speed=Float.NaN;depthSamples.clear();
                }
            }
            if(wasPaused&&rc==0){
                // Keep the modal gate closed until live controls are observed.
                Handler h=hudWorker;if(h!=null)h.post(this::refreshHud);
                Handler w=worker;if(w!=null)w.postDelayed(this::sampleTree,80);
            }
            // Acceleration is a live HUD button. Touching it must not erase
            // obstacle tracks, lane acknowledgement, or drill acknowledgement.
            return wasPaused;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        finally{action.recycle();}
    }
    boolean confirmVisualResume(long capturedAt,int lane){
        synchronized(lock){
            long now=SystemClock.uptimeMillis();
            if(!latest.paused||latest.gameOver||!latest.targetVisible||lane<0||lane>2
                    ||latest.menuTapAt==0||capturedAt<latest.menuTapAt+32||now-capturedAt>250
                    ||pendingMenu.isEmpty()||!pendingMenuPaused)return false;
            if(pendingMenu.contains("ロングビット"))latest.tipReach=Math.min(34,latest.tipReach+8);
            latest.paused=false;latest.resumeAt=latest.menuTapAt;latest.menuTapAt=0;
            visualResumeAt=now;pauseObservedAt=now;depthSamples.clear();
            RunTrace.event("MENU_VISUAL_CONFIRMED label="+pendingMenu+" capture="+capturedAt+" lane="+lane);
            pendingMenu="";menuLabel="";menuAt=0;
            if(menu!=null){menu.recycle();menu=null;}
            Handler w=worker;if(w!=null)w.postDelayed(()->{if(worker!=null){sampleTree();lastTreeAt=SystemClock.uptimeMillis();}},80);
            return true;
        }
    }
    private static String nodeText(AccessibilityNodeInfo n){
        StringBuilder s=new StringBuilder(label(n));
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){try{s.append(' ').append(nodeText(c));}finally{c.recycle();}}}
        return s.toString().trim();
    }
    private static void collect(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out){
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){
            if(c.isVisibleToUser()){out.add(c);collect(c,out);}
            else {try{collect(c,out);}finally{c.recycle();}}
        }}
    }
    boolean pauseForCaptureFailure(){
        AccessibilityNodeInfo n;
        synchronized(lock){n=pauseControl==null?null:AccessibilityNodeInfo.obtain(pauseControl);}
        if(n==null)return false;
        try{
            if(!n.refresh()||!n.isVisibleToUser())return false;
            Rect box=new Rect();n.getBoundsInScreen(box);if(box.isEmpty())return false;
            Path p=new Path();p.moveTo(box.centerX(),box.centerY());
            return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,40)).build(),null,main);
        }
        finally{n.recycle();}
    }
    int tapControl(int control)throws InterruptedException{
        return tapControl(control,SystemClock.uptimeMillis()+180);
    }
    int tapControl(int control,long deadline)throws InterruptedException{
        Rect box;
        synchronized(lock){
            if(!latest.targetVisible||latest.paused||latest.gameOver)return -5;
            long now=SystemClock.uptimeMillis();
            boolean justResumed=visualResumeAt>controlsAt&&now-visualResumeAt<1500;
            if(now-controlsAt>2000&&!justResumed)return -4;
            box=new Rect(controls[control]);
        }
        // Rectangles come from a recent concrete-button tree sample. Virtual
        // AX node identities may expire on every DOM update; do not drop a shot
        // just because a cached node cannot refresh. tap() rechecks foreground.
        int rc=box.isEmpty()?-4:tap(box.centerX(),box.centerY(),deadline);
        RunTrace.event("CONTROL_TOUCH index="+control+" bounds="+box+" rc="+rc);
        return rc;
    }
    // 0 = gesture completed; 1 = dispatched, completion still unknown.
    // A timeout before dispatch expires the queued request permanently.
    int tap(int x,int y)throws InterruptedException{
        return tap(x,y,SystemClock.uptimeMillis()+500);
    }
    private int tap(int x,int y,long deadline)throws InterruptedException{
        if(SystemClock.uptimeMillis()>=deadline)return -3;
        CountDownLatch done=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger state=new java.util.concurrent.atomic.AtomicInteger(0);
        Runnable request=()->{
            if(state.get()!=0||SystemClock.uptimeMillis()>=deadline){done.countDown();return;}
            AccessibilityNodeInfo r=root(false);
            boolean target=r!=null&&TARGET.equals(String.valueOf(r.getPackageName()));
            if(r!=null)r.recycle();
            if(!target){state.compareAndSet(0,3);done.countDown();return;}
            // The root query may itself outlive the caller's deadline.
            if(SystemClock.uptimeMillis()>=deadline||!state.compareAndSet(0,1)){done.countDown();return;}
            try{
                Path p=new Path();p.moveTo(x,y);
                boolean dispatched=dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p,0,40)).build(),new GestureResultCallback(){
                    @Override public void onCompleted(GestureDescription g){state.set(2);done.countDown();}
                    @Override public void onCancelled(GestureDescription g){state.set(3);done.countDown();}
                },main);
                if(!dispatched){state.set(3);done.countDown();}
            }catch(RuntimeException failure){state.set(3);done.countDown();RunTrace.event("TOUCH_ERROR "+failure);}
        };
        main.post(request);
        try{done.await(Math.max(1,deadline-SystemClock.uptimeMillis()+60),TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        state.compareAndSet(0,4);main.removeCallbacks(request);
        int result=state.get();
        if(result==1)RunTrace.event("TOUCH_COMPLETION_UNKNOWN x="+x+" y="+y);
        return result==2?0:result==1?1:-3;
    }
}
