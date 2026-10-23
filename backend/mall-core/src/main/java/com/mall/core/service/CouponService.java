package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.CouponDTO;
import com.mall.core.model.vo.CouponRecordVO;
import com.mall.core.model.vo.CouponVO;
import com.mall.core.model.vo.UserCouponVO;

import java.math.BigDecimal;
import java.util.List;

public interface CouponService {

    /** 领券中心：可领的券 + 我是否已领 */
    List<CouponVO> center(Long userId);

    /** 领取一张券（每人限一张，靠唯一键兜底并发） */
    void receive(Long userId, Long couponId);

    /** 我的券；status 为 null 时返回全部 */
    List<UserCouponVO> mine(Long userId, Integer status);

    /** 结算页：当前商品金额下可用的券（已按优惠金额倒序） */
    List<UserCouponVO> availableFor(Long userId, BigDecimal goodsAmount);

    /**
     * 下单时锁定券（status 0 → 1）。
     *
     * <p>用条件 UPDATE 而不是"先查再改"：同一张券并发被两个订单使用，只有一个能成功。
     */
    void lock(Long userId, Long userCouponId, Long orderId);

    /** 取消/超时关单时退回券（status 1 → 0）；退款不退回（券视为已核销）。 */
    void releaseByOrder(Long orderId);

    /**
     * 把「券模板已过有效期、但用户券还是未使用」的脏数据置为已过期（status 0 → 2）。
     *
     * <p>为什么必须有：过期只在查券时判定的话，库里状态永远是 0，
     * 「我的券」会一直显示「未使用」，点去使用才被计价接口拒绝。返回纠正条数。
     */
    int expireOutdated();

    // ------------------------------------------------------------------ 后台

    /** 后台：券分页（含已停发的），keyword 匹配券名 */
    PageResult<CouponVO> pageForAdmin(long pageNum, long pageSize, Integer status, String keyword);

    /** 后台：新建券模板，返回 id */
    Long create(CouponDTO dto);

    /** 后台：修改券模板（发放总量不能小于已领取数） */
    void update(Long id, CouponDTO dto);

    /** 后台：停发/恢复 status=0|1。停发后已领到手的券仍可正常使用 */
    void updateStatus(Long id, Integer status);

    /** 后台：删除。已被领过的券只能停发，不能删（否则用户的券会凭空消失） */
    void delete(Long id);

    /** 后台：某张券的领取明细（最多 limit 条，按领取时间倒序） */
    List<CouponRecordVO> records(Long couponId, int limit);
}
