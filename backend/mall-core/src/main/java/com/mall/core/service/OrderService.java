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

    /** 结算试算：金额明细 + 可用券 */
    com.mall.core.model.vo.OrderPreviewVO preview(Long userId, com.mall.core.model.dto.PreviewOrderDTO dto);

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

    /** 发货：已支付 -> 已发货，必须带快递公司与运单号（否则用户和客服都查不到物流） */
    void ship(Long orderId, String company, String shipNo);


    /** 后台关闭：待支付 -> 已关闭（释放锁定库存） */
    void close(Long orderId);

    /** 超时关单：把 expire_time 已过且仍待支付的订单关掉并释放库存，返回处理条数 */
    int closeExpiredOrders(int limit);

    /**
     * 导出订单为 CSV 文本（含 UTF-8 BOM，Excel 直接双击不乱码）。
     *
     * @param limit 最大行数，防止一次导出把内存打满
     */
    String exportCsv(Integer status, String keyword, int limit);

    /**
     * 后台补发虚拟商品（支付成功但发放失败时用）。
     *
     * <p>只在订单仍是「已支付」时可调用：发放成功会把订单推进到「已完成」，
     * 所以重复点击不会重复发卡。
     */
    void regrantDigital(Long orderId);

    /**
     * 支付成功后落账：订单 待支付 -> 已支付，扣减库存、累加销量。
     *
     * <p>由支付模块在「渠道回调 / 模拟支付成功」时调用。与 {@link #pay} 的区别是**幂等** ——
     * 渠道会重试回调，重复调用不会重复扣库存。
     */
    void markPaid(Long orderId, String channel, String paymentNo);
}
