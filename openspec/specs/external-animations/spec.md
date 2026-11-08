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

### Requirement: 外部集站内播放（解析直链 + 浏览器直连）

外部集默认通过站内播放器播放：作品可见者触发解析任务后，由前端 hls.js 直连
解析出的视频地址播放，不经平台服务器转发。

#### Scenario: 解析并播放外部集

- **WHEN** 作品可见者对已绑定外部集发起解析（POST resolve）
- **THEN** 平台创建 external.resolve 任务，Worker 以无头浏览器加载剧集页并嗅探
  视频直链，成功后写入 external_episodes.resolved_url 与 resolved_at
- **AND** 前端获取到新鲜（4 分钟内）的解析结果后用 hls.js 直接播放，视频字节
  不经过平台服务器

#### Scenario: 解析失败显式降级

- **WHEN** 嗅探超时或未发现视频流
- **THEN** 任务 FAILED 并携带错误码，前端显式展示错误并提供"跳转原站"入口，
  不做静默回退

#### Scenario: 解析幂等

- **WHEN** 同一外部集 30 秒窗口内重复发起解析且已有 QUEUED/RUNNING 任务
- **THEN** 返回既有任务，不创建重复任务

#### Scenario: token 过期续播

- **WHEN** 播放中视频流失效（源站签名 token 过期）
- **THEN** 前端自动重新解析并从上次播放位置继续，不要求用户重新操作

### Requirement: 资源站目录同步

平台可从规则源关联的资源站 API 同步全量/增量目录到资源目录层，目录条目与
个人书库分离。

#### Scenario: 全量同步

- **WHEN** 管理员触发 catalog.sync（full 模式，指定动漫分类）
- **THEN** Worker 逐分类逐页拉取资源站目录并以 (source_id, vod_id) 幂等 upsert，
  页间限速；任务状态与结果可查询

#### Scenario: 增量同步

- **WHEN** 以 incremental 模式触发（h=24）
- **THEN** 仅拉取资源站最近更新窗口内的条目，已有条目集数/备注被更新

### Requirement: 目录条目点播物化

用户可将目录条目物化为个人书库作品并播放。

#### Scenario: 物化并播放

- **WHEN** 用户对未物化的目录条目发起 materialize
- **THEN** 平台创建作品并按线路解析绑定外部集、回链 work_id；随后播放复用
  既有解析与直连链路；重复物化返回既有作品，不重复建集
