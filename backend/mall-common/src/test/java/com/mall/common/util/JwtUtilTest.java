package com.mall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 是整套鉴权的根：这里守住三件事——签发/解析能对上、换密钥解不开、过期与篡改会被拒。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "another-test-secret-0123456789abcdef0123456789";

    @Test
    void create_then_parse_keeps_subject_and_claims() {
        String token = JwtUtil.create(SECRET, 60_000L, Map.of("role", 1, "username", "malladmin"), "42");

        Claims claims = JwtUtil.parse(SECRET, token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(((Number) claims.get("role")).intValue()).isEqualTo(1);
        assertThat(claims.get("username")).isEqualTo("malladmin");
    }

    @Test
    void parse_with_another_secret_is_rejected() {
        String token = JwtUtil.create(SECRET, 60_000L, Map.of(), "1");

        assertThatThrownBy(() -> JwtUtil.parse(OTHER_SECRET, token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expired_token_is_rejected() {
        String token = JwtUtil.create(SECRET, -1_000L, Map.of(), "1");

        assertThatThrownBy(() -> JwtUtil.parse(SECRET, token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tampered_token_is_rejected() {
        String token = JwtUtil.create(SECRET, 60_000L, Map.of("role", 0), "1");
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> JwtUtil.parse(SECRET, tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void too_short_secret_is_rejected_instead_of_silently_downgraded() {
        // HS256 要求密钥 >= 256 bit；太短必须直接报错，不能悄悄用一个弱密钥签发票据
        assertThatThrownBy(() -> JwtUtil.create("too-short", 60_000L, Map.of(), "1"))
                .isInstanceOf(JwtException.class);
    }
}
