package com.tanuki276.cosc;

/** Timestamped observations of the visible HUD. No game memory is read. */
final class GameFeedback {
    enum Mode { UNKNOWN, READY, ACTIVE, COOLING, MENU }
    Mode mode = Mode.UNKNOWN;
    int heat = -1, hp = -1, depth, tipReach;
    float cooldownSeconds = 1.1f;
    long observedAt, depthAt, modeAt, frameAt, lastMoveAt;
    boolean targetVisible, paused, gameOver;
    float frameDepth = Float.NaN, controlDepth = Float.NaN, speed = Float.NaN;
    float cssScale;
    int viewLeft, viewTop, viewWidth, viewHeight;
    float inputLatencyMs = 120, framePeriodMs = 83;

    static float nominalSpeed(float depth) {
        return (float)Math.min(78, 16 + 62 * Math.log1p(Math.max(0, depth) / 5000.0) / Math.log1p(20));
    }
    float speed() { return Float.isNaN(speed) ? nominalSpeed(depth) : speed; }
    float imageDepth() { return Float.isNaN(frameDepth) ? depth : frameDepth; }
    float decisionDepth() { return Float.isNaN(controlDepth) ? depth : controlDepth; }
    GameFeedback copy() {
        GameFeedback f = new GameFeedback();
        f.mode=mode;f.heat=heat;f.hp=hp;f.depth=depth;f.tipReach=tipReach;
        f.cooldownSeconds=cooldownSeconds;f.observedAt=observedAt;f.depthAt=depthAt;f.modeAt=modeAt;
        f.frameAt=frameAt;f.lastMoveAt=lastMoveAt;f.targetVisible=targetVisible;f.paused=paused;f.gameOver=gameOver;
        f.frameDepth=frameDepth;f.controlDepth=controlDepth;f.speed=speed;f.cssScale=cssScale;
        f.viewLeft=viewLeft;f.viewTop=viewTop;f.viewWidth=viewWidth;f.viewHeight=viewHeight;
        f.inputLatencyMs=inputLatencyMs;f.framePeriodMs=framePeriodMs;
        return f;
    }
    void atFrame(long capturedAt, long now) {
        frameAt=capturedAt;
        if (depthAt > 0 && !paused) {
            frameDepth=Math.max(0, depth + speed() * Math.max(-1.0f, Math.min(.8f, (capturedAt-depthAt)/1000f)));
            controlDepth=Math.max(0, depth + speed() * Math.max(-.2f, Math.min(.8f, (now-depthAt)/1000f)));
        }
    }
}
