package com.mangashelf.library;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 书架与进度端点（DESIGN 16.1 书架 / 进度）。 */
@RestController
@RequestMapping("/api/v1/me")
public class LibraryController {

    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    @PutMapping("/library/{workId}")
    public Map<String, Object> addToShelf(@AuthUser CurrentUser user, @PathVariable UUID workId) {
        boolean created = library.addToShelf(user, workId);
        return Map.of("work_id", workId, "created", created);
    }

    @DeleteMapping("/library/{workId}")
    public Map<String, Object> removeFromShelf(@AuthUser CurrentUser user, @PathVariable UUID workId) {
        library.removeFromShelf(user, workId);
        return Map.of("work_id", workId, "removed", true);
    }

    @GetMapping("/library")
    public LibraryService.ShelfView shelf(@AuthUser CurrentUser user) {
        return library.shelf(user);
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
