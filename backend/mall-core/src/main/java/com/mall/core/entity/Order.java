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

    /** 运费（按运费模板分组计算后求和） */
    private BigDecimal freightAmount;

    /** 优惠金额（券） */
    private BigDecimal discountAmount;

    /** 使用的用户券 id */
    private Long userCouponId;

    /** 见 com.mall.core.enums.OrderStatus */
    private Integer status;

    /** 1 实物 2 虚拟：下单时按商品类型快照（虚拟订单不要地址、支付即发放并直接完成） */
    private Integer orderType;

    /** 快递公司 code（见 ExpressCompany），发货时写入 */
    private String shipCompany;

    /** 运单号 */
    private String shipNo;

    private LocalDateTime shipTime;

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
