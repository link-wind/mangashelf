# 提案：M3 项目闭环 —— MangaWeaver 接入协议（平台侧）

## Why

M2 完成了"能读、能看、能发布"（DESIGN 2.1 目标 1–3），但四能力只有站内用户可用。
M3 是首个完整产品里程碑（DESIGN 22）：通过开放 API 接入 MangaWeaver，形成
素材获取 → 动画创作 → 成片发布回传 → 网站观看的闭环。本变更实现平台侧协议
（DESIGN 17）：scope Token、素材导出清单、发布草稿与回执查询。

MangaWeaver 侧的客户端接入在其自身仓库以独立 OpenSpec 变更实施（DESIGN 24.1-7）。

## What Changes

- 迁移 V4：api_tokens 表（DESIGN 8.2）；works 增加 reading_direction（17.2 清单字段）；
  animation_revisions 增加 origin_client / origin_run_ref（17.3 回执与幂等）。
- 新增 `identity/` 增量：Token 签发（原始值仅创建时展示，库中只存 SHA-256）与撤销、
  Bearer 过滤器（与 Session 共存、不落会话）、scope 校验助手、携带 Bearer 的请求豁免 CSRF。
- 新增 `integration/` 模块：
  - `POST /integrations/mangaweaver/exports`（materials:read）：版本化导出清单，
    页面含 asset_id、verified sha256 与短期下载地址，platform_id 来自稳定配置（17.2）。
  - `POST /integrations/mangaweaver/publications`（animations:write）：创建或按键返回
    同一发布草稿（可选 animationId 定向换版），登记 origin_run_ref 与来源。
  - `GET /integrations/mangaweaver/publications/{originRunRef}`：回执查询——版本状态、
    任务状态与发布后的 watch_url（17.3 步骤 6）。
- 既有端点接 scope（Bearer 场景）：上传/verify → animations:write；publish → animations:publish；
  目录读 → catalog:read；浏览器 Session 用户不受影响。
- verify 请求的来源改为可选：publications 已登记来源时无需重复提交。
- 前端个人设置页：Token 创建（一次性展示）/列表/撤销（DESIGN 4）。
- `docs/api/mangaweaver-integration.md`：协议 v1 完整契约（客户端实现依据）。

## Capabilities

### New

- `platform-integration`：scope Token 与素材导出/成片发布回传协议。

### Modified

- `user-identity`：个人接入 Token 的创建、查看摘要与撤销。
- `animation-publishing`：发布回执（origin_run_ref）与协议侧发布。

## Impact

- 影响：迁移 V4、identity/animation/catalog 小幅扩展、新 integration 模块、
  前端设置页、协议文档；MangaWeaver 仓库新增独立变更与最小客户端（另行提交）。
- 不影响：M1/M2 站内行为；队列拓扑不变。
- 风险：Bearer 与 Session 并存的 CSRF 边界以实测为准；导出清单的摘要以已核验值为准；
  客户端重试幂等在并发下以查询去重（本地单客户端场景，记录边界）。

## 验收（DESIGN 22 M3 退出条件）

一次真实导入（MangaWeaver 客户端经协议下载页面并核验摘要）、创作成片发布回传
（客户端上传成片 → 验证 → 发布）与网站观看完成。
