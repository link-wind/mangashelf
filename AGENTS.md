# AGENTS.md — MangaShelf 工程约定

对话与文档使用中文。本文件是所有会话（人类与 Agent）的工程约定；设计依据一律以
[docs/DESIGN.md](docs/DESIGN.md) 为准，本文件不重复设计内容。

## 核心原则

1. 以 docs/DESIGN.md 为唯一设计依据；实现前先读对应章节，不凭印象引入设计之外的结构。
2. 精简、按需拆分、不过度设计。不为未来需求加抽象层，不建通用插件体系、DAG 引擎、
   通用工作流框架；模块之间用明确方法调用。
3. 每个里程碑只创建当期需要的表、端点和模块目录（DESIGN 第 22 节），不提前搭建完整骨架。
4. 显式 SQL（Spring JDBC），不引入 ORM；状态与约束尽量下沉到数据库
   （外键、CHECK、部分唯一索引），不只在 Java 层校验。
5. 数据库任务记录是事实，队列消息只是通知；任何"队列决定状态"的实现都是错误的。

## 复用与来源

- 复用 MangaWeaver 代码时固定来源提交（当前基线 `e057b32`），核对许可证，在
  design.md 记录来源与改动理由。移植的 Python 组件剥离桌面会话耦合
  （`events.emit` 对 `agent.conversation` 的惰性依赖）。
- 不把"参考实现支持某格式"当作"服务端可安全接收任意该格式文件"（DESIGN 10.1）。

## 验证与测试

1. 少量关键逻辑测试 + 真实最小链路，不机械追求覆盖率（DESIGN 21）。
2. 事务、约束和队列行为在真实 PostgreSQL／RabbitMQ 环境验证，不用大量 Mock 声称一致性。
3. 每个里程碑的验证场景、结果与已知限制写入 `docs/validation/<里程碑>.md`。
4. 显式报错，不静默回退；权限、格式和参数错误直接失败，不做全量 Exception 自动重试。

## 文档与变更

1. 变更走 OpenSpec：`openspec/changes/<name>/`（proposal.md / design.md / tasks.md /
   specs delta），完成后 `openspec archive` 归档。活跃变更不加日期前缀。
2. 决策、实测结果以日期追加到对应 design.md，不倒改历史段落。
3. 版本号、性能数字在真实构建／测量后才写入文档；未实现写"设计／计划"，
   完成后才写"实现／构建"（DESIGN 23.3）。
4. 日志与文档不包含 Cookie、Authorization、模型 Key、签名 URL 和私人正文（DESIGN 19）。

## 代码风格

- Java：跟随 Spring 社区惯例；模块内按需放 Controller / Service / Repository，
  不建 domain/application/infrastructure 三套空目录。
- Python（processing/）：固定任务入口 `python -m processing.run --task <task.json>`；
  stdout 只输出 JSONL 进度，日志走 stderr，产物写 result.json（DESIGN 12.2）。
- 提交信息用中文祈使句，一次提交对应一个完整小步骤。

## 安全边界（不可违反）

- Python 任务不持有数据库、对象存储管理员或用户会话凭据；不接收任意命令、任意 URL。
- 服务端生成对象 Key 与签名 URL；用户不能指定任意桶和对象 Key。
- 模型接口 Key 只放服务端环境配置；原始 API Token 仅创建时展示。
- 搜索必须在有权访问的内容范围内召回，不能先检索全库再前端隐藏（DESIGN 3.3）。
