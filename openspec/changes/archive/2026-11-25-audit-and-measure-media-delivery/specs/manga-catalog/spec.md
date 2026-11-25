## ADDED Requirements

### Requirement: 媒体交付分层缓存与无 N+1 页面授权

漫画页面阅读图与缩略图 MUST 由对象存储通过短期预签名地址直接提供，Java API MUST NOT 转发图片字节流。
对象存储产物上传与预签名下发 MUST 按资产类型携带明确的 `Cache-Control` 响应头，并在实时权限校验通过后的短期窗口内复用有效签名 URL 以支持浏览器缓存与 `ETag`/`304` 条件验证。
章节页面清单接口 MUST 批量查询资产对象键，避免按页逐条查询数据库（N+1）；阅读器 MUST 复用未过期窗口并仅做有限下一页预加载，不一次预取整本原图。

#### Scenario: 翻页回看命中浏览器缓存与 304

- **WHEN** 读者在签名复用窗口内先后请求同一章节页面清单并访问同一页阅读图
- **THEN** 两次请求返回一致的预签名 URL，对象存储响应携带 `Cache-Control: private, max-age=86400, immutable` 与 `ETag`，携带 `If-None-Match` 请求返回 `304 Not Modified` 且响应体为 0 字节

#### Scenario: 章节下架后立即停止签发

- **WHEN** 作品被所有者下架或改为 PRIVATE 后，非所有者再次请求页面清单或资产访问接口
- **THEN** API 实时权限校验拒绝访问并返回 404，不签发或复用任何预签名地址

#### Scenario: 封面代理条件请求返回 304

- **WHEN** 客户端携带上次响应返回的 `ETag` 请求 `/api/v1/bangumi-covers/{subjectId}`
- **THEN** 服务端返回 `304 Not Modified` 与 0 字节响应体，不重复输出图片二进制
