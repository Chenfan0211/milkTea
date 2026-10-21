package com.wuling.device.dto;

import lombok.Data;

/**
 * 后台「设备管理」列表行。
 *
 * <p><b>字段名即前端表格列 key</b>：CI 的 DedicatedEndpointConsistencyTest
 * 会校验页面直读列与后端返回字段一致，故命名不得随意改动
 * （改这里必须同步改 src/views/subject/device/index.vue）。
 */
@Data
public class AdminDeviceDTO {

    private Long id;

    /** 设备编码（报文 deviceSN） */
    private String deviceSn;

    /** 设备ID（报文 deviceName） */
    private String deviceName;

    /** 运营别名 */
    private String alias;

    /** 当前归属门店 id（NULL=未绑定） */
    private Long storeSubjectId;

    /** 当前归属门店名（实时 join biz_subject，门店改名后立即生效） */
    private String storeName;

    /** ONLINE / OFFLINE */
    private String onlineStatus;

    /** 最近上报时间 */
    private String lastReportTime;

    /** 最近状态变更时间 */
    private String lastStatusChangeTime;

    /** 1启用 0停用 */
    private Integer enabled;

    private String remark;

    /** 当前绑定生效时间 */
    private String bindTime;

    private String createTime;
}
