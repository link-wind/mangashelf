# MangaShelf · 漫画阅读与动画内容平台

一个支持阅读漫画、观看对应改编动画、按剧情查找内容的 Web 平台。通过开放 API 与
[MangaWeaver](../MangaWeaver)（独立创作客户端）连接，形成素材获取、动画创作和成片发布的闭环。

没有安装 MangaWeaver 的读者，也可以独立浏览漫画、阅读章节、观看动画、收藏作品并保存进度。

## 文档

- [详细设计 v0.1](docs/DESIGN.md) —— 产品边界、领域模型、异步处理、接入协议与验收标准
- [系统架构图](docs/architecture.png)（可编辑源文件 [architecture.drawio](docs/architecture.drawio)）
- [验证记录](docs/validation/) —— 每个里程碑的实测结果
- [API 文档](docs/api/) —— 按里程碑逐步补充

## 技术形态

一个 Java 工程、两种运行角色（API / Worker），共用业务模块和数据库迁移：

| 层 | 选型 |
| --- | --- |
| 后端 | Java 25 · Spring Boot 4.x · Spring Security + Spring Session JDBC · Spring JDBC（显式 SQL） |
| AI | Spring AI 2.x（仅 Embedding / RAG 接入，创作 Agent 保留在 MangaWeaver） |
| 数据 | PostgreSQL + pgvector / pg_trgm · Flyway 迁移 |
| 异步 | RabbitMQ（数据库任务为事实，队列只通知） |
| 存储 | S3 兼容对象存储（私有桶、签名上传、按需媒体授权） |
| 处理 | 固定 Python 任务入口 + FFmpeg（分页、媒体校验、OCR、索引） |
| 前端 | React + TypeScript + Vite |
| 部署 | Docker Compose（PostgreSQL / RabbitMQ / MinIO / API / Worker） |

> 界面风格参照 [gugu.fan](https://gugu.fan/)（B 站蓝系：底 `#f8f9fa`、主操作 `#00aeec`、
> 链接 `#00658b`、白卡片 12px 圆角、胶囊搜索、半透明毛玻璃顶栏），无广告。

## 里程碑（设计第 22 节）

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| M0 技术验证 | 固定版本、数据库扩展、对象签名、Python 协议 | ✅ 已完成（2026-11-07，见 docs/validation/m0.md） |
| M1 漫画阅读 | 身份、作品／章节、图片／CBZ、版本发布、书架、进度 | ✅ 已完成（2026-11-08，见 docs/validation/m1.md） |
| M2 动画观看 | 动画版本、来源范围、媒体校验、播放器、原作跳转 | ✅ 已完成（2026-11-08，见 docs/validation/m2.md） |
| M3 项目闭环 | 受限 Token、导出、MangaWeaver 导入／主动发布 | ✅ 已完成（2026-11-08，见 docs/validation/m3.md；协议文档 docs/api/mangaweaver-integration.md） |
| M4 智能检索 | OCR、字面检索、Embedding、混合召回、引用问答 | ✅ 已完成（2026-11-08，见 docs/validation/m4.md；问答待对话模型配置） |
| M5 工程验收 | 中断恢复、权限、部署、备份、容量测量 | ✅ 已完成（2026-11-08，见 docs/validation/m5.md） |
| M6 外部动画 | 声明式规则源（Kazumi 兼容子集）、外部集搜索绑定、链接跳转播放 | ✅ 已完成（2026-11-08，见 docs/validation/m6.md） |
| M7 外部动画播放 | 无头嗅探解析直链、hls.js 站内直连播放、失效重解析续播 | ✅ 已完成（2026-11-08，见 docs/validation/m7.md；真实源覆盖边界见该文档 §3/§4） |
| M8 资源目录 | 资源站目录同步（全量/增量）、目录浏览、点播物化入书库 | ✅ 已完成（2026-11-08，见 docs/validation/m8.md；7506 部动漫目录） |
| M10 可用性闭环 | 搜索覆盖目录、周期同步、服务端进度/历史/继续观看、解析限流 | ✅ 已完成（2026-11-09，见 docs/validation/m10.md） |
| M11 内容与容错 | 多线路物化与切换、正片/PV 分组、目录/作品详情页、规则源 Cookie | ✅ 已完成（2026-11-09，见 docs/validation/m10.md） |
| M22 阅读器对齐 HOMURA | 阅读方向 RTL、单双页/首页单排、点击分区、横滑翻页、翻页动画、Esc 逐层退出 | ✅ 已完成（2026-11-09，见 docs/validation/m22.md） |
| M21 漫画 UI 改版 | 漫画索引两栏 + 详情章节格 + 沉浸式阅读器（滚动/翻页） | ✅ 已完成（2026-11-09，见 docs/validation/m21.md） |
| M20 浏览态播放 | 剧集可挂目录条目（不建作品），加入书库时整体收编 | ✅ 已完成（2026-11-09，见 docs/validation/m20.md） |
| M19 直达播放与数据清理 | 点进动画作品直达播放页（续播集优先）+ 测试数据清理脚本 | ✅ 已完成（2026-11-09，见 docs/validation/m19.md） |
| M18 跨源与进度 | 热门榜中文标题跨源匹配（10/12）、漫画进度服务端化并并入历史 | ✅ 已完成（2026-11-09，见 docs/validation/m18.md） |
| M17 漫画多源 | 数据源注册表（能力声明）+ 哔哩漫画热门榜 + MangaDex 榜单/阅读 | ✅ 已完成（2026-11-09，见 docs/validation/m17.md） |
| M16 外部漫画 | MangaDex 搜索/详情/章节、竖向阅读器、图片代理（白名单+缓存+限流） | ✅ 已完成（2026-11-09，见 docs/validation/m16.md） |
| M15 漫画专区 | /manga 索引（缩略图封面）、/manga/:id 详情（续读/逐章已读）、首页漫画区块 | ✅ 已完成（2026-11-09，见 docs/validation/m15.md） |
| M14 首页内容 | Banner 4 张轮播+缩略图、时刻表预览、最近更新、高分榜、分类、页脚 | ✅ 已完成（2026-11-09，见 docs/validation/m14.md） |
| M13 播放器/弹幕 | 自定义播放器皮肤、首页轮播 Banner、站内弹幕（服务端存储） | ✅ 已完成（2026-11-09，见 docs/validation/m13.md） |
| M12 体验与纪律 | 暗色主题、OpenSpec 清账、首个单测 | ✅ 已完成（2026-11-09，见 docs/validation/m10.md；弹幕延后） |
| M9 Bangumi 元数据 | 本季新番日历、封面/评分、标题匹配+资源站反查、时刻表周视图、封面服务端代理 | ✅ 已完成（2026-11-09，见 docs/validation/m9.md；本季 114 部，68 部可看） |

首个完整产品里程碑是 M3。每个里程碑开始时在 `openspec/changes/` 建立对应变更提案。

**M0–M22 全部完成**（外部弹幕源/自动换线/追番红点/部署重验列为后续）。 验证记录见 docs/validation/（m0–m9），部署见下方本地开发。

## 本地开发

```bash
cp .env.example .env          # 填入本地占位配置
docker compose up -d          # PostgreSQL 18 + RabbitMQ 4 + SeaweedFS（S3 兼容）
cd server && ./mvnw package   # Java 25 构建（JAVA_HOME 见下）
java -jar target/mangashelf-server-0.1.0-SNAPSHOT.jar                 # API 角色（含 Flyway 迁移）
MANGASHELF_PYTHON=$PWD/../processing/.venv/bin/python \
MANGASHELF_PROCESSING_ROOT=$PWD/.. \
MANGASHELF_WORK_ROOT=$PWD/../data/jobs \
java -jar target/mangashelf-server-0.1.0-SNAPSHOT.jar --mangashelf.role=worker   # Worker 角色
./scripts/smoke-m0.sh         # 上传→核验→幂等 冒烟
./scripts/smoke-m1.sh         # M1 全链路（双用户/发布/阅读/进度/越权）

cd ../web && npm install && npm run dev   # 前端 http://localhost:5173（同源代理 /api 与 /media）
```

Python 处理任务 venv：

```bash
python3 -m venv processing/.venv
processing/.venv/bin/pip install -r processing/requirements.txt   # Pillow 12.3.0（锁定）
```

Java 25 安装（Homebrew，keg-only）：

```bash
brew install openjdk@25
export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
# 可选：注册到系统 JVM 目录（需 sudo）
# sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-25.jdk
```

## 工程目录

见 [DESIGN.md 第 7 节](docs/DESIGN.md)。目录按里程碑实际需要创建，不预建空骨架。
