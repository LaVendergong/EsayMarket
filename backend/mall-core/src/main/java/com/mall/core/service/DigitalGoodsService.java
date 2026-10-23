package com.mall.core.service;

import com.mall.core.entity.Order;
import com.mall.core.entity.OrderItem;
import com.mall.core.model.vo.DigitalGrantVO;
import com.mall.core.model.vo.GrantContentVO;

import java.util.List;

/**
 * 虚拟商品的"发放"（相当于实物的发货）。
 *
 * <p>与实物的区别：没有物流、没有后台点发货这一步，支付成功就发；发放完订单直接置为已完成。
 */
public interface DigitalGoodsService {

    /**
     * 支付成功后发放。**不抛异常**：钱已经收了，发放失败不能让支付落账回滚，
     * 失败的记一条 status=9 的凭证，后台人工补发。
     *
     * @return 成功发放的条数
     */
    int grant(Order order, List<OrderItem> items);

    /** 某订单的凭证（不含明文内容） */
    List<DigitalGrantVO> byOrder(Long userId, Long orderId);

    /** 我的卡券（全部状态，作废的也展示，让用户知道原因） */
    List<DigitalGrantVO> mine(Long userId);

    /** 查看明文：校验归属、凭证可用，并累加 viewCount（售后据此判断能否退） */
    GrantContentVO reveal(Long userId, Long grantId);

    /** 退款后作废：卡密不回池（防重复售卖），外链凭证也标作废 */
    void revokeByOrder(Long orderId, List<Long> orderItemIds);

    /**
     * 导入卡密。每行 {@code 卡号,卡密}（逗号或 Tab 分隔，只有卡号也可以）。
     * 导入成功多少张就给 SKU 加多少库存 —— 池子就是库存来源。
     */
    int importCardKeys(Long skuId, List<String> lines);

    /** 某 SKU 剩余可用卡密数（后台核对用） */
    long availableCount(Long skuId);

    /**
     * 售后前置校验：虚拟订单只允许「仅退款」，且已经查看过的卡密不能自助退。
     *
     * <p>为什么"看过就不给退"：卡密一旦展示就无法收回，允许退款等于白送。
     * 实物订单直接放行（走原有规则）。
     */
    void assertRefundable(Order order, Integer afterSaleType);

    /** 对账：卡密池可用数与可售库存不一致的 SKU 数量（0 才算健康） */
    long checkStockConsistency();
}
