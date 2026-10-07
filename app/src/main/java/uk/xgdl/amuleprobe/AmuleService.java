package uk.xgdl.amuleprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.text.format.Formatter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AmuleService extends Service {
    public static final String ACTION_STOP = "uk.xgdl.amuleprobe.STOP";
    public static final String PREFS = "amule_runtime";
    public static final String READY = "ready";
    public static final String ADMIN_PASSWORD = "admin_password";
    private static final String EC_PASSWORD = "ec_password";
    public static final String LAST_ERROR = "last_error";

    private static final String TAG = "aMuleService";
    private static final String CHANNEL = "amule_core";
    private static final int NOTIFICATION_ID = 1;
    private static final int API_FAILURE_THRESHOLD = 3;
    private static final long HEALTH_CHECK_SECONDS = 5;
    private static final long STORAGE_CHECK_SECONDS = 60;
    private static final long BASE_RECOVERY_DELAY_MS = 5_000;
    private static final long MAX_RECOVERY_DELAY_MS = 5 * 60_000;
    private static final long LOW_STORAGE_THRESHOLD_BYTES = 1024L * 1024L * 1024L;

    private volatile Process daemon;
    private volatile Process api;
    private volatile boolean starting;
    private volatile boolean recoveringApi;
    private volatile boolean stopRequested;
    private volatile ScheduledExecutorService downloadExporter;
    private volatile ScheduledExecutorService healthMonitor;
    private volatile boolean coreReady;
    private volatile boolean lowStorage;
    private volatile long availableStorageBytes;
    private volatile String networkStatus = "Internet available · aMule is running";
    private volatile String lastNotificationText;
    private int apiUnhealthyChecks;
    private int recoveryFailures;
    private volatile long nextRecoveryAtMillis;
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("aMule-UPnP-discovery");
            multicastLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCore();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopRequested = false;

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        watchDefaultNetwork();
        startHealthMonitor();

        synchronized (this) {
            if (!starting && (daemon == null || !daemon.isAlive())) {
                starting = true;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(READY, false).putString(LAST_ERROR, "").apply();
                new Thread(this::startCore, "aMule-startup").start();
            }
        }
        return START_STICKY;
    }

    private void startCore() {
        try {
            if (stopRequested) return;
            File config = new File(getFilesDir(), "amule");
            File downloads = new File(config, "Incoming");
            File temp = new File(config, "Temp");
            if (!config.mkdirs() && !config.isDirectory()) throw new IOException("Cannot create aMule data folder");
            downloads.mkdirs();
            temp.mkdirs();
            // Wi-Fi filters multicast packets unless the app holds this lock;
            // pupnp uses multicast SSDP discovery to find the UPnP gateway.
            // Hold it only when the saved aMule configuration enables UPnP.
            if (isUpnpEnabled(config) && multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
            copyAssets("webui", new File(getFilesDir(), "webui"));

            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String ecPassword = prefs.getString(EC_PASSWORD, null);
            if (ecPassword == null || ecPassword.isEmpty()) ecPassword = randomSecret();
            String adminPassword = prefs.getString(ADMIN_PASSWORD, null);
            if (adminPassword == null || adminPassword.isEmpty()) adminPassword = randomSecret();
            if (!prefs.edit().putString(EC_PASSWORD, ecPassword)
                    .putString(ADMIN_PASSWORD, adminPassword).commit()) {
                throw new IOException("Could not save the aMule login credentials");
            }
            writePrivateFile(new File(config, "amuleapi.conf"),
                    "[Server]\nBindAddress=127.0.0.1\nPort=4713\nStaticRoot="
                            + new File(getFilesDir(), "webui").getAbsolutePath()
                            + "\n[EC]\nHost=127.0.0.1\nPort=4712\nPassword=" + ecPassword
                            + "\nEncryption=1\n");

            File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
            File daemonBinary = new File(nativeDir, "libamuled.so");
            File apiBinary = new File(nativeDir, "libamuleapi.so");
            if (!daemonBinary.canExecute() || !apiBinary.canExecute()) {
                throw new IOException("aMule binaries are missing or not executable");
            }

            File daemonLog = new File(config, "amuled.log");
            ProcessBuilder daemonBuilder = new ProcessBuilder(
                    daemonBinary.getAbsolutePath(), "--config-dir", config.getAbsolutePath(),
                    "--ec-config", "--enable-stdin", "--log-stdout");
            daemonBuilder.redirectErrorStream(true).redirectOutput(daemonLog);
            if (stopRequested) return;
            daemon = daemonBuilder.start();
            daemon.getOutputStream().write((ecPassword + "\n").getBytes("UTF-8"));
            daemon.getOutputStream().close();

            if (!waitForPort(4712, 30000)) throw new IOException("aMule daemon did not open its control port");

            ProcessBuilder setAdmin = new ProcessBuilder(apiBinary.getAbsolutePath(),
                    "--config-dir", config.getAbsolutePath(), "--set-admin-pass=" + adminPassword);
            setAdmin.redirectErrorStream(true).redirectOutput(new File(config, "api-setup.log"));
            Process setup = setAdmin.start();
            if (!setup.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || setup.exitValue() != 0) {
                throw new IOException("Could not initialise the aMule web login");
            }

            startWebApiWithRetry(apiBinary, config);

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(ADMIN_PASSWORD, adminPassword).putBoolean(READY, true)
                    .putString(LAST_ERROR, "").apply();
            startDownloadExporter(downloads);
            coreReady = true;
            resetRecoveryBackoff();
            checkStorageSafely();
            updateNetworkNotification();
        } catch (Exception error) {
            // A partial startup must not leave the daemon running without its web interface.
            stopProcess(api);
            stopProcess(daemon);
            api = null;
            daemon = null;
            coreReady = false;
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            if (!stopRequested) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(READY, false).putString(LAST_ERROR, error.getMessage()).apply();
                updateNotification("aMule could not start: " + error.getMessage());
                recordRecoveryFailure();
            }
        } finally {
            starting = false;
        }
    }

    private void startWebApiWithRetry(File apiBinary, File config) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) throw new IOException("aMule startup was stopped");
            if (daemon == null || !daemon.isAlive()) {
                throw new IOException("aMule daemon stopped before its web interface was ready");
            }

            attempt++;
            ProcessBuilder apiBuilder = new ProcessBuilder(apiBinary.getAbsolutePath(),
                    "--config-dir", config.getAbsolutePath(), "--foreground");
            apiBuilder.redirectErrorStream(true).redirectOutput(new File(config, "amuleapi.log"));
            api = apiBuilder.start();
            if (stopRequested) {
                stopProcess(api);
                api = null;
                throw new IOException("aMule startup was stopped");
            }

            long attemptTimeout = Math.min(30_000, deadline - System.currentTimeMillis());
            if (waitForPort(4713, attemptTimeout)) {
                android.util.Log.i(TAG, "aMule web interface ready on attempt " + attempt);
                return;
            }

            android.util.Log.w(TAG, "aMule web interface did not connect to the daemon on attempt " + attempt);
            stopProcess(api);
            api = null;
            if (daemon == null || !daemon.isAlive()) {
                throw new IOException("aMule daemon stopped before its web interface was ready");
            }
            Thread.sleep(1000);
        }

        throw new IOException("aMule web interface did not start after retrying the daemon control connection");
    }

    private boolean waitForPort(int port, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (daemon != null && !daemon.isAlive()) return false;
            if (port == 4713 && api != null && !api.isAlive()) return false;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                return true;
            } catch (IOException ignored) {
                Thread.sleep(300);
            }
        }
        return false;
    }

    private synchronized void startHealthMonitor() {
        if (healthMonitor != null && !healthMonitor.isShutdown()) return;
        healthMonitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "aMule-health-monitor");
            thread.setDaemon(true);
            return thread;
        });
        healthMonitor.scheduleWithFixedDelay(this::checkCoreHealthSafely,
                HEALTH_CHECK_SECONDS, HEALTH_CHECK_SECONDS, TimeUnit.SECONDS);
        healthMonitor.scheduleWithFixedDelay(this::checkStorageSafely,
                STORAGE_CHECK_SECONDS, STORAGE_CHECK_SECONDS, TimeUnit.SECONDS);
    }

    private void checkCoreHealthSafely() {
        try {
            checkCoreHealth();
        } catch (RuntimeException error) {
            android.util.Log.e(TAG, "Could not check aMule process health", error);
        }
    }

    private void checkCoreHealth() {
        if (stopRequested || starting || recoveringApi) return;

        Process currentDaemon = daemon;
        if (currentDaemon == null || !currentDaemon.isAlive()) {
            if (System.currentTimeMillis() >= nextRecoveryAtMillis) restartCoreAfterFailure();
            return;
        }

        Process currentApi = api;
        if (coreReady && currentApi != null && currentApi.isAlive() && isPortOpen(4713)) {
            apiUnhealthyChecks = 0;
            return;
        }

        if (++apiUnhealthyChecks >= API_FAILURE_THRESHOLD
                && System.currentTimeMillis() >= nextRecoveryAtMillis) {
            restartWebApiAfterFailure();
        }
    }

    private synchronized void restartCoreAfterFailure() {
        if (stopRequested || starting || recoveringApi
                || System.currentTimeMillis() < nextRecoveryAtMillis) return;

        android.util.Log.w(TAG, "aMule daemon stopped unexpectedly; restarting the core");
        coreReady = false;
        starting = true;
        apiUnhealthyChecks = 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(READY, false).apply();
        updateNotification("aMule stopped · restarting");
        stopDownloadExporter();
        stopProcess(api);
        stopProcess(daemon);
        api = null;
        daemon = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        new Thread(this::startCore, "aMule-core-recovery").start();
    }

    private synchronized void restartWebApiAfterFailure() {
        if (stopRequested || starting || recoveringApi
                || System.currentTimeMillis() < nextRecoveryAtMillis) return;

        android.util.Log.w(TAG, "aMule web interface stopped responding; restarting its API process");
        apiUnhealthyChecks = 0;
        recoveringApi = true;
        coreReady = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(READY, false).apply();
        updateNotification("aMule web interface disconnected · reconnecting");
        new Thread(this::recoverWebApi, "aMule-api-recovery").start();
    }

    private void recoverWebApi() {
        try {
            if (stopRequested) return;
            File config = new File(getFilesDir(), "amule");
            File apiBinary = new File(getApplicationInfo().nativeLibraryDir, "libamuleapi.so");
            if (daemon == null || !daemon.isAlive()) {
                throw new IOException("aMule daemon stopped during web interface recovery");
            }
            if (!apiBinary.canExecute()) throw new IOException("aMule web API binary is missing");

            stopProcess(api);
            api = null;
            if (stopRequested) return;
            startWebApiWithRetry(apiBinary, config);
            if (daemon == null || !daemon.isAlive()) {
                throw new IOException("aMule daemon stopped during web interface recovery");
            }

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(READY, true).putString(LAST_ERROR, "").apply();
            coreReady = true;
            resetRecoveryBackoff();
            updateNetworkNotification();
            android.util.Log.i(TAG, "aMule web interface recovered");
        } catch (Exception error) {
            coreReady = false;
            if (!stopRequested) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(READY, false).putString(LAST_ERROR, error.getMessage()).apply();
                updateNotification("Web interface recovery failed: " + error.getMessage());
                recordRecoveryFailure();
                android.util.Log.e(TAG, "Could not recover the aMule web interface", error);
            }
        } finally {
            recoveringApi = false;
        }
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private synchronized void recordRecoveryFailure() {
        recoveryFailures++;
        int shift = Math.min(recoveryFailures - 1, 6);
        long delay = Math.min(MAX_RECOVERY_DELAY_MS, BASE_RECOVERY_DELAY_MS << shift);
        nextRecoveryAtMillis = System.currentTimeMillis() + delay;
        android.util.Log.w(TAG, "Will retry aMule recovery in " + (delay / 1000) + " seconds");
    }

    private synchronized void resetRecoveryBackoff() {
        recoveryFailures = 0;
        nextRecoveryAtMillis = 0;
    }

    private void stopCore() {
        stopRequested = true;
        coreReady = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(READY, false).apply();
        ScheduledExecutorService monitor = healthMonitor;
        healthMonitor = null;
        if (monitor != null) monitor.shutdownNow();
        stopDownloadExporter();
        stopProcess(api);
        stopProcess(daemon);
        api = null;
        daemon = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void startDownloadExporter(File incoming) {
        stopDownloadExporter();
        ScheduledExecutorService exporter = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "aMule-download-export");
            thread.setDaemon(true);
            return thread;
        });
        downloadExporter = exporter;
        CompletedDownloadExporter scanner = new CompletedDownloadExporter(this, incoming);
        exporter.scheduleWithFixedDelay(() -> {
            try {
                scanner.scan();
            } catch (RuntimeException error) {
                android.util.Log.e("aMuleExport", "Could not scan completed downloads", error);
            }
        }, 10, 15, TimeUnit.SECONDS);
    }

    private void stopDownloadExporter() {
        ScheduledExecutorService exporter = downloadExporter;
        downloadExporter = null;
        if (exporter != null) exporter.shutdownNow();
    }

    private boolean isUpnpEnabled(File config) throws IOException {
        File preferences = new File(config, "amule.conf");
        if (!preferences.isFile()) return false;
        boolean inEmuleSection = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(preferences), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    inEmuleSection = "[eMule]".equals(line);
                } else if (inEmuleSection && line.startsWith("UPnPEnabled=")) {
                    return "1".equals(line.substring("UPnPEnabled=".length()).trim());
                }
            }
        }
        return false;
    }

    private static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private String randomSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(48);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private void writePrivateFile(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setReadable(true, true) || !file.setWritable(true, true)) {
            throw new IOException("Could not restrict permissions on " + file.getName());
        }
    }

    private void copyAssets(String assetDir, File destination) throws IOException {
        if (!destination.exists() && !destination.mkdirs()) throw new IOException("Cannot create web assets folder");
        String[] children = getAssets().list(assetDir);
        if (children == null) return;
        for (String child : children) {
            String path = assetDir + "/" + child;
            File target = new File(destination, child);
            String[] nested = getAssets().list(path);
            if (nested != null && nested.length > 0) {
                copyAssets(path, target);
            } else {
                try (InputStream in = getAssets().open(path); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "aMule transfers",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, MainActivity.class).setAction(ACTION_STOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent action = PendingIntent.getActivity(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("aMule")
                .setContentText("Starting aMule…")
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action)
                .build();
    }

    private void updateNotification(String text) {
        if (text.equals(lastNotificationText)) return;
        lastNotificationText = text;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MainActivity.class).setAction(ACTION_STOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent action = PendingIntent.getActivity(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("aMule").setContentText(text).setContentIntent(content).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action).build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private void watchDefaultNetwork() {
        if (connectivityManager == null || networkCallback != null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                updateNetworkNotification();
            }

            @Override
            public void onLost(Network network) {
                updateNetworkNotification();
            }

            @Override
            public void onUnavailable() {
                updateNetworkNotification();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                updateNetworkNotification();
            }
        };

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            updateNetworkNotification();
        } catch (RuntimeException error) {
            android.util.Log.w(TAG, "Could not monitor network changes", error);
            networkCallback = null;
        }
    }

    private void updateNetworkNotification() {
        if (!coreReady) return;

        Network active = connectivityManager == null ? null : connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null || connectivityManager == null
                ? null : connectivityManager.getNetworkCapabilities(active);
        if (capabilities == null) {
            networkStatus = "No network connection";
        } else if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            networkStatus = "Network changing · checking internet";
        } else {
            networkStatus = "Internet available · aMule is running";
        }
        updateRunningNotification();
    }

    private void checkStorageSafely() {
        if (!coreReady) return;
        try {
            long usableBytes = new File(getFilesDir(), "amule").getUsableSpace();
            availableStorageBytes = usableBytes;
            lowStorage = usableBytes < LOW_STORAGE_THRESHOLD_BYTES;
            updateRunningNotification();
        } catch (RuntimeException error) {
            android.util.Log.e(TAG, "Could not check available storage", error);
        }
    }

    private void updateRunningNotification() {
        if (!coreReady) return;
        String status = lowStorage
                ? "Low storage · " + Formatter.formatShortFileSize(this, availableStorageBytes) + " free"
                : networkStatus;
        updateNotification(status);
    }

    @Override
    public void onDestroy() {
        stopCore();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
