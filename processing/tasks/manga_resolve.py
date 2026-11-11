"""manga.resolve：Playwright 打开漫画网页版解析详情/章节页图（M29）。

与动画 external.resolve 同构：无头浏览器执行与真实访问者相同的 JS（拷贝漫画
章节图由前端 AES 解密后写入 img[data-src]），不做逐站逆向。游客章节页
contentKey 为空、0 页，必须带登录 Cookie（或 Playwright storage_state）。
URL 由 Java 源实现按固定模板拼接，Python 不接收用户直接提交的任意 URL。
"""

from __future__ import annotations

import re
from typing import Any, Callable
from urllib.parse import urlsplit

DEFAULT_TIMEOUT_SECONDS = 45
LAZY_SCROLL_ROUNDS = 8
MIN_IMAGE_WIDTH = 300

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
    # 多数章节 JS 解密后已写入 data-src：先无滚动提取，失败再滚动触发 lazysizes
    pages = _collect_pages(page)
    if not pages:
        for _ in range(LAZY_SCROLL_ROUNDS):
            page.evaluate("window.scrollBy(0, document.body.scrollHeight)")
            page.wait_for_timeout(700)
        pages = _collect_pages(page)

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


def _resolve_chapter_canvas(page: Any, url: str, run_dir: Any) -> dict[str, Any]:
    """哔哩漫画式 canvas 阅读器：页图渲染在 <canvas>，翻页逐屏截图（M35）。

    - 等 canvas 出现并完成 WASM 解密渲染（实测 2~20s）；
    - 对每个 canvas 元素截图（PNG，450×700 实测可读）；
    - 键盘 ArrowRight 翻页，采集至达到页数提示或连续无变化；
    - 输出 artifacts=[{path, ordinal}]，由 Java 上传对象存储。
    """
    import hashlib
    _goto_with_retry(page, url, DEFAULT_TIMEOUT_SECONDS)
    canvas_count = 0
    for _ in range(15):
        page.wait_for_timeout(2000)
        canvas_count = page.locator("canvas").count()
        if canvas_count > 0:
            break
    if canvas_count == 0:
        body = page.inner_text("body")
        return {"status": "FAILED", "error_code": "NO_CANVAS",
                "message": f"阅读页未渲染 canvas（可能需登录/付费）：{body[:60]}"}

    total_hint = 0
    try:
        m = re.search(r"(\d+)\s*P", page.inner_text("body"))
        if m:
            total_hint = int(m.group(1))
    except Exception:
        pass

    artifacts: list[dict[str, Any]] = []
    seen: set[str] = set()
    # 连续多轮无新图才算翻完（翻页后 canvas 可能短暂显示加载中）
    stale_rounds = 0
    stale_limit = 6
    for step in range(max(total_hint * 3, 120)):
        canvases = page.locator("canvas").element_handles()
        new_in_round = 0
        for c in canvases:
            try:
                data = c.screenshot(timeout=8000)
            except Exception:
                continue
            digest = hashlib.md5(data).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            # 过滤页尾/空白 canvas（字节数过小）
            if len(data) < 10000:
                continue
            path = run_dir / f"canvas-{len(artifacts):04d}.png"
            path.write_bytes(data)
            artifacts.append({"path": path.name, "ordinal": len(artifacts) + 1})
            new_in_round += 1
        if total_hint and len(artifacts) >= total_hint:
            break
        if new_in_round == 0:
            stale_rounds += 1
            if stale_rounds >= stale_limit:
                break
        else:
            stale_rounds = 0
        page.keyboard.press("ArrowRight")
        page.wait_for_timeout(2500)

    if not artifacts:
        return {"status": "FAILED", "error_code": "NO_SHOTS",
                "message": "canvas 截图未采集到页图"}
    return {"status": "SUCCEEDED", "page_count": len(artifacts), "artifacts": artifacts}


def run(spec: dict[str, Any], run_dir: Any, progress: Callable[..., None]) -> dict[str, Any]:
    params = spec.get("params") or {}
    mode = str(params.get("mode") or "").strip()
    url = str(params.get("url") or "").strip()
    cookie = str(params.get("cookie") or "").strip()
    storage_state = str(params.get("storage_state") or "").strip() or None
    if mode not in ("detail", "chapter", "search", "chapter-canvas"):
        return {"status": "FAILED", "error_code": "BAD_MODE", "message": f"未知 mode：{mode}"}
    if mode == "chapter-canvas":
        allowed = ("https://manga.bilibili.com/",)
        if not url.startswith(allowed):
            return {"status": "FAILED", "error_code": "BAD_URL",
                    "message": "canvas 模式仅允许哔哩漫画站内 URL"}
    elif not (url.startswith("https://www.mangacopy.com/")
            or url.startswith("https://www.copy4000.com/")):
        return {"status": "FAILED", "error_code": "BAD_URL", "message": "仅允许 mangacopy.com 站内 URL"}

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {"status": "FAILED", "error_code": "PLAYWRIGHT_MISSING",
                "message": "Worker 缺少 playwright 依赖"}

    progress("manga", f"启动无头浏览器（{mode}）")
    with sync_playwright() as p:
        browser = p.chromium.launch(args=[
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--no-proxy-server",
            "--disable-quic",
        ])
        try:
            context_kwargs: dict[str, Any] = {
                "user_agent": UA,
                "viewport": {"width": 1280, "height": 1000},
                "locale": "zh-TW",
                # Clash 系统代理下的 TUN 干扰直连；走代理反而更稳定（M35 实测）
                "proxy": {"server": "http://127.0.0.1:7892"},
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
            if mode == "chapter-canvas":
                return _resolve_chapter_canvas(page, url, run_dir)
            return _resolve_chapter(page, url)
        finally:
            browser.close()
