## 1. OpenSpec 与现状审计
- [x] 1.1 审计漫画阅读、动画播放、资产签名、封面代理与 Nginx `/media/` 代理链路
- [x] 1.2 实测 SeaweedFS 3.96 对 `Cache-Control`、`response-cache-control`、`ETag`/`If-None-Match`（304）与 `Range`（206）的实际支持

## 2. 后端媒体交付优化
- [x] 2.1 在 `PresignService` 实现分层 `MediaCacheProfile`（`response-cache-control`）与授权后短期签名窗口复用（带 `mangashelf.storage.presign-reuse-enabled` 开关）
- [x] 2.2 在 `ChapterParseHandler` 与 `AnimationVerifyHandler` 上传产物时写入不可变 `Cache-Control` 元数据
- [x] 2.3 在 `AssetRepository` 增加批量 `findObjectKeysByIds`，消除 `CatalogService.pages` 与 `AnimationService.playback` 的 N+1 查询，并按媒体角色传 `MediaCacheProfile`
- [x] 2.4 为 `/api/v1/bangumi-covers/{subjectId}` 增加强 `ETag` 与 `If-None-Match` 304 条件响应
- [x] 2.5 补充单元测试（`PresignServiceTest` 等）验证签名复用、过期刷新、开关关闭与 `Cache-Control` 头参数

## 3. 前端阅读器与 Nginx 代理优化
- [x] 3.1 优化 `web/src/pages/Reader.tsx`：窗口缓存复用（按过期时间按需刷新）+ 有限预加载 `current + 1`、`current + 2`
- [x] 3.2 优化 `deploy/nginx/nginx.conf` 的 `/media/` 代理配置（HTTP/1.1 长连接、关闭大文件缓冲、强制 Range 支持）

## 4. 端到端实测与验证报告
- [x] 4.1 编写合规自生成媒体实测脚本 `scripts/bench/run_media_bench.py`（合成 20 页 WebP 漫画 + 10s H.264/AAC MP4 动画）
- [x] 4.2 在真实 PostgreSQL 18 + SeaweedFS 3.96 + Java API 环境执行基线与优化对照实测（含首图/首帧、304、Range 206、N+1 SQL、Java API 媒体带宽 0 B 核验）
- [x] 4.3 运行后端全量测试（`./server/mvnw test`）与前端构建校验（`npm --prefix web run build`）
- [x] 4.4 撰写 `docs/validation/audit-and-measure-media-delivery.md` 并更新 `README.md`
