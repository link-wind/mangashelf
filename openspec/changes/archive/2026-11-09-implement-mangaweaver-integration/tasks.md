# 任务：M3 MangaWeaver 接入协议（平台侧）

## 迁移与配置

- [x] 迁移 V4：api_tokens（token_hash UNIQUE / prefix / scopes / expires_at / revoked_at）
- [x] V4：works.reading_direction（默认 rtl，CHECK rtl|ltr）；animation_revisions 增
      origin_client / origin_run_ref + 查询索引
- [x] MangaShelfProperties.platformId + .env.example 占位 + integration 端点启动检查（缺失 503）

## Token 体系

- [x] TokenService：生成（ms_ 前缀 + 32 字节熵）、SHA-256 入库、prefix 展示、撤销
- [x] POST/GET/DELETE /me/api-tokens（原始值仅创建响应返回一次；列表只含摘要）
- [x] BearerTokenFilter：认证注入 TokenAuthentication（SCOPE_* authorities）、不落会话、无效 401
- [x] CSRF 对携带 Bearer 头的请求豁免（自定义 RequestMatcher）；浏览器路径不变
- [x] TokenScopes.require 助手（非 Token 认证直接通过；Token 缺 scope → 403）

## integration 模块

- [x] POST /integrations/mangaweaver/exports（materials:read + 可读版本）：
      schema_version/platform_id/reading_direction/pages[{page_id, ordinal, asset_id,
      sha256(verified), download_url, expires_at}]，支持 pageIds 过滤
- [x] POST /integrations/mangaweaver/publications（animations:write）：幂等创建草稿
      （按键返回 / 定向 animationId 换版 / 新建动画），登记来源与 origin_run_ref
- [x] GET /integrations/mangaweaver/publications/{originRunRef}：状态回执（版本状态、
      任务状态、发布后 watch_url）
- [x] verify 来源可选（沿用已登记来源）；publish/upload 经 Token 的 scope 校验
- [x] 目录读端点（GET /works、/works/{id}、/chapters/{id}）Token + catalog:read

## 前端与文档

- [x] 个人设置页 /settings：Token 创建（一次性展示）/列表/撤销
- [x] docs/api/mangaweaver-integration.md：协议 v1（端点、请求/响应示例、scope 表、
      错误码、幂等语义、平台来源与下载地址有效期）

## 验证与收尾

- [x] smoke-m3.sh（模拟客户端，全 Bearer）：Token 签发 → 目录读 → 导出清单 →
      下载页面并核验 SHA-256 → publications 草稿（幂等重放返回同稿）→ 上传成片 →
      verify → 状态查询 → publish → watch_url → 网站播放 → 撤销 Token 后 401
- [x] scope 不足场景：无 animations:publish 的 Token 发布 → 403
- [x] MangaWeaver 仓库：独立 OpenSpec 变更 + 最小真实客户端（export / publish 子命令）
- [x] 真实端到端：MangaWeaver CLI 完成一次导入（摘要核验）与一次成片发布回传
- [x] docs/validation/m3.md 写实测结果与已知限制；README 里程碑状态更新
