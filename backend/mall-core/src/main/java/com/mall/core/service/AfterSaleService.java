package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.AfterSaleApplyDTO;
import com.mall.core.model.vo.AfterSaleVO;

public interface AfterSaleService {

    /** 用户申请售后，返回售后单 id */
    Long apply(Long userId, AfterSaleApplyDTO dto);

    /** 我的售后列表 */
    PageResult<AfterSaleVO> pageForUser(Long userId, long pageNum, long pageSize);

    /** 售后详情（只能看自己的） */
    AfterSaleVO detailForUser(Long userId, Long id);

    /** 用户撤销（仅待处理） */
    void cancel(Long userId, Long id);

    /** 某笔订单最近一条售后单（订单详情页用来展示入口/状态），没有返回 null */
    AfterSaleVO latestOfOrder(Long userId, Long orderId);

    // ---------------------------------------------------------------- 后台

    PageResult<AfterSaleVO> pageForAdmin(long pageNum, long pageSize, Integer status);

    AfterSaleVO detailForAdmin(Long id);

    /** 同意并退款（退货退款会把库存加回去） */
    void approve(Long id, String remark);

    /** 拒绝（订单状态恢复到申请前） */
    void reject(Long id, String remark);
}
