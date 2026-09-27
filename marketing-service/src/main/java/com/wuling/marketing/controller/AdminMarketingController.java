package com.wuling.marketing.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.marketing.entity.Comment;
import com.wuling.marketing.service.CommentService;
import com.wuling.marketing.service.GiftCardAdminService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/marketing")
public class AdminMarketingController {

    private final CommentService commentService;
    private final GiftCardAdminService giftCardAdminService;

    public AdminMarketingController(CommentService commentService,
                                    GiftCardAdminService giftCardAdminService) {
        this.commentService = commentService;
        this.giftCardAdminService = giftCardAdminService;
    }

    @GetMapping("/comments")
    public Result<PageResult<Comment>> comments(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String status) {
        return Result.ok(commentService.page(current, size, status));
    }

    /** 兑换记录列表（联表补齐用户昵称/商品名称，时光币为整数点数） */
    @GetMapping("/exchange-orders")
    public Result<PageResult<java.util.Map<String, Object>>> exchangeOrders(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String recordNo,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String status) {
        return Result.ok(giftCardAdminService.listExchangeOrders(current, size, recordNo, user, product, status));
    }

    /** 礼品卡订单列表（联表补齐卡种/面额/购买人，金额单位为分） */
    @GetMapping("/gift-card-orders")
    public Result<PageResult<java.util.Map<String, Object>>> giftCardOrders(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String buyer,
            @RequestParam(required = false) String status) {
        return Result.ok(giftCardAdminService.listOrders(current, size, orderNo, buyer, status));
    }

}
