package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.dto.RegisterDTO;
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

@Tag(name = "认证")
@RestController
@RequestMapping("/api/v1/portal/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthCookies authCookies;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public R<Long> register(@Valid @RequestBody RegisterDTO dto) {
        return R.ok(userService.register(dto));
    }

    /**
     * 登录。
     *
     * <p>登录态用 <b>HttpOnly Cookie</b> 下发（JS 读不到，XSS 偷不走）；
     * 响应体里**仍然返回 token**，是给自动化脚本和服务端调用用的（它们走 Bearer 头，
     * 不存在 CSRF 问题）。浏览器端忽略响应体里的 token 即可。
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletResponse response) {
        LoginVO vo = userService.login(dto);
        authCookies.issue(response, vo.getToken());
        return R.ok(vo);
    }

    /** 退出登录：Cookie 是 HttpOnly 的，前端删不掉，必须由服务端清除。 */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletResponse response) {
        authCookies.clear(response);
        return R.ok(null);
    }
}
