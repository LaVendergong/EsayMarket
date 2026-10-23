package com.mall.core.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class LoginVO {

    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private Integer role;

    /** 后台角色 code（仅 role=1 时有值），如 SUPER_ADMIN */
    private List<String> roles;

    /** 权限码（仅 role=1 时有值），前端按它控制菜单与按钮 */
    private List<String> permissions;

    /** 0 = 平台方（跨租户），>0 = 某商户 */
    private Long tenantId;
}
