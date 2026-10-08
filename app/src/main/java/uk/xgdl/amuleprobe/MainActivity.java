package uk.xgdl.amuleprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Native Android UI experiment. The production WebView remains on the main branch. */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1;
    private int INK;
    private int MUTED;
    private int BLUE;
    private int PAGE;
    private int BORDER;
    private int SURFACE;
    private int TOOLBAR;
    private int FOOTER;
    private boolean darkMode;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final AtomicBoolean eventStreamRunning = new AtomicBoolean(false);
    private final Set<String> livePagesPending = new HashSet<>();
    private volatile boolean eventStreamLive;
    private volatile String lastEventId = "";
    private boolean liveDownloadsPending;
    private final OnBackInvokedCallback backCallback = this::handleBack;
    private TextView startupMessage;
    private TextView pageTitle;
    private TextView footer;
    private LinearLayout navigationBar;
    private LinearLayout pageContent;
    private NativeApiClient api;
    private JSONObject status = new JSONObject();
    private JSONArray downloads = new JSONArray();
    private JSONArray downloadCategories = new JSONArray();
    private JSONArray searchResults = new JSONArray();
    private JSONArray openSearches = new JSONArray();
    private JSONArray sharedFiles = new JSONArray();
    private JSONArray clients = new JSONArray();
    private JSONArray knownClients = new JSONArray();
    private JSONArray chats = new JSONArray();
    private JSONArray friends = new JSONArray();
    private JSONArray servers = new JSONArray();
    private int clientsTotal;
    private int knownClientsTotal;
    private int serversTotal;
    private JSONArray statNodes = new JSONArray();
    private final Map<String, JSONObject> graphData = new HashMap<>();
    private NativeStrings nativeStrings;
    private long lastGraphRefreshMillis;
    private JSONObject preferences = new JSONObject();
    private boolean preferencesLoaded;
    private String preferencesError;
    private JSONObject aboutVersionInfo;
    private String aboutVersionError;
    private boolean aboutVersionLoading;
    private boolean aboutUpdateCheckRunning;
    private int activeSearchId;
    private final Map<Integer, SearchUiState> searchUiStates = new HashMap<>();
    private String activeSearchQuery = "";
    private String searchState = "";
    private boolean searchKadActive;
    private final Set<String> selectedSearchHashes = new HashSet<>();
    private String searchQuery = "";
    private String searchExtension = "";
    private String searchMinSources = "";
    private String searchMinSize = "";
    private String searchMaxSize = "";
    private String searchFileType = "";
    private String searchResultFilter = "";
    private String searchResultHave = "All";
    private String searchResultSort = "Sources";
    private final Set<String> hiddenSearchFields = new HashSet<>();
    private EditText searchInput;
    private Spinner searchType;
    private Spinner searchFileTypeSpinner;
    private String selectedPage = "Downloads";
    private String lastApiError;
    private String downloadFilterQuery = "";
    private String downloadFilterStatus = "All";
    private String downloadFilterCategory = "All";
    private String downloadSort = "Name";
    private final Set<String> selectedDownloadHashes = new HashSet<>();
    private String preferencesTab = "General";
    private String nativeTheme = "system";
    private String clientListMode = "Connected";
    private String clientsSort = "Name";
    private String serverFilter = "";
    private String serverSort = "Name";
    private String sharedFilter = "";
    private String sharedUploadFilter = "All";
    private String sharedSort = "Name";
    private String clientsFilter = "";
    private final Set<String> selectedSharedHashes = new HashSet<>();
    private String nativeLanguage = "English";
    private boolean opening;
    private int attempts;

    private static final class SearchUiState {
        final Set<String> selectedHashes = new HashSet<>();
        final Set<String> hiddenFields = new HashSet<>();
        String filter = "";
        String have = "All";
        String sort = "Sources";

        SearchUiState() {
            hiddenFields.addAll(java.util.Arrays.asList("directory", "length", "bitrate", "codec", "artist", "album", "title"));
        }
    }

    private SearchUiState searchUiState(int id) {
        return searchUiStates.computeIfAbsent(id, ignored -> new SearchUiState());
    }

    private void saveActiveSearchUiState() {
        if (activeSearchId <= 0) return;
        SearchUiState state = searchUiState(activeSearchId);
        state.selectedHashes.clear();
        state.selectedHashes.addAll(selectedSearchHashes);
        state.filter = searchResultFilter;
        state.have = searchResultHave;
        state.sort = searchResultSort;
        state.hiddenFields.clear();
        state.hiddenFields.addAll(hiddenSearchFields);
    }

    private void restoreSearchUiState(int id) {
        SearchUiState state = searchUiState(id);
        selectedSearchHashes.clear();
        selectedSearchHashes.addAll(state.selectedHashes);
        searchResultFilter = state.filter;
        searchResultHave = state.have;
        searchResultSort = state.sort;
        hiddenSearchFields.clear();
        hiddenSearchFields.addAll(state.hiddenFields);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (api != null && !isFinishing()) {
                refreshSnapshot();
                handler.postDelayed(this, eventStreamLive ? 30000 : 5000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        nativeTheme = getSharedPreferences("native_ui", MODE_PRIVATE).getString("theme", "system");
        nativeLanguage = getSharedPreferences("native_ui", MODE_PRIVATE).getString("language", "English");
        boolean systemDark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(nativeTheme.equals("dark") || (nativeTheme.equals("system") && systemDark)
                ? R.style.PreviewThemeDark : R.style.PreviewTheme);
        super.onCreate(state);
        nativeStrings = new NativeStrings(this);
        if (state != null) {
            selectedPage = state.getString("native_page", selectedPage);
            preferencesTab = state.getString("native_preferences_tab", preferencesTab);
        }
        applyNativePalette();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        if (handleStopIntent(getIntent())) return;
        showStarting();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
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
        frame.setBackgroundColor(PAGE);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        progressParams.bottomMargin = dp(38);
        frame.addView(progress, progressParams);
        startupMessage = new TextView(this);
        startupMessage.setText(tr("Starting aMule…"));
        startupMessage.setTextSize(18);
        startupMessage.setTextColor(INK);
        startupMessage.setGravity(Gravity.CENTER);
        startupMessage.setPadding(dp(24), dp(24), dp(24), dp(24));
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        textParams.topMargin = dp(46);
        frame.addView(startupMessage, textParams);
        setContentView(frame);
        applySystemBarInsets(frame);
    }

    private void checkReady() {
        if (isFinishing() || opening) return;
        android.content.SharedPreferences prefs = getSharedPreferences(AmuleService.PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(AmuleService.READY, false)) {
            opening = true;
            String password = prefs.getString(AmuleService.ADMIN_PASSWORD, "");
            new Thread(() -> loginAndOpen(password), "aMule-native-login").start();
            return;
        }
        String error = prefs.getString(AmuleService.LAST_ERROR, "");
        if (!error.isEmpty()) {
            startupMessage.setText(tr("aMule could not start") + "\n\n" + error);
            return;
        }
        if (++attempts > 180) {
            startupMessage.setText(tr("aMule is taking longer than expected to start. Check the persistent notification."));
            return;
        }
        handler.postDelayed(this::checkReady, 500);
    }

    private void loginAndOpen(String password) {
        NativeApiClient client = new NativeApiClient();
        try {
            client.login(password);
            JSONObject nextStatus = client.get("status");
            JSONArray nextDownloads = client.get("downloads?status=all&limit=1000000000")
                    .optJSONArray("downloads");
            runOnUiThread(() -> {
                api = client;
                status = nextStatus;
                downloads = nextDownloads == null ? new JSONArray() : nextDownloads;
                showNativeInterface();
                // A recreated activity has fresh in-memory page data. Reload the selected
                // page after login so restored Preferences (and other secondary pages)
                // do not remain empty until the user leaves and reopens them.
                if (selectedPage.equals("Search")) refreshSearch();
                else if (!selectedPage.equals("Downloads")) refreshSectionData();
                handler.postDelayed(poll, 5000);
                startNativeEventStream(client);
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                opening = false;
                startupMessage.setText(tr("Could not open the local aMule API") + "\n\n" + error.getMessage());
            });
        }
    }

    private void startNativeEventStream(NativeApiClient client) {
        if (!eventStreamRunning.compareAndSet(false, true)) return;
        Thread stream = new Thread(() -> {
            while (eventStreamRunning.get() && !isFinishing()) {
                try {
                    client.streamEvents(eventStreamRunning, lastEventId, new NativeApiClient.EventListener() {
                        @Override public void onConnected() { handler.post(() -> { eventStreamLive = true; updateFooter(lastApiError); }); }
                        @Override public void onEvent(String name, String id, JSONObject payload) {
                            lastEventId = id;
                            handleNativeEvent(name, payload);
                        }
                    });
                } catch (Exception failure) {
                    handler.post(() -> { eventStreamLive = false; updateFooter(lastApiError); });
                }
                if (!eventStreamRunning.get()) break;
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }, "aMule-native-event-stream");
        stream.setDaemon(true);
        stream.start();
    }

    private void handleNativeEvent(String name, JSONObject payload) {
        if (name.equals("resync")) {
            handler.post(() -> { livePagesPending.clear(); liveDownloadsPending = false; refreshSnapshot(); if (selectedPage.equals("Search")) refreshSearch(); });
            return;
        }
        if (name.equals("status_changed")) {
            handler.post(() -> { status = payload; updateFooter(lastApiError); if (selectedPage.equals("Networks")) renderCurrentPage(); });
            return;
        }
        String page = null;
        if (name.startsWith("download_") || name.equals("comments_updated")) page = "Downloads";
        else if (name.startsWith("shared_")) page = "Shared";
        else if (name.startsWith("server_")) page = "Networks";
        else if (name.startsWith("client_")) page = "Clients";
        else if (name.startsWith("friend_") || name.startsWith("chat_")) page = "Messages";
        else if (name.startsWith("search_")) page = "Search";
        if (page == null) return;
        synchronized (livePagesPending) {
            if (page.equals("Downloads")) liveDownloadsPending = true;
            else livePagesPending.add(page);
        }
        handler.removeCallbacks(liveRefresh);
        handler.postDelayed(liveRefresh, 350);
    }

    private final Runnable liveRefresh = () -> {
        boolean downloadsChanged;
        Set<String> pages;
        synchronized (livePagesPending) { downloadsChanged = liveDownloadsPending; liveDownloadsPending = false; pages = new HashSet<>(livePagesPending); livePagesPending.clear(); }
        if (downloadsChanged && selectedPage.equals("Downloads")) refreshSnapshot();
        if (pages.contains(selectedPage)) {
            if (selectedPage.equals("Search")) refreshSearch();
            else refreshSectionData();
        }
    };

    private void refreshSnapshot() {
        if (api == null || !refreshing.compareAndSet(false, true)) return;
        new Thread(() -> {
            JSONObject nextStatus = status;
            JSONArray nextDownloads = downloads;
            String error = null;
            try {
                nextStatus = api.get("status");
                JSONArray rows = api.get("downloads?status=all&limit=1000000000").optJSONArray("downloads");
                if (rows != null) nextDownloads = rows;
            } catch (IOException | JSONException failure) {
                error = failure.getMessage();
            } finally {
                JSONObject resultStatus = nextStatus;
                JSONArray resultDownloads = nextDownloads;
                String resultError = error;
                handler.post(() -> {
                    status = resultStatus;
                    downloads = resultDownloads;
                    refreshing.set(false);
                    lastApiError = resultError;
                    View focused = getCurrentFocus();
                    if (selectedPage.equals("Preferences")) updateFooter(lastApiError);
                    else if (!(focused instanceof EditText && focused.hasFocus())) renderCurrentPage();
                    else updateFooter(lastApiError);
                });
            }
        }, "aMule-native-refresh").start();
        if (selectedPage.equals("Search")) refreshSearch();
        else if (!selectedPage.equals("Preferences")) refreshSectionData();
    }

    private void refreshSectionData() {
        if (api == null) return;
        final String page = selectedPage;
        if (page.equals("Statistics") || page.equals("Networks")) refreshGraphData(page);
        String endpoint;
        if (page.equals("Downloads")) endpoint = "categories";
        else if (page.equals("Shared")) endpoint = "shared";
        else if (page.equals("Clients")) endpoint = clientListMode.equals("Known")
                ? "known_clients?limit=50&offset=0&sort=" + clientSortField() + "&order=" + clientSortOrder()
                : "clients?limit=50&offset=0&sort=" + clientSortField() + "&order=" + clientSortOrder();
        else if (page.equals("Messages")) endpoint = "chats";
        else if (page.equals("Networks")) endpoint = "servers?limit=50&offset=0&sort=" + serverSortField() + "&order=" + serverSortOrder();
        else if (page.equals("Statistics")) endpoint = "stats/tree";
        else if (page.equals("Preferences")) endpoint = "preferences";
        else return;
        new Thread(() -> {
            try {
                JSONObject result = api.get(endpoint);
                JSONArray friendResult = page.equals("Messages") ? api.get("friends").optJSONArray("friends") : null;
                handler.post(() -> {
                    if (!page.equals(selectedPage)) return;
                    if (page.equals("Downloads")) downloadCategories = result.optJSONArray("categories") == null ? new JSONArray() : result.optJSONArray("categories");
                    else if (page.equals("Shared")) sharedFiles = result.optJSONArray("shared") == null ? new JSONArray() : result.optJSONArray("shared");
                    else if (page.equals("Clients")) {
                        if (clientListMode.equals("Known")) { knownClients = result.optJSONArray("known_clients") == null ? new JSONArray() : result.optJSONArray("known_clients"); knownClientsTotal = result.optInt("total", knownClients.length()); }
                        else { clients = result.optJSONArray("clients") == null ? new JSONArray() : result.optJSONArray("clients"); clientsTotal = result.optInt("total", clients.length()); }
                    }
                    else if (page.equals("Messages")) {
                        chats = result.optJSONArray("chats") == null ? new JSONArray() : result.optJSONArray("chats");
                        friends = friendResult == null ? new JSONArray() : friendResult;
                    }
                    else if (page.equals("Networks")) { servers = result.optJSONArray("servers") == null ? new JSONArray() : result.optJSONArray("servers"); serversTotal = result.optInt("total", servers.length()); }
                    else if (page.equals("Statistics")) statNodes = result.optJSONArray("nodes") == null ? new JSONArray() : result.optJSONArray("nodes");
                    else {
                        preferences = result;
                        preferencesLoaded = true;
                        preferencesError = null;
                    }
                    renderCurrentPage();
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    lastApiError = failure.getMessage();
                    if (page.equals("Preferences")) preferencesError = failure.getMessage();
                    if (page.equals(selectedPage)) {
                        if (page.equals("Preferences")) renderCurrentPage();
                        else updateFooter(lastApiError);
                    }
                });
            }
        }, "aMule-native-" + page.toLowerCase(java.util.Locale.UK)).start();
    }

    private void refreshSearch() {
        if (api == null) return;
        new Thread(() -> {
            try {
                JSONArray listed = api.get("search").optJSONArray("searches");
                if (listed == null) listed = new JSONArray();
                int selectedId = activeSearchId;
                JSONObject selected = null;
                for (int i = 0; i < listed.length(); i++) {
                    JSONObject item = listed.optJSONObject(i);
                    if (item != null && item.optInt("search_id") == selectedId) selected = item;
                }
                if (selected == null && listed.length() > 0) {
                    for (int i = listed.length() - 1; i >= 0; i--) {
                        JSONObject item = listed.optJSONObject(i);
                        if (item != null && item.optString("state").equals("running")) { selected = item; break; }
                    }
                    if (selected == null) selected = listed.optJSONObject(listed.length() - 1);
                    if (selected != null) selectedId = selected.optInt("search_id");
                }
                JSONArray visibleSearches = listed;
                JSONObject active = selected;
                int oldId = activeSearchId;
                if (selectedId == 0 || selected == null) {
                    handler.post(() -> {
                        boolean changed = !openSearches.toString().equals(visibleSearches.toString()) || activeSearchId != 0;
                        saveActiveSearchUiState();
                        openSearches = visibleSearches;
                        activeSearchId = 0;
                        activeSearchQuery = "";
                        searchState = "";
                        searchKadActive = false;
                        searchResults = new JSONArray();
                        if (changed && selectedPage.equals("Search")) renderCurrentPage();
                    });
                    return;
                }
                JSONObject response = api.get("search/" + selectedId + "/results");
                JSONArray rows = response.optJSONArray("results");
                JSONObject progress = response.optJSONObject("progress");
                String query = response.optString("query", active == null ? "" : active.optString("query", ""));
                String state = progress == null ? (active == null ? "" : active.optString("state", "")) : progress.optString("state", "");
                boolean kadActive = progress != null && progress.optBoolean("kad_active");
                int id = selectedId;
                int chosenId = selectedId;
                handler.post(() -> {
                    boolean tabsChanged = !openSearches.toString().equals(visibleSearches.toString());
                    openSearches = visibleSearches;
                    if (id == activeSearchId || activeSearchId == oldId) {
                        if (activeSearchId != chosenId) {
                            saveActiveSearchUiState();
                            restoreSearchUiState(chosenId);
                        }
                        activeSearchId = chosenId;
                        boolean changed = !searchResults.toString().equals((rows == null ? new JSONArray() : rows).toString())
                                || !searchState.equals(state) || searchKadActive != kadActive
                                || !activeSearchQuery.equals(query) || oldId != chosenId;
                        searchResults = rows == null ? new JSONArray() : rows;
                        activeSearchQuery = query;
                        searchState = state;
                        searchKadActive = kadActive;
                        if ((changed || tabsChanged) && selectedPage.equals("Search")) renderCurrentPage();
                    }
                });
            } catch (Exception failure) {
                lastApiError = failure.getMessage();
            }
        }, "aMule-native-search-refresh").start();
    }

    private void startSearch(String query, String type) {
        String clean = query.trim();
        if (clean.isEmpty()) {
            nativeToast( "Enter search terms first", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("query", clean).put("type", type);
                if (!searchFileType.isEmpty()) body.put("file_type", searchFileType);
                if (!searchExtension.trim().isEmpty()) body.put("extension", searchExtension.trim());
                if (Integer.parseInt(searchMinSources.isEmpty() ? "0" : searchMinSources) > 0)
                    body.put("min_source_count", Integer.parseInt(searchMinSources));
                if (Double.parseDouble(searchMinSize.isEmpty() ? "0" : searchMinSize) > 0)
                    body.put("min_size_bytes", (long) (Double.parseDouble(searchMinSize) * 1048576));
                if (Double.parseDouble(searchMaxSize.isEmpty() ? "0" : searchMaxSize) > 0)
                    body.put("max_size_bytes", (long) (Double.parseDouble(searchMaxSize) * 1048576));
                JSONObject response = api.post("search", body);
                int id = response.optInt("search_id");
                handler.post(() -> {
                    saveActiveSearchUiState();
                    activeSearchId = id;
                    searchUiStates.remove(id);
                    restoreSearchUiState(id);
                    activeSearchQuery = clean;
                    searchState = "running";
                    searchResults = new JSONArray();
                    renderCurrentPage();
                    refreshSearch();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Search failed: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-search-start").start();
    }

    private void selectSearch(int id) {
        if (id == 0 || id == activeSearchId) { refreshSearch(); return; }
        saveActiveSearchUiState();
        activeSearchId = id;
        restoreSearchUiState(id);
        searchResults = new JSONArray();
        for (int i = 0; i < openSearches.length(); i++) {
            JSONObject item = openSearches.optJSONObject(i);
            if (item != null && item.optInt("search_id") == id) {
                activeSearchQuery = item.optString("query", "");
                searchState = item.optString("state", "");
                break;
            }
        }
        searchKadActive = false;
        renderCurrentPage();
        refreshSearch();
    }

    private void closeSearch(int id) {
        new Thread(() -> {
            try {
                api.delete("search/" + id);
                handler.post(() -> {
                    searchUiStates.remove(id);
                    if (activeSearchId == id) activeSearchId = 0;
                    refreshSearch();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not close search: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-search-close").start();
    }

    private void browsePeer(String endpoint, int ecid, String name) {
        new Thread(() -> {
            try {
                JSONObject result = api.post(endpoint + "/" + ecid + "/shared_files", new JSONObject());
                int id = result.optInt("search_id");
                if (id <= 0) throw new IOException("aMule did not return a browse search");
                handler.post(() -> {
                    saveActiveSearchUiState();
                    activeSearchId = id;
                    searchUiStates.remove(id);
                    restoreSearchUiState(id);
                    activeSearchQuery = name;
                    searchState = "running";
                    searchResults = new JSONArray();
                    searchKadActive = false;
                    selectedPage = "Search";
                    renderCurrentPage();
                    refreshSearch();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not browse this peer: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-peer-browse").start();
    }

    private void addSearchResult(JSONObject result) {
        String hash = result.optString("hash", "");
        if (hash.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONArray categories = api.get("categories").optJSONArray("categories");
                if (categories == null) categories = new JSONArray();
                JSONArray rows = categories;
                handler.post(() -> showSearchResultOptions(result, hash, rows));
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not load download categories: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-search-download").start();
    }

    private void showSearchResultOptions(JSONObject result, String hash, JSONArray categories) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);
        JSONArray alternatives = result.optJSONArray("alternate_names");
        Spinner namePicker = null;
        if (alternatives != null && alternatives.length() > 0) {
            String[] names = new String[alternatives.length() + 1];
            names[0] = result.optString("name", "Unknown file");
            for (int i = 0; i < alternatives.length(); i++) {
                JSONObject alternative = alternatives.optJSONObject(i);
                names[i + 1] = alternative == null ? "Unknown name" : alternative.optString("name", "Unknown name");
            }
            namePicker = new Spinner(this);
            ArrayAdapter<String> namesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
            namesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            namePicker.setAdapter(namesAdapter);
            content.addView(namePicker, marginParams(0, 0, 0, 8));
        }
        String[] categoryNames = new String[Math.max(1, categories.length())];
        int[] categoryIndexes = new int[categoryNames.length];
        if (categories.length() == 0) categoryNames[0] = "Default";
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            categoryNames[i] = category == null ? "Default" : category.optString("name", "Default");
            categoryIndexes[i] = category == null ? 0 : category.optInt("index");
        }
        Spinner categoryPicker = new Spinner(this);
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categoryNames);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categoryPicker.setAdapter(categoryAdapter);
        content.addView(categoryPicker);
        Spinner selectedNamePicker = namePicker;
        new AlertDialog.Builder(this).setTitle(tr("Add to downloads")).setView(content).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Add"), (dialog, which) -> {
                    JSONObject body = json("category_index", categoryIndexes[categoryPicker.getSelectedItemPosition()]);
                    if (selectedNamePicker != null && selectedNamePicker.getSelectedItemPosition() > 0) {
                        JSONObject alternative = alternatives.optJSONObject(selectedNamePicker.getSelectedItemPosition() - 1);
                        if (alternative != null && alternative.optInt("ecid") > 0) {
                            try { body.put("ecid", alternative.optInt("ecid")); } catch (JSONException ignored) { }
                        }
                    }
                    new Thread(() -> {
                        try {
                            api.post("search/results/" + hash + "/download", body);
                            handler.post(() -> { nativeToast( "Added to downloads", Toast.LENGTH_SHORT).show(); refreshSnapshot(); });
                        } catch (Exception failure) {
                            handler.post(() -> nativeToast( "Could not add download: " + failure.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }, "aMule-native-search-download").start();
                }).show();
    }

    private void showNativeInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(6), dp(12), dp(6));
        toolbar.setBackgroundColor(TOOLBAR);
        TextView menu = new TextView(this);
        menu.setId(R.id.native_menu);
        menu.setText(tr("☰"));
        menu.setTextSize(25);
        menu.setTextColor(INK);
        menu.setGravity(Gravity.CENTER);
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        menu.setOnClickListener(this::showNavigation);

        TextView brand = new TextView(this);
        brand.setText(tr("aMule"));
        brand.setTextColor(BLUE);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setTextSize(16);
        brand.setPadding(dp(8), 0, dp(14), 0);
        toolbar.addView(brand);

        pageTitle = new TextView(this);
        pageTitle.setId(R.id.native_page_title);
        pageTitle.setTextSize(22);
        pageTitle.setTextColor(INK);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(pageTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView refresh = new TextView(this);
        refresh.setText(tr("↻"));
        refresh.setTextSize(28);
        refresh.setTextColor(INK);
        refresh.setGravity(Gravity.CENTER);
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(48)));
        refresh.setOnClickListener(view -> refreshSnapshot());
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(12), dp(14), dp(12), dp(18));
        scroll.addView(pageContent);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        navigationBar = createNavigationBar();
        root.addView(navigationBar);
        footer = new TextView(this);
        footer.setTextSize(12);
        footer.setTextColor(INK);
        footer.setPadding(dp(14), dp(10), dp(14), dp(10));
        footer.setBackgroundColor(FOOTER);
        root.addView(footer);

        setContentView(root);
        applySystemBarInsets(root);
        renderCurrentPage();
    }

    private LinearLayout createNavigationBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        nav.setBackgroundColor(SURFACE);
        String[] pages = {"Networks", "Search", "Downloads", "Shared", "More"};
        for (String page : pages) {
            TextView tab = new TextView(this);
            tab.setText(tr(page));
            tab.setTextSize(11);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(2), dp(10), dp(2), dp(10));
            tab.setTextColor(page.equals(selectedPage) ? BLUE : MUTED);
            tab.setTypeface(Typeface.DEFAULT, page.equals(selectedPage) ? Typeface.BOLD : Typeface.NORMAL);
            nav.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tab.setOnClickListener(view -> {
                if (page.equals("More")) showMoreMenu(view);
                else selectPage(page);
            });
        }
        return nav;
    }

    private void showNavigation(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] pages = {"Networks", "Search", "Downloads", "Shared", "Clients",
                "Messages", "Statistics", "Preferences", "About", "Full Web UI"};
        for (int i = 0; i < pages.length; i++) menu.getMenu().add(0, i, i, tr(pages[i]));
        menu.setOnMenuItemClickListener(item -> {
            String page = pages[item.getItemId()];
            if (page.equals("Full Web UI")) {
                startActivity(new Intent(this, WebUiActivity.class));
                return true;
            }
            selectPage(page);
            return true;
        });
        menu.show();
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] pages = {"Clients", "Messages", "Statistics", "Preferences", "About", "Full Web UI"};
        for (int i = 0; i < pages.length; i++) menu.getMenu().add(0, i, i, tr(pages[i]));
        menu.setOnMenuItemClickListener(item -> {
            String page = pages[item.getItemId()];
            if (page.equals("Full Web UI")) {
                startActivity(new Intent(this, WebUiActivity.class));
                return true;
            }
            selectPage(page);
            return true;
        });
        menu.show();
    }

    private void selectPage(String page) {
        selectedPage = page;
        renderCurrentPage();
        if (page.equals("Networks") || page.equals("Downloads") || page.equals("Search")
                || page.equals("Shared") || page.equals("Clients") || page.equals("Messages")
                || page.equals("Statistics") || page.equals("Preferences")) refreshSnapshot();
        if (page.equals("Preferences")) refreshSectionData();
        if (page.equals("About")) loadAboutVersion();
    }

    private void renderCurrentPage() {
        if (pageContent == null) return;
        pageTitle.setText(tr(selectedPage));
        updateNavigationSelection();
        pageContent.removeAllViews();
        if (selectedPage.equals("Downloads")) renderDownloads();
        else if (selectedPage.equals("Networks")) renderNetworks();
        else if (selectedPage.equals("Search")) renderSearch();
        else if (selectedPage.equals("Shared")) renderShared();
        else if (selectedPage.equals("Clients")) renderClients();
        else if (selectedPage.equals("Messages")) renderMessages();
        else if (selectedPage.equals("Statistics")) renderStatistics();
        else if (selectedPage.equals("Preferences")) renderPreferences();
        else if (selectedPage.equals("About")) renderAbout();
        else renderComingSoon();
        updateFooter(lastApiError);
    }

    private void updateNavigationSelection() {
        if (navigationBar == null) return;
        for (int i = 0; i < navigationBar.getChildCount(); i++) {
            View child = navigationBar.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tab = (TextView) child;
            boolean selected = tab.getText().toString().equals(tr(selectedPage));
            tab.setTextColor(selected ? BLUE : MUTED);
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void renderDownloads() {
        LinearLayout card = card();
        LinearLayout heading = horizontal();
        TextView title = new TextView(this);
        title.setText(tr("All  " + downloads.length()));
        title.setTextColor(INK);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        title.setSingleLine(true);
        CheckBox selectAll = new CheckBox(this);
        selectAll.setText(tr("Select shown"));
        selectAll.setChecked(filteredDownloads().length() > 0 && selectedDownloadHashes.containsAll(downloadHashes(filteredDownloads())));
        selectAll.setOnCheckedChangeListener((button, checked) -> {
            for (String hash : downloadHashes(filteredDownloads())) {
                if (checked) selectedDownloadHashes.add(hash); else selectedDownloadHashes.remove(hash);
            }
            renderCurrentPage();
        });
        heading.addView(selectAll);
        Button categories = button("Manage categories", false);
        categories.setOnClickListener(view -> showCategoriesDialog());
        card.addView(heading);
        LinearLayout categoryAction = horizontal();
        categoryAction.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        categoryAction.addView(categories);
        card.addView(categoryAction, marginParams(0, 0, 0, 4));

        Button addLink = button("Add ed2k or magnet link", true);
        addLink.setOnClickListener(view -> showAddLinkDialog());
        card.addView(addLink, marginParams(0, 2, 0, 0));

        LinearLayout filterCard = card();
        LinearLayout filterRow = horizontal();
        EditText filterText = new EditText(this);
        filterText.setHint(tr("Filter downloads"));
        filterText.setSingleLine(true);
        filterText.setText(downloadFilterQuery);
        filterText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { downloadFilterQuery = s.toString(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        filterRow.addView(filterText, new LinearLayout.LayoutParams(0, dp(52), 1));
        String[] statuses = {"All", "Downloading", "Waiting", "Paused", "Stopped", "Hashing", "Completed", "Erroneous", "Insufficient disk"};
        Spinner statusFilter = spinner(statuses, downloadFilterStatus);
        filterRow.addView(statusFilter, new LinearLayout.LayoutParams(dp(130), dp(52)));
        String[] categoryLabels = new String[downloadCategories.length() + 1];
        String[] categoryIndexes = new String[downloadCategories.length() + 1];
        categoryLabels[0] = "All categories";
        categoryIndexes[0] = "All";
        for (int i = 0; i < downloadCategories.length(); i++) {
            JSONObject category = downloadCategories.optJSONObject(i);
            categoryLabels[i + 1] = category == null ? "Default" : category.optString("name", "Default");
            categoryIndexes[i + 1] = category == null ? "0" : String.valueOf(category.optInt("index"));
        }
        Spinner categoryFilter = spinner(categoryLabels, categoryNameForFilter(downloadFilterCategory, categoryIndexes, categoryLabels));
        Button applyFilter = button("Apply", false);
        applyFilter.setOnClickListener(view -> {
            downloadFilterStatus = statusFilter.getSelectedItem().toString();
            downloadFilterCategory = categoryIndexes[Math.max(0, categoryFilter.getSelectedItemPosition())];
            renderCurrentPage();
        });
        filterRow.addView(applyFilter);
        filterCard.addView(filterRow);
        LinearLayout categoryRow = horizontal();
        categoryRow.addView(bodyText("Category"), new LinearLayout.LayoutParams(dp(90), dp(48)));
        categoryRow.addView(categoryFilter, new LinearLayout.LayoutParams(0, dp(48), 1));
        categoryRow.addView(button("Apply", false), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        View categoryApply = categoryRow.getChildAt(categoryRow.getChildCount() - 1);
        categoryApply.setOnClickListener(view -> {
            downloadFilterCategory = categoryIndexes[Math.max(0, categoryFilter.getSelectedItemPosition())];
            renderCurrentPage();
        });
        filterCard.addView(categoryRow);
        pageContent.addView(filterCard, marginParams(0, 0, 0, 10));

        Button bulkActions = button("Actions for " + selectedDownloadHashes.size() + " selected downloads", false);
        bulkActions.setEnabled(!selectedDownloadHashes.isEmpty());
        bulkActions.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Selected downloads"))
                .setItems(localizedOptions(new String[] {"Resume", "Pause", "Cancel", "Priority", "Category"}),
                        (dialog, choice) -> runBulkDownloadAction(new String[] {"Resume", "Pause", "Cancel", "Priority", "Category"}[choice]))
                .show());
        card.addView(bulkActions, marginParams(0, 8, 0, 0));
        Button clearCompleted = button("Clear completed downloads", false);
        clearCompleted.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Clear completed downloads?"))
                .setMessage(tr("This removes completed entries from the queue. It does not delete the completed files."))
                .setNegativeButton(tr("Keep"), null).setPositiveButton(tr("Clear"), (dialog, which) ->
                        new Thread(() -> {
                            try { api.post("downloads_clear_completed", new JSONObject()); handler.post(() -> refreshSnapshot()); }
                            catch (Exception error) { handler.post(() -> nativeToast( "Could not clear completed downloads: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
                        }, "aMule-native-clear-completed").start()).show());
        card.addView(clearCompleted, marginParams(0, 4, 0, 0));
        Spinner sort = spinner(new String[] {"Name", "Size", "Progress", "Speed", "Sources", "Status"}, downloadSort);
        sort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String next = (String) parent.getItemAtPosition(position);
                if (!downloadSort.equals(next)) { downloadSort = next; renderCurrentPage(); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        card.addView(sort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView info = new TextView(this);
        JSONArray visibleDownloads = filteredDownloads();
        selectedDownloadHashes.retainAll(downloadHashes(visibleDownloads));
        info.setText(tr("Download queue  ·  " + visibleDownloads.length() + " shown of " + downloads.length()
                + "  ·  " + selectedDownloadHashes.size() + " selected"));
        info.setTextColor(MUTED);
        info.setTextSize(14);
        info.setPadding(0, dp(6), 0, dp(8));
        card.addView(info);
        pageContent.addView(card, marginParams(0, 0, 0, 12));

        if (visibleDownloads.length() == 0) {
            LinearLayout empty = card();
            TextView nothing = new TextView(this);
            nothing.setText(tr("Nothing here yet."));
            nothing.setTextSize(18);
            nothing.setTextColor(MUTED);
            nothing.setGravity(Gravity.CENTER);
            nothing.setPadding(dp(12), dp(40), dp(12), dp(40));
            empty.addView(nothing);
            TextView hint = new TextView(this);
            hint.setText(tr("Files you add to aMule will appear here."));
            hint.setTextSize(14);
            hint.setTextColor(MUTED);
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint);
            pageContent.addView(empty, marginParams(0, 0, 0, 12));
        } else {
            for (int i = 0; i < visibleDownloads.length(); i++) {
                JSONObject download = visibleDownloads.optJSONObject(i);
                if (download != null) pageContent.addView(downloadCard(download), marginParams(0, 0, 0, 10));
            }
        }

        LinearLayout totals = card();
        long size = 0;
        long done = 0;
        long speed = 0;
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject row = downloads.optJSONObject(i);
            if (row == null) continue;
            size += row.optLong("size_bytes");
            done += row.optLong("completed_bytes");
            speed += row.optLong("speed_bytes_per_second");
        }
        TextView totalText = bodyText(downloads.length() + " files   ·   Size " + bytes(size)
                + "   ·   Done " + bytes(done) + "   ·   Speed " + speed(speed));
        totals.addView(totalText);
        pageContent.addView(totals);
    }

    private JSONArray filteredDownloads() {
        JSONArray filtered = new JSONArray();
        String query = downloadFilterQuery.trim().toLowerCase(java.util.Locale.ROOT);
        String wanted = downloadFilterStatus.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject row = downloads.optJSONObject(i);
            if (row == null) continue;
            String name = row.optString("name", "").toLowerCase(java.util.Locale.ROOT);
            String state = row.optString("status", "").toLowerCase(java.util.Locale.ROOT);
            if (!query.isEmpty() && !name.contains(query)) continue;
            if (!wanted.equals("all") && !state.equals(wanted)) continue;
            if (!downloadFilterCategory.equals("All") && row.optInt("category_index", 0) != parseInt(downloadFilterCategory, -1)) continue;
            filtered.put(row);
        }
        ArrayList<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < filtered.length(); i++) { JSONObject item = filtered.optJSONObject(i); if (item != null) sorted.add(item); }
        Comparator<JSONObject> comparator;
        if (downloadSort.equals("Size")) comparator = Comparator.comparingLong(item -> item.optLong("size_bytes"));
        else if (downloadSort.equals("Progress")) comparator = Comparator.comparingDouble(item -> item.optJSONObject("progress") == null ? 0 : item.optJSONObject("progress").optDouble("percent"));
        else if (downloadSort.equals("Speed")) comparator = Comparator.comparingLong(item -> item.optLong("speed_bytes_per_second"));
        else if (downloadSort.equals("Sources")) comparator = Comparator.comparingInt(item -> item.optJSONObject("sources") == null ? 0 : item.optJSONObject("sources").optInt("total"));
        else if (downloadSort.equals("Status")) comparator = Comparator.comparing(item -> item.optString("status", ""));
        else comparator = Comparator.comparing(item -> item.optString("name", "").toLowerCase(java.util.Locale.ROOT));
        sorted.sort(comparator);
        if (!downloadSort.equals("Name") && !downloadSort.equals("Status")) java.util.Collections.reverse(sorted);
        JSONArray result = new JSONArray();
        for (JSONObject item : sorted) result.put(item);
        return result;
    }

    private ArrayList<String> downloadHashes(JSONArray rows) {
        ArrayList<String> hashes = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item != null && !item.optString("hash").isEmpty()) hashes.add(item.optString("hash"));
        }
        return hashes;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private String categoryNameForFilter(String selected, String[] indexes, String[] labels) {
        for (int i = 0; i < indexes.length; i++) if (indexes[i].equals(selected)) return labels[i];
        return labels[0];
    }

    private void runBulkDownloadAction(String action) {
        ArrayList<String> hashes = new ArrayList<>(selectedDownloadHashes);
        if (hashes.isEmpty()) return;
        Runnable run = () -> new Thread(() -> {
            try {
                JSONObject payload = new JSONObject().put("hashes", new JSONArray(hashes));
                if (action.equals("Resume") || action.equals("Pause")) {
                    payload.put("action", action.equals("Resume") ? "resume" : "pause");
                    api.patch("downloads", payload);
                } else if (action.equals("Cancel")) {
                    api.delete("downloads", payload);
                } else if (action.equals("Priority")) {
                    String priority = getSharedPreferences("native_ui", MODE_PRIVATE).getString("bulk_priority", "normal");
                    payload.put("priority", priority);
                    api.patch("downloads", payload);
                } else if (action.equals("Category")) {
                    int index = parseInt(downloadFilterCategory, 0);
                    payload.put("category_index", Math.max(0, index));
                    api.patch("downloads", payload);
                }
                handler.post(() -> { selectedDownloadHashes.clear(); nativeToast( action + " applied to selected downloads", Toast.LENGTH_SHORT).show(); refreshSnapshot(); });
            } catch (Exception error) {
                handler.post(() -> nativeToast( "Bulk action failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-download-bulk-action").start();
        if (action.equals("Cancel")) new AlertDialog.Builder(this).setTitle(tr("Cancel selected downloads?"))
                .setMessage(tr("This removes the selected part files from disk.")).setNegativeButton(tr("Keep"), null)
                .setPositiveButton(tr("Cancel downloads"), (dialog, which) -> run.run()).show();
        else if (action.equals("Priority")) new AlertDialog.Builder(this).setTitle(tr("Priority"))
                .setItems(localizedOptions(new String[] {"Automatic", "Low", "Normal", "High"}), (dialog, which) -> {
                    String[] values = {"auto", "low", "normal", "high"};
                    getSharedPreferences("native_ui", MODE_PRIVATE).edit().putString("bulk_priority", values[which]).apply();
                    run.run();
                }).show();
        else if (action.equals("Category")) showBulkCategoryPicker(run);
        else run.run();
    }

    private void showBulkCategoryPicker(Runnable ignored) {
        String[] labels = new String[downloadCategories.length()];
        int[] indexes = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            JSONObject item = downloadCategories.optJSONObject(i);
            labels[i] = item == null ? "Default" : item.optString("name", "Default");
            indexes[i] = item == null ? 0 : item.optInt("index");
        }
        new AlertDialog.Builder(this).setTitle(tr("Move selected to category")).setItems(labels, (dialog, choice) -> {
            ArrayList<String> hashes = new ArrayList<>(selectedDownloadHashes);
            new Thread(() -> {
                try {
                    api.patch("downloads", new JSONObject().put("hashes", new JSONArray(hashes)).put("category_index", indexes[choice]));
                    handler.post(() -> { selectedDownloadHashes.clear(); refreshSnapshot(); });
                } catch (Exception error) { handler.post(() -> nativeToast( "Category change failed: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
            }, "aMule-native-download-bulk-category").start();
        }).setNegativeButton(tr("Cancel"), null).show();
    }

    private View downloadCard(JSONObject download) {
        LinearLayout row = card();
        LinearLayout titleRow = horizontal();
        TextView name = new TextView(this);
        name.setText(download.optString("name", "Unknown file"));
        name.setTextColor(INK);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        name.setOnClickListener(view -> showDownloadDetails(download));
        CheckBox selected = new CheckBox(this);
        selected.setContentDescription("Select " + download.optString("name", "download"));
        selected.setChecked(selectedDownloadHashes.contains(download.optString("hash")));
        selected.setOnCheckedChangeListener((button, checked) -> {
            String hash = download.optString("hash");
            if (checked) selectedDownloadHashes.add(hash); else selectedDownloadHashes.remove(hash);
            renderCurrentPage();
        });
        titleRow.addView(selected);
        row.addView(titleRow);

        TextView details = bodyText(tr(download.optString("status", "unknown")) + "  ·  "
                + bytes(download.optLong("completed_bytes")) + tr(" of ") + bytes(download.optLong("size_bytes"))
                + "  ·  " + speed(download.optLong("speed_bytes_per_second")));
        details.setPadding(0, dp(6), 0, dp(8));
        row.addView(details);

        JSONObject progress = download.optJSONObject("progress");
        int percent = progress == null ? 0 : (int) Math.round(progress.optDouble("percent"));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, percent)));
        row.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        LinearLayout actions = horizontal();
        String currentStatus = download.optString("status", "");
        boolean canResume = currentStatus.equals("paused") || currentStatus.equals("stopped");
        Button toggle = button(canResume ? "Resume" : "Pause", false);
        toggle.setOnClickListener(view -> mutate("PATCH", "downloads/" + download.optString("hash"),
                json("action", canResume ? "resume" : "pause"), "Download updated"));
        actions.addView(toggle, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button remove = button("Cancel", false);
        remove.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Cancel download?"))
                .setMessage(download.optString("name"))
                .setNegativeButton(tr("Keep"), null)
                .setPositiveButton(tr("Cancel download"), (dialog, which) -> mutate("DELETE", "downloads/" + download.optString("hash"), null, "Download cancelled"))
                .show());
        actions.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1));
        row.addView(actions, marginParams(0, 6, 0, 0));
        LinearLayout organisation = horizontal();
        Button priority = button("Priority", false);
        priority.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Download priority"))
                .setItems(localizedOptions(new String[] {"Automatic", "Low", "Normal", "High"}), (dialog, choice) -> {
                    String[] values = {"auto", "low", "normal", "high"};
                    mutate("PATCH", "downloads/" + download.optString("hash"), json("priority", values[choice]), "Priority updated");
                }).show());
        organisation.addView(priority, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button category = button("Category", false);
        category.setOnClickListener(view -> chooseDownloadCategory(download));
        organisation.addView(category, new LinearLayout.LayoutParams(0, dp(42), 1));
        row.addView(organisation, marginParams(0, 4, 0, 0));
        Button detail = button("Details", false);
        detail.setOnClickListener(view -> showDownloadDetails(download));
        row.addView(detail, marginParams(0, 4, 0, 0));
        return row;
    }

    private void showDownloadDetails(JSONObject item) {
        String hash = item.optString("hash", "");
        if (hash.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject detail = api.get("downloads/" + hash);
                JSONArray comments = api.get("downloads/" + hash + "/comments").optJSONArray("comments");
                JSONArray filenames = api.get("downloads/" + hash + "/filenames").optJSONArray("filenames");
                JSONArray peers = api.get("downloads/" + hash + "/clients").optJSONArray("clients");
                handler.post(() -> renderDownloadDetails(detail, comments == null ? new JSONArray() : comments,
                        filenames == null ? new JSONArray() : filenames, peers == null ? new JSONArray() : peers));
            } catch (Exception error) {
                handler.post(() -> nativeToast( "Could not load download details: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-download-detail").start();
    }

    private void renderDownloadDetails(JSONObject detail, JSONArray comments, JSONArray filenames, JSONArray peers) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(4), dp(18), 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        String hash = detail.optString("hash", "");
        String name = detail.optString("name", "Download");
        addDetailHeading(content, name);
        addDetailLine(content, "Status", detail.optString("status", "unknown"));
        addDetailLine(content, "Size / downloaded", bytes(detail.optLong("size_bytes")) + " / " + bytes(detail.optLong("completed_bytes")));
        JSONObject progress = detail.optJSONObject("progress");
        if (progress != null) addDetailLine(content, "Progress", String.format(java.util.Locale.UK, "%.1f%%", progress.optDouble("percent")));
        addDetailLine(content, "Speed", speed(detail.optLong("speed_bytes_per_second")));
        addDetailLine(content, "Sources", String.valueOf(detail.optJSONObject("sources") == null ? 0 : detail.optJSONObject("sources").optInt("total")));
        if (detail.has("remaining_seconds") && !detail.isNull("remaining_seconds")) addDetailLine(content, "Estimated time left", durationLabel(detail.optLong("remaining_seconds")));
        addDetailLine(content, "Priority", detail.optString("priority", "normal") + (detail.optBoolean("priority_auto") ? " (automatic)" : ""));
        addDetailLine(content, "Category", categoryName(detail.optInt("category_index", 0)));
        addDetailLine(content, "Directory", detail.optString("directory", ""));
        if (detail.has("part_file_name")) addDetailLine(content, "Part file", detail.optString("part_file_name", ""));
        addDetailLine(content, "Active time", durationLabel(detail.optLong("active_seconds")));
        addDetailLine(content, "Available parts", detail.optInt("available_part_count") + " / " + detail.optInt("total_part_count"));
        JSONArray parts = progress == null ? null : progress.optJSONArray("parts");
        if (parts != null) {
            int completeParts = 0;
            for (int i = 0; i < parts.length(); i++) if ("complete".equals(parts.optJSONObject(i) == null ? "" : parts.optJSONObject(i).optString("state"))) completeParts++;
            addDetailLine(content, "Completed chunks", completeParts + " / " + parts.length());
        }
        addDetailLine(content, "Corruption lost", bytes(detail.optLong("lost_to_corruption_bytes")));
        addDetailLine(content, "Compression saved", bytes(detail.optLong("gained_by_compression_bytes")));
        addDetailLine(content, "AICH", detail.isNull("aich_hash") ? "Not available" : detail.optString("aich_hash", "Not available"));
        JSONObject media = detail.optJSONObject("media");
        if (media != null) {
            addDetailHeading(content, "Media information");
            addDetailLine(content, "Artist / album / title", String.join(" · ", media.optString("artist", ""), media.optString("album", ""), media.optString("title", "")));
            addDetailLine(content, "Codec", media.optString("codec", ""));
            addDetailLine(content, "Duration", durationLabel(media.optLong("duration_seconds")));
            addDetailLine(content, "Bitrate", media.optInt("bitrate_kilobits_per_second") + " kb/s");
        }
        addDetailHeading(content, "Filenames reported by sources");
        for (int i = 0; i < filenames.length(); i++) {
            JSONObject filename = filenames.optJSONObject(i);
            if (filename != null) {
                String reported = filename.optString("filename", "Unknown");
                addDetailLine(content, reported, filename.optInt("source_count") + " sources");
                Button takeover = button("Use this filename", false);
                takeover.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Rename download?"))
                        .setMessage(tr("Use “" + reported + "” as the download name?"))
                        .setNegativeButton(tr("Keep current"), null).setPositiveButton(tr("Rename"), (dialog, which) ->
                                mutate("PATCH", "downloads/" + hash, json("name", reported), "Download renamed")).show());
                content.addView(takeover);
            }
        }
        addDetailHeading(content, "Comments and ratings");
        addDetailLine(content, "Your rating", detail.optInt("my_rating") + " / 5");
        addDetailLine(content, "Your comment", detail.optString("my_comment", ""));
        Button editComment = button("Edit your comment and rating", false);
        editComment.setOnClickListener(view -> showOwnDownloadCommentEditor(detail));
        content.addView(editComment);
        for (int i = 0; i < comments.length(); i++) {
            JSONObject comment = comments.optJSONObject(i);
            if (comment != null) addDetailLine(content, comment.optString("username", "Peer") + " · " + comment.optInt("rating") + "/5", comment.optString("comment", ""));
        }
        Button kad = button(detail.optBoolean("kad_comment_lookup_running") ? "Kad lookup running…" : "Get comments from Kad", false);
        kad.setEnabled(!detail.optBoolean("kad_comment_lookup_running"));
        kad.setOnClickListener(view -> apiAction("POST", "downloads/" + hash + "/comments", new JSONObject(), "Kad comments requested"));
        content.addView(kad, marginParams(0, 8, 0, 0));
        addDetailHeading(content, "Connected clients (" + peers.length() + ")");
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.optJSONObject(i);
            if (peer != null) addDetailLine(content, peer.optString("name", "Unknown peer"), peer.optString("role", "") + (peer.optBoolean("a4af") ? " · A4AF" : ""));
        }
        Button a4af = button(detail.optBoolean("a4af_auto") ? "Disable automatic source swapping" : "Enable automatic source swapping", false);
        a4af.setOnClickListener(view -> mutate("PATCH", "downloads/" + hash, json("a4af_auto", !detail.optBoolean("a4af_auto")), "A4AF preference updated"));
        content.addView(a4af, marginParams(0, 8, 0, 0));
        LinearLayout swaps = horizontal();
        Button swapHere = button("Swap sources here", false);
        swapHere.setOnClickListener(view -> apiAction("POST", "downloads/" + hash + "/a4af", json("action", "swap_this"), "Source swap requested"));
        Button swapAway = button("Swap sources elsewhere", false);
        swapAway.setOnClickListener(view -> apiAction("POST", "downloads/" + hash + "/a4af", json("action", "swap_others"), "Source swap requested"));
        swaps.addView(swapHere, new LinearLayout.LayoutParams(0, dp(48), 1));
        swaps.addView(swapAway, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(swaps);
        new AlertDialog.Builder(this).setTitle(tr("Download details")).setView(scroll)
                .setPositiveButton(tr("Close"), null).show();
    }

    private void addDetailHeading(LinearLayout target, String value) {
        TextView heading = bodyText(value);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(12), 0, dp(4));
        target.addView(heading);
    }

    private void showOwnDownloadCommentEditor(JSONObject detail) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText comment = new EditText(this);
        comment.setHint(tr("Your comment"));
        comment.setText(detail.optString("my_comment", ""));
        form.addView(comment);
        Spinner rating = spinner(new String[] {"Unrated", "1 · Invalid", "2 · Poor", "3 · Fair", "4 · Good", "5 · Excellent"},
                new String[] {"Unrated", "1 · Invalid", "2 · Poor", "3 · Fair", "4 · Good", "5 · Excellent"}[Math.max(0, Math.min(5, detail.optInt("my_rating")))]);
        form.addView(rating);
        new AlertDialog.Builder(this).setTitle(tr("Your file comment")).setView(form).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Save"), (dialog, which) -> new Thread(() -> {
                    try {
                        api.patch("downloads/" + detail.optString("hash"), new JSONObject()
                                .put("my_comment", comment.getText().toString())
                                .put("my_rating", rating.getSelectedItemPosition()));
                        handler.post(() -> nativeToast( "Comment and rating saved", Toast.LENGTH_SHORT).show());
                    } catch (Exception error) { handler.post(() -> nativeToast( "Could not save comment: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
                }, "aMule-native-download-comment-save").start()).show();
    }

    private void addDetailLine(LinearLayout target, String label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView line = bodyText(tr(label) + ": " + tr(value));
        line.setPadding(dp(2), dp(3), dp(2), dp(3));
        target.addView(line);
    }

    private static String durationLabel(long seconds) {
        if (seconds <= 0) return "—";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        return days > 0 ? days + "d " + hours + "h " + minutes + "m" : hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private String categoryName(int index) {
        for (int i = 0; i < downloadCategories.length(); i++) {
            JSONObject item = downloadCategories.optJSONObject(i);
            if (item != null && item.optInt("index") == index) return item.optString("name", "Default");
        }
        return "Default";
    }

    private void chooseDownloadCategory(JSONObject download) {
        new Thread(() -> {
            try {
                JSONArray categories = api.get("categories").optJSONArray("categories");
                if (categories == null) categories = new JSONArray();
                JSONArray rows = categories;
                handler.post(() -> {
                    String[] names = new String[rows.length()]; int[] indexes = new int[rows.length()];
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject item = rows.optJSONObject(i);
                        names[i] = item == null ? "Default" : item.optString("name", "Default");
                        indexes[i] = item == null ? 0 : item.optInt("index");
                    }
                    new AlertDialog.Builder(this).setTitle(tr("Move to category"))
                            .setItems(names, (dialog, choice) -> mutate("PATCH", "downloads/" + download.optString("hash"),
                                    json("category_index", indexes[choice]), "Category updated"))
                            .setNegativeButton(tr("Cancel"), null).show();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not load categories: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-download-categories").start();
    }

    private void renderNetworks() {
        LinearLayout networkActions = card();
        LinearLayout buttons = horizontal();
        buttons.addView(networkToggle("eD2k", "ed2k"), new LinearLayout.LayoutParams(0, dp(48), 1));
        buttons.addView(networkToggle("Kad", "kad"), new LinearLayout.LayoutParams(0, dp(48), 1));
        networkActions.addView(buttons);
        LinearLayout tools = horizontal();
        Button updateServers = button("Update server list", false);
        updateServers.setOnClickListener(view -> showUpdateUrlDialog("servers", "servers_update", "Update server list"));
        Button updateKad = button("Update Kad nodes", false);
        updateKad.setOnClickListener(view -> showUpdateUrlDialog("kad", "kad/update", "Update Kad nodes"));
        tools.addView(updateServers, new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(updateKad, new LinearLayout.LayoutParams(0, dp(46), 1));
        networkActions.addView(tools);
        LinearLayout diagnostics = horizontal();
        Button amuleLog = button("aMule log", false);
        amuleLog.setOnClickListener(view -> showNetworkLog("logs/amule", "aMule log", true));
        Button serverInfo = button("Server info log", false);
        serverInfo.setOnClickListener(view -> showNetworkLog("logs/server_info", "Server info log", false));
        Button kadInfo = button("Kad details", false);
        kadInfo.setOnClickListener(view -> showKadDetails());
        diagnostics.addView(amuleLog, new LinearLayout.LayoutParams(0, dp(46), 1));
        diagnostics.addView(serverInfo, new LinearLayout.LayoutParams(0, dp(46), 1));
        diagnostics.addView(kadInfo, new LinearLayout.LayoutParams(0, dp(46), 1));
        networkActions.addView(diagnostics);
        pageContent.addView(networkActions, marginParams(0, 0, 0, 10));
        addNetworkCard("eD2k", "ed2k");
        addNetworkCard("Kad", "kad");
        LinearLayout serverHeader = horizontal();
        TextView serverHeading = new TextView(this);
        serverHeading.setText(tr("Servers") + "  ·  " + servers.length() + tr(" of ") + serversTotal);
        serverHeading.setTextColor(INK);
        serverHeading.setTextSize(18);
        serverHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        serverHeader.addView(serverHeading, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button addServer = button("Add server", false);
        addServer.setOnClickListener(view -> showAddServerDialog());
        serverHeader.addView(addServer);
        pageContent.addView(serverHeader);
        EditText filterServers = new EditText(this); filterServers.setSingleLine(true); filterServers.setHint(tr("Filter by server name or address")); filterServers.setText(serverFilter);
        filterServers.addTextChangedListener(new TextWatcher() { @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) { serverFilter = s.toString(); }
            @Override public void afterTextChanged(Editable e) { } });
        pageContent.addView(filterServers);
        Button applyServerFilter = button("Apply filter", false); applyServerFilter.setOnClickListener(v -> renderCurrentPage()); pageContent.addView(applyServerFilter);
        String[] serverSortOptions = {"Name", "Users", "Files", "Ping"};
        Spinner serverOrder = translatedSpinner(serverSortOptions, java.util.Arrays.asList(serverSortOptions).indexOf(serverSort));
        serverOrder.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { String next = serverSortOptions[pos]; if (!serverSort.equals(next)) { serverSort = next; servers = new JSONArray(); refreshSectionData(); } }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        }); pageContent.addView(serverOrder, marginParams(0, 0, 0, 6));
        for (int i = 0; i < servers.length(); i++) {
            JSONObject server = servers.optJSONObject(i);
            if (server == null) continue;
            String query = serverFilter.trim().toLowerCase(java.util.Locale.ROOT);
            String haystack = (displayValue(server, "name", "") + " " + displayValue(server, "address", "")).toLowerCase(java.util.Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            LinearLayout item = card();
            TextView serverName = bodyText(displayValue(server, "name", "Unnamed server"));
            serverName.setTextColor(INK); serverName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.addView(serverName);
            item.addView(bodyText(displayValue(server, "address", "Address unavailable")
                    + "  ·  " + server.optInt("user_count") + " " + tr("users") + "  ·  " + server.optLong("file_count") + " " + tr("files")
                    + "  ·  " + tr("Ping") + " " + (server.optInt("ping_ms") > 0 ? server.optInt("ping_ms") + " ms" : "—")));
            item.addView(bodyText("Priority: " + server.optString("priority", "normal") + "  ·  "
                    + (server.optBoolean("permanent") ? "Permanent" : "Temporary")));
            Button connect = button("Connect", false);
            connect.setOnClickListener(view -> mutate("POST", "servers/" + server.optInt("ecid") + "/connect", new JSONObject(), "Connecting to server"));
            LinearLayout serverActions = new LinearLayout(this);
            serverActions.setOrientation(LinearLayout.VERTICAL);
            LinearLayout primaryActions = horizontal();
            primaryActions.addView(connect, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button priority = button("Priority", false);
            priority.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Server priority"))
                    .setItems(localizedOptions(new String[] {"Low", "Normal", "High"}), (dialog, choice) -> {
                        String[] values = {"low", "normal", "high"};
                        mutate("PATCH", "servers/" + server.optInt("ecid"), json("priority", values[choice]), "Server priority updated");
                    }).show());
            primaryActions.addView(priority, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button permanent = button(server.optBoolean("permanent") ? "Make temporary" : "Make permanent", false);
            permanent.setOnClickListener(view -> mutate("PATCH", "servers/" + server.optInt("ecid"),
                    json("permanent", !server.optBoolean("permanent")), "Server setting updated"));
            LinearLayout secondaryActions = horizontal();
            secondaryActions.addView(permanent, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button remove = button("Remove", false);
            remove.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Remove server?"))
                    .setMessage(server.optString("name", "Server"))
                    .setNegativeButton(tr("Keep"), null).setPositiveButton(tr("Remove"), (dialog, which) ->
                            mutate("DELETE", "servers/" + server.optInt("ecid"), null, "Server removed")).show());
            secondaryActions.addView(remove, new LinearLayout.LayoutParams(0, dp(48), 1));
            serverActions.addView(primaryActions);
            serverActions.addView(secondaryActions);
            item.addView(serverActions, marginParams(0, 6, 0, 0));
            pageContent.addView(item, marginParams(0, 0, 0, 8));
        }
        if (servers.length() < serversTotal) { Button more = button("Load more servers", false); more.setOnClickListener(v -> loadMoreServers()); pageContent.addView(more); }
    }

    private Button networkToggle(String label, String network) {
        JSONObject data = status.optJSONObject(network);
        String state = data == null ? "unknown" : data.optString("state", "unknown");
        boolean up = state.equals("connected") || state.equals("connecting");
        Button action = button(label + ": " + tr(state) + "  ·  " + tr(up ? "Disconnect" : "Connect"), false);
        action.setOnClickListener(view -> mutate("POST", "networks/" + (up ? "disconnect" : "connect"),
                json("network", network), label + " request sent"));
        return action;
    }

    private void mutate(String method, String path, JSONObject body, String success) {
        new Thread(() -> {
            try {
                if (method.equals("PATCH")) api.patch(path, body == null ? new JSONObject() : body);
                else if (method.equals("DELETE")) api.delete(path);
                else api.post(path, body == null ? new JSONObject() : body);
                handler.post(() -> {
                    nativeToast( success, Toast.LENGTH_SHORT).show();
                    refreshSnapshot();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Action failed: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-action").start();
    }

    private static JSONObject json(String key, Object value) {
        JSONObject object = new JSONObject();
        try { object.put(key, value); } catch (JSONException ignored) { }
        return object;
    }

    private void renderSearch() {
        LinearLayout form = card();
        TextView label = new TextView(this);
        label.setText(tr("Search for files"));
        label.setTextColor(INK);
        label.setTextSize(18);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        form.addView(label);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint(tr("Enter file name or keywords"));
        searchInput.setText(searchQuery);
        searchInput.setTextSize(16);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { searchQuery = s.toString(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        form.addView(searchInput, marginParams(0, 8, 0, 4));

        LinearLayout controls = horizontal();
        String[] searchLabels = status.optBoolean("search_all_supported")
                ? new String[] {"Global", "Kad", "Local", "All networks"}
                : new String[] {"Global", "Kad", "Local"};
        String[] searchKinds = status.optBoolean("search_all_supported")
                ? new String[] {"global", "kad", "local", "all"}
                : new String[] {"global", "kad", "local"};
        searchType = spinner(searchLabels, "Global");
        controls.addView(searchType, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button go = button("Search", true);
        controls.addView(go, new LinearLayout.LayoutParams(0, dp(48), 1));
        go.setOnClickListener(view -> startSearch(searchQuery,
                searchKinds[Math.min(searchKinds.length - 1, Math.max(0, searchType.getSelectedItemPosition()))]));
        form.addView(controls);
        TextView filterHeading = bodyText("Optional filters");
        filterHeading.setPadding(0, dp(10), 0, dp(2));
        form.addView(filterHeading);
        String[] typeLabels = {"Any file type", "Audio", "Video", "Picture", "Text", "Program", "Archive", "Disc image"};
        String[] typeValues = {"", "audio", "video", "picture", "text", "program", "archive", "disc_image"};
        searchFileTypeSpinner = spinner(typeLabels, "Any file type");
        int typeIndex = 0;
        for (int i = 0; i < typeValues.length; i++) if (typeValues[i].equals(searchFileType)) typeIndex = i;
        searchFileTypeSpinner.setSelection(typeIndex);
        searchFileTypeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { searchFileType = typeValues[position]; }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        form.addView(searchFileTypeSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout filterRow = horizontal();
        filterRow.addView(searchField("Extension", searchExtension, value -> searchExtension = value), new LinearLayout.LayoutParams(0, dp(56), 1));
        filterRow.addView(searchField("Min sources", searchMinSources, value -> searchMinSources = value, true), new LinearLayout.LayoutParams(0, dp(56), 1));
        form.addView(filterRow);
        LinearLayout sizeRow = horizontal();
        sizeRow.addView(searchField("Min size (MiB)", searchMinSize, value -> searchMinSize = value, true), new LinearLayout.LayoutParams(0, dp(56), 1));
        sizeRow.addView(searchField("Max size (MiB)", searchMaxSize, value -> searchMaxSize = value, true), new LinearLayout.LayoutParams(0, dp(56), 1));
        form.addView(sizeRow);
        pageContent.addView(form, marginParams(0, 0, 0, 12));

        if (openSearches.length() > 0) {
            LinearLayout tabsCard = card();
            LinearLayout tabs = horizontal();
            for (int i = 0; i < openSearches.length(); i++) {
                JSONObject item = openSearches.optJSONObject(i);
                if (item == null) continue;
                int id = item.optInt("search_id");
                String query = item.optString("query", "Search " + id);
                String tabLabel = query + "  ·  " + item.optInt("result_count");
                LinearLayout tab = horizontal();
                Button select = button(tabLabel, id == activeSearchId);
                select.setMaxWidth(dp(220));
                select.setOnClickListener(view -> selectSearch(id));
                tab.addView(select);
                Button close = button("×", false);
                close.setContentDescription("Close search " + query);
                close.setOnClickListener(view -> closeSearch(id));
                tab.addView(close);
                tabs.addView(tab, marginParams(0, 0, dp(6), 0));
            }
            HorizontalScrollView strip = new HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false);
            strip.addView(tabs);
            tabsCard.addView(strip);
            pageContent.addView(tabsCard, marginParams(0, 0, 0, 10));
        }

        if (activeSearchId == 0) {
            LinearLayout empty = card();
            empty.addView(bodyText("Enter a query and choose Search. Results will appear here."));
            pageContent.addView(empty);
            return;
        }

        LinearLayout summary = card();
        summary.addView(bodyText(activeSearchQuery + "  ·  " + searchState + "  ·  " + searchResults.length() + " results"));
        LinearLayout actions = horizontal();
        Button update = button("Refresh", false);
        update.setOnClickListener(view -> refreshSearch());
        actions.addView(update, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button stop = button("Stop search", false);
        stop.setEnabled(searchState.equals("running"));
        stop.setOnClickListener(view -> new Thread(() -> {
            try {
                api.post("search/" + activeSearchId + "/stop", new JSONObject());
                handler.post(() -> nativeToast( "Search stopped", Toast.LENGTH_SHORT).show());
                refreshSearch();
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not stop search: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-search-stop").start());
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(44), 1));
        if (searchKadActive && searchState.equals("running")) {
            Button more = button("More Kad results", false);
            more.setOnClickListener(view -> new Thread(() -> {
                try {
                    api.post("search/" + activeSearchId + "/more", new JSONObject());
                    handler.post(() -> nativeToast( "Kad search widened", Toast.LENGTH_SHORT).show());
                } catch (Exception failure) {
                    handler.post(() -> nativeToast( "Could not extend Kad search: " + failure.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "aMule-native-search-more").start());
            actions.addView(more, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        summary.addView(actions);
        pageContent.addView(summary, marginParams(0, 0, 0, 10));

        addSearchResultTools();
        ArrayList<JSONObject> visibleResults = new ArrayList<>();
        String needle = searchResultFilter.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < searchResults.length(); i++) {
            JSONObject item = searchResults.optJSONObject(i);
            if (item == null) continue;
            JSONObject media = item.optJSONObject("media");
            String searchable = item.optString("name", "") + " " + (media == null ? "" :
                    media.optString("artist", "") + " " + media.optString("album", "") + " " + media.optString("title", ""));
            if (!needle.isEmpty() && !searchable.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            boolean have = item.optBoolean("already_downloaded");
            if (searchResultHave.equals("Have") && !have) continue;
            if (searchResultHave.equals("Not in downloads") && have) continue;
            visibleResults.add(item);
        }
        Comparator<JSONObject> comparator;
        if (searchResultSort.equals("Name")) comparator = Comparator.comparing(item -> item.optString("name", "").toLowerCase(java.util.Locale.ROOT));
        else if (searchResultSort.equals("Size")) comparator = Comparator.comparingLong(item -> item.optLong("size_bytes"));
        else if (searchResultSort.equals("Rating")) comparator = Comparator.comparingInt(item -> item.optInt("rating"));
        else if (searchResultSort.equals("Type")) comparator = Comparator.comparing(item -> item.optString("file_type", ""));
        else if (searchResultSort.equals("Status")) comparator = Comparator.comparing(item -> item.optString("status", ""));
        else comparator = Comparator.<JSONObject>comparingInt(item -> {
            JSONObject sources = item.optJSONObject("sources");
            return sources == null ? item.optInt("source_count") : sources.optInt("total");
        }).reversed();
        visibleResults.sort(comparator);
        Set<String> visibleHashes = new HashSet<>();
        for (JSONObject item : visibleResults) visibleHashes.add(item.optString("hash", ""));
        // Keep bulk actions scoped to rows that remain visible after filtering.
        selectedSearchHashes.retainAll(visibleHashes);
        saveActiveSearchUiState();

        if (visibleResults.isEmpty()) {
            LinearLayout none = card();
            none.addView(bodyText(searchResults.length() == 0 ? "No results yet. Search results can take a little while to arrive." : "No results match these filters."));
            pageContent.addView(none);
        }
        for (JSONObject result : visibleResults) {
            LinearLayout row = card();
            LinearLayout titleRow = horizontal();
            TextView name = new TextView(this);
            name.setText(result.optString("name", "Unknown file"));
            name.setTextColor(INK);
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            titleRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            String hash = result.optString("hash", "");
            CheckBox select = new CheckBox(this);
            select.setContentDescription("Select " + result.optString("name", "file"));
            select.setChecked(selectedSearchHashes.contains(hash));
            select.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedSearchHashes.add(hash); else selectedSearchHashes.remove(hash);
                saveActiveSearchUiState();
                renderCurrentPage();
            });
            titleRow.addView(select);
            row.addView(titleRow);
            JSONObject sources = result.optJSONObject("sources");
            int sourceCount = sources == null ? result.optInt("source_count", 0) : sources.optInt("total", 0);
            ArrayList<String> details = new ArrayList<>();
            if (!hiddenSearchFields.contains("size")) details.add(bytes(result.optLong("size_bytes")));
            if (!hiddenSearchFields.contains("sources")) {
                int completeSources = sources == null ? 0 : sources.optInt("complete", 0);
                details.add(completeSources > 0 ? sourceCount + " (" + completeSources + ") sources" : sourceCount + " sources");
            }
            int rating = result.optInt("rating");
            if (!hiddenSearchFields.contains("rating")) details.add(rating > 0 ? rating + "/5 rating" : "Unrated");
            String fileType = result.optString("file_type", "");
            if (!hiddenSearchFields.contains("type") && !fileType.isEmpty()) details.add(prettyPreferenceKey(fileType));
            String resultStatus = result.optString("status", "");
            if (!hiddenSearchFields.contains("status") && !resultStatus.isEmpty()) details.add(resultStatus);
            String directory = result.optString("directory", "");
            if (!hiddenSearchFields.contains("directory") && !directory.isEmpty()) details.add(directory);
            if (!details.isEmpty()) row.addView(bodyText(String.join("  ·  ", details)));
            JSONObject media = result.optJSONObject("media");
            if (media != null) {
                ArrayList<String> mediaDetails = new ArrayList<>();
                String artist = media.optString("artist", "");
                String album = media.optString("album", "");
                String title = media.optString("title", "");
                if (!hiddenSearchFields.contains("artist") && !artist.isEmpty()) mediaDetails.add(artist);
                if (!hiddenSearchFields.contains("album") && !album.isEmpty()) mediaDetails.add(album);
                if (!hiddenSearchFields.contains("title") && !title.isEmpty()) mediaDetails.add(title);
                long duration = media.optLong("duration_seconds");
                if (!hiddenSearchFields.contains("length") && duration > 0) mediaDetails.add(String.format(java.util.Locale.UK, "%d:%02d", duration / 60, duration % 60));
                int bitrate = media.optInt("bitrate_kilobits_per_second");
                if (!hiddenSearchFields.contains("bitrate") && bitrate > 0) mediaDetails.add(bitrate + " kb/s");
                String codec = media.optString("codec", "");
                if (!hiddenSearchFields.contains("codec") && !codec.isEmpty()) mediaDetails.add(codec);
                if (!mediaDetails.isEmpty()) row.addView(bodyText(String.join("  ·  ", mediaDetails)));
            }
            boolean alreadyQueued = result.optBoolean("already_downloaded");
            Button add = button(alreadyQueued ? "Already in downloads" : "Add to downloads", !alreadyQueued);
            add.setEnabled(!alreadyQueued);
            add.setOnClickListener(view -> addSearchResult(result));
            LinearLayout resultActions = horizontal();
            resultActions.addView(add, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button comments = button("Comments", false);
            comments.setOnClickListener(view -> showSearchComments(result));
            resultActions.addView(comments, new LinearLayout.LayoutParams(0, dp(44), 1));
            row.addView(resultActions, marginParams(0, 8, 0, 0));
            pageContent.addView(row, marginParams(0, 0, 0, 8));
        }
    }

    private void addSearchResultTools() {
        LinearLayout tools = card();
        LinearLayout filterRow = horizontal();
        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint(tr("Filter result names"));
        filter.setText(searchResultFilter);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { searchResultFilter = s.toString(); saveActiveSearchUiState(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        filterRow.addView(filter, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button apply = button("Apply", false);
        apply.setOnClickListener(view -> renderCurrentPage());
        filterRow.addView(apply);
        tools.addView(filterRow);
        LinearLayout optionRow = horizontal();
        Spinner sort = spinner(new String[] {"Sources", "Name", "Size", "Rating", "Type", "Status"}, searchResultSort);
        sort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String next = (String) parent.getItemAtPosition(position);
                if (!searchResultSort.equals(next)) { searchResultSort = next; saveActiveSearchUiState(); renderCurrentPage(); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        optionRow.addView(sort, new LinearLayout.LayoutParams(0, dp(48), 1));
        Spinner have = spinner(new String[] {"All", "Not in downloads", "Have"}, searchResultHave);
        have.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String next = (String) parent.getItemAtPosition(position);
                if (!searchResultHave.equals(next)) { searchResultHave = next; saveActiveSearchUiState(); renderCurrentPage(); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        optionRow.addView(have, new LinearLayout.LayoutParams(0, dp(48), 1));
        tools.addView(optionRow);
        Button fields = button("Choose result details", false);
        fields.setOnClickListener(view -> showSearchResultFields());
        tools.addView(fields, marginParams(0, 4, 0, 0));
        Button addSelected = button("Add selected to downloads  ·  " + selectedSearchHashes.size(), false);
        addSelected.setEnabled(!selectedSearchHashes.isEmpty());
        addSelected.setOnClickListener(view -> addSelectedSearchResults());
        tools.addView(addSelected, marginParams(0, 4, 0, 0));
        Button related = button("Search for related files  ·  " + selectedSearchHashes.size(), false);
        related.setEnabled(!selectedSearchHashes.isEmpty());
        related.setOnClickListener(view -> startRelatedSearch());
        tools.addView(related, marginParams(0, 4, 0, 0));
        pageContent.addView(tools, marginParams(0, 0, 0, 10));
    }

    private void showSearchResultFields() {
        String[] labels = {"Size", "Sources", "Rating", "File type", "Status", "Directory", "Duration", "Bitrate", "Codec", "Artist", "Album", "Media title"};
        String[] keys = {"size", "sources", "rating", "type", "status", "directory", "length", "bitrate", "codec", "artist", "album", "title"};
        Set<String> workingHidden = new HashSet<>(hiddenSearchFields);
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = !workingHidden.contains(keys[i]);
        new AlertDialog.Builder(this).setTitle(tr("Result details"))
                .setMultiChoiceItems(localizedOptions(labels), checked, (dialog, which, isChecked) -> {
                    if (isChecked) workingHidden.remove(keys[which]); else workingHidden.add(keys[which]);
                })
                .setPositiveButton(tr("Done"), (dialog, which) -> {
                    hiddenSearchFields.clear();
                    hiddenSearchFields.addAll(workingHidden);
                    saveActiveSearchUiState();
                    renderCurrentPage();
                })
                .setNegativeButton(tr("Cancel"), (dialog, which) -> renderCurrentPage())
                .show();
    }

    private Spinner spinner(String[] options, String selected) {
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return translateSpinnerRow(super.getView(position, convertView, parent), position);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return translateSpinnerRow(super.getDropDownView(position, convertView, parent), position);
            }
            private View translateSpinnerRow(View row, int position) {
                if (row instanceof TextView) ((TextView) row).setText(tr(getItem(position)));
                return row;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        for (int i = 0; i < options.length; i++) if (options[i].equals(selected)) picker.setSelection(i);
        return picker;
    }

    private String[] localizedOptions(String[] options) {
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) labels[i] = tr(options[i]);
        return labels;
    }

    private Spinner translatedSpinner(String[] options, int selectedPosition) {
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) labels[i] = tr(options[i]);
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        if (selectedPosition >= 0 && selectedPosition < labels.length) picker.setSelection(selectedPosition);
        return picker;
    }

    private void addSelectedSearchResults() {
        ArrayList<String> hashes = new ArrayList<>(selectedSearchHashes);
        if (hashes.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONArray categories = api.get("categories").optJSONArray("categories");
                if (categories == null) categories = new JSONArray();
                JSONArray rows = categories;
                handler.post(() -> {
                    String[] labels = new String[Math.max(1, rows.length())];
                    int[] indexes = new int[labels.length];
                    if (rows.length() == 0) labels[0] = "Default";
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject item = rows.optJSONObject(i);
                        labels[i] = item == null ? "Default" : item.optString("name", "Default");
                        indexes[i] = item == null ? 0 : item.optInt("index");
                    }
                    new AlertDialog.Builder(this).setTitle(tr("Add " + hashes.size() + " selected files"))
                            .setItems(labels, (dialog, choice) -> new Thread(() -> {
                                int added = 0;
                                try {
                                    for (String hash : hashes) {
                                        api.post("search/results/" + hash + "/download", json("category_index", indexes[choice]));
                                        added++;
                                    }
                                    int count = added;
                                    handler.post(() -> {
                                        selectedSearchHashes.clear();
                                        saveActiveSearchUiState();
                                        nativeToast( count + " files added to downloads", Toast.LENGTH_SHORT).show();
                                        renderCurrentPage();
                                        refreshSnapshot();
                                    });
                                } catch (Exception failure) {
                                    int count = added;
                                    handler.post(() -> nativeToast( count + " added; remaining files failed: " + failure.getMessage(), Toast.LENGTH_LONG).show());
                                }
                            }, "aMule-native-search-bulk-add").start()).setNegativeButton(tr("Cancel"), null).show();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not load download categories: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-search-bulk-categories").start();
    }

    private void startRelatedSearch() {
        if (selectedSearchHashes.isEmpty()) return;
        ArrayList<String> hashes = new ArrayList<>(selectedSearchHashes);
        new Thread(() -> {
            try {
                JSONObject ed2k = status.optJSONObject("ed2k");
                String activeAddress = ed2k == null ? "" : ipv4Address(ed2k.optString("server_ip", ""));
                int activePort = ed2k == null ? 0 : ed2k.optInt("server_port");
                JSONArray list = api.get("servers").optJSONArray("servers");
                if (list != null && !activeAddress.isEmpty()) for (int i = 0; i < list.length(); i++) {
                    JSONObject server = list.optJSONObject(i);
                    if (server == null || !activeAddress.equals(ipv4Address(server.optString("address", "")))
                            || activePort != server.optInt("port")) continue;
                    JSONObject flags = server.optJSONObject("tcp_flags");
                    if (flags != null && !flags.optBoolean("related_search")) {
                        handler.post(() -> nativeToast( "The connected server does not support related-file searches", Toast.LENGTH_LONG).show());
                        return;
                    }
                    break;
                }
            } catch (Exception ignored) { /* If capabilities are unavailable, let the server answer. */ }
            StringBuilder query = new StringBuilder("related::");
            for (String hash : hashes) {
                if (query.length() > "related::".length()) query.append("::");
                query.append(hash.toUpperCase(java.util.Locale.ROOT));
            }
            handler.post(() -> startSearch(query.toString(), "local"));
        }, "aMule-native-related-search-check").start();
    }

    private static String ipv4Address(String text) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+").matcher(text == null ? "" : text);
        return match.find() ? match.group() : "";
    }

    private void showSearchComments(JSONObject result) {
        String hash = result.optString("hash", "");
        if (hash.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject response = api.get("search/results/" + hash + "/comments");
                JSONArray comments = response.optJSONArray("comments");
                StringBuilder text = new StringBuilder();
                if (comments != null) for (int i = 0; i < comments.length(); i++) {
                    JSONObject item = comments.optJSONObject(i);
                    if (item == null) continue;
                    if (text.length() > 0) text.append("\n\n");
                    text.append(item.optString("username", "Unknown user"));
                    int rating = item.optInt("rating", -1);
                    if (rating >= 0) text.append("  ·  Rating ").append(rating).append("/5");
                    String filename = item.optString("filename", "");
                    if (!filename.isEmpty()) text.append("\n").append(filename);
                    String comment = item.optString("comment", "");
                    if (!comment.isEmpty()) text.append("\n").append(comment);
                }
                String content = text.length() == 0 ? "No comments are available." : text.toString();
                boolean lookupRunning = response.optBoolean("kad_comment_lookup_running");
                handler.post(() -> {
                    TextView message = bodyText(content);
                    ScrollView scroll = new ScrollView(this);
                    scroll.setPadding(dp(20), dp(8), dp(20), dp(8));
                    scroll.addView(message);
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(tr("Comments  ·  " + result.optString("name", "File")))
                            .setView(scroll).setNegativeButton(tr("Close"), null)
                            .setPositiveButton(tr("Refresh"), (d, which) -> showSearchComments(result))
                            .setNeutralButton(lookupRunning ? "Searching Kad…" : "Get Kad comments", null).create();
                    dialog.setOnShowListener(ignored -> {
                        Button getKad = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                        getKad.setEnabled(!lookupRunning);
                        getKad.setOnClickListener(view -> new Thread(() -> {
                            try {
                                api.post("search/results/" + hash + "/comments", new JSONObject());
                                handler.post(() -> {
                                    dialog.dismiss();
                                    nativeToast( "Kad comment lookup started", Toast.LENGTH_SHORT).show();
                                    handler.postDelayed(() -> showSearchComments(result), 2000);
                                });
                            } catch (Exception failure) {
                                handler.post(() -> nativeToast( "Could not request Kad comments: " + failure.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        }, "aMule-native-search-comments-kad").start());
                    });
                    dialog.show();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not load comments: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-search-comments").start();
    }

    private void renderShared() {
        LinearLayout toolbar = card();
        TextView title = new TextView(this);
        title.setText(tr("Shared files  ·  " + filteredSharedFiles().length() + " / " + sharedFiles.length()));
        title.setTextColor(INK); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(title);
        EditText filter = new EditText(this); filter.setSingleLine(true); filter.setHint(tr("Filter shared files")); filter.setText(sharedFilter);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { sharedFilter = s.toString(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        toolbar.addView(filter);
        Button applySharedFilter = button("Apply filter", false);
        applySharedFilter.setOnClickListener(v -> renderCurrentPage());
        toolbar.addView(applySharedFilter);
        Spinner uploadFilter = spinner(new String[] {"All", "Uploading", "Idle"}, sharedUploadFilter);
        uploadFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { String next = new String[] {"All", "Uploading", "Idle"}[pos]; if (!sharedUploadFilter.equals(next)) { sharedUploadFilter = next; renderCurrentPage(); } }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        toolbar.addView(uploadFilter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        Spinner sharedOrder = spinner(new String[] {"Name", "Size", "Upload speed", "Uploaded"}, sharedSort);
        sharedOrder.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { String next = new String[] {"Name", "Size", "Upload speed", "Uploaded"}[pos]; if (!sharedSort.equals(next)) { sharedSort = next; renderCurrentPage(); } }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        }); toolbar.addView(sharedOrder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        Button reload = button("Reload shared files", true);
        reload.setOnClickListener(view -> mutate("POST", "shared_reload", new JSONObject(), "Shared file scan started"));
        toolbar.addView(reload, marginParams(0, 8, 0, 0));
        Button refreshMedia = button("Refresh all media metadata", false);
        refreshMedia.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Refresh media metadata?"))
                .setMessage(tr("aMule will scan shared audio and video files in the background."))
                .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Refresh"), (d, w) -> mutate("POST", "shared/media/refresh", new JSONObject(), "Media metadata refresh queued")).show());
        toolbar.addView(refreshMedia, marginParams(0, 6, 0, 0));
        Button bulkPriority = button("Set selected priority", false);
        bulkPriority.setOnClickListener(view -> showBulkSharedPriority());
        toolbar.addView(bulkPriority, marginParams(0, 6, 0, 0));
        pageContent.addView(toolbar, marginParams(0, 0, 0, 10));
        JSONArray visibleFiles = filteredSharedFiles();
        if (visibleFiles.length() == 0) {
            LinearLayout empty = card(); empty.addView(bodyText("No shared files were returned by aMule.")); pageContent.addView(empty); return;
        }
        for (int i = 0; i < visibleFiles.length(); i++) {
            JSONObject file = visibleFiles.optJSONObject(i); if (file == null) continue;
            LinearLayout item = card();
            CheckBox selected = new CheckBox(this); selected.setText(tr("Select")); selected.setChecked(selectedSharedHashes.contains(file.optString("hash")));
            selected.setOnCheckedChangeListener((button, checked) -> { if (checked) selectedSharedHashes.add(file.optString("hash")); else selectedSharedHashes.remove(file.optString("hash")); });
            item.addView(selected);
            TextView name = bodyText(displayValue(file, "name", "Unknown file"));
            name.setTextColor(INK); name.setTextSize(16); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); name.setOnClickListener(v -> showSharedDetails(file)); item.addView(name);
            item.addView(bodyText(bytes(file.optLong("size_bytes")) + "  ·  " + file.optInt("uploading_client_count") + " " + tr("uploading") + "  ·  " + speed(file.optLong("upload_speed_bytes_per_second"))));
            item.addView(bodyText("Uploaded this session " + bytes(file.optLong("uploaded_bytes_session")) + "  ·  lifetime " + bytes(file.optLong("uploaded_bytes_total"))));
            LinearLayout actions = horizontal();
            Button priority = button("Priority: " + file.optString("priority", "auto"), false);
            priority.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Upload priority"))
                    .setItems(localizedOptions(new String[] {"Auto", "Very low", "Low", "Normal", "High", "Release"}), (dialog, choice) -> {
                        String[] values = {"auto", "very_low", "low", "normal", "high", "release"};
                        mutate("PATCH", "shared/" + file.optString("hash"), json("priority", values[choice]), "Priority updated");
                    }).show());
            actions.addView(priority, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button verify = button("Verify", false);
            verify.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Verify shared file?")).setMessage(tr("aMule will re-hash this file in the background."))
                    .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Verify"), (d, w) -> mutate("POST", "shared/" + file.optString("hash") + "/verify", new JSONObject(), "Verification started")).show());
            actions.addView(verify, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button details = button("Details", false); details.setOnClickListener(view -> showSharedDetails(file));
            actions.addView(details, new LinearLayout.LayoutParams(0, dp(44), 1));
            item.addView(actions, marginParams(0, 6, 0, 0));
            pageContent.addView(item, marginParams(0, 0, 0, 8));
        }
    }

    private JSONArray filteredSharedFiles() {
        JSONArray result = new JSONArray(); String q = sharedFilter.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < sharedFiles.length(); i++) { JSONObject f = sharedFiles.optJSONObject(i); if (f == null) continue;
            boolean uploading = f.optInt("uploading_client_count") > 0;
            if (sharedUploadFilter.equals("Uploading") && !uploading || sharedUploadFilter.equals("Idle") && uploading) continue;
            if (!q.isEmpty() && !f.optString("name").toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            result.put(f);
        }
        ArrayList<JSONObject> sorted = new ArrayList<>(); for (int i = 0; i < result.length(); i++) sorted.add(result.optJSONObject(i));
        Comparator<JSONObject> comparator;
        if (sharedSort.equals("Size")) comparator = Comparator.comparingLong(f -> f.optLong("size_bytes"));
        else if (sharedSort.equals("Upload speed")) comparator = Comparator.comparingLong(f -> f.optLong("upload_speed_bytes_per_second"));
        else if (sharedSort.equals("Uploaded")) comparator = Comparator.comparingLong(f -> f.optLong("uploaded_bytes_total"));
        else comparator = Comparator.comparing(f -> f.optString("name").toLowerCase(java.util.Locale.ROOT));
        sorted.sort(comparator); if (!sharedSort.equals("Name")) java.util.Collections.reverse(sorted);
        JSONArray ordered = new JSONArray(); for (JSONObject file : sorted) ordered.put(file); return ordered;
    }

    private void showBulkSharedPriority() {
        if (selectedSharedHashes.isEmpty()) { nativeToast( "Select one or more shared files first", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle(tr("Upload priority"))
                .setItems(localizedOptions(new String[] {"Auto", "Very low", "Low", "Normal", "High", "Release"}), (d, which) -> {
                    String[] values = {"auto", "very_low", "low", "normal", "high", "release"}; JSONArray hashes = new JSONArray();
                    for (String hash : selectedSharedHashes) hashes.put(hash);
                    try { mutate("PATCH", "shared", new JSONObject().put("hashes", hashes).put("priority", values[which]), "Priority update submitted"); selectedSharedHashes.clear(); }
                    catch (JSONException ignored) { }
                }).show();
    }

    private void showSharedDetails(JSONObject file) {
        String hash = file.optString("hash");
        new Thread(() -> { try {
            JSONObject detail = api.get("shared/" + hash);
            JSONArray clients = api.get("shared/" + hash + "/clients").optJSONArray("clients");
            StringBuilder text = new StringBuilder();
            text.append("Size: ").append(bytes(detail.optLong("size_bytes"))).append("\nDirectory: ").append(detail.optString("directory", "Unknown"))
                    .append("\nPriority: ").append(detail.optString("priority")).append("\nComplete sources: ").append(detail.optJSONObject("sources") == null ? 0 : detail.optJSONObject("sources").optInt("complete"))
                    .append("\nUploaded total: ").append(bytes(detail.optLong("uploaded_bytes_total"))).append("\nRequests: ").append(detail.optInt("request_count_total"))
                    .append("\nYour rating: ").append(detail.optInt("my_rating")).append(" / 5\nYour comment: ").append(displayValue(detail, "my_comment", "None"));
            JSONObject media = detail.optJSONObject("media"); if (media != null) text.append("\nMedia: ").append(media.optString("artist", "")).append(" ").append(media.optString("title", "")).append("  ").append(media.optString("codec", ""));
            text.append("\n\nUploading clients: ").append(clients == null ? 0 : clients.length());
            if (clients != null) for (int i = 0; i < clients.length(); i++) {
                JSONObject c = clients.optJSONObject(i);
                if (c != null) text.append("\n• ").append(displayValue(c, "name", "Unknown client"))
                        .append("  ").append(displayValue(c, "ip", "Address unavailable"));
            }
            handler.post(() -> new AlertDialog.Builder(this).setTitle(displayValue(detail, "name", "Shared file")).setMessage(text.toString())
                    .setNeutralButton(tr("Edit comment/rating"), (d, w) -> showSharedCommentDialog(detail))
                    .setPositiveButton(tr("Close"), null).show());
        } catch (Exception e) { handler.post(() -> nativeToast( "Could not load shared-file details: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-shared-details").start();
    }

    private void showSharedCommentDialog(JSONObject file) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText comment = new EditText(this); comment.setHint(tr("Your comment (up to 50 characters)")); comment.setText(displayValue(file, "my_comment", "")); comment.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(50)}); form.addView(comment);
        String[] ratings = {"No rating", "1 star", "2 stars", "3 stars", "4 stars", "5 stars"};
        Spinner rating = spinner(ratings, ratings[Math.max(0, Math.min(5, file.optInt("my_rating")))]); form.addView(rating);
        new AlertDialog.Builder(this).setTitle(tr("Your comment and rating")).setView(form).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Save"), (dialog, which) -> {
                    try { JSONObject body = new JSONObject().put("my_comment", comment.getText().toString().trim()).put("my_rating", rating.getSelectedItemPosition());
                        mutate("PATCH", "shared/" + file.optString("hash"), body, "Comment and rating saved");
                    } catch (JSONException ignored) { }
                }).show();
    }

    private void showAddLinkDialog() {
        EditText input = new EditText(this);
        input.setHint(tr("ed2k://… or magnet:?…"));
        input.setMinLines(2);
        input.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this).setTitle(tr("Add download link")).setView(input)
                .setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Add"), (dialog, which) -> {
                    String link = input.getText().toString().trim();
                    if (link.isEmpty()) return;
                    try {
                        JSONArray links = new JSONArray().put(link);
                        JSONObject payload = new JSONObject().put("links", links);
                        new Thread(() -> {
                            try { api.post("downloads", payload); handler.post(() -> { nativeToast( "Link sent to aMule", Toast.LENGTH_SHORT).show(); refreshSnapshot(); }); }
                            catch (Exception failure) { handler.post(() -> nativeToast( "Could not add link: " + failure.getMessage(), Toast.LENGTH_LONG).show()); }
                        }, "aMule-native-add-link").start();
                    } catch (JSONException ignored) { }
                }).show();
    }

    private void showCategoriesDialog() {
        new Thread(() -> {
            try {
                JSONArray list = api.get("categories").optJSONArray("categories");
                if (list == null) list = new JSONArray();
                JSONArray categories = list;
                handler.post(() -> {
                    String[] labels = new String[categories.length() + 1];
                    labels[0] = "Add category…";
                    for (int i = 0; i < categories.length(); i++) {
                        JSONObject c = categories.optJSONObject(i);
                        labels[i + 1] = c == null ? "Category" : c.optString("name", "Category") + "  ·  " + c.optString("save_path", "");
                    }
                    new AlertDialog.Builder(this).setTitle(tr("Download categories"))
                            .setItems(labels, (dialog, choice) -> {
                                if (choice == 0) showCategoryForm(null);
                                else {
                                    JSONObject selected = categories.optJSONObject(choice - 1);
                                    if (selected != null && selected.optInt("index") == 0)
                                        nativeToast( "The default category cannot be edited", Toast.LENGTH_SHORT).show();
                                    else showCategoryForm(selected);
                                }
                            }).setNegativeButton(tr("Close"), null).show();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not load categories: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-categories").start();
    }

    private void showCategoryForm(JSONObject existing) {
        boolean editing = existing != null && existing.optInt("index") != 0;
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(this); name.setHint(tr("Category name")); form.addView(name);
        EditText path = new EditText(this); path.setHint(tr("Save directory")); form.addView(path);
        if (editing) { name.setText(existing.optString("name")); path.setText(existing.optString("save_path")); }
        new AlertDialog.Builder(this).setTitle(tr(editing ? "Edit category" : "Add category")).setView(form)
                .setNegativeButton(tr("Cancel"), null)
                .setNeutralButton(editing ? tr("Delete") : null, editing ? (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle(tr("Delete category?")).setNegativeButton(tr("Keep"), null)
                        .setPositiveButton(tr("Delete"), (d, w) -> mutate("DELETE", "categories/" + existing.optInt("index"), null, "Category deleted"))
                        .show() : null)
                .setPositiveButton(tr("Save"), (dialog, which) -> {
                    String categoryName = name.getText().toString().trim(); String savePath = path.getText().toString().trim();
                    if (categoryName.isEmpty() || savePath.isEmpty()) { nativeToast( "Name and save directory are required", Toast.LENGTH_SHORT).show(); return; }
                    try {
                        JSONObject body = new JSONObject().put("name", categoryName).put("save_path", savePath)
                                .put("priority", existing == null ? "auto" : existing.optString("priority", "auto"))
                                .put("color", existing == null ? "#1664c0" : existing.optString("color", "#1664c0"));
                        mutate(editing ? "PATCH" : "POST", editing ? "categories/" + existing.optInt("index") : "categories", body, "Category saved");
                    } catch (JSONException ignored) { }
                }).show();
    }

    private void renderClients() {
        LinearLayout toolbar = card();
        TextView heading = bodyText("Clients"); heading.setTextColor(INK); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); toolbar.addView(heading);
        EditText filter = new EditText(this); filter.setHint(tr("Filter by name, address or software")); filter.setSingleLine(true); filter.setText(clientsFilter);
        filter.addTextChangedListener(new TextWatcher() { @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) { clientsFilter = s.toString(); }
            @Override public void afterTextChanged(Editable e) { } }); toolbar.addView(filter);
        Button applyClientsFilter = button("Apply filter", false); applyClientsFilter.setOnClickListener(v -> renderCurrentPage()); toolbar.addView(applyClientsFilter);
        Button mode = button(clientListMode.equals("Known") ? "Show connected clients" : "Show known clients", false);
        mode.setOnClickListener(v -> { clientListMode = clientListMode.equals("Known") ? "Connected" : "Known"; clients = new JSONArray(); knownClients = new JSONArray(); refreshSectionData(); });
        toolbar.addView(mode);
        String[] clientSortOptions = clientListMode.equals("Known")
                ? new String[] {"Last seen", "Name", "Software", "Downloaded", "Uploaded"}
                : new String[] {"Name", "Software", "Downloaded", "Uploaded", "Download speed", "Upload speed"};
        int selectedClientSort = java.util.Arrays.asList(clientSortOptions).indexOf(clientsSort);
        Spinner sort = translatedSpinner(clientSortOptions, selectedClientSort < 0 ? 0 : selectedClientSort);
        sort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { String next = clientSortOptions[pos]; if (!clientsSort.equals(next)) { clientsSort = next; if (clientListMode.equals("Known")) knownClients = new JSONArray(); else clients = new JSONArray(); refreshSectionData(); } }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        }); toolbar.addView(sort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        pageContent.addView(toolbar, marginParams(0, 0, 0, 10));
        if (clientListMode.equals("Known")) { renderKnownClients(); return; }
        LinearLayout summary = card();
        summary.addView(bodyText("Connected clients  ·  " + clients.length() + tr(" of ") + clientsTotal));
        pageContent.addView(summary, marginParams(0, 0, 0, 10));
        if (clients.length() == 0) {
            LinearLayout empty = card(); empty.addView(bodyText("No clients are connected at the moment.")); pageContent.addView(empty); return;
        }
        for (int i = 0; i < clients.length(); i++) {
            JSONObject client = clients.optJSONObject(i); if (client == null) continue;
            String query = clientsFilter.trim().toLowerCase(java.util.Locale.ROOT);
            String haystack = (displayValue(client, "name", "") + " " + displayValue(client, "ip", "") + " " + displayValue(client, "software", "") + " " + displayValue(client, "software_version", "")).toLowerCase(java.util.Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            LinearLayout row = card();
            String clientName = displayValue(client, "name", "Unnamed client #" + client.optInt("ecid"));
            TextView name = bodyText(clientName);
            name.setTextColor(INK); name.setTextSize(16); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); row.addView(name);
            String address = displayValue(client, "ip", "Address unavailable"); if (client.optInt("port") > 0) address += ":" + client.optInt("port");
            row.addView(bodyText(address));
            row.addView(bodyText("Download: " + displayValue(client, "download_state", "idle") + "  " + speed(client.optLong("download_speed_bytes_per_second"))));
            row.addView(bodyText("Upload: " + displayValue(client, "upload_state", "idle") + "  " + speed(client.optLong("upload_speed_bytes_per_second"))));
            String software = displayValue(client, "software", "Unknown software"); String version = displayValue(client, "software_version", "");
            row.addView(bodyText(software + (version.isEmpty() ? "" : " " + version) + "  ·  " + displayValue(client, "source_origin", "Unknown source")));
            row.setOnClickListener(view -> showClientDetails(client.optInt("ecid"), clientName));
            Button browse = button("View shared files", false);
            browse.setOnClickListener(view -> browsePeer("clients", client.optInt("ecid"), client.optString("name", "Peer files")));
            row.addView(browse, marginParams(0, 6, 0, 0));
            pageContent.addView(row, marginParams(0, 0, 0, 8));
        }
        if (clients.length() < clientsTotal) { Button more = button("Load more clients", false); more.setOnClickListener(v -> loadMoreClients()); pageContent.addView(more); }
    }

    private void renderKnownClients() {
        TextView count = bodyText("Known clients  ·  " + knownClients.length() + tr(" of ") + knownClientsTotal); pageContent.addView(count);
        for (int i = 0; i < knownClients.length(); i++) { JSONObject c = knownClients.optJSONObject(i); if (c == null) continue;
            String query = clientsFilter.trim().toLowerCase(java.util.Locale.ROOT);
            String haystack = (displayValue(c, "name", "") + " " + displayValue(c, "ip", "") + " " + displayValue(c, "software", "") + " " + displayValue(c, "software_version", "")).toLowerCase(java.util.Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            LinearLayout row = card(); TextView name = bodyText(displayValue(c, "name", "Unnamed client")); name.setTextColor(INK); name.setTextSize(16); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); row.addView(name);
            String address = displayValue(c, "ip", "Address unknown"); if (!c.isNull("port") && c.optInt("port") > 0) address += ":" + c.optInt("port");
            row.addView(bodyText(address + (c.optBoolean("connected") ? "  ·  connected" : "")));
            String software = displayValue(c, "software", "Unknown software"); String version = displayValue(c, "software_version", "");
            row.addView(bodyText(software + (version.isEmpty() ? "" : " " + version) + "  ·  seen " + dateTime(c.optLong("last_seen_at"))));
            row.addView(bodyText("Downloaded " + bytes(c.optLong("downloaded_bytes_total")) + "  ·  uploaded " + bytes(c.optLong("uploaded_bytes_total"))));
            pageContent.addView(row, marginParams(0, 0, 0, 8));
        }
        if (knownClients.length() < knownClientsTotal) { Button more = button("Load more clients", false); more.setOnClickListener(v -> loadMoreClients()); pageContent.addView(more); }
    }

    private void showClientDetails(int ecid, String title) {
        new Thread(() -> { try { JSONObject c = api.get("clients/" + ecid); String message = "Address: " + displayValue(c, "ip", "Unknown") + (c.optInt("port") > 0 ? ":" + c.optInt("port") : "")
                + "\nSoftware: " + displayValue(c, "software", "Unknown") + " " + displayValue(c, "software_version", "") + "\nOS: " + displayValue(c, "reported_os", "Unknown")
                + "\nSource: " + displayValue(c, "source_origin", "Unknown") + "\nIdentity: " + displayValue(c, "ident_state", "Unknown") + "\nKad port: " + c.optInt("kad_port")
                + "\nDownloaded: " + bytes(c.optLong("downloaded_bytes_total")) + "\nUploaded: " + bytes(c.optLong("uploaded_bytes_total"))
                + "\nCredit ratio: " + c.optString("credit_ratio", "Unknown") + "\nShared files browsable: " + c.optBoolean("shared_files_browsable");
                handler.post(() -> new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(tr("Close"), null).setNeutralButton(tr("Browse files"), (d, w) -> browsePeer("clients", ecid, title + " files")).show());
        } catch (Exception e) { handler.post(() -> nativeToast( "Could not load client details: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-client-details").start();
    }

    private void renderMessages() {
        LinearLayout heading = horizontal();
        TextView title = bodyText("Conversations  ·  " + chats.length());
        title.setTextColor(INK); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button add = button("Add friend", true);
        add.setOnClickListener(view -> showAddFriendDialog()); heading.addView(add);
        pageContent.addView(heading, marginParams(0, 0, 0, 8));
        if (chats.length() == 0) {
            LinearLayout empty = card(); empty.addView(bodyText("No conversations yet. Add a friend by IP address and port to start one.")); pageContent.addView(empty);
        }
        for (int i = 0; i < chats.length(); i++) { JSONObject chat = chats.optJSONObject(i); if (chat == null) continue;
            LinearLayout row = card(); TextView name = bodyText(chat.optString("name", chat.optString("address", "Conversation"))); name.setTextColor(INK); name.setTextSize(16); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); row.addView(name);
            JSONObject last = chat.optJSONObject("last_message"); row.addView(bodyText(tr(chat.optBoolean("connected") ? "Online" : "Offline") + "  ·  " + chat.optInt("message_count") + " " + tr("messages") + (last == null ? "" : "\n" + (last.optString("direction").equals("in") ? tr("Them:") : tr("You:")) + " " + last.optString("text"))));
            Button open = button("Open conversation", true); open.setOnClickListener(view -> showChatDialog(chat)); row.addView(open); pageContent.addView(row, marginParams(0, 0, 0, 8));
        }
        if (friends.length() > 0) { TextView friendsTitle = bodyText("Friend controls"); friendsTitle.setTextColor(INK); friendsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); pageContent.addView(friendsTitle); }
        for (int i = 0; i < friends.length(); i++) {
            JSONObject friend = friends.optJSONObject(i); if (friend == null) continue;
            LinearLayout row = card();
            TextView name = bodyText(friend.optString("name", "Unnamed friend"));
            name.setTextColor(INK); name.setTextSize(16); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); row.addView(name);
            row.addView(bodyText((friend.optInt("client_ecid") > 0 ? "Online" : "Offline") + "  ·  " + friend.optString("ip", "") + ":" + friend.optInt("port")));
            Button message = button("Open conversation", true);
            message.setOnClickListener(view -> showChatDialog(friend));
            row.addView(message, marginParams(0, 6, 0, 0));
            Button slot = button(friend.optBoolean("friend_slot") ? "Remove friend slot" : "Set friend slot", false);
            slot.setOnClickListener(view -> mutate("PATCH", "friends/" + friend.optInt("ecid"),
                    json("friend_slot", !friend.optBoolean("friend_slot")), "Friend slot updated"));
            row.addView(slot, marginParams(0, 6, 0, 0));
            Button browse = button("View shared files", false);
            browse.setOnClickListener(view -> browsePeer("friends", friend.optInt("ecid"), friend.optString("name", "Friend files")));
            row.addView(browse, marginParams(0, 6, 0, 0));
            Button remove = button("Remove friend", false);
            remove.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(tr("Remove friend?"))
                    .setNegativeButton(tr("Keep"), null).setPositiveButton(tr("Remove"), (dialog, which) -> mutate("DELETE", "friends/" + friend.optInt("ecid"), null, "Friend removed")).show());
            row.addView(remove, marginParams(0, 6, 0, 0)); pageContent.addView(row, marginParams(0, 0, 0, 8));
        }
    }

    private void showChatDialog(JSONObject friend) {
        String address = friend.optString("address", friend.optString("ip", "") + ":" + friend.optInt("port"));
        new Thread(() -> {
            JSONArray messages = new JSONArray();
            try { messages = api.get("chats/" + address + "/messages?tail=50").optJSONArray("messages"); }
            catch (Exception ignored) { /* A new conversation has no history yet. */ }
            JSONArray transcript = messages == null ? new JSONArray() : messages;
            handler.post(() -> {
                LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18), dp(4), dp(18), 0);
                ScrollView scroll = new ScrollView(this); TextView log = bodyText(""); StringBuilder lines = new StringBuilder();
                final long[] lastId = {0};
                for (int i = 0; i < transcript.length(); i++) {
                    JSONObject item = transcript.optJSONObject(i); if (item == null) continue;
                    appendChatLine(lines, item); lastId[0] = Math.max(lastId[0], item.optLong("id"));
                }
                log.setText(lines.length() == 0 ? "No messages yet." : lines.toString());
                scroll.addView(log); content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
                EditText draft = new EditText(this); draft.setHint(tr("Write a message")); content.addView(draft);
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle(displayValue(friend, "name", "Conversation"))
                        .setView(content).setNegativeButton(tr("Close"), null).setPositiveButton(tr("Send"), null).create();
                Runnable[] chatPoll = new Runnable[1];
                chatPoll[0] = () -> {
                    if (!dialog.isShowing()) return;
                    new Thread(() -> {
                        try {
                            JSONObject update = api.get("chats/" + address + "/messages?since_message_id=" + lastId[0]);
                            JSONArray fresh = update.optJSONArray("messages");
                            if (fresh != null && fresh.length() > 0) handler.post(() -> {
                                for (int i = 0; i < fresh.length(); i++) { JSONObject msg = fresh.optJSONObject(i); if (msg != null && msg.optLong("id") > lastId[0]) { appendChatLine(lines, msg); lastId[0] = msg.optLong("id"); } }
                                log.setText(lines.toString()); scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                            });
                        } catch (Exception ignored) { }
                        handler.postDelayed(chatPoll[0], 3000);
                    }, "aMule-native-chat-poll").start();
                };
                dialog.setOnDismissListener(ignored -> handler.removeCallbacks(chatPoll[0]));
                dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String text = draft.getText().toString().trim(); if (text.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            api.post("chats/" + address + "/messages", json("text", text));
                            handler.post(() -> { draft.setText(tr("")); handler.removeCallbacks(chatPoll[0]); chatPoll[0].run(); });
                        } catch (Exception failure) {
                            handler.post(() -> nativeToast( "Message could not be sent: " + failure.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }, "aMule-native-chat-send").start();
                }));
                dialog.show();
                handler.post(chatPoll[0]);
            });
        }, "aMule-native-chat-load").start();
    }

    private void appendChatLine(StringBuilder lines, JSONObject message) {
        String who = message.optString("direction", "in").equals("out") ? "You" : "Peer";
        String time = message.isNull("sent_at") ? "time unavailable" : dateTime(message.optLong("sent_at"));
        lines.append("[").append(time).append("] ").append(who).append(": ").append(message.optString("text", "")).append("\n\n");
    }

    private String dateTime(long epochSeconds) {
        if (epochSeconds <= 0) return "unknown";
        return android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", epochSeconds * 1000L).toString();
    }

    private String displayValue(JSONObject object, String key, String fallback) {
        if (object == null || object.isNull(key)) return fallback;
        String value = object.optString(key, "").trim();
        return value.isEmpty() || value.equalsIgnoreCase("null") ? fallback : value;
    }

    private String clientSortField() {
        if (clientListMode.equals("Known")) {
            if (clientsSort.equals("Downloaded")) return "downloaded_bytes_total";
            if (clientsSort.equals("Uploaded")) return "uploaded_bytes_total";
            if (clientsSort.equals("Software")) return "software";
            if (clientsSort.equals("Name")) return "name";
            return "last_seen_at";
        }
        if (clientsSort.equals("Downloaded")) return "downloaded_bytes_total";
        if (clientsSort.equals("Uploaded")) return "uploaded_bytes_total";
        if (clientsSort.equals("Download speed")) return "download_speed_bytes_per_second";
        if (clientsSort.equals("Upload speed")) return "upload_speed_bytes_per_second";
        if (clientsSort.equals("Software")) return "software";
        return "name";
    }

    private String clientSortOrder() { return clientsSort.equals("Name") || clientsSort.equals("Software") ? "asc" : "desc"; }
    private String serverSortField() {
        if (serverSort.equals("Users")) return "user_count";
        if (serverSort.equals("Files")) return "file_count";
        if (serverSort.equals("Ping")) return "ping_ms";
        return "name";
    }
    private String serverSortOrder() { return serverSort.equals("Name") ? "asc" : "desc"; }

    private void loadMoreClients() {
        boolean known = clientListMode.equals("Known");
        int offset = known ? knownClients.length() : clients.length();
        String resource = known ? "known_clients" : "clients";
        String key = known ? "known_clients" : "clients";
        String path = resource + "?limit=50&offset=" + offset + "&sort=" + clientSortField() + "&order=" + clientSortOrder();
        new Thread(() -> { try {
            JSONObject page = api.get(path); JSONArray entries = page.optJSONArray(key);
            handler.post(() -> {
                JSONArray destination = known ? knownClients : clients;
                if (entries != null) for (int i = 0; i < entries.length(); i++) destination.put(entries.optJSONObject(i));
                if (known) knownClientsTotal = page.optInt("total", knownClientsTotal); else clientsTotal = page.optInt("total", clientsTotal);
                renderCurrentPage();
            });
        } catch (Exception failure) { handler.post(() -> nativeToast( "Could not load more clients: " + failure.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-client-page").start();
    }

    private void loadMoreServers() {
        int offset = servers.length();
        new Thread(() -> { try {
            JSONObject page = api.get("servers?limit=50&offset=" + offset + "&sort=" + serverSortField() + "&order=" + serverSortOrder());
            JSONArray entries = page.optJSONArray("servers");
            handler.post(() -> { if (entries != null) for (int i = 0; i < entries.length(); i++) servers.put(entries.optJSONObject(i));
                serversTotal = page.optInt("total", serversTotal); renderCurrentPage(); });
        } catch (Exception failure) { handler.post(() -> nativeToast( "Could not load more servers: " + failure.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-server-page").start();
    }

    private void showAddFriendDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText ip = new EditText(this); ip.setHint(tr("IP address")); ip.setSingleLine(true); form.addView(ip);
        EditText port = new EditText(this); port.setHint(tr("Port (4662)")); port.setInputType(2); port.setText(tr("4662")); form.addView(port);
        EditText name = new EditText(this); name.setHint(tr("Name (optional)")); name.setSingleLine(true); form.addView(name);
        new AlertDialog.Builder(this).setTitle(tr("Add friend")).setView(form).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Add"), (dialog, which) -> {
                    try {
                        JSONObject body = new JSONObject().put("ip", ip.getText().toString().trim()).put("port", Integer.parseInt(port.getText().toString()));
                        if (name.length() > 0) body.put("name", name.getText().toString().trim());
                        mutate("POST", "friends", body, "Friend added");
                    } catch (Exception error) { nativeToast( "Enter a valid IP and port", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void renderStatistics() {
        addGraphCard("download_speed", "Download speed", Color.rgb(58, 175, 93));
        addGraphCard("upload_speed", "Upload speed", Color.rgb(59, 134, 224));
        addGraphCard("connections", "Connections", Color.rgb(214, 138, 12));
        addGraphCard("kad_nodes", "Kad nodes", Color.rgb(138, 92, 214));
        LinearLayout card = card();
        card.addView(bodyText("Transfer and session statistics"));
        pageContent.addView(card, marginParams(0, 0, 0, 10));
        addStatNodes(statNodes, 0);
        if (statNodes.length() == 0) {
            LinearLayout empty = card(); empty.addView(bodyText("No statistics are available yet.")); pageContent.addView(empty);
        }
    }

    private int graphIntervalSeconds() {
        return getSharedPreferences("native_ui", MODE_PRIVATE).getInt("graph_interval", 12);
    }

    private void refreshGraphData(String page) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastGraphRefreshMillis < 15000) return;
        lastGraphRefreshMillis = now;
        String[] names = page.equals("Networks") ? new String[] {"kad_nodes"}
                : new String[] {"download_speed", "upload_speed", "connections", "kad_nodes"};
        new Thread(() -> {
            Map<String, JSONObject> updates = new HashMap<>();
            for (String name : names) {
                try { updates.put(name, api.get("stats/graphs/" + name + "?width=240&interval_seconds=" + graphIntervalSeconds())); }
                catch (Exception ignored) { }
            }
            handler.post(() -> {
                graphData.putAll(updates);
                if (selectedPage.equals(page)) renderCurrentPage();
            });
        }, "aMule-native-stat-graphs").start();
    }

    private void addGraphCard(String key, String title, int color) {
        JSONObject response = graphData.get(key);
        if (response == null) return;
        LinearLayout frame = card();
        TextView heading = bodyText(title);
        heading.setTextColor(INK); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        frame.addView(heading);
        frame.addView(new NativeGraphView(this, response, color, MUTED, BORDER), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        if (key.equals("connections")) frame.addView(bodyText("Total connections  ·  active downloads  ·  active uploads"));
        JSONObject session = response.optJSONObject("session");
        if (session != null && key.equals("download_speed")) frame.addView(bodyText("This session  ·  downloaded " + bytes(session.optLong("downloaded_bytes")) + "  ·  uploaded " + bytes(session.optLong("uploaded_bytes"))));
        pageContent.addView(frame, marginParams(0, 0, 0, 8));
    }

    private void addStatNodes(JSONArray nodes, int depth) {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i); if (node == null) continue;
            String label = node.optString("label", node.optString("key", "Statistic"));
            String value = node.has("value") ? String.valueOf(node.opt("value")) : "";
            LinearLayout row = card();
            TextView text = bodyText(label + (value.isEmpty() ? "" : "  ·  " + value));
            text.setTextColor(depth == 0 ? INK : MUTED); if (depth == 0) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(text); row.setPadding(dp(12 + depth * 12), dp(10), dp(12), dp(10));
            pageContent.addView(row, marginParams(0, 0, 0, 5));
            JSONArray children = node.optJSONArray("children"); if (children != null) addStatNodes(children, depth + 1);
        }
    }

    private void renderPreferences() {
        String[] tabs = {"Appearance", "General", "Connection", "Directories", "Servers", "Files", "Security", "GeoIP", "Proxy", "Message filter", "Remote controls", "Online signature", "Advanced", "API credentials"};
        int selected = 0;
        for (int i = 0; i < tabs.length; i++) if (tabs[i].equals(preferencesTab)) selected = i;
        Spinner picker = translatedSpinner(tabs, selected);
        picker.setId(R.id.native_preferences_section);
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!preferencesTab.equals(tabs[position])) { preferencesTab = tabs[position]; renderCurrentPage(); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        pageContent.addView(picker, marginParams(0, 0, 0, 10));
        switch (preferencesTab) {
            case "Appearance": addNativeAppearancePanel(); break;
            case "General": addPreferenceCategory("general", "General"); break;
            case "Connection": addPreferenceCategory("connection", "Connection"); break;
            case "Directories":
                addPreferenceCategory("directories", "Directories");
                Button shareDirs = button("Manage shared directories", false);
                shareDirs.setOnClickListener(view -> showSharedDirectoriesDialog());
                pageContent.addView(shareDirs, marginParams(0, 0, 0, 10));
                break;
            case "Servers":
                addPreferenceCategory("servers", "Servers");
                addPreferenceCategory("kad", "Kad");
                addActionButton("Update server list", "servers_update", "servers", "update_url");
                addActionButton("Update Kad nodes", "kad/update", "kad", "update_url");
                break;
            case "Files": addPreferenceCategory("files", "Files"); break;
            case "Security":
                addPreferenceCategory("security", "Security");
                addActionButton("Reload IP filter", "ipfilter/reload", null, null);
                addActionButton("Update IP filter", "ipfilter/update", "security", "ipfilter_update_url");
                break;
            case "GeoIP": addPreferenceCategory("geoip", "GeoIP"); addActionButton("Update GeoIP database", "geoip/update", null, null); break;
            case "Proxy": addPreferenceCategory("connection", "Proxy", "proxy_"); break;
            case "Message filter": addPreferenceCategory("message_filter", "Message filter"); break;
            case "Remote controls": addPreferenceCategory("remote_controls", "Remote controls"); break;
            case "Online signature": addPreferenceCategory("online_signature", "Online signature"); break;
            case "Advanced":
                addPreferenceCategory("advanced", "Advanced");
                addPreferenceCategory("files", "Memory mapping", "mmap_");
                break;
            case "API credentials": addApiCredentialsPanel(); break;
        }
        LinearLayout note = card();
        note.addView(bodyText(preferencesTab.equals("Appearance")
                ? "Appearance choices are saved on this Android device and do not change daemon preferences."
                : "Tap a value to edit it. Changes are saved to the running aMule core immediately. API passwords have their own page."));
        pageContent.addView(note, marginParams(0, 4, 0, 0));
    }

    private void addNativeAppearancePanel() {
        LinearLayout group = card();
        TextView heading = bodyText("Native app theme");
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        group.addView(heading);
        Spinner theme = translatedSpinner(new String[] {"System", "Light", "Dark"},
                nativeTheme.equals("light") ? 1 : nativeTheme.equals("dark") ? 2 : 0);
        theme.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String[] values = {"system", "light", "dark"};
                String selected = values[position];
                if (!nativeTheme.equals(selected)) {
                    nativeTheme = selected;
                    getSharedPreferences("native_ui", MODE_PRIVATE).edit().putString("theme", selected).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        group.addView(theme, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView languageLabel = bodyText("Interface language"); languageLabel.setTextColor(INK); languageLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD); group.addView(languageLabel);
        Spinner language = translatedSpinner(new String[] {"English", "Español"}, nativeLanguage.equals("Español") ? 1 : 0);
        language.setId(R.id.native_language);
        language.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String next = position == 0 ? "English" : "Español";
                if (!nativeLanguage.equals(next)) { nativeLanguage = next; getSharedPreferences("native_ui", MODE_PRIVATE).edit().putString("language", next).apply(); recreate(); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        group.addView(language, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        group.addView(bodyText("The native interface is available in English and Spanish. Names and statistics supplied by aMule remain in the core's language."));
        pageContent.addView(group, marginParams(0, 0, 0, 10));

        LinearLayout graphGroup = card();
        TextView graphHeading = bodyText("Statistics graph range");
        graphHeading.setTextColor(INK); graphHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        graphGroup.addView(graphHeading);
        String[] ranges = {"5 minutes", "1 hour", "6 hours", "24 hours"};
        int[] intervals = {1, 12, 72, 288};
        int selectedRange = 1;
        for (int i = 0; i < intervals.length; i++) if (intervals[i] == graphIntervalSeconds()) selectedRange = i;
        Spinner graphRange = translatedSpinner(ranges, selectedRange);
        graphRange.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int interval = intervals[position];
                if (graphIntervalSeconds() != interval) {
                    getSharedPreferences("native_ui", MODE_PRIVATE).edit().putInt("graph_interval", interval).apply();
                    lastGraphRefreshMillis = 0;
                    if (selectedPage.equals("Statistics") || selectedPage.equals("Networks")) refreshGraphData(selectedPage);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        graphGroup.addView(graphRange, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        pageContent.addView(graphGroup, marginParams(0, 0, 0, 10));
    }

    private void applyNativePalette() {
        darkMode = nativeTheme.equals("dark") || (nativeTheme.equals("system")
                && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        if (darkMode) {
            INK = Color.rgb(232, 235, 241);
            MUTED = Color.rgb(170, 180, 194);
            BLUE = Color.rgb(112, 174, 255);
            PAGE = Color.rgb(24, 28, 35);
            BORDER = Color.rgb(62, 72, 87);
            SURFACE = Color.rgb(34, 40, 49);
            TOOLBAR = Color.rgb(31, 37, 46);
            FOOTER = TOOLBAR;
        } else {
            INK = Color.rgb(39, 48, 61);
            MUTED = Color.rgb(100, 111, 128);
            BLUE = Color.rgb(31, 105, 195);
            PAGE = Color.rgb(233, 237, 244);
            BORDER = Color.rgb(211, 218, 228);
            SURFACE = Color.WHITE;
            TOOLBAR = Color.rgb(223, 228, 236);
            FOOTER = Color.rgb(227, 232, 240);
        }
    }

    private void addPreferenceCategory(String category, String title) { addPreferenceCategory(category, title, ""); }

    private void addPreferenceCategory(String category, String title, String prefix) {
        LinearLayout group = card();
        TextView heading = bodyText(title); heading.setTextColor(INK); heading.setTextSize(18); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); group.addView(heading);
        JSONObject values = preferences.optJSONObject(category);
        if (values == null) values = new JSONObject();
        addPreferenceRows(group, category, values, prefix);
        if (group.getChildCount() == 1) {
            String message = !preferencesLoaded ? "Loading preferences…"
                    : preferencesError != null ? "Could not load preferences: " + preferencesError
                    : "No settings were returned by this aMule build.";
            group.addView(bodyText(message));
        }
        pageContent.addView(group, marginParams(0, 0, 0, 10));
    }

    private void addPreferenceRows(LinearLayout group, String category, JSONObject values, String prefix) {
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!prefix.isEmpty() && !key.startsWith(prefix)) continue;
            Object value = values.opt(key);
            if (value == null || isCapabilityField(key)) continue;
            if (value instanceof JSONObject) {
                LinearLayout nested = card();
                TextView heading = bodyText(prettyPreferenceKey(key)); heading.setTextColor(INK); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); nested.addView(heading);
                addPreferenceRows(nested, category + "." + key, (JSONObject) value, "");
                group.addView(nested, marginParams(0, 6, 0, 0));
                continue;
            }
            addPreferenceRow(group, category, key, value);
        }
    }

    private void addPreferenceRow(LinearLayout group, String category, String key, Object value) {
        boolean readOnly = isReadOnlyPreference(category, key);
        LinearLayout row = horizontal();
        TextView label = bodyText(prettyPreferenceKey(key));
        row.addView(label, new LinearLayout.LayoutParams(0, dp(52), 1));
        String fullPath = category + "." + key;
        if (value instanceof Boolean) {
            CheckBox check = new CheckBox(this); check.setChecked((Boolean) value); check.setText(tr((Boolean) value ? "On" : "Off")); check.setEnabled(!readOnly);
            check.setOnCheckedChangeListener((button, checked) -> { if (!readOnly) savePreference(category, key, checked); });
            row.addView(check);
        } else {
            String display = preferenceValueLabel(category, key, value);
            String buttonText = display.isEmpty() ? (readOnly ? "(not set)" : "(empty)")
                    : (readOnly ? display : (isPasswordPreference(fullPath) ? "Set / change…" : display));
            Button edit = button(buttonText, false);
            edit.setEnabled(!readOnly);
            edit.setMaxWidth(dp(190));
            edit.setMaxLines(2);
            edit.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (!readOnly) edit.setOnClickListener(view -> {
                String[] choices = preferenceChoices(category, key);
                if (choices == null) editPreference(category, key, tr(prettyPreferenceKey(key)), value);
                else new AlertDialog.Builder(this).setTitle(tr(prettyPreferenceKey(key))).setSingleChoiceItems(localizedOptions(choices), preferenceChoiceIndex(category, key, value),
                        (dialog, selected) -> { savePreference(category, key, choiceValue(category, key, selected)); dialog.dismiss(); })
                        .setNegativeButton(tr("Cancel"), null).show();
            });
            row.addView(edit);
        }
        group.addView(row);
    }

    private static boolean isCapabilityField(String key) {
        return key.equals("supported") || key.endsWith("_supported") || key.equals("mmap_supported")
                || key.equals("upnp_supported");
    }

    private static boolean isReadOnlyPreference(String category, String key) {
        if (category.startsWith("remote_controls.external_connections")) return true;
        return key.equals("daemon_host_name") || key.equals("user_hash") || key.equals("ed2k_enabled")
                || key.equals("password_set") || key.equals("download_in_progress") || key.equals("loaded_source")
                || key.equals("db_path") || key.equals("db_loaded") || key.equals("last_update_status");
    }

    private static boolean isPasswordPreference(String path) {
        return path.endsWith("password") || path.endsWith("_password");
    }

    private static String[] preferenceChoices(String category, String key) {
        if (category.equals("security") && key.equals("shared_files_visibility")) return new String[] {"Everybody", "Friends", "Nobody"};
        if (category.equals("connection") && key.equals("proxy_type")) return new String[] {"SOCKS5", "SOCKS4", "HTTP", "SOCKS4a"};
        if (category.equals("geoip") && key.equals("source")) return new String[] {"DB-IP", "MaxMind", "Custom"};
        return null;
    }

    private static Object choiceValue(String category, String key, int selected) {
        if (category.equals("security") && key.equals("shared_files_visibility")) return new String[] {"everybody", "friends", "nobody"}[selected];
        if (category.equals("connection") && key.equals("proxy_type")) return new String[] {"socks5", "socks4", "http", "socks4a"}[selected];
        if (category.equals("geoip") && key.equals("source")) return new String[] {"dbip", "maxmind", "custom"}[selected];
        return "";
    }

    private static int preferenceChoiceIndex(String category, String key, Object value) {
        String raw = String.valueOf(value);
        String[] choices;
        if (category.equals("security") && key.equals("shared_files_visibility")) choices = new String[] {"everybody", "friends", "nobody"};
        else if (category.equals("connection") && key.equals("proxy_type")) choices = new String[] {"socks5", "socks4", "http", "socks4a"};
        else if (category.equals("geoip") && key.equals("source")) choices = new String[] {"dbip", "maxmind", "custom"};
        else return -1;
        for (int i = 0; i < choices.length; i++) if (choices[i].equals(raw)) return i;
        return -1;
    }

    private static String preferenceValueLabel(String category, String key, Object value) {
        String raw = value instanceof JSONArray ? arrayToText((JSONArray) value) : String.valueOf(value);
        if (category.equals("security") && key.equals("shared_files_visibility")) {
            switch (raw) { case "everybody": return "Everybody"; case "friends": return "Friends"; case "nobody": return "Nobody"; }
        }
        if (category.equals("connection") && key.equals("proxy_type")) {
            switch (raw) { case "socks5": return "SOCKS5"; case "socks4": return "SOCKS4"; case "http": return "HTTP"; case "socks4a": return "SOCKS4a"; }
        }
        if (category.equals("geoip") && key.equals("source")) {
            switch (raw) { case "dbip": return "DB-IP"; case "maxmind": return "MaxMind"; case "custom": return "Custom"; }
        }
        return raw;
    }

    private static String arrayToText(JSONArray array) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < array.length(); i++) { if (i > 0) value.append("|"); value.append(array.optString(i)); }
        return value.toString();
    }

    private static String prettyPreferenceKey(String key) {
        switch (key) {
            case "max_connection_count": return "Max connections";
            case "max_download_kibibytes_per_second": return "Max download KiB/s";
            case "max_upload_kibibytes_per_second": return "Max upload KiB/s";
            case "max_sources_per_file_count": return "Max sources per file";
            case "extended_udp_port_enabled": return "Extended UDP port enabled";
            case "ed2k_enabled": return "eD2k enabled";
            case "kad_enabled": return "Kad enabled";
            case "bind_address": return "Bind address";
            case "bind_interface": return "Bind interface";
            case "user_hash": return "User hash";
            case "daemon_host_name": return "Daemon host name";
            case "update_url": return "Update URL";
            case "ipfilter_update_url": return "IP filter update URL";
            case "geoip": return "GeoIP";
            case "upnp": return "UPnP";
            case "mmap": return "Memory mapping";
        }
        String[] words = key.replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private void editPreference(String category, String key, String label, Object current) {
        EditText input = new EditText(this); input.setSingleLine(!(current instanceof JSONArray));
        String initial = current instanceof JSONArray ? arrayToText((JSONArray) current) : (isPasswordPreference(category + "." + key) ? "" : String.valueOf(current));
        input.setText(initial);
        if (isPasswordPreference(category + "." + key)) input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (current instanceof Number) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        new AlertDialog.Builder(this).setTitle(label).setView(input).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Save"), (dialog, which) -> {
                    String value = input.getText().toString().trim(); Object parsed = value;
                    try {
                        if (current instanceof Number) parsed = Long.parseLong(value);
                        else if (current instanceof JSONArray) {
                            JSONArray array = new JSONArray(); for (String item : value.split("\\|")) if (!item.trim().isEmpty()) array.put(item.trim()); parsed = array;
                        }
                    } catch (NumberFormatException error) { nativeToast( "Enter a valid number", Toast.LENGTH_SHORT).show(); return; }
                    savePreference(category, key, parsed);
                }).show();
    }

    private void savePreference(String category, String key, Object value) {
        try {
            JSONObject patch = new JSONObject();
            JSONObject cursor = patch;
            String[] parts = category.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                JSONObject nested = cursor.optJSONObject(parts[i]);
                if (nested == null) { nested = new JSONObject(); cursor.put(parts[i], nested); }
                cursor = nested;
            }
            cursor.put(parts[parts.length - 1], new JSONObject().put(key, value));
            new Thread(() -> {
                try { api.patch("preferences", patch); handler.post(() -> { nativeToast( "Preference saved", Toast.LENGTH_SHORT).show(); refreshSectionData(); }); }
                catch (Exception failure) { handler.post(() -> nativeToast( "Could not save preference: " + failure.getMessage(), Toast.LENGTH_LONG).show()); }
            }, "aMule-native-preference-save").start();
        } catch (JSONException ignored) { }
    }

    private void addActionButton(String label, String endpoint, String sourceCategory, String sourceKey) {
        Button action = button(label, false);
        action.setOnClickListener(view -> new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                if (sourceCategory != null && sourceKey != null) {
                    JSONObject source = preferences.optJSONObject(sourceCategory);
                    String url = source == null ? "" : source.optString(sourceKey, "");
                    if (url.trim().isEmpty()) {
                        handler.post(() -> nativeToast( "Set the update URL first", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    body.put("url", url);
                }
                JSONObject result = api.post(endpoint, body);
                JSONArray outcomes = result.optJSONArray("results");
                int failed = 0;
                if (outcomes != null) for (int i = 0; i < outcomes.length(); i++) {
                    JSONObject outcome = outcomes.optJSONObject(i);
                    if (outcome != null && !outcome.optBoolean("ok", true)) failed++;
                }
                int rejected = failed;
                handler.post(() -> nativeToast( rejected == 0 ? label + " requested" : label + ": " + rejected + " item(s) rejected", Toast.LENGTH_LONG).show());
            } catch (Exception failure) {
                handler.post(() -> nativeToast( label + " failed: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-preference-action").start());
        pageContent.addView(action, marginParams(0, 0, 0, 8));
    }

    private void addApiCredentialsPanel() {
        LinearLayout panel = card();
        panel.addView(bodyText("Loading API access settings…"));
        pageContent.addView(panel, marginParams(0, 0, 0, 10));
        new Thread(() -> {
            try {
                JSONObject state = api.get("auth/passwords");
                handler.post(() -> {
                    if (!selectedPage.equals("Preferences") || !preferencesTab.equals("API credentials")) return;
                    panel.removeAllViews();
                    TextView heading = bodyText("Local API access");
                    heading.setTextColor(INK); heading.setTextSize(18); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    panel.addView(heading);
                    CheckBox guestAccess = new CheckBox(this);
                    boolean wasGuestEnabled = state.optBoolean("guest_access_enabled");
                    guestAccess.setChecked(wasGuestEnabled);
                    guestAccess.setText(tr("Allow guest access"));
                    panel.addView(guestAccess, marginParams(0, 8, 0, 2));
                    EditText current = passwordEntry("Current admin password"); panel.addView(current);
                    EditText admin = passwordEntry("New admin password (optional)"); panel.addView(admin);
                    EditText guest = passwordEntry("New guest password (optional)");
                    guest.setEnabled(wasGuestEnabled);
                    guestAccess.setOnCheckedChangeListener((button, enabled) -> {
                        guest.setEnabled(enabled);
                        if (!enabled) guest.setText(tr(""));
                    });
                    panel.addView(guest);
                    Button save = button("Apply credential changes", true);
                    save.setOnClickListener(view -> {
                        String currentValue = current.getText().toString();
                        String adminValue = admin.getText().toString();
                        String guestValue = guest.getText().toString();
                        boolean guestNowEnabled = guestAccess.isChecked();
                        boolean guestToggleChanged = guestNowEnabled != wasGuestEnabled;
                        if (currentValue.isEmpty()) {
                            nativeToast( "Enter the current admin password", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!guestToggleChanged && adminValue.isEmpty() && (!guestNowEnabled || guestValue.isEmpty())) {
                            nativeToast( "There are no credential changes to apply", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (guestNowEnabled && !wasGuestEnabled && guestValue.isEmpty()) {
                            nativeToast( "Set a guest password before enabling guest access", Toast.LENGTH_LONG).show();
                            return;
                        }
                        save.setEnabled(false);
                        new Thread(() -> {
                            try {
                                JSONObject patch = new JSONObject().put("current_password", currentValue);
                                if (!adminValue.isEmpty()) patch.put("admin_password", adminValue);
                                if (guestToggleChanged) patch.put("guest_access_enabled", guestNowEnabled);
                                if (guestNowEnabled && !guestValue.isEmpty()) patch.put("guest_password", guestValue);
                                JSONObject updated = api.patch("auth/passwords", patch);
                                if (!adminValue.isEmpty()) getSharedPreferences(AmuleService.PREFS, MODE_PRIVATE)
                                        .edit().putString(AmuleService.ADMIN_PASSWORD, adminValue).apply();
                                handler.post(() -> {
                                    save.setEnabled(true);
                                    current.setText(tr("")); admin.setText(tr("")); guest.setText(tr(""));
                                    guestAccess.setChecked(updated.optBoolean("guest_access_enabled", guestNowEnabled));
                                    nativeToast( "API credentials updated", Toast.LENGTH_SHORT).show();
                                });
                            } catch (Exception failure) {
                                handler.post(() -> {
                                    save.setEnabled(true);
                                    nativeToast( "Could not update API credentials: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                                });
                            }
                        }, "aMule-native-api-credentials-save").start();
                    });
                    panel.addView(save, marginParams(0, 10, 0, 0));
                    panel.addView(bodyText("Password values are write-only. Leave a new password blank to keep it unchanged."), marginParams(0, 8, 0, 0));
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    if (!selectedPage.equals("Preferences") || !preferencesTab.equals("API credentials")) return;
                    panel.removeAllViews();
                    panel.addView(bodyText("Could not load API access settings: " + failure.getMessage()));
                });
            }
        }, "aMule-native-api-credentials-load").start();
    }

    private EditText passwordEntry(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(tr(hint));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void showSharedDirectoriesDialog() {
        new Thread(() -> {
            try {
                JSONArray dirs = api.get("share_directories").optJSONArray("directories");
                if (dirs == null) dirs = new JSONArray();
                JSONArray directories = dirs;
                handler.post(() -> {
                    String[] labels = new String[directories.length() + 1];
                    labels[0] = "Add shared directory…";
                    for (int i = 0; i < directories.length(); i++) {
                        JSONObject dir = directories.optJSONObject(i);
                        labels[i + 1] = dir == null ? "Unknown" : dir.optString("path") + (dir.optBoolean("recursive") ? "  ·  recursive" : "");
                    }
                    new AlertDialog.Builder(this).setTitle(tr("Shared directories"))
                            .setItems(labels, (dialog, which) -> {
                                if (which == 0) showAddSharedDirectoryDialog();
                                else {
                                    JSONObject dir = directories.optJSONObject(which - 1);
                                    if (dir != null) showSharedDirectoryActions(dir);
                                }
                            }).setNegativeButton(tr("Close"), null).show();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Could not load shared directories: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-shared-directories").start();
    }

    private void showAddSharedDirectoryDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText path = new EditText(this); path.setHint(tr("Directory path")); path.setSingleLine(true); form.addView(path);
        CheckBox recursive = new CheckBox(this); recursive.setText(tr("Include subdirectories")); recursive.setChecked(true); form.addView(recursive);
        new AlertDialog.Builder(this).setTitle(tr("Add shared directory")).setView(form).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Add"), (dialog, which) -> {
                    try {
                        JSONObject body = new JSONObject().put("path", path.getText().toString().trim()).put("recursive", recursive.isChecked());
                        apiAction("POST", "share_directories", body, "Shared directory added");
                    } catch (JSONException ignored) { }
                }).show();
    }

    private void showSharedDirectoryActions(JSONObject directory) {
        String path = directory.optString("path", "");
        new AlertDialog.Builder(this).setTitle(path).setItems(localizedOptions(new String[] {"Toggle recursive sharing", "Remove directory"}), (dialog, which) -> {
            if (which == 0) {
                try {
                    JSONObject body = new JSONObject().put("path", path).put("recursive", !directory.optBoolean("recursive"));
                    apiAction("POST", "share_directories", body, "Sharing option updated");
                } catch (JSONException ignored) { }
            } else new AlertDialog.Builder(this).setTitle(tr("Remove shared directory?"))
                    .setMessage(path).setNegativeButton(tr("Keep"), null)
                    .setPositiveButton(tr("Remove"), (confirm, choice) -> {
                        try { apiAction("DELETE", "share_directories?path=" + java.net.URLEncoder.encode(path, "UTF-8"), null, "Shared directory removed"); }
                        catch (java.io.UnsupportedEncodingException ignored) { }
                    }).show();
        }).show();
    }

    private void apiAction(String method, String path, JSONObject body, String success) {
        new Thread(() -> {
            try {
                JSONObject result = method.equals("DELETE") ? api.delete(path) : api.post(path, body == null ? new JSONObject() : body);
                JSONArray outcomes = result.optJSONArray("results");
                int failed = 0;
                if (outcomes != null) for (int i = 0; i < outcomes.length(); i++) {
                    JSONObject outcome = outcomes.optJSONObject(i);
                    if (outcome != null && !outcome.optBoolean("ok", true)) failed++;
                }
                int rejected = failed;
                handler.post(() -> {
                    nativeToast( rejected == 0 ? success : rejected + " directory item(s) were rejected", Toast.LENGTH_LONG).show();
                    if (selectedPage.equals("Preferences")) refreshSectionData();
                    else refreshSnapshot();
                });
            } catch (Exception failure) {
                handler.post(() -> nativeToast( "Action failed: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "aMule-native-api-action").start();
    }

    private void addNetworkCard(String label, String key) {
        JSONObject network = status.optJSONObject(key);
        String state = network == null ? "unknown" : network.optString("state", "unknown");
        LinearLayout item = card();
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(18);
        name.setTextColor(INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        item.addView(name);
        TextView stateText = bodyText(tr("Status: ") + tr(state));
        stateText.setPadding(0, dp(6), 0, 0);
        item.addView(stateText);
        if (key.equals("kad")) {
            Button bootstrap = button("Bootstrap from node", false);
            bootstrap.setOnClickListener(view -> showKadBootstrapDialog());
            item.addView(bootstrap, marginParams(0, 8, 0, 0));
            JSONObject kad = status.optJSONObject("kad");
            if (kad != null) {
                JSONObject kadNetwork = kad.optJSONObject("network");
                addDetailLine(item, "Routing-table nodes", kadNetwork == null ? "Unknown" : String.valueOf(kadNetwork.optInt("node_count")));
                addDetailLine(item, "TCP firewalled", kad.isNull("firewalled_tcp") ? "Unknown" : kad.optBoolean("firewalled_tcp") ? "Yes" : "No");
            }
        }
        pageContent.addView(item, marginParams(0, 0, 0, 10));
        if (key.equals("kad")) addGraphCard("kad_nodes", "Kad nodes", Color.rgb(138, 92, 214));
    }

    private void showKadDetails() {
        new Thread(() -> {
            try {
                JSONObject kad = api.get("kad");
                handler.post(() -> {
                    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18), dp(4), dp(18), 0);
                    for (String key : new String[] {"state", "firewalled_tcp", "firewalled_udp", "lan_mode", "public_ip", "node_id"}) {
                        if (kad.has(key)) addDetailLine(content, prettyPreferenceKey(key), kad.isNull(key) ? "Unknown" : String.valueOf(kad.opt(key)));
                    }
                    JSONObject network = kad.optJSONObject("network");
                    if (network != null) for (String key : new String[] {"user_count", "file_count", "node_count"}) addDetailLine(content, "Network " + prettyPreferenceKey(key), String.valueOf(network.optInt(key)));
                    JSONObject indexed = kad.optJSONObject("indexed");
                    if (indexed != null) for (String key : new String[] {"sources", "keywords", "notes", "load_percent"}) addDetailLine(content, "Indexed " + prettyPreferenceKey(key), String.valueOf(indexed.optInt(key)));
                    JSONObject buddy = kad.optJSONObject("buddy");
                    if (buddy != null) for (String key : new String[] {"state", "ip", "port"}) addDetailLine(content, "Buddy " + prettyPreferenceKey(key), String.valueOf(buddy.opt(key)));
                    new AlertDialog.Builder(this).setTitle(tr("Kad status")).setView(content).setPositiveButton(tr("Close"), null).show();
                });
            } catch (Exception error) { handler.post(() -> nativeToast( "Could not load Kad status: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-kad-details").start();
    }

    private void showNetworkLog(String endpoint, String title, boolean linesArray) {
        new Thread(() -> {
            try {
                JSONObject response = api.get(endpoint + "?tail=500");
                JSONArray lines = response.optJSONArray("lines");
                String text = linesArray && lines != null ? joinLogLines(lines) : response.optString("text", "");
                handler.post(() -> {
                    TextView body = bodyText(text.isEmpty() ? "No log entries." : text);
                    body.setTextIsSelectable(true);
                    body.setTypeface(android.graphics.Typeface.MONOSPACE);
                    ScrollView scroll = new ScrollView(this); scroll.addView(body);
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                            .setNegativeButton(tr("Close"), null).setNeutralButton(tr("Share"), null).setPositiveButton(tr("Clear log"), null).create();
                    dialog.setOnShowListener(ignored -> {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                            startActivity(Intent.createChooser(share, "Share " + title));
                        });
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> new Thread(() -> {
                            try { api.delete(endpoint); handler.post(() -> { body.setText(tr("Log cleared.")); nativeToast( "Log cleared", Toast.LENGTH_SHORT).show(); }); }
                            catch (Exception error) { handler.post(() -> nativeToast( "Could not clear log: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
                        }, "aMule-native-log-clear").start());
                    });
                    dialog.show();
                });
            } catch (Exception error) { handler.post(() -> nativeToast( "Could not load log: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-log-load").start();
    }

    private void showUpdateUrlDialog(String category, String endpoint, String title) {
        new Thread(() -> {
            try {
                JSONObject prefs = api.get("preferences");
                JSONObject group = prefs.optJSONObject(category);
                String savedUrl = group == null ? "" : group.optString("update_url", "");
                handler.post(() -> {
                    EditText url = new EditText(this);
                    url.setSingleLine(true);
                    url.setHint(tr("https://…"));
                    url.setText(savedUrl);
                    LinearLayout form = new LinearLayout(this); form.setPadding(dp(20), dp(4), dp(20), 0); form.addView(url);
                    AlertDialog.Builder prompt = new AlertDialog.Builder(this).setTitle(tr(title)).setView(form)
                            .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Download"), (dialog, which) -> {
                                String value = url.getText().toString().trim();
                                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                                    nativeToast( "Enter a valid HTTP or HTTPS URL", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Runnable update = () -> apiAction("POST", endpoint, json("url", value), title + " requested");
                                if (endpoint.equals("kad/update")) new AlertDialog.Builder(this)
                                        .setTitle(tr("Replace Kad node list?"))
                                        .setMessage(tr("When the new list is installed, Kad briefly stops and reconnects."))
                                        .setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Continue"), (confirm, choice) -> update.run()).show();
                                else update.run();
                            });
                    prompt.show();
                });
            } catch (Exception error) { handler.post(() -> nativeToast( "Could not read update URL: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "aMule-native-update-url").start();
    }

    private static String joinLogLines(JSONArray lines) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length(); i++) result.append(lines.optString(i));
        return result.toString();
    }

    private void showAddServerDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText address = new EditText(this); address.setHint(tr("Host or IP:port")); address.setSingleLine(true); form.addView(address);
        EditText name = new EditText(this); name.setHint(tr("Name (optional)")); name.setSingleLine(true); form.addView(name);
        new AlertDialog.Builder(this).setTitle(tr("Add server")).setView(form).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Add"), (dialog, which) -> {
                    String host = address.getText().toString().trim(); if (host.isEmpty()) return;
                    JSONObject body = json("address", host);
                    if (name.length() > 0) try { body.put("name", name.getText().toString().trim()); } catch (JSONException ignored) { }
                    mutate("POST", "servers", body, "Server added");
                }).show();
    }

    private void showKadBootstrapDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText ip = new EditText(this); ip.setHint(tr("Node IP address")); ip.setSingleLine(true); form.addView(ip);
        EditText port = new EditText(this); port.setHint(tr("Node port")); port.setInputType(2); form.addView(port);
        new AlertDialog.Builder(this).setTitle(tr("Bootstrap Kad")).setView(form).setNegativeButton(tr("Cancel"), null)
                .setPositiveButton(tr("Bootstrap"), (dialog, which) -> {
                    try {
                        JSONObject body = new JSONObject().put("ip", ip.getText().toString().trim())
                                .put("port", Integer.parseInt(port.getText().toString()));
                        mutate("POST", "kad/bootstrap", body, "Kad bootstrap requested");
                    } catch (Exception error) { nativeToast( "Enter a valid IP and port", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void renderComingSoon() {
        LinearLayout card = card();
        TextView title = new TextView(this);
        title.setText(tr(selectedPage));
        title.setTextSize(20);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        TextView note = bodyText("This native UI experiment starts with Downloads and network status. "
                + "Other sections remain available in the WebView version on the main branch.");
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note);
        pageContent.addView(card);
    }

    private void renderAbout() {
        LinearLayout about = card();
        TextView title = bodyText("aMule for Android"); title.setTextColor(INK); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        about.addView(title);
        String version = "unknown";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        about.addView(bodyText("Native Android interface  ·  app version " + version));
        about.addView(bodyText("The native screens use the aMule core and its local API. The Web UI is also available from the app menu."));
        pageContent.addView(about, marginParams(0, 0, 0, 10));

        LinearLayout versions = card();
        TextView versionsTitle = bodyText("Core versions");
        versionsTitle.setTextColor(INK); versionsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        versions.addView(versionsTitle);
        if (aboutVersionInfo == null) {
            versions.addView(bodyText(aboutVersionError == null
                    ? (aboutVersionLoading ? "Loading version information…" : "Version information has not loaded.")
                    : "Could not load version information: " + aboutVersionError));
            Button retry = button(aboutVersionError == null ? "Refresh version information" : "Retry", false);
            retry.setOnClickListener(view -> { aboutVersionError = null; loadAboutVersion(); });
            versions.addView(retry);
        } else {
            String apiVersion = aboutVersionInfo.optString("amuleapi_version", "Unknown");
            String daemonVersion = aboutVersionInfo.optString("daemon_version", "Not connected");
            versions.addView(bodyText("aMule API: " + apiVersion));
            versions.addView(bodyText("aMule daemon: " + daemonVersion));
            versions.addView(bodyText("API version: " + aboutVersionInfo.optString("api_version", "Unknown")));
            if (!daemonVersion.isEmpty() && !daemonVersion.equals("Not connected") && !daemonVersion.equals(apiVersion)) {
                TextView mismatch = bodyText("The API and daemon versions do not match.");
                mismatch.setTextColor(0xffa13a34); versions.addView(mismatch);
            }
            JSONObject update = aboutVersionInfo.optJSONObject("update");
            if (update != null && update.optBoolean("check_enabled")) {
                if (update.optBoolean("available")) {
                    versions.addView(bodyText("Update available: " + update.optString("latest_version", "")));
                } else if (update.optBoolean("checked")) {
                    versions.addView(bodyText("No newer version was found."));
                }
                if (update.has("last_checked_at")) {
                    versions.addView(bodyText("Last checked: " + dateTime(update.optLong("last_checked_at"))));
                }
                Button check = button(aboutUpdateCheckRunning ? "Checking for updates…" : "Check for updates", false);
                check.setEnabled(!aboutUpdateCheckRunning);
                check.setOnClickListener(view -> checkForUpdates());
                versions.addView(check, marginParams(0, 6, 0, 0));
            }
        }
        pageContent.addView(versions, marginParams(0, 0, 0, 10));

        Button web = button("Open full Web UI", true);
        web.setOnClickListener(view -> startActivity(new Intent(this, WebUiActivity.class)));
        pageContent.addView(web, marginParams(0, 0, 0, 10));
    }

    private void loadAboutVersion() {
        if (api == null || aboutVersionLoading) return;
        aboutVersionLoading = true;
        new Thread(() -> {
            try {
                JSONObject info = api.get("version");
                handler.post(() -> {
                    aboutVersionInfo = info;
                    aboutVersionError = null;
                    aboutVersionLoading = false;
                    if (selectedPage.equals("About")) renderCurrentPage();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    aboutVersionError = error.getMessage();
                    aboutVersionLoading = false;
                    if (selectedPage.equals("About")) renderCurrentPage();
                });
            }
        }, "aMule-native-version-info").start();
    }

    private void checkForUpdates() {
        if (api == null || aboutUpdateCheckRunning) return;
        aboutUpdateCheckRunning = true;
        renderCurrentPage();
        new Thread(() -> {
            try {
                api.post("version/check", new JSONObject());
                Thread.sleep(2000);
                JSONObject info = api.get("version");
                handler.post(() -> {
                    aboutVersionInfo = info;
                    aboutVersionError = null;
                    aboutUpdateCheckRunning = false;
                    if (selectedPage.equals("About")) renderCurrentPage();
                    nativeToast( "Update check complete", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    aboutVersionError = error.getMessage();
                    aboutUpdateCheckRunning = false;
                    if (selectedPage.equals("About")) renderCurrentPage();
                    nativeToast( "Could not check for updates: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "aMule-native-update-check").start();
    }

    private void updateFooter(String error) {
        if (footer == null) return;
        JSONObject ed2k = status.optJSONObject("ed2k");
        JSONObject kad = status.optJSONObject("kad");
        String left = tr(ed2k == null ? "unknown" : ed2k.optString("state", "unknown"));
        String right = tr(kad == null ? "unknown" : kad.optString("state", "unknown"));
        String live = error == null ? tr(eventStreamLive ? "live · events" : "live · polling") : tr("offline · ") + error;
        footer.setText(live + "   |   ED2K: " + left + "   |   Kad: " + right);
        footer.setTextColor(error == null ? INK : 0xffa13a34);
    }

    private void showComingSoon(String label) {
        nativeToast( label + " will be added in a later prototype pass", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), BORDER);
        view.setBackground(background);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button button(String label, boolean primary) {
        Button view = new Button(this);
        view.setText(tr(label));
        view.setTextSize(12);
        view.setAllCaps(false);
        view.setTextColor(primary ? (darkMode ? Color.rgb(20, 29, 41) : Color.WHITE) : INK);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? BLUE : SURFACE));
        return view;
    }

    private TextView bodyText(String value) {
        TextView text = new TextView(this);
        text.setText(tr(value));
        text.setTextSize(14);
        text.setTextColor(MUTED);
        return text;
    }

    private Toast nativeToast(String message, int duration) {
        return Toast.makeText(this, tr(message), duration);
    }

    private String tr(String value) {
        return nativeStrings.translate(value, nativeLanguage);
    }

    private EditText searchField(String hint, String value, java.util.function.Consumer<String> update) {
        return searchField(hint, value, update, false);
    }

    private EditText searchField(String hint, String value, java.util.function.Consumer<String> update, boolean numeric) {
        EditText input = new EditText(this);
        input.setHint(tr(hint));
        input.setSingleLine(true);
        input.setTextSize(14);
        if (numeric) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.accept(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        return input;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double amount = value;
        int index = -1;
        do { amount /= 1024.0; index++; } while (amount >= 1024 && index < units.length - 1);
        return String.format(java.util.Locale.UK, "%.1f %s", amount, units[index]);
    }

    private static String speed(long value) {
        return bytes(value) + "/s";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        new AlertDialog.Builder(this)
                .setTitle(tr("aMule is running"))
                .setMessage(tr("Keep aMule running in the background, or stop it and close the app?"))
                .setNegativeButton(tr("Keep running"), (dialog, which) -> finish())
                .setPositiveButton(tr("Stop aMule"), (dialog, which) -> {
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
            return new WindowInsets.Builder(insets).setInsets(insetTypes, Insets.NONE).build();
        });
        content.requestApplyInsets();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(liveRefresh);
        eventStreamRunning.set(false);
        if (api != null) api.closeEventStream();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("native_page", selectedPage);
        outState.putString("native_preferences_tab", preferencesTab);
        super.onSaveInstanceState(outState);
    }
}
