package com.mall.core.model.vo;

import com.mall.core.entity.User;
import lombok.Data;

@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private String avatar;
    private Integer role;

    public static UserVO from(User u) {
        if (u == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setNickname(u.getNickname());
        vo.setPhone(u.getPhone());
        vo.setAvatar(u.getAvatar());
        vo.setRole(u.getRole());
        return vo;
    }
}
