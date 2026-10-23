package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Category;
import com.mall.core.entity.FreightTemplate;
import com.mall.core.entity.Product;
import com.mall.core.entity.Sku;
import com.mall.core.enums.DeliveryType;
import com.mall.core.enums.FreightType;
import com.mall.core.enums.ProductType;
import com.mall.core.mapper.CategoryMapper;
import com.mall.core.mapper.FreightTemplateMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SearchLogMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.dto.ProductDTO;
import com.mall.core.model.dto.SkuDTO;
import com.mall.core.model.vo.ProductDetailVO;
import com.mall.core.model.vo.ProductVO;
import com.mall.core.model.vo.SkuVO;
import com.mall.core.service.CategoryService;
import com.mall.core.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final String SORT_PRICE_ASC = "price_asc";
    private static final String SORT_PRICE_DESC = "price_desc";
    private static final String SORT_SALES_DESC = "sales_desc";
    private static final String SORT_NEW = "new";
    /** 相关度：只在有关键词时才有意义 */
    private static final String SORT_RELEVANCE = "relevance";
    private static final long MAX_PAGE_SIZE = 100L;

    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final CategoryService categoryService;
    private final FreightTemplateMapper freightTemplateMapper;
    private final SearchLogMapper searchLogMapper;

    @Override
    public PageResult<ProductVO> pageForPortal(long pageNum, long pageSize, Long categoryId, String keyword,
                                               BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        LambdaQueryWrapper<Product> w = Wrappers.<Product>lambdaQuery().eq(Product::getStatus, 1);
        if (kw != null) {
            // 四路都搜：商品名、副标题、详情、分类名（分类名走子查询，避免为了搜分类去 JOIN 分页）
            String categoryLike = "SELECT id FROM t_category WHERE deleted = 0 AND name LIKE " + likeLiteral(kw);
            w.and(q -> q.like(Product::getName, kw)
                    .or().like(Product::getSubTitle, kw)
                    .or().like(Product::getDetail, kw)
                    .or().inSql(Product::getCategoryId, categoryLike));
            // 记热词：只记关键词，失败不能影响搜索本身
            try {
                searchLogMapper.insert(kw);
            } catch (Exception e) {
                log.warn("[search] record keyword failed: {}", kw, e);
            }
        }
        // keyword 已在上面处理，这里只传分类/价格
        applyCommonFilter(w, categoryId, null, minPrice, maxPrice);

        String s = sort == null ? "" : sort;
        if (kw != null && (s.isEmpty() || SORT_RELEVANCE.equals(s))) {
            // 相关度排序只能用 SQL 表达式，MP 的 orderBy 表达不了；关键词已转义后再拼
            w.last("ORDER BY " + relevanceOrder(kw) + ", sales DESC, id DESC");
        } else {
            switch (s) {
                case SORT_PRICE_ASC -> w.orderByAsc(Product::getPrice);
                case SORT_PRICE_DESC -> w.orderByDesc(Product::getPrice);
                case SORT_SALES_DESC -> w.orderByDesc(Product::getSales);
                case SORT_NEW -> w.orderByDesc(Product::getId);
                // 没有关键词却要"最相关"：等价于综合排序，别让用户看到随机顺序
                default -> w.orderByDesc(Product::getSales).orderByDesc(Product::getId);
            }
        }
        return doPage(pageNum, pageSize, w);
    }

    /**
     * 相关度：命中位置越"重要"分越高。
     *
     * <p>为什么不只用 LIKE 过滤 + 销量排序：搜「手机」时，标题里就叫「手机」的商品
     * 应该排在"详情里提了一句手机"的商品前面，否则搜索结果看起来完全不相关。
     */
    private String relevanceOrder(String kw) {
        String eq = sqlLiteral(kw);
        String prefix = prefixLiteral(kw);
        String contains = likeLiteral(kw);
        return "CASE "
                + "WHEN name = " + eq + " THEN 100 "
                + "WHEN name LIKE " + prefix + " THEN 80 "
                + "WHEN name LIKE " + contains + " THEN 60 "
                + "WHEN sub_title LIKE " + contains + " THEN 40 "
                + "WHEN category_id IN (SELECT id FROM t_category WHERE deleted = 0 AND name LIKE "
                + contains + ") THEN 30 "
                + "ELSE 10 END DESC";
    }

    /**
     * 拼进 SQL 的字符串字面量：单引号、反斜杠要转义。
     * （相关度 ORDER BY 没法用预编译参数，只能自己做这件事；别直接拼原始输入。）
     */
    private static String sqlLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * LIKE 模式的字面量：**先**转义用户输入里的通配符，**再**加两侧 {@code %}。
     *
     * <p>顺序不能反：先把 '%kw%' 拼好再整体转义，会把我们自己加的通配符也转义成字面量，
     * 于是 `LIKE '\%手机\%'` 永远匹配不到任何东西（踩过一次）。
     */
    private static String likeLiteral(String raw) {
        return "'%" + likeEscape(raw) + "%'";
    }

    /** 前缀 LIKE：'kw%' */
    private static String prefixLiteral(String raw) {
        return "'" + likeEscape(raw) + "%'";
    }

    private static String likeEscape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @Override
    public List<String> hotKeywords(int limit) {
        int n = Math.min(Math.max(limit, 1), 20);
        return searchLogMapper.hotKeywords(LocalDateTime.now().minusDays(7), n);
    }

    @Override
    public List<String> suggest(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        int max = Math.min(Math.max(limit, 1), 20);
        String kw = keyword.trim();
        String prefix = kw + "%";
        // 分类最多占 3 条：用户更常搜的是具体商品，分类多了会把商品挤掉
        List<String> cats = productMapper.suggestCategories(prefix, Math.min(max, 3));
        List<String> items = productMapper.suggestProducts(prefix, max);
        if (items.isEmpty()) {
            // 前缀没命中时退一步用包含匹配，否则输入"手机壳"这种词什么都提示不出来
            items = productMapper.suggestProducts("%" + kw + "%", max);
        }
        Set<String> out = new java.util.LinkedHashSet<>(cats);
        out.addAll(items);
        return out.stream().limit(max).toList();
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
        ProductDetailVO vo = ProductDetailVO.from(p, c == null ? null : c.getName(), splitImages(p.getImages()), skuVOs);
        vo.setFreightText(freightTextOf(p));
        return vo;
    }

    /**
     * 运费文案由后端拼：前台只负责显示，不必懂「固定运费 / 满额包邮 / 模板停用算包邮」这些规则。
     * 规则必须和 PricingServiceImpl 一致，否则会出现「详情页说包邮、结算页却收 8 元」。
     */
    private String freightTextOf(Product p) {
        if (ProductType.of(p.getProductType()) == ProductType.VIRTUAL) {
            return "虚拟商品，无需物流";
        }
        if (p.getFreightTemplateId() == null) {
            return "包邮";
        }
        FreightTemplate t = freightTemplateMapper.selectById(p.getFreightTemplateId());
        // 模板被删/停用：计价时按包邮处理，文案也必须说包邮
        if (t == null || Objects.equals(t.getStatus(), 0)) {
            return "包邮";
        }
        BigDecimal fee = t.getBaseFee() == null ? BigDecimal.ZERO : t.getBaseFee();
        FreightType type = FreightType.of(t.getType());
        if (type == FreightType.FREE_OVER && t.getFreeThreshold() != null) {
            String threshold = t.getFreeThreshold().stripTrailingZeros().toPlainString();
            return fee.compareTo(BigDecimal.ZERO) > 0
                    ? "满 ¥" + threshold + " 包邮，未满运费 ¥" + fee.setScale(2, RoundingMode.HALF_UP)
                    : "满 ¥" + threshold + " 包邮";
        }
        return fee.compareTo(BigDecimal.ZERO) > 0
                ? "运费 ¥" + fee.setScale(2, RoundingMode.HALF_UP)
                : "包邮";
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

        ProductType type = ProductType.of(dto.getProductType());
        p.setProductType(type.getCode());
        if (type == ProductType.VIRTUAL) {
            DeliveryType delivery = DeliveryType.of(dto.getDeliveryType());
            if (delivery == null) {
                throw new BusinessException(ResultCode.VIRTUAL_GOODS_CONFIG_ERROR, "请选择发放方式（卡密/外链）");
            }
            if (delivery == DeliveryType.LINK && !StringUtils.hasText(dto.getDeliveryUrl())) {
                throw new BusinessException(ResultCode.VIRTUAL_GOODS_CONFIG_ERROR, "外链类商品必须填写下载地址");
            }
            p.setDeliveryType(delivery.getCode());
            p.setDeliveryUrl(delivery == DeliveryType.LINK ? dto.getDeliveryUrl().trim() : null);
            // 虚拟商品没有物流：运费模板一律置空，结算页不该出现运费
            p.setFreightTemplateId(null);
        } else {
            p.setDeliveryType(null);
            p.setDeliveryUrl(null);
            // 绑了模板就必须真的存在，否则下单时计价的运费会直接抛异常
            if (dto.getFreightTemplateId() != null
                    && freightTemplateMapper.selectById(dto.getFreightTemplateId()) == null) {
                throw new BusinessException(ResultCode.FREIGHT_TEMPLATE_NOT_FOUND);
            }
            p.setFreightTemplateId(dto.getFreightTemplateId());
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
