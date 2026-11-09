# 设计：漫画多数据源（M17）

## D1. 能力模型

```
enum Capability { RANK, SEARCH, DETAIL, READ }
interface MangaSource { id, name, capabilities, rank/search/detail/pages }
```

控制器在调用前校验能力，缺失时 409 + 原因；前端按 capabilities 决定交互。
新增源 = 实现接口 + 注册 Bean，不改控制器。

## D2. 源现状

| 源 | 能力 | 实测 |
| --- | --- | --- |
| bilibili | RANK | HomeHot 匿名 200；ComicDetail/Search 需登录（code=99） |
| mangadex | RANK/SEARCH/DETAIL/READ | followedCount 排序、feed、at-home 页图 |

## D3. 韧性

- 哔哩封面追加 `@480w.jpg` 降采样（3.2MB → 71KB）。
- MangaDex：10 分钟 TTL 缓存；上游 IOException 时重试一次，仍失败则回退陈旧缓存；
  无缓存则 502 显式报错。
- 图片代理白名单：MangaDex 域名 + `*.hdslb.com`；磁盘缓存。

## D4. 前端

`/manga` 顶部「热门漫画」：源切换 pill、排名角标、更新进度；`readable=false` 的条目
以新窗口打开官方详情并显示「站外」徽标；`readable=true` 进入站内详情/阅读。
