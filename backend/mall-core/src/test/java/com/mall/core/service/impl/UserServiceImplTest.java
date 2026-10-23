package com.mall.core.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.User;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.vo.LoginVO;
import com.mall.core.security.JwtProperties;
import com.mall.core.security.LoginThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录：除了签发 token，重点是**失败要计数、成功要清计数、被限流时不能碰数据库**。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtProperties jwtProperties;
    @Mock private LoginThrottle loginThrottle;
    @Mock private com.mall.core.service.AdminPermissionService adminPermissionService;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userMapper, passwordEncoder, jwtProperties, loginThrottle,
                adminPermissionService);
    }

    private User activeUser() {
        User u = new User();
        u.setId(7L);
        u.setUsername("tester");
        u.setPassword("hashed");
        u.setNickname("Tester");
        u.setRole(0);
        u.setStatus(1);
        return u;
    }

    private LoginDTO dto(String username, String password) {
        LoginDTO d = new LoginDTO();
        d.setUsername(username);
        d.setPassword(password);
        return d;
    }

    @Test
    void throttled_bailsOutBeforeTouchingTheDatabase() {
        doThrow(new BusinessException(ResultCode.LOGIN_TOO_MANY_ATTEMPTS))
                .when(loginThrottle).checkAllowed("tester");

        BusinessException e = catchThrowableOfType(() -> service.login(dto("tester", "whatever")), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.LOGIN_TOO_MANY_ATTEMPTS.getCode());
        // 关键：已限流就不该再查库、再跑 BCrypt（否则限流反而成了压测入口）
        verifyNoInteractions(userMapper);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void unknownUsername_countsAsFailure_andLooksLikeAWrongPassword() {
        when(userMapper.selectOne(any())).thenReturn(null);

        BusinessException e = catchThrowableOfType(() -> service.login(dto("nobody", "x")), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.PASSWORD_ERROR.getCode());
        // 不能暴露「用户不存在」，否则错误信息差异就是用户名枚举探针
        assertThat(e.getMessage()).isEqualTo(ResultCode.PASSWORD_ERROR.getMessage());
        verify(loginThrottle).recordFailure("nobody");
    }

    @Test
    void wrongPassword_countsAsFailure_andDoesNotReset() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(passwordEncoder.matches("bad", "hashed")).thenReturn(false);

        BusinessException e = catchThrowableOfType(() -> service.login(dto("tester", "bad")), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.PASSWORD_ERROR.getCode());
        verify(loginThrottle).recordFailure("tester");
        verify(loginThrottle, never()).reset(anyString());
    }

    @Test
    void successfulLogin_resetsTheFailureCounter_andReturnsAToken() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(passwordEncoder.matches("good", "hashed")).thenReturn(true);
        when(jwtProperties.getSecret()).thenReturn("unit-test-secret-0123456789abcdef0123456789abcdef");
        when(jwtProperties.getAccessExpireMillis()).thenReturn(900_000L);

        LoginVO vo = service.login(dto("tester", "good"));

        assertThat(vo.getToken()).isNotBlank();
        assertThat(vo.getUserId()).isEqualTo(7L);
        assertThat(vo.getRole()).isZero();
        verify(loginThrottle).reset("tester");
        verify(loginThrottle, never()).recordFailure(anyString());
    }

    @Test
    void disabledAccount_doesNotResetTheCounter() {
        User disabled = activeUser();
        disabled.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(disabled);
        when(passwordEncoder.matches("good", "hashed")).thenReturn(true);

        BusinessException e = catchThrowableOfType(() -> service.login(dto("tester", "good")), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.ACCOUNT_DISABLED.getCode());
        // 禁用账号不该顺手把别人的失败计数清掉
        verify(loginThrottle, never()).reset(anyString());
    }
}
