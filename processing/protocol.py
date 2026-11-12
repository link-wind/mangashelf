"""进程协议辅助（DESIGN 12.2）：JSONL 进度、日志与结果清单。"""

from __future__ import annotations

import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any, Callable

ProgressCallback = Callable[[str, str], None]


def emit_progress(sequence: int, stage: str | None, message: str | None) -> None:
    """向 stdout 写一行 JSONL 进度；这是 stdout 唯一允许的内容。"""
    event: dict[str, Any] = {"seq": sequence, "type": "progress"}
    if stage:
        event["stage"] = stage
    if message:
        event["message"] = message
    sys.stdout.write(json.dumps(event, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def log(message: str) -> None:
    """日志只走 stderr。"""
    sys.stderr.write(f"[processing] {message}\n")
    sys.stderr.flush()


def write_result(run_dir: Path, result: dict[str, Any]) -> None:
    """原子写出 result.json：先写临时文件再改名，避免留下半截清单。"""
    target = run_dir / "result.json"
    tmp = run_dir / "result.json.tmp"
    tmp.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, target)


def resolve_inside(run_dir: Path, relative: str) -> Path:
    """解析任务目录内的相对路径；越界路径直接拒绝（不接收宿主任意路径）。"""
    resolved = (run_dir / relative).resolve()
    run_root = run_dir.resolve()
    if not resolved.is_relative_to(run_root):
        raise ValueError(f"路径越界：{relative}")
    return resolved


def run_task(spec: dict[str, Any], run_dir: Path, impl: Callable[..., dict[str, Any]]) -> int:
    """统一执行外壳：进度序列、异常处理与 result.json 输出。

    impl 返回结果扩展字典（如 artifacts / pages），与公共字段合并写入 result.json。
    """
    sequence = 0

    def progress(stage: str | None = None, message: str | None = None) -> None:
        nonlocal sequence
        sequence += 1
        emit_progress(sequence, stage, message)

    try:
        extra = impl(spec, run_dir, progress)
        result: dict[str, Any] = {
            "schema_version": spec["schema_version"],
            "job_id": spec["job_id"],
            "task": spec["task"],
            "attempt": spec["attempt"],
            "status": extra.get("status", "SUCCEEDED"),
        }
        result.update({key: value for key, value in extra.items() if key != "status"})
        write_result(run_dir, result)
        return 0 if result["status"] == "SUCCEEDED" else 1
    except Exception as exc:  # noqa: BLE001 —— 统一落盘失败清单供 Java 诊断
        log("task failed: " + "".join(traceback.format_exception(exc)))
        result = {
            "schema_version": spec.get("schema_version", 1),
            "job_id": spec.get("job_id"),
            "task": spec.get("task"),
            "attempt": spec.get("attempt", 0),
            "status": "FAILED",
            "error_code": "TASK_FAILED",
            "message": str(exc),
        }
        try:
            write_result(run_dir, result)
        except Exception:  # noqa: BLE001 —— 连结果清单都写不出来时只能退出码报告
            log("failed to write result.json")
        return 1
