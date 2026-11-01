package com.tanuki276.cosc;

/** Arithmetic checks on occupancy intervals, not a game replay or emulator. */
public final class LaneSafetyCheck {
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        check(!LaneSafety.clear(new float[]{30},100,0,.8f),"crossing before endpoint must block");
        check(LaneSafety.clear(new float[]{-30},100,0,.8f),"passed rock must not block");
        check(!LaneSafety.clear(new float[]{22},100,0,0),"body boundary is occupied");
        check(!LaneSafety.clear(new float[]{120},100,.8f,1.2f),"second row crossing must block");
        check(LaneSafety.choose(new float[][]{{40},{45},{}},1,100,.12f,.1f)==2,"choose open adjacent lane");
        check(LaneSafety.choose(new float[][]{{45},{20},{}},0,100,.12f,.1f)==0,"cannot jump over occupied middle lane");
        check(LaneSafety.choose(new float[][]{{},{},{}},1,100,.12f,.1f)==1,"stay when clear");
        check(LaneSafety.choose(new float[][]{{},{30},{}},1,100,.15f,.1f)==1,"do not claim escape before input can arrive");
        System.out.println("LaneSafety interval checks passed");
    }
}
