package com.mangashelf.external;

import com.mangashelf.config.HotspotCache;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.external.manga.BilibiliHttpClient;
import com.mangashelf.external.manga.MangaCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 全站番剧与漫画的评分、地区与综合推荐分维护服务：
 * 1) SQL 毫秒级重算贝叶斯评分与综合推荐分（含漫画跨源 title_key 地区/评分/热度共享传播）；
 * 2) 批量补齐番剧 Bangumi v0 + AniList 真实评分、评分数与精确地区（支持断点续跑）；
 * 3) 批量补齐漫画哔哩官方 area_id 精确地区/人气 + Bangumi 书籍(type=1) + AniList Manga 真实评分与地区。
 */
@Service
public class CatalogRankingService {

    private static final Logger log = LoggerFactory.getLogger(CatalogRankingService.class);
    private static final String UA = "linkwind/mangashelf-selfhost (personal self-hosted platform)";
    private static final String ANILIST_URL = "https://graphql.anilist.co";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final String ANILIST_META_QUERY = """
            query ($search: String, $type: MediaType) {
              Page(page: 1, perPage: 5) {
                media(search: $search, type: $type, sort: SEARCH_MATCH) {
                  id
                  countryOfOrigin
                  averageScore
                  popularity
                  title { romaji english native }
                  synonyms
                  coverImage { extraLarge large }
                }
              }
            }
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final HotspotCache hotspotCache;
    private final BilibiliHttpClient bilibiliClient;
    private final MangaCatalogRepository mangaCatalog;

    public record RecomputeStats(int animeUpdated, int mangaUpdated) {}

    public record AnimeEnrichStats(int subjectsRefreshed, int checked, int matchedBangumi,
                                   int matchedAniList, int regionUpdated, int recomputed) {}

    public record MangaEnrichStats(int bilibiliSynced, int checked, int matchedBangumi,
                                   int matchedAniList, int regionUpdated, int recomputed) {}

    record BgmHit(long id, String name, String nameCn, String coverUrl, Double score,
                  int ratingCount, Integer rank, LocalDate airDate, String summary, List<String> tags) {}

    record AniMeta(String countryOfOrigin, Integer averageScore, Integer popularity, String coverUrl) {}

    @Autowired
    public CatalogRankingService(MangaShelfProperties props, JdbcTemplate jdbc, ObjectMapper objectMapper,
                                 HotspotCache hotspotCache, BilibiliHttpClient bilibiliClient,
                                 MangaCatalogRepository mangaCatalog) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.hotspotCache = hotspotCache;
        this.bilibiliClient = bilibiliClient;
        this.mangaCatalog = mangaCatalog;
        var builder = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        var cfg = props.bangumi();
        if (cfg != null && cfg.proxy() != null && !cfg.proxy().isBlank()) {
            URI uri = BangumiService.resolveProxyUri(cfg.proxy());
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort())));
        }
        this.httpClient = builder.build();
    }

    /** 毫秒级重算全站番剧与漫画的跨源地区传播、贝叶斯评分与综合推荐分。 */
    public RecomputeStats recomputeAll() {
        int a = recomputeAnimeScores();
        int m = recomputeMangaScores();
        return new RecomputeStats(a, m);
    }

    /** 同步 bangumi_subjects 评分/地区到 external_catalog_items 并重算 bayesian_rating 与 recommend_score。 */
    public int recomputeAnimeScores() {
        jdbc.update("""
                UPDATE external_catalog_items e
                   SET external_rating = COALESCE(
                           CASE WHEN b.rating IS NOT NULL AND b.rating > 0 THEN round(b.rating::numeric, 1) END,
                           e.external_rating),
                       external_rating_count = GREATEST(e.external_rating_count,
                           CASE WHEN b.rating_count > 0 THEN b.rating_count
                                WHEN b.rating IS NOT NULL AND b.rating > 0 THEN (CASE WHEN b.in_calendar THEN 200 ELSE 100 END)
                                ELSE 0 END),
                       region = CASE
                           WHEN e.region_confidence < 30 AND b.tags && ARRAY['国产', '中国', '国漫', '中国大陆', '港台'] THEN 'CN'
                           WHEN e.region_confidence < 30 AND (b.tags && ARRAY['韩国', '韩漫'] OR b.name ~ '[가-힣]') THEN 'KR'
                           WHEN e.region_confidence < 30 AND b.tags && ARRAY['欧美', '美国', '英国', '法国'] THEN 'OVERSEAS'
                           WHEN e.region_confidence < 30 AND (b.tags && ARRAY['日本'] OR b.name ~ '[ぁ-ヿ]') THEN 'JP'
                           ELSE e.region
                       END,
                       region_rank = CASE
                           WHEN e.region_confidence < 30 AND b.tags && ARRAY['国产', '中国', '国漫', '中国大陆', '港台'] THEN 2
                           WHEN e.region_confidence < 30 AND (b.tags && ARRAY['韩国', '韩漫'] OR b.name ~ '[가-힣]') THEN 3
                           WHEN e.region_confidence < 30 AND b.tags && ARRAY['欧美', '美国', '英国', '法国'] THEN 4
                           WHEN e.region_confidence < 30 AND (b.tags && ARRAY['日本'] OR b.name ~ '[ぁ-ヿ]') THEN 1
                           ELSE e.region_rank
                       END,
                       region_confidence = CASE
                           WHEN e.region_confidence < 30 AND (
                                b.tags && ARRAY['国产', '中国', '国漫', '中国大陆', '港台', '韩国', '韩漫', '欧美', '美国', '英国', '法国', '日本']
                                OR b.name ~ '[ぁ-ヿ가-힣]') THEN 30
                           ELSE e.region_confidence
                       END
                  FROM bangumi_subjects b
                 WHERE b.subject_id = e.bangumi_subject_id
                """);

        int updated = jdbc.update("""
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
                       )
                """);
        hotspotCache.invalidate("catalog_page");
        hotspotCache.invalidate("catalog_count");
        return updated;
    }

    /**
     * 漫画跨源 title_key 传播高置信度地区与最佳评分，并重算全库 bayesian_rating 与 recommend_score。
     * 例：哔哩 area_id=2 判定《航海王》为 JP（conf=30）后，自动同步给腾讯/拷贝同名条目。
     */
    public int recomputeMangaScores() {
        // 1) 同 title_key 跨源共享最高置信度地区
        jdbc.update("""
                WITH best_reg AS (
                    SELECT DISTINCT ON (title_key)
                           title_key, region, region_rank, region_confidence
                      FROM manga_catalog_items
                     WHERE title_key <> ''
                       AND region <> 'UNKNOWN'
                     ORDER BY title_key, region_confidence DESC, region_rank ASC, synced_at DESC
                )
                UPDATE manga_catalog_items m
                   SET region = br.region,
                       region_rank = br.region_rank,
                       region_confidence = br.region_confidence
                  FROM best_reg br
                 WHERE m.title_key = br.title_key
                   AND br.region_confidence > m.region_confidence
                """);

        // 2) 同 title_key 跨源共享真实评分、评分数与最高源站热度
        jdbc.update("""
                WITH best_stat AS (
                    SELECT title_key,
                           MAX(rating) AS max_rating,
                           MAX(rating_count) AS max_votes,
                           MAX(popularity_score) AS max_pop
                      FROM manga_catalog_items
                     WHERE title_key <> ''
                     GROUP BY title_key
                )
                UPDATE manga_catalog_items m
                   SET rating = COALESCE(m.rating, bs.max_rating),
                       rating_count = GREATEST(m.rating_count, COALESCE(bs.max_votes, 0)),
                       popularity_score = GREATEST(m.popularity_score, COALESCE(bs.max_pop, 0))
                  FROM best_stat bs
                 WHERE m.title_key = bs.title_key
                   AND (m.rating IS NULL AND bs.max_rating IS NOT NULL
                        OR m.rating_count < COALESCE(bs.max_votes, 0)
                        OR m.popularity_score < COALESCE(bs.max_pop, 0))
                """);

        // 3) 重算 bayesian_rating 与 recommend_score
        int updated = jdbc.update("""
                WITH grp AS (
                    SELECT COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id) AS gk,
                           COUNT(DISTINCT source) AS src_cnt,
                           MAX(COALESCE(episode_count, NULLIF(substring(remarks FROM '([0-9]+)'), '')::int, 0)) AS max_ep,
                           BOOL_OR(cover_url <> '') AS has_cover,
                           MAX(cardinality(tags)) AS max_tags,
                           MAX(popularity_score) AS max_pop,
                           MAX(rating) AS max_rating,
                           MAX(rating_count) AS max_votes
                      FROM manga_catalog_items
                     GROUP BY 1
                )
                UPDATE manga_catalog_items m
                   SET bayesian_rating = CASE
                           WHEN g.max_rating IS NOT NULL AND g.max_rating > 0 THEN
                               round((((GREATEST(g.max_votes, 10)::numeric / (GREATEST(g.max_votes, 10) + 50.0)) * g.max_rating)
                                    + ((50.0 / (GREATEST(g.max_votes, 10) + 50.0)) * 7.0))::numeric, 2)
                           ELSE NULL
                       END,
                       recommend_score = GREATEST(0.0,
                           (CASE
                               WHEN g.max_rating IS NOT NULL AND g.max_rating > 0 THEN
                                   LEAST(40.0, GREATEST(0.0, (
                                       (((GREATEST(g.max_votes, 10)::double precision / (GREATEST(g.max_votes, 10) + 50.0)) * g.max_rating::double precision)
                                        + ((50.0 / (GREATEST(g.max_votes, 10) + 50.0)) * 7.0)) - 4.0) / 5.5 * 40.0))
                               ELSE LEAST(26.0, COALESCE(g.max_pop, 0) * 0.26)
                            END)
                           + LEAST(35.0, COALESCE(g.max_pop, 0) * 0.35)
                           + (CASE WHEN g.src_cnt >= 3 THEN 15.0 WHEN g.src_cnt = 2 THEN 9.0 ELSE 0.0 END)
                           + (CASE WHEN g.max_ep >= 50 THEN 5.0 WHEN g.max_ep >= 12 THEN 3.5 WHEN g.max_ep >= 1 THEN 1.5 ELSE 0.0 END)
                           + (CASE WHEN g.has_cover THEN 3.0 ELSE 0.0 END)
                           + (CASE WHEN g.max_tags >= 2 THEN 2.0 WHEN g.max_tags = 1 THEN 1.0 ELSE 0.0 END)
                       )
                  FROM grp g
                 WHERE COALESCE(NULLIF(m.title_key, ''), m.source || ':' || m.source_item_id) = g.gk
                """);
        return updated;
    }

    /**
     * 批量补齐番剧评分、评分数与精确地区（支持断点续跑）：
     * 1) 先补齐存量 bangumi_subjects 缺失的 rating_count / bgm_rank；
     * 2) 优先为尚未检测的高集数/近期番剧查询 Bangumi v0(type=2) + AniList(ANIME) 写回评分与国家地区。
     */
    public AnimeEnrichStats enrichAnimeRatingsAndRegions(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 400);
        int subjectsRefreshed = refreshBangumiSubjectVotes(Math.min(safeLimit, 120));

        record Candidate(UUID id, String name, String region, int conf, Long bgmId) {}
        List<Candidate> rows = jdbc.query("""
                SELECT id, name, region, region_confidence, bangumi_subject_id
                  FROM external_catalog_items
                 WHERE enriched_at IS NULL
                   AND category_id IN (29, 30, 31, 32, 33)
                   AND name NOT LIKE '%动态漫%'
                   AND name NOT LIKE '%动态漫画%'
                 ORDER BY (bangumi_subject_id IS NULL) DESC,
                          region_rank ASC,
                          episode_count DESC,
                          COALESCE(resource_updated_at, synced_at) DESC
                 LIMIT ?
                """, (rs, i) -> new Candidate(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("region"),
                rs.getInt("region_confidence"),
                rs.getObject("bangumi_subject_id", Long.class)), safeLimit);

        int checked = 0;
        int matchedBgm = 0;
        int matchedAni = 0;
        int regionUpdated = 0;

        for (int i = 0; i < rows.size(); i++) {
            Candidate c = rows.get(i);
            checked++;
            try {
                BgmHit bgm = searchBangumiBest(c.name(), 2);
                Double bgmScore = null;
                int bgmVotes = 0;
                CatalogRanking.RegionInfo bestRegion = CatalogRanking.RegionInfo.of(c.region(), c.conf());
                String altQuery = null;

                if (bgm != null) {
                    matchedBgm++;
                    bgmScore = bgm.score();
                    bgmVotes = bgm.ratingCount();
                    altQuery = bgm.name();
                    upsertBangumiSubjectFromHit(bgm);
                    jdbc.update("UPDATE external_catalog_items SET bangumi_subject_id = COALESCE(bangumi_subject_id, ?) WHERE id = ?",
                            bgm.id(), c.id());
                    CatalogRanking.RegionInfo bgmReg = CatalogRanking.fromBangumiSubject(bgm.name(), bgm.tags());
                    if (bgmReg.confidence() > bestRegion.confidence()) {
                        bestRegion = bgmReg;
                    }
                }

                // 若 Bangumi 未命中评分或地区尚未精确（如日韩混杂分类且无假名/标签），补查 AniList
                Integer aniScore = null;
                Integer aniPop = null;
                String aniCover = null;
                if (bgmScore == null || bestRegion.confidence() < CatalogRanking.CONF_OFFICIAL_AREA) {
                    AniMeta ani = queryAniList("ANIME", c.name(), altQuery);
                    if (ani != null) {
                        matchedAni++;
                        aniScore = ani.averageScore();
                        aniPop = ani.popularity();
                        aniCover = ani.coverUrl();
                        CatalogRanking.RegionInfo aniReg = CatalogRanking.fromAniListCountry(ani.countryOfOrigin());
                        if (aniReg.confidence() >= bestRegion.confidence() && !CatalogRanking.REGION_UNKNOWN.equals(aniReg.region())) {
                            bestRegion = aniReg;
                        }
                    }
                }

                CatalogRanking.FusedRating fused = CatalogRanking.fuseRatings(bgmScore, bgmVotes, aniScore, aniPop);
                boolean changedRegion = bestRegion.confidence() > c.conf() && !bestRegion.region().equals(c.region());
                if (changedRegion) {
                    regionUpdated++;
                }

                jdbc.update("""
                        UPDATE external_catalog_items
                           SET region = CASE WHEN ? >= region_confidence AND ? <> 'UNKNOWN' THEN ? ELSE region END,
                               region_rank = CASE WHEN ? >= region_confidence AND ? <> 'UNKNOWN' THEN ? ELSE region_rank END,
                               region_confidence = GREATEST(region_confidence, ?),
                               external_rating = COALESCE(?, external_rating),
                               external_rating_count = GREATEST(external_rating_count, ?),
                               hd_cover_url = CASE WHEN hd_cover_url = '' AND ? <> '' THEN ? ELSE hd_cover_url END,
                               enriched_at = now()
                         WHERE id = ?
                        """,
                        bestRegion.confidence(), bestRegion.region(), bestRegion.region(),
                        bestRegion.confidence(), bestRegion.region(), bestRegion.rank(),
                        bestRegion.confidence(),
                        fused == null ? null : fused.rawScore(),
                        fused == null ? 0 : fused.votes(),
                        aniCover == null ? "" : aniCover, aniCover == null ? "" : aniCover,
                        c.id());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("anime ranking enrich failed for {}: {}", c.name(), e.getMessage());
                jdbc.update("UPDATE external_catalog_items SET enriched_at = now() WHERE id = ?", c.id());
            }

            if (i + 1 < rows.size()) {
                sleepQuietly(220);
            }
        }

        int recomputed = recomputeAnimeScores();
        return new AnimeEnrichStats(subjectsRefreshed, checked, matchedBgm, matchedAni, regionUpdated, recomputed);
    }

    /**
     * 批量补齐漫画精确地区、源站人气与真实外部评分（支持断点续跑）：
     * 1) 可选先执行哔哩漫画官方 area_id (2=JP, 1=CN, 6=KR, 5=OVERSEAS) 快速扫榜，秒级精确标注数千部漫画并跨源传播；
     * 2) 优先为跨源多平台 / 高人气头部漫画查询 Bangumi 书籍(type=1) + AniList(MANGA) 写入真实评分与精确地区。
     */
    public MangaEnrichStats enrichMangaRatingsAndRegions(int limit, boolean syncBilibiliAreas, int bilibiliPagesPerArea) {
        int safeLimit = Math.min(Math.max(limit, 0), 400);
        int biliSynced = syncBilibiliAreas ? syncBilibiliRegions(Math.min(Math.max(bilibiliPagesPerArea, 1), 60)) : 0;
        if (biliSynced > 0) {
            recomputeMangaScores();
        }

        int checked = 0;
        int matchedBgm = 0;
        int matchedAni = 0;
        int regionUpdated = 0;

        if (safeLimit > 0) {
            record Candidate(String groupKey, String titleKey, String name, String region, int conf) {}
            List<Candidate> rows = jdbc.query("""
                    WITH grouped AS (
                        SELECT COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id) AS gk,
                               MAX(title_key) AS tk,
                               (ARRAY_AGG(name ORDER BY
                                   CASE source WHEN 'bilibili' THEN 0 WHEN 'tencent' THEN 1 WHEN 'copymanga' THEN 2 ELSE 3 END,
                                   synced_at DESC))[1] AS sample_name,
                               (ARRAY_AGG(region ORDER BY region_confidence DESC, region_rank ASC))[1] AS best_region,
                               MAX(region_confidence) AS max_conf,
                               COUNT(DISTINCT source) AS src_cnt,
                               MAX(popularity_score) AS max_pop,
                               MIN(region_rank) AS min_rr,
                               MAX(COALESCE(episode_count, 0)) AS max_ep,
                               BOOL_OR(enriched_at IS NOT NULL) AS any_enriched
                          FROM manga_catalog_items
                         GROUP BY 1
                    )
                    SELECT gk, tk, sample_name, best_region, max_conf
                      FROM grouped
                     WHERE NOT any_enriched
                     ORDER BY src_cnt DESC, max_pop DESC, min_rr ASC, max_ep DESC
                     LIMIT ?
                    """, (rs, i) -> new Candidate(
                    rs.getString("gk"),
                    rs.getString("tk"),
                    rs.getString("sample_name"),
                    rs.getString("best_region"),
                    rs.getInt("max_conf")), safeLimit);

            for (int i = 0; i < rows.size(); i++) {
                Candidate c = rows.get(i);
                checked++;
                try {
                    BgmHit bgm = searchBangumiBest(c.name(), 1);
                    Double bgmScore = null;
                    int bgmVotes = 0;
                    CatalogRanking.RegionInfo bestRegion = CatalogRanking.RegionInfo.of(c.region(), c.conf());
                    String altQuery = null;

                    if (bgm != null) {
                        matchedBgm++;
                        bgmScore = bgm.score();
                        bgmVotes = bgm.ratingCount();
                        altQuery = bgm.name();
                        CatalogRanking.RegionInfo bgmReg = CatalogRanking.fromBangumiSubject(bgm.name(), bgm.tags());
                        if (bgmReg.confidence() > bestRegion.confidence()) {
                            bestRegion = bgmReg;
                        }
                    }

                    Integer aniScore = null;
                    Integer aniPop = null;
                    if (bgmScore == null || bestRegion.confidence() < CatalogRanking.CONF_OFFICIAL_AREA) {
                        AniMeta ani = queryAniList("MANGA", c.name(), altQuery);
                        if (ani != null) {
                            matchedAni++;
                            aniScore = ani.averageScore();
                            aniPop = ani.popularity();
                            CatalogRanking.RegionInfo aniReg = CatalogRanking.fromAniListCountry(ani.countryOfOrigin());
                            if (aniReg.confidence() >= bestRegion.confidence() && !CatalogRanking.REGION_UNKNOWN.equals(aniReg.region())) {
                                bestRegion = aniReg;
                            }
                        }
                    }

                    CatalogRanking.FusedRating fused = CatalogRanking.fuseRatings(bgmScore, bgmVotes, aniScore, aniPop);
                    if (bestRegion.confidence() > c.conf() && !bestRegion.region().equals(c.region())) {
                        regionUpdated++;
                    }

                    jdbc.update("""
                            UPDATE manga_catalog_items
                               SET region = CASE WHEN ? >= region_confidence AND ? <> 'UNKNOWN' THEN ? ELSE region END,
                                   region_rank = CASE WHEN ? >= region_confidence AND ? <> 'UNKNOWN' THEN ? ELSE region_rank END,
                                   region_confidence = GREATEST(region_confidence, ?),
                                   rating = COALESCE(?, rating),
                                   rating_count = GREATEST(rating_count, ?),
                                   enriched_at = now()
                             WHERE COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id) = ?
                            """,
                            bestRegion.confidence(), bestRegion.region(), bestRegion.region(),
                            bestRegion.confidence(), bestRegion.region(), bestRegion.rank(),
                            bestRegion.confidence(),
                            fused == null ? null : fused.rawScore(),
                            fused == null ? 0 : fused.votes(),
                            c.groupKey());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("manga ranking enrich failed for {}: {}", c.name(), e.getMessage());
                    jdbc.update("""
                            UPDATE manga_catalog_items SET enriched_at = now()
                             WHERE COALESCE(NULLIF(title_key, ''), source || ':' || source_item_id) = ?
                            """, c.groupKey());
                }

                if (i + 1 < rows.size()) {
                    sleepQuietly(220);
                }
            }
        }

        int recomputed = recomputeMangaScores();
        return new MangaEnrichStats(biliSynced, checked, matchedBgm, matchedAni, regionUpdated, recomputed);
    }

    /** 按哔哩官方 area_id (2=JP, 1=CN, 6=KR, 5=OVERSEAS) 快速拉取人气榜，补齐精确地区与源站人气分。 */
    public int syncBilibiliRegions(int pagesPerArea) {
        if (bilibiliClient == null) return 0;
        int[] areas = {2, 1, 6, 5};
        int pageSize = 30;
        int synced = 0;
        for (int areaId : areas) {
            CatalogRanking.RegionInfo areaReg = CatalogRanking.fromBilibiliAreaId(areaId);
            int maxPages = areaId == 5 ? Math.min(pagesPerArea, 10) : pagesPerArea;
            for (int page = 1; page <= maxPages; page++) {
                try {
                    JsonNode list = bilibiliClient.classPage(-1, areaId, page, pageSize);
                    if (list == null || !list.isArray() || list.isEmpty()) break;
                    int baseRank = (page - 1) * pageSize;
                    int count = 0;
                    for (JsonNode c : list) {
                        String id = c.path("season_id").asText("");
                        String title = c.path("title").asText("").trim();
                        if (id.isBlank() || title.isBlank()) continue;
                        String cover = c.path("vertical_cover").asText("");
                        if (cover.contains("hdslb.com") && !cover.contains("@")) {
                            cover = cover + "@480w.jpg";
                        }
                        boolean finished = c.path("is_finish").asInt(0) == 1;
                        int total = c.path("total").asInt(0);
                        int lastOrd = c.path("last_ord").asInt(0);
                        Integer epCount = total > 0 ? total : (lastOrd > 0 ? lastOrd : null);
                        String lastShort = c.path("last_short_title").asText("").trim();
                        String remarks = finished
                                ? (epCount != null ? "已完结·全" + epCount + "话" : "已完结")
                                : (!lastShort.isBlank() ? "更新至" + lastShort + "话" : (epCount != null ? "更新至" + epCount + "话" : ""));
                        List<String> tags = new ArrayList<>();
                        c.path("styles").forEach(s -> {
                            String t = s.asText("").trim();
                            if (!t.isBlank()) tags.add(t);
                        });
                        String author = joinNames(c.path("author"));
                        double popScore = CatalogRanking.popularityFromRank(baseRank + count, maxPages * pageSize);
                        mangaCatalog.upsert(UUID.randomUUID(), "bilibili", id, title, author, cover, remarks,
                                tags, epCount, areaReg.region(), areaReg.confidence(), popScore);
                        synced++;
                        count++;
                    }
                    if (count < pageSize) break;
                    sleepQuietly(120);
                } catch (Exception e) {
                    log.warn("bilibili area {} page {} sync failed: {}", areaId, page, e.getMessage());
                    break;
                }
            }
        }
        return synced;
    }

    // ── 外部元数据检索辅助 ──

    private int refreshBangumiSubjectVotes(int limit) {
        List<Long> ids = jdbc.query("""
                SELECT subject_id
                  FROM bangumi_subjects
                 WHERE rating_count = 0
                 ORDER BY in_calendar DESC, rating DESC NULLS LAST
                 LIMIT ?
                """, (rs, i) -> rs.getLong(1), limit);
        int refreshed = 0;
        for (int i = 0; i < ids.size(); i++) {
            long sid = ids.get(i);
            try {
                JsonNode detail = getJson("https://api.bgm.tv/v0/subjects/" + sid);
                Double score = detail.path("rating").path("score").isNumber()
                        ? detail.path("rating").path("score").asDouble() : null;
                if (score != null && score <= 0) score = null;
                int total = Math.max(0, detail.path("rating").path("total").asInt(0));
                int rankVal = detail.path("rating").path("rank").asInt(0);
                Integer rank = rankVal > 0 ? rankVal : null;
                jdbc.update("""
                        UPDATE bangumi_subjects
                           SET rating = COALESCE(?, rating),
                               rating_count = GREATEST(rating_count, ?),
                               bgm_rank = COALESCE(?, bgm_rank)
                         WHERE subject_id = ?
                        """, score, total, rank, sid);
                refreshed++;
            } catch (Exception e) {
                log.debug("refresh bangumi subject {} votes failed: {}", sid, e.getMessage());
            }
            if (i + 1 < ids.size()) {
                sleepQuietly(160);
            }
        }
        return refreshed;
    }

    private BgmHit searchBangumiBest(String keyword, int subjectType) throws Exception {
        if (keyword == null || keyword.isBlank()) return null;
        String cleanQuery = cleanSearchTitle(keyword);
        if (cleanQuery.length() < 2) return null;

        var body = objectMapper.createObjectNode();
        body.put("keyword", cleanQuery);
        body.set("filter", objectMapper.createObjectNode()
                .set("type", objectMapper.createArrayNode().add(subjectType)));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.bgm.tv/v0/search/subjects?limit=5"))
                .timeout(TIMEOUT)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            Thread.sleep(3000);
            return null;
        }
        if (resp.statusCode() != 200) return null;

        JsonNode data = objectMapper.readTree(resp.body()).path("data");
        if (!data.isArray() || data.isEmpty()) return null;

        int querySeason = subjectType == 2 ? BangumiService.extractSeasonNumber(keyword) : 0;
        BgmHit best = null;
        double bestMatch = 0;
        for (JsonNode x : data) {
            long id = x.path("id").asLong(0);
            if (id <= 0) continue;
            String name = x.path("name").asText("").trim();
            String nameCn = x.path("name_cn").asText("").trim();
            double sim = Math.max(
                    BangumiService.titleMatchScore(cleanQuery, nameCn),
                    BangumiService.titleMatchScore(cleanQuery, name));
            if (sim < 0.72) continue;
            double adjusted = sim;
            if (subjectType == 2 && querySeason > 0) {
                int candSeason = Math.max(BangumiService.extractSeasonNumber(nameCn), BangumiService.extractSeasonNumber(name));
                if (candSeason == querySeason) adjusted += 0.08;
                else if (candSeason > 0) adjusted -= 0.12;
            }
            if (adjusted > bestMatch) {
                bestMatch = adjusted;
                Double score = extractBgmScore(x);
                int votes = Math.max(0, x.path("rating").path("total").asInt(0));
                int rankVal = x.path("rating").path("rank").asInt(x.path("rank").asInt(0));
                Integer rank = rankVal > 0 ? rankVal : null;
                List<String> tags = new ArrayList<>();
                x.path("meta_tags").forEach(t -> {
                    String v = t.asText("").trim();
                    if (!v.isBlank()) tags.add(v);
                });
                x.path("tags").forEach(t -> {
                    String v = t.path("name").asText("").trim();
                    if (!v.isBlank() && tags.size() < 12) tags.add(v);
                });
                best = new BgmHit(id, name, nameCn, pickImage(x.path("images")), score, votes, rank,
                        parseDate(x.path("date").asText("")), x.path("summary").asText(""), tags);
            }
        }
        return best;
    }

    private AniMeta queryAniList(String mediaType, String... rawKeywords) throws Exception {
        List<String> queries = new ArrayList<>();
        for (String kw : rawKeywords) {
            if (kw != null && !kw.isBlank()) {
                String clean = cleanSearchTitle(kw);
                if (clean.length() >= 2 && !queries.contains(clean)) {
                    queries.add(clean);
                }
            }
        }
        if (queries.isEmpty()) return null;

        for (String kw : queries) {
            var payload = objectMapper.createObjectNode();
            payload.put("query", ANILIST_META_QUERY);
            var vars = objectMapper.createObjectNode();
            vars.put("search", kw);
            vars.put("type", mediaType);
            payload.set("variables", vars);
            HttpRequest req = HttpRequest.newBuilder(URI.create(ANILIST_URL))
                    .timeout(TIMEOUT)
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                log.warn("AniList rate limited (429) during ranking enrich, backing off 4s");
                Thread.sleep(4000);
                continue;
            }
            if (resp.statusCode() != 200) continue;

            JsonNode list = objectMapper.readTree(resp.body()).path("data").path("Page").path("media");
            if (!list.isArray()) continue;
            AniMeta best = null;
            double bestSim = 0;
            for (JsonNode m : list) {
                List<String> titles = new ArrayList<>();
                JsonNode tn = m.path("title");
                for (String f : new String[]{"native", "romaji", "english"}) {
                    String v = tn.path(f).asText("").trim();
                    if (!v.isBlank()) titles.add(v);
                }
                m.path("synonyms").forEach(s -> {
                    String v = s.asText("").trim();
                    if (!v.isBlank()) titles.add(v);
                });
                double sim = 0;
                for (String q : queries) {
                    for (String t : titles) {
                        sim = Math.max(sim, BangumiService.titleMatchScore(q, t));
                    }
                }
                if (sim < 0.68) continue;
                if (sim > bestSim) {
                    bestSim = sim;
                    String country = m.path("countryOfOrigin").asText("").trim();
                    Integer avg = m.path("averageScore").isInt() ? m.path("averageScore").asInt() : null;
                    Integer pop = m.path("popularity").isInt() ? m.path("popularity").asInt() : null;
                    String cover = m.path("coverImage").path("extraLarge").asText("").trim();
                    if (cover.isBlank()) cover = m.path("coverImage").path("large").asText("").trim();
                    best = new AniMeta(country, avg, pop, cover);
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private void upsertBangumiSubjectFromHit(BgmHit hit) {
        String[] tagArr = hit.tags().toArray(new String[0]);
        jdbc.update("""
                INSERT INTO bangumi_subjects
                    (subject_id, name, name_cn, cover_url, rating, rating_count, bgm_rank, air_date, summary, in_calendar, tags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?)
                ON CONFLICT (subject_id) DO UPDATE SET
                    name_cn = CASE WHEN EXCLUDED.name_cn <> '' THEN EXCLUDED.name_cn ELSE bangumi_subjects.name_cn END,
                    cover_url = CASE WHEN EXCLUDED.cover_url <> '' THEN EXCLUDED.cover_url ELSE bangumi_subjects.cover_url END,
                    rating = COALESCE(EXCLUDED.rating, bangumi_subjects.rating),
                    rating_count = GREATEST(bangumi_subjects.rating_count, EXCLUDED.rating_count),
                    bgm_rank = COALESCE(EXCLUDED.bgm_rank, bangumi_subjects.bgm_rank),
                    air_date = COALESCE(EXCLUDED.air_date, bangumi_subjects.air_date),
                    summary = CASE WHEN EXCLUDED.summary <> '' THEN EXCLUDED.summary ELSE bangumi_subjects.summary END,
                    tags = CASE WHEN cardinality(EXCLUDED.tags) > 0 THEN EXCLUDED.tags ELSE bangumi_subjects.tags END,
                    synced_at = now()
                """, hit.id(), hit.name(), hit.nameCn(), hit.coverUrl(), hit.score(),
                hit.ratingCount(), hit.rank(), hit.airDate(), hit.summary(), tagArr);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }

    private static Double extractBgmScore(JsonNode x) {
        if (x.path("rating").path("score").isNumber()) {
            double v = x.path("rating").path("score").asDouble();
            if (v > 0) return Math.round(v * 10.0) / 10.0;
        }
        if (x.path("score").isNumber()) {
            double v = x.path("score").asDouble();
            if (v > 0) return Math.round(v * 10.0) / 10.0;
        }
        return null;
    }

    private static String pickImage(JsonNode images) {
        for (String field : new String[]{"large", "medium", "common"}) {
            String v = images.path(field).asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static LocalDate parseDate(String s) {
        try {
            return s != null && s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String cleanSearchTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("（[^）]*）|\\([^)]*\\)|\\[[^]]*]|【[^】]*】", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String joinNames(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.isTextual() ? n.asText("") : n.path("name").asText("");
            if (!s.isBlank()) names.add(s.trim());
        }
        return String.join(" / ", names);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
