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
import com.mall.core.enums.ExpressCompany;
import com.mall.core.enums.OrderStatus;
import com.mall.core.enums.ProductType;
import com.mall.core.mapper.CartMapper;
import com.mall.core.mapper.OrderItemMapper;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.metric.OrderMetrics;
import com.mall.core.model.dto.CreateDirectOrderDTO;
import com.mall.core.model.dto.CreateOrderDTO;
import com.mall.core.model.dto.PreviewOrderDTO;
import com.mall.core.model.vo.OrderCreateVO;
import com.mall.core.model.vo.OrderItemVO;
import com.mall.core.model.vo.OrderPreviewVO;
import com.mall.core.model.vo.OrderExportRowVO;
import com.mall.core.model.vo.OrderVO;
import com.mall.core.service.AddressService;
import com.mall.core.pricing.PricingLine;
import com.mall.core.pricing.PricingResult;
import com.mall.core.pricing.PricingService;
import com.mall.core.service.CouponService;
import com.mall.core.service.DigitalGoodsService;
import com.mall.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter CSV_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 导出上限：一次导出几十万行会把内存和 Excel 一起拖死 */
    private static final int MAX_EXPORT_ROWS = 5000;
    private static final long MAX_PAGE_SIZE = 100L;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final AddressService addressService;
    private final OrderProperties orderProperties;
    private final OrderMetrics orderMetrics;
    private final PricingService pricingService;
    private final CouponService couponService;
    private final DigitalGoodsService digitalGoodsService;

    /** 客户端生成的幂等键最大长度，与 t_order.request_id 的列定义一致 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    /** 下单需要结算的一行：来自购物车选中项，或来自「立即购买」的单个 SKU */
    private record OrderLine(Long productId, Long skuId, int quantity) {
    }

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

        // ② 幂等：同一个 requestId 已经建过单就把那一单还回去
        Order existing = findIdempotentOrder(userId, requestId);
        if (existing != null) {
            return OrderCreateVO.from(existing);
        }

        if (carts.isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }

        List<OrderLine> lines = new ArrayList<>(carts.size());
        for (Cart c : carts) {
            int qty = c.getQuantity() == null ? 0 : c.getQuantity();
            if (qty < 1) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "购买数量不合法");
            }
            lines.add(new OrderLine(c.getProductId(), c.getSkuId(), qty));
        }

        // 地址在 placeOrder 里解析：虚拟订单不需要地址，先知道商品类型才能决定要不要地址
        OrderCreateVO vo = placeOrder(userId, requestId, dto == null ? null : dto.getAddressId(), lines,
                dto == null ? null : dto.getUserCouponId());
        // 下单成功后移除购物车里已结算的（选中的）条目
        cartMapper.deleteChecked(userId);
        return vo;
    }

    /**
     * 「立即购买」：指定 SKU + 数量直接下单，**完全不读也不写购物车**。
     *
     * <p>在此之前 H5 的立即购买是「先把该 SKU 加购，再把购物车其它条目取消勾选」绕出来的，
     * 会破坏用户原有的勾选。这个入口才是正确做法。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateVO createDirect(Long userId, CreateDirectOrderDTO dto) {
        if (dto == null || dto.getSkuId() == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "skuId 不能为空");
        }
        int qty = dto.getQuantity() == null ? 1 : dto.getQuantity();
        if (qty < 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "购买数量不合法");
        }
        String requestId = normalizeRequestId(dto.getRequestId());

        Order existing = findIdempotentOrder(userId, requestId);
        if (existing != null) {
            return OrderCreateVO.from(existing);
        }

        Sku sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException(ResultCode.SKU_NOT_FOUND);
        }
        return placeOrder(userId, requestId, dto.getAddressId(),
                List.of(new OrderLine(sku.getProductId(), sku.getId(), qty)), dto.getUserCouponId());
    }

    /**
     * 幂等命中查询：按 (userId, requestId) 找已建的单。
     *
     * <p>用 FOR UPDATE 而不是普通查询——锁定读读的是**最新已提交数据**，不受本事务快照影响；
     * 并发重复提交时，后到者才能看到前者刚刚提交的那一单（普通查询可能读到旧快照而查不到）。
     */
    private Order findIdempotentOrder(Long userId, String requestId) {
        if (requestId == null) {
            return null;
        }
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(Order::getRequestId, requestId)
                .last("FOR UPDATE"));
    }

    /**
     * 真正建单：校验商品/SKU → 原子锁库存 → 落订单与明细 → 快照收货信息。
     * 购物车相关的事（读选中项、清选中项）由调用方处理，所以这个方法被两个入口共用。
     */
    private OrderCreateVO placeOrder(Long userId, String requestId, Long addressId,
                                     List<OrderLine> lines, Long userCouponId) {
        // 按 SKU 升序加锁：多个事务锁同一批 SKU 时加锁顺序一致，能显著减少死锁
        // （死锁本身的兜底是 DeadlockRetry，见 OrderController）
        List<OrderLine> ordered = lines.stream()
                .sorted(Comparator.comparing(OrderLine::skuId))
                .toList();

        // ① 先查商品判类型：虚拟订单不要地址，且**不能和实物混买**——
        //    混在一起后运费、发货时机、售后规则全部分叉，一单里很难讲清楚
        List<Product> products = new ArrayList<>(ordered.size());
        List<Sku> skus = new ArrayList<>(ordered.size());
        boolean hasVirtual = false;
        boolean hasPhysical = false;
        for (OrderLine line : ordered) {
            Product p = productMapper.selectById(line.productId());
            if (p == null) {
                throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
            }
            if (p.getStatus() == null || p.getStatus() == 0) {
                throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF, "商品已下架：" + p.getName());
            }
            Sku sku = skuMapper.selectById(line.skuId());
            if (sku == null || !Objects.equals(sku.getProductId(), p.getId())) {
                throw new BusinessException(ResultCode.SKU_NOT_FOUND);
            }
            if (ProductType.of(p.getProductType()) == ProductType.VIRTUAL) {
                hasVirtual = true;
            } else {
                hasPhysical = true;
            }
            products.add(p);
            skus.add(sku);
        }
        if (hasVirtual && hasPhysical) {
            throw new BusinessException(ResultCode.MIXED_ORDER_NOT_ALLOWED);
        }
        boolean virtual = hasVirtual;
        Address address = virtual ? null : resolveAddress(userId, addressId);

        // ② 锁库存 + 构造订单明细
        List<OrderItem> items = new ArrayList<>(ordered.size());
        List<PricingLine> pricingLines = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            OrderLine line = ordered.get(i);
            Product p = products.get(i);
            Sku sku = skus.get(i);
            // 防超卖的关键：条件 UPDATE 原子锁库存，失败即回滚整单
            if (skuMapper.lockStock(sku.getId(), line.quantity()) == 0) {
                // 计数器要在抛异常前打：异常会让事务回滚，但指标不该跟着消失
                orderMetrics.stockShortage();
                throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH, "库存不足：" + p.getName());
            }
            BigDecimal lineAmount = sku.getPrice() == null ? BigDecimal.ZERO
                    : sku.getPrice().multiply(BigDecimal.valueOf(line.quantity())).setScale(2, RoundingMode.HALF_UP);
            OrderItem item = new OrderItem();
            item.setProductId(p.getId());
            item.setSkuId(sku.getId());
            item.setProductName(p.getName());
            item.setProductImage(StringUtils.hasText(sku.getImage()) ? sku.getImage() : p.getMainImage());
            item.setSpec(sku.getSpec());
            item.setPrice(sku.getPrice());
            item.setQuantity(line.quantity());
            item.setTotalAmount(lineAmount);
            items.add(item);

            // 计价只信库里的价格与商品上绑的运费模板；虚拟商品强制无运费
            Long freightTemplateId = virtual ? null : p.getFreightTemplateId();
            pricingLines.add(new PricingLine(p.getId(), sku.getId(), line.quantity(),
                    sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice(), freightTemplateId));
        }

        // 服务端重新算一遍：商品金额 → 运费 → 优惠 → 应付，并把优惠分摊到每行。
        // 绝不采信前端传来的任何金额（那样改个请求就能 1 分钱下单）。
        PricingResult pricing = pricingService.calculate(pricingLines, userId, userCouponId);

        for (int i = 0; i < items.size(); i++) {
            PricingResult.LineShare share = pricing.getLines().get(i);
            items.get(i).setDiscountAmount(share.getDiscountAmount());
            items.get(i).setPayAmount(share.getPayAmount());
        }

        Order order = new Order();
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setTotalAmount(pricing.getGoodsAmount());
        order.setFreightAmount(pricing.getFreightAmount());
        order.setDiscountAmount(pricing.getDiscountAmount());
        order.setPayAmount(pricing.getPayAmount());
        order.setUserCouponId(pricing.getUserCouponId());
        order.setStatus(OrderStatus.UNPAID.getCode());
        // 类型快照：商品之后从虚拟改回实物，历史订单的发货/售后规则也不能跟着变
        order.setOrderType(virtual ? ProductType.VIRTUAL.getCode() : ProductType.PHYSICAL.getCode());
        if (address != null) {
            order.setReceiver(address.getReceiver());
            order.setReceiverPhone(address.getPhone());
            order.setReceiverAddr(joinAddress(address));
        }
        order.setExpireTime(LocalDateTime.now().plusMinutes(orderProperties.getExpireMinutes()));
        insertOrder(order);

        // 券在这里锁定（status 0→1 + 绑 order_id）。并发用同一张券只有一个能成功。
        if (pricing.getUserCouponId() != null) {
            couponService.lock(userId, pricing.getUserCouponId(), order.getId());
        }

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            orderItemMapper.insert(item);
        }
        orderMetrics.orderCreated();
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
        markPaidInternal(order, false);
    }

    /**
     * 支付成功后落账（由 {@code PaymentService} 在渠道回调/模拟支付成功时调用）。
     *
     * <p>和 {@link #pay} 的区别是**幂等**：渠道回调会重试，同一笔支付可能通知多次，
     * 第二次进来时订单已经是已支付，这里直接返回，不会再扣一次库存、再加一次销量。
     *
     * <p>注意扣库存用的是 `deductStock`（stock 真扣减），不是 release —— 后者只释放锁定库存，
     * 用在取消订单上。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(Long orderId, String channel, String paymentNo) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        log.info("[order] markPaid orderNo={} channel={} paymentNo={}", order.getOrderNo(), channel, paymentNo);
        markPaidInternal(order, true);
    }

    private void markPaidInternal(Order order, boolean idempotent) {
        Long orderId = order.getId();
        if (order.getExpireTime() != null && !order.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单已超时，无法支付");
        }
        List<OrderItem> items = itemsOf(orderId);
        if (transition(orderId, order.getUserId(), OrderStatus.UNPAID, OrderStatus.PAID, true) == 0) {
            if (idempotent && alreadyPaid(orderId)) {
                // 渠道重复回调：钱只收到一次，库存/销量也只能算一次
                return;
            }
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

        // 虚拟商品没有物流：支付成功即发放，发放成功直接置「已完成」。
        // 发放失败不抛异常（钱已经收了，不能让支付落账回滚），订单停在「已支付」等后台补发。
        if (ProductType.of(order.getOrderType()) == ProductType.VIRTUAL) {
            int granted = digitalGoodsService.grant(order, items);
            if (granted > 0) {
                transition(orderId, null, OrderStatus.PAID, OrderStatus.FINISHED, false);
            } else {
                log.error("[order] virtual order paid but nothing granted, orderNo={}", order.getOrderNo());
            }
        }
    }

    /** 订单是否已经处在「付过钱」之后的状态（重复回调时用来判定幂等） */
    private boolean alreadyPaid(Long orderId) {
        Order cur = orderMapper.selectById(orderId);
        if (cur == null || cur.getStatus() == null) {
            return false;
        }
        int s = cur.getStatus();
        return s == OrderStatus.PAID.getCode()
                || s == OrderStatus.SHIPPED.getCode()
                || s == OrderStatus.FINISHED.getCode()
                || s == OrderStatus.AFTER_SALE.getCode()
                || s == OrderStatus.REFUNDED.getCode();
    }

    /**
     * 结算试算：只读，不锁库存、不改任何数据。
     *
     * <p>金额必须和真正下单走同一套计价（{@link PricingService}），否则会出现
     * 「结算页 90 元、提交后 100 元」。这里算出来的数字只是给前端展示，下单时服务端会再算一遍。
     */
    @Override
    public OrderPreviewVO preview(Long userId, PreviewOrderDTO dto) {
        Long userCouponId = dto == null ? null : dto.getUserCouponId();
        List<OrderLine> lines;
        if (dto != null && dto.getSkuId() != null) {
            // 立即购买：只看这一个 SKU，不碰购物车
            int qty = dto.getQuantity() == null ? 1 : dto.getQuantity();
            if (qty < 1) {
                throw new BusinessException(ResultCode.VALIDATE_FAILED, "购买数量不合法");
            }
            Sku sku = skuMapper.selectById(dto.getSkuId());
            if (sku == null) {
                throw new BusinessException(ResultCode.SKU_NOT_FOUND);
            }
            lines = List.of(new OrderLine(sku.getProductId(), sku.getId(), qty));
        } else {
            // 购物车结算：与 create() 同样的筛选条件
            List<Cart> carts = cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                    .eq(Cart::getUserId, userId)
                    .eq(Cart::getChecked, 1)
                    .orderByAsc(Cart::getId));
            if (carts.isEmpty()) {
                throw new BusinessException(ResultCode.CART_EMPTY);
            }
            lines = new ArrayList<>(carts.size());
            for (Cart c : carts) {
                int qty = c.getQuantity() == null ? 0 : c.getQuantity();
                if (qty < 1) {
                    throw new BusinessException(ResultCode.VALIDATE_FAILED, "购买数量不合法");
                }
                lines.add(new OrderLine(c.getProductId(), c.getSkuId(), qty));
            }
        }

        List<PricingLine> pricingLines = new ArrayList<>(lines.size());
        List<OrderPreviewVO.Item> items = new ArrayList<>(lines.size());
        boolean hasVirtual = false;
        boolean hasPhysical = false;
        for (OrderLine line : lines) {
            Product p = productMapper.selectById(line.productId());
            if (p == null) {
                throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
            }
            if (p.getStatus() == null || p.getStatus() == 0) {
                throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF, "商品已下架：" + p.getName());
            }
            Sku sku = skuMapper.selectById(line.skuId());
            if (sku == null || !Objects.equals(sku.getProductId(), p.getId())) {
                throw new BusinessException(ResultCode.SKU_NOT_FOUND);
            }
            BigDecimal price = sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice();
            boolean isVirtual = ProductType.of(p.getProductType()) == ProductType.VIRTUAL;
            if (isVirtual) {
                hasVirtual = true;
            } else {
                hasPhysical = true;
            }
            pricingLines.add(new PricingLine(p.getId(), sku.getId(), line.quantity(), price,
                    isVirtual ? null : p.getFreightTemplateId()));

            OrderPreviewVO.Item it = new OrderPreviewVO.Item();
            it.setProductId(p.getId());
            it.setSkuId(sku.getId());
            it.setProductName(p.getName());
            it.setImage(StringUtils.hasText(sku.getImage()) ? sku.getImage() : p.getMainImage());
            it.setSpec(sku.getSpec());
            it.setPrice(price);
            it.setQuantity(line.quantity());
            items.add(it);
        }

        // 和下单保持同一套规则：混单直接拒绝，别等提交时才报错
        if (hasVirtual && hasPhysical) {
            throw new BusinessException(ResultCode.MIXED_ORDER_NOT_ALLOWED);
        }

        PricingResult pricing = pricingService.calculate(pricingLines, userId, userCouponId);

        // 分摊结果与入参同序，按下标回填即可
        for (int i = 0; i < items.size(); i++) {
            PricingResult.LineShare share = pricing.getLines().get(i);
            OrderPreviewVO.Item it = items.get(i);
            it.setLineAmount(share.getLineAmount());
            it.setDiscountAmount(share.getDiscountAmount());
            it.setPayAmount(share.getPayAmount());
        }

        OrderPreviewVO vo = new OrderPreviewVO();
        vo.setGoodsAmount(pricing.getGoodsAmount());
        vo.setFreightAmount(pricing.getFreightAmount());
        vo.setDiscountAmount(pricing.getDiscountAmount());
        vo.setPayAmount(pricing.getPayAmount());
        vo.setUserCouponId(pricing.getUserCouponId());
        // 前台据此决定要不要渲染地址块（虚拟订单不需要收货地址）
        vo.setOrderType(hasVirtual ? ProductType.VIRTUAL.getCode() : ProductType.PHYSICAL.getCode());
        vo.setOrderTypeText(ProductType.textOf(vo.getOrderType()));
        vo.setItems(items);
        // 券的可用性依赖「当前商品金额」，所以只能算完价再查
        vo.setCoupons(couponService.availableFor(userId, pricing.getGoodsAmount()));
        return vo;
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
        // 券退回：订单都没了，券不能跟着作废
        couponService.releaseByOrder(orderId);
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
    @Transactional(rollbackFor = Exception.class)
    public void regrantDigital(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (ProductType.of(order.getOrderType()) != ProductType.VIRTUAL) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "该订单不是虚拟商品订单");
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.PAID.getCode())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有「已支付」的虚拟订单才能补发");
        }
        int granted = digitalGoodsService.grant(order, itemsOf(orderId));
        if (granted == 0) {
            throw new BusinessException(ResultCode.CARD_KEY_REQUIRED, "补发失败：卡密不足或商品未配置发放方式");
        }
        transition(orderId, null, OrderStatus.PAID, OrderStatus.FINISHED, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(Long orderId, String company, String shipNo) {
        ExpressCompany express = ExpressCompany.of(company);
        if (express == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "请选择快递公司");
        }
        String no = shipNo == null ? "" : shipNo.trim();
        if (!no.matches("[A-Za-z0-9-]{4,64}")) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "运单号只能是 4-64 位字母、数字或短横线");
        }
        // 状态机照旧走 transition（保证和其它入口一致），物流字段单独写
        if (transition(orderId, null, OrderStatus.PAID, OrderStatus.SHIPPED, false) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有已支付订单才能发货");
        }
        Order upd = new Order();
        upd.setId(orderId);
        upd.setShipCompany(express.name());
        upd.setShipNo(no);
        upd.setShipTime(LocalDateTime.now());
        orderMapper.updateById(upd);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long orderId) {
        List<OrderItem> items = itemsOf(orderId);
        if (transition(orderId, null, OrderStatus.UNPAID, OrderStatus.CLOSED, false) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "只有待支付订单才能关闭");
        }
        release(items);
        // 后台关单和用户取消一样要把券退回，否则券会永远卡在这张死单上
        couponService.releaseByOrder(orderId);
    }

    @Override
    public String exportCsv(Integer status, String keyword, int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_EXPORT_ROWS);
        List<OrderExportRowVO> rows = orderMapper.listForExport(status,
                StringUtils.hasText(keyword) ? keyword.trim() : null, n);

        // BOM 必须放在最前面：没有它 Excel 打开中文是乱码（Windows 默认按 GBK 解析）
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("订单号,下单时间,支付时间,状态,类型,用户ID,商品金额,运费,优惠,实付,快递公司,运单号,收货人,商品明细\r\n");
        for (OrderExportRowVO r : rows) {
            sb.append(csvCell(r.getOrderNo())).append(',')
                    .append(csvCell(fmtCsvTime(r.getCreateTime()))).append(',')
                    .append(csvCell(fmtCsvTime(r.getPayTime()))).append(',')
                    .append(csvCell(OrderStatus.textOf(r.getStatus()))).append(',')
                    .append(csvCell(ProductType.textOf(r.getOrderType()))).append(',')
                    .append(csvCell(r.getUserId() == null ? "" : String.valueOf(r.getUserId()))).append(',')
                    .append(csvCell(moneyText(r.getTotalAmount()))).append(',')
                    .append(csvCell(moneyText(r.getFreightAmount()))).append(',')
                    .append(csvCell(moneyText(r.getDiscountAmount()))).append(',')
                    .append(csvCell(moneyText(r.getPayAmount()))).append(',')
                    .append(csvCell(ExpressCompany.textOf(r.getShipCompany()))).append(',')
                    .append(csvCell(r.getShipNo())).append(',')
                    .append(csvCell(r.getReceiver())).append(',')
                    .append(csvCell(r.getItems()))
                    .append("\r\n");
        }
        return sb.toString();
    }

    /** CSV 单元格转义：含逗号/引号/换行时用双引号包起来，内部引号双写 */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        boolean needQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        return needQuote ? "\"" + v + "\"" : v;
    }

    private static String fmtCsvTime(LocalDateTime time) {
        return time == null ? "" : time.format(CSV_TIME_FORMAT);
    }

    private static String moneyText(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
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
            couponService.releaseByOrder(o.getId());
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
