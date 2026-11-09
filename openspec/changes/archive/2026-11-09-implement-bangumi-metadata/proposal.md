# 提案：Bangumi 元数据层与本季新番（M9）

## 背景

M8 建成资源目录层（7506 部动漫），但目录只有资源站数据：标题、资源站海报、
更新备注。参考对象 gugu.fan 的"内容厚度"——评分、官方封面、放送星期、本季日历
——来自 Bangumi（api.bgm.tv，开放接口）。

同时修正 M8 的范围问题：增量同步会把窗口内的非动漫分类（短剧/综艺等）一并入库，
目录应只保留动漫分类。

## 变更内容

1. **目录只收动漫**：增量同步按 params.categories 过滤 type_id；清理已入库的
   非动漫条目（265 条）。
2. **Bangumi 元数据层**（V10）：`bangumi_subjects` 缓存表（subject_id/名称/中文名/
   封面/评分/放送日/星期/简介/本季标记），`external_catalog_items.bangumi_subject_id`
   关联列。
3. **bangumi.sync 任务**：拉取 `api.bgm.tv/calendar`（本季新番，实测 114 部），
   本地标题归一化匹配资源目录（实测 29 部覆盖）。
4. **封面代理**：`GET /api/v1/bangumi-covers/{subjectId}`——服务端按 subjectId
   取图（不接受任意 URL，避免 SSRF），解决 lain.bgm.tv 在受限网络不可直连。
5. **前端**：番剧索引顶部「本季新番」分区（官方封面 + 评分 + 放送星期 +
   「可看」徽标 + 一键播放/加入书库）；首页本季新番横滚；设置页同步触发。

## 不做的事

- 不做 Bangumi 账号绑定/收藏同步（需要 OAuth 与用户授权，超出当前范围）。
- 不做全库 subject 富化：只缓存本季日历（`in_calendar`），其余按需扩展。
- 不做弹幕（弹弹play）与时刻表周视图（后续迭代）。

## 风险

- Bangumi API 限速（1 req/s 规范）：日历单次请求 + 本地匹配，不密集调用。
- 标题匹配为启发式（等值 → 前缀），覆盖率有限（29/114）；接受，后续可加
  别名/搜索 API 提升。
- api.bgm.tv 与 lain.bgm.tv 在部分网络需代理：提供 `MANGASHELF_BANGUMI_PROXY`
  配置项与封面服务端代理。
