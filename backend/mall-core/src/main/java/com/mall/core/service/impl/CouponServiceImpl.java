package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Coupon;
import com.mall.core.entity.User;
import com.mall.core.entity.UserCoupon;
import com.mall.core.enums.UserCouponStatus;
import com.mall.core.mapper.CouponMapper;
import com.mall.core.mapper.UserCouponMapper;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.dto.CouponDTO;
import com.mall.core.model.vo.CouponRecordVO;
import com.mall.core.model.vo.CouponVO;
import com.mall.core.model.vo.UserCouponVO;
import com.mall.core.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    /** 与其它后台分页一致的上限，避免 pageSize 传 100000 把库拖死 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final UserMapper userMapper;

    @Override
    public List<CouponVO> center(Long userId) {
        List<Coupon> coupons = couponMapper.selectList(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getStatus, 1)
                .orderByDesc(Coupon::getAmount));
        Set<Long> mine = new HashSet<>();
        if (userId != null && !coupons.isEmpty()) {
            userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                            .eq(UserCoupon::getUserId, userId))
                    .forEach(uc -> mine.add(uc.getCouponId()));
        }
        List<CouponVO> list = new ArrayList<>(coupons.size());
        for (Coupon c : coupons) {
            CouponVO vo = CouponVO.from(c);
            vo.setReceivedByMe(mine.contains(c.getId()));
            list.add(vo);
        }
        return list;
    }

    /**
     * 领取。
     *
     * <p>两处并发要防：
     * <ul>
     *   <li>同一用户并发领同一张 → 靠 {@code uk_user_coupon} 唯一键，捕获重复键即可</li>
     *   <li>库存并发超发 → 靠 {@code UPDATE ... WHERE received < total} 的影响行数</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long userId, Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }
        if (!Objects.equals(coupon.getStatus(), 1)) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE, "该券已停止发放");
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getValidTo() != null && now.isAfter(coupon.getValidTo())) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE, "该券已过期");
        }

        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        uc.setStatus(UserCouponStatus.UNUSED.getCode());
        uc.setReceiveTime(now);
        try {
            userCouponMapper.insert(uc);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.COUPON_ALREADY_RECEIVED);
        }

        // 库存：条件 UPDATE 兜底，超发直接回滚
        int n = couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
                .setSql("received = received + 1")
                .eq(Coupon::getId, couponId)
                .apply("received < total"));
        if (n == 0) {
            throw new BusinessException(ResultCode.COUPON_SOLD_OUT);
        }
        log.info("[coupon] user={} received coupon={}", userId, couponId);
    }

    @Override
    public List<UserCouponVO> mine(Long userId, Integer status) {
        List<UserCoupon> list = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(status != null, UserCoupon::getStatus, status)
                .orderByDesc(UserCoupon::getId));
        List<UserCouponVO> vos = toVO(list);

        // 兜底纠正：过期判断要读券模板的有效期，所以放在 toVO 之后。
        // 定时任务也会扫（expireOutdated），这里保证用户「当下看到的」状态一定是对的。
        LocalDateTime now = LocalDateTime.now();
        List<Long> outdated = new ArrayList<>();
        for (UserCouponVO vo : vos) {
            if (Objects.equals(vo.getStatus(), UserCouponStatus.UNUSED.getCode())
                    && vo.getValidTo() != null && now.isAfter(vo.getValidTo())) {
                vo.setStatus(UserCouponStatus.EXPIRED.getCode());
                vo.setStatusText(UserCouponStatus.textOf(UserCouponStatus.EXPIRED.getCode()));
                outdated.add(vo.getId());
            }
        }
        if (!outdated.isEmpty()) {
            userCouponMapper.markExpired(outdated);
        }
        return vos;
    }

    @Override
    public List<UserCouponVO> availableFor(Long userId, BigDecimal goodsAmount) {
        List<UserCouponVO> all = mine(userId, UserCouponStatus.UNUSED.getCode());
        LocalDateTime now = LocalDateTime.now();
        List<UserCouponVO> usable = new ArrayList<>();
        for (UserCouponVO vo : all) {
            // mine() 已把过期券纠正成 2，这里再挡一次：结算页不该出现"已过期"的选项
            if (!Objects.equals(vo.getStatus(), UserCouponStatus.UNUSED.getCode())) {
                continue;
            }
            boolean inWindow = (vo.getValidFrom() == null || !now.isBefore(vo.getValidFrom()))
                    && (vo.getValidTo() == null || !now.isAfter(vo.getValidTo()));
            boolean reachThreshold = goodsAmount != null && goodsAmount.compareTo(vo.getThreshold()) >= 0;
            vo.setUsable(inWindow && reachThreshold);
            usable.add(vo);
        }
        // 能用的排前面，其次按面额倒序
        usable.sort((a, b) -> {
            if (!Objects.equals(a.getUsable(), b.getUsable())) {
                return Boolean.TRUE.equals(a.getUsable()) ? -1 : 1;
            }
            return b.getAmount().compareTo(a.getAmount());
        });
        return usable;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lock(Long userId, Long userCouponId, Long orderId) {
        int n = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .set(UserCoupon::getStatus, UserCouponStatus.USED.getCode())
                .set(UserCoupon::getOrderId, orderId)
                .set(UserCoupon::getUseTime, LocalDateTime.now())
                .eq(UserCoupon::getId, userCouponId)
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, UserCouponStatus.UNUSED.getCode()));
        if (n == 0) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE, "优惠券已被使用，请重新选择");
        }
    }

    @Override
    public void releaseByOrder(Long orderId) {
        if (orderId == null) {
            return;
        }
        int n = userCouponMapper.update(null, Wrappers.<UserCoupon>lambdaUpdate()
                .set(UserCoupon::getStatus, UserCouponStatus.UNUSED.getCode())
                .set(UserCoupon::getOrderId, null)
                .set(UserCoupon::getUseTime, null)
                .eq(UserCoupon::getOrderId, orderId)
                .eq(UserCoupon::getStatus, UserCouponStatus.USED.getCode()));
        if (n > 0) {
            log.info("[coupon] released {} coupon(s) for order={}", n, orderId);
        }
    }

    // ------------------------------------------------------------------ 后台

    @Override
    public PageResult<CouponVO> pageForAdmin(long pageNum, long pageSize, Integer status, String keyword) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        Page<Coupon> page = couponMapper.selectPage(new Page<>(pn, ps), Wrappers.<Coupon>lambdaQuery()
                .eq(status != null, Coupon::getStatus, status)
                .like(kw != null, Coupon::getName, kw)
                .orderByDesc(Coupon::getId));
        List<CouponVO> list = page.getRecords().stream().map(CouponVO::from).toList();
        return PageResult.of(page.getTotal(), pn, ps, list);
    }

    @Override
    public Long create(CouponDTO dto) {
        checkWindow(dto);
        Coupon c = new Coupon();
        apply(c, dto);
        c.setReceived(0);
        couponMapper.insert(c);
        log.info("[coupon] admin created coupon={} name={}", c.getId(), c.getName());
        return c.getId();
    }

    @Override
    public void update(Long id, CouponDTO dto) {
        checkWindow(dto);
        Coupon c = requireCoupon(id);
        int received = c.getReceived() == null ? 0 : c.getReceived();
        if (dto.getTotal() < received) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "发放总量不能小于已领取数（" + received + "）");
        }
        apply(c, dto);
        // 用 updateEditable 而不是 updateById：后者跳过 null，清空失效时间不会生效
        couponMapper.updateEditable(c);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "status 只能是 0 或 1");
        }
        requireCoupon(id);
        Coupon upd = new Coupon();
        upd.setId(id);
        upd.setStatus(status);
        couponMapper.updateById(upd);
    }

    @Override
    public void delete(Long id) {
        requireCoupon(id);
        Long taken = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getCouponId, id));
        if (taken != null && taken > 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "已有 " + taken + " 位用户领取，只能停发不能删除");
        }
        couponMapper.deleteById(id);
    }

    @Override
    public int expireOutdated() {
        // 一条 SQL 搞定：把「未使用 + 模板已过期」的用户券批量置为已过期（条件 UPDATE，多实例重复执行安全）
        int n = userCouponMapper.expireByOutdatedTemplate();
        if (n > 0) {
            log.info("[coupon] expired {} user coupon(s)", n);
        }
        return n;
    }

    @Override
    public List<CouponRecordVO> records(Long couponId, int limit) {
        requireCoupon(couponId);
        List<UserCoupon> list = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getCouponId, couponId)
                .orderByDesc(UserCoupon::getId)
                // 券可能被几千人领过，后台只看最近的一批；上限写死，避免 pageSize 传大了拖垮库
                .last("limit " + Math.min(Math.max(limit, 1), 500)));
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = list.stream().map(UserCoupon::getUserId).collect(Collectors.toSet());
        // 一次批量查用户名，别在循环里 N+1
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<CouponRecordVO> out = new ArrayList<>(list.size());
        for (UserCoupon uc : list) {
            CouponRecordVO vo = new CouponRecordVO();
            vo.setId(uc.getId());
            vo.setUserId(uc.getUserId());
            User u = users.get(uc.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setNickname(u.getNickname());
            }
            vo.setStatus(uc.getStatus());
            vo.setStatusText(UserCouponStatus.textOf(uc.getStatus()));
            vo.setReceiveTime(uc.getReceiveTime());
            vo.setUseTime(uc.getUseTime());
            vo.setOrderId(uc.getOrderId());
            out.add(vo);
        }
        return out;
    }

    private Coupon requireCoupon(Long id) {
        Coupon c = couponMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }
        return c;
    }

    private void apply(Coupon c, CouponDTO dto) {
        c.setName(dto.getName().trim());
        c.setThreshold(dto.getThreshold());
        c.setAmount(dto.getAmount());
        c.setTotal(dto.getTotal());
        c.setValidFrom(dto.getValidFrom());
        c.setValidTo(dto.getValidTo());
        c.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }

    /** 生效时间必须早于失效时间，否则这张券永远领不到也用不了 */
    private void checkWindow(CouponDTO dto) {
        if (dto.getValidFrom() != null && dto.getValidTo() != null
                && !dto.getValidFrom().isBefore(dto.getValidTo())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "生效时间必须早于失效时间");
        }
    }

    private List<UserCouponVO> toVO(List<UserCoupon> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        list.forEach(uc -> ids.add(uc.getCouponId()));
        var coupons = couponMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Coupon::getId, c -> c, (a, b) -> a));

        List<UserCouponVO> out = new ArrayList<>(list.size());
        for (UserCoupon uc : list) {
            Coupon c = coupons.get(uc.getCouponId());
            if (c == null) {
                continue;
            }
            out.add(UserCouponVO.of(uc.getId(), uc.getCouponId(), c.getName(), c.getThreshold(),
                    c.getAmount(), uc.getStatus(), uc.getOrderId(), c.getValidFrom(), c.getValidTo(),
                    uc.getReceiveTime(), uc.getUseTime()));
        }
        return out;
    }
}
