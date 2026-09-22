package com.wuling.file.controller;

import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import com.wuling.file.service.UploadValidationService;
import com.wuling.file.service.UploadValidationService.ValidatedUpload;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文件上传端点（第 2 期骨架）。
 *
 * <p><b>安全状态说明（重要）</b>：
 * 本端点目前只做「类型与大小校验 + 重命名」，<b>尚未接入</b>：
 * 图片二次编码、对象存储、ClamAV 病毒扫描。
 * 因此该端点<b>不应对外暴露</b>，仅供内部联调；
 * 待上述三项补齐后再通过网关开放。
 */
@RestController
public class FileController {

    private final UploadValidationService uploadService;

    public FileController(UploadValidationService uploadService) {
        this.uploadService = uploadService;
    }

    /** 服务自述（不暴露任何配置细节） */
    @GetMapping("/api/v1/files/service-info")
    public Map<String, Object> info() {
        return Map.of(
                "service", "file-service",
                "phase", "phase-2-skeleton",
                "status", "up",
                "note", "校验能力已就绪；对象存储与病毒扫描待接入，暂不对外提供上传"
        );
    }

    /**
     * 校验上传文件（内部联调用）。
     *
     * <p>注意：仅返回校验结果，<b>不落盘、不返回可访问 URL</b>，
     * 避免在校验链路不完整时产生可访问的未扫描文件。
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
            // 校验失败：只回笼统原因，不回显内部判定细节以外的信息
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
