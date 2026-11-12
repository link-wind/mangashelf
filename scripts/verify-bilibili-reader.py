"""真实站内链路：按需加载、完整 HTTP 页图、失败重试、末页与历史续读。"""

import argparse
from contextlib import ExitStack
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
from secrets import token_hex
import sys
from time import monotonic
import traceback
from urllib.parse import urlsplit

from PIL import Image
from playwright.sync_api import expect, sync_playwright


def emit(stage, **fields):
    print(json.dumps({"stage": stage, **fields}, ensure_ascii=False), flush=True)


def wait_image(page, ordinal):
    wrapper = page.locator(f'[data-reader-page="{ordinal}"]')
    expect(wrapper).to_have_attribute("data-page-ready", "true", timeout=45000)
    image = wrapper.get_by_alt_text(f"第 {ordinal} 页")
    assert image.evaluate("(image) => image.complete && image.naturalWidth > 0")


def register(page, base):
    page.goto(base + "/login")
    page.get_by_role("button", name="没有账号？注册").click()
    page.get_by_text("登录名", exact=True).locator("input").fill("http-reader-" + token_hex(5))
    page.get_by_text("密码", exact=True).locator("input").fill(token_hex(16))
    page.get_by_role("button", name="注册并登录").click()
    page.wait_for_url(base + "/", timeout=15000)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:5173")
    parser.add_argument("--screenshot")
    parser.add_argument("--cache-only", action="store_true")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    target = urlsplit(base)
    if target.scheme != "http" or target.hostname not in ("localhost", "127.0.0.1"):
        parser.error("验证入口只允许本机 HTTP 地址")
    chapter = "mc24742-218098"
    manifest_path = f"/api/v1/manga-sources/bilibili/chapter/{chapter}/pages"
    image_pattern = re.compile(r"/api/v1/manga-sources/bilibili/chapter/" + chapter + r"/image/(\d+)")
    emit("starting", base=base)
    with sync_playwright() as playwright, ExitStack() as cleanup:
        browser = playwright.chromium.launch()
        cleanup.callback(browser.close)
        context = browser.new_context(viewport={"width": 1280, "height": 900})
        page = context.new_page()
        register(page, base)
        if args.cache_only:
            response = context.request.get(base + manifest_path)
            assert response.ok
            manifest = response.json()
            response = context.request.get(base + manifest["pages"][-1])
            assert response.ok and response.headers["x-mangashelf-cache"] == "HIT"
            with Image.open(BytesIO(response.body())) as image:
                image.load()
            emit("restart_cache", hit=True, pages=len(manifest["pages"]))
            return
        page.evaluate("""localStorage.setItem('ms-manga-reader-settings',
            JSON.stringify({dir:'ltr',pageMode:'double-cover',anim:'none',hd:true}))""")
        requests = []

        def request_seen(request):
            matched = image_pattern.search(urlsplit(request.url).path)
            if matched:
                requests.append({"page": int(matched.group(1))})

        page.on("request", request_seen)
        emit("home")
        page.goto(base + "/manga")
        with page.expect_response(lambda response:
                "/api/v1/manga-sources/bilibili/sections" in response.url, timeout=30000) as ranked:
            page.get_by_role("button", name="哔哩漫画", exact=True).click()
        assert ranked.value.ok
        with page.expect_response(lambda response:
                urlsplit(response.url).path == "/api/v1/manga-sources/bilibili/manga/24742",
                timeout=45000) as detailed:
            page.locator('button.manga-tile[title="航海王"]').first.click()
        assert detailed.value.ok
        detail = detailed.value.json()
        chapters = detail["chapters"]
        assert any(item["id"] == chapter and not item["external"] for item in chapters)
        emit("detail", chapters=len(chapters), public=sum(not item["external"] for item in chapters))
        started = monotonic()
        with page.expect_response(lambda response:
                urlsplit(response.url).path == manifest_path, timeout=45000) as manifest_response:
            page.locator("button.chapter-cell").filter(
                has=page.get_by_text("004", exact=True)).click()
        assert manifest_response.value.ok
        manifest = manifest_response.value.json()
        assert len(manifest["pages"]) == 20 and len(manifest["dimensions"]) == 20
        assert all(url.startswith("/api/v1/manga-sources/bilibili/chapter/")
                   for url in manifest["pages"])
        assert "sessionId" not in manifest
        emit("manifest", pages=20, seconds=round(monotonic() - started, 2))
        wait_image(page, 1)
        page.wait_for_timeout(500)
        initial = sorted({request["page"] for request in requests})
        assert set(initial) <= {1, 2, 3}, initial
        emit("initial_demand", requested_pages=initial)
        slider = page.locator(".rf-bottom input")
        last_spread = slider.get_attribute("max")
        with page.expect_response(lambda response:
                "/api/v1/me/manga-progress" in response.url
                and response.request.method == "PUT") as saved:
            slider.focus()
            slider.press("End")
        assert saved.value.ok
        wait_image(page, 20)
        expect(slider).to_have_value(last_spread)
        emit("last_page", decoded=True)

        digests = set()
        total_bytes = 0
        cache_results = {"HIT": 0, "MISS": 0}
        for ordinal, url in enumerate(manifest["pages"], 1):
            response = context.request.get(base + url, timeout=45000)
            assert response.ok, f"第 {ordinal} 页 HTTP {response.status}"
            assert response.headers.get("cache-control") == "private, no-store"
            assert response.headers.get("content-type") in ("image/jpeg", "image/png")
            body = response.body()
            total_bytes += len(body)
            with Image.open(BytesIO(body)) as image:
                image.load()
                size = manifest["dimensions"][ordinal - 1]
                assert image.size == (size["width"], size["height"])
            digest = hashlib.sha256(body).hexdigest()
            assert digest not in digests
            digests.add(digest)
            cache_results[response.headers["x-mangashelf-cache"]] += 1
        repeat = context.request.get(base + manifest["pages"][-1])
        assert repeat.headers["x-mangashelf-cache"] == "HIT"
        emit("complete_http_read", decoded=len(digests), bytes=total_bytes, cache_results=cache_results)

        anonymous = browser.new_context()
        assert anonymous.request.get(base + manifest["pages"][0]).status in (401, 403)
        anonymous.close()
        wrong_version = manifest["pages"][0].partition("?v=")[0] + "?v=" + "0" * 64
        invalid = context.request.get(base + wrong_version)
        assert invalid.status == 409
        assert invalid.headers.get("cache-control") == "private, no-store"
        locked = next(item["id"] for item in chapters if item["external"])
        denied = context.request.get(base +
                f"/api/v1/manga-sources/bilibili/chapter/{locked}/pages")
        assert denied.status == 403
        emit("access_checks", anonymous_denied=True, old_version_denied=True, locked_denied=True)

        failure_pattern = "**/api/v1/manga-sources/bilibili/chapter/" + chapter + "/image/20?*"
        page.route(failure_pattern, lambda route: route.fulfill(
            status=502, content_type="application/json",
            body=json.dumps({"code": "CONTROLLED_FAILURE", "message": "受控页图失败，请重试"})))
        page.reload()
        retry = page.get_by_role("button", name="重试第 20 页")
        expect(retry).to_be_visible(timeout=15000)
        page.unroute(failure_pattern)
        retry.click()
        wait_image(page, 20)
        emit("page_retry", passed=True)

        page.goto(base + "/history")
        requests.clear()
        with page.expect_response(lambda response:
                urlsplit(response.url).path == manifest_path) as resumed:
            page.get_by_role("link", name="继续阅读").click()
        assert resumed.value.ok
        expect(page.locator(".rf-bottom input")).to_have_value(last_spread, timeout=15000)
        wait_image(page, 20)
        assert all(request["page"] >= 19 for request in requests), requests
        emit("history", resumed_page=20, requested_pages=sorted({item["page"] for item in requests}))

        page.get_by_role("button", name="设置", exact=True).click()
        requests.clear()
        page.get_by_role("button", name="单页", exact=True).click()
        wait_image(page, 20)
        expect(page.locator(".rf-bottom input")).to_have_value("20")
        page.wait_for_timeout(300)
        assert all(item["page"] >= 18 for item in requests), requests
        if args.screenshot:
            page.screenshot(path=args.screenshot)
        emit("scroll_mode", retained_page=20, requested_pages=sorted({item["page"] for item in requests}))
        page.set_viewport_size({"width": 390, "height": 844})
        wait_image(page, 20)
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        emit("mobile_layout", width=390, horizontal_overflow=False)
        progress = context.request.get(base + "/api/v1/me/manga-progress?limit=50").json()
        current = next(row for row in progress if row["source"] == "bilibili" and row["mangaId"] == "24742")
        assert current["chapterId"] == chapter and current["page"] == 20
        requests.clear()
        page.reload()
        wait_image(page, 20)
        expect(page.locator(".rf-bottom input")).to_have_value("20")
        assert all(item["page"] >= 18 for item in requests), requests
        emit("scroll_resume", retained_page=20,
             requested_pages=sorted({item["page"] for item in requests}))
        sample = context.request.get(base +
            "/api/v1/manga-sources/bilibili/chapter/mc24442-209069/pages", timeout=45000)
        assert sample.ok
        sample_manifest = sample.json()
        assert len(sample_manifest["pages"]) == 9
        for ordinal, url in enumerate(sample_manifest["pages"], 1):
            response = context.request.get(base + url, timeout=45000)
            assert response.ok
            with Image.open(BytesIO(response.body())) as image:
                image.load()
                size = sample_manifest["dimensions"][ordinal - 1]
                assert image.size == (size["width"], size["height"])
        emit("second_sample", decoded=9)
        emit("passed", history_page=20)


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        chain = []
        current = failure
        while current is not None and len(chain) < 4:
            chain.append({
                "error_type": type(current).__name__,
                "frames": [f"{Path(frame.filename).name}:{frame.lineno}:{frame.name}"
                           for frame in traceback.extract_tb(current.__traceback__)[-6:]],
            })
            current = current.__context__
        emit("failed", errors=chain)
        sys.exit(1)
