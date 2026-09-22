package com.wuling.subject.port;

import java.util.List;

/**
 * 主体（门店/渠道/投资人/供应商/平台）查询端口（第 12 期）。
 *
 * <p>为什么需要：`finance`（3 处）与 `product`（1 处）需要读取主体信息，
 * 而主体数据归属 subject 域。直接注入 `BizSubjectMapper` 会让这些域
 * 在编译期依赖 subject，拆分时无法独立。
 *
 * <p>抽成端口后：
 * <ul>
 *   <li>依赖方只依赖本接口（位于 subject 包内，但作为稳定契约）；</li>
 *   <li>当前实现走本地 Mapper（单体阶段零开销）；</li>
 *   <li>将来 subject 拆为独立服务时，替换为远程实现即可，业务代码不变。</li>
 * </ul>
 *
 * <p><b>只读语义</b>：本端口不提供任何写操作。
 */
public interface SubjectQueryPort {

    /** 主体基本信息（只暴露依赖方需要的字段） */
    class SubjectView {
        private Long id;
        private String code;
        private String name;
        private String subjectType;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSubjectType() {
            return subjectType;
        }

        public void setSubjectType(String subjectType) {
            this.subjectType = subjectType;
        }
    }

    /**
     * 按 ID 查询主体。
     *
     * @return 主体视图；不存在返回 null
     */
    SubjectView findById(Long subjectId);

    /**
     * 查询主体名称（最常用，避免全量加载）。
     */
    String findName(Long subjectId);

    /**
     * 门店绑定的投资人主体 ID。
     *
     * <p>用于分账时确定投资人份额归属（finance/LedgerService 事务内调用）。
     */
    Long findInvestorOfStore(Long storeSubjectId);

    /**
     * 渠道绑定的门店主体 ID 列表。
     */
    List<Long> findStoreIdsByChannel(Long channelSubjectId);

    /**
     * 投资人投资的门店主体 ID 列表。
     */
    List<Long> findStoreIdsByInvestor(Long investorSubjectId);

    /**
     * 按类型取首个主体 ID（如 PLATFORM 平台主体）。
     *
     * <p>用于分账时定位平台主体，属资金路径的只读依赖。
     */
    Long findFirstByType(String subjectType);

    /**
     * 批量查询主体名称（供列表场景避免 N+1）。
     */
    java.util.Map<Long, String> findNames(List<Long> subjectIds);
}
