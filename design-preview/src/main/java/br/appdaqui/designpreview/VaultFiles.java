package br.appdaqui.designpreview;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class VaultFiles {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final Context context;

    VaultFiles(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "allembra-vault.json"));
    }

    String read() throws IOException {
        synchronized (LOCK) {
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return "";
            try (InputStream input = file.openRead()) { return readUtf8(input); }
        }
    }

    void write(String body) throws IOException {
        if (body == null || body.length() > MAX_BYTES || !body.startsWith("{\"version\":1,"))
            throw new IOException("Arquivo de cofre inválido.");
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("Arquivo de cofre excede o limite.");
        synchronized (LOCK) {
            FileOutputStream output = file.startWrite();
            try {
                output.write(encoded);
                file.finishWrite(output);
            } catch (IOException error) {
                file.failWrite(output);
                throw error;
            }
        }
    }

    String importBackup(Uri source) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source)) { return readUtf8(input); }
    }

    void exportBackup(Uri destination) throws IOException {
        InputStream snapshot;
        synchronized (LOCK) { snapshot = file.openRead(); }
        try (InputStream input = snapshot;
             OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("Destino indisponível.");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        if (input == null) throw new IOException("Arquivo indisponível.");
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (content.size() > MAX_BYTES - count) throw new IOException("Arquivo excede o limite.");
            content.write(buffer, 0, count);
        }
        return new String(content.toByteArray(), StandardCharsets.UTF_8);
    }
}
