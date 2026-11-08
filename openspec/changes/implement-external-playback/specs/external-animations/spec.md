## MODIFIED Requirements

### Requirement: 外部集播放

外部集默认通过站内播放器播放：作品可见者触发解析任务后，由前端 hls.js 直连
解析出的视频地址播放，不经平台服务器转发。

#### Scenario: 解析并播放外部集

- **WHEN** 作品可见者对已绑定外部集发起解析（POST resolve）
- **THEN** 平台创建 external.resolve 任务，Worker 以无头浏览器加载剧集页并嗅探
  视频直链，成功后写入 external_episodes.resolved_url 与 resolved_at
- **AND** 前端获取到新鲜（4 分钟内）的解析结果后用 hls.js 直接播放，视频字节
  不经过平台服务器

#### Scenario: 解析失败显式降级

- **WHEN** 嗅探超时或未发现视频流
- **THEN** 任务 FAILED 并携带错误码，前端显式展示错误并提供"跳转原站"入口，
  不做静默回退

#### Scenario: 解析幂等

- **WHEN** 同一外部集 30 秒窗口内重复发起解析且已有 QUEUED/RUNNING 任务
- **THEN** 返回既有任务，不创建重复任务

#### Scenario: token 过期续播

- **WHEN** 播放中视频流失效（源站签名 token 过期）
- **THEN** 前端自动重新解析并从上次播放位置继续，不要求用户重新操作
