package com.mall.core.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 金额分摊（最大余额法）。
 *
 * <p>为什么必须分摊：订单级优惠如果不落到每一行，"退其中一件该退多少钱"永远算不清。
 * 而按比例分摊会出现小数，四舍五入会让各行之和 ≠ 订单优惠——差一分钱，对账就永远对不平。
 *
 * <p>做法：全部换成「分」用整数算，先按比例取整，剩下的零头按余数从大到小一分一分补，
 * 保证 {@code Σ 每行优惠 == 订单优惠}。
 */
final class PricingSupport {

    private PricingSupport() {
    }

    static List<BigDecimal> allocate(BigDecimal totalDiscount, List<BigDecimal> lineAmounts) {
        int n = lineAmounts == null ? 0 : lineAmounts.size();
        List<BigDecimal> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        if (n == 0) {
            return result;
        }

        long totalFen = 0L;
        long[] lineFen = new long[n];
        for (int i = 0; i < n; i++) {
            lineFen[i] = toFen(lineAmounts.get(i));
            totalFen += lineFen[i];
        }
        long discountFen = toFen(totalDiscount);
        if (discountFen <= 0 || totalFen <= 0) {
            return result;
        }
        // 优惠不能超过商品总额（券面额大于商品金额时只减到 0）
        discountFen = Math.min(discountFen, totalFen);

        long[] share = new long[n];
        long[] remainder = new long[n];
        long assigned = 0L;
        for (int i = 0; i < n; i++) {
            long numerator = Math.multiplyExact(lineFen[i], discountFen);
            share[i] = numerator / totalFen;
            remainder[i] = numerator % totalFen;
            assigned += share[i];
        }

        long left = discountFen - assigned;
        if (left > 0) {
            // 余数大的先补（一样大时金额大的先补），保证结果稳定可复现
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, Comparator
                    .comparingLong((Integer i) -> remainder[i]).reversed()
                    .thenComparing(Comparator.comparingLong((Integer i) -> lineFen[i]).reversed()));
            for (int k = 0; k < left; k++) {
                share[order[k % n]] += 1;
            }
        }

        for (int i = 0; i < n; i++) {
            result.set(i, fromFen(share[i]));
        }
        return result;
    }

    static long toFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    static BigDecimal fromFen(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }
}
