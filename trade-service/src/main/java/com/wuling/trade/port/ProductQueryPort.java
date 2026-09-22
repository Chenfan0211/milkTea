package com.wuling.trade.port;

/**
 * 交易域对外部域的只读查询端口（第 6 期）。
 *
 * <p>为什么要抽这一层：trade 在下单时需要读取「商品、商品-门店关联、主体（门店）」，
 * 这些数据归属 product / subject 域。直接注入它们的 Mapper 会让 trade
 * 在编译期依赖这两个域，拆分时无法独立。
 *
 * <p>抽成接口后：
 * <ul>
 *   <li>trade 只依赖本接口（位于自己的包内），编译期不再依赖 product/subject；</li>
 *   <li>当前实现仍走本地 Mapper（单体阶段，零网络开销）；</li>
 *   <li>将来拆出 product-service / subject-service 时，只需换一个
 *       Feign 实现，trade 的业务代码无需改动。</li>
 * </ul>
 *
 * <p>只读语义：本端口只提供查询，不提供任何写入 —— 写操作应通过事件或
 * 对应服务的接口完成。
 */
public interface ProductQueryPort {

    /**
     * 按业务商品 ID 查询商品。
     *
     * @param productId 业务商品 ID（如 classic-001）
     * @return 商品视图；不存在返回 null
     */
    ProductView findProduct(String productId);

    /**
     * 校验商品是否已在指定门店上架。
     *
     * @param productId       商品主键
     * @param storeSubjectId  门店主体 ID
     * @return true=已上架
     */
    boolean isProductInStore(Long productId, Long storeSubjectId);

    /**
     * 查询主体名称。
     *
     * @param subjectId 主体 ID
     * @return 名称；不存在返回 null
     */
    String findSubjectName(Long subjectId);

    /** 商品视图（只暴露交易域需要的字段，避免泄漏 product 域实体） */
    class ProductView {
        /** 商品主键 */
        private Long id;
        /** 业务商品 ID */
        private String productId;
        /** 名称 */
        private String name;
        /** 售价（分） */
        private Long price;
        /** 原价（分） */
        private Long originalPrice;
        /** 是否上架：1=上架 */
        private Integer onSale;
        /** 供应商主体 ID */
        private Long supplierSubjectId;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getProductId() {
            return productId;
        }

        public void setProductId(String productId) {
            this.productId = productId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getPrice() {
            return price;
        }

        public void setPrice(Long price) {
            this.price = price;
        }

        public Long getOriginalPrice() {
            return originalPrice;
        }

        public void setOriginalPrice(Long originalPrice) {
            this.originalPrice = originalPrice;
        }

        public Integer getOnSale() {
            return onSale;
        }

        public void setOnSale(Integer onSale) {
            this.onSale = onSale;
        }

        public Long getSupplierSubjectId() {
            return supplierSubjectId;
        }

        public void setSupplierSubjectId(Long supplierSubjectId) {
            this.supplierSubjectId = supplierSubjectId;
        }
    }
}
