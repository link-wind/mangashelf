## Why

拷贝漫画的详情与章节目录当前依赖浏览器启动、脚本执行和 DOM 展开，目录缓存失效后等待明显。按照 DESIGN 4、19、21 节，通过官网公开 HTTP 数据提供完整目录，并明确区分目录与图片的解析路径。

## What Changes

- 详情 HTML 与官网加密目录接口改为 Java HTTP 请求，按网页公开算法解码目录。
- 保留完整章节顺序、分组与现有章节 ID，校验缺失、重复和声明数量。
- 有界缓存详情与目录，失败明确报错，不自动回退浏览器。
- 搜索与章节图片沿用已有实现，阅读器继续按需加载页图。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `manga-sources`：新增拷贝漫画详情与完整目录不依赖浏览器的要求。

## Impact

涉及拷贝漫画 Java 源适配、少量测试、真实 HTTP 验证脚本与验收文档。API 结构与章节 ID 不变，复用现有 Jsoup 与 Java 加密库，不增加依赖。
