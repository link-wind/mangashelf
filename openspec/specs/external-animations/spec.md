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

### Requirement: 番剧目录可检索

站内搜索 MUST 同时覆盖番剧目录与站内内容。

#### Scenario: 搜索命中目录

- **WHEN** 用户输入关键词
- **THEN** 页面展示目录匹配结果（封面卡，可进入条目详情）与站内语义/字面结果

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
