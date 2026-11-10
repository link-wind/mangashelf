# 设计：跨源同作合并（M26）

## 聚簇算法

```
raw = 每个分区的 (item, score=1/(60+rank), sourceId, sourceName)
按 score 降序依次加入聚簇：
  key = normalizeTitle(title)      # NFKC + 小写 + 去空白/标点/符号，保留括号文字
  与已有聚簇代表 key 精确相等 → 合并
  否则 similarity(代表key, key) ≥ 0.9 → 合并
  否则新建聚簇
```

- 相似度复用 `MangaMatcher.similarity`（字符二元组 Jaccard）。
- 阈值 0.9 只合并近似同一标题的写法差异；实测「航海王」vs「航海王番外篇」
  相似度约 0.4，不会误合并。
- 聚簇得分 = 成员 RRF 分数之和（跨榜/跨源累加）。

## 条目选择

- 展示条目：优先 `readable=true`（中文源站内），其次分数最高；
  封面/标题取展示条目。
- `sources`：成员去重后的源名（按分数顺序）；`sourceCount` 为其数量。
- 阅读入口：展示条目自带 readSource/readId/externalUrl；若非可读且存在
  站外链接，点击打开原站。

## 接口

DiscoveryItem = RankItem + `sourceCount` + `sources`；每行 items 统一为
DiscoveryItem（单源行 sourceCount=1）。前端仅在 sourceCount>1 时显示「N 源」。

## 前端

- 卡片右上角「N 源」徽标（title 提示源名单）。
- hero chips 增加「N 源可读」。

## 决策记录

- 2026-11-10：DiscoveryItem 首版嵌套（item 内嵌 RankItem）导致前端 hero
  `.slice` 崩溃整页白屏；React 19 渲染错误不进 console，靠 createRoot
  onUncaughtError 写 document.title 定位。改为服务端摊平输出（RankItem 字段
  + sourceCount/sources），前端形状不变；main.tsx 保留错误钩子。
- 2026-11-10：相似度阈值取 0.9（仅写法差异合并）；「航海王」vs「航海王番外篇」
  实测相似度约 0.4 不误合并；完全不同译名不合并（别名词典不做）。
