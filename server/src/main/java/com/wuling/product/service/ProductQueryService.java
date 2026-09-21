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
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductQueryService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ProductSpecMapper specMapper;
    private final ProductStoreMapper productStoreMapper;
    private final BizSubjectMapper bizSubjectMapper;
    private final ObjectMapper objectMapper;

    public ProductQueryService(ProductMapper productMapper,
                               ProductCategoryMapper categoryMapper,
                               ProductSpecMapper specMapper,
                               ProductStoreMapper productStoreMapper,
                               BizSubjectMapper bizSubjectMapper,
                               ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.specMapper = specMapper;
        this.productStoreMapper = productStoreMapper;
        this.bizSubjectMapper = bizSubjectMapper;
        this.objectMapper = objectMapper;
    }

    public List<MenuDTO.MenuTab> getMenu() {
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
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

        Map<Long, ProductCategory> categoryById = categories.stream()
                .collect(Collectors.toMap(ProductCategory::getId, Function.identity()));

        List<MenuDTO.MenuTab> tabs = new ArrayList<>();
        for (ProductCategory tab : categories.stream().filter(c -> "TAB".equals(c.getType())).toList()) {
            MenuDTO.MenuTab tabDto = new MenuDTO.MenuTab();
            tabDto.setId(tab.getCode());
            tabDto.setLabel(tab.getName());
            List<MenuDTO.MenuGroup> groups = new ArrayList<>();
            for (ProductCategory group : categories.stream()
                    .filter(c -> "GROUP".equals(c.getType()) && tab.getId().equals(c.getParentId())).toList()) {
                MenuDTO.MenuGroup groupDto = new MenuDTO.MenuGroup();
                groupDto.setId(group.getCode());
                groupDto.setLabel(group.getName());
                List<MenuDTO.MenuCategory> categoryDtos = new ArrayList<>();
                for (ProductCategory category : categories.stream()
                        .filter(c -> "CATEGORY".equals(c.getType()) && group.getId().equals(c.getParentId())).toList()) {
                    MenuDTO.MenuCategory categoryDto = new MenuDTO.MenuCategory();
                    categoryDto.setId(category.getCode());
                    categoryDto.setLabel(category.getName());
                    List<Product> categoryProducts = productsByCategory.getOrDefault(category.getId(), List.of());
                    categoryDto.setProducts(categoryProducts.stream()
                            .map(p -> toMenuProduct(p, specsByProduct.getOrDefault(p.getId(), List.of())))
                            .toList());
                    categoryDtos.add(categoryDto);
                }
                groupDto.setCategories(categoryDtos);
                groups.add(groupDto);
            }
            tabDto.setGroups(groups);
            tabs.add(tabDto);
        }
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

    public List<CategoryDTO> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
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
        dto.setDescription(product.getDescription());
        dto.setOnSale(product.getOnSale() != null && product.getOnSale() == 1 ? "on" : "off");
        dto.setSplitReady(product.getSplitRuleId() != null ? "ready" : "incomplete");
        dto.setCreateTime(product.getCreateTime() == null ? null : product.getCreateTime().format(FMT));

        ProductCategory category = product.getCategoryId() == null ? null : categoryMapper.selectById(product.getCategoryId());
        dto.setCategory(category == null ? "-" : category.getName());

        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, product.getId()));
        dto.setSpecCount((int) specs.stream().map(ProductSpec::getGroupCode).distinct().count());

        List<String> storeNames = new ArrayList<>();
        List<ProductStore> links = productStoreMapper.selectList(new LambdaQueryWrapper<ProductStore>()
                .eq(ProductStore::getProductId, product.getId()));
        for (ProductStore link : links) {
            BizSubject store = bizSubjectMapper.selectById(link.getStoreSubjectId());
            if (store != null) {
                storeNames.add(store.getName());
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
