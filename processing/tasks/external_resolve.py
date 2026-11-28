"""external.resolve：外部剧集页视频直链解析（M7 design.md D5 + MacCMS 秒解快路径）。

优先级：
1. 直链快路径：episode_url 本身即 .m3u8/.mp4，零网络开销直接返回。
2. MacCMS player_aaaa 纯 HTTP 快路径：抓取剧集页 HTML 解码 player_aaaa（encrypt=0/1/2），
   若为 .m3u8/.mp4 直链（如 7sefun 七色B线），单跳 HEAD 读 302 Location 返回，免启 Chromium。
3. 无头浏览器嗅探兜底：Playwright 加载剧集页并监听首个 .m3u8/.mp4 网络请求。

episode_url 由 Java Worker 从 external_episodes 读取后经 params 下发，
Python 不接收用户直接提交的任意 URL（AGENTS.md 安全边界）。
"""

from __future__ import annotations

import base64
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from typing import Any
from urllib.parse import urljoin, urlsplit

VIDEO_URL_RE = re.compile(r"\.(m3u8|mp4)(\?|$)", re.IGNORECASE)
PLAYER_AAAA_RE = re.compile(r"player_aaaa\s*=\s*(\{.*?\})\s*</script>", re.S | re.I)
DEFAULT_TIMEOUT_SECONDS = 30
HTTP_FASTPATH_TIMEOUT_SECONDS = 12
PLAY_CLICK_AT_SECONDS = 8.0
DEFAULT_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

# 常见起播按钮：嗅探站点播放器各异，点击只是兜底（多数站自动起播）
PLAY_BUTTON_SELECTORS = [
    "video",
    ".art-video-player",
    ".dplayer-play-btn",
    ".play-btn",
    "[class*='play'][role='button']",
    "button[aria-label*='播放']",
    "button:has-text('播放')",
]


def _is_video_url(url: str) -> bool:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https"):
        return False
    return bool(VIDEO_URL_RE.search(parts.path) or VIDEO_URL_RE.search(url.split("?", 1)[0]))


def _video_kind(url: str) -> str:
    return "hls" if ".m3u8" in url.split("?", 1)[0].lower() else "mp4"


def extract_maccms_player_url(html: str) -> str | None:
    """从 MacCMS 播放页 HTML 中提取并解码 player_aaaa.url。"""
    m = PLAYER_AAAA_RE.search(html or "")
    if not m:
        return None
    try:
        payload = json.loads(m.group(1))
    except (json.JSONDecodeError, ValueError):
        return None
    raw_url = str(payload.get("url") or "").strip()
    if not raw_url:
        return None
    try:
        encrypt = int(payload.get("encrypt") or 0)
    except (TypeError, ValueError):
        encrypt = 0
    if encrypt == 2:
        try:
            decoded_bytes = base64.b64decode(raw_url)
            raw_url = urllib.parse.unquote(decoded_bytes.decode("utf-8", errors="replace"))
        except Exception:
            return None
    elif encrypt == 1:
        raw_url = urllib.parse.unquote(raw_url)
    return raw_url.strip() or None


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        return None


def _http_fetch_text(url: str, headers: dict[str, str], timeout: float) -> str | None:
    last_err: Exception | None = None
    for attempt in range(2):
        req = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if getattr(resp, "status", 200) != 200:
                    return None
                return resp.read().decode("utf-8", errors="replace")
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            last_err = e
            if attempt == 0:
                time.sleep(0.4)
    _ = last_err
    return None


def _probe_single_redirect(url: str, headers: dict[str, str], timeout: float) -> str:
    """对直链做一次不跟跳 HEAD，若返回 301/302 Location 且为合法视频地址则采用之。"""
    opener = urllib.request.build_opener(_NoRedirectHandler())
    req = urllib.request.Request(url, headers=headers, method="HEAD")
    try:
        with opener.open(req, timeout=timeout) as resp:
            loc = resp.headers.get("Location")
            if loc:
                resolved = urljoin(url, loc.strip())
                if _is_video_url(resolved):
                    return resolved
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            loc = e.headers.get("Location")
            if loc:
                resolved = urljoin(url, loc.strip())
                if _is_video_url(resolved):
                    return resolved
    except Exception:
        pass
    return url


def _try_maccms_http_resolve(
    episode_url: str,
    referer: str | None,
    cookie: str | None,
    timeout: float,
    progress: Callable[..., None],
) -> tuple[str, str] | None:
    headers: dict[str, str] = {"User-Agent": DEFAULT_UA}
    if referer:
        headers["Referer"] = referer
    if cookie:
        headers["Cookie"] = cookie

    html = _http_fetch_text(episode_url, headers, min(timeout, HTTP_FASTPATH_TIMEOUT_SECONDS))
    if not html:
        return None
    decoded = extract_maccms_player_url(html)
    if not decoded or not _is_video_url(decoded):
        return None

    probe_headers = {"User-Agent": DEFAULT_UA}
    final_url = _probe_single_redirect(decoded, probe_headers, min(timeout, HTTP_FASTPATH_TIMEOUT_SECONDS))
    kind = _video_kind(final_url)
    progress("sniff", f"HTTP 快路径捕获视频直链（{kind}）")
    return final_url, kind


def run(spec: dict[str, Any], run_dir: Any, progress: Callable[..., None]) -> dict[str, Any]:
    params = spec.get("params") or {}
    episode_url = str(params.get("episode_url") or "").strip()
    if not episode_url:
        return {"status": "FAILED", "error_code": "NO_EPISODE_URL", "message": "params 缺少 episode_url"}
    referer = str(params.get("referer") or "").strip() or None
    cookie = str(params.get("cookie") or "").strip() or None
    timeout_seconds = float(params.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)

    # 1. 直链快路径：剧集地址本身即 m3u8/mp4（资源站 API 线路），免嗅探
    if _is_video_url(episode_url):
        kind = _video_kind(episode_url)
        progress("sniff", f"剧集地址已是直链（{kind}），免嗅探")
        return {"status": "SUCCEEDED", "play_url": episode_url, "play_kind": kind}

    # 2. MacCMS player_aaaa 纯 HTTP 快路径（如 7sefun 七色B线），免启动 Chromium
    progress("sniff", "尝试 HTTP 快路径解析剧集页")
    fast = _try_maccms_http_resolve(episode_url, referer, cookie, timeout_seconds, progress)
    if fast is not None:
        return {"status": "SUCCEEDED", "play_url": fast[0], "play_kind": fast[1]}

    # 3. 无头浏览器嗅探兜底
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {"status": "FAILED", "error_code": "PLAYWRIGHT_MISSING",
                "message": "Worker 缺少 playwright 依赖"}

    progress("sniff", "启动无头浏览器")
    found: list[str] = []

    def on_request(req: Any) -> None:
        if not found and _is_video_url(req.url):
            found.append(req.url)

    with sync_playwright() as p:
        browser = p.chromium.launch(args=[
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--autoplay-policy=no-user-gesture-required",
        ])
        try:
            context_kwargs: dict[str, Any] = {}
            headers: dict[str, str] = {}
            if referer:
                headers["Referer"] = referer
            # 登录墙源：仅为服务端持有的 Cookie（用户不直接提交 URL/Cookie）
            if cookie:
                headers["Cookie"] = cookie
            if headers:
                context_kwargs["extra_http_headers"] = headers
            page = browser.new_context(**context_kwargs).new_page()
            page.on("request", on_request)

            progress("sniff", f"打开剧集页 {episode_url}")
            page.goto(episode_url, wait_until="domcontentloaded", timeout=timeout_seconds * 1000)

            started = time.monotonic()
            deadline = started + timeout_seconds
            clicked = False
            while time.monotonic() < deadline and not found:
                if not clicked and time.monotonic() - started > PLAY_CLICK_AT_SECONDS:
                    for selector in PLAY_BUTTON_SELECTORS:
                        try:
                            el = page.query_selector(selector)
                            if el:
                                el.click(timeout=1000)
                                progress("sniff", "尝试点击起播")
                                break
                        except Exception:
                            continue
                    clicked = True
                page.wait_for_timeout(300)
        finally:
            browser.close()

    if not found:
        return {"status": "FAILED", "error_code": "NO_VIDEO_FOUND",
                "message": f"{timeout_seconds:.0f} 秒内未嗅探到视频流"}

    url = found[0]
    kind = _video_kind(url)
    progress("sniff", f"捕获视频直链（{kind}）")
    return {"status": "SUCCEEDED", "play_url": url, "play_kind": kind}
