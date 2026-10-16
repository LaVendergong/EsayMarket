package com.mall.core.service.impl;

import com.mall.common.exception.BusinessException;
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
import com.mall.core.model.dto.CreateDirectOrderDTO;
import com.mall.core.model.dto.CreateOrderDTO;
import com.mall.core.model.vo.OrderCreateVO;
import com.mall.core.service.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 订单域的核心逻辑测试。这里覆盖的都是「错了会直接造成钱/库存问题」的分支：
 * 下单锁库存与金额快照、支付才计销量、超时订单不许支付、状态被抢先流转时的幂等跳过。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private CartMapper cartMapper;
    @Mock private ProductMapper productMapper;
    @Mock private SkuMapper skuMapper;
    @Mock private AddressService addressService;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        OrderProperties props = new OrderProperties();
        props.setExpireMinutes(30);
        service = new OrderServiceImpl(orderMapper, orderItemMapper, cartMapper, productMapper, skuMapper,
                addressService, props);
    }

    // ------------------------------------------------------------------ 测试数据
    private Cart cart(long id, long productId, long skuId, int qty) {
        Cart c = new Cart();
        c.setId(id);
        c.setUserId(1L);
        c.setProductId(productId);
        c.setSkuId(skuId);
        c.setQuantity(qty);
        c.setChecked(1);
        return c;
    }

    private Product product(long id, int status) {
        Product p = new Product();
        p.setId(id);
        p.setName("商品" + id);
        p.setStatus(status);
        p.setMainImage("/img/p" + id + ".svg");
        return p;
    }

    private Sku sku(long id, long productId, String price, int stock) {
        Sku s = new Sku();
        s.setId(id);
        s.setProductId(productId);
        s.setSpec("规格" + id);
        s.setPrice(new BigDecimal(price));
        s.setStock(stock);
        s.setLockedStock(0);
        return s;
    }

    private Address address() {
        Address a = new Address();
        a.setId(9L);
        a.setUserId(1L);
        a.setReceiver("张三");
        a.setPhone("13800000000");
        a.setProvince("广东省");
        a.setCity("深圳市");
        a.setDistrict("南山区");
        a.setDetail("科技园1号");
        return a;
    }

    private Order order(long id, int status, LocalDateTime expireTime) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(1L);
        o.setOrderNo("NO" + id);
        o.setStatus(status);
        o.setExpireTime(expireTime);
        o.setTotalAmount(new BigDecimal("100.00"));
        o.setPayAmount(new BigDecimal("100.00"));
        return o;
    }

    private OrderItem item(long id, long orderId, long productId, long skuId, int qty, String price) {
        OrderItem i = new OrderItem();
        i.setId(id);
        i.setOrderId(orderId);
        i.setOrderNo("NO" + orderId);
        i.setProductId(productId);
        i.setSkuId(skuId);
        i.setProductName("商品" + productId);
        i.setSpec("规格" + skuId);
        i.setQuantity(qty);
        i.setPrice(new BigDecimal(price));
        i.setTotalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        return i;
    }

    private CreateOrderDTO dtoWithAddress(long addressId) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setAddressId(addressId);
        return dto;
    }

    private CreateDirectOrderDTO directDto(long skuId, int quantity) {
        CreateDirectOrderDTO dto = new CreateDirectOrderDTO();
        dto.setSkuId(skuId);
        dto.setQuantity(quantity);
        dto.setAddressId(9L);
        return dto;
    }

    private int codeOf(BusinessException e) {
        assertThat(e).isNotNull();
        return e.getCode();
    }

    // ================================================================== create
    @Test
    void create_withEmptyCart_isRejectedAndNothingIsWritten() {
        when(cartMapper.selectList(any())).thenReturn(List.of());

        BusinessException e = catchThrowableOfType(() -> service.create(1L, dtoWithAddress(9L)), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.CART_EMPTY.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void create_withoutAnyAddress_isRejected() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(1L, 10L, 100L, 1)));
        when(addressService.getDefault(1L)).thenReturn(null);

        BusinessException e = catchThrowableOfType(() -> service.create(1L, null), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.ADDRESS_REQUIRED.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void create_withOffShelfProduct_isRejected() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(1L, 10L, 100L, 1)));
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(productMapper.selectById(10L)).thenReturn(product(10L, 0));

        BusinessException e = catchThrowableOfType(() -> service.create(1L, dtoWithAddress(9L)), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.PRODUCT_OFF_SHELF.getCode());
        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
    }

    @Test
    void create_whenStockLockFails_isRejectedAndOrderIsNotInserted() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(1L, 10L, 100L, 2)));
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(productMapper.selectById(10L)).thenReturn(product(10L, 1));
        when(skuMapper.selectById(100L)).thenReturn(sku(100L, 10L, "30.00", 1));
        when(skuMapper.lockStock(100L, 2)).thenReturn(0);

        BusinessException e = catchThrowableOfType(() -> service.create(1L, dtoWithAddress(9L)), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.STOCK_NOT_ENOUGH.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(cartMapper, never()).deleteChecked(anyLong());
    }

    @Test
    void create_happyPath_snapshotsAddressLocksStockAndBuildsItems() {
        when(cartMapper.selectList(any())).thenReturn(List.of(
                cart(1L, 10L, 100L, 2),
                cart(2L, 10L, 101L, 1)));
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(productMapper.selectById(10L)).thenReturn(product(10L, 1));
        when(skuMapper.selectById(100L)).thenReturn(sku(100L, 10L, "30.00", 5));
        when(skuMapper.selectById(101L)).thenReturn(sku(101L, 10L, "40.00", 5));
        when(skuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(777L);
            return 1;
        });

        OrderCreateVO vo = service.create(1L, dtoWithAddress(9L));

        assertThat(vo.getOrderId()).isEqualTo(777L);
        assertThat(vo.getOrderNo()).isNotBlank();
        assertThat(vo.getPayAmount()).isEqualByComparingTo("100.00"); // 2×30 + 1×40
        assertThat(vo.getStatus()).isEqualTo(OrderStatus.UNPAID.getCode());
        assertThat(vo.getExpireTime()).isAfter(LocalDateTime.now().plusMinutes(25));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.UNPAID.getCode());
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getReceiver()).isEqualTo("张三");
        assertThat(saved.getReceiverPhone()).isEqualTo("13800000000");
        assertThat(saved.getReceiverAddr()).isEqualTo("广东省深圳市南山区科技园1号");

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper, times(2)).insert(itemCaptor.capture());
        List<OrderItem> items = itemCaptor.getAllValues();
        assertThat(items).extracting(OrderItem::getSkuId).containsExactly(100L, 101L);
        assertThat(items.get(0).getTotalAmount()).isEqualByComparingTo("60.00");
        assertThat(items.get(1).getTotalAmount()).isEqualByComparingTo("40.00");
        assertThat(items.get(0).getOrderId()).isEqualTo(777L);

        verify(skuMapper).lockStock(100L, 2);
        verify(skuMapper).lockStock(101L, 1);
        verify(cartMapper).deleteChecked(1L);
        // 下单只是锁库存，不能计销量
        verify(productMapper, never()).incrementSales(anyLong(), anyInt());
    }

    // ============================================================ create 幂等
    @Test
    void create_withAlreadyUsedRequestId_returnsThatOrderAndTouchesNothingElse() {
        Order existing = order(88L, OrderStatus.UNPAID.getCode(), LocalDateTime.now().plusMinutes(10));
        existing.setRequestId("req-1");
        // 上一次已经把购物车选中项清掉了，所以这次读出来是空的
        when(cartMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectOne(any())).thenReturn(existing);

        CreateOrderDTO dto = dtoWithAddress(9L);
        dto.setRequestId("req-1");

        OrderCreateVO vo = service.create(1L, dto);

        assertThat(vo.getOrderId()).isEqualTo(88L);
        assertThat(vo.getOrderNo()).isEqualTo("NO88");
        assertThat(vo.getPayAmount()).isEqualByComparingTo("100.00");
        verify(orderMapper, never()).insert(any(Order.class));
        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
        verify(cartMapper, never()).deleteChecked(anyLong());
    }

    @Test
    void create_withRequestId_storesTheTrimmedKeyOnTheOrderRow() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(1L, 10L, 100L, 1)));
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(productMapper.selectById(10L)).thenReturn(product(10L, 1));
        when(skuMapper.selectById(100L)).thenReturn(sku(100L, 10L, "30.00", 5));
        when(skuMapper.lockStock(anyLong(), anyInt())).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(1L);
            return 1;
        });

        CreateOrderDTO dto = dtoWithAddress(9L);
        dto.setRequestId("   req-2   ");
        service.create(1L, dto);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getRequestId()).isEqualTo("req-2");
    }

    @Test
    void create_withTooLongRequestId_isRejectedBeforeTakingAnyLock() {
        CreateOrderDTO dto = dtoWithAddress(9L);
        dto.setRequestId("x".repeat(65));

        BusinessException e = catchThrowableOfType(() -> service.create(1L, dto), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verify(cartMapper, never()).selectList(any());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void create_withoutRequestId_skipsTheIdempotencyLookup() {
        when(cartMapper.selectList(any())).thenReturn(List.of());

        BusinessException e = catchThrowableOfType(() -> service.create(1L, null), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.CART_EMPTY.getCode());
        verify(orderMapper, never()).selectOne(any());
    }

    // ============================================================ createDirect（立即购买）
    @Test
    void createDirect_withoutSkuId_isRejectedBeforeAnythingHappens() {
        CreateDirectOrderDTO dto = new CreateDirectOrderDTO();

        BusinessException e = catchThrowableOfType(() -> service.createDirect(1L, dto), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verifyNoInteractions(cartMapper);
    }

    @Test
    void createDirect_withNonPositiveQuantity_isRejected() {
        CreateDirectOrderDTO dto = directDto(100L, 0);

        BusinessException e = catchThrowableOfType(() -> service.createDirect(1L, dto), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
    }

    @Test
    void createDirect_withUnknownSku_isRejected() {
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        // skuMapper.selectById 未打桩 -> 返回 null

        BusinessException e = catchThrowableOfType(() -> service.createDirect(1L, directDto(999L, 1)), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.SKU_NOT_FOUND.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createDirect_withOffShelfProduct_isRejected() {
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(skuMapper.selectById(100L)).thenReturn(sku(100L, 10L, "30.00", 5));
        when(productMapper.selectById(10L)).thenReturn(product(10L, 0));

        BusinessException e = catchThrowableOfType(() -> service.createDirect(1L, directDto(100L, 1)), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.PRODUCT_OFF_SHELF.getCode());
        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
    }

    @Test
    void createDirect_whenStockLockFails_isRejected() {
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(skuMapper.selectById(100L)).thenReturn(sku(100L, 10L, "30.00", 1));
        when(productMapper.selectById(10L)).thenReturn(product(10L, 1));
        when(skuMapper.lockStock(100L, 3)).thenReturn(0);

        BusinessException e = catchThrowableOfType(() -> service.createDirect(1L, directDto(100L, 3)), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.STOCK_NOT_ENOUGH.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createDirect_happyPath_placesTheOrderWithoutTouchingTheCart() {
        when(addressService.getOwned(1L, 9L)).thenReturn(address());
        when(skuMapper.selectById(100L)).thenReturn(sku(100L, 10L, "30.00", 5));
        when(productMapper.selectById(10L)).thenReturn(product(10L, 1));
        when(skuMapper.lockStock(100L, 2)).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(500L);
            return 1;
        });

        OrderCreateVO vo = service.createDirect(1L, directDto(100L, 2));

        assertThat(vo.getOrderId()).isEqualTo(500L);
        assertThat(vo.getPayAmount()).isEqualByComparingTo("60.00");
        assertThat(vo.getStatus()).isEqualTo(OrderStatus.UNPAID.getCode());

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getSkuId()).isEqualTo(100L);
        assertThat(itemCaptor.getValue().getQuantity()).isEqualTo(2);
        assertThat(itemCaptor.getValue().getTotalAmount()).isEqualByComparingTo("60.00");

        verify(skuMapper).lockStock(100L, 2);
        // 本功能的核心：立即购买不读也不写购物车（不会破坏用户原有的勾选）
        verify(cartMapper, never()).selectList(any());
        verify(cartMapper, never()).deleteChecked(anyLong());
    }

    @Test
    void createDirect_withReusedRequestId_returnsTheExistingOrderWithoutLockingStock() {
        Order existing = order(700L, OrderStatus.UNPAID.getCode(), LocalDateTime.now().plusMinutes(10));
        existing.setRequestId("direct-1");
        when(orderMapper.selectOne(any())).thenReturn(existing);

        CreateDirectOrderDTO dto = directDto(100L, 2);
        dto.setRequestId("direct-1");

        OrderCreateVO vo = service.createDirect(1L, dto);

        assertThat(vo.getOrderId()).isEqualTo(700L);
        verify(orderMapper, never()).insert(any(Order.class));
        verify(skuMapper, never()).lockStock(anyLong(), anyInt());
    }

    // ================================================================== pay
    @Test
    void pay_expiredOrder_isRejectedBeforeAnyStockChange() {
        when(orderMapper.selectById(50L)).thenReturn(order(50L, 0, LocalDateTime.now().minusMinutes(1)));

        BusinessException e = catchThrowableOfType(() -> service.pay(1L, 50L), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.ORDER_STATUS_ERROR.getCode());
        assertThat(e.getMessage()).contains("超时");
        verify(orderMapper, never()).update(any(Order.class), any());
        verify(skuMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void pay_whenStatusWasAlreadyChangedBySomeoneElse_isRejected() {
        when(orderMapper.selectById(50L)).thenReturn(order(50L, 0, LocalDateTime.now().plusMinutes(10)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 50L, 10L, 100L, 1, "30.00")));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0); // 别人抢先流转了

        BusinessException e = catchThrowableOfType(() -> service.pay(1L, 50L), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.ORDER_STATUS_ERROR.getCode());
        verify(skuMapper, never()).deductStock(anyLong(), anyInt());
        verify(productMapper, never()).incrementSales(anyLong(), anyInt());
    }

    @Test
    void pay_success_marksPaidDeductsStockAndMergesSalesPerProduct() {
        when(orderMapper.selectById(50L)).thenReturn(order(50L, 0, LocalDateTime.now().plusMinutes(10)));
        // 同一商品的两个 SKU：销量必须合并成一次 +3，而不是加两次
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(1L, 50L, 10L, 100L, 2, "30.00"),
                item(2L, 50L, 10L, 101L, 1, "40.00")));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);
        when(skuMapper.deductStock(anyLong(), anyInt())).thenReturn(1);

        service.pay(1L, 50L);

        ArgumentCaptor<Order> patchCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(patchCaptor.capture(), any());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo(OrderStatus.PAID.getCode());
        assertThat(patchCaptor.getValue().getPayTime()).isNotNull();

        verify(skuMapper).deductStock(100L, 2);
        verify(skuMapper).deductStock(101L, 1);
        verify(productMapper, times(1)).incrementSales(10L, 3);
    }

    @Test
    void pay_whenDeductStockFails_throwsStockNotEnough() {
        when(orderMapper.selectById(50L)).thenReturn(order(50L, 0, LocalDateTime.now().plusMinutes(10)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 50L, 10L, 100L, 2, "30.00")));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);
        when(skuMapper.deductStock(100L, 2)).thenReturn(0);

        BusinessException e = catchThrowableOfType(() -> service.pay(1L, 50L), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.STOCK_NOT_ENOUGH.getCode());
        verify(productMapper, never()).incrementSales(anyLong(), anyInt());
    }

    // ================================================================== cancel / close / timeout
    @Test
    void cancel_releasesLockedStockForEveryItem() {
        when(orderMapper.selectById(50L)).thenReturn(order(50L, 0, LocalDateTime.now().plusMinutes(10)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(1L, 50L, 10L, 100L, 2, "30.00"),
                item(2L, 50L, 10L, 101L, 1, "40.00")));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.cancel(1L, 50L);

        verify(skuMapper).releaseStock(100L, 2);
        verify(skuMapper).releaseStock(101L, 1);
        verify(skuMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void cancel_twice_isRejectedTheSecondTime() {
        when(orderMapper.selectById(50L)).thenReturn(order(50L, 4, LocalDateTime.now().plusMinutes(10)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 50L, 10L, 100L, 2, "30.00")));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        BusinessException e = catchThrowableOfType(() -> service.cancel(1L, 50L), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.ORDER_STATUS_ERROR.getCode());
        verify(skuMapper, never()).releaseStock(anyLong(), anyInt());
    }

    @Test
    void anotherUsersOrder_isTreatedAsNotFound() {
        Order other = order(50L, 0, LocalDateTime.now().plusMinutes(10));
        other.setUserId(999L);
        when(orderMapper.selectById(50L)).thenReturn(other);

        BusinessException e = catchThrowableOfType(() -> service.detail(1L, 50L), BusinessException.class);

        assertThat(codeOf(e)).isEqualTo(ResultCode.ORDER_NOT_FOUND.getCode());
    }

    @Test
    void closeExpiredOrders_skipsOrdersSomeoneElseAlreadyHandled() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(5);
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(50L, 0, past),
                order(51L, 0, past)));
        // 第一单流转失败（被用户取消/后台关闭抢先），第二单成功
        when(orderMapper.update(any(Order.class), any())).thenReturn(0, 1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 51L, 10L, 200L, 1, "30.00")));

        int closed = service.closeExpiredOrders(100);

        assertThat(closed).isEqualTo(1);
        verify(skuMapper).releaseStock(200L, 1);
        verify(skuMapper, never()).releaseStock(eq(100L), anyInt());
    }
}
