# Manual testing record

This records manual checks of the Android preview build and the areas still awaiting review.

## Device and build

- Device: Pixel 10 Pro XL, ARM64, GrapheneOS.
- Device OS/API: Android 17, API 37.
- App label: aMule Android Preview, version 0.1.3.
- Android minimum configured by the app: API 33.
- Android target configured by the app: API 36.
- APK built locally as a debug build, including newly rebuilt ARM64 `amuled` and `amuleapi` binaries with UPnP enabled.
- `./gradlew assembleDebug` completed successfully on the Linux development host with JDK 21 and Android SDK Platform 36.
- Native core cross-build completed with the pinned Linux x86-64 / NDK 28.2.13676358 toolchain and pupnp 22.1.8.
- Test date: 2026-10-07.

## Exercised

- App startup, foreground service, local aMule web UI login and display.
- Android 16/API 36 full packaged app on the `aMule_API36_x86_64` Google APIs emulator. The ARM64 daemon and API executables ran through the emulator's `libndk_translation.so`; the foreground service stayed active, local API login and status/download/shared-directory requests passed, and the queue was empty. This exercises the complete app on API 36, but does not replace a native ARM64 API 36 device test.
- API 36 interactive controls: global search for `amule` returned 462 results; ED2K and Kad each disconnected and reconnected through their controls; category creation rejected a blank form, accepted a temporary category, opened its edit form, and deleted it again; the Add Friend form showed required-field validation and was cancelled without creating a friend.
- API 36 UI navigation through Networks, Searches, Downloads, Shared files, Clients, Messages, Statistics, Preferences and About; all Preferences sections opened. The Web UI theme changed to Dark and saved, then was restored to System and saved. About → Check now completed and showed the current version as up to date.
- API 36 Downloads controls: opened the priority selector, category selector and status filter and confirmed their options were displayed; opened the column picker and reviewed its visibility/reset options; clicked Clear completed with an empty queue. Since no download rows existed, no row-specific action or clear-completed removal was possible to verify.
- Main areas visited: Networks, Searches, Downloads, Shared files, Clients, Messages, Statistics, Preferences and About.
- Preference subsections visited: Web UI, General, Connection, Directories, Servers, Files, Security, Proxy, Filters, Remote Controls, Online Signature and Advanced.
- Safe dialogs opened and dismissed: Web UI reset confirmation, Add Friend, Add category and Clear aMule log.
- Empty Add server and Kad bootstrap submissions, which displayed their validation messages.
- Notification Stop action, then reopening the app to start the service again.
- A completed 5.1 MiB download and export to Android Downloads.
- A second completed-file check: the requested small download was published to shared Downloads, the private Incoming directory was empty, and the local API listed the file and its shared directory.
- Ten minutes with the phone locked: the foreground service and both native processes stayed alive; download byte counts advanced, four downloads completed, all four were moved into shared Downloads, and the exporter logged no errors.
- Notification and battery restrictions on Android 17/API 37: with the `POST_NOTIFICATION` app-op set to ignore and battery saver enabled for 20 seconds, the foreground service and local API remained available at both checks. The six queued downloads reported `downloading`, but zero sources were transferring and their byte counters did not advance during this short test. The notification app-op and battery-saver setting were restored to their prior effective states afterward.
- A 15-second airplane-mode interruption followed by Wi-Fi recovery: the service and processes stayed alive, the API became available again, and transfer byte counts advanced after reconnection.
- Network-state notification check on the updated build: with airplane mode enabled and Wi-Fi disabled, the foreground notification showed “No network connection”; after restoring Wi-Fi and disabling airplane mode, it showed “Internet available · aMule is running”. The daemon and API remained available and all six queued downloads remained listed. No sources were transferring during this repeat, so it does not establish byte progress across this particular interruption.
- Live-transfer network-loss repeat: seven downloads had 18 active sources before a 15-second full network interruption. The queue remained intact and the first post-restore sample showed about 186 MB more cumulative received data, but later samples had zero active sources and unchanged byte totals even after the eD2k server reconnected with Low ID. This run therefore does not establish that peer transfers resumed after the network interruption.
- API process recovery: the API process was terminated while the daemon remained running. After the health checks detected the failure, the service restarted the API; the daemon PID stayed unchanged, API login succeeded, and the six queued downloads remained listed.
- Daemon process recovery: the daemon process was terminated while no sources were transferring. The service restarted the core, the API became available after its third startup attempt, and the smoke check found the same six queued downloads. This forced daemon exit drops live peers; the test verifies recovery of the saved queue, not continuity of peer connections or active transfers.
- Live-transfer daemon recovery: the daemon was deliberately terminated while 50 sources were transferring. The service started a new daemon process and the API became ready after its second handshake attempt. All 16 download records remained; after about 40 seconds, 3 sources were transferring and cumulative received bytes had increased by about 22 MiB from the pre-crash sample. About 20 seconds later, 6 sources were transferring and the total had increased by about 72 MiB. This verifies that saved partial downloads resume after a daemon crash; peer sockets themselves do not survive the crash.
- The app's normal Stop action followed by unlocking and reopening it: the daemon/API restarted, the existing queue reappeared, and completed test files remained in the shared list.
- Device reboot and manual relaunch: the app does not auto-start after boot. The first post-reboot startup exposed a race where the daemon opened its EC TCP port before it was ready to complete the API handshake. The service now retries the API process while the daemon remains alive; on the next launch the API became ready on attempt 2, and the smoke check found the restored queue and shared files.
- `scripts/android-smoke-test.sh` against the connected debug build, checking the service, daemon, API login, status, downloads, shared files and shared directories.
- Five Android instrumentation cases on an Android 16/API 36 x86_64 emulator: duplicate destination names preserve both files; an injected “no space left” write failure preserves the Incoming original; a persisted publish marker recovers a completed export without another copy; a mid-copy write interruption resumes the same pending MediaStore entry; and the instrumentation process kills a dedicated app process after a partial write, then a fresh process resumes that same entry and completes the move. The no-space condition was injected by the test and was not caused by filling emulator storage.
- Android 17/API 37 phone smoke test: a uniquely named file was created in the app’s private Incoming folder; the running foreground service exported it to Downloads, the test verified the copied bytes and that the private original was removed, then deleted the temporary exported test row.
- API 36 edge-to-edge layout on the phone: status bar no longer overlaps the Web UI toolbar, the bottom status strip clears the gesture bar, and Back opens the keep-running/stop prompt. Choosing Keep running left the foreground service and API healthy.
- Mobile layout in short landscape orientation and Kad chart labels.
- Recent Android log checked for FATAL and ANR entries; none were found in the checked log window.

## Automated CI

The `Android build and tests` workflow builds the packaged debug APK from a clean checkout, then builds a test-host variant and runs the five exporter instrumentation tests on an Android 16/API 36 x86_64 emulator. The test-host variant omits the ARM64 native executables, so this CI job validates the APK build and Java exporter logic; it does not exercise the native daemon or replace the ARM64 device smoke tests.

## UPnP port-mapping test

The phone was connected to the local router over Wi-Fi. In **Preferences → Connection**, UPnP was enabled and the app service was restarted so the daemon could initialise its control point.

- The daemon log reported an Internet Gateway Device and subscribed to its `WANIPConnection:1` service.
- The router reported two existing mappings before aMule's requests and five afterwards. This is consistent with aMule adding the three P2P mappings: TCP listen, server UDP (TCP+3), and extended client UDP. The EC and Web API ports are not requested by this configuration.
- aMule connected to eD2k with High ID and Kad reached `Connected (ok)`.
- The TCP and UDP ports were changed live to TCP `48321` and client UDP `48325` (server UDP `48324`), then restored to TCP `4662` and client UDP `4672`. During each replacement the router reported two entries while old mappings were removed, then five after the new mappings were added; eD2k returned to High ID.

This verifies local-router discovery, aMule's UPnP request path, eD2k reachability, Kad reaching its healthy status, and mapping refresh across the tested live port changes on this one router/device combination. The mapping count is router-reported; the router's administration page was not independently captured. Other routers have not been tested.

## Observed result from the earlier copy-based build

The service started the daemon/API and the UI was reachable. The download completed and appeared under `Downloads/aMule/Complete/`; the original remained in aMule's Incoming directory. That test predates the move-and-share change documented in the README. Stop ended the native processes, and a later app launch started them again. The short-landscape pane layout and chart spacing changes appeared as intended in the observed screens.

An earlier test showed eD2k Low ID and firewalled Kad. uTP remains disabled in the Android binary, so no uTP behaviour has been verified.

## Not covered / next checks

- Exhaustive interaction with every button, every preference value, and all populated-state actions. The API 36 pass covered representative controls and validation, while several actions need data (for example, pause/resume/cancel on a real queued download, client actions and chat) and were not exercised there.
- Applying potentially disruptive settings, destructive actions, or clearing user data.
- Triggering the low-storage notification; the 1 GiB threshold is implemented, but storage was not deliberately filled to test it.
- OS process reclamation and extended background operation beyond the ten-minute phone test.
- Native ARM64 execution on a physical Android 16/API 36 device; the API 36 x86_64 emulator ran the packaged ARM64 core through Android's native translation layer.
- Other OEMs, screen sizes, CPU ABIs, router configurations and port-forwarding arrangements.
- Extended transfers beyond the ten-minute screen-off check, pause/resume behaviour, and storage-exhaustion behaviour.
- Android reclaiming the app process under memory pressure during a MediaStore export; the test explicitly kills a dedicated app process to exercise abrupt process-death recovery.
- Byte progress on an actually transferring download while notification posting is denied and battery saver is enabled; no sources were transferring during that test window.
- uTP operation or a uTP stream across a live port change; uTP was disabled in this Android binary.
- Rebuilding the native core and dependencies from source in CI; CI currently packages the checked-in ARM64 executables.
- Independent installation/testing by another person.
- Automatic start at device boot is not implemented; aMule must be opened manually after reboot.
- UPnP behaviour on other routers.

## Suggested reviewer checklist

1. Run `./gradlew assembleDebug` from a clean checkout with JDK 17+ and Android SDK Platform 36 installed.
2. Install on a supported ARM64 device and confirm startup, loopback Web API access and the foreground-service notification.
3. Start a transfer, leave the app, lock the device, and check that the transfer continues as expected.
4. Stop and restart from the notification and confirm there are no orphan native processes.
5. Complete a small transfer and verify the file is available under `Downloads/aMule/Complete/`, the private Incoming original is removed, and the file remains visible in aMule's Shared Files list.
6. Try the main controls and preferences, recording any settings that are unsupported or unsafe on a phone.
7. Inspect logs and network bindings, ensuring the Web API is not exposed beyond loopback by default.
