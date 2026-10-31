package com.tanuki276.cosc;

/** Observations from visible WebView accessibility text, never game memory. */
final class GameFeedback {
    enum Mode { UNKNOWN, READY, ACTIVE, COOLING, MENU }
    Mode mode = Mode.UNKNOWN;
    int heat = -1;
    float cooldownSeconds = 1.1f;
    int hp = -1;
    int depth;
    int tipReach;
    long observedAt;
    boolean targetVisible;
}
