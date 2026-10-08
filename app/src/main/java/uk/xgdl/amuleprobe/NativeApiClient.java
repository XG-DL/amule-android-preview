package uk.xgdl.amuleprobe;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small loopback API client for the local native-UI experiment. */
final class NativeApiClient {
    private static final String BASE_URL = "http://127.0.0.1:4713/api/v1/";
    private String cookie;
    private volatile HttpURLConnection eventConnection;

    interface EventListener {
        void onConnected();
        void onEvent(String name, String id, JSONObject payload);
    }

    void streamEvents(AtomicBoolean running, String lastEventId, EventListener listener) throws IOException {
        if (cookie == null) throw new IOException("The local aMule API session has not been opened");
        HttpURLConnection connection = open("events?channels=downloads,shared,servers,clients,friends,chats,status,search,comments,logs", "GET");
        eventConnection = connection;
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Cookie", cookie);
        if (lastEventId != null && !lastEventId.isEmpty()) connection.setRequestProperty("Last-Event-ID", lastEventId);
        connection.setReadTimeout(45000);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Event stream failed (HTTP " + status + ")");
            listener.onConnected();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String name = "message", id = lastEventId == null ? "" : lastEventId;
                StringBuilder data = new StringBuilder();
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            try { listener.onEvent(name, id, new JSONObject(data.toString())); }
                            catch (JSONException ignored) { }
                        }
                        name = "message"; data.setLength(0); continue;
                    }
                    if (line.startsWith(":")) continue;
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1).replaceFirst("^ ", "");
                    if (field.equals("event")) name = value;
                    else if (field.equals("id")) id = value;
                    else if (field.equals("data")) { if (data.length() > 0) data.append('\n'); data.append(value); }
                }
            }
        } finally {
            if (eventConnection == connection) eventConnection = null;
            connection.disconnect();
        }
    }

    void closeEventStream() {
        HttpURLConnection active = eventConnection;
        if (active != null) active.disconnect();
    }

    void login(String password) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("password", password);
        HttpURLConnection connection = open("auth/login", "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        String setCookie = connection.getHeaderField("Set-Cookie");
        String response = readResponse(connection, status);
        connection.disconnect();
        if (status != HttpURLConnection.HTTP_OK || setCookie == null) {
            throw new IOException("Local aMule API login failed (HTTP " + status + ")" + errorDetail(response));
        }
        int attributes = setCookie.indexOf(';');
        cookie = attributes < 0 ? setCookie : setCookie.substring(0, attributes);
    }

    JSONObject get(String path) throws IOException, JSONException {
        if (cookie == null) throw new IOException("The local aMule API session has not been opened");
        HttpURLConnection connection = open(path, "GET");
        connection.setRequestProperty("Cookie", cookie);
        int status = connection.getResponseCode();
        rememberCookie(connection.getHeaderField("Set-Cookie"));
        String response = readResponse(connection, status);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("Local aMule API request failed (HTTP " + status + ")" + errorDetail(response));
        }
        return new JSONObject(response);
    }

    JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        return send(path, "POST", body);
    }

    JSONObject patch(String path, JSONObject body) throws IOException, JSONException {
        if (cookie == null) throw new IOException("The local aMule API session has not been opened");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 4713), 5000);
            socket.setSoTimeout(10000);
            String headers = "PATCH /api/v1/" + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:4713\r\n"
                    + "Accept: application/json\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Cookie: " + cookie + "\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + payload.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = socket.getInputStream().read(buffer)) != -1) responseBytes.write(buffer, 0, count);
            byte[] raw = responseBytes.toByteArray();
            int split = indexOf(raw, 0, "\r\n\r\n");
            if (split < 0) throw new IOException("Invalid response from local aMule API");
            String responseHeaders = new String(raw, 0, split, StandardCharsets.US_ASCII);
            byte[] responseBytesBody = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
            rememberCookie(responseHeaders);
            int status = 500;
            String[] first = responseHeaders.split("\r\n", 2)[0].split(" ");
            if (first.length > 1) status = Integer.parseInt(first[1]);
            if (responseHeaders.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) {
                responseBytesBody = unchunk(responseBytesBody);
            }
            String responseBody = new String(responseBytesBody, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new IOException("Local aMule API request failed (HTTP " + status + ")" + errorDetail(responseBody));
            }
            return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
        }
    }

    private static int indexOf(byte[] data, int start, String needle) {
        byte[] bytes = needle.getBytes(StandardCharsets.US_ASCII);
        outer: for (int offset = start; offset <= data.length - bytes.length; offset++) {
            for (int i = 0; i < bytes.length; i++) {
                if (data[offset + i] != bytes[i]) continue outer;
            }
            return offset;
        }
        return -1;
    }

    private static byte[] unchunk(byte[] body) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < body.length) {
            int end = indexOf(body, offset, "\r\n");
            if (end < 0) throw new IOException("Invalid chunked response from local aMule API");
            int size;
            String sizeLine = new String(body, offset, end - offset, StandardCharsets.US_ASCII);
            int extension = sizeLine.indexOf(';');
            if (extension >= 0) sizeLine = sizeLine.substring(0, extension);
            try { size = Integer.parseInt(sizeLine.trim(), 16); }
            catch (NumberFormatException error) { throw new IOException("Invalid chunked response from local aMule API", error); }
            if (size == 0) break;
            int start = end + 2;
            int finish = start + size;
            if (finish + 2 > body.length || body[finish] != '\r' || body[finish + 1] != '\n') {
                throw new IOException("Truncated response from local aMule API");
            }
            decoded.write(body, start, size);
            offset = finish + 2;
        }
        return decoded.toByteArray();
    }

    JSONObject delete(String path) throws IOException, JSONException {
        return send(path, "DELETE", null);
    }

    JSONObject delete(String path, JSONObject body) throws IOException, JSONException {
        return send(path, "DELETE", body);
    }

    private JSONObject send(String path, String method, JSONObject body) throws IOException, JSONException {
        HttpURLConnection connection = open(path, method);
        connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        rememberCookie(connection.getHeaderField("Set-Cookie"));
        String response = readResponse(connection, status);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("Local aMule API request failed (HTTP " + status + ")" + errorDetail(response));
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private void rememberCookie(String headerOrHeaders) {
        if (headerOrHeaders == null) return;
        for (String line : headerOrHeaders.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(0, colon).trim().equalsIgnoreCase("Set-Cookie")) continue;
            String value = line.substring(colon + 1).trim();
            int attributes = value.indexOf(';');
            cookie = attributes < 0 ? value : value.substring(0, attributes);
            return;
        }
        // HttpURLConnection returns the header value without its field name.
        if (!headerOrHeaders.contains("\n") && !headerOrHeaders.contains(":")) {
            int attributes = headerOrHeaders.indexOf(';');
            if (attributes > 0) cookie = headerOrHeaders.substring(0, attributes).trim();
        }
    }

    private static String readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String errorDetail(String response) {
        try {
            JSONObject envelope = new JSONObject(response);
            JSONObject error = envelope.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                return message.isEmpty() ? "" : ": " + message;
            }
        } catch (JSONException ignored) {
            // Keep the HTTP status as the useful part of a non-JSON response.
        }
        return "";
    }
}
