package com.mall.core.security;

import com.mall.common.util.JwtUtil;
import com.mall.core.service.AdminPermissionService;
import com.mall.core.tenant.TenantContext;
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
    private final AdminPermissionService adminPermissionService;

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

        // 租户：前台靠请求头（默认 1 号店），后台以 token 为准（下面会覆盖）
        long tenantId = resolveTenantFromHeader(request);

        if (token != null) {
            request.setAttribute(AuthCookies.ATTR_SOURCE, source);
            try {
                Claims claims = JwtUtil.parse(jwtProperties.getSecret(), token);
                Long userId = Long.valueOf(claims.getSubject());
                Integer role = claims.get("role", Integer.class);
                Integer tenantClaim = claims.get("tenantId", Integer.class);
                if (tenantClaim != null && role != null && role == 1) {
                    // 管理员改个 header 就能看别家？不行，后台一律以 token 里的租户为准。
                    // 只认管理员（role=1）的 claim：买家没有租户概念，别让 0 变成「平台视角」
                    tenantId = tenantClaim;
                }
                UserContext.set(userId, role);
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                if (role != null && role == 1) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    // 后台权限码也塞进 authorities，接口上用 @PreAuthorize("hasAuthority('order:ship')")
                    try {
                        adminPermissionService.codesOf(userId)
                                .forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
                    } catch (Exception e) {
                        // 权限加载失败就只保留 ROLE_ADMIN：宁可让 @PreAuthorize 拒绝，也不能放行
                        logger.warn("加载后台权限失败 userId=" + userId + ": " + e.getMessage());
                    }
                } else {
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                }
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                logger.debug("JWT 解析失败: " + e.getMessage());
                UserContext.clear();
                SecurityContextHolder.clearContext();
            }
        }
        TenantContext.set(tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    /**
     * 前台传 {@code X-Tenant-Id} 指定进哪家店。
     *
     * <p>只接受正整数：0 是「平台方」标识，前端不许通过请求头把自己变成平台视角。
     */
    private static long resolveTenantFromHeader(HttpServletRequest request) {
        String raw = request.getHeader("X-Tenant-Id");
        if (raw == null || raw.isBlank()) {
            return TenantContext.DEFAULT_TENANT;
        }
        try {
            long t = Long.parseLong(raw.trim());
            return t > 0 ? t : TenantContext.DEFAULT_TENANT;
        } catch (NumberFormatException e) {
            return TenantContext.DEFAULT_TENANT;
        }
    }
}
