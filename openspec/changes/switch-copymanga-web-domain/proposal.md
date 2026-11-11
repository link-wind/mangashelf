# 提案：拷贝漫画网页源切换到主站 mangacopy.com

## Why

用户指定使用 `https://www.mangacopy.com/comics` 作为拷贝漫画网页源。
copy4000.com 是大陆镜像，主站结构一致且同样可直连。

## What Changes

- `CopyMangaBrowserSource.WEB` 改为 `https://www.mangacopy.com`；
- Python `manga.resolve` URL 白名单改为主站（保留 copy4000 兼容）；
- Cookie 域按实际主机生成，不再写死 copy4000；
- RANK 仍使用官方 API（api.copy202602.com/ranks），不变。
