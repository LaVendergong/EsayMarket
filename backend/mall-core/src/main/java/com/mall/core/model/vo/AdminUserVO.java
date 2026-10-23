package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "后台管理员（含角色）")
public class AdminUserVO {

    private Long id;

    private String username;

    private String nickname;

    private Integer status;

    private LocalDateTime createTime;

    /** 角色 id 列表，用于分配弹窗回显 */
    private List<Long> roleIds;

    /** 角色名，用于列表展示 */
    private String roleNames;
}
