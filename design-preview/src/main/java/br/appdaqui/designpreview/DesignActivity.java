package br.appdaqui.designpreview;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.webkit.JavascriptInterface;
import android.provider.MediaStore;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesignActivity extends Activity {
    private static final String HOST = "allembra-preview.invalid";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int MEDIA_PERMISSION = 31, MIC_PERMISSION = 33, CAMERA_PERMISSION = 34;
    private static final int TAKE_PHOTO = 51, TAKE_VIDEO = 52, MEDIA_LIMIT = 15 * 1024 * 1024;
    private static final int NOTIFICATION_PERMISSION = 32, SAVE_DOCUMENT = 41, OPEN_DOCUMENT = 42;
    private FrameLayout container;
    private VaultFiles vault;
    private NativeMic microphone;
    private VaultBiometrics biometrics;
    private boolean micPermissionPending;
    private int cameraPermissionPending, cameraRequest;
    private File cameraOutput;
    private String capturedMedia;
    private final Object captureLock = new Object();
    private String restoration;
    private volatile boolean documentOpen;
    private ValueAnimator surfaceAnimation;
    private int currentSurface = BLUE;
    private static final int BLUE = 0xFF005A85;
    private WebView web;
    private PermissionRequest pending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLUE);
        getWindow().setNavigationBarColor(BLUE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        vault = new VaultFiles(this);
        microphone = new NativeMic(this);
        biometrics = new VaultBiometrics(this);
        container = new FrameLayout(this);
        container.setBackgroundColor(BLUE);
        if (Build.VERSION.SDK_INT >= 35) {
            container.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets edge = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(edge.left, edge.top, edge.right, Math.max(edge.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }
        web = new WebView(this);
        web.setBackgroundColor(BLUE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        web.setHorizontalScrollBarEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.addJavascriptInterface(dataBridge, "AllembraNative");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOME.equals(uri.toString())) return false;
                if (request.isForMainFrame() && request.hasGesture() && "https".equals(uri.getScheme())
                        && ("freesound.org".equals(uri.getHost()) || "creativecommons.org".equals(uri.getHost()))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException ignored) {}
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isLocal(uri) || !"GET".equals(request.getMethod())) return notFound();
                String filename = uri.getLastPathSegment();
                String type;
                if ("index.html".equals(filename)) type = "text/html";
                else if ("lucide.min.js".equals(filename) || "vault.js".equals(filename)) type = "application/javascript";
                else if ("cogita.svg".equals(filename)) type = "image/svg+xml";
                else if ("mark.png".equals(filename)) type = "image/png";
                else if ("inter.ttf".equals(filename) || "newsreader.ttf".equals(filename)) type = "font/ttf";
                else return notFound();
                try {
                    return new WebResourceResponse(type, type.startsWith("font/") || type.equals("image/png") ? null : "UTF-8", getAssets().open(filename));
                } catch (IOException missing) { return notFound(); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { requestMedia(request); }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pending == request) pending = null;
            }
        });
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        web.loadUrl(HOME);
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
    }

    private static boolean isLocal(Uri uri) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost()) && uri.getPort() == -1
            && uri.getUserInfo() == null && uri.getPathSegments().size() <= 1;
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String androidPermission(String resource) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return Manifest.permission.CAMERA;
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) return Manifest.permission.RECORD_AUDIO;
        return null;
    }

    private void requestMedia(PermissionRequest request) {
        if (!isLocal(request.getOrigin()) || !HOME.equals(web.getUrl()) || pending != null) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String resource : request.getResources()) {
            String permission = androidPermission(resource);
            if (permission == null) { request.deny(); return; }
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) request.grant(request.getResources());
        else {
            pending = request;
            requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CAMERA_PERMISSION) {
            int capture = cameraPermissionPending;
            cameraPermissionPending = 0;
            if (capture == 0) return;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera(capture);
            else callWeb("allembraCameraFailed", "Permita a câmera nos Ajustes do Android.");
            return;
        }
        if (code == MIC_PERMISSION) {
            if (!micPermissionPending) return;
            micPermissionPending = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startNativeMic();
            else callWeb("allembraMicFailed", "Permita o microfone nos Ajustes do Android.");
            return;
        }
        if (code != MEDIA_PERMISSION || pending == null) return;
        PermissionRequest request = pending;
        pending = null;
        for (String resource : request.getResources()) {
            String permission = androidPermission(resource);
            if (permission == null || checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                request.deny();
                return;
            }
        }
        if (HOME.equals(web.getUrl())) request.grant(request.getResources());
        else request.deny();
    }

    private void callWeb(String function, String value) {
        runOnUiThread(() -> {
            if (!isDestroyed()) web.evaluateJavascript("window." + function + "(" +
                (value == null ? "" : JSONObject.quote(value)) + ")", null);
        });
    }

    private void requestNativeMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startNativeMic();
        } else if (!micPermissionPending) {
            micPermissionPending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
        }
    }

    private void startNativeMic() {
        try {
            microphone.start(() -> runOnUiThread(this::stopNativeMic));
            callWeb("allembraMicStarted", null);
        } catch (IOException | RuntimeException failure) {
            callWeb("allembraMicFailed", "Não foi possível iniciar o microfone. Confira o acesso global e feche outros gravadores.");
        }
    }

    private void stopNativeMic() {
        if (microphone.finish()) callWeb("allembraMicFinished", null);
        else callWeb("allembraMicFailed", "Gravação curta ou indisponível. Tente novamente.");
    }

    private void requestCamera(int code) {
        if (cameraRequest != 0 || cameraPermissionPending != 0) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera(code);
        else {
            cameraPermissionPending = code;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private void launchCamera(int code) {
        try {
            File file = ShareFiles.create(this, "capture", code == TAKE_VIDEO ? "mp4" : "jpg");
            if (!file.createNewFile()) throw new IOException("Não foi possível abrir o arquivo temporário.");
            Uri target = ShareFiles.uri(this, file);
            Intent capture = new Intent(code == TAKE_VIDEO ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, target)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            capture.setClipData(ClipData.newUri(getContentResolver(), file.getName(), target));
            if (code == TAKE_VIDEO) capture.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
                .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1).putExtra(MediaStore.EXTRA_SIZE_LIMIT, (long) MEDIA_LIMIT);
            cameraOutput = file;
            cameraRequest = code;
            startActivityForResult(capture, code);
        } catch (IOException | ActivityNotFoundException | SecurityException unavailable) {
            cameraRequest = 0;
            if (cameraOutput != null) cameraOutput.delete();
            cameraOutput = null;
            callWeb("allembraCameraFailed", "O app de câmera do Android não pôde ser aberto.");
        }
    }

    private void receiveCamera(int code, int result, Intent data) {
        File file = cameraOutput;
        cameraOutput = null; cameraRequest = 0;
        if (file == null) return;
        Uri granted = ShareFiles.uri(this, file);
        revokeUriPermission(granted, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (result != RESULT_OK) {
            file.delete();
            callWeb("allembraCameraFailed", "Captura cancelada. Toque na pena para tentar novamente.");
            return;
        }
        Uri source = file.length() > 0 ? granted : data == null ? null : data.getData();
        if (source == null || !"content".equals(source.getScheme())) {
            file.delete();
            callWeb("allembraCameraFailed", "A câmera não devolveu um arquivo.");
            return;
        }
        new Thread(() -> {
            try {
                if (code == TAKE_VIDEO) {
                    MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                    try {
                        metadata.setDataSource(this, source);
                        long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        if (duration > 30500) throw new IOException("O vídeo excede 30 segundos. Grave um trecho mais curto.");
                    } finally { metadata.release(); }
                }
                String encoded;
                try (InputStream input = getContentResolver().openInputStream(source);
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    if (input == null) throw new IOException("A câmera não devolveu um arquivo.");
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (bytes.size() > MEDIA_LIMIT - count)
                            throw new IOException("A captura excede 15 MB. Tente um vídeo mais curto.");
                        bytes.write(buffer, 0, count);
                    }
                    if (bytes.size() == 0) throw new IOException("A captura ficou vazia.");
                    encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                }
                synchronized (captureLock) { capturedMedia = encoded; }
                callWeb("allembraCameraReady", code == TAKE_VIDEO ? "video/mp4" : "image/jpeg");
            } catch (Exception failure) {
                String message = failure instanceof IOException && failure.getMessage() != null ?
                    failure.getMessage() : "Não foi possível ler a captura.";
                callWeb("allembraCameraFailed", message);
            } finally { file.delete(); }
        }, "allembra-camera").start();
    }

    private final class VaultBridge {
        @JavascriptInterface public void cameraStart(boolean video) {
            runOnUiThread(() -> requestCamera(video ? TAKE_VIDEO : TAKE_PHOTO));
        }
        @JavascriptInterface public String cameraTake() {
            synchronized (captureLock) {
                String value = capturedMedia; capturedMedia = null;
                return value == null ? "" : value;
            }
        }
        @JavascriptInterface public void micStart() { runOnUiThread(DesignActivity.this::requestNativeMic); }
        @JavascriptInterface public void micStop() { runOnUiThread(DesignActivity.this::stopNativeMic); }
        @JavascriptInterface public void micCancel() {
            runOnUiThread(() -> { micPermissionPending = false; microphone.discard(); });
        }
        @JavascriptInterface public boolean biometricEnabled() { return biometrics.enabled(); }
        @JavascriptInterface public void biometricSave(String phrase) {
            if (phrase == null || phrase.length() < 16 || phrase.length() > 512) return;
            runOnUiThread(() -> biometrics.authenticate(phrase,
                result -> callWeb("allembraBiometricSaved", null), msg -> callWeb("allembraSecurityMessage", msg)));
        }
        @JavascriptInterface public void biometricOpen() {
            if (!biometrics.enabled()) return;
            runOnUiThread(() -> biometrics.authenticate(null,
                value -> callWeb("allembraBiometricPhrase", value), msg -> callWeb("allembraSecurityMessage", msg)));
        }
        @JavascriptInterface public void biometricClear() { runOnUiThread(biometrics::clear); }

        @JavascriptInterface public void shareText(String text) {
            if (text == null || text.isEmpty() || text.length() > 100000) return;
            runOnUiThread(() -> openShare(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)));
        }

        @JavascriptInterface public void shareMedia(String mime, String base64) {
            if (mime == null || base64 == null || base64.length() > 4 * ((MEDIA_LIMIT + 2) / 3)) return;
            final String extension;
            switch (mime) {
                case "image/jpeg": extension = "jpg"; break;
                case "image/png": extension = "png"; break;
                case "video/mp4": extension = "mp4"; break;
                case "audio/mp4": extension = "m4a"; break;
                case "video/webm": extension = "webm"; break;
                case "audio/ogg": extension = "ogg"; break;
                default: backupStatus("Formato indisponível para compartilhar. Você pode compartilhar um print."); return;
            }
            new Thread(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.NO_WRAP);
                    if (data.length == 0 || data.length > MEDIA_LIMIT) return;
                    File file = ShareFiles.create(DesignActivity.this, "midia", extension);
                    try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); }
                    runOnUiThread(() -> openShare(fileIntent(file, mime)));
                } catch (IOException | IllegalArgumentException failure) { backupStatus("Não foi possível compartilhar a mídia."); }
            }, "allembra-share-media").start();
        }

        @JavascriptInterface public void sharePrint(int left, int top, int width, int height, int viewportScroll, boolean story) {
            runOnUiThread(() -> captureLetter(left, top, width, height, story));
        }

        @JavascriptInterface public void draftRecoveryEmail(String address, String envelope) {
            if (address == null || address.length() > 250 || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()
                || envelope == null || envelope.length() > 8192
                || !envelope.startsWith("{\"version\":1,\"purpose\":\"recovery-key\"")) return;
            new Thread(() -> {
                try {
                    File file = ShareFiles.create(DesignActivity.this, "chave", "json");
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        output.write(envelope.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> openShare(fileIntent(file, "application/json")
                        .putExtra(Intent.EXTRA_EMAIL, new String[]{address})
                        .putExtra(Intent.EXTRA_SUBJECT, "Allembra · chave de recuperação cifrada")
                        .putExtra(Intent.EXTRA_TEXT, "Guarde este arquivo. Para recuperar sua frase, use a senha de emergência escolhida no Allembra.")));
                } catch (IOException failure) { callWeb("allembraSecurityMessage", "Não foi possível preparar o arquivo."); }
            }, "allembra-key-share").start();
        }

        @JavascriptInterface public int micLevel() { return microphone.level(); }
        @JavascriptInterface public String micTake() { return microphone.take(); }

        @JavascriptInterface public void surface(String css) {
            if (css == null || !css.matches("#[a-fA-F0-9]{6}")) return;
            int color = Color.parseColor(css);
            runOnUiThread(() -> {
                if (surfaceAnimation != null) surfaceAnimation.cancel();
                if (currentSurface == color) return;
                surfaceAnimation = ValueAnimator.ofArgb(currentSurface, color);
                surfaceAnimation.setDuration(320);
                surfaceAnimation.addUpdateListener(animation -> {
                    currentSurface = (int) animation.getAnimatedValue();
                    container.setBackgroundColor(currentSurface);
                    web.setBackgroundColor(currentSurface);
                    getWindow().setStatusBarColor(currentSurface);
                    getWindow().setNavigationBarColor(currentSurface);
                });
                surfaceAnimation.start();
            });
        }

        @JavascriptInterface public String readVault() {
            try { return vault.read(); } catch (IOException failure) { return "!erro-de-leitura"; }
        }

        @JavascriptInterface public boolean writeVault(String cipherText) {
            try { vault.write(cipherText); return true; }
            catch (IOException failure) { return false; }
        }

        @JavascriptInterface public void chooseBackup() {
            runOnUiThread(() -> pickDocument(Intent.ACTION_CREATE_DOCUMENT, SAVE_DOCUMENT));
        }

        @JavascriptInterface public void shareBackup() {
            new Thread(() -> {
                try {
                    File copy = ShareFiles.create(DesignActivity.this, "backup", "json");
                    try (FileOutputStream out = new FileOutputStream(copy)) {
                        String encrypted = vault.read();
                        if (encrypted.isEmpty()) throw new IOException("Cofre vazio.");
                        out.write(encrypted.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> openShare(fileIntent(copy, "application/json")));
                } catch (IOException failure) { backupStatus("Não foi possível preparar a cópia criptografada."); }
            }, "allembra-backup-share").start();
        }

        @JavascriptInterface public void chooseRestore() {
            runOnUiThread(() -> pickDocument(Intent.ACTION_OPEN_DOCUMENT, OPEN_DOCUMENT));
        }

        @JavascriptInterface public synchronized String takeRestore() {
            String value = restoration;
            restoration = null;
            return value;
        }

        synchronized void setRestore(String value) { restoration = value; }

        @JavascriptInterface public void configure(String frequency, boolean reminders, int hours) {
            if (!("off".equals(frequency) || "daily".equals(frequency) || "weekly".equals(frequency))
                    || hours < 1 || hours > 168) return;
            runOnUiThread(() -> {
                PreviewJobs.configure(DesignActivity.this, frequency, reminders, hours);
                if (reminders && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            });
        }

        @JavascriptInterface public void offerAutofill() {
            runOnUiThread(() -> {
                AutofillManager manager = getSystemService(AutofillManager.class);
                if (manager != null && manager.isEnabled()) manager.commit();
                else backupStatus("Ative um gerenciador em Configurações do Android para salvar a frase nele.");
            });
        }
    }

    private Intent fileIntent(File file, String mime) {
        Uri uri = ShareFiles.uri(this, file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), file.getName(), uri));
        return intent;
    }

    private void openShare(Intent send) {
        if (isDestroyed()) return;
        try { startActivity(Intent.createChooser(send, "Compartilhar com")); }
        catch (ActivityNotFoundException unavailable) { backupStatus("Nenhum app de compartilhamento está disponível."); }
    }

    private void captureLetter(int left, int top, int requestedWidth, int requestedHeight, boolean story) {
        int x = Math.max(0, left), y = Math.max(0, top);
        int width = Math.min(requestedWidth - Math.max(0, -left), web.getWidth() - x);
        int height = Math.min(requestedHeight - Math.max(0, -top), web.getHeight() - y);
        if (width <= 0 || height <= 0 || (long) width * height > 8_000_000) {
            backupStatus("Deixe a carta visível para capturar o print.");
            return;
        }
        // WebView#draw renders the visible viewport at its present scroll position.
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.translate(-x, -y);
        web.draw(canvas);
        new Thread(() -> {
            try {
                File file = ShareFiles.create(this, "carta", "png");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    int targetHeight = story ? 1920 : 2160;
                    Bitmap card = Bitmap.createBitmap(1080, targetHeight, Bitmap.Config.ARGB_8888);
                    try {
                        Canvas layout = new Canvas(card);
                        layout.drawColor(0xFF075878);
                        float scale = Math.min(960F / image.getWidth(), (targetHeight - 200F) / image.getHeight());
                        float w = image.getWidth() * scale, h = image.getHeight() * scale;
                        layout.drawBitmap(image, null, new android.graphics.RectF((1080-w)/2, (targetHeight-h)/2,
                            (1080+w)/2, (targetHeight+h)/2), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                        if (!card.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException();
                    } finally { card.recycle(); }
                }
                runOnUiThread(() -> openShare(fileIntent(file, "image/png")));
            } catch (IOException failure) { backupStatus("Não foi possível preparar o print."); }
            finally { image.recycle(); }
        }, "allembra-share-print").start();
    }

    private final VaultBridge dataBridge = new VaultBridge();

    private void pickDocument(String action, int code) {
        if (documentOpen) return;
        Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(code == SAVE_DOCUMENT ? "application/json" : "*/*");
        if (code == SAVE_DOCUMENT) intent.putExtra(Intent.EXTRA_TITLE, "Allembra-Backup.json");
        try {
            documentOpen = true;
            startActivityForResult(intent, code);
        } catch (ActivityNotFoundException missing) {
            documentOpen = false;
            backupStatus("O seletor de documentos está indisponível.");
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == TAKE_PHOTO || code == TAKE_VIDEO) { receiveCamera(code, result, data); return; }
        if (code != SAVE_DOCUMENT && code != OPEN_DOCUMENT) return;
        documentOpen = false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) return;
        if (code == OPEN_DOCUMENT) {
            new Thread(() -> {
                try {
                    dataBridge.setRestore(vault.importBackup(uri));
                    runOnUiThread(() -> {
                        if (!isDestroyed()) web.evaluateJavascript("window.allembraRestoreReady()", null);
                    });
                } catch (Exception failure) { backupStatus("Não foi possível ler o backup selecionado."); }
            }, "allembra-import").start();
            return;
        }
        new Thread(() -> {
            try {
                vault.exportBackup(uri);
                boolean granted = false;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    granted = true;
                } catch (SecurityException ignored) {}
                if (granted) {
                    android.content.SharedPreferences prefs = PreviewJobs.prefs(this);
                    String previous = prefs.getString("destination", null);
                    prefs.edit().putString("destination", uri.toString()).apply();
                    if (previous != null && !previous.equals(uri.toString())) {
                        try { getContentResolver().releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                        catch (SecurityException ignored) {}
                    }
                    PreviewJobs.configure(this, prefs.getString("frequency", "off"), prefs.getBoolean("reminders", false), prefs.getInt("hours", 24));
                }
                backupStatus(granted ? "Backup criptografado salvo." : "Cópia salva. O provedor não autorizou atualização automática.");
            } catch (Exception failure) { backupStatus("Não foi possível gravar no destino selecionado."); }
        }, "allembra-export").start();
    }

    private void backupStatus(String message) {
        runOnUiThread(() -> {
            if (!isDestroyed()) web.evaluateJavascript("window.allembraBackupStatus(" + JSONObject.quote(message) + ")", null);
        });
    }

    @Override public void onBackPressed() { goBack(); }

    private void goBack() {
        web.evaluateJavascript("window.allembraBack && window.allembraBack()", handled -> {
            if (!"true".equals(handled)) finish();
        });
    }

    @Override protected void onPause() {
        if (web != null && pending == null && !micPermissionPending && cameraPermissionPending == 0 && cameraRequest == 0) {
            web.evaluateJavascript("window.dispatchEvent(new Event('allembra:pause'))", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        if (pending != null) { pending.deny(); pending = null; }
        if (surfaceAnimation != null) surfaceAnimation.cancel();
        if (microphone != null) microphone.discard();
        if (cameraOutput != null) cameraOutput.delete();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
