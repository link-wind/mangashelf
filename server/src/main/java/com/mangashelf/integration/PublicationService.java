package com.mangashelf.integration;

import com.mangashelf.animation.Animation;
import com.mangashelf.animation.AnimationRepository;
import com.mangashelf.animation.AnimationRevision;
import com.mangashelf.animation.AnimationService;
import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import com.mangashelf.integration.IntegrationController.PublicationDraft;
import com.mangashelf.integration.IntegrationController.PublicationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 成片发布回传（DESIGN 17.3）：以（用户、接入类型、客户端发布请求键）为幂等边界。
 * 同键返回同一草稿；新键创建新修订版（可选定向既有动画），不覆盖既有成片。
 * requested_visibility 只记录意图，创建草稿不对读者公开。
 */
@Service
public class PublicationService {

    public static final String ORIGIN_CLIENT = "mangaweaver";
    public static final int SCHEMA_VERSION = 1;

    private final AnimationRepository animations;
    private final AnimationService animationService;

    public PublicationService(AnimationRepository animations, AnimationService animationService) {
        this.animations = animations;
        this.animationService = animationService;
    }

    public PublicationDraft createOrGet(CurrentUser user, PublicationRequest request) {
        if (!"mangaweaver".equals(ORIGIN_CLIENT)) { // 常量自检，防误改
            throw new IllegalStateException("origin client mismatch");
        }
        var existing = animations.findRevisionByOrigin(user.id(), ORIGIN_CLIENT, request.originRunRef());
        if (existing.isPresent()) {
            return toDraft(existing.get());
        }

        Animation animation;
        if (request.animationId() != null) {
            // 定向换版：客户端携带上一次发布回执中的 animationId
            animation = animations.findAnimation(request.animationId()).orElseThrow(
                    () -> new ApiException(404, "ANIMATION_NOT_FOUND", "目标动画不存在。"));
            if (!animation.ownerId().equals(user.id()) && !user.isAdmin()) {
                throw new ApiException(404, "ANIMATION_NOT_FOUND", "目标动画不存在。");
            }
        } else {
            String visibility = request.requestedVisibility() == null
                    ? Animation.VISIBILITY_PRIVATE : request.requestedVisibility();
            if (!Animation.VISIBILITY_PRIVATE.equals(visibility)
                    && !Animation.VISIBILITY_PUBLIC.equals(visibility)) {
                throw new ApiException(400, "INVALID_VISIBILITY", "requestedVisibility 不合法。");
            }
            // requestedVisibility 只记录意图：DRAFT 草稿不会对读者公开（DESIGN 17.3）
            animation = animationService.create(user, request.title(),
                    request.description() == null ? "" : request.description(), visibility);
        }
        AnimationService.RevisionCreated created =
                animationService.createRevision(user, animation.id());
        AnimationRevision revision = animations.findRevision(created.revisionId()).orElseThrow();
        animationService.registerSources(user, revision.id(),
                request.sources().stream()
                        .map(s -> new AnimationService.SourceRequest(s.chapterRevisionId(),
                                s.pageStart(), s.pageEnd(), s.ordinal()))
                        .toList());
        animations.setOrigin(revision.id(), ORIGIN_CLIENT, request.originRunRef());
        log.info("publication draft {} created for animation {} by {} (run {})",
                revision.id(), animation.id(), user.id(), request.originRunRef());
        return toDraft(revision);
    }

    public PublicationDraft status(CurrentUser user, String originRunRef) {
        AnimationRevision revision = animations
                .findRevisionByOrigin(user.id(), ORIGIN_CLIENT, originRunRef)
                .orElseThrow(() -> new ApiException(404, "PUBLICATION_NOT_FOUND",
                        "没有找到该发布键对应的草稿。"));
        return toDraft(revision);
    }

    private PublicationDraft toDraft(AnimationRevision revision) {
        Animation animation = animations.findAnimation(revision.animationId()).orElseThrow();
        boolean published = Animation.STATUS_PUBLISHED.equals(animation.status())
                && AnimationRevision.STATUS_PUBLISHED.equals(revision.status())
                && revision.id().equals(animation.currentRevisionId());
        String watchUrl = published ? "/watch/" + animation.id() : null;
        return new PublicationDraft(SCHEMA_VERSION, animation.id(), revision.id(),
                revision.status(), watchUrl);
    }

    private static final Logger log = LoggerFactory.getLogger(PublicationService.class);
}
