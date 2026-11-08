# 提案：M2 动画观看

## Why

M1 交付了"能读"，但"能看"与"能发布"还没有（DESIGN 2.1 目标 2/3）。M2 交付最小动画观看闭环：
上传视频 → ffprobe 媒体验证 → 绑定来源（章节版本页面范围）→ 发布 → 读者从漫画页进入视频、
再返回原作；换版不混淆。这是 M3（MangaWeaver 接入闭环）的直接前提——M3 的发布协议
复用本阶段建立的版本/来源/校验机制。

## What Changes

- 迁移 V3：animations / animation_revisions / animation_sources / watch_progress；
  upload purpose 与资产 kind 扩展（VIDEO / POSTER / SUBTITLE）；
  jobs 新增 animation.verify 任务种类（目标为动画版本）。
- 新增 `animation/`：动画容器与版本的创建、可见性与状态机（DESIGN 9）、
  verify 编排（绑定资产 + 来源清单 → animation.verify 任务）、发布事务（DESIGN 13.4
  五道校验）、播放授权与短期媒体地址（DESIGN 14.2）、页级关联查询
  （GET /chapter-revisions/{id}/animations?page=N，DESIGN 16.1）。
- `processing/`：新增 `media.verify` 任务——ffprobe 校验容器/编码/时长/分辨率/音轨、
  ffmpeg 截取封面（未提供封面时）、字幕格式检查与 SRT→WebVTT 固定转换（DESIGN 13.3）。
- `library/`：观看进度（watch_progress），旧进度绑定旧版本，切版提示重新开始
  （DESIGN 13.1/14.3）。
- `web/`：观看页播放器（续播、字幕、来源信息、回到原作）、阅读器页级"观看这段动画"入口
  （DESIGN 4.1）、我的素材扩展动画管理（上传/验证/发布/下架）。

## Capabilities

### New

- `animation-viewing`：动画版本、来源映射、媒体校验、播放授权与观看进度。
- `animation-publishing`：来源合法性校验与发布事务（公开动画要求来源可公开）。

## Impact

- 影响：数据库 V3、animation 模块、processing 新任务、library 增量、前端三处增量。
- 不影响：MangaWeaver 仓库（接入协议属 M3）；现有 content.process 队列拓扑
  （animation.verify 走同一队列，仍只有两类队列，DESIGN 11.1）。
- 风险：ffmpeg 输出解析的格式差异以实测为准；视频大文件上传的预算与超时需实测调整；
  Range 播放在 SeaweedFS 上已具备（M0 实测 206），播放器侧行为需浏览器实测。

## 验收（DESIGN 22 M2 退出条件）

从漫画页进入视频，再返回原作；换版不混淆。另按 21.1 覆盖：媒体未完成时发布被拒、
越权播放被拒、进度冲突明确返回、下架后新请求拒绝。
