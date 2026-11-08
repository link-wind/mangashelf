package com.mangashelf.external;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobCreationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 资源目录业务（M8 D3/D4）：同步触发、目录浏览、点播物化。 */
@Service
public class ExternalCatalogService {

    private static final long SYNC_DEDUP_WINDOW_SECONDS = 30;

    private final ExternalCatalogRepository catalogItems;
    private final ExternalRepository externals;
    private final CatalogRepository catalog;
    private final JobCreationService jobCreation;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public ExternalCatalogService(ExternalCatalogRepository catalogItems, ExternalRepository externals,
                                  CatalogRepository catalog, JobCreationService jobCreation,
                                  TransactionTemplate tx, ObjectMapper objectMapper) {
        this.catalogItems = catalogItems;
        this.externals = externals;
        this.catalog = catalog;
        this.jobCreation = jobCreation;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    // ── 同步 ──

    public record SyncStarted(UUID jobId) {}

    public SyncStarted triggerSync(CurrentUser user, UUID sourceId, List<Integer> categories, String mode) {
        requireAdmin(user);
        if (externals.findSource(sourceId).isEmpty()) {
            throw new ApiException(404, "SOURCE_NOT_FOUND", "资源源不存在。");
        }
        var latest = catalogItems.findLatestSyncJob(sourceId);
        boolean active = latest.isPresent()
                && (Job.STATUS_QUEUED.equals(latest.get().status()) || Job.STATUS_RUNNING.equals(latest.get().status()));
        if (!active) {
            String params;
            try {
                var node = objectMapper.createObjectNode();
                node.put("mode", mode == null ? "full" : mode);
                node.set("categories", objectMapper.valueToTree(categories == null ? List.of() : categories));
                params = node.toString();
            } catch (Exception e) {
                throw new IllegalStateException("params 序列化失败", e);
            }
            long window = Instant.now().getEpochSecond() / SYNC_DEDUP_WINDOW_SECONDS;
            jobCreation.create(Job.KIND_CATALOG_SYNC, user.id(), null, null, null, null, null,
                    sourceId, params, "catalog.sync:" + sourceId + ":" + mode + ":" + window);
        }
        return new SyncStarted(latest.map(Job::id).orElse(null));
    }

    public Job syncStatus(CurrentUser user, UUID sourceId) {
        requireAdmin(user);
        return catalogItems.findLatestSyncJob(sourceId).orElse(null);
    }

    // ── 目录浏览 ──

    public record CatalogItemPage(List<ExternalCatalogItem> items, String nextCursor, long total) {}

    public CatalogItemPage listItems(String cursor, int limit, String q, UUID sourceId, Integer categoryId) {
        Cursor c = decodeCursor(cursor);
        int effectiveLimit = Math.min(Math.max(limit, 1), 60);
        List<ExternalCatalogItem> items = catalogItems.page(sourceId, categoryId, q,
                c.updatedAt, c.id, effectiveLimit);
        String next = items.size() == effectiveLimit
                ? encodeCursor(coalesce(items.getLast().resourceUpdatedAt(), items.getLast().syncedAt()),
                        items.getLast().id())
                : null;
        return new CatalogItemPage(items, next, catalogItems.count(sourceId, categoryId));
    }

    // ── 物化 ──

    public record Materialized(UUID workId, boolean created) {}

    public Materialized materialize(CurrentUser user, UUID itemId) {
        ExternalCatalogItem item = catalogItems.findItem(itemId).orElseThrow(
                () -> new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。"));
        if (item.workId() != null) {
            return new Materialized(item.workId(), false);
        }
        UUID workId = tx.execute(status -> {
            // 行锁防并发重复物化（UNIQUE(source_id,vod_id) 行）
            var fresh = catalogItems.findItem(itemId).orElseThrow();
            if (fresh.workId() != null) return fresh.workId();
            UUID wid = catalog.insertWork(user.id(), fresh.name(), fresh.remarks());
            for (var ep : parseEpisodes(fresh)) {
                externals.insertEpisode(UUID.randomUUID(), wid, fresh.sourceId(),
                        ep.title(), ep.url(), ep.ordinal());
            }
            catalogItems.markMaterialized(itemId, wid);
            return wid;
        });
        return new Materialized(workId, true);
    }

    /** 选线路：优先名称含 m3u8 的组；解析 名称$url#名称$url。 */
    private record Episode(String title, String url, int ordinal) {}

    private List<Episode> parseEpisodes(ExternalCatalogItem item) {
        List<Episode> out = new ArrayList<>();
        try {
            var groups = objectMapper.readTree(item.playGroups());
            JsonNode picked = null;
            for (JsonNode g : groups) {
                if (g.path("from").asText().contains("m3u8")) { picked = g; break; }
                if (picked == null) picked = g;
            }
            if (picked == null) return out;
            for (String seg : picked.path("raw").asText().split("#")) {
                String[] kv = seg.split("\\$", 2);
                if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                    out.add(new Episode(kv[0], kv[1], out.size() + 1));
                }
            }
        } catch (Exception e) {
            throw new ApiException(422, "PLAY_GROUPS_INVALID", "线路数据无法解析：" + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new ApiException(422, "NO_EPISODES", "该条目没有可解析的剧集地址");
        }
        return out;
    }

    private void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "只有管理员可以管理资源目录。");
        }
    }

    // ── 游标（resource_updated_at, id）──

    private record Cursor(Instant updatedAt, UUID id) {}

    private static String encodeCursor(Instant t, UUID id) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((t.toString() + "|" + id).getBytes());
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            // 首页哨兵：PG timestamptz 上限内的"最大"值（Long.MAX_VALUE/2 会溢出 PG 范围）
            return new Cursor(Instant.parse("9999-12-31T23:59:59Z"), new UUID(Long.MAX_VALUE, Long.MAX_VALUE));
        }
        try {
            String s = new String(java.util.Base64.getUrlDecoder().decode(cursor));
            int cut = s.lastIndexOf('|');
            return new Cursor(Instant.parse(s.substring(0, cut)), UUID.fromString(s.substring(cut + 1)));
        } catch (Exception e) {
            throw new ApiException(400, "BAD_CURSOR", "分页游标无效。");
        }
    }

    private static Instant coalesce(Instant a, Instant b) {
        return a != null ? a : b;
    }
}
