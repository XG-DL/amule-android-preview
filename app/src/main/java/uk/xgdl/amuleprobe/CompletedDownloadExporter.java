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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Moves finished files from aMule's private Incoming folder to shared Downloads/aMule/Complete. */
final class CompletedDownloadExporter {
    private static final String TAG = "aMuleExport";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/aMule/Complete/";
    private static final String PREF_EXPORTED = "exported_";
    private static final String PREF_SHARED_RELOAD_PENDING = "shared_reload_pending";

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
        retrySharedReload();

        File[] files = incoming.listFiles();
        if (files == null) return;

        Set<String> present = new HashSet<>();
        for (File file : files) {
            if (!file.isFile() || file.isHidden() || isTemporary(file.getName())) continue;
            String path = file.getAbsolutePath();
            present.add(path);
            String marker = fingerprint(file);
            if (marker == null) {
                candidates.remove(path);
                continue;
            }

            if (marker.equals(exportedMarker(path))) {
                try {
                    movePreviouslyExported(file, path, marker);
                } catch (Exception error) {
                    Log.e(TAG, "Could not remove the private original of an already exported file", error);
                }
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
                move(file, path, marker);
                candidates.remove(path);
            } catch (Exception error) {
                previous.stableScans = 1;
                Log.e(TAG, "Could not move a completed file to Downloads/aMule/Complete", error);
            }
        }
        candidates.keySet().retainAll(present);
    }

    private String exportedMarker(String path) {
        String key = PREF_EXPORTED + sha256(path);
        return context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE)
                .getString(key, null);
    }

    private void move(File source, String sourcePath, String marker) throws Exception {
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
            MessageDigest copiedDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Android could not open the Downloads file");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copiedDigest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
            }
            if (copied != source.length() || !marker.equals(fingerprint(source))) {
                throw new IllegalStateException("The source file changed while it was being copied");
            }
            byte[] copiedHash = copiedDigest.digest();
            try (InputStream published = resolver.openInputStream(destination)) {
                if (published == null || !MessageDigest.isEqual(copiedHash, digest(published))) {
                    throw new IllegalStateException("The Downloads copy failed its content check");
                }
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) {
                throw new IllegalStateException("Android could not finish publishing the Downloads file");
            }
        } catch (Exception error) {
            resolver.delete(destination, null, null);
            throw error;
        }

        String token = null;
        try {
            token = login();
            ensureDestinationDirectoryShared(token, destination);
            finishMove(source, sourcePath, marker, token);
            Log.i(TAG, "Moved completed file to Downloads/aMule/Complete: " + displayName);
        } catch (Exception error) {
            resolver.delete(destination, null, null);
            throw error;
        } finally {
            if (token != null) logout(token);
        }
    }

    private void movePreviouslyExported(File source, String sourcePath, String marker) throws Exception {
        Uri existing = findMatchingExport(source);
        if (existing == null) {
            context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                    .remove(PREF_EXPORTED + sha256(sourcePath)).commit();
            return;
        }
        String token = login();
        try {
            ensureDestinationDirectoryShared(token, existing);
            finishMove(source, sourcePath, marker, token);
            Log.i(TAG, "Removed duplicate private original; keeping shared Downloads file: " + source.getName());
        } finally {
            logout(token);
        }
    }

    private void finishMove(File source, String sourcePath, String marker, String token) throws Exception {
        if (!marker.equals(fingerprint(source))) {
            throw new IllegalStateException("The completed source changed before it could be moved");
        }
        if (!context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_SHARED_RELOAD_PENDING, true).commit()) {
            throw new IllegalStateException("Could not persist the pending shared-files refresh");
        }
        if (!source.delete()) {
            retrySharedReload(token);
            throw new IllegalStateException("Android could not remove the private original");
        }
        context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                .remove(PREF_EXPORTED + sha256(sourcePath)).commit();
        retrySharedReload(token);
    }

    private void ensureDestinationDirectoryShared(String token, Uri destination) throws Exception {
        String[] projection = {MediaStore.MediaColumns.DATA};
        String path;
        try (Cursor cursor = context.getContentResolver().query(destination, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IllegalStateException("Android could not locate the Downloads file on disk");
            }
            int pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            if (pathColumn < 0 || cursor.isNull(pathColumn)) {
                throw new IllegalStateException("Android did not provide a filesystem path for the Downloads file");
            }
            path = new File(cursor.getString(pathColumn)).getParent();
        }

        JSONObject body = new JSONObject().put("path", path).put("recursive", false);
        JSONObject configured = requestApi(token, "GET", "share_directories", null);
        JSONArray directories = configured.optJSONArray("directories");
        if (directories != null) {
            for (int i = 0; i < directories.length(); i++) {
                JSONObject item = directories.optJSONObject(i);
                if (item != null && path.equals(item.optString("path"))) return;
            }
        }
        JSONObject response = postApi(token, "share_directories", body);
        JSONArray results = response.optJSONArray("results");
        if (results == null) throw new IllegalStateException("aMule did not confirm the Downloads share directory");
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null && path.equals(item.optString("id"))) {
                if (!item.optBoolean("ok")) {
                    JSONObject error = item.optJSONObject("error");
                    throw new IllegalStateException("aMule could not share Downloads/aMule/Complete: "
                            + (error == null ? "directory rejected" : error.optString("message", "directory rejected")));
                }
                return;
            }
        }
        throw new IllegalStateException("aMule did not accept the Downloads share directory");
    }

    private void retrySharedReload() {
        if (!context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_SHARED_RELOAD_PENDING, false)) return;
        try {
            String token = login();
            try {
                retrySharedReload(token);
            } finally {
                logout(token);
            }
        } catch (Exception error) {
            Log.e(TAG, "Shared-file refresh is pending; will retry", error);
        }
    }

    private void retrySharedReload(String token) {
        try {
            postApi(token, "shared_reload", null);
            context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_SHARED_RELOAD_PENDING, false).commit();
        } catch (Exception error) {
            Log.e(TAG, "Moved file is in Downloads, but aMule's share refresh will be retried", error);
        }
    }

    private void logout(String token) {
        try {
            postApi(token, "auth/logout", null);
        } catch (Exception error) {
            Log.w(TAG, "Could not close the temporary local API session", error);
        }
    }

    private String login() throws Exception {
        String password = context.getSharedPreferences(AmuleService.PREFS, Context.MODE_PRIVATE)
                .getString(AmuleService.ADMIN_PASSWORD, null);
        if (password == null || password.isEmpty()) throw new IllegalStateException("aMule API password is unavailable");
        JSONObject response = postApi(null, "auth/login", new JSONObject().put("password", password));
        String token = response.optString("token", "");
        if (token.isEmpty()) throw new IllegalStateException("aMule did not issue an API session token");
        return token;
    }

    private JSONObject postApi(String token, String endpoint, JSONObject body) throws Exception {
        return requestApi(token, "POST", endpoint, body);
    }

    private JSONObject requestApi(String token, String method, String endpoint, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:4713/api/v1/" + endpoint).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            if (token == null) connection.setRequestProperty("Accept", "application/jwt");
            else connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes("UTF-8"));
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder responseText = new StringBuilder();
            if (input != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) responseText.append(line);
                }
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("aMule API " + endpoint + " returned HTTP " + status + ": " + responseText);
            }
            return responseText.length() == 0 ? new JSONObject() : new JSONObject(responseText.toString());
        } finally {
            connection.disconnect();
        }
    }

    private Uri findMatchingExport(File source) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE};
        try (Cursor cursor = resolver.query(collection, projection,
                MediaStore.Downloads.RELATIVE_PATH + "=?", new String[] {RELATIVE_PATH}, null)) {
            if (cursor == null) return null;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            while (cursor.moveToNext()) {
                if (!isUniqueVariant(source.getName(), cursor.getString(nameColumn))) continue;
                if (sizeColumn >= 0 && cursor.getLong(sizeColumn) != source.length()) continue;
                Uri item = Uri.withAppendedPath(collection, cursor.getString(idColumn));
                if (sameContent(source, item)) return item;
            }
        }
        return null;
    }

    private boolean sameContent(File source, Uri destination) throws Exception {
        MessageDigest sourceDigest = MessageDigest.getInstance("SHA-256");
        MessageDigest destinationDigest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(source)) { updateDigest(sourceDigest, input); }
        try (InputStream input = context.getContentResolver().openInputStream(destination)) {
            if (input == null) return false;
            updateDigest(destinationDigest, input);
        }
        return MessageDigest.isEqual(sourceDigest.digest(), destinationDigest.digest());
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateDigest(digest, input);
        return digest.digest();
    }

    private static void updateDigest(MessageDigest digest, InputStream input) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }

    private static boolean isUniqueVariant(String original, String candidate) {
        if (original.equals(candidate)) return true;
        int dot = original.lastIndexOf('.');
        String stem = dot > 0 ? original.substring(0, dot) : original;
        String extension = dot > 0 ? original.substring(dot) : "";
        String prefix = stem + " (";
        if (!candidate.startsWith(prefix) || !candidate.endsWith(")" + extension)) return false;
        String suffix = candidate.substring(prefix.length(), candidate.length() - extension.length() - 1);
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) if (!Character.isDigit(suffix.charAt(i))) return false;
        return true;
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
