package com.weimanmk.beeftv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static { System.loadLibrary("beeftv"); }
    static native String startBackend(String dataDir);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    WebView web;
    private String baseURL;
    private String sessionCookie;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingURL;
    private java.io.File pendingFile;
    private PermissionRequest microphoneRequest;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff111111);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        TextView loading = new TextView(this);
        loading.setText("正在启动 BeefTV 手机工作区…");
        loading.setTextColor(0xffeeeeee);
        loading.setGravity(android.view.Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        WORKER.execute(() -> {
            try {
                JSONObject info = new JSONObject(startBackend(new java.io.File(getFilesDir(), "workspace").getAbsolutePath()));
                if (info.has("error")) throw new IllegalStateException(info.getString("error"));
                String url = info.getString("url");
                String cookie = "BeefTVSession=" + info.getString("token");
                runOnUiThread(() -> { if (!isDestroyed()) openWorkspace(url, cookie); });
            } catch (Throwable failure) {
                runOnUiThread(() -> { if (!isDestroyed()) new AlertDialog.Builder(this)
                    .setTitle("工作区启动失败").setMessage(failure.getMessage())
                    .setPositiveButton("关闭", (dialog, which) -> finish()).show(); });
            }
        });
    }

    private boolean local(Uri uri) {
        return baseURL != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 18790;
    }

    private void openWorkspace(String url, String cookie) {
        baseURL = url;
        sessionCookie = cookie;
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (local(target)) return false;
                if (request.isForMainFrame() && request.hasGesture() && "https".equals(target.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, target)); } catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!local(request.getOrigin()) || web == null || !local(Uri.parse(web.getUrl()))) { request.deny(); return; }
                    String[] resources = request.getResources();
                    if (resources.length != 1 || !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0])) { request.deny(); return; }
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (microphoneRequest != null) microphoneRequest.deny();
                        microphoneRequest = request;
                        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 12);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> { if (microphoneRequest == request) microphoneRequest = null; });
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                String[] types = fileMimeTypes(params.getAcceptTypes());
                if (types.length > 0) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, 10); return true; }
                catch (ActivityNotFoundException failure) { fileCallback = null; callback.onReceiveValue(null); return false; }
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view; fullscreenCallback = callback;
                web.setVisibility(View.GONE); root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        web.setDownloadListener((download, agent, disposition, mime, size) -> {
            if (pendingURL != null || pendingFile != null) { toast("请先完成当前文件保存"); return; }
            if (local(Uri.parse(download))) {
                pendingURL = download;
                chooseSave(android.webkit.URLUtil.guessFileName(download, disposition, mime), mime);
            } else if (download.startsWith("blob:" + baseURL + "/")) {
                // Pull only the user-requested local blob; never expose a native JavaScript bridge.
                pendingURL = download;
                String key = "__beefExport" + java.util.UUID.randomUUID().toString().replace("-", "");
                web.evaluateJavascript("window[" + JSONObject.quote(key) + "]={state:'pending'};(async()=>{try{const r=await fetch(" + JSONObject.quote(download) + ");if(!r.ok)throw Error('fetch');const b=await r.blob();"
                    + "window[" + JSONObject.quote(key) + "]={state:'ready',blob:b,size:b.size,type:b.type}}catch(e){window[" + JSONObject.quote(key) + "]={state:'error'}}})()", ignored -> readBlob(key, mime, 0));
            } else { toast("暂不支持此下载地址，请使用素材原文件下载"); }
        });
        root.removeAllViews();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        cookies.setCookie(baseURL, cookie + "; Path=/; HttpOnly; SameSite=Strict", accepted -> {
            if (Boolean.TRUE.equals(accepted)) web.loadUrl(baseURL + "/"); else toast("无法初始化本地会话，请重启应用");
        });
    }

    static String[] fileMimeTypes(String[] acceptTypes) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : acceptTypes) for (String item : value.split(",")) {
            String type = item.trim().toLowerCase(java.util.Locale.ROOT);
            if (type.startsWith(".")) type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
            if (type != null && type.contains("/")) result.add(type);
        }
        return result.toArray(new String[0]);
    }

    private void readBlob(String key, String mime, int attempt) {
        if (web == null) { pendingURL = null; return; }
        web.evaluateJavascript("(()=>{const v=window[" + JSONObject.quote(key) + "];return v?{state:v.state,size:v.size,type:v.type}:null})()", raw -> {
            try {
                JSONObject value = new JSONObject(raw);
                if ("pending".equals(value.optString("state")) && attempt < 200) {
                    web.postDelayed(() -> readBlob(key, mime, attempt + 1), 100); return;
                }
                if (!"ready".equals(value.optString("state"))) throw new java.io.IOException("读取导出文件失败");
                long size = value.getLong("size");
                if (size > getCacheDir().getUsableSpace()) throw new java.io.IOException("手机可用空间不足");
                pendingFile = java.io.File.createTempFile("beeftv-export-", ".tmp", getCacheDir());
                String type = value.optString("type", mime);
                readBlobChunk(key, type.isEmpty() ? mime : type, size, 0, 0, false);
            } catch (Exception failure) { failBlob(key, failure.getMessage()); }
        });
    }

    private void readBlobChunk(String key, String mime, long size, long offset, int attempt, boolean started) {
        if (web == null || pendingFile == null) return;
        if (offset == size) {
            web.evaluateJavascript("delete window[" + JSONObject.quote(key) + "]", null);
            pendingURL = null;
            String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime == null ? "" : mime.split(";")[0]);
            chooseSave("BeefTV-export" + (extension == null ? "" : "." + extension), mime);
            return;
        }
        long end = Math.min(size, offset + 262144);
        if (!started) {
            web.evaluateJavascript("(()=>{const v=window[" + JSONObject.quote(key) + "];v.part='pending';const r=new FileReader();r.onload=()=>v.part=r.result;r.onerror=()=>v.part='error';r.readAsDataURL(v.blob.slice(" + offset + "," + end + "))})()",
                ignored -> readBlobChunk(key, mime, size, offset, 0, true));
            return;
        }
        web.evaluateJavascript("window[" + JSONObject.quote(key) + "]?.part", raw -> {
            try {
                Object value = new org.json.JSONTokener(raw).nextValue();
                if ("pending".equals(value) && attempt < 300) {
                    web.postDelayed(() -> readBlobChunk(key, mime, size, offset, attempt + 1, true), 10); return;
                }
                if (!(value instanceof String) || !((String)value).startsWith("data:")) throw new java.io.IOException("读取导出分块失败");
                String encoded = (String)value;
                byte[] bytes = android.util.Base64.decode(encoded.substring(encoded.indexOf(',') + 1), android.util.Base64.DEFAULT);
                if (bytes.length != end - offset) throw new java.io.IOException("导出分块长度不匹配");
                java.io.File file = pendingFile;
                WORKER.execute(() -> {
                    if (isDestroyed()) return;
                    try (OutputStream output = new java.io.FileOutputStream(file, true)) {
                        output.write(bytes);
                        runOnUiThread(() -> readBlobChunk(key, mime, size, end, 0, false));
                    } catch (Exception failure) { runOnUiThread(() -> failBlob(key, failure.getMessage())); }
                });
            } catch (Exception failure) { failBlob(key, failure.getMessage()); }
        });
    }

    private void failBlob(String key, String reason) {
        if (web != null) web.evaluateJavascript("delete window[" + JSONObject.quote(key) + "]", null);
        pendingURL = null;
        clearPendingFile();
        toast("导出失败：" + reason);
    }

    private void clearPendingFile() {
        if (pendingFile != null) { pendingFile.delete(); pendingFile = null; }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 12 && microphoneRequest != null) {
            PermissionRequest pending = microphoneRequest; microphoneRequest = null;
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                && web != null && local(Uri.parse(web.getUrl()))) pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pending.deny();
        }
    }

    private void chooseSave(String name, String mime) {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime).putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(save, 11); }
        catch (ActivityNotFoundException failure) { pendingURL = null; clearPendingFile(); toast("系统没有可用的文件保存工具"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10 && fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback; fileCallback = null;
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        } else if (request == 11) {
            String source = pendingURL; java.io.File file = pendingFile; pendingURL = null; pendingFile = null;
            if (result != RESULT_OK || data == null || data.getData() == null) { if (file != null) file.delete(); return; }
            Uri destination = data.getData();
            WORKER.execute(() -> {
                HttpURLConnection connection = null;
                try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new java.io.IOException("无法写入目标文件");
                    if (file != null) {
                        try (InputStream input = new java.io.FileInputStream(file)) {
                            byte[] buffer = new byte[65536]; int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    }
                    else {
                        connection = (HttpURLConnection) new URL(source).openConnection();
                        connection.setInstanceFollowRedirects(false);
                        connection.setRequestProperty("Cookie", sessionCookie);
                        connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
                        if (connection.getResponseCode() != 200) throw new java.io.IOException("下载失败：" + connection.getResponseCode());
                        try (InputStream input = connection.getInputStream()) {
                            byte[] buffer = new byte[65536]; int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    }
                    runOnUiThread(() -> toast("已保存文件"));
                } catch (Exception failure) { runOnUiThread(() -> toast("保存失败：" + failure.getMessage())); }
                finally { if (connection != null) connection.disconnect(); if (file != null) file.delete(); }
            });
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void exitFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen); fullscreen = null; web.setVisibility(View.VISIBLE);
        fullscreenCallback.onCustomViewHidden(); fullscreenCallback = null;
    }
    @Override public void onBackPressed() {
        if (fullscreen != null) { exitFullscreen(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (microphoneRequest != null) { microphoneRequest.deny(); microphoneRequest = null; }
        clearPendingFile();
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
        // The Go singleton survives orientation/activity recreation. Android closes
        // the process and SQLite with the app; no foreground/background service.
        super.onDestroy();
    }
}
