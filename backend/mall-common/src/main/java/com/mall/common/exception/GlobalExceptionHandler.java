package com.mall.common.exception;

import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        return R.fail(ResultCode.VALIDATE_FAILED.getCode(), firstFieldMessage(e.getBindingResult().getFieldError()));
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException e) {
        return R.fail(ResultCode.VALIDATE_FAILED.getCode(), firstFieldMessage(e.getBindingResult().getFieldError()));
    }

    /** 缺少必填参数 / 参数类型不匹配：按参数校验失败处理（400），不要兜底成 500 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public R<Void> handleMissingParam(Exception e) {
        log.warn("参数异常: {}", e.getMessage());
        return R.fail(ResultCode.VALIDATE_FAILED.getCode(), e.getMessage());
    }

    /**
     * 请求体读不出来（畸形 JSON、类型对不上等）：这是**客户端的错**，必须按 400 返回。
     * 之前落到下面的 Exception 兜底里变成 500，会把客户端错误算进服务端错误率、误导告警。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return R.fail(ResultCode.VALIDATE_FAILED.getCode(), "请求体格式不正确");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ResultCode.NOT_FOUND));
    }

    /** 方法不支持：返回真正的 HTTP 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(ResultCode.METHOD_NOT_ALLOWED.getCode(),
                        ResultCode.METHOD_NOT_ALLOWED.getMessage() + ": " + e.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return R.fail(ResultCode.FAILED);
    }

    private String firstFieldMessage(FieldError fe) {
        if (fe == null) {
            return ResultCode.VALIDATE_FAILED.getMessage();
        }
        return fe.getField() + " " + fe.getDefaultMessage();
    }
}
