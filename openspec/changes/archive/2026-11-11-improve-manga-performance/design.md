# 设计：漫画性能优化

## 服务端

- `MangaSectionsService`：key=source:limit，TTL 10 分钟；`MangaSourceController`
  与 `MangaDiscoveryService` 共用，避免重复抓取。
- `MangaWarmup`（仅 api profile）：ApplicationReadyEvent 后台调用
  `discovery.discover(12)`，填充分区/聚合缓存。实测预热 4.4s。
- `MangaBrowserResolveService`：内存缓存之外增加磁盘 JSON 缓存
  （`data/manga-browser-cache/{sha}.json`，30 分钟），任务结果落盘后重启可用。

## Python

- `browser_plan`：页图配置 `images.regex` 时跳过滚动；goto 后等待 load、
  `_settle` 等 URL 稳定（站点常在数据后 302）；evaluate/content 遇导航竞态重试；
  镜像页空壳自动 reload 一次。
- `manga_resolve`：章节页先无滚动提取 data-src，失败再滚动，节省 5s。
- 规则等待 2.5s（包子/嬉皮），保留安全值（1.5s 会与跳转竞争）。

## 前端

- 首页分区缓存 `ms-manga-cache:{source}`，10 分钟；有缓存先渲染，后台刷新，
  失败保留旧内容并提示。
- 阅读器滚动处理器缓存 `img` 列表，100ms 节流；长章节滚动不再每帧遍历全部图片。

## 实测

| 场景 | 结果 |
|---|---|
| 首页首次可交互（预热后） | 263ms |
| 切换数据源（拷贝） | 110ms |
| 冷启动聚合接口 | 15ms（预热完成） |
| 拷贝详情/页图 冷 | 7.8s / 5.4s |
| 拷贝详情/页图 重启后（磁盘缓存） | 15ms / 8ms |
| 包子页图（可达时） | 6s（原 14s） |
