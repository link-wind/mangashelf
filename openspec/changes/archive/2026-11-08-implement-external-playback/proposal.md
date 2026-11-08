# 提案：外部动画站内播放（M7）

## 背景

M6 完成了外部动画的规则源搜索与剧集绑定，但播放是"跳转原站"。经真实站点解剖
（gugu.fan 模式实测：自有聚合 API + 浏览器 hls.js 直连采集 CDN）确认：

- 采集生态 CDN 的防盗链是**短时效签名 URL**（分钟级），不是 Referer 绑定；
- CORS 全开（`Access-Control-Allow-Origin: *`），浏览器可直连拉流；
- 因此"服务端解析 + 浏览器直连播放"可以同时保住：自有播放器、零服务器带宽、
  零安装（任何浏览器）。

## 变更内容

1. **Worker 解析任务** `external.resolve`：Playwright 无头 Chromium 打开剧集页，
   嗅探网络请求中的 m3u8/mp4 直链（对源站加密层透明，无需逐站逆向），结果落
   `external_episodes.resolved_url`（含 resolved_at 供时效判断）。
2. **API**：`POST /external-episodes/{id}/resolve`（入队，30 秒窗口幂等）+
   `GET /external-episodes/{id}/resolve`（查最新解析状态与结果）。
3. **前端**：外部集"播放"改为站内 hls.js 播放器（直连源站 CDN），token 过期/
   断流时自动重新解析并 seek 回原位置；续播位置本地保存；保留跳转原站兜底。

## 不做的事

- 不做流代理与 playlist 改写（对 CORS 开放源不必要；真 Referer 校验源未来按需加）。
- 不做无头浏览器以外的解析器替换（yt-dlp 对目标生态实测无效，见 docs/validation/m7.md）。
- 不引入 WebView/常驻浏览器进程；解析按需启动、完成即释放。
- 不做外部集的服务端观看进度（本期续播位置存本地，服务端进度留待与动画进度体系统一）。

## 风险

- Chromium 增加镜像体积（约 +400MB）与解析期间内存（单次 ~300MB，完成即释放）。
- 源站结构变化导致嗅探超时 → 显式 FAILED + 保留跳转兜底，不静默降级。
