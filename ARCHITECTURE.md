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
  AudioLeveler     automatic gain for that PCM (cameras record very quietly)
  LiveSockets      MseStream (go2rtc MSE over websocket: codec negotiation, fMP4 chunks), WebRtcSignaling (go2rtc
                   WebRTC offer/answer/candidates, for two-way audio) and FrigateSocket (Frigate's /ws, PTZ)
  Timeline         timeline math: label -> person/animal/other, motion + tracked objects -> bars, RecordingMap
                   (VOD player position <-> wall-clock time across recording gaps), TimelineScale (zoom steps)
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
                      chips, mute; Talkback: two-way audio; PtzControls; switches; activity)
  ui/review           review queue + review item (HLS playback, tracked objects, snapshots)
  ui/recordings       per-camera day/hour list
  ui/timeline         per-camera activity timeline under an inline recording player: yellow playhead line, pinch zoom
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
- Two-way audio: `GET /api/go2rtc/streams/{name}` (any role that may see it) proxies go2rtc's `/api/streams` with
  `microphone`, so producers list an `audio, sendonly, PCMA/8000` media when the camera has a speaker (the web UI's
  check). Talk = WebRTC over `/live/webrtc/api/ws?src=<stream>`: client `{"type":"webrtc/offer","value":sdp}`, go2rtc
  `webrtc/answer` then `webrtc/candidate` (candidate line, m-line 0); the client may trickle `webrtc/candidate` too.
  Media goes to go2rtc's WebRTC port 8555, which must be published and in `go2rtc.webrtc.candidates` (Frigate's
  default is only `stun:8555`, i.e. the public address). go2rtc forwards the RTP payload to the camera without
  transcoding, so the offer must contain the camera's codec (PCMA/PCMU). Backchannel sources: `tapo://<cloud
  password>@ip` (TP-Link account password, not the camera account), `dvrip://user:pw@ip:34567?backchannel=1`
  (XMeye), RTSP with ONVIF backchannel. A go2rtc stream lists several sources; the first that has a media wins.
- PTZ: `GET /api/{camera}/ptz/info` → `{name, features: [pt, zoom, pt-r, zoom-r, zoom-a, focus, pt-r-fov], presets:
  [names]}`, or `{}` without ONVIF (`onvif.host` empty). Commands only over the websocket `/ws`:
  `{"topic":"<camera>/ptz","payload":"MOVE_UP|MOVE_DOWN|MOVE_LEFT|MOVE_RIGHT|ZOOM_IN|ZOOM_OUT|STOP|preset_<name>"}`;
  continuous moves run until `STOP`. Allowed for admins and roles that may see the camera. No REST endpoint.
  Tapo-class cameras ignore a ContinuousMove stopped within ~0.5 s, so a tap holds the move for at least 700 ms.
- `GET /api/{camera}` = MJPEG (`multipart/x-mixed-replace`, parts **without Content-Length**; `fps`, `height`,
  pre-0.15 `h`, `bbox`). `GET /api/{camera}/latest.jpg?height=`.
- `GET /vod/{camera}/start/{s}/end/{e}/index.m3u8` = HLS (fMP4 segments, relative URLs) → seekable playback.
- `GET /api/{camera}/recordings/summary?timezone=` → days with hours in that time zone.
- `GET /api/review/activity/motion?cameras=&after=&before=&scale=` (0.14+) → `[{start_time, motion, camera}]`, one
  record per `scale` seconds from the recording segments; motion is 0–100 and Frigate negates every other camera's
  values for its own graph, so only the magnitude counts. `GET /api/{camera}/recordings?after=&before=` → segments
  `[{start_time, end_time, duration, ...}]` overlapping the range.
- VOD playlists (`/vod/{camera}/start/{s}/end/{e}`) concatenate the segments in the window, clipping the first and
  last and **skipping gaps**: player position 0 is the first recorded moment, not `s`. `RecordingMap` rebuilds that
  layout from the segments to convert positions to times and back. `GET /api/events?cameras=&after=&before=` filters on the
  start time, so the timeline asks from an hour earlier to catch objects still on screen.
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
- **Two-way audio over WebRTC, like the web UI.** Frigate exposes no other way to reach go2rtc's backchannel (no HTTP
  passthrough to go2rtc's `/api/ffmpeg`, and an RTSP publish would become a producer, not a backchannel). The app
  uses Google's libwebrtc (prebuilt `io.github.webrtc-sdk:android`) with one send-only audio transceiver; the mic is
  captured at 8 kHz with hardware echo cancel/noise suppression and encoded as G.711 for the camera. Signaling uses
  the session client (cookie, pinning); media is DTLS-SRTP straight to go2rtc. No third-party ICE servers; off the
  LAN it works only where 8555 is reachable (VPN). Tap to talk, tap to stop; talking stops when the screen goes to
  the background or is left. A talk button only appears for a camera with a backchannel. It is read from the config
  first (`go2rtc.streams`: a `tapo://` or `backchannel=1` source in one of the camera's live streams, or in another
  stream whose source has the same camera host, e.g. a talk-only stream; only the host and the flag are kept, never
  credentials). Cameras without one are probed through `/api/go2rtc/streams/{name}` (ONVIF RTSP backchannel), with a
  10 s limit. Reason: probing a live stream that also holds `tapo://` makes go2rtc start the Tapo video session and
  open a second Tapo session for the backchannel, which a Tapo C212 never answers, so the probe hung for minutes and
  no button appeared. A talk-only `tapo://` stream uses a single session (talk only, no video).
- **Automatic gain for camera sound.** Measured on the owner's cameras (2026-11-20): Tapo C212 -60 dB mean / -35 dB
  peak, XMeye -57/-32, Tapo doorbell -72/-63. `AudioLeveler` raises quiet sound towards half of full scale (cap 20x,
  ~26 dB; peak envelope with instant attack and 1.5 s release; gain falls in ~5 ms, rises over ~1 s) and leaves loud
  sound at 1x. It runs in `FlacAudioRenderer` (G.711 cameras); AAC/Opus streams use MediaCodec unchanged.
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
- **Timeline = motion + tracked objects** (like Frigate's own review timeline): one Canvas (no list) drawn from a
  view state `top` (time at the top edge) + `spp` (seconds per pixel), so zoom is continuous: pinch or +/- between
  one 30 s motion record per 40 dp and two days on screen. Bars aggregate motion to a step that keeps >= 4 dp between
  them (square root of the share of the busiest record loaded, so quiet motion still shows), colored by the most
  important object in that step: person red > animal yellow > other blue; a translucent band marks each object's
  time. Tick/label steps follow the zoom (`TimelineScale`). Activity loads in 6 h pages as the view nears the oldest
  loaded time (stops after 4 empty pages or 31 days); the newest hour refreshes every minute while in view.
- **Playhead + inline player.** The player above the timeline loads one clock hour of recording (up to now) as HLS
  VOD and the yellow line is its position mapped through `RecordingMap`. It follows playback (the view scrolls along
  when the line would leave the screen, unless the user touched the timeline in the last 3 s), continues into the next
  hour at the end of one, and seeks when the line or its time handle is dragged (seeks within the loaded hour while
  dragging, loads another hour on release) or the timeline is tapped. Times without recording jump to the next
  recorded moment (at the live edge: the newest one). Gesture handlers read state through `rememberUpdatedState`
  (they outlive recompositions; a stale player reference would seek a released ExoPlayer).
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
| Microphone misuse / eavesdropping on talk | RECORD_AUDIO asked on first tap; the mic is open only while the talk button is active and the screen is in the foreground (closed on ON_STOP/leave). Talk audio is DTLS-SRTP to the user's own go2rtc only; signaling is the authenticated, pinned websocket; Frigate checks the role may see the stream. |
| CSRF | Not applicable to a native client; Frigate's `X-CSRF-TOKEN` header is sent on writes as Frigate requires. |

Tests for these paths: `core/.../SessionTest.kt` (cookie scope, CSRF, re-login, refresh, wrong/rate-limited
login, custom cookie name), `app/.../AppUiTest.kt` (pinning dialog, refused certificate, cleartext refused,
expired session with/without password, sign-out forgets secrets), `AccountStoreTest.kt` (encryption at rest), `core/.../LiveSocketsTest.kt` (cookie on websocket upgrades, refused
stream → Forbidden, PTZ topic/payloads, WebRTC signaling incl. refused stream and go2rtc errors).

## 6. Platform constraints

- minSdk 26, target/compile 37. Android 17 needs `ACCESS_LOCAL_NETWORK` for LAN servers (`ui/LocalNetworkAccess.kt`);
  Robolectric runs SDK 35, so only the emulator e2e run exercises it.
- `RECORD_AUDIO` (runtime, on first talk) and `MODIFY_AUDIO_SETTINGS` for two-way audio. libwebrtc is native: no
  32-bit x86 build is shipped (the talk button then says it isn't supported).
- Android 13+ needs `POST_NOTIFICATIONS` (asked when notifications are switched on).
- Release builds use R8 (`-dontobfuscate`); `scripts/e2e-release.py` checks the minified APK on the emulator.
