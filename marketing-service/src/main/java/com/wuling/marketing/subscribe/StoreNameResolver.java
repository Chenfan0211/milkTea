package com.wuling.marketing.subscribe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/** 从系统主体表解析有效门店名称；只接受门店 ID，不接受客户端传名称。 */
@Component
public class StoreNameResolver {

    private final JdbcTemplate jdbcTemplate;

    public StoreNameResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String resolve(Long storeSubjectId) {
        if (storeSubjectId == null) {
            return null;
        }
        List<String> names = jdbcTemplate.queryForList(
                "select name from biz_subject where id = ? and subject_type = 'STORE' "
                        + "and status = 'active' and deleted = 0",
                String.class,
                storeSubjectId);
        if (names == null || names.isEmpty() || !StringUtils.hasText(names.get(0))) {
            return null;
        }
        return names.get(0).trim();
    }
}
