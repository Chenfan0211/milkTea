package com.wuling.marketing.internal;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.dto.internal.CouponLockRequest;
import com.wuling.marketing.dto.internal.CouponLockResponse;
import com.wuling.marketing.dto.internal.CouponOrderRequest;
import com.wuling.marketing.dto.internal.CouponReleaseRequest;
import com.wuling.marketing.dto.internal.CouponRestoreResponse;
import com.wuling.marketing.service.CouponService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** trade 调用营销优惠券能力的内部 HTTP 契约。 */
@RestController
@RequestMapping("/internal/coupons")
public class CouponInternalController {

    private final CouponService couponService;

    public CouponInternalController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping("/lock")
    public CouponLockResponse lock(@RequestBody CouponLockRequest request) {
        try {
            if (request == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "缺少锁券参数");
            }
            CouponService.CouponLockResult result = couponService.lockByOrderNo(
                    request.getUserId(),
                    request.getUserCouponId(),
                    request.getOrderNo(),
                    request.getStoreSubjectId(),
                    request.getProductIds(),
                    request.getScene(),
                    request.getOrderAmount());
            return CouponLockResponse.success(result.userCouponId(), result.couponId(), result.discountAmount());
        } catch (BusinessException e) {
            return CouponLockResponse.failure(e.getMessage());
        }
    }

    @PostMapping("/consume")
    public Map<String, Object> consume(@RequestBody CouponOrderRequest request) {
        try {
            if (request == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "缺少核销参数");
            }
            couponService.consume(request.getUserId(), request.getUserCouponId(), request.getOrderNo());
            return success();
        } catch (BusinessException e) {
            return failure(e.getMessage());
        }
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody CouponReleaseRequest request) {
        try {
            if (request == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "缺少释放参数");
            }
            couponService.release(request.getOrderNo(), request.getReason());
            return success();
        } catch (BusinessException e) {
            return failure(e.getMessage());
        }
    }

    @PostMapping("/restore-refund")
    public CouponRestoreResponse restoreRefund(@RequestBody CouponOrderRequest request) {
        try {
            if (request == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "缺少退款恢复参数");
            }
            String status = couponService.restoreAfterRefund(
                    request.getUserId(), request.getUserCouponId(), request.getOrderNo());
            return CouponRestoreResponse.success(status);
        } catch (BusinessException e) {
            return CouponRestoreResponse.failure(e.getMessage());
        }
    }

    private Map<String, Object> success() {
        return Map.of("success", true);
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message == null ? "优惠券操作失败" : message);
        return body;
    }
}
