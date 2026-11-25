# MangaShelf · 漫画阅读与动画内容平台

一个支持阅读漫画、观看对应改编动画、按剧情查找内容的 Web 平台。通过开放 API 与
[MangaWeaver](../MangaWeaver)（独立创作客户端）连接，形成素材获取、动画创作和成片发布的闭环。

没有安装 MangaWeaver 的读者，也可以独立浏览漫画、阅读章节、观看动画、收藏作品并保存进度。

## 文档

- [详细设计 v0.1](docs/DESIGN.md) —— 产品边界、领域模型、异步处理、接入协议与验收标准
- [系统架构图](docs/architecture.png)（可编辑源文件 [architecture.drawio](docs/architecture.drawio)）
- [服务器部署](docs/deploy-server.md) —— Docker Compose、域名/TLS、管理员初始化与备份
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
processing/.venv/bin/python -m playwright install chromium
```

本机启动 API 时，如需使用浏览器漫画源，也要设置上面 Worker 命令中的
`MANGASHELF_PYTHON`、`MANGASHELF_PROCESSING_ROOT` 和 `MANGASHELF_WORK_ROOT`。
可选的 `MANGASHELF_BROWSER_PROXY` 用于指定其他浏览器漫画源的代理。
全栈部署时填容器可达的代理地址，不要填宿主机的 `127.0.0.1`。

哔哩目录和阅读使用 Java HTTP，`MANGASHELF_BILIBILI_PROXY` 留空时显式直连。
章节先返回完整页序，图片按阅读位置下载；缓存保存无签名索引和校验后的原图，
默认目录/索引有效期为 30/10 分钟，页图闲置 7 天过期，容量上限 512 MiB。
配置见 `.env.example`；全栈通过 `bilibili-http-cache` 卷跨重启保存。
不要将临时签名地址或 Cookie 写入配置、缓存或日志。
真实本地与 Docker 的完整阅读、按需请求和跨重建缓存结果见
[哔哩 HTTP 阅读验收](docs/validation/bilibili-http-reader.md)。

所有外部漫画及条漫统一按阅读位置加载，恢复进度前不预读章首，失败页可独立重试。
未知尺寸通过占位、已测尺寸记录和滚动锚点保持当前位置；各源清单提取方式及实测边界见
[外部漫画与条漫按需加载验收](docs/validation/external-manga-lazy-loading.md)。

拷贝漫画的详情与完整章节目录使用官方 v3 API（Java HTTP），缓存 30 分钟、最多
128 部作品，缓存失效和进程重启后仍通过 API 获取。默认直连；出口受限时显式设置
`MANGASHELF_COPYMANGA_PROXY`（如本机 `http://127.0.0.1:7892`，Docker 使用
`http://host.docker.internal:7892`），不会自动换出口或回退浏览器。
搜索和章节图片仍使用现有浏览器解析与 `MANGASHELF_BROWSER_PROXY`；公开章节可匿名读取，
受限章节需要登录态。浏览器源与登录助手统一使用 `https://www.copy4000.com`，
避免登录态按 `mangacopy.com` 域保存后无法复用。
目录缺失或数量不符会报错；验收与限制见
[拷贝漫画 HTTP 目录验收](docs/validation/copymanga-http-chapters.md)。
受限章节可用登录助手生成 storage_state，设置
`MANGASHELF_COPYMANGA_STORAGE_STATE` 后重启 API/Worker。

Java 25 安装（Homebrew，keg-only）：

```bash
brew install openjdk@25
export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
# 可选：注册到系统 JVM 目录（需 sudo）
# sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-25.jdk
```

## 性能、可靠性与混合检索实测

- **高并发读路径与热点缓存（PostgreSQL + `HotspotCache`）**：在固定本机单实例（Java 25 `-Xmx512m` + Docker PostgreSQL 18，7,512 条番剧目录）24 VUs × 12s 混合并发压测下：
  - **Stage 1（基线）**：`414.25 RPS`、p50/p95 `57.32 / 89.52 ms`、平均响应体 `295.60 KB`（列表返回 `play_groups` + `Seq Scan 2,228 pages`）；
  - **Stage 2（V29 复合/表达式索引 + 列表去 `play_groups` + 批量聚合，关闭缓存）**：`1,405.48 RPS`（`3.39×`）、p50/p95 `16.26 / 28.10 ms`、平均响应体 `16.21 KB`，首屏列表 SQL 由 `Seq Scan 2,228 pages / 3.03 ms` 降至 `Index Scan 24 pages / 0.05 ms`；
  - **Stage 3（叠加 `HotspotCache` 有界 TTL + 并发回源合并防击穿）**：`1,912.09 RPS`（`4.62×`）、p50/p95 `11.19 / 22.36 ms`，Prometheus 实测热点命中率 `99.98%`（23,376 hit / 5 miss）。
- **媒体交付与缓存实测（SeaweedFS S3 直连 + Range + 304）**：使用本机合规自生成素材（20 页 `1080×1600` WebP 漫画 `16.94 MiB` + 10s `1280×720` H.264/AAC `faststart` MP4 `4.52 MiB`）在真实 PostgreSQL 18 + SeaweedFS 3.96 上完成对照实测：
  - **100% 对象存储直连**：漫画页图与动画 MP4 字节完全绕过 Spring Boot（Java API 转发媒体字节恒为 `0 B`）；Nginx `/media/` 配置 HTTP/1.1 长连接、`proxy_buffering off` 与 `proxy_force_ranges on`；
  - **漫画 30 次翻页会话（20 页顺序 + 跨秒后 10 页回看）**：消除 `/pages` 资产键 N+1（`41 → 2` SQL）、启用授权后短期签名窗口复用（`PresignService`）与分层 `Cache-Control: private, max-age=86400, immutable`、叠加 `Reader.tsx` 窗口复用与 2 页有限预加载后：API 请求由 `30 → 2` 次（`-93.3%`）、跨秒签名 URL 稳定率 `0% → 100%`、浏览器/预加载命中率 `0% → 96.7%`、对象存储下载流量由 `25.41 → 16.94 MiB`（回看流量 `-100%`）、翻页 p95 由 `18.05 → 4.72 ms`（`-73.9%`），对象存储与封面代理均验证 `ETag` / `If-None-Match` → `304 Not Modified`（`0 B`）；
  - **动画 MP4 播放与实时权限**：首段 `Range: bytes=0-65535` 与中/后段随机拖动均返回 `206 Partial Content`（TTFB `0.76–1.05 ms`、`Accept-Ranges: bytes`、`Cache-Control: private, max-age=86400`），下架后 `/playback` 立即返回 `404`（签名复用绝不绕过实时权限校验）。
- **异步任务可靠性（PostgreSQL + RabbitMQ）**：数据库 `jobs` 为状态唯一事实，RabbitMQ 仅作通知通道，不引入 Kafka/Redis Stream。通过 Transactional Outbox（互斥领取租约 + Publisher Confirm + 指数退避 jitter）、Worker `lease_token` Fencing（含 `ChapterParseHandler` / `AnimationVerifyHandler` 短事务校验）、单条独立事务恢复扫描、白名单 `TransientJobException` 有限重试与 `content.dlq` 毒消息隔离应对崩溃与重投。
- **零 OCR 站内混合检索（`pg_trgm` + `pgvector`）**：基于站内作品、动画、番剧目录（含 Bangumi 元数据）与漫画目录构建 `search_documents`（V28），所有策略 SQL 前置权限过滤（`is_public = true OR owner_id = :viewerId`）。
  - 在 200 条向量化站内文档、120 条分级标注查询（40 dev / 80 test 盲测，本地 ONNX `e5-small-v2` 384 维）上实测：
    - 整串 `pg_trgm` 首轮基线 `Recall@10 = 0.2563` → 优化为 `word_similarity` + 多词拆分后 `A: LEXICAL` 升至 `Recall@10 = 0.9975 / nDCG@10 = 0.9901`（暖缓存 p50/p95 `8.63 / 16.75 ms`）；
    - `B: VECTOR`（`e5-small-v2`）在英文剧情语义改写上达到 `1.000 / 1.000`，但纯中文受英文分词 `[UNK]` 坍缩影响（总体 `Recall@10 = 0.6875`）；
    - `C: HYBRID_RRF (k=20)` 总体 `Recall@10 = 0.9908 / MRR@10 = 0.9708 / nDCG@10 = 0.9677`；叠加毫秒级确定性轻量重排 `D: HYBRID_RERANK` 后提升至 `MRR@10 = 0.9906 / nDCG@10 = 0.9882`（`cross_type_topic` 的 `nDCG@10` 由 `0.873` 提至 `0.953`，暖缓存 p50/p95 `19.05 / 29.16 ms`）。
- **一键复现命令**：

```bash
cd server && ./mvnw test                                       # 58/58（48 单测 + 8 故障注入 IT + 2 Hybrid Search IT）
python3 scripts/bench/run_load_bench.py --base http://127.0.0.1:8080 --vus 24 --duration 12
# 或使用 k6：k6 run -e BASE_URL=http://127.0.0.1:8080 scripts/bench/k6-workload.js
# 媒体交付实测（合规自生成素材）：processing/.venv/bin/python scripts/bench/run_media_bench.py --mode prepare --fixture /tmp/fx.json
# 可选可观测看板：docker compose --profile full,obs up -d prometheus grafana
```

详细实测报告与错例/执行计划分析见：
- [性能基线报告（Stage 1）](docs/validation/benchmark-baseline.md)
- [数据库读路径优化与热点缓存三阶段实测](docs/validation/benchmark-db-and-hotspot-cache.md)
- [媒体交付链路审计与端到端实测](docs/validation/audit-and-measure-media-delivery.md)
- [异步任务可靠性与故障注入验证](docs/validation/harden-reliable-jobs-and-fault-injection.md)
- [站内混合检索基线与 Rerank 实测](docs/validation/benchmark-and-optimize-hybrid-search.md)

## 工程目录

见 [DESIGN.md 第 7 节](docs/DESIGN.md)。目录按里程碑实际需要创建，不预建空骨架。
