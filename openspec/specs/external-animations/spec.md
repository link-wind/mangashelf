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

外部集播放 MUST 由服务端解析直链并交浏览器直连来源；解析任务 MUST 由 Worker 执行，
API 角色 MUST NOT 运行浏览器。解析幂等、失败显式降级与 token 过期续播行为保持不变；
无 Worker 消费解析队列时 MUST 显式提示解析不可用。

#### Scenario: 解析并播放外部集

- **WHEN** 用户点播一部已绑定外部集
- **THEN** 服务端创建解析任务由 Worker 执行，完成后返回直链由浏览器直连播放

#### Scenario: 解析失败显式降级

- **WHEN** 解析失败（源站无直链或超时）
- **THEN** 返回明确错误与可重试入口，不伪造播放地址

#### Scenario: 解析幂等

- **WHEN** 对同一集重复发起解析（幂等窗口内）
- **THEN** 复用同一任务与结果，不重复排队

#### Scenario: token 过期续播

- **WHEN** 直链 token 过期
- **THEN** 重新解析可恢复播放，进度保留

#### Scenario: 无 Worker 时解析不可用

- **WHEN** 无 Worker 消费解析队列时发起解析
- **THEN** 任务保持待执行并提示解析不可用，不假装成功

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

用户 MUST 能直接点播目录条目（含时刻表与目录详情入口），播放 MUST NOT 创建用户作品
或改变目录条目归属。只有用户显式执行「导入为我的作品」时，平台才 MUST 创建作品并把
剧集收编到作品下；重复导入 MUST 返回既有作品，不重复建集。

#### Scenario: 时刻表直接播放

- **WHEN** 用户在时刻表点击一部已收录番剧的播放
- **THEN** 进入浏览态播放并记录进度，不创建作品，作品数量不变

#### Scenario: 显式导入

- **WHEN** 用户对未物化的目录条目执行「导入为我的作品」
- **THEN** 平台创建作品、按线路绑定剧集并回链 work_id；重复导入返回既有作品

#### Scenario: 物化并播放

- **WHEN** 用户显式导入目录条目后播放
- **THEN** 平台按线路解析绑定外部集并回链 work_id，随后播放复用既有解析与直连链路；
  重复导入返回既有作品，不重复建集

#### Scenario: 导入前后进度稳定

- **WHEN** 用户先浏览态观看某集，之后导入该目录条目并继续观看
- **THEN** 续播位置保持，不因导入丢失或重置

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

### Requirement: 资源目录仅包含动漫分类

资源目录只保存动漫分类（国产/日韩/欧美/港台/海外动漫）的条目。

#### Scenario: 增量同步过滤

- **WHEN** 以 incremental 模式同步（携带 categories）
- **THEN** 只入库 type_id 属于给定动漫分类的条目，非动漫条目被忽略

### Requirement: 番剧目录可检索

站内搜索 MUST 同时覆盖番剧目录与站内内容。番剧索引 MUST 支持标题搜索、类型分类
（全部/日韩/国产/欧美/海外）以及状态筛选（连载中/已完结/剧场版·HD，依据资源站
remarks/categoryName）与排序（最近更新/集数最多）。筛选与排序 MUST 在服务端执行，
结果分页可用。

#### Scenario: 搜索命中目录

- **WHEN** 用户输入关键词
- **THEN** 页面展示目录匹配结果（封面卡，可进入条目详情）与站内语义/字面结果

#### Scenario: 按状态筛选

- **WHEN** 用户在番剧索引选择「已完结」
- **THEN** 列表仅显示 remarks 标注已完结的条目

#### Scenario: 按更新排序

- **WHEN** 用户选择「最近更新」排序
- **THEN** 列表按资源站最近更新时间倒序排列

#### Scenario: 组合筛选

- **WHEN** 用户同时选择类型、状态并搜索标题
- **THEN** 三个条件叠加生效，分页保持可用

### Requirement: 外部集播放进度由服务端保存

外部集的观看进度 MUST 保存在服务端并跨设备续播；切换线路 MUST NOT 丢失进度。
进度写入 MUST 携带 `expectedVersion`，版本冲突时返回 409 `PROGRESS_CONFLICT` 与当前快照，
MUST NOT 覆盖已保存位置；历史与续看列表 MUST 返回 `version` 供播放器携带，
并 MUST 支持按作品与集号读取单条进度。

#### Scenario: 跨设备续播

- **WHEN** 用户在某设备观看到 position，之后在另一设备打开同一作品同一集
- **THEN** 播放器从该 position 续播

#### Scenario: 切换线路保持进度

- **WHEN** 用户在某线路看到 position 后切换到另一线路的同集号
- **THEN** 播放器仍从该 position 续播

#### Scenario: 过期写入不覆盖

- **WHEN** 旧请求携带过期 `expectedVersion` 保存观看进度
- **THEN** 返回 409 与当前快照；播放器以服务端版本更新本地状态并显式提示，不静默覆盖

### Requirement: 多线路物化与切换

物化 MUST 写入目录条目全部线路；观看页 MUST 支持线路切换与正片/PV 分组。

#### Scenario: 切换线路

- **WHEN** 用户在选择器点击另一线路
- **THEN** 跳转到该线路的同一集号，选集列表随线路更新

### Requirement: 周期同步

Worker MUST 周期性同步资源目录与 Bangumi 日历，事实记录于 jobs 表。

#### Scenario: 目录自动增量同步

- **WHEN** 距上次目录同步超过 30 分钟
- **THEN** Worker 创建 catalog.sync（incremental, h=24, 动漫分类）任务

### Requirement: 解析限流

解析端点 MUST 限制单用户频率，防止被用作公开解析服务。

#### Scenario: 超频被拒绝

- **WHEN** 单用户一分钟内发起超过 20 次解析请求
- **THEN** 返回 429 RESOLVE_RATE_LIMITED

### Requirement: 站内弹幕

平台 MUST 支持按作品与集号存储和展示弹幕，弹幕随播放时间轴滚动。

#### Scenario: 发送弹幕

- **WHEN** 用户在当前播放时间点发送 1–200 字弹幕
- **THEN** 弹幕入库并在同一集下一次播放时按时间点出现

#### Scenario: 断线重连与跳转

- **WHEN** 用户拖动进度条回退
- **THEN** 弹幕层清空并从新时间点重新同步，不重复堆积

### Requirement: 自定义播放器

观看页 MUST 提供自绘播放控制（播放/暂停、进度、时间、倍速、弹幕开关、全屏），
HLS 与 MP4 均可播放。

#### Scenario: 播放 HLS 外部集

- **WHEN** 解析得到 m3u8 直链
- **THEN** 播放器通过 hls.js 播放，控制条可用，进度上报服务端

### Requirement: 首页内容分区

首页 MUST 展示 Banner 轮播、新番时刻表预览、最近更新、本季高分榜与分类入口，
数据均来自平台已有接口。

#### Scenario: Banner 轮播切换

- **WHEN** 用户查看首页
- **THEN** 首屏展示本季评分前 4 的轮播 Banner，可点击缩略图切换，含播放/追番入口

#### Scenario: 最近更新

- **WHEN** 用户查看首页
- **THEN** 「最近更新」按资源更新时间倒序展示目录条目及更新进度

### Requirement: 点进动画作品直达播放

仅有外部集的动画作品 MUST 在打开作品时直接进入播放页。

#### Scenario: 首次打开

- **WHEN** 用户从列表点进一个只有外部集的动画作品
- **THEN** 浏览器直接落在该作品首集的播放页

#### Scenario: 已有观看进度

- **WHEN** 用户此前看过该作品第 N 集
- **THEN** 打开作品时直接进入第 N 集（优先上次线路）

### Requirement: 浏览态播放

未入库的目录条目 MUST 可直接播放，且播放本身不创建作品。

#### Scenario: 点击首页卡片

- **WHEN** 用户点击未入库的目录条目
- **THEN** 进入浏览态播放页，书库作品数量不变

### Requirement: 浏览态收编

用户显式加入书库时，已生成的浏览态剧集 MUST 整体转挂到作品，不重复解析。

#### Scenario: 加入书库

- **WHEN** 用户在浏览态播放页点击「加入书库」
- **THEN** 创建作品并把该条目剧集改挂到作品，跳转到作品播放页

### Requirement: 浏览态播放进度

未物化目录条目的播放进度 MUST 保存在服务端并跨设备续播；进度 MUST 以目录条目与集号
为键并携带版本冲突语义（与外部集进度一致）。目录条目被导入后，系统 MUST 仍为该剧集
解析到同一目录条目进度键，使续播位置保持不变。

#### Scenario: 浏览态续播

- **WHEN** 用户在一台设备浏览态看到 position，之后在另一设备打开同一条目同一集
- **THEN** 播放器从该 position 续播

#### Scenario: 浏览态过期写入

- **WHEN** 旧请求携带过期 `expectedVersion` 保存浏览态进度
- **THEN** 返回 409 与当前快照，播放器以服务端版本更新本地状态并提示


### Requirement: 番剧目录多维分类、年份评分排序与页码分页

`GET /api/v1/catalog-items` MUST 支持 1-based `page` 页码分页并返回 `total` 与 `totalPages`，同时支持 `year`（年份）筛选、`sort=rating`（评分最高）排序及关键字 `q` 下的准确总数统计；系统 MUST 提供 `GET /api/v1/catalog-items/genres` 聚合库内热门题材。

#### Scenario: 组合筛选与关键词下的准确分页

- **WHEN** 客户端按分区、题材、状态、年份、排序与关键字请求指定页码
- **THEN** 返回该页条目与匹配的 `total`/`totalPages`，与无分页全量筛选总数一致

### Requirement: 动画追更提醒与轻量个性化推荐

系统 MUST 提供 `GET /api/v1/recommendations/anime`，对登录用户检测已收藏或已观看连载番的新更集数生成追更提醒，并基于收藏与观看历史题材/分区画像结合贝叶斯评分、更新活跃度与题材打散生成带解释理由的推荐列表；访客访问时自动返回精选公开推荐且不读取私有数据。

#### Scenario: 已追连载番出现新集时生成追更提醒

- **WHEN** 用户曾观看至第 5 集，且目录同步后该番 `episode_count` 更新至 8
- **THEN** 追更提醒列表包含该番并标明最新集数与新增 3 集
