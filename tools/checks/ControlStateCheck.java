package com.tanuki276.cosc;

/** Command/HUD state checks only; no game update, replay or Android execution. */
public final class ControlStateCheck {
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    private static GameFeedback hud(GameFeedback.Mode mode,long at){GameFeedback f=new GameFeedback();f.mode=mode;f.modeAt=at;return f;}
    public static void main(String[] args){
        ControlTiming t=new ControlTiming();
        t.accepted(GameAnalyzer.Type.DRILL,1000);
        check(t.allow(GameAnalyzer.Type.DRILL,hud(GameFeedback.Mode.COOLING,1250),1250),"cooling acknowledges a pulse whose ACTIVE sample was missed");
        t.reset();t.accepted(GameAnalyzer.Type.DRILL,1000);
        check(!t.allow(GameAnalyzer.Type.DRILL,hud(GameFeedback.Mode.UNKNOWN,1250),1250),"unknown must not acknowledge a toggle");
        check(t.allow(GameAnalyzer.Type.RIGHT,hud(GameFeedback.Mode.UNKNOWN,1250),1250),"escape allowed while awaiting HUD");
        t.reset();t.accepted(GameAnalyzer.Type.CANCEL,1000);
        check(!t.allow(GameAnalyzer.Type.CANCEL,hud(GameFeedback.Mode.ACTIVE,1250),1250),"active must not acknowledge cancellation");
        check(t.allow(GameAnalyzer.Type.DRILL,hud(GameFeedback.Mode.READY,1300),1300),"ready acknowledges cancellation");
        t.reset();t.accepted(GameAnalyzer.Type.DRILL,1000);
        check(!t.allow(GameAnalyzer.Type.DRILL,hud(GameFeedback.Mode.COOLING,990),1250),"pre-command cooldown cannot acknowledge");
        check(GameAnalyzer.blend(0x29272e,0xffd166,.15f)==0x494136,"selected-cell composite rock colour");
        System.out.println("Control acknowledgement and composite-colour checks passed");
    }
}
