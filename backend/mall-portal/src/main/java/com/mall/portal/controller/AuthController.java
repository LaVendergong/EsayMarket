package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.dto.RegisterDTO;
import com.mall.core.model.vo.LoginVO;
import com.mall.core.security.AuthCookies;
import com.mall.core.security.RefreshTokenService;
import com.mall.core.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final RefreshTokenService refreshTokenService;
    private final AuthCookies authCookies;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public R<Long> register(@Valid @RequestBody RegisterDTO dto) {
        return R.ok(userService.register(dto));
    }

    /**
     * 登录。
     *
     * <p>登录态用两个 **HttpOnly Cookie** 下发：短命的 access（15 分钟）+ 长命但可吊销的 refresh（7 天）。
     * 响应体里**仍然返回 access token**，是给自动化脚本和服务端调用用的（它们走 Bearer 头）。
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletResponse response) {
        LoginVO vo = userService.login(dto);
        authCookies.issue(response, vo.getToken(), refreshTokenService.issue(vo.getUserId()));
        return R.ok(vo);
    }

    /**
     * 用 refresh 换新的 access（浏览器 access 过期后自动调这个）。
     *
     * <p>refresh **用一次即换新**：旧的那条在同一事务语义下被标记作废。
     * 所以即使 refresh 被偷，攻击者用了一次之后，真实用户下次刷新会失败 —— 这反而是个信号。
     */
    @Operation(summary = "刷新登录态")
    @PostMapping("/refresh")
    public R<LoginVO> refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = authCookies.resolveRefresh(request);
        long userId = refreshTokenService.consume(raw);
        LoginVO vo = userService.refreshLogin(userId);
        authCookies.issue(response, vo.getToken(), refreshTokenService.issue(userId));
        return R.ok(vo);
    }

    /** 退出登录：吊销 refresh（服务端库里那一行）+ 清掉三个 Cookie */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        refreshTokenService.revoke(authCookies.resolveRefresh(request));
        authCookies.clear(response);
        return R.ok(null);
    }
}
