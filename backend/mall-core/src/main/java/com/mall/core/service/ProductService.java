package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.ProductDTO;
import com.mall.core.model.vo.ProductDetailVO;
import com.mall.core.model.vo.ProductVO;

import java.math.BigDecimal;

public interface ProductService {

    /**
     * 前台商品分页（只含上架商品）。
     *
     * @param sort default(综合) / price_asc / price_desc / sales_desc / new
     */
    PageResult<ProductVO> pageForPortal(long pageNum, long pageSize, Long categoryId, String keyword,
                                        BigDecimal minPrice, BigDecimal maxPrice, String sort);

    ProductDetailVO detailForPortal(Long id);

    /** 后台商品分页（含下架） */
    PageResult<ProductVO> pageForAdmin(long pageNum, long pageSize, Long categoryId, String keyword, Integer status);

    ProductDetailVO detailForAdmin(Long id);

    Long create(ProductDTO dto);

    void update(Long id, ProductDTO dto);

    void updateStatus(Long id, Integer status);

    void delete(Long id);
}
