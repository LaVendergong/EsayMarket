package com.mall.core.pricing;

import com.mall.common.exception.BusinessException;
import com.mall.core.entity.Coupon;
import com.mall.core.entity.FreightTemplate;
import com.mall.core.entity.UserCoupon;
import com.mall.core.mapper.CouponMapper;
import com.mall.core.mapper.FreightTemplateMapper;
import com.mall.core.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 计价与分摊。
 *
 * <p>这里盯的是"钱不能少一分也不能多一分"：
 * <ul>
 *   <li>各行分摊之和必须**精确等于**订单优惠（差一分，退款与对账就永远对不平）</li>
 *   <li>任何一行分摊不能超过该行金额（否则会出现负价行）</li>
 *   <li>券只能减商品金额，不能把运费也减掉</li>
 * </ul>
 */
class PricingServiceTest {

    private FreightTemplateMapper freightMapper;
    private UserCouponMapper userCouponMapper;
    private CouponMapper couponMapper;
    private PricingServiceImpl service;

    @BeforeEach
    void setUp() {
        freightMapper = mock(FreightTemplateMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        couponMapper = mock(CouponMapper.class);
        service = new PricingServiceImpl(freightMapper, userCouponMapper, couponMapper);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // ---------------------------------------------------------------- 分摊算法

    @Test
    void allocate_sumsExactlyToDiscount() {
        List<BigDecimal> lines = List.of(d("33.33"), d("33.33"), d("33.34"));

        List<BigDecimal> shares = PricingService.allocate(d("10.00"), lines);

        BigDecimal sum = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10.00");
    }

    @Test
    void allocate_remainderGoesToLargestRemainder() {
        // 1.00 分到三个 3.00 的行上：每行 0.3333…，余数一样大，按顺序补，保证和恰好为 1.00
        List<BigDecimal> shares = PricingService.allocate(d("1.00"),
                List.of(d("3.00"), d("3.00"), d("3.00")));

        BigDecimal sum = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1.00");
        assertThat(shares).allSatisfy(s -> assertThat(s).isBetween(d("0.33"), d("0.34")));
    }

    @Test
    void allocate_neverExceedsLineAmount() {
        List<BigDecimal> shares = PricingService.allocate(d("100.00"),
                List.of(d("10.00"), d("0.01"), d("5.00")));

        assertThat(shares.get(0)).isEqualByComparingTo("10.00");
        assertThat(shares.get(1)).isEqualByComparingTo("0.01");
        assertThat(shares.get(2)).isEqualByComparingTo("5.00");
    }

    @Test
    void allocate_zeroDiscountGivesZeros() {
        List<BigDecimal> shares = PricingService.allocate(BigDecimal.ZERO, List.of(d("10.00"), d("20.00")));
        assertThat(shares).allSatisfy(s -> assertThat(s).isEqualByComparingTo("0.00"));
    }

    // ---------------------------------------------------------------- 运费

    private PricingLine line(String price, int qty, Long templateId) {
        return new PricingLine(1L, 1L, qty, d(price), templateId);
    }

    @Test
    void freight_freeOverChargesBaseOnlyBelowThreshold() {
        FreightTemplate t = new FreightTemplate();
        t.setId(9L);
        t.setType("FREE_OVER");
        t.setBaseFee(d("10.00"));
        t.setFreeThreshold(d("99.00"));
        t.setStatus(1);
        when(freightMapper.selectBatchIds(any())).thenReturn(List.of(t));

        // 60 元：不满 99，收 10 元运费
        PricingResult below = service.calculate(List.of(line("60.00", 1, 9L)), 1L, null);
        assertThat(below.getFreightAmount()).isEqualByComparingTo("10.00");
        assertThat(below.getPayAmount()).isEqualByComparingTo("70.00");

        // 120 元：满 99，包邮
        PricingResult above = service.calculate(List.of(line("120.00", 1, 9L)), 1L, null);
        assertThat(above.getFreightAmount()).isEqualByComparingTo("0.00");
        assertThat(above.getPayAmount()).isEqualByComparingTo("120.00");
    }

    @Test
    void freight_noTemplateMeansFreeShipping() {
        PricingResult r = service.calculate(List.of(line("50.00", 2, null)), 1L, null);
        assertThat(r.getFreightAmount()).isEqualByComparingTo("0.00");
        assertThat(r.getPayAmount()).isEqualByComparingTo("100.00");
    }

    // ---------------------------------------------------------------- 优惠券

    private void stubCoupon(String threshold, String amount, int status) {
        UserCoupon uc = new UserCoupon();
        uc.setId(5L);
        uc.setUserId(1L);
        uc.setCouponId(7L);
        uc.setStatus(status);
        when(userCouponMapper.selectOne(any())).thenReturn(uc);

        Coupon c = new Coupon();
        c.setId(7L);
        c.setName("满100减20");
        c.setThreshold(d(threshold));
        c.setAmount(d(amount));
        c.setStatus(1);
        when(couponMapper.selectById(7L)).thenReturn(c);
    }

    @Test
    void coupon_discountAppliesAndIsAllocated() {
        stubCoupon("100.00", "20.00", 0);

        PricingResult r = service.calculate(
                List.of(line("60.00", 1, null), line("40.00", 1, null)), 1L, 5L);

        assertThat(r.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(r.getPayAmount()).isEqualByComparingTo("80.00");
        // 回填的必须是用户券 id（5），不是券模板 id（7）：下单拿它去锁券，用错就锁不到
        assertThat(r.getUserCouponId()).isEqualTo(5L);
        // 60:40 分摊 → 12 / 8
        assertThat(r.getLines().get(0).getDiscountAmount()).isEqualByComparingTo("12.00");
        assertThat(r.getLines().get(1).getDiscountAmount()).isEqualByComparingTo("8.00");
    }

    @Test
    void coupon_belowThresholdIsRejected() {
        stubCoupon("100.00", "20.00", 0);

        assertThatThrownBy(() -> service.calculate(List.of(line("60.00", 1, null)), 1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("满 100 元可用");
    }

    @Test
    void coupon_alreadyUsedIsRejected() {
        stubCoupon("100.00", "20.00", 1);

        assertThatThrownBy(() -> service.calculate(List.of(line("120.00", 1, null)), 1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("优惠券");
    }

    @Test
    void coupon_biggerThanGoodsNeverMakesPayNegative() {
        stubCoupon("0.00", "50.00", 0);

        // 商品 30 + 运费 10，券 50：只能减到商品金额 30，运费照收 → 应付 10
        FreightTemplate t = new FreightTemplate();
        t.setId(3L);
        t.setType("FIXED");
        t.setBaseFee(d("10.00"));
        t.setStatus(1);
        when(freightMapper.selectBatchIds(any())).thenReturn(List.of(t));

        PricingResult r = service.calculate(List.of(line("30.00", 1, 3L)), 1L, 5L);

        assertThat(r.getDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(r.getPayAmount()).isEqualByComparingTo("10.00");
        // 关键：券不能把运费也抵掉
        assertThat(r.getPayAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
