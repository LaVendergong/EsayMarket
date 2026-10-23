package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Order;
import com.mall.core.entity.OrderItem;
import com.mall.core.entity.Review;
import com.mall.core.entity.User;
import com.mall.core.enums.OrderStatus;
import com.mall.core.mapper.OrderItemMapper;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.ReviewMapper;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.dto.ReviewDTO;
import com.mall.core.model.vo.ReviewVO;
import com.mall.core.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private static final long MAX_PAGE_SIZE = 100L;
    private static final String ANONYMOUS_LABEL = "匿名用户";

    private final ReviewMapper reviewMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long userId, ReviewDTO dto) {
        // 前端只传 orderItemId：商品、订单、规格全部由服务端反查，避免伪造别人的订单来评价
        OrderItem item = orderItemMapper.selectById(dto.getOrderItemId());
        if (item == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "订单项不存在");
        }
        Order order = orderMapper.selectById(item.getOrderId());
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            // 不区分"不存在"和"不是你的"，避免枚举订单项
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.FINISHED.getCode())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "订单完成后才能评价");
        }
        Long exists = reviewMapper.selectCount(Wrappers.<Review>lambdaQuery()
                .eq(Review::getOrderItemId, item.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.REVIEW_ALREADY_SUBMITTED);
        }

        Review r = new Review();
        r.setOrderId(order.getId());
        r.setOrderItemId(item.getId());
        r.setUserId(userId);
        r.setProductId(item.getProductId());
        r.setSkuId(item.getSkuId());
        r.setRating(dto.getRating());
        r.setContent(StringUtils.hasText(dto.getContent()) ? dto.getContent().trim() : null);
        r.setAnonymous(Objects.equals(dto.getAnonymous(), 1) ? 1 : 0);
        List<String> images = normalizeImages(dto.getImages());
        r.setImages(images.isEmpty() ? null : String.join(",", images));
        r.setStatus(1);
        try {
            reviewMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一键是最后一道防线
            throw new BusinessException(ResultCode.REVIEW_ALREADY_SUBMITTED);
        }
        reviewMapper.refreshProductRating(item.getProductId());
    }

    @Override
    public PageResult<ReviewVO> pageForProduct(Long productId, long pageNum, long pageSize) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Review> page = reviewMapper.selectPage(new Page<>(pn, ps), Wrappers.<Review>lambdaQuery()
                .eq(Review::getProductId, productId)
                .eq(Review::getStatus, 1)
                .orderByDesc(Review::getId));
        return PageResult.of(page.getTotal(), pn, ps, toVO(page.getRecords()));
    }

    @Override
    public List<Long> reviewedItemIds(Long userId, Long orderId) {
        return reviewMapper.selectList(Wrappers.<Review>lambdaQuery()
                        .select(Review::getOrderItemId)
                        .eq(Review::getOrderId, orderId)
                        .eq(Review::getUserId, userId))
                .stream().map(Review::getOrderItemId).toList();
    }

    @Override
    public PageResult<ReviewVO> pageForAdmin(long pageNum, long pageSize, Long productId, Integer status) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Review> page = reviewMapper.selectPage(new Page<>(pn, ps), Wrappers.<Review>lambdaQuery()
                .eq(productId != null, Review::getProductId, productId)
                .eq(status != null, Review::getStatus, status)
                .orderByDesc(Review::getId));
        return PageResult.of(page.getTotal(), pn, ps, toVO(page.getRecords()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "status 只能是 0 或 1");
        }
        Review r = reviewMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ResultCode.REVIEW_NOT_FOUND);
        }
        Review upd = new Review();
        upd.setId(id);
        upd.setStatus(status);
        reviewMapper.updateById(upd);
        // 隐藏/恢复都要重算：否则列表页的评分和评价内容会对不上
        reviewMapper.refreshProductRating(r.getProductId());
    }

    /**
     * 只接受本站上传的图片地址（{@code /uploads/...}）。
     *
     * <p>不做这一步的话，评价里可以塞任意外链：广告、追踪像素、甚至伪装成图片的恶意链接。
     * 换对象存储后地址会带上 CDN 域名，所以判断用 {@code contains("/uploads/")} 而不是前缀比较。
     */
    private List<String> normalizeImages(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        for (String item : raw) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String url = item.trim();
            if (!url.contains("/uploads/")) {
                throw new BusinessException(ResultCode.UPLOAD_INVALID, "图片地址不合法");
            }
            if (!out.contains(url)) {
                out.add(url);
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static List<String> splitImages(String images) {
        if (!StringUtils.hasText(images)) {
            return List.of();
        }
        return Arrays.stream(images.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRefundedByOrder(Long orderId) {
        List<Review> affected = reviewMapper.selectList(Wrappers.<Review>lambdaQuery()
                .eq(Review::getOrderId, orderId)
                .eq(Review::getRefunded, 0));
        if (affected.isEmpty()) {
            return;
        }
        reviewMapper.markRefundedByOrder(orderId);
        // 评分口径变了（剔除 refunded），受影响的商品都要重算
        affected.stream().map(Review::getProductId).distinct()
                .forEach(reviewMapper::refreshProductRating);
    }

    /** 昵称和规格都取快照/批量查：列表里不能出现 N+1，也不能暴露用户 id */
    private List<ReviewVO> toVO(List<Review> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = list.stream().map(Review::getOrderItemId).collect(Collectors.toSet());
        Map<Long, OrderItem> items = orderItemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(OrderItem::getId, i -> i, (a, b) -> a));
        Set<Long> userIds = list.stream().map(Review::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<ReviewVO> out = new ArrayList<>(list.size());
        for (Review r : list) {
            ReviewVO vo = new ReviewVO();
            vo.setId(r.getId());
            vo.setOrderId(r.getOrderId());
            vo.setOrderItemId(r.getOrderItemId());
            vo.setProductId(r.getProductId());
            vo.setSkuId(r.getSkuId());
            vo.setRating(r.getRating());
            vo.setContent(r.getContent());
            vo.setImages(splitImages(r.getImages()));
            vo.setAnonymous(r.getAnonymous());
            vo.setStatus(r.getStatus());
            vo.setRefunded(r.getRefunded());
            vo.setCreateTime(r.getCreateTime());
            OrderItem item = items.get(r.getOrderItemId());
            if (item != null) {
                vo.setSpec(item.getSpec());
                vo.setProductName(item.getProductName());
            }
            if (Objects.equals(r.getAnonymous(), 1)) {
                vo.setUserLabel(ANONYMOUS_LABEL);
            } else {
                User u = users.get(r.getUserId());
                // 昵称可能为空，兜底成"用户"；绝不回退到 username/id
                vo.setUserLabel(u != null && StringUtils.hasText(u.getNickname()) ? u.getNickname() : "用户");
            }
            out.add(vo);
        }
        return out;
    }
}
