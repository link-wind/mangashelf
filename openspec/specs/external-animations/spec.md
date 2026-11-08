# external-animations Specification

## Purpose
TBD - created by archiving change implement-external-animations. Update Purpose after archive.

## Requirements

### Requirement: 管理员导入声明式规则源

管理员 MUST 能导入 Kazumi 兼容 JSON 规则（XPath / JSON API 两模式）并启停；
规则 MUST 包含 name / baseURL / 搜索与章节解析字段。非管理员 MUST NOT 能导入或修改规则。

#### Scenario: 导入规则并启用

- **WHEN** 管理员提交合法规则 JSON 并启用
- **THEN** 规则出现在启用列表中，用户可对其搜索

### Requirement: 用户搜索并绑定外部集

作品所有者 MUST 能用启用的规则源搜索动画，并将搜索结果绑定为外部集；
绑定 MUST 记录来源 ID 与播放地址。解绑后外部集不再展示。

#### Scenario: 搜索并绑定

- **WHEN** 所有者用规则源搜索关键词并选择结果绑定
- **THEN** 外部集创建，作品详情可见

#### Scenario: 非所有者绑定

- **WHEN** 非所有者尝试为作品绑定外部集
- **THEN** 返回 404（不暴露作品存在性）
