# 哔哩公开章节纯 HTTP 取图验证

验证日期：2026-11-12（Asia/Shanghai）。变更：`verify-bilibili-http-images`。

## 结论

**两个既有公开章节均可在完全不启动浏览器、不截图、不使用个人登录态的情况下，通过 HTTP 取得整章普通 JPEG。**

《航海王》第 004 话通过 20/20 页，《非人哉》样本通过 9/9 页。所有图片均完成 Pillow `load()`，落到系统临时目录后再次打开并完整解码；按官方索引顺序编号，核对页数、尺寸和 SHA-256，没有缺页或重复图片。临时图片在验证结束后删除。

这是匿名公开样本的可行性证据，不代表所有作品、所有网络或所有未来接口版本均可用。现有生产阅读器仍使用原采集实现，本变更仅提供验证脚本和记录。

## 实际链路

1. 用直连 HTTP 请求公开的 `https://api.bilibili.com/x/frontend/finger/spi`，取得临时匿名指纹。
2. 在内存中将指纹作为 `buvid3`、`buvid4` 发送给漫画 API；不读取个人浏览器、`SESSDATA` 或用户会话。
3. `POST /twirp/comic.v1.Comic/GetImageIndex`，使用 Android 参数及 `epId` 请求体，取得 `data.images`。本次成功请求无需 App Key，也未生成网页 `ultra_sign`。
4. 按 `images` 的原顺序逐页调用 `POST /twirp/comic.v1.Comic/ImageToken`，将索引路径换成当前有效的 `complete_url`。
5. 通过 HTTP 下载图片，逐张完整解码；将实际尺寸与索引中的 `x`、`y` 核对。签名 URL 和令牌仅在内存中使用，不写日志或文档。

本次参数组合为 `device=android`、`platform=android`、`mobi_app=android_comic`、`version=4.13.0`、`build=36413002`、`channel=bilicomic`。这里是被实测接受的上游请求参数，不是 MangaShelf 构建或依赖版本声明。

## 样本和结果

环境：本机 macOS、Python 3.14.6、项目现有 Pillow；纯 HTTP 模式仅使用 Pillow 和 Python 标准库，未导入 Playwright 或启动 Chromium。最终两章验证同时运行，因此下表耗时只是本机本次观测，不作性能基准或加速比承诺。

| 样本 | 官方章节标识 | 已知总页数 / 下载并解码 | 返回格式 | 下载字节数 | 本次耗时 | 末页尺寸 |
| --- | --- | --- | --- | --- | --- | --- |
| 《航海王》第 004 话 | `mc24742/218098` | 20 / 20 | 全部 JPEG | 25,160,347 | 11.39 秒 | 1600 × 2336 |
| 《非人哉》公开样本 | `mc24442/209069` | 9 / 9 | 全部 JPEG | 956,494 | 3.40 秒 | 1063 × 894 |

《航海王》总页数同时由本次原站阅读器 HUD 确认为 20P；《非人哉》的已知 9 页来自既有 M35 样本记录。本次 HTTP 索引分别返回 20、9 条，所有条目逐一取得图片。严格脚本还核对了页码从 1 连续到末页、每页摘要唯一，以及总字节数与逐页之和相等。

末页 SHA-256：

- 《航海王》：`f517ba052ad05a03678557d8d75c2cf4251eea08d71bd3ef0b027990938ef5c4`
- 《非人哉》：`9cf044da57051d6e40f837765aca596b2f21c623e2d679c33c6ba85655d825c1`

## 为什么前期探测失败不能直接归因于签名

本次对照观察：

| 请求方式 | 实测 |
| --- | --- |
| 直连、Android 参数、不初始化匿名指纹 | `code=99`，未取得索引 |
| 直连、初始化匿名指纹、网页参数 | `code=99`，未取得索引 |
| 直连、初始化匿名指纹、Android 参数 | `code=0`，取得完整索引和逐页令牌，最终两章全通过 |
| 本机系统代理路径 | 指纹与 API 探测出现 TLS/SSL 错误；本次未验证整章下载成功 |

因此，前期把不同参数、指纹或网络条件下的失败统称为「缺少新签名」证据不足。上述对照只说明本次环境中的结果，不能保证初始化指纹在所有环境都是必要或充分条件。

正常网页阅读器是另一条链路：观察到 `GetImageIndex` 请求有 `m2`，`ImageToken` 有 `m1`，查询参数包含 `ultra_sign`；其响应中的 `data` 可能为空，实际载荷位于 `bytesData`。同一章前几页返回可解码 AVIF，后续观察到非普通图片字节和 WASM 资源。只重放这些网页请求、只检查 `code=0` 或只拿到前几页，都不能证明 HTTP 整章可用。

本次成功选择了返回有效 `data` 的 Android 参数链路，取得的全部页图都是普通 JPEG，没有移植网页签名、响应封装或 CPX 解码算法。

## 复现

在仓库根目录使用现有 processing 虚拟环境：

```bash
processing/.venv/bin/python scripts/check-bilibili-http.py --chapter 004 --network direct
processing/.venv/bin/python scripts/check-bilibili-http.py --chapter nonhuman --network direct
```

标准输出为脱敏 JSONL。成功必须以 `complete` 结束，并满足 `expected_pages == decoded_pages`、`browser_started: false`、`screenshots: 0`。任一步失败均非零退出，没有自动切换代理或浏览器回退。

需要重现网页请求观察时，可单独运行：

```bash
processing/.venv/bin/python scripts/check-bilibili-http.py --mode observe --chapter 004
```

`observe` 明确会启动匿名 Chromium，只做网络观察、确认页数与跳到末页，不调用截图；观察结束后关闭浏览器，再尝试 HTTP 重放。它不属于上表的纯 HTTP 成功路径。`--network` 控制纯 HTTP 模式，观察模式沿用既有浏览器代理配置。

## 来源与限制

- 接口参数的调查参考 `bandcomic/RESTful-bilibili-manga-api`，固定提交 `a5a8145eaf3f66e2934a29a76d3df5f8f03f4323`，主要为 `cloud-functions/backend.py`；已核对其 MIT 许可证。本次未引入该服务或复制其下载器、签名、会话和加密实现，验证脚本独立编写。
- 来源地址：`https://github.com/bandcomic/RESTful-bilibili-manga-api/tree/a5a8145eaf3f66e2934a29a76d3df5f8f03f4323`。
- 仅验证上述两个匿名公开样本，不处理需要购买或解锁的内容；HTTP 路径不因失败自动使用个人账号。
- 上游非承诺稳定的接口，仍可能改参数、限流或失效；未做并发压测和长期稳定性试验。
- 未验证容器内的直连、代理或图片出口策略；正式接入前需要单独验证部署环境。
- 原始 JPEG 比当前截图大，本次《航海王》整章约 25 MB；正式接入需另定按需读取、缓存和存储策略，不能只替换网络调用而忽略成本。
- 本变更没有生产缓存、对象存储、权限或状态迁移，因此没有重跑 PostgreSQL／RabbitMQ／全栈验收；正式替换需按既有要求另行验证。

## 校验

- 两个真实章节各完成两次 HTTP 整章下载；上表保留最终一次的脱敏结果。
- 脚本语法编译与命令行帮助通过；逐页摘要、连续页序、总字节数及无浏览器标志的结果核对通过。
- 诊断输出检查未包含完整 URL、Cookie、Authorization 或个人登录态。
- OpenSpec 严格校验、`git diff --check` 与新增文件空白检查通过。
