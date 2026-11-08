"""chapter.parse：把已核验的 CBZ 或单图解析为自然页序的页面清单（DESIGN 10.3）。

复用来源（AGENTS 复用与来源）：MangaWeaver src/mangaweaver/preprocessing/import_manga.py
@ e057b32 的自然排序 key、zip 防护（contained / 条目与总量上限 / 拒绝嵌套压缩）与
页面落盘约定（EXIF 转正、RGB 化、240x320 缩略图）；剥离 events.emit 与桌面 IO 依赖，
输出走 12.2 协议。每页补充宽高与逐页 SHA-256（其 book.json 未记录）。
"""

from __future__ import annotations

import hashlib
import re
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, Callable

from PIL import Image, ImageOps

from processing import protocol

READER_QUALITY = 90
THUMBNAIL_SIZE = (240, 320)
THUMBNAIL_QUALITY = 80

ALLOWED_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"}
FORBIDDEN_ARCHIVES = {".zip", ".cbz", ".rar", ".7z", ".tar", ".gz"}


def _natural_key(name: str) -> list[Any]:
    """自然排序：数字段按整数比较，其余小写（import_manga.py L143-144 的判定逻辑）。"""
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", name)]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_entry_name(name: str) -> PurePosixPath:
    """拒绝绝对路径、目录穿越与反斜杠（import_manga.contained 的防护语义）。"""
    if "\\" in name:
        raise ValueError(f"压缩包条目包含反斜杠路径：{name}")
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts:
        raise ValueError(f"压缩包条目路径越界：{name}")
    return p


def _collect_cbz_pages(source: Path, limits: dict[str, Any]) -> list[tuple[str, zipfile.ZipInfo]]:
    max_entries = int(limits.get("max_entries", 2000))
    max_total = int(limits.get("max_total_bytes", 512 << 20))
    max_file = int(limits.get("max_file_bytes", 100 << 20))

    with zipfile.ZipFile(source) as archive:
        infos = [i for i in archive.infolist() if not i.is_dir()]
        if len(infos) > max_entries:
            raise ValueError(f"压缩包条目数超限：{len(infos)} > {max_entries}")

        total = 0
        pages: list[tuple[str, zipfile.ZipInfo]] = []
        for info in infos:
            entry = _safe_entry_name(info.filename)
            ext = entry.suffix.lower()
            if ext in FORBIDDEN_ARCHIVES:
                raise ValueError(f"拒绝嵌套压缩包条目：{info.filename}")
            if ext not in ALLOWED_EXTENSIONS:
                continue  # 非图片条目跳过（如目录说明文件）
            if info.file_size > max_file:
                raise ValueError(f"单文件超过上限：{info.filename}")
            total += info.file_size
            if total > max_total:
                raise ValueError(f"解压总量超过上限：{total} > {max_total}")
            pages.append((info.filename, info))

        if not pages:
            raise ValueError("压缩包中没有受支持的图片")
        pages.sort(key=lambda item: _natural_key(item[0]))
        return pages


def _derive_image(raw: Any, output_dir: Path, ordinal: int) -> dict[str, Any]:
    """EXIF 转正 + RGB 化（import_manga.load_pages 的落盘约定），写 reader 与 thumbnail。"""
    with Image.open(raw) as opened:
        image = ImageOps.exif_transpose(opened).convert("RGB")
    width, height = image.size

    page_dir = output_dir / "pages" / f"{ordinal:04d}"
    page_dir.mkdir(parents=True, exist_ok=True)
    reader_path = page_dir / "reader.webp"
    image.save(reader_path, "WEBP", quality=READER_QUALITY, method=4)

    thumbnail = image.copy()
    thumbnail.thumbnail(THUMBNAIL_SIZE)
    thumbnail_path = page_dir / "thumbnail.webp"
    thumbnail.save(thumbnail_path, "WEBP", quality=THUMBNAIL_QUALITY)

    return {
        "reader": {
            "path": f"output/pages/{ordinal:04d}/reader.webp",
            "width": width,
            "height": height,
            "bytes": reader_path.stat().st_size,
            "sha256": _sha256(reader_path),
        },
        "thumbnail": {
            "path": f"output/pages/{ordinal:04d}/thumbnail.webp",
            "width": thumbnail.width,
            "height": thumbnail.height,
            "bytes": thumbnail_path.stat().st_size,
            "sha256": _sha256(thumbnail_path),
        },
    }


def run(spec: dict[str, Any], run_dir: Path,
        progress: Callable[..., None]) -> dict[str, Any]:
    inputs = spec.get("inputs") or []
    if not inputs:
        raise ValueError("任务缺少输入清单")
    source = protocol.resolve_inside(run_dir, inputs[0]["path"])
    if not source.is_file():
        raise ValueError(f"输入文件不存在：{inputs[0]['path']}")
    limits = spec.get("limits") or {}

    output_rel = spec.get("output_dir", "output")
    output_dir = protocol.resolve_inside(run_dir, output_rel)
    output_dir.mkdir(parents=True, exist_ok=True)

    pages: list[dict[str, Any]] = []

    def emit(ordinal: int, total: int, source_name: str) -> None:
        progress("parse", f"页面 {ordinal}/{total}：{source_name}")

    try:
        with zipfile.ZipFile(source) as archive:
            is_archive = True
    except zipfile.BadZipFile:
        is_archive = False

    if is_archive:
        entries = _collect_cbz_pages(source, limits)
        total = len(entries)
        with zipfile.ZipFile(source) as archive:
            for ordinal, (name, info) in enumerate(entries, start=1):
                emit(ordinal, total, name)
                with archive.open(info) as raw:
                    derived = _derive_image(raw, output_dir, ordinal)
                pages.append({"ordinal": ordinal, "source": name, **derived})
    else:
        ext = source.suffix.lower()
        if ext not in ALLOWED_EXTENSIONS:
            raise ValueError(f"不支持的输入类型：{ext or '(无扩展名)'}")
        emit(1, 1, source.name)
        with source.open("rb") as raw:
            derived = _derive_image(raw, output_dir, 1)
        pages.append({"ordinal": 1, "source": source.name, **derived})

    progress("parse", f"页面清单完成，共 {len(pages)} 页")
    return {"page_count": len(pages), "pages": pages}
