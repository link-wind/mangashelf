"""用真实 Chromium 验证页位、页序和缺页错误，不依赖上游网络。"""

import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch

from PIL import Image
from playwright.sync_api import sync_playwright

from processing.tasks import manga_resolve

URL = "https://manga.bilibili.com/mc999999999/999999999"


def reader_html(missing_page: int = 0) -> str:
    return """
    <div class="info-hud"><span class="current-page"></span>
        <span class="next-page"></span> 6P</div>
    <input type="range" class="range-input" min="1" max="3" value="1">
    <div class="view-container secondary-image"><div class="image-container">
        <canvas width="240" height="320"></canvas></div></div>
    <div class="view-container primary-image"><div class="image-container">
        <canvas width="240" height="320"></canvas></div></div>
    <script>
    const input = document.querySelector('input');
    function draw() {
        const n = Number(input.value) * 2 - 1;
        document.querySelector('.current-page').textContent = n;
        document.querySelector('.next-page').textContent = n + 1;
        for (const [ord, kind] of [[n, 'primary'], [n + 1, 'secondary']]) {
            const ctx = document.querySelector('.' + kind + '-image canvas').getContext('2d');
            ctx.fillStyle = 'white';
            ctx.fillRect(0, 0, 240, 320);
            if (ord === MISSING_PAGE) continue;
            ctx.fillStyle = 'rgb(' + (ord * 20) + ',0,0)';
            ctx.fillRect(0, 0, 120, 320);
        }
    }
    input.addEventListener('input', draw);
    draw();
    </script>
    """.replace("MISSING_PAGE", str(missing_page))


class BilibiliCaptureTest(TestCase):
    def capture(self, html, verify, progress=None):
        with sync_playwright() as p, TemporaryDirectory() as tmp:
            browser = p.chromium.launch()
            try:
                page = browser.new_page()
                page.route(URL, lambda route: route.fulfill(
                    content_type="text/html; charset=utf-8", body=html))
                with patch.object(manga_resolve, "CANVAS_SPREAD_WAIT_SECONDS", 2):
                    result = manga_resolve._resolve_chapter_canvas(page, URL, Path(tmp), progress)
                verify(result, Path(tmp))
            finally:
                browser.close()

    def test_capture_follows_hud_order_not_dom_order(self):
        def verify(result, directory):
            self.assertEqual("SUCCEEDED", result["status"])
            self.assertEqual(6, result["expected_pages"])
            for i, artifact in enumerate(result["artifacts"], 1):
                self.assertEqual(i, artifact["ordinal"])
                with Image.open(directory / artifact["path"]) as page:
                    self.assertEqual((i * 20, 0, 0), page.getpixel((10, 10))[:3])

        self.capture(reader_html(), verify)

    def test_missing_page_does_not_produce_manifest(self):
        def verify(result, _):
            self.assertEqual("FAILED", result["status"])
            self.assertEqual("CAPTURE_INCOMPLETE", result["error_code"])
            self.assertIn("5/6", result["message"])
            self.assertIn("第 4 页", result["message"])
            self.assertNotIn("artifacts", result)

        self.capture(reader_html(4), verify)

    def test_capture_emits_page_ready_progress(self):
        events = []

        def progress(_stage, message):
            events.append(json.loads(message))

        def verify(result, directory):
            self.assertEqual("SUCCEEDED", result["status"])
            self.assertEqual({"event": "capture-ready", "expected_pages": 6}, events[0])
            pages = [event for event in events if event["event"] == "page-ready"]
            self.assertEqual(list(range(1, 7)), [event["ordinal"] for event in pages])
            for i, event in enumerate(pages, 1):
                self.assertEqual(f"page-{i}.png", event["path"])
                self.assertTrue((directory / event["path"]).is_file())

        self.capture(reader_html(), verify, progress)

    def test_scroll_waits_for_pages_outside_the_viewport(self):
        html = """
        <div class="info-hud"><span class="current-page">1</span> 3P</div>
        <script>
        const io = new IntersectionObserver(items => {
            for (const {target, isIntersecting} of items) {
                if (!isIntersecting) continue;
                const ctx = target.querySelector('canvas').getContext('2d');
                ctx.fillStyle = 'white';
                ctx.fillRect(0, 0, 250, 400);
                ctx.fillStyle = 'rgb(0,' + target.dataset.color + ',0)';
                ctx.fillRect(0, 0, 125, 400);
                target.classList.add('image-loaded');
                io.unobserve(target);
            }
        });
        for (let i = 1; i <= 3; i++) {
            const item = document.createElement('div');
            item.className = 'image-item';
            item.dataset.color = i * 30;
            item.innerHTML = '<div class="image-container"><canvas width="250" height="400"></canvas></div>';
            document.body.append(item);
            io.observe(item);
        }
        </script>
        """

        def verify(result, directory):
            self.assertEqual("SUCCEEDED", result["status"])
            self.assertEqual(3, result["expected_pages"])
            for i, artifact in enumerate(result["artifacts"], 1):
                with Image.open(directory / artifact["path"]) as page:
                    self.assertEqual((0, i * 30, 0), page.getpixel((10, 10))[:3])

        self.capture(html, verify)
