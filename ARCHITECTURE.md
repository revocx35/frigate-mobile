# Architecture

Frigate Mobile is a native Android client (Kotlin, Jetpack Compose, Material 3) for the Frigate NVR. It has no
backend: the phone talks to the user's Frigate server over HTTPS.

## 1. Components

```
core/   (pure Kotlin/JVM, unit-tested without Android)
  ServerAddress    parse/normalize the typed address; HTTPS only; LAN detection
  FrigateSession   session cookie, CSRF header, cookie refresh, silent re-login (OkHttp interceptor)
  FrigateClient    the REST API (config, stats, review, events, recordings, camera switches)
  MediaUrls        URLs of images (latest frame, thumbnails, snapshots) and video (MJPEG, HLS VOD, MP4)
  MjpegReader      splits Frigate's multipart MJPEG stream into JPEG frames
  FlacDecoder      FLAC frames -> 16-bit PCM (go2rtc's camera audio; Android's decoder rejects it)
  LiveSockets      MseStream (go2rtc MSE over websocket: codec negotiation, fMP4 chunks) and FrigateSocket
                   (Frigate's /ws, used for PTZ commands)
  Models           lenient models; config/stats parsed from JsonObject (Frigate adds fields every release)

app/
  data/AppGraph       manual DI: stores, trust, the one OkHttpClient per sign-in, Coil ImageLoader, sign-in/out
  data/AccountStore   the account (SharedPreferences, no backup); token + password encrypted (Secrets.kt)
  data/ServerTrust    X509 trust manager + hostname verifier: system CAs, plus per-host pinned certificates
  data/SettingsStore  theme, live quality, boxes, notifications, last notified review time
  data/AlertWorker    WorkManager job: polls /api/review for new alerts, posts notifications
  data/AppIcon        icon choice: enables one launcher <activity-alias> (.LauncherDefault/.LauncherBird); custom
                      image stored in filesDir/logo (cropped square PNG, 20 MB input cap) + pinned shortcut
  ui/AppRoot          login vs. main shell, Navigation Compose routes
  ui/cameras          grid (live: LiveView on the camera's last live.streams entry = sub stream, muted, cropped;
                      LatestFrame fallback: latest.jpg polled every 2 s through the session client, last frame kept
                      per camera in Connection.frames), camera screen (LiveView: MSE player with MJPEG fallback, stream
                      chips, mute; PtzControls; switches; activity)
  ui/review           review queue + review item (HLS playback, tracked objects, snapshots)
  ui/recordings       per-camera day/hour list
  ui/player           ExoPlayer (Media3) through the session's OkHttp client; software-decoder retry
  ui/system           stats, settings, account, sign out
```

## 2. Data flow

- Every network request (API JSON, Coil images, ExoPlayer HLS segments, the MJPEG stream) goes through **one
  OkHttpClient per sign-in** that carries `FrigateSession.interceptor` and `ServerTrust`. So the cookie, CSRF
  header, refresh and pinning apply uniformly, and there's no second code path to get wrong.
- Screens load with `rememberLoader {}` (`ui/components/Common.kt`): spinner, error with retry, stale data kept on
  a failed refresh. No ViewModels: the activity handles configuration changes itself (`configChanges` in the
  manifest), so composition state survives rotation.
- Session end: when Frigate rejects the cookie and there's no remembered password, the interceptor clears the token;
  `AppGraph` disconnects and the sign-in screen shows "Your Frigate session has ended".

## 3. Frigate API facts the code relies on (verified on 0.18.3, source read from the image)

- Port 8971 = nginx with TLS (self-signed by default) and `auth_request` on everything except `/api/login`.
  Port 5000 is unauthenticated plain HTTP: the app refuses it.
- `POST /api/login {"user","password"}` → 200 + `Set-Cookie: <auth.cookie_name>=<JWT>`; 401 wrong; 429 rate limit;
  404 when auth is disabled. The cookie name is configurable, so the app takes it from the login response.
- The JWT is refreshed (new `Set-Cookie`) when it is within `auth.refresh_time` of expiry, **only for the cookie,
  not for `Authorization: Bearer`**, which is why the app sends a `Cookie` header.
- State-changing requests need `X-CSRF-TOKEN` when an `Origin` header is present; the app always sends it.
- `GET /api/review` (cameras, severity, reviewed=0/1, before/after, limit), `GET /api/review/{id}`,
  `GET /api/review/summary` (`last24Hours`), `POST /api/reviews/viewed {"ids": [...], "reviewed": true}`.
- Review thumbnails: `thumb_path` is a file path (`/media/frigate/clips/review/…webp`) served under `/clips/`.
- `GET /api/event_ids?ids=a,b` (fallback: `GET /api/events/{id}` each). `sub_label` is a string (old) or
  `[name, score]` (new); scores live in `data.top_score` in newer releases.
- go2rtc is reachable only as websockets: `GET /live/mse/api/ws?src=<go2rtc stream>` (MSE) and
  `/live/webrtc/api/ws`. There is no `/api/go2rtc/api/...` HTTP passthrough (no stream.mp4/m3u8). nginx's auth
  check (`$go2rtc_stream_proxy`) answers **403** when `src` isn't an existing go2rtc stream or the role may not see
  the camera. MSE protocol: client sends `{"type":"mse","value":"<codec list>"}`, go2rtc answers
  `{"type":"mse","value":"video/mp4; codecs=..."}` (or `{"type":"error",...}`), then binary fMP4 (init + fragments).
- `cameras.X.live.streams` maps labels (e.g. `fhd`, `sd`) to go2rtc stream names, in config order; Frigate fills in
  `{name: name}` when unset, even if go2rtc has no such stream. Such a name passes nginx's check (it belongs to the
  camera) and go2rtc answers `{"type":"error","value":"mse: stream not found"}` (seen on 0.18.3) → MJPEG fallback.
- PTZ: `GET /api/{camera}/ptz/info` → `{name, features: [pt, zoom, pt-r, zoom-r, zoom-a, focus, pt-r-fov], presets:
  [names]}`, or `{}` without ONVIF (`onvif.host` empty). Commands only over the websocket `/ws`:
  `{"topic":"<camera>/ptz","payload":"MOVE_UP|MOVE_DOWN|MOVE_LEFT|MOVE_RIGHT|ZOOM_IN|ZOOM_OUT|STOP|preset_<name>"}`;
  continuous moves run until `STOP`. Allowed for admins and roles that may see the camera. No REST endpoint.
  Tapo-class cameras ignore a ContinuousMove stopped within ~0.5 s, so a tap holds the move for at least 700 ms.
- `GET /api/{camera}` = MJPEG (`multipart/x-mixed-replace`, parts **without Content-Length**; `fps`, `height`,
  pre-0.15 `h`, `bbox`). `GET /api/{camera}/latest.jpg?height=`.
- `GET /vod/{camera}/start/{s}/end/{e}/index.m3u8` = HLS (fMP4 segments, relative URLs) → seekable playback.
- `GET /api/{camera}/recordings/summary?timezone=` → days with hours in that time zone.
- `PUT /api/camera/{camera}/set/{feature} {"value":"ON"|"OFF"}` (admin) exists since 0.17; older releases → 404 →
  "needs a newer Frigate". Frigate applies switches asynchronously: `/api/config` shows them a moment later.
- `/api/config` reflects runtime switches (`detect.enabled`, `record.enabled`, `enabled_in_config`, …).
- `/api/stats`: `service.version`, `service.latest_version`, `service.storage` (MB), `detectors.*.inference_speed`,
  `cpu_usages["frigate.full_system"]`, `gpu_usages`, `cameras.*.camera_fps`.

## 4. Key decisions

- **Live view = go2rtc MSE in ExoPlayer, MJPEG as fallback.** Frigate's MJPEG is re-encoded from the detect stream
  (detect resolution, at most detect fps), which looked poor. go2rtc's MSE websocket carries the camera's own H.264/
  H.265 + audio as fragmented MP4; a custom Media3 `DataSource` feeds those chunks to `FragmentedMp4Extractor`, all
  through the session's OkHttp client (cookie + pinning), no WebView. The stream is negotiated before the player is
  built, so a refused stream (403/no go2rtc stream/codec mismatch) falls back to MJPEG immediately with the reason
  shown. Latency stays low: small buffers and playback at 1.15–1.5x while more than 1.5 s is buffered. Reconnects
  make a new socket and player (the stream can't be resumed); decoder failures retry once with software decoders.
  HEVC is offered only when the phone has an HEVC decoder.
- **Audio only when unmuted, decoded by the app.** Most IP cameras send PCMA audio; go2rtc re-packs it for MSE as
  16-bit mono 8 kHz FLAC with VERBATIM subframes (1024-sample frames) and a STREAMINFO claiming 32768-sample blocks
  (checked on the owner's Tapo C212, 2026-11-20). Android's `c2.android.flac.decoder` rejects it (emulator:
  `CodecException 0x80000000`; phones: black picture, since the audio track gates playback). So:
  - muted playback asks go2rtc for video codecs only (an audio track is also the playback clock);
  - unmuting reconnects with audio codecs, and FLAC is played by `ui/cameras/FlacAudioRenderer.kt` (a Media3
    `DecoderAudioRenderer`, put before the MediaCodec renderers by `LiveRenderersFactory`) using `core/FlacDecoder`
    (all subframe types; tested sample-exact against ffmpeg);
  - safety nets: an audio renderer error, no first frame within 10 s, or a position frozen for 3 s while playing
    drops back to video only ("No sound for this camera").
- **PTZ over Frigate's websocket** like the web UI (there's no REST endpoint): one `/ws` connection per camera
  screen, opened when the camera reports PTZ features, closed when leaving. Press-and-hold sends a move, release
  sends `STOP`.
- **Live grid** (setting `live_grid`, default on, only with the Full live mode): every card on screen plays its
  camera's last `live.streams` entry (by convention the sub stream) muted. LazyVerticalGrid composes only visible
  cards, so off-screen cameras use no data or decoders. go2rtc remuxes, so the server cost is small. Cameras without a
  go2rtc stream show polled still frames instead of MJPEG (cheaper for Frigate).
- **Grid frames polled directly**, not through Coil: each card fetches `latest.jpg` every 2 s while visible and
  resumed, swaps the bitmap only after decoding (no flicker), so no image cache can pin an old frame.
- **Recordings = HLS VOD** (Media3 ExoPlayer + OkHttp data source): instant start and seeking. If the hardware
  H.264 decoder fails mid-stream (seen on the emulator's goldfish decoder; some phones reject camera streams too),
  the player restarts once with software decoders first, at the same position.
- **Notifications by polling** (WorkManager, 15 min): Frigate's own push uses Web Push for browsers. No foreground
  service, nothing runs while notifications are off.
- **App icon.** Android can't set an arbitrary image as an app's launcher icon. Built-in icons switch via
  activity-aliases (one enabled at a time; enable the new one before disabling the old). A custom image is picked
  with the system photo picker (no storage permission), stored privately, shown in the app and offered as a pinned
  shortcut (inset into the adaptive-icon safe zone). The bird is original artwork (`docs/logo/frigate-bird.svg`),
  deliberately not Frigate's trademarked logo.
- **Single account.** Most households run one Frigate; it keeps trust/session handling simple.

## 5. Threat model

Assets: the Frigate password, the session JWT (full access to cameras and recordings for its role), camera images.

| Threat | Mitigation |
| --- | --- |
| Network attacker reads credentials/video | HTTPS only (`cleartextTrafficPermitted=false`, `http://` refused in the parser). |
| MITM with a forged certificate | System CAs, or the exact leaf certificate the user pinned after comparing its SHA-256 fingerprint. Pins are per host; hostname check is only skipped for that exact pinned certificate (Frigate's generated cert doesn't name the LAN IP). |
| Token leaked to another host (redirects, image URLs) | The interceptor adds the cookie only when scheme, host and port match the server. `followSslRedirects(false)`. |
| Stolen phone storage / backups | Token and optional password AES-GCM-encrypted with a non-exportable Android Keystore key; `allowBackup=false` and data-extraction rules exclude everything. |
| Password kept longer than wanted | Stored only if "stay signed in" is checked; sign-out deletes token, password and cached images. Frigate JWTs can't be revoked server-side; they expire after `auth.session_length`. |
| Brute force via the app | Frigate's server-side rate limit (`auth.failed_login_rate_limit`); the app shows 429 as "too many attempts" and never retries a failed login in a loop (one silent re-login per 401). |
| Lock-screen exposure | Notification channel and notifications are `VISIBILITY_PRIVATE` with a public version without thumbnail. |
| Live stream / PTZ abuse | Both websockets go through the session client (cookie only to the server, pinned TLS). Stream names come from Frigate's own config; Frigate checks role access to the stream and camera. Sockets exist only while a camera screen is resumed. |
| CSRF | Not applicable to a native client; Frigate's `X-CSRF-TOKEN` header is sent on writes as Frigate requires. |

Tests for these paths: `core/.../SessionTest.kt` (cookie scope, CSRF, re-login, refresh, wrong/rate-limited
login, custom cookie name), `app/.../AppUiTest.kt` (pinning dialog, refused certificate, cleartext refused,
expired session with/without password, sign-out forgets secrets), `AccountStoreTest.kt` (encryption at rest), `core/.../LiveSocketsTest.kt` (cookie on websocket upgrades, refused
stream → Forbidden, PTZ topic/payloads).

## 6. Platform constraints

- minSdk 26, target/compile 37. Android 17 needs `ACCESS_LOCAL_NETWORK` for LAN servers (`ui/LocalNetworkAccess.kt`);
  Robolectric runs SDK 35, so only the emulator e2e run exercises it.
- Android 13+ needs `POST_NOTIFICATIONS` (asked when notifications are switched on).
- Release builds use R8 (`-dontobfuscate`); `scripts/e2e-release.py` checks the minified APK on the emulator.
