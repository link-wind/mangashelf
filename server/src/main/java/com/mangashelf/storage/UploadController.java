package com.mangashelf.storage;

import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 上传与资产访问端点（DESIGN 16.1 上传 / 资产）；所有权与可见性实时判定。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class UploadController {

    private final UploadService uploads;
    private final AssetAccessService assetAccess;

    public UploadController(UploadService uploads, AssetAccessService assetAccess) {
        this.uploads = uploads;
        this.assetAccess = assetAccess;
    }

    public record CreateUploadRequest(
            @NotBlank @Pattern(regexp = "MANGA_PAGE") String purpose,
            @NotNull @Positive Long expectedSizeBytes,
            @NotBlank String contentType,
            String expectedSha256,
            @NotBlank String idempotencyKey) {
    }

    @PostMapping("/uploads")
    public UploadService.CreatedUpload create(@AuthUser CurrentUser user,
                                              @Valid @RequestBody UploadService.CreateRequest request) {
        // Bearer 客户端（MangaWeaver）上传走 animations:write（DESIGN 17.1）；浏览器用户不受影响
        com.mangashelf.identity.TokenScopes.require(com.mangashelf.identity.Scopes.ANIMATIONS_WRITE);
        return uploads.create(user.id(), request);
    }

    @PostMapping("/uploads/{id}/complete")
    public ResponseEntity<UploadService.CompleteResult> complete(@AuthUser CurrentUser user,
                                                                 @PathVariable UUID id) {
        // 文件核验由 Worker 异步完成（DESIGN 10.2 步骤 6）：返回 202 与任务 ID
        return ResponseEntity.accepted().body(uploads.complete(user, id));
    }

    @GetMapping("/uploads/{id}")
    public UploadService.UploadSnapshot snapshot(@AuthUser CurrentUser user, @PathVariable UUID id) {
        return uploads.snapshot(user, id);
    }

    public record AssetAccessRequest(@NotBlank @Pattern(regexp = "READ") String purpose) {
    }

    @PostMapping("/assets/{id}/access")
    public PresignService.PresignedUrl assetAccess(@AuthUser CurrentUser user, @PathVariable UUID id,
                                                   @Valid @RequestBody AssetAccessRequest request) {
        return assetAccess.authorizeRead(user, id);
    }
}
