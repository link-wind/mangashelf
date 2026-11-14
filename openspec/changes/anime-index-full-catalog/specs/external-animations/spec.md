## MODIFIED Requirements

### Requirement: Bangumi 本季新番元数据

平台从 Bangumi 官方 API 拉取本季新番日历，缓存封面、评分与放送星期，并与
资源目录按标题桥接。番剧索引 MUST 以全量资源目录为分类浏览主体；
本季新番 MUST 作为索引内的筛选视图保留（按 Bangumi 关联过滤），MUST NOT 再
占据页面主体。新番时刻表与本季入口 MUST 保持可用。

#### Scenario: 同步本季日历

- **WHEN** 管理员触发 bangumi.sync
- **THEN** Worker 拉取 api.bgm.tv/calendar 并 upsert 全部本季条目（标记 in_calendar），
  随后本地标题匹配资源目录条目；结果可查询

#### Scenario: 番剧索引展示全部番剧

- **WHEN** 用户打开番剧索引
- **THEN** 默认展示资源目录全部条目的封面网格（分类/状态/题材/排序/搜索可用），
  本季新番不再占据页面主体

#### Scenario: 本季新番筛选视图

- **WHEN** 用户在番剧索引选择「本季新番」类型
- **THEN** 列表仅显示 Bangumi 日历关联的条目，有资源覆盖的显示「可看」并可播放

#### Scenario: 本季新番展示与播放

- **WHEN** 用户在番剧索引切换到「本季新番」类型并选择有资源覆盖的条目播放
- **THEN** 播放行为与全量目录条目一致（浏览态播放/导入），不因视图切换而不同

#### Scenario: 封面代理

- **WHEN** 客户端请求 /api/v1/bangumi-covers/{subjectId}
- **THEN** 服务端按库内记录的 cover_url 取图并返回（仅接受库内 subjectId，
  不接受任意 URL），受限网络经配置代理访问
