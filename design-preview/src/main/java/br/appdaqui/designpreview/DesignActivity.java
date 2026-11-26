package br.appdaqui.designpreview;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
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
    private static final int BLUE = 0xFF005A85;
    private WebView web;
    private PermissionRequest pending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLUE);
        getWindow().setNavigationBarColor(BLUE);
        FrameLayout container = new FrameLayout(this);
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
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
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
                else if ("lucide.min.js".equals(filename)) type = "application/javascript";
                else if ("inter.ttf".equals(filename) || "newsreader.ttf".equals(filename)) type = "font/ttf";
                else return notFound();
                try {
                    return new WebResourceResponse(type, type.equals("font/ttf") ? null : "UTF-8", getAssets().open(filename));
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

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.allembraBack && window.allembraBack()", handled -> {
            if (!"true".equals(handled)) super.onBackPressed();
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
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
