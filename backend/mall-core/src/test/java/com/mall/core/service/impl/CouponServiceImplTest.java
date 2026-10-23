package com.mall.core.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Coupon;
import com.mall.core.entity.UserCoupon;
import com.mall.core.enums.UserCouponStatus;
import com.mall.core.mapper.CouponMapper;
import com.mall.core.mapper.UserCouponMapper;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.dto.CouponDTO;
import com.mall.core.model.vo.UserCouponVO;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台券维护里「改错了会伤到已有用户」的分支：
 * 删掉已被领取的券、把总量改到比已领还少、时间窗写反。
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    @Mock private CouponMapper couponMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private UserMapper userMapper;

    private CouponServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CouponServiceImpl(couponMapper, userCouponMapper, userMapper);
    }

    private Coupon coupon(long id, int received) {
        Coupon c = new Coupon();
        c.setId(id);
        c.setName("满100减20");
        c.setThreshold(new BigDecimal("100.00"));
        c.setAmount(new BigDecimal("20.00"));
        c.setTotal(100);
        c.setReceived(received);
        c.setStatus(1);
        return c;
    }

    private CouponDTO dto(int total) {
        CouponDTO d = new CouponDTO();
        d.setName("满100减20");
        d.setThreshold(new BigDecimal("100.00"));
        d.setAmount(new BigDecimal("20.00"));
        d.setTotal(total);
        return d;
    }

    @Test
    void create_defaultsReceivedToZeroAndStatusToEnabled() {
        service.create(dto(100));

        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponMapper).insert(captor.capture());
        assertThat(captor.getValue().getReceived()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void create_withReversedValidWindow_isRejected() {
        CouponDTO d = dto(100);
        d.setValidFrom(LocalDateTime.now());
        d.setValidTo(LocalDateTime.now().minusDays(1));

        BusinessException e = catchThrowableOfType(() -> service.create(d), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verify(couponMapper, never()).insert(any(Coupon.class));
    }

    @Test
    void update_totalSmallerThanReceived_isRejected() {
        when(couponMapper.selectById(5L)).thenReturn(coupon(5L, 30));

        BusinessException e = catchThrowableOfType(() -> service.update(5L, dto(10)), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verify(couponMapper, never()).updateEditable(any(Coupon.class));
    }

    @Test
    void update_canClearValidity() {
        Coupon existing = coupon(5L, 3);
        existing.setValidTo(LocalDateTime.now().plusDays(3));
        when(couponMapper.selectById(5L)).thenReturn(existing);
        CouponDTO d = dto(100);

        service.update(5L, d);

        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponMapper).updateEditable(captor.capture());
        // 失效时间传 null = 改回长期有效，必须真的写回 NULL（updateById 会跳过 null）
        assertThat(captor.getValue().getValidTo()).isNull();
    }

    @Test
    void delete_whenSomeoneAlreadyReceived_isRejected() {
        when(couponMapper.selectById(5L)).thenReturn(coupon(5L, 3));
        when(userCouponMapper.selectCount(any())).thenReturn(3L);

        BusinessException e = catchThrowableOfType(() -> service.delete(5L), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verify(couponMapper, never()).deleteById(anyLong());
    }

    @Test
    void delete_whenNobodyReceived_removesIt() {
        when(couponMapper.selectById(5L)).thenReturn(coupon(5L, 0));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);

        service.delete(5L);

        verify(couponMapper).deleteById(5L);
    }

    // ------------------------------------------------------------------ 过期

    private UserCoupon unusedCoupon(long id, long couponId) {
        UserCoupon uc = new UserCoupon();
        uc.setId(id);
        uc.setUserId(1L);
        uc.setCouponId(couponId);
        uc.setStatus(0);
        return uc;
    }

    @Test
    void mine_reportsExpiredWhenTemplateValidityPassed() {
        when(userCouponMapper.selectList(any())).thenReturn(List.of(unusedCoupon(11L, 7L)));
        Coupon expired = coupon(7L, 0);
        expired.setValidTo(LocalDateTime.now().minusDays(1));
        when(couponMapper.selectBatchIds(any())).thenReturn(List.of(expired));

        List<UserCouponVO> vos = service.mine(1L, null);

        // 用户看到的必须是「已过期」，不能还是「未使用」——否则点去使用才被计价拒绝
        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getStatus()).isEqualTo(UserCouponStatus.EXPIRED.getCode());
        assertThat(vos.get(0).getStatusText()).isEqualTo("已过期");
        // 顺手把库里的脏状态纠正掉
        verify(userCouponMapper).markExpired(List.of(11L));
    }

    @Test
    void mine_keepsUnusedWhenStillInWindow() {
        when(userCouponMapper.selectList(any())).thenReturn(List.of(unusedCoupon(11L, 7L)));
        Coupon alive = coupon(7L, 0);
        alive.setValidTo(LocalDateTime.now().plusDays(1));
        when(couponMapper.selectBatchIds(any())).thenReturn(List.of(alive));

        List<UserCouponVO> vos = service.mine(1L, null);

        assertThat(vos.get(0).getStatus()).isEqualTo(UserCouponStatus.UNUSED.getCode());
        verify(userCouponMapper, never()).markExpired(any());
    }

    @Test
    void availableFor_hidesExpiredCoupons() {
        when(userCouponMapper.selectList(any())).thenReturn(List.of(unusedCoupon(11L, 7L)));
        Coupon expired = coupon(7L, 0);
        expired.setValidTo(LocalDateTime.now().minusDays(1));
        when(couponMapper.selectBatchIds(any())).thenReturn(List.of(expired));

        assertThat(service.availableFor(1L, new BigDecimal("200.00"))).isEmpty();
    }
}
