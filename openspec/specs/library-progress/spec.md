# library-progress Specification

## Purpose
TBD - created by archiving change implement-manga-reading. Update Purpose after archive.

## Requirements

### Requirement: 收藏与取消收藏

登录用户 MUST 能收藏或取消收藏三类内容：本地作品、外部漫画（按源与作品标识）、
外部番剧目录条目。每个收藏 MUST 有且只有一个明确目标，由数据库约束保证；同一用户
对同一目标 MUST 唯一，重复加入返回既有收藏。收藏 MUST 独立于阅读与观看记录：
浏览、播放或阅读本身 MUST NOT 产生收藏。

#### Scenario: 重复加入收藏

- **WHEN** 用户对已在收藏中的作品再次发起收藏
- **THEN** 系统返回既有收藏条目，条目数量不变

#### Scenario: 收藏外部漫画

- **WHEN** 用户在外部漫画详情页收藏一部未收藏的作品
- **THEN** 收藏列表出现该作品，目标为漫画源与作品标识；阅读进度不因此产生

#### Scenario: 收藏外部番剧目录条目

- **WHEN** 用户在目录详情或浏览播放页收藏一部番剧
- **THEN** 收藏列表出现该目录条目；不创建用户作品

#### Scenario: 播放不产生收藏

- **WHEN** 用户播放未收藏的目录条目或阅读未收藏的外部漫画
- **THEN** 收藏列表不变；该活动只进入历史与继续观看

### Requirement: 阅读进度以版本号防覆盖

进度写入 MUST 携带 expected_version：匹配时更新位置并递增版本；不匹配时返回
409 PROGRESS_CONFLICT 及当前快照。进度 MUST 允许向前或向后阅读，
MUST NOT 用最大页码代替真实位置。

#### Scenario: 过期客户端写入

- **WHEN** 客户端以过期 expected_version 提交进度
- **THEN** 系统返回 409 与当前快照，已保存进度不被覆盖

#### Scenario: 向回翻阅

- **WHEN** 用户从第 10 页跳回第 3 页并保存进度
- **THEN** 进度记录为第 3 页，刷新后从第 3 页继续

### Requirement: 进度写入统一版本冲突语义

所有进度写入（本地阅读、本地观看、外部漫画、外部观看）MUST 携带 `expectedVersion`
并采用同一冲突语义：版本匹配时更新位置并递增版本，返回新快照；不匹配时 MUST 返回
409 `PROGRESS_CONFLICT`，`details.current` 为服务端当前快照，且 MUST NOT 覆盖已保存位置。
首次写入 MUST 以 `expectedVersion = 0` 表示。批量列表响应 MUST 返回每条的 `version`，
供客户端续读时携带。

#### Scenario: 外部漫画过期写入

- **WHEN** 两台设备分别以同一 `expectedVersion` 保存同一外部漫画进度
- **THEN** 先到者成功并递增版本，后到者收到 409 与当前快照，已保存进度不被覆盖

#### Scenario: 外部观看过期写入

- **WHEN** 旧请求携带过期 `expectedVersion` 保存外部集观看进度
- **THEN** 返回 409 与当前快照，播放器采纳服务端位置

#### Scenario: 首次写入

- **WHEN** 用户首次保存某作品进度并携带 `expectedVersion = 0`
- **THEN** 创建进度记录，版本为 1

#### Scenario: 缺少版本

- **WHEN** 客户端未携带 `expectedVersion`
- **THEN** 返回 400，不写入进度

### Requirement: 收藏列表统一响应

收藏列表 MUST 以统一响应返回三类目标，包含类型、标题、封面（可得时）、目标标识与
收藏时间，并 MUST 支持按类型筛选。取消收藏 MUST 仅删除对应关系，MUST NOT 删除目标内容
或阅读/观看记录。

#### Scenario: 混合收藏列表

- **WHEN** 用户同时收藏了本地作品、外部漫画与外部番剧
- **THEN** 列表按收藏时间倒序返回三类条目，各条目类型可区分且可取消

#### Scenario: 取消收藏

- **WHEN** 用户取消收藏一部外部漫画
- **THEN** 收藏关系删除，该作品的阅读进度与历史记录保留

### Requirement: 历史记录与继续观看分离

历史记录 MUST 由阅读或播放产生，按最近活动倒序，覆盖本地作品阅读、外部漫画阅读、
本地动画观看与外部番剧观看（含未收藏的浏览态播放）。继续阅读/观看 MUST 按目标聚合：
每个作品或目录条目只返回最近可恢复位置。历史 MUST NOT 被当作收藏展示；收藏列表
MUST NOT 因浏览而产生条目。

#### Scenario: 浏览态播放进入历史

- **WHEN** 用户直接播放未收藏的目录条目
- **THEN** 该次观看出现在历史记录与继续观看中，收藏列表不增加条目

#### Scenario: 继续观看按作品聚合

- **WHEN** 用户先后观看同一作品的第 1、2 集
- **THEN** 继续观看只返回该作品最近一集的位置，不重复列出多集

#### Scenario: 历史与收藏独立

- **WHEN** 用户取消收藏一部已看的作品
- **THEN** 该作品从收藏列表消失，历史与继续观看仍保留


### Requirement: 收藏支持「在追 / 已看完（已读完）」状态

`favorites` 表 MUST 以数据库约束支持 `status`（`WATCHING` / `COMPLETED`）及 `completed_at`，并提供统一状态切换端点；标记为 `COMPLETED` 的作品 MUST 移出新集追更提醒，并在个性化推荐画像中获得高于普通收藏的题材权重。

#### Scenario: 标记番剧已看完并联动追更提醒

- **WHEN** 已登录用户在番剧详情页或「我的」点击「标记已看完」
- **THEN** 收藏状态更新为 `COMPLETED`，在「我的 → 收藏」可按「已看完」筛选，且该番不再出现在追更提醒中
