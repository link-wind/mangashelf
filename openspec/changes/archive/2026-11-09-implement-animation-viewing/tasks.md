# 任务：M2 动画观看

## 迁移

- [x] 迁移 V3：animations / animation_revisions / animation_sources / watch_progress
- [x] animations.current_revision_id 组合外键（UNIQUE(animation_id, id) + 复合 FK）
- [x] upload_sessions purpose 扩展（ANIMATION_VIDEO / ANIMATION_POSTER / ANIMATION_SUBTITLE）
- [x] assets kind 扩展（VIDEO / POSTER / SUBTITLE）
- [x] jobs 新增 animation_revision_id 与 animation.verify 种类，目标 CHECK 更新
- [x] animation_sources：ordinal 唯一 + page_start ≤ page_end CHECK（跨表范围在事务校验）

## animation 模块

- [x] 动画/版本/来源仓储与服务（状态机 DRAFT→VERIFYING→READY→PUBLISHED，DESIGN 9）
- [x] 端点：GET/POST /animations、GET /animations/{id}、POST /animations/{id}/revisions、
      POST /animations/{id}/withdraw
- [x] POST /animation-revisions/{id}/verify：绑定资产（VERIFIED + 归属 + kind 匹配）
      + 来源清单（页码范围事务内校验）→ animation.verify 任务
- [x] POST /animation-revisions/{id}/publish：五道校验（13.4）+ 事务锁 + 切换当前版本
- [x] GET /animations/{id}/playback：可见性 + 当前版本 PUBLISHED → 签名视频/封面/字幕地址
      + 观看进度快照与 revisionMatches
- [x] GET /chapter-revisions/{id}/animations?page=N：页级关联（可见动画列表）
- [x] 素材归属校验：绑定资产属同一用户（13.4-2）

## 媒体验证任务

- [x] Python media.verify：ffprobe JSON 解析（容器/编码/时长/分辨率/音轨）、
      无声视频如实上报、ffmpeg 封面提取、WebVTT 直通 / SRT→VTT 固定转换
- [x] AnimationVerifyHandler：VERIFYING→READY，产物校验上传（animations/<revision-id>/…），
      duration_ms/尺寸/编码入库；失败 → FAILED（可定位错误码）
- [x] 下载产物经解码器/解析器打开的验收步骤（防 aws-chunked 污染回归）

## 观看进度

- [x] PUT/GET /me/watch-progress/{animation_id}：expected_version 409 冲突 + 允许回看
- [x] playback 返回 revisionMatches；切版不把旧秒数套到新时长

## 前端 web/

- [x] 观看页 /watch/:animationId：video 播放器、续播、字幕 track、来源信息、回到原作（携带页码续读）
- [x] 阅读器页级"观看这段动画"入口（不自动播放，DESIGN 4.1）；多版本列表展示
- [x] 我的素材：动画管理（创建/上传视频/验证/发布/下架）
- [x] 书库/详情的动画标识

## 验证与收尾

- [x] 生成测试 MP4（ffmpeg h264+aac），smoke-m2.sh：上传→验证→发布→播放（Range 206）→
      页级关联→进度冲突→换版 revisionMatches=false→越权与未完成发布拒绝
- [x] 无声视频与错误格式（如把文本伪装成 mp4）→ 任务 FAILED 可定位
- [x] 浏览器播放验收：播放器出画、字幕可挂、回到原作携带页码
- [x] docs/validation/m2.md 写实测结果与已知限制；README 里程碑状态更新
