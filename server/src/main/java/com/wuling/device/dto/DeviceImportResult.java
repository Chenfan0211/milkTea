package com.wuling.device.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 设备批量导入结果。accepted=false 时整批未写入，errors 为全部行级错误。 */
@Data
public class DeviceImportResult {

    private boolean accepted;
    private int total;
    private int imported;
    private List<String> errors = new ArrayList<>();

    public static DeviceImportResult rejected(int total, List<String> errors) {
        DeviceImportResult result = new DeviceImportResult();
        result.setAccepted(false);
        result.setTotal(total);
        result.setImported(0);
        result.setErrors(errors);
        return result;
    }

    public static DeviceImportResult imported(int total) {
        DeviceImportResult result = new DeviceImportResult();
        result.setAccepted(true);
        result.setTotal(total);
        result.setImported(total);
        result.setErrors(List.of());
        return result;
    }
}
