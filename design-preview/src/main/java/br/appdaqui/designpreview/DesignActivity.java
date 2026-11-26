package br.appdaqui.designpreview;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Color;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.webkit.JavascriptInterface;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesignActivity extends Activity {
    private static final String HOST = "allembra-preview.invalid";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int MEDIA_PERMISSION = 31;
    private static final int NOTIFICATION_PERMISSION = 32, SAVE_DOCUMENT = 41, OPEN_DOCUMENT = 42;
    private FrameLayout container;
    private VaultFiles vault;
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

    private final class VaultBridge {
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
        if (web != null && pending == null) {
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
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
