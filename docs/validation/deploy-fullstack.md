# 部署重验：全栈空库 + 最新镜像（V1–V15）

日期：2026-11-10（M5 后首次全栈重验，含 M23 代码）

## 方式

- 独立 compose 项目 `msh-verify`（独立端口 25432/25672/29000/28080/25173、
  独立卷），不触碰开发栈；`--profile full` 起完整 6 服务。
- 验证两轮：首轮暴露缺陷 → 修复 → `down -v` 后第二轮回归。

## 首轮暴露并修复的缺陷

1. **compose profiles 缺失（M5 遗留）**：注释声称 `--profile full` 起全栈，
   但 api/worker/web 从未挂 profiles 键，裸 `up -d` 会与开发机原生 8080 冲突。
   修复：三服务补 `profiles: ["full"]`（实测裸 config 仅 3 依赖、full 6 服务）。
2. **worker 镜像 Python 断链**：venv 在 python:3.14-slim（Debian）中创建后
   跨阶段复制进 temurin（Ubuntu noble），`bin/python` symlink 悬空——容器内
   Python/OCR/嗅探从未真正可用（M7/M20 验证均在本机 worker 完成）。
   修复：Dockerfile.worker 改单阶段同基底（noble apt python3 + venv），
   模型/浏览器缓存落 /opt 固定路径并 chown 运行用户，不再依赖 root HOME。
3. **worker 规则源加载竞态**：worker 与 api 并发启动，worker 查
   `manga_source_rules` 时 V15 迁移未完成（bad SQL grammar）。
   修复：MangaSourceRegistry 规则源加载移至 ApplicationReadyEvent，
   失败延迟 10s 重试一次。

## 第二轮回归（全部通过）

- 迁移链：空库 V1–V15 全部 `success=t`（含 V15 规则表 + 腾讯种子）。
- 六容器全部 Up，api 健康 UP（liveness/readiness）。
- 经 web(nginx) 反代：登录 200；三源注册表
  （bilibili RANK / mangadex 全能力 / tencent 全能力）。
- 容器网络直连外网：bilibili、tencent 榜单 200；航海王详情 1198 话；
  第 1 话 58 页解码。
- worker 容器内：Python 3.12.3 venv 可用；Chromium 启动渲染 OK；
  RapidOCR 初始化 OK；规则漫画源已加载 [tencent]。
- **断网验证**（`--network none`）：RapidOCR + Chromium 离线可用，
  运行期零联网承诺成立。

## 排障记录（值得留的坑）

- compose 按 `-p <project>` 隔离镜像标签：`docker compose build`（默认项目）
  更新的是 `mangashelf-worker`，而 `msh-verify` 项目用的是 `msh-verify-worker`
  ——修完镜像不重建项目镜像，`up -d` 会继续用旧镜像。重验须在目标项目下
  显式 `build` 再 `up`。

## 遗留

- 生产部署形态（域名/TLS/备份/监控）不在本验证范围（DESIGN 20 生产另行决策）。
- api 容器未定义 compose healthcheck（当前 worker 以 ready 重试自愈；
  编排级健康门禁可后续加）。
