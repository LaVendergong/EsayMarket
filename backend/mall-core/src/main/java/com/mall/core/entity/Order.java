package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import com.mall.core.crypto.EncryptedStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
// autoResultMap 必开，否则查询结果不会被 typeHandler 解密（见 User 上的说明）
@TableName(value = "t_order", autoResultMap = true)
public class Order extends BaseEntity {

    private String orderNo;

    /** 下单幂等键：由客户端在一次结算里固定不变地传入，(user_id, request_id) 上有唯一键 */
    private String requestId;

    private Long userId;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    /** 见 com.mall.core.enums.OrderStatus */
    private Integer status;

    /** PII：收货人快照（订单落库时从地址复制，之后地址改了也不影响历史订单） */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String receiver;

    /** PII：联系电话快照 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String receiverPhone;

    /** PII：完整地址快照（省市区 + 门牌拼起来的，整体加密） */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String receiverAddr;

    private LocalDateTime payTime;

    /** 未支付订单的关单时间点（超时关单定时任务扫描用） */
    private LocalDateTime expireTime;
}
