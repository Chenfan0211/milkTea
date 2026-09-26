package com.wuling.file.controller;

import com.wuling.common.api.Result;
import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import com.wuling.file.service.ImageStorageService;
import com.wuling.file.service.ImageStorageService.ImageNotFoundException;
import com.wuling.file.service.ImageStorageService.InvalidFilePathException;
import com.wuling.file.service.ImageStorageService.PublicImage;
import com.wuling.file.service.ImageStorageService.StoredImage;
import com.wuling.file.service.UploadValidationService;
import com.wuling.file.service.UploadValidationService.ValidatedUpload;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文件上传与公开读取端点。
 */
@RestController
public class FileController {

    private final UploadValidationService uploadService;
    private final ImageStorageService imageStorageService;

    public FileController(UploadValidationService uploadService,
                          ImageStorageService imageStorageService) {
        this.uploadService = uploadService;
        this.imageStorageService = imageStorageService;
    }

    /** 服务自述（不暴露任何配置细节） */
    @GetMapping("/api/v1/files/service-info")
    public Map<String, Object> info() {
        return Map.of(
                "service", "file-service",
                "phase", "phase-2-image-storage",
                "status", "up"
        );
    }

    /**
     * 管理员上传图片。实际鉴权由 {@code AdminAuthInterceptor} 完成。
     */
    @PostMapping("/api/v1/files/images")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            StoredImage result = imageStorageService.store(
                    file.getOriginalFilename(), file.getBytes(), file.getContentType());
            return ResponseEntity.ok(Result.ok(result));
        } catch (InvalidFileException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            return badRequest("文件读取失败");
        }
    }

    /**
     * 公开读取已经过重编码的图片。只允许 UUID 文件名和 jpg/png 后缀。
     */
    @GetMapping("/api/v1/files/public/{storedName:.+}")
    public ResponseEntity<byte[]> readPublicImage(@PathVariable String storedName) {
        try {
            PublicImage image = imageStorageService.loadPublicImage(storedName);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.mimeType()))
                    .contentLength(image.bytes().length)
                    .body(image.bytes());
        } catch (InvalidFilePathException e) {
            return ResponseEntity.badRequest().build();
        } catch (ImageNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 校验上传文件（内部联调用，保留原有行为）。
     */
    @PostMapping("/api/v1/files/validate")
    public ResponseEntity<?> validate(@RequestParam("file") MultipartFile file) throws IOException {
        try {
            ValidatedUpload result = uploadService.validate(
                    file.getOriginalFilename(), file.getBytes(), file.getContentType());
            return ResponseEntity.ok(Map.of(
                    "storedName", result.storedName(),
                    "mimeType", result.mimeType(),
                    "size", result.size()));
        } catch (InvalidFileException e) {
            return badRequest(e.getMessage());
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize() {
        return ResponseEntity.badRequest().body(Map.of("message", "文件超过大小限制（5MB）"));
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}