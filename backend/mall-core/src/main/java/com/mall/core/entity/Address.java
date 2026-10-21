package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import com.mall.core.crypto.EncryptedStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
// autoResultMap 必开，否则查询结果不会被 typeHandler 解密（见 User 上的说明）
@TableName(value = "t_address", autoResultMap = true)
public class Address extends BaseEntity {

    private Long userId;

    /** PII：收货人姓名 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String receiver;

    /** PII：联系电话 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String phone;

    private String province;

    private String city;

    private String district;

    /** PII：详细门牌（省市区不算敏感，精确到门牌才是） */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String detail;

    /** 0-否 1-是 */
    private Integer isDefault;
}
