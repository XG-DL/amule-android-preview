#!/usr/bin/env bash
set -euo pipefail

# Rebuild the pinned ARM64 Android dependencies and then the aMule executables.
# Sources and build products go under native-core/.android-deps by default.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
deps_root="${ANDROID_DEPS_DIR:-$repo_root/native-core/.android-deps}"
downloads="$deps_root/downloads"
sources="$deps_root/sources"
prefix="$deps_root/install"
builds="$deps_root/build"
api=29
expected_ndk_version=28.2.13676358

required=(ANDROID_NDK_HOME AMULE_SOURCE_DIR)
for name in "${required[@]}"; do
	if [[ -z "${!name:-}" ]]; then
		printf 'Set %s before running this script.\n' "$name" >&2
		exit 2
	fi
done

for tool in curl cmake make perl tar unzip sha256sum cut find grep cp; do
	if ! command -v "$tool" >/dev/null 2>&1; then
		printf 'Required build tool not found: %s\n' "$tool" >&2
		exit 2
	fi
done

if [[ ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]]; then
	printf 'Android NDK toolchain not found under %s\n' "$ANDROID_NDK_HOME" >&2
	exit 2
fi
if ! grep -Fqx "Pkg.Revision = $expected_ndk_version" "$ANDROID_NDK_HOME/source.properties"; then
	printf 'Expected Android NDK %s under %s.\n' "$expected_ndk_version" "$ANDROID_NDK_HOME" >&2
	exit 2
fi

host_tag=linux-x86_64
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
for tool in aarch64-linux-android${api}-clang aarch64-linux-android${api}-clang++ llvm-ar llvm-ranlib; do
	if [[ ! -x "$toolchain/$tool" ]]; then
		printf 'NDK tool not found: %s\n' "$toolchain/$tool" >&2
		exit 2
	fi
done

mkdir -p "$downloads" "$sources" "$builds" "$prefix"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$(dirname "$(dirname "$ANDROID_NDK_HOME")")}"

fetch() {
	local file="$1" url="$2" expected="$3" actual candidate
	if [[ ! -f "$downloads/$file" ]]; then
		candidate="$downloads/$file.part"
		printf 'Downloading %s\n' "$file"
		curl --fail --location --retry 3 "$url" --output "$candidate"
		actual="$(sha256sum "$candidate" | cut -d ' ' -f 1)"
		if [[ "$actual" != "$expected" ]]; then
			printf 'SHA-256 mismatch for %s\nExpected: %s\nActual:   %s\n' "$file" "$expected" "$actual" >&2
			exit 2
		fi
		mv "$candidate" "$downloads/$file"
	fi
	actual="$(sha256sum "$downloads/$file" | cut -d ' ' -f 1)"
	if [[ "$actual" != "$expected" ]]; then
		printf 'SHA-256 mismatch for %s\nExpected: %s\nActual:   %s\n' "$file" "$expected" "$actual" >&2
		exit 2
	fi
}

unpack() {
	local archive="$1" destination="$2" member="$3"
	if [[ -d "$destination" ]]; then
		if [[ ! -e "$destination/$member" ]] && ! find "$destination" -mindepth 1 -maxdepth 2 -name "$member" -print -quit | grep -q .; then
			printf 'Refusing to reuse incomplete source directory: %s\nRemove it and rerun.\n' "$destination" >&2
			exit 2
		fi
		return
	fi
	mkdir -p "$destination"
	case "$archive" in
		*.zip) unzip -q "$downloads/$archive" -d "$destination" ;;
		*.tar.gz) tar -xzf "$downloads/$archive" -C "$destination" --strip-components=1 ;;
		*.tar.xz) tar -xJf "$downloads/$archive" -C "$destination" --strip-components=1 ;;
		*.tar.bz2) tar -xjf "$downloads/$archive" -C "$destination" --strip-components=1 ;;
		*) printf 'Unsupported source archive: %s\n' "$archive" >&2; exit 2 ;;
	esac
	if [[ ! -e "$destination/$member" ]] && ! find "$destination" -mindepth 1 -maxdepth 2 -name "$member" -print -quit | grep -q .; then
		printf 'Expected source file %s missing after unpacking %s\n' "$member" "$archive" >&2
		exit 2
	fi
}

# SHA-256 values pin the exact release archives; do not replace them with moving branches.
fetch boost_1_83_0.tar.bz2 \
	https://archives.boost.io/release/1.83.0/source/boost_1_83_0.tar.bz2 \
	6478edfe2f3305127cffe8caf73ea0176c53769f4bf1585be237eb30798c3b8e
fetch cryptopp-CRYPTOPP_8_9_0.zip \
	https://github.com/weidai11/cryptopp/archive/refs/tags/CRYPTOPP_8_9_0.zip \
	b885403cb13d490bebe90f25fad7150b88857f7acf3bd8b9ca1cec04c9ec8a51
fetch curl-8.22.0.tar.xz \
	https://curl.se/download/curl-8.22.0.tar.xz \
	f7ef3ae8a22e521f289803fe93543eb64c329b58aa73a9e224dfd915a2a5f4f7
fetch openssl-3.5.9.tar.gz \
	https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz \
	603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
fetch wxWidgets-3.3.3.tar.bz2 \
	https://github.com/wxWidgets/wxWidgets/releases/download/v3.3.3/wxWidgets-3.3.3.tar.bz2 \
	81b09d6dd9f1ed9301f8c55a968a488d0491f264dc2bab19a7e407ac67009482
fetch pupnp-release-22.1.8.tar.gz \
	https://codeload.github.com/pupnp/pupnp/tar.gz/refs/tags/release-22.1.8 \
	abc191bd8f083c9ae73c93d9fa6ba051d54e086430da426c97ff7d989d581fec

unpack boost_1_83_0.tar.bz2 "$sources/boost_1_83_0" boost/version.hpp
unpack cryptopp-CRYPTOPP_8_9_0.zip "$sources/cryptopp-8.9.0" GNUmakefile-cross
unpack curl-8.22.0.tar.xz "$sources/curl-8.22.0" CMakeLists.txt
unpack openssl-3.5.9.tar.gz "$sources/openssl-3.5.9" Configure
unpack wxWidgets-3.3.3.tar.bz2 "$sources/wxWidgets-3.3.3" configure
unpack pupnp-release-22.1.8.tar.gz "$sources/pupnp-22.1.8" CMakeLists.txt

openssl_src="$sources/openssl-3.5.9"
openssl_prefix="$prefix/openssl"
openssl_build="$builds/openssl"
mkdir -p "$openssl_build"
printf 'Building OpenSSL for Android API %s...\n' "$api"
if ! (
	cd "$openssl_build"
	export PATH="$toolchain:$PATH"
	"$openssl_src/Configure" android-arm64 \
		-D__ANDROID_API__="$api" \
		--prefix="$openssl_prefix" \
		--openssldir="$openssl_prefix/ssl" \
		no-shared no-tests no-module no-docs
	make --silent --jobs="${BUILD_JOBS:-2}"
	make --silent install_sw
) >"$builds/openssl.log" 2>&1; then
	tail -n 60 "$builds/openssl.log" >&2
	exit 1
fi

cryptopp_makefile="$(find "$sources/cryptopp-8.9.0" -mindepth 1 -maxdepth 2 -name GNUmakefile-cross -print -quit)"
cryptopp_src="$(dirname "$cryptopp_makefile")"
(
	cd "$cryptopp_src"
	export ANDROID_API="$api" ANDROID_CPU=arm64-v8a
	set +u
	source TestScripts/setenv-android.sh "$api" arm64-v8a
	set -u
	make -f GNUmakefile-cross --silent --jobs="${BUILD_JOBS:-2}" libcryptopp.a
)
mkdir -p "$prefix/include/cryptopp" "$prefix/lib"
cp "$cryptopp_src"/*.h "$prefix/include/cryptopp/"
cp "$cryptopp_src/libcryptopp.a" "$prefix/lib/libcryptopp.a"

curl_build="$builds/curl"
curl_prefix="$prefix/curl"
cmake -S "$sources/curl-8.22.0" -B "$curl_build" \
	-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI=arm64-v8a \
	-DANDROID_PLATFORM="android-$api" \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_INSTALL_PREFIX="$curl_prefix" \
	-DBUILD_SHARED_LIBS=OFF \
	-DBUILD_CURL_EXE=OFF \
	-DBUILD_LIBCURL_DOCS=OFF \
	-DBUILD_TESTING=OFF \
	-DCURL_USE_OPENSSL=ON \
	-DCURL_USE_LIBSSH2=OFF \
	-DCURL_USE_LIBPSL=OFF \
	-DOPENSSL_ROOT_DIR="$openssl_prefix" \
	-DOPENSSL_INCLUDE_DIR="$openssl_prefix/include" \
	-DOPENSSL_SSL_LIBRARY="$openssl_prefix/lib/libssl.a" \
	-DOPENSSL_CRYPTO_LIBRARY="$openssl_prefix/lib/libcrypto.a" \
	-DZLIB_LIBRARY_RELEASE="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/sysroot/usr/lib/aarch64-linux-android/$api/libz.so" \
	-DZLIB_INCLUDE_DIR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/sysroot/usr/include" \
	-DCURL_DISABLE_DICT=ON -DCURL_DISABLE_FILE=ON -DCURL_DISABLE_FTP=ON \
	-DCURL_DISABLE_GOPHER=ON -DCURL_DISABLE_IMAP=ON -DCURL_DISABLE_LDAP=ON \
	-DCURL_DISABLE_LDAPS=ON -DCURL_DISABLE_MQTT=ON -DCURL_DISABLE_POP3=ON \
	-DCURL_DISABLE_RTSP=ON -DCURL_DISABLE_SMTP=ON -DCURL_DISABLE_TELNET=ON \
	-DCURL_DISABLE_TFTP=ON -DCURL_DISABLE_WEBSOCKETS=ON
cmake --build "$curl_build" --parallel "${BUILD_JOBS:-2}"
cmake --install "$curl_build"

wx_src="$sources/wxWidgets-3.3.3"
wx_prefix="$prefix/wx"
(
	cd "$wx_src"
	PATH="$toolchain:$PATH" \
	LIBCURL_CFLAGS="-I$curl_prefix/include" \
	LIBCURL_LIBS="-L$curl_prefix/lib -lcurl -L$openssl_prefix/lib -lssl -lcrypto -lz -ldl" \
	./configure --host=aarch64-linux-android \
		--prefix="$wx_prefix" \
		--disable-gui --disable-shared --disable-tests --disable-sys-libs \
		--with-regex=builtin --with-zlib=sys --with-libcurl \
		--enable-webrequest --enable-utf8 --enable-utf8only \
		CC="$toolchain/aarch64-linux-android${api}-clang" \
		CXX="$toolchain/aarch64-linux-android${api}-clang++" \
		AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib"
	make --silent --jobs="${BUILD_JOBS:-2}"
	make install
)

pupnp_src="$sources/pupnp-22.1.8"
pupnp_prefix="$prefix/pupnp"
pupnp_build="$builds/pupnp"
cmake -S "$pupnp_src" -B "$pupnp_build" \
	-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI=arm64-v8a \
	-DANDROID_PLATFORM="android-$api" \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_C_FLAGS=-DINCLUDE_DEVICE_APIS \
	-DCMAKE_INSTALL_PREFIX="$pupnp_prefix" \
	-DCMAKE_INSTALL_LIBDIR=lib \
	-DUPNP_BUILD_SHARED=OFF \
	-DUPNP_BUILD_STATIC=ON \
	-DUPNP_BUILD_SAMPLES=OFF \
	-DUPNP_ENABLE_TESTING=OFF \
	-DUPNP_ENABLE_TESTING_INTEGRATION=OFF \
	-DUPNP_ENABLE_IPV6=OFF \
	-DUPNP_ENABLE_OPEN_SSL=OFF \
	-DUPNP_ENABLE_BACKTRACE=OFF \
	-DUPNP_ENABLE_WEBSERVER=ON
cmake --build "$pupnp_build" --parallel "${BUILD_JOBS:-2}"
cmake --install "$pupnp_build"

if [[ -d "$prefix/include/boost" ]]; then
	printf 'Boost headers already installed at %s/include/boost; leaving them in place.\n' "$prefix" >&2
else
	cp -a "$sources/boost_1_83_0/boost" "$prefix/include/boost"
fi
boost_cmake_dir="$prefix/lib/cmake/Boost-1.83.0"
mkdir -p "$boost_cmake_dir"
cp "$repo_root/native-core/boost-cmake/BoostConfig.cmake" \
	"$repo_root/native-core/boost-cmake/BoostConfigVersion.cmake" \
	"$boost_cmake_dir/"

AMULE_BUILD_DIR="${AMULE_BUILD_DIR:-$AMULE_SOURCE_DIR/build-android}" \
AMULE_SOURCE_DIR="$AMULE_SOURCE_DIR" \
ANDROID_NDK_HOME="$ANDROID_NDK_HOME" \
PUPNP_ROOT="$pupnp_prefix" \
WX_CONFIG="$wx_prefix/bin/wx-config" \
BOOST_INCLUDE_DIR="$prefix/include" \
BOOST_CMAKE_DIR="$boost_cmake_dir" \
CRYPTOPP_INCLUDE_DIR="$prefix/include" \
CRYPTOPP_LIBRARY="$prefix/lib/libcryptopp.a" \
"$repo_root/native-core/build-core.sh"
