package com.weimanmk.beeftv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Exercises the packaged native library, real SQLite, Cookie boundary and rendered SPA. */
public class MobileSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity = (MainActivity)startActivitySync(launch);
            JSONObject session = new JSONObject(MainActivity.startBackend(new java.io.File(getTargetContext().getFilesDir(), "workspace").getAbsolutePath()));
            check(!session.has("error"), "Native backend startup failed: " + session.optString("error"));
            String url = session.getString("url");
            String cookie = "BeefTVSession=" + session.getString("token");
            check(status(url + "/api/workspace/bootstrap", "") == 401, "API accepted missing cookie");
            check(status(url + "/api/workspace/bootstrap", "BeefTVSession=wrong") == 401, "API accepted invalid cookie");
            JSONObject bootstrap = request(url + "/api/workspace/bootstrap", cookie, null);
            check(bootstrap.getInt("code") == 0, "Workspace bootstrap failed");
            SharedPreferences evidence = getTargetContext().getSharedPreferences("mobile-smoke", 0);
            String id = evidence.getString("project", null);
            if (id == null) {
                JSONObject created = request(url + "/api/projects", cookie,
                    "{\"name\":\"Android persistence smoke\",\"type\":\"short_film\",\"aspectRatio\":\"16:9\",\"sourceType\":\"blank\"}");
                check(created.getInt("code") == 0, "Create project failed: " + created.optString("msg"));
                id = created.getJSONObject("data").getJSONObject("project").getString("id");
                check(evidence.edit().putString("project", id).commit(), "Cannot save smoke evidence");
            }
            JSONObject persisted = request(url + "/api/projects/" + id, cookie, null);
            check(persisted.getInt("code") == 0, "Cannot reload project after process restart");
            boolean rendered = false;
            for (int attempt = 0; attempt < 180; attempt++) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<String> value = new AtomicReference<>("");
                runOnMainSync(() -> {
                    if (activity.web == null) { done.countDown(); return; }
                    activity.web.evaluateJavascript("Boolean(document.querySelector('.app-user-workspace') && document.body.innerText.length > 30)", answer -> { value.set(answer); done.countDown(); });
                });
                done.await(5, TimeUnit.SECONDS);
                if ("true".equals(value.get())) { rendered = true; break; }
                Thread.sleep(500);
            }
            check(rendered, "SPA did not render the actual workspace");
            verifyCanvasSave(activity, url, cookie);
            result.putString("stream", "MOBILE_SMOKE_OK: native startup, session protection, project persistence, WebView canvas commit and canvas restart persistence\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "MOBILE_SMOKE_FAILED: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void verifyCanvasSave(MainActivity activity, String url, String cookie) throws Exception {
        String canvasID = "mobile-canvas-save-smoke";
        String endpoint = url + "/api/canvas-projects/" + canvasID;
        if (status(endpoint, cookie) == 200) {
            JSONObject existing = request(endpoint, cookie, null).getJSONObject("data").getJSONObject("project");
            check("Saved on Android".equals(existing.getJSONArray("nodes").getJSONObject(0).getString("content")), "Canvas nodes lost after process restart");
        } else {
            JSONObject empty = new JSONObject().put("id", canvasID).put("title", "Android canvas save")
                .put("revision", 0).put("nodes", new org.json.JSONArray()).put("connections", new org.json.JSONArray());
            JSONObject created = requestMethod(endpoint, cookie, new JSONObject().put("project", empty).toString(), "PUT");
            check(created.getInt("code") == 0, "Initial canvas creation failed: " + created.optString("msg"));
        }
        JSONObject before = request(endpoint, cookie, null).getJSONObject("data").getJSONObject("project");
        int revision = before.getInt("revision");
        JSONObject text = new JSONObject().put("id", "mobile-text-1").put("type", "text").put("title", "Text node")
            .put("content", "Saved on Android").put("position", new JSONObject().put("x", 80).put("y", 160)).put("width", 240).put("height", 180);
        JSONObject document = new JSONObject().put("title", "Android canvas save " + (revision + 1)).put("nodes", new org.json.JSONArray().put(text))
            .put("connections", new org.json.JSONArray());
        JSONObject payload = new JSONObject().put("opId", java.util.UUID.randomUUID().toString())
            .put("params", new JSONObject().put("canvasId", canvasID).put("expectedRevision", revision).put("document", document));
        // Use the real WebView session, Origin and cookie exactly as the canvas UI.
        JSONObject saved = browserCommit(activity, payload);
        check(saved.getInt("code") == 0, "WebView canvas commit failed: " + saved.optString("msg"));
        check("manual".equals(saved.getJSONObject("data").getString("caller")), "Canvas write has wrong principal");
        JSONObject persisted = request(endpoint, cookie, null).getJSONObject("data").getJSONObject("project");
        check(persisted.getInt("revision") > revision, "Canvas revision did not advance");
        check("Saved on Android".equals(persisted.getJSONArray("nodes").getJSONObject(0).getString("content")), "Canvas text was not persisted");
        payload.put("opId", java.util.UUID.randomUUID().toString());
        JSONObject stale = browserCommit(activity, payload);
        check(stale.getInt("code") == 409, "Stale canvas revision was accepted");
        runOnMainSync(() -> activity.web.loadUrl(url + "/canvas/" + canvasID));
        boolean nodeRendered = false;
        for (int attempt = 0; attempt < 180; attempt++) {
            if ("true".equals(evaluate(activity, "Boolean(document.querySelector('[data-node-id=\"mobile-text-1\"]'))"))) { nodeRendered = true; break; }
            Thread.sleep(500);
        }
        check(nodeRendered, "Saved canvas node did not render in the editor");
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "workspace.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            screenshot.recycle();
        }
    }
    private JSONObject browserCommit(MainActivity activity, JSONObject payload) throws Exception {
        String script = "window.__mobileSaveSmoke=null;fetch('/api/ops/canvas.document.commit',{method:'POST',headers:{'Content-Type':'application/json'},body:"
            + JSONObject.quote(payload.toString()) + "}).then(r=>r.json()).then(r=>window.__mobileSaveSmoke=JSON.stringify(r)).catch(e=>window.__mobileSaveSmoke=JSON.stringify({code:-1,msg:String(e)}))";
        evaluate(activity, script);
        for (int attempt = 0; attempt < 90; attempt++) {
            Object result = new org.json.JSONTokener(evaluate(activity, "window.__mobileSaveSmoke")).nextValue();
            if (result instanceof String) return new JSONObject((String)result);
            Thread.sleep(100);
        }
        throw new AssertionError("Canvas commit timed out in WebView");
    }
    private String evaluate(MainActivity activity, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>("null");
        runOnMainSync(() -> activity.web.evaluateJavascript(script, answer -> { value.set(answer); done.countDown(); }));
        check(done.await(10, TimeUnit.SECONDS), "WebView evaluation timed out");
        return value.get();
    }
    private void check(boolean success, String message) { if (!success) throw new AssertionError(message); }
    private int status(String url, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Cookie", cookie);
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }
    private JSONObject request(String url, String cookie, String body) throws Exception {
        return requestMethod(url, cookie, body, body == null ? "GET" : "POST");
    }
    private JSONObject requestMethod(String url, String cookie, String body, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            java.io.InputStream input = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream();
            try (java.io.InputStream stream = input) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
}
