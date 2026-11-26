package br.appdaqui.capsula;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Appwrite {
    private static final int CHUNK = 5 * 1024 * 1024;
    private static final byte[] PROOF = {42};
    private static final String PUBLIC_ID = "[A-Za-z0-9][A-Za-z0-9._-]{0,35}";
    private static final java.util.regex.Pattern FILE_NAME =
        java.util.regex.Pattern.compile("[0-9a-f-]{36}\\.caps");
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    final String endpoint, project, bucket, recoveryUrl, verificationUrl, origin;
    String userId, email;
    boolean verified;

    Appwrite(Context context) {
        Properties props = new Properties();
        try (InputStream data = context.getAssets().open("appwrite.properties")) { props.load(data); }
        catch (IOException ignored) {}
        endpoint = props.getProperty("endpoint","").trim();
        project = props.getProperty("projectId","").trim();
        bucket = props.getProperty("bucketId","").trim();
        recoveryUrl = props.getProperty("recoveryUrl","").trim();
        verificationUrl = props.getProperty("verificationUrl","").trim();
        origin = "appwrite-android://" + context.getPackageName();
    }

    boolean configured() {
        return endpoint.matches("https://(?:[a-z0-9-]+\\.)?cloud\\.appwrite\\.io/v1")
            && project.matches(PUBLIC_ID) && bucket.matches(PUBLIC_ID);
    }

    static String generateCode() {
        byte[] bytes = new byte[32];
        Vault.RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static SecretKey recoveryKey(String code) throws IOException {
        if (!code.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Confira o código de recuperação.");
        byte[] bytes = Base64.decode(code, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new SecretKeySpec(bytes,"AES");
    }

    void createAccount(String email, String password) throws Exception {
        json("POST","/account",new JSONObject().put("userId","unique()").put("email",email).put("password",password));
    }

    void login(String address, String password) throws Exception {
        clear();
        try {
            json("POST","/account/sessions/email",new JSONObject().put("email",address).put("password",password));
            refreshAccount();
        } catch (Exception failure) {
            clear();
            throw failure;
        }
    }

    void refreshAccount() throws Exception {
        JSONObject account = json("GET","/account",null);
        String id = account.getString("$id");
        if (!id.matches(PUBLIC_ID)) throw new IOException();
        userId = id;
        email = account.getString("email");
        verified = account.optBoolean("emailVerification");
    }

    static boolean validLink(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) { return false; }
    }

    void sendVerification() throws Exception {
        if (!validLink(verificationUrl)) throw new IOException();
        json("POST","/account/verification",new JSONObject().put("url",verificationUrl));
    }

    void recover(String address) throws Exception {
        if (!validLink(recoveryUrl)) throw new IOException();
        try {
            json("POST","/account/recovery",new JSONObject().put("email",address).put("url",recoveryUrl));
        } catch (HttpError e) {
            if (e.status != 404) throw e;
        }
    }

    void logout() throws Exception {
        try { request("DELETE","/account/sessions/current",null,null,Collections.emptyMap()); }
        finally { clear(); }
    }

    void clear() {
        userId = email = null;
        verified = false;
        cookies.getCookieStore().removeAll();
    }

    String scope() { return endpoint + "/" + project + "/" + bucket + "/" + userId; }
    private String files() { return "/storage/buckets/" + bucket + "/files"; }
    private String binding(String name) { return scope() + "/" + name; }
    private String verifierId() { return UUID.nameUUIDFromBytes(binding("chave").getBytes(StandardCharsets.UTF_8)).toString(); }

    private byte[] download(String id) throws Exception {
        if (!id.matches(PUBLIC_ID)) throw new IOException();
        return request("GET",files() + "/" + id + "/download",null,null,Collections.emptyMap());
    }

    void verify(SecretKey key, boolean create) throws Exception {
        String id = verifierId();
        byte[] proof;
        try { proof = download(id); }
        catch (HttpError e) {
            if (!create || e.status != 404) throw e;
            try { upload(id,"chave.caps",Vault.seal(PROOF,key,binding("chave"))); }
            catch (HttpError race) { if (race.status != 409) throw race; }
            proof = download(id);
        }
        try {
            if (!Arrays.equals(Vault.open(proof,key,binding("chave")),PROOF)) throw new IOException();
        } catch (Exception e) { throw new IOException("Este código não corresponde ao backup da conta."); }
    }

    private Map<String,String> remoteFiles() throws Exception {
        Map<String,String> found = new LinkedHashMap<>();
        for (int offset = 0; ; offset += 100) {
            String query = "?" + query("limit",100) + "&" + query("offset",offset);
            JSONArray page = json("GET",files() + query,null).getJSONArray("files");
            for (int i = 0; i < page.length(); i++) {
                JSONObject file = page.getJSONObject(i);
                String name = file.optString("name"), id = file.optString("$id");
                if (!FILE_NAME.matcher(name).matches() || !id.matches(PUBLIC_ID)
                        || file.optInt("chunksUploaded",-1) != file.optInt("chunksTotal",0)) continue;
                try {
                    String record = UUID.fromString(name.substring(0,36)).toString();
                    found.putIfAbsent(record,id);
                } catch (IllegalArgumentException ignored) {}
            }
            if (page.length() < 100) return found;
        }
    }

    private String query(String method,int value) throws Exception {
        JSONObject query = new JSONObject().put("method",method).put("values",new JSONArray().put(value));
        return "queries%5B%5D=" + URLEncoder.encode(query.toString(),"UTF-8");
    }

    int transfer(Vault vault, SecretKey key, boolean send) throws Exception {
        Map<String,String> remote = remoteFiles();
        Set<String> local = new LinkedHashSet<>(vault.ids());
        int count = 0;
        if (send) {
            for (String id : local) if (!remote.containsKey(id)) {
                String name = id + ".caps";
                upload(UUID.randomUUID().toString(),name,Vault.seal(vault.load(id).pack(),key,binding(name)));
                count++;
            }
        } else {
            for (Map.Entry<String,String> item : remote.entrySet()) if (!local.contains(item.getKey())) {
                String id = item.getKey();
                Vault.Entry entry = Vault.Entry.unpack(Vault.open(download(item.getValue()),key,binding(id + ".caps")));
                if (!entry.id().equals(id)) throw new IOException();
                vault.save(entry);
                count++;
            }
        }
        return count;
    }

    private void upload(String id,String filename,byte[] data) throws Exception {
        boolean chunked = data.length > CHUNK;
        for (int start = 0; start < data.length; start += CHUNK) {
            int end = Math.min(data.length,start + CHUNK);
            String delim = "allembra-" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            part(body,delim,"fileId",id);
            part(body,delim,"permissions[]","read(\"user:" + userId + "\")");
            append(body,"--" + delim + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            body.write(data,start,end - start);
            append(body,"\r\n--" + delim + "--\r\n");
            Map<String,String> headers = new HashMap<>();
            if (chunked) {
                headers.put("Content-Range","bytes " + start + "-" + (end - 1) + "/" + data.length);
                if (start > 0) headers.put("X-Appwrite-ID",id);
            }
            request("POST",files(),body.toByteArray(),"multipart/form-data; boundary=" + delim,headers);
        }
    }

    private static void part(ByteArrayOutputStream body,String delimiter,String key,String value) throws IOException {
        append(body,"--" + delimiter + "\r\nContent-Disposition: form-data; name=\"" + key + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void append(ByteArrayOutputStream body,String value) throws IOException {
        body.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject json(String method,String path,JSONObject body) throws Exception {
        byte[] data = request(method,path,body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8),
            "application/json",Collections.emptyMap());
        return data.length == 0 ? new JSONObject() : new JSONObject(new String(data,StandardCharsets.UTF_8));
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status) { this.status = status; }
    }

    private byte[] request(String method,String path,byte[] bytes,String mime,Map<String,String> extra) throws Exception {
        if (!configured()) throw new IOException();
        URI uri = URI.create(endpoint + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-Appwrite-Project",project);
            conn.setRequestProperty("Origin",origin);
            for (Map.Entry<String,List<String>> header : cookies.get(uri,Collections.emptyMap()).entrySet())
                for (String value : header.getValue()) conn.addRequestProperty(header.getKey(),value);
            for (Map.Entry<String,String> header : extra.entrySet()) conn.setRequestProperty(header.getKey(),header.getValue());
            if (bytes != null) {
                conn.setRequestProperty("Content-Type",mime);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = conn.getOutputStream()) { output.write(bytes); }
            }
            int status = conn.getResponseCode();
            cookies.put(uri,conn.getHeaderFields());
            if (status == 204) return new byte[0];
            if (status >= 200 && status < 300) return Vault.read(conn.getInputStream(),Vault.MAX_RECORD);
            throw new HttpError(status);
        } finally { conn.disconnect(); }
    }
}
