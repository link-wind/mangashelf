# 提案：多源聚合推荐（中文源）+ 数据源过滤

## Why

漫画首页目前按单一源展示推荐/榜单，用户需要在各源之间切换才能看全。用户要求
聚合多源推荐，并明确：**只保留中文数据源与中文内容**。

## What Changes

1. 数据源声明语言：`MangaSource.language()`（腾讯动漫/哔哩漫画=zh，
   MangaDex=multi）；`/manga-sources` 返回 language，前端据此过滤。
2. 新增 `MangaDiscoveryService`：仅聚合 zh 源，虚拟线程并行抓取（单源 8s
   超时、失败隔离），RRF（reciprocal rank fusion，k=60）融合排序 +
   标题归一化去重 + 中文标题过滤，整体缓存 10 分钟。
3. 新增 `GET /api/v1/manga-discovery?limit=`：第一行「综合热榜」，其后为
   各中文源的原始分区；失败源在 `errors` 中说明，不影响其余源。
4. 前端 /manga：源切换增加「综合」并作为默认；hero 与综合热榜取融合结果，
   下方保留各源分区；源 chips 只显示中文源。
5. 外部漫画搜索页也只保留中文源（移除 MangaDex 入口；旧链接仍可用）。

## 不做

- 作品级同作合并卡片（方案 C，后续）；
- 个性化推荐（方案 D，需用户画像）；
- 物理删除 MangaDex 代码与数据（旧链接、跨源桥接仍依赖读取能力）。
