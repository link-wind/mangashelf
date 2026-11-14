## ADDED Requirements

### Requirement: 本季新番元数据含题材标签

Bangumi 日历同步 MUST 保存条目的题材标签（tags，如热血/机甲/恋爱）；番剧索引 MUST
支持按题材筛选目录条目（依据条目关联的 Bangumi 标签），题材列表来自已同步的标签集合。
未关联 Bangumi 的条目 MUST NOT 出现在题材筛选结果中。

#### Scenario: 同步保留标签

- **WHEN** bangumi.sync 拉取本季日历
- **THEN** 每个条目的 tags 保存到库中，重复同步不丢失

#### Scenario: 按题材筛选番剧

- **WHEN** 用户在番剧索引选择「热血」
- **THEN** 列表仅显示关联 Bangumi 标签含热血的目录条目
