# MangaWeaver 接入协议 v1（schema_version = 1）

> 状态：随 M3 实现（2026-11-08）。本文档是客户端实现的依据；与 DESIGN.md 第 17 节对应。
> 基础路径 `/api/v1`。认证方式为 Bearer Token；浏览器 Session 端点不在本文档范围。

## 1. 认证

1. 用户在 MangaShelf「个人设置」创建接入 Token（选择 scope，默认 90 天有效）。
   **原始 Token 仅在创建响应中展示一次**，格式 `ms_<43 字符 base64url>`。
2. 客户端在本地配置平台地址与 Token，所有请求携带：

```
Authorization: Bearer ms_xxxxxxxx
```

3. 无效 / 已撤销 / 过期 Token → `401 INVALID_TOKEN`。撤销后不能再签发媒体链接；
   已签发地址在有效期内仍可访问（短有效期边界）。
4. Token 请求不携带 Cookie，不受 CSRF 限制；禁止把 Token 用于浏览器内跨站调用。

### scope 表（DESIGN 17.1）

| scope | 能力 |
| --- | --- |
| `catalog:read` | 读取有权访问的目录（GET /works、/works/{id}、/chapters/{id}） |
| `materials:read` | 导出漫画页面（POST /integrations/mangaweaver/exports） |
| `search:read` | 检索有权访问的内容（语义检索 M4 前无对应端点） |
| `animations:write` | 管理自己的动画草稿与上传（上传、verify、publications） |
| `animations:publish` | 显式发布自己的成片（POST /animation-revisions/{id}/publish） |

scope 不足 → `403 TOKEN_SCOPE_INSUFFICIENT`。Token 能力与用户自身权限取交集。

## 2. 素材导出（materials:read）

### `POST /api/v1/integrations/mangaweaver/exports`

```json
{
  "chapter_revision_id": "cr_example",
  "page_ids": ["page_e1", "page_e2"]
}
```

`pageIds` 可省略（导出全部页面）。响应：

```json
{
  "schema_version": 1,
  "platform_id": "mangashelf-instance-local",
  "work_id": "work_example",
  "chapter_id": "chapter_example",
  "chapter_revision_id": "cr_example",
  "reading_direction": "rtl",
  "pages": [
    {
      "page_id": "page_e1",
      "ordinal": 1,
      "asset_id": "asset_example",
      "sha256": "<64 位十六进制，取已核验值>",
      "download_url": "<短期签名地址>",
      "expires_at": "2026-11-08T06:30:00Z"
    }
  ]
}
```

- 客户端下载后 MUST 计算并核对 SHA-256；地址过期通过重新调用本接口获取（来源 ID 不变）。
- `platform_id` 由平台配置稳定生成，客户端以它认定素材来源（不用可变域名）。

## 3. 成片发布回传（animations:write / animations:publish）

流程（DESIGN 17.3）：创建草稿 → 上传媒体 → 验证 → 查询状态 → 用户确认发布 → 回执。

### 3.1 `POST /api/v1/integrations/mangaweaver/publications`

```json
{
  "schema_version": 1,
  "title": "道歉与沉默",
  "description": "选填",
  "requestedVisibility": "PUBLIC",
  "animationId": "anim_example",
  "originRunRef": "mangaweaver-run-20261108-001",
  "sources": [
    { "chapterRevisionId": "cr_example", "pageStart": 2, "pageEnd": 4, "ordinal": 1 }
  ]
}
```

- `originRunRef`：客户端发布请求键，幂等边界为（用户、mangaweaver、该键）。
  **同键重复调用返回同一草稿**；重新制作用新键。
- `animationId` 可选：携带上次回执中的动画 ID → 在该动画下创建新修订版；
  缺省 → 创建新动画。
- `requestedVisibility` 只记录意图，创建草稿不会对读者公开。

响应：

```json
{
  "schema_version": 1,
  "animationId": "anim_example",
  "revisionId": "arev_example",
  "revisionStatus": "DRAFT",
  "watchUrl": null
}
```

### 3.2 上传媒体（复用通用上传 API，animations:write）

1. `POST /api/v1/uploads`，purpose 分别为 `ANIMATION_VIDEO`（必选）、
   `ANIMATION_POSTER`、`ANIMATION_SUBTITLE`（可选，字幕支持 VTT/SRT），
   请求携带 `IdempotencyKey`；响应含预签名 PUT 地址。
2. `PUT <预签名地址>`，携带返回的 `Content-Type`。
3. `POST /api/v1/uploads/{id}/complete` → 轮询 `GET /api/v1/uploads/{id}`
   直到 `assetState = "VERIFIED"`，取 `assetId`。

### 3.3 验证（animations:write）

```
POST /api/v1/animation-revisions/{revisionId}/verify
{ "video_asset_id": "...", "poster_asset_id": "...", "subtitle_asset_id": "..." }
```

`sources` 可省略——publications 已登记来源清单。
服务端以 ffprobe 实测媒体（容器 MP4；视频 h264/vp9/av1；音频 aac/mp3/opus 或无声；
无声视频如实标注）。失败 → 版本 FAILED（`GET /api/v1/animation-revisions/{id}`
可见 error_code），草稿与资产保留可重试。

### 3.4 状态查询与发布（animations:publish）

- 查询：`GET /api/v1/integrations/mangaweaver/publications/{originRunRef}` →
  `revisionStatus` 与发布后的 `watchUrl`。
- 用户确认后：`POST /api/v1/animation-revisions/{revisionId}/publish` →
  版本 PUBLISHED，容器状态随发布流转。
- 再次查询同键：`revisionStatus = "PUBLISHED"`、`watchUrl = "/watch/<animationId>"`。

## 4. 错误结构

与会话端点一致（DESIGN 16.2）：`{code, message, request_id, details}`。
常用：`400 PAGE_RANGE_INVALID`（页码超出来源版本页数）、`409 UPLOAD_NOT_VERIFIED`、
`409 INVALID_STATE`（状态不允许）、`403 TOKEN_SCOPE_INSUFFICIENT`、
`404 PUBLICATION_NOT_FOUND`（发布键未注册）。

## 5. 边界

- 本地漫画发布：平台无法凭文件名确认来源；MUST 先导入原作并引用其章节版本（DESIGN 17.4）。
- 初期无平台回调：客户端主动查询状态。
- 地址有效期：签名地址默认 600 秒，过期重新获取即可，不改来源 ID。
