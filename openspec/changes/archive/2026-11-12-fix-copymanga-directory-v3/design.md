## Context

前一轮将网页加密目录改为 Java HTTP，协议本身可解码，但上游会对部分出口返回
`groups.default.count=0` 的合法空目录；真实 Playwright 请求也得到相同结果。
因此问题不是缺少某个网页请求头，而是该目录接口的匿名风控波动。

2026-11-12 通过代理验证了官方 v3 API：详情接口返回 `comic` 和 `groups`，
章节接口用 `limit/offset` 分页，匿名请求可取得完整公开目录。该方案不执行网页
脚本，也不需要网页目录参数。

## Goals / Non-Goals

**Goals:** 用官方 v3 API 稳定读取详情和完整目录，保持现有 API、章节 ID、缓存和
前端行为；目录请求不启动浏览器。

**Non-Goals:** 不替换搜索与章节图片解析，不处理登录/付费章节，不引入通用 API
框架、动态节点池或浏览器兜底。

## Decisions

- 固定 API 入口 `https://api.manga2026.xyz/api/v3`，请求头使用公开客户端协议
  `COPY/3.0.9`、`version=3.0.9`、`versionCode=83`、`platform=3`、`region=1`；
  每个请求生成 `request_id`。不发送 Cookie 或 Authorization。
- 详情读取 `/comic2/{pathWord}?platform=3&request_id=...`，校验
  `comic.path_word` 与请求一致、`comic.uuid` 和标题非空，并读取作者、主题、
  状态、简介和封面。
- 章节按详情分组逐个读取
  `/comic/{pathWord}/group/{groupPath}/chapters?platform=3&limit=100&offset=...`；
  每页必须返回非空列表，`results.total` 必须等于分组 `count`，直到累计数量达到
  声明值；章节 UUID 必须合法且全局唯一。
- 保留现有 30 分钟内存缓存、最多 128 部作品和同作品并发锁。只缓存成功结果，
  失败不缓存；进程重启重新 HTTP 获取。
- 上游空目录仍只重试一次，之后返回 502
  `COPYMANGA_DIRECTORY_UNAVAILABLE`；HTTP 404 或 code 210 返回 404
  `COPYMANGA_NOT_FOUND`；截断、重复、数量不符和解码失败返回
  `COPYMANGA_DIRECTORY_INVALID`。
- 请求限制固定主机、禁止重定向、15 秒超时、4 MiB 响应体和 10,000 章。代理仍由
  `MANGASHELF_COPYMANGA_PROXY` 显式配置，默认直连。

## Risks / Trade-offs

- [API 域名或版本头变化] → 明确报错，更新单一适配器；不自动抓网页或启动浏览器。
- [章节数很多需要多次请求] → 每页 100 条并受 10,000 章上限约束；仅在详情未命中
  缓存时读取，成功后复用 30 分钟。
- [图片仍可能需要登录] → 本次只解决目录；源说明和验证文档继续明确图片边界。

## Migration Plan

替换 `CopyMangaHttpDetailService` 的内部请求与解析即可，章节 ID 和接口不变。
构建后重启 API/Worker，清除旧目录缓存后验证三部作品；失败时保留明确错误，
不恢复网页 AES 目录作为静默兜底。
