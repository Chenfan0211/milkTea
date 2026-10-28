package com.wuling.product.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MallProductAdminDTO;
import com.wuling.product.dto.MallProductSaveRequest;
import com.wuling.product.dto.MallSkuAdminDTO;
import com.wuling.product.dto.MallStockAdjustRequest;
import com.wuling.product.entity.MallCategory;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.entity.MallSku;
import com.wuling.product.entity.MallStockLog;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import com.wuling.product.mapper.MallSkuMapper;
import com.wuling.product.mapper.MallStockLogMapper;
import com.wuling.product.port.SubjectQueryPort;
import com.wuling.product.port.SplitRuleValidationPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
@Service
public class MallProductAdminService {
    private static final long MAX_PAGE_SIZE = 100;
    private static final Set<String> PRODUCT_STATUSES = Set.of("DRAFT", "ON_SALE", "OFF_SALE");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final MallProductMapper productMapper;
    private final MallSkuMapper skuMapper;
    private final MallCategoryMapper categoryMapper;
    private final MallStockLogMapper stockLogMapper;
    private final SubjectQueryPort subjectQueryPort;
    private final ObjectMapper objectMapper;
    private final SplitRuleValidationPort splitRuleValidationPort;
    public MallProductAdminService(MallProductMapper productMapper,
                                   MallSkuMapper skuMapper,
                                   MallCategoryMapper categoryMapper,
                                   MallStockLogMapper stockLogMapper,
                                   SubjectQueryPort subjectQueryPort) {
        this(productMapper, skuMapper, categoryMapper, stockLogMapper, subjectQueryPort,
                new ObjectMapper(), null);
    }
    public MallProductAdminService(MallProductMapper productMapper,
                                   MallSkuMapper skuMapper,
                                   MallCategoryMapper categoryMapper,
                                   MallStockLogMapper stockLogMapper,
                                   SubjectQueryPort subjectQueryPort,
                                   ObjectMapper objectMapper) {
        this(productMapper, skuMapper, categoryMapper, stockLogMapper, subjectQueryPort,
                objectMapper, null);
    }
    @Autowired
    public MallProductAdminService(MallProductMapper productMapper,
                                   MallSkuMapper skuMapper,
                                   MallCategoryMapper categoryMapper,
                                   MallStockLogMapper stockLogMapper,
                                   SubjectQueryPort subjectQueryPort,
                                   ObjectMapper objectMapper,
                                   SplitRuleValidationPort splitRuleValidationPort) {
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.categoryMapper = categoryMapper;
        this.stockLogMapper = stockLogMapper;
        this.subjectQueryPort = subjectQueryPort;
        this.objectMapper = objectMapper;
        this.splitRuleValidationPort = splitRuleValidationPort;
    }
    public PageResult<MallProductAdminDTO> pageProducts(long page, long size,
                                                        Long categoryId,
                                                        String status,
                                                        String keyword) {
        long safePage = page < 1 ? 1 : page;
        long safeSize = size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        LambdaQueryWrapper<MallProduct> query = new LambdaQueryWrapper<MallProduct>()
                .eq(categoryId != null, MallProduct::getCategoryId, categoryId)
                .eq(StringUtils.hasText(status), MallProduct::getStatus, upper(status))
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(MallProduct::getName, keyword.trim())
                        .or().like(MallProduct::getProductCode, keyword.trim()))
                .orderByAsc(MallProduct::getSort)
                .orderByDesc(MallProduct::getId);
        Page<MallProduct> result = productMapper.selectPage(new Page<>(safePage, safeSize), query);
        Map<Long, List<MallSku>> skusByProduct = loadSkus(result.getRecords());
        Map<Long, String> storeNames = subjectNames(result.getRecords().stream()
                .map(MallProduct::getAttributionStoreSubjectId).filter(Objects::nonNull).toList());
        Map<Long, String> channelNames = subjectNames(result.getRecords().stream()
                .map(MallProduct::getAttributionChannelSubjectId).filter(Objects::nonNull).toList());
        Map<Long, String> suppliers = subjectNames(skusByProduct.values().stream().flatMap(List::stream)
                .map(MallSku::getSupplierSubjectId).filter(Objects::nonNull).toList());
        Map<Long, String> categories = loadCategoryNames(result.getRecords());
        List<MallProductAdminDTO> records = result.getRecords().stream()
                .map(product -> toAdminDTO(product, skusByProduct.getOrDefault(product.getId(), List.of()),
                        categories, storeNames, channelNames, suppliers))
                .toList();
        return PageResult.of(records, result.getCurrent(), result.getSize(), result.getTotal());
    }
    public MallProductAdminDTO getProduct(Long id) {
        MallProduct product = requireProduct(id);
        List<MallSku> skus = loadSkus(List.of(product)).getOrDefault(id, List.of());
        Map<Long, String> suppliers = subjectNames(skus.stream().map(MallSku::getSupplierSubjectId)
                .filter(Objects::nonNull).toList());
        String storeName = subjectQueryPort.findName(product.getAttributionStoreSubjectId());
        String channelName = subjectQueryPort.findName(product.getAttributionChannelSubjectId());
        Map<Long, String> storeNames = new HashMap<>();
        if (product.getAttributionStoreSubjectId() != null) {
            storeNames.put(product.getAttributionStoreSubjectId(), storeName);
        }
        Map<Long, String> channelNames = new HashMap<>();
        if (product.getAttributionChannelSubjectId() != null) {
            channelNames.put(product.getAttributionChannelSubjectId(), channelName);
        }
        return toAdminDTO(product, skus, loadCategoryNames(List.of(product)), storeNames, channelNames, suppliers);
    }
    @Transactional(rollbackFor = Exception.class)
    public MallProductAdminDTO create(MallProductSaveRequest request) {
        String productCode = requireText(request.getProductCode(), "商品编码不能为空");
        MallProduct existing = productMapper.selectByProductCodeIncludingDeleted(productCode);
        if (existing != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品编码已存在");
        }
        MallProduct product = new MallProduct();
        product.setProductCode(productCode);
        product.setSalesCount(0);
        apply(product, request);
        if ("ON_SALE".equals(product.getStatus())) {
            validateOnSale(product, List.of());
        }
        productMapper.insert(product);
        return getProduct(product.getId());
    }
    @Transactional(rollbackFor = Exception.class)
    public MallProductAdminDTO update(Long id, MallProductSaveRequest request) {
        MallProduct product = requireProduct(id);
        String productCode = requireText(request.getProductCode(), "商品编码不能为空");
        MallProduct existing = productMapper.selectByProductCodeIncludingDeleted(productCode);
        if (existing != null && !existing.getId().equals(id)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品编码已存在");
        }
        product.setProductCode(productCode);
        String previousStatus = product.getStatus();
        apply(product, request);
        if (!StringUtils.hasText(request.getStatus())) {
            product.setStatus(previousStatus);
        }
        if ("ON_SALE".equals(product.getStatus())) {
            List<MallSku> skus = loadSkus(List.of(product)).getOrDefault(id, List.of());
            validateOnSale(product, skus);
        }
        productMapper.updateById(product);
        return getProduct(id);
    }
    @Transactional(rollbackFor = Exception.class)
    public MallProductAdminDTO updateStatus(Long id, String targetStatus) {
        MallProduct product = requireProduct(id);
        String status = upper(targetStatus);
        if (!"ON_SALE".equals(status) && !"OFF_SALE".equals(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品状态仅支持 ON_SALE/OFF_SALE");
        }
        if ("ON_SALE".equals(status)) {
            validateOnSale(product, loadSkus(List.of(product)).getOrDefault(id, List.of()));
        }
        product.setStatus(status);
        productMapper.updateById(product);
        return getProduct(id);
    }
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireProduct(id);
        if (stockLogMapper != null && stockLogMapper.existsByProductId(id)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品已有订单或库存流水，只能下架，不能删除");
        }
        productMapper.deleteById(id);
    }
    @Transactional(rollbackFor = Exception.class)
    public List<MallSkuAdminDTO> saveSkus(Long productId, List<MallSkuAdminDTO> requests) {
        MallProduct product = requireProduct(productId);
        List<MallSkuAdminDTO> items = requests == null ? List.of() : requests;
        validateSkuPayload(productId, items);
        List<MallSku> existing = lockSkus(
                loadSkus(List.of(product)).getOrDefault(productId, List.of()));
        Map<Long, MallSku> existingById = existing.stream()
                .collect(Collectors.toMap(MallSku::getId, Function.identity()));
        Set<Long> retainedIds = new HashSet<>();
        List<MallSku> saved = new ArrayList<>();
        for (MallSkuAdminDTO item : items) {
            MallSku sku;
            if (item.getId() == null) {
                sku = new MallSku();
                sku.setProductId(productId);
                sku.setLockedStock(0);
                sku.setVersion(0);
            } else {
                sku = existingById.get(item.getId());
                if (sku == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 不属于当前商品");
                }
                retainedIds.add(item.getId());
            }
            applySku(sku, item);
            saved.add(sku);
        }
        for (MallSku old : existing) {
            if (!retainedIds.contains(old.getId())) {
                if (value(old.getStock()) > 0 || value(old.getLockedStock()) > 0) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 仍有库存或锁定库存，不能删除");
                }
                skuMapper.deleteById(old.getId());
            }
        }
        for (MallSku sku : saved) {
            if (sku.getId() == null) {
                skuMapper.insert(sku);
            } else {
                skuMapper.updateById(sku);
            }
        }
        if ("ON_SALE".equals(product.getStatus())) {
            validateOnSale(product, saved);
        }
        Map<Long, String> suppliers = subjectNames(saved.stream().map(MallSku::getSupplierSubjectId)
                .filter(Objects::nonNull).toList());
        return saved.stream().map(sku -> toAdminSku(sku, suppliers.get(sku.getSupplierSubjectId()))).toList();
    }
    @Transactional(rollbackFor = Exception.class)
    public MallSkuAdminDTO adjustStock(Long productId, Long skuId,
                                       MallStockAdjustRequest request, String operatorId) {
        MallSku sku = skuMapper.selectByIdForUpdate(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商城 SKU 不存在");
        }
        if (request == null || request.getStock() == null || request.getStock() < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "库存不能为负数");
        }
        int locked = value(sku.getLockedStock());
        if (request.getStock() < locked) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "库存不能低于已锁定库存");
        }
        int before = value(sku.getStock());
        int delta = request.getStock() - before;
        skuMapper.updateStockState(skuId, request.getStock(), locked);
        MallStockLog log = new MallStockLog();
        log.setSkuId(skuId);
        // 库存调整没有业务单号，但唯一索引 (biz_type,biz_no,change_type,sku_id) 依赖该列；
        // 留空会让索引对 NULL 失效（MySQL 唯一索引不去重 NULL），因此在写入侧生成审计号。
        log.setBizType("ADJUST");
        log.setBizNo("ADJ-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        log.setChangeType("ADJUST");
        log.setStockDelta(delta);
        log.setLockedDelta(0);
        log.setStockAfter(request.getStock());
        log.setLockedAfter(locked);
        log.setOperatorType("ADMIN");
        log.setOperatorId(operatorId);
        log.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark().trim() : "后台调整库存");
        stockLogMapper.insert(log);
        sku.setStock(request.getStock());
        sku.setLockedStock(locked);
        return toAdminSku(sku, subjectQueryPort.findName(sku.getSupplierSubjectId()));
    }
    private void validateSkuPayload(Long productId, List<MallSkuAdminDTO> items) {
        Set<Long> ids = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (MallSkuAdminDTO item : items) {
            if (item == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 不能为空");
            }
            String code = requireText(item.getSkuCode(), "SKU 编码不能为空");
            if (!codes.add(code.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 编码重复");
            }
            if (item.getId() != null && !ids.add(item.getId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU ID 重复");
            }
            MallSku sameCode = skuMapper.selectBySkuCodeIncludingDeleted(code);
            if (sameCode != null && !sameCode.getId().equals(item.getId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 编码已存在");
            }
            validateSkuNumbers(item);
        }
    }
    private void validateSkuNumbers(MallSkuAdminDTO item) {
        if (!StringUtils.hasText(item.getSpecValue())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 规格值不能为空");
        }
        if (item.getSupplierSubjectId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 供应商不能为空");
        }
        validateSubject(item.getSupplierSubjectId(), "SUPPLIER", "SKU 供应商无效");
        if (negative(item.getSalePrice()) || negative(item.getOriginalPrice())
                || negative(item.getDistributionCommission())
                || negative(item.getCostPrice()) || negative(item.getPlatformCommission())
                || item.getStock() != null && item.getStock() < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 价格和库存不能为负数");
        }
    }
    private void validateOnSale(MallProduct product, List<MallSku> skus) {
        if (product.getAttributionStoreSubjectId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "归属门店不能为空");
        }
        validateSubject(product.getAttributionStoreSubjectId(), "STORE", "归属门店无效");
        if (product.getAttributionChannelSubjectId() != null) {
            validateSubject(product.getAttributionChannelSubjectId(), "CHANNEL", "归属渠道无效");
        }
        if (skus == null || skus.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品上架前至少需要一个 SKU");
        }
        if (splitRuleValidationPort != null
                && !splitRuleValidationPort.isValid(product.getSplitRuleId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分账规则不存在、已停用或当前没有全局规则");
        }
        for (MallSku sku : skus) {
            if (sku.getSupplierSubjectId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 供应商不能为空");
            }
            validateSubject(sku.getSupplierSubjectId(), "SUPPLIER", "SKU 供应商无效");
            if (negative(sku.getSalePrice()) || negative(sku.getOriginalPrice())
                    || negative(sku.getCostPrice()) || negative(sku.getPlatformCommission())
                    || value(sku.getStock()) < 0 || value(sku.getLockedStock()) < 0
                    || value(sku.getLockedStock()) > value(sku.getStock())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 价格或库存状态不合法");
            }
        }
    }
    private void apply(MallProduct product, MallProductSaveRequest request) {
        MallCategory category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商城分类不存在");
        }
        if (request.getAttributionStoreSubjectId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "归属门店不能为空");
        }
        validateSubject(request.getAttributionStoreSubjectId(), "STORE", "归属门店无效");
        if (request.getAttributionChannelSubjectId() != null) {
            validateSubject(request.getAttributionChannelSubjectId(), "CHANNEL", "归属渠道无效");
        }
        product.setCategoryId(request.getCategoryId());
        product.setName(requireText(request.getName(), "商品名称不能为空"));
        product.setSubtitle(trimToNull(request.getSubtitle()));
        product.setSpecName(StringUtils.hasText(request.getSpecName()) ? request.getSpecName().trim() : "规格");
        product.setCoverImage(trimToNull(request.getCoverImage()));
        product.setGalleryImages(writeStringList(request.getGalleryImages()));
        product.setDescription(trimToNull(request.getDescription()));
        product.setAttributionStoreSubjectId(request.getAttributionStoreSubjectId());
        product.setAttributionChannelSubjectId(request.getAttributionChannelSubjectId());
        product.setSplitRuleId(request.getSplitRuleId());
        product.setSort(request.getSort() == null ? 0 : request.getSort());
        String status = StringUtils.hasText(request.getStatus()) ? upper(request.getStatus()) : "DRAFT";
        if (!PRODUCT_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品状态仅支持 DRAFT/ON_SALE/OFF_SALE");
        }
        product.setStatus(status);
    }
    private void applySku(MallSku sku, MallSkuAdminDTO item) {
        sku.setSkuCode(item.getSkuCode().trim());
        sku.setSpecValue(item.getSpecValue().trim());
        sku.setImage(trimToNull(item.getImage()));
        sku.setSalePrice(item.getSalePrice() == null ? 0L : item.getSalePrice());
        sku.setOriginalPrice(item.getOriginalPrice() == null ? 0L : item.getOriginalPrice());
        sku.setCostPrice(item.getCostPrice() == null ? 0L : item.getCostPrice());
        sku.setPlatformCommission(item.getPlatformCommission() == null ? 0L : item.getPlatformCommission());
        sku.setDistributionCommission(item.getDistributionCommission() == null ? 0L : item.getDistributionCommission());
        sku.setSupplierSubjectId(item.getSupplierSubjectId());
        if (item.getStock() != null) {
            int locked = value(sku.getLockedStock());
            if (item.getStock() < locked) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 库存不能低于已锁定库存");
            }
            sku.setStock(item.getStock());
        }
        if (sku.getStock() == null) {
            sku.setStock(0);
        }
        sku.setSort(item.getSort() == null ? 0 : item.getSort());
    }
    private List<MallSku> lockSkus(List<MallSku> skus) {
        List<MallSku> locked = new ArrayList<>();
        for (MallSku sku : skus.stream().sorted(Comparator.comparing(MallSku::getId)).toList()) {
            MallSku fresh = skuMapper.selectByIdForUpdate(sku.getId());
            if (fresh == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "SKU 状态已变更，请刷新后重试");
            }
            locked.add(fresh);
        }
        return locked;
    }
    private Map<Long, List<MallSku>> loadSkus(List<MallProduct> products) {
        List<Long> productIds = products.stream().map(MallProduct::getId).filter(Objects::nonNull).toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<MallSku>()
                        .in(MallSku::getProductId, productIds)
                        .orderByAsc(MallSku::getSort)
                        .orderByAsc(MallSku::getId))
                .stream().collect(Collectors.groupingBy(MallSku::getProductId));
    }
    private Map<Long, String> loadCategoryNames(List<MallProduct> products) {
        List<Long> ids = products.stream().map(MallProduct::getCategoryId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(MallCategory::getId, MallCategory::getName));
    }
    private Map<Long, String> subjectNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        return distinct.isEmpty() ? Map.of() : subjectQueryPort.findNames(distinct);
    }
    private MallProductAdminDTO toAdminDTO(MallProduct product, List<MallSku> skus,
                                           Map<Long, String> categories,
                                           Map<Long, String> stores,
                                           Map<Long, String> channels,
                                           Map<Long, String> suppliers) {
        MallProductAdminDTO dto = new MallProductAdminDTO();
        dto.setId(product.getId());
        dto.setProductCode(product.getProductCode());
        dto.setCategoryId(product.getCategoryId());
        dto.setCategoryName(categories.get(product.getCategoryId()));
        dto.setName(product.getName());
        dto.setSubtitle(product.getSubtitle());
        dto.setSpecName(product.getSpecName());
        dto.setCoverImage(product.getCoverImage());
        dto.setGalleryImages(parseStringList(product.getGalleryImages()));
        dto.setDescription(product.getDescription());
        dto.setAttributionStoreSubjectId(product.getAttributionStoreSubjectId());
        dto.setAttributionStoreName(stores.get(product.getAttributionStoreSubjectId()));
        dto.setAttributionChannelSubjectId(product.getAttributionChannelSubjectId());
        dto.setAttributionChannelName(channels.get(product.getAttributionChannelSubjectId()));
        dto.setSplitRuleId(product.getSplitRuleId());
        dto.setStatus(product.getStatus());
        dto.setSort(product.getSort());
        dto.setSalesCount(product.getSalesCount());
        dto.setCreateTime(product.getCreateTime() == null ? null : product.getCreateTime().format(TIME_FORMAT));
        dto.setUpdateTime(product.getUpdateTime() == null ? null : product.getUpdateTime().format(TIME_FORMAT));
        dto.setSkus(skus.stream().map(sku -> toAdminSku(sku, suppliers.get(sku.getSupplierSubjectId()))).toList());
        dto.setMinPrice(skus.stream().map(MallSku::getSalePrice).filter(Objects::nonNull).min(Long::compareTo).orElse(null));
        dto.setMaxPrice(skus.stream().map(MallSku::getSalePrice).filter(Objects::nonNull).max(Long::compareTo).orElse(null));
        dto.setAvailableStock(skus.stream().mapToInt(MallSku::availableStock).sum());
        boolean hasHistory = stockLogMapper != null && stockLogMapper.existsByProductId(product.getId());
        dto.setDeletable(!hasHistory);
        dto.setDeleteBlockedReason(hasHistory ? "商品已有订单或库存流水，只能下架，不能删除" : null);
        return dto;
    }
    private MallSkuAdminDTO toAdminSku(MallSku sku, String supplierName) {
        MallSkuAdminDTO dto = new MallSkuAdminDTO();
        dto.setId(sku.getId());
        dto.setSkuCode(sku.getSkuCode());
        dto.setSpecValue(sku.getSpecValue());
        dto.setImage(sku.getImage());
        dto.setSalePrice(sku.getSalePrice());
        dto.setOriginalPrice(sku.getOriginalPrice());
        dto.setCostPrice(sku.getCostPrice());
        dto.setPlatformCommission(sku.getPlatformCommission());
        dto.setDistributionCommission(sku.getDistributionCommission());
        dto.setSupplierSubjectId(sku.getSupplierSubjectId());
        dto.setSupplierName(supplierName);
        dto.setStock(sku.getStock());
        dto.setLockedStock(sku.getLockedStock());
        dto.setAvailableStock(sku.availableStock());
        dto.setSort(sku.getSort());
        dto.setVersion(sku.getVersion());
        return dto;
    }
    private MallProduct requireProduct(Long id) {
        MallProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商城商品不存在");
        }
        return product;
    }
    private void validateSubject(Long id, String expectedType, String message) {
        SubjectQueryPort.SubjectView subject = subjectQueryPort.findById(id);
        if (subject == null || !expectedType.equalsIgnoreCase(subject.getSubjectType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
    }
    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
    private String writeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品图片数据格式错误");
        }
    }
    private static boolean negative(Long value) {
        return value != null && value < 0;
    }
    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return value.trim();
    }
}

