# 设计：外部漫画（MangaDex，M16）

## D1. 数据来源

MangaDex 开放 API（api.mangadex.org），直连可达；`MANGASHELF_MANGADEX_PROXY` 可配代理。
章节页图由 `at-home/server/{chapterId}` 返回 CDN 基址与文件清单。

## D2. 图片策略

- 封面：统一走服务端代理（体积小、可缓存、避免热链限制）。
- 页图：默认浏览器直连 CDN（省服务器带宽），失败自动回退代理；
  默认 dataSaver 尺寸（~120KB/页），可切高清。
- 代理安全：仅 `uploads.mangadex.org` 与 `*.mangadex.network`；按 URL 哈希落盘
  `data/manga-cache`；信号量并发 3 + 429 退避（上游约 5 req/s）。

## D3. 阅读器

竖向滚动（条漫/日漫通用），`loading=lazy` 仅前 3 页 eager，滚动位置换算页序存 localStorage。

## D4. 章节可用性

MangaDex 部分章节为「外链」（`externalUrl` 非空、pages=0），此类章节在列表中不提供阅读，
详情页显式说明并建议切换语言。
