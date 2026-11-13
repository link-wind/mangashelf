## 1. 数据库与收藏后端

- [x] 1.1 新增 `V24__favorites_and_browse_progress.sql`：建 `favorites`（CHECK + 三部分唯一索引）、复制 `library_entries` 后删表、建 `catalog_watch_progress`
- [x] 1.2 新增收藏仓储/服务/端点：`GET /me/favorites`、`PUT|DELETE /me/favorites/work/{workId}`、`PUT|DELETE /me/favorites/manga`、`PUT|DELETE /me/favorites/catalog/{itemId}`；三目标幂等、类型校验
- [x] 1.3 真实 PostgreSQL 验证：复制迁移、重复收藏幂等、非法组合被约束拒绝、取消收藏不动进度

## 2. 收藏前端

- [x] 2.1 `Shelf.tsx` 只保留收藏：漫画（本地作品 + 外部漫画）与动漫（目录条目）分组、取消收藏、空状态
- [x] 2.2 `WorkDetail`、`MangaDetail`、`ExternalMangaDetail`、`CatalogDetail` 接入新收藏端点与状态展示
- [x] 2.3 浏览播放页 `BrowseWatch` 提供收藏/取消收藏，替代「加入书库」

## 3. 浏览态播放进度

- [x] 3.1 新增 `GET|PUT /api/v1/me/episode-progress`：按 `episodeId` 路由目录/作品进度表，版本冲突 409 + 当前快照
- [x] 3.2 `BrowseWatch` 读写浏览态进度：进入恢复位置、播放中版本化写入、冲突提示
- [x] 3.3 `ExternalWatch` 改用 `episode-progress`（作品态同一切口），删除旧 `external-progress` 调用
- [x] 3.4 验证物化前后同一集进度键稳定：浏览观看 → 导入 → 作品页续播位置一致

## 4. 历史与继续观看

- [x] 4.1 新增 `GET /me/history`（按章节/集展开的四类活动）与 `GET /me/continue`（按作品/目录条目聚合）
- [x] 4.2 `History.tsx` 改用统一历史（含本地阅读与动画）；`Home` 改用 `/me/continue`；`Shelf` 移除进度区块
- [x] 4.3 退役 `/me/library`、`/me/watch-history`、`/me/continue-watching`、`/external-progress`、`/me/external-progress`
- [x] 4.4 验证：浏览态播放进入历史与续看、取消收藏保留历史、继续观看每个目标只一行

## 5. 播放去物化

- [x] 5.1 `Schedule.tsx` 播放直接跳浏览态入口，不再调用 materialize
- [x] 5.2 `CatalogDetail.tsx` 主操作为立即播放 + 收藏；「导入为我的作品」作为次要操作并说明后果
- [x] 5.3 验证：时刻表播放不创建作品；显式导入后作品出现且重复导入幂等

## 6. 播放逻辑合并

- [x] 6.1 抽取 `useEpisodePlayback`：解析轮询、选集跳转、版本化进度写入与冲突提示
- [x] 6.2 `BrowseWatch` 与 `ExternalWatch` 改用共享 hook，删除重复实现，保持路由与展示不变

## 7. 验证与收尾

- [x] 7.1 浏览器全场景验证：收藏增删、历史/续看、浏览态播放与续播、导入前后续播、多设备冲突提示
- [x] 7.2 `mvnw test`、Python 套件、`npm run build` 全部通过
- [x] 7.3 验证结果写入 `docs/validation/separate-favorites-and-history.md`
