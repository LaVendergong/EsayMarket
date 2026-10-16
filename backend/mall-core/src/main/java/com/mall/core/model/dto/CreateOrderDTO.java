package com.mall.core.model.dto;

import lombok.Data;

@Data
public class CreateOrderDTO {

    /** 收货地址 ID；不传则用该用户的默认地址 */
    private Long addressId;

    /**
     * 下单幂等键（客户端生成，建议 UUID，最长 64 字符）。
     * 同一次结算里保持不变地传同一个值，重复提交（双击 / 重试）只会落一单并返回原单；
     * 不传则退化为普通下单（仅靠购物车行锁避免并发重复）。
     */
    private String requestId;
}
