package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {

    private String username;

    private String password;

    private String nickname;

    private String phone;

    private String avatar;

    /** 0-普通用户 1-管理员 */
    private Integer role;

    /** 0-禁用 1-正常 */
    private Integer status;
}
