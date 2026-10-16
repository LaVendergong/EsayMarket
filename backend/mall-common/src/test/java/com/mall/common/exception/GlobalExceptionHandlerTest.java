package com.mall.common.exception;

import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常 → 响应码的映射。
 *
 * <p>重点守住一条：**客户端的错不能返回 5xx**。之前「请求体是畸形 JSON」会落到 Exception 兜底里变成
 * code 500，把客户端错误算进服务端错误率、误导告警——这是实测踩出来的（见 docs §7 P1 章节）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessException_keepsItsOwnCode() {
        R<Void> r = handler.handleBusiness(new BusinessException(ResultCode.STOCK_NOT_ENOUGH));

        assertThat(r.getCode()).isEqualTo(ResultCode.STOCK_NOT_ENOUGH.getCode());
    }

    @Test
    void malformedRequestBody_isAClientError400() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));

        R<Void> r = handler.handleNotReadable(e);

        assertThat(r.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
    }

    @Test
    void missingRequestParam_isAClientError400() {
        R<Void> r = handler.handleMissingParam(new MissingServletRequestParameterException("pageNum", "int"));

        assertThat(r.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
    }

    @Test
    void unsupportedMethod_isReally405() {
        ResponseEntity<R<Void>> resp = handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ResultCode.METHOD_NOT_ALLOWED.getCode());
    }

    @Test
    void unknownException_staysServerError500() {
        R<Void> r = handler.handleOther(new IllegalStateException("boom"));

        assertThat(r.getCode()).isEqualTo(ResultCode.FAILED.getCode());
    }
}
