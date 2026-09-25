package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

/**
 * 商品规格组 DTO（管理端编辑用）。
 *
 * <p>与前端 {@code SpecGroupsEditor} 的结构对齐：
 * 组 -> 选项列表，其中每个选项带「加价（分）」与「是否默认选中」。
 *
 * <p>存储侧 {@code product_spec} 为行式（一选项一行），
 * 由 {@link com.wuling.product.service.AdminProductWriteService} 负责双向转换。
 */
@Data
public class SpecGroupsDTO {

    /** 规格组 */
    @Data
    public static class Group {
        /** 组编码（如 size / temperature） */
        private String id;
        /** 组名称（如 容量 / 冰量） */
        private String label;
        /** 选项列表 */
        private List<Option> options;
    }

    /** 规格选项 */
    @Data
    public static class Option {
        /** 选项编码 */
        private String id;
        /** 选项名称（如 中杯 / 大杯） */
        private String label;
        /** 加价，单位：分 */
        private Long priceDelta;
        /** 是否为该组默认选中项 */
        private Boolean selected;
        /** 选项图标（可选） */
        private String icon;
    }

    /** 规格组列表 */
    private List<Group> groups;
}
