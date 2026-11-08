"""media.verify：ffprobe 实测视频媒体并产出封面与字幕（DESIGN 13.3）。

声明的 content-type 与扩展名不替代检查：容器/编码/时长/分辨率/音轨一律以
ffprobe 实际输出为准；无声视频如实上报 has_audio=false，不伪造合格标签。
封面未提供时以 ffmpeg 在 1s 处截取一帧；字幕 WebVTT 直通、SRT 固定转换为 VTT
（不再次翻译对白）。不支持的组合任务失败并给出可定位错误码。
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any, Callable

from PIL import Image

from processing import protocol

# 基线规则（design.md D1）：MP4 容器；常见浏览器可播放编码。
ALLOWED_CONTAINERS = {"mp4"}
ALLOWED_VIDEO_CODECS = {"h264", "vp9", "av1"}
ALLOWED_AUDIO_CODECS = {"aac", "mp3", "opus"}
POSTER_SECONDS = 1
POSTER_MAX_WIDTH = 1280


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _resolve_input(run_dir: Path, inputs: list[dict[str, Any]], role: str) -> Path | None:
    for item in inputs:
        if item.get("role") == role:
            return protocol.resolve_inside(run_dir, item["path"])
    return None


def _ffprobe(source: Path) -> dict[str, Any]:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json",
         "-show_format", "-show_streams", str(source)],
        capture_output=True, text=True, timeout=120,
    )
    if proc.returncode != 0:
        raise ValueError(f"ffprobe 无法读取该文件：{proc.stderr.strip()[:200]}")
    return json.loads(proc.stdout)


def _classify(probe: dict[str, Any], source: Path) -> dict[str, Any]:
    format_info = probe.get("format") or {}
    streams = probe.get("streams") or []
    format_names = set((format_info.get("format_name") or "").split(","))
    if not (format_names & ALLOWED_CONTAINERS):
        raise ValueError(f"不支持的容器格式：{sorted(format_names)}（基线要求 MP4）")

    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    if video is None:
        raise ValueError("文件中没有视频流")
    video_codec = (video.get("codec_name") or "").lower()
    if video_codec not in ALLOWED_VIDEO_CODECS:
        raise ValueError(f"不支持的视频编码：{video_codec}（基线：{sorted(ALLOWED_VIDEO_CODECS)}）")

    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    audio_codec = None
    has_audio = audio is not None
    if audio is not None:
        audio_codec = (audio.get("codec_name") or "").lower()
        if audio_codec not in ALLOWED_AUDIO_CODECS:
            raise ValueError(f"不支持的音频编码：{audio_codec}（基线：{sorted(ALLOWED_AUDIO_CODECS)}）")

    duration_s = float(format_info.get("duration") or 0)
    if duration_s <= 0:
        raise ValueError("无法确定媒体时长")
    width = int(video.get("width") or 0)
    height = int(video.get("height") or 0)
    if width <= 0 or height <= 0:
        raise ValueError("无法确定视频分辨率")
    return {
        "container": "mp4",
        "video_codec": video_codec,
        "audio_codec": audio_codec,
        "has_audio": has_audio,
        "duration_ms": int(duration_s * 1000),
        "width": width,
        "height": height,
    }


def _extract_poster(source: Path, output_dir: Path) -> Path:
    poster = output_dir / "poster.jpg"
    proc = subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-ss", str(POSTER_SECONDS), "-i", str(source),
         "-frames:v", "1", "-vf", f"scale='min({POSTER_MAX_WIDTH},iw)':-2",
         str(poster)],
        capture_output=True, text=True, timeout=120,
    )
    if proc.returncode != 0 or not poster.is_file():
        raise ValueError("封面提取失败：" + proc.stderr.strip()[:200])
    with Image.open(poster) as im:
        im.load()
    return poster


def _normalize_poster(source: Path, output_dir: Path) -> Path:
    """用户提供的封面：解码验证并统一为 JPEG（防 aws-chunked 类污染与格式漂移）。"""
    with Image.open(source) as im:  # 无法解码即失败
        im.load()
        rgb = im.convert("RGB")
        if rgb.width > POSTER_MAX_WIDTH:
            ratio = POSTER_MAX_WIDTH / rgb.width
            rgb = rgb.resize((POSTER_MAX_WIDTH, int(rgb.height * ratio)))
    poster = output_dir / "poster.jpg"
    rgb.save(poster, "JPEG", quality=90)
    return poster


def _is_srt(text: str) -> bool:
    return bool(text.strip()) and "-->" in text and not text.lstrip().startswith("WEBVTT")


def _srt_to_vtt(text: str) -> str:
    """固定格式转换（DESIGN 13.3）：加 WEBVTT 头、时间轴逗号改点。不翻译对白。"""
    body = text.replace(",", ".")
    return "WEBVTT\n\n" + body.strip() + "\n"


def _normalize_subtitle(source: Path, output_dir: Path) -> Path:
    text = source.read_text(encoding="utf-8", errors="strict")
    if text.lstrip().startswith("WEBVTT"):
        vtt = text if text.startswith("WEBVTT") else "WEBVTT\n\n" + text.strip() + "\n"
    elif _is_srt(text):
        vtt = _srt_to_vtt(text)
    else:
        raise ValueError("字幕格式不支持（基线：WebVTT 或 SRT）")
    target = output_dir / "subtitles.vtt"
    target.write_text(vtt, encoding="utf-8")
    return target


def _artifact(role: str, path: Path, output_rel: str) -> dict[str, Any]:
    name = path.name
    return {
        "role": role,
        "path": f"{output_rel}/{name}",
        "bytes": path.stat().st_size,
        "sha256": _sha256(path),
    }


def run(spec: dict[str, Any], run_dir: Path,
        progress: Callable[..., None]) -> dict[str, Any]:
    inputs = spec.get("inputs") or []
    video = _resolve_input(run_dir, inputs, "video")
    if video is None or not video.is_file():
        raise ValueError("任务缺少视频输入")
    poster = _resolve_input(run_dir, inputs, "poster")
    subtitle = _resolve_input(run_dir, inputs, "subtitle")
    for path, label in ((video, "video"), (poster, "poster"), (subtitle, "subtitle")):
        if path is not None and not path.is_file():
            raise ValueError(f"输入文件不存在：{label}")

    output_rel = spec.get("output_dir", "output")
    output_dir = protocol.resolve_inside(run_dir, output_rel)
    output_dir.mkdir(parents=True, exist_ok=True)

    progress("probe", "ffprobe 读取媒体信息")
    probe = _ffprobe(video)
    media = _classify(probe, video)
    progress("probe", f"容器/编码合格：{media['video_codec']}/{media['audio_codec'] or '无声'} "
                      f"{media['duration_ms']}ms")

    artifacts: list[dict[str, Any]] = []
    if poster is not None:
        progress("poster", "校验并提供封面")
        poster_path = _normalize_poster(poster, output_dir)
    else:
        progress("poster", "从视频截取封面")
        poster_path = _extract_poster(video, output_dir)
    artifacts.append(_artifact("POSTER", poster_path, output_rel))

    if subtitle is not None:
        progress("subtitle", "检查字幕格式")
        subtitle_path = _normalize_subtitle(subtitle, output_dir)
        artifacts.append(_artifact("SUBTITLE", subtitle_path, output_rel))

    return {"media": media, "artifacts": artifacts}
