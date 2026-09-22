package com.wuling.system.crud;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.sql.SqlGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 CRUD 服务。
 *
 * 安全约束（关键）：
 * - 资源名、表名、列名全部来自 CrudRegistry 白名单，绝不拼接前端传入的标识符；
 * - 值一律使用占位符绑定，杜绝 SQL 注入；
 * - 只写白名单内字段，防止改到 id/deleted 等敏感列；
 * - 删除为逻辑删除（deleted=1），与全局约定一致。
 */
@Service
public class CrudService {

    private final JdbcTemplate jdbcTemplate;

    public CrudService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResult<Map<String, Object>> page(String resource, long current, long size, Map<String, String> search) {
        CrudRegistry.Resource def = require(resource);
        // SQL 安全：表名与排序子句经 SqlGuard 强制校验（标识符无法参数化，只能白名单约束）
        String table = SqlGuard.ident(def.table());
        String orderBy = SqlGuard.orderBy(def.orderBy());
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();

        if (search != null) {
            for (Map.Entry<String, String> entry : search.entrySet()) {
                String value = entry.getValue();
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                String column = camelToSnake(entry.getKey());
                if (!def.searchable().contains(column)) {
                    continue;
                }
                where.append(" and ").append(column).append(" like ?");
                args.add("%" + value.trim() + "%");
            }
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from " + table + where, Long.class, args.toArray());
        long offset = Math.max(0, (current - 1) * size);
        String sql = "select * from " + table + where
                + " order by " + orderBy + " limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql, pageArgs.toArray());
        return PageResult.of(records.stream().map(this::camelize).toList(),
                current, size, total == null ? 0L : total);
    }

    public Map<String, Object> getOne(String resource, long id) {
        CrudRegistry.Resource def = require(resource);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from " + SqlGuard.ident(def.table()) + " where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return camelize(rows.get(0));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(String resource, Map<String, Object> payload) {
        CrudRegistry.Resource def = require(resource);
        Map<String, Object> values = filterWritable(def, payload);
        if (values.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有可写入的字段");
        }
        String columns = String.join(", ", values.keySet());
        String placeholders = String.join(", ", values.keySet().stream().map(c -> "?").toList());
        String sql = "insert into " + SqlGuard.ident(def.table()) + " (" + columns + ") values (" + placeholders + ")";
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            for (Object value : values.values()) {
                ps.setObject(index++, value);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return getOne(resource, key == null ? 0L : key.longValue());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String resource, long id, Map<String, Object> payload) {
        CrudRegistry.Resource def = require(resource);
        Map<String, Object> values = filterWritable(def, payload);
        if (values.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有可更新的字段");
        }
        String sets = String.join(", ", values.keySet().stream().map(c -> c + " = ?").toList());
        List<Object> args = new ArrayList<>(values.values());
        args.add(id);
        int affected = jdbcTemplate.update(
                "update " + SqlGuard.ident(def.table()) + " set " + sets + " where id = ? and deleted = 0", args.toArray());
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return getOne(resource, id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String resource, long id) {
        CrudRegistry.Resource def = require(resource);
        int affected = jdbcTemplate.update(
                "update " + SqlGuard.ident(def.table()) + " set deleted = 1 where id = ? and deleted = 0", id);
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
    }

    // ---------- 内部工具 ----------

    private CrudRegistry.Resource require(String resource) {
        CrudRegistry.Resource def = CrudRegistry.get(resource);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "不支持的资源: " + resource);
        }
        return def;
    }

    /** 只保留白名单字段，并把驼峰 key 转为下划线列名 */
    private Map<String, Object> filterWritable(CrudRegistry.Resource def, Map<String, Object> payload) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (payload == null) {
            return values;
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String column = camelToSnake(entry.getKey());
            if (!def.writable().contains(column)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Boolean bool) {
                value = bool ? 1 : 0;
            }
            values.put(column, value);
        }
        return values;
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private String snakeToCamel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    private Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> result.put(snakeToCamel(k), v));
        return result;
    }
}
