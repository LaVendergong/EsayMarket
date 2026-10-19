package com.mall.core.config;

import com.mall.core.security.JwtAuthFilter;
import com.mall.core.web.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

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
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
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
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        // 让前端能读到 traceId（默认只暴露 Cache-Control 等少数几个响应头）
        cfg.setExposedHeaders(List.of(TraceIdFilter.HEADER));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
