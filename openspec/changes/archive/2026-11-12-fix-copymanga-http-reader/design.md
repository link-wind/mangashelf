## Context

`baoshimonv` 第 10 话在 v3 章节接口返回 `size=10` 和 10 个 `contents`，
每页约 699×5370 的条漫长图。浏览器只提取到第 1 张就返回成功，磁盘缓存又把
该 1 页结果保存 30 分钟，造成“看不全”。阅读器本身没有裁切，问题在采集不完整。

## Goals / Non-Goals

**Goals:** 公开章节用 HTTP 一次取得完整页图清单；校验声明页数与实际数量；
阅读器按页懒加载并在图片就绪后保存进度。

**Non-Goals:** 不绕过登录/付费；不把 code 210 当成登录失败；不实现单张长图内部
切片；不引入新的通用 HTTP 框架。

## Decisions

- 使用官方 v3 章节接口，协议头与详情服务一致：
  `COPY/3.0.9`、`version=3.0.9`、`versionCode=83`、`platform=3`、
  `source=copyApp`、`region=1`，不发送 Cookie。
- `chapter.size` 必须在 1..1000，`chapter.contents` 必须是同长度数组；任何一项
  不符都返回 502 `COPYMANGA_READER_INVALID`，不缓存失败结果。
- 页图 URL 只接受 HTTPS、无用户信息、无片段、非本机/私有 IP 字面量。
- 成功结果缓存 10 分钟、最多 256 章；命中缓存不重复请求上游。
- `code=210` 返回 502 `COPYMANGA_READER_RATE_LIMITED`；`is_lock=true` 且无内容
  返回 403 `COPYMANGA_LOGIN_REQUIRED`。仅后者且已配置 Cookie/storage_state 时
  才回退浏览器。
- Python 兜底滚动最多 16 轮，只有连续两轮在页面底部且页数不增长才结束。
- 阅读器监听图片就绪事件；页码变化或同一页超过 3 秒节流后保存进度。

## Risks / Trade-offs

- [接口域名或协议头变化] → 明确报错并保留浏览器兜底入口，不静默返回不完整页图。
- [单页长图仍会一次下载整页] → 页级按需加载只解决“多页章节”；单张内部切片不在本次范围。
- [受限章节] → 不回退匿名浏览器；必须配置登录态，错误信息明确。

## Migration Plan

新增 `CopyMangaHttpReaderService` 并让 `CopyMangaBrowserSource.pages` 优先调用；
重建 API/Worker/Web 后清除旧浏览器缓存结果，重新验证 10 页章节、首屏请求数和进度。
