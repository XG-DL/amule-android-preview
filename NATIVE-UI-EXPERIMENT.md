# Native Android UI experiment

This branch explores a native Android frontend for the Android aMule preview. It remains separate from the existing `main` branch.

The app runs the same native aMule daemon as the main branch. The Android UI talks to the bundled `amuleapi` service on loopback. The existing WebView remains available from the app menu.

## Screenshots

These screenshots were captured from the English interface on an Android 16 (API 36) x86_64 emulator. The APK's bundled ARM64 aMule binaries run there through Android's ARM translation layer. The Search and Downloads images show their empty states after the temporary test downloads were removed.

| Networks and Kad | Statistics |
|---|---|
| ![Native Networks screen showing eD2k and Kad status and a Kad graph](screenshots/native/networks.png) | ![Native Statistics screen showing transfer and connection graphs](screenshots/native/statistics.png) |

| Search | Downloads |
|---|---|
| ![Native Search screen with search type and optional filters](screenshots/native/search.png) | ![Native Downloads screen showing filters and download actions with an empty queue](screenshots/native/downloads.png) |

| Appearance | Connection preferences |
|---|---|
| ![Native Appearance preferences showing the English interface option](screenshots/native/appearance.png) | ![Native Connection preferences showing editable daemon settings](screenshots/native/preferences-connection.png) |

## Native screens

- **Downloads:** filter by text, status and category; sort by name, size, progress, speed, sources or status; select rows for pause/resume, cancel, priority, category changes; clear completed items; inspect per-download parts, sources, comments, alternate filenames and connected clients.
- **Networks:** eD2k/Kad connection controls, server list management with name/address filtering, sorting and paging, server-list and Kad-node updates, Kad details, Kad node graph, and readable aMule/server information logs with share and clear actions.
- **Statistics:** native graphs for download speed, upload speed, connections and Kad nodes, including active upload/download series on the connection graph, session transfer totals, a statistics tree and locally chosen graph sample range.
- **Clients:** switch between connected clients and known clients, filter by name/address/software, sort and page through results, inspect live client details and browse a connected peer's shared files.
- **Shared files:** filter by filename and upload state, sort by name/size/upload rate/lifetime uploaded bytes, select multiple files for upload-priority changes, reload the share list, request metadata refresh, verify a file, and open file details including media metadata and uploading clients.
- **Messages:** conversation list with last-message preview, friends controls and add-friend flow. Open conversations load recent history, send messages and poll for incoming/outgoing messages while the dialog is open.
- **Appearance:** local System/Light/Dark theme, English/Spanish interface choice and graph range. Spanish covers navigation, preference sections and main screen controls; aMule-provided names, logs, statistics values and some less common strings remain in the daemon/UI language.
- **About:** Android app version, aMule API and daemon versions, API version, update status, and the configured update-check action.
- Native search includes Global/Kad/Local search, filters, sorting, per-search state, selection and result details. Native preferences cover the settings exposed by the API.
- The Full Web UI remains available from the app menu for additional controls and workflows.

## Implementation notes

Mobile cards present data from the API; client and server lists load 50 records at a time; graph controls offer four fixed ranges. The Spanish UI translates app-owned labels and controls; filenames, server/client names, logs and message text supplied by aMule or the user remain as supplied. Some per-file detail operations depend on the API endpoints supported by the connected aMule build. The Full Web UI provides its own controls from the app menu.

The native UI subscribes to the API's server-sent event stream and refreshes the affected screen when events arrive. REST polling remains as a fallback and periodic reconciliation. Open chat dialogs also poll for new messages every three seconds while visible. Long-lived graph history depends on the data the daemon retains and the selected sample interval.

## Build

From the project directory:

```sh
./gradlew assembleDebug
```

Install the debug APK on a connected Android device or emulator with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Review state

The project was built with `assembleDebug` on 7 October 2026 and manually exercised against the Android 16/API 36 emulator and local daemon. The emulator reports x86_64, while the installed app package contains ARM64 code and the system provides `libndk_translation`; this verifies the app on that emulator image through Android's ARM translation, not a native x86_64 build. The walkthrough verified the live event connection, active download progress, shared-file detail and comment/rating editor (the temporary test comment was cleared), connected/known client switching, server-name filtering, Kad and transfer graphs, graph-range changes, and the navigation/preferences controls. The General "Version Check Enabled" preference was toggled off, remained off after leaving and reopening Preferences, then was restored to its original enabled state. The numeric editor saved the existing Max connections value (500) unchanged and displayed it after refresh. The Directories Exclude Patterns list and Servers Update URL were each saved unchanged; the UI reported success and refreshed the same values. With proxy use disabled, the current SOCKS5 proxy type was reselected and saved unchanged. The API credentials page loaded without changing credentials. The shared-directory manager loaded, and an empty app-private test directory was added, found in the list, and removed; a fresh preferences fetch showed no remaining shared path, and the empty test directory was deleted. The Add shared directory form was also cancelled once without submitting. A temporary download category pointing to an empty app-private directory was created, appeared in the category manager, then was deleted; its test directory was removed. Search network choices (Global, Kad, Local and All networks) and file-type filters were cycled and restored; an empty query showed local validation and did not send a request. A unique nonsense Global query completed with zero results and was removed afterwards. The result-details dialog opened and was cancelled; result sort and availability filter menus opened, the availability filter was restored to All, and a name filter was applied with no rows present. These checks cover the empty-state controls, but do not verify sorting, filtering, selection, or download actions on actual result rows. In Downloads, the Completed status filter showed the completed item, category choices showed All categories and Default, and sorting was changed from Name to Size and restored to Name; the status and category filters were returned to All. The server action buttons were checked in their two-row mobile layout. The eD2k control disconnected and reconnected successfully, returning to its original connected state. The add-link, add-category, clear-completed and cancel-download dialogs were opened and dismissed without changing the queue. The temporary download was paused and resumed successfully. Pagination was tested with a temporary five-item page size: both known clients (5 to 9 records) and servers (5 to 10) loaded another page. The production page size was restored to 50 before the final build. The API helper was stopped and automatically restarted by the Android service; the daemon stayed running and the native event stream reconnected. The About screen showed matching API/daemon versions and the current update status, and its update-check action completed. The Messages screen showed its empty state; no friend or chat data was available to verify an actual timestamped conversation or incoming-message refresh.

The established app checkout remains separate; this experiment is on `native-android-ui-experiment`.

On 8 October 2026, the Spanish catalog was audited against the native UI's labels and mixed-language captions were corrected, including byte counts, server/client totals, upload counts, network-state buttons, Kad details and preference fields. The Android debug build completed successfully. The updated build was installed on the emulator and the English and Spanish main screens and Preferences were visually checked. Core-supplied names, log entries and user-authored message text remain in their original language.
