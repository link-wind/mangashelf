# 设计：M3 MangaWeaver 接入协议（平台侧）

## Context

DESIGN 17 定义了接入契约（身份范围、素材导出、成片发布、本地漫画发布限制），
16.1 定义 Token 端点，16.2 要求幂等键。M1/M2 已提供：身份体系、上传协议、
任务模型、动画版本与来源、发布事务。本变更把这套能力以 scope Token 开放给本地客户端。

## Goals / Non-Goals

**Goals**

- Token 生命周期：创建（一次性展示）/列表摘要/撤销；Bearer 认证与 scope 校验。
- 素材导出：版本化清单 + 短期下载地址，客户端自行下载并核验 SHA-256。
- 成片发布回传：幂等创建草稿 → 复用通用上传 → verify → 状态查询 → 发布回执（watch_url）。
- 协议文档 docs/api/mangaweaver-integration.md（schema_version 1）作为客户端实现依据。

**Non-Goals**

- 不实现 MangaWeaver 桌面 UI 集成（客户端仓库另行变更，本阶段交付最小 CLI 客户端）。
- 不做平台回调本地客户端（DESIGN 17.3：客户端主动查询）。
- 不开放 search:read 的语义检索（M4）；跨用户改编授权仍不支持（DESIGN 3.2）。
- 不做 Token 细粒度过期策略 UI（过期时间创建时给定，默认 90 天）。

## Decisions

### D1：Token 形态与存储（DESIGN 16.1 / 17.1）

原始 Token `ms_<43 字符 base64url>`（32 字节熵），创建响应一次性返回；
库中仅存 SHA-256 十六进制（token_hash UNIQUE）与前 12 字符 prefix 供列表辨认。
scope 以逗号串存储，首版五种（DESIGN 17.1）；过期时间创建时给定（默认 90 天）；
撤销置 revoked_at。认证时校验未撤销未过期，Token 权限与用户权限取交集
（DISABLED 用户一律拒绝）。

### D2：Bearer 与 Session 共存，CSRF 按请求形态豁免

BearerTokenFilter 在 Security 过滤链前部运行：命中 `Authorization: Bearer ms_…` 时
认证并注入 TokenAuthentication（authorities 含 SCOPE_* 与 ROLE_USER），不写会话；
无效 Token 直接 401（与 17.1 一致，不暴露细节）。CSRF 对携带 Bearer 头的请求豁免
（自定义 RequestMatcher）——Token 客户端无 Cookie，CSRF 威胁模型不适用；
浏览器 Session 路径完全不变。

### D3：scope 在端点内显式校验（不建注解框架）

`TokenScopes.require(auth, "materials:read")` 静态助手：非 Token 认证（浏览器 Session）
直接通过，Token 认证缺 scope 抛 403 TOKEN_SCOPE_INSUFFICIENT（AGENTS：不建通用机制）。
落点：integration 两个端点、上传（animations:write）、verify（animations:write）、
publish（animations:publish）、目录读（catalog:read）、任务读（任意有效 Token）。

### D4：发布草稿幂等与定向换版（DESIGN 17.3）

publications 请求携带 originRunRef（客户端发布请求键）与可选 animationId：
- 按键查询（owner + origin_client='mangaweaver' + origin_run_ref）命中 → 原样返回草稿；
- 未命中：带 animationId 且归属正确 → 在该动画下建新修订版（"重新制作 → 新发布键 →
  修订版，不覆盖上一成片"）；未带 → 创建新动画（visibility=requested）+ 首个修订版。
- origin_run_ref 是追踪信息不授予权限；同键并发双提交的去重以查询实现，
  并发窗口记录为已知边界（本地单客户端顺序调用）。

### D5：导出清单以已核验事实填充（DESIGN 17.2）

pages[].sha256 取 assets.verified_sha256（非用户声明）；download_url 为短期签名地址；
platform_id 来自 `MANGASHELF_PLATFORM_ID` 配置（启动检查必填，响应 17.2 示例；
不用可变域名充当平台来源）；reading_direction 取 works.reading_direction（V4 新列，
默认 rtl，PATCH /works 可改）。请求可带 pageIds 过滤；缺省导出全部页面。

### D6：verify 来源可选化（协议闭环需要）

publications 已登记来源清单；客户端随后调用 verify 只需绑定资产。
VerifyRequest.sources 为空时沿用已登记来源（无来源才 400），避免客户端重复提交清单。

## Risks / Trade-offs

- Token 泄露：短过期 + 可撤销 + 日志不含 Token（DESIGN 19）；scope 最小化由用户自选。
- 同键并发幂等窗口：本地客户端顺序调用下可忽略；若未来多端并发需唯一索引方案
  （需 denormalize owner，届时独立变更）。
- Bearer 端点的 CSRF 豁免面 = 携带 Bearer 头的请求；Session Cookie 不会出现在
  无关客户端，风险可控并记录。

## Migration Plan

V4 前向迁移；platform_id 缺失时 integration 端点返回 503 SYSTEM_MISCONFIGURED
（DESIGN 19：启动检查必填项）。

## Open Questions

- MangaWeaver CLI 客户端的命令形态（在其仓库变更中决定，最小为 export / publish 两个子命令）。
