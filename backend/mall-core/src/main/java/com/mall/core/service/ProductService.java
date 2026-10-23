package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.ProductDTO;
import com.mall.core.model.vo.ProductDetailVO;
import com.mall.core.model.vo.ProductVO;

import java.math.BigDecimal;
import java.util.List;

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

    /**
     * 搜索联想：分类名 + 商品名，去重后最多 limit 条。
     *
     * <p>不返回销量/图片：联想列表只用来补全关键词，点一下还是走正常的搜索接口。
     */
    /** 热词：近 7 天被搜次数最多的关键词（给搜索框下拉用） */
    List<String> hotKeywords(int limit);

    List<String> suggest(String keyword, int limit);

    ProductDetailVO detailForAdmin(Long id);

    Long create(ProductDTO dto);

    void update(Long id, ProductDTO dto);

    void updateStatus(Long id, Integer status);

    void delete(Long id);
}
