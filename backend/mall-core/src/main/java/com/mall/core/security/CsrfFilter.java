package com.mall.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF 防护（双提交令牌）。
 *
 * <p><b>只在「本次请求是靠 Cookie 认证的」时候才校验</b>，这一点很关键：
 * <ul>
 *   <li>浏览器用 Cookie 认证 → 可能被跨站伪造 → 必须带 {@code X-CSRF-Token}</li>
 *   <li>脚本/服务端用 {@code Authorization: Bearer} → 攻击者站点没法让浏览器自动带上这个头
 *       → 不存在 CSRF，不需要额外校验（否则所有自动化脚本都要改）</li>
 * </ul>
 *
 * <p>登录/注册路径豁免：此时还没有登录态 Cookie，而且用户可能就是带着一个过期的旧 Cookie 来重新登录的。
 *
 * <p>为什么除了 SameSite 还要做这个：{@code SameSite=Lax} 是浏览器行为，老浏览器/某些 WebView
 * 并不遵守；双提交是在服务端强制的，不依赖浏览器。
 */
@Component
@RequiredArgsConstructor
public class CsrfFilter extends OncePerRequestFilter {

    private final AuthCookies authCookies;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!needsCheck(request)) {
            chain.doFilter(request, response);
            return;
        }
        String expected = authCookies.readCsrf(request);
        String actual = request.getHeader(AuthCookies.CSRF_HEADER);
        // 常量时间比较意义不大（这里是随机串、且要拿到 Cookie 才能猜），但保持一致习惯
        if (expected == null || actual == null || !expected.equals(actual)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"CSRF 校验失败，请刷新页面重试\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean needsCheck(HttpServletRequest request) {
        String method = request.getMethod();
        // 只读方法不改状态，放行
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        // 只有 Cookie 认证才可能被跨站伪造
        Object source = request.getAttribute(AuthCookies.ATTR_SOURCE);
        if (!AuthCookies.SOURCE_COOKIE.equals(source)) {
            return false;
        }
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/portal/auth/") && !uri.startsWith("/api/v1/admin/auth/");
    }
}
