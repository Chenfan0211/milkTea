package com.wuling.subject.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.subject.service.LeaderAttributionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/app/leader")
public class AppLeaderController {

    private final LeaderAttributionService attribution;

    public AppLeaderController(LeaderAttributionService attribution) {
        this.attribution = attribution;
    }

    @GetMapping("/attribution")
    public Result<Map<String, Object>> attribution() {
        return Result.ok(attribution.resolve(CurrentUser.require()));
    }

    @PostMapping("/bind-share")
    public Result<Map<String, Object>> bindShare(@RequestBody(required = false) Map<String, Object> body) {
        Long userId = CurrentUser.require();
        if (body != null && body.containsKey("leaderUserId")) {
            return Result.ok(attribution.bindShare(userId, positiveId(body.get("leaderUserId"))));
        }
        return Result.ok(attribution.bindShareFromSharer(userId,
                positiveId(body == null ? null : body.get("shareUserId"))));
    }

    private Long positiveId(Object value) {
        try {
            long id = Long.parseLong(String.valueOf(value));
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 无效链接统一返回明确的业务码。
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "无效的分享参数");
    }
}
