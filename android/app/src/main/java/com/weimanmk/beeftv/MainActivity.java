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
    private byte[] pendingBytes;
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
        return baseURL != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 18789;
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
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                String[] types = params.getAcceptTypes();
                if (types.length > 0 && !types[0].isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
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
            if (pendingURL != null || pendingBytes != null) { toast("请先完成当前文件保存"); return; }
            if (local(Uri.parse(download))) {
                pendingURL = download;
                chooseSave(android.webkit.URLUtil.guessFileName(download, disposition, mime), mime);
            } else if (download.startsWith("blob:" + baseURL + "/")) {
                // Pull only the user-requested local blob; never expose a native JavaScript bridge.
                pendingURL = download;
                String key = "__beefExport" + java.util.UUID.randomUUID().toString().replace("-", "");
                web.evaluateJavascript("window[" + JSONObject.quote(key) + "]='pending';(async()=>{try{const b=await(await fetch(" + JSONObject.quote(download) + ")).blob();"
                    + "if(b.size>33554432)throw Error('文件超过32MB，请下载素材原文件');const f=new FileReader();f.onload=()=>window[" + JSONObject.quote(key) + "]=f.result;"
                    + "f.onerror=()=>window[" + JSONObject.quote(key) + "]='error';f.readAsDataURL(b)}catch(e){window[" + JSONObject.quote(key) + "]='error'}})()", ignored -> readBlob(key, mime, 0));
            } else { toast("暂不支持此下载地址，请使用素材原文件下载"); }
        });
        root.removeAllViews();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        cookies.setCookie(baseURL, cookie + "; Path=/; HttpOnly; SameSite=Strict", accepted -> {
            if (Boolean.TRUE.equals(accepted)) web.loadUrl(baseURL + "/"); else toast("无法初始化本地会话，请重启应用");
        });
    }

    private void readBlob(String key, String mime, int attempt) {
        if (web == null) { pendingURL = null; return; }
        web.evaluateJavascript("window[" + JSONObject.quote(key) + "]", raw -> {
            try {
                Object value = new org.json.JSONTokener(raw).nextValue();
                if ("pending".equals(value) && attempt < 200) {
                    web.postDelayed(() -> readBlob(key, mime, attempt + 1), 100); return;
                }
                web.evaluateJavascript("delete window[" + JSONObject.quote(key) + "]", null);
                pendingURL = null;
                if (!(value instanceof String) || !((String)value).startsWith("data:")) throw new IllegalStateException();
                String encoded = (String)value;
                pendingBytes = android.util.Base64.decode(encoded.substring(encoded.indexOf(',') + 1), android.util.Base64.DEFAULT);
                chooseSave("BeefTV-export" + ("application/zip".equals(mime) ? ".zip" : ""), mime);
            } catch (Exception failure) { pendingURL = null; pendingBytes = null; toast("导出失败，Blob 文件上限为 32MB，请尝试下载素材原文件"); }
        });
    }

    private void chooseSave(String name, String mime) {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime).putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(save, 11); }
        catch (ActivityNotFoundException failure) { pendingURL = null; pendingBytes = null; toast("系统没有可用的文件保存工具"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10 && fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback; fileCallback = null;
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        } else if (request == 11) {
            String source = pendingURL; byte[] bytes = pendingBytes; pendingURL = null; pendingBytes = null;
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri destination = data.getData();
            WORKER.execute(() -> {
                HttpURLConnection connection = null;
                try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new java.io.IOException("无法写入目标文件");
                    if (bytes != null) output.write(bytes);
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
                finally { if (connection != null) connection.disconnect(); }
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
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
        // The Go singleton survives orientation/activity recreation. Android closes
        // the process and SQLite with the app; no foreground/background service.
        super.onDestroy();
    }
}
