"""browser.plan：声明式浏览器提取（M32）。

规则行（manga_source_rules.rules.browserPlan）描述 URL 模板与 CSS 选择器，
本任务用 Playwright 打开页面、执行 JS、按 plan 提取统一结构：

- search:  列表 -> [{id,title,cover}]
- detail:  single 字段 + list 章节（或 slots 数字章节）
- pages:   滚动加载后收集图片 URL

只做数据翻译，展示仍由站内 UI 负责。URL 由 Java 按模板拼好，Python 不
接收用户提交的任意 URL。
"""

from __future__ import annotations

import json
import re
from typing import Any, Callable
from urllib.parse import urlsplit

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
DEFAULT_TIMEOUT_SECONDS = 40


def _parse_cookies(cookie: str) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for part in (cookie or "").split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        name, value = name.strip(), value.strip()
        if not name:
            continue
        host = ""
        out.append({"name": name, "value": value, "url": "https://www.baozimh.com"})
    return out


def _query_field(page: Any, spec: dict[str, Any], scope: str = "document") -> str:
    select = str(spec.get("select") or "")
    attr = str(spec.get("attr") or "")
    attrs = spec.get("attrs") or []
    regex = str(spec.get("regex") or "")
    last = bool(spec.get("last"))
    js = """(args) => {
        const {scope, select, attr, attrs, regex, last} = args;
        const root = scope === 'document' ? document : scope;
        const els = select ? [...root.querySelectorAll(select)] : [root];
        if (!els.length) return '';
        const el = last ? els[els.length - 1] : els[0];
        let v = '';
        if (attrs && attrs.length) {
            for (const a of attrs) { v = el.getAttribute(a) || ''; if (v) break; }
        } else {
            v = attr ? (el.getAttribute(attr) || '') : (el.innerText || '');
        }
        if (regex) {
            const m = v.match(new RegExp(regex));
            v = m ? (m.length > 1 ? m[1] : m[0]) : '';
        }
        return (v || '').trim();
    }"""
    return page.evaluate(js, {"scope": scope, "select": select, "attr": attr,
                              "attrs": attrs, "regex": regex, "last": last}) or ""


def _extract_list(page: Any, plan: dict[str, Any]) -> list[dict[str, str]]:
    selector = str(plan.get("list") or "")
    fields = plan.get("fields") or {}
    if not selector:
        return []
    raw = page.evaluate(
        """(args) => {
            const {selector, fields} = args;
            const pick = (el, spec) => {
                const sub = spec.select ? el.querySelector(spec.select) : el;
                if (!sub) return '';
                let v = '';
                if (spec.attrs && spec.attrs.length) {
                    for (const a of spec.attrs) { v = sub.getAttribute(a) || ''; if (v) break; }
                } else {
                    v = spec.attr ? (sub.getAttribute(spec.attr) || '') : (sub.innerText || '');
                }
                if (spec.regex) {
                    const m = v.match(new RegExp(spec.regex));
                    v = m ? (m.length > 1 ? m[1] : m[0]) : '';
                }
                return (v || '').trim();
            };
            return [...document.querySelectorAll(selector)].map(el => {
                const row = {};
                for (const [k, spec] of Object.entries(fields)) row[k] = pick(el, spec);
                return row;
            });
        }""",
        {"selector": selector, "fields": fields},
    )
    merged: dict[str, dict[str, str]] = {}
    for row in raw or []:
        key = row.get("id") or ""
        if not key:
            continue
        entry = merged.setdefault(key, {"id": key, "title": "", "cover": ""})
        if row.get("title"):
            entry["title"] = row["title"]
        if row.get("cover"):
            entry["cover"] = row["cover"]
    return [v for v in merged.values() if v.get("title")]


def _resolve_path(data: Any, path: str) -> Any:
    cur = data
    for part in (path or "").split("."):
        if not part:
            continue
        if not isinstance(cur, dict):
            return None
        cur = cur.get(part)
    return cur


def _chapters_from_api(page: Any, api: dict[str, Any], vars_: dict[str, str]) -> list[dict[str, str]]:
    base_vars = dict(vars_)
    id_regex = str(api.get("idRegex") or "")
    id_var = str(api.get("idVar") or "mid")
    if id_regex:
        m = re.match(id_regex, str(vars_.get("id") or ""))
        if m:
            base_vars[id_var] = m.group(1)
    url_tpl = str(api.get("url") or "")
    list_path = str(api.get("list") or "data.items")
    total_path = str(api.get("totalPages") or "data.total_pages")
    fields = api.get("fields") or {"id": "hid", "title": "title"}
    out: list[dict[str, str]] = []
    page_no = 1
    total_pages = 1
    while page_no <= total_pages and page_no <= 100:
        url = url_tpl
        for key, value in base_vars.items():
            url = url.replace("{" + key + "}", str(value))
        url = url.replace("{page}", str(page_no))
        resp = page.request.get(url)
        if not resp.ok:
            break
        payload = resp.json()
        rows = _resolve_path(payload, list_path) or []
        for row in rows:
            item = {key: str(row.get(field) or "") for key, field in fields.items()}
            if item.get("id"):
                out.append(item)
        total_pages = int(_resolve_path(payload, total_path) or 1)
        page_no += 1
    return out


def run(spec: dict[str, Any], run_dir: Any, progress: Callable[..., None]) -> dict[str, Any]:
    params = spec.get("params") or {}
    action = str(params.get("action") or "").strip()
    plan_text = str(params.get("plan") or "")
    vars_ = params.get("vars") or {}
    cookie = str(params.get("cookie") or "").strip()
    storage_state = str(params.get("storage_state") or "").strip() or None
    if action not in ("search", "detail", "pages"):
        return {"status": "FAILED", "error_code": "BAD_ACTION", "message": f"未知 action：{action}"}
    try:
        plan = json.loads(plan_text)
    except Exception:
        return {"status": "FAILED", "error_code": "BAD_PLAN", "message": "plan 不是合法 JSON"}
    section = plan.get(action) or {}
    url = str(section.get("url") or "")
    for key, value in (vars_ or {}).items():
        url = url.replace("{" + key + "}", str(value))
    if not url.startswith(("http://", "https://")):
        return {"status": "FAILED", "error_code": "BAD_URL", "message": "URL 模板未生成合法地址"}

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return {"status": "FAILED", "error_code": "PLAYWRIGHT_MISSING",
                "message": "Worker 缺少 playwright 依赖"}

    progress("browser", f"打开 {url}")
    with sync_playwright() as p:
        browser = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        try:
            ctx_kwargs: dict[str, Any] = {
                "user_agent": UA,
                "viewport": {"width": 1280, "height": 1000},
                "locale": "zh-CN",
                "ignore_https_errors": True,
            }
            if storage_state:
                ctx_kwargs["storage_state"] = storage_state
            context = browser.new_context(**ctx_kwargs)
            if cookie:
                context.add_cookies(_parse_cookies(cookie))
            page = context.new_page()
            page.goto(url, wait_until="domcontentloaded",
                      timeout=DEFAULT_TIMEOUT_SECONDS * 1000)
            page.wait_for_timeout(int(section.get("wait_ms") or 2500))

            for text in section.get("click") or []:
                try:
                    page.evaluate(
                        """(t) => {
                            const els = [...document.querySelectorAll('a,button,div,span')];
                            const el = els.find(e => (e.textContent || '').trim() === t);
                            if (el) el.click();
                        }""", str(text))
                    page.wait_for_timeout(1200)
                except Exception:
                    pass

            for _ in range(int(section.get("scroll") or 0)):
                page.evaluate("window.scrollBy(0, document.body.scrollHeight)")
                page.wait_for_timeout(700)

            if action == "pages":
                images_plan = section.get("images") or {}
                pattern = str(images_plan.get("regex") or "")
                if pattern:
                    html = page.content()
                    found = re.findall(pattern, html)
                    pages = []
                    seen_paths: set[str] = set()
                    excludes = [str(x) for x in (images_plan.get("exclude") or [])]
                    for src in found:
                        src = src.strip()
                        if not src.startswith(("http://", "https://")):
                            continue
                        if any(x.lower() in src.lower() for x in excludes):
                            continue
                        # 同一页可能来自多个 CDN 域名：按路径去重（保留首个）
                        path = urlsplit(src).path
                        if path in seen_paths:
                            continue
                        seen_paths.add(path)
                        pages.append(src)
                    if not pages:
                        return {"status": "FAILED", "error_code": "NO_PAGES",
                                "message": "未从页面提取到页图 URL"}
                    return {"status": "SUCCEEDED", "pages": pages}
                selector = str(images_plan.get("select") or "img")
                attrs = images_plan.get("attrs") or ["data-src", "src"]
                min_width = int(images_plan.get("minWidth") or 0)
                excludes = [str(x) for x in (images_plan.get("exclude") or [])]
                raw = page.evaluate(
                    """(args) => {
                        const {selector, attrs, minWidth} = args;
                        return [...document.querySelectorAll(selector)].map(e => {
                            let src = '';
                            for (const a of attrs) { src = e.getAttribute(a) || ''; if (src) break; }
                            if (!src) src = e.currentSrc || e.src || '';
                            return {src, w: e.naturalWidth || 0};
                        });
                    }""", {"selector": selector, "attrs": attrs, "minWidth": min_width})
                pages: list[str] = []
                for image in raw or []:
                    src = (image.get("src") or "").strip()
                    if not src.startswith(("http://", "https://")):
                        continue
                    if any(x.lower() in src.lower() for x in excludes):
                        continue
                    if min_width and image.get("w") and image["w"] < min_width:
                        continue
                    if src not in pages:
                        pages.append(src)
                if not pages:
                    return {"status": "FAILED", "error_code": "NO_PAGES",
                            "message": "未解析到页图（可能需登录或页面结构变化）"}
                return {"status": "SUCCEEDED", "pages": pages}

            single = {}
            for key, field_spec in (section.get("single") or {}).items():
                single[key] = _query_field(page, field_spec)

            items = _extract_list(page, section)
            chapters_api = section.get("chaptersApi")
            if chapters_api:
                items = _chapters_from_api(page, chapters_api, vars_ or {})

            slots = section.get("slots")
            last_slot = None
            if slots:
                pattern = str(slots.get("lastRegex") or r"(\\d+)")
                html = page.content()
                found = [int(x) for x in re.findall(pattern, html)]
                if found:
                    last_slot = max(found)

            return {"status": "SUCCEEDED", "single": single, "items": items,
                    "last_slot": last_slot}
        finally:
            browser.close()
