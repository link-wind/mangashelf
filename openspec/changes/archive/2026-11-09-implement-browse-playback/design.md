# 设计：浏览态播放（M20）

## D1. 归属模型

`external_episodes` 的归属二选一：`work_id` 或 `catalog_item_id`（CHECK 约束）。
唯一性拆成两个部分唯一索引，避免 NULL 参与唯一键失效。

## D2. 懒生成

首次访问某目录条目的剧集时，按 `play_groups` 解析并写入（挂 catalog_item_id）。
幂等：部分索引上的 ON CONFLICT DO UPDATE。

## D3. 解析与进度

解析复用 `external.resolve`（目标仍是 episode id），可见性：挂作品的按作品可见性，
挂目录条目的登录用户即可解析。浏览态进度仅 localStorage；入库后走服务端进度。

## D4. 收编

`materialize` 先建作品，再 `adoptEpisodes(catalogItemId, workId)`；若返回 0 才按原有
逻辑解析生成。避免同一部作品在浏览与入库间重复解析。
