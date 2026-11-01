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
        try{
            if(file.exists()&&file.length()>0){
                File previous=new File(c.getFilesDir(),"previous-run.txt");
                java.nio.file.Files.copy(file.toPath(),previous.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            writer=new BufferedWriter(new FileWriter(file,false));
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
                long start=Math.max(0,r.length()-16_000);r.seek(start);byte[] b=new byte[(int)(r.length()-start)];r.readFully(b);
                return "COSC diagnostics\n"+new String(b,java.nio.charset.StandardCharsets.UTF_8);
            }
        }catch(IOException e){return "Log unavailable: "+e.getMessage();}
    }
    static synchronized File snapshot(Context c) throws IOException {
        if(writer!=null)writer.flush();
        File dir=new File(c.getCacheDir(),"reports");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create report directory");
        File[] old=dir.listFiles();
        if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>7L*24*60*60*1000)f.delete();
        File out=new File(dir,"cosc-"+System.currentTimeMillis()+"-"+SystemClock.uptimeMillis()+".txt");
        try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(out))){
            stream.write(("COSC diagnostics export "+BuildConfig.VERSION_NAME+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            boolean any=false;
            for(String name:new String[]{"previous-run.txt","last-run.txt"}){
                File src=new File(c.getFilesDir(),name);if(!src.isFile())continue;
                stream.write(("\n--- "+name+" ---\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.copy(src.toPath(),stream);any=true;
            }
            if(!any)stream.write("No run recorded.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }
    static synchronized void close(){try{if(writer!=null)writer.close();}catch(IOException ignored){}writer=null;}
}
