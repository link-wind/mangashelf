# 设计：漫画规则源引擎（M23）

## 架构

沿用动画侧模式（RuleEngine + external_sources.rules）：**源 = 规则数据**，
宿主 = 一个解释器。`MangaSource` 接口不变，作为内部稳定契约。

```
MangaSourceRegistry
├── 代码源 Bean：mangadex（多端点聚合+桥接）、bilibili（HomeHot JSON）
└── 规则源（V15 manga_source_rules 表，启动时加载）：tencent …后续拷贝漫画等
      └── RuleMangaSource implements MangaSource
            └── MangaRuleEngine（HTML jsoup / API JSONPath / 解码钩子）
```

## 规则 JSON schema（闭合集合，不执行 JS）

```jsonc
{
  "headers": { "User-Agent": "…", "Referer": "…" },
  "rank": {
    "url": "https://m.ac.qq.com/", "mode": "html",
    "list": "div.rank-container a.comic-link",
    "fields": {
      "id":    { "attr": "data-id" },
      "title": { "select": "strong.comic-title" },
      "cover": { "select": "img", "attr": "src" }
    }
  },
  "search": {
    "url": "https://m.ac.qq.com/search/smart?word={query}", "mode": "api",
    "list": "$.data[*]",
    "fields": { "id": "$.id", "title": "$.title", "altTitle": "$.artist_name" }
  },
  "detail": {
    "url": "https://m.ac.qq.com/comic/index/id/{id}", "mode": "html",
    "meta": {
      "title":       { "select": "meta[property=og:title]", "attr": "content" },
      "description": { "select": "meta[property=og:description]", "attr": "content" },
      "cover":       { "select": "meta[property=og:image]", "attr": "content" }
    },
    "chapters": {
      "list": "a.chapter-link",
      "id":    { "attr": "data-cid", "template": "{mangaId}-{v}" },
      "label": { "attr": "data-seq" },
      "title": { "select": "p.chapter-title" }
    },
    "chapterSort": "numeric-asc",
    "chapterPages": -1
  },
  "pages": {
    "mode": "decode",
    "decode": "tencent-plain",
    "url": "https://m.ac.qq.com/chapter/index/id/{comicId}/cid/{cid}?style=plain&qimei=mangashelf-3a7f",
    "chapterIdTemplate": "{comicId}-{cid}",
    "list": "$.picture[*].url"
  }
}
```

- 字段提取：HTML 模式 `{select?, attr?, text?}`；API 模式 JSONPath。
- `mode: "decode"` 的 pages 走命名钩子；`tencent-plain` 钩子实现
  nonce 求值（字符白名单递归下降：数字/括号/!/+,-,*,/,%,&,^,|/Math.pow）
  + 区间删除 + base64，实测样本见 TencentNonceDecoderTest。
- 章节 ID 复合格式 `{comicId}-{cid}`：模板拆分为变量供 URL 使用。

## 数据库（V15）

```sql
CREATE TABLE manga_source_rules (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    note        TEXT NOT NULL DEFAULT '',
    rules       JSONB NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 种子：腾讯动漫
```

注册表启动时（ApplicationReadyEvent）加载 enabled 规则行构建 RuleMangaSource；
代码源 Bean 始终在前。

## 探测实测（2026-11-09/10）

| 源 | 结论 |
|---|---|
| 腾讯动漫 | 全链路匿名可用：RANK 首页 SSR、SEARCH /search/smart JSON、DETAIL 作品页 SSR（1~2.5MB，1198 话直出）、READ style=plain 密文（nonce 求值+区间删除+base64 → 页图直链 manhua.acimg.cn，无防盗链） |
| 拷贝漫画 | 版本头破解（appVersion/last 自举 3.1.0）；update/newest、recs 可用；comic2 详情被「破解版」IP 封禁（1h 自动解除）、search 维护中——待解除后以规则行接入 |
| 哔哩漫画 | 匿名仅 HomeHot；详情/阅读需登录（code 99）+ WASM 图片解密，不做 |
| 快看漫画 | pweb 全接口 aegis answer 挑战（须真实浏览器执行 JS），不做 |
| 动漫之家/包子/漫画柜 | 本机网络全不可达 |

## 决策记录

- 2026-11-10（M28）：接入拷贝漫画（V17，RANK-only）。从官方 APK 3.1.0 的
  Flutter AOT 快照（libapp.so）提取当前主力 API 域名 api.copy202602.com 与
  完整 /api/v3/ 路径清单；210 封禁跟 IP 走不跟域名走，详情/搜索暂不可用，
  ranks/update/newest/recs 匿名可用。新增规则级 bootstrapVersion（版本自举：
  GET appVersion/last 换最新版本号注入 version/appVersion 头，缓存 1h）；
  无 detail/pages 节的源条目自动转站外（externalUrl + readable=false）。
  聚合验证：综合榜来源 bilibili+copymanga+tencent 三源。
  上游 ranks 延迟抖动大（1~12s、偶发 503），discovery 单源超时 15→20s。

- 2026-11-10：按用户反馈改为规则驱动架构；TencentComicsSource 一次性实现废弃，
  解码逻辑保留为 `tencent-plain` 钩子；拷贝漫画探明形态、暂缓接入。
- 2026-11-09：腾讯动漫选定首个全能力中文规则源；榜单不做跨源桥接（自身可读）。

## 决策记录（续）

- 2026-11-10：nonce eval 表达式每次请求随机变化，实测样式池含 charCodeAt、
  Math 函数族、~~按位非、前导点小数、parseInt、document.getElementsByTagName
  （恒真）、substring（结果数字强转）、三元+比较、大数字 token（需 long）。
  求值器按白名单全覆盖，真实请求连续 8/8 解码成功；新样式显式报 502 带原始
  表达式，按日志补。
- 2026-11-10：付费章节官方返回 1 页试读（与官方站行为一致，正常展示）；完全
  锁定（0 页）显式报错。阅读器页图改为直连优先 + data-original 代理回退一次
  （修复双重代理缺陷）；图片代理缓存目录在 WORK_ROOT 缺省时曾落到只读根
  （M16 遗留），默认改 /tmp/mangashelf-work。
- 2026-11-10：双页模式补进度上报（spread→页码换算，3s 节流），M22 期间仅
  单页滚动上报。
