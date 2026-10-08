# Working on the Android app

The repository has two app interfaces:

- `main` builds the WebView interface.
- `native-android-ui-experiment` builds the native Android interface and includes the Web UI in its menu.

Both branches use the same application ID, Android service, completed-download exporter, native aMule binaries and core patch. Their APKs replace one another when installed on the same device.

## Keep the shared code together

Make service, exporter, native-core, build and CI changes on `main`, then merge `main` into `native-android-ui-experiment`. The activity and README are interface-specific, so review their merge conflicts against the intended interface. After the merge, compare the shared paths:

```sh
git diff main..native-android-ui-experiment -- \
  .github/workflows/android.yml \
  scripts/ci-emulator.sh scripts/android-smoke-test.sh \
  app/src/main/java/uk/xgdl/amuleprobe/AmuleService.java \
  app/src/main/java/uk/xgdl/amuleprobe/CompletedDownloadExporter.java \
  native-core
```

The comparison should be empty unless an interface branch is deliberately testing a shared change before it reaches `main`.

## Build and check

Build either branch with `./gradlew assembleDebug`. CI builds the packaged APK, runs exporter instrumentation tests on an Android 16/API 36 emulator, and then installs the packaged APK to check its daemon, local API and foreground service. See [TESTING.md](TESTING.md) for the physical-device review record and remaining coverage.

The ARM64 native executables are checked in. To rebuild them from the pinned aMule source and Android patch, follow [native-core/README.md](native-core/README.md); an APK build alone does not rebuild them.
