package com.wuling.common.api;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {

    private List<T> records;
    private long current;
    private long size;
    private long total;

    public static <T> PageResult<T> of(List<T> records, long current, long size, long total) {
        PageResult<T> page = new PageResult<>();
        page.setRecords(records);
        page.setCurrent(current);
        page.setSize(size);
        page.setTotal(total);
        return page;
    }
}
