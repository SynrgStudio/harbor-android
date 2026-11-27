"""A pretend DLNA TV on the PC, to try "Play on a TV" on the emulator without a real TV.

    python scripts/fake-tv.py

Debug builds also look for a TV at http://10.0.2.2:7320/desc.xml (the emulator can't do SSDP
through its network), so "Fake TV (PC)" shows up in the player's TV list. It takes the commands a
real TV gets (SetAVTransportURI, Play, Pause, Seek, Stop, GetPositionInfo, GetTransportInfo),
prints them, and fetches the first megabyte of the video like a TV would, through `adb forward`
(the emulator's own address isn't reachable from the PC).
"""
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import unescape

PORT = 7320
DURATION = 734  # seconds; Tears of Steel
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5590")


def find_adb():
    """adb from the SDK in local.properties (sdk.dir), else ANDROID_HOME, else the PATH."""
    roots = []
    props = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "local.properties")
    if os.path.exists(props):
        for line in open(props, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                roots.append(line.split("=", 1)[1].strip().replace("\\\\", "\\").replace("\\:", ":"))
    roots.append(os.environ.get("ANDROID_HOME", ""))
    for root in roots:
        for name in ("adb.exe", "adb"):
            path = os.path.join(root, "platform-tools", name)
            if root and os.path.exists(path):
                return path
    return "adb"


ADB = find_adb()

DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>%s</friendlyName><UDN>uuid:fake-tv-pc</UDN>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/ctl</controlURL></service></serviceList>
</device></root>"""

# The name the app lists; FAKE_TV_NAME="Living room TV" for pictures.
DESCRIPTION = DESCRIPTION.replace("%s", os.environ.get("FAKE_TV_NAME", "Fake TV (PC)"))

state = {"uri": None, "state": "NO_MEDIA_PRESENT", "pos": 0.0, "since": None}
lock = threading.RLock()


def position():
    with lock:
        pos = state["pos"]
        if state["state"] == "PLAYING" and state["since"]:
            pos += time.time() - state["since"]
        return min(pos, DURATION)


def clock(s):
    s = int(s)
    return "%d:%02d:%02d" % (s // 3600, (s % 3600) // 60, s % 60)


def fetch(uri):
    """Reads the first megabyte, like a TV starting playback."""
    m = re.match(r"http://([\d.]+):(\d+)(/.*)", uri)
    if m:
        port = m.group(2)
        subprocess.run([ADB, "-s", SERIAL, "forward", "tcp:" + port, "tcp:" + port], capture_output=True)
        uri = "http://127.0.0.1:%s%s" % (port, m.group(3))
    try:
        req = urllib.request.Request(uri, headers={"Range": "bytes=0-1048575"})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
            print("  fetched %d bytes, HTTP %d, %s, %s" % (len(data), r.status, r.headers.get("Content-Type"), r.headers.get("Content-Range")))
    except Exception as e:
        print("  fetch failed:", e)


class Handler(BaseHTTPRequestHandler):
    def reply(self, body, ctype="text/xml"):
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/desc.xml":
            self.reply(DESCRIPTION)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
        action = self.headers.get("SOAPACTION", "").strip('"').split("#")[-1]
        out = ""
        with lock:
            if action == "SetAVTransportURI":
                uri = unescape(re.search(r"<CurrentURI>(.*?)</CurrentURI>", body, re.S).group(1))
                meta = unescape(re.search(r"<CurrentURIMetaData>(.*?)</CurrentURIMetaData>", body, re.S).group(1))
                title = re.search(r"<dc:title>(.*?)</dc:title>", meta)
                caption = re.search(r"CaptionInfoEx[^>]*>(.*?)<", meta)
                state.update(uri=uri, state="STOPPED", pos=0.0, since=None)
                print("SetAVTransportURI", uri, "| title:", title and unescape(title.group(1)), "| subtitle:", caption and unescape(caption.group(1)))
                threading.Thread(target=fetch, args=(uri,), daemon=True).start()
            elif action == "Play":
                state.update(state="PLAYING", since=time.time())
                print("Play")
            elif action == "Pause":
                state.update(pos=position(), state="PAUSED_PLAYBACK", since=None)
                print("Pause at", clock(state["pos"]))
            elif action == "Stop":
                state.update(state="STOPPED", since=None)
                print("Stop")
            elif action == "Seek":
                target = re.search(r"<Target>(.*?)</Target>", body).group(1)
                h, m, s = [float(x) for x in target.split(":")]
                state.update(pos=h * 3600 + m * 60 + s, since=time.time() if state["state"] == "PLAYING" else None)
                print("Seek", target)
            elif action == "GetPositionInfo":
                out = "<RelTime>%s</RelTime><TrackDuration>%s</TrackDuration>" % (clock(position()), clock(DURATION))
            elif action == "GetTransportInfo":
                out = "<CurrentTransportState>%s</CurrentTransportState>" % state["state"]
        self.reply('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                   '<u:%sResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">%s</u:%sResponse></s:Body></s:Envelope>' % (action, out, action))

    def log_message(self, fmt, *args):
        pass


print("Fake TV on port %d (the emulator reaches it at 10.0.2.2:%d)" % (PORT, PORT))
sys.stdout.flush()
ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
