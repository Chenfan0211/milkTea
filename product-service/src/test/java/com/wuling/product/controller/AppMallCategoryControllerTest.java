package com.wuling.product.controller;

import com.wuling.product.dto.MallCategoryDTO;
import com.wuling.product.service.MallCategoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppMallCategoryControllerTest {

    @Test
    void appCategoryResponseCarriesIconImage() {
        MallCategoryService categoryService = mock(MallCategoryService.class);
        MallCategoryDTO category = new MallCategoryDTO();
        category.setId(1L);
        category.setCode("tea-goods");
        category.setName("茶器好物");
        category.setIconImage("/api/v1/files/public/0123456789abcdef0123456789abcdef.png");
        category.setStatus("ENABLED");
        when(categoryService.listAppCategories()).thenReturn(List.of(category));

        AppMallController controller = new AppMallController(categoryService, null);

        assertEquals(category.getIconImage(), controller.categories().getData().get(0).getIconImage());
    }
}
