## ADDED Requirements

### Requirement: Bangumi 本季新番元数据

平台从 Bangumi 官方 API 拉取本季新番日历，缓存封面、评分与放送星期，并与
资源目录按标题桥接，使新番在索引页可直接播放。

#### Scenario: 同步本季日历

- **WHEN** 管理员触发 bangumi.sync
- **THEN** Worker 拉取 api.bgm.tv/calendar 并 upsert 全部本季条目（标记 in_calendar），
  随后本地标题匹配资源目录条目；结果可查询

#### Scenario: 本季新番展示与播放

- **WHEN** 用户打开番剧索引
- **THEN** 顶部展示本季新番（Bangumi 封面、评分、放送星期），有资源覆盖的条目
  显示「可看」，点击「播放」自动物化为作品并进入作品页

#### Scenario: 封面代理

- **WHEN** 客户端请求 /api/v1/bangumi-covers/{subjectId}
- **THEN** 服务端按库内记录的 cover_url 取图并返回（仅接受库内 subjectId，
  不接受任意 URL），受限网络经配置代理访问

### Requirement: 资源目录仅包含动漫分类

资源目录只保存动漫分类（国产/日韩/欧美/港台/海外动漫）的条目。

#### Scenario: 增量同步过滤

- **WHEN** 以 incremental 模式同步（携带 categories）
- **THEN** 只入库 type_id 属于给定动漫分类的条目，非动漫条目被忽略
