# 设计：长条漫自动滚动

- 探测：`new Image()` 预加载 `data.pages` 前 2 张，取 `naturalHeight/naturalWidth`；
  任一张 ≥ 3 即长条漫。加载失败视为未知（不强制）。
- 生效模式：`effectiveMode = manualMode ?? (longStrip ? 'single' : settings.pageMode)`。
  - `manualMode` 仅在当前章节内有效，换章重置；
  - 用户手动选页模式时同时写入全局设置（作为普通作品的默认偏好）。
- 所有翻页/进度/渲染判断从 `settings.pageMode` 改为 `effectiveMode`。
- 页头 chip 提示，避免用户误以为设置失效。
- 实测：拷贝 664×4897 → 滚动；腾讯 800×1132 → 保持双页。
