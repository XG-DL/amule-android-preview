package uk.xgdl.amuleprobe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Copies finished files from aMule's private Incoming folder to shared Downloads/aMule/Complete. */
final class CompletedDownloadExporter {
    private static final String TAG = "aMuleExport";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/aMule/Complete/";
    private static final String PREF_EXPORTED = "exported_";

    private final Context context;
    private final File incoming;
    private final Map<String, Fingerprint> candidates = new HashMap<>();

    CompletedDownloadExporter(Context context, File incoming) {
        this.context = context.getApplicationContext();
        this.incoming = incoming;
    }

    void scan() {
        if (Build.VERSION.SDK_INT < 29) {
            Log.w(TAG, "Shared Downloads export requires Android 10 or newer");
            return;
        }
        File[] files = incoming.listFiles();
        if (files == null) return;

        Set<String> present = new HashSet<>();
        for (File file : files) {
            if (!file.isFile() || file.isHidden() || isTemporary(file.getName())) continue;
            String path = file.getAbsolutePath();
            present.add(path);
            String marker = fingerprint(file);
            if (marker == null || isExported(path, marker)) {
                candidates.remove(path);
                continue;
            }

            Fingerprint previous = candidates.get(path);
            if (previous == null || !previous.marker.equals(marker)) {
                candidates.put(path, new Fingerprint(marker, 1));
                continue;
            }
            if (previous.stableScans < 2) {
                previous.stableScans++;
                continue;
            }

            try {
                export(file, path, marker);
                candidates.remove(path);
            } catch (Exception error) {
                previous.stableScans = 1;
                Log.e(TAG, "Could not copy a completed file to Downloads/aMule/Complete", error);
            }
        }
        candidates.keySet().retainAll(present);
    }

    private boolean isExported(String path, String marker) {
        String key = PREF_EXPORTED + sha256(path);
        return marker.equals(context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE)
                .getString(key, null));
    }

    private void export(File source, String sourcePath, String marker) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String displayName = uniqueDisplayName(resolver, collection, source.getName());

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        String mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extensionOf(displayName));
        values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri destination = resolver.insert(collection, values);
        if (destination == null) throw new IllegalStateException("Android could not create the Downloads file");

        try {
            long copied = 0;
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Android could not open the Downloads file");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
            }
            if (copied != source.length() || !marker.equals(fingerprint(source))) {
                throw new IllegalStateException("The source file changed while it was being copied");
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) {
                throw new IllegalStateException("Android could not finish publishing the Downloads file");
            }
            context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PREF_EXPORTED + sha256(sourcePath), marker).apply();
            Log.i(TAG, "Copied completed file to Downloads/aMule/Complete: " + displayName);
        } catch (Exception error) {
            resolver.delete(destination, null, null);
            throw error;
        }
    }

    private String uniqueDisplayName(ContentResolver resolver, Uri collection, String requested) {
        String stem = requested;
        String extension = "";
        int dot = requested.lastIndexOf('.');
        if (dot > 0) {
            stem = requested.substring(0, dot);
            extension = requested.substring(dot);
        }

        String candidate = requested;
        for (int suffix = 1; nameExists(resolver, collection, candidate); suffix++) {
            candidate = stem + " (" + suffix + ")" + extension;
        }
        return candidate;
    }

    private boolean nameExists(ContentResolver resolver, Uri collection, String name) {
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (Cursor cursor = resolver.query(collection, projection, selection,
                new String[] {name, RELATIVE_PATH}, null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    private static String fingerprint(File file) {
        if (!file.exists() || !file.canRead()) return null;
        return file.length() + ":" + file.lastModified();
    }

    private static boolean isTemporary(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".part") || lower.endsWith(".part.met") || lower.endsWith(".bak");
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Fingerprint {
        final String marker;
        int stableScans;

        Fingerprint(String marker, int stableScans) {
            this.marker = marker;
            this.stableScans = stableScans;
        }
    }
}
