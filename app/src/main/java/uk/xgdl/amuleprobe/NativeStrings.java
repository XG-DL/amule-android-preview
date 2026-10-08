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
    private final Map<String, String> words = new HashMap<>();

    NativeStrings(Context context) {
        this.context = context;
        loadCommonWords();
        loadSpanishCatalog();
    }

    private void loadCommonWords() {
        words.put("Networks", "Redes"); words.put("Search", "Buscar"); words.put("Downloads", "Descargas"); words.put("Shared", "Compartidos"); words.put("More", "Más");
        words.put("Clients", "Clientes"); words.put("Messages", "Mensajes"); words.put("Statistics", "Estadísticas"); words.put("Preferences", "Preferencias"); words.put("About", "Acerca de");
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
