package com.mangashelf.integration;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.CatalogService;
import com.mangashelf.catalog.ChapterRevision;
import com.mangashelf.catalog.Page;
import com.mangashelf.config.ApiException;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.integration.IntegrationController.ExportManifest;
import com.mangashelf.integration.IntegrationController.ExportPage;
import com.mangashelf.integration.IntegrationController.ExportRequest;
import com.mangashelf.storage.Asset;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.PresignService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 素材导出清单（DESIGN 17.2）：面向请求方可读的固定章节版本，
 * sha256 取已核验值，下载地址为短期签名；platform_id 来自稳定配置。
 */
@Service
public class ExportService {

    public static final int SCHEMA_VERSION = 1;

    private final CatalogRepository catalog;
    private final CatalogService catalogService;
    private final AssetRepository assets;
    private final PresignService presign;
    private final MangaShelfProperties props;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ExportService(CatalogRepository catalog, CatalogService catalogService,
                         AssetRepository assets, PresignService presign, MangaShelfProperties props,
                         org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.assets = assets;
        this.presign = presign;
        this.props = props;
        this.jdbc = jdbc;
    }

    public ExportManifest buildManifest(CurrentUser user, UUID chapterRevisionId, List<UUID> pageIds) {
        requirePlatformId();
        ChapterRevision revision = catalogService.requireReadableRevision(user, chapterRevisionId);
        List<Page> pages = catalog.listPages(chapterRevisionId, 0, Integer.MAX_VALUE);
        if (!pages.isEmpty() && pageIds != null && !pageIds.isEmpty()) {
            Set<UUID> wanted = Set.copyOf(pageIds);
            pages = pages.stream().filter(p -> wanted.contains(p.id())).toList();
            if (pages.size() != pageIds.size()) {
                throw new ApiException(400, "PAGE_NOT_FOUND", "请求的页面中有不在该版本内的页。");
            }
        }
        List<ExportPage> items = pages.stream().map(p -> {
            String sha = assets.findById(p.imageAssetId())
                    .map(Asset::verifiedSha256).orElse(null);
            if (sha == null) {
                throw new ApiException(409, "PAGE_ASSET_NOT_VERIFIED",
                        "页面资产尚未完成核验，无法导出。");
            }
            var url = presign.presignGet(objectKey(p.imageAssetId()));
            return new ExportPage(p.id(), p.ordinal(), p.imageAssetId(), sha,
                    url.url(), String.valueOf(url.expiresAt()));
        }).toList();
        var work = catalog.findWork(workIdOfRevision(chapterRevisionId)).orElseThrow();
        return new ExportManifest(SCHEMA_VERSION, props.platformId(),
                work.id().toString(), revision.chapterId().toString(), chapterRevisionId.toString(),
                work.readingDirection(), items);
    }

    private void requirePlatformId() {
        if (props.platformId() == null || props.platformId().isBlank()) {
            throw new ApiException(503, "SYSTEM_MISCONFIGURED",
                    "平台来源标识未配置，导出服务暂不可用。");
        }
    }

    private UUID workIdOfRevision(UUID revisionId) {
        return jdbc.queryForObject("""
                SELECT c.work_id FROM chapter_revisions r JOIN chapters c ON c.id = r.chapter_id
                WHERE r.id = ?
                """, UUID.class, revisionId);
    }

    private String objectKey(UUID assetId) {
        return assets.findObjectKey(assetId).orElseThrow(
                () -> new ApiException(404, "ASSET_NOT_FOUND", "页面资产不存在。"));
    }
}
