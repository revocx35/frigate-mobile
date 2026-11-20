#!/usr/bin/env bash
# Disposable Frigate for live tests and the emulator e2e run. Never point the tests at a real Frigate.
#   scripts/dev-frigate.sh up     -> start Frigate (TLS + auth on 127.0.0.1:18971), print the test env vars
#   scripts/dev-frigate.sh down   -> remove the container and its data
# Two cameras loop a generated test video; the CPU detector runs at 2 fps to stay light. "front" also has a go2rtc
# stream (720p, 25 fps + PCMA audio like most IP cameras, from live.mp4) for the full-quality live view; "garage"
# has none (MJPEG fallback). "front" also has a fake speaker for two-way audio: what the app sends lands in
# $DIR/media/talk.alaw (WebRTC on 127.0.0.1:18555, reachable from the emulator as 10.0.2.2:18555).
# Inside the Android emulator the server is https://10.0.2.2:18971.
set -euo pipefail

IMAGE="${FRIGATE_IMAGE:-ghcr.io/blakeblackshear/frigate:stable}"
NAME=frigate-mobile-dev
DIR="${FRIGATE_DEV_DIR:-${TMPDIR:-/tmp}/frigate-mobile-dev}"
PORT="${FRIGATE_DEV_PORT:-18971}"
WEBRTC_PORT="${FRIGATE_DEV_WEBRTC_PORT:-18555}"
USER_NAME=admin
PASSWORD_FILE="$DIR/admin-password"

case "${1:-up}" in
  up)
    mkdir -p "$DIR/config" "$DIR/media"
    if [ ! -f "$DIR/media/test.mp4" ]; then
      ffmpeg -loglevel error -f lavfi -i "testsrc2=size=640x360:rate=10" -t 60 -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
        -g 20 "$DIR/media/test.mp4"
    fi
    if [ ! -f "$DIR/media/live.mp4" ]; then
      ffmpeg -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=8000" \
        -t 600 -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 -c:a aac -shortest "$DIR/media/live.mp4"
    fi
    cat > "$DIR/config/config.yml" <<YAML
mqtt:
  enabled: false
auth:
  enabled: true
detectors:
  cpu1:
    type: cpu
    num_threads: 1
record:
  enabled: true
  continuous:
    days: 1
  alerts:
    retain:
      days: 1
  detections:
    retain:
      days: 1
snapshots:
  enabled: true
go2rtc:
  webrtc:
    candidates:
      - 10.0.2.2:$WEBRTC_PORT
      - 127.0.0.1:$WEBRTC_PORT
  streams:
    front:
      - ffmpeg:/media/frigate/live.mp4#video=copy#audio=pcma
      # A fake camera speaker (two-way audio): go2rtc pipes the talk audio (A-law) into dd, which appends it to
      # talk.alaw right away (ffmpeg would buffer 256 KB). exec sources need GO2RTC_ALLOW_ARBITRARY_EXEC.
      - "exec:dd of=/media/frigate/talk.alaw oflag=append conv=notrunc#backchannel=1#audio=pcma/8000"
cameras:
  front:
    ffmpeg:
      inputs:
        - path: /media/frigate/test.mp4
          input_args: -re -stream_loop -1 -fflags +genpts
          roles: [detect, record]
    detect:
      width: 640
      height: 360
      fps: 2
  garage:
    ffmpeg:
      inputs:
        - path: /media/frigate/test.mp4
          input_args: -re -stream_loop -1 -fflags +genpts
          roles: [detect]
    detect:
      width: 640
      height: 360
      fps: 1
version: 0.18-0
YAML
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --shm-size=128m --cpus=1.5 --memory=2g \
      -p "127.0.0.1:$PORT:8971" -p "127.0.0.1:$WEBRTC_PORT:8555/tcp" -p "127.0.0.1:$WEBRTC_PORT:8555/udp" \
      -v "$DIR/config:/config" -v "$DIR/media:/media/frigate" \
      -e TZ=UTC -e GO2RTC_ALLOW_ARBITRARY_EXEC=true "$IMAGE" >/dev/null
    echo "waiting for Frigate..." >&2
    for _ in $(seq 1 90); do
      pw=$(docker logs "$NAME" 2>&1 | grep -oE 'Password: [^ ]+' | tail -1 | cut -d' ' -f2 || true)
      if [ -n "$pw" ]; then echo "$pw" > "$PASSWORD_FILE"; fi
      if [ -s "$PASSWORD_FILE" ] && curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$PORT/api/version" | grep -q '^401$'; then
        break
      fi
      sleep 2
    done
    [ -s "$PASSWORD_FILE" ] || { echo "no admin password in the logs; see docker logs $NAME" >&2; exit 1; }
    fp=$(echo | openssl s_client -connect "127.0.0.1:$PORT" 2>/dev/null | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2)
    echo "export FRIGATE_IT_URL=https://127.0.0.1:$PORT"
    echo "export FRIGATE_IT_USER=$USER_NAME"
    echo "export FRIGATE_IT_PASSWORD=$(cat "$PASSWORD_FILE")"
    echo "export FRIGATE_IT_CERT_SHA256=$fp"
    ;;
  down)
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$DIR"
    echo "removed"
    ;;
  *)
    echo "usage: $0 [up|down]" >&2; exit 2 ;;
esac
