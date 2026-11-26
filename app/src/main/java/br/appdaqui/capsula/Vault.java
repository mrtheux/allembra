package br.appdaqui.capsula;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class Vault {
    static final int MAX_MEDIA = 12 * 1024 * 1024;
    static final int MAX_RECORD = MAX_MEDIA + 128 * 1024;
    static final SecureRandom RANDOM = new SecureRandom();
    final File dir;
    Vault(Context c) {
        dir = new File(c.getNoBackupFilesDir(), "registros");
        dir.mkdirs();
    }

    static final class Entry {
        final JSONObject meta;
        final byte[] media;
        Entry(JSONObject meta, byte[] media) { this.meta = meta; this.media = media; }
        String id() { return meta.optString("id"); }
        long opens() { return meta.optLong("opens"); }

        byte[] pack() {
            byte[] header = meta.toString().getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(4 + header.length + media.length).putInt(header.length).put(header).put(media).array();
        }
        static Entry unpack(byte[] bytes) throws Exception {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            int length = input.getInt();
            if (length < 2 || length > 64 * 1024 || length > input.remaining()) throw new IOException();
            byte[] header = new byte[length];
            input.get(header);
            byte[] media = new byte[input.remaining()];
            if (media.length > MAX_MEDIA) throw new IOException();
            input.get(media);
            JSONObject meta = new JSONObject(new String(header, StandardCharsets.UTF_8));
            UUID.fromString(meta.getString("id"));
            String type = meta.getString("type");
            if (!Arrays.asList("Foto","Vídeo","Texto","Pensamento","Áudio").contains(type)
                    || meta.getLong("created") <= 0 || meta.getLong("opens") <= meta.getLong("created")
                    || meta.getString("text").length() > 4000 || meta.getString("group").length() > 60) throw new IOException();
            return new Entry(meta, media);
        }
    }

    synchronized SecretKey localKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Key key = store.getKey("daqui-local-v1", null);
        if (key != null) return (SecretKey) key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("daqui-local-v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }

    static byte[] seal(byte[] data, SecretKey key, String binding) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(data);
        return ByteBuffer.allocate(13 + encrypted.length).put((byte) 1).put(cipher.getIV()).put(encrypted).array();
    }

    static byte[] open(byte[] sealed, SecretKey key, String binding) throws Exception {
        if (sealed.length < 29 || sealed[0] != 1) throw new IOException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 1, 12));
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(sealed, 13, sealed.length - 13);
    }

    static byte[] read(InputStream input, int max) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > max - data.size()) throw new IOException("Arquivo acima do limite.");
                data.write(buffer, 0, count);
            }
            return data.toByteArray();
        }
    }

    File file(String id) { return new File(dir, UUID.fromString(id).toString() + ".caps"); }

    synchronized void save(Entry entry) throws Exception {
        byte[] sealed = seal(entry.pack(), localKey(), entry.id());
        AtomicFile output = new AtomicFile(file(entry.id()));
        FileOutputStream stream = output.startWrite();
        try { stream.write(sealed); output.finishWrite(stream); }
        catch (Exception error) { output.failWrite(stream); throw error; }
    }

    synchronized Entry load(String id) throws Exception {
        AtomicFile input = new AtomicFile(file(id));
        return Entry.unpack(open(read(input.openRead(), MAX_RECORD), localKey(), id));
    }

    List<String> ids() {
        List<String> ids = new ArrayList<>();
        String[] names = dir.list((d, name) -> name.matches("[0-9a-f-]{36}\\.caps"));
        if (names != null) for (String name : names) ids.add(name.substring(0, 36));
        Collections.sort(ids);
        return ids;
    }
}
