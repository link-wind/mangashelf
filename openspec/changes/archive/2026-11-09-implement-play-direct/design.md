# 设计：直达播放（M19）

`playTarget(user, workId)`：列出作品外部集；取该用户最近进度（ordinal、line），
在同集同线路中找候选，找不到退化为同集任意线路，再退化首集。

前端 WorkDetail 在数据加载后判断：`chapters.length === 0` 时请求 play-target，
存在则 `navigate(..., { replace: true })`；失败或无外部集则停留详情页（显式不跳）。
