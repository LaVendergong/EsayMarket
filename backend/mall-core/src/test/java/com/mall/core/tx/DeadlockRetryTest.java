package com.mall.core.tx;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 死锁重试。
 *
 * <p>这段逻辑一旦写错会非常隐蔽：要么把非死锁异常也重试（放大故障），
 * 要么把死锁当业务错误抛给用户（压测里就是 24 单挂 18 单）。
 */
class DeadlockRetryTest {

    @Test
    void detectsDeadlockFromSpringException() {
        DeadlockLoserDataAccessException e =
                new DeadlockLoserDataAccessException("deadlock", new SQLException("Deadlock found"));
        assertThat(DeadlockRetry.isDeadlock(e)).isTrue();
    }

    @Test
    void detectsDeadlockFromMysqlErrorCode1213() {
        SQLException sql = new SQLException("x", "40001", 1213);
        assertThat(DeadlockRetry.isDeadlock(new RuntimeException("wrapped", sql))).isTrue();
    }

    @Test
    void detectsDeadlockFromMessageOnly() {
        assertThat(DeadlockRetry.isDeadlock(new RuntimeException("Deadlock found when trying to get lock"))).isTrue();
    }

    @Test
    void doesNotTreatBusinessExceptionAsDeadlock() {
        assertThat(DeadlockRetry.isDeadlock(new IllegalStateException("库存不足"))).isFalse();
    }

    @Test
    void retriesUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = DeadlockRetry.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new DeadlockLoserDataAccessException("deadlock", new SQLException("Deadlock found"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> DeadlockRetry.run(() -> {
            calls.incrementAndGet();
            throw new DeadlockLoserDataAccessException("deadlock", new SQLException("Deadlock found"));
        }, 3)).isInstanceOf(DeadlockLoserDataAccessException.class);

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> DeadlockRetry.run(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("业务错误");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).isEqualTo(1);
    }
}
