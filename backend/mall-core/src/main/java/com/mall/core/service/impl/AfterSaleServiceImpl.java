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
import com.mall.core.enums.RefundStatus;
import com.mall.core.mapper.AfterSaleMapper;
import com.mall.core.mapper.OrderItemMapper;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.mapper.SkuMapper;
import com.mall.core.model.dto.AfterSaleApplyDTO;
import com.mall.core.model.vo.AfterSaleVO;
import com.mall.core.model.vo.OrderItemVO;
import com.mall.core.pay.PayModels;
import com.mall.core.service.AfterSaleService;
import com.mall.core.service.DigitalGoodsService;
import com.mall.core.service.PaymentService;
import com.mall.core.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AfterSaleMapper afterSaleMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final PaymentService paymentService;
    private final DigitalGoodsService digitalGoodsService;
    private final ReviewService reviewService;
    /** 审批的最后一步（售后单 + 订单 + 库存）要用事务包住，但退款那次外部调用不能包在里面 */
    private final TransactionTemplate transactionTemplate;

    public AfterSaleServiceImpl(AfterSaleMapper afterSaleMapper,
                                OrderMapper orderMapper,
                                OrderItemMapper orderItemMapper,
                                SkuMapper skuMapper,
                                ProductMapper productMapper,
                                PaymentService paymentService,
                                DigitalGoodsService digitalGoodsService,
                                ReviewService reviewService,
                                PlatformTransactionManager transactionManager) {
        this.afterSaleMapper = afterSaleMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.skuMapper = skuMapper;
        this.productMapper = productMapper;
        this.paymentService = paymentService;
        this.digitalGoodsService = digitalGoodsService;
        this.reviewService = reviewService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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

        // 虚拟商品：只允许仅退款，且卡密不能被查看过（看过了退不了，否则等于白送）
        digitalGoodsService.assertRefundable(order, dto.getType());

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

    /**
     * 同意售后 = **先把钱真退回去，再改状态**。
     *
     * <p>顺序不能反：如果先标「已退款」再调渠道，一旦渠道失败，用户看到的是"已退款"但没收到钱，
     * 这是最容易变成投诉/资金差错的一种顺序。
     *
     * <p>两个刻意的设计：
     * <ul>
     *   <li><b>方法本身不开事务</b>：调用渠道是外部 IO，把它包在事务里会让数据库连接被占住十几秒；
     *       而且退款失败时我们需要把失败原因**提交**下来（否则抛异常会把记录一起回滚掉，客服什么也看不到）。</li>
     *   <li><b>退款单号由售后单号派生</b>（{@code RF + afterSaleNo}）：失败后重试复用同一个号，
     *       支付宝/微信按它幂等，绝不会重复退钱。</li>
     * </ul>
     */
    @Override
    public void approve(Long id, String remark) {
        AfterSale as = require(id);
        if (!Objects.equals(as.getStatus(), AfterSaleStatus.PENDING.getCode())) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }

        // 1) 真退款（已经退成功过的重试直接跳过，避免无谓的渠道调用）
        if (!Objects.equals(as.getRefundStatus(), RefundStatus.SUCCESS.getCode())) {
            String refundNo = refundNoOf(as);
            PayModels.RefundResult result;
            try {
                result = paymentService.refund(as.getOrderId(), refundNo, as.getRefundAmount(),
                        "售后退款 " + as.getAfterSaleNo());
            } catch (BusinessException e) {
                recordRefundFailure(as.getId(), e.getMessage());
                throw e;
            }
            if (!result.isSuccess()) {
                recordRefundFailure(as.getId(), "渠道未受理退款");
                throw new BusinessException(ResultCode.REFUND_FAILED, "渠道退款失败，稍后可重试（不会重复退款）");
            }
            recordRefundSuccess(as.getId(), refundNo, result.getChannel(), result.isPending());
        }

        // 2) 状态流转（放在事务里：售后单 + 订单 + 库存 必须一起成功）
        transactionTemplate.executeWithoutResult(status -> {
            // 影响 0 行 = 已被其他客服处理过
            if (markHandled(id, AfterSaleStatus.APPROVED, remark) == 0) {
                throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
            }
            // 订单：售后中 -> 已退款
            if (transitionOrder(as.getOrderId(), null,
                    OrderStatus.AFTER_SALE.getCode(), OrderStatus.REFUNDED.getCode()) == 0) {
                throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
            }
            List<OrderItem> items = itemsOf(as.getOrderId());
            // 退货退款：货退回仓库，库存加回去（仅退款不加，否则账面库存虚高会超卖）
            if (AfterSaleType.needsStockRestore(as.getType())) {
                for (OrderItem item : items) {
                    skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
                }
            }
            // 虚拟商品：凭证作废，已发出的卡密不回池（同一张卡被卖两次是最严重的事故）
            digitalGoodsService.revokeByOrder(as.getOrderId(),
                    items.stream().map(OrderItem::getId).toList());
            // 退款 = 交易取消：销量要退回去，否则商品列表的销量只增不减，和报表对不上
            for (OrderItem item : items) {
                productMapper.decrementSales(item.getProductId(), item.getQuantity());
            }
            // 评价保留（用户确实写过），但打上退款标记，不再计入商品评分
            reviewService.markRefundedByOrder(as.getOrderId());
        });

        log.info("[after-sale] approved no={} orderNo={} type={} amount={} refundNo={} channel={} stockRestored={}",
                as.getAfterSaleNo(), as.getOrderNo(), as.getType(), as.getRefundAmount(),
                refundNoOf(as), as.getRefundChannel(), AfterSaleType.needsStockRestore(as.getType()));
    }

    /** 退款单号：由售后单号派生，重试永远同一个号（渠道据此幂等） */
    private static String refundNoOf(AfterSale as) {
        return "RF" + as.getAfterSaleNo();
    }

    /** 退款失败要**提交**下来给客服看（所以不能放在会回滚的事务里） */
    private void recordRefundFailure(Long id, String reason) {
        AfterSale patch = new AfterSale();
        patch.setId(id);
        patch.setRefundStatus(RefundStatus.FAILED.getCode());
        patch.setRefundError(reason == null ? null : reason.substring(0, Math.min(reason.length(), 190)));
        afterSaleMapper.updateById(patch);
        log.warn("[after-sale] refund failed id={} reason={}", id, reason);
    }

    private void recordRefundSuccess(Long id, String refundNo, String channel, boolean pending) {
        AfterSale patch = new AfterSale();
        patch.setId(id);
        patch.setRefundNo(refundNo);
        patch.setRefundChannel(channel);
        patch.setRefundStatus(pending ? RefundStatus.PROCESSING.getCode() : RefundStatus.SUCCESS.getCode());
        patch.setRefundTime(LocalDateTime.now());
        patch.setRefundError(null);
        afterSaleMapper.updateById(patch);
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
