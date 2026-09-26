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
    /**
     * 平台提成比例（万分比）。固定金额模型下不再参与计算，保留字段向后兼容。
     */
    private Integer platformRatio;
    /**
     * 门店每件提成（分）。固定金额，按商品件数计。
     */
    private Integer storeRatio;
    /**
     * 资源方每件提成（分）。固定金额，按商品件数计；无渠道归因时为 0。
     */
    private Integer channelRatio;
    /**
     * 投资人提成比例（万分比）。
     */
    private Integer investorRatio;
    /**
     * 投资人当月累计分账达标额（分）。必填（> 0）。
     *
     * <p>业务口径：按**投资人当月累计分账额**判定，达到该阈值后，当月改用 {@link #investorRatioAfter}。
     */
    private Long investorThresholdAmount;
    /**
     * 达标后的投资人比例（万分比）。
     *
     * <p>固定金额模型下，投资人比例提升的部分不再需要「平台让出」来保证合计，
     * 平台本就是尾差兜底方，投资人增量自动从平台尾差中体现。
     */
    private Integer investorRatioAfter;
    /**
     * 供应商比例（万分比）。成本直给模型下不再参与计算，保留字段向后兼容。
     */
    private Integer supplierRatio;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /**
     * 按投资人**当月累计分账额**解析本单应使用的投资人比例（万分比）。
     *
     * <p>规则：
     * <ul>
     *   <li>阈值为空或 &lt;= 0：视为未启用，返回原比例 investorRatio；</li>
     *   <li>达标后比例为空或 &lt;= 0：视为未配置，返回原比例；</li>
     *   <li>累计额 &gt;= 阈值：返回 investorRatioAfter；</li>
     *   <li>其余（未达标）：返回 investorRatio。</li>
     * </ul>
     *
     * <p>边界口径：累计额**恰好等于**阈值即视为达标（本单起用新比例）。
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

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}