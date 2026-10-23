package com.mall.core.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Coupon;
import com.mall.core.entity.FreightTemplate;
import com.mall.core.entity.UserCoupon;
import com.mall.core.enums.FreightType;
import com.mall.core.enums.UserCouponStatus;
import com.mall.core.mapper.CouponMapper;
import com.mall.core.mapper.FreightTemplateMapper;
import com.mall.core.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 计价：商品金额 → 运费 → 优惠 → 应付，并把优惠分摊到每一行。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>金额只信服务端</b>：单价取库里的 SKU，前端传来的任何金额都不采信</li>
 *   <li><b>券只能减商品金额</b>，不含运费（否则"运费 10 元、券 20 元"会变成倒贴运费）</li>
 *   <li><b>分摊之和必须等于订单优惠</b>，一分不差（见 {@link PricingSupport}）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingServiceImpl implements PricingService {

    private final FreightTemplateMapper freightTemplateMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponMapper couponMapper;

    @Override
    public PricingResult calculate(List<PricingLine> lines, Long userId, Long userCouponId) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }

        BigDecimal goodsAmount = BigDecimal.ZERO;
        List<BigDecimal> lineAmounts = new ArrayList<>(lines.size());
        for (PricingLine line : lines) {
            BigDecimal amount = line.lineAmount();
            lineAmounts.add(amount);
            goodsAmount = goodsAmount.add(amount);
        }
        goodsAmount = goodsAmount.setScale(2, RoundingMode.HALF_UP);

        BigDecimal freightAmount = calcFreight(lines);

        BigDecimal discountAmount = BigDecimal.ZERO;
        Long appliedCouponId = null;
        if (userCouponId != null) {
            Coupon coupon = resolveCoupon(userId, userCouponId, goodsAmount);
            // 券最多减到 0，不会出现负数应付
            discountAmount = coupon.getAmount().min(goodsAmount).setScale(2, RoundingMode.HALF_UP);
            // 回填的必须是**用户券 id**（t_user_coupon.id），不是券模板 id：
            // 下单要拿它去 update t_user_coupon 做锁定，用模板 id 会锁到别人的券/锁不到。
            appliedCouponId = userCouponId;
        }

        BigDecimal payAmount = goodsAmount.add(freightAmount).subtract(discountAmount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        List<BigDecimal> shares = PricingSupport.allocate(discountAmount, lineAmounts);
        List<PricingResult.LineShare> lineShares = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            PricingLine line = lines.get(i);
            BigDecimal share = shares.get(i);
            lineShares.add(PricingResult.LineShare.builder()
                    .skuId(line.getSkuId())
                    .lineAmount(lineAmounts.get(i))
                    .discountAmount(share)
                    .payAmount(lineAmounts.get(i).subtract(share).setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        log.debug("[pricing] goods={} freight={} discount={} pay={} coupon={}",
                goodsAmount, freightAmount, discountAmount, payAmount, appliedCouponId);

        return PricingResult.builder()
                .goodsAmount(goodsAmount)
                .freightAmount(freightAmount)
                .discountAmount(discountAmount)
                .payAmount(payAmount)
                .userCouponId(appliedCouponId)
                .lines(lineShares)
                .build();
    }

    /**
     * 运费：**按模板分组**分别计算再求和。
     *
     * <p>为什么分组：不同商品可能用不同模板（比如大件 20 元、小件满 99 包邮），
     * 简单地对整单商品金额套一个模板，会把"满额包邮"算错。
     */
    private BigDecimal calcFreight(List<PricingLine> lines) {
        Map<Long, List<PricingLine>> groups = new LinkedHashMap<>();
        for (PricingLine line : lines) {
            // null 表示包邮，不参与分组
            if (line.getFreightTemplateId() != null) {
                groups.computeIfAbsent(line.getFreightTemplateId(), k -> new ArrayList<>()).add(line);
            }
        }
        if (groups.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        List<FreightTemplate> templates = freightTemplateMapper.selectBatchIds(groups.keySet());
        Map<Long, FreightTemplate> byId = templates.stream()
                .collect(Collectors.toMap(FreightTemplate::getId, t -> t, (a, b) -> a));

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, List<PricingLine>> entry : groups.entrySet()) {
            FreightTemplate template = byId.get(entry.getKey());
            if (template == null) {
                throw new BusinessException(ResultCode.FREIGHT_TEMPLATE_NOT_FOUND,
                        "商品绑定的运费模板不存在（id=" + entry.getKey() + "）");
            }
            if (Objects.equals(template.getStatus(), 0)) {
                continue;   // 停用的模板按包邮处理，不让用户为后台配置问题买单
            }
            BigDecimal groupAmount = entry.getValue().stream()
                    .map(PricingLine::lineAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            FreightType type = FreightType.of(template.getType());
            BigDecimal fee;
            if (type == FreightType.FREE_OVER && template.getFreeThreshold() != null
                    && groupAmount.compareTo(template.getFreeThreshold()) >= 0) {
                fee = BigDecimal.ZERO;
            } else {
                fee = template.getBaseFee() == null ? BigDecimal.ZERO : template.getBaseFee();
            }
            total = total.add(fee);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 校验券可用性并返回券模板；不可用直接抛业务异常（不静默忽略，否则用户会以为优惠生效了） */
    private Coupon resolveCoupon(Long userId, Long userCouponId, BigDecimal goodsAmount) {
        UserCoupon uc = userCouponMapper.selectOne(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getId, userCouponId)
                .eq(UserCoupon::getUserId, userId));
        if (uc == null) {
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }
        if (!Objects.equals(uc.getStatus(), UserCouponStatus.UNUSED.getCode())) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE,
                    "该优惠券" + UserCouponStatus.textOf(uc.getStatus()));
        }
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null) {
            throw new BusinessException(ResultCode.COUPON_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom())) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE, "该优惠券还未生效");
        }
        if (coupon.getValidTo() != null && now.isAfter(coupon.getValidTo())) {
            throw new BusinessException(ResultCode.COUPON_UNAVAILABLE, "该优惠券已过期");
        }
        if (goodsAmount.compareTo(coupon.getThreshold()) < 0) {
            throw new BusinessException(ResultCode.COUPON_THRESHOLD_NOT_MET,
                    "满 " + coupon.getThreshold().stripTrailingZeros().toPlainString() + " 元可用");
        }
        return coupon;
    }
}
