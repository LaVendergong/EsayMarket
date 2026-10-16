package com.mall.core.model.dto;

import lombok.Data;

@Data
public class CreateOrderDTO {

    /** 收货地址 ID；不传则用该用户的默认地址 */
    private Long addressId;
}
