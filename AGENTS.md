# AGENTS.md: notes for AI agents

Frigate Mobile is a native Android client (Kotlin, Jetpack Compose, Material 3) for the Frigate NVR. It talks
straight to Frigate's HTTP API over HTTPS; there is no backend. Design, the Frigate API facts the code relies on,
and the threat model are in `ARCHITECTURE.md`.

## Layout
- `core/`: pure Kotlin/JVM. `FrigateClient` (REST API), `FrigateSession` (cookie, CSRF, refresh, re-login
  interceptor), `MediaUrls`, `MjpegReader`, `LiveSockets.kt` (`MseStream` go2rtc MSE, `FrigateSocket` /ws + PTZ),
  `Models.kt`, `ServerAddress` (HTTPS-only parsing).
  Tests: `core/src/test` (unit, MockWebServer over TLS, `LiveFrigateTest` against a real Frigate).
  Recorded Frigate 0.18.3 responses: `core/src/test/resources/fixtures/0.18.3/` (copied to `app/src/test/resources/fixtures/`).
- `app/`: `data/` (AppGraph DI, AccountStore + Keystore cipher, ServerTrust pinning, Settings, AlertWorker),
  `ui/` (screens per feature, `components/Common.kt` loader/formatting, `theme/AppIcons.kt` generated).
  Live: `ui/cameras/LiveView.kt` (MSE player + MJPEG fallback), `MjpegView.kt`, `PtzControls.kt`, `LatestFrame.kt`.
  Tests: `app/src/test` (Robolectric UI tests against `FakeFrigateServer`, screenshot tests, worker test).
- `scripts/`: `dev-frigate.sh` (disposable Frigate in Docker), `emulator.sh`, `e2e-release.py`, `release.sh`, `gen_icons.py`.
- `fastlane/metadata/`: store texts and per-versionCode changelogs. `docs/screenshots/`: README images (from e2e run).

## Commands
- JDK 21 is required (`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`; JDK 25 here has no javac toolchain
  capability for AGP). SDK at `/opt/android-sdk` via `local.properties` (not committed).
- On the dev LXC wrap heavy jobs: `heavy ./gradlew …` (capped CPU/RAM).
- CI set: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (57 tests; 5 skipped: the live one without a server, screenshots unless recording).
- Live tests: `eval "$(scripts/dev-frigate.sh up)"` (Frigate `stable` on https://127.0.0.1:18971, two looping test
  cameras, admin password from its first-start log, cert fingerprint exported), rerun `:core:test`.
  `scripts/dev-frigate.sh down` removes it. The image is ~9 GB on disk: remove it when done if disk is tight.
- Screenshots: `./gradlew :app:recordRoborazziDebug --tests '*ScreenshotTest*'` → `app/build/screenshots/`.
- The dev Frigate's "front" camera has a go2rtc stream (720p/25 fps from `live.mp4`), "garage" has none, so the e2e
  run checks both the MSE path and the MJPEG fallback.
- Release check on Android 17: `AVD=npm_test scripts/emulator.sh start` (or `scripts/emulator.sh setup` once for
  its own AVD), Frigate up, `./gradlew :app:assembleRelease`, `python3 scripts/e2e-release.py` → `build/e2e/`.
  Run it before every release.
- Icons: add to `ICONS` in `scripts/gen_icons.py`, run it, use `AppIcons.X`.

## Releasing
The signing key never leaves the build machine (no CI signing secrets). Steps:
1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, update docs, commit.
2. Run the e2e check (above), then `./gradlew :app:assembleRelease` with `keystore.properties` pointing at the
   release keystore (location in the owner's notes / Claude's memory, never in the repo).
3. `scripts/release.sh X.Y.Z` tags, pushes and creates the GitHub release with `frigate-mobile-X.Y.Z.apk` + `.sha256`.
4. Run the owner's self-hosted F-Droid publish step for `frigate-mobile` (documented outside this repo).
Losing the keystore means users must uninstall to update.

## Conventions
- All network I/O goes through the connection's single OkHttpClient (`AppGraph.connect`), which carries the session
  interceptor and `ServerTrust`. Images (Coil) and video (Media3) too. Never create another client without both.
- Model fields always have defaults; parse config/stats from `JsonObject` with the null-safe helpers in `Models.kt`.
- UI coroutines launch with `Dispatchers.Main.immediate`; network calls are `suspend` and switch to IO in `core`.
- Never do blocking network work on the main thread, including `connectionPool.evictAll()` (closing TLS sends data).
- Commits use the GitHub noreply identity and end with the Co-Authored-By trailer.

## Never do
- Never allow cleartext HTTP or trust-all certificates; never disable hostname verification except via an exact pin.
- Never send the session cookie to another host; never log tokens, passwords or cookies (`SessionToken.toString` masks).
- Never commit `keystore.properties`, `local.properties`, keystores, the dev Frigate password or tokens.
- Never point tests at the owner's production Frigate; use `scripts/dev-frigate.sh`.
- Don't add `material-icons-extended`.

## Gotchas
- OkHttp with IPv6+IPv4 (e.g. `localhost`) reports a rejected certificate as `ConnectException` with the TLS
  error suppressed: sign-in checks `ServerTrust.lastRejected`, not the exception type.
- Frigate's generated cert has CN `*`; a new container generates a new cert (new fingerprint).
- The emulator's `c2.goldfish.h264.decoder` fails on Frigate recordings; the player's software-decoder retry
  handles it. Live MJPEG doesn't use MediaCodec.
- Frigate 0.18 has no HTTP passthrough to go2rtc: live video is the MSE websocket only. A `src` that isn't an
  existing go2rtc stream gets 403 (by design: go2rtc would otherwise start an ad hoc producer). Never build `src`
  from user input; take it from `live.streams`.
- PTZ has no REST API: it's `<camera>/ptz` on `/ws`. The dev Frigate has no ONVIF camera, so PTZ is covered by
  `LiveSocketsTest` and `AppUiTest.ptzHoldMovesAndReleaseStops` (fake server), not by the e2e run.
- The live setting is stored under `live_mode` (1.1.0+); the old `live_quality` key is ignored on purpose so 1.0.x
  users move to the full stream.
- Compose dialogs are separate windows: their test tags are not resource ids for UI Automator; match by text.
- Robolectric needs `WorkManagerTestInitHelper` (done in `TestApp`).
- `mipmap-anydpi-v26` must keep its `-v26` suffix.
- Launcher entries are the activity-aliases `.LauncherDefault` / `.LauncherBird` (MainActivity has no LAUNCHER
  filter). Never rename them: home-screen icons point at them. Don't use Frigate's own logo as an icon (trademark).
