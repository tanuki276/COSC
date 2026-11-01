package com.tanuki276.cosc;

/** Do not send another toggle until the game acknowledges the previous one. */
final class ControlTiming {
    private GameAnalyzer.Type awaiting;
    private long sentAt;
    private float latencyMs = 120;
    float latencyMs() { return latencyMs; }
    void reset() { awaiting=null;sentAt=0;latencyMs=120; }
    boolean allow(GameAnalyzer.Type type, GameFeedback f, long now) {
        if(type==GameAnalyzer.Type.LEFT||type==GameAnalyzer.Type.RIGHT)return true;
        if (awaiting != null) {
            long sampleAt=f.modeAt;
            boolean acknowledged=sampleAt >= sentAt && (awaiting == GameAnalyzer.Type.DRILL
                    ? f.mode == GameFeedback.Mode.ACTIVE
                    : (f.mode == GameFeedback.Mode.READY || f.mode == GameFeedback.Mode.COOLING));
            if (acknowledged) {
                if (awaiting == GameAnalyzer.Type.DRILL) {
                    float delay=Math.max(25, Math.min(350, sampleAt-sentAt));
                    latencyMs=latencyMs*.65f+delay*.35f;
                }
                awaiting=null;
            } else if (now-sentAt < Math.max(700, latencyMs*3)) return false;
            else awaiting=null;
        }
        return (type != GameAnalyzer.Type.DRILL && type != GameAnalyzer.Type.CANCEL) || now-sentAt >= 80;
    }
    void accepted(GameAnalyzer.Type type,long requestedAt) {
        if(type==GameAnalyzer.Type.DRILL||type==GameAnalyzer.Type.CANCEL){awaiting=type;sentAt=requestedAt;}
    }
}
