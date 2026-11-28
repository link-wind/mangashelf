# 任务清单：integrate-7sefun-backup-line

- [x] 创建 OpenSpec 变更文档（proposal / design / tasks / spec delta）
- [x] 新增 Flyway `V38__seed_7sefun_source.sql` 幂等固化 `七色番 (7sefun)` 规则源
- [x] 实现 `RuleEngine` 瞬时网络错误重试与重定向支持
- [x] 实现 `RuleLineMatcher` 季期感知标题匹配及单元测试
- [x] 在 `ExternalCatalogService` / `ExternalController` / `SecurityConfig` 中实现 `POST /api/v1/catalog-items/{id}/extra-lines` 与 `ensureBrowseEpisodes` 多源隔离
- [x] 在 `ExternalService` 中补充签名直链 `Expires` 提前过期判定及单元测试
- [x] 在 `processing/tasks/external_resolve.py` 中实现 MacCMS `player_aaaa` 纯 HTTP 秒解快路径并补齐 Python 单测
- [x] 在前端 `BrowseWatch.tsx` / `EpisodePlayerShell.tsx` / `lineLabel.ts` 接入「+ 高清直连线」按钮与线路展示名
- [x] 运行 Java 单测、Python 单测、前端 TypeScript/Vite 构建
- [x] 重启本地 API/Worker，完成真实番剧目录条目的「挂线 → 秒解 → 播放」端到端验证并写入 `docs/validation/integrate-7sefun-backup-line.md`
- [x] 清理临时私有测试作品残留，确认工作区整洁
