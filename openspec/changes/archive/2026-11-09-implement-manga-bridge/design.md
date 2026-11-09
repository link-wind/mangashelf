# 设计：跨源桥接与进度（M18）

## D1. 匹配

MangaDex `title` 检索跨语言（实测：航海王→One Piece、碧蓝之海→Grand Blue）。
匹配三级：
1. 任一 title/altTitle 归一化完全相等；
2. 二元组 Jaccard ≥ 0.72；
3. 检索有结果且查询长度 ≥ 2 → 取首位（相关性排序）。

只在榜单前 12 条做匹配（首次约 7s），结果按标题缓存 1h；失败不缓存。

## D2. 进度

表 `external_manga_progress` 主键 (user_id, source, manga_id)，记录 chapter/page/total
与展示所需的 title/cover。阅读器进入时取该作进度，若章节一致且 page>1 则滚到对应页；
滚动按 3s 节流上报，离开页面强制上报一次。

## D3. 风险

第 3 级为启发式匹配，可能误配同名作品；界面把它标为「站内」，进入后可见实际标题，
可自行返回原站。后续可用更多元数据（作者、年份）提升置信。
