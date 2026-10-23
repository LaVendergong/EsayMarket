package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Favorite;
import com.mall.core.entity.Product;
import com.mall.core.entity.Sku;
import com.mall.core.mapper.FavoriteMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.vo.FavoriteVO;
import com.mall.core.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private static final long MAX_PAGE_SIZE = 100L;

    private final FavoriteMapper favoriteMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;

    @Override
    public boolean toggle(Long userId, Long productId) {
        if (productMapper.selectById(productId) == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        // 已收藏 -> 取消；未收藏 -> 收藏。两个方向都幂等，重复点不会出错
        if (favoriteMapper.countOne(userId, productId) > 0) {
            favoriteMapper.unfavorite(userId, productId);
            return false;
        }
        favoriteMapper.favorite(userId, productId);
        return true;
    }

    @Override
    public PageResult<FavoriteVO> page(Long userId, long pageNum, long pageSize) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Favorite> page = favoriteMapper.selectPage(new Page<>(pn, ps), Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .orderByDesc(Favorite::getId));
        List<Favorite> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(page.getTotal(), pn, ps, List.of());
        }
        Set<Long> productIds = records.stream().map(Favorite::getProductId).collect(Collectors.toSet());
        Map<Long, Product> products = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        // 库存一次聚合出来，别在每个商品上单独查一遍 SKU
        Map<Long, Integer> stockByProduct = skuMapper.selectList(Wrappers.<Sku>lambdaQuery()
                        .in(Sku::getProductId, productIds))
                .stream()
                .collect(Collectors.groupingBy(Sku::getProductId,
                        Collectors.summingInt(s -> s.getStock() == null ? 0 : s.getStock())));

        List<FavoriteVO> out = new ArrayList<>(records.size());
        for (Favorite f : records) {
            Product p = products.get(f.getProductId());
            FavoriteVO vo = new FavoriteVO();
            vo.setId(f.getId());
            vo.setProductId(f.getProductId());
            vo.setFavoriteTime(f.getCreateTime());
            if (p != null) {
                vo.setProductName(p.getName());
                vo.setSubTitle(p.getSubTitle());
                vo.setMainImage(p.getMainImage());
                vo.setPrice(p.getPrice());
                vo.setStatus(p.getStatus());
                vo.setStock(stockByProduct.getOrDefault(p.getId(), 0));
            }
            out.add(vo);
        }
        return PageResult.of(page.getTotal(), pn, ps, out);
    }

    @Override
    public boolean isFavorite(Long userId, Long productId) {
        return favoriteMapper.countOne(userId, productId) > 0;
    }
}
