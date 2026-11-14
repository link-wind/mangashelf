# platform-integration Specification

## Purpose
TBD - created by archiving change implement-mangaweaver-integration. Update Purpose after archive.

## Requirements

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

### Requirement: 多实例部署形态

compose MUST 提供双 API 副本的部署定义（显式服务或等价机制），两个实例 MUST 共享
数据库、Redis、对象存储与页图缓存卷，入口 MUST 通过 upstream 配置多个 API 上游，
实例 MUST 用可区分的平台标识（MANGASHELF_PLATFORM_ID）便于日志排查。
单实例部署 MUST NOT 因新增定义而改变默认行为。

#### Scenario: 入口转发到两副本

- **WHEN** 启用多实例入口配置
- **THEN** 请求被转发到任一健康 API 副本；摘除一个副本后其余副本继续服务

#### Scenario: 页图缓存共享卷

- **WHEN** 两个 API 实例同时挂载哔哩缓存卷
- **THEN** 任一实例下载的页图对另一实例可读（同一路径与键）

### Requirement: 本季新番元数据含题材标签

Bangumi 日历同步 MUST 保存条目的题材标签（tags，如热血/机甲/恋爱）；番剧索引 MUST
支持按题材筛选目录条目（依据条目关联的 Bangumi 标签），题材列表来自已同步的标签集合。
未关联 Bangumi 的条目 MUST NOT 出现在题材筛选结果中。

#### Scenario: 同步保留标签

- **WHEN** bangumi.sync 拉取本季日历
- **THEN** 每个条目的 tags 保存到库中，重复同步不丢失

#### Scenario: 按题材筛选番剧

- **WHEN** 用户在番剧索引选择「热血」
- **THEN** 列表仅显示关联 Bangumi 标签含热血的目录条目
