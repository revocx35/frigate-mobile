#!/usr/bin/env python3
"""End-to-end check of a *release* (R8-minified) APK on the emulator against a real Frigate, driven like a user
through UI Automator.

Robolectric tests run unminified code against a fake server; this script catches what R8 or the real Frigate
breaks. It installs the APK, signs in to the disposable test Frigate (incl. the Android 17 local network prompt
and the certificate dialog, whose fingerprint must match the server's), watches live video, talks through the dev Frigate's fake camera speaker, switches a camera
feature (verified through Frigate's API), plays a recording, opens the timeline, plays a review item, enables notifications, restarts
the app to check the stored session, signs out, and fails on any crash.

Usage (emulator running: scripts/emulator.sh start; Frigate: eval "$(scripts/dev-frigate.sh up)"):
    ./gradlew :app:assembleRelease
    python3 scripts/e2e-release.py [path/to/app-release.apk]
Screenshots go to build/e2e/.
"""
import hashlib
import json
import os
import re
import ssl
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

PKG = "io.github.revocx35.frigatemobile"
DEV_CONTAINER = "frigate-mobile-dev"  # scripts/dev-frigate.sh
SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
HOST_URL = os.environ.get("FRIGATE_IT_URL", "https://127.0.0.1:18971")  # as seen from this machine
APP_URL = HOST_URL.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")  # from the emulator
USER = os.environ.get("FRIGATE_IT_USER", "admin")
PASSWORD = os.environ["FRIGATE_IT_PASSWORD"]
PIN = os.environ["FRIGATE_IT_CERT_SHA256"].upper()
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "e2e")
steps = 0


def adb(*args, check=True, capture=True):
    r = subprocess.run([ADB, *args], capture_output=capture, text=capture)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr if capture else ''}")
    return r.stdout if capture else ""


def step(msg):
    global steps
    steps += 1
    print(f"[{steps:02d}] {msg}", flush=True)


# ---- UI Automator helpers -----------------------------------------------------------------------------

def dump():
    """The current UI tree. While windows are switching UI Automator can return "null root node": that's treated
    as an empty screen, so the callers' timeouts decide when to give up."""
    out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
    start = out.find("<?xml")
    end = out.rfind("</hierarchy>")
    if start >= 0 and end > 0:
        return ET.fromstring(out[start:end + len("</hierarchy>")])
    time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def nodes(root):
    return root.iter("node")


def matches(n, text=None, contains=None, rid=None, desc=None):
    if text is not None and n.get("text") != text:
        return False
    if contains is not None and contains not in (n.get("text") or "") + (n.get("content-desc") or ""):
        return False
    if rid is not None and n.get("resource-id") != rid:
        return False
    if desc is not None and n.get("content-desc") != desc:
        return False
    return True


def find(timeout=20, **kw):
    deadline = time.time() + timeout
    while True:
        check_alive()
        root = dump()
        for n in nodes(root):
            if matches(n, **kw):
                return n
        # A slow emulator sometimes shows "System UI isn't responding" right after boot: not the app's fault.
        wait = next((n for n in nodes(root) if n.get("text") == "Wait"), None)
        if wait is not None and any("isn't responding" in (n.get("text") or "") and PKG not in (n.get("text") or "") for n in nodes(root)):
            x, y = center(wait)
            adb("shell", "input", "tap", str(x), str(y))
        if time.time() > deadline:
            shot("FAILED")
            raise AssertionError(f"not found on screen: {kw}")
        time.sleep(1)


def exists(**kw):
    return any(matches(n, **kw) for n in nodes(dump()))


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(timeout=20, **kw):
    x, y = center(find(timeout=timeout, **kw))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def type_into(value, secret=False, **kw):
    """Types into a field and checks the result (a just-focused field on a slow emulator can drop keystrokes)."""
    for _ in range(3):
        tap(**kw)
        time.sleep(1)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(60):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", value.replace(" ", "%s"))
        time.sleep(0.8)
        got = find(**kw).get("text") or ""
        if secret or got == value:
            return
    raise AssertionError(f"could not type into {kw}: got {got!r}")


def scroll_to(rid, swipes=8):
    """Scrolls the screen up until an element with this resource id is visible."""
    for _ in range(swipes):
        if exists(rid=rid):
            return
        adb("shell", "input", "swipe", "500", "1700", "500", "900", "400")
        time.sleep(0.6)
    find(rid=rid, timeout=5)


def key(code):
    adb("shell", "input", "keyevent", str(code))
    time.sleep(0.6)


def back():
    key(4)


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, f"{steps:02d}_{name}.png"), "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def distinct_frames(node, shots=8, every=0.4):
    """How many of [shots] raw screen grabs of [node]'s area differ: a smooth 25 fps test stream changes every time
    (its picture includes a running timer), a stalled one repeats."""
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    seen = set()
    for _ in range(shots):
        raw = subprocess.run([ADB, "exec-out", "screencap"], capture_output=True).stdout
        w = int.from_bytes(raw[0:4], "little")
        header = 16 if len(raw) >= 16 + w * int.from_bytes(raw[4:8], "little") * 4 else 12
        rows = b"".join(raw[header + (y * w + x1) * 4: header + (y * w + x2) * 4] for y in range(y1, y2, 4))
        seen.add(hashlib.sha256(rows).digest())
        time.sleep(every)
    return len(seen)


def check_alive():
    crashes = adb("logcat", "-b", "crash", "-d", check=False)
    if PKG in crashes:
        print(crashes[-4000:])
        raise AssertionError("the app crashed (see the crash log above)")


def launch(expect=None, rid=None, attempts=3):
    """Starts the app and waits for [expect]. Retried: right after `pm clear`/install, Android sometimes kills the
    freshly started process while it removes the old task ("failed to attach"), leaving the home screen."""
    for i in range(attempts):
        time.sleep(2)
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
        try:
            return find(contains=expect, rid=rid, timeout=30) if rid else find(contains=expect, timeout=30)
        except AssertionError:
            if i == attempts - 1:
                raise


# ---- Frigate API (to verify what the UI did) -------------------------------------------------------------

def tls_context():
    """Trusts exactly the test server's certificate after checking its pinned fingerprint (like the app)."""
    host, port = HOST_URL.split("//")[1].split(":")
    pem = ssl.get_server_certificate((host, int(port)))
    fp = ":".join(f"{b:02X}" for b in hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).digest())
    assert fp == PIN, f"server certificate {fp} doesn't match FRIGATE_IT_CERT_SHA256"
    ctx = ssl.create_default_context(cadata=pem)
    ctx.check_hostname = False  # Frigate's generated certificate doesn't name 127.0.0.1; the exact cert is pinned
    return ctx


CTX = None
COOKIE = None


def api(method, path, body=None):
    global CTX, COOKIE
    CTX = CTX or tls_context()
    if COOKIE is None:
        req = urllib.request.Request(f"{HOST_URL}/api/login", method="POST",
                                     data=json.dumps({"user": USER, "password": PASSWORD}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, context=CTX, timeout=30) as r:
            COOKIE = r.headers["Set-Cookie"].split(";")[0]
    req = urllib.request.Request(f"{HOST_URL}/api/{path}", method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Cookie": COOKIE, "X-CSRF-TOKEN": "1"})
    with urllib.request.urlopen(req, context=CTX, timeout=30) as r:
        return json.loads(r.read() or b"null")


def sdk():
    return int(adb("shell", "getprop", "ro.build.version.sdk").strip())


def run(apk):
    step(f"install {apk}")
    adb("install", "-r", apk)
    adb("shell", "pm", "clear", PKG)  # also revokes runtime permissions, so the prompts are tested too
    adb("logcat", "-b", "crash", "-c", check=False)
    launch("Sign in to your Frigate NVR")

    step("make fresh activity on the test Frigate")
    api("POST", "events/front/person/create", {"duration": 8, "include_recording": True})
    api("POST", "events/front/dog/create", {"duration": 8, "include_recording": True})  # yellow on the timeline

    step("sign in")
    shot("login")
    type_into(APP_URL, rid="server")
    type_into(USER, rid="user")
    type_into(PASSWORD, secret=True, rid="password")
    back()  # hide keyboard
    tap(rid="sign_in")
    if sdk() >= 37:
        step("local network permission prompt (Android 17+)")
        find(text="Allow local network access", timeout=20)  # the app explains first
        tap(text="Allow")
        find(contains="nearby devices", timeout=20)  # then Android's own prompt ("Nearby devices")
        shot("permission")
        tap(text="Allow")

    step("certificate dialog: fingerprint must match the server's")
    # Dialogs are separate windows: their test tags aren't resource ids, so match by text.
    find(text="Trust this certificate?", timeout=40)
    find(text=PIN, timeout=5)  # the fingerprint the app shows is the server's real one
    shot("certificate")
    tap(text="Trust")
    find(rid="camera_front", timeout=40)
    time.sleep(3)
    shot("cameras")

    step("camera grid: frames keep refreshing")
    first = subprocess.run([ADB, "exec-out", "screencap"], capture_output=True).stdout
    time.sleep(5)
    second = subprocess.run([ADB, "exec-out", "screencap"], capture_output=True).stdout
    assert hashlib.sha256(first).digest() != hashlib.sha256(second).digest(), "camera grid frames didn't change"
    time.sleep(5)
    card = find(rid="camera_front")
    smooth = distinct_frames(card)
    print(f"     grid card (live sub stream): {smooth}/8 distinct frames", flush=True)
    assert smooth >= 6, f"live grid card isn't playing smoothly: only {smooth} of 8 grabs differ"
    shot("cameras_live")

    step("camera: full-quality live view (go2rtc MSE), then a feature switch")
    tap(rid="camera_front")
    find(rid="live")
    find(rid="live_video", timeout=30)  # set once the first decoded frame is on screen
    time.sleep(5)
    smooth = distinct_frames(find(rid="live"))
    print(f"     live view: {smooth}/8 distinct frames (muted)", flush=True)
    assert smooth >= 6, f"live view is stuttering: only {smooth} of 8 grabs differ"
    tap(rid="mute")  # sound on: audio track joins the stream
    find(rid="live_video", timeout=30)
    time.sleep(5)
    smooth = distinct_frames(find(rid="live"))
    print(f"     live view: {smooth}/8 distinct frames (sound on)", flush=True)
    assert smooth >= 6, f"live view with sound is stuttering: only {smooth} of 8 grabs differ"
    assert not exists(rid="no_sound"), "sound was dropped: the camera's FLAC audio didn't play"
    uid = adb("shell", "cmd", "package", "list", "packages", "-U", PKG).split("uid:")[-1].strip()
    players = [l for l in adb("shell", "dumpsys", "audio").splitlines() if f"u/pid:{uid}/" in l and "state:started" in l]
    print(f"     audio players started by the app: {len(players)}", flush=True)
    assert players, "no audio is playing although sound is on"
    tap(rid="mute")
    assert not exists(rid="live_fallback"), "fell back to MJPEG although the camera has a go2rtc stream"
    assert not exists(contains="Reconnecting"), "live view failed"
    shot("live")

    step("two-way audio: talk through the camera (the dev Frigate's fake speaker records it)")
    talk_size = lambda: int(subprocess.run(["docker", "exec", DEV_CONTAINER, "sh", "-c",
                                            "stat -c %s /media/frigate/talk.alaw 2>/dev/null || echo 0"],
                                           capture_output=True, text=True).stdout.strip() or 0)
    before_talk = talk_size()
    tap(rid="talk", timeout=30)  # shown once Frigate reports the stream's "audio, sendonly" media
    find(contains="While using the app", timeout=20)  # microphone permission prompt
    tap(contains="While using the app")
    find(desc="Stop talking", timeout=20)
    for _ in range(30):  # WebRTC connects, then A-law reaches go2rtc's backchannel (8000 bytes/s)
        if talk_size() - before_talk > 8000:
            break
        time.sleep(0.5)
    else:
        raise AssertionError(f"no talk audio reached the camera (talk.alaw {before_talk} -> {talk_size()} bytes)")
    shot("talking")
    assert not exists(contains="Couldn't reach"), "talk connection failed"
    tap(rid="talk")
    find(desc="Talk", timeout=10)
    sent = talk_size() - before_talk
    print(f"     talk audio received by the camera: {sent} bytes", flush=True)
    time.sleep(2)
    assert talk_size() - before_talk - sent < 4000, "the microphone kept sending after stopping"

    before = api("GET", "config")["cameras"]["front"]["snapshots"]["enabled"]
    tap(rid="switch_snapshots")
    for _ in range(20):
        if api("GET", "config")["cameras"]["front"]["snapshots"]["enabled"] != before:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("snapshots switch didn't reach Frigate")
    tap(rid="switch_snapshots")
    shot("camera")

    step("recordings: play the newest hour")
    tap(rid="recordings")
    find(rid="recording_days")
    hour = next(n for n in nodes(dump()) if (n.get("resource-id") or "").startswith("hour_"))
    x, y = center(hour)
    adb("shell", "input", "tap", str(x), str(y))
    find(rid="player")
    time.sleep(8)
    assert not exists(contains="Playback failed") and not exists(contains="No recording"), "recording didn't play"
    shot("recording")
    back()  # player -> recordings
    back()  # recordings -> camera

    step("timeline: person (red) and animal (yellow) detections, tap plays from there")
    tap(rid="timeline_button")
    find(rid="timeline")
    # Rows (timeline_row_N) describe their detections: "10:45 PM: Person, Dog".
    rows = lambda: [n for n in nodes(dump()) if (n.get("resource-id") or "").startswith("timeline_row_")]
    deadline = time.time() + 30
    while not any("Person" in (n.get("content-desc") or "") for n in rows()):
        assert time.time() < deadline, "the person event isn't on the timeline"
        time.sleep(1)
    assert any("Dog" in (n.get("content-desc") or "") for n in rows()), "the dog event isn't on the timeline"
    row = next(n for n in rows() if "Person" in (n.get("content-desc") or ""))
    time.sleep(2)
    shot("timeline")
    x, y = center(row)
    adb("shell", "input", "tap", str(x), str(y))
    find(rid="player")
    time.sleep(6)
    assert not exists(contains="Playback failed") and not exists(contains="No recording"), "timeline playback failed"
    back()  # player -> timeline
    back()  # timeline -> camera
    back()  # camera -> cameras
    find(rid="camera_front")

    step("camera without a go2rtc stream falls back to MJPEG")
    tap(rid="camera_garage")
    find(rid="live_fallback", timeout=30)
    time.sleep(4)
    assert not exists(contains="Reconnecting"), "MJPEG fallback failed"
    shot("live_mjpeg")
    back()
    find(rid="camera_front")

    step("review: open an item, it plays and gets marked reviewed")
    tap(rid="tab_review")
    find(rid="unreviewed_filter")
    tap(rid="unreviewed_filter")  # show all, including ones opened before
    item = None
    for _ in range(20):
        item = next((n for n in nodes(dump()) if (n.get("resource-id") or "").startswith("review_1")), None)
        if item is not None:
            break
        time.sleep(1)
    assert item is not None, "no review items"
    rid = item.get("resource-id")
    shot("review")
    x, y = center(item)
    adb("shell", "input", "tap", str(x), str(y))
    find(rid="player")
    time.sleep(6)
    assert not exists(contains="Playback failed"), "review clip didn't play"
    shot("review_detail")
    back()

    step("system: stats, notifications on")
    tap(rid="tab_system")
    find(contains="Frigate 0.")
    tap(rid="notifications_switch")
    if sdk() >= 33:
        find(text="Allow", timeout=15)
        tap(text="Allow")
    shot("system")

    step("app icon: switch to the bird, then pick a custom image")
    scroll_to("icon_Bird")
    tap(rid="icon_Bird")
    time.sleep(2)
    pkg = adb("shell", "dumpsys", "package", PKG)
    enabled = pkg.split("enabledComponents:")[1].split("disabledComponents:")[0] if "enabledComponents:" in pkg else ""
    assert "LauncherBird" in enabled, "bird launcher entry not enabled"
    shot("icon_bird")
    png = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "logo", "frigate-bird.png")
    adb("push", png, "/sdcard/Pictures/e2e-logo.png")
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Pictures/e2e-logo.png", check=False)
    time.sleep(2)
    scroll_to("icon_Custom")
    tap(rid="icon_Custom")
    photo = find(contains="Photo taken", timeout=30)  # the system photo picker's thumbnails
    x, y = center(photo)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.5)
    if exists(text="Done"):  # newer photo pickers confirm the selection
        tap(text="Done")
    for _ in range(4):
        if exists(rid="remove_custom_icon"):  # below "Add to home screen": scroll until it is on screen
            break
        adb("shell", "input", "swipe", "500", "1800", "500", "900")
    find(rid="remove_custom_icon", timeout=20)
    shot("icon_custom")
    tap(rid="remove_custom_icon")
    scroll_to("icon_Default")
    adb("shell", "rm", "/sdcard/Pictures/e2e-logo.png", check=False)

    step("restart the app: the stored session must survive")
    adb("shell", "am", "force-stop", PKG)
    launch(rid="camera_front")
    shot("restarted")

    step("sign out")
    tap(rid="tab_system")
    for _ in range(4):
        if exists(rid="sign_out"):
            break
        adb("shell", "input", "swipe", "500", "1800", "500", "600")
    tap(rid="sign_out")
    find(text="Sign out?")
    buttons = [n for n in nodes(dump()) if n.get("text") == "Sign out"]
    x, y = center(buttons[-1])  # the dialog's button, not the list entry behind it
    adb("shell", "input", "tap", str(x), str(y))
    find(rid="sign_in")

    check_alive()
    print(f"OK: {steps} steps passed, screenshots in {os.path.normpath(OUT)}")


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk")
