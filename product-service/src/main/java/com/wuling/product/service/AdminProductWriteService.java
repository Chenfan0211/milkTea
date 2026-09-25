package com.wuling.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.AdminProductDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.dto.SpecGroupsDTO;
import com.wuling.product.entity.ProductSpec;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductSpecMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端商品写操作。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>字段白名单</b>：只接受明确列出的字段，防止前端越权改 id/deleted 等列；</li>
 *   <li><b>单位和值域转换</b>：前端用「元」，库中用「分」；前端 onSale 用 on/off，库中用 0/1；</li>
 *   <li>删除为逻辑删除，与全局约定一致。</li>
 * </ul>
 */
@Service
public class AdminProductWriteService {

    private final ProductMapper productMapper;
    private final ProductSpecMapper productSpecMapper;
    private final ProductStoreMapper productStoreMapper;
    private final ProductQueryService productQueryService;

    public AdminProductWriteService(ProductMapper productMapper,
                                   ProductSpecMapper productSpecMapper,
                                   ProductStoreMapper productStoreMapper,
                                   ProductQueryService productQueryService) {
        this.productMapper = productMapper;
        this.productSpecMapper = productSpecMapper;
        this.productStoreMapper = productStoreMapper;
        this.productQueryService = productQueryService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminProductDTO create(Map<String, Object> payload) {
        Product product = new Product();
        product.setProductId(text(payload, "productId"));
        if (!StringUtils.hasText(product.getProductId())) {
            // 未传商品编码时，用 code 兜底，保证 product_id 唯一非空
            String code = text(payload, "code");
            if (!StringUtils.hasText(code)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "缺少商品编码");
            }
            product.setProductId(code);
        }
        if (productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductId, product.getProductId())) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品编码已存在: " + product.getProductId());
        }
        applyEditable(product, payload);
        product.setOnSale(parseOnSale(payload.get("onSale")));
        if (!StringUtils.hasText(product.getName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少商品名称");
        }
        if (product.getStoredValuePrice() == null) {
            product.setStoredValuePrice(0L);
        }
        if (product.getStoredValuePrice() < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "储值立减金额不能为负数");
        }
        productMapper.insert(product);
        return reload(product.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminProductDTO update(Long id, Map<String, Object> payload) {
        Product product = requireProduct(id);
        applyEditable(product, payload);
        if (payload.containsKey("onSale")) {
            product.setOnSale(parseOnSale(payload.get("onSale")));
        }
        if (product.getStoredValuePrice() == null) {
            product.setStoredValuePrice(0L);
        }
        if (product.getStoredValuePrice() < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "储值立减金额不能为负数");
        }
        productMapper.updateById(product);
        return reload(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminProductDTO updateOnSale(Long id, String onSale) {
        Product product = requireProduct(id);
        product.setOnSale(parseOnSale(onSale));
        productMapper.updateById(product);
        return reload(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireProduct(id);
        // MyBatis-Plus 逻辑删除（deleted = 1）
        productMapper.deleteById(id);
    }

    // ---------- 规格组读写 ----------

    /**
     * 读取商品的规格组。
     *
     * <p>存储为行式（一选项一行），此处按 group_code 聚合为「组 -> 选项」结构，
     * 与前端 SpecGroupsEditor 对齐。
     */
    public SpecGroupsDTO getSpecGroups(Long productId) {
        requireProduct(productId);
        List<ProductSpec> specs = productSpecMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, productId)
                .orderByAsc(ProductSpec::getSort)
                .orderByAsc(ProductSpec::getId));

        // 按 group_code 聚合，保持出现顺序
        Map<String, SpecGroupsDTO.Group> byCode = new LinkedHashMap<>();
        for (ProductSpec spec : specs) {
            SpecGroupsDTO.Group group = byCode.computeIfAbsent(spec.getGroupCode(), code -> {
                SpecGroupsDTO.Group g = new SpecGroupsDTO.Group();
                g.setId(spec.getGroupCode());
                g.setLabel(spec.getGroupLabel());
                g.setOptions(new ArrayList<>());
                return g;
            });
            SpecGroupsDTO.Option option = new SpecGroupsDTO.Option();
            option.setId(spec.getOptionCode());
            option.setLabel(spec.getOptionLabel());
            option.setPriceDelta(spec.getPriceDelta());
            option.setSelected(spec.getSelected() != null && spec.getSelected() == 1);
            option.setIcon(spec.getIcon());
            group.getOptions().add(option);
        }

        SpecGroupsDTO dto = new SpecGroupsDTO();
        dto.setGroups(new ArrayList<>(byCode.values()));
        return dto;
    }

    /**
     * 保存商品的规格组（整体替换）。
     *
     * <p>策略：先逻辑删除该商品全部旧规格，再按新结构插入。
     * 之所以整体替换而非增量比对：规格组结构简单、条目少（通常 < 20 行），
     * 整体替换逻辑清晰且不会残留脏数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public SpecGroupsDTO saveSpecGroups(Long productId, SpecGroupsDTO payload) {
        requireProduct(productId);

        // 1) 清理旧规格（逻辑删除）
        List<ProductSpec> olds = productSpecMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, productId));
        for (ProductSpec old : olds) {
            productSpecMapper.deleteById(old.getId());
        }

        // 2) 写入新规格
        List<SpecGroupsDTO.Group> groups = payload == null ? null : payload.getGroups();
        if (groups != null) {
            int sort = 0;
            for (SpecGroupsDTO.Group group : groups) {
                if (group == null || !StringUtils.hasText(group.getId())) {
                    continue;
                }
                List<SpecGroupsDTO.Option> options = group.getOptions();
                if (options == null || options.isEmpty()) {
                    continue;
                }
                for (SpecGroupsDTO.Option option : options) {
                    if (option == null || !StringUtils.hasText(option.getId())) {
                        continue;
                    }
                    ProductSpec spec = new ProductSpec();
                    spec.setProductId(productId);
                    spec.setGroupCode(group.getId());
                    spec.setGroupLabel(StringUtils.hasText(group.getLabel()) ? group.getLabel() : group.getId());
                    spec.setOptionCode(option.getId());
                    spec.setOptionLabel(StringUtils.hasText(option.getLabel()) ? option.getLabel() : option.getId());
                    spec.setPriceDelta(option.getPriceDelta() == null ? 0L : option.getPriceDelta());
                    spec.setSelected(Boolean.TRUE.equals(option.getSelected()) ? 1 : 0);
                    spec.setIcon(option.getIcon());
                    spec.setSort(sort++);
                    productSpecMapper.insert(spec);
                }
            }
        }
        return getSpecGroups(productId);
    }

    // ---------- 门店关联读写 ----------

    /** 读取商品已关联的门店主体 ID 列表 */
    public List<Long> getStoreIds(Long productId) {
        requireProduct(productId);
        return productStoreMapper.selectList(new LambdaQueryWrapper<ProductStore>()
                        .eq(ProductStore::getProductId, productId))
                .stream().map(ProductStore::getStoreSubjectId).distinct().toList();
    }

    /**
     * 保存商品的门店关联（整体替换）。
     *
     * <p>去重后重建，避免同一门店重复插入（表上无唯一键）。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> saveStoreIds(Long productId, List<Long> storeSubjectIds) {
        requireProduct(productId);

        // 物理删除：product_store 是纯关联表，且带唯一键
        // uk_product_store(product_id, store_subject_id)。
        // 注意 MyBatis-Plus 的 delete() 受 @TableLogic 影响，只会置 deleted=1，
        // 旧行仍占用唯一键，导致「先删后重新添加同一门店」触发 Duplicate entry；
        // 故改用 Mapper 上的原生 DELETE（见 ProductStoreMapper#physicalDeleteByProductId）。
        productStoreMapper.physicalDeleteByProductId(productId);

        if (storeSubjectIds != null) {
            for (Long storeId : storeSubjectIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
                ProductStore link = new ProductStore();
                link.setProductId(productId);
                link.setStoreSubjectId(storeId);
                productStoreMapper.insert(link);
            }
        }
        return getStoreIds(productId);
    }

    // ---------- 内部工具 ----------

    private Product requireProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    private AdminProductDTO reload(Long id) {
        // 复用查询服务的组装逻辑（分类名 / 门店 / 规格数 / 上下架 / 分账状态）
        AdminProductDTO dto = productQueryService.getAdminProduct(id);
        if (dto == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        return dto;
    }

    /** 应用可编辑字段（只接受白名单字段，忽略 null） */
    private void applyEditable(Product product, Map<String, Object> payload) {
        if (payload.containsKey("name")) product.setName(text(payload, "name"));
        if (payload.containsKey("code")) product.setCode(text(payload, "code"));
        if (payload.containsKey("categoryId")) product.setCategoryId(number(payload, "categoryId"));
        if (payload.containsKey("description")) product.setDescription(text(payload, "description"));
        if (payload.containsKey("image")) product.setImage(text(payload, "image"));
        if (payload.containsKey("tags")) product.setTags(toJsonArrayText(payload.get("tags")));
        // 金额：前端传「元」，库中存「分」
        if (payload.containsKey("price")) product.setPrice(yuanToFen(payload.get("price")));
        if (payload.containsKey("originalPrice")) product.setOriginalPrice(yuanToFen(payload.get("originalPrice")));
        if (payload.containsKey("costPrice")) product.setCostPrice(yuanToFen(payload.get("costPrice")));
        if (payload.containsKey("platformCommission")) {
            product.setPlatformCommission(yuanToFen(payload.get("platformCommission")));
        }
        if (payload.containsKey("storedValuePrice")) {
            product.setStoredValuePrice(yuanToFen(payload.get("storedValuePrice")));
        }
        if (payload.containsKey("galleryImage")) product.setGalleryImage(text(payload, "galleryImage"));
        if (payload.containsKey("imageDisclaimer")) product.setImageDisclaimer(text(payload, "imageDisclaimer"));
        if (payload.containsKey("promotionText")) product.setPromotionText(text(payload, "promotionText"));
        if (payload.containsKey("ingredients")) product.setIngredients(text(payload, "ingredients"));
        if (payload.containsKey("allergens")) product.setAllergens(text(payload, "allergens"));
        if (payload.containsKey("cupCapacity")) product.setCupCapacity(text(payload, "cupCapacity"));
        if (payload.containsKey("tips")) product.setTips(toJsonArrayText(payload.get("tips")));
    }

    /** onSale: on/off -> 1/0；也接受 1/0、true/false */
    private Integer parseOnSale(Object value) {
        if (value == null) {
            return 1;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String s = String.valueOf(value).trim();
        if ("on".equalsIgnoreCase(s) || "1".equals(s) || "true".equalsIgnoreCase(s)) {
            return 1;
        }
        if ("off".equalsIgnoreCase(s) || "0".equals(s) || "false".equalsIgnoreCase(s)) {
            return 0;
        }
        return 1;
    }

    /** 元 -> 分（四舍五入）；已是整数分则原样返回 */
    private Long yuanToFen(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        if (value instanceof Number n) {
            return Math.round(n.doubleValue() * 100);
        }
        try {
            return Math.round(Double.parseDouble(String.valueOf(value).trim()) * 100);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String text(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Long number(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 标签数组 -> JSON 文本；已是字符串则原样返回 */
    private String toJsonArrayText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(String.valueOf(list.get(i)).replace("\"", "\\\"")).append('"');
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }
}
