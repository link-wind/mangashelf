"""image.derive：为已核验的原图生成阅读派生图与缩略图（DESIGN 10.3）。

参考 MangaWeaver import_manga.py 的页面落盘约定（来源 e057b32）：
EXIF 转正、RGB 化、缩略图 240x320；清单补充宽高字段（其 book.json 未记录）。
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any, Callable

from PIL import Image, ImageOps

from processing import protocol

READER_QUALITY = 90
THUMBNAIL_SIZE = (240, 320)
THUMBNAIL_QUALITY = 80


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(spec: dict[str, Any], run_dir: Path,
        progress: Callable[..., None]) -> list[dict[str, Any]]:
    inputs = spec.get("inputs") or []
    if not inputs:
        raise ValueError("任务缺少输入清单")
    source = protocol.resolve_inside(run_dir, inputs[0]["path"])
    if not source.is_file():
        raise ValueError(f"输入文件不存在：{inputs[0]['path']}")

    output_rel = spec.get("output_dir", "output")
    output_dir = protocol.resolve_inside(run_dir, output_rel)
    output_dir.mkdir(parents=True, exist_ok=True)

    progress("derive", "打开图像并转正")
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    width, height = image.size

    progress("derive", "生成阅读图")
    reader_path = output_dir / "reader.webp"
    image.save(reader_path, "WEBP", quality=READER_QUALITY, method=4)

    progress("derive", "生成缩略图")
    thumbnail = image.copy()
    thumbnail.thumbnail(THUMBNAIL_SIZE)
    thumbnail_path = output_dir / "thumbnail.webp"
    thumbnail.save(thumbnail_path, "WEBP", quality=THUMBNAIL_QUALITY)

    artifacts = []
    for ordinal, (role, path, art_width, art_height) in enumerate([
            ("READER_IMAGE", reader_path, width, height),
            ("THUMBNAIL", thumbnail_path, thumbnail.width, thumbnail.height),
    ], start=1):
        artifacts.append({
            "path": f"{output_rel}/{path.name}",
            "ordinal": ordinal,
            "role": role,
            "content_type": "image/webp",
            "width": art_width,
            "height": art_height,
            "bytes": path.stat().st_size,
            "sha256": _sha256(path),
        })
    progress("derive", "产物清单完成")
    return artifacts
