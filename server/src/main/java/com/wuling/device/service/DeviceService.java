package com.wuling.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.audit.AuditLogService;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.device.dto.AdminDeviceDTO;
import com.wuling.device.dto.DeviceImportResult;
import com.wuling.device.dto.DeviceUpsert;
import com.wuling.device.entity.DeviceBrewRecord;
import com.wuling.device.entity.DeviceInfo;
import com.wuling.device.entity.DeviceMessageError;
import com.wuling.device.entity.DeviceStatusLog;
import com.wuling.device.entity.DeviceStoreBinding;
import com.wuling.device.mapper.DeviceBrewRecordMapper;
import com.wuling.device.mapper.DeviceInfoMapper;
import com.wuling.device.mapper.DeviceMessageErrorMapper;
import com.wuling.device.mapper.DeviceStatusLogMapper;
import com.wuling.device.mapper.DeviceStoreBindingMapper;
import com.wuling.device.mns.DeviceMessageEnvelope;
import com.wuling.security.AdminUser;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 茶机设备域服务。
 *
 * <p>职责分两块：
 * <ol>
 *   <li><b>队列侧</b>：状态/冲泡消息落库（自动建档、幂等去重、状态流水）；</li>
 *   <li><b>后台侧</b>：设备台账分页查询、绑定/换绑/解绑（含绑定历史）。</li>
 * </ol>
 *
 * <p><b>为什么后台列表用 JdbcTemplate 而不是 MyBatis-Plus</b>：
 * 列表要显示门店名（实时 join biz_subject，门店改名立即生效），
 * 还要支持「未绑定」这类 IS NULL 条件，通用单表查询表达不了。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEVICE_SN_MAX = 64;
    private static final int DEVICE_NAME_MAX = 64;
    private static final int DEVICE_ALIAS_MAX = 64;
    private static final int DEVICE_REMARK_MAX = 255;
    private static final int DEVICE_IMPORT_MAX_ROWS = 5000;
    private static final Pattern DEVICE_IDENTITY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\x00-\\x1F\\x7F]");

    /** 异常报文留档最大长度（TEXT 上限 65535 字节，中文按 3 字节估算） */
    private static final int RAW_BODY_MAX = 16_000;

    /**
     * 门店名快照最大长度（与 biz_subject.name / 设备表 store_name 列的 VARCHAR(128) 对齐）。
     *
     * <p>为什么要显式截断：门店名由运营维护，长度不受设备域控制。
     * 一旦超过列宽，MySQL 严格模式会报 Data too long 并回滚整个事务 ——
     * 冲泡记录会因此反复重试并最终进死信队列（不是丢单条，是整批消费卡住）。
     * 快照只用于展示与回溯，截断比整条消息失败代价小得多。
     */
    private static final int STORE_NAME_MAX = 128;

    private static final String ACTION_BIND = "BIND";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_RESTORE = "RESTORE";

    /**
     * 门店侧搜索关键词最大长度。
     *
     * <p>搜索框是自由输入，超长关键词既无业务价值又会让 LIKE 变慢，故服务端统一截断
     * （最长的可比对字段是 device_name/alias 的 64 字符，50 足够覆盖正常输入）。
     */
    static final int KEYWORD_MAX_LENGTH = 50;

    /** 审计日志模块名（与「系统管理 → 审计日志」页面口径一致） */
    private static final String AUDIT_MODULE_DEVICE = "DEVICE";
    private static final String ACTION_UNBIND = "UNBIND";

    private final DeviceInfoMapper deviceInfoMapper;
    private final DeviceStoreBindingMapper deviceStoreBindingMapper;
    private final DeviceBrewRecordMapper deviceBrewRecordMapper;
    private final DeviceStatusLogMapper deviceStatusLogMapper;
    private final DeviceMessageErrorMapper deviceMessageErrorMapper;
    private final BizSubjectMapper bizSubjectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLog;

    public DeviceService(DeviceInfoMapper deviceInfoMapper,
                         DeviceStoreBindingMapper deviceStoreBindingMapper,
                         DeviceBrewRecordMapper deviceBrewRecordMapper,
                         DeviceStatusLogMapper deviceStatusLogMapper,
                         DeviceMessageErrorMapper deviceMessageErrorMapper,
                         BizSubjectMapper bizSubjectMapper,
                         JdbcTemplate jdbcTemplate,
                         AuditLogService auditLog) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.deviceStoreBindingMapper = deviceStoreBindingMapper;
        this.deviceBrewRecordMapper = deviceBrewRecordMapper;
        this.deviceStatusLogMapper = deviceStatusLogMapper;
        this.deviceMessageErrorMapper = deviceMessageErrorMapper;
        this.bizSubjectMapper = bizSubjectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLog = auditLog;
    }

    // ==================== 队列消息落库 ====================

    /**
     * 处理设备状态消息（messagetype=status）。
     *
     * <p>设备不存在则自动建档（未绑定门店）；状态发生变化时才写流水，
     * 避免设备高频上报把状态表撑爆。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleStatusMessage(DeviceMessageEnvelope envelope) {
        String status = envelope.getOnlineStatus();
        LocalDateTime reportTime = parseReportTime(envelope);
        DeviceInfo device = ensureDevice(envelope);
        if (device == null) {
            // 台账已被运营删除：不重建台账、不写状态流水（流水是台账的附属数据），
            // 否则「删掉的设备」会因为还在上报而自己回到列表里。
            log.info("设备台账已删除，状态消息不重建台账 deviceSn={} status={} reportTime={}",
                    envelope.getDeviceSn(), status, reportTime);
            return;
        }

        // 旧状态不得覆盖新状态：以「当前状态的确立时间」为水位线，
        // 比它更早的状态报文一律丢弃（MQ 是 at-least-once，重试/重复投递会乱序到达）。
        // 同一秒的报文仍按后到者生效 —— 秒级精度下无法再区分先后，保持原有行为。
        LocalDateTime stateSince = device.getLastStatusChangeTime();
        if (stateSince != null && reportTime.isBefore(stateSince)) {
            log.warn("忽略过期的设备状态报文 deviceSn={} 报文状态={} 报文时间={} 当前状态={} 确立于={}",
                    device.getDeviceSn(), status, reportTime, device.getOnlineStatus(), stateSince);
            return;
        }

        boolean changed = !status.equalsIgnoreCase(device.getOnlineStatus() == null ? "" : device.getOnlineStatus());

        DeviceInfo update = new DeviceInfo();
        update.setId(device.getId());
        update.setOnlineStatus(status);
        applyReportFields(update, device, envelope, reportTime);
        if (changed) {
            update.setLastStatusChangeTime(reportTime);
        }
        deviceInfoMapper.updateById(update);

        if (changed) {
            insertStatusLog(device, status, reportTime, envelope.getRawPayload());
            log.info("设备状态变更 deviceSn={} status={} reportTime={}", device.getDeviceSn(), status, reportTime);
        }
    }

    /**
     * 处理冲泡记录消息（messagetype=upload）。
     *
     * <p>门店取「当前绑定」作为写入快照；重复消息（同 device_sn + base_create_time +
     * tea_code + serial_number）由唯一键拦下，不产生重复记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleBrewMessage(DeviceMessageEnvelope envelope) {
        DeviceInfo device = ensureDevice(envelope);
        LocalDateTime reportTime = parseReportTime(envelope);

        DeviceBrewRecord record = new DeviceBrewRecord();
        record.setDeviceSn(envelope.getDeviceSn());
        record.setDeviceName(StringUtils.hasText(envelope.getDeviceName())
                ? envelope.getDeviceName() : (device == null ? null : device.getDeviceName()));
        record.setTeaCode(nullToEmpty(envelope.getTeaCode()));
        record.setTeaName(envelope.getTeaName());
        record.setCustomerCode(envelope.getCustomerCode());
        record.setMadeDay(envelope.getMadeDay());
        record.setSerialNumber(nullToEmpty(envelope.getSerialNumber()));
        record.setBaseCreateTime(reportTime);
        record.setRawPayload(envelope.getRawPayload());
        if (device != null && device.getStoreSubjectId() != null) {
            record.setStoreSubjectId(device.getStoreSubjectId());
            record.setStoreName(truncate(subjectNameIncludeDeleted(device.getStoreSubjectId()), STORE_NAME_MAX));
        }

        try {
            deviceBrewRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 幂等兜底：MQ 只保证 at-least-once，重复投递不应产生重复记录
            log.info("冲泡记录重复已跳过 deviceSn={} baseCreateTime={} teaCode={} serialNumber={}",
                    record.getDeviceSn(), reportTime, record.getTeaCode(), record.getSerialNumber());
        }

        // 冲泡同样是一次「上报」：台账存在时同步刷新「最近上报时间」
        //（台账已被运营删除时 device 为 null，此处不触碰台账）
        touchReportTime(device, envelope, reportTime);
    }

    /** 解析失败/未知类型的报文留档（原样保存，便于事后核对与重放）。 */
    public void recordMessageError(String sourceMessageId, String messagetype, String rawBody, String reason) {
        DeviceMessageError error = new DeviceMessageError();
        error.setSourceMessageId(truncate(sourceMessageId, 128));
        error.setMessagetype(truncate(messagetype, 32));
        error.setRawBody(truncate(rawBody, RAW_BODY_MAX));
        error.setErrorReason(truncate(reason, 255));
        try {
            deviceMessageErrorMapper.insert(error);
        } catch (Exception e) {
            // 留档失败不能再抛：否则会打断 ack 流程，让坏消息被无限重投
            log.error("设备异常报文留档失败 messageId={} reason={}", sourceMessageId, e.getMessage(), e);
        }
    }

    /**
     * 取设备台账；不存在且未被删除过则自动建档（未绑定门店）。
     *
     * <p>两台机器同时收到同一台新设备的首条消息会撞唯一键，
     * 此时回查已插入的行返回，不视为错误。
     *
     * @return 台账行；<b>返回 null 表示该 SN 的台账已被运营删除</b> ——
     *         此时不重建台账（否则「删除」会被一条上报悄悄撤销），
     *         调用方需要按「没有台账」的分支继续落事实数据。
     */
    private DeviceInfo ensureDevice(DeviceMessageEnvelope envelope) {
        DeviceInfo existing = findByDeviceSn(envelope.getDeviceSn());
        if (existing != null) {
            return existing;
        }
        if (existsDeletedLedgerDevice(envelope.getDeviceSn())) {
            // 台账被运营删除过：不再自动重建，否则「删除」形同虚设（消息一来设备就回来了）。
            // 需要重新接收时由运营在后台「新增设备」恢复（复用原行，不产生重复台账）。
            log.info("设备台账已被删除，消息不自动重建台账 deviceSn={}", envelope.getDeviceSn());
            return null;
        }
        DeviceInfo created = new DeviceInfo();
        created.setDeviceSn(envelope.getDeviceSn());
        created.setDeviceName(envelope.getDeviceName());
        created.setEnabled(1);
        try {
            deviceInfoMapper.insert(created);
            log.info("设备自动建档（未绑定门店）deviceSn={} deviceName={}",
                    created.getDeviceSn(), created.getDeviceName());
            return created;
        } catch (DuplicateKeyException e) {
            DeviceInfo concurrent = findByDeviceSn(envelope.getDeviceSn());
            if (concurrent == null) {
                throw e;
            }
            return concurrent;
        }
    }

    private DeviceInfo findByDeviceSn(String deviceSn) {
        return deviceInfoMapper.selectOne(new LambdaQueryWrapper<DeviceInfo>()
                .eq(DeviceInfo::getDeviceSn, deviceSn)
                .last("limit 1"));
    }

    /** 活动设备ID查询必须显式带 deleted = 0，逻辑删除设备不能继续占用设备ID。 */
    private Long findActiveDeviceIdByDeviceName(String deviceName) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from device_info where device_name = ? and deleted = 0 order by id desc limit 1",
                Long.class, deviceName);
        return ids == null || ids.isEmpty() ? null : ids.get(0);
    }

    private void insertStatusLog(DeviceInfo device, String status, LocalDateTime reportTime, String rawPayload) {
        DeviceStatusLog logRow = new DeviceStatusLog();
        logRow.setDeviceSn(device.getDeviceSn());
        logRow.setOnlineStatus(status);
        logRow.setBaseCreateTime(reportTime);
        // 原始 payload 留档：对方日后给 status 报文加字段时可回溯（与冲泡记录同口径，V78）
        logRow.setRawPayload(rawPayload);
        if (device.getStoreSubjectId() != null) {
            logRow.setStoreSubjectId(device.getStoreSubjectId());
            logRow.setStoreName(truncate(subjectNameIncludeDeleted(device.getStoreSubjectId()), STORE_NAME_MAX));
        }
        try {
            deviceStatusLogMapper.insert(logRow);
        } catch (DuplicateKeyException e) {
            log.info("状态流水重复已跳过 deviceSn={} baseCreateTime={}", device.getDeviceSn(), reportTime);
        }
    }

    /**
     * 填充两类报文共有的「上报衍生字段」（最近上报时间 / 补全设备ID）。
     *
     * <p><b>最近上报时间只允许向前推进</b>：MQ 是 at-least-once，重试与重复投递
     * 会让旧报文晚于新报文到达；无条件覆盖会把「最近上报」改小，
     * 运营看到的现象就是「设备明明在报，最近上报却停住了」。
     */
    private void applyReportFields(DeviceInfo target,
                                   DeviceInfo current,
                                   DeviceMessageEnvelope envelope,
                                   LocalDateTime reportTime) {
        if (isNewer(reportTime, current.getLastReportTime())) {
            target.setLastReportTime(reportTime);
        }
        if (!StringUtils.hasText(current.getDeviceName()) && StringUtils.hasText(envelope.getDeviceName())) {
            target.setDeviceName(envelope.getDeviceName());
        }
    }

    /** 仅刷新台账的「最近上报时间」（冲泡报文用；状态报文由 {@link #applyReportFields} 一并处理）。 */
    private void touchReportTime(DeviceInfo device, DeviceMessageEnvelope envelope, LocalDateTime reportTime) {
        if (device == null) {
            return;
        }
        DeviceInfo touch = new DeviceInfo();
        touch.setId(device.getId());
        applyReportFields(touch, device, envelope, reportTime);
        if (touch.getLastReportTime() == null && !StringUtils.hasText(touch.getDeviceName())) {
            return;
        }
        deviceInfoMapper.updateById(touch);
    }

    private static boolean isNewer(LocalDateTime candidate, LocalDateTime current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    /** 是否存在被软删的同 SN 台账（自动建档据此判断「不复活」）。 */
    private boolean existsDeletedLedgerDevice(String deviceSn) {
        return findDeletedLedgerId(deviceSn) != null;
    }

    /** 取被软删的同 SN 台账 id（后台新增设备时复用该行，避免同 SN 出现两行）。 */
    private Long findDeletedLedgerId(String deviceSn) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from device_info where device_sn = ? and deleted = 1 order by id desc limit 1",
                Long.class, deviceSn);
        return ids.isEmpty() ? null : ids.get(0);
    }
    private LocalDateTime parseReportTime(DeviceMessageEnvelope envelope) {
        return LocalDateTime.parse(envelope.getReportTime(), FMT);
    }

    // ==================== 后台接口 ====================

    /**
     * 设备台账分页查询。
     *
     * @param keyword       设备编码/设备ID/别名 模糊匹配
     * @param storeSubjectId 归属门店筛选
     * @param bindStatus    bound=已绑定 / unbound=未绑定 / 其它=全部
     * @param onlineStatus  ONLINE / OFFLINE
     * @param enabled       1启用 0停用
     */
    public PageResult<AdminDeviceDTO> pageDevices(long current,
                                                 long size,
                                                 String keyword,
                                                 Long storeSubjectId,
                                                 String bindStatus,
                                                 String onlineStatus,
                                                 Integer enabled) {
        StringBuilder where = new StringBuilder(" where d.deleted = 0");
        List<Object> args = new ArrayList<>();

        if (StringUtils.hasText(keyword)) {
            where.append(" and (d.device_sn like ? or d.device_name like ? or d.alias like ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (storeSubjectId != null) {
            where.append(" and d.store_subject_id = ?");
            args.add(storeSubjectId);
        }
        if ("bound".equalsIgnoreCase(nullToEmpty(bindStatus))) {
            where.append(" and d.store_subject_id is not null");
        } else if ("unbound".equalsIgnoreCase(nullToEmpty(bindStatus))) {
            where.append(" and d.store_subject_id is null");
        }
        if (StringUtils.hasText(onlineStatus)) {
            where.append(" and d.online_status = ?");
            args.add(onlineStatus.trim().toUpperCase());
        }
        if (enabled != null) {
            where.append(" and d.enabled = ?");
            args.add(enabled);
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from device_info d" + where, Long.class, args.toArray());

        long safeSize = size <= 0 ? 10 : size;
        long offset = Math.max(0, (current - 1) * safeSize);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        List<AdminDeviceDTO> records = jdbcTemplate.query(
                "select d.id, d.device_sn, d.device_name, d.alias, d.remark, d.enabled, d.online_status, "
                        + "d.last_report_time, d.last_status_change_time, d.store_subject_id, d.bind_time, d.create_time, "
                        + "s.name as store_name "
                        + "from device_info d "
                        + "left join biz_subject s on s.id = d.store_subject_id and s.deleted = 0"
                        + where
                        + " order by d.id desc limit ? offset ?",
                (rs, rowNum) -> mapDevice(rs),
                pageArgs.toArray());

        return PageResult.of(records, current, safeSize, total == null ? 0L : total);
    }

    private AdminDeviceDTO mapDevice(ResultSet rs) throws SQLException {
        AdminDeviceDTO dto = new AdminDeviceDTO();
        dto.setId(rs.getLong("id"));
        dto.setDeviceSn(rs.getString("device_sn"));
        dto.setDeviceName(rs.getString("device_name"));
        dto.setAlias(rs.getString("alias"));
        dto.setRemark(rs.getString("remark"));
        dto.setEnabled(rs.getObject("enabled") == null ? null : rs.getInt("enabled"));
        dto.setOnlineStatus(rs.getString("online_status"));
        dto.setLastReportTime(formatTime(rs.getTimestamp("last_report_time")));
        dto.setLastStatusChangeTime(formatTime(rs.getTimestamp("last_status_change_time")));
        long storeSubjectId = rs.getLong("store_subject_id");
        if (storeSubjectId > 0 && !rs.wasNull()) {
            dto.setStoreSubjectId(storeSubjectId);
        }
        dto.setStoreName(rs.getString("store_name"));
        dto.setBindTime(formatTime(rs.getTimestamp("bind_time")));
        dto.setCreateTime(formatTime(rs.getTimestamp("create_time")));
        return dto;
    }

    // ==================== 小程序门店侧（只读） ====================

    /**
     * 门店侧的设备清单分页（小程序「设备管理」页）。
     *
     * <p><b>为什么固定 bound 口径</b>：门店只能看到当前绑定在自己名下的设备，
     * 未绑定设备属运营待处理项，不该出现在门店侧；把筛选写死在服务里，
     * 前端多传参数也绕不过去（越权成本最低的做法是「不可传入」）。
     */
    public PageResult<AdminDeviceDTO> pageStoreDevices(long current, long size, long storeSubjectId) {
        return pageDevices(current, size, null, storeSubjectId, "bound", null, null);
    }

    /**
     * 门店侧的设备概览（小程序「设备管理」页头部：共 N 台 · 在线 M 台）。
     *
     * <p><b>为什么单独一个查询</b>：设备清单是分页的，若用「当前页条数」当总数，
     * 门店设备超过一页时头部数字就会偏小；在线数同理不能只数当前页。
     * 两个数都由服务端一次聚合算出，与列表口径（当前绑定本店）完全一致。
     */
    public Map<String, Object> storeDeviceSummary(long storeSubjectId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select count(*) as total, "
                        + "sum(case when online_status = 'ONLINE' then 1 else 0 end) as online "
                        + "from device_info where deleted = 0 and store_subject_id = ?",
                storeSubjectId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", numberOrZero(row.get("total")));
        summary.put("online", numberOrZero(row.get("online")));
        return summary;
    }

    private static long numberOrZero(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * 门店侧的胶囊冲泡记录分页（小程序「冲泡记录」页）。
     *
     * <p><b>口径</b>：按 {@code store_subject_id}（写入时刻的门店快照）过滤，
     * 与运营后台「冲泡记录」页完全一致 —— 设备解绑或换绑后，历史记录仍归属当时门店。
     *
     * <p><b>时间范围</b>：入参已是「左闭右开」边界（{@code from} 含、{@code to} 不含），
     * 由调用方把「结束日」换算成次日 00:00:00，避免 23:59:59 当秒的记录被漏掉
     * （与后台页面的处理口径相同）。
     *
     * @param deviceSn 设备编码精确匹配，null=全部设备
     * @param keyword  关键词模糊匹配（茶品名/编码、设备编码/ID/别名、客户代码、序列号），null=不筛
     * @param from     起始时间（含），null=不限
     * @param to       结束时间（不含），null=不限
     */
    public PageResult<Map<String, Object>> pageStoreBrewRecords(long current,
                                                               long size,
                                                               long storeSubjectId,
                                                               String deviceSn,
                                                               String keyword,
                                                               LocalDateTime from,
                                                               LocalDateTime to) {
        StringBuilder where = new StringBuilder(" where b.deleted = 0 and b.store_subject_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(storeSubjectId);

        if (StringUtils.hasText(deviceSn)) {
            where.append(" and b.device_sn = ?");
            args.add(deviceSn.trim());
        }
        if (StringUtils.hasText(keyword)) {
            // 一个搜索框搜多个字段：任一命中即返回。
            // coalesce(d.alias,'') 是必要的——别名来自 left join，直接比较 d.alias 会在
            // 「设备台账缺失/已删除」时把行判成不匹配，等于把未绑定设备的记录从结果里悄悄剔掉。
            where.append(" and (b.tea_name like ? escape '\\\\' or b.tea_code like ? escape '\\\\'")
                    .append(" or b.device_sn like ? escape '\\\\' or b.device_name like ? escape '\\\\'")
                    .append(" or coalesce(d.alias, '') like ? escape '\\\\'")
                    .append(" or b.customer_code like ? escape '\\\\' or b.serial_number like ? escape '\\\\')");
            String like = "%" + escapeLike(truncate(keyword.trim(), KEYWORD_MAX_LENGTH)) + "%";
            for (int i = 0; i < 7; i++) {
                args.add(like);
            }
        }
        if (from != null) {
            where.append(" and b.base_create_time >= ?");
            args.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            where.append(" and b.base_create_time < ?");
            args.add(Timestamp.valueOf(to));
        }

        // 统计与列表必须共用同一段 FROM + JOIN：别名条件引用了 d.alias，
        // 统计语句缺 join 会直接 SQL 报错（join 按 (device_sn, deleted=0) 唯一，不会放大行数）。
        String fromClause = " from device_brew_record b"
                + " left join device_info d on d.device_sn = b.device_sn and d.deleted = 0";

        Long total = jdbcTemplate.queryForObject(
                "select count(*)" + fromClause + where, Long.class, args.toArray());

        long safeSize = size <= 0 ? 20 : size;
        long offset = Math.max(0, (current - 1) * safeSize);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        List<Map<String, Object>> records = jdbcTemplate.query(
                "select b.id, b.device_sn, b.device_name, b.store_name, b.tea_code, b.tea_name, "
                        + "b.customer_code, b.made_day, b.serial_number, b.base_create_time, b.create_time, "
                        + "d.alias as device_alias "
                        + fromClause.substring(1)
                        + where
                        + " order by b.base_create_time desc, b.id desc limit ? offset ?",
                (rs, rowNum) -> mapStoreBrewRecord(rs),
                pageArgs.toArray());

        return PageResult.of(records, current, safeSize, total == null ? 0L : total);
    }

    /** 冲泡记录行 → 小程序可直读的驼峰结构（字段名即前端读取字段）。 */
    private Map<String, Object> mapStoreBrewRecord(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("baseCreateTime", formatTime(rs.getTimestamp("base_create_time")));
        row.put("deviceSn", rs.getString("device_sn"));
        row.put("deviceName", rs.getString("device_name"));
        row.put("alias", rs.getString("device_alias"));
        row.put("storeName", rs.getString("store_name"));
        row.put("teaCode", rs.getString("tea_code"));
        row.put("teaName", rs.getString("tea_name"));
        row.put("customerCode", rs.getString("customer_code"));
        row.put("madeDay", rs.getString("made_day"));
        row.put("serialNumber", rs.getString("serial_number"));
        return row;
    }

    /** 单条实体 → 列表 DTO（新增/编辑接口回显用，字段口径与分页查询保持一致）。 */
    private AdminDeviceDTO toDto(DeviceInfo device) {
        AdminDeviceDTO dto = new AdminDeviceDTO();
        dto.setId(device.getId());
        dto.setDeviceSn(device.getDeviceSn());
        dto.setDeviceName(device.getDeviceName());
        dto.setAlias(device.getAlias());
        dto.setRemark(device.getRemark());
        dto.setEnabled(device.getEnabled());
        dto.setOnlineStatus(device.getOnlineStatus());
        dto.setLastReportTime(device.getLastReportTime() == null ? null : device.getLastReportTime().format(FMT));
        dto.setLastStatusChangeTime(device.getLastStatusChangeTime() == null
                ? null : device.getLastStatusChangeTime().format(FMT));
        dto.setStoreSubjectId(device.getStoreSubjectId());
        dto.setStoreName(subjectName(device.getStoreSubjectId()));
        dto.setBindTime(device.getBindTime() == null ? null : device.getBindTime().format(FMT));
        dto.setCreateTime(device.getCreateTime() == null ? null : device.getCreateTime().format(FMT));
        return dto;
    }

    /**
     * 新增设备（运营手工录入，用于「先建档再等设备上报」的场景）。
     *
     * <p>若同 SN 的台账曾被删除（软删），这里<b>恢复原行</b>而不是插一行新的：
     * device_info 的唯一键是 (device_sn, deleted)，直接 insert 会留下两行同 SN 台账，
     * 后续按 SN 排查、或再删一次时很容易搞混。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminDeviceDTO createDevice(DeviceUpsert upsert) {
        List<String> errors = validateDeviceUpsert(upsert, null);
        if (!errors.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, errors.get(0));
        }
        String deviceSn = trimToNull(upsert.getDeviceSn());
        String deviceName = trimToNull(upsert.getDeviceName());
        DeviceInfo device = new DeviceInfo();
        device.setDeviceSn(deviceSn);
        device.setDeviceName(deviceName);
        device.setAlias(trimToNull(upsert.getAlias()));
        device.setRemark(trimToNull(upsert.getRemark()));
        device.setEnabled(upsert.getEnabled() == null ? 1 : upsert.getEnabled());

        Long deletedId = findDeletedLedgerId(deviceSn);
        if (deletedId != null) {
            // 先把 deleted 复位并清掉删除前的在线状态（已失效），再走 MyBatis-Plus 更新：
            // 它的 @TableLogic 会给 update 追加 deleted = 0 条件，所以必须先让这行「活过来」。
            jdbcTemplate.update("update device_info set deleted = 0, online_status = null, "
                    + "last_report_time = null, last_status_change_time = null, "
                    + "store_subject_id = null, bind_time = null where id = ?", deletedId);
            device.setId(deletedId);
            deviceInfoMapper.updateById(device);
            log.info("恢复已删除设备台账 deviceId={} deviceSn={}", deletedId, deviceSn);
            auditLog.recordChange(currentOperator(), AUDIT_MODULE_DEVICE, ACTION_RESTORE, deviceSn,
                    "deleted=1", "deleted=0", "后台新增设备，复用并恢复已删除台账", null);
            return toDto(requireDevice(deletedId));
        }

        try {
            deviceInfoMapper.insert(device);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "设备编码已存在：" + deviceSn);
        }
        return toDto(device);
    }

    /** 编辑设备（设备编码可改，但必须保持唯一）。 */
    @Transactional(rollbackFor = Exception.class)
    public AdminDeviceDTO updateDevice(long deviceId, DeviceUpsert upsert) {
        DeviceInfo device = requireDevice(deviceId);
        List<String> errors = validateDeviceUpsert(upsert, deviceId);
        if (!errors.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, errors.get(0));
        }
        String deviceSn = trimToNull(upsert.getDeviceSn());
        if (!deviceSn.equals(device.getDeviceSn())) {
            DeviceInfo other = findByDeviceSn(deviceSn);
            if (other != null && !other.getId().equals(device.getId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "设备编码已存在：" + deviceSn);
            }
            device.setDeviceSn(deviceSn);
        }
        device.setDeviceName(trimToNull(upsert.getDeviceName()));
        device.setAlias(trimToNull(upsert.getAlias()));
        device.setRemark(trimToNull(upsert.getRemark()));
        device.setEnabled(upsert.getEnabled() == null ? 1 : upsert.getEnabled());
        deviceInfoMapper.updateById(device);
        return toDto(device);
    }

    /**
     * 批量导入设备。
     *
     * <p>整批先校验、后写入：任意一行失败时不新增、也不恢复任何台账。
     * 校验口径与单条新增/编辑一致，逻辑删除的设备不占用编码和设备ID。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeviceImportResult importDevices(List<DeviceUpsert> rows) {
        int total = rows == null ? 0 : rows.size();
        if (total == 0) {
            return DeviceImportResult.rejected(0, List.of("导入文件没有设备数据"));
        }
        if (total > DEVICE_IMPORT_MAX_ROWS) {
            return DeviceImportResult.rejected(total,
                    List.of("单次最多 " + DEVICE_IMPORT_MAX_ROWS + " 行，请拆分文件后重试"));
        }

        List<String> errors = new ArrayList<>();
        Set<String> seenDeviceSn = new HashSet<>();
        Set<String> seenDeviceName = new HashSet<>();

        for (int i = 0; i < total; i++) {
            DeviceUpsert row = rows.get(i);
            int line = i + 1;
            List<String> rowErrors = validateDeviceUpsert(row, null);
            String deviceSn = row == null ? null : trimToNull(row.getDeviceSn());
            String deviceName = row == null ? null : trimToNull(row.getDeviceName());

            if (deviceSn != null && !seenDeviceSn.add(deviceSn)) {
                rowErrors.add("设备编码重复：" + deviceSn);
            }
            if (deviceName != null && !seenDeviceName.add(deviceName)) {
                rowErrors.add("设备ID重复：" + deviceName);
            }
            for (String error : rowErrors) {
                errors.add("第 " + line + " 行：" + error);
            }
        }

        if (!errors.isEmpty()) {
            return DeviceImportResult.rejected(total, errors);
        }

        for (DeviceUpsert row : rows) {
            createDevice(row);
        }
        return DeviceImportResult.imported(total);
    }

    /** 校验后台新增/编辑/导入共用的设备字段和活动数据唯一性。 */
    private List<String> validateDeviceUpsert(DeviceUpsert upsert, Long excludeDeviceId) {
        List<String> errors = new ArrayList<>();
        String deviceSn = upsert == null ? null : trimToNull(upsert.getDeviceSn());
        String deviceName = upsert == null ? null : trimToNull(upsert.getDeviceName());
        String alias = upsert == null ? null : trimToNull(upsert.getAlias());
        String remark = upsert == null ? null : trimToNull(upsert.getRemark());

        if (deviceSn == null) {
            errors.add("设备编码（deviceSN）不能为空");
        } else if (!DEVICE_IDENTITY_PATTERN.matcher(deviceSn).matches()) {
            errors.add("设备编码仅允许字母、数字、点、下划线、短横线，且不超过 " + DEVICE_SN_MAX + " 个字符");
        } else {
            DeviceInfo existing = findByDeviceSn(deviceSn);
            if (existing != null && (excludeDeviceId == null || !Objects.equals(existing.getId(), excludeDeviceId))) {
                errors.add("设备编码已存在：" + deviceSn);
            }
        }

        if (deviceName == null) {
            errors.add("设备ID（deviceName）不能为空");
        } else if (!DEVICE_IDENTITY_PATTERN.matcher(deviceName).matches()) {
            errors.add("设备ID仅允许字母、数字、点、下划线、短横线，且不超过 " + DEVICE_NAME_MAX + " 个字符");
        } else {
            Long activeId = findActiveDeviceIdByDeviceName(deviceName);
            if (activeId != null && (excludeDeviceId == null || !activeId.equals(excludeDeviceId))) {
                errors.add("设备ID已存在：" + deviceName);
            }
        }

        if (alias != null && alias.length() > DEVICE_ALIAS_MAX) {
            errors.add("别名不能超过 " + DEVICE_ALIAS_MAX + " 个字符");
        }
        if (remark != null && remark.length() > DEVICE_REMARK_MAX) {
            errors.add("备注不能超过 " + DEVICE_REMARK_MAX + " 个字符");
        }
        if (alias != null && CONTROL_CHAR_PATTERN.matcher(alias).find()) {
            errors.add("别名不能包含控制字符");
        }
        if (remark != null && CONTROL_CHAR_PATTERN.matcher(remark).find()) {
            errors.add("备注不能包含控制字符");
        }
        if (upsert != null && upsert.getEnabled() != null
                && upsert.getEnabled() != 0 && upsert.getEnabled() != 1) {
            errors.add("启用状态只能为启用或停用");
        }
        return errors;
    }

    /**
     * 删除设备台账（软删；需先解绑门店）。
     *
     * <p><b>为什么要求先解绑</b>：删除会让该设备之后的报文失去门店归属落点 ——
     * 仍在服役的设备被误删后，就只能从「冲泡记录」里看到没有门店的裸记录。
     * 把解绑作为删除前的显式动作，保证删除是「有意图的清理」。
     *
     * <p><b>删除后同 SN 的消息不会自动重建台账</b>（见 ensureDevice），只落事实表；
     * 需要重新接收时用「新增设备」恢复（复用原行）。
     *
     * <p><b>删除原因落库</b>：写一条 {@code audit_log}（module=DEVICE / action=DELETE / target=deviceSN），
     * 「系统管理 → 审计日志」可按操作对象=设备编码查到原因、操作人与时间；
     * 同时写服务端日志，便于按时间线排查。删除的台账行本身仍保留在库里（deleted=1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDevice(long deviceId, String reason) {
        DeviceInfo device = requireDevice(deviceId);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "删除备注不能为空");
        }
        String normalizedReason = reason.trim();
        if (device.getStoreSubjectId() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该设备仍绑定门店，请先解绑再删除");
        }
        int affected = jdbcTemplate.update(
                "update device_info set deleted = 1 where id = ? and deleted = 0", deviceId);
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "设备不存在或已删除");
        }
        String operator = currentOperator();
        // 审计留痕：「谁在什么时候因为什么删了哪台设备」——原因进库，可查可倒查
        auditLog.recordChange(operator, AUDIT_MODULE_DEVICE, ACTION_DELETE, device.getDeviceSn(),
                deviceSnapshot(device), "deleted=1", normalizedReason, null);
        log.info("删除设备台账 deviceId={} deviceSn={} operator={} reason={}",
                device.getId(), device.getDeviceSn(), operator, truncate(normalizedReason, 255));
    }

    /** 台账快照（写审计用）：只记关键可读字段，避免把整行塞进 audit_log。 */
    private static String deviceSnapshot(DeviceInfo device) {
        return "deviceName=" + nullToEmpty(device.getDeviceName())
                + ";alias=" + nullToEmpty(device.getAlias())
                + ";onlineStatus=" + nullToEmpty(device.getOnlineStatus())
                + ";enabled=" + (device.getEnabled() == null ? "" : device.getEnabled())
                + ";storeSubjectId=" + (device.getStoreSubjectId() == null ? "" : device.getStoreSubjectId());
    }
    /**
     * 绑定/换绑门店。
     *
     * <p>换绑时先给旧门店补一条 UNBIND 历史，再写新门店的 BIND 历史，保证
     * 「某台设备某段时间属于哪家门店」可完整回溯。
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindStore(long deviceId, Long storeSubjectId, String remark) {
        if (storeSubjectId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择要绑定的门店");
        }
        DeviceInfo device = requireDevice(deviceId);
        BizSubject store = requireStore(storeSubjectId);
        if (storeSubjectId.equals(device.getStoreSubjectId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该设备已绑定此门店，无需重复绑定");
        }
        LocalDateTime now = LocalDateTime.now();
        String operator = currentOperator();

        if (device.getStoreSubjectId() != null) {
            BizSubject previous = findSubject(device.getStoreSubjectId());
            insertBinding(device, previous, ACTION_UNBIND, null, now, operator,
                    "换绑门店：" + store.getName());
        }
        insertBinding(device, store, ACTION_BIND, now, null, operator, remark);

        device.setStoreSubjectId(storeSubjectId);
        device.setBindTime(now);
        deviceInfoMapper.updateById(device);
        log.info("设备绑定门店 deviceId={} deviceSn={} storeSubjectId={} operator={}",
                device.getId(), device.getDeviceSn(), storeSubjectId, operator);
    }

    /** 解绑门店（设备回到「未绑定」，之后的上报将不再归属门店）。 */
    @Transactional(rollbackFor = Exception.class)
    public void unbindStore(long deviceId, String reason) {
        DeviceInfo device = requireDevice(deviceId);
        if (device.getStoreSubjectId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该设备当前未绑定门店");
        }
        BizSubject store = findSubject(device.getStoreSubjectId());
        String operator = currentOperator();
        insertBinding(device, store, ACTION_UNBIND, null, LocalDateTime.now(), operator, reason);

        // store_subject_id / bind_time 需要置空，而 MyBatis-Plus 默认忽略 null 字段，
        // 故此处显式 SQL 更新，避免「解绑点了没反应」。
        jdbcTemplate.update("update device_info set store_subject_id = null, bind_time = null where id = ? and deleted = 0",
                device.getId());
        log.info("设备解绑门店 deviceId={} deviceSn={} storeSubjectId={} operator={}",
                device.getId(), device.getDeviceSn(), device.getStoreSubjectId(), operator);
    }

    /** 绑定历史（设备详情弹层用），新→旧排列。 */
    public List<Map<String, Object>> listBindingHistory(long deviceId) {
        requireDevice(deviceId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, action, store_subject_id, store_name, bind_time, unbind_time, operator, remark, create_time "
                        + "from device_store_binding where device_id = ? and deleted = 0 order by id desc limit 100",
                deviceId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            String action = String.valueOf(row.get("action"));
            item.put("id", row.get("id"));
            item.put("action", action);
            item.put("actionLabel", ACTION_BIND.equals(action) ? "绑定" : "解绑");
            item.put("storeSubjectId", row.get("store_subject_id"));
            item.put("storeName", row.get("store_name"));
            item.put("bindTime", formatValue(row.get("bind_time")));
            item.put("unbindTime", formatValue(row.get("unbind_time")));
            item.put("operator", row.get("operator"));
            item.put("remark", row.get("remark"));
            item.put("createTime", formatValue(row.get("create_time")));
            result.add(item);
        }
        return result;
    }

    // ==================== 内部工具 ====================

    private DeviceInfo requireDevice(long deviceId) {
        DeviceInfo device = deviceInfoMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "设备不存在或已删除");
        }
        return device;
    }

    private BizSubject requireStore(Long storeSubjectId) {
        BizSubject subject = findSubject(storeSubjectId);
        if (subject == null || !"STORE".equalsIgnoreCase(nullToEmpty(subject.getSubjectType()))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "门店不存在，请重新选择");
        }
        return subject;
    }

    private BizSubject findSubject(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        return bizSubjectMapper.selectById(subjectId);
    }

    private String subjectName(Long subjectId) {
        BizSubject subject = findSubject(subjectId);
        return subject == null ? null : subject.getName();
    }

    /**
     * 读门店名用于「写入门店名快照」，<b>包含已被逻辑删除的门店</b>。
     *
     * <p>为什么不复用 {@link #subjectName}：它走 {@code BizSubjectMapper.selectById}，
     * 而 {@code BizSubject.deleted} 标了 {@code @TableLogic}，门店被删后根本查不到 ——
     * 快照会被写成 NULL，与 V77 的「门店删除时只保留 store_name 快照」口径直接冲突，
     * 表现为「解绑/换绑历史里看不出当时是哪家门店」。
     * 快照是审计字段，门店删除后仍要可回溯，故这里显式直读一次、绕过逻辑删除。
     */
    private String subjectNameIncludeDeleted(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        List<String> names = jdbcTemplate.queryForList(
                "select name from biz_subject where id = ?", String.class, subjectId);
        return names.isEmpty() ? null : names.get(0);
    }

    private void insertBinding(DeviceInfo device,
                              BizSubject store,
                              String action,
                              LocalDateTime bindTime,
                              LocalDateTime unbindTime,
                              String operator,
                              String remark) {
        DeviceStoreBinding binding = new DeviceStoreBinding();
        binding.setDeviceId(device.getId());
        binding.setDeviceSn(device.getDeviceSn());
        // 门店可能已被逻辑删除（findSubject 返回 null，如解绑/换绑时门店已删）：
        // 此时 store_subject_id 置空（避免外键指向已删主体，见 V77），
        // 但门店名快照必须保留 —— 绑定历史是审计数据，
        // 门店删了也要能回溯「这台设备当时挂在哪家门店」。
        binding.setStoreSubjectId(store == null ? null : store.getId());
        binding.setStoreName(truncate(store == null
                ? subjectNameIncludeDeleted(device.getStoreSubjectId())
                : store.getName(), STORE_NAME_MAX));
        binding.setAction(action);
        binding.setBindTime(bindTime);
        binding.setUnbindTime(unbindTime);
        binding.setOperator(operator);
        binding.setRemark(truncate(remark, 255));
        deviceStoreBindingMapper.insert(binding);
    }

    private String currentOperator() {
        String username = AdminUser.getUsername();
        return StringUtils.hasText(username) ? truncate(username, 64) : "system";
    }

    private static String formatTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().format(FMT);
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(FMT);
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(FMT);
        }
        return String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 转义 LIKE 通配符。
     *
     * <p>为什么不直接拼：用户输入里的 {@code %} 或 {@code _} 不清除，会被 MySQL 当通配符 ——
     * 只敲一个 {@code %} 就等于「无条件全表扫描」。SQL 侧统一用 {@code escape '\'}，
     * 且显式转义反斜杠本身，保证默认转义符被改写时（sql_mode 变化）依然正确。
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
