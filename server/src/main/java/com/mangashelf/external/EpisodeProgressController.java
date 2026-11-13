package com.mangashelf.external;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 按集统一的播放进度端点（separate-favorites-and-history）：
 * 目录条目剧集写目录进度表，其余写作品进度表；物化前后进度键不变。
 */
@RestController
@RequestMapping("/api/v1/me")
public class EpisodeProgressController {

    private final ExternalService externals;

    public EpisodeProgressController(ExternalService externals) {
        this.externals = externals;
    }

    public record SaveRequest(@NotNull UUID episodeId, long positionMs, Long durationMs,
                              Integer expectedVersion) {}

    @GetMapping("/episode-progress")
    public ExternalService.EpisodeProgress progress(@AuthUser CurrentUser user,
                                                    @RequestParam UUID episodeId) {
        return externals.progress(user, episodeId);
    }

    @PutMapping("/episode-progress")
    public ExternalService.EpisodeProgress save(@AuthUser CurrentUser user,
                                                @Valid @RequestBody SaveRequest request) {
        return externals.saveProgress(user, request.episodeId(), request.positionMs(),
                request.durationMs(), request.expectedVersion());
    }
}
