package com.mall.core.security;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.RefreshToken;
import com.mall.core.mapper.RefreshTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新令牌的语义守则。这里错了的后果是「登出之后还能继续登入」或「同一个 refresh 能被反复使用」，
 * 都是实打实的安全问题。
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenMapper refreshTokenMapper;
    @Mock private JwtProperties jwtProperties;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(refreshTokenMapper, jwtProperties);
    }

    @Test
    void issue_storesOnlyAHash_neverThePlaintext() {
        when(jwtProperties.getRefreshExpireMillis()).thenReturn(604_800_000L);

        String raw = service.issue(7L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).insert(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(raw).isNotBlank().doesNotContain("=");
        assertThat(saved.getTokenHash()).isEqualTo(RefreshTokenService.sha256Hex(raw));
        assertThat(saved.getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getRevoked()).isZero();
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
    }

    @Test
    void consume_rotatesTheToken_andReturnsTheOwner() {
        RefreshToken row = new RefreshToken();
        row.setId(1L);
        row.setUserId(42L);
        row.setRevoked(0);
        row.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(row);
        when(refreshTokenMapper.update(any(), any())).thenReturn(1);

        assertThat(service.consume("some-raw-token")).isEqualTo(42L);
    }

    @Test
    void consume_failsWhenTheTokenWasAlreadyUsed() {
        RefreshToken row = new RefreshToken();
        row.setId(1L);
        row.setUserId(42L);
        row.setRevoked(0);
        row.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(row);
        // 条件 UPDATE（revoked=0）影响 0 行 = 已经被别人用掉了
        when(refreshTokenMapper.update(any(), any())).thenReturn(0);

        BusinessException e = catchThrowableOfType(() -> service.consume("replayed"), BusinessException.class);
        assertThat(e.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    void consume_failsOnUnknownOrEmptyToken() {
        assertThatThrownBy(() -> service.consume(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.consume("  ")).isInstanceOf(BusinessException.class);
        when(refreshTokenMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.consume("nope")).isInstanceOf(BusinessException.class);
    }

    @Test
    void revoke_isIdempotentAndToleratesMissingToken() {
        service.revoke(null);
        service.revoke("  ");
        verify(refreshTokenMapper, never()).update(any(), any());

        service.revoke("raw");
        verify(refreshTokenMapper).update(any(), any());
    }

    @Test
    void revokeAll_touchesEveryActiveTokenOfTheUser() {
        when(refreshTokenMapper.update(any(), any())).thenReturn(3);

        assertThat(service.revokeAll(9L)).isEqualTo(3);
    }

    @Test
    void sha256_isStableAndHex() {
        assertThat(RefreshTokenService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static <T extends Throwable> T catchThrowableOfType(ThrowingCallable callable, Class<T> type) {
        try {
            callable.call();
        } catch (Throwable t) {
            assertThat(type.isInstance(t)).as("期望抛 %s，实际抛 %s", type.getSimpleName(), t.getClass().getSimpleName()).isTrue();
            return type.cast(t);
        }
        throw new AssertionError("期望抛出 " + type.getSimpleName() + "，但什么都没抛");
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}
