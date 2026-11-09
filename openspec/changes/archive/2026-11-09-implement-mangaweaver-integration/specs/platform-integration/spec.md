# platform-integration 规格（M3）

## ADDED Requirements

### Requirement: 个人接入 Token 一次性展示与可撤销

登录用户 MUST 能创建带 scope 集合与过期时间的接入 Token；原始 Token MUST 仅在
创建响应中展示一次，系统只保存其安全哈希与可辨认前缀。用户 MUST 能查看 Token 摘要
（前缀、scope、创建与过期时间）并撤销；撤销后该 Token MUST 立即不能通过认证。

#### Scenario: 创建并列出 Token

- **WHEN** 用户创建带 materials:read 的 Token，随后查看 Token 列表
- **THEN** 创建响应含原始 Token（仅此一次），列表只显示前缀与摘要信息

#### Scenario: 撤销后访问

- **WHEN** 已撤销 Token 的持有者调用接入端点
- **THEN** 返回 401，且不能再签发新的媒体链接；已签发地址仍受短有效期约束

### Requirement: scope 限定接入能力

接入端点 MUST 校验 Token scope：素材导出需 materials:read，目录读需 catalog:read，
成片草稿与上传需 animations:write，显式发布需 animations:publish。
scope 不足 MUST 返回 403 且不执行操作。Token 能力与用户自身权限取交集。

#### Scenario: 缺少发布 scope

- **WHEN** 持有 animations:write（无 animations:publish）的 Token 调用发布
- **THEN** 返回 403，动画保持原状态

### Requirement: 素材导出返回版本化清单

导出 MUST 面向请求方有权阅读的固定章节版本，返回版本化清单（协议 schema_version、
稳定 platform_id、阅读方向、页序、每页已核验 SHA-256 与短期下载地址）。
用户声明或文件名 MUST NOT 作为可信业务字段。

#### Scenario: 导出并核验页面

- **WHEN** 客户端请求导出某已发布章节版本并逐页下载
- **THEN** 清单中每页 SHA-256 与下载内容一致，地址过期后须重新获取且来源 ID 不变

### Requirement: 发布草稿按键幂等且可查询回执

成片发布 MUST 以（用户、接入类型、客户端发布请求键）为幂等边界：
同键重复调用返回同一草稿；新键创建新修订版且 MUST NOT 覆盖既有成片。
客户端 MUST 能按键查询草稿状态；发布完成后回执含动画 ID、版本 ID 与观看地址。

#### Scenario: 同键重放

- **WHEN** 客户端以同一发布键重复调用 publications
- **THEN** 返回同一动画与版本，不产生新的草稿

#### Scenario: 发布完成后的回执

- **WHEN** 草稿经上传、验证并发布后客户端查询同键状态
- **THEN** 回执含 revisionStatus=PUBLISHED 与 watch_url，站内可观看
