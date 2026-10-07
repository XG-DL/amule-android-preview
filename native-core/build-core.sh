#!/usr/bin/env bash
set -euo pipefail

required=(
  AMULE_SOURCE_DIR
  ANDROID_NDK_HOME
  PUPNP_ROOT
  WX_CONFIG
  BOOST_INCLUDE_DIR
  CRYPTOPP_INCLUDE_DIR
  CRYPTOPP_LIBRARY
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'Set %s before running this script.\n' "$name" >&2
    exit 2
  fi
done

for path in \
  "$AMULE_SOURCE_DIR/CMakeLists.txt" \
  "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  "$PUPNP_ROOT/lib/cmake/UPNP/UPNPConfig.cmake" \
  "$WX_CONFIG" \
  "$BOOST_INCLUDE_DIR/boost/version.hpp" \
  "$CRYPTOPP_INCLUDE_DIR/cryptopp/config.h" \
  "$CRYPTOPP_LIBRARY"; do
  if [[ ! -e "$path" ]]; then
    printf 'Required build input not found: %s\n' "$path" >&2
    exit 2
  fi
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${AMULE_BUILD_DIR:-$AMULE_SOURCE_DIR/build-android}"
expected_commit=f1d4b19ed01d07c6509d2d751219e4a1405c661c
actual_commit="$(git -C "$AMULE_SOURCE_DIR" rev-parse HEAD)"
if [[ "$actual_commit" != "$expected_commit" ]]; then
  printf 'Expected aMule commit %s, found %s.\n' "$expected_commit" "$actual_commit" >&2
  exit 2
fi
if ! git -C "$AMULE_SOURCE_DIR" apply --reverse --check "$repo_root/native-core/android-core.patch"; then
  printf 'The Android patch is not applied cleanly to %s.\n' "$AMULE_SOURCE_DIR" >&2
  exit 2
fi

cmake_extra_args=()
if [[ -n "${BOOST_CMAKE_DIR:-}" ]]; then
  cmake_extra_args+=("-DBoost_DIR=$BOOST_CMAKE_DIR")
fi
android_system_libs="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/33"
if [[ -d "$android_system_libs" ]]; then
  cmake_extra_args+=("-DCMAKE_LIBRARY_PATH=$android_system_libs")
fi

cmake -S "$AMULE_SOURCE_DIR" -B "$build_dir" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DCMAKE_MODULE_PATH= \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-33 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_MONOLITHIC=OFF \
  -DBUILD_DAEMON=ON \
  -DBUILD_AMULEAPI=ON \
  -DENABLE_IPV6=OFF \
  -DENABLE_UPNP=ON \
  -DENABLE_IP2COUNTRY=OFF \
  -DENABLE_UTP=OFF \
  -DENABLE_QUIC=OFF \
  -DENABLE_NLS=OFF \
  -DwxWidgets_CONFIG_EXECUTABLE="$WX_CONFIG" \
  -DUPNP_DIR="$PUPNP_ROOT/lib/cmake/UPNP" \
  -DCRYPTOPP_INCLUDE_DIR="$CRYPTOPP_INCLUDE_DIR" \
  -DCRYPTOPP_CONFIG_FILE="$CRYPTOPP_INCLUDE_DIR/cryptopp/config.h" \
  -DCRYPTOPP_VERSION=890 \
  -DCRYPTOPP_LIBRARY="$CRYPTOPP_LIBRARY" \
  -DCMAKE_CXX_FLAGS="-isystem $BOOST_INCLUDE_DIR" \
  "${cmake_extra_args[@]}"

cmake --build "$build_dir" --target amuled amuleapi --parallel "${BUILD_JOBS:-2}"

printf '\nBuilt native executables:\n%s\n%s\n' \
  "$build_dir/src/amuled" \
  "$build_dir/src/webapi/amuleapi"
printf '\nCopy them to the Android package only after reviewing the build output:\n%s\n%s\n' \
  "$repo_root/app/src/main/jniLibs/arm64-v8a/libamuled.so" \
  "$repo_root/app/src/main/jniLibs/arm64-v8a/libamuleapi.so"
