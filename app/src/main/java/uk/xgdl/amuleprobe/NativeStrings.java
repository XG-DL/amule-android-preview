package uk.xgdl.amuleprobe;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Translates app-owned labels while leaving names and values from aMule unchanged. */
final class NativeStrings {
    private final Context context;
    private final Map<String, String> spanishCatalog = new HashMap<>();
    private final Map<String, String> chineseCatalog = new HashMap<>();
    private final Map<String, String> hindiCatalog = new HashMap<>();
    private final Map<String, String> words = new HashMap<>();

    NativeStrings(Context context) {
        this.context = context;
        loadCommonWords();
        loadSpanishCatalog();
        loadChineseCatalog();
        loadHindiCatalog();
    }

    private void loadCommonWords() {
        words.put("Networks", "Redes"); words.put("Search", "Buscar"); words.put("Downloads", "Descargas"); words.put("Shared", "Compartidos"); words.put("More", "Más");
        words.put("Clients", "Clientes"); words.put("Messages", "Mensajes"); words.put("Statistics", "Estadísticas"); words.put("Preferences", "Preferencias"); words.put("About", "Acerca de");
        words.put("Contributors", "Colaboradores"); words.put("Could not load contributors", "No se pudo cargar la lista de colaboradores");
        words.put("Conversations", "Conversaciones"); words.put("Connected clients", "Clientes conectados"); words.put("Known clients", "Clientes conocidos");
        words.put("Friend controls", "Controles de amigos");
        words.put("Full Web UI", "Interfaz web completa");
        words.put("Appearance", "Apariencia"); words.put("General", "General"); words.put("Connection", "Conexión"); words.put("Directories", "Directorios");
        words.put("Servers", "Servidores"); words.put("Files", "Archivos"); words.put("Security", "Seguridad"); words.put("GeoIP", "GeoIP");
        words.put("Proxy", "Proxy"); words.put("Message filter", "Filtro de mensajes"); words.put("Remote controls", "Controles remotos");
        words.put("Online signature", "Firma en línea"); words.put("Advanced", "Avanzado"); words.put("API credentials", "Credenciales de API");
        words.put("Apply", "Aplicar"); words.put("Cancel", "Cancelar"); words.put("Close", "Cerrar"); words.put("Save", "Guardar"); words.put("Delete", "Eliminar"); words.put("Remove", "Eliminar");
        words.put("Connect", "Conectar"); words.put("Disconnect", "Desconectar"); words.put("Details", "Detalles"); words.put("Verify", "Verificar"); words.put("Refresh", "Actualizar"); words.put("Stop", "Detener");
        words.put("Add server", "Añadir servidor"); words.put("Priority", "Prioridad"); words.put("Server priority", "Prioridad del servidor");
        words.put("Show connected clients", "Mostrar clientes conectados"); words.put("Show known clients", "Mostrar clientes conocidos");
        words.put("Last seen", "Visto por última vez"); words.put("Downloaded", "Descargado"); words.put("Uploaded", "Subido");
        words.put("Software", "Programa"); words.put("Download speed", "Velocidad de descarga"); words.put("Upload speed", "Velocidad de subida");
        words.put("Make permanent", "Hacer permanente"); words.put("Make temporary", "Hacer temporal"); words.put("Temporary", "Temporal"); words.put("Permanent", "Permanente");
        words.put("Apply filter", "Aplicar filtro"); words.put("Load more servers", "Cargar más servidores"); words.put("Load more clients", "Cargar más clientes");
        words.put("Name", "Nombre"); words.put("Users", "Usuarios"); words.put("Ping", "Latencia");
        words.put("Filter by server name or address", "Filtrar por nombre o dirección del servidor");
        words.put("Filter by name, address or software", "Filtrar por nombre, dirección o programa");
        words.put("Add friend", "Añadir amigo"); words.put("Open conversation", "Abrir conversación"); words.put("Set friend slot", "Asignar puesto de amigo"); words.put("Remove friend slot", "Quitar puesto de amigo");
        words.put("No conversations yet. Add a friend by IP address and port to start one.", "Todavía no hay conversaciones. Añade un amigo por dirección IP y puerto para iniciar una.");
        words.put("No messages yet.", "Todavía no hay mensajes."); words.put("Write a message", "Escribe un mensaje"); words.put("Send", "Enviar");
        words.put("Online", "En línea"); words.put("Offline", "Desconectado"); words.put("Them:", "Esa persona:"); words.put("You:", "Tú:");
        words.put("Select", "Seleccionar"); words.put("All", "Todos"); words.put("Uploading", "Subiendo"); words.put("Idle", "Inactivo");
        words.put("Interface language", "Idioma de la interfaz"); words.put("Native app theme", "Tema de la aplicación"); words.put("System", "Sistema"); words.put("Light", "Claro"); words.put("Dark", "Oscuro");
        words.put("Statistics graph range", "Intervalo de las gráficas"); words.put("5 minutes", "5 minutos"); words.put("1 hour", "1 hora"); words.put("6 hours", "6 horas"); words.put("24 hours", "24 horas");
        words.put("Spanish translation covers navigation and common controls. Daemon-provided names and statistics remain in the daemon's language.", "La traducción al español cubre la navegación y los controles habituales. Los nombres y estadísticas proporcionados por el demonio mantienen su idioma.");
        words.put("Appearance choices are saved on this Android device and do not change daemon preferences.", "Las opciones de apariencia se guardan en este dispositivo Android y no cambian las preferencias del demonio.");
    }

    String translate(String value, String language) {
        String runtime = translateRuntimeMessage(value, language);
        if (runtime != null) return runtime;
        if (language.equals("简体中文")) return translateChinese(value);
        if (language.equals("हिंदी")) return translateHindi(value);
        if (!language.equals("Español")) return value;
        String translated = spanishCatalog.get(value);
        if (translated == null) translated = words.get(value);
        if (translated != null) return translated;
        if (value.startsWith("All  ")) return "Total: " + value.substring("All  ".length());
        if (value.startsWith("Download queue  ·  ")) return "Cola de descargas  ·  " + value.substring("Download queue  ·  ".length()).replace(" shown of ", " mostradas de ").replace(" selected", " seleccionadas");
        if (value.startsWith("Actions for ") && value.endsWith(" selected downloads")) {
            return "Acciones para " + value.substring("Actions for ".length(), value.length() - " selected downloads".length()) + " descargas seleccionadas";
        }
        if (value.startsWith("Use “") && value.endsWith("” as the download name?")) {
            return "¿Usar «" + value.substring("Use “".length(), value.length() - "” as the download name?".length()) + "» como nombre de la descarga?";
        }
        if (value.startsWith("Shared files  ·  ")) return "Archivos compartidos  ·  " + value.substring("Shared files  ·  ".length());
        if (value.startsWith("Comments  ·  ")) return "Comentarios  ·  " + value.substring("Comments  ·  ".length());
        if (value.startsWith("Connected clients  ·  ")) return "Clientes conectados  ·  " + value.substring("Connected clients  ·  ".length());
        if (value.startsWith("Known clients  ·  ")) return "Clientes conocidos  ·  " + value.substring("Known clients  ·  ".length());
        int totalsStart = value.indexOf(" files   ·   Size ");
        if (totalsStart > 0) {
            return value.substring(0, totalsStart) + " archivos   ·   Tamaño "
                    + value.substring(totalsStart + " files   ·   Size ".length())
                    .replace("   ·   Done ", "   ·   Descargado ").replace("   ·   Speed ", "   ·   Velocidad ");
        }
        String prefix = null;
        String prefixTranslation = null;
        for (String key : spanishCatalog.keySet()) {
            if (key.endsWith(" ") && value.startsWith(key) && (prefix == null || key.length() > prefix.length())) {
                prefix = key;
                prefixTranslation = spanishCatalog.get(key);
            }
        }
        for (Map.Entry<String, String> entry : words.entrySet()) {
            String key = entry.getKey() + "  ·";
            if (value.startsWith(key) && (prefix == null || entry.getKey().length() > prefix.length())) {
                prefix = entry.getKey();
                prefixTranslation = entry.getValue();
            }
        }
        if (prefix != null) return translateNativeTail(prefixTranslation + value.substring(prefix.length()));
        return value;
    }

    /** Translate app-generated messages while preserving inserted counts and error details. */
    private String translateRuntimeMessage(String value, String language) {
        if (language.equals("English")) return null;
        int addedSuffix = value.indexOf(" added; remaining files failed: ");
        if (addedSuffix > 0 && value.substring(0, addedSuffix).matches("\\d+")) {
            String count = value.substring(0, addedSuffix);
            String detail = value.substring(addedSuffix + " added; remaining files failed: ".length());
            if (language.equals("Español")) return count + " añadidos; no se pudieron añadir los demás archivos: " + detail;
            if (language.equals("简体中文")) return "已添加 " + count + " 个；其余文件添加失败：" + detail;
            return count + " फ़ाइलें जोड़ी गईं; बाकी फ़ाइलें जोड़ने में विफलता: " + detail;
        }
        String filesAdded = " files added to downloads";
        if (value.endsWith(filesAdded)) {
            String count = value.substring(0, value.length() - filesAdded.length());
            if (count.matches("\\d+")) {
                if (language.equals("Español")) return count + " archivos añadidos a descargas";
                if (language.equals("简体中文")) return "已将 " + count + " 个文件添加到下载队列";
                return "डाउनलोड में " + count + " फ़ाइलें जोड़ी गईं";
            }
        }
        String appliedSuffix = " applied to selected downloads";
        if (value.endsWith(appliedSuffix)) {
            String action = value.substring(0, value.length() - appliedSuffix.length());
            String translatedAction = translate(action, language);
            if (language.equals("Español")) return translatedAction + " aplicada a las descargas seleccionadas";
            if (language.equals("简体中文")) return "已对所选下载执行" + translatedAction;
            return "चुने गए डाउनलोड पर " + translatedAction + " लागू किया गया";
        }
        String requestedSuffix = " requested";
        if (value.endsWith(requestedSuffix)) {
            String action = value.substring(0, value.length() - requestedSuffix.length());
            String translatedAction = translate(action, language);
            if (!translatedAction.equals(action)) {
                if (language.equals("Español")) return translatedAction + " solicitado";
                if (language.equals("简体中文")) return "已请求" + translatedAction;
                return translatedAction + " का अनुरोध किया गया";
            }
        }
        String rejectedSuffix = " item(s) rejected";
        if (value.endsWith(rejectedSuffix)) {
            int separator = value.lastIndexOf(": ");
            if (separator > 0 && value.substring(separator + 2, value.length() - rejectedSuffix.length()).matches("\\d+")) {
                String label = translate(value.substring(0, separator), language);
                String count = value.substring(separator + 2, value.length() - rejectedSuffix.length());
                if (language.equals("Español")) return label + ": " + count + " elemento(s) rechazado(s)";
                if (language.equals("简体中文")) return label + "：" + count + " 项被拒绝";
                return label + ": " + count + " आइटम अस्वीकार किए गए";
            }
        }
        String failedSeparator = " failed: ";
        int failedAt = value.lastIndexOf(failedSeparator);
        if (failedAt > 0) {
            String label = translate(value.substring(0, failedAt), language);
            String detail = value.substring(failedAt + failedSeparator.length());
            if (!label.equals(value.substring(0, failedAt))) {
                if (language.equals("Español")) return label + " falló: " + detail;
                if (language.equals("简体中文")) return label + "失败：" + detail;
                return label + " विफल: " + detail;
            }
        }
        String prototypeSuffix = " will be added in a later prototype pass";
        if (value.endsWith(prototypeSuffix)) {
            String label = translate(value.substring(0, value.length() - prototypeSuffix.length()), language);
            if (language.equals("Español")) return label + " se añadirá en una versión posterior del prototipo";
            if (language.equals("简体中文")) return "将在后续原型版本中添加" + label;
            return label + " को प्रोटोटाइप के अगले चरण में जोड़ा जाएगा";
        }
        String directoryRejectionSuffix = " directory item(s) were rejected";
        if (value.endsWith(directoryRejectionSuffix)) {
            String count = value.substring(0, value.length() - directoryRejectionSuffix.length());
            if (count.matches("\\d+")) {
                if (language.equals("Español")) return "Se rechazaron " + count + " elementos de directorio";
                if (language.equals("简体中文")) return count + " 个目录项被拒绝";
                return count + " डायरेक्टरी आइटम अस्वीकार किए गए";
            }
        }
        return null;
    }

    private String translateChinese(String value) {
        String translated = chineseCatalog.get(value);
        if (translated != null) return translated;
        if (value.startsWith("All  ")) return "总计  " + value.substring("All  ".length());
        if (value.startsWith("Download queue  ·  ")) {
            return "下载队列  ·  " + value.substring("Download queue  ·  ".length())
                    .replace(" shown of ", " 个已显示，共 ").replace(" selected", " 个已选择");
        }
        if (value.startsWith("Actions for ") && value.endsWith(" selected downloads")) {
            return "对 " + value.substring("Actions for ".length(), value.length() - " selected downloads".length())
                    + " 个所选下载执行操作";
        }
        if (value.startsWith("Add ") && value.endsWith(" selected files")) {
            return "添加 " + value.substring("Add ".length(), value.length() - " selected files".length()) + " 个所选文件";
        }
        if (value.startsWith("Use “") && value.endsWith("” as the download name?")) {
            return "要将“" + value.substring("Use “".length(), value.length() - "” as the download name?".length())
                    + "”用作下载名称吗？";
        }
        String[][] prefixes = {
                {"Shared files  ·  ", "共享文件  ·  "}, {"Comments  ·  ", "评论  ·  "},
                {"Conversations  ·  ", "对话  ·  "}, {"Connected clients  ·  ", "已连接客户端  ·  "},
                {"Known clients  ·  ", "已知客户端  ·  "}, {"Native Android interface  ·  app version ", "原生 Android 界面  ·  应用版本 "},
                {"This session  ·  downloaded ", "本次会话  ·  已下载 "}, {"Uploaded this session ", "本次会话已上传 "},
                {"Downloaded ", "已下载 "}, {"Upload: ", "上传："}, {"Download: ", "下载："},
                {"aMule daemon: ", "aMule 守护进程："}, {"Last checked: ", "上次检查："},
                {"Update available: ", "有可用更新："}, {"Priority: ", "优先级："}, {"Status: ", "状态："}
        };
        for (String[] prefix : prefixes) {
            if (value.startsWith(prefix[0])) {
                String tail = value.substring(prefix[0].length());
                if (prefix[0].equals("Priority: ") || prefix[0].equals("Status: ")
                        || prefix[0].equals("Download: ") || prefix[0].equals("Upload: ")) {
                    tail = translateChineseKnownValues(tail);
                }
                return prefix[1] + tail;
            }
        }
        String sourcePrefix = null;
        String translatedPrefix = null;
        for (Map.Entry<String, String> entry : chineseCatalog.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(" ") && value.startsWith(key)
                    && (sourcePrefix == null || key.length() > sourcePrefix.length())) {
                sourcePrefix = key;
                translatedPrefix = entry.getValue();
            }
        }
        if (sourcePrefix != null) return translatedPrefix + value.substring(sourcePrefix.length());
        return value;
    }

    private String translateHindi(String value) {
        String translated = hindiCatalog.get(value);
        if (translated != null) return translated;
        if (value.startsWith("All  ")) return "कुल  " + value.substring("All  ".length());
        if (value.startsWith("Download queue  ·  ")) {
            return "डाउनलोड कतार  ·  " + value.substring("Download queue  ·  ".length())
                    .replace(" shown of ", " दिखाए गए, कुल ").replace(" selected", " चुने गए");
        }
        if (value.startsWith("Actions for ") && value.endsWith(" selected downloads")) {
            return value.substring("Actions for ".length(), value.length() - " selected downloads".length())
                    + " चुने गए डाउनलोड के लिए कार्रवाइयाँ";
        }
        if (value.startsWith("Add ") && value.endsWith(" selected files")) {
            return value.substring("Add ".length(), value.length() - " selected files".length()) + " चुनी गई फ़ाइलें जोड़ें";
        }
        if (value.startsWith("Use “") && value.endsWith("” as the download name?")) {
            return "क्या “" + value.substring("Use “".length(), value.length() - "” as the download name?".length())
                    + "” को डाउनलोड नाम के रूप में इस्तेमाल करें?";
        }
        String[][] knownPrefixes = {{"Download: ", "डाउनलोड: "}, {"Upload: ", "अपलोड: "},
                {"Priority: ", "प्राथमिकता: "}, {"Status: ", "स्थिति: "}};
        for (String[] prefix : knownPrefixes) {
            if (value.startsWith(prefix[0])) {
                return prefix[1] + translateHindiKnownValues(value.substring(prefix[0].length()));
            }
        }
        String sourcePrefix = null;
        String translatedPrefix = null;
        for (Map.Entry<String, String> entry : hindiCatalog.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(" ") && value.startsWith(key)
                    && (sourcePrefix == null || key.length() > sourcePrefix.length())) {
                sourcePrefix = key;
                translatedPrefix = entry.getValue();
            }
        }
        if (sourcePrefix != null) return translatedPrefix + value.substring(sourcePrefix.length());
        return value;
    }

    private String translateHindiKnownValues(String value) {
        String[] parts = value.split("  ·  ", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String trimmed = part.trim();
            String translated = hindiCatalog.get(trimmed);
            if (translated == null && !trimmed.isEmpty()) {
                translated = hindiCatalog.get(Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1));
            }
            if (translated != null) {
                int start = part.indexOf(trimmed);
                parts[i] = part.substring(0, start) + translated + part.substring(start + trimmed.length());
            }
        }
        return String.join("  ·  ", parts);
    }

    private String translateChineseKnownValues(String value) {
        String[] parts = value.split("  ·  ", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String trimmed = part.trim();
            String translated = chineseCatalog.get(trimmed);
            if (translated == null && !trimmed.isEmpty()) {
                String titleCase = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
                translated = chineseCatalog.get(titleCase);
            }
            if (translated != null) {
                int start = part.indexOf(trimmed);
                parts[i] = part.substring(0, start) + translated + part.substring(start + trimmed.length());
                continue;
            }
            int separator = part.indexOf(' ');
            if (separator > 0) {
                String first = part.substring(0, separator);
                translated = chineseCatalog.get(first);
                if (translated == null) translated = chineseCatalog.get(Character.toUpperCase(first.charAt(0)) + first.substring(1));
                if (translated != null) parts[i] = translated + part.substring(separator);
            }
        }
        return String.join("  ·  ", parts);
    }

    private void loadChineseCatalog() {
        try {
            JSONObject catalog = new JSONObject(readAsset("i18n/native-zh-CN.json"));
            java.util.Iterator<String> keys = catalog.keys();
            while (keys.hasNext()) {
                String source = keys.next();
                chineseCatalog.put(source, catalog.optString(source, source));
            }
        } catch (Exception ignored) { /* English remains the fallback for untranslated labels. */ }
    }

    private void loadHindiCatalog() {
        try {
            JSONObject catalog = new JSONObject(readAsset("i18n/native-hi.json"));
            java.util.Iterator<String> keys = catalog.keys();
            while (keys.hasNext()) {
                String source = keys.next();
                hindiCatalog.put(source, catalog.optString(source, source));
            }
        } catch (Exception ignored) { /* English remains the fallback for untranslated labels. */ }
    }

    private String translateNativeTail(String value) {
        return value.replace("  ·  uploaded ", "  ·  subido ")
                .replace("  ·  lifetime ", "  ·  total acumulado ")
                .replace("  ·  connected", "  ·  conectado")
                .replace("  ·  seen ", "  ·  visto ")
                .replace(" results", " resultados")
                .replace("  ·  Temporary", "  ·  Temporal")
                .replace("  ·  Permanent", "  ·  Permanente")
                .replace("downloading", "descargando")
                .replace("waiting", "en espera")
                .replace("paused", "en pausa")
                .replace("stopped", "detenida")
                .replace("completed", "completada")
                .replace("hashing", "calculando hash")
                .replace("erroneous", "con error");
    }

    private void loadSpanishCatalog() {
        try {
            JSONObject english = new JSONObject(readAsset("i18n/en.json"));
            JSONObject spanish = new JSONObject(readAsset("i18n/es.json"));
            java.util.Iterator<String> keys = english.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String en = english.optString(key, ""), es = spanish.optString(key, "");
                if (!en.isEmpty() && !es.isEmpty()) spanishCatalog.putIfAbsent(en, es);
            }
            JSONObject nativeSpanish = new JSONObject(readAsset("i18n/native-es.json"));
            java.util.Iterator<String> nativeKeys = nativeSpanish.keys();
            while (nativeKeys.hasNext()) {
                String source = nativeKeys.next();
                spanishCatalog.put(source, nativeSpanish.optString(source, source));
            }
        } catch (Exception ignored) { /* The hand-written common labels remain available. */ }
    }

    private String readAsset(String path) throws IOException {
        try (InputStream input = context.getAssets().open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
