package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.config.OrderProperties;
import com.mall.core.entity.Address;
import com.mall.core.entity.Cart;
import com.mall.core.entity.Order;
import com.mall.core.entity.OrderItem;
import com.mall.core.entity.Product;
import com.mall.core.entity.Sku;
import com.mall.core.enums.OrderStatus;
import com.mall.core.mapper.CartMapper;
import com.mall.core.mapper.OrderItemMapper;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.dto.CreateOrderDTO;
import com.mall.core.model.vo.OrderCreateVO;
import com.mall.core.model.vo.OrderItemVO;
import com.mall.core.model.vo.OrderVO;
import com.mall.core.service.AddressService;
import com.mall.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long MAX_PAGE_SIZE = 100L;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final AddressService addressService;
    private final OrderProperties orderProperties;

    /** 客户端生成的幂等键最大长度，与 t_order.request_id 的列定义一致 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateVO create(Long userId, CreateOrderDTO dto) {
        String requestId = normalizeRequestId(dto == null ? null : dto.getRequestId());

        // ① 先锁住「该用户已选中的购物车行」。同一用户并发的两次提交会在这里被串行化：
        //    后到的那次要等前者提交，而锁定读（FOR UPDATE）总是读最新已提交数据，
        //    于是它会看到选中项已被清空，不会再建第二单、也不会重复锁库存。
        List<Cart> carts = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getChecked, 1)
                .orderByAsc(Cart::getId)
                // 这里的裸 SQL 是刻意的：只有加锁读才能让并发提交串行化并绕过事务快照
                .last("FOR UPDATE"));

        // ② 幂等：同一个 requestId（调用方在一次结算里固定不变）已经建过单，直接把那一单还回去。
        //    同样用 FOR UPDATE：锁定读读的是最新已提交数据，不受本事务快照影响。
        if (requestId != null) {
            Order existing = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getRequestId, requestId)
                    .last("FOR UPDATE"));
            if (existing != null) {
                return OrderCreateVO.from(existing);
            }
        }

        if (carts.isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }
        Address address = resolveAddress(userId, dto == null ? null : dto.getAddressId());

        List<OrderItem> items = new ArrayList<>(carts.size());
        BigDecimal total = BigDecimal.ZERO;
        for (Cart c : carts) {
            int qty = c.getQuantity() == null ? 0 : c.getQuantity();
            if (qty < 1) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "购买数量不合法");
            }
            Product p = productMapper.selectById(c.getProductId());
            if (p == null) {
                throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
            }
            if (p.getStatus() == null || p.getStatus() == 0) {
                throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF, "商品已下架：" + p.getName());
            }
            Sku sku = skuMapper.selectById(c.getSkuId());
            if (sku == null || !Objects.equals(sku.getProductId(), p.getId())) {
                throw new BusinessException(ResultCode.SKU_NOT_FOUND);
            }
            // 防超卖的关键：条件 UPDATE 原子锁库存，失败即回滚整单
            if (skuMapper.lockStock(sku.getId(), qty) == 0) {
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "库存不足：" + p.getName());
            }
            BigDecimal lineAmount = sku.getPrice() == null ? BigDecimal.ZERO
                    : sku.getPrice().multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            OrderItem item = new OrderItem();
            item.setProductId(p.getId());
            item.setSkuId(sku.getId());
            item.setProductName(p.getName());
            item.setProductImage(StringUtils.hasText(sku.getImage()) ? sku.getImage() : p.getMainImage());
            item.setSpec(sku.getSpec());
            item.setPrice(sku.getPrice());
            item.setQuantity(qty);
            item.setTotalAmount(lineAmount);
            items.add(item);
            total = total.add(lineAmount);
        }

        Order order = new Order();
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setTotalAmount(total);
        order.setPayAmount(total);
        order.setStatus(OrderStatus.UNPAID.getCode());
        order.setReceiver(address.getReceiver());
        order.setReceiverPhone(address.getPhone());
        order.setReceiverAddr(joinAddress(address));
        order.setExpireTime(LocalDateTime.now().plusMinutes(orderProperties.getExpireMinutes()));
        insertOrder(order);

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            orderItemMapper.insert(item);
        }
        // 下单成功后移除购物车里已结算的（选中的）条目
        cartMapper.deleteChecked(userId);
        return OrderCreateVO.from(order);
    }

    @Override
    public OrderVO detail(Long userId, Long orderId) {
        Order o = requireOwned(userId, orderId);
        return OrderVO.from(o, toItemVOList(itemsOf(orderId)));
    }

    @Override
    public PageResult<OrderVO> pageByUser(Long userId, long pageNum, long pageSize, Integer status) {
        return doPage(pageNum, pageSize, Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(status != null, Order::getStatus, status)
                .orderByDesc(Order::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long userId, Long orderId) {
        Order order = requireOwned(userId, orderId);
        if (order.getExpireTime() != null && !order.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单已超时，无法支付");
        }
        List<OrderItem> items = itemsOf(orderId);
        if (transition(orderId, userId, OrderStatus.UNPAID, OrderStatus.PAID, true) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有待支付订单才能支付");
        }
        Map<Long, Integer> qtyByProduct = new HashMap<>();
        for (OrderItem item : items) {
            if (skuMapper.deductStock(item.getSkuId(), item.getQuantity()) == 0) {
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "库存不足，支付失败");
            }
            qtyByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        // 销量在下单时不算，支付成功才计
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            productMapper.incrementSales(e.getKey(), e.getValue());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        requireOwned(userId, orderId);
        List<OrderItem> items = itemsOf(orderId);
        if (transition(orderId, userId, OrderStatus.UNPAID, OrderStatus.CLOSED, false) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有待支付订单才能取消");
        }
        release(items);
    }

    @Override
    public void confirm(Long userId, Long orderId) {
        requireOwned(userId, orderId);
        if (transition(orderId, userId, OrderStatus.SHIPPED, OrderStatus.FINISHED, false) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有已发货订单才能确认收货");
        }
    }

    @Override
    public PageResult<OrderVO> pageForAdmin(long pageNum, long pageSize, Integer status, String keyword) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return doPage(pageNum, pageSize, Wrappers.<Order>lambdaQuery()
                .eq(status != null, Order::getStatus, status)
                .like(kw != null, Order::getOrderNo, kw)
                .orderByDesc(Order::getId));
    }

    @Override
    public OrderVO detailForAdmin(Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return OrderVO.from(o, toItemVOList(itemsOf(orderId)));
    }

    @Override
    public void ship(Long orderId) {
        if (transition(orderId, null, OrderStatus.PAID, OrderStatus.SHIPPED, false) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有已支付订单才能发货");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long orderId) {
        List<OrderItem> items = itemsOf(orderId);
        if (transition(orderId, null, OrderStatus.UNPAID, OrderStatus.CLOSED, false) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有待支付订单才能关闭");
        }
        release(items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeExpiredOrders(int limit) {
        List<Order> expired = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.UNPAID.getCode())
                .le(Order::getExpireTime, LocalDateTime.now())
                .orderByAsc(Order::getId)
                .last("limit " + Math.max(limit, 1)));
        int closed = 0;
        for (Order o : expired) {
            // 带 status 条件的流转：被别人（用户取消 / 支付 / 后台关闭）抢先处理过就跳过
            if (transition(o.getId(), null, OrderStatus.UNPAID, OrderStatus.CLOSED, false) == 0) {
                continue;
            }
            release(itemsOf(o.getId()));
            closed++;
        }
        return closed;
    }

    // ------------------------------------------------------------------ 内部方法

    private Address resolveAddress(Long userId, Long addressId) {
        if (addressId != null) {
            return addressService.getOwned(userId, addressId);
        }
        Address def = addressService.getDefault(userId);
        if (def == null) {
            throw new BusinessException(ResultCode.ADDRESS_REQUIRED);
        }
        return def;
    }

    private String joinAddress(Address a) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{a.getProvince(), a.getCity(), a.getDistrict(), a.getDetail()}) {
            if (StringUtils.hasText(part)) {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** 状态流转：带 from 条件的 UPDATE，返回 0 表示当前状态不允许该操作 */
    private int transition(Long orderId, Long userId, OrderStatus from, OrderStatus to, boolean markPayTime) {
        Order patch = new Order();
        patch.setStatus(to.getCode());
        if (markPayTime) {
            patch.setPayTime(LocalDateTime.now());
        }
        LambdaUpdateWrapper<Order> w = Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, from.getCode());
        if (userId != null) {
            w.eq(Order::getUserId, userId);
        }
        return orderMapper.update(patch, w);
    }

    private void release(List<OrderItem> items) {
        for (OrderItem item : items) {
            skuMapper.releaseStock(item.getSkuId(), item.getQuantity());
        }
    }

    private Order requireOwned(Long userId, Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null || !Objects.equals(o.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return o;
    }

    private List<OrderItem> itemsOf(Long orderId) {
        return orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderId)
                .orderByAsc(OrderItem::getId));
    }

    private List<OrderItemVO> toItemVOList(List<OrderItem> items) {
        return items.stream().map(OrderItemVO::from).toList();
    }

    private PageResult<OrderVO> doPage(long pageNum, long pageSize, LambdaQueryWrapper<Order> w) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Order> page = new Page<>(pn, ps);
        orderMapper.selectPage(page, w);
        List<Order> records = page.getRecords();
        Map<Long, List<OrderItemVO>> itemMap = Map.of();
        if (!records.isEmpty()) {
            List<Long> orderIds = records.stream().map(Order::getId).toList();
            itemMap = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                            .in(OrderItem::getOrderId, orderIds)
                            .orderByAsc(OrderItem::getId))
                    .stream()
                    .collect(Collectors.groupingBy(OrderItem::getOrderId,
                            Collectors.mapping(OrderItemVO::from, Collectors.toList())));
        }
        List<OrderVO> list = new ArrayList<>(records.size());
        for (Order o : records) {
            list.add(OrderVO.from(o, itemMap.getOrDefault(o.getId(), List.of())));
        }
        return PageResult.of(page.getTotal(), pn, ps, list);
    }

    /** 单号：时间戳 + 6 位随机数（order_no 上有唯一键兜底） */
    private String nextOrderNo() {
        return ORDER_NO_FORMAT.format(LocalDateTime.now())
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** 幂等键规整：空白视为「不传」；过长直接拒绝，不要等落到数据库才报错 */
    private String normalizeRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return null;
        }
        String trimmed = requestId.trim();
        if (trimmed.length() > MAX_REQUEST_ID_LENGTH) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "requestId 最长 " + MAX_REQUEST_ID_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /** 撞唯一键说明单号重复：换号重试，避免把 DuplicateKeyException 漏成 500 */
    private void insertOrder(Order order) {
        for (int attempt = 0; attempt < 3; attempt++) {
            order.setOrderNo(nextOrderNo());
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException ignored) {
                // 换一个单号再试
            }
        }
        throw new BusinessException(ResultCode.FAILED, "订单号生成冲突，请重试");
    }
}
