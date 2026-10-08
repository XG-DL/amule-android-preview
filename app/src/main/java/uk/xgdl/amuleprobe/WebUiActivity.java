package uk.xgdl.amuleprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public final class WebUiActivity extends Activity {
    private static final String WEB_URL = "http://127.0.0.1:4713/";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView message;
    private WebView webView;
    private int attempts;
    private boolean opening;
    private final OnBackInvokedCallback backCallback = this::handleBack;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        if (handleStopIntent(getIntent())) return;
        showStarting();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        startCoreService();
    }

    private void startCoreService() {
        Intent service = new Intent(this, AmuleService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        handler.postDelayed(this::checkReady, 400);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) startCoreService();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStopIntent(intent);
    }

    private boolean handleStopIntent(Intent intent) {
        if (intent == null || !AmuleService.ACTION_STOP.equals(intent.getAction())) return false;
        Intent service = new Intent(this, AmuleService.class).setAction(AmuleService.ACTION_STOP);
        startService(service);
        finish();
        return true;
    }

    private void showStarting() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xffe9edf4);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = android.view.Gravity.CENTER;
        progressParams.bottomMargin = 42;
        frame.addView(progress, progressParams);
        message = new TextView(this);
        message.setText("Starting aMule…");
        message.setTextSize(18);
        message.setTextColor(0xff28303b);
        message.setGravity(android.view.Gravity.CENTER);
        message.setPadding(24, 24, 24, 24);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.gravity = android.view.Gravity.CENTER;
        textParams.topMargin = 46;
        frame.addView(message, textParams);
        setContentView(frame);
        applySystemBarInsets(frame);
    }

    private void checkReady() {
        if (isFinishing() || opening) return;
        android.content.SharedPreferences prefs = getSharedPreferences(AmuleService.PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(AmuleService.READY, false)) {
            opening = true;
            String password = prefs.getString(AmuleService.ADMIN_PASSWORD, "");
            new Thread(() -> loginAndOpen(password), "aMule-web-login").start();
            return;
        }
        String error = prefs.getString(AmuleService.LAST_ERROR, "");
        if (!error.isEmpty()) {
            message.setText("aMule could not start\n\n" + error);
            return;
        }
        if (++attempts > 180) {
            message.setText("aMule is taking longer than expected to start. Check the persistent notification.");
            return;
        }
        handler.postDelayed(this::checkReady, 500);
    }

    private void loginAndOpen(String password) {
        try {
            URL endpoint = new URL(WEB_URL + "api/v1/auth/login");
            HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            String body = new JSONObject().put("password", password).toString();
            connection.getOutputStream().write(body.getBytes("UTF-8"));
            int status = connection.getResponseCode();
            String cookie = connection.getHeaderField("Set-Cookie");
            InputStream response = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (response != null) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = response.read(buffer)) != -1) sink.write(buffer, 0, count);
                response.close();
            }
            connection.disconnect();
            if (status != 200 || cookie == null) throw new Exception("Could not establish the local web session (HTTP " + status + ")");

            CountDownLatch cookieSet = new CountDownLatch(1);
            runOnUiThread(() -> {
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                cookies.setCookie(WEB_URL, cookie, accepted -> cookieSet.countDown());
            });
            if (!cookieSet.await(5, TimeUnit.SECONDS)) throw new Exception("Could not initialise the local web session");
            runOnUiThread(this::showWebInterface);
        } catch (Exception error) {
            runOnUiThread(() -> {
                opening = false;
                message.setText("Could not open the aMule interface\n\n" + error.getMessage());
            });
        }
    }

    private void showWebInterface() {
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        FrameLayout container = new FrameLayout(this);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(container);
        applySystemBarInsets(container);
        webView.loadUrl(WEB_URL);
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("aMule is running")
                .setMessage("Keep aMule running in the background, or stop it and close the app?")
                .setNegativeButton("Keep running", (dialog, which) -> finish())
                .setPositiveButton("Stop aMule", (dialog, which) -> {
                    Intent stop = new Intent(this, AmuleService.class).setAction(AmuleService.ACTION_STOP);
                    startService(stop);
                    finish();
                })
                .show();
    }

    private void applySystemBarInsets(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int insetTypes = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
            Insets bars = insets.getInsets(insetTypes);
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return new WindowInsets.Builder(insets)
                    .setInsets(insetTypes, Insets.NONE)
                    .build();
        });
        content.requestApplyInsets();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }
}
