package com.tanuki276.cosc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, URI-granted access to immutable diagnostic exports only. */
public final class TraceProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(uri.getPathSegments().size()!=1 || name==null || !name.matches("cosc-[0-9]+-[0-9]+\\.txt"))
            throw new FileNotFoundException("Invalid export");
        File f=new File(new File(getContext().getCacheDir(),"reports"),name);
        if(!f.isFile())throw new FileNotFoundException("Export no longer available");
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);
        try {
            File f=file(uri);Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++) {
                if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=f.getName();
                else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=f.length();
            }
            cursor.addRow(values);
        } catch(FileNotFoundException ignored) { }
        return cursor;
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException("Read only"); }
}
