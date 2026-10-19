package com.mall.core.security;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.config.LoginProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 登录失败限流：同一用户名在窗口内失败达到上限后，直接拒绝（连密码都不校验），到窗口过期自动解锁。
 *
 * <p><b>为什么需要</b>：没有它，登录接口就是可无限次爆破的——BCrypt 只让每次尝试变慢，挡不住持续轮询。
 *
 * <p><b>几个刻意的取舍</b>：
 * <ul>
 *   <li>按**用户名**计数，不按 IP：本项目没有可信的客户端 IP（生产走 nginx 反代，remoteAddr 是代理地址），
 *       拿不到可信 IP 时按 IP 计数反而会把所有用户算成一伙。副作用是「知道用户名就能把人锁住」，
 *       所以窗口故意设得短（默认 5 分钟），宁可让攻击者少尝试也不要长期误伤真实用户。</li>
 *   <li>计数放在**本进程内存**里：单实例足够，重启即清零；将来多副本部署需要换成 Redis 之类的共享存储，
 *       否则每个副本各算各的（等于把上限放大到 副本数 × maxFailures）。</li>
 *   <li>用户名不存在时**也计入失败**，否则错误信息的差异会变成可枚举用户名的探针。</li>
 * </ul>
 */
@Slf4j
@Component
public class LoginThrottle {

    /** 条目超过这个数量时顺手清理过期项，避免被大量随机用户名撑大内存 */
    private static final int PRUNE_THRESHOLD = 1000;

    private final LoginProperties properties;
    /** 可注入的时钟，便于测试「窗口过期后自动解锁」；生产用系统时钟 */
    private final LongSupplier clock;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginThrottle(LoginProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    LoginThrottle(LoginProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 校验是否还允许尝试。已超限则抛 {@link ResultCode#LOGIN_TOO_MANY_ATTEMPTS}。
     */
    public void checkAllowed(String username) {
        String key = key(username);
        if (key == null) {
            return;
        }
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        long now = clock.getAsLong();
        if (isExpired(attempt, now)) {
            attempts.remove(key, attempt);
            return;
        }
        if (attempt.failures >= properties.getMaxFailures()) {
            long remainSeconds = Math.max(1, (attempt.windowStartAt + windowMillis() - now + 999) / 1000);
            log.warn("[login-throttle] 拒绝尝试: username={} failures={} 还需等待 {}s", key, attempt.failures, remainSeconds);
            throw new BusinessException(ResultCode.LOGIN_TOO_MANY_ATTEMPTS,
                    "登录失败次数过多，请 " + remainSeconds + " 秒后再试");
        }
    }

    /** 记录一次失败（用户名不存在也算） */
    public void recordFailure(String username) {
        String key = key(username);
        if (key == null) {
            return;
        }
        long now = clock.getAsLong();
        attempts.compute(key, (k, old) -> {
            if (old == null || isExpired(old, now)) {
                return new Attempt(1, now);
            }
            old.failures++;
            return old;
        });
        if (attempts.size() > PRUNE_THRESHOLD) {
            pruneExpired(now);
        }
    }

    /** 登录成功：清掉这个用户名的失败计数 */
    public void reset(String username) {
        String key = key(username);
        if (key != null) {
            attempts.remove(key);
        }
    }

    private void pruneExpired(long now) {
        attempts.entrySet().removeIf(e -> isExpired(e.getValue(), now));
    }

    private boolean isExpired(Attempt attempt, long now) {
        return now - attempt.windowStartAt > windowMillis();
    }

    private long windowMillis() {
        return properties.getLockSeconds() * 1000L;
    }

    private String key(String username) {
        return StringUtils.hasText(username) ? username.trim() : null;
    }

    private static final class Attempt {
        private int failures;
        private final long windowStartAt;

        private Attempt(int failures, long windowStartAt) {
            this.failures = failures;
            this.windowStartAt = windowStartAt;
        }
    }
}
