"""固定任务协议：任务显式失败时保留结果清单并返回非零退出码。"""

import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from processing import protocol


class ProtocolTest(TestCase):
    def test_explicit_failure_keeps_result_and_returns_nonzero(self):
        spec = {"schema_version": 1, "job_id": 0, "task": "manga.resolve", "attempt": 0}
        with TemporaryDirectory() as tmp:
            directory = Path(tmp)
            code = protocol.run_task(spec, directory, lambda *_: {
                "status": "FAILED", "error_code": "CAPTURE_INCOMPLETE", "message": "缺页"
            })
            result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
            self.assertEqual(1, code)
            self.assertEqual("FAILED", result["status"])
            self.assertEqual("CAPTURE_INCOMPLETE", result["error_code"])
