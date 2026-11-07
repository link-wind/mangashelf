package com.mangashelf.storage;

import com.mangashelf.config.MangaShelfProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 上传与资产访问端点（DESIGN 16.1 上传 / 资产）。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class UploadController {

    private final UploadService uploads;
    private final MangaShelfProperties props;

    public UploadController(UploadService uploads, MangaShelfProperties props) {
        this.uploads = uploads;
        this.props = props;
    }

    public record CreateUploadRequest(
            @NotBlank @Pattern(regexp = "MANGA_PAGE") String purpose,
            @NotNull @Positive Long expectedSizeBytes,
            @NotBlank String contentType,
            String expectedSha256,
            @NotBlank String idempotencyKey) {
    }

    @PostMapping("/uploads")
    public ResponseEntity<UploadService.CreatedUpload> create(@Valid @RequestBody UploadService.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(uploads.create(props.demoOwnerId(), request));
    }

    @PostMapping("/uploads/{id}/complete")
    public ResponseEntity<UploadService.CompleteResult> complete(@PathVariable UUID id) {
        // 文件核验由 Worker 异步完成（DESIGN 10.2 步骤 6）：返回 202 与任务 ID
        return ResponseEntity.accepted().body(uploads.complete(id));
    }

    @GetMapping("/uploads/{id}")
    public UploadService.UploadSnapshot snapshot(@PathVariable UUID id) {
        return uploads.snapshot(id);
    }

    public record AssetAccessRequest(@NotBlank @Pattern(regexp = "READ") String purpose) {
    }

    @PostMapping("/assets/{id}/access")
    public PresignService.PresignedUrl assetAccess(@PathVariable UUID id,
                                                   @Valid @RequestBody AssetAccessRequest request) {
        return uploads.assetAccess(props.demoOwnerId(), id);
    }

    @GetMapping("/assets/{id}/access")
    public PresignService.PresignedUrl assetAccessGet(@PathVariable UUID id) {
        // 便捷读取入口（M0）；正式权限模型在 M1 收紧
        return uploads.assetAccess(props.demoOwnerId(), id);
    }
}
