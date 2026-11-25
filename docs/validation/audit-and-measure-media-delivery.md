# audit-and-measure-media-delivery 验证记录

- 日期：2026-11-25
- 代码基线：`main` `6a3703a` + 本变更改动（分层 `Cache-Control` + 授权后短期签名窗口复用 + 批量 `findObjectKeysByIds` + Bangumi 封面 `ETag`/304 + `Reader.tsx` 窗口缓存与有限预加载 + Nginx `/media/` 代理调优）
- 控制变量（两阶段严格一致）：
  - API：本机 Java 25.0.5（`-Xms256m -Xmx512m`，Spring Boot 4.1.2，监听 `:18089`，预热一轮后采集第二轮数据）
  - DB：Docker Desktop `pgvector/pgvector:pg18`（PostgreSQL 18.6）
  - 对象存储：Docker Desktop `chrislusf/seaweedfs:3.96`（监听 `:9000`，私有桶 `mangashelf`）
  - 合规测试素材（由 `scripts/bench/run_media_bench.py --mode prepare` 本机合成，测完即清理，不依赖第三方未授权资源）：
    - 漫画：20 张 `1080×1600` WebP 阅读图（合计 `17,758,836 B` / `16.94 MiB`，单页均值 ~867 KiB）+ 20 张 `240×320` WebP 缩略图
    - 动画：10s `1280×720` `30fps` H.264 + AAC `-movflags +faststart` MP4（`4,740,868 B` / `4.52 MiB`）+ `poster.jpg`（`42,668 B`）+ `subtitles.vtt`（`174 B`）

## 1. 现状审计结论

1. **媒体字节直连边界核验**：漫画阅读图、缩略图、动画视频、封面与字幕均经短期 S3 SigV4 预签名 URL 由 SeaweedFS（或 Nginx `/media/` 反代）直接传输；实测 Java API 转发的媒体字节数恒为 **0 B**（仅输出轻量 JSON 元数据）。
2. **审计定位的五处瓶颈与短板**：
   - `ChapterParseHandler` / `AnimationVerifyHandler` 上传不可变派生资产未写 `Cache-Control`，`PresignService.presignGet` 未传 `response-cache-control` 且每秒生成全新 `X-Amz-Date` 签名导致浏览器翻页回看缓存命中率为 `0%`；
   - `CatalogService.pages` 与 `AnimationService.playback` 逐资产循环调用 `assets.findObjectKey`（20 页页面清单触发 41 次 SQL，N+1）；
   - `/api/v1/bangumi-covers/{subjectId}` 仅有 `Cache-Control` 缺少 `ETag` 与 `If-None-Match` 304 响应；
   - `web/src/pages/Reader.tsx` 每次翻页无条件重拉 API 窗口且缺少有限下一页预加载；
   - `deploy/nginx/nginx.conf` 的 `/media/` 缺少 HTTP/1.1 长连接、`proxy_buffering off` 与 `proxy_force_ranges on`。

## 2. 漫画阅读会话对照实测（20 页顺序 + 跨秒后 10 页回看，共 30 次视图）

两阶段在读完第 20 页后均停顿 `1.1s` 再回看第 11–20 页，模拟真实阅读跨自然秒回看场景：

| 指标 | Stage 1：基线（`presign-reuse=false` + 逐页重拉） | Stage 2：优化（`presign-reuse=true` + 窗口复用 + 2 页预加载 + 批量 SQL） | 变化 |
| --- | --- | --- | --- |
| API `/pages` 请求次数 | 30 次 | **2 次** | **-93.3%** |
| API JSON 响应总字节 | 144,350 B（141.0 KiB） | **21,857 B（21.3 KiB）** | **-84.9%** |
| 单次 `/pages?limit=20` 资产 SQL 数 | 40 次逐条查询（+1 页表 = 41） | **1 次 `id = ANY(?)` 批量查询（+1 页表 = 2）** | **-95.1% SQL** |
| `/pages?limit=20` 延迟（p50 / p95） | 5.67 ms / 11.25 ms | **3.56 ms / 8.09 ms** | p50 **-37.2%** / p95 **-28.1%** |
| 跨自然秒签名 URL 稳定率 | 0.0%（0/20 一致） | **100.0%（20/20 一致）** | 浏览器缓存生效 |
| 浏览器/客户端命中率（30 次翻页） | 0.0%（0/30） | **96.7%（29/30，仅首图冷载）** | 翻页近似零等待 |
| 对象存储媒体下载次数 / 总字节 | 30 次 / 26,644,978 B（25.41 MiB） | **20 次 / 17,758,836 B（16.94 MiB）** | 回看流量 **-100%**，总流量 **-33.3%** |
| Java API 媒体字节转发 | **0 B** | **0 B** | 100% 直连对象存储 |
| 首图加载耗时（含首次 API + 图） | 12.83 ms | 14.84 ms | 本地环回量级一致 |
| 翻页端到端延迟（p50 / p95 / avg） | 8.43 / 18.05 / 9.59 ms | **0.00 / 4.72 / 0.48 ms** | p95 **-73.9%** |

- **对象存储响应头与条件验证（SeaweedFS 3.96 实测）**：
  - `Content-Type: image/webp`
  - `Cache-Control: private, max-age=86400, immutable`
  - `ETag: "46ad76b1a3ea6b1bd502dce9ddf0ce18"`
  - 携带 `If-None-Match` 条件重新验证：**`304 Not Modified`（`0 B` / `1.54 ms`）**。

## 3. 动画 MP4 播放、Range 206 拖动与实时权限核验

| 测试项 | 实测结果 | 说明 |
| --- | --- | --- |
| `GET /api/v1/animations/{id}/playback` | p50 **2.83 ms** / p95 **3.25 ms** | 3 个资产一次批量查询（`ANY(?)`） |
| 视频服务主机与 API 带宽 | `127.0.0.1:9000` / API 转发 **0 B** | 视频字节完全绕过 Spring Boot |
| 首段加载 `Range: bytes=0-65535`（moov + 首帧） | **`206 Partial Content`**，`65,536 B`，TTFB **1.05 ms** / 总耗时 **1.34 ms** | `Accept-Ranges: bytes`、`Content-Range: bytes 0-65535/4740868`、`Cache-Control: private, max-age=86400`、`ETag` 齐全 |
| 中段拖动 `Range: bytes=262144-393215` | **`206 Partial Content`**，`131,072 B`，TTFB **0.78 ms** / 总耗时 **1.35 ms** | 随机拖动无缓冲阻塞 |
| 后段拖动 `Range: bytes=524288-655359` | **`206 Partial Content`**，`131,072 B`，TTFB **0.76 ms** / 总耗时 **1.36 ms** | 响应稳定 |
| 封面 `poster.jpg`（200 vs 304） | `200`（42,668 B / 0.88 ms）→ `If-None-Match` **`304`（0 B / 0.46 ms）** | `Cache-Control: private, max-age=86400, immutable` |
| 字幕 `subtitles.vtt`（200 vs 304） | `200`（174 B / 0.50 ms）→ `If-None-Match` **`304`（0 B / 0.44 ms）** | `Cache-Control: private, max-age=86400, immutable` |
| 实时权限撤回验证 | 下架前 `200` → `POST /withdraw` `200` → 下架后立即 **`404 ANIMATION_NOT_FOUND`** | 签名复用缓存位于权限判定之后，绝不绕过下架/私有校验 |

## 4. Prometheus 签名复用与封面 304 验证

- Stage 2 结束后采集 `http://127.0.0.1:18089/actuator/prometheus`：
  ```text
  mangashelf_media_presign_requests_total{profile="immutable_image",result="reuse"} 2639.0
  mangashelf_media_presign_requests_total{profile="immutable_image",result="sign"} 41.0
  mangashelf_media_presign_requests_total{profile="immutable_subtitle",result="reuse"} 39.0
  mangashelf_media_presign_requests_total{profile="immutable_subtitle",result="sign"} 1.0
  mangashelf_media_presign_requests_total{profile="immutable_video",result="reuse"} 39.0
  mangashelf_media_presign_requests_total{profile="immutable_video",result="sign"} 1.0
  ```
  合计 **2,717 次复用 / 43 次重新签发（复用率 98.44%）**。
- `/api/v1/bangumi-covers/{subjectId}` 条件校验由 `BangumiCoverCacheTest` 覆盖：首次返回强 `ETag` 与 `Cache-Control: public, max-age=604800`，携带强/弱 `If-None-Match` 返回 `304 Not Modified`（`0 B`），ETag 不匹配时回源返回 `200 OK`。

## 5. 回归验证与已知限制

- **前端构建**：`npm --prefix web run build`（`tsc -b && vite build`）通过。
- **后端全量测试**：`./server/mvnw test` **58/58 通过**（0 失败 / 0 错误 / 0 跳过，43.2s；含新增 `PresignServiceTest` 2 项、`BangumiCoverCacheTest` 1 项，以及既有 55 项单测与真实 PostgreSQL/RabbitMQ 集成测试）。
- **技术取舍与限制**：
  1. 预签名 URL 复用窗口设为 `TTL`（默认 600s）的前半段（300s），已下架/改私有内容立即被 API 拒绝新签发（404），已发出的短期链接最迟在剩余 TTL 内自然过期——与 S3 预签名机制本身的安全语义一致（DESIGN 18.1）。
  2. 多实例部署下进程内 `PresignService` 复用表不跨节点同步，同一用户命中不同副本仍获得有效签名，仅跨副本首请求各自签发一次；后续如需多实例跨副本完全同 URL，可将 `S3Presigner` 改为固定时间桶时钟或接入共享存储。
  3. 本次实测在本机环回（JVM + Docker SeaweedFS）执行，反映的是链路正确性、字节节省与消除 N+1/重签后的相对收益；公网或高丢包移动端网络下，浏览器缓存命中与 2 页预加载带来的体感首图/翻页延迟收益将显著大于本地环回。
