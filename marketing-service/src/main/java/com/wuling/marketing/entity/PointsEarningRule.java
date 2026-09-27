package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("points_earning_rule")
public class PointsEarningRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String action;
    private String reward;
    private String note;
    private Integer sort;

    /**
     * 奖励类型：per-yuan / fixed / fixed-per / multiplier。
     * 结构化存储后，发放时光币的逻辑可直接按类型计算，不必解析文本。
     */
    private String rewardType;

    /** 币数（fixed / fixed-per / per-yuan）或倍数（multiplier）。 */
    private Long rewardValue;

    /** 仅 per-yuan 使用：每 X（单位见 basisUnit），金额单位为分。 */
    private Long basisAmount;

    /** 计量单位：yuan / person / time / day。 */
    private String basisUnit;

    /** 每日上限（次/天）；null 表示不限。 */
    private Integer dailyLimit;

    /**
     * 启用状态：1=启用，0=停用。
     * 只有启用的规则才对小程序端生效并展示；停用规则仍保留在后台供重新启用。
     */
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
