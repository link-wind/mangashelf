"""固定任务入口：python -m processing.run --task <task.json>（DESIGN 12.2）。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from processing import protocol
from processing.tasks.chapter_parse import run as chapter_parse
from processing.tasks.image_derive import run as image_derive
from processing.tasks.media_verify import run as media_verify

# 固定任务清单；不开放任意脚本执行（DESIGN 12.2）
TASKS = {
    "image.derive": image_derive,
    "chapter.parse": chapter_parse,
    "media.verify": media_verify,
}

SCHEMA_VERSION = 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="processing.run")
    parser.add_argument("--task", required=True, help="task.json 路径（由 Worker 生成）")
    args = parser.parse_args(argv)

    task_path = Path(args.task).resolve()
    if not task_path.is_file():
        protocol.log(f"task.json 不存在：{task_path}")
        return 2

    spec = json.loads(task_path.read_text(encoding="utf-8"))
    if spec.get("schema_version") != SCHEMA_VERSION:
        protocol.log(f"不支持的协议版本：{spec.get('schema_version')}")
        return 2
    task = spec.get("task")
    impl = TASKS.get(task)
    if impl is None:
        protocol.log(f"未知任务类型：{task}")
        return 2

    run_dir = task_path.parent
    return protocol.run_task(spec, run_dir, impl)


if __name__ == "__main__":
    sys.exit(main())
