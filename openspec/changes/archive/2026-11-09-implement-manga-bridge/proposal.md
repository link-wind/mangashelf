# 提案：跨源桥接与漫画进度服务端化（M18）

## Why

M17 引入了哔哩漫画热门榜，但条目只能跳原站；同一作品在 MangaDex 明明可读。
另外外部漫画进度只存 localStorage，换设备丢失，也没进观看历史。

## What Changes

1. `MangaMatcher`：用中文标题在 MangaDex 检索同作（MangaDex 标题检索跨语言），
   三级置信（精确 / 相似度 / 检索首位），结果缓存 1 小时。
2. `RankItem` 增加 `readSource`/`readId`：命中即可站内阅读。
3. V13 `external_manga_progress` + `/api/v1/me/manga-progress`；
   阅读器续读与节流上报；历史页增加「漫画阅读」。

## 不做

- 章节级对齐、读完自动下一话、多源进度合并。
