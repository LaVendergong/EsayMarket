package com.mall.core.model.vo;

import com.mall.core.entity.Category;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryVO {

    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private String icon;
    private Integer status;
    private List<CategoryVO> children = new ArrayList<>();

    public static CategoryVO from(Category c) {
        CategoryVO vo = new CategoryVO();
        vo.setId(c.getId());
        vo.setParentId(c.getParentId());
        vo.setName(c.getName());
        vo.setSort(c.getSort());
        vo.setIcon(c.getIcon());
        vo.setStatus(c.getStatus());
        return vo;
    }
}
