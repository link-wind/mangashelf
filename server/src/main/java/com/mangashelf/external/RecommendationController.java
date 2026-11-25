package com.mangashelf.external;

import com.mangashelf.identity.CurrentUser;
import com.mangashelf.identity.OptionalAuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 动画与漫画轻量推荐端点（登录态个性化，访客态公开精选）。 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/anime")
    public RecommendationService.AnimeRecommendationResult anime(
            @OptionalAuthUser CurrentUser user,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return recommendations.recommendAnime(user, limit, offset);
    }

    @GetMapping("/manga")
    public RecommendationService.MangaRecommendationResult manga(
            @OptionalAuthUser CurrentUser user,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return recommendations.recommendManga(user, limit, offset);
    }
}
