package com.wuling.user.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.user.dto.AddressRequest;
import com.wuling.user.dto.AddressResponse;
import com.wuling.user.dto.AddressSnapshotResponse;
import com.wuling.user.entity.AppUserAddress;
import com.wuling.user.mapper.AppUserAddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 小程序收货地址与内部地址快照服务。
 *
 * <p>对外方法均要求显式传入已认证的 userId；所有按 ID 的操作在 SQL 层
 * 同时匹配 user_id，未命中统一返回「收货地址不存在」，不泄露他人地址是否存在。
 */
@Service
public class AddressService {

    private static final int MAX_ADDRESSES = 20;
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private final AppUserAddressMapper mapper;

    public AddressService(AppUserAddressMapper mapper) {
        this.mapper = mapper;
    }

    public List<AddressResponse> list(Long userId) {
        Long uid = requireUserId(userId);
        return mapper.selectOwnedList(uid).stream().map(AddressResponse::from).toList();
    }

    /**
     * 新增地址。事务内先锁定用户行，保证并发新增不会突破 20 条上限，
     * 也让「是否首条地址」的判断与插入保持原子性。
     */
    @Transactional(rollbackFor = Exception.class)
    public AddressResponse create(Long userId, AddressRequest request) {
        Long uid = requireUserId(userId);
        validateAndNormalize(request);
        lockUser(uid);

        long count = mapper.countOwned(uid);
        if (count >= MAX_ADDRESSES) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "收货地址最多保存" + MAX_ADDRESSES + "条");
        }

        boolean makeDefault = count == 0 || Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault && count > 0) {
            mapper.clearOwnedDefaults(uid);
        }

        LocalDateTime now = LocalDateTime.now();
        AppUserAddress address = new AppUserAddress();
        address.setUserId(uid);
        applyEditableFields(address, request);
        address.setIsDefault(makeDefault ? 1 : 0);
        address.setCreateTime(now);
        address.setUpdateTime(now);
        address.setDeleted(0);
        mapper.insert(address);
        return AddressResponse.from(address);
    }

    @Transactional(rollbackFor = Exception.class)
    public AddressResponse update(Long userId, Long id, AddressRequest request) {
        Long uid = requireUserId(userId);
        validateAndNormalize(request);
        lockUser(uid);
        AppUserAddress address = requireOwned(uid, id);

        if (Boolean.TRUE.equals(request.getIsDefault())) {
            mapper.clearOwnedDefaults(uid);
            address.setIsDefault(1);
        } else if (!Integer.valueOf(1).equals(address.getIsDefault())) {
            address.setIsDefault(0);
        }

        applyEditableFields(address, request);
        address.setUpdateTime(LocalDateTime.now());
        mapper.updateById(address);
        return AddressResponse.from(address);
    }

    /**
     * 删除地址。若删除的是默认地址，则在同一事务中把剩余地址里最近更新的一条设为默认。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        Long uid = requireUserId(userId);
        lockUser(uid);
        AppUserAddress address = requireOwned(uid, id);
        int deleted = mapper.logicalDeleteOwned(id, uid);
        if (deleted != 1) {
            throw notFound();
        }
        if (Integer.valueOf(1).equals(address.getIsDefault())) {
            AppUserAddress fallback = mapper.selectMostRecentOwned(uid);
            if (fallback != null) {
                mapper.markOwnedDefault(fallback.getId(), uid);
            }
        }
    }

    /**
     * 事务内先清除该用户全部旧默认，再设置目标地址，保证任一时刻只有一个默认地址。
     */
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long id) {
        Long uid = requireUserId(userId);
        lockUser(uid);
        requireOwned(uid, id);
        mapper.clearOwnedDefaults(uid);
        if (mapper.markOwnedDefault(id, uid) != 1) {
            throw notFound();
        }
    }

    /** 仅供 trade-service 内部调用；仍显式校验地址归属。 */
    public AddressSnapshotResponse snapshot(Long userId, Long id) {
        Long uid = requireUserId(userId);
        AppUserAddress address = requireOwned(uid, id);
        return new AddressSnapshotResponse(
                address.getRecipientName(),
                address.getPhone(),
                address.getProvinceName(),
                address.getCityName(),
                address.getDistrictName(),
                address.getDetailAddress()
        );
    }

    private void lockUser(Long userId) {
        if (mapper.lockUser(userId) == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private AppUserAddress requireOwned(Long userId, Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
        AppUserAddress address = mapper.selectOwnedById(id, userId);
        if (address == null) {
            throw notFound();
        }
        return address;
    }

    private static void validateAndNormalize(AddressRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数不能为空");
        }
        request.setRecipientName(required(request.getRecipientName(),
                "请输入收件人姓名", 32, "收件人姓名不能超过32个字符"));
        request.setPhone(required(request.getPhone(),
                "请输入手机号", 20, "手机号不能超过20个字符"));
        if (!MOBILE.matcher(request.getPhone()).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请输入正确的手机号");
        }
        request.setProvinceCode(required(request.getProvinceCode(),
                "请选择省份", 32, "省份编码不能超过32个字符"));
        request.setProvinceName(required(request.getProvinceName(),
                "请选择省份", 64, "省份名称不能超过64个字符"));
        request.setCityCode(required(request.getCityCode(),
                "请选择城市", 32, "城市编码不能超过32个字符"));
        request.setCityName(required(request.getCityName(),
                "请选择城市", 64, "城市名称不能超过64个字符"));
        request.setDistrictCode(required(request.getDistrictCode(),
                "请选择区县", 32, "区县编码不能超过32个字符"));
        request.setDistrictName(required(request.getDistrictName(),
                "请选择区县", 64, "区县名称不能超过64个字符"));
        request.setDetailAddress(required(request.getDetailAddress(),
                "请输入详细地址", 255, "详细地址不能超过255个字符"));
        request.setTag(optional(request.getTag(), 16, "地址标签不能超过16个字符"));
    }

    private static String required(String value, String blankMessage, int maxLength, String lengthMessage) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, blankMessage);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ResultCode.BAD_REQUEST, lengthMessage);
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String lengthMessage) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ResultCode.BAD_REQUEST, lengthMessage);
        }
        return normalized;
    }

    private static void applyEditableFields(AppUserAddress address, AddressRequest request) {
        address.setRecipientName(request.getRecipientName());
        address.setPhone(request.getPhone());
        address.setProvinceCode(request.getProvinceCode());
        address.setProvinceName(request.getProvinceName());
        address.setCityCode(request.getCityCode());
        address.setCityName(request.getCityName());
        address.setDistrictCode(request.getDistrictCode());
        address.setDistrictName(request.getDistrictName());
        address.setDetailAddress(request.getDetailAddress());
        address.setTag(request.getTag());
    }

    private static Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }

    private static BusinessException notFound() {
        return new BusinessException(ResultCode.NOT_FOUND, "收货地址不存在");
    }
}