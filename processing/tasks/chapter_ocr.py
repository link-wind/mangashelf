"""chapter.ocr：页级 OCR（RapidOCR），产出逐页文本清单（DESIGN 15.2）。

输入为 Java 预下载的页面图片（task.json inputs 带 ordinal）；本地推理无模型费用。
无文字页如实输出空串；stdout 仅 JSONL 进度（DESIGN 12.2）。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from rapidocr import RapidOCR

from processing import protocol

_ocr: Any = None


def _get_engine() -> Any:
    global _ocr
    if _ocr is None:
        _ocr = RapidOCR()
    return _ocr


def run(spec: dict[str, Any], run_dir: Path,
        progress: Callable[..., None]) -> dict[str, Any]:
    pages = [item for item in (spec.get("inputs") or []) if item.get("role") == "page"]
    if not pages:
        raise ValueError("任务缺少页面输入清单")
    pages.sort(key=lambda item: item.get("ordinal", 0))

    results = []
    total = len(pages)
    for index, item in enumerate(pages, start=1):
        ordinal = item.get("ordinal", index)
        image = protocol.resolve_inside(run_dir, item["path"])
        if not image.is_file():
            raise ValueError(f"页面文件不存在：{item['path']}")
        progress("ocr", f"识别第 {ordinal} 页（{index}/{total}）")
        result = _get_engine()(str(image))
        lines = list(result.txts) if result is not None and result.txts is not None else []
        text = "\n".join(line for line in lines if line.strip())
        results.append({"ordinal": ordinal, "text": text})
    progress("ocr", f"识别完成，共 {total} 页")
    return {"page_count": total, "pages": results}
