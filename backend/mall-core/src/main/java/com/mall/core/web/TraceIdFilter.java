package com.mall.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求打一个 traceId，写进 MDC（日志里可见）和响应头 {@code X-Trace-Id}（前端/网关可回传）。
 *
 * <p>为什么需要：线上出问题时，用户只会说「刚才下单失败了」。没有 traceId 时，
 * 一堆并发日志里根本挑不出他那一笔；有了 traceId 就能让用户把响应头里的值给你，一条命令捞全链路。
 *
 * <p>细节：
 * <ul>
 *   <li>上游（nginx / 网关）已经带了 {@code X-Trace-Id} 就沿用，便于跨服务串起来</li>
 *   <li>外部传进来的值会被长度与字符白名单卡住：它会进日志，不能让它带上换行之类的伪造日志内容</li>
 *   <li>放在最高优先级，连 Spring Security 的日志也带 traceId；finally 里清理 MDC，避免线程复用串号</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = normalize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 只接受「字母/数字/中划线/下划线」且长度受限的上游值，其它一律重新生成 */
    private String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH || !trimmed.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        return trimmed;
    }
}
