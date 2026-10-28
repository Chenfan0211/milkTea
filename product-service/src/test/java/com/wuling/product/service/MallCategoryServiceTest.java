package com.wuling.product.service;

import com.wuling.common.exception.BusinessException;
import com.wuling.product.dto.MallCategoryDTO;
import com.wuling.product.dto.MallCategorySaveRequest;
import com.wuling.product.entity.MallCategory;
import com.wuling.product.mapper.MallCategoryMapper;
import com.wuling.product.mapper.MallProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallCategoryServiceTest {

    private MallCategoryMapper categoryMapper;
    private MallCategoryService service;

    @BeforeEach
    void setUp() {
        categoryMapper = mock(MallCategoryMapper.class);
        service = new MallCategoryService(categoryMapper, mock(MallProductMapper.class));
    }

    @Test
    @DisplayName("创建与编辑保存文件服务分类图 URL，并回传 iconImage")
    void createAndUpdatePersistCategoryIcon() {
        MallCategorySaveRequest createRequest = request("tea-goods", "茶器好物");
        createRequest.setIconImage("/api/v1/files/public/0123456789abcdef0123456789abcdef.png");
        service.create(createRequest);

        ArgumentCaptor<MallCategory> createCaptor = ArgumentCaptor.forClass(MallCategory.class);
        verify(categoryMapper).insert(createCaptor.capture());
        assertEquals(createRequest.getIconImage(), createCaptor.getValue().getIconImage());

        MallCategory existing = category(1L);
        when(categoryMapper.selectById(1L)).thenReturn(existing);
        when(categoryMapper.selectByCodeIncludingDeleted("tea-goods")).thenReturn(existing);
        MallCategorySaveRequest updateRequest = request("tea-goods", "茶具");
        updateRequest.setIconImage("/api/v1/files/public/fedcba9876543210fedcba9876543210.jpg");
        MallCategoryDTO dto = service.update(1L, updateRequest);

        assertEquals(updateRequest.getIconImage(), existing.getIconImage());
        assertEquals(updateRequest.getIconImage(), dto.getIconImage());
        verify(categoryMapper).updateById(existing);
    }

    @Test
    @DisplayName("空白分类图统一保存为 null，便于运营移除图片")
    void blankCategoryIconIsStoredAsNull() {
        MallCategorySaveRequest request = request("tea-goods", "茶器好物");
        request.setIconImage("   ");
        service.create(request);

        ArgumentCaptor<MallCategory> captor = ArgumentCaptor.forClass(MallCategory.class);
        verify(categoryMapper).insert(captor.capture());
        assertNull(captor.getValue().getIconImage());
    }

    @Test
    @DisplayName("历史空白分类图在公开响应中归一为 null")
    void blankStoredIconIsExposedAsNull() {
        MallCategory category = category(1L);
        category.setIconImage("   ");
        when(categoryMapper.selectList(any())).thenReturn(java.util.List.of(category));

        assertNull(service.listAppCategories().get(0).getIconImage());
    }

    @Test
    @DisplayName("分类图只接受文件服务生成的本地公开 URL")
    void rejectsNonFileServiceIconUrl() {
        for (String value : new String[]{
                "https://example.com/icon.png",
                "/api/v1/files/public/../../secret.png",
                "/api/v1/files/public/0123456789abcdef0123456789abcdef.webp",
                "/api/v1/files/images/0123456789abcdef0123456789abcdef.png"
        }) {
            MallCategorySaveRequest request = request("tea-goods", "茶器好物");
            request.setIconImage(value);
            BusinessException error = assertThrows(BusinessException.class, () -> service.create(request), value);
            assertEquals("分类图必须是文件服务公开图片地址", error.getMessage());
            verify(categoryMapper, never()).insert(any(MallCategory.class));
        }
    }

    private static MallCategorySaveRequest request(String code, String name) {
        MallCategorySaveRequest request = new MallCategorySaveRequest();
        request.setCode(code);
        request.setName(name);
        request.setSort(0);
        request.setStatus("ENABLED");
        return request;
    }

    private static MallCategory category(Long id) {
        MallCategory category = new MallCategory();
        category.setId(id);
        category.setCode("tea-goods");
        category.setName("茶器好物");
        category.setSort(0);
        category.setStatus("ENABLED");
        return category;
    }
}
