package com.wuling.marketing.service;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 礼品卡后台分组与卡面聚合管理。 */
@Service
public class GiftCardAdminService {

    private final JdbcTemplate jdbcTemplate;

    public GiftCardAdminService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResult<Map<String, Object>> listFaces(long current, long size, String name) {
        StringBuilder sql = new StringBuilder(
                "select d.id, d.group_id, d.card_name, d.card_image, d.name, d.amount, "
                        + "d.sale_price, d.sort, d.status, "
                        + "coalesce(g.name, d.group_title, d.group_id) as display_group_title "
                        + "from gift_card_denomination d "
                        + "left join gift_card_group g on g.code = d.group_id and g.deleted = 0 "
                        + "where d.deleted = 0");
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            sql.append(" and (d.card_name like ? or d.name like ? or g.name like ?)");
            String like = "%" + name.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by d.sort asc, d.group_id asc, d.card_name asc, d.amount asc, d.id asc");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        Map<String, Map<String, Object>> faces = new LinkedHashMap<>();
        Set<Long> denominationIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Long id = longValue(row.get("id"));
            if (id != null) {
                denominationIds.add(id);
            }
            String groupId = stringValue(row.get("group_id"), "default");
            String cardName = stringValue(row.get("card_name"), stringValue(row.get("name"), "礼品卡"));
            String key = groupId + "\u0000" + cardName;
            Map<String, Object> face = faces.get(key);
            if (face == null) {
                face = new LinkedHashMap<>();
                face.put("faceId", id);
                face.put("groupId", groupId);
                face.put("groupTitle", stringValue(row.get("display_group_title"), groupId));
                face.put("cardName", cardName);
                face.put("cardImage", row.get("card_image"));
                face.put("sort", row.get("sort"));
                face.put("status", "enabled");
                face.put("denominations", new ArrayList<Map<String, Object>>());
                face.put("faceValues", new ArrayList<Long>());
                faces.put(key, face);
            }
            Long currentFaceId = longValue(face.get("faceId"));
            if (currentFaceId == null || (id != null && id < currentFaceId)) {
                face.put("faceId", id);
            }
            if (!"enabled".equals(String.valueOf(row.get("status")))) {
                face.put("status", "disabled");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> denominations = (List<Map<String, Object>>) face.get("denominations");
            Map<String, Object> denomination = new LinkedHashMap<>();
            denomination.put("id", id);
            denomination.put("amount", row.get("amount"));
            denomination.put("salePrice", row.get("sale_price"));
            denomination.put("referenced", false);
            denomination.put("deleted", false);
            denominations.add(denomination);
            @SuppressWarnings("unchecked")
            List<Long> faceValues = (List<Long>) face.get("faceValues");
            faceValues.add(longValue(row.get("amount")));
        }

        Set<Long> referencedIds = loadReferencedIds(denominationIds);
        for (Map<String, Object> face : faces.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> denominations = (List<Map<String, Object>>) face.get("denominations");
            for (Map<String, Object> denomination : denominations) {
                Long id = longValue(denomination.get("id"));
                denomination.put("referenced", id != null && referencedIds.contains(id));
            }
        }

        List<Map<String, Object>> allFaces = new ArrayList<>(faces.values());
        long effectiveSize = Math.max(1, size);
        long offset = Math.max(0, (Math.max(1, current) - 1) * effectiveSize);
        int from = (int) Math.min(offset, allFaces.size());
        int to = (int) Math.min(offset + effectiveSize, allFaces.size());
        return PageResult.of(allFaces.subList(from, to), Math.max(1, current), effectiveSize, allFaces.size());
    }

    /**
     * 后台「礼品卡订单」分页查询（业务视图）。
     *
     * 通用 CRUD 只查 gift_card_order 单表，缺卡种/面额/购买人等展示字段，
     * 因此这里用联表查询一次性补齐，金额单位均为「分」，由前端转元展示。
     *
     * 搜索口径：
     *   orderNo -> gift_card_order.order_no 模糊
     *   buyer   -> app_user.nick_name 模糊
     *   status  -> gift_card_order.status 等值
     */
    public PageResult<Map<String, Object>> listOrders(long current, long size,
                                                       String orderNo, String buyer, String status) {
        StringBuilder where = new StringBuilder(" where o.deleted = 0");
        List<Object> args = new ArrayList<>();

        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and o.order_no like ?");
            args.add("%" + orderNo.trim() + "%");
        }
        if (buyer != null && !buyer.isBlank()) {
            where.append(" and u.nick_name like ?");
            args.add("%" + buyer.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" and o.status = ?");
            args.add(status.trim());
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from gift_card_order o "
                        + "left join app_user u on u.id = o.user_id" + where,
                Long.class, args.toArray());

        long effectiveSize = Math.max(1, size);
        long effectiveCurrent = Math.max(1, current);
        long offset = Math.max(0, (effectiveCurrent - 1) * effectiveSize);

        String sql = "select o.id, o.order_no, o.amount, o.status, o.create_time, o.denomination_id, "
                + "coalesce(d.card_name, d.name, '礼品卡') as card_name, "
                + "d.amount as face_value, "
                + "coalesce(u.nick_name, '') as buyer "
                + "from gift_card_order o "
                + "left join gift_card_denomination d on d.id = o.denomination_id "
                + "left join app_user u on u.id = o.user_id"
                + where
                + " order by o.id desc limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(effectiveSize);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray());
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> srcRow : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderNo", srcRow.get("order_no"));
            row.put("cardName", srcRow.get("card_name"));
            row.put("faceValue", longValue(srcRow.get("face_value")));
            // 当前业务为一单一卡，数量固定为 1
            row.put("quantity", 1);
            row.put("amount", longValue(srcRow.get("amount")));
            row.put("buyer", stringValue(srcRow.get("buyer"), "—"));
            row.put("status", srcRow.get("status"));
            row.put("createTime", srcRow.get("create_time"));
            records.add(row);
        }
        return PageResult.of(records, effectiveCurrent, effectiveSize, total == null ? 0L : total);
    }

    /**
     * 后台「兑换记录」分页查询（业务视图）。
     *
     * 通用 CRUD 只查 exchange_order 单表，缺用户昵称 / 商品名称等展示字段，
     * 这里联表补齐，金额（时光币）为整数点数值，无需分转元。
     *
     * 搜索口径：
     *   recordNo -> exchange_order.exchange_no 模糊
     *   user     -> app_user.nick_name 模糊
     *   product  -> points_product.name 模糊
     *   status   -> exchange_order.status 等值
     */
    public PageResult<Map<String, Object>> listExchangeOrders(long current, long size,
                                                               String recordNo, String user,
                                                               String product, String status) {
        StringBuilder where = new StringBuilder(" where e.deleted = 0");
        List<Object> args = new ArrayList<>();

        if (recordNo != null && !recordNo.isBlank()) {
            where.append(" and e.exchange_no like ?");
            args.add("%" + recordNo.trim() + "%");
        }
        if (user != null && !user.isBlank()) {
            where.append(" and u.nick_name like ?");
            args.add("%" + user.trim() + "%");
        }
        if (product != null && !product.isBlank()) {
            where.append(" and p.name like ?");
            args.add("%" + product.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" and e.status = ?");
            args.add(status.trim());
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from exchange_order e "
                        + "left join app_user u on u.id = e.user_id "
                        + "left join points_product p on p.id = e.points_product_id" + where,
                Long.class, args.toArray());

        long effectiveSize = Math.max(1, size);
        long effectiveCurrent = Math.max(1, current);
        long offset = Math.max(0, (effectiveCurrent - 1) * effectiveSize);

        String sql = "select e.id, e.exchange_no, e.points, e.quantity, e.status, e.create_time, "
                + "e.pickup_code, "
                + "coalesce(u.nick_name, '') as user_name, "
                + "coalesce(p.name, '') as product_name "
                + "from exchange_order e "
                + "left join app_user u on u.id = e.user_id "
                + "left join points_product p on p.id = e.points_product_id"
                + where
                + " order by e.id desc limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(effectiveSize);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray());
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> srcRow : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", longValue(srcRow.get("id")));
            row.put("recordNo", srcRow.get("exchange_no"));
            row.put("user", stringValue(srcRow.get("user_name"), "—"));
            row.put("product", stringValue(srcRow.get("product_name"), "—"));
            row.put("quantity", srcRow.get("quantity") == null ? 1 : ((Number) srcRow.get("quantity")).intValue());
            row.put("points", longValue(srcRow.get("points")));
            row.put("status", srcRow.get("status"));
            row.put("applyTime", srcRow.get("create_time"));
            row.put("pickupCode", srcRow.get("pickup_code"));
            records.add(row);
        }
        return PageResult.of(records, effectiveCurrent, effectiveSize, total == null ? 0L : total);
    }

    public List<Map<String, Object>> listGroups() {
        return jdbcTemplate.queryForList(
                "select code, name, sort, create_time, update_time "
                        + "from gift_card_group where deleted = 0 order by sort asc, code asc");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createGroup(GiftCardGroupRequest request) {
        String name = requireName(request == null ? null : request.name());
        int sort = request == null || request.sort() == null ? 0 : request.sort();
        String code;
        do {
            code = "gift-group-" + UUID.randomUUID().toString().replace("-", "");
        } while (groupExists(code));
        jdbcTemplate.update("insert into gift_card_group (code, name, sort, deleted) values (?, ?, ?, 0)",
                code, name, sort);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("sort", sort);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(String code, GiftCardGroupRequest request) {
        String name = requireName(request == null ? null : request.name());
        int sort = request == null || request.sort() == null ? 0 : request.sort();
        int changed = jdbcTemplate.update(
                "update gift_card_group set name = ?, sort = ?, update_time = CURRENT_TIMESTAMP "
                        + "where code = ? and deleted = 0",
                name, sort, code);
        if (changed == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡分组不存在");
        }
        // 名称是卡面展示元数据，历史（deleted=1）面额也同步，保证历史订单仍能回显当前分组名。
        jdbcTemplate.update("update gift_card_denomination set group_title = ?, update_time = CURRENT_TIMESTAMP "
                + "where group_id = ?", name, code);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(String code) {
        Long faceCount = jdbcTemplate.queryForObject(
                "select count(distinct card_name) from gift_card_denomination "
                        + "where group_id = ? and deleted = 0",
                Long.class, code);
        if (faceCount != null && faceCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分组下存在有效卡面，禁止删除");
        }
        int changed = jdbcTemplate.update(
                "update gift_card_group set deleted = 1, update_time = CURRENT_TIMESTAMP "
                        + "where code = ? and deleted = 0",
                code);
        if (changed == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡分组不存在");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void createFace(SaveGiftCardFaceRequest request) {
        String groupId = requireGroupId(request == null ? null : request.groupId());
        String groupName = requireGroupName(groupId);
        String cardName = requireName(request.cardName());
        int sort = request.sort() == null ? 0 : request.sort();
        List<DenominationRequest> denominations = validateDenominations(request.denominations());

        Long sameFaceCount = jdbcTemplate.queryForObject(
                "select count(*) from gift_card_denomination "
                        + "where group_id = ? and card_name = ? and deleted = 0",
                Long.class, groupId, cardName);
        if (sameFaceCount != null && sameFaceCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "同分组下卡面名称已存在");
        }

        for (DenominationRequest denomination : denominations) {
            jdbcTemplate.update(
                    "insert into gift_card_denomination "
                            + "(code, group_id, group_title, card_name, card_image, name, amount, "
                            + "sale_price, status, sort, deleted) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, 'disabled', ?, 0)",
                    newCode(), groupId, groupName, cardName, request.cardImage(),
                    denominationName(cardName, denomination.amount()), denomination.amount(),
                    denomination.salePrice(), sort);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateFace(Long faceId, SaveGiftCardFaceRequest request) {
        if (faceId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "卡面标识不能为空");
        }
        List<Map<String, Object>> faceRows = jdbcTemplate.queryForList(
                "select group_id, card_name from gift_card_denomination where id = ? and deleted = 0",
                faceId);
        if (faceRows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡卡面不存在");
        }
        String oldGroupId = stringValue(faceRows.get(0).get("group_id"), null);
        String oldCardName = stringValue(faceRows.get(0).get("card_name"), null);
        String groupId = requireGroupId(request == null ? null : request.groupId());
        String groupName = requireGroupName(groupId);
        String cardName = requireName(request == null ? null : request.cardName());
        int sort = request.sort() == null ? 0 : request.sort();
        String cardImage = request.cardImage();
        List<DenominationRequest> denominations = validateDenominations(request.denominations());

        Long targetFaceCount = jdbcTemplate.queryForObject(
                "select count(*) from gift_card_denomination "
                        + "where group_id = ? and card_name = ? and deleted = 0 "
                        + "and (group_id <> ? or card_name <> ?)",
                Long.class, groupId, cardName, oldGroupId, oldCardName);
        if (targetFaceCount != null && targetFaceCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "同分组下卡面名称已存在");
        }

        List<Map<String, Object>> activeRows = jdbcTemplate.queryForList(
                "select id, code, amount, sale_price, status from gift_card_denomination "
                        + "where group_id = ? and card_name = ? and deleted = 0",
                oldGroupId, oldCardName);
        Map<Long, Map<String, Object>> activeById = new LinkedHashMap<>();
        boolean allEnabled = !activeRows.isEmpty();
        for (Map<String, Object> row : activeRows) {
            Long id = longValue(row.get("id"));
            if (id != null) {
                activeById.put(id, row);
            }
            if (!"enabled".equals(String.valueOf(row.get("status")))) {
                allEnabled = false;
            }
        }
        String inheritedStatus = allEnabled ? "enabled" : "disabled";

        Set<Long> submittedIds = new HashSet<>();
        for (DenominationRequest denomination : denominations) {
            Long id = denomination.id();
            if (id == null) {
                continue;
            }
            if (!submittedIds.add(id)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "同一面额不能重复提交");
            }
            Map<String, Object> current = activeById.get(id);
            if (current == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "面额不属于当前卡面");
            }
            Long currentAmount = longValue(current.get("amount"));
            if (isReferenced(id) && !currentAmount.equals(denomination.amount())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "面额已被礼品卡或订单引用，不能修改金额");
            }
        }

        for (Long activeId : activeById.keySet()) {
            if (!submittedIds.contains(activeId)) {
                jdbcTemplate.update("update gift_card_denomination set deleted = 1, "
                        + "update_time = CURRENT_TIMESTAMP where id = ? and deleted = 0", activeId);
            }
        }

        for (DenominationRequest denomination : denominations) {
            if (denomination.id() == null) {
                jdbcTemplate.update(
                        "insert into gift_card_denomination "
                                + "(code, group_id, group_title, card_name, card_image, name, amount, "
                                + "sale_price, status, sort, deleted) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                        newCode(), groupId, groupName, cardName, cardImage,
                        denominationName(cardName, denomination.amount()), denomination.amount(),
                        denomination.salePrice(), inheritedStatus, sort);
            } else {
                jdbcTemplate.update(
                        "update gift_card_denomination set group_id = ?, group_title = ?, card_name = ?, card_image = ?, "
                                + "name = ?, amount = ?, sale_price = ?, sort = ?, update_time = CURRENT_TIMESTAMP "
                                + "where id = ? and deleted = 0",
                        groupId, groupName, cardName, cardImage, denominationName(cardName, denomination.amount()),
                        denomination.amount(), denomination.salePrice(), sort, denomination.id());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setFaceStatus(Long faceId, boolean enabled) {
        List<Map<String, Object>> faceRows = jdbcTemplate.queryForList(
                "select group_id, card_name from gift_card_denomination where id = ? and deleted = 0",
                faceId);
        if (faceRows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡卡面不存在");
        }
        String groupId = stringValue(faceRows.get(0).get("group_id"), null);
        String cardName = stringValue(faceRows.get(0).get("card_name"), null);
        jdbcTemplate.update(
                "update gift_card_denomination set status = ?, update_time = CURRENT_TIMESTAMP "
                        + "where group_id = ? and card_name = ? and deleted = 0",
                enabled ? "enabled" : "disabled", groupId, cardName);
    }

    private Set<Long> loadReferencedIds(Set<Long> denominationIds) {
        if (denominationIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(denominationIds.size(), "?"));
        List<Object> args = new ArrayList<>(denominationIds);
        args.addAll(denominationIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select denomination_id from gift_card where denomination_id in (" + placeholders + ") "
                        + "union select denomination_id from gift_card_order where denomination_id in ("
                        + placeholders + ")",
                args.toArray());
        Set<Long> result = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Long id = longValue(row.get("denomination_id"));
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private boolean isReferenced(Long denominationId) {
        Long count = jdbcTemplate.queryForObject(
                "select (select count(*) from gift_card where denomination_id = ?) "
                        + "+ (select count(*) from gift_card_order where denomination_id = ?)",
                Long.class, denominationId, denominationId);
        return count != null && count > 0;
    }

    private boolean groupExists(String code) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from gift_card_group where code = ?", Long.class, code);
        return count != null && count > 0;
    }

    private String requireGroupName(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡分组不存在");
        }
        try {
            String name = jdbcTemplate.queryForObject(
                    "select name from gift_card_group where code = ? and deleted = 0", String.class, code);
            if (name == null || name.isBlank()) {
                throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡分组不存在");
            }
            return name;
        } catch (EmptyResultDataAccessException e) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼品卡分组不存在");
        }
    }

    private String requireGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "礼品卡分组不能为空");
        }
        return groupId.trim();
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "名称不能为空");
        }
        return name.trim();
    }

    private List<DenominationRequest> validateDenominations(List<DenominationRequest> denominations) {
        if (denominations == null || denominations.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "至少保留一条有效面额");
        }
        Set<Long> amounts = new HashSet<>();
        for (DenominationRequest denomination : denominations) {
            if (denomination == null || denomination.amount() == null || denomination.amount() <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "面额金额必须大于 0");
            }
            if (denomination.salePrice() == null || denomination.salePrice() <= 0
                    || denomination.salePrice() > denomination.amount()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "售价必须大于 0 且不超过面额");
            }
            if (!amounts.add(denomination.amount())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "有效面值不能重复");
            }
        }
        return denominations;
    }

    private String newCode() {
        return "gift-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String denominationName(String cardName, long amount) {
        String yuan = BigDecimal.valueOf(amount, 2).stripTrailingZeros().toPlainString();
        return cardName + " " + yuan + "元礼品卡";
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    public record GiftCardGroupRequest(String name, Integer sort) {
    }

    public record SaveGiftCardFaceRequest(String groupId,
                                          String cardName,
                                          String cardImage,
                                          Integer sort,
                                          List<DenominationRequest> denominations) {
    }

    public record DenominationRequest(Long id, Long amount, Long salePrice) {
    }
}
