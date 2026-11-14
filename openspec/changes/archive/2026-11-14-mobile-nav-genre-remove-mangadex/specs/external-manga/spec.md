## MODIFIED Requirements

### Requirement: 外部漫画搜索与阅读

平台 MUST 通过统一数据源接口（manga-sources）按源搜索外部漫画并提供站内阅读；
所有源共用同一详情、章节与页图响应协议，前端 MUST NOT 按源分叉调用不同端点。
多语言筛选与外链章节为已移除的 MangaDex 专属行为，MUST NOT 再出现。

#### Scenario: 搜索并阅读

- **WHEN** 用户在漫画索引搜索并打开一部作品、选择可读章节
- **THEN** 详情、章节与页图请求统一走按源接口，阅读器按章节顺序竖向展示页面图片

#### Scenario: 语言与清晰度

- **WHEN** 用户打开一个源的作品
- **THEN** 不提供多语言切换（源各自决定内容语言），阅读器清晰度/模式设置按源可用性生效

#### Scenario: 外链章节

- **WHEN** 读取含 MangaDex 时期外链标记的历史数据
- **THEN** 不再展示外链章节语义；相关数据已随 MangaDex 源移除清理，章节按可读性正常展示

## REMOVED Requirements

### Requirement: 图片代理安全

**Reason**: 图片代理为 MangaDex 专属（仅允许其官方图片域名）；MangaDex 源整体移除后，
其余源的封面均为可直接访问的地址，代理端点与白名单机制一并删除。
**Migration**: 前端封面改为直连源站地址；历史请求该端点将返回 404。
