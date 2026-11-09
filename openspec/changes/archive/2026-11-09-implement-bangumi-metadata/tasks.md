# 任务：M9 Bangumi 元数据层与本季新番

## 清理与迁移

- [x] 增量同步按 categories 过滤（只收动漫）+ 清理非动漫条目
- [x] V10：bangumi_subjects + 目录关联列 + bangumi.sync kind

## 后端

- [x] BangumiService：日历同步 / 本地标题匹配 / 封面代理读取（可选代理）
- [x] BangumiSyncHandler + POST/GET /admin/bangumi-sync
- [x] GET /calendar（去重 + 覆盖标记）
- [x] GET /bangumi-covers/{subjectId}（服务端代理，不接受任意 URL）

## 前端

- [x] SeasonSection（封面/评分/星期/可看徽标/一键播放）
- [x] 番剧索引顶部 + 首页横滚 + 设置页同步按钮

## 验证

- [x] 日历同步（114 部）+ 匹配（29 部覆盖）
- [x] 封面代理 200 + 页面封面显示
- [x] 本季新番一键播放 → 作品页
- [x] docs/validation/m9.md + 提交归档
