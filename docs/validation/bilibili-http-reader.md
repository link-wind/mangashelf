# 哔哩 HTTP 阅读、按需加载与持久缓存验收

验证日期：2026-11-12（Asia/Shanghai）。变更：`replace-bilibili-capture-with-http`。
设计依据：`docs/DESIGN.md` §12、§14、§18–§21；HTTP 可行性和接口参数来源见
[前置验证](bilibili-http-images.md)。

## 实现结果

哔哩榜单、作品目录、章节索引和页图由 Java HTTP 客户端直接获取，正常阅读不再创建
Python/Chromium 截图会话、不上传截图、不轮询采集状态。章节清单就绪只代表完整页序
已确认，不代表图片已经全部下载。其他漫画源的浏览器解析保留。

阅读器以原始尺寸保留页位，滚动模式在视口上下 600px 范围开始取图；翻页模式读取当前
页组并预取下一组。恢复历史完成后才开始请求正文，切换页模式保留所在页。每页有加载
状态和独立重试；失败不会伪装为读取成功。

| 缓存 | 默认边界 |
| --- | --- |
| 作品目录 | 30 分钟 |
| 章节索引 | 10 分钟 |
| 原图 | 闲置 7 天 |
| 总磁盘容量 | 512 MiB，过期和最旧文件清理 |
| 下载 | 最多 4 个并发下载；同页请求合并，原子写入 |

磁盘只保存规范化目录、无签名索引和校验后的 JPEG/PNG；匿名指纹和临时签名只在内存。
页图大小、尺寸、格式、完整解码与索引一致性均检查后才缓存。站内页图要求登录，
响应为 `private, no-store`；索引版本变化拒绝旧地址，锁定章节不能借缓存继续读取。
目录授权最多存在上述目录缓存窗口，过期重新核对失败时不交付过期授权。

Compose 使用 API 专属的 `bilibili-http-cache` 卷。缓存及哔哩数据源只在 API 角色
初始化，Worker 不创建或清理该目录。默认显式直连，专用代理和 TTL/容量可配置，
上游失败不自动切换网络或回退截图。

## 真实环境与结果

本地入口 `http://localhost:5173`，原生 Java API/Worker，真实 PostgreSQL、RabbitMQ、
SeaweedFS。隔离 Docker 项目 `mangashelf-bili-check`，入口 `http://localhost:15173`，
保留原数据库、对象存储和消息队列卷，重建 API/Worker/Web。Docker 全局内存上限
4 GiB，API 1,664 MiB、Worker 512 MiB；两套环境关闭可选 Embedding，不涉及模型调用。

使用 `scripts/verify-bilibili-reader.py` 经真实 UI 注册临时测试用户，完成以下链路。
UI 验收会启动测试浏览器，截图仅用于检查阅读界面；上游目录和图片全部由 Java HTTP
获取。容器进程检查时 API、Worker 均只有 Java 进程，没有生产 Chromium 子进程。

| 场景 | 本地及隔离 Docker 实测 |
| --- | --- |
| 首页 → 哔哩榜单 → 航海王 → 第 004 话 | 目录 1,199 话，其中 196 话公开；章节清单 20 页及对应尺寸，无截图会话字段 |
| 首次按需加载 | 最终脚本记录发起的请求而非只记录完成的响应；章首仅第 1、2、3 页，没有取整章 |
| 跳末页 | 无需下载中间页即可跳到第 20 页，浏览器完整解码 |
| 整章 HTTP 读取 | 《航海王》20/20 页完整解码，尺寸与索引一致、摘要各不相同；共 25,160,347 字节 |
| 第二个样本 | 《非人哉》`mc24442-209069` 经生产 Java 端点 9/9 页完整解码，尺寸一致 |
| 重复读取 | 已下载页返回 `X-MangaShelf-Cache: HIT`；最终整章复验 20 页均命中 |
| 首轮未缓存读取 | 本地整章检查 4 HIT / 16 MISS，Docker 2 HIT / 18 MISS；此前 UI 已读章首及末页，部分预取可能取消，命中数不作性能指标 |
| 页级失败 | 仅对测试浏览器第 20 页注入 502；出现明确错误与「重试第 20 页」，解除注入后重试成功 |
| 历史续读 | 历史「继续阅读」恢复第 20 页；发起请求仅第 20 页；服务端 PostgreSQL 进度为 20/20 |
| 切换单页 | 保留第 20 页，请求仅第 19、20 页 |
| 单页刷新续读 | 恢复第 20 页，最终两套环境均只请求第 18、19、20 页，没有请求章首 |
| 窄屏 | 390px 宽度无水平溢出，末页仍可解码；另检查 1280px 界面截图 |
| 权限与版本 | 未登录页图 401/403；锁定章节清单 403；错误索引版本 409，错误响应同样禁止缓存 |
| 本地重启 | 停止并重启 API/Worker 后，末页仍为 HIT 且可解码 |
| 容器重建与上游不可用 | 保留缓存卷，强制重建 API 并临时指定不可连接的专用代理；榜单实时请求对照返回 502，但有效章节清单与第 20 页仍可读取并返回 HIT |
| 恢复正常配置 | 清除测试代理并再次重建 API；重跑完整 UI、20 页及 9 页样本全部通过 |
| 部署状态 | 最终六服务均运行，依赖健康，API 健康为 UP；API/Worker 无 OOM、无重启 |

本轮真实验收修复了两处问题：匿名指纹合法的 `/` 字符被原校验误拒绝；Worker 不应
在 API 的缓存路径初始化读取服务。已分别补入指纹测试及角色限定，并重新构建和部署。

## 回归与复现

- Java `./mvnw -q package`：30 项测试通过，0 失败/错误/跳过；新增 7 项覆盖真实本地
  HTTP 响应、指纹刷新边界、索引不预取、磁盘重启、并发合并、版本、授权过期、
  损坏图片、清理与 URL 白名单。受控上游测试不替代上述真实官方接口验证。
- Web `npm run build`：TypeScript 与 Vite 生产构建通过，保留既有大于 500 kB 的包体警告。
- UI 脚本语法、本变更及 `manga-sources` 的 OpenSpec 严格校验、
  `git diff --check` 与新增文件空白检查通过；已同步规范并归档。
- 补充运行全库 `openspec validate --all --strict`：10 项通过，既有且本轮未修改的
  `external-animations` 不通过，原因是 5 条历史需求缺少 SHALL/MUST 关键字，
  警告在严格模式下视为失败；未扩展修改无关规范。

```bash
processing/.venv/bin/python scripts/verify-bilibili-reader.py \
  --base http://localhost:5173 --screenshot /tmp/mangashelf-http-reader-local.png
processing/.venv/bin/python scripts/verify-bilibili-reader.py \
  --base http://localhost:15173 --screenshot /tmp/mangashelf-http-reader-docker.png
processing/.venv/bin/python scripts/verify-bilibili-reader.py \
  --base http://localhost:15173 --cache-only
```

`--cache-only` 要求同一环境已完成完整脚本，且目录、索引与末页缓存仍有效。输出为脱敏
JSONL，任何断言失败均非零退出。测试代理仅用于上述受控验证，最终已恢复显式直连。

## 已知边界

- 官方非稳定接口仅实测上述两个公开样本，没有覆盖全部作品、长期限流和并发压测。
  需要登录、购买、解锁的章节仍去原站，不使用个人哔哩登录态。
- 首次原图读取仍受网络影响；整话约 25 MB 的样本说明按需加载和容量清理必须保留，
  本次不声称固定延迟或加速比。
- 旧截图对象保留，未迁移或删除。独立 Python 截图函数及其历史测试仍存在，但没有
  Java 生产阅读入口调用；其他浏览器源仍需要镜像中的 Python/Chromium。
- 没有新增表、数据库迁移或改变队列协议；本次真实 PostgreSQL 验证针对登录和阅读进度，
  未把已有上传、OCR 或消息一致性的历史验收表述为本轮重新完成。
