package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Cart;
import com.mall.core.entity.Product;
import com.mall.core.entity.Sku;
import com.mall.core.mapper.CartMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.dto.CartAddDTO;
import com.mall.core.model.vo.CartVO;
import com.mall.core.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;

    @Override
    public List<CartVO> list(Long userId) {
        List<Cart> carts = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .orderByDesc(Cart::getId));
        if (carts.isEmpty()) {
            return List.of();
        }
        Set<Long> productIds = carts.stream().map(Cart::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> skuIds = carts.stream().map(Cart::getSkuId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Product> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Sku> skus = skuIds.isEmpty() ? Map.of()
                : skuMapper.selectBatchIds(skuIds).stream().collect(Collectors.toMap(Sku::getId, Function.identity()));

        List<CartVO> out = new ArrayList<>(carts.size());
        for (Cart c : carts) {
            Product p = products.get(c.getProductId());
            Sku s = skus.get(c.getSkuId());
            CartVO vo = new CartVO();
            vo.setId(c.getId());
            vo.setProductId(c.getProductId());
            vo.setSkuId(c.getSkuId());
            vo.setQuantity(c.getQuantity());
            vo.setChecked(c.getChecked());
            if (p != null) {
                vo.setProductName(p.getName());
                vo.setProductStatus(p.getStatus());
            }
            if (s != null) {
                vo.setSpec(s.getSpec());
                vo.setPrice(s.getPrice());
                vo.setAvailableStock(available(s));
                vo.setProductImage(StringUtils.hasText(s.getImage()) ? s.getImage() : (p == null ? null : p.getMainImage()));
                if (s.getPrice() != null && c.getQuantity() != null) {
                    vo.setTotalPrice(s.getPrice().multiply(BigDecimal.valueOf(c.getQuantity())).setScale(2, RoundingMode.HALF_UP));
                }
            }
            boolean valid = p != null && p.getStatus() != null && p.getStatus() == 1
                    && s != null && vo.getAvailableStock() != null && vo.getAvailableStock() > 0;
            vo.setValid(valid);
            out.add(vo);
        }
        return out;
    }

    @Override
    public Long add(Long userId, CartAddDTO dto) {
        int qty = dto.getQuantity() == null ? 0 : dto.getQuantity();
        if (qty < 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "数量不能小于 1");
        }
        Sku sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException(ResultCode.SKU_NOT_FOUND);
        }
        Product p = productMapper.selectById(sku.getProductId());
        if (p == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (p.getStatus() == null || p.getStatus() == 0) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        int available = available(sku);
        Cart exist = cartMapper.selectOne(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getSkuId, sku.getId())
                .last("limit 1"));
        int totalQty = (exist == null || exist.getQuantity() == null ? 0 : exist.getQuantity()) + qty;
        if (totalQty > available) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "库存不足，最多可购 " + available + " 件");
        }
        if (exist == null) {
            Cart c = new Cart();
            c.setUserId(userId);
            c.setProductId(p.getId());
            c.setSkuId(sku.getId());
            c.setQuantity(qty);
            c.setChecked(1);
            cartMapper.insert(c);
            return c.getId();
        }
        exist.setQuantity(totalQty);
        exist.setChecked(1);
        cartMapper.updateById(exist);
        return exist.getId();
    }

    @Override
    public void updateQuantity(Long userId, Long id, Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "数量不能小于 1");
        }
        Cart c = requireOwned(userId, id);
        Sku sku = skuMapper.selectById(c.getSkuId());
        if (sku == null) {
            throw new BusinessException(ResultCode.SKU_NOT_FOUND);
        }
        int available = available(sku);
        if (quantity > available) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "库存不足，最多可购 " + available + " 件");
        }
        Cart upd = new Cart();
        upd.setId(id);
        upd.setQuantity(quantity);
        cartMapper.updateById(upd);
    }

    @Override
    public void updateChecked(Long userId, Long id, Integer checked) {
        if (checked == null || (checked != 0 && checked != 1)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "checked 只能是 0 或 1");
        }
        requireOwned(userId, id);
        Cart upd = new Cart();
        upd.setId(id);
        upd.setChecked(checked);
        cartMapper.updateById(upd);
    }

    @Override
    public void remove(Long userId, Long id) {
        requireOwned(userId, id);
        cartMapper.deleteOwned(id, userId);
    }

    @Override
    public void clearChecked(Long userId) {
        cartMapper.deleteChecked(userId);
    }

    private Cart requireOwned(Long userId, Long id) {
        Cart c = cartMapper.selectById(id);
        if (c == null || !Objects.equals(c.getUserId(), userId)) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }
        return c;
    }

    private int available(Sku s) {
        int stock = s.getStock() == null ? 0 : s.getStock();
        int locked = s.getLockedStock() == null ? 0 : s.getLockedStock();
        return Math.max(stock - locked, 0);
    }
}
