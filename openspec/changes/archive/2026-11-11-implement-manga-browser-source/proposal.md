# 提案：漫画浏览器解析源（复制动画 Worker 模式，M29）

## Why

拷贝漫画的应用 API 对本机出口 IP 持续返回 210（匿名/破解客户端风控），但其
**网页版 copy4000.com 可直连**；章节页由前端 JS 用 contentKey 解密图片，
纯 HTTP 抓取只能拿到 0 页。动画侧已有成熟方案：Worker 无头浏览器执行真实
JS，前端直连源站 CDN。漫画复用同一模式即可站内阅读。

## What Changes

1. 新增 Python 任务 `manga.resolve`（Playwright）：
   - `detail`：打开 `/comic/{path}`，点「目錄」展开章节并解析标题/封面/简介/章节。
   - `chapter`：打开章节页，滚动触发 lazysizes，收集解密后的页图 URL；
     游客 `contentKey` 为空时显式返回 `LOGIN_REQUIRED`。
   - 仅允许 `https://www.copy4000.com/` 站内 URL（不接受任意 URL）。
2. 新增 `MangaBrowserResolveService`：固定 Python 入口 + 单并发信号量 +
   结果缓存（章节 10 分钟 / 详情 30 分钟）+ 任务目录用后即删（task.json 含 Cookie）。
3. 新增代码源 `CopyMangaBrowserSource`（id=copymanga，RANK/DETAIL/READ）：
   - RANK 走公开 API（版本自举头）；封面去掉缩略图后缀取原图。
   - DETAIL/READ 走浏览器解析，章节 ID 为 `{pathWord}~{uuid}`。
   - 登录态来自 `MANGASHELF_COPYMANGA_COOKIE` 或
     `MANGASHELF_COPYMANGA_STORAGE_STATE`。
4. V18 删除 V17 的 RANK-only 规则行；注册表改为代码源优先（同名规则行忽略）。
5. 新增登录助手 `processing.manga_login`：本机打开可见浏览器登录一次，
   保存 storage_state（不进入 Worker）。

## 不做

- 不破解付费/锁定章节（无页图时显式报错）。
- 不做验证码/登录自动化；登录由用户手动完成一次。
