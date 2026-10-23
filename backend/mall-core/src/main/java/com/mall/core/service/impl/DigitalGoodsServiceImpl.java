package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.CardKey;
import com.mall.core.entity.DigitalGrant;
import com.mall.core.entity.Order;
import com.mall.core.entity.OrderItem;
import com.mall.core.entity.Product;
import com.mall.core.entity.Sku;
import com.mall.core.enums.AfterSaleType;
import com.mall.core.enums.DeliveryType;
import com.mall.core.enums.ProductType;
import com.mall.core.mapper.CardKeyMapper;
import com.mall.core.mapper.DigitalGrantMapper;
import com.mall.core.mapper.OrderItemMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.vo.DigitalGrantVO;
import com.mall.core.model.vo.GrantContentVO;
import com.mall.core.service.DigitalGoodsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalGoodsServiceImpl implements DigitalGoodsService {

    /** 凭证状态：1 已发放 2 已作废 9 发放失败 */
    private static final int GRANTED = 1;
    private static final int REVOKED = 2;
    private static final int FAILED = 9;

    private final DigitalGrantMapper digitalGrantMapper;
    private final CardKeyMapper cardKeyMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final OrderItemMapper orderItemMapper;

    @Override
    public int grant(Order order, List<OrderItem> items) {
        int granted = 0;
        for (OrderItem item : items) {
            Product p = productMapper.selectById(item.getProductId());
            if (p == null || ProductType.of(p.getProductType()) != ProductType.VIRTUAL) {
                continue;
            }
            DeliveryType type = DeliveryType.of(p.getDeliveryType());
            if (type == null) {
                saveFailed(order, item, null);
                continue;
            }
            if (type == DeliveryType.LINK) {
                if (!StringUtils.hasText(p.getDeliveryUrl())) {
                    saveFailed(order, item, type.getCode());
                    continue;
                }
                // 外链与数量无关：买 3 份也是同一个链接，发一条就够
                saveGrant(order, item, type.getCode(), p.getDeliveryUrl());
                granted++;
                continue;
            }
            // 卡密：一张卡一条凭证
            int qty = item.getQuantity() == null ? 1 : item.getQuantity();
            for (int i = 0; i < qty; i++) {
                Long keyId = cardKeyMapper.lockOne(item.getSkuId());
                if (keyId == null) {
                    // 池子空了但库存还在（导入时没同步 stock），只能人工补
                    log.error("[digital] card key exhausted: skuId={} orderNo={} itemId={}",
                            item.getSkuId(), order.getOrderNo(), item.getId());
                    saveFailed(order, item, type.getCode());
                    break;
                }
                CardKey key = cardKeyMapper.selectById(keyId);
                cardKeyMapper.markSold(keyId, order.getId(), item.getId());
                saveGrant(order, item, type.getCode(), key.getCardNo() + "|" + key.getCardSecret());
                granted++;
            }
        }
        return granted;
    }

    private void saveGrant(Order order, OrderItem item, int deliveryType, String content) {
        DigitalGrant g = new DigitalGrant();
        g.setOrderId(order.getId());
        g.setOrderItemId(item.getId());
        g.setUserId(order.getUserId());
        g.setProductId(item.getProductId());
        g.setSkuId(item.getSkuId());
        g.setDeliveryType(deliveryType);
        g.setContent(content);
        g.setStatus(GRANTED);
        g.setViewCount(0);
        g.setGrantTime(LocalDateTime.now());
        digitalGrantMapper.insert(g);
    }

    private void saveFailed(Order order, OrderItem item, Integer deliveryType) {
        log.error("[digital] grant failed: orderNo={} itemId={} skuId={} deliveryType={}",
                order.getOrderNo(), item.getId(), item.getSkuId(), deliveryType);
        DigitalGrant g = new DigitalGrant();
        g.setOrderId(order.getId());
        g.setOrderItemId(item.getId());
        g.setUserId(order.getUserId());
        g.setProductId(item.getProductId());
        g.setSkuId(item.getSkuId());
        g.setDeliveryType(deliveryType == null ? 1 : deliveryType);
        g.setContent("");
        g.setStatus(FAILED);
        g.setViewCount(0);
        g.setGrantTime(LocalDateTime.now());
        digitalGrantMapper.insert(g);
    }

    @Override
    public List<DigitalGrantVO> byOrder(Long userId, Long orderId) {
        List<DigitalGrant> list = digitalGrantMapper.selectList(Wrappers.<DigitalGrant>lambdaQuery()
                .eq(DigitalGrant::getOrderId, orderId)
                .eq(DigitalGrant::getUserId, userId)
                .orderByAsc(DigitalGrant::getId));
        return toVO(list);
    }

    @Override
    public List<DigitalGrantVO> mine(Long userId) {
        List<DigitalGrant> list = digitalGrantMapper.selectList(Wrappers.<DigitalGrant>lambdaQuery()
                .eq(DigitalGrant::getUserId, userId)
                .orderByDesc(DigitalGrant::getId));
        return toVO(list);
    }

    /** 商品名/规格/图片取订单项快照：商品后来改名/删图都不该影响历史凭证 */
    private List<DigitalGrantVO> toVO(List<DigitalGrant> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = list.stream().map(DigitalGrant::getOrderItemId).collect(Collectors.toSet());
        Map<Long, OrderItem> items = orderItemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(OrderItem::getId, i -> i, (a, b) -> a));
        List<DigitalGrantVO> out = new ArrayList<>(list.size());
        for (DigitalGrant g : list) {
            DigitalGrantVO vo = DigitalGrantVO.of(g.getId(), g.getOrderId(), g.getOrderItemId(),
                    g.getProductId(), g.getSkuId(), g.getDeliveryType(), g.getStatus(),
                    g.getViewCount(), g.getGrantTime());
            OrderItem item = items.get(g.getOrderItemId());
            if (item != null) {
                vo.setProductName(item.getProductName());
                vo.setSpec(item.getSpec());
                vo.setProductImage(item.getProductImage());
            }
            out.add(vo);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GrantContentVO reveal(Long userId, Long grantId) {
        DigitalGrant g = digitalGrantMapper.selectById(grantId);
        if (g == null || !Objects.equals(g.getUserId(), userId)) {
            // 不区分「不存在」和「不是你的」：否则可以用来枚举别人的凭证 id
            throw new BusinessException(ResultCode.DIGITAL_GRANT_NOT_FOUND);
        }
        if (!Objects.equals(g.getStatus(), GRANTED)) {
            throw new BusinessException(ResultCode.DIGITAL_GRANT_UNAVAILABLE);
        }
        digitalGrantMapper.incrViewCount(grantId);
        GrantContentVO vo = new GrantContentVO();
        vo.setDeliveryType(g.getDeliveryType());
        vo.setContent(g.getContent());
        vo.setViewCount((g.getViewCount() == null ? 0 : g.getViewCount()) + 1);
        return vo;
    }

    @Override
    public void revokeByOrder(Long orderId, List<Long> orderItemIds) {
        digitalGrantMapper.revokeByOrder(orderId);
        if (orderItemIds != null && !orderItemIds.isEmpty()) {
            cardKeyMapper.revokeByOrderItems(orderItemIds);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importCardKeys(Long skuId, List<String> lines) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException(ResultCode.SKU_NOT_FOUND);
        }
        int n = 0;
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String[] parts = line.trim().split("[,\\t]", 2);
            String no = parts[0].trim();
            if (no.isEmpty()) {
                continue;
            }
            CardKey key = new CardKey();
            key.setProductId(sku.getProductId());
            key.setSkuId(skuId);
            key.setCardNo(no);
            key.setCardSecret(parts.length > 1 ? parts[1].trim() : "");
            key.setStatus(0);
            cardKeyMapper.insert(key);
            n++;
        }
        // 池子即库存：导多少张就加多少可售库存
        if (n > 0) {
            skuMapper.addStock(skuId, n);
        }
        log.info("[digital] imported {} card key(s) for skuId={}", n, skuId);
        return n;
    }

    @Override
    public long availableCount(Long skuId) {
        return cardKeyMapper.countAvailable(skuId);
    }

    @Override
    public long checkStockConsistency() {
        long mismatched = cardKeyMapper.countMismatchedSkus();
        if (mismatched > 0) {
            // 不一致意味着"还能卖 N 件，但池子里没有卡"——不修就会给用户发出空卡
            log.error("[digital] card key pool mismatch: {} sku(s) where available != stock - locked", mismatched);
        }
        return mismatched;
    }

    @Override
    public void assertRefundable(Order order, Integer afterSaleType) {
        if (ProductType.of(order.getOrderType()) != ProductType.VIRTUAL) {
            return;
        }
        if (!Objects.equals(afterSaleType, AfterSaleType.REFUND_ONLY.getCode())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "虚拟商品只支持「仅退款」");
        }
        Long viewed = digitalGrantMapper.selectCount(Wrappers.<DigitalGrant>lambdaQuery()
                .eq(DigitalGrant::getOrderId, order.getId())
                .gt(DigitalGrant::getViewCount, 0));
        if (viewed != null && viewed > 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "凭证已查看，不能自助退款，请联系客服");
        }
    }
}
