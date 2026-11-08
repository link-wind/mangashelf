# 任务：M6 外部动画接入

## 迁移

- [x] V7：external_sources（name, base_url, rules_json JSONB, enabled）、
      external_episodes（work_id, source_id, title, episode_url, ordinal）

## external 模块

- [x] ExternalSourceRepository / ExternalEpisodeRepository
- [x] 规则引擎：XPath 模式（搜索列表/标题/链接）+ JSON API 模式（JSONPath）
- [x] POST /api/v1/admin/external-sources（管理员导入规则 JSON）
- [x] GET /api/v1/external-sources（启用列表）
- [x] POST /api/v1/works/{id}/external-search {sourceId, keyword} → 搜索结果
- [x] POST /api/v1/works/{id}/external-episodes {sourceId, episodes: [{title, url, ordinal}]} → 绑定
- [x] GET /api/v1/works/{id}/external-episodes → 已绑定列表
- [x] DELETE /api/v1/external-episodes/{id} → 解绑

## 前端

- [x] 管理员规则源管理（导入/启停/删除）
- [x] 作品详情：外部集列表 + "从外部源添加"搜索绑定入口
- [x] 外部集播放跳转

## 验证

- [x] smoke-m6：导入规则 → 搜索 → 绑定 → 列表 → 解绑
- [x] docs/validation/m6.md
