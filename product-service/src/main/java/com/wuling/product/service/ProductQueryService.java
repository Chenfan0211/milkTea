package com.wuling.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.PageResult;
import com.wuling.product.dto.AdminProductDTO;
import com.wuling.product.dto.CategoryDTO;
import com.wuling.product.dto.MenuDTO;
import com.wuling.product.dto.ProductDetailDTO;
import com.wuling.product.entity.Product;
import com.wuling.product.entity.ProductCategory;
import com.wuling.product.entity.ProductSpec;
import com.wuling.product.entity.ProductStore;
import com.wuling.product.mapper.ProductCategoryMapper;
import com.wuling.product.mapper.ProductMapper;
import com.wuling.product.mapper.ProductSpecMapper;
import com.wuling.product.mapper.ProductStoreMapper;
import com.wuling.product.port.SubjectQueryPort;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProductQueryService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ProductSpecMapper specMapper;
    private final ProductStoreMapper productStoreMapper;
    /** 第 12 期：主体查询改走端口 */
    private final SubjectQueryPort subjectQueryPort;
    private final ObjectMapper objectMapper;

    public ProductQueryService(ProductMapper productMapper,
                               ProductCategoryMapper categoryMapper,
                               ProductSpecMapper specMapper,
                               ProductStoreMapper productStoreMapper,
                               SubjectQueryPort subjectQueryPort,
                               ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.specMapper = specMapper;
        this.productStoreMapper = productStoreMapper;
        this.subjectQueryPort = subjectQueryPort;
        this.objectMapper = objectMapper;
    }

    public List<MenuDTO.MenuTab> getMenu() {
        // 单层分类：仅启用且 type=CATEGORY 的节点参与菜单
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getType, "CATEGORY")
                .eq(ProductCategory::getEnabled, 1)
                .orderByAsc(ProductCategory::getSort)
                .orderByAsc(ProductCategory::getId));
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getOnSale, 1)
                .orderByAsc(Product::getId));
        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .orderByAsc(ProductSpec::getSort)
                .orderByAsc(ProductSpec::getId));

        Map<Long, List<ProductSpec>> specsByProduct = specs.stream()
                .collect(Collectors.groupingBy(ProductSpec::getProductId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Product>> productsByCategory = products.stream()
                .collect(Collectors.groupingBy(Product::getCategoryId, LinkedHashMap::new, Collectors.toList()));

        // 完全单层：1 个 tab -> 1 个 group -> 分类列表
        MenuDTO.MenuTab tabDto = new MenuDTO.MenuTab();
        tabDto.setId("menu");
        tabDto.setLabel("菜单");

        MenuDTO.MenuGroup groupDto = new MenuDTO.MenuGroup();
        groupDto.setId("all");
        groupDto.setLabel("全部");

        List<MenuDTO.MenuCategory> categoryDtos = new ArrayList<>();
        for (ProductCategory category : categories) {
            MenuDTO.MenuCategory categoryDto = new MenuDTO.MenuCategory();
            categoryDto.setId(category.getCode());
            categoryDto.setLabel(category.getName());
            categoryDto.setTag(category.getTag());
            categoryDto.setProducts(productsByCategory.getOrDefault(category.getId(), List.of()).stream()
                    .map(p -> toMenuProduct(p, specsByProduct.getOrDefault(p.getId(), List.of())))
                    .toList());
            categoryDtos.add(categoryDto);
        }
        groupDto.setCategories(categoryDtos);

        List<MenuDTO.MenuGroup> groups = new ArrayList<>();
        groups.add(groupDto);
        tabDto.setGroups(groups);

        List<MenuDTO.MenuTab> tabs = new ArrayList<>();
        tabs.add(tabDto);
        return tabs;
    }
    public ProductDetailDTO getProductDetail(String productId) {
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductId, productId));
        if (product == null) {
            return null;
        }
        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, product.getId())
                .orderByAsc(ProductSpec::getSort)
                .orderByAsc(ProductSpec::getId));
        ProductDetailDTO dto = new ProductDetailDTO();
        dto.setId(product.getProductId());
        dto.setName(product.getName());
        dto.setTags(parseStringList(product.getTags()));
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setStoredValuePrice(product.getStoredValuePrice());
        dto.setImage(product.getImage());
        dto.setGalleryImage(product.getGalleryImage() == null ? product.getImage() : product.getGalleryImage());
        dto.setImageDisclaimer(product.getImageDisclaimer());
        dto.setPromotionText(product.getPromotionText());
        dto.setPriceLabel(product.getPriceLabel());
        dto.setDiscountRate(product.getDiscountRate());
        dto.setSpecTag(product.getSpecTag());
        dto.setBadgeIcon(product.getBadgeIcon());
        dto.setIngredients(product.getIngredients());
        dto.setAllergens(product.getAllergens());
        dto.setCupCapacity(product.getCupCapacity());
        dto.setTips(parseStringList(product.getTips()));
        dto.setSpecGroups(buildSpecGroups(specs));
        return dto;
    }

    public PageResult<AdminProductDTO> pageAdminProducts(long current, long size, String search) {
        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<Product>().orderByAsc(Product::getId);
        if (StringUtils.hasText(search)) {
            query.and(w -> w.like(Product::getProductId, search)
                    .or().like(Product::getName, search)
                    .or().like(Product::getCode, search));
        }
        Page<Product> page = productMapper.selectPage(new Page<>(current, size), query);
        List<AdminProductDTO> records = page.getRecords().stream().map(this::toAdminProduct).toList();
        return PageResult.of(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 按 id 组装单个商品 DTO（写操作后回填用） */
    public AdminProductDTO getAdminProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return null;
        }
        return toAdminProduct(product);
    }

    public List<CategoryDTO> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getType, "CATEGORY")
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId))
                .stream().map(category -> {
                    CategoryDTO dto = new CategoryDTO();
                    dto.setId(category.getId());
                    dto.setParentId(category.getParentId());
                    dto.setCode(category.getCode());
                    dto.setName(category.getName());
                    dto.setType(category.getType());
                    dto.setSort(category.getSort());
                    dto.setTag(category.getTag());
                    dto.setEnabled(category.getEnabled());
                    return dto;
                }).toList();
    }
    private MenuDTO.MenuProduct toMenuProduct(Product product, List<ProductSpec> specs) {
        MenuDTO.MenuProduct dto = new MenuDTO.MenuProduct();
        dto.setId(product.getProductId());
        dto.setName(product.getName());
        dto.setTags(parseStringList(product.getTags()));
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setStoredValuePrice(product.getStoredValuePrice());
        dto.setImage(product.getImage());
        dto.setGalleryImage(product.getGalleryImage() == null ? product.getImage() : product.getGalleryImage());
        dto.setImageDisclaimer(product.getImageDisclaimer());
        dto.setPromotionText(product.getPromotionText());
        dto.setPriceLabel(product.getPriceLabel());
        dto.setDiscountRate(product.getDiscountRate());
        dto.setSpecTag(product.getSpecTag());
        dto.setBadgeIcon(product.getBadgeIcon());
        dto.setIngredients(product.getIngredients());
        dto.setAllergens(product.getAllergens());
        dto.setCupCapacity(product.getCupCapacity());
        dto.setTips(parseStringList(product.getTips()));
        dto.setSpecGroups(buildSpecGroups(specs));
        return dto;
    }

    private List<MenuDTO.SpecGroup> buildSpecGroups(List<ProductSpec> specs) {
        Map<String, List<ProductSpec>> byGroup = specs.stream()
                .collect(Collectors.groupingBy(ProductSpec::getGroupCode, LinkedHashMap::new, Collectors.toList()));
        List<MenuDTO.SpecGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ProductSpec>> entry : byGroup.entrySet()) {
            MenuDTO.SpecGroup group = new MenuDTO.SpecGroup();
            group.setId(entry.getKey());
            group.setLabel(entry.getValue().isEmpty() ? entry.getKey() : entry.getValue().get(0).getGroupLabel());
            List<MenuDTO.SpecOption> options = entry.getValue().stream()
                    .sorted(Comparator.comparing(ProductSpec::getSort, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(spec -> {
                        MenuDTO.SpecOption option = new MenuDTO.SpecOption();
                        option.setId(spec.getOptionCode());
                        option.setLabel(spec.getOptionLabel());
                        option.setSelected(spec.getSelected() != null && spec.getSelected() == 1);
                        option.setPriceDelta(spec.getPriceDelta());
                        option.setIcon(spec.getIcon());
                        return option;
                    }).toList();
            group.setOptions(options);
            groups.add(group);
        }
        return groups;
    }

    private AdminProductDTO toAdminProduct(Product product) {
        AdminProductDTO dto = new AdminProductDTO();
        dto.setId(product.getId());
        dto.setProductId(product.getProductId());
        dto.setCode(product.getCode());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setCostPrice(product.getCostPrice());
        dto.setPlatformCommission(product.getPlatformCommission());
        dto.setStoredValuePrice(product.getStoredValuePrice());
        dto.setGalleryImage(product.getGalleryImage() == null ? product.getImage() : product.getGalleryImage());
        dto.setImageDisclaimer(product.getImageDisclaimer());
        dto.setPromotionText(product.getPromotionText());
        dto.setIngredients(product.getIngredients());
        dto.setAllergens(product.getAllergens());
        dto.setCupCapacity(product.getCupCapacity());
        dto.setTips(parseStringList(product.getTips()));
        dto.setDescription(product.getDescription());
        dto.setOnSale(product.getOnSale() != null && product.getOnSale() == 1 ? "on" : "off");
        dto.setSplitReady(product.getSplitRuleId() != null ? "ready" : "incomplete");
        dto.setCreateTime(product.getCreateTime() == null ? null : product.getCreateTime().format(FMT));

        // 分类：name 供列表展示，id 供编辑回显（前端下拉以 id 为值）
        ProductCategory category = product.getCategoryId() == null ? null : categoryMapper.selectById(product.getCategoryId());
        dto.setCategory(category == null ? "-" : category.getName());
        dto.setCategoryId(product.getCategoryId());
        // 标签：库中为 JSON 文本，统一解析为字符串列表（与菜单接口 toMenuProduct 同口径）
        dto.setTags(parseStringList(product.getTags()));

        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, product.getId()));
        dto.setSpecCount((int) specs.stream().map(ProductSpec::getGroupCode).distinct().count());

        List<String> storeNames = new ArrayList<>();
        List<ProductStore> links = productStoreMapper.selectList(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getProductId, product.getId()));
        for (ProductStore link : links) {
            String storeName = subjectQueryPort.findName(link.getStoreSubjectId());
            if (storeName != null) {
                storeNames.add(storeName);
            }
        }
        dto.setStores(storeNames);
        dto.setStore(storeNames.isEmpty() ? "-" : storeNames.get(0));
        return dto;
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
}

