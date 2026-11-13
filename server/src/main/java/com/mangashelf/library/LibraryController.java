package com.mangashelf.library;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 阅读进度端点（DESIGN 16.1）；收藏已迁移到 /api/v1/me/favorites。 */
@RestController
@RequestMapping("/api/v1/me")
public class LibraryController {

    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    @PutMapping("/reading-progress/{chapterId}")
    public LibraryService.ProgressSnapshot saveProgress(@AuthUser CurrentUser user,
                                                        @PathVariable UUID chapterId,
                                                        @Valid @RequestBody LibraryService.SaveProgressRequest request) {
        return library.saveProgress(user, chapterId, request);
    }

    @GetMapping("/reading-progress/{chapterId}")
    public LibraryService.ProgressSnapshot progress(@AuthUser CurrentUser user,
                                                    @PathVariable UUID chapterId) {
        return library.progress(user, chapterId);
    }
}
