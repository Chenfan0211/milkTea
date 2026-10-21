package com.wuling.user.controller;

import com.wuling.common.api.Result;
import com.wuling.security.CurrentUser;
import com.wuling.user.dto.tongue.TongueDetectionResponse;
import com.wuling.user.dto.tongue.TongueReportDetailResponse;
import com.wuling.user.dto.tongue.TongueReportSummaryResponse;
import com.wuling.user.service.TongueReportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 小程序舌象检测接口。
 *
 * <p>userId 一律取自 JWT，不接受前端传入；接口不得返回第三方 reportId、
 * file-service storedName 或任何公开图片 URL。
 */
@RestController
@RequestMapping("/api/v1/app/tongue")
public class TongueController {

    private final TongueReportService tongueReportService;

    public TongueController(TongueReportService tongueReportService) {
        this.tongueReportService = tongueReportService;
    }

    @PostMapping(value = "/detections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<TongueDetectionResponse> detect(@RequestParam("image") MultipartFile image,
                                                  @RequestParam("age") Integer age,
                                                  @RequestParam("sex") String sex) {
        return Result.ok(tongueReportService.detect(CurrentUser.require(), image, age, sex));
    }

    @GetMapping("/reports")
    public Result<List<TongueReportSummaryResponse>> reports() {
        return Result.ok(tongueReportService.listRecent(CurrentUser.require()));
    }

    @GetMapping("/reports/{id}")
    public Result<TongueReportDetailResponse> report(@PathVariable Long id) {
        return Result.ok(tongueReportService.detail(CurrentUser.require(), id));
    }

    @PostMapping("/reports/{id}/refresh")
    public Result<TongueReportDetailResponse> refresh(@PathVariable Long id) {
        return Result.ok(tongueReportService.refresh(CurrentUser.require(), id));
    }

    @GetMapping("/reports/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        TongueReportService.ImageContent image = tongueReportService.image(CurrentUser.require(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .body(image.bytes());
    }

    @DeleteMapping("/reports/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tongueReportService.delete(CurrentUser.require(), id);
        return Result.ok();
    }
}
