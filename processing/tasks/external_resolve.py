"""external.resolve：Playwright 嗅探外部剧集页的视频直链（M7 design.md D5）。

加载剧集页，监听网络层捕获首个 m3u8/mp4 请求。对源站加密层透明：
无头浏览器执行与真实访问者相同的 JS/请求链路，不做逐站逆向。
episode_url 由 Java Worker 从 external_episodes 读取后经 params 下发，
Python 不接收用户直接提交的任意 URL（AGENTS.md 安全边界）。
"""

from __future__ import annotations

import re
import time
from collections.abc import Callable
from typing import Any
from urllib.parse import urlsplit

VIDEO_URL_RE = re.compile(r"\.(m3u8|mp4)(\?|$)", re.IGNORECASE)
DEFAULT_TIMEOUT_SECONDS = 30
PLAY_CLICK_AT_SECONDS = 8.0

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


def run(spec: dict[str, Any], run_dir: Any, progress: Callable[..., None]) -> dict[str, Any]:
    params = spec.get("params") or {}
    episode_url = str(params.get("episode_url") or "").strip()
    if not episode_url:
        return {"status": "FAILED", "error_code": "NO_EPISODE_URL", "message": "params 缺少 episode_url"}
    referer = str(params.get("referer") or "").strip() or None
    cookie = str(params.get("cookie") or "").strip() or None
    timeout_seconds = float(params.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {"status": "FAILED", "error_code": "PLAYWRIGHT_MISSING",
                "message": "Worker 缺少 playwright 依赖"}

    progress("sniff", "启动无头浏览器")

    # 直链快路径：剧集地址本身即 m3u8/mp4（资源站 API 线路），免嗅探
    if _is_video_url(episode_url):
        kind = "hls" if ".m3u8" in episode_url.lower() else "mp4"
        progress("sniff", f"剧集地址已是直链（{kind}），免嗅探")
        return {"status": "SUCCEEDED", "play_url": episode_url, "play_kind": kind}
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
    kind = "hls" if ".m3u8" in url.lower() else "mp4"
    progress("sniff", f"捕获视频直链（{kind}）")
    return {"status": "SUCCEEDED", "play_url": url, "play_kind": kind}
