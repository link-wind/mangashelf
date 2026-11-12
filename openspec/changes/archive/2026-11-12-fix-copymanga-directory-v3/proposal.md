## Why

官网 `/comicdetail/{pathWord}/chapters` 加密目录在相同请求头和代理下会随机返回
`count=0` 的合法空分组，导致详情页只能显示“暂未返回公开目录”。该接口不是稳定的
纯 HTTP 目录来源，继续重试或自动切换出口也不能保证目录完整。

拷贝漫画官方 v3 API 提供匿名详情、分组和章节分页，已在 2026-11-12 实测取得
《寶石魔女》39 话、《海贼王》402 话、《葬送的芙莉蓮》153 话。将详情与目录切换
到该接口，避免网页风控空目录和浏览器依赖。

## What Changes

- 详情与章节目录改走 `https://api.manga2026.xyz/api/v3`，匿名请求，不发送 Cookie。
- 详情读取作品元数据和分组声明；章节按 `limit=100` 分页拉取，校验 `total`、分组
  `count`、UUID、名称和全局唯一性。
- 保留现有章节 ID `{pathWord}~{uuid}`、页面路由、30 分钟有界缓存和并发合并。
- 删除网页目录参数、AES 解码和 Jsoup 断言；目录失败明确报错，不回退浏览器。
- 搜索与章节图片仍走已有浏览器路径，本次不扩大图片登录或付费内容范围。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `manga-sources`：拷贝漫画详情与完整目录改为官方 v3 匿名 API。

## Impact

仅影响拷贝漫画 Java 源适配、协议测试、验证文档和 OpenSpec；API 结构、章节 ID、
前端组件与数据库不变。
