## ADDED Requirements

### Requirement: 数据库迁移由单一部署步骤执行

多实例部署下，数据库迁移 MUST 由单独的部署步骤（一次性迁移服务或发布流程）执行，
所有 API/Worker 常驻副本 MUST 禁用启动迁移（`MANGASHELF_DB_MIGRATE=false` 或等价机制）。
迁移失败 MUST 阻止应用副本启动对外服务。单实例部署保持"API 启动迁移"默认行为。

#### Scenario: 双 API 并发启动

- **WHEN** 两个 API 副本同时启动且迁移开关关闭
- **THEN** 两副本均不执行迁移，直接启动并等待就绪；无 Flyway 竞态锁日志

#### Scenario: 迁移步骤失败阻止发布

- **WHEN** 一次性迁移步骤执行失败
- **THEN** 容器以非零码退出，部署流程中断，应用副本不启动

## ADDED Requirements

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
