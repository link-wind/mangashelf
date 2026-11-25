# animation-viewing Specification

## Purpose
TBD - created by archiving change implement-animation-viewing. Update Purpose after archive.

## Requirements

### Requirement: 动画内容与媒体分离

动画容器 MUST 保存标题、简介、可见性与对外状态；动画版本 MUST 保存视频、封面、字幕
与来源映射。修订视频 MUST 创建新版本；版本号在同一动画内唯一。

#### Scenario: 创建动画并进入版本流程

- **WHEN** 用户创建动画并创建新版本
- **THEN** 版本处于 DRAFT，等待资产绑定与验证

### Requirement: 媒体经验证才能 READY

系统 MUST 使用固定任务对视频执行实际检查（容器、编码、时长、分辨率、音轨），
声明的 content-type 与扩展名 MUST NOT 替代媒体检查。检查通过后版本进入 READY；
无声视频 MUST 以实际检查结果标注，不伪造合格标签。不支持的编码 MUST 明确失败。

#### Scenario: 伪装扩展名的文件

- **WHEN** 用户把文本文件改名为 .mp4 上传并提交验证
- **THEN** 任务失败并给出可定位错误码，版本进入 FAILED，不产生播放能力

#### Scenario: 合法 MP4 完成验证

- **WHEN** h264+aac 的 MP4 完成验证
- **THEN** 版本 READY，时长/分辨率/编码入库，封面与字幕产物可读

### Requirement: 来源映射指向固定章节版本

每条来源 MUST 包含固定章节版本 ID、起止自然页码与顺序；同一动画版本内顺序 MUST 唯一；
页码范围 MUST 在事务内对照被引用版本的页数校验。一段动画 MAY 包含多条来源（跨章节、
不连续页面）；同一漫画页 MAY 关联多个动画。

#### Scenario: 页码范围越界

- **WHEN** 来源声明的 page_end 超过被引用版本的页数
- **THEN** 绑定被拒绝，错误指明超出范围

### Requirement: 发布前完成五道校验

发布事务 MUST 校验：操作者拥有动画；视频资产已核验且归属同一用户与当前草稿；
来源版本存在且发布者有权引用；来源清单完整、页码范围有效；
**公开动画的全部来源满足公开条件**。全部通过后版本置 PUBLISHED 并原子切换当前版本；
两个并发发布 MUST 只有一次成功。

#### Scenario: 公开动画引用私人来源

- **WHEN** 用户以 PUBLIC 可见性发布引用私人章节版本的动画
- **THEN** 发布被拒绝并指明来源不满足公开条件

#### Scenario: 媒体未完成时发布

- **WHEN** 版本仍处于 VERIFYING/FAILED 时请求发布
- **THEN** 发布被拒绝（409），读者不会看到不完整内容

### Requirement: 播放经授权并返回短期媒体地址

播放信息 MUST 在完成身份与可见性检查后返回；视频字节由对象存储经短期签名地址提供，
API MUST NOT 将整段视频加载进内存。Range 与拖动进度 MUST 可用。

#### Scenario: 未授权播放

- **WHEN** 用户播放未公开且不属于自己的动画
- **THEN** 返回 404，不暴露存在性

#### Scenario: 拖动进度

- **WHEN** 播放器以 Range 请求视频中段
- **THEN** 返回 206 与对应字节区间

### Requirement: 页级关联与回到原作

漫画页 MUST 能查询到覆盖该页的已发布动画（当页有权阅读）；观看页 MUST 显示来源作品、
章节与页码范围并提供回到原作的入口。有多个改编版本时 MUST 展示可选列表。

#### Scenario: 从漫画页进入视频再返回

- **WHEN** 读者在覆盖页点击"观看这段动画"，随后在观看页点击回到原作
- **THEN** 进入播放页；返回后定位到来源章节版本的原页码

### Requirement: 动画媒体直连缓存与 Range 交付保障

动画播放接口 MUST 批量查询视频、封面与字幕资产对象键，并为视频、封面与字幕分别下发明确的 `Cache-Control` 响应头；视频字节 MUST 经对象存储直出（Java API 转发 0 字节媒体流量），反向代理 `/media/` MUST 支持 HTTP/1.1 长连接、流式透传与字节 `Range` 分段播放。

#### Scenario: 动画首段加载与中段拖动返回 206

- **WHEN** 客户端使用播放接口签发的视频地址先后发起 `Range: bytes=0-65535` 与中段字节区间请求
- **THEN** 对象存储直接返回 `206 Partial Content`、`Accept-Ranges: bytes`、`Content-Range`、`Cache-Control` 与 `ETag`，Java API 不经手视频字节流
