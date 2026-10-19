package com.mall.core.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 过滤器：它是线上排查「用户说刚才下单失败了」的唯一抓手，所以取值、透传、清理都要守住。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void generatesTraceId_visibleDuringTheChain_andCleanedUpAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> duringChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        String header = response.getHeader(TraceIdFilter.HEADER);
        assertThat(header).isNotNull().matches("[0-9a-f]{16}");
        // 请求处理过程中日志能拿到它
        assertThat(duringChain.get()).isEqualTo(header);
        // finally 里必须清理，否则线程复用会把 traceId 串到下个请求
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void keepsUpstreamTraceId_soTheWholeChainCanBeCorrelated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "upstream-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("upstream-001");
    }

    @Test
    void suspiciousUpstreamValue_isReplacedNotEchoed() throws Exception {
        // 这个值会进日志，带换行就能伪造日志行
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "abc\r\nX-Injected: evil");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String header = response.getHeader(TraceIdFilter.HEADER);
        assertThat(header).doesNotContain("X-Injected").matches("[0-9a-f]{16}");
    }

    @Test
    void overlongUpstreamValue_isReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "x".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{16}");
    }
}
