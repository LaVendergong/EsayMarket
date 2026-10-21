package com.mall.core.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 登录态 Cookie 的签发 / 清除 / 读取，以及 CSRF 双提交令牌。
 *
 * <p>两个 Cookie：
 * <ul>
 *   <li>{@code mall_portal_token} / {@code mall_admin_token}：JWT，<b>HttpOnly</b>，JS 读不到</li>
 *   <li>{@code mall_csrf}：CSRF 令牌，<b>故意不是 HttpOnly</b> —— 前端必须能读出来，
 *       再放进请求头 {@code X-CSRF-Token}，服务端比对两者是否一致（双提交）</li>
 * </ul>
 *
 * <p>为什么要 CSRF：Cookie 是浏览器自动携带的，攻击者站点只要能让浏览器发一个跨站 POST，
 * 就会"带着用户身份"打到我们的接口。{@code SameSite=Lax} 能挡掉大部分，
 * 但双提交是标准做法，且对老浏览器也有效。
 */
@Component
@RequiredArgsConstructor
public class AuthCookies {

    public static final String CSRF_COOKIE = "mall_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    /** 请求属性：本次请求的凭证来源（header / cookie），供 CSRF 过滤器判断 */
    public static final String ATTR_SOURCE = "mall.auth.source";
    public static final String SOURCE_HEADER = "header";
    public static final String SOURCE_COOKIE = "cookie";

    private final AuthCookieProperties properties;

    @Value("${spring.application.name:mall-portal}")
    private String appName;

    /** 管理后台用 admin 那个名字，其余按门户处理 */
    public String tokenCookieName() {
        return appName != null && appName.contains("admin") ? properties.getAdminName() : properties.getPortalName();
    }

    /** 登录成功后下发两个 Cookie */
    public void issue(HttpServletResponse response, String token) {
        add(response, tokenCookieName(), token, true);
        add(response, CSRF_COOKIE, UUID.randomUUID().toString().replace("-", ""), false);
    }

    /** 退出登录：把两个 Cookie 都置空并立即过期 */
    public void clear(HttpServletResponse response) {
        add(response, tokenCookieName(), "", true);
        add(response, CSRF_COOKIE, "", false);
    }

    /** 从 Cookie 里取 JWT（没有就返回 null） */
    public String resolveToken(HttpServletRequest request) {
        return read(request, tokenCookieName());
    }

    public String readCsrf(HttpServletRequest request) {
        return read(request, CSRF_COOKIE);
    }

    private String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private void add(HttpServletResponse response, String name, String value, boolean httpOnly) {
        ResponseCookie cookie = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(httpOnly)
                .secure(properties.isSecure())
                .path("/")
                .maxAge(value == null || value.isEmpty() ? Duration.ZERO : Duration.ofSeconds(properties.getMaxAgeSeconds()))
                .sameSite(properties.getSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
