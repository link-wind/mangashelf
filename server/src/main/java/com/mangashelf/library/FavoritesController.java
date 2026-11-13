package com.mangashelf.library;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 统一收藏端点（separate-favorites-and-history）：三类目标，重复收藏幂等。 */
@RestController
@RequestMapping("/api/v1/me/favorites")
public class FavoritesController {

    private final FavoritesService favorites;

    public FavoritesController(FavoritesService favorites) {
        this.favorites = favorites;
    }

    @GetMapping
    public List<FavoriteRepository.FavoriteRow> list(@AuthUser CurrentUser user,
                                                     @RequestParam(defaultValue = "all") String type) {
        return favorites.list(user, type);
    }

    @PutMapping("/work/{workId}")
    public ResponseEntity<Void> addWork(@AuthUser CurrentUser user, @PathVariable UUID workId) {
        favorites.addWork(user, workId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/work/{workId}")
    public ResponseEntity<Void> removeWork(@AuthUser CurrentUser user, @PathVariable UUID workId) {
        favorites.removeWork(user, workId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/manga")
    public ResponseEntity<Void> addManga(@AuthUser CurrentUser user,
                                         @RequestParam String source,
                                         @RequestParam String mangaId,
                                         @RequestParam(required = false) String title) {
        favorites.addManga(user, source, mangaId, title);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/manga")
    public ResponseEntity<Void> removeManga(@AuthUser CurrentUser user,
                                            @RequestParam String source,
                                            @RequestParam String mangaId) {
        favorites.removeManga(user, source, mangaId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/catalog/{itemId}")
    public ResponseEntity<Void> addCatalog(@AuthUser CurrentUser user, @PathVariable UUID itemId) {
        favorites.addCatalog(user, itemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/catalog/{itemId}")
    public ResponseEntity<Void> removeCatalog(@AuthUser CurrentUser user, @PathVariable UUID itemId) {
        favorites.removeCatalog(user, itemId);
        return ResponseEntity.noContent().build();
    }
}
