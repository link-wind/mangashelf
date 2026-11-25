package com.mangashelf.storage;

import java.util.UUID;

import com.mangashelf.config.ApiException;
import com.mangashelf.identity.CurrentUser;
import org.springframework.stereotype.Service;

/**
 * 资产访问授权（DESIGN 3.3 / 6.1 / 14.2）：所有签名按当前用户与内容实时状态判定，
 * 同一规则覆盖页面、缩略图与原上传；已发布作品的公开读取要求 PUBLIC + PUBLISHED + 版本 PUBLISHED。
 */
@Service
public class AssetAccessService {

    private final AssetRepository assets;
    private final UploadSessionRepository sessions;
    private final PresignService presign;

    public AssetAccessService(AssetRepository assets, UploadSessionRepository sessions,
                              PresignService presign) {
        this.assets = assets;
        this.sessions = sessions;
        this.presign = presign;
    }

    public PresignService.PresignedUrl authorizeRead(CurrentUser user, UUID assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(() ->
                new ApiException(404, "ASSET_NOT_FOUND", "资产不存在。"));

        // 1) 页面资产：追溯 page → revision → work，按作品实时状态判定
        var pageRef = assets.findPageRefByAsset(assetId);
        if (pageRef.isPresent()) {
            AssetRepository.PageAssetRef ref = pageRef.get();
            boolean owner = ref.workOwnerId().equals(user.id()) || user.isAdmin();
            boolean publicReadable = "PUBLIC".equals(ref.workVisibility())
                    && "PUBLISHED".equals(ref.workStatus())
                    && "PUBLISHED".equals(ref.revisionStatus());
            if (!owner && !publicReadable) {
                throw new ApiException(404, "ASSET_NOT_FOUND", "资产不存在。");
            }
            return presign.presignGet(asset.objectKey(), PresignService.MediaCacheProfile.IMMUTABLE_IMAGE);
        }

        // 2) 上传原件：会话所有者（或管理员）
        if (asset.uploadSessionId() != null) {
            UploadSession session = sessions.findById(asset.uploadSessionId()).orElseThrow(() ->
                    new ApiException(404, "ASSET_NOT_FOUND", "资产不存在。"));
            if (!session.ownerId().equals(user.id()) && !user.isAdmin()) {
                throw new ApiException(403, "FORBIDDEN", "无权访问该资产。");
            }
            return presign.presignGet(asset.objectKey(), profileOf(asset.kind()));
        }

        // 3) 其余资产（任务产物等）：所有者判定
        if (!asset.ownerId().equals(user.id()) && !user.isAdmin()) {
            throw new ApiException(403, "FORBIDDEN", "无权访问该资产。");
        }
        return presign.presignGet(asset.objectKey(), profileOf(asset.kind()));
    }

    private static PresignService.MediaCacheProfile profileOf(String kind) {
        if (kind == null) {
            return PresignService.MediaCacheProfile.DEFAULT;
        }
        return switch (kind) {
            case "READER_IMAGE", "THUMBNAIL", Asset.KIND_POSTER -> PresignService.MediaCacheProfile.IMMUTABLE_IMAGE;
            case Asset.KIND_VIDEO -> PresignService.MediaCacheProfile.IMMUTABLE_VIDEO;
            case Asset.KIND_SUBTITLE -> PresignService.MediaCacheProfile.IMMUTABLE_SUBTITLE;
            default -> PresignService.MediaCacheProfile.DEFAULT;
        };
    }
}
