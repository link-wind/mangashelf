"""仅本机的阅读器验收夹具：生产 Web 构建、生成条漫、请求计数与一次性失败。"""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
from pathlib import Path
import re
from threading import Lock
from time import sleep
from urllib.parse import parse_qs, urlsplit

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
CASES = {"strip", "resume", "paged", "normal", "remote", "bili"}
TOTAL = 80
LOCK = Lock()
EVENTS = []
PROGRESS = {"resume": 40, "paged": 40, "normal": 40}
PROGRESS_READY = set()
FAILED = set()


def dimensions(ordinal, case="strip"):
    return {"width": 800, "height": 1200 if case == "normal" else 3200 if ordinal % 2 else 4800}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        return

    def send(self, status, body, kind="application/json", cache="no-store"):
        if isinstance(body, (list, dict)):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", cache)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            return

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/__check":
            with LOCK:
                self.send(200, {"events": EVENTS, "progress": PROGRESS})
            return
        if path == "/api/v1/auth/me":
            self.send(200, {"id": "00000000-0000-0000-0000-000000000001",
                            "login": "reader-validation", "role": "USER"})
            return
        if path == "/api/v1/me/manga-progress":
            sleep(0.3)
            referrer = urlsplit(self.headers.get("Referer", ""))
            chapter = re.fullmatch(r"/manga-ext/read/(\w+)", referrer.path)
            manga_id = parse_qs(referrer.query).get("mangaId", [""])[0]
            with LOCK:
                if chapter and chapter[1] in CASES:
                    PROGRESS_READY.add(chapter[1])
                rows = [{"source": "bilibili" if case == "bili" else "copymanga",
                         "mangaId": manga_id if chapter and chapter[1] == case else case,
                         "chapterId": case, "page": page,
                         "totalPages": TOTAL, "title": "生成条漫验证"}
                        for case, page in PROGRESS.items()]
            self.send(200, rows)
            return
        if path == "/api/v1/external-manga/image":
            original = parse_qs(urlsplit(self.path).query).get("u", [""])[0]
            target = re.fullmatch(r"/reader-fixture/remote/(\d+)\.png", urlsplit(original).path)
            if not target:
                self.send(400, {})
                return
            with LOCK:
                EVENTS.append({"case": "remote", "page": int(target[1]), "proxy": True,
                               "after_restore": "remote" in PROGRESS_READY})
            self.send(502, {"code": "FIXTURE_PROXY_FAILURE", "message": "受控代理失败"})
            return
        manifest = re.fullmatch(r"/api/v1/manga-sources/(copymanga|bilibili)/chapter/(\w+)/pages", path)
        if manifest and manifest[2] in CASES:
            case = manifest[2]
            if case == "resume":
                sleep(0.5)
            pages = [f"/reader-fixture/{case}/{ordinal}.png" for ordinal in range(1, TOTAL + 1)]
            if case == "remote":
                pages = [f"http://127.0.0.1:{self.server.server_port}/reader-fixture/remote/{ordinal}.png"
                         for ordinal in range(1, TOTAL + 1)]
            if case == "bili":
                pages = [f"/api/v1/manga-sources/bilibili/chapter/bili/image/{ordinal}?v={'0' * 64}"
                         for ordinal in range(1, TOTAL + 1)]
            self.send(200, {"pages": pages, "dataSaver": [], "external": False,
                           "dimensions": [dimensions(ordinal, case) for ordinal in range(1, TOTAL + 1)]
                           if case == "bili" else []})
            return
        detail = re.fullmatch(r"/api/v1/manga-sources/(copymanga|bilibili)/manga/(\w+)", path)
        if detail and detail[2] in CASES:
            case = detail[2]
            chapters = ("strip", "resume") if case in ("strip", "resume") else (case,)
            self.send(200, {"item": {"title": "生成条漫验证"}, "chapters": [
                {"id": chapter, "label": str(ordinal), "title": "按需加载验证", "lang": "zh",
                 "pages": TOTAL, "external": False} for ordinal, chapter in enumerate(chapters, 1)]})
            return
        image = re.fullmatch(r"/reader-fixture/(\w+)/(\d+)\.png", path)
        if not image:
            image = re.fullmatch(r"/api/v1/manga-sources/bilibili/chapter/(\w+)/image/(\d+)", path)
        if image and image[1] in CASES and 1 <= int(image[2]) <= TOTAL:
            case, ordinal = image[1], int(image[2])
            with LOCK:
                EVENTS.append({"case": case, "page": ordinal, "after_restore": case in PROGRESS_READY})
                fail = ordinal == TOTAL and case not in FAILED
                if fail:
                    FAILED.add(case)
            if fail:
                self.send(502, {"code": "FIXTURE_FAILURE", "message": "受控图片失败，请重试"})
                return
            size = dimensions(ordinal, case)
            canvas = Image.new("RGB", (size["width"], size["height"]), "#e5edf5")
            draw = ImageDraw.Draw(canvas)
            for top in range(0, size["height"], 400):
                draw.rectangle((24, top + 24, 776, top + 376), outline="#416984", width=4)
                draw.text((60, top + 60), f"PAGE {ordinal} - PANEL {top // 400 + 1}",
                          fill="#11283a", font_size=36)
            output = BytesIO()
            canvas.save(output, "PNG")
            self.send(200, output.getvalue(), "image/png", "public, max-age=3600")
            return
        if path.startswith("/api/"):
            self.send(404, {"code": "FIXTURE_NOT_FOUND", "message": "未提供此验收接口"})
            return
        if path.startswith("/assets/"):
            asset = (ROOT / "web/dist" / path.removeprefix("/")).resolve()
            if not asset.is_relative_to(ROOT / "web/dist/assets") or not asset.is_file():
                self.send(404, {})
                return
            kind = "text/css" if asset.suffix == ".css" else "application/javascript"
            self.send(200, asset.read_bytes(), kind)
            return
        self.send(200, (ROOT / "web/dist/index.html").read_bytes(), "text/html")

    def do_PUT(self):
        if urlsplit(self.path).path != "/api/v1/me/manga-progress":
            self.send(404, {})
            return
        value = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        case = value.get("chapterId")
        if case not in CASES or not 1 <= value.get("page", 0) <= TOTAL:
            self.send(400, {})
            return
        with LOCK:
            PROGRESS[case] = value["page"]
        self.send(200, {})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=15174)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(json.dumps({"fixture": f"http://localhost:{args.port}",
                      "cases": sorted(CASES), "pages": TOTAL}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
