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
 * 卡密池的一条记录。
 *
 * <p>库存口径：导入 N 张时 {@code t_sku.stock += N}，下单走常规锁库存，
 * 支付后从池子里条件 UPDATE 抽一张（0 可用 → 1 已售出）。卡号/卡密加密存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_card_key", autoResultMap = true)
public class CardKey extends BaseEntity {

    private Long productId;

    private Long skuId;

    /** 卡号（AES-256-GCM 密文） */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String cardNo;

    /** 卡密（AES-256-GCM 密文） */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String cardSecret;

    /** 0 可用 1 已售出 2 已作废（退款/泄露） */
    private Integer status;

    private Long orderId;

    private Long orderItemId;

    private LocalDateTime soldTime;
}
