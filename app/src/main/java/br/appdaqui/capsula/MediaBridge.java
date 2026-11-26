package br.appdaqui.capsula;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class MediaBridge extends ContentProvider {
    static Uri uri(String name) { return Uri.parse("content://br.appdaqui.capsula.capture/" + name); }
    static File file(Context c, String name) { return new File(c.getCacheDir(), "daqui-" + name); }
    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.matches("(captura|aberto)-[0-9a-f-]{36}\\.(jpg|mp4|m4a)"))
            throw new IllegalArgumentException();
        return file(getContext(), name);
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        String name = resolve(uri).getName();
        return name.endsWith(".jpg") ? "image/jpeg" : name.endsWith(".mp4") ? "video/mp4" : "audio/mp4";
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file.getName().startsWith("daqui-aberto-") && !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file = resolve(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? file.getName() : OpenableColumns.SIZE.equals(column) ? file.length() : null);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
