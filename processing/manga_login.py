"""拷贝漫画网页版登录助手（M29，本机交互使用，不进入 Worker）。

用法（在仓库根目录）：
    processing/.venv/bin/python -m processing.manga_login \
        --out data/copymanga-state.json

打开可见的 Chromium，手动完成登录后回终端按回车，脚本保存 Playwright
storage_state 供服务端无头浏览器复用。服务端配置：
    MANGASHELF_COPYMANGA_STORAGE_STATE=<绝对路径>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

LOGIN_URL = "https://www.copy4000.com/"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="processing.manga_login")
    parser.add_argument("--out", default="data/copymanga-state.json",
                        help="storage_state 保存路径")
    args = parser.parse_args(argv)

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("缺少 playwright：请用 processing/.venv/bin/python 运行", file=sys.stderr)
        return 2

    out = Path(args.out).expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False, args=["--no-sandbox"])
        context = browser.new_context(locale="zh-TW")
        page = context.new_page()
        page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=60_000)
        print("\n请在弹出的浏览器里登录拷贝漫画，登录完成后回到终端按回车…", file=sys.stderr)
        try:
            input()
        except EOFError:
            pass
        context.storage_state(path=str(out))
        browser.close()

    print(f"已保存登录态：{out}", file=sys.stderr)
    print(f"服务端启动前设置：MANGASHELF_COPYMANGA_STORAGE_STATE={out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
