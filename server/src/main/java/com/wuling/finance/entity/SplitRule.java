package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("split_rule")
public class SplitRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String scope;
    private Long productId;
    private Integer platformRatio;
    private Integer storeRatio;
    private Integer channelRatio;
    private Integer investorRatio;
    /**
     * 投资人当月累计分账达标额（分）。0 = 不启用「达标后比例」规则。
     *
     * <p>业务口径：按**投资人当月累计分账额**判定，达到该阈值后，当月改用 {@link #investorRatioAfter}。
     */
    private Long investorThresholdAmount;
    /**
     * 达标后的投资人比例（万分比）。0 = 未配置，保留 {@link #investorRatio}。
     *
     * <p>替换时差额由平台（{@link #platformRatio}）承接，保证五方合计仍为 10000。
     */
    private Integer investorRatioAfter;
    private Integer supplierRatio;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public int totalRatio() {
        return nz(platformRatio) + nz(storeRatio) + nz(channelRatio) + nz(investorRatio) + nz(supplierRatio);
    }

    /**
     * 按投资人**当月累计分账额**解析本单应使用的投资人比例（万分比）。
     *
     * <p>规则：
     * <ul>
     *   <li>阈值为空或 &lt;= 0：未启用，返回原比例 investorRatio；</li>
     *   <li>达标后比例为空或 &lt;= 0：未配置，返回原比例（视为未启用）；</li>
     *   <li>累计额 &gt;= 阈值：返回 investorRatioAfter；</li>
     *   <li>其余（未达标）：返回 investorRatio。</li>
     * </ul>
     *
     * <p>边界口径：累计额**恰好等于**阈值即视为达标（本单起用新比例）。
     * 阈值语义是「达到」，用户理解上达到即生效。
     *
     * @param accumulatedCurrentMonth 投资人当月累计已分账金额（分），null 按 0 处理
     * @return 本单生效的投资人比例（万分比）
     */
    public int resolveInvestorRatio(Long accumulatedCurrentMonth) {
        long accumulated = accumulatedCurrentMonth == null ? 0L : accumulatedCurrentMonth;
        long threshold = investorThresholdAmount == null ? 0L : investorThresholdAmount;
        int after = nz(investorRatioAfter);
        // 阈值未启用、或达标比例未配置，都退化为原比例
        if (threshold <= 0 || after <= 0) {
            return nz(investorRatio);
        }
        return accumulated >= threshold ? after : nz(investorRatio);
    }

    /**
     * 应用「达标后比例」后的有效平台比例（万分比）。
     *
     * <p>投资人比例提升的部分由平台让出：平台 = 原平台 - (达标比例 - 原投资人比例)。
     * 这样五方合计恒为 10000，且不改动门店/渠道/供应商的既有份额。
     *
     * @param accumulatedCurrentMonth 投资人当月累计已分账金额（分）
     * @return 本单生效的平台比例（万分比）
     */
    public int resolvePlatformRatio(Long accumulatedCurrentMonth) {
        int effectiveInvestor = resolveInvestorRatio(accumulatedCurrentMonth);
        int delta = effectiveInvestor - nz(investorRatio);
        return nz(platformRatio) - delta;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
