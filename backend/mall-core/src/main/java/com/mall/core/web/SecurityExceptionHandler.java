package com.mall.core.web;

import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 权限不足的统一处理。
 *
 * <p>放在 core 而不是 common：{@code AccessDeniedException} 来自 spring-security，
 * 而 mall-common 是纯通用模块，不该为了一个异常处理器引入安全框架依赖。
 *
 * <p>不接的话会被 {@code GlobalExceptionHandler} 的兜底分支吞成 500，
 * 前端只提示"服务器错误"，排查时看不出是权限问题。
 */
@Slf4j
@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return R.fail(ResultCode.FORBIDDEN.getCode(), "没有操作权限");
    }
}
