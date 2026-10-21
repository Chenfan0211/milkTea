package com.wuling.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 舌象检测报告。
 *
 * <p>image_storage_name / report_id / report_json 仅在服务端内部流转，
 * 不通过小程序接口返回。
 */
@Data
@TableName("tongue_report")
public class TongueReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String reportId;
    private String sex;
    private Integer age;
    private String imageStorageName;
    private String physique;
    private String summarize;
    private String productListJson;
    private String reportJson;
    private String reportStatus;
    private LocalDateTime lastFetchAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
