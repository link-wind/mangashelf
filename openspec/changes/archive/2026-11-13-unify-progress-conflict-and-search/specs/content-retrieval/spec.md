## ADDED Requirements

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
