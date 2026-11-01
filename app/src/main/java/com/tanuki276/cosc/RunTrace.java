package com.tanuki276.cosc;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;

/** Local bounded flight recorder. Export is an explicit user action. */
final class RunTrace {
    private static File file;
    private static BufferedWriter writer;
    private static long flushed;
    static synchronized void start(Context c){
        close();file=new File(c.getFilesDir(),"last-run.txt");
        try{writer=new BufferedWriter(new FileWriter(file,false));
            event("COSC "+BuildConfig.VERSION_NAME+" SDK="+Build.VERSION.SDK_INT+" device="+Build.MANUFACTURER+" "+Build.MODEL);
        }catch(IOException ignored){}
    }
    static synchronized void event(String text){
        if(writer==null)return;
        try{
            writer.write(SystemClock.uptimeMillis()+" "+text.replace('\n',' ')+"\n");
            long now=SystemClock.uptimeMillis();
            if(now-flushed>1000){writer.flush();flushed=now;
                if(file.length()>4_000_000){writer.close();File previous=new File(file.getParentFile(),"previous-run.txt");
                    if(previous.exists())previous.delete();file.renameTo(previous);writer=new BufferedWriter(new FileWriter(file,false));}
            }
        }catch(IOException ignored){}
    }
    static synchronized String report(Context c){
        try{if(writer!=null)writer.flush();File f=new File(c.getFilesDir(),"last-run.txt");
            if(!f.exists())return "No run recorded.";
            try(RandomAccessFile r=new RandomAccessFile(f,"r")){
                long start=Math.max(0,r.length()-180_000);r.seek(start);byte[] b=new byte[(int)(r.length()-start)];r.readFully(b);
                return "COSC diagnostics\n"+new String(b,java.nio.charset.StandardCharsets.UTF_8);
            }
        }catch(IOException e){return "Log unavailable: "+e.getMessage();}
    }
    static synchronized void close(){try{if(writer!=null)writer.close();}catch(IOException ignored){}writer=null;}
}
