package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Category;
import com.mall.core.entity.Product;
import com.mall.core.mapper.CategoryMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.model.dto.CategoryDTO;
import com.mall.core.model.vo.CategoryVO;
import com.mall.core.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;

    @Override
    public List<CategoryVO> tree(Integer status) {
        List<Category> all = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .eq(status != null, Category::getStatus, status)
                .orderByAsc(Category::getSort)
                .orderByAsc(Category::getId));
        Map<Long, CategoryVO> map = new LinkedHashMap<>();
        for (Category c : all) {
            map.put(c.getId(), CategoryVO.from(c));
        }
        List<CategoryVO> roots = new ArrayList<>();
        for (CategoryVO vo : map.values()) {
            CategoryVO parent = vo.getParentId() == null ? null : map.get(vo.getParentId());
            // 父节点不存在（顶级或已被过滤掉）时按根节点处理
            if (parent == null || parent == vo) {
                roots.add(vo);
            } else {
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    @Override
    public List<Long> selfAndDescendantIds(Long id) {
        List<Category> all = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .select(Category::getId, Category::getParentId));
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(id);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Category c : all) {
                if (ids.contains(c.getParentId()) && ids.add(c.getId())) {
                    changed = true;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    @Override
    public Long create(CategoryDTO dto) {
        Long parentId = normalizeParent(dto.getParentId());
        requireParent(parentId);
        checkNameUnique(parentId, dto.getName(), null);
        Category c = new Category();
        c.setParentId(parentId);
        c.setName(dto.getName().trim());
        c.setSort(dto.getSort() == null ? 0 : dto.getSort());
        c.setIcon(dto.getIcon());
        c.setStatus(normalizeStatus(dto.getStatus()));
        categoryMapper.insert(c);
        return c.getId();
    }

    @Override
    public void update(Long id, CategoryDTO dto) {
        Category c = categoryMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        Long parentId = normalizeParent(dto.getParentId());
        if (parentId.equals(id)) {
            throw new BusinessException(ResultCode.FAILED, "父分类不能是自己");
        }
        requireParent(parentId);
        if (isDescendantOf(id, parentId)) {
            throw new BusinessException(ResultCode.FAILED, "父分类不能是自己的子分类");
        }
        checkNameUnique(parentId, dto.getName(), id);
        c.setParentId(parentId);
        c.setName(dto.getName().trim());
        if (dto.getSort() != null) {
            c.setSort(dto.getSort());
        }
        c.setIcon(dto.getIcon());
        if (dto.getStatus() != null) {
            c.setStatus(normalizeStatus(dto.getStatus()));
        }
        categoryMapper.updateById(c);
    }

    @Override
    public void delete(Long id) {
        Category c = categoryMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        Long children = categoryMapper.selectCount(Wrappers.<Category>lambdaQuery().eq(Category::getParentId, id));
        Long products = productMapper.selectCount(Wrappers.<Product>lambdaQuery().eq(Product::getCategoryId, id));
        if ((children != null && children > 0) || (products != null && products > 0)) {
            throw new BusinessException(ResultCode.CATEGORY_HAS_DATA);
        }
        categoryMapper.deleteById(id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Category c = categoryMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        Category upd = new Category();
        upd.setId(id);
        upd.setStatus(normalizeStatus(status));
        categoryMapper.updateById(upd);
    }

    /** 判断 candidateId 是否位于 ancestorId 的子树中（含自身） */
    private boolean isDescendantOf(Long ancestorId, Long candidateId) {
        Set<Long> visited = new HashSet<>();
        Long cur = candidateId;
        while (cur != null && cur != 0L && visited.add(cur)) {
            if (cur.equals(ancestorId)) {
                return true;
            }
            Category p = categoryMapper.selectById(cur);
            if (p == null) {
                return false;
            }
            cur = p.getParentId();
        }
        return false;
    }

    private void requireParent(Long parentId) {
        if (parentId != 0L && categoryMapper.selectById(parentId) == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND, "父分类不存在");
        }
    }

    private void checkNameUnique(Long parentId, String name, Long excludeId) {
        Long cnt = categoryMapper.selectCount(Wrappers.<Category>lambdaQuery()
                .eq(Category::getParentId, parentId)
                .eq(Category::getName, name == null ? null : name.trim())
                .ne(excludeId != null, Category::getId, excludeId));
        if (cnt != null && cnt > 0) {
            throw new BusinessException(ResultCode.CATEGORY_NAME_EXISTS);
        }
    }

    private Long normalizeParent(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private Integer normalizeStatus(Integer status) {
        if (status == null) {
            return 1;
        }
        if (status != 0 && status != 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "状态只能是 0 或 1");
        }
        return status;
    }
}
