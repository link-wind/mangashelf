"""验证站内拷贝漫画 HTTP 目录、缓存与可选的真实阅读链路。"""

import argparse
from contextlib import contextmanager
import http.cookiejar
import json
from pathlib import Path
from secrets import token_hex
import subprocess
import sys
from threading import Event, Thread
from time import monotonic
import traceback
from urllib.error import HTTPError
from urllib.parse import unquote, urlsplit
from urllib.request import build_opener, HTTPCookieProcessor, ProxyHandler, Request


def emit(stage, **fields):
    print(json.dumps({"stage": stage, **fields}, ensure_ascii=False), flush=True)


@contextmanager
def observe_server(pid):
    stopped = Event()
    observed = set()
    errors = []

    def sample():
        try:
            while not stopped.is_set():
                process_list = subprocess.run(
                    ["ps", "-axo", "pid=,ppid=,comm="],
                    check=True, capture_output=True, text=True,
                )
                rows = [row.strip().split(None, 2) for row in process_list.stdout.splitlines()]
                descendants = {pid}
                assert any(len(row) == 3 and int(row[0]) == pid for row in rows)
                for _ in range(8):
                    descendants.update(int(row[0]) for row in rows
                                       if len(row) == 3 and int(row[1]) in descendants)
                observed.update(row[2].rsplit("/", 1)[-1] for row in rows
                                if len(row) == 3 and int(row[0]) in descendants - {pid})
                stopped.wait(0.1)
        except Exception as error:
            errors.append(type(error).__name__)

    thread = Thread(target=sample, daemon=True) if pid else None
    if thread:
        thread.start()
    try:
        yield observed
    finally:
        stopped.set()
        if thread:
            thread.join()
        assert not errors, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:5173")
    parser.add_argument("--server-pid", type=int)
    parser.add_argument("--ui", action="store_true")
    parser.add_argument("--reader-manga", choices=("baoshimonv", "zangsongdefulilian"),
                        default="zangsongdefulilian")
    parser.add_argument("--expect-pages", type=int, default=0,
                        help="可选：断言阅读清单的页数（用于完整章节回归）")
    parser.add_argument("--expect-login-required", action="store_true")
    parser.add_argument("--screenshot")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    target = urlsplit(base)
    if target.scheme != "http" or target.hostname not in ("localhost", "127.0.0.1"):
        parser.error("验证入口只允许本机 HTTP 地址")
    jar = http.cookiejar.CookieJar()
    opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(jar))

    def call(path, data=None):
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
            headers["X-XSRF-TOKEN"] = unquote(next(
                cookie.value for cookie in jar if cookie.name == "XSRF-TOKEN"))
        request = Request(base + path, headers=headers,
                          data=json.dumps(data).encode() if data is not None else None)
        try:
            with opener.open(request, timeout=60) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.loads(error.read() or "{}")

    call("/api/v1/auth/me")
    credentials = {"login": "copy-http-" + token_hex(5), "password": token_hex(16)}
    assert call("/api/v1/auth/register", credentials)[0] == 201
    assert call("/api/v1/auth/login", credentials)[0] == 200
    details = {}
    with observe_server(args.server_pid) as observed:
        for manga in ("baoshimonv", "haizeiwang", "zangsongdefulilian"):
            path = "/api/v1/manga-sources/copymanga/manga/" + manga
            started = monotonic()
            status, detail = call(path)
            if status != 200:
                emit("directory_failed", manga=manga, status=status, code=detail.get("code"),
                     message=detail.get("message") if str(detail.get("code", "")).startswith("COPYMANGA_") else None)
            assert status == 200, (status, detail.get("code"), detail.get("message"))
            seconds = monotonic() - started
            chapters = detail["chapters"]
            ids = [chapter["id"] for chapter in chapters]
            assert chapters and len(ids) == len(set(ids))
            assert all(chapter["id"].startswith(manga + "~") and chapter["label"]
                       and not chapter["external"] for chapter in chapters)
            started = monotonic()
            repeated_status, repeated = call(path)
            assert repeated_status == 200 and repeated == detail
            emit("directory", manga=manga, chapters=len(chapters),
                 first=chapters[0]["label"], last=chapters[-1]["label"],
                 first_seconds=round(seconds, 3),
                 repeated_seconds=round(monotonic() - started, 3))
            details[manga] = detail
        status, invalid = call("/api/v1/manga-sources/copymanga/manga/invalid.id")
        assert status == 400 and invalid["code"] == "COPYMANGA_INVALID_ID"
    if args.server_pid:
        assert not observed, sorted(observed)
        emit("directory_processes", server_children=[])
    if not args.ui:
        return

    from playwright.sync_api import expect, sync_playwright

    manga = args.reader_manga
    legacy_chapter = "baoshimonv~0e62971e-3cfe-11f0-823d-fa163e02432f"
    assert any(chapter["id"] == legacy_chapter for chapter in details["baoshimonv"]["chapters"])
    chapter_id = legacy_chapter if manga == "baoshimonv" else details[manga]["chapters"][0]["id"]
    manifest_path = "/api/v1/manga-sources/copymanga/chapter/" + chapter_id + "/pages"
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        try:
            context = browser.new_context(viewport={"width": 1280, "height": 900})
            context.add_cookies([{"name": cookie.name, "value": cookie.value, "url": base}
                                 for cookie in jar])
            page = context.new_page()
            image_requests = []
            page.on("request", lambda request: image_requests.append(request.url)
                    if request.resource_type == "image"
                    and urlsplit(request.url).hostname not in ("localhost", "127.0.0.1")
                    else None)
            page.goto(base + "/manga-ext/" + manga + "?source=copymanga")
            expect(page.get_by_role("heading", name=details[manga]["item"]["title"],
                                    exact=True)).to_be_visible()
            expect(page.locator("button.chapter-cell")).to_have_count(
                len(details[manga]["chapters"]))
            if args.screenshot:
                screenshot = Path(args.screenshot)
                page.screenshot(path=str(screenshot.with_stem(screenshot.stem + "-detail")))
            chapter = next(chapter for chapter in details[manga]["chapters"]
                           if chapter["id"] == chapter_id)
            with page.expect_response(lambda response:
                    urlsplit(response.url).path == manifest_path, timeout=60000) as resolved:
                page.locator("button.chapter-cell").filter(
                    has=page.get_by_text(chapter["label"], exact=True)).click()
            payload = resolved.value.json()
            emit("reader_manifest", status=resolved.value.status,
                 pages=len(payload.get("pages", [])), code=payload.get("code"))
            if args.expect_login_required:
                assert resolved.value.status == 403
                failure = payload
                assert failure["code"] == "COPYMANGA_LOGIN_REQUIRED"
                expect(page.get_by_text(failure["message"], exact=True)).to_be_visible()
                if args.screenshot:
                    page.screenshot(path=args.screenshot)
                emit("reader_restricted", status=403, code=failure["code"])
                return
            assert resolved.value.ok
            manifest = payload
            count = len(manifest["pages"])
            assert count > 0
            if args.expect_pages:
                assert count == args.expect_pages, (count, args.expect_pages)
            first = page.locator('[data-reader-page="1"]')
            expect(first).to_have_attribute("data-page-ready", "true", timeout=60000)
            page.wait_for_timeout(2000)
            initial_images = len(set(image_requests))
            if count > 2:
                assert initial_images < count, (initial_images, count)
            emit("reader_lazy_load", initial_images=initial_images, pages=count)
            last = page.locator(f'[data-reader-page="{count}"]')
            if last.count():
                last.scroll_into_view_if_needed()
            else:
                page.locator(".rf-bottom input").focus()
                page.locator(".rf-bottom input").press("End")
            expect(last).to_have_attribute("data-page-ready", "true", timeout=60000)
            image = last.get_by_alt_text(f"第 {count} 页")
            size = image.evaluate("(image) => [image.naturalWidth, image.naturalHeight]")
            assert size[0] > 0 and size[1] > 0
            viewport = page.evaluate(
                "(element) => ({height: window.innerHeight, top: element.getBoundingClientRect().top,"
                " bottom: element.getBoundingClientRect().bottom})", last.element_handle())
            assert viewport["top"] < viewport["height"] and viewport["bottom"] > 0, viewport
            if args.screenshot:
                page.screenshot(path=args.screenshot)
            emit("reader", manga=manga, pages=count, last_size=size)
            page.wait_for_timeout(4000)
            response = context.request.get(base + "/api/v1/me/manga-progress?limit=50")
            assert response.ok
            progress = next(row for row in response.json()
                            if row["source"] == "copymanga" and row["mangaId"] == manga)
            assert progress["chapterId"] == chapter_id and progress["page"] == count
            emit("progress", page=progress["page"], source=progress["source"])
        finally:
            browser.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        frames = traceback.extract_tb(error.__traceback__)
        emit("failed", error=type(error).__name__,
             locations=[f"{frame.filename.rsplit('/', 1)[-1]}:{frame.lineno}" for frame in frames])
        sys.exit(1)
