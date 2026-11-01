package com.tanuki276.cosc;

/** Palette matches must also differ from the background beside the object. */
final class HazardContrast {
    static boolean distinct(int pixel,int left,int right){return delta(pixel,left)>=16&&delta(pixel,right)>=16;}
    private static int delta(int a,int b){
        return Math.max(Math.abs(((a>>16)&255)-((b>>16)&255)),
                Math.max(Math.abs(((a>>8)&255)-((b>>8)&255)),Math.abs((a&255)-(b&255))));
    }
}
