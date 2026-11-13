# content-retrieval Specification

## Purpose
TBD - created by archiving change implement-intelligent-retrieval. Update Purpose after archive.

## Requirements

### Requirement: 页级索引由所有者显式触发并完整激活

所有者 MUST 能对 READY/PUBLISHED 章节版本显式启动索引批次；批次 MUST 以
BUILDING → READY 构建，完整落库后原子 ACTIVE。索引 MUST 记录来源清单摘要与
处理版本。重复触发 MUST NOT 与既有 ACTIVE 批次混用结果。

#### Scenario: 触发索引并完成

- **WHEN** 所有者对已解析版本触发索引并等待完成
- **THEN** 批次 ACTIVE，每页存在页级文本块（无文字页如实为空），批次记录来源摘要

### Requirement: 搜索在授权范围内召回

搜索 MUST 先在授权范围内检索（owner 或 PUBLIC+PUBLISHED），结果 MUST 提供
作品/章节/页级定位。未发布版本的索引块 MUST NOT 出现在结果中。
"有动画"过滤 MUST 按页级关联实时判定。

#### Scenario: 私人素材不被检索

- **WHEN** 用户 A 对私人草稿构建索引后，用户 B 以相同关键词搜索
- **THEN** 结果不含 A 的私人内容

#### Scenario: 页级定位

- **WHEN** 搜索命中某页 OCR 文本
- **THEN** 结果含作品/章节/页序，可跳转到该页阅读

### Requirement: 语义召回与批次模型一致

语义分支 MUST 仅在配置了经验证的 Embedding 模型时可用；查询与索引 MUST 使用
同一模型与维度；不同批次的向量 MUST NOT 混合召回。未配置时入口 MUST 明确标记
不可用（503），字面检索不受影响。

#### Scenario: 模型未配置

- **WHEN** 未配置 Embedding 模型时请求语义检索
- **THEN** 返回 503 并明确说明；字面检索仍可用

### Requirement: 问答只引用服务端证据

问答 MUST 先检索并将证据以服务端证据 ID（S1…Sn）提供给模型；模型输出中引用的
ID MUST 经服务端映射回真实页面，无效 ID MUST 被丢弃。证据不足时 MUST 返回
"当前素材无法确认"并可继续展示搜索结果。素材文本 MUST 作为不可信资料处理，
问答 MUST 没有任何数据库写入权限。

#### Scenario: 无对话模型

- **WHEN** 未配置对话模型时请求问答
- **THEN** 返回 503 QA_NOT_CONFIGURED，搜索不受影响

#### Scenario: 素材中不存在的问题

- **WHEN** 提问内容无法由召回证据支持
- **THEN** 回答明确"当前素材无法确认"，不伪造引用

### Requirement: 搜索模式显式区分

全站搜索 MUST 区分 `title`（默认）与 `semantic` 两种模式。`title` 模式 MUST NOT 计算
查询向量或调用 Embedding 模型；`semantic` 模式 MUST 使用与索引一致的经验证模型。
"有动画"筛选 MUST 在两种模式下按页级关联实时生效，且前端发送的筛选参数与后端契约一致。

#### Scenario: 默认标题检索不跑模型

- **WHEN** 客户端以默认模式搜索标题且未请求语义
- **THEN** 服务端仅执行字面与标题召回，不计算查询向量，响应耗时不受模型影响

#### Scenario: 语义模式未配置模型

- **WHEN** 客户端请求 `semantic` 模式但未配置 Embedding 模型
- **THEN** 返回 503 并明确说明；字面检索仍可用

#### Scenario: 只看有动画

- **WHEN** 客户端携带"有动画"筛选搜索
- **THEN** 结果仅包含存在已发布动画页级关联的作品或页面，筛选在标题与语义模式均可生效
