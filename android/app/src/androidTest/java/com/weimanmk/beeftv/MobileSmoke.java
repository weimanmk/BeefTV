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
    private MainActivity activeActivity;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity = (MainActivity)startActivitySync(launch);
            activeActivity = activity;
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
            verifyModelConfig(url, cookie, evidence);
            verifyResources(activity, url, cookie, evidence);
            verifyLibraryCRUD(url, cookie);
            check(request(url + "/api/tasks?pageSize=10", cookie, null).getInt("code") == 0, "Task list failed");
            verifyCanvasSave(activity, url, cookie);
            verifyHistory(activity, url, cookie);
            verifyRoutes(activity, url);
            verifyRecording(activity);
            verifyBlobExport(activity);
            result.putString("stream", "MOBILE_SMOKE_OK: startup, session, project/canvas/config/resource restart persistence, model revision conflicts, WebView multipart upload, resource bytes, folders/assets CRUD, task list, history restore, mobile routes, microphone, 34MB blob export and cancellation cleanup\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            String details = "";
            try {
                captureScreen("failure.png");
                if (activeActivity != null) details = evaluate(activeActivity, "JSON.stringify({url:location.href,text:document.body.innerText.slice(0,3000),nodes:document.querySelectorAll('[data-node-id]').length})");
            } catch (Exception ignored) { }
            result.putString("stream", "MOBILE_SMOKE_FAILED: " + failure + "\nPAGE: " + details + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void verifyModelConfig(String url, String cookie, SharedPreferences evidence) throws Exception {
        String endpoint = url + "/api/workspace/model-config";
        JSONObject before = data(request(endpoint, cookie, null));
        JSONObject config = before.getJSONObject("config");
        String previous = evidence.getString("configMarker", null);
        if (previous != null) check(previous.equals(config.optString("systemPrompt")), "Model settings lost on restart");
        String marker = "Android model config persistence smoke " + java.util.UUID.randomUUID();
        config.put("systemPrompt", marker);
        long revision = before.getLong("revision");
        JSONObject payload = new JSONObject().put("config", config).put("expectedRevision", revision);
        JSONObject saved = data(requestMethod(endpoint, cookie, payload.toString(), "PUT"));
        check(saved.getLong("revision") > revision, "Model config revision did not advance");
        config.put("systemPrompt", marker + " stale");
        check(requestMethod(endpoint, cookie, payload.toString(), "PUT").getInt("code") == 409, "Stale model settings overwrote new settings");
        check(marker.equals(data(request(endpoint, cookie, null)).getJSONObject("config").getString("systemPrompt")), "Model config was not persisted");
        check(evidence.edit().putString("configMarker", marker).commit(), "Cannot record config evidence");
    }
    private void verifyResources(MainActivity activity, String url, String cookie, SharedPreferences evidence) throws Exception {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";
        String id = evidence.getString("resource", null);
        if (id == null) {
            JSONObject uploaded = browserJSON(activity, "(async()=>{const bytes=Uint8Array.from(atob(" + JSONObject.quote(png) + "),c=>c.charCodeAt(0));const form=new FormData();form.append('kind','image');form.append('width','1');form.append('height','1');form.append('file',new Blob([bytes],{type:'image/png'}),'android-smoke.png');return await(await fetch('/api/resources',{method:'POST',body:form})).json()})()");
            JSONObject resource = data(uploaded).getJSONObject("resource");
            id = resource.getString("id");
            check(evidence.edit().putString("resource", id).commit(), "Cannot save resource evidence");
        }
        check(data(request(url + "/api/resources/" + id, cookie, null)).getJSONObject("resource").getString("id").equals(id), "Resource metadata lost on restart");
        HttpURLConnection connection = (HttpURLConnection)new URL(url + "/api/resources/" + id + "/file").openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setRequestProperty("Cookie", cookie);
        try {
            check(connection.getResponseCode() == 200, "Resource download failed");
            byte[] expected = android.util.Base64.decode(png, android.util.Base64.DEFAULT);
            try (java.io.InputStream input = connection.getInputStream()) {
                check(java.util.Arrays.equals(expected, input.readAllBytes()), "Resource download bytes changed");
            }
        } finally { connection.disconnect(); }
        check(status(url + "/api/resources/" + id + "/file", "") == 401, "Resource exposed without session");
        data(request(url + "/api/resources/storage-usage", cookie, null));
    }
    private void verifyLibraryCRUD(String url, String cookie) throws Exception {
        String base = url + "/api/asset-folders";
        JSONObject folder = data(request(base, cookie, "{\"name\":\"Android smoke folder\"}")).getJSONObject("folder");
        String folderID = folder.getString("id");
        data(requestMethod(base + "/" + folderID, cookie, "{\"name\":\"Android renamed folder\"}", "PATCH"));
        check(data(request(base, cookie, null)).getJSONArray("folders").toString().contains("Android renamed folder"), "Folder rename missing from library");
        String id = "mobile-asset-" + java.util.UUID.randomUUID();
        JSONObject asset = new JSONObject().put("id", id).put("kind", "text").put("title", "Android text asset").put("coverUrl", "")
            .put("tags", new org.json.JSONArray()).put("folderId", folderID).put("data", new JSONObject().put("content", "Android asset contents"));
        String endpoint = url + "/api/assets/" + id;
        data(requestMethod(endpoint, cookie, new JSONObject().put("asset", asset).toString(), "PUT"));
        check("Android asset contents".equals(data(request(endpoint, cookie, null)).getJSONObject("asset").getJSONObject("data").getString("content")), "Asset content mismatch");
        data(requestMethod(endpoint, cookie, null, "DELETE"));
        check(status(endpoint, cookie) == 404, "Deleted asset still accessible");
        data(requestMethod(base + "/" + folderID, cookie, null, "DELETE"));
        String canvasFolder = "mobile-folder-" + java.util.UUID.randomUUID();
        String canvasEndpoint = url + "/api/canvas-folders/" + canvasFolder;
        data(requestMethod(canvasEndpoint, cookie, new JSONObject().put("folder", new JSONObject().put("id", canvasFolder).put("name", "Android canvas folder")).toString(), "PUT"));
        check(data(request(url + "/api/canvas-folders", cookie, null)).getJSONArray("folders").toString().contains(canvasFolder), "Canvas folder not listed");
        data(requestMethod(canvasEndpoint, cookie, null, "DELETE"));
    }
    private void verifyHistory(MainActivity activity, String url, String cookie) throws Exception {
        String endpoint = url + "/api/canvas-projects/mobile-canvas-save-smoke";
        JSONObject before = data(request(endpoint, cookie, null)).getJSONObject("project");
        org.json.JSONArray snapshots = data(request(endpoint + "/history", cookie, null)).getJSONArray("snapshots");
        check(snapshots.length() > 0, "Canvas history missing");
        String snapshotID = snapshots.getJSONObject(0).getString("id");
        JSONObject history = data(request(endpoint + "/history/" + snapshotID, cookie, null)).getJSONObject("project");
        JSONObject restored = data(request(endpoint + "/history/" + snapshotID + "/restore", cookie, new JSONObject().put("revision", before.getInt("revision")).toString())).getJSONObject("project");
        check(restored.getInt("revision") > before.getInt("revision"), "Restore did not advance revision");
        JSONObject actual = data(request(endpoint, cookie, null)).getJSONObject("project");
        check(history.getString("title").equals(actual.getString("title")), "Restored title mismatch");
        check(history.getJSONArray("nodes").toString().equals(actual.getJSONArray("nodes").toString()), "Restored nodes mismatch");
        JSONObject payload = new JSONObject().put("opId", java.util.UUID.randomUUID().toString()).put("params", new JSONObject()
            .put("canvasId", "mobile-canvas-save-smoke").put("expectedRevision", actual.getInt("revision")).put("document", new JSONObject()
                .put("title", before.getString("title")).put("nodes", before.getJSONArray("nodes")).put("connections", before.getJSONArray("connections"))));
        data(browserCommit(activity, payload));
        JSONObject after = data(request(endpoint, cookie, null)).getJSONObject("project");
        check(before.getJSONArray("nodes").toString().equals(after.getJSONArray("nodes").toString()), "History test failed to return to original nodes: " + after);
    }
    private void verifyRoutes(MainActivity activity, String url) throws Exception {
        check(java.util.Arrays.asList(MainActivity.fileMimeTypes(new String[]{".zip,.json", "image/*"})).contains("application/zip"), "ZIP file picker filter missing");
        String[][] routes = {{"/canvas", ".lib-tv-project-page"}, {"/assets", ".assets-library-page"}, {"/settings", ".settings-page"}, {"/canvas/mobile-canvas-save-smoke", "[data-node-id='mobile-text-1']"}};
        for (String[] route : routes) {
            runOnMainSync(() -> activity.web.loadUrl(url + route[0]));
            boolean ready = false;
            for (int attempt = 0; attempt < 120; attempt++) {
                // Route identity and real content are required; the shared shell alone is insufficient.
                String script = "Boolean(location.pathname===" + JSONObject.quote(route[0]) + " && document.querySelector(" + JSONObject.quote(route[1]) + "))";
                if ("true".equals(evaluate(activity, script))) { ready = true; break; }
                Thread.sleep(250);
            }
            check(ready, "Mobile route failed to render: " + route[0]);
            check("true".equals(evaluate(activity, "document.documentElement.scrollWidth <= innerWidth+2")), "Horizontal overflow: " + route[0]);
            Thread.sleep(1000);
            captureScreen("route-" + route[0].replace('/', '_') + ".png");
        }
        check("true".equals(evaluate(activity, "!document.querySelector('.canvas-topbar-agent-button')")), "Unavailable desktop assistant shown on mobile");
    }
    private void captureScreen(String name) throws Exception {
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) return;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { screenshot.recycle(); }
    }
    private void verifyRecording(MainActivity activity) throws Exception {
        JSONObject recorded = browserJSON(activity, "(async()=>{const stream=await navigator.mediaDevices.getUserMedia({audio:true});try{return await new Promise((resolve,reject)=>{const chunks=[];const r=new MediaRecorder(stream);r.ondataavailable=e=>chunks.push(e.data);r.onerror=reject;r.onstop=()=>resolve({code:0,data:{bytes:new Blob(chunks).size}});r.start();setTimeout(()=>r.stop(),500)})}finally{stream.getTracks().forEach(t=>t.stop())}})()");
        check(data(recorded).getInt("bytes") > 0, "Microphone produced no recording data");
    }
    private void verifyBlobExport(MainActivity activity) throws Exception {
        long size = 34L * 1024 * 1024 + 17;
        evaluate(activity, "(()=>{const b=new Uint8Array(" + size + ");for(let i=0;i<b.length;i++)b[i]=i%251;window.__exportSmokeURL=URL.createObjectURL(new Blob([b],{type:'application/zip'}));const a=document.createElement('a');a.href=window.__exportSmokeURL;a.download='android-export-smoke.zip';document.body.append(a);a.click();a.remove()})()");
        java.io.File exported = null;
        for (int attempt = 0; attempt < 600; attempt++) {
            java.io.File[] files = getTargetContext().getCacheDir().listFiles((dir, name) -> name.startsWith("beeftv-export-"));
            if (files != null) for (java.io.File file : files) if (file.length() == size) exported = file;
            if (exported != null) break;
            Thread.sleep(100);
        }
        check(exported != null, "34MB blob was not transferred to native export cache");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(exported, "r")) {
            for (long offset : new long[]{0, 262143, 262144, 33554432, size - 1}) {
                file.seek(offset); check(file.readUnsignedByte() == offset % 251, "Blob export byte mismatch at " + offset);
            }
        }
        // Cancel the actual system save picker and ensure private temporary data is removed.
        Thread.sleep(1000);
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        for (int attempt = 0; attempt < 50 && exported.exists(); attempt++) Thread.sleep(100);
        check(!exported.exists(), "Cancelled export left temporary data");
        evaluate(activity, "URL.revokeObjectURL(window.__exportSmokeURL);delete window.__exportSmokeURL");
    }
    private JSONObject data(JSONObject envelope) throws Exception {
        check(envelope.getInt("code") == 0, "API failed: " + envelope.optString("msg"));
        return envelope.getJSONObject("data");
    }
    private JSONObject browserJSON(MainActivity activity, String expression) throws Exception {
        evaluate(activity, "window.__parityResult=null;Promise.resolve(" + expression + ").then(v=>window.__parityResult=JSON.stringify(v)).catch(e=>window.__parityResult=JSON.stringify({code:-1,msg:String(e)}))");
        for (int attempt = 0; attempt < 300; attempt++) {
            Object result = new org.json.JSONTokener(evaluate(activity, "window.__parityResult")).nextValue();
            if (result instanceof String) return new JSONObject((String)result);
            Thread.sleep(100);
        }
        throw new AssertionError("WebView parity request timed out");
    }
    private void verifyCanvasSave(MainActivity activity, String url, String cookie) throws Exception {
        String canvasID = "mobile-canvas-save-smoke";
        String endpoint = url + "/api/canvas-projects/" + canvasID;
        if (status(endpoint, cookie) == 200) {
            JSONObject existing = request(endpoint, cookie, null).getJSONObject("data").getJSONObject("project");
            check("Saved on Android".equals(existing.getJSONArray("nodes").getJSONObject(0).getJSONObject("metadata").getString("content")), "Canvas nodes lost after process restart");
        } else {
            JSONObject empty = new JSONObject().put("id", canvasID).put("title", "Android canvas save")
                .put("revision", 0).put("nodes", new org.json.JSONArray()).put("connections", new org.json.JSONArray());
            JSONObject created = requestMethod(endpoint, cookie, new JSONObject().put("project", empty).toString(), "PUT");
            check(created.getInt("code") == 0, "Initial canvas creation failed: " + created.optString("msg"));
        }
        JSONObject before = request(endpoint, cookie, null).getJSONObject("data").getJSONObject("project");
        int revision = before.getInt("revision");
        JSONObject text = new JSONObject().put("id", "mobile-text-1").put("type", "text").put("title", "Text node")
            .put("metadata", new JSONObject().put("content", "Saved on Android")).put("position", new JSONObject().put("x", 80).put("y", 160)).put("width", 240).put("height", 180);
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
        check("Saved on Android".equals(persisted.getJSONArray("nodes").getJSONObject(0).getJSONObject("metadata").getString("content")), "Canvas text was not persisted");
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
        check("true".equals(evaluate(activity, "document.querySelector('[data-node-id=\"mobile-text-1\"]').innerText.includes('Saved on Android')")), "Saved text did not render inside the node");
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
