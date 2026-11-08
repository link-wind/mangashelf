# 设计：外部动画站内播放（M7）

## D1. 架构（gugu.fan 模式复刻）

```
用户点播放 → POST /external-episodes/{id}/resolve
  → jobs(kind=external.resolve, external_episode_id) → content.process 队列
  → Worker：ExternalResolveHandler → PythonTaskRunner(external.resolve)
      Playwright 加载剧集页 → 监听网络层捕获 m3u8/mp4 → result.json
  → handler 写 external_episodes.resolved_url / resolved_at / resolved_kind
前端轮询 GET /external-episodes/{id}/resolve
  → 拿到新鲜 playUrl → hls.js 直连源站 CDN 播放（不经 MangaShelf 服务器）
  → token 过期（hls.js error）→ 自动重新 resolve → seek 回进度
```

解析与播放分离：解析在 Worker（有浏览器），播放在前端（零带宽）。
API 角色全程不接触视频流。

## D2. 数据模型（V8）

- `jobs` 扩展 `external.resolve` 种类，新增 `external_episode_id uuid REFERENCES
  external_episodes(id)`；`jobs_target_by_kind` 约束同步扩展（互斥模式不变）。
- `external_episodes` 新增 `resolved_url text`、`resolved_kind text`（hls|mp4）、
  `resolved_at timestamptz`——解析结果是外部集的领域状态，落集上行而非任务表。
- 索引 `idx_jobs_external_episode (external_episode_id, created_at)`。

## D3. 任务协议扩展

`TaskSpec` 增加 `params`（可选 JSON 对象，snake_case 键）。协议向后兼容：
既有任务无 params，Python 端 `spec.get("params", {})` 读取。
`external.resolve` 的 params：`episode_url`（必需）、`referer`（可选，来自规则）、
`timeout_seconds`（服务端下发，默认 30）。

安全边界（AGENTS.md）：`episode_url` 由 Java Worker 从 `external_episodes` 读取后
下发，Python 不接收用户直接提交的任意 URL；任务无 DB/对象存储凭据。

## D4. 幂等与时效

- 幂等键：`dedup_key = "external.resolve:" + episodeId + ":" + epochSecond/30`。
  30 秒窗口内的重复点击共享同一任务；窗口过后允许重新解析（token 过期是常态需求，
  与一次性处理任务的永久幂等不同，design 决策记录于此）。
- 去重查询：`findLatestJob(kind, externalEpisodeId)`，QUEUED/RUNNING 中直接复用。
- 新鲜度：API 层判定 `resolved_at` 距今 ≤ 4 分钟视为可播（源站 token 实测分钟级），
  过期则前端触发重新解析。4 分钟为保守值，不依赖源站承诺。

## D5. Python 嗅探任务

- Playwright sync API + Chromium headless，启动参数含
  `--autoplay-policy=no-user-gesture-required`（免交互自动起播）。
- 监听 `page.on("request")`，匹配 `\.m3u8(\?|$)|\.mp4(\?|$)`（忽略大小写），
  首个命中即返回；约 8 秒未命中时尝试点击常见播放按钮选择器后继续等待。
- 结果：`{"status":"SUCCEEDED","play_url":…,"play_kind":"hls"|"mp4"}` 或
  `{"status":"FAILED","error_code":"NO_VIDEO_FOUND",…}`。
- 超时 30s（可由 params 下调）；完成即关闭浏览器，无常驻进程。

## D6. 前端播放

- 引入 hls.js（Chrome 播 HLS 必需；Safari 原生支持，统一走 hls.js 简化分支）。
- 播放状态机：idle → resolving（轮询 2s）→ playing → stalled/error（自动重解析，
  保留 `currentTime` 恢复）。续播位置存 `localStorage`（`ms-ext-pos:{episodeId}`），
  每 10 秒落一次；服务端进度体系留待后续与动画统一。
- 失败显式化：解析 FAILED 时展示错误 + "跳转原站"按钮（M6 链路保留），不静默降级。
