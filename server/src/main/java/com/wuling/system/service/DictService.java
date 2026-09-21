package com.wuling.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.system.dto.StoreTypeDTO;
import com.wuling.system.entity.SysDictItem;
import com.wuling.system.mapper.SysDictItemMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DictService {

    private final SysDictItemMapper dictItemMapper;

    public DictService(SysDictItemMapper dictItemMapper) {
        this.dictItemMapper = dictItemMapper;
    }

    public List<StoreTypeDTO> listStoreTypes() {
        List<SysDictItem> items = dictItemMapper.selectList(new LambdaQueryWrapper<SysDictItem>()
                .eq(SysDictItem::getDictType, "store_type")
                .eq(SysDictItem::getEnabled, 1)
                .orderByAsc(SysDictItem::getSort));
        return items.stream().map(item -> {
            StoreTypeDTO dto = new StoreTypeDTO();
            dto.setId(item.getId());
            dto.setCode(item.getItemCode());
            dto.setName(item.getItemName());
            dto.setSort(item.getSort());
            dto.setEnabled(item.getEnabled() != null && item.getEnabled() == 1);
            return dto;
        }).toList();
    }
}
