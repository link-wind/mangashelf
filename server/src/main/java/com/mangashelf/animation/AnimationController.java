package com.mangashelf.animation;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 动画端点（DESIGN 16.1 动画 / 换版 / 校验 / 发布 / 下架 / 播放 / 关联 / 进度）。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AnimationController {

    private final AnimationService animations;

    public AnimationController(AnimationService animations) {
        this.animations = animations;
    }

    public record CreateAnimationRequest(@NotBlank @Size(max = 200) String title,
                                         @Size(max = 2000) String description) {
    }

    @PostMapping("/animations")
    public Animation create(@AuthUser CurrentUser user,
                            @Valid @RequestBody CreateAnimationRequest request) {
        return animations.create(user, request.title(),
                request.description() == null ? "" : request.description());
    }

    @GetMapping("/animations")
    public AnimationService.AnimationListPage list(@AuthUser CurrentUser user,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "20") int limit,
                                                   @RequestParam(required = false) String q) {
        return animations.list(user, cursor, limit, q);
    }

    @GetMapping("/animations/{id}")
    public AnimationService.AnimationView detail(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return animations.animationView(user, id);
    }

    @PostMapping("/animations/{id}/revisions")
    public AnimationService.RevisionCreated createRevision(@AuthUser CurrentUser user,
                                                           @PathVariable UUID id) {
        return animations.createRevision(user, id);
    }

    @GetMapping("/animation-revisions/{id}")
    public AnimationRevision revision(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return animations.requireOwnedRevision(user, id);
    }

    @PostMapping("/animation-revisions/{id}/verify")
    public AnimationService.VerifyAccepted verify(@AuthUser CurrentUser user, @PathVariable UUID id,
                                                  @Valid @RequestBody AnimationService.VerifyRequest request) {
        com.mangashelf.identity.TokenScopes.require(com.mangashelf.identity.Scopes.ANIMATIONS_WRITE);
        return animations.verify(user, id, request);
    }

    @PostMapping("/animation-revisions/{id}/publish")
    public AnimationRevision publishRevision(@AuthUser CurrentUser user, @PathVariable UUID id) {
        com.mangashelf.identity.TokenScopes.require(com.mangashelf.identity.Scopes.ANIMATIONS_PUBLISH);
        return animations.publishRevision(user, id);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/animations/{id}")
    public Animation patch(@AuthUser CurrentUser user, @PathVariable UUID id,
                           @Valid @RequestBody AnimationService.PatchAnimationRequest request) {
        return animations.patch(user, id, request);
    }

    @PostMapping("/animations/{id}/withdraw")
    public Animation withdraw(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return animations.withdraw(user, id);
    }

    @GetMapping("/animations/{id}/playback")
    public AnimationService.Playback playback(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return animations.playback(user, id);
    }

    @GetMapping("/chapter-revisions/{id}/animations")
    public List<AnimationService.PageAdaptationView> pageAdaptations(
            @AuthUser CurrentUser user, @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "1") int page) {
        return animations.pageAdaptations(user, id, page);
    }

    public record SaveWatchProgressRequest(long positionMs, int expectedVersion) {
    }

    @PutMapping("/me/watch-progress/{animationId}")
    public AnimationService.WatchProgressView saveWatchProgress(@AuthUser CurrentUser user,
                                                                @PathVariable UUID animationId,
                                                                @Valid @RequestBody SaveWatchProgressRequest request) {
        return animations.saveWatchProgress(user, animationId,
                new AnimationService.SaveWatchProgressRequest(request.positionMs(), request.expectedVersion()));
    }

    @GetMapping("/me/watch-progress/{animationId}")
    public AnimationService.WatchProgressView watchProgress(@AuthUser CurrentUser user,
                                                            @PathVariable UUID animationId) {
        return animations.watchProgress(user, animationId);
    }
}
