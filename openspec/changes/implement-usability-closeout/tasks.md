# 任务：M10/M11/M12

## 迁移

- [x] V11：多线路列 + external_watch_progress + external_sources.cookie

## M10 可用性

- [x] 搜索页覆盖番剧目录
- [x] ScheduledSyncService（目录 30 分钟 / Bangumi 6 小时，worker only）
- [x] 进度服务端化 + 观看历史页 + 首页继续观看
- [x] resolve 限流（20 次/分钟/用户）

## M11 内容厚度

- [x] 多线路物化与观看页线路切换 + 正片/PV 分组
- [x] 目录条目详情页
- [x] 作品详情页封面 + Bangumi 元数据
- [x] 规则源 Cookie（仅服务端下发）

## M12 体验与纪律

- [x] 暗色主题
- [x] OpenSpec M0–M5 全部归档
- [x] 标题匹配单测（4 例）

## 验证

- [x] 接口验证（多线路物化 2×12、进度读写、continue-watching、external-meta）
- [x] 页面截图（目录详情、作品详情、多线路观看页）
- [x] 单测 4/4 通过
- [x] docs/validation/m10.md
