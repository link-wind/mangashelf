# 设计：漫画浏览器解析源（M29）

## 与动画方案的同构关系

| | 动画（M7） | 漫画（M29） |
|---|---|---|
| 解析 | Worker Playwright 嗅探 m3u8/mp4 | Worker Playwright 执行 JS 解密页图 |
| 交付 | 前端 hls.js 直连 CDN | 阅读器直连 CDN（失败走代理） |
| 登录 | 源级 Cookie 注入 | Cookie / storage_state |
| 缓存 | 解析结果落库 | 内存缓存 10/30 分钟 |

## 数据流

```
前端 /manga-ext/{path}?source=copymanga
  → GET /api/v1/manga-sources/copymanga/manga/{path}
      → MangaBrowserResolveService.resolveDetail
          → python -m processing.run（task=manga.resolve, mode=detail）
              → Chromium 打开 copy4000.com/comic/{path}，点目录，解析 DOM
  → GET /api/v1/manga-sources/copymanga/chapter/{path}~{uuid}/pages
      → resolveChapter → 滚动触发 lazysizes → 收集 img[data-src]
  → 阅读器直连图片 CDN
```

## 实测（2026-11-11）

- 详情：匿名可解析，39 话（第1话…第二季第11集），耗时 ~7s。
- 章节：匿名可出图（例如 2 张 664×4897 长条），耗时 ~10s；
  若上游把某话设为仅登录可见，脚本返回 `LOGIN_REQUIRED`。
- 图片 CDN（`*.mangafunb.fun`）直连可下载，已加入代理白名单。

## 安全边界

- Python 任务只接受 `https://www.copy4000.com/` 前缀的 URL（Java 侧拼接，
  用户不直接传 URL）。
- Cookie 仅通过 task.json 下发；任务目录解析后立即递归删除。
- 日志不输出 Cookie。

## 配置

```bash
# 方式一：登录态文件（推荐）
processing/.venv/bin/python -m processing.manga_login
export MANGASHELF_COPYMANGA_STORAGE_STATE=$PWD/data/copymanga-state.json
# 方式二：Cookie 串
export MANGASHELF_COPYMANGA_COOKIE='k=v; k2=v2'
# 本机 Playwright 解释器（系统 python 无 playwright 时必须）
export MANGASHELF_PYTHON=$PWD/processing/.venv/bin/python
```
