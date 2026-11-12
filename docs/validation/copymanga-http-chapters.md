# 拷贝漫画 HTTP 目录验收

日期：2026-11-12，Asia/Shanghai。
变更：`replace-copymanga-chapters-with-http`。
依据：DESIGN §4、§19、§21。

> 后续修正：2026-11-12 起生产目录已改走官方 v3 API，网页加密目录仅保留为历史
> 验证记录；最新目录与图片结论见文末「v3 目录修正」「章节图片路径修正」。

## 范围与实现

详情与完整章节目录改为 Java HTTP，入口仍是
`GET /api/v1/manga-sources/copymanga/manga/{pathWord}`。
章节 ID 保持 `{pathWord}~{uuid}`，搜索与章节图片继续使用原来的浏览器解析。
本轮没有把图片解析改为 HTTP，也不将目录返回成功视为整话图片可读。

流程：

1. 固定请求官网 `https://www.copy4000.com/comic/{pathWord}`，解析标题、作者、封面和简介。
2. 从 HTML 取得公开目录参数，向同源 `/comicdetail/{pathWord}/chapters` 发送官网使用的目录请求头。
3. Java 按网页公开算法解码目录；不下载执行远程脚本，不启动 Python/Chromium。
4. 校验作品标识、所有分组的声明数量、章节 UUID/名称与全局唯一性，保留官网顺序。
5. 只缓存成功的不可变详情，30 分钟过期，最多 128 部作品，同作品并发合并。

请求限制为固定官网、禁止重定向、15 秒请求超时、4 MiB 响应与 10,000 章。
详情不使用个人 Cookie；目录参数不写入缓存或日志。内存缓存不跨进程重启，
重启后重新 HTTP 获取，不回退浏览器。失败或不完整目录不缓存。
上游返回 HTTP 200、但目录被解出为空章节时，只对同一官方地址立即重试一次；
仍为空则返回 502 `COPYMANGA_DIRECTORY_UNAVAILABLE`（“暂未返回公开目录”），
与解码/数量/重复校验失败区分，避免把上游风控波动误报为协议损坏。

## 真实目录结果

本地使用真实 PostgreSQL、RabbitMQ、SeaweedFS，原生 Java API/Worker；
容器使用隔离项目 `mangashelf-bili-check`，只重建更新 API/Worker，保留全部卷及其它服务。

| 样本 | 章节数 | 首章 / 末章 | 容器冷请求 | 容器重复请求 |
| --- | ---: | --- | ---: | ---: |
| 寶石魔女 `baoshimonv` | 39 | 第1话 / 第二季第11集 | 1.122 秒 | 0.007 秒 |
| 海贼王 `haizeiwang` | 402 | 1卷 / 第1197话 | 1.072 秒 | 0.007 秒 |
| 葬送的芙莉蓮 `zangsongdefulilian` | 153 | 第01话 / 葬送的芙莉莲公式书 | 1.040 秒 | 0.009 秒 |

重复返回与首次 JSON 完全相同，所有 ID 唯一，旧章节
`baoshimonv~0e62971e-3cfe-11f0-823d-fa163e02432f` 仍在目录中。
非法作品标识实测返回 400 `COPYMANGA_INVALID_ID`。
上述耗时是本次测量，不是性能承诺。

原生 Java 曾取得同样三份完整目录，冷请求分别为 1.160、1.281、1.043 秒，
重复请求为 0.014、0.012、0.010 秒。目录阶段每 100 毫秒采样 API 进程树，
未发现任何子进程。另有源适配测试确认详情阶段不调用浏览器服务。
容器目录验收结束后的 API 进程检查亦只有 Java，无 Python/Chromium。

**原生出口仍不稳定**：后续重启、清空内存缓存后，同样的代理配置出现了官网返回
空目录的情况，服务端按设计返回 502，不以旧数据或浏览器兜底。不能把先前一次
成功当作该出口已稳定。隔离容器当前三份目录和缓存验收通过；Codex 浏览器打开的是
`http://localhost:15173/manga-ext/baoshimonv?source=copymanga`，实际显示 39 话。
没有将原生 `5173` 的失败伪装成正常结果。

同日再次重建容器后，冷缓存得到空目录；显式恢复
`MANGASHELF_COPYMANGA_PROXY=http://host.docker.internal:7892` 后仍为空。
Playwright 经代理打开官网时，目录请求本身也收到 578 字节空目录，
因此不是仅靠 Java 请求头即可修复，也不能用验收浏览器作为成功证据。
当前有效的 39/402/153 章页面来自此前成功目录的 30 分钟内存缓存。
清除上游空目录误报后，服务端会明确提示稍后重试；缓存过期后的真实链路仍待
上游恢复公开目录后再验收。

## 阅读路径边界

从目录点击章节，原有 ID 及阅读路由保持可用。当前固定旧章节的匿名图片解析返回
`LOGIN_REQUIRED`；《葬送的芙莉蓮》首话的生产验收也未取得可用清单。
本轮没有完成拷贝漫画的完整图片/末页/续读验收。
一次独立浏览器探测曾提取到 1 个地址，不作为整话完整性的证据。

同时修正了已有错误传播：Python 任务把登录受限写入 `result.json` 并以非零退出，
Java 之前直接抛出进程错误，用户只能看到普通上游失败。现在先识别该明确业务结果，
返回 403 `COPYMANGA_LOGIN_REQUIRED`，提示配置拷贝登录态。容器真实页面已确认显示
对应提示，不展示空阅读器，也不记录/返回 Cookie。

全源按需加载组件本轮未修改。其既有条漫、跳页与续读验证见
[外部漫画按需加载验收](external-manga-lazy-loading.md)。

## 构建与测试

`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./mvnw -q package`
通过，35 项测试全部通过。

新增的少量关键场景：

- 本地真实 HTTP 服务验证官网请求头、公开参数含标点、全部分组、规范化详情、
  并发合并、有效缓存与不调用浏览器、不发送个人登录态。
- 目录缺失、重复、空列表、错误作品及非法 UUID 拒绝，失败不缓存。
- 空目录只重试一次；仍为空返回可区分的“上游暂未返回公开目录”错误。
- 缓存过期后，上游错误不被旧缓存遮蔽；恢复后可以刷新。
- 非法 ID、缺少公开参数、坏密文、无效 JSON、404/302 与非法代理明确失败。
- Python 写入 `LOGIN_REQUIRED` 后非零退出，Java 保留 403 业务语义且清理任务目录。

本轮未改前端组件、数据库结构或队列协议，不新增前端构建或事务一致性的完成声明。

## 2026-11-12 v3 目录修正

上文记录的网页加密目录 (`/comicdetail/{pathWord}/chapters`) 在相同出口下会返回
合法的空分组：`build.path_word` 正确，`groups.default.count=0`。这不是解码失败，
也不能靠增加网页请求头修复；真实 Playwright 请求同样得到 0 章节。

随后验证了拷贝漫画官方 v3 匿名 API，并将生产详情与目录切换到
`https://api.manga2026.xyz/api/v3`：

- 详情：`GET /comic2/{pathWord}?platform=3&request_id=...`，返回作品元数据与分组
  声明，不发送 Cookie。
- 目录：`GET /comic/{pathWord}/group/{groupPath}/chapters?platform=3&limit=100&offset=...`，
  每页校验 `results.total`、分组 `count` 和章节 UUID。
- 章节 ID 仍为 `{pathWord}~{uuid}`，缓存仍为 30 分钟、最多 128 部作品。

2026-11-12 使用真实 API 的 Java 验证结果：

| 样本 | 章节数 | 首章 / 末章 | 目录请求数（含详情） |
| --- | ---: | --- | ---: |
| 寶石魔女 `baoshimonv` | 39 | 第1话 / 第二季第11集 | 2 |
| 海贼王 `haizeiwang` | 402 | 1卷 / 第1197话 | 6 |
| 葬送的芙莉蓮 `zangsongdefulilian` | 153 | 第01话 / 葬送的芙莉莲公式书 | 3 |

旧章节 `baoshimonv~0e62971e-3cfe-11f0-823d-fa163e02432f` 仍在目录中。
该验证通过 `MANGASHELF_COPYMANGA_PROXY=http://127.0.0.1:7892` 完成，证明当前
出口下 v3 API 可用；不把它表述为所有网络出口永久可用。

本地 `http://localhost:5173` 冷请求为 1.138 / 2.802 / 1.685 秒，重复请求为
0.013 / 0.018 / 0.017 秒；隔离容器重建 API/Worker 后
`http://localhost:15173` 冷请求为 1.727 / 2.658 / 1.782 秒，重复请求为
0.009 / 0.012 / 0.012 秒，三份目录均完整通过。耗时是本次测量，不是性能承诺。

`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./mvnw -q package` 通过，36 项测试全部通过；`manga-sources` 规范严格校验通过，
OpenSpec 变更已归档为 `2026-11-12-fix-copymanga-directory-v3`。

目录阶段仍不启动 Python 或 Chromium。搜索和章节图片继续走既有浏览器路径；
本轮没有完成图片完整阅读、末页和续读验收，不能把目录成功等同于整话可读。
验证脚本 `scripts/verify-copymanga-http.py` 仍按原有详情/缓存/进程树断言执行。

### 章节图片路径修正

2026-11-12 继续探测了 v3 图片接口：

- `chapter2` 使用当前 3.0.9 协议头时返回 HTTP 200 / `code=200`，但 `results` 为空；
- 旧版 `COPY/3.0.0` 协议曾一次返回《寶石魔女》第 10 话 10 页，随后同一出口立即返回
  HTTP 210，提示疑似破解客户端并要求更新正版后等待 1 小时；
- 这不能作为稳定的匿名阅读路径，也不能把 210 当成登录失败。

继续检查发现浏览器源原先使用 `https://www.mangacopy.com`，而登录助手和实际
可渲染的章节页使用 `https://www.copy4000.com`。同一章节在 `mangacopy.com` 返回
0 页并触发登录提示，在 `copy4000.com` 匿名返回 1 张 699×5370 长图。v3 目录
仍显示 `size=10`，说明网页端可能把该话合并成一张长图，不能直接按 `size` 断言
网页页数。

已将浏览器源与 `processing.manga_login` 统一到 `https://www.copy4000.com`。
使用真实产品页面重新执行完整路径：

```text
directory baoshimonv 39
reader_manifest status=200 pages=1
reader pages=1 last_size=[699,5370]
progress page=1 source=copymanga
```

本地 `http://localhost:5173` 和重建后的隔离容器 `http://localhost:15173` 均通过
上述阅读、末页和进度保存断言。因此本次公开样本的匿名图片、末页渲染和进度保存
通过。旧协议 210 响应没有接入生产；受限章节仍会显示登录态提示，不伪造页图、
不跳过登录。

## 2026-11-12 章节图片 HTTP 化与按需加载修正

上一节的 `pages=1` 不是完整章节，而是浏览器首屏看到第 1 张长图后提前返回成功。
同一章节的 v3 匿名接口
`GET https://api.manga2026.xyz/api/v3/comic/{pathWord}/chapter/{uuid}?platform=3`
返回 `chapter.size=10` 与 10 个 `contents`：

```text
chapter.name=第10话  size=10  contents=10  is_long=true  is_lock=false
page 1   699x5370
page 10  699x6014
```

第 1 页和末页地址不同，确认不是“一章合并成一张长图”。新增
`CopyMangaHttpReaderService` 后，公开章节不再启动 Python/Chromium：

- 校验 `chapter.size` 在 1..1000，且 `chapter.contents` 长度与声明一致；
  1/10 或 9/10 都返回 502 `COPYMANGA_READER_INVALID`，不写入成功缓存。
- `code=210` 返回 502 `COPYMANGA_READER_RATE_LIMITED`；`is_lock=true` 且无内容
  返回 403 `COPYMANGA_LOGIN_REQUIRED`。只有后者且已配置登录态时才回退浏览器。
- 成功清单缓存 10 分钟、最多 256 章；图片 URL 只接受 HTTPS，不接收本机/私有
  IP 字面量。
- Python 浏览器兜底改为持续滚动合并，连续两轮到底且页数不增长才结束，避免再次
  首屏 1 张即成功。
- 阅读器在图片就绪事件后补记进度；跳到末页时不再因为页码先变化而漏保存。

2026-11-12 本地与容器验证结果：

```text
directory baoshimonv 39 / haizeiwang 402 / zangsongdefulilian 153
reader_manifest status=200 pages=10
reader_lazy_load initial_images=2 pages=10
reader pages=10 last_size=[699,6014]
progress page=10 source=copymanga
```

首屏只请求第 1、2 页，滚动到末页后才请求第 10 页，页级按需加载生效；本地
`http://localhost:5173` 与重建后的容器 `http://localhost:15173` 均通过。

`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./mvnw -q package`（含新增完整性、缓存、限流和登录错误测试）通过，
`npm run build` 通过；OpenSpec 变更
`2026-11-12-fix-copymanga-http-reader` 已归档。

已知限制：单张长图内部仍会一次下载整页，本次只实现章节页级按需加载；v3 章节
接口返回 210 时不会用匿名浏览器绕过，受限章节仍需配置登录态。

## 复现与配置

只测目录（标准库即可，无需启动验收浏览器）：

```bash
python3 scripts/verify-copymanga-http.py --base http://localhost:15173
```

原生环境可加 `--server-pid <API进程号>` 检查目录阶段的进程树。
验证目录页面及当前已知的权限错误：

```bash
processing/.venv/bin/python scripts/verify-copymanga-http.py \
  --base http://localhost:15173 --ui --reader-manga baoshimonv \
  --expect-login-required --screenshot /tmp/mangashelf-copy-http-login-required.png
```

`--ui` 使用独立验收浏览器操作产品页面，属于测试工具，不是生产目录实现。
2026-11-12 章节图片 HTTP 化后，成功路径可加 `--expect-pages 10`：

```bash
processing/.venv/bin/python scripts/verify-copymanga-http.py \
  --base http://localhost:15173 --ui --reader-manga baoshimonv \
  --expect-pages 10 --screenshot /tmp/mangashelf-copymanga-bao10-http.png
```

不加 `--expect-login-required` 时按完整页数、末页可见、懒加载和进度保存断言。
`--expect-login-required` 仅用于验证受限章节的明确错误，不再代表当前匿名样本。

`MANGASHELF_COPYMANGA_PROXY` 是独立 HTTP 配置，留空显式直连，不继承系统代理；
本轮本机配置 `http://127.0.0.1:7892`，容器配置
`http://host.docker.internal:7892`。公开章节图片与目录使用该 HTTP 配置；
搜索和受限章节的浏览器回退使用 `MANGASHELF_BROWSER_PROXY`。
代理地址必须是无凭据、带端口的 HTTP 地址。更换运行环境时需要显式配置可达出口；
不保证所有出口、作品和未来官网协议始终可用。

## 同日后续复验

- 容器目录继续命中缓存，Codex 浏览器保留了实际展示 39 话的详情页。
- 再次打开固定旧章节时，上游浏览器路径改为返回 200 和 1 张图，图片
  699×5370 解码成功。由于历史样本曾有 2 张图，本轮不能证明这一张就是完整章节；
  进度检查也没有取得该样本的记录。因此不将这次 200 记为完整阅读或续读通过，
  也不将前次 403 推断为该章节永久需要登录。`--expect-login-required` 是对应
  已观察失败状态的断言，随着上游结果变化可能不再成立。
- 当前变更及 `manga-sources` 主规范严格校验通过，新增文件空白检查和
  `git diff --check` 通过，变更已同步并归档。
- 全量主规范严格校验为 10 项通过、1 项失败：未修改的 `external-animations`
  有 5 个既有需求缺少 SHALL/MUST。该问题不属于本次目录变更，没有顺带修改。
- 随后将空目录单次重试与可区分错误补入实现，重新执行全量构建；最终测试数见下方
  最新构建记录。上游空目录本身没有被测试当成成功。
