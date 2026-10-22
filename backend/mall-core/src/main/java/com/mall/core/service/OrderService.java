package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.CreateDirectOrderDTO;
import com.mall.core.model.dto.CreateOrderDTO;
import com.mall.core.model.vo.OrderCreateVO;
import com.mall.core.model.vo.OrderVO;

public interface OrderService {

    /** 结算购物车中「已选中」的条目并生成订单（同时锁定库存） */
    OrderCreateVO create(Long userId, CreateOrderDTO dto);

    /** 「立即购买」：指定 SKU + 数量直接下单，**不读也不写购物车** */
    OrderCreateVO createDirect(Long userId, CreateDirectOrderDTO dto);

    OrderVO detail(Long userId, Long orderId);

    PageResult<OrderVO> pageByUser(Long userId, long pageNum, long pageSize, Integer status);

    /** 模拟支付：待支付 -> 已支付（真正扣减库存） */
    void pay(Long userId, Long orderId);

    /** 用户取消：待支付 -> 已关闭（释放锁定库存） */
    void cancel(Long userId, Long orderId);

    /** 确认收货：已发货 -> 已完成 */
    void confirm(Long userId, Long orderId);

    // ---------------------------------------------------------------- 后台
    PageResult<OrderVO> pageForAdmin(long pageNum, long pageSize, Integer status, String keyword);

    OrderVO detailForAdmin(Long orderId);

    /** 发货：已支付 -> 已发货 */
    void ship(Long orderId);

    /** 后台关闭：待支付 -> 已关闭（释放锁定库存） */
    void close(Long orderId);

    /** 超时关单：把 expire_time 已过且仍待支付的订单关掉并释放库存，返回处理条数 */
    int closeExpiredOrders(int limit);

    /**
     * 支付成功后落账：订单 待支付 -> 已支付，扣减库存、累加销量。
     *
     * <p>由支付模块在「渠道回调 / 模拟支付成功」时调用。与 {@link #pay} 的区别是**幂等** ——
     * 渠道会重试回调，重复调用不会重复扣库存。
     */
    void markPaid(Long orderId, String channel, String paymentNo);
}
