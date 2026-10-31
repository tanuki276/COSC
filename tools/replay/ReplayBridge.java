package com.tanuki276.cosc;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.*;
import java.util.Base64;
public final class ReplayBridge {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        GameAnalyzer a = new GameAnalyzer();
        String line;
        while ((line=in.readLine())!=null) {
            String[] p=line.split(" ",11);
            if(p[0].equals("RESET")){a.reset();System.out.println("OK");continue;}
            int w=Integer.parseInt(p[0]),h=Integer.parseInt(p[1]),lane=Integer.parseInt(p[2]);
            GameFeedback f = new GameFeedback(); f.targetVisible=true;
            f.mode=GameFeedback.Mode.valueOf(p[3]); f.heat=Integer.parseInt(p[4]);
            f.depth=Integer.parseInt(p[5]); f.tipReach=Integer.parseInt(p[6]);
            long now=Long.parseLong(p[7]); f.observedAt=now;
            float scale=Float.parseFloat(p[8]);
            f.cooldownSeconds=Float.parseFloat(p[10]);
            byte[] packed=Base64.getDecoder().decode(p[9]);
            byte[] bytes=new java.util.zip.InflaterInputStream(new ByteArrayInputStream(packed)).readAllBytes();
            IntBuffer pixels=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int[] data=new int[w*h];pixels.get(data);
            GameAnalyzer.Decision d=a.analyze(new Bitmap(w,h,data),lane,scale,f,now);
            System.out.println(d.type+" "+a.observedLane+" "+d.reason);
        }
    }
}
