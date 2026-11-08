# 任务：M7 外部动画站内播放

## 迁移

- [x] V8：jobs 扩展 external.resolve + external_episode_id；external_episodes 解析结果列

## Python

- [x] TaskSpec 增加 params 协议字段（向后兼容）
- [x] tasks/external_resolve.py：Playwright 嗅探 m3u8/mp4
- [x] run.py 注册表登记 external.resolve

## Java

- [x] Job/JobRepository/JobCreationService：externalEpisodeId 目标列贯通
- [x] ExternalResolveHandler：读集→下发嗅探→结果落 resolved_url
- [x] ExternalService/Controller：POST resolve（30s 幂等）+ GET resolve 状态
- [x] ExternalRepository：findEpisode / findLatestResolveJob / markResolved

## 前端

- [x] hls.js 引入与外部集站内播放器
- [x] 解析轮询 → 起播 → 过期自动重解析续播
- [x] 失败显式错误 + 跳转原站兜底

## 验证

- [x] Playwright 本地安装（npmmirror 镜像回退）
- [x] smoke：mock 剧集页嗅探全链路（建源→绑定→resolve→拿到直链）
- [x] docs/validation/m7.md + README 里程碑表
