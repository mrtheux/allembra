package br.appdaqui.designpreview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

public final class ShareFiles extends ContentProvider {
    private static final String VALID_NAME = "allembra-(carta|chave|backup|midia)-[a-f0-9-]{36}\\.(png|json|jpg|mp4|m4a|webm|ogg)";

    private static final String CAPTURE_NAME = "allembra-capture-[a-f0-9-]{36}\\.(jpg|mp4)";

    static File create(Context context, String kind, String extension) throws IOException {
        File directory = new File(context.getCacheDir(), "allembra-share");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache indisponível.");
        File[] old = directory.listFiles();
        if (old != null) for (File file : old)
            if (System.currentTimeMillis() - file.lastModified() > 24 * 3600000L) file.delete();
        return new File(directory, "allembra-" + kind + "-" + UUID.randomUUID() + "." + extension);
    }

    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".share")
            .appendPath(file.getName()).build();
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || uri.getPathSegments().size() != 1 || !(name.matches(VALID_NAME) || name.matches(CAPTURE_NAME))) return null;
        return new File(new File(getContext().getCacheDir(), "allembra-share"), name);
    }

    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException();
        if (file.getName().matches(CAPTURE_NAME))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        if (!"r".equals(mode) || !file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        File file = resolve(uri);
        if (file == null) return null;
        String name = file.getName();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".ogg")) return "audio/ogg";
        return "application/json";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File file = resolve(uri);
        String[] fields = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(fields);
        if (file == null || !file.exists()) return cursor;
        Object[] row = new Object[fields.length];
        for (int i=0;i<fields.length;i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(fields[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(fields[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
