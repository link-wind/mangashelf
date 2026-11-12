## Why

拷贝漫画公开章节此前仍走浏览器解析。浏览器在首屏看到 1 张长图后就返回成功，
而 v3 章节接口实际声明 `size=10`，导致阅读器只显示第 1 页、后续 9 页缺失；
单张长图也让页级按需加载退化成一次加载整章。该结果还被磁盘缓存复用，
用户会反复看到不完整章节。

## What Changes

- 新增拷贝漫画 v3 章节图片 HTTP 读取，匿名请求
  `/comic/{pathWord}/chapter/{uuid}?platform=3`。
- 校验 `chapter.size`、`chapter.contents` 数量、章节归属和图片 URL；声明页数与
  实际图片数不一致时返回 `COPYMANGA_READER_INVALID`。
- 公开章节不再启动 Python/Chromium；只有明确要求登录且已配置登录态时，才保留
  浏览器回退。
- 浏览器页图收集改为持续滚动合并，避免首屏 1 张即提前成功。
- 阅读器按可视位置加载页图；图片就绪后再补记进度，修复跳页后进度停在旧页。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `manga-sources`：拷贝漫画公开章节图片改为 v3 HTTP，并明确完整性校验与受限章节错误。

## Impact

仅影响拷贝漫画 Java 源适配、Python 浏览器兜底、阅读器进度保存、验证脚本和文档；
章节 ID、前端路由、数据库结构不变。
