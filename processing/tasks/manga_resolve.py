"""manga.resolve：Playwright 打开漫画网页版解析详情/章节页图（M29）。

与动画 external.resolve 同构：无头浏览器执行与真实访问者相同的 JS（拷贝漫画
章节图由前端 AES 解密后写入 img[data-src]），不做逐站逆向。部分章节页在游客
状态下 contentKey 为空、0 页；无法取得页图时明确提示配置登录 Cookie（或
Playwright storage_state），不伪造页图。
URL 由 Java 源实现按固定模板拼接，Python 不接收用户直接提交的任意 URL。
"""

from __future__ import annotations

import os
import json
import re
from contextlib import contextmanager
from typing import Any, Callable
from urllib.parse import urlsplit

DEFAULT_TIMEOUT_SECONDS = 45
LAZY_SCROLL_ROUNDS = 16
MIN_IMAGE_WIDTH = 300
CANVAS_SPREAD_WAIT_SECONDS = 18

# 站点广告/静态资源特征（章节页图不应包含）
AD_MARKERS = (
    "/static/ads/",
    "websitefree",
    "allicon",
    "loading",
    "play_cartoon",
    "/static/cdn/",
    "/static/websitefree/",
)
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")



def _goto_with_retry(page: Any, url: str, timeout_seconds: int = 30) -> None:
    """Chromium 偶发 SSL_BAD_RECORD_MAC（网络/代理干扰），重试 5 次。"""
    last = None
    for attempt in range(5):
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=timeout_seconds * 1000)
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            text = str(exc)
            retryable = ("ERR_SSL_BAD_RECORD_MAC_ALERT" in text
                         or "ERR_CONNECTION" in text
                         or "ERR_TIMED_OUT" in text
                         or "NS_ERROR" in text)
            if attempt < 4 and retryable:
                page.wait_for_timeout(1200)
                continue
            raise
    if last:
        raise last


def _parse_cookies(cookie: str, host: str) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for part in (cookie or "").split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        name, value = name.strip(), value.strip()
        if not name:
            continue
        for domain in ("." + host, host):
            out.append({"name": name, "value": value, "domain": domain, "path": "/"})
    return out


def _looks_like_image(url: str) -> bool:
    try:
        parts = urlsplit(url)
    except Exception:
        return False
    if parts.scheme not in ("http", "https"):
        return False
    lower = url.lower()
    if any(marker in lower for marker in AD_MARKERS):
        return False
    path = parts.path.lower()
    return any(path.endswith(ext) for ext in IMAGE_EXTS)


def _expand_catalog(page: Any) -> None:
    """点击「目錄/目录」展开全部章节（详情页默认只渲染最新一话）。"""
    try:
        page.evaluate(
            """() => {
                const els = [...document.querySelectorAll('a,button,div,span')];
                const el = els.find(e => /^(目錄|目录)$/.test((e.textContent || '').trim()));
                if (el) el.click();
            }"""
        )
        page.wait_for_timeout(1500)
    except Exception:
        pass


PSEUDO_LABELS = {"開始閱讀", "开始阅读", "繼續閱讀", "继续阅读"}


def _collect_chapters(page: Any) -> list[dict[str, str]]:
    links = page.eval_on_selector_all(
        "a[href*='/chapter/']",
        "els => els.map(e => ({ href: e.getAttribute('href') || '', "
        "text: (e.textContent || '').trim(), "
        "cls: (e.className || '').toString(), "
        "parent: (e.parentElement && e.parentElement.className || '').toString() }))",
    )
    # 目录列表优先；页头「開始閱讀」按钮与「最近更新」链接放到最后兜底，
    # 避免伪标签覆盖真实章节名、或最近更新章节挤在列表最前。
    catalog: list[dict[str, str]] = []
    header: list[dict[str, str]] = []
    for link in links:
        if link["text"] in PSEUDO_LABELS or "comicParticulars" in link["cls"]:
            continue
        (header if "table-default-right" in link["parent"] else catalog).append(link)

    seen: dict[str, dict[str, str]] = {}
    for link in catalog + header:
        match = re.search(r"/comic/([^/]+)/chapter/([0-9a-fA-F-]+)", link["href"])
        if not match:
            continue
        uuid = match.group(2)
        if uuid in seen:
            continue
        label = link["text"] or ""
        seen[uuid] = {"id": uuid, "label": label, "title": label}
    return list(seen.values())


def _resolve_search(page: Any, url: str) -> dict[str, Any]:
    _goto_with_retry(page, url, DEFAULT_TIMEOUT_SECONDS)
    try:
        page.wait_for_load_state("load", timeout=8000)
    except Exception:
        pass
    page.wait_for_timeout(2500)
    links = page.eval_on_selector_all(
        "a[href*='/comic/']",
        "els => els.filter(e => !(e.getAttribute('href') || '').includes('/chapter/'))"
        ".map(e => ({ href: e.getAttribute('href') || '', "
        "text: (e.innerText || '').trim(), "
        "img: (e.querySelector('img') || {}).src || '' }))",
    )
    merged: dict[str, dict[str, str]] = {}
    for link in links:
        match = re.search(r"^/comic/([a-zA-Z0-9_-]+)/?$", link["href"])
        if not match:
            continue
        path_word = match.group(1)
        entry = merged.setdefault(path_word, {"id": path_word, "title": "", "cover": ""})
        if link["text"]:
            entry["title"] = link["text"]
        if link["img"]:
            entry["cover"] = link["img"]
    results = [e for e in merged.values() if e["title"]]
    return {"status": "SUCCEEDED", "results": results}


def _resolve_detail(page: Any, url: str) -> dict[str, Any]:
    _goto_with_retry(page, url, DEFAULT_TIMEOUT_SECONDS)
    try:
        page.wait_for_load_state("load", timeout=8000)
    except Exception:
        pass
    page.wait_for_timeout(2500)
    _expand_catalog(page)
    info = page.evaluate(
        """() => {
            const meta = (n) => {
                const el = document.querySelector(`meta[property="${n}"], meta[name="${n}"]`);
                return el ? (el.content || '') : '';
            };
            const h1 = document.querySelector('h1');
            const title = (h1 && h1.innerText.trim()) ||
                (document.title.split('-')[0] || '').trim();
            const imgs = [...document.images].map(i => ({
                src: i.currentSrc || i.src || '',
                w: i.naturalWidth || 0
            }));
            const cover = meta('og:image') ||
                (imgs.find(i => i.src.includes('/cover/') && i.w > 0) || {}).src || '';
            const desc = meta('description') || meta('og:description') ||
                ((document.querySelector('.intro, .comic-intro, .detail-intro') || {}).innerText || '');
            const body = document.body.innerText || '';
            const author = (body.match(/作者[：:]\\s*([^\\n]{1,40})/) || [])[1] || '';
            return { title, cover, description: (desc || '').trim(), author: (author || '').trim() };
        }"""
    )
    chapters = _collect_chapters(page)
    if not info.get("title"):
        return {"status": "FAILED", "error_code": "DETAIL_EMPTY", "message": "详情页未解析到标题"}
    return {
        "status": "SUCCEEDED",
        "title": info["title"],
        "cover": info["cover"],
        "description": info["description"],
        "author": info["author"],
        "chapters": chapters,
    }


def _collect_pages(page: Any) -> list[str]:
    images = page.eval_on_selector_all(
        "img",
        "els => els.map(e => ({ src: e.getAttribute('data-src') || e.currentSrc || e.src || '', "
        "w: e.naturalWidth || 0 }))",
    )
    pages: list[str] = []
    for image in images:
        src = (image.get("src") or "").strip()
        if not _looks_like_image(src):
            continue
        if image.get("w") and image["w"] < MIN_IMAGE_WIDTH:
            continue
        if src not in pages:
            pages.append(src)
    return pages


def _resolve_chapter(page: Any, url: str) -> dict[str, Any]:
    _goto_with_retry(page, url, DEFAULT_TIMEOUT_SECONDS)
    try:
        page.wait_for_load_state("load", timeout=8000)
    except Exception:
        pass
    page.wait_for_timeout(2000)
    # 多数章节 JS 解密后已写入 data-src；但首屏可能只渲染第一张，必须继续滚动
    # 触发懒加载并把后续页合并进来，不能因为已经看到 1 张就提前成功。
    pages = _collect_pages(page)
    stale_rounds = 0
    for _ in range(LAZY_SCROLL_ROUNDS):
        before = len(pages)
        page.evaluate("window.scrollBy(0, Math.max(window.innerHeight, 600))")
        page.wait_for_timeout(700)
        pages = _collect_pages(page)
        at_bottom = page.evaluate(
            "() => window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2"
        )
        if len(pages) == before and at_bottom:
            stale_rounds += 1
            if stale_rounds >= 2:
                break
        else:
            stale_rounds = 0

    if pages:
        return {"status": "SUCCEEDED", "pages": pages}

    content_key = page.evaluate(
        "() => (typeof contentKey === 'string' ? contentKey : '')"
    )
    body = page.inner_text("body")
    if not content_key or "登錄" in body or "登录" in body:
        return {
            "status": "FAILED",
            "error_code": "LOGIN_REQUIRED",
            "message": "章节内容需要登录：请配置拷贝漫画 Cookie / storage_state 后重试",
        }
    return {
        "status": "FAILED",
        "error_code": "NO_PAGES",
        "message": "章节页未解析到图片（可能为付费/锁定章节，或页面结构变化）",
    }


def _resolve_chapter_canvas(page: Any, url: str, run_dir: Any,
                            progress: Callable[..., None] | None = None) -> dict[str, Any]:
    """使用官方匿名阅读器的页位与进度条；截图仅来自已渲染的页。"""
    from hashlib import sha256
    from io import BytesIO
    from time import monotonic

    from PIL import Image, ImageStat
    from playwright.sync_api import Error as BrowserError, TimeoutError as BrowserTimeout

    def failed(code: str, message: str) -> dict[str, Any]:
        if progress:
            progress("manga", json.dumps({"event": "capture-failed", "code": code,
                                          "message": message}, ensure_ascii=False))
        return {"status": "FAILED", "error_code": code, "message": message}

    def same_chapter() -> bool:
        target = urlsplit(page.url)
        return (target.scheme, target.netloc, target.path) == ("https", "manga.bilibili.com", urlsplit(url).path)

    deadline = monotonic() + 96
    hud = page.locator(".info-hud")
    slider = page.locator("input.range-input")
    try:
        for attempt in range(2):
            try:
                response = page.goto(url, wait_until="domcontentloaded", timeout=12000)
                if response is not None and response.status in (401, 403):
                    return failed("CAPTURE_NOT_PUBLIC", "哔哩章节未开放匿名访问")
                hud.locator(".current-page").wait_for(state="attached", timeout=12000)
                break
            except BrowserError as exc:
                retryable = isinstance(exc, BrowserTimeout) or any(code in str(exc) for code in (
                    "ERR_SSL_BAD_RECORD_MAC", "ERR_CONNECTION_", "ERR_TIMED_OUT",
                ))
                if not retryable:
                    return failed("CAPTURE_PAGE_COUNT_UNKNOWN", "未能打开哔哩公开阅读页")
                if attempt == 1:
                    return failed("CAPTURE_PAGE_COUNT_UNKNOWN", "哔哩章节未提供可确认的页数，可能未公开或加载失败")
        match = re.search(r"(\d+)\s*P\b", hud.inner_text(timeout=3000))
    except (BrowserTimeout, ValueError):
        return failed("CAPTURE_PAGE_COUNT_UNKNOWN", "哔哩章节未提供可确认的页数，可能未公开或加载失败")
    if not same_chapter():
        return failed("CAPTURE_NOT_PUBLIC", "哔哩章节未公开或已跳转")
    expected = int(match.group(1)) if match else 0
    if not 1 <= expected <= 300:
        return failed("CAPTURE_PAGE_COUNT_UNKNOWN", "无法确认哔哩章节的完整页数")
    if progress:
        progress("manga", json.dumps({"event": "capture-ready",
                                      "expected_pages": expected}, ensure_ascii=False))

    # 隐藏绘制在漫画页上方的阅读控件，保持浏览器自身渲染流程。
    page.add_style_tag(content="""
        .bullet-screen, .info-hud, .floating-button, .toolbar { visibility: hidden !important }
    """)
    artifacts: dict[int, dict[str, Any]] = {}
    seen: set[bytes] = set()

    def screenshot(canvas: Any) -> tuple[bytes, bytes] | None:
        try:
            data = canvas.screenshot(timeout=1200, animations="disabled")
        except BrowserTimeout:
            return None
        with Image.open(BytesIO(data)) as image:
            stddev = ImageStat.Stat(image.convert("L").resize((48, 48))).stddev[0]
        digest = sha256(data).digest()
        return (data, digest) if stddev >= 12 and digest not in seen else None

    def save(ordinal: int, data: bytes, digest: bytes) -> None:
        name = f"page-{ordinal}.png"
        (run_dir / f"{name}.tmp").write_bytes(data)
        os.replace(run_dir / f"{name}.tmp", run_dir / name)
        artifacts[ordinal] = {"path": name, "ordinal": ordinal}
        seen.add(digest)
        if progress:
            progress("manga", json.dumps({"event": "page-ready", "ordinal": ordinal,
                                          "path": name}, ensure_ascii=False))

    def incomplete() -> dict[str, Any]:
        first_missing = next(i for i in range(1, expected + 1) if i not in artifacts)
        return failed("CAPTURE_INCOMPLETE",
                      f"哔哩章节采集不完整：已确认 {len(artifacts)}/{expected} 页，首个缺页是第 {first_missing} 页")

    def complete() -> dict[str, Any]:
        return {"status": "SUCCEEDED", "expected_pages": expected,
                "artifacts": [artifacts[i] for i in range(1, expected + 1)]}

    # 条漫阅读器以独立容器保留原始页序，并以 image-loaded 标记渲染完成。
    items = page.locator(".image-item:has(.image-container canvas)")
    if items.count():
        try:
            items.nth(expected - 1).wait_for(state="attached", timeout=3000)
        except BrowserTimeout:
            return incomplete()
        if items.count() != expected:
            return incomplete()
        for i in range(expected):
            if not same_chapter():
                return failed("CAPTURE_NOT_PUBLIC", "阅读器已离开所请求的哔哩章节")
            wait_ms = int(min(CANVAS_SPREAD_WAIT_SECONDS, deadline - monotonic()) * 1000)
            if wait_ms < 1:
                return incomplete()
            item = items.nth(i)
            try:
                item.scroll_into_view_if_needed(timeout=min(wait_ms, 2000))
                item.and_(page.locator(".image-loaded")).wait_for(state="attached", timeout=wait_ms)
                shot = screenshot(item.locator("canvas"))
            except BrowserTimeout:
                return incomplete()
            if shot is None:
                return incomplete()
            save(i + 1, *shot)
        return complete()

    try:
        spread_count = int(slider.get_attribute("max", timeout=3000) or 0)
    except (BrowserTimeout, ValueError):
        return failed("CAPTURE_PAGE_COUNT_UNKNOWN", "无法确认哔哩章节页位")
    if not 1 <= spread_count <= expected:
        return failed("CAPTURE_PAGE_COUNT_UNKNOWN", "无法确认哔哩章节页位")

    def seek_spread(spread: int) -> None:
        slider.evaluate("""(input, spread) => {
            input.value = spread;
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
        }""", str(spread), timeout=2000)

    # 先走完整章记录原站页位，再回到缺页的页组触发原站重试。
    # 暂存已确认的页，只有 1..expected 全部出现才交付清单。
    positions: dict[int, tuple[int, ...]] = {}

    def collect_spread(spread: int, first_page: int, wait_seconds: float) -> tuple[int, ...]:
        if not same_chapter():
            return ()
        if slider.input_value(timeout=2000) == str(spread) and spread in positions and spread_count > 1:
            seek_spread(spread - 1 if spread > 1 else 2)
            page.wait_for_timeout(300)
        seek_spread(spread)
        spread_deadline = min(deadline, monotonic() + wait_seconds)
        previous: dict[int, bytes] = {}
        ordinals = positions.get(spread, ())
        while monotonic() < spread_deadline and same_chapter():
            page.wait_for_timeout(350)
            first, second = hud.evaluate("""node => {
                const primary = node.querySelector('.current-page');
                const secondary = node.querySelector('.next-page');
                return [Number(primary?.textContent), Number(secondary?.textContent)];
            }""", timeout=2000)
            if first != first_page or (second and (second != first + 1 or second > expected)):
                continue
            actual = (first, second) if second else (first,)
            if ordinals and actual != ordinals:
                continue
            ordinals = actual
            selectors = (".view-container:not(.secondary-image) .image-container canvas", ".secondary-image canvas")
            current: dict[int, bytes] = {}
            for ordinal, selector in zip(ordinals, selectors):
                if ordinal in artifacts:
                    continue
                shot = screenshot(page.locator(selector))
                if shot is None or not same_chapter():
                    continue
                data, digest = shot
                if digest in current.values():
                    continue
                current[ordinal] = digest
                if previous.get(ordinal) == digest:
                    save(ordinal, data, digest)
            if all(ordinal in artifacts for ordinal in ordinals):
                break
            previous = current
        return ordinals

    first_page = 1
    for spread in range(1, spread_count + 1):
        if first_page > expected or monotonic() >= deadline:
            break
        ordinals = collect_spread(spread, first_page, min(6, CANVAS_SPREAD_WAIT_SECONDS))
        if not ordinals:
            break
        positions[spread] = ordinals
        first_page = ordinals[-1] + 1

    for _ in range(2):
        if not same_chapter():
            return failed("CAPTURE_NOT_PUBLIC", "阅读器已离开所请求的哔哩章节")
        pending = [(spread, ordinals) for spread, ordinals in positions.items()
                   if any(ordinal not in artifacts for ordinal in ordinals)]
        if not pending or monotonic() >= deadline:
            break
        for spread, ordinals in pending:
            if monotonic() >= deadline or not same_chapter():
                break
            collect_spread(spread, ordinals[0], min(12, CANVAS_SPREAD_WAIT_SECONDS))
    if not same_chapter():
        return failed("CAPTURE_NOT_PUBLIC", "阅读器已离开所请求的哔哩章节")
    return complete() if len(artifacts) == expected else incomplete()


@contextmanager
def _bilibili_browser(playwright: Any, run_dir: Any, proxy: str | None):
    """用原站支持的普通 Chromium 渲染，调试连接只负责翻页和截图。"""
    import shutil
    import signal
    import socket
    import subprocess
    import sys
    from time import monotonic, sleep

    from playwright.sync_api import Error as BrowserError

    prefix: list[str] = []
    if sys.platform.startswith("linux"):
        if not shutil.which("xvfb-run"):
            raise RuntimeError("哔哩阅读任务缺少 Chromium 所需的 Xvfb")
        prefix = ["xvfb-run", "-a", "-s", "-screen 0 1280x1100x24"]
    if proxy and (urlsplit(proxy).username is not None or urlsplit(proxy).password is not None):
        raise ValueError("哔哩浏览器代理仅支持不带凭据的地址")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    args = [
        *prefix, playwright.chromium.executable_path,
        f"--user-data-dir={run_dir / 'chromium-profile'}",
        "--remote-debugging-address=127.0.0.1", f"--remote-debugging-port={port}",
        "--no-first-run", "--no-default-browser-check", "--window-size=1280,1100",
        "--no-sandbox", "--disable-dev-shm-usage", "--disable-quic",
    ]
    if proxy:
        args.append(f"--proxy-server={proxy}")
    args.append("about:blank")
    process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               start_new_session=True)
    try:
        deadline = monotonic() + 10
        while process.poll() is None and monotonic() < deadline:
            try:
                browser = playwright.chromium.connect_over_cdp(f"http://127.0.0.1:{port}", timeout=1000)
                break
            except BrowserError:
                sleep(0.2)
        else:
            raise RuntimeError("哔哩阅读浏览器未能启动")
        try:
            yield browser.contexts[0].new_page()
        finally:
            browser.close()
    finally:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def _resolve_bilibili_detail(page: Any, url: str) -> dict[str, Any]:
    """读取官方详情页公开的章节清单，核对总数后再返回。"""
    from playwright.sync_api import Error as BrowserError

    locate = """() => {
        for (const el of document.querySelectorAll('body *')) {
            const season = el.__vue__?.$data?.seasonData;
            if (season?.episodeList?.length && season.episodeList.length === Number(season.total))
                return season;
        }
        return null;
    }"""
    failure = {"status": "FAILED", "error_code": "BILIBILI_DETAIL_UNAVAILABLE",
               "message": "暂时无法确认哔哩的完整章节列表，请前往原站"}
    for _ in range(2):
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=10000)
            page.wait_for_function(f"() => !!(({locate})())", timeout=9000)
            break
        except BrowserError:
            pass
    else:
        return failure

    if urlsplit(page.url)._replace(query="", fragment="").geturl() != url:
        return failure
    payload = page.evaluate(f"""() => {{
        const season = ({locate})();
        if (!season || season.episodeList.length > 10000) return null;
        const chapters = [...season.episodeList].sort((a,b) => a.ord-b.ord).map(ep => ({{
            id: ep.id, label: String(ep.shortTitle || ep.ord || ''), title: ep.title || '',
            public: ep.isLocked === false && ep.isFreeEpisode === true,
        }}));
        return {{title: season.title, description: season.evaluate || '',
            cover: season.verticalCover || '', comic_status: season.onShowStatusText || '',
            chapters, chapter_count: Number(season.total)}};
    }}""")
    if not payload or not payload.get("title"):
        return failure
    chapters = payload["chapters"]
    ids = [c.get("id") for c in chapters]
    if (len(ids) != payload["chapter_count"] or any(type(i) is not int or not 0 < i < 10**12 for i in ids)
            or len(set(ids)) != len(ids)):
        return failure
    return {"status": "SUCCEEDED", **payload}


def run(spec: dict[str, Any], run_dir: Any, progress: Callable[..., None]) -> dict[str, Any]:
    params = spec.get("params") or {}
    mode = str(params.get("mode") or "").strip()
    url = str(params.get("url") or "").strip()
    cookie = str(params.get("cookie") or "").strip()
    storage_state = str(params.get("storage_state") or "").strip() or None
    if mode not in ("detail", "chapter", "search", "chapter-canvas", "bilibili-detail"):
        return {"status": "FAILED", "error_code": "BAD_MODE", "message": f"未知 mode：{mode}"}
    if mode == "chapter-canvas":
        if not re.fullmatch(r"https://manga\.bilibili\.com/mc[0-9]{1,12}/[0-9]{1,12}", url):
            return {"status": "FAILED", "error_code": "BAD_URL",
                    "message": "canvas 模式仅允许哔哩漫画站内 URL"}
    elif mode == "bilibili-detail":
        if not re.fullmatch(r"https://manga\.bilibili\.com/detail/mc[0-9]{1,12}", url):
            return {"status": "FAILED", "error_code": "BAD_URL", "message": "仅允许哔哩漫画详情 URL"}
    elif not (url.startswith("https://www.mangacopy.com/")
            or url.startswith("https://www.copy4000.com/")):
        return {"status": "FAILED", "error_code": "BAD_URL", "message": "仅允许 mangacopy.com 站内 URL"}

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {"status": "FAILED", "error_code": "PLAYWRIGHT_MISSING",
                "message": "Worker 缺少 playwright 依赖"}

    progress("manga", f"启动浏览器（{mode}）")
    with sync_playwright() as p:
        proxy = os.environ.get("MANGASHELF_BROWSER_PROXY")
        if mode in ("chapter-canvas", "bilibili-detail"):
            with _bilibili_browser(p, run_dir, proxy) as page:
                if mode == "bilibili-detail":
                    return _resolve_bilibili_detail(page, url)
                return _resolve_chapter_canvas(page, url, run_dir, progress)
        launch_args = [
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-quic",
        ]
        if not proxy:
            launch_args.append("--no-proxy-server")
        browser = p.chromium.launch(args=launch_args, proxy={"server": proxy} if proxy else None)
        try:
            context_kwargs: dict[str, Any] = {
                "user_agent": UA,
                "viewport": {"width": 1280, "height": 1000},
                "locale": "zh-TW",
            }
            if storage_state:
                context_kwargs["storage_state"] = storage_state
            context = browser.new_context(**context_kwargs)
            if cookie:
                context.add_cookies(_parse_cookies(cookie, urlsplit(url).hostname or "www.mangacopy.com"))
            page = context.new_page()
            progress("manga", f"打开 {url}")
            if mode == "detail":
                return _resolve_detail(page, url)
            if mode == "search":
                return _resolve_search(page, url)
            return _resolve_chapter(page, url)
        finally:
            browser.close()
