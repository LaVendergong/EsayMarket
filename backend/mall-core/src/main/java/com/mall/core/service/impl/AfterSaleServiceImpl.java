package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.AfterSale;
import com.mall.core.entity.Order;
import com.mall.core.entity.OrderItem;
import com.mall.core.enums.AfterSaleStatus;
import com.mall.core.enums.AfterSaleType;
import com.mall.core.enums.OrderStatus;
import com.mall.core.mapper.AfterSaleMapper;
import com.mall.core.mapper.OrderItemMapper;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.dto.AfterSaleApplyDTO;
import com.mall.core.model.vo.AfterSaleVO;
import com.mall.core.model.vo.OrderItemVO;
import com.mall.core.service.AfterSaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 售后的状态机实现。
 *
 * <p>整个流程只有四个动作：申请、撤销、同意、拒绝。四条不变式，全部靠
 * <b>带状态条件的 UPDATE + 判断影响行数</b>来保证（不是「先查再改」，那样并发下会重复处理）：
 *
 * <ol>
 *   <li>订单只有在 已支付/已发货/已完成 时才能申请售后</li>
 *   <li>同一时刻一笔订单只能有一个进行中的售后单 —— 申请时会先把订单推进到「售后中」，
 *       并发第二个申请影响 0 行，直接报 3005</li>
 *   <li>售后单只有 {@code PENDING} 能被处理，处理过的一律 3004（重复点「同意」不会重复退款）</li>
 *   <li>拒绝或撤销必须把订单状态<b>退回申请前的状态</b>，否则订单会永远卡在「售后中」</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AfterSaleMapper afterSaleMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;

    // ================================================================ 用户侧

    @Override
    @Transactional
    public Long apply(Long userId, AfterSaleApplyDTO dto) {
        if (!AfterSaleType.isValid(dto.getType())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "售后类型不正确");
        }
        Order order = orderMapper.selectById(dto.getOrderId());
        // 不是自己的订单一律按「不存在」处理，不泄露别人的订单是否存在
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        int before = order.getStatus() == null ? -1 : order.getStatus();
        if (before != OrderStatus.PAID.getCode()
                && before != OrderStatus.SHIPPED.getCode()
                && before != OrderStatus.FINISHED.getCode()) {
            // 待支付先付款；已关闭/已退款/售后中 都不该再申请
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_ALLOWED,
                    "当前订单状态（" + OrderStatus.textOf(before) + "）不支持申请售后");
        }

        // 抢单：把订单从 before 推进到「售后中」。影响 0 行说明刚被别人改过（重复申请/后台关单）
        if (transitionOrder(order.getId(), userId, before, OrderStatus.AFTER_SALE.getCode()) == 0) {
            throw new BusinessException(ResultCode.AFTER_SALE_EXISTS);
        }

        AfterSale row = new AfterSale();
        row.setAfterSaleNo(newAfterSaleNo());
        row.setOrderId(order.getId());
        row.setOrderNo(order.getOrderNo());
        row.setUserId(userId);
        row.setType(dto.getType());
        row.setStatus(AfterSaleStatus.PENDING.getCode());
        row.setReason(dto.getReason().trim());
        row.setDescription(trimToNull(dto.getDescription()));
        // 退款金额取「下单时实付」的快照，避免订单金额之后变动导致退款对不上账
        row.setRefundAmount(order.getPayAmount());
        row.setOrderStatusBefore(before);
        row.setApplyTime(LocalDateTime.now());
        afterSaleMapper.insert(row);

        log.info("[after-sale] applied no={} orderNo={} type={} amount={}",
                row.getAfterSaleNo(), row.getOrderNo(), row.getType(), row.getRefundAmount());
        return row.getId();
    }

    @Override
    public PageResult<AfterSaleVO> pageForUser(Long userId, long pageNum, long pageSize) {
        return page(Wrappers.<AfterSale>lambdaQuery()
                .eq(AfterSale::getUserId, userId)
                .orderByDesc(AfterSale::getId), pageNum, pageSize);
    }

    @Override
    public AfterSaleVO detailForUser(Long userId, Long id) {
        return toDetail(requireOwned(userId, id));
    }

    @Override
    @Transactional
    public void cancel(Long userId, Long id) {
        AfterSale as = requireOwned(userId, id);
        if (!Objects.equals(as.getStatus(), AfterSaleStatus.PENDING.getCode())) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }
        if (markHandled(as.getId(), AfterSaleStatus.CANCELED, null) == 0) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }
        restoreOrder(as);
        log.info("[after-sale] canceled by user no={}", as.getAfterSaleNo());
    }

    @Override
    public AfterSaleVO latestOfOrder(Long userId, Long orderId) {
        AfterSale as = afterSaleMapper.selectOne(Wrappers.<AfterSale>lambdaQuery()
                .eq(AfterSale::getOrderId, orderId)
                .eq(AfterSale::getUserId, userId)
                .orderByDesc(AfterSale::getId)
                .last("LIMIT 1"));
        return as == null ? null : AfterSaleVO.from(as);
    }

    // ================================================================ 后台侧

    @Override
    public PageResult<AfterSaleVO> pageForAdmin(long pageNum, long pageSize, Integer status) {
        var q = Wrappers.<AfterSale>lambdaQuery().orderByDesc(AfterSale::getId);
        if (status != null) {
            q.eq(AfterSale::getStatus, status);
        }
        return page(q, pageNum, pageSize);
    }

    @Override
    public AfterSaleVO detailForAdmin(Long id) {
        return toDetail(require(id));
    }

    @Override
    @Transactional
    public void approve(Long id, String remark) {
        AfterSale as = require(id);
        if (!Objects.equals(as.getStatus(), AfterSaleStatus.PENDING.getCode())) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }
        // 先改售后单：影响 0 行 = 已被其他客服处理过，直接拒绝，避免重复退款
        if (markHandled(id, AfterSaleStatus.APPROVED, remark) == 0) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }
        // 订单：售后中 -> 已退款
        if (transitionOrder(as.getOrderId(), null, OrderStatus.AFTER_SALE.getCode(), OrderStatus.REFUNDED.getCode()) == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
        // 退货退款：货退回仓库，库存加回去（仅退款不加，否则账面库存虚高会超卖）
        if (AfterSaleType.needsStockRestore(as.getType())) {
            for (OrderItem item : itemsOf(as.getOrderId())) {
                skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            }
        }
        log.info("[after-sale] approved no={} orderNo={} type={} amount={} stockRestored={}",
                as.getAfterSaleNo(), as.getOrderNo(), as.getType(), as.getRefundAmount(),
                AfterSaleType.needsStockRestore(as.getType()));
    }

    @Override
    @Transactional
    public void reject(Long id, String remark) {
        AfterSale as = require(id);
        if (!Objects.equals(as.getStatus(), AfterSaleStatus.PENDING.getCode())) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }
        if (markHandled(id, AfterSaleStatus.REJECTED, remark) == 0) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }
        restoreOrder(as);
        log.info("[after-sale] rejected no={} orderNo={}", as.getAfterSaleNo(), as.getOrderNo());
    }

    // ================================================================ 内部

    /** 订单状态流转：带 from 条件的 UPDATE，返回 0 表示当前状态已不是 from */
    private int transitionOrder(Long orderId, Long userId, int from, int to) {
        Order patch = new Order();
        patch.setStatus(to);
        LambdaUpdateWrapper<Order> w = Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, from);
        if (userId != null) {
            w.eq(Order::getUserId, userId);
        }
        return orderMapper.update(patch, w);
    }

    /** 把售后单从「待处理」推进到终态；已经处理过返回 0 */
    private int markHandled(Long id, AfterSaleStatus to, String remark) {
        AfterSale patch = new AfterSale();
        patch.setStatus(to.getCode());
        patch.setHandleTime(LocalDateTime.now());
        patch.setHandleRemark(trimToNull(remark));
        return afterSaleMapper.update(patch, Wrappers.<AfterSale>lambdaUpdate()
                .eq(AfterSale::getId, id)
                .eq(AfterSale::getStatus, AfterSaleStatus.PENDING.getCode()));
    }

    /** 拒绝/撤销后把订单退回申请前的状态 */
    private void restoreOrder(AfterSale as) {
        int n = transitionOrder(as.getOrderId(), null, OrderStatus.AFTER_SALE.getCode(), as.getOrderStatusBefore());
        if (n == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR,
                    "订单状态已被其它操作改变，无法恢复");
        }
    }

    private PageResult<AfterSaleVO> page(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AfterSale> q,
                                         long pageNum, long pageSize) {
        IPage<AfterSale> page = afterSaleMapper.selectPage(new Page<>(pageNum, pageSize), q);
        List<AfterSaleVO> list = page.getRecords().stream().map(AfterSaleVO::from).toList();
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }

    /** 详情里补上订单商品，前端不用再请求一次订单接口 */
    private AfterSaleVO toDetail(AfterSale as) {
        AfterSaleVO vo = AfterSaleVO.from(as);
        vo.setItems(itemsOf(as.getOrderId()).stream().map(OrderItemVO::from).toList());
        return vo;
    }

    private List<OrderItem> itemsOf(Long orderId) {
        return orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, orderId));
    }

    private AfterSale require(Long id) {
        AfterSale as = afterSaleMapper.selectById(id);
        if (as == null) {
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_FOUND);
        }
        return as;
    }

    private AfterSale requireOwned(Long userId, Long id) {
        AfterSale as = require(id);
        if (!Objects.equals(as.getUserId(), userId)) {
            // 别人的售后单按不存在处理
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_FOUND);
        }
        return as;
    }

    private static String newAfterSaleNo() {
        return "AS" + NO_FORMAT.format(LocalDateTime.now())
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
