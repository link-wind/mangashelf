package com.mangashelf.catalog;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 漫画专区（M15）：索引与详情。 */
@RestController
@RequestMapping("/api/v1")
public class MangaController {

    private final MangaService manga;

    public MangaController(MangaService manga) {
        this.manga = manga;
    }

    @GetMapping("/manga")
    public MangaService.MangaListPage list(@AuthUser CurrentUser user,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "24") int limit,
                                           @RequestParam(required = false) String q) {
        return manga.list(user, cursor, limit, q);
    }

    @GetMapping("/manga/{workId}")
    public MangaService.MangaDetail detail(@AuthUser CurrentUser user, @PathVariable UUID workId) {
        return manga.detail(user, workId);
    }
}
