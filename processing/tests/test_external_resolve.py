"""MacCMS player_aaaa 纯 HTTP 快路径解析单测（本地 mock HTTP，不依赖外网或 Playwright）。"""

import base64
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import TestCase

from processing.tasks import external_resolve


class ExternalResolveFastPathTest(TestCase):
    def test_extracts_encrypt_0_1_2_urls(self):
        plain = "https://cdn.example.com/v/ep1.m3u8"
        html0 = f'<script>var player_aaaa={{"encrypt":0,"url":"{plain}"}}</script>'
        self.assertEqual(plain, external_resolve.extract_maccms_player_url(html0))

        enc1 = urllib.parse.quote(plain, safe="")
        html1 = f'<script>var player_aaaa={{"encrypt":1,"url":"{enc1}"}}</script>'
        self.assertEqual(plain, external_resolve.extract_maccms_player_url(html1))

        mp4 = "https://cdn.example.com/d/file/第01集.mp4"
        enc2 = base64.b64encode(urllib.parse.quote(mp4, safe="").encode("utf-8")).decode("ascii")
        html2 = f'<script>var player_aaaa={{"encrypt":2,"url":"{enc2}"}}</script>'
        self.assertEqual(mp4, external_resolve.extract_maccms_player_url(html2))

    def test_non_video_player_url_returns_none_for_fastpath(self):
        html = '<script>var player_aaaa={"encrypt":0,"url":"https://www.lmm85.com/play/1_1.html"}</script>'
        decoded = external_resolve.extract_maccms_player_url(html)
        self.assertEqual("https://www.lmm85.com/play/1_1.html", decoded)
        self.assertFalse(external_resolve._is_video_url(decoded))

    def test_http_fastpath_follows_single_302_location_without_playwright(self):
        final_cdn = "https://cdn.example.com/FAMILYCLOUD/video.mp4?Expires=1999999999"

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/vodplay/1-1-1.html":
                    target = f"http://127.0.0.1:{self.server.server_port}/d/ep01.mp4"
                    enc = base64.b64encode(urllib.parse.quote(target, safe="").encode("utf-8")).decode("ascii")
                    body = f'<script>var player_aaaa={{"encrypt":2,"url":"{enc}"}}</script>'.encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.send_response(404)
                    self.end_headers()

            def do_HEAD(self):
                if self.path == "/d/ep01.mp4":
                    self.send_response(302)
                    self.send_header("Location", final_cdn)
                    self.end_headers()
                else:
                    self.send_response(404)
                    self.end_headers()

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        try:
            events = []
            res = external_resolve.run(
                {"params": {"episode_url": f"http://127.0.0.1:{server.server_port}/vodplay/1-1-1.html"}},
                None,
                lambda *a: events.append(a),
            )
            self.assertEqual("SUCCEEDED", res["status"])
            self.assertEqual("mp4", res["play_kind"])
            self.assertEqual(final_cdn, res["play_url"])
        finally:
            server.shutdown()
            server.server_close()
