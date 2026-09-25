package com.wuling.marketing.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.marketing.dto.StoredValuePackageDTO;
import com.wuling.marketing.entity.*;
import com.wuling.marketing.service.*;
import com.wuling.user.entity.AppUser;
import com.wuling.security.CurrentUser;
import com.wuling.user.mapper.AppUserMapper;
import com.wuling.marketing.mapper.MemberLevelMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 小程序端：营销（优惠券 / 储值 / 礼品卡 / 积分 / 评论） */
@RestController
@RequestMapping("/api/v1/app")
public class AppMarketingController {

    private final CouponService couponService;
    private final StoredValueService storedValueService;
    private final GiftCardService giftCardService;
    private final PointsService pointsService;
    private final CommentService commentService;
    private final AppUserMapper appUserMapper;
    private final MemberLevelMapper memberLevelMapper;

    public AppMarketingController(CouponService couponService,
                                  StoredValueService storedValueService,
                                  GiftCardService giftCardService,
                                  PointsService pointsService,
                                  CommentService commentService,
                                  AppUserMapper appUserMapper,
                                  MemberLevelMapper memberLevelMapper) {
        this.couponService = couponService;
        this.storedValueService = storedValueService;
        this.giftCardService = giftCardService;
        this.pointsService = pointsService;
        this.commentService = commentService;
        this.appUserMapper = appUserMapper;
        this.memberLevelMapper = memberLevelMapper;
    }

    // ---------- 优惠券 ----------
    @GetMapping("/coupons")
    public Result<List<Coupon>> couponTemplates() {
        return Result.ok(couponService.listEnabled());
    }

    /**
     * 我的优惠券。
     * 注意：userId 一律取自 JWT，路径参数仅用于兼容旧前端调用，不参与鉴权。
     * 前端可传任意占位值（如 0），实际归属以 token 为准。
     */
    @GetMapping("/users/{userId}/coupons")
    public Result<List<UserCoupon>> myCoupons(@PathVariable Long userId,
                                              @RequestParam(required = false) String status) {
        return Result.ok(couponService.myCoupons(CurrentUser.require(), status));
    }

    /** 领取优惠券（userId 取自 JWT） */
    @PostMapping("/users/{userId}/coupons/{couponId}/receive")
    public Result<UserCoupon> receive(@PathVariable Long userId, @PathVariable Long couponId) {
        return Result.ok(couponService.receive(CurrentUser.require(), couponId));
    }

    // ---------- 储值 ----------
    @GetMapping("/stored-value/packages")
    public Result<List<StoredValuePackageDTO>> packages() {
        return Result.ok(storedValueService.listPackages());
    }

    /**
     * 创建储值订单（待支付）。
     *
     * <p>第 15 期改造：原 {@code recharge} 一步完成「建单 + 置 PAID + 入账」，
     * 是 mock 时代写法。接入微信支付后必须拆开 —— 建单只落 UNPAID，
     * 入账改由支付回调驱动（见 {@code StoredValueInternalController#settle}）。
     * 前端 {@code requestPayment} 的 success 回调<b>不代表资金到账</b>，不可用于入账。
     *
     * <p>金额以服务端套餐配置为准，不接受前端传入。
     */
    @PostMapping("/stored-value/orders")
    public Result<StoredValueOrder> createStoredValueOrder(@RequestParam Long packageId) {
        return Result.ok(storedValueService.createOrder(CurrentUser.require(), packageId));
    }

    /**
     * 储值订单视图（供小程序支付后主动查单）。
     *
     * <p>前端唤起收银台后轮询本接口，以<b>服务端状态</b>为准展示结果，
     * 不信任前端 requestPayment 的成功回调。
     */
    @GetMapping("/stored-value/orders/{orderNo}")
    public Result<StoredValueOrder> storedValueOrder(@PathVariable String orderNo) {
        return Result.ok(storedValueService.orderView(CurrentUser.require(), orderNo));
    }

    @GetMapping("/stored-value/orders")
    public Result<PageResult<StoredValueOrder>> storedOrders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(storedValueService.myOrders(CurrentUser.require(), page, size));
    }

    // ---------- 礼品卡 ----------
    @GetMapping("/gift-cards/denominations")
    public Result<List<GiftCardDenomination>> denominations() {
        return Result.ok(giftCardService.listDenominations());
    }

    @PostMapping("/gift-cards/purchase")
    public Result<GiftCardOrder> purchase(@RequestParam Long denominationId) {
        return Result.ok(giftCardService.purchase(CurrentUser.require(), denominationId));
    }

    @GetMapping("/gift-cards")
    public Result<List<GiftCard>> myGiftCards() {
        return Result.ok(giftCardService.myCards(CurrentUser.require()));
    }

    @GetMapping("/gift-cards/orders")
    public Result<PageResult<GiftCardOrder>> myGiftCardOrders(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(giftCardService.myOrders(CurrentUser.require(), page, size));
    }

    @PostMapping("/gift-cards/orders/{orderId}/cancel")
    public Result<GiftCardOrder> cancelGiftCardOrder(@PathVariable Long orderId) {
        return Result.ok(giftCardService.cancelOrder(CurrentUser.require(), orderId));
    }

    @PostMapping("/gift-cards/verify")
    public Result<GiftCardOrder> verifyGiftCard(@RequestParam String orderNo) {
        return Result.ok(giftCardService.verifyOrder(CurrentUser.require(), orderNo));
    }

    // ---------- 积分 ----------
    @GetMapping("/points/products")
    public Result<List<PointsProduct>> pointsProducts() {
        return Result.ok(pointsService.listProducts(null));
    }

    @GetMapping("/points/rules")
    public Result<List<PointsEarningRule>> pointsRules() {
        return Result.ok(pointsService.earningRules());
    }

    @GetMapping("/points/records")
    public Result<List<PointsRecord>> pointsRecords() {
        return Result.ok(pointsService.records(CurrentUser.require()));
    }

    @GetMapping("/points/signin-dates")
    public Result<List<String>> signinDates() {
        return Result.ok(pointsService.signinDates(CurrentUser.require()));
    }

    @PostMapping("/points/signin")
    public Result<Map<String, Object>> signIn() {
        long balance = pointsService.signIn(CurrentUser.require());
        return Result.ok(Map.of("balance", balance, "message", "签到成功"));
    }

    @PostMapping("/points/exchange")
    public Result<ExchangeOrder> exchange(@RequestParam Long productId) {
        return Result.ok(pointsService.exchange(CurrentUser.require(), productId));
    }

    @GetMapping("/points/exchange-orders")
    public Result<List<ExchangeOrder>> exchangeOrders() {
        return Result.ok(pointsService.exchangeOrders(CurrentUser.require()));
    }

    // ---------- 会员等级 ----------
    @GetMapping("/member-levels")
    public Result<List<MemberLevel>> memberLevels() {
        return Result.ok(memberLevelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemberLevel>()
                        .orderByAsc(MemberLevel::getSort)
                        .orderByAsc(MemberLevel::getId)));
    }

    // ---------- 用户 ----------
    /** 用户详情：仅允许查询自己（userId 取自 JWT） */
    @GetMapping("/users/{userId}")
    public Result<AppUser> user(@PathVariable Long userId) {
        Long current = CurrentUser.require();
        if (!current.equals(userId)) {
            throw new com.wuling.common.exception.BusinessException(
                    com.wuling.common.api.ResultCode.FORBIDDEN, "无权访问其他用户信息");
        }
        return Result.ok(appUserMapper.selectById(current));
    }

    // ---------- 评论 ----------
    @PostMapping("/comments")
    public Result<Comment> comment(@RequestParam Long orderId,
                                   @RequestParam(required = false) Integer rating,
                                   @RequestParam(required = false) String content,
                                   @RequestParam(required = false) String images) {
        return Result.ok(commentService.submit(orderId, CurrentUser.require(), rating, content, images));
    }
}






