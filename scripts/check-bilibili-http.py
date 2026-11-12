"""固定公开章节的纯 HTTP 取图验证；可选观察浏览器请求，不保存凭据或签名 URL。"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
from time import monotonic
import urllib.request
from urllib.parse import urlencode, urlsplit

from PIL import Image


CHAPTERS = {
    "004": "https://manga.bilibili.com/mc24742/218098",
    "nonhuman": "https://manga.bilibili.com/mc24442/209069",
}
EXPECTED_PAGES = {"004": 20, "nonhuman": 9}
AGENT = ("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
APP_QUERY = {
    "device": "android", "platform": "android", "mobi_app": "android_comic",
    "version": "4.13.0", "build": "36413002", "channel": "bilicomic",
}


def emit(event, **fields):
    print(json.dumps({"event": event, **fields}, ensure_ascii=False), flush=True)


def image_info(body):
    try:
        with Image.open(io.BytesIO(body)) as image:
            image.load()
            return {"format": image.format, "width": image.width, "height": image.height}
    except (OSError, ValueError):
        return {"format": None}


def json_info(body):
    try:
        payload = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return {}
    if not isinstance(payload, dict):
        return {"json_type": type(payload).__name__}
    data = payload.get("data")
    result = {"code": payload.get("code"), "data_type": type(data).__name__,
              "top_fields": {name: type(value).__name__ for name, value in payload.items()}}
    if isinstance(data, dict):
        result["data_keys"] = sorted(data)
        for name in ("images", "ep_list"):
            items = data.get(name)
            if isinstance(items, list):
                result[name + "_count"] = len(items)
                if items and isinstance(items[0], dict):
                    result[name + "_item_keys"] = sorted(items[0])
    elif isinstance(data, list):
        result["item_count"] = len(data)
        if data and isinstance(data[0], dict):
            result["item_keys"] = sorted(data[0])
    return result


def check_http(chapter, network):
    started = monotonic()
    proxy_handler = (urllib.request.ProxyHandler({})
                     if network == "direct" else urllib.request.ProxyHandler())
    opener = urllib.request.build_opener(proxy_handler)
    headers = {
        "User-Agent": AGENT, "Content-Type": "application/json",
        "Origin": "https://manga.bilibili.com", "Referer": "https://manga.bilibili.com/",
    }
    emit("start", mode="http", chapter=chapter, network=network, browser_started=False)

    def fetch(url, data=None):
        target = urlsplit(url)
        if (target.scheme != "https" or target.username or target.password
                or not (target.hostname in ("api.bilibili.com", "manga.bilibili.com")
                        or (target.hostname or "").endswith((".hdslb.com", ".bcomic.com")))):
            raise ValueError("HTTP 地址不在固定来源范围内")
        request_headers = headers.copy()
        if target.hostname not in ("api.bilibili.com", "manga.bilibili.com"):
            request_headers.pop("Cookie", None)
        request = urllib.request.Request(
            url, data=json.dumps(data).encode() if data is not None else None,
            headers=request_headers,
        )
        with opener.open(request, timeout=20) as response:
            body = response.read(12 * 1024 * 1024 + 1)
            if len(body) > 12 * 1024 * 1024:
                raise ValueError("HTTP 响应超过验证上限")
            return body

    fingerprint = json.loads(fetch("https://api.bilibili.com/x/frontend/finger/spi"))
    fingerprint_data = fingerprint.get("data") or {}
    if fingerprint.get("code") != 0 or not fingerprint_data.get("b_3"):
        raise ValueError("匿名指纹初始化失败")
    headers["Cookie"] = "; ".join(
        name + "=" + fingerprint_data[key] for key, name in
        (("b_3", "buvid3"), ("b_4", "buvid4")) if fingerprint_data.get(key)
    )
    emit("fingerprint", available=True, user_session=False)

    def api_call(method, payload):
        body = fetch("https://manga.bilibili.com/twirp/comic.v1.Comic/"
                     + method + "?" + urlencode(APP_QUERY), payload)
        summary = json_info(body)
        emit("http_api", kind=method, **summary)
        result = json.loads(body)
        if result.get("code") != 0 or not result.get("data"):
            raise ValueError(method + " 未返回有效数据")
        return result["data"]

    episode = int(CHAPTERS[chapter].rsplit("/", 1)[-1])
    index = api_call("GetImageIndex", {"epId": episode})
    images = index.get("images") or []
    if len(images) != EXPECTED_PAGES[chapter]:
        raise ValueError("索引页数与已知公开样本不一致")
    expected = len(images)
    emit("index", expected_pages=expected,
         item_fields=sorted(images[0]) if isinstance(images[0], dict) else [])
    completed = []
    total_bytes = 0
    with TemporaryDirectory(prefix="mangashelf-http-pages-") as temporary:
        for ordinal, entry in enumerate(images, 1):
            path = entry.get("path")
            if not isinstance(path, str) or not path.startswith("/"):
                raise ValueError("索引页面路径无效")
            token_data = api_call("ImageToken", {"urls": json.dumps([path])})
            if not isinstance(token_data, list) or len(token_data) != 1:
                raise ValueError("单页令牌响应数量不符")
            token = token_data[0]
            image_url = token.get("complete_url")
            if not image_url:
                image_url = token["url"] + "?" + urlencode({"token": token["token"]})
            body = fetch(image_url)
            info = image_info(body)
            if not info["format"]:
                raise ValueError("HTTP 返回内容不是可直接解码的完整图片")
            if info["width"] < 100 or info["height"] < 100:
                raise ValueError("图片尺寸不足以作为漫画页")
            for field, declared_field in (("width", "x"), ("height", "y")):
                declared = entry.get(declared_field)
                if isinstance(declared, int) and declared > 0 and declared != info[field]:
                    raise ValueError("图片尺寸与章节索引不一致")
            digest = hashlib.sha256(body).hexdigest()
            if digest in completed:
                raise ValueError("索引不同页返回重复图片")
            completed.append(digest)
            total_bytes += len(body)
            destination = Path(temporary) / f"{ordinal:03d}.image"
            destination.write_bytes(body)
            with Image.open(destination) as saved:
                saved.load()
            emit("page", ordinal=ordinal, bytes=len(body), directly_decodable=True,
                 sha256=digest, **info)
    emit("complete", chapter=chapter, expected_pages=expected, decoded_pages=len(completed),
         total_bytes=total_bytes, seconds=round(monotonic() - started, 2),
         browser_started=False, screenshots=0, artifacts_retained=False)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chapter", choices=CHAPTERS, default="004")
    parser.add_argument("--mode", choices=("http", "observe"), default="http")
    parser.add_argument("--network", choices=("direct", "system-proxy"), default="direct")
    args = parser.parse_args()
    if args.mode == "http":
        return check_http(args.chapter, args.network)
    from playwright.sync_api import sync_playwright

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from processing.tasks.manga_resolve import _bilibili_browser
    observed = []
    replay = []
    chapter_url = CHAPTERS[args.chapter]
    with TemporaryDirectory(prefix="mangashelf-http-check-") as temporary:
        with sync_playwright() as playwright:
            with _bilibili_browser(
                playwright, Path(temporary), os.environ.get("MANGASHELF_BROWSER_PROXY")
            ) as page:
                def on_response(response):
                    request = response.request
                    target = urlsplit(response.url)
                    is_api = target.hostname == "manga.bilibili.com" and "/twirp/" in target.path
                    is_media = (
                        (target.hostname or "").endswith((".hdslb.com", ".bcomic.com"))
                        and request.resource_type in ("fetch", "xhr", "image")
                    )
                    if not is_api and not is_media:
                        return
                    try:
                        body = response.body()
                        label = target.path.rsplit("/", 1)[-1] if is_api else "media"
                        summary = {
                            "kind": label, "host": target.hostname, "status": response.status,
                            "bytes": len(body), "content_type": response.headers.get("content-type"),
                        }
                        summary.update(json_info(body) if is_api else image_info(body))
                        observed.append(summary)
                        emit("response", **summary)
                        if is_api and label in ("GetImageIndex", "ImageToken"):
                            request_body = request.post_data or ""
                            try:
                                parsed_body = json.loads(request_body)
                                body_info = {name: type(value).__name__
                                             for name, value in parsed_body.items()}
                            except (ValueError, AttributeError):
                                body_info = {"encoding": "non-json"}
                            emit("request_shape", kind=label, fields=body_info,
                                 query_fields=sorted(
                                     part.partition("=")[0] for part in target.query.split("&")
                                 ))
                            replay[:] = [entry for entry in replay if entry[0] != label]
                            replay.append((label, response.url, request.method, request.post_data,
                                           request.all_headers()))
                    except Exception as error:
                        emit("response_error", error_type=type(error).__name__)

                page.on("response", on_response)
                try:
                    page.goto(chapter_url, wait_until="domcontentloaded", timeout=30000)
                    page.locator(".info-hud .current-page").wait_for(timeout=20000)
                    page.wait_for_timeout(8000)
                    text = page.locator(".info-hud").inner_text(timeout=3000)
                    match = re.search(r"(\d+)\s*P\b", text)
                    expected = int(match.group(1)) if match else 0
                    emit("chapter", chapter=args.chapter, expected_pages=expected)
                    slider = page.locator("input.range-input")
                    if expected and slider.count():
                        slider.evaluate("""(element, total) => {
                            element.value = String(total);
                            element.dispatchEvent(new Event('input', {bubbles: true}));
                            element.dispatchEvent(new Event('change', {bubbles: true}));
                        }""", expected)
                        page.wait_for_timeout(12000)
                    emit("browser_observation", api_responses=len(observed), replay_requests=len(replay))
                except Exception as error:
                    emit("browser_error", error_type=type(error).__name__)
        emit("browser_closed")
        for label, url, method, data, headers in replay:
            safe_headers = {
                name: value for name, value in headers.items()
                if name.lower() in ("user-agent", "referer", "origin", "content-type", "cookie")
            }
            request = urllib.request.Request(
                url, data=data.encode() if data else None, method=method, headers=safe_headers
            )
            try:
                with urllib.request.urlopen(request, timeout=20) as response:
                    body = response.read()
                    emit("http_replay", kind=label, status=response.status,
                         bytes=len(body), **json_info(body))
            except Exception as error:
                emit("http_replay_error", kind=label, error_type=type(error).__name__)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        emit("failed", error_type=type(error).__name__)
        raise SystemExit(1)
