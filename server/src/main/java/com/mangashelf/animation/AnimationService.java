package com.mangashelf.animation;

import com.mangashelf.catalog.CatalogRepository;
import com.mangashelf.catalog.CatalogService;
import com.mangashelf.catalog.ChapterRevision;
import com.mangashelf.config.ApiException;
import com.mangashelf.config.JdbcTime;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.ingestion.Job;
import com.mangashelf.ingestion.JobCreationService;
import com.mangashelf.storage.Asset;
import com.mangashelf.storage.AssetRepository;
import com.mangashelf.storage.PresignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 动画业务（DESIGN 9 / 13 / 14.2 / 16.1）。
 * 所有权、可见性、发布状态一起判定；公开读取要求 PUBLIC + PUBLISHED + 当前版本 PUBLISHED。
 */
@Service
public class AnimationService {

    private static final Logger log = LoggerFactory.getLogger(AnimationService.class);
    private static final int MAX_PAGE_LIMIT = 50;

    private final AnimationRepository animations;
    private final AssetRepository assets;
    private final CatalogRepository catalog;
    private final CatalogService catalogService;
    private final JobCreationService jobCreation;
    private final PresignService presign;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AnimationService(AnimationRepository animations, AssetRepository assets,
                            CatalogRepository catalog, CatalogService catalogService,
                            JobCreationService jobCreation, PresignService presign,
                            TransactionTemplate tx, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.animations = animations;
        this.assets = assets;
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.jobCreation = jobCreation;
        this.presign = presign;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    // ── 动画容器 ──

    public Animation create(CurrentUser user, String title, String description) {
        return create(user, title, description, Animation.VISIBILITY_PRIVATE);
    }

    public Animation create(CurrentUser user, String title, String description, String visibility) {
        UUID id = animations.insertAnimation(user.id(), title, description);
        if (visibility != null && !Animation.VISIBILITY_PRIVATE.equals(visibility)) {
            jdbc.update("UPDATE animations SET visibility = ? WHERE id = ?", visibility, id);
        }
        log.info("animation {} created by {}", id, user.id());
        return animations.findAnimation(id).orElseThrow();
    }

    public boolean isVisible(CurrentUser viewer, Animation animation) {
        return animation.ownerId().equals(viewer.id()) || viewer.isAdmin()
                || (Animation.VISIBILITY_PUBLIC.equals(animation.visibility())
                    && Animation.STATUS_PUBLISHED.equals(animation.status()));
    }

    public Animation requireVisibleAnimation(CurrentUser viewer, UUID animationId) {
        Animation animation = animations.findAnimation(animationId).orElseThrow(() ->
                new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。"));
        if (!isVisible(viewer, animation)) {
            throw new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。");
        }
        return animation;
    }

    private Animation requireOwnedAnimation(CurrentUser user, UUID animationId) {
        Animation animation = animations.findAnimation(animationId).orElseThrow(() ->
                new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。"));
        if (!animation.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。");
        }
        return animation;
    }

    public record AnimationView(Animation animation, List<RevisionView> revisions, boolean owner) {
    }

    public record RevisionView(UUID id, int revisionNo, String status, Long durationMs,
                               Integer videoWidth, Integer videoHeight, Instant createdAt) {
    }

    public AnimationView animationView(CurrentUser viewer, UUID animationId) {
        Animation animation = requireVisibleAnimation(viewer, animationId);
        boolean owner = animation.ownerId().equals(viewer.id()) || viewer.isAdmin();
        List<RevisionView> revisions = owner
                ? animations.listRevisions(animationId).stream()
                        .map(r -> new RevisionView(r.id(), r.revisionNo(), r.status(), r.durationMs(),
                                r.videoWidth(), r.videoHeight(), r.createdAt()))
                        .toList()
                : List.of();
        return new AnimationView(animation, revisions, owner);
    }

    public record AnimationListPage(List<Animation> items, String nextCursor) {
    }

    public AnimationListPage list(CurrentUser viewer, String cursor, int limit, String q) {
        var c = CatalogService.decodeCursor(cursor);
        int effectiveLimit = Math.min(limit, MAX_PAGE_LIMIT);
        List<Animation> items = animations.listVisible(viewer.id(), q, JdbcTime.t(c.createdAt()),
                c.id(), effectiveLimit);
        String next = items.size() == effectiveLimit
                ? CatalogService.encodeCursor(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new AnimationListPage(items, next);
    }

    // ── 版本 ──

    public record RevisionCreated(UUID revisionId) {
    }

    public RevisionCreated createRevision(CurrentUser user, UUID animationId) {
        requireOwnedAnimation(user, animationId);
        int revisionNo = animations.nextRevisionNo(animationId);
        return tx.execute(status -> {
            UUID revisionId = animations.insertRevision(animationId, revisionNo);
            log.info("animation revision {} (#{}) created for {}", revisionId, revisionNo, animationId);
            return new RevisionCreated(revisionId);
        });
    }

    public AnimationRevision requireOwnedRevision(CurrentUser user, UUID revisionId) {
        AnimationRevision revision = animations.findRevision(revisionId).orElseThrow(() ->
                new ApiException(404, "REVISION_NOT_FOUND", "动画版本不存在。"));
        Animation animation = animations.findAnimation(revision.animationId()).orElseThrow();
        if (!animation.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(404, "REVISION_NOT_FOUND", "动画版本不存在。");
        }
        return revision;
    }

    // ── verify（DESIGN 13.3/13.4 前置：绑定资产 + 来源清单）──

    public record SourceRequest(UUID chapterRevisionId, int pageStart, int pageEnd, int ordinal) {
    }

    public record VerifyRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("video_asset_id") UUID videoAssetId,
            @com.fasterxml.jackson.annotation.JsonProperty("poster_asset_id") UUID posterAssetId,
            @com.fasterxml.jackson.annotation.JsonProperty("subtitle_asset_id") UUID subtitleAssetId,
            List<SourceRequest> sources) {
    }

    public record VerifyAccepted(
            @com.fasterxml.jackson.annotation.JsonProperty("revision_id") UUID revisionId,
            @com.fasterxml.jackson.annotation.JsonProperty("job_id") UUID jobId) {
    }

    public VerifyAccepted verify(CurrentUser user, UUID revisionId, VerifyRequest request) {
        AnimationRevision revision = requireOwnedRevision(user, revisionId);
        if (!AnimationRevision.STATUS_DRAFT.equals(revision.status())
                && !AnimationRevision.STATUS_FAILED.equals(revision.status())) {
            throw new ApiException(409, "INVALID_STATE", "版本当前状态不允许提交验证。当前：" + revision.status());
        }

        // 资产绑定：VERIFIED + 归属同一用户 + kind 匹配（DESIGN 13.4-2）
        UUID videoAssetId = requireAssetOfKind(user, request.videoAssetId(), Asset.KIND_VIDEO, "videoAssetId");
        UUID posterAssetId = request.posterAssetId() == null ? null
                : requireAssetOfKind(user, request.posterAssetId(), Asset.KIND_POSTER, "posterAssetId");
        UUID subtitleAssetId = request.subtitleAssetId() == null ? null
                : requireAssetOfKind(user, request.subtitleAssetId(), Asset.KIND_SUBTITLE, "subtitleAssetId");

        // 来源：请求可省略（协议侧 publications 已登记，DESIGN 17.3），沿用已登记清单
        List<AnimationSource> sources;
        boolean replaceSources = request.sources() != null && !request.sources().isEmpty();
        if (replaceSources) {
            sources = registerSources(user, revisionId, request.sources());
        } else {
            sources = animations.listSources(revisionId);
            if (sources.isEmpty()) {
                throw new ApiException(400, "SOURCES_REQUIRED", "至少需要一条来源映射。");
            }
        }
        String manifestSha = sourceManifestSha(sources);

        UUID jobId = tx.execute(status -> {
            if (replaceSources) {
                animations.replaceSources(revisionId, sources);
            }
            animations.bindAssetsAndMedia(revisionId, videoAssetId, posterAssetId, subtitleAssetId,
                    null, null, null, null, null, null, manifestSha);
            animations.markRevisionStatus(revisionId, AnimationRevision.STATUS_DRAFT, null, null);
            return jobCreation.create(Job.KIND_ANIMATION_VERIFY, user.id(), null, null, revisionId,
                    null, null, "verify:" + revisionId);
        });
        return new VerifyAccepted(revisionId, jobId);
    }

    /** 来源登记与校验（DESIGN 13.2/8.3）：固定章节版本可引用、页码范围 ≤ 页数、ordinal 唯一。 */
    public List<AnimationSource> registerSources(CurrentUser user, UUID revisionId,
                                                 List<AnimationService.SourceRequest> requests) {
        Set<Integer> ordinals = new HashSet<>();
        List<AnimationSource> sources = new ArrayList<>();
        for (SourceRequest s : requests) {
            ChapterRevision chapterRev = catalogService.requireOwnedRevision(user, s.chapterRevisionId());
            if (!ChapterRevision.STATUS_PUBLISHED.equals(chapterRev.status())
                    && !ChapterRevision.STATUS_READY.equals(chapterRev.status())) {
                throw new ApiException(409, "SOURCE_NOT_READABLE",
                        "来源章节版本不可引用（需 READY 或 PUBLISHED）。");
            }
            if (chapterRev.pageCount() <= 0) {
                throw new ApiException(409, "SOURCE_NOT_READY", "来源章节版本还没有页面清单。");
            }
            if (s.pageStart() < 1 || s.pageStart() > s.pageEnd() || s.pageEnd() > chapterRev.pageCount()) {
                throw new ApiException(400, "PAGE_RANGE_INVALID",
                        "页码范围无效：来源版本共 " + chapterRev.pageCount() + " 页。",
                        Map.of("chapter_revision_id", s.chapterRevisionId().toString(),
                               "page_start", s.pageStart(), "page_end", s.pageEnd()));
            }
            if (!ordinals.add(s.ordinal())) {
                throw new ApiException(400, "ORDINAL_DUPLICATE", "来源顺序重复。");
            }
            sources.add(new AnimationSource(UUID.randomUUID(), revisionId, s.chapterRevisionId(),
                    s.pageStart(), s.pageEnd(), s.ordinal()));
        }
        tx.executeWithoutResult(status -> animations.replaceSources(revisionId, sources));
        return sources;
    }

    private UUID requireAssetOfKind(CurrentUser user, UUID assetId, String kind, String field) {
        Asset asset = assets.findById(assetId).orElseThrow(() ->
                new ApiException(404, "ASSET_NOT_FOUND", "绑定的资产不存在：" + field));
        if (!asset.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(404, "ASSET_NOT_FOUND", "绑定的资产不存在：" + field);
        }
        if (!Asset.STATE_VERIFIED.equals(asset.state())) {
            throw new ApiException(409, "ASSET_NOT_VERIFIED", "绑定资产尚未通过核验：" + field);
        }
        if (!kind.equals(asset.kind())) {
            throw new ApiException(400, "ASSET_KIND_MISMATCH",
                    "资产类型不匹配：" + field + " 需要 " + kind + "，实际 " + asset.kind());
        }
        return assetId;
    }

    static String sourceManifestSha(List<AnimationSource> sources) {
        StringBuilder sb = new StringBuilder();
        for (AnimationSource s : sources.stream()
                .sorted(java.util.Comparator.comparingInt(AnimationSource::ordinal)).toList()) {
            sb.append(s.ordinal()).append('|').append(s.chapterRevisionId()).append('|')
              .append(s.pageStart()).append('|').append(s.pageEnd()).append('\n');
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 发布（DESIGN 13.4 五道校验）──

    public AnimationRevision publishRevision(CurrentUser user, UUID revisionId) {
        AnimationRevision revision = requireOwnedRevision(user, revisionId);
        Animation animation = animations.findAnimation(revision.animationId()).orElseThrow();
        return tx.execute(status -> {
            animations.lockAnimation(animation.id());
            AnimationRevision fresh = animations.findRevision(revisionId).orElseThrow();
            // 1) 操作者拥有动画（requireOwnedRevision 已验）；2) 视频资产核验完成且归属正确
            if (fresh.videoAssetId() == null) {
                throw new ApiException(409, "VIDEO_NOT_BOUND", "版本尚未绑定视频资产。");
            }
            Asset video = assets.findById(fresh.videoAssetId()).orElseThrow();
            if (!Asset.STATE_VERIFIED.equals(video.state())) {
                throw new ApiException(409, "VIDEO_NOT_VERIFIED", "视频资产未通过核验。");
            }
            // 3) 状态必须是 READY（媒体校验通过，DESIGN 9）
            if (!AnimationRevision.STATUS_READY.equals(fresh.status())) {
                throw new ApiException(409, "INVALID_STATE",
                        "版本当前状态不允许发布（期望 READY）。当前：" + fresh.status());
            }
            // 4) 来源清单完整、页码范围仍有效、公开动画的来源满足公开条件（DESIGN 13.4-3/4）
            List<AnimationSource> sources = animations.listSources(revisionId);
            if (sources.isEmpty()) {
                throw new ApiException(409, "SOURCES_REQUIRED", "来源清单为空。");
            }
            for (AnimationSource s : sources) {
                ChapterRevision chapterRev = catalog.findRevision(s.chapterRevisionId()).orElseThrow();
                Animation targetAnimation = animations.findAnimation(animation.id()).orElseThrow();
                if (Animation.VISIBILITY_PUBLIC.equals(targetAnimation.visibility())) {
                    catalogService.requireVisibleWork(user, workIdOf(chapterRev));
                    if (!ChapterRevision.STATUS_PUBLISHED.equals(chapterRev.status())) {
                        throw new ApiException(409, "SOURCE_NOT_PUBLIC",
                                "公开动画的来源章节版本必须已发布。");
                    }
                }
                if (s.pageEnd() > chapterRev.pageCount()) {
                    throw new ApiException(409, "PAGE_RANGE_INVALID",
                            "来源页码范围超出当前版本页数（版本可能已更换）。");
                }
            }
            // 5) 事务锁下置 PUBLISHED 并切换当前版本；容器 DRAFT/WITHDRAWN 随首次/重新发布流转
            animations.markRevisionStatus(revisionId, AnimationRevision.STATUS_PUBLISHED, null, null);
            animations.switchCurrentRevision(animation.id(), revisionId);
            if (!Animation.STATUS_PUBLISHED.equals(animation.status())) {
                animations.updateAnimationStatus(animation.id(), Animation.STATUS_PUBLISHED);
            }
            log.info("animation revision {} published for {}", revisionId, animation.id());
            return animations.findRevision(revisionId).orElseThrow();
        });
    }

    /** 由章节版本反查作品（只读跨表，访问授权属内容判定）。 */
    private UUID workIdOf(ChapterRevision revision) {
        return jdbc.queryForObject("""
                SELECT c.work_id FROM chapter_revisions r JOIN chapters c ON c.id = r.chapter_id
                WHERE r.id = ?
                """, UUID.class, revision.id());
    }

    public record PatchAnimationRequest(String title, String description, String visibility) {
    }

    public Animation patch(CurrentUser user, UUID animationId, PatchAnimationRequest request) {
        Animation animation = requireOwnedAnimation(user, animationId);
        String title = request.title() != null ? request.title() : animation.title();
        String description = request.description() != null ? request.description() : animation.description();
        String visibility = request.visibility() != null ? request.visibility() : animation.visibility();
        if (!Animation.VISIBILITY_PRIVATE.equals(visibility)
                && !Animation.VISIBILITY_PUBLIC.equals(visibility)) {
            throw new ApiException(400, "INVALID_VISIBILITY", "visibility 只能为 PRIVATE 或 PUBLIC。");
        }
        jdbc.update("""
                UPDATE animations SET title = ?, description = ?, visibility = ?, updated_at = now()
                WHERE id = ?
                """, title, description, visibility, animationId);
        return animations.findAnimation(animationId).orElseThrow();
    }

    // ── 下架（DESIGN 16.1）──

    public Animation withdraw(CurrentUser user, UUID animationId) {
        requireOwnedAnimation(user, animationId);
        animations.updateAnimationStatus(animationId, Animation.STATUS_WITHDRAWN);
        return animations.findAnimation(animationId).orElseThrow();
    }

    // ── 播放（DESIGN 14.2）──

    public record SourceView(UUID chapterRevisionId, int pageStart, int pageEnd,
                             UUID chapterId, int chapterOrdinal, String chapterTitle,
                             UUID workId, String workTitle) {
    }

    public record Playback(UUID animationId, UUID revisionId, String revisionStatus,
                           Long durationMs, String videoUrl, String posterUrl, String subtitleUrl,
                           java.time.Instant videoExpiresAt,
                           WatchProgressView progress, boolean revisionMatches,
                           List<SourceView> sources) {
    }

    public record WatchProgressView(UUID revisionId, long positionMs, int version) {
    }

    public Playback playback(CurrentUser viewer, UUID animationId) {
        Animation animation = requireVisibleAnimation(viewer, animationId);
        if (animation.currentRevisionId() == null) {
            throw new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。");
        }
        AnimationRevision revision = animations.findRevision(animation.currentRevisionId()).orElseThrow();
        if (!Animation.STATUS_PUBLISHED.equals(animation.status())
                || !AnimationRevision.STATUS_PUBLISHED.equals(revision.status())) {
            throw new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。");
        }
        if (revision.videoAssetId() == null) {
            throw new ApiException(404, "ANIMATION_NOT_FOUND", "动画不存在。");
        }
        String videoKey = assets.findObjectKey(revision.videoAssetId()).orElseThrow();
        var video = presign.presignGet(videoKey);
        String posterUrl = null;
        if (revision.posterAssetId() != null) {
            posterUrl = presign.presignGet(
                    assets.findObjectKey(revision.posterAssetId()).orElseThrow()).url();
        }
        String subtitleUrl = null;
        if (revision.subtitleAssetId() != null) {
            subtitleUrl = presign.presignGet(
                    assets.findObjectKey(revision.subtitleAssetId()).orElseThrow()).url();
        }
        var progress = animations.findWatchProgress(viewer.id(), animationId)
                .map(p -> new WatchProgressView(p.revisionId(), p.positionMs(), p.version()))
                .orElse(null);
        boolean matches = progress == null || progress.revisionId().equals(revision.id());
        List<SourceView> sources = animations.listSourcesWithWork(revision.id()).stream()
                .map(s -> new SourceView(s.chapterRevisionId(), s.pageStart(), s.pageEnd(),
                        s.chapterId(), s.chapterOrdinal(), s.chapterTitle(), s.workId(), s.workTitle()))
                .toList();
        return new Playback(animationId, revision.id(), revision.status(), revision.durationMs(),
                video.url(), posterUrl, subtitleUrl, video.expiresAt(), progress, matches, sources);
    }

    // ── 页级关联（DESIGN 4.1 / 16.1）──

    public record PageAdaptationView(UUID animationId, String title, UUID revisionId,
                                     int pageStart, int pageEnd) {
    }

    public List<PageAdaptationView> pageAdaptations(CurrentUser viewer, UUID chapterRevisionId, int page) {
        // 读者必须能阅读该章节版本（DESIGN 3.3：关联查询同一权限规则，与阅读清单共用判定）
        catalogService.requireReadableRevision(viewer, chapterRevisionId);
        return animations.findPageAdaptations(chapterRevisionId, page).stream()
                .filter(a -> Animation.VISIBILITY_PUBLIC.equals(a.visibility()))
                .map(a -> new PageAdaptationView(a.animationId(), a.title(), a.revisionId(),
                        a.pageStart(), a.pageEnd()))
                .toList();
    }

    // ── 观看进度（DESIGN 14.3）──

    public record SaveWatchProgressRequest(long positionMs, int expectedVersion) {
    }

    public WatchProgressView saveWatchProgress(CurrentUser user, UUID animationId,
                                               SaveWatchProgressRequest request) {
        Animation animation = requireVisibleAnimation(user, animationId);
        UUID boundRevision = animation.currentRevisionId() != null ? animation.currentRevisionId() : null;
        if (boundRevision == null) {
            throw new ApiException(409, "INVALID_STATE", "动画还没有已发布版本。");
        }
        if (request.positionMs() < 0) {
            throw new ApiException(400, "INVALID_POSITION", "播放位置不合法。");
        }
        return animations.upsertWatchProgress(user.id(), animationId, boundRevision,
                request.positionMs(), request.expectedVersion())
                .map(v -> new WatchProgressView(boundRevision, request.positionMs(), v))
                .orElseGet(() -> {
                    var current = animations.findWatchProgress(user.id(), animationId).orElseThrow();
                    throw new ApiException(409, "PROGRESS_CONFLICT", "进度已被其他设备更新，请刷新。",
                            Map.of("current", new WatchProgressView(current.revisionId(),
                                    current.positionMs(), current.version())));
                });
    }

    public WatchProgressView watchProgress(CurrentUser user, UUID animationId) {
        requireVisibleAnimation(user, animationId);
        return animations.findWatchProgress(user.id(), animationId)
                .map(p -> new WatchProgressView(p.revisionId(), p.positionMs(), p.version()))
                .orElseThrow(() -> new ApiException(404, "PROGRESS_NOT_FOUND", "没有观看进度。"));
    }
}
