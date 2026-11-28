# integrate-7sefun-backup-line 验证记录

- 日期：2026-11-28
- 环境：macOS（Java 25 / Spring Boot 4.1.2 / Python 3.14 venv）+ Docker（PostgreSQL 18 / RabbitMQ 4 / Redis 7 / SeaweedFS 3.96）+ 本地 API (`:8080`) / Worker + Vite (`:5173`)
- 变更：`openspec/changes/integrate-7sefun-backup-line/`

## 验收场景与实测结果

| 场景 | 验证方式 | 实测结果 |
| --- | --- | --- |
| Flyway `V38__seed_7sefun_source.sql` 幂等迁移 | API 启动执行迁移 + `flyway_schema_history` 核对 | ✅ V38 迁移成功，`external_sources` 存在已启用的 `七色番 (7sefun)` 规则源 |
| Java 单元测试 | `cd server && ./mvnw -o test -Dtest='*Test'` | ✅ **94/94 通过**（含新增 `RuleLineMatcherTest` 4 项：季期/剧场版区分、签名直链 `Expires` 提前过期判定） |
| Python 处理单元测试 | `processing/.venv/bin/python -m unittest discover -s processing/tests -v` | ✅ **4/4 通过**（含 `player_aaaa` `encrypt=0/1/2` 解码、单跳 302 `Location` 提取与非直链回退校验） |
| 前端类型检查与构建 | `cd web && npm run build` | ✅ `tsc -b && vite build` 通过（1.90s） |
| 访客按需挂线（同季精准匹配） | 匿名请求 `POST /api/v1/catalog-items/d515c6f1-9e8a-4c2c-87d4-bca098bd43d7/extra-lines`（《葬送的芙莉莲第二季》） | ✅ `200 OK`（耗时 `1.58s`），精准匹配《葬送的芙莉莲第二季》（未错配第一季），写入 `7sefun_mp4` 共 10 集，线路列表更新为 `['lzm3u8(10)', 'liangzi(10)', '7sefun_mp4(10)']` |
| 多源共存保护（`ensureBrowseEpisodes`） | 再次请求 `GET /api/v1/catalog-items/{id}/episodes` | ✅ 保留主源与 `7sefun_mp4` 全部 3 条线路，未覆盖或重复写入 |
| `player_aaaa` 纯 HTTP 秒解快路径 | `POST /api/v1/external-episodes/8f9b9870-9f5e-4716-a757-7a1125b9553a/resolve` 端到端轮询 | ✅ **`2.58s`** 内完成（API 入库 → Outbox → RabbitMQ → Worker → Python HTTP 快路径 → 落库），返回 `SUCCEEDED` + `playKind = mp4`（未启动 Chromium，较此前 Playwright 嗅探 ~19s 大幅提速） |

## 已知限制

- 仅挂载 `7sefun.top` 七色B线（`-1-` / `from=ndx` 直连 MP4）；七色A线（`-2-` / `lmm85` 嵌套反自动化 iframe）已通过规则选择器排除，不做反爬对抗。
- 部分番剧在 `7sefun.top` 未收录或无 B 线时，接口显式返回 `added=false` 与提示文案，前端通过 Toast 告知用户，不静默失败。
- 实测 `www.7sefun.top` 偶发 TLS 握手断开，`RuleEngine` 与 `external_resolve.py` 已加入单次短重试（400ms）；最终 MP4 节点由浏览器直连，播放速度与连通性取决于客户端本地网络出口，服务端不中转视频字节。

## 2026-11-28 切集与切线性能优化补充实测

针对切集/切线偏慢问题，补齐四项优化：`FastPlaybackResolver`（API 层直链秒回 + MacCMS `player_aaaa` 同步 HTTP 快解）、带 `Expires` 签名直链分层缓存窗口（60 分钟上限 + 距过期 120s 保护）、Worker `content.resolve` 并发度提至 2 / 超时收紧至 18s / Outbox 间隔 200ms、前端 `useEpisodePlayback.ts` 渐进退避轮询（`250→350→500→800→1200→1500ms`）+ 复用 POST 响应 + 下一集静默预热。

- 单元测试与构建：Java **98/98 通过**（含新增 `FastPlaybackResolverTest` 4 项），前端 `tsc -b && vite build` 通过。
- 本地真实链路耗时实测（《葬送的芙莉莲第二季》`d515c6f1-9e8a-4c2c-87d4-bca098bd43d7`）：
  - **国内主线路（`lzm3u8`）切集**：`GET /resolve` **`8.2 ms`**（`jobId = null`，零队列、零 Python 进程、零轮询；优化前需走异步任务 + 前端 2s 轮询约 `2.5s`）；
  - **7sefun 高清直连线 · 首次冷切集（第 03 集）**：`GET` `6.4 ms` 未命中 → `POST /resolve` API 同步快解直出 **`1,906.6 ms`**（`jobId = null`，0 次轮询，耗时基本全为上游网络往返）；
  - **7sefun 高清直连线 · 预热/缓存命中（60 分钟有效期内）**：`GET /resolve` **`5.1 ms`** 毫秒级秒开。

