# 提案：外部漫画接入（MangaDex，M16）

## Why

动画侧已有「外部番剧」能力，漫画侧只有本地导入。用户希望像看番一样直接搜索并阅读
外部漫画，无需先下载导入。

## What Changes

1. 接入 MangaDex 官方开放 API（无需密钥）：搜索、作品详情、章节列表、章节页图。
2. 图片代理 `GET /api/v1/external-manga/image?u=`：仅允许 MangaDex 图片域名，
   磁盘缓存 + 上游并发限流与 429 退避。
3. 前端 `/manga-ext`（搜索）、`/manga-ext/:id`（详情/章节）、
   `/manga-ext/read/:chapterId`（竖向滚动阅读器）。

## 不做

- 章节间自动跳转、离线下载、评论、阅读进度服务端化（后续）。
