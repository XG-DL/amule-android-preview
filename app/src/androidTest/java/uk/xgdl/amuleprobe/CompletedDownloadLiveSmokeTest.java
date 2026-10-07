package uk.xgdl.amuleprobe;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Checks that the running app service exports a private Incoming file on a real device. */
@RunWith(AndroidJUnit4.class)
public final class CompletedDownloadLiveSmokeTest {
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/aMule/Complete/";

    @Test
    public void runningServiceExportsCompletedFileAndTestRemovesIt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Instrumentation must target the installed aMule app",
                "uk.xgdl.amuleprobe".equals(context.getPackageName()));
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull("Could not find the app launcher activity", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        SystemClock.sleep(5000);

        File incoming = new File(new File(context.getFilesDir(), "amule"), "Incoming");
        assertTrue(incoming.isDirectory());
        String name = "CodexAndroid17ExportSmoke-" + UUID.randomUUID() + ".txt";
        byte[] expected = "Android 17 live exporter smoke check\n".getBytes(StandardCharsets.UTF_8);
        File source = new File(incoming, name);
        Uri exported = null;
        try {
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(expected);
            }

            long deadline = SystemClock.elapsedRealtime() + 120_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                exported = findExport(context, name);
                if (exported != null && !source.exists()) break;
                SystemClock.sleep(1000);
            }

            assertFalse("The running service did not remove the private source after export", source.exists());
            assertNotNull("The running service did not publish the file to Downloads", exported);
            try (InputStream input = context.getContentResolver().openInputStream(exported)) {
                assertNotNull(input);
                assertArrayEquals(expected, input.readAllBytes());
            }
        } finally {
            if (source.exists()) assertTrue("Could not remove the private smoke-test source", source.delete());
            if (exported != null) context.getContentResolver().delete(exported, null, null);
        }
    }

    private static Uri findExport(Context context, String name) {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection,
                new String[] {name, RELATIVE_PATH}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return Uri.withAppendedPath(collection, cursor.getString(0));
        }
    }
}
