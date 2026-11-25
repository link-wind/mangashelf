# Delta for manga-catalog

## ADDED Requirements

### Requirement: 目录列表精简投影与索引化读路径

番剧目录分页（`GET /api/v1/catalog-items`）与作品列表（`GET /api/v1/works`）MUST 避免向列表响应传输仅详情页使用的巨型播放线路载荷（`play_groups`），高频排序键（更新时间表达式、集数降序）、题材数组筛选（`bangumi_subjects.tags`）与公开作品游标查询 MUST 具备对应数据库索引支撑；目录详情与播放接口 MUST 仍完整返回线路信息，对外功能行为不变。

#### Scenario: 列表不传输巨型线路载荷而详情保持完整

- **WHEN** 客户端请求 `GET /api/v1/catalog-items` 获取番剧列表，随后请求其中一条的 `GET /api/v1/catalog-items/{id}`
- **THEN** 列表查询走索引且不读取 `play_groups` 巨型 JSONB，详情接口仍完整返回全部播放线路与集数信息

### Requirement: 热点只读缓存显式失效与可观测

针对压测证实的高频低变只读接口（本季日历、无筛选首屏目录与总计数），缓存层 MUST 具备明确 TTL、容量上限、并发回源合并（防缓存击穿）与显式失效路径（目录或 Bangumi 同步完成后主动失效），并通过 Micrometer 暴露缓存命中与未命中计数；缓存失效或禁用时 MUST 透明回源数据库。

#### Scenario: 同步完成后热点缓存立即失效

- **WHEN** 本季日历或番剧目录完成一次同步更新
- **THEN** 对应热点缓存被主动清空，下一次读取返回最新数据库结果，且命中/未命中指标可观测
