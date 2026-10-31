package android.graphics;
public final class Bitmap {
    private final int w,h; private final int[] data;
    public Bitmap(int w,int h,int[] data) {this.w=w;this.h=h;this.data=data;}
    public int getWidth(){return w;} public int getHeight(){return h;}
    public void getPixels(int[] dst,int offset,int stride,int x,int y,int width,int height){
        for(int row=0;row<height;row++)System.arraycopy(data,(y+row)*w+x,dst,offset+row*stride,width);
    }
    public int getPixel(int x,int y){return data[y*w+x];}
}
