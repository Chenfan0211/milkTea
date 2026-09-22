package com.wuling.marketing.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.marketing.entity.Comment;
import com.wuling.marketing.service.CommentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/marketing")
public class AdminMarketingController {

    private final CommentService commentService;

    public AdminMarketingController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/comments")
    public Result<PageResult<Comment>> comments(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String status) {
        return Result.ok(commentService.page(current, size, status));
    }

    @PostMapping("/comments/{id}/review")
    public Result<Comment> review(@PathVariable Long id,
                                  @RequestParam boolean approve,
                                  @RequestParam(required = false) String reason) {
        return Result.ok(commentService.review(id, approve, reason));
    }
}
