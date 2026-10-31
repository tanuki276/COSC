package android.graphics;
public final class Bitmap {
    private final int w,h; private final int[] data;
    public Bitmap(int w,int h,int[] data) {this.w=w;this.h=h;this.data=data;}
    public int getWidth(){return w;} public int getHeight(){return h;}
    public int getPixel(int x,int y){return data[y*w+x];}
}
