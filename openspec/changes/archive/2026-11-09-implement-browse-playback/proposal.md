# 提案：浏览态播放（M20）

## Why

外部剧集必须挂在作品上，导致「点开即播」只能先物化——纯浏览也会在书库留下作品，
一次点击即产生一条记录，污染书库。

## What Changes

1. V14：`external_episodes` 支持挂目录条目（`catalog_item_id`），`work_id` 可空，
   归属二选一；唯一键改为两个部分索引。
2. 目录条目剧集懒生成；新增 `/catalog-items/{id}/episodes` 与 `play-target`。
3. 新增浏览态播放页 `/browse/:itemId/:episodeId`（不建作品）。
4. 物化改为收编：`adoptEpisodes` 把浏览态剧集改挂作品，不再重复解析。

## 不做

- 浏览态进度服务端化、浏览→入库的进度迁移。
