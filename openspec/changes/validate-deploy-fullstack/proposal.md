# 提案：全栈部署重验（V1–V15 空库 + 最新镜像）

## Why

部署线欠账（M5 后首次）：worker/API 镜像落后于 M23 代码；V1–V15 迁移链从未在
空库上整体跑过；compose 注释声称 `--profile full` 起全栈，但 api/worker/web
从未挂 profiles 键（裸 up 会与开发机原生 8080 冲突）——M5 遗留缺陷。

## What Changes

1. compose.yaml：api/worker/web 补 `profiles: ["full"]`（恢复 M5 文档行为，
   日常 `up -d` 只起依赖）。
2. 重建 api/worker/web 镜像（含 M23 jar 与最新前端 dist）。
3. 独立 compose 项目（独立端口/卷，不触碰开发栈）从零起全栈：
   验证 V1–V15 迁移、健康检查、登录、三源榜单、worker Playwright 可用。
4. 验证记录写入 docs/validation/deploy-fullstack.md。

## 不做

- 生产部署（域名/TLS/备份策略）——DESIGN 20 生产形态另行里程碑；
- 性能/容量复测（M5 已测，无代码变化假设不变）。
