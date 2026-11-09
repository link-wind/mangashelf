# 设计：M2 动画观看

## Context

DESIGN 8.2（表）、9（状态机）、13（动画上传/来源/发布）、14.2（播放）、16.1（端点）
为本阶段依据。M1 已建立：身份与所有权判定、任务模型（租约/恢复/SSE）、对象存储访问
（含 aws-chunked 修复）、SSE 前端模式。M2 复用这些机制，不新增队列。

## Goals / Non-Goals

**Goals**

- 上传视频/封面/字幕 → ffprobe 实测校验 → 绑定来源（章节版本 + 页码范围）→ 发布 → 播放。
- 漫画页级关联动画（一段动画可覆盖多页，多页指向同一记录）与观看页回到原作。
- 观看进度：版本号防覆盖 + 旧进度绑定旧版本（切版提示重头播）。
- 发布五道校验（DESIGN 13.4），公开动画要求全部来源满足公开条件。

**Non-Goals**

- 不推断 video_start_ms → panel_id 秒级映射（DESIGN 13.2）；页码范围定位即止。
- 不做 HLS / 多码率 / 转码（DESIGN 14.2）；不支持的编码明确报错。
- 不做 origin_client / origin_run_ref（M3 接入协议时随迁移补充）。
- 不做动画编辑器、多版本自动比选 UI（多版本列表在观看页展示，DESIGN 4.1）。

## Decisions

### D1：媒体校验走固定 Python 任务（DESIGN 12.2）

`python -m processing.run --task task.json`，任务类型 `media.verify`：
ffprobe（-print_format json）读取容器/编码/时长/分辨率/音轨；ffmpeg 截取封面
（未提供封面时，1s 处一帧）；字幕检查（WebVTT 直通、SRT 固定转换为 VTT，不二次翻译对白）。
基线规则写入任务常量：容器 MP4；视频编码 h264/vp9/av1；音频 aac/mp3/opus 或无声；
无声视频不伪造合格标签（result 布尔字段如实上报，DESIGN 13.3）。
不支持的组合任务失败并给出可定位错误码。

### D2：视频资产复用上传原件，派生物进版本专属 Key

视频资产即 ANIMATION_VIDEO 上传的 ORIGINAL（核验过的字节，不复制）；
封面/字幕为校验产物，Key 按设计 18.1：`animations/<revision-id>/poster.jpg`、
`animations/<revision-id>/subtitles.vtt`。animation_revisions.video_asset_id 指向上传资产。

### D3：来源合法性在 verify 与 publish 两次校验（DESIGN 13.2/13.4）

verify 时校验来源版本存在且页码范围 ≤ page_count（事务内查询，普通 CHECK 不跨表，DESIGN 8.3）；
publish 事务内重新校验：操作者拥有动画、视频资产 VERIFIED 且归属正确、
来源清单完整且 ordinal 唯一、页码范围仍有效、**公开动画的全部来源满足公开条件**
（来源章节版本 PUBLISHED 且其作品 PUBLIC+PUBLISHED）。发布锁定动画行，
切换 current_revision_id（组合外键，与 M1 同模式）。

### D4：播放授权与观看进度（DESIGN 14.2/14.3）

`GET /animations/{id}/playback`：所有者/管理员或（PUBLIC + PUBLISHED + 当前版本 PUBLISHED）；
返回短期签名视频/封面/字幕地址（数据库不存签名）。Range 已在 M0 实测（206）。
watch_progress 主键 (user_id, animation_id)，行内记录绑定版本；
当前版本 ≠ 绑定版本时 playback 返回 revisionMatches=false，前端提示从头播放
（不把旧秒数套到新时长），进度行保留供用户回到旧版本上下文。
写入与阅读进度同款 ON CONFLICT 条件更新，409 返回当前快照。

### D5：页级关联查询即查即算（DESIGN 8.4）

`GET /chapter-revisions/{id}/animations?page=N`：按 chapter_revision_id 过滤来源、
判断 page_start ≤ N ≤ page_end、联动画版本/容器可见性。来源表在此阶段规模小，
先按 chapter_revision_id 索引缩小范围，不预建复杂区间索引（DESIGN 8.4）。

### D6：前端复用 M1 代理与播放器原生能力

观看页 `<video controls>` + 签名 src（/media 同源代理）+ `<track>` 字幕；
`timeupdate` 每 ~5s 提交进度，暂停/离开前提交；`revisionMatches=false` 时提示并从 0 播放。
阅读器在当前页存在已发布动画时显示"观看这段动画"按钮，不自动播放（DESIGN 4.1）；
多个改编版本展示列表，不认定最新为正式版。

## Risks / Trade-offs

- ffmpeg/ffprobe 输出跨版本差异：任务内显式解析 format/streams 关键字段，解析失败即任务失败。
- 大视频上传占用上传预算：默认 500MB 足够演示；预算参数化（.env.example 已有）。
- 播放器对签名过期的处理：刷新页面重新取 playback 即可（地址短有效期是安全边界）。

## Migration Plan

V2 → V3 前向迁移；不修改既有迁移。

## Open Questions

- ffmpeg 8.1 的 ffprobe JSON 字段兼容性（实现时以实际输出为准）。
- SeaweedFS 对大对象 Range 的起始偏移行为（M0 已测 206，播放拖动待浏览器实测）。
