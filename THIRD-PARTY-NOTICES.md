# Third-party notices

The aMule core is licensed under GPL-2.0-or-later; see [LICENSE.md](LICENSE.md). The packaged executables were built with the following libraries; some are statically linked, while zlib is provided by Android as a shared system library. The listed licence texts are included under [`licenses/native/`](licenses/native/). Android system libraries are not bundled as app files.

| Component in native executables | Version | Licence | Notice |
|---|---:|---|---|
| wxWidgets | 3.3.3 | wxWindows Library Licence 3.1 | [wxWidgets](licenses/native/wxWindows-Library-Licence.txt) |
| Boost headers | 1.83.0 | Boost Software License 1.0 | [Boost](licenses/native/Boost-Software-License-1.0.txt) |
| Crypto++ | 8.9.0 | Boost Software License 1.0; see its included notice for additional attributions | [Crypto++](licenses/native/CryptoPP-License.txt) |
| libcurl | 8.22.0 | curl license (MIT-style) | [curl](licenses/native/curl-COPYING.txt) |
| OpenSSL | 3.5.9 | Apache License 2.0 | [OpenSSL](licenses/native/OpenSSL-LICENSE.txt) |
| Expat (via wxWidgets) | bundled with wxWidgets 3.3.3 | MIT | [Expat](licenses/native/Expat-COPYING.txt) |
| zlib | 1.3.0.1, Android NDK 28.2.13676358 | zlib license | [zlib notice](licenses/native/zlib-LICENSE.txt) |

The responsive web UI includes Preact and htm. Their licence notices are retained beside the vendored files in `app/src/main/assets/webui/js/vendor/`. This notice list describes the libraries identified in the included ARM64 executables; it is not a general list of optional aMule build features.
