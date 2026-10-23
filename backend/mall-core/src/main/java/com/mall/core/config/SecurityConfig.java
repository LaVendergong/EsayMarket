package com.mall.core.config;

import com.mall.core.security.AuthCookies;
import com.mall.core.security.CsrfFilter;
import com.mall.core.security.JwtAuthFilter;
import com.mall.core.web.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CsrfFilter csrfFilter;

    /** 无需登录即可访问的路径（业务接口统一在 /api/v1/** 下，见各 Controller 的 @RequestMapping） */
    private static final String[] WHITELIST = {
            "/api/v1/portal/auth/**",
            "/api/v1/admin/auth/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/favicon.ico",
            "/actuator/**"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(WHITELIST).permitAll()
                        // 商城浏览类接口匿名开放（只读），下单/购物车等仍要登录
                        .requestMatchers(HttpMethod.GET, "/api/v1/portal/category/**", "/api/v1/portal/product/**").permitAll()
                        // 商品评价列表：没登录的人看详情页也要能看到别人的评价
                        .requestMatchers(HttpMethod.GET, "/api/v1/portal/review/product/**").permitAll()
                        // 支付渠道的异步回调：渠道服务器不带我们的 token，必须匿名可达。
                        // 安全性完全依赖各渠道的**验签**（支付宝 RSA2 / 微信平台证书），验不过直接抛异常，
                        // 走不到改订单状态那一步 —— 见 PaymentServiceImpl.handleNotify。
                        .requestMatchers(HttpMethod.POST, "/api/v1/portal/pay/notify/**").permitAll()
                // 退款结果通知同理（微信有退款通知，支付宝走主动查单）
                .requestMatchers(HttpMethod.POST, "/api/v1/portal/pay/refund-notify/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // CSRF 必须排在 JwtAuthFilter **之后**：它要根据「这次请求是靠 Cookie 还是 Bearer 认证的」决定要不要校验
                .addFilterAfter(csrfFilter, JwtAuthFilter.class);
        return http.build();
    }

    /**
     * 允许跨域的来源白名单（逗号分隔）。
     *
     * <p>开发默认是两个前端的 dev 地址；生产用 {@code MALL_CORS_ALLOWED_ORIGINS} 覆盖成真实域名。
     *
     * <p>⚠️ <b>绝对不能配成 {@code "*"}</b>：本项目开着 {@code allowCredentials}，
     * 通配来源 + 携带凭证 = 任意站点都能拿用户身份调后端。prod 下
     * {@code ProductionConfigGuard} 会直接拦掉含 {@code *} 的配置。
     */
    @Value("${mall.cors.allowed-origins:http://localhost:5173,http://localhost:5174,"
            + "http://127.0.0.1:5173,http://127.0.0.1:5174}")
    private String[] allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(allowedOrigins));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id", AuthCookies.CSRF_HEADER));
        // 让前端能读到 traceId（默认只暴露 Cache-Control 等少数几个响应头）
        cfg.setExposedHeaders(List.of(TraceIdFilter.HEADER));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
