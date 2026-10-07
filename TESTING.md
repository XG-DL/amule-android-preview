# Manual testing record

This records manual checks of the Android preview build and the areas still awaiting review.

## Device and build

- Device: Pixel 10 Pro XL, ARM64, GrapheneOS.
- Device OS/API: Android 17, API 37.
- App label: aMule Android Preview, version 0.1.2.
- Android minimum configured by the app: API 33.
- APK built locally as a debug build, including newly rebuilt ARM64 `amuled` and `amuleapi` binaries with UPnP enabled.
- `./gradlew assembleDebug` completed successfully on the Linux development host with JDK 21 and Android SDK Platform 36.
- Native core cross-build completed with the pinned Linux x86-64 / NDK 28.2.13676358 toolchain and pupnp 22.1.8.
- Test date: 2026-10-07.

## Exercised

- App startup, foreground service, local aMule web UI login and display.
- Main areas visited: Networks, Searches, Downloads, Shared files, Clients, Messages, Statistics, Preferences and About.
- Preference subsections visited: Web UI, General, Connection, Directories, Servers, Files, Security, Proxy, Filters, Remote Controls, Online Signature and Advanced.
- Safe dialogs opened and dismissed: Web UI reset confirmation, Add Friend, Add category and Clear aMule log.
- Empty Add server and Kad bootstrap submissions, which displayed their validation messages.
- Notification Stop action, then reopening the app to start the service again.
- A completed 5.1 MiB download and export to Android Downloads.
- Mobile layout in short landscape orientation and Kad chart labels.
- Recent Android log checked for FATAL and ANR entries; none were found in the checked log window.

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

- Exhaustive interaction with every button, dialog and preference control.
- Applying potentially disruptive settings, destructive actions, or clearing user data.
- Reboot, OS process reclamation, extended background operation, battery saver and notification denial flows.
- Other Android versions, OEMs, screen sizes, CPU ABIs, router configurations and port-forwarding arrangements.
- Sustained transfer, multiple simultaneous transfers, pause/resume, and sharing after export.
- uTP operation or a uTP stream across a live port change; uTP was disabled in this Android binary.
- Reproducible build from a clean checkout and independent installation/testing by another person.
- The latest move-and-share flow, including old-copy migration, aMule's shared-directory registration and the refreshed Shared Files list; it has built successfully but has not yet been re-tested on the phone.
- UPnP behaviour on other routers.

## Suggested reviewer checklist

1. Run `./gradlew assembleDebug` from a clean checkout with JDK 17+ and Android SDK Platform 36 installed.
2. Install on a supported ARM64 device and confirm startup, loopback Web API access and the foreground-service notification.
3. Start a transfer, leave the app, lock the device, and check that the transfer continues as expected.
4. Stop and restart from the notification and confirm there are no orphan native processes.
5. Complete a small transfer and verify the file is available under `Downloads/aMule/Complete/`, the private Incoming original is removed, and the file remains visible in aMule's Shared Files list.
6. Try the main controls and preferences, recording any settings that are unsupported or unsafe on a phone.
7. Inspect logs and network bindings, ensuring the Web API is not exposed beyond loopback by default.
