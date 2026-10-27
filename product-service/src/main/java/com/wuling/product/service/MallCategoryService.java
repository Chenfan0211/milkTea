package com.wuling.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MallCategoryDTO;
import com.wuling.product.dto.MallCategorySaveRequest;
import com.wuling.product.entity.MallCategory;
import com.wuling.product.entity.MallProduct;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class MallCategoryService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MallCategoryMapper categoryMapper;
    private final MallProductMapper productMapper;

    public MallCategoryService(MallCategoryMapper categoryMapper, MallProductMapper productMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
    }

    public List<MallCategoryDTO> listAppCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<MallCategory>()
                        .eq(MallCategory::getStatus, "ENABLED")
                        .orderByAsc(MallCategory::getSort)
                        .orderByAsc(MallCategory::getId))
                .stream().map(this::toDTO).toList();
    }

    public List<MallCategoryDTO> listAdminCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<MallCategory>()
                        .orderByAsc(MallCategory::getSort)
                        .orderByAsc(MallCategory::getId))
                .stream().map(this::toDTO).toList();
    }

    public MallCategoryDTO getAdminCategory(Long id) {
        return toDTO(requireCategory(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public MallCategoryDTO create(MallCategorySaveRequest request) {
        String code = requireText(request.getCode(), "分类编码不能为空");
        MallCategory existing = categoryMapper.selectByCodeIncludingDeleted(code);
        if (existing != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类编码已存在");
        }
        MallCategory category = new MallCategory();
        category.setCode(code);
        apply(category, request);
        categoryMapper.insert(category);
        return toDTO(category);
    }

    @Transactional(rollbackFor = Exception.class)
    public MallCategoryDTO update(Long id, MallCategorySaveRequest request) {
        MallCategory category = requireCategory(id);
        String code = requireText(request.getCode(), "分类编码不能为空");
        MallCategory existing = categoryMapper.selectByCodeIncludingDeleted(code);
        if (existing != null && !existing.getId().equals(id)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类编码已存在");
        }
        category.setCode(code);
        apply(category, request);
        categoryMapper.updateById(category);
        return toDTO(category);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireCategory(id);
        Long count = productMapper.selectCount(new LambdaQueryWrapper<MallProduct>()
                .eq(MallProduct::getCategoryId, id));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类下仍有商品，不能删除");
        }
        categoryMapper.deleteById(id);
    }

    private void apply(MallCategory category, MallCategorySaveRequest request) {
        category.setName(requireText(request.getName(), "分类名称不能为空"));
        category.setSort(request.getSort() == null ? 0 : request.getSort());
        String status = StringUtils.hasText(request.getStatus()) ? request.getStatus().trim() : "ENABLED";
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类状态仅支持 ENABLED/DISABLED");
        }
        category.setStatus(status);
    }

    private MallCategory requireCategory(Long id) {
        MallCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商城分类不存在");
        }
        return category;
    }

    private MallCategoryDTO toDTO(MallCategory category) {
        MallCategoryDTO dto = new MallCategoryDTO();
        dto.setId(category.getId());
        dto.setCode(category.getCode());
        dto.setName(category.getName());
        dto.setSort(category.getSort());
        dto.setStatus(category.getStatus());
        dto.setCreateTime(category.getCreateTime() == null ? null : category.getCreateTime().format(TIME_FORMAT));
        dto.setUpdateTime(category.getUpdateTime() == null ? null : category.getUpdateTime().format(TIME_FORMAT));
        return dto;
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return value.trim();
    }
}
