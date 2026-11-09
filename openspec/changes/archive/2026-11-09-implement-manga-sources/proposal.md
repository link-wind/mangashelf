# 提案：漫画多数据源与热门榜元数据（M17）

## Why

单一源（MangaDex）缺少「热门/排序」类元数据，且不便扩展更多来源。
需要一套可插拔的漫画源抽象，并能引入哔哩漫画这类平台的热门榜作为排序依据。

## What Changes

1. `MangaSource` 接口 + `MangaSourceRegistry`：按能力（RANK/SEARCH/DETAIL/READ）声明。
2. `BilibiliMangaSource`：HomeHot 热门榜（匿名可用）；详情/搜索需登录，声明为不支持。
3. `MangaDexSource`：榜单（关注数）、搜索、详情、阅读。
4. 统一入口 `/api/v1/manga-sources/...`；能力缺失显式返回 409。
5. 前端漫画页「热门漫画」可按源切换；不可站内读的条目跳官方页并标注「站外」。

## 不做

- 跨源实体去重、个性化推荐、按源偏好配置、哔哩漫画登录态接入。
