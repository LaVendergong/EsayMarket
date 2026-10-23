package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import com.mall.core.crypto.EncryptedStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 虚拟商品发放凭证（用户能看到的那张"卡"）。
 *
 * <p>为什么要单独一张表而不是直接查卡密池：外链类没有池子、卡密类和它字段又不同。
 * 统一到这里之后，「我的卡券 / 订单详情 / 查看审计 / 退款作废」都只处理一种数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_digital_grant", autoResultMap = true)
public class DigitalGrant extends BaseEntity {

    private Long orderId;

    private Long orderItemId;

    private Long userId;

    private Long productId;

    private Long skuId;

    /** 见 com.mall.core.enums.DeliveryType（1 卡密 2 外链） */
    private Integer deliveryType;

    /** 加密的 {@code {"no":"..","secret":".."}} 或 {@code {"url":".."}}；不加密等于把卡密写进库和备份 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String content;

    /** 1 已发放 2 已作废 9 发放失败（待人工） */
    private Integer status;

    /** 查看次数：>0 说明卡密已暴露，售后不再自动退款 */
    private Integer viewCount;

    private LocalDateTime grantTime;
}
