package com.wuling.subject.service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 经营主体编码（{@code biz_subject.code}）规则。
 *
 * <p><b>为什么改成「主键派生」</b>：历史实现有三套互不一致的算法，且都以
 * 「当前该类主体的行数」推算序号：
 * <ul>
 *   <li>{@code StoreService#nextStoreCode}：{@code ST-} + (非删除门店数 + 1001)；</li>
 *   <li>{@code AdminSubjectProfileController#nextCode}：{@code RS-/SU-/IV-} + (该类总数 + 1000)；</li>
 *   <li>{@code CrudService#generateCode}：前缀 + 时间戳 + 随机数。</li>
 * </ul>
 * 三者都必然撞 {@code uk_biz_subject_code}（该唯一键不含 {@code deleted}）：
 * <ol>
 *   <li><b>软删除回退</b>：删掉 ST-1003 后非删除计数变 4，下一个又发 ST-1005（已存在）；</li>
 *   <li><b>手工/导入编码</b>：导入一条手填 RS-1004 后行数 4，自动号仍是 RS-1004；</li>
 *   <li><b>并发新增</b>：count 读不到彼此的未提交行，两个请求算出同一编码。</li>
 * </ol>
 * 撞车时抛的是 {@code DuplicateKeyException}，被全局兜底成「服务器内部错误」，
 * 运营只看到 500，完全定位不到原因。
 *
 * <p><b>现在的口径</b>：{@code 前缀 + "-" + 8 位随机数字 + 自增主键}，
 * 例如 {@code ST-48392015106}。
 * 主键由数据库原子分配，因此：
 * <ul>
 *   <li>自增主键追加在末尾，不同主体的正式编码不会重复；</li>
 *   <li>不同类型前缀不同，全局也不会撞（{@code uk_biz_subject_code} 是全局唯一键）；</li>
 *   <li>自动编号不再依赖当前行数，软删除和并发新增不会让下一个号码回退。</li>
 * </ul>
 *
 * <p><b>为什么加 8 位随机数</b>：编码不再暴露连续主体序号的全部规律，同时末尾保留
 * 自增主键作为唯一性根据。随机数固定为 8 位，不足 8 位时前补零。
 *
 * <p><b>兼容</b>：存量编码（4 位种子、手工导入的 {@code SU-TEST37} 等）一律不改写，
 * 前端只把 code 当不透明标识，无需同步迁移。
 */
public final class SubjectCodes {

    /** 前缀：门店。 */
    public static final String STORE = "STORE";
    /** 前缀：门店（历史遗留，部分测试/脚本用此写法）。 */
    private static final String TYPE_STORE = "STORE";

    private SubjectCodes() {
    }

    /**
     * 由主体类型与主键生成正式编码。
     *
     * @param subjectType {@code biz_subject.subject_type}（大小写不敏感）
     * @param id          主体自增主键
     * @return 形如 {@code ST-48392015106} 的编码
     */
    public static String of(String subjectType, long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("主体主键必须为正数，否则编码不可用：id=" + id);
        }
        int random = ThreadLocalRandom.current().nextInt(100_000_000);
        return prefix(subjectType) + "-" + String.format("%08d", random) + id;
    }

    /**
     * 插入前的占位编码。
     *
     * <p>正式编码要等主键分配后才算得出，而 {@code biz_subject.code} 是
     * {@code NOT NULL + UNIQUE}，插入时必须先给一个值。占位码带随机 UUID，
     * 保证同一时刻的多笔插入互不冲突；调用方随后必须立即回写为 {@link #of} 的结果。
     *
     * <p>占位码若因异常残留，其特征是含 {@code -TMP-}，便于事后排查清理。
     */
    public static String placeholder(String subjectType) {
        return prefix(subjectType) + "-TMP-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 判断编码是否为未被回写的占位码。 */
    public static boolean isPlaceholder(String code) {
        return code != null && code.contains("-TMP-");
    }

    /** 主体类型 -> 编码前缀。未知类型统一 {@code SUB}，不会与已知前缀冲突。 */
    public static String prefix(String subjectType) {
        if (subjectType == null) {
            return "SUB";
        }
        return switch (subjectType.trim().toUpperCase()) {
            case TYPE_STORE -> "ST";
            case "CHANNEL" -> "RS";
            case "SUPPLIER" -> "SU";
            case "INVESTOR" -> "IV";
            case "PLATFORM" -> "PT";
            case "LEADER" -> "LD";
            default -> "SUB";
        };
    }
}
