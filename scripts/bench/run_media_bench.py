#!/usr/bin/env python3
"""
MangaShelf 媒体交付链路端到端实测脚本（audit-and-measure-media-delivery）。

严格使用本机合规自生成素材（Pillow 合成 20 页 1080x1600 WebP 漫画 + ffmpeg 合成
10s 1280x720 H.264/AAC faststart MP4 视频与封面/字幕），不依赖第三方未授权资源。

用法：
  # 1. 准备合规测试素材并写入真实 SeaweedFS + PostgreSQL
  processing/.venv/bin/python scripts/bench/run_media_bench.py --mode prepare --fixture /tmp/media-fixture.json

  # 2. 在指定 API 上执行基线或优化模式实测
  processing/.venv/bin/python scripts/bench/run_media_bench.py --mode bench --profile baseline \
      --api-base http://127.0.0.1:18089 --fixture /tmp/media-fixture.json --out /tmp/media-baseline.json
  processing/.venv/bin/python scripts/bench/run_media_bench.py --mode bench --profile optimized \
      --api-base http://127.0.0.1:18089 --fixture /tmp/media-fixture.json --out /tmp/media-optimized.json

  # 3. 清理全部测试数据与对象存储文件
  processing/.venv/bin/python scripts/bench/run_media_bench.py --mode cleanup --fixture /tmp/media-fixture.json
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import hmac
import http.client
import io
import json
import os
import random
import statistics
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw

ADMIN_OWNER_ID = "00000000-0000-0000-0000-000000000001"
S3_ENDPOINT = os.environ.get("MANGASHELF_S3_ENDPOINT", "http://127.0.0.1:9000")
S3_BUCKET = os.environ.get("MANGASHELF_S3_BUCKET", "mangashelf")
S3_ACCESS_KEY = os.environ.get("MANGASHELF_S3_ACCESS_KEY", "mangashelf")
S3_SECRET_KEY = os.environ.get("MANGASHELF_S3_SECRET_KEY", "mangashelf-secret")
S3_REGION = os.environ.get("MANGASHELF_S3_REGION", "us-east-1")
DOCKER_BIN = os.environ.get("DOCKER_BIN", "/usr/local/bin/docker")
DB_CONTAINER = os.environ.get("DB_CONTAINER", "mangashelf-db-1")


# ── S3 SigV4 工具（零第三方依赖）──

def _sign(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def _sig_key(secret: str, date_stamp: str, region: str, service: str) -> bytes:
    return _sign(_sign(_sign(_sign(("AWS4" + secret).encode("utf-8"), date_stamp), region), service), "aws4_request")


def s3_presign(method: str, key: str, expires: int = 300) -> str:
    parsed = urllib.parse.urlparse(S3_ENDPOINT)
    host = parsed.netloc
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    canonical_uri = f"/{S3_BUCKET}/{key}"
    scope = f"{date_stamp}/{S3_REGION}/s3/aws4_request"
    params = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": f"{S3_ACCESS_KEY}/{scope}",
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(expires),
        "X-Amz-SignedHeaders": "host",
    }
    qs = "&".join(f"{urllib.parse.quote(k, safe='-_.~')}={urllib.parse.quote(v, safe='-_.~')}" for k, v in sorted(params.items()))
    canonical_req = "\n".join([method, canonical_uri, qs, f"host:{host}\n", "host", "UNSIGNED-PAYLOAD"])
    string_to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_req.encode("utf-8")).hexdigest()])
    sig = hmac.new(_sig_key(S3_SECRET_KEY, date_stamp, S3_REGION, "s3"), string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{S3_ENDPOINT}{canonical_uri}?{qs}&X-Amz-Signature={sig}"


def s3_put(key: str, data: bytes, content_type: str, cache_control: str | None = None) -> None:
    url = s3_presign("PUT", key)
    headers = {"Content-Type": content_type}
    if cache_control:
        headers["Cache-Control"] = cache_control
    req = urllib.request.Request(url, data=data, method="PUT", headers=headers)
    with urllib.request.urlopen(req, timeout=30) as resp:
        assert resp.status in (200, 204), f"S3 PUT failed: {resp.status}"


def s3_delete(key: str) -> None:
    url = s3_presign("DELETE", key)
    req = urllib.request.Request(url, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=15):
            pass
    except urllib.error.HTTPError:
        pass


def psql(sql: str) -> str:
    proc = subprocess.run(
        [DOCKER_BIN, "exec", "-i", DB_CONTAINER, "psql", "-U", "mangashelf", "-d", "mangashelf", "-v", "ON_ERROR_STOP=1", "-q"],
        input=sql, text=True, capture_output=True, check=True,
    )
    return proc.stdout


# ── 合成合规漫画页图与 MP4 测试视频 ──

def generate_comic_page(ordinal: int) -> tuple[bytes, bytes, int, int]:
    """合成具有网点/纹理与漫画分格的 1080x1600 WebP 阅读图与 240x320 缩略图。"""
    w, h = 1080, 1600
    rng = random.Random(20261125 + ordinal)
    # 造带有噪点/线条的像素块，使 WebP 压缩后保持接近真实漫画页体积（~80-150KB）
    raw = bytearray(w * h * 3)
    base_r = (ordinal * 29) % 180 + 40
    base_g = (ordinal * 53) % 180 + 40
    base_b = (ordinal * 71) % 180 + 40
    # 行带状渐变 + 局部噪声颗粒（快速生成且不可轻易 RLE 压成 2KB）
    row_noise = bytes(rng.getrandbits(8) for _ in range(w * 3))
    for y in range(h):
        shift = (y * 17 + ordinal * 31) % (w * 3)
        start = y * w * 3
        part = row_noise[shift:] + row_noise[:shift]
        # 混入一点渐变线条感
        if (y // 16) % 2 == 0:
            raw[start:start + w * 3] = part
        else:
            raw[start:start + w * 3] = bytes((b ^ ((base_r + y) & 0xFF)) for b in part)

    img = Image.frombytes("RGB", (w, h), bytes(raw))
    draw = ImageDraw.Draw(img)
    # 画 4 个漫画分格边框和页码
    draw.rectangle([30, 30, w - 30, h - 30], outline=(20, 20, 20), width=10)
    draw.rectangle([60, 60, w - 60, 520], fill=(base_r, base_g, base_b), outline=(10, 10, 10), width=6)
    draw.rectangle([60, 560, 520, 1100], outline=(10, 10, 10), width=6)
    draw.rectangle([560, 560, w - 60, 1100], outline=(10, 10, 10), width=6)
    draw.rectangle([60, 1140, w - 60, h - 60], outline=(10, 10, 10), width=6)
    draw.text((100, 100), f"MangaShelf Synthetic Benchmark Page #{ordinal:02d}", fill=(255, 255, 255))

    buf_reader = io.BytesIO()
    img.save(buf_reader, format="WEBP", quality=85, method=4)
    reader_bytes = buf_reader.getvalue()

    thumb = img.copy()
    thumb.thumbnail((240, 320))
    buf_thumb = io.BytesIO()
    thumb.save(buf_thumb, format="WEBP", quality=80)
    thumb_bytes = buf_thumb.getvalue()
    return reader_bytes, thumb_bytes, w, h


def generate_synthetic_mp4() -> tuple[bytes, bytes, bytes]:
    """使用 ffmpeg 合成 10s 1280x720 30fps H.264+AAC faststart MP4、poster.jpg 与 subtitles.vtt。"""
    with tempfile.TemporaryDirectory(prefix="ms-media-bench-") as tmp:
        tmp_path = Path(tmp)
        mp4_path = tmp_path / "video.mp4"
        poster_path = tmp_path / "poster.jpg"
        ffmpeg = "/opt/homebrew/bin/ffmpeg" if Path("/opt/homebrew/bin/ffmpeg").exists() else "ffmpeg"
        subprocess.run(
            [
                ffmpeg, "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=10",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=10",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                str(mp4_path),
            ],
            check=True,
        )
        subprocess.run(
            [ffmpeg, "-y", "-v", "error", "-ss", "1", "-i", str(mp4_path), "-frames:v", "1", str(poster_path)],
            check=True,
        )
        vtt_bytes = (
            "WEBVTT\n\n"
            "00:00:00.500 --> 00:00:04.500\n"
            "MangaShelf 合规合成测试视频（第一段）\n\n"
            "00:00:05.000 --> 00:00:09.500\n"
            "MangaShelf Range 拖动与缓存实测（第二段）\n"
        ).encode("utf-8")
        return mp4_path.read_bytes(), poster_path.read_bytes(), vtt_bytes


def prepare_fixture(fixture_path: Path, page_count: int) -> None:
    work_id = str(uuid.uuid4())
    chapter_id = str(uuid.uuid4())
    chapter_rev_id = str(uuid.uuid4())
    source_asset_id = str(uuid.uuid4())
    anim_id = str(uuid.uuid4())
    anim_rev_id = str(uuid.uuid4())
    video_asset_id = str(uuid.uuid4())
    poster_asset_id = str(uuid.uuid4())
    subtitle_asset_id = str(uuid.uuid4())
    anim_source_id = str(uuid.uuid4())

    object_keys: list[str] = []
    asset_ids: list[str] = [source_asset_id, video_asset_id, poster_asset_id, subtitle_asset_id]
    sql_lines = ["BEGIN;"]

    # 1) 漫画作品、章节、源占位资产与章节版本
    source_key = f"users/{ADMIN_OWNER_ID}/uploads/{source_asset_id}/original"
    s3_put(source_key, b"synthetic-cbz-placeholder", "application/zip")
    object_keys.append(source_key)

    sql_lines.append(
        f"INSERT INTO works (id, owner_id, title, description, visibility, status) "
        f"VALUES ('{work_id}', '{ADMIN_OWNER_ID}', 'Media Bench Synthetic Manga', '合规合成压测作品', 'PUBLIC', 'PUBLISHED');"
    )
    sql_lines.append(
        f"INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256, content_type) "
        f"VALUES ('{source_asset_id}', '{ADMIN_OWNER_ID}', '{source_key}', 'ORIGINAL', 'VERIFIED', 25, "
        f"'{hashlib.sha256(b'synthetic-cbz-placeholder').hexdigest()}', 'application/zip');"
    )
    sql_lines.append(
        f"INSERT INTO chapters (id, work_id, ordinal, title) VALUES ('{chapter_id}', '{work_id}', 1, '第 1 话（压测样本）');"
    )
    sql_lines.append(
        f"INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_asset_id, status, page_count, manifest_sha256) "
        f"VALUES ('{chapter_rev_id}', '{chapter_id}', 1, '{source_asset_id}', 'PUBLISHED', {page_count}, 'bench-manifest');"
    )
    sql_lines.append(
        f"UPDATE chapters SET current_revision_id = '{chapter_rev_id}' WHERE id = '{chapter_id}';"
    )

    total_reader_bytes = 0
    total_thumb_bytes = 0
    for ordinal in range(1, page_count + 1):
        reader_bytes, thumb_bytes, w, h = generate_comic_page(ordinal)
        total_reader_bytes += len(reader_bytes)
        total_thumb_bytes += len(thumb_bytes)
        page_id = str(uuid.uuid4())
        img_asset_id = str(uuid.uuid4())
        thumb_asset_id = str(uuid.uuid4())
        asset_ids.extend([img_asset_id, thumb_asset_id])

        img_key = f"chapters/{chapter_rev_id}/pages/{page_id}/reader.webp"
        thumb_key = f"chapters/{chapter_rev_id}/pages/{page_id}/thumbnail.webp"
        # 上传时写入不可变 Cache-Control（与 ChapterParseHandler 优化一致）
        s3_put(img_key, reader_bytes, "image/webp", "private, max-age=86400, immutable")
        s3_put(thumb_key, thumb_bytes, "image/webp", "private, max-age=86400, immutable")
        object_keys.extend([img_key, thumb_key])

        sql_lines.append(
            f"INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256, content_type) "
            f"VALUES ('{img_asset_id}', '{ADMIN_OWNER_ID}', '{img_key}', 'READER_IMAGE', 'VERIFIED', "
            f"{len(reader_bytes)}, '{hashlib.sha256(reader_bytes).hexdigest()}', 'image/webp');"
        )
        sql_lines.append(
            f"INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256, content_type) "
            f"VALUES ('{thumb_asset_id}', '{ADMIN_OWNER_ID}', '{thumb_key}', 'THUMBNAIL', 'VERIFIED', "
            f"{len(thumb_bytes)}, '{hashlib.sha256(thumb_bytes).hexdigest()}', 'image/webp');"
        )
        sql_lines.append(
            f"INSERT INTO pages (id, chapter_revision_id, ordinal, image_asset_id, thumbnail_asset_id, width, height) "
            f"VALUES ('{page_id}', '{chapter_rev_id}', {ordinal}, '{img_asset_id}', '{thumb_asset_id}', {w}, {h});"
        )

    # 2) 合成合规 MP4 动画、封面与字幕
    mp4_bytes, poster_bytes, vtt_bytes = generate_synthetic_mp4()
    video_key = f"animations/{anim_rev_id}/video.mp4"
    poster_key = f"animations/{anim_rev_id}/poster.jpg"
    subtitle_key = f"animations/{anim_rev_id}/subtitles.vtt"
    s3_put(video_key, mp4_bytes, "video/mp4", "private, max-age=86400")
    s3_put(poster_key, poster_bytes, "image/jpeg", "private, max-age=86400, immutable")
    s3_put(subtitle_key, vtt_bytes, "text/vtt", "private, max-age=86400, immutable")
    object_keys.extend([video_key, poster_key, subtitle_key])

    sql_lines.append(
        f"INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256, content_type) "
        f"VALUES ('{video_asset_id}', '{ADMIN_OWNER_ID}', '{video_key}', 'VIDEO', 'VERIFIED', "
        f"{len(mp4_bytes)}, '{hashlib.sha256(mp4_bytes).hexdigest()}', 'video/mp4');"
    )
    sql_lines.append(
        f"INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256, content_type) "
        f"VALUES ('{poster_asset_id}', '{ADMIN_OWNER_ID}', '{poster_key}', 'POSTER', 'VERIFIED', "
        f"{len(poster_bytes)}, '{hashlib.sha256(poster_bytes).hexdigest()}', 'image/jpeg');"
    )
    sql_lines.append(
        f"INSERT INTO assets (id, owner_id, object_key, kind, state, size_bytes, verified_sha256, content_type) "
        f"VALUES ('{subtitle_asset_id}', '{ADMIN_OWNER_ID}', '{subtitle_key}', 'SUBTITLE', 'VERIFIED', "
        f"{len(vtt_bytes)}, '{hashlib.sha256(vtt_bytes).hexdigest()}', 'text/vtt');"
    )
    sql_lines.append(
        f"INSERT INTO animations (id, owner_id, title, description, visibility, status) "
        f"VALUES ('{anim_id}', '{ADMIN_OWNER_ID}', 'Media Bench Synthetic Animation', '合规合成压测动画', 'PUBLIC', 'PUBLISHED');"
    )
    sql_lines.append(
        f"INSERT INTO animation_revisions (id, animation_id, revision_no, status, video_asset_id, poster_asset_id, "
        f"subtitle_asset_id, duration_ms, video_width, video_height, video_codec, audio_codec, has_audio, source_manifest_sha256) "
        f"VALUES ('{anim_rev_id}', '{anim_id}', 1, 'PUBLISHED', '{video_asset_id}', '{poster_asset_id}', "
        f"'{subtitle_asset_id}', 10000, 1280, 720, 'h264', 'aac', true, 'bench-source');"
    )
    sql_lines.append(
        f"UPDATE animations SET current_revision_id = '{anim_rev_id}' WHERE id = '{anim_id}';"
    )
    sql_lines.append(
        f"INSERT INTO animation_sources (id, animation_revision_id, chapter_revision_id, page_start, page_end, ordinal) "
        f"VALUES ('{anim_source_id}', '{anim_rev_id}', '{chapter_rev_id}', 1, 5, 1);"
    )
    sql_lines.append("COMMIT;")
    psql("\n".join(sql_lines))

    fixture = {
        "work_id": work_id,
        "chapter_id": chapter_id,
        "chapter_revision_id": chapter_rev_id,
        "animation_id": anim_id,
        "animation_revision_id": anim_rev_id,
        "page_count": page_count,
        "total_reader_bytes": total_reader_bytes,
        "total_thumb_bytes": total_thumb_bytes,
        "video_bytes": len(mp4_bytes),
        "poster_bytes": len(poster_bytes),
        "subtitle_bytes": len(vtt_bytes),
        "asset_ids": asset_ids,
        "object_keys": object_keys,
    }
    fixture_path.write_text(json.dumps(fixture, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Prepared fixture: {page_count} pages ({total_reader_bytes/1024:.1f} KiB reader), "
          f"MP4 {len(mp4_bytes)/1024:.1f} KiB -> {fixture_path}")


def cleanup_fixture(fixture_path: Path) -> None:
    data = json.loads(fixture_path.read_text(encoding="utf-8"))
    sql = f"""
BEGIN;
UPDATE animations SET current_revision_id = NULL WHERE id = '{data["animation_id"]}';
DELETE FROM animation_sources WHERE animation_revision_id = '{data["animation_revision_id"]}';
DELETE FROM watch_progress WHERE animation_id = '{data["animation_id"]}';
DELETE FROM animation_revisions WHERE id = '{data["animation_revision_id"]}';
DELETE FROM animations WHERE id = '{data["animation_id"]}';
UPDATE chapters SET current_revision_id = NULL WHERE id = '{data["chapter_id"]}';
DELETE FROM reading_progress WHERE chapter_id = '{data["chapter_id"]}';
DELETE FROM pages WHERE chapter_revision_id = '{data["chapter_revision_id"]}';
DELETE FROM chapter_revisions WHERE id = '{data["chapter_revision_id"]}';
DELETE FROM chapters WHERE id = '{data["chapter_id"]}';
DELETE FROM works WHERE id = '{data["work_id"]}';
DELETE FROM assets WHERE id IN ({",".join(f"'{a}'" for a in data["asset_ids"])});
COMMIT;
"""
    psql(sql)
    for key in data["object_keys"]:
        s3_delete(key)
    print(f"Cleaned up fixture DB rows and {len(data['object_keys'])} S3 objects.")


# ── HTTP 客户端封装 ──

class HttpSession:
    def __init__(self, base_url: str):
        parsed = urllib.parse.urlparse(base_url)
        self.scheme = parsed.scheme
        self.host = parsed.hostname or "127.0.0.1"
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.cookies: dict[str, str] = {}
        self.conn = http.client.HTTPConnection(self.host, self.port, timeout=30)

    def _cookie_header(self) -> str:
        return "; ".join(f"{k}={v}" for k, v in self.cookies.items())

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], bytes, float, float]:
        hdrs = dict(headers or {})
        if self.cookies:
            hdrs["Cookie"] = self._cookie_header()
        t0 = time.perf_counter()
        self.conn.request(method, path, body=body, headers=hdrs)
        resp = self.conn.getresponse()
        ttfb_ms = (time.perf_counter() - t0) * 1000.0
        raw = resp.read()
        total_ms = (time.perf_counter() - t0) * 1000.0
        resp_hdrs: dict[str, str] = {}
        for k, v in resp.getheaders():
            kl = k.lower()
            if kl == "set-cookie":
                part = v.split(";", 1)[0]
                if "=" in part:
                    ck, cv = part.split("=", 1)
                    self.cookies[ck.strip()] = cv.strip()
            else:
                resp_hdrs[kl] = v
        return resp.status, resp_hdrs, raw, ttfb_ms, total_ms

    def login(self, username: str, password: str) -> None:
        # 先触发 CSRF Cookie
        self.request("GET", "/api/v1/auth/me")
        xsrf = urllib.parse.unquote(self.cookies.get("XSRF-TOKEN", ""))
        payload = json.dumps({"login": username, "password": password}).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if xsrf:
            headers["X-XSRF-TOKEN"] = xsrf
        status, _, body, _, _ = self.request("POST", "/api/v1/auth/login", body=payload, headers=headers)
        if status != 200:
            raise RuntimeError(f"Login failed ({status}): {body.decode('utf-8', 'ignore')}")


def fetch_url(url: str, headers: dict[str, str] | None = None) -> tuple[int, dict[str, str], int, float, float, str]:
    parsed = urllib.parse.urlparse(url)
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=30)
    path = parsed.path + (f"?{parsed.query}" if parsed.query else "")
    t0 = time.perf_counter()
    conn.request("GET", path, headers=headers or {})
    resp = conn.getresponse()
    ttfb_ms = (time.perf_counter() - t0) * 1000.0
    raw = resp.read()
    total_ms = (time.perf_counter() - t0) * 1000.0
    resp_hdrs = {k.lower(): v for k, v in resp.getheaders()}
    conn.close()
    return resp.status, resp_hdrs, len(raw), ttfb_ms, total_ms, f"{parsed.hostname}:{parsed.port}"


def pct(vals: list[float], p: float) -> float:
    if not vals:
        return 0.0
    s = sorted(vals)
    idx = min(len(s) - 1, max(0, int(round((p / 100.0) * (len(s) - 1)))))
    return round(s[idx], 2)


# ── 核心实测流程 ──

def run_bench(api_base: str, profile: str, fixture_path: Path, out_path: Path | None,
              username: str, password: str) -> None:
    fx = json.loads(fixture_path.read_text(encoding="utf-8"))
    rev_id = fx["chapter_revision_id"]
    anim_id = fx["animation_id"]
    page_count = fx["page_count"]

    sess = HttpSession(api_base)
    sess.login(username, password)
    api_host = urllib.parse.urlparse(api_base).netloc

    # ── 1. 漫画阅读会话（顺序 1..20 + 回看 11..20，共 30 次页面阅读）──
    reading_sequence = list(range(1, page_count + 1)) + list(range(11, page_count + 1))
    browser_cache: dict[str, tuple[str, int]] = {}  # url -> (etag, size_bytes)
    window_cache: dict[int, dict[str, Any]] = {}
    prefetched: set[str] = set()

    api_calls = 0
    api_json_bytes = 0
    api_media_bytes = 0
    s3_media_bytes = 0
    cache_hits = 0
    network_fetches = 0
    turn_latencies_ms: list[float] = []
    first_image_ms = 0.0
    sample_img_headers: dict[str, str] = {}
    sample_img_url = ""

    def load_window(start_ord: int, limit: int) -> float:
        nonlocal api_calls, api_json_bytes
        status, _, raw, _, ms = sess.request(
            "GET", f"/api/v1/chapter-revisions/{rev_id}/pages?limit={limit}&cursor={max(0, start_ord - 1)}"
        )
        assert status == 200, f"/pages failed: {status}"
        api_calls += 1
        api_json_bytes += len(raw)
        data = json.loads(raw)
        if profile == "baseline":
            window_cache.clear()
        for p in data["pages"]:
            window_cache[p["ordinal"]] = p
        return ms

    # 初始打开第 1 页
    t_first0 = time.perf_counter()
    load_window(1, 5 if profile == "baseline" else 10)

    for idx, ord_no in enumerate(reading_sequence):
        if idx == page_count:
            # 模拟真实读者阅读超过 1 秒后回看前面页面，检验跨自然秒的签名 URL 缓存稳定性
            time.sleep(1.1)
        t_turn = time.perf_counter()
        if profile == "baseline":
            # 旧版 Reader.tsx：每次翻页都重发 API 请求（第 1 页已在上面加载）
            if idx > 0:
                load_window(ord_no, 5)
            # 模拟翻页间隔 > 1 秒时 S3 SigV4 时间戳变化（实测也直接验证：开启/关闭 reuse 时连续两次请求）
        else:
            if ord_no not in window_cache:
                load_window(ord_no, 10)
            elif ord_no + 2 <= page_count and (ord_no + 2) not in window_cache:
                load_window(ord_no + 2, 10)

        page_obj = window_cache[ord_no]
        url = page_obj["imageUrl"]
        if not sample_img_url:
            sample_img_url = url

        if url in browser_cache:
            cache_hits += 1
            turn_ms = (time.perf_counter() - t_turn) * 1000.0
        else:
            st, hdrs, size, _, _, target_host = fetch_url(url)
            assert st == 200, f"image GET failed: {st}"
            network_fetches += 1
            if target_host == api_host:
                api_media_bytes += size
            else:
                s3_media_bytes += size
            if not sample_img_headers:
                sample_img_headers = {k: hdrs.get(k, "") for k in ("content-type", "cache-control", "etag", "accept-ranges")}
            browser_cache[url] = (hdrs.get("etag", ""), size)
            turn_ms = (time.perf_counter() - t_turn) * 1000.0

        if idx == 0:
            first_image_ms = (time.perf_counter() - t_first0) * 1000.0
        turn_latencies_ms.append(turn_ms)

        # 优化模式：有限预加载 current+1, current+2（异步概念：不计入当前页渲染阻塞，但计入网络流量和缓存）
        if profile == "optimized":
            for ahead in (ord_no + 1, ord_no + 2):
                if ahead <= page_count and ahead in window_cache:
                    a_url = window_cache[ahead]["imageUrl"]
                    if a_url not in browser_cache and a_url not in prefetched:
                        prefetched.add(a_url)
                        st, hdrs, size, _, _, target_host = fetch_url(a_url)
                        assert st == 200
                        network_fetches += 1
                        if target_host == api_host:
                            api_media_bytes += size
                        else:
                            s3_media_bytes += size
                        browser_cache[a_url] = (hdrs.get("etag", ""), size)

    # ── 2. 签名 URL 跨秒稳定性 & 对象存储 ETag / 304 实测 ──
    # 等待 1.1s 跨越自然秒边界，再次调用 /pages?limit=20 检验是否返回完全相同的预签名 URL
    _, _, raw_a, _, _ = sess.request("GET", f"/api/v1/chapter-revisions/{rev_id}/pages?limit=20")
    urls_a = [p["imageUrl"] for p in json.loads(raw_a)["pages"]]
    time.sleep(1.1)
    _, _, raw_b, _, _ = sess.request("GET", f"/api/v1/chapter-revisions/{rev_id}/pages?limit=20")
    urls_b = [p["imageUrl"] for p in json.loads(raw_b)["pages"]]
    stable_url_ratio = sum(1 for a, b in zip(urls_a, urls_b) if a == b) / max(1, len(urls_a))

    # 条件请求 If-None-Match -> 304
    sample_etag = sample_img_headers.get("etag", "")
    cond_status, _, cond_bytes, _, cond_ms, _ = fetch_url(urls_b[0], headers={"If-None-Match": sample_etag})

    # 20 页元数据接口 30 次耗时（评估消除 N+1 后的批量查询耗时）
    pages_api_ms: list[float] = []
    for _ in range(30):
        st, _, _, _, ms = sess.request("GET", f"/api/v1/chapter-revisions/{rev_id}/pages?limit=20")
        assert st == 200
        pages_api_ms.append(ms)

    # ── 3. 动画 MP4 播放与 Range 206 实测 ──
    playback_ms_list: list[float] = []
    pb_data: dict[str, Any] = {}
    for _ in range(20):
        st, _, raw, _, ms = sess.request("GET", f"/api/v1/animations/{anim_id}/playback")
        assert st == 200, f"/playback failed: {st}"
        playback_ms_list.append(ms)
        pb_data = json.loads(raw)

    video_url = pb_data["videoUrl"]
    poster_url = pb_data["posterUrl"]
    subtitle_url = pb_data["subtitleUrl"]

    # 首段 Range 0-65535（对应浏览器加载 moov + 首帧）
    v1_st, v1_hdrs, v1_bytes, v1_ttfb, v1_total, v1_host = fetch_url(video_url, headers={"Range": "bytes=0-65535"})
    # 中段拖动 Seek Range 262144-393215
    v2_st, v2_hdrs, v2_bytes, v2_ttfb, v2_total, _ = fetch_url(video_url, headers={"Range": "bytes=262144-393215"})
    # 后段拖动 Seek Range 524288-655359
    v3_st, v3_hdrs, v3_bytes, v3_ttfb, v3_total, _ = fetch_url(video_url, headers={"Range": "bytes=524288-655359"})

    # 封面与字幕 200 + 304 实测
    p1_st, p1_hdrs, p1_bytes, _, p1_ms, _ = fetch_url(poster_url)
    p2_st, _, p2_bytes, _, p2_ms, _ = fetch_url(poster_url, headers={"If-None-Match": p1_hdrs.get("etag", "")})
    s1_st, s1_hdrs, s1_bytes, _, s1_ms, _ = fetch_url(subtitle_url)
    s2_st, _, s2_bytes, _, s2_ms, _ = fetch_url(subtitle_url, headers={"If-None-Match": s1_hdrs.get("etag", "")})

    # ── 4. Prometheus 签名复用指标采集 ──
    prom_st, _, prom_raw, _, _ = sess.request("GET", "/actuator/prometheus")
    presign_metrics: list[str] = []
    if prom_st == 200:
        for line in prom_raw.decode("utf-8", "ignore").splitlines():
            if line.startswith("mangashelf_media_presign_requests_total"):
                presign_metrics.append(line)

    summary = {
        "profile": profile,
        "api_base": api_base,
        "manga_reading_30_turns": {
            "page_views": len(reading_sequence),
            "api_calls": api_calls,
            "api_json_bytes": api_json_bytes,
            "api_media_forwarded_bytes": api_media_bytes,
            "s3_media_downloaded_bytes": s3_media_bytes,
            "network_image_fetches": network_fetches,
            "browser_cache_hits": cache_hits,
            "browser_cache_hit_rate": round(cache_hits / len(reading_sequence), 4),
            "first_image_ms": round(first_image_ms, 2),
            "turn_p50_ms": pct(turn_latencies_ms, 50),
            "turn_p95_ms": pct(turn_latencies_ms, 95),
            "turn_avg_ms": round(statistics.mean(turn_latencies_ms), 2),
            "sample_image_headers": sample_img_headers,
            "cross_second_url_stability": round(stable_url_ratio, 4),
            "conditional_if_none_match": {
                "status": cond_status,
                "bytes": cond_bytes,
                "ms": round(cond_ms, 2),
            },
            "pages_20_api_p50_ms": pct(pages_api_ms, 50),
            "pages_20_api_p95_ms": pct(pages_api_ms, 95),
        },
        "animation_playback": {
            "playback_api_p50_ms": pct(playback_ms_list, 50),
            "playback_api_p95_ms": pct(playback_ms_list, 95),
            "video_served_by_host": v1_host,
            "api_video_forwarded_bytes": 0 if v1_host != api_host else v1_bytes,
            "first_range_64k": {
                "status": v1_st,
                "bytes": v1_bytes,
                "ttfb_ms": round(v1_ttfb, 2),
                "total_ms": round(v1_total, 2),
                "accept_ranges": v1_hdrs.get("accept-ranges", ""),
                "content_range": v1_hdrs.get("content-range", ""),
                "cache_control": v1_hdrs.get("cache-control", ""),
                "etag": v1_hdrs.get("etag", ""),
            },
            "seek_mid_128k": {
                "status": v2_st,
                "bytes": v2_bytes,
                "ttfb_ms": round(v2_ttfb, 2),
                "total_ms": round(v2_total, 2),
                "content_range": v2_hdrs.get("content-range", ""),
            },
            "seek_late_128k": {
                "status": v3_st,
                "bytes": v3_bytes,
                "ttfb_ms": round(v3_ttfb, 2),
                "total_ms": round(v3_total, 2),
                "content_range": v3_hdrs.get("content-range", ""),
            },
            "poster_200_vs_304": {
                "first_status": p1_st, "first_bytes": p1_bytes, "first_ms": round(p1_ms, 2),
                "cache_control": p1_hdrs.get("cache-control", ""),
                "cond_status": p2_st, "cond_bytes": p2_bytes, "cond_ms": round(p2_ms, 2),
            },
            "subtitle_200_vs_304": {
                "first_status": s1_st, "first_bytes": s1_bytes, "first_ms": round(s1_ms, 2),
                "cache_control": s1_hdrs.get("cache-control", ""),
                "cond_status": s2_st, "cond_bytes": s2_bytes, "cond_ms": round(s2_ms, 2),
            },
        },
        "prometheus_presign_metrics": presign_metrics,
    }
    text = json.dumps(summary, indent=2, ensure_ascii=False)
    print(text)
    if out_path:
        out_path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["prepare", "bench", "cleanup"], required=True)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--pages", type=int, default=20)
    parser.add_argument("--profile", choices=["baseline", "optimized"], default="optimized")
    parser.add_argument("--api-base", default="http://127.0.0.1:18089")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="mangashelf-admin")
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    fixture_path = Path(args.fixture)
    if args.mode == "prepare":
        prepare_fixture(fixture_path, args.pages)
    elif args.mode == "bench":
        run_bench(args.api_base, args.profile, fixture_path, Path(args.out) if args.out else None,
                  args.username, args.password)
    elif args.mode == "cleanup":
        cleanup_fixture(fixture_path)


if __name__ == "__main__":
    main()
