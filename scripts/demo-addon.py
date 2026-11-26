"""A tiny Stremio addon with the Blender Foundation's open movies (CC BY), for screenshots.

    python scripts/demo-addon.py
Then add http://10.0.2.2:7311/manifest.json in the app's Settings (on the emulator; 10.0.2.2 is the PC).
It serves Tears of Steel and Sintel streams and Tears of Steel subtitles from download.blender.org.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOS = "https://download.blender.org/demo/movies/ToS/"
SINTEL = "https://download.blender.org/durian/movies/"

MANIFEST = {
    "id": "demo.openmovies",
    "version": "1.0.0",
    "name": "Open Movies",
    "description": "The Blender Foundation's open movies (CC BY).",
    "resources": ["stream", "subtitles"],
    "types": ["movie"],
    "idPrefixes": ["tt"],
    "catalogs": [],
}

STREAMS = {
    "tt2285752": [  # Tears of Steel
        {
            "name": "Open Movies\n720p",
            "title": "Tears of Steel 720p H.264\n💾 372 MB ⚙️ Blender Foundation",
            "url": TOS + "tears_of_steel_720p.mov",
            "behaviorHints": {"filename": "tears_of_steel_720p.mov", "videoSize": 372178639, "bingeGroup": "openmovies-720p"},
        },
    ],
    "tt1727587": [  # Sintel
        {
            "name": "Open Movies\n1080p",
            "title": "Sintel 2010 1080p\n💾 1.18 GB ⚙️ Blender Foundation",
            "url": SINTEL + "Sintel.2010.1080p.mkv",
            "behaviorHints": {"filename": "Sintel.2010.1080p.mkv", "videoSize": 1180090590},
        },
    ],
}

SUBTITLES = {
    "tt2285752": [
        {"id": "tos-en", "url": TOS + "subtitles/TOS-en.srt", "lang": "eng"},
        {"id": "tos-es", "url": TOS + "subtitles/TOS-es.srt", "lang": "spa"},
        {"id": "tos-fr", "url": TOS + "subtitles/TOS-fr-orig.srt", "lang": "fre"},
        {"id": "tos-de", "url": TOS + "subtitles/TOS-de.srt", "lang": "ger"},
    ],
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        parts = path.strip("/").split("/")
        body = None
        if path == "/manifest.json":
            body = MANIFEST
        elif len(parts) >= 3 and parts[0] in ("stream", "subtitles"):
            vid = parts[2].removesuffix(".json").split(":")[0]
            table = STREAMS if parts[0] == "stream" else SUBTITLES
            body = {parts[0] + "s" if parts[0] == "stream" else "subtitles": table.get(vid, [])}
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s\n" % (fmt % args))


ThreadingHTTPServer(("0.0.0.0", 7311), Handler).serve_forever()
