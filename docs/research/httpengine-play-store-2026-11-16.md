# HttpEngine and Play Store validation, 2026-11-16

The installed original Play Store 54.1.11 (85411140) failed to start because
`android.net.http.HttpEngine$Builder` was absent. The desktop launch shortcut was
valid. The earlier successful Store trial documented in
`microg-play-gemini-validation-2026-11-06.json` used version 45.3.21 (84532130).
No earlier HttpEngine implementation was found in the locally available Git
history; the older successful trial does not establish compatibility with the
newer Store's platform HTTP provider.

Gesher now supplies the Android HttpEngine API in gesher-app.jar. Ordinary URL
requests use the existing URLConnection transport. Full-duplex HTTP/2 streams
use the AOSP OkHttp framed implementation already shipped in core-libart.jar,
including HPACK and flow control. TLS uses the existing default trust manager,
HTTPS endpoint identification and ALPN; the existing HTTP CONNECT proxy is
selected without changing system proxy settings or intercepting TLS.

The real Store exposed additional integration requirements: Android's proxy
selector can return unresolved HTTP proxy addresses, response header metadata
must retain `:status` for gRPC, and final trailers must arrive without an extra
application read. Final completion also waits for queued application callbacks.

## Validation

- The production API matched 202 public method/field descriptors in 27 classes
  against the installed Android SDK 36 reference. This checks linkage, not full
  platform behavior.
- The host HTTP suite passed 55 checks covering real loopback sockets, redirect handling, fixed and
  chunked uploads with rewind, cancellation, callback exceptions, executor
  behavior, immutable snapshots and IPv6 authorities. A separate JVM tests an
  actually installed global URLStreamHandlerFactory.
- Independent QA reproduced and corrected global URL factory recursion and
  double-bracketed IPv6 authorities. It re-ran the host/API checks and four ART
  scenarios and found no remaining blockers in the documented supported scope.
- Real Gesher ART tests loaded all five matching boot image spaces and exercised
  ordinary HTTP plus an independent local HTTP/2 wire peer. The peer verifies
  true duplex data, flushing, retained status metadata, gRPC trailers, completion
  without an additional read, and gzip content decoding.
- A separate real ART HTTPS request negotiated HTTP/2 with a public Google
  endpoint through the user's existing loopback CONNECT proxy, including the
  unresolved proxy-address form. No TLS verification was disabled.
- The installed runtime was updated together with its matching app boot image
  extension. The original primary and framework boot images were retained. Local
  rollback copies of each replaced runtime artifact were kept outside Git.
- After the metadata fix, a real installed Store launch completed in 4.842 s.
  Windows UI inspection showed the loaded catalog, app/game recommendations and
  category navigation. This establishes startup and catalog loading; purchases,
  DRM and installation of a new app were not tested in this trial.

The initial missing-class failure, intermediate retry/server screens, and
successful final catalog were distinguished during testing; mere process
survival was not counted as catalog success. User account data and Store APKs
were not reset or downgraded. This note contains no account identifiers,
credentials, request headers, response bodies or proprietary APKs.

The host tests are included in CI and the ART smoke tests in Windows track 94.
QUIC, Brotli, engine-private HTTP caches and additional public-key pinning remain
outside this adapter's supported scope; see [network.md](../network.md).

## API and transport references

- [Android 16 Connectivity public API](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-16.0.0_r4/framework/api/current.txt)
- [Android 16 AOSP OkHttp framed transport](https://android.googlesource.com/platform/external/okhttp/+/refs/tags/android-16.0.0_r4/repackaged/okhttp/src/main/java/com/android/okhttp/internal/framed/)
