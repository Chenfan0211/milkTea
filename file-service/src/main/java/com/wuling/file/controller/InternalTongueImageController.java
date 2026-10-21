package com.wuling.file.controller;

import com.wuling.common.api.Result;
import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import com.wuling.file.service.ImageStorageService;
import com.wuling.file.service.ImageStorageService.ImageNotFoundException;
import com.wuling.file.service.ImageStorageService.InvalidFilePathException;
import com.wuling.file.service.ImageStorageService.PublicImage;
import com.wuling.file.service.ImageStorageService.StoredImage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * file-service 与业务服务之间的内部舌象图片接口。
 *
 * <p>不通过网关暴露：网关只路由 /api/v1/** 与 /auth/**。业务服务只拿到
 * storedName，不向小程序返回公开 URL。
 */
@RestController
@RequestMapping("/internal/tongue-images")
public class InternalTongueImageController {

    private final ImageStorageService imageStorageService;

    public InternalTongueImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    /** 上传并重编码舌照，只返回内部存储名与元数据。 */
    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            StoredImage stored = imageStorageService.store(
                    file.getOriginalFilename(), file.getBytes(), file.getContentType());
            return ResponseEntity.ok(Result.ok(Map.of(
                    "storedName", stored.storedName(),
                    "mimeType", stored.mimeType(),
                    "size", stored.size())));
        } catch (InvalidFileException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            return badRequest("文件读取失败");
        }
    }

    /** 读取内部舌照，供 user-service 做归属校验后代理。 */
    @GetMapping("/{storedName:.+}")
    public ResponseEntity<byte[]> read(@PathVariable String storedName) {
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

    /** 失败补偿删除；不存在按幂等成功处理。 */
    @DeleteMapping("/{storedName:.+}")
    public ResponseEntity<?> delete(@PathVariable String storedName) {
        try {
            imageStorageService.deleteImage(storedName);
            return ResponseEntity.ok(Result.ok());
        } catch (InvalidFilePathException e) {
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
