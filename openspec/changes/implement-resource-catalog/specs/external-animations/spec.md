## ADDED Requirements

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
