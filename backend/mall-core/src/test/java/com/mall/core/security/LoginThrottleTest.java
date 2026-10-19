package com.mall.core.security;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.config.LoginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 登录失败限流。没有它，登录接口就是可无限爆破的——BCrypt 只让每次尝试变慢，挡不住持续轮询。
 */
class LoginThrottleTest {

    private static final String USER = "tester";

    private LoginProperties properties;
    private long now;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        properties = new LoginProperties();
        properties.setMaxFailures(3);
        properties.setLockSeconds(60);
        now = 1_000_000L;
        // 注入可控时钟：才能确定性地测「窗口过期后自动解锁」
        throttle = new LoginThrottle(properties, () -> now);
    }

    @Test
    void freshUser_isAllowed() {
        assertThatCode(() -> throttle.checkAllowed(USER)).doesNotThrowAnyException();
    }

    @Test
    void belowLimit_isStillAllowed() {
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);

        assertThatCode(() -> throttle.checkAllowed(USER)).doesNotThrowAnyException();
    }

    @Test
    void reachingLimit_blocksFurtherAttempts() {
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);

        BusinessException e = catchThrowableOfType(() -> throttle.checkAllowed(USER), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.LOGIN_TOO_MANY_ATTEMPTS.getCode());
        // 提示里带剩余秒数，用户才知道要等多久
        assertThat(e.getMessage()).contains("秒后再试");
    }

    @Test
    void reset_clearsTheCounter() {
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);

        throttle.reset(USER);

        assertThatCode(() -> throttle.checkAllowed(USER)).doesNotThrowAnyException();
    }

    @Test
    void differentUsernames_doNotAffectEachOther() {
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);

        assertThatCode(() -> throttle.checkAllowed("someone-else")).doesNotThrowAnyException();
    }

    @Test
    void windowExpiry_unlocksAutomatically_andCounterRestarts() {
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);

        now += 61_000L;

        assertThatCode(() -> throttle.checkAllowed(USER)).doesNotThrowAnyException();

        // 解锁后计数从 1 重新开始，而不是接着之前的 3
        throttle.recordFailure(USER);
        throttle.recordFailure(USER);
        assertThatCode(() -> throttle.checkAllowed(USER)).doesNotThrowAnyException();
    }

    @Test
    void blankUsername_isIgnoredInsteadOfThrowing() {
        assertThatCode(() -> {
            throttle.checkAllowed(null);
            throttle.checkAllowed("  ");
            throttle.recordFailure("");
        }).doesNotThrowAnyException();
    }
}
