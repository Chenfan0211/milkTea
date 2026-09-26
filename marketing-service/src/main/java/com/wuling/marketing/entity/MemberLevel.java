package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "member_level", autoResultMap = true)
public class MemberLevel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String levelCode;
    private String name;
    private Long amountTarget;
    private String discount;
    /**
     * 会员权益（JSON 列，结构化 [{icon,text,count}]）。
     *
     * <p>数据库列为 JSON，这里用 JacksonTypeHandler 让 MyBatis-Plus 读取时自动
     * 反序列化为 List，小程序接口 /api/v1/app/member-levels 因此直接返回数组，
     * 而非带转义的 JSON 字符串（旧实现是 String 类型，靠小程序端 JSON.parse 兜底，
     * 链路脆弱且后台列表/编辑弹窗因 Array.isArray 判断而显示为空）。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> benefits;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}