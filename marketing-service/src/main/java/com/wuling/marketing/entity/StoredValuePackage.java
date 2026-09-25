package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 储值套餐。
 *
 * <p>{@code usageParagraphs} 存 MySQL JSON 列（见 {@code V23} 迁移），
 * 以字符串形式承载数组；由 Service 层解析为 {@code List<String>} 下发。
 * 这里刻意不声明为 List：MyBatis-Plus 对 JSON 列的自动映射依赖
 * 类型处理器，显式保留 String 更可控，也避免 mock/无配置场景下的反序列化异常。
 */
@Data
@TableName("stored_value_package")
public class StoredValuePackage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Long amount;
    private String status;

    /** 使用说明（JSON 字符串数组，每行一条） */
    private String usageParagraphs;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
