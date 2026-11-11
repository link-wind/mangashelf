# 提案：嬉皮漫画声明式源 + 分页章节 API（M33）

## Why

继续验证声明式浏览器源：嬉皮漫画站点可达，章节列表由公开分页 API 提供，
阅读页图全部在 HTML 的 `img[data-src]` 中。用规则接入可证明「新增站点只加
配置」，同时补齐 browserPlan 对分页 API 的支持。

## What Changes

1. browserPlan 支持 `detail.chaptersApi`：按 `{page}` 循环请求公开 JSON API，
   读取 `data.items` 与 `data.total_pages`，映射为统一章节列表。
2. browserPlan 的 pages 变量通用化：`{chapterId}` 原样可用；若章节 ID 为
   `{mangaId}|{slot}` 形态再拆出 `{comicId}`/`{slot}`。
3. 嬉皮漫画规则（V20，SEARCH/DETAIL/READ）：搜索 `/search?q=`；详情章节走
   `hipapi1.s3file.top/v1/manga/chapters`（50/页，共 256 话自动翻页）；
   阅读页用 `img[data-src*='hip-tx']` 一次取全部页图。
4. 图片代理白名单加 `*.s3imgs.top`。

## 不做

- 漫蛙：章节图是 `blob:`（浏览器内解密生成），无法作为 URL 交给阅读器；
  需另做「浏览器取字节」管线，暂不接入。
