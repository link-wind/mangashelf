-- V36: 番剧与漫画统一地区分级（JP > CN > KR > OVERSEAS）与多源评分/推荐排序预计算字段

ALTER TABLE bangumi_subjects
    ADD COLUMN IF NOT EXISTS rating_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bgm_rank integer;

ALTER TABLE external_catalog_items
    ADD COLUMN IF NOT EXISTS region varchar(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS region_rank smallint NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS region_confidence smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source_score numeric(3,1),
    ADD COLUMN IF NOT EXISTS source_hits integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS external_rating numeric(3,1),
    ADD COLUMN IF NOT EXISTS external_rating_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bayesian_rating numeric(4,2),
    ADD COLUMN IF NOT EXISTS recommend_score double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS enriched_at timestamptz;

ALTER TABLE external_catalog_items
    DROP CONSTRAINT IF EXISTS chk_ext_catalog_region,
    ADD CONSTRAINT chk_ext_catalog_region CHECK (region IN ('JP', 'CN', 'KR', 'OVERSEAS', 'UNKNOWN')),
    DROP CONSTRAINT IF EXISTS chk_ext_catalog_region_rank,
    ADD CONSTRAINT chk_ext_catalog_region_rank CHECK (region_rank BETWEEN 1 AND 5);

ALTER TABLE manga_catalog_items
    ADD COLUMN IF NOT EXISTS region varchar(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS region_rank smallint NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS region_confidence smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS popularity_score double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rating numeric(3,1),
    ADD COLUMN IF NOT EXISTS rating_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bayesian_rating numeric(4,2),
    ADD COLUMN IF NOT EXISTS recommend_score double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS enriched_at timestamptz;

ALTER TABLE manga_catalog_items
    DROP CONSTRAINT IF EXISTS chk_manga_catalog_region,
    ADD CONSTRAINT chk_manga_catalog_region CHECK (region IN ('JP', 'CN', 'KR', 'OVERSEAS', 'UNKNOWN')),
    DROP CONSTRAINT IF EXISTS chk_manga_catalog_region_rank,
    ADD CONSTRAINT chk_manga_catalog_region_rank CHECK (region_rank BETWEEN 1 AND 5);

-- 1) 番剧存量地区与评分初始化回填（秒级生效，后续由同步与批量回填任务持续提纯）
UPDATE external_catalog_items e
   SET region = CASE
           WHEN e.category_id IN (29, 32) THEN 'CN'
           WHEN e.category_id IN (31, 33) THEN 'OVERSEAS'
           WHEN e.category_id = 30 AND (e.tags && ARRAY['韩国', '韩漫', '韩国动画'] OR e.name ~ '[가-힣]') THEN 'KR'
           WHEN e.category_id = 30 THEN 'JP'
           ELSE 'UNKNOWN'
       END,
       region_rank = CASE
           WHEN e.category_id IN (29, 32) THEN 2
           WHEN e.category_id IN (31, 33) THEN 4
           WHEN e.category_id = 30 AND (e.tags && ARRAY['韩国', '韩漫', '韩国动画'] OR e.name ~ '[가-힣]') THEN 3
           WHEN e.category_id = 30 THEN 1
           ELSE 5
       END,
       region_confidence = CASE
           WHEN e.category_id = 30 AND (e.tags && ARRAY['韩国', '韩漫', '韩国动画'] OR e.name ~ '[가-힣]') THEN 20
           WHEN e.category_id IN (29, 30, 31, 32, 33) THEN 10
           ELSE 0
       END
 WHERE e.region = 'UNKNOWN';

-- 已关联 Bangumi 条目的番剧优先用 Bangumi 标签/原名修正地区与写入初始外部评分
UPDATE external_catalog_items e
   SET region = CASE
           WHEN b.tags && ARRAY['国产', '中国', '国漫', '中国大陆', '港台'] THEN 'CN'
           WHEN b.tags && ARRAY['韩国', '韩漫'] OR b.name ~ '[가-힣]' THEN 'KR'
           WHEN b.tags && ARRAY['欧美', '美国', '英国', '法国'] THEN 'OVERSEAS'
           WHEN b.tags && ARRAY['日本'] OR b.name ~ '[ぁ-ヿ]' THEN 'JP'
           ELSE e.region
       END,
       region_rank = CASE
           WHEN b.tags && ARRAY['国产', '中国', '国漫', '中国大陆', '港台'] THEN 2
           WHEN b.tags && ARRAY['韩国', '韩漫'] OR b.name ~ '[가-힣]' THEN 3
           WHEN b.tags && ARRAY['欧美', '美国', '英国', '法国'] THEN 4
           WHEN b.tags && ARRAY['日本'] OR b.name ~ '[ぁ-ヿ]' THEN 1
           ELSE e.region_rank
       END,
       region_confidence = CASE
           WHEN (b.tags && ARRAY['国产', '中国', '国漫', '中国大陆', '港台', '韩国', '韩漫', '欧美', '美国', '英国', '法国', '日本'])
                OR b.name ~ '[ぁ-ヿ가-힣]' THEN GREATEST(e.region_confidence, 30)
           ELSE e.region_confidence
       END,
       external_rating = COALESCE(e.external_rating, CASE WHEN b.rating IS NOT NULL AND b.rating > 0 THEN round(b.rating::numeric, 1) END),
       external_rating_count = GREATEST(e.external_rating_count,
           CASE WHEN b.rating_count > 0 THEN b.rating_count
                WHEN b.rating IS NOT NULL AND b.rating > 0 THEN (CASE WHEN b.in_calendar THEN 200 ELSE 100 END)
                ELSE 0 END)
  FROM bangumi_subjects b
 WHERE b.subject_id = e.bangumi_subject_id;

-- 初始化番剧贝叶斯评分与综合推荐分
UPDATE external_catalog_items e
   SET bayesian_rating = CASE
           WHEN e.external_rating IS NOT NULL AND e.external_rating > 0 THEN
               round((((GREATEST(e.external_rating_count, 10)::numeric / (GREATEST(e.external_rating_count, 10) + 50.0)) * e.external_rating)
                    + ((50.0 / (GREATEST(e.external_rating_count, 10) + 50.0)) * 7.0))::numeric, 2)
           ELSE NULL
       END,
       recommend_score = GREATEST(0.0,
           (CASE
               WHEN e.external_rating IS NOT NULL AND e.external_rating > 0 THEN
                   LEAST(55.0, GREATEST(0.0, (
                       (((GREATEST(e.external_rating_count, 10)::double precision / (GREATEST(e.external_rating_count, 10) + 50.0)) * e.external_rating::double precision)
                        + ((50.0 / (GREATEST(e.external_rating_count, 10) + 50.0)) * 7.0)) - 4.0) / 5.5 * 55.0))
               WHEN e.source_score IS NOT NULL AND e.source_score > 0 THEN
                   LEAST(38.0, GREATEST(0.0, (e.source_score::double precision - 4.0) / 5.5 * 38.0))
               ELSE 18.0
            END)
           + LEAST(20.0, log10(1.0 + GREATEST(e.external_rating_count, 0) + GREATEST(e.source_hits, 0) / 20.0) / 4.2 * 20.0)
           + (CASE WHEN EXISTS (SELECT 1 FROM bangumi_subjects b WHERE b.subject_id = e.bangumi_subject_id AND b.in_calendar) THEN 5.0 ELSE 0.0 END)
           + (CASE
               WHEN COALESCE(e.resource_updated_at, e.synced_at) >= now() - interval '30 days' THEN 12.0
               WHEN COALESCE(e.resource_updated_at, e.synced_at) >= now() - interval '180 days' THEN 8.0
               WHEN COALESCE(e.resource_updated_at, e.synced_at) >= now() - interval '730 days' THEN 4.0
               ELSE 1.0
              END)
           + (CASE WHEN e.bangumi_subject_id IS NOT NULL THEN 4.0 ELSE 0.0 END)
           + (CASE WHEN e.hd_cover_url <> '' OR e.bangumi_subject_id IS NOT NULL THEN 2.0 ELSE 0.0 END)
           + (CASE WHEN e.episode_count >= 6 THEN 2.0 WHEN e.episode_count >= 1 THEN 1.0 ELSE 0.0 END)
           - (CASE WHEN e.name ~ '(动态漫|动态漫画|解说|短剧)' OR e.category_id NOT IN (29, 30, 31, 32, 33) THEN 18.0 ELSE 0.0 END)
       );

-- 2) 漫画存量地区先验与基础推荐分初始化回填
UPDATE manga_catalog_items m
   SET region = CASE
           WHEN m.tags && ARRAY['韩漫', '韩国'] OR m.name ~ '[가-힣]' THEN 'KR'
           WHEN m.tags && ARRAY['日漫', '日本'] THEN 'JP'
           WHEN m.tags && ARRAY['国漫', '国产', '中国'] THEN 'CN'
           WHEN m.tags && ARRAY['美漫', '欧美'] THEN 'OVERSEAS'
           WHEN m.source = 'copymanga' THEN 'JP'
           WHEN m.source IN ('tencent', 'bilibili', 'baozimh', 'hipmh') THEN 'CN'
           ELSE 'UNKNOWN'
       END,
       region_rank = CASE
           WHEN m.tags && ARRAY['韩漫', '韩国'] OR m.name ~ '[가-힣]' THEN 3
           WHEN m.tags && ARRAY['日漫', '日本'] THEN 1
           WHEN m.tags && ARRAY['国漫', '国产', '中国'] THEN 2
           WHEN m.tags && ARRAY['美漫', '欧美'] THEN 4
           WHEN m.source = 'copymanga' THEN 1
           WHEN m.source IN ('tencent', 'bilibili', 'baozimh', 'hipmh') THEN 2
           ELSE 5
       END,
       region_confidence = CASE
           WHEN m.tags && ARRAY['韩漫', '韩国', '日漫', '日本', '国漫', '国产', '中国', '美漫', '欧美'] OR m.name ~ '[가-힣]' THEN 20
           WHEN m.source IN ('copymanga', 'tencent', 'bilibili', 'baozimh', 'hipmh') THEN 10
           ELSE 0
       END
 WHERE m.region = 'UNKNOWN';

WITH grp AS (
    SELECT COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id) AS gk,
           COUNT(DISTINCT source) AS src_cnt,
           MAX(COALESCE(episode_count, NULLIF(substring(remarks FROM '([0-9]+)'), '')::int, 0)) AS max_ep,
           BOOL_OR(cover_url <> '') AS has_cover,
           MAX(cardinality(tags)) AS max_tags,
           MAX(popularity_score) AS max_pop
      FROM manga_catalog_items
     GROUP BY 1
)
UPDATE manga_catalog_items m
   SET recommend_score = GREATEST(0.0,
           LEAST(26.0, g.max_pop * 0.26)
         + LEAST(35.0, g.max_pop * 0.35)
         + (CASE WHEN g.src_cnt >= 3 THEN 15.0 WHEN g.src_cnt = 2 THEN 9.0 ELSE 0.0 END)
         + (CASE WHEN g.max_ep >= 50 THEN 5.0 WHEN g.max_ep >= 12 THEN 3.5 WHEN g.max_ep >= 1 THEN 1.5 ELSE 0.0 END)
         + (CASE WHEN g.has_cover THEN 3.0 ELSE 0.0 END)
         + (CASE WHEN g.max_tags >= 2 THEN 2.0 WHEN g.max_tags = 1 THEN 1.0 ELSE 0.0 END)
       )
  FROM grp g
 WHERE COALESCE(NULLIF(m.title_key, ''), m.source || ':' || m.source_item_id) = g.gk;

-- 3) 复合排序与地区筛选索引
CREATE INDEX IF NOT EXISTS idx_ext_catalog_recommend
    ON external_catalog_items (region_rank ASC, recommend_score DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_ext_catalog_rating
    ON external_catalog_items (bayesian_rating DESC NULLS LAST, region_rank ASC, recommend_score DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_ext_catalog_region
    ON external_catalog_items (region);

CREATE INDEX IF NOT EXISTS idx_manga_catalog_recommend
    ON manga_catalog_items (region_rank ASC, recommend_score DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_manga_catalog_rating
    ON manga_catalog_items (bayesian_rating DESC NULLS LAST, region_rank ASC, recommend_score DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_manga_catalog_region
    ON manga_catalog_items (region);
