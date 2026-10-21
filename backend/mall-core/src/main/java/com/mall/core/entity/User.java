package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mall.core.base.BaseEntity;
import com.mall.core.crypto.EncryptedStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
// autoResultMap 必须为 true：否则 MyBatis-Plus 生成的查询不会带上字段的 typeHandler，
// 结果是「写进去是密文、读出来还是密文」。这个坑很容易漏（见 docs §8 安全说明）。
@TableName(value = "t_user", autoResultMap = true)
public class User extends BaseEntity {

    private String username;

    /**
     * BCrypt 哈希（不可逆）。
     *
     * <p>{@code @JsonIgnore}：任何接口都不该把它序列化出去 —— 现在所有接口都走 VO，
     * 这一层是为了防止将来有人图省事直接返回实体。
     * <p>{@code @ToString.Exclude}：避免 {@code log.info("{}", user)} 把哈希写进日志。
     */
    @JsonIgnore
    @ToString.Exclude
    private String password;

    private String nickname;

    /** PII：库里存 AES-256-GCM 密文，读写自动加解密；toString 排除，避免整对象打日志时泄露 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    @ToString.Exclude
    private String phone;

    private String avatar;

    /** 0-普通用户 1-管理员 */
    private Integer role;

    /** 0-禁用 1-正常 */
    private Integer status;
}
