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
            result.putString("stream", "MOBILE_SMOKE_OK: native startup, session protection, project persistence and rendered workspace\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "MOBILE_SMOKE_FAILED: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void check(boolean success, String message) { if (!success) throw new AssertionError(message); }
    private int status(String url, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Cookie", cookie);
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }
    private JSONObject request(String url, String cookie, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
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
