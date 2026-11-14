package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部漫画历史端点（M16 残留）：搜索/详情/页图已统一到 /api/v1/manga-sources/mangadex，
 * MangaDex 源移除后（mobile-nav-genre-remove-mangadex）本控制器仅保留历史截图链接的
 * 410 提示，处理旧收藏/历史记录里的遗留链接。
 */
@RestController
@RequestMapping("/api/v1/external-manga")
public class ExternalMangaController {

    @org.springframework.web.bind.annotation.GetMapping("/captured/{hash}/{ordinal}.png")
    public org.springframework.http.ResponseEntity<byte[]> captured(
            @AuthUser CurrentUser user,
            @org.springframework.web.bind.annotation.PathVariable String hash,
            @org.springframework.web.bind.annotation.PathVariable int ordinal) throws Exception {
        throw new ApiException(410, "CAPTURE_REPLACED", "截图阅读已替换，请重新打开章节");
    }
}
