package com.mall.core.service;

import com.mall.core.model.dto.CategoryDTO;
import com.mall.core.model.vo.CategoryVO;

import java.util.List;

public interface CategoryService {

    /** 分类树；status 为 null 表示不过滤状态（后台用） */
    List<CategoryVO> tree(Integer status);

    /** 自身 + 所有后代分类 ID（商品按父分类筛选时用） */
    List<Long> selfAndDescendantIds(Long id);

    Long create(CategoryDTO dto);

    void update(Long id, CategoryDTO dto);

    void delete(Long id);

    void updateStatus(Long id, Integer status);
}
