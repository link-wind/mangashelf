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


def _parse_cookies(cookie: str) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for part in (cookie or "").split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        name, value = name.strip(), value.strip()
        if not name:
            continue
        for domain in (".copy4000.com", "www.copy4000.com"):
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


def _resolve_detail(page: Any, url: str) -> dict[str, Any]:
    page.goto(url, wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_SECONDS * 1000)
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


def _resolve_chapter(page: Any, url: str) -> dict[str, Any]:
    page.goto(url, wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_SECONDS * 1000)
    page.wait_for_timeout(2000)
    # 章节图由 JS 解密后写入 data-src，滚动触发 lazysizes 拉取
    for _ in range(LAZY_SCROLL_ROUNDS):
        page.evaluate("window.scrollBy(0, document.body.scrollHeight)")
        page.wait_for_timeout(700)

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


def run(spec: dict[str, Any], run_dir: Any, progress: Callable[..., None]) -> dict[str, Any]:
    params = spec.get("params") or {}
    mode = str(params.get("mode") or "").strip()
    url = str(params.get("url") or "").strip()
    cookie = str(params.get("cookie") or "").strip()
    storage_state = str(params.get("storage_state") or "").strip() or None
    if mode not in ("detail", "chapter"):
        return {"status": "FAILED", "error_code": "BAD_MODE", "message": f"未知 mode：{mode}"}
    if not url.startswith("https://www.copy4000.com/"):
        return {"status": "FAILED", "error_code": "BAD_URL", "message": "仅允许 copy4000.com 站内 URL"}

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
        ])
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
                context.add_cookies(_parse_cookies(cookie))
            page = context.new_page()
            progress("manga", f"打开 {url}")
            if mode == "detail":
                return _resolve_detail(page, url)
            return _resolve_chapter(page, url)
        finally:
            browser.close()
