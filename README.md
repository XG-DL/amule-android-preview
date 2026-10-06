# aMule for Android (preview)

An Android package that runs aMule's native daemon on the phone and presents aMule's responsive web interface in an Android WebView. This is an early, working prototype intended for hands-on review.

| Item | Current status |
|---|---|
| Android app | Version 0.1, debug build |
| Supported ABI | `arm64-v8a` only |
| Minimum Android API | 33 (Android 13) |
| Target Android API | 35 |
| Interface | aMule Web UI inside an Android WebView |
| aMule core | Android ARM64 `amuled` and `amuleapi` binaries included |
| Tested device | Pixel 10 Pro XL, ARM64, GrapheneOS (Android 17 / API 37) |

## Screenshots

Captured from the running app on the tested phone, in landscape orientation.

### Networks — Kad

![aMule Android Networks screen showing Kad status and node graph](screenshots/networks-kad.png)

### Downloads

![aMule Android Downloads screen](screenshots/downloads.png)

### Preferences — Web UI

![aMule Android Preferences screen showing Web UI settings](screenshots/preferences-webui.png)

## How the app works

The Android wrapper starts two native aMule programs as child processes:

1. `amuled` runs the eD2k/Kad client and stores its configuration and working files in the app's private storage.
2. `amuleapi` serves the bundled aMule Web UI and its REST API on `127.0.0.1:4713`. The wrapper connects the WebView to this local address and creates a local authenticated session.

Both programs run under an Android foreground service. Android displays a persistent notification while the service is active; its **Stop** action terminates the API and daemon. The Android app needs Internet access for aMule's network traffic. The Web API is configured for loopback access, rather than a LAN listener.

The WebView talks to the local API over HTTP on `127.0.0.1`; the Android manifest permits cleartext traffic for this local connection. The API itself is configured to listen only on loopback.

aMule's configuration, incomplete files (`Temp`) and original completed files (`Incoming`) live in app-private storage. Completed files are copied to the shared `Downloads/aMule/Complete/` folder so they are accessible from Android file managers; the copy does not move or remove the original.

The app packages the responsive aMule Web UI with small mobile layout and chart-label adjustments. The phone displays it in a WebView.

The included core build has IPv6, UPnP, IP geolocation, uTP, QUIC and aMule's native gettext catalogs disabled. These are build choices in this preview, not a claim that Android cannot support them. In particular, this build does not provide uTP or UPnP port mapping. The Web UI has its own bundled translations and remains separate from the native gettext catalogs.

## What works in this preview

- Starts and stops the native daemon and Web API from the Android app.
- Keeps the daemon running in the foreground service when the screen is closed.
- Shows the Web UI areas available in aMule's web client: Networks, Searches, Downloads, Shared files, Clients, Messages, Statistics, Preferences and About.
- Uses the aMule Web UI for configuration; settings shown there are those implemented by the Web API.
- Copies completed files from aMule's private Incoming directory to `Downloads/aMule/Complete/` on supported Android versions (API 33 and later). The original remains in Incoming for sharing. The exported copy uses additional storage space.
- Includes tested layout adjustments for short landscape screens and Kad graph labels.

Network reachability depends on the user's network and port-forwarding setup, as with aMule generally.

## Build and install the Android app

The Android ARM64 aMule executables are included in this repository under `app/src/main/jniLibs/arm64-v8a/`. Building the APK packages those binaries; it does not rebuild the aMule native core.

### Ready-made testing build

Download a preview APK from [GitHub Releases](https://github.com/XG-DL/amule-android-preview/releases). The APK is a debug-signed development build for testing. Android may ask you to allow installation from the app used to open the download. The release notes include the APK's SHA-256 checksum.

### Requirements

- JDK 17 or newer.
- Android SDK Platform 36, Android Build Tools and NDK `28.2.13676358` installed.
- Gradle is provided by the checked-in wrapper (`./gradlew`); no system Gradle or CMake is needed. This APK build does not compile native code. NDK `28.2.13676358` is pinned so Gradle can strip debug symbols from the included executables with the same NDK generation used to build them.
- Android SDK location available through `ANDROID_HOME`/`ANDROID_SDK_ROOT` or a local, untracked `local.properties` file.

### Build

From the repository root:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Install on a connected ARM64 Android device

Enable Android developer options and USB debugging, connect the device, then run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p uk.xgdl.amuleprobe 1
```

Allow notifications when Android asks. The notification is how Android indicates the transfer service is active and provides the Stop action.

## Native aMule source and rebuilding the core

The included native executables were built from aMule commit [`f1d4b19ed01d07c6509d2d751219e4a1405c661c`](https://github.com/amule-org/amule/commit/f1d4b19ed01d07c6509d2d751219e4a1405c661c), plus the Android and Web UI patch in [native-core/android-core.patch](native-core/android-core.patch). The patch changes Android argument and password handling, Android certificate bundle setup, and the mobile Web UI layout and graph labels. [native-core/README.md](native-core/README.md) records the native build inputs and binary identifiers.

Rebuilding the APK is a single Gradle command above. Rebuilding the native executables is a separate cross-compilation step. [native-core/build-android-deps.sh](native-core/build-android-deps.sh) downloads SHA-256-pinned archives and builds wxWidgets, Boost headers, Crypto++, curl and OpenSSL for ARM64 Android, then builds the aMule binaries. It requires Linux x86-64, Android NDK `28.2.13676358`, CMake 3.28.3 or newer, and the patched source checkout described in [native-core/README.md](native-core/README.md). The script keeps third-party sources and generated files outside the tracked project files under `native-core/.android-deps/`; it does not replace the packaged binaries automatically.

## Testing record

Manual smoke testing was performed on one ARM64 GrapheneOS phone on 6 October 2026. It covered app/service startup and stop, the main Web UI areas and preference subsections, safe-to-dismiss dialogs, a completed 5.1 MiB download, and the completed-file export. The checked Android log window contained no fatal crash or ANR entries. See [TESTING.md](TESTING.md) for exactly what was exercised and what remains untested.

An earlier network check showed eD2k Low ID and firewalled Kad. uTP was disabled in this Android build, so this test says nothing about uTP connection behaviour; the optional experimental uTP build path has not been substantively tested.

## Known limits

- ARM64 only. Other device architectures have no bundled native binaries.
- Tested on one GrapheneOS phone, not a range of Android versions or manufacturers.
- No automated Android UI test suite is included; interaction testing to date is manual.
- Reboot recovery, Android process reclamation, battery-saver behaviour, long background transfers and all preference effects have not been verified.
- The native dependency and aMule cross-build is automated for Linux x86-64 by `native-core/build-android-deps.sh`. It is separate from the Android wrapper/APK build and has not yet been exercised on other host operating systems or architectures.

## Licensing and project status

The Android wrapper and Android-specific aMule changes are released under GPL-2.0-or-later; see [LICENSE.md](LICENSE.md). The included native binaries are built from the pinned aMule source and patch noted above. [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) identifies their bundled dependencies and licence terms. The Web UI also retains the Preact and htm notices alongside the vendored files under `app/src/main/assets/webui/js/vendor/`.

The app is available for testing and review.
