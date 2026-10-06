# Native core provenance

The files `app/src/main/jniLibs/arm64-v8a/libamuled.so` and `libamuleapi.so` are Android ARM64 PIE executables (despite the `.so` extension, used so Android packages and extracts them as native libraries). The app launches them as separate processes; it does not load them through JNI.

## Source used

- Upstream: <https://github.com/amule-org/amule>
- Base commit: `f1d4b19ed01d07c6509d2d751219e4a1405c661c`
- Local Android/Web UI changes: [`android-core.patch`](android-core.patch)
- aMule source licence: [GPL-2.0-or-later](../LICENSE.md)

Run these commands from the Android project root to reconstruct the modified source tree:

```sh
export AMULE_ANDROID_PROJECT="$PWD"
git clone https://github.com/amule-org/amule.git amule-core
git -C amule-core checkout f1d4b19ed01d07c6509d2d751219e4a1405c661c
git -C amule-core apply "$AMULE_ANDROID_PROJECT/native-core/android-core.patch"
```

The patch is an ordinary `git diff` against that exact commit. It is not a commit in the upstream aMule repository.

## Build configuration used for the included executables

The binaries were built on Linux x86-64 with Android NDK `28.2.13676358`, Android API 33, ABI `arm64-v8a`, CMake `3.28.3`, and a Release CMake build. The packaged targets are `amuled` and `amuleapi`. IPv6, UPnP, IP geolocation, uTP, QUIC and native-language catalogs were disabled. The executables depend dynamically only on Android system libraries (`libc`, `libdl`, `liblog`, `libm`, and `libz`). The remaining native dependencies were statically linked.

The native build used these dependency versions:

| Dependency | Version |
|---|---:|
| wxWidgets | 3.3.3 |
| Boost headers | 1.83.0 |
| Crypto++ | 8.9.0 |
| libcurl | 8.22.0 |
| OpenSSL | 3.5.9 |
| zlib | Android NDK 28.2.13676358 system library |

The dependency source archives are not vendored. [`build-android-deps.sh`](build-android-deps.sh) downloads pinned release archives, verifies their SHA-256 digests, builds the Android-target dependencies, then calls [`build-core.sh`](build-core.sh) to build aMule. It is intended for Linux x86-64 with Android NDK `28.2.13676358` and CMake 3.28.3 or newer. It does not install system packages or build the Android wrapper.

Dependencies are built for Android API 29 and the aMule executables for API 33. Boost is headers-only in this build. Source archives, extracted trees, and build products are kept in `native-core/.android-deps/`, which is ignored by Git. Set `ANDROID_DEPS_DIR` to use another work directory or `BUILD_JOBS` to change the default parallelism of two jobs.

After reconstructing the modified aMule source tree above, set the source and NDK paths, then run the complete native build from the Android project root:

```sh
export AMULE_SOURCE_DIR="$HOME/src/amule-android-core"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/28.2.13676358"
./native-core/build-android-deps.sh
```

The script prints the `amuled` and `amuleapi` output paths. It does not overwrite the binaries packaged in the app. Review the outputs, copy them to `app/src/main/jniLibs/arm64-v8a/libamuled.so` and `libamuleapi.so`, then run `./gradlew assembleDebug`. The default aMule build directory is `$AMULE_SOURCE_DIR/build-android`; set `AMULE_BUILD_DIR` to use another location.

For aMule-only iteration after the dependencies are built, use [`build-core.sh`](build-core.sh) directly and set `AMULE_SOURCE_DIR`, `ANDROID_NDK_HOME`, `WX_CONFIG`, `BOOST_INCLUDE_DIR`, `CRYPTOPP_INCLUDE_DIR` and `CRYPTOPP_LIBRARY` as shown in that script. The scripts pin source versions and archive digests. Compiler and host differences mean this does not promise bit-for-bit identical executables.

The identifiers below describe the executables currently included in the APK. A fresh native build writes new executables to the aMule build directory and does not overwrite these packaged files; compiler and linker versions can therefore produce different identifiers.

## Packaged binary identifiers

| File | ELF Build ID | SHA-256 |
|---|---|---|
| `libamuled.so` | `c7c40bcede46f47574328f667f6da084d129790a` | `6d7246b6d4f96c9dbbd2adeb9e5bfd9ee47f4b3ceebb542a9a8f1b19669fd51d` |
| `libamuleapi.so` | `18250f25677f761289951f3de3a1fecfe87d49b2` | `eacb370a89bdbe7422c4e7b5a458e681d360f3c37256c83c8163eb848f3748d1` |
