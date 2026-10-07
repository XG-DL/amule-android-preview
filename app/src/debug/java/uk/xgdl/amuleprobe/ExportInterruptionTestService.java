package uk.xgdl.amuleprobe;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/** Debug-only helper that terminates its separate app process during an export write. */
public final class ExportInterruptionTestService extends Service {
    static final String ACTION_CRASH_DURING_EXPORT =
            "uk.xgdl.amuleprobe.action.CRASH_DURING_EXPORT_TEST";
    static final String ACTION_CRASH_POINT_REACHED =
            "uk.xgdl.amuleprobe.action.EXPORT_CRASH_POINT_REACHED";
    static final String ACTION_RECOVER_EXPORT =
            "uk.xgdl.amuleprobe.action.RECOVER_EXPORT_TEST";
    static final String ACTION_RECOVERY_FINISHED =
            "uk.xgdl.amuleprobe.action.EXPORT_RECOVERY_FINISHED";
    static final String EXTRA_NAME = "name";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECOVER_EXPORT.equals(intent.getAction())) {
            File incoming = new File(new File(getFilesDir(), "amule"), "Incoming");
            stopSelf(startId);
            new Thread(() -> {
                CompletedDownloadExporter exporter = new CompletedDownloadExporter(
                        ExportInterruptionTestService.this, incoming);
                exporter.scan();
                exporter.scan();
                exporter.scan();
                sendBroadcast(new Intent(ACTION_RECOVERY_FINISHED)
                        .setPackage("uk.xgdl.amuleprobe"));
            }, "export-recovery-test").start();
            return START_NOT_STICKY;
        }

        String name = intent == null ? null : intent.getStringExtra(EXTRA_NAME);
        if (!ACTION_CRASH_DURING_EXPORT.equals(intent == null ? null : intent.getAction()) || name == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Clear the started-service record before the intentional process death, avoiding a system restart.
        stopSelf(startId);

        stopSelf(startId);
        File incoming = new File(new File(getFilesDir(), "amule"), "Incoming");
        new Thread(() -> {
            CompletedDownloadExporter.OutputStreamOpener pauseDuringWrite = (resolver, destination) ->
                    new PauseAfterPartialWrite(resolver, destination);
            CompletedDownloadExporter exporter = new CompletedDownloadExporter(
                    ExportInterruptionTestService.this, incoming, pauseDuringWrite);
            exporter.scan();
            exporter.scan();
            exporter.scan();
        }, "export-crash-test").start();
        return START_NOT_STICKY;
    }

    private final class PauseAfterPartialWrite extends OutputStream {
        private final OutputStream output;
        private boolean killed;

        PauseAfterPartialWrite(ContentResolver resolver, Uri destination) throws Exception {
            output = resolver.openOutputStream(destination, "w");
            if (output == null) throw new IOException("Could not open test export destination");
        }

        @Override public void write(int value) throws IOException { output.write(value); }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (!killed) {
                output.write(bytes, offset, Math.min(128, length));
                sendBroadcast(new Intent(ACTION_CRASH_POINT_REACHED)
                        .setPackage("uk.xgdl.amuleprobe"));
                killed = true;
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("The export test writer was interrupted", interrupted);
                }
            }
            output.write(bytes, offset, length);
        }

        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException { output.close(); }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
