package com.mall.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "长度需在 4-32 之间")
    private String username;

    /**
     * 密码策略：8-64 位，且必须同时包含字母和数字。
     *
     * <p>为什么要卡复杂度：纯数字/纯字母的短口令在撞库面前几乎等于没有密码，
     * 而注册接口是公开的 —— 密码太弱，前面所有的 BCrypt、限流都白做。
     * 服务层还会额外拒绝超过 72 **字节** 的密码（BCrypt 只取前 72 字节，超出部分等于没设）。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "长度需在 8-64 之间")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    private String password;

    private String nickname;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
