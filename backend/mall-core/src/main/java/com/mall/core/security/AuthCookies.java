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
 * <p>三个 Cookie：
 * <ul>
 *   <li>{@code mall_portal_token} / {@code mall_admin_token}：**access** JWT，HttpOnly，15 分钟</li>
 *   <li>{@code mall_portal_refresh} / {@code mall_admin_refresh}：**refresh** 令牌，HttpOnly，7 天，
 *       只能用来换新的 access（换一次旧的立即作废，见 {@link RefreshTokenService}）</li>
 *   <li>{@code mall_csrf}：CSRF 令牌，**故意不是 HttpOnly** —— 前端必须读得到，
 *       再放进请求头 {@code X-CSRF-Token}，服务端比对两者（双提交）</li>
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
    private final JwtProperties jwtProperties;

    @Value("${spring.application.name:mall-portal}")
    private String appName;

    private boolean isAdminApp() {
        return appName != null && appName.contains("admin");
    }

    /** access token 的 Cookie 名 */
    public String tokenCookieName() {
        return isAdminApp() ? properties.getAdminName() : properties.getPortalName();
    }

    /** refresh token 的 Cookie 名 */
    public String refreshCookieName() {
        return isAdminApp() ? properties.getAdminRefreshName() : properties.getPortalRefreshName();
    }

    /**
     * 登录/刷新成功后下发三个 Cookie。
     *
     * <p>access 的 Max-Age 就是它的有效期（15 分钟），refresh 是 7 天 ——
     * 这样浏览器自己就会把过期的 access 丢掉，不必等一次 401 才发现。
     */
    public void issue(HttpServletResponse response, String accessToken, String refreshToken) {
        add(response, tokenCookieName(), accessToken, true, jwtProperties.getAccessExpireMillis() / 1000);
        add(response, refreshCookieName(), refreshToken, true, jwtProperties.getRefreshExpireMillis() / 1000);
        add(response, CSRF_COOKIE, UUID.randomUUID().toString().replace("-", ""), false,
                jwtProperties.getRefreshExpireMillis() / 1000);
    }

    /** 退出登录：三个 Cookie 全部置空并立即过期 */
    public void clear(HttpServletResponse response) {
        add(response, tokenCookieName(), "", true, 0);
        add(response, refreshCookieName(), "", true, 0);
        add(response, CSRF_COOKIE, "", false, 0);
    }

    /** 从 Cookie 里取 access JWT（没有就返回 null） */
    public String resolveToken(HttpServletRequest request) {
        return read(request, tokenCookieName());
    }

    /** 从 Cookie 里取 refresh 令牌（没有就返回 null） */
    public String resolveRefresh(HttpServletRequest request) {
        return read(request, refreshCookieName());
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

    private void add(HttpServletResponse response, String name, String value, boolean httpOnly, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(httpOnly)
                .secure(properties.isSecure())
                .path("/")
                .maxAge(maxAgeSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(maxAgeSeconds))
                .sameSite(properties.getSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
