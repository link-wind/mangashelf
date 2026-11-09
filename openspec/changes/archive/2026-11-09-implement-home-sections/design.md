# 设计：首页内容对齐（M14）

## D1. 数据来源

| 区块 | 接口 | 说明 |
| --- | --- | --- |
| Banner / 时刻表 / 榜单 | `GET /api/v1/calendar` | 本季 114 部，含评分/星期/简介/资源备注/集数/分类 |
| 最近更新 | `GET /api/v1/catalog-items?limit=12` | 按 COALESCE(resource_updated_at, synced_at) 倒序 |
| 继续观看 | `GET /api/v1/me/continue-watching` | 服务端进度 |
| 我的作品 | `GET /api/v1/works?limit=12` | 书库 |

首页并行请求四个接口（Promise.all），任一失败不阻塞其余区块。

## D2. Banner

评分前 4 轮播；背景 = 封面 `filter: blur(5px) brightness(.58)` + 两段渐变遮罩，
保证竖版封面素材下画面可辨识且文字对比度足够。缩略图切换与自动轮播并存。

「追番」= 必要时先物化，再 `PUT /me/library/{workId}`。

## D3. 时刻表预览

默认选中今天（周日映射为 7），星期 pill 切换；条目点击进入目录详情。

## D4. 深链

`/anime?category=29|30|31|32|33` 由 AnimeIndex 读取 URL 初始化筛选。
