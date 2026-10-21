package com.mall.admin.controller;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.vo.LoginVO;
import com.mall.core.security.AuthCookies;
import com.mall.core.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台认证")
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthCookies authCookies;

    /**
     * 后台登录（仅管理员）。
     *
     * <p>先把角色校验做完**再下发 Cookie** —— 否则非管理员也会拿到一个（虽然没权限、
     * 但仍然是有效登录态）的 Cookie。
     */
    @Operation(summary = "后台登录（仅管理员）")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletResponse response) {
        LoginVO vo = userService.login(dto);
        if (vo.getRole() == null || vo.getRole() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非管理员账号，无法登录后台");
        }
        authCookies.issue(response, vo.getToken());
        return R.ok(vo);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletResponse response) {
        authCookies.clear(response);
        return R.ok(null);
    }
}
