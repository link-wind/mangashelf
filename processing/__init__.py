"""MangaShelf 固定处理任务包（DESIGN 12.2）。

入口：python -m processing.run --task <task.json>
stdout 只允许 JSONL 进度事件；日志写 stderr；完成后写 result.json。
Python 不持有数据库、对象存储或用户会话凭据。
"""

__version__ = "0.1.0"
