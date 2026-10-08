package uk.xgdl.amuleprobe;

import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises MediaStore export failures on a disposable Android test device. */
@RunWith(AndroidJUnit4.class)
public final class CompletedDownloadExporterTest {
    private static final String PREFIX = "CodexExportTest-";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/aMule/Complete/";

    private Context context;
    private File incoming;
    private ServerSocket apiSocket;
    private Thread apiThread;
    private volatile boolean serving;
    private final Set<String> sharedDirectories = new HashSet<>();

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        incoming = new File(new File(context.getFilesDir(), "amule"), "Incoming");
        assertTrue(incoming.mkdirs() || incoming.isDirectory());
        cleanMediaStoreRows();
        for (File file : filesWithPrefix()) assertTrue(file.delete());
        context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                .putString(AmuleService.ADMIN_PASSWORD, "export-test-password").commit();
        apiSocket = new ServerSocket(4713, 8, InetAddress.getByName("127.0.0.1"));
        serving = true;
        apiThread = new Thread(this::serveApi, "export-test-api");
        apiThread.start();
    }

    @After
    public void tearDown() throws Exception {
        serving = false;
        if (apiSocket != null) apiSocket.close();
        if (apiThread != null) apiThread.join(2000);
        cleanMediaStoreRows();
        for (File file : filesWithPrefix()) file.delete();
        context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void testDuplicateDestinationNameKeepsBothFiles() throws Exception {
        String name = PREFIX + "duplicate.txt";
        insertDownload(name, "older contents".getBytes(StandardCharsets.UTF_8));
        File source = source(name, "new completed contents".getBytes(StandardCharsets.UTF_8));

        scanToStability(new CompletedDownloadExporter(context, incoming));

        assertFalse("The private source should be moved after publication", source.exists());
        List<String> names = namesInDownloads();
        assertTrue("Published file should retain its requested name " + name + "; got " + names,
                names.contains(name));
        assertTrue(names.contains(PREFIX + "duplicate (1).txt"));
    }

    @Test
    public void testNoSpaceDuringCopyKeepsOriginalAndRemovesPendingRow() throws Exception {
        String name = PREFIX + "no-space.bin";
        File source = source(name, "completed source remains intact".getBytes(StandardCharsets.UTF_8));
        CompletedDownloadExporter.OutputStreamOpener noSpace = (resolver, destination) -> {
            throw new IOException("No space left on device (simulated)");
        };

        scanToStability(new CompletedDownloadExporter(context, incoming, noSpace));

        assertTrue("The private original must survive a failed copy", source.isFile());
        assertEquals("A failed export must not leave a MediaStore row", 0, countRows(name));
    }

    @Test
    public void testPublishedFileMarkerRecoversAfterInterruption() throws Exception {
        String name = PREFIX + "interrupted.txt";
        byte[] contents = "published before simulated process interruption".getBytes(StandardCharsets.UTF_8);
        File source = source(name, contents);
        insertDownload(name, contents);
        String marker = source.length() + ":" + source.lastModified();
        String key = "exported_" + sha256(source.getAbsolutePath());
        context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, marker).commit();

        new CompletedDownloadExporter(context, incoming).scan();

        assertFalse("Recovery should remove the private duplicate", source.exists());
        assertEquals("Recovery should keep one published copy", 1, countRows(name));
        assertNull(context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE)
                .getString(key, null));
    }

    @Test
    public void testInterruptedCopyResumesSamePendingEntry() throws Exception {
        String name = PREFIX + "interrupted-copy-" + UUID.randomUUID() + ".bin";
        byte[] contents = new byte[160_000];
        for (int i = 0; i < contents.length; i++) contents[i] = (byte) (i * 31);
        File source = source(name, contents);
        boolean[] interrupted = {false};
        CompletedDownloadExporter.OutputStreamOpener crashDuringWrite = (resolver, destination) -> {
            OutputStream output = resolver.openOutputStream(destination, "w");
            assertNotNull(output);
            return new OutputStream() {
                @Override public void write(int value) throws IOException { output.write(value); }

                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (!interrupted[0]) {
                        output.write(bytes, offset, Math.min(128, length));
                        interrupted[0] = true;
                        throw new SimulatedProcessDeath();
                    }
                    output.write(bytes, offset, length);
                }

                @Override public void flush() throws IOException { output.flush(); }
                @Override public void close() throws IOException { output.close(); }
            };
        };

        try {
            scanToStability(new CompletedDownloadExporter(context, incoming, crashDuringWrite));
            throw new AssertionError("Expected simulated process interruption");
        } catch (SimulatedProcessDeath expected) {
            assertTrue("Interrupted export must leave the Incoming original", source.isFile());
        }

        scanToStability(new CompletedDownloadExporter(context, incoming));

        assertFalse("Retry should remove the original only after publishing", source.exists());
        List<String> names = namesInDownloads();
        assertEquals("Retry should publish exactly one file: " + names, 1, names.size());
        assertTrue("Expected " + name + "; got " + names, names.contains(name));
    }

    @Test
    public void testSeparateWriterProcessKillResumesPendingExport() throws Exception {
        String name = PREFIX + "killed-process-" + UUID.randomUUID() + ".bin";
        byte[] contents = new byte[160_000];
        for (int i = 0; i < contents.length; i++) contents[i] = (byte) (i * 17);
        File source = source(name, contents);
        CountDownLatch killedDuringWrite = new CountDownLatch(1);
        CountDownLatch recoveryFinished = new CountDownLatch(1);
        Context instrumentationContext = context;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receivedContext, Intent intent) {
                if (ExportInterruptionTestService.ACTION_CRASH_POINT_REACHED.equals(intent.getAction())) {
                    killedDuringWrite.countDown();
                } else if (ExportInterruptionTestService.ACTION_RECOVERY_FINISHED.equals(intent.getAction())) {
                    recoveryFinished.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ExportInterruptionTestService.ACTION_CRASH_POINT_REACHED);
        filter.addAction(ExportInterruptionTestService.ACTION_RECOVERY_FINISHED);
        instrumentationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        Intent crashWriter = new Intent()
                .setClassName(context.getPackageName(), ExportInterruptionTestService.class.getName())
                .setAction(ExportInterruptionTestService.ACTION_CRASH_DURING_EXPORT)
                .putExtra(ExportInterruptionTestService.EXTRA_NAME, name);
        try {
            assertNotNull(context.startService(crashWriter));
            assertTrue("The writer did not reach the mid-copy kill point",
                    killedDuringWrite.await(20, TimeUnit.SECONDS));
            assertTrue("The Incoming original must survive the process death", source.isFile());
            String writerPid = testWriterProcessId();
            assertNotNull("The dedicated writer process should be running at the pause point", writerPid);
            Process.killProcess(Integer.parseInt(writerPid));

            long processDeadline = SystemClock.elapsedRealtime() + 10_000;
            while (testWriterProcessId() != null && SystemClock.elapsedRealtime() < processDeadline) {
                SystemClock.sleep(100);
            }
            assertNull("The dedicated writer process should have been killed",
                    testWriterProcessId());

            Intent recover = new Intent()
                    .setClassName(context.getPackageName(), ExportInterruptionTestService.class.getName())
                    .setAction(ExportInterruptionTestService.ACTION_RECOVER_EXPORT);
            assertNotNull(context.startService(recover));
            assertTrue("The restarted app process did not finish export recovery",
                    recoveryFinished.await(45, TimeUnit.SECONDS));
        } finally {
            instrumentationContext.unregisterReceiver(receiver);
        }

        assertFalse("Retry should remove the source only after publication", source.exists());
        List<String> names = namesInDownloads();
        assertTrue("Recovered file should keep the requested name: " + names, names.contains(name));
        assertFalse("Recovery must not create a suffixed duplicate",
                names.contains(name.substring(0, name.length() - 4) + " (1).bin"));
    }

    private String testWriterProcessId() throws Exception {
        ParcelFileDescriptor processList = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand("pidof " + context.getPackageName()
                        + ":exportinterruption");
        try (FileInputStream input = new FileInputStream(processList.getFileDescriptor())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            String[] processIds = output.toString(StandardCharsets.UTF_8.name()).trim().split("\\s+");
            return processIds.length == 0 || processIds[0].isEmpty() ? null : processIds[0];
        } finally {
            processList.close();
        }
    }

    private void scanToStability(CompletedDownloadExporter exporter) {
        exporter.scan();
        exporter.scan();
        exporter.scan();
    }

    private File source(String name, byte[] contents) throws IOException {
        File file = new File(incoming, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(contents);
        }
        return file;
    }

    private Uri insertDownload(String name, byte[] contents) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        assertNotNull(uri);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
            assertNotNull(output);
            output.write(contents);
        }
        ContentValues visible = new ContentValues();
        visible.put(MediaStore.Downloads.IS_PENDING, 0);
        assertEquals(1, context.getContentResolver().update(uri, visible, null, null));
        return uri;
    }

    private List<String> namesInDownloads() {
        List<String> names = new ArrayList<>();
        String[] projection = {MediaStore.Downloads.DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), projection,
                MediaStore.Downloads.RELATIVE_PATH + "=?", new String[] {RELATIVE_PATH}, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name.startsWith(PREFIX)) names.add(name);
            }
        }
        return names;
    }

    private int countRows(String name) {
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                + MediaStore.Downloads.DISPLAY_NAME + "=?";
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), projection,
                selection, new String[] {RELATIVE_PATH, name}, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    private void cleanMediaStoreRows() {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
        List<Uri> rows = new ArrayList<>();
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (Cursor cursor = context.getContentResolver().query(collection, projection,
                selection, new String[] {RELATIVE_PATH}, null)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn).startsWith(PREFIX)) {
                    rows.add(Uri.withAppendedPath(collection, cursor.getString(idColumn)));
                }
            }
        }
        for (Uri row : rows) context.getContentResolver().delete(row, null, null);
    }

    private List<File> filesWithPrefix() {
        List<File> files = new ArrayList<>();
        File[] entries = incoming.listFiles();
        if (entries != null) {
            for (File file : entries) if (file.getName().startsWith(PREFIX)) files.add(file);
        }
        return files;
    }

    private void serveApi() {
        while (serving) {
            try (Socket socket = apiSocket.accept()) {
                socket.setSoTimeout(3000);
                InputStream input = socket.getInputStream();
                String requestLine = readLine(input);
                if (requestLine == null) continue;
                int contentLength = 0;
                String header;
                while ((header = readLine(input)) != null && !header.isEmpty()) {
                    if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        contentLength = Integer.parseInt(header.substring(15).trim());
                    }
                }
                byte[] requestBody = input.readNBytes(contentLength);
                String[] requestParts = requestLine.split(" ");
                String method = requestParts[0];
                String path = requestParts[1];
                JSONObject response = apiResponse(method, path, requestBody);
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
            } catch (IOException error) {
                if (serving) android.util.Log.e("ExportTestApi", "Local fake API request failed", error);
            } catch (Exception error) {
                android.util.Log.e("ExportTestApi", "Could not prepare fake API response", error);
            }
        }
    }

    private synchronized JSONObject apiResponse(String method, String path, byte[] requestBody) throws Exception {
        if (path.endsWith("/auth/login")) return new JSONObject().put("token", "export-test-token");
        if (path.endsWith("/share_directories") && method.equals("GET")) {
            JSONArray directories = new JSONArray();
            for (String directory : sharedDirectories) directories.put(new JSONObject().put("path", directory));
            return new JSONObject().put("directories", directories);
        }
        if (path.endsWith("/share_directories") && method.equals("POST")) {
            JSONObject request = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
            String directory = request.getString("path");
            sharedDirectories.add(directory);
            return new JSONObject().put("results", new JSONArray().put(
                    new JSONObject().put("id", directory).put("ok", true)));
        }
        return new JSONObject();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
        }
        if (value == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.US_ASCII.name());
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static final class SimulatedProcessDeath extends Error {
        private static final long serialVersionUID = 1L;
    }

}
