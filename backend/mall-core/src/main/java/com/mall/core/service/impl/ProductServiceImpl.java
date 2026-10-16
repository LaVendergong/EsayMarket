package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Category;
import com.mall.core.entity.Product;
import com.mall.core.entity.Sku;
import com.mall.core.mapper.CategoryMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.dto.ProductDTO;
import com.mall.core.model.dto.SkuDTO;
import com.mall.core.model.vo.ProductDetailVO;
import com.mall.core.model.vo.ProductVO;
import com.mall.core.model.vo.SkuVO;
import com.mall.core.service.CategoryService;
import com.mall.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final String SORT_PRICE_ASC = "price_asc";
    private static final String SORT_PRICE_DESC = "price_desc";
    private static final String SORT_SALES_DESC = "sales_desc";
    private static final String SORT_NEW = "new";
    private static final long MAX_PAGE_SIZE = 100L;

    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final CategoryService categoryService;

    @Override
    public PageResult<ProductVO> pageForPortal(long pageNum, long pageSize, Long categoryId, String keyword,
                                               BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        LambdaQueryWrapper<Product> w = Wrappers.<Product>lambdaQuery().eq(Product::getStatus, 1);
        applyCommonFilter(w, categoryId, keyword, minPrice, maxPrice);
        switch (sort == null ? "" : sort) {
            case SORT_PRICE_ASC -> w.orderByAsc(Product::getPrice);
            case SORT_PRICE_DESC -> w.orderByDesc(Product::getPrice);
            case SORT_SALES_DESC -> w.orderByDesc(Product::getSales);
            case SORT_NEW -> w.orderByDesc(Product::getId);
            default -> w.orderByDesc(Product::getSales).orderByDesc(Product::getId);
        }
        return doPage(pageNum, pageSize, w);
    }

    @Override
    public ProductDetailVO detailForPortal(Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (p.getStatus() == null || p.getStatus() == 0) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return buildDetail(p);
    }

    @Override
    public PageResult<ProductVO> pageForAdmin(long pageNum, long pageSize, Long categoryId, String keyword, Integer status) {
        LambdaQueryWrapper<Product> w = Wrappers.<Product>lambdaQuery();
        w.eq(status != null, Product::getStatus, status);
        applyCommonFilter(w, categoryId, keyword, null, null);
        w.orderByDesc(Product::getId);
        return doPage(pageNum, pageSize, w);
    }

    @Override
    public ProductDetailVO detailForAdmin(Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return buildDetail(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductDTO dto) {
        requireCategory(dto.getCategoryId());
        checkDuplicateSkuCode(dto.getSkus());
        Product p = new Product();
        apply(p, dto);
        p.setSales(0);
        if (dto.getStatus() == null) {
            p.setStatus(1);
        }
        productMapper.insert(p);
        for (SkuDTO s : dto.getSkus()) {
            skuMapper.insert(buildSku(p.getId(), s));
        }
        return p.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductDTO dto) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        requireCategory(dto.getCategoryId());
        checkDuplicateSkuCode(dto.getSkus());
        apply(p, dto);
        productMapper.updateById(p);
        syncSkus(id, dto.getSkus());
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        Product upd = new Product();
        upd.setId(id);
        upd.setStatus(normalizeStatus(status));
        productMapper.updateById(upd);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        skuMapper.delete(Wrappers.<Sku>lambdaQuery().eq(Sku::getProductId, id));
        productMapper.deleteById(id);
    }

    // ------------------------------------------------------------------ 内部方法

    private void applyCommonFilter(LambdaQueryWrapper<Product> w, Long categoryId, String keyword,
                                   BigDecimal minPrice, BigDecimal maxPrice) {
        if (categoryId != null) {
            // 选父分类时连同子分类商品一起返回
            w.in(Product::getCategoryId, categoryService.selfAndDescendantIds(categoryId));
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(Product::getName, kw).or().like(Product::getSubTitle, kw));
        }
        if (minPrice != null) {
            w.ge(Product::getPrice, minPrice);
        }
        if (maxPrice != null) {
            w.le(Product::getPrice, maxPrice);
        }
    }

    private PageResult<ProductVO> doPage(long pageNum, long pageSize, LambdaQueryWrapper<Product> w) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Product> page = new Page<>(pn, ps);
        productMapper.selectPage(page, w);
        return PageResult.of(page.getTotal(), pn, ps, toVOList(page.getRecords()));
    }

    /** 批量补齐分类名与库存合计，避免 N+1 */
    private List<ProductVO> toVOList(List<Product> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = records.stream().map(Product::getId).toList();
        // 列表展示「可售库存」= stock - locked_stock（locked 是被待支付订单锁定的量）
        Map<Long, Integer> stockMap = skuMapper.selectList(Wrappers.<Sku>lambdaQuery()
                        .in(Sku::getProductId, productIds))
                .stream()
                .collect(Collectors.groupingBy(Sku::getProductId,
                        Collectors.summingInt(SkuVO::availableOf)));
        Set<Long> categoryIds = records.stream().map(Product::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
        List<ProductVO> list = new ArrayList<>(records.size());
        for (Product p : records) {
            ProductVO vo = ProductVO.from(p);
            vo.setStock(stockMap.getOrDefault(p.getId(), 0));
            vo.setCategoryName(categoryNames.get(p.getCategoryId()));
            list.add(vo);
        }
        return list;
    }

    private ProductDetailVO buildDetail(Product p) {
        List<Sku> skus = skuMapper.selectList(Wrappers.<Sku>lambdaQuery()
                .eq(Sku::getProductId, p.getId())
                .orderByAsc(Sku::getPrice)
                .orderByAsc(Sku::getId));
        Category c = p.getCategoryId() == null ? null : categoryMapper.selectById(p.getCategoryId());
        List<SkuVO> skuVOs = skus.stream().map(SkuVO::from).toList();
        return ProductDetailVO.from(p, c == null ? null : c.getName(), splitImages(p.getImages()), skuVOs);
    }

    /** 更新时按 id 对齐 SKU：有 id 更新、无 id 新增、未出现的逻辑删除 */
    private void syncSkus(Long productId, List<SkuDTO> skus) {
        List<Sku> exists = skuMapper.selectList(Wrappers.<Sku>lambdaQuery().eq(Sku::getProductId, productId));
        Map<Long, Sku> byId = exists.stream().collect(Collectors.toMap(Sku::getId, s -> s));
        Set<Long> keep = new HashSet<>();
        for (SkuDTO d : skus) {
            if (d.getId() == null) {
                skuMapper.insert(buildSku(productId, d));
            } else {
                Sku s = byId.get(d.getId());
                if (s == null) {
                    throw new BusinessException(ResultCode.SKU_NOT_FOUND, "SKU 不属于当前商品: " + d.getId());
                }
                applySku(s, d);
                skuMapper.updateById(s);
                keep.add(s.getId());
            }
        }
        for (Sku s : exists) {
            if (!keep.contains(s.getId())) {
                skuMapper.deleteById(s.getId());
            }
        }
    }

    private void apply(Product p, ProductDTO dto) {
        p.setCategoryId(dto.getCategoryId());
        p.setName(dto.getName().trim());
        p.setSubTitle(dto.getSubTitle());
        p.setMainImage(dto.getMainImage());
        p.setImages(joinImages(dto.getImages()));
        p.setDetail(dto.getDetail());
        p.setPrice(dto.getPrice());
        if (dto.getStatus() != null) {
            p.setStatus(normalizeStatus(dto.getStatus()));
        }
    }

    private Sku buildSku(Long productId, SkuDTO d) {
        Sku s = new Sku();
        s.setProductId(productId);
        applySku(s, d);
        s.setLockedStock(0);
        return s;
    }

    private void applySku(Sku s, SkuDTO d) {
        s.setSkuCode(d.getSkuCode());
        s.setSpec(StringUtils.hasText(d.getSpec()) ? d.getSpec().trim() : "默认");
        s.setImage(d.getImage());
        s.setPrice(d.getPrice());
        s.setStock(d.getStock());
    }

    private void requireCategory(Long categoryId) {
        if (categoryId == null || categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
    }

    private void checkDuplicateSkuCode(List<SkuDTO> skus) {
        Set<String> seen = new HashSet<>();
        for (SkuDTO s : skus) {
            if (StringUtils.hasText(s.getSkuCode()) && !seen.add(s.getSkuCode().trim())) {
                throw new BusinessException(ResultCode.FAILED, "SKU 编码重复: " + s.getSkuCode());
            }
        }
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

    private String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        String joined = images.stream().filter(StringUtils::hasText)
                .map(String::trim).collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private List<String> splitImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }
}
