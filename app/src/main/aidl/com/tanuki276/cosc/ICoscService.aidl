package com.tanuki276.cosc;

interface ICoscService {
    void destroy() = 16777114;
    String ping() = 1;
    byte[] captureJpeg(int maxWidth, int quality) = 2;
    String displayInfo() = 3;
    int keyEvent(int keyCode) = 4;
    int tap(int x, int y) = 5;
    int launchTarget() = 6;
}
