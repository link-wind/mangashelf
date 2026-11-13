package com.mangashelf.library;

import com.mangashelf.catalog.CatalogService;
import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 统一收藏：本地作品、外部漫画、外部番剧目录条目（separate-favorites-and-history）。 */
@Service
public class FavoritesService {

    private static final int MAX_TITLE = 200;

    private final FavoriteRepository favorites;
    private final CatalogService catalog;

    public FavoritesService(FavoriteRepository favorites, CatalogService catalog) {
        this.favorites = favorites;
        this.catalog = catalog;
    }

    public List<FavoriteRepository.FavoriteRow> list(CurrentUser user, String type) {
        String normalized = type == null || type.isBlank() || "all".equals(type) ? null : type;
        if (normalized != null && !List.of("work", "manga", "catalog").contains(normalized)) {
            throw new ApiException(400, "INVALID_ARGUMENT", "未知收藏类型：" + type);
        }
        return favorites.list(user.id(), normalized);
    }

    public void addWork(CurrentUser user, UUID workId) {
        catalog.requireVisibleWork(user, workId);
        favorites.addWork(user.id(), workId);
    }

    public void removeWork(CurrentUser user, UUID workId) {
        favorites.removeWork(user.id(), workId);
    }

    public void addManga(CurrentUser user, String source, String mangaId, String title) {
        if (source == null || source.isBlank() || mangaId == null || mangaId.isBlank()) {
            throw new ApiException(400, "INVALID_ARGUMENT", "缺少 source 或 mangaId。");
        }
        String safeTitle = title == null || title.isBlank() ? null : title.trim();
        if (safeTitle != null && safeTitle.length() > MAX_TITLE) {
            safeTitle = safeTitle.substring(0, MAX_TITLE);
        }
        favorites.addManga(user.id(), source.trim(), mangaId.trim(), safeTitle);
    }

    public void removeManga(CurrentUser user, String source, String mangaId) {
        if (source == null || source.isBlank() || mangaId == null || mangaId.isBlank()) {
            throw new ApiException(400, "INVALID_ARGUMENT", "缺少 source 或 mangaId。");
        }
        favorites.removeManga(user.id(), source.trim(), mangaId.trim());
    }

    public void addCatalog(CurrentUser user, UUID itemId) {
        if (!favorites.catalogItemExists(itemId)) {
            throw new ApiException(404, "ITEM_NOT_FOUND", "目录条目不存在。");
        }
        favorites.addCatalog(user.id(), itemId);
    }

    public void removeCatalog(CurrentUser user, UUID itemId) {
        favorites.removeCatalog(user.id(), itemId);
    }
}
