package com.mall.core.security;

import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析凭证并填充 SecurityContext 与 UserContext。
 *
 * <p>凭证来源有两种，按优先级：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} —— 自动化脚本、服务间调用</li>
 *   <li>{@code HttpOnly Cookie} —— 浏览器（前端登录后由服务端下发，JS 读不到）</li>
 * </ol>
 * 来源会被记到请求属性 {@link AuthCookies#ATTR_SOURCE} 上，
 * 供 {@link CsrfFilter} 决定「要不要校验 CSRF」（只有 Cookie 认证才需要）。
 *
 * <p>解析失败不阻断请求，交由后续授权规则处理（匿名访问受保护资源 -> 401）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtProperties jwtProperties;
    private final AuthCookies authCookies;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        String source = null;

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            token = header.substring(PREFIX.length());
            source = AuthCookies.SOURCE_HEADER;
        } else {
            String fromCookie = authCookies.resolveToken(request);
            if (fromCookie != null) {
                token = fromCookie;
                source = AuthCookies.SOURCE_COOKIE;
            }
        }

        if (token != null) {
            request.setAttribute(AuthCookies.ATTR_SOURCE, source);
            try {
                Claims claims = JwtUtil.parse(jwtProperties.getSecret(), token);
                Long userId = Long.valueOf(claims.getSubject());
                Integer role = claims.get("role", Integer.class);
                UserContext.set(userId, role);
                String authority = (role != null && role == 1) ? "ROLE_ADMIN" : "ROLE_USER";
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority(authority)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                logger.debug("JWT 解析失败: " + e.getMessage());
                UserContext.clear();
                SecurityContextHolder.clearContext();
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
