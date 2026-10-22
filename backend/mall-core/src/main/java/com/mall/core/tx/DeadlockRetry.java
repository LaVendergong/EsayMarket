package com.mall.core.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * 死锁重试。
 *
 * <p>为什么必须有它：高并发下多个事务按不同顺序锁同一批行，MySQL 会主动 kill 掉其中一个
 * （`Deadlock found when trying to get lock; try restarting transaction`）。
 * 这**不是 bug，是数据库的正常并发控制**，正确做法就是重试 —— 把 500 抛给用户才是 bug。
 *
 * <p>实测背景：24 个买家并发抢 8 件库存时，24 次下单里有 18 次 500（全是死锁），
 * 加上重试后降为 0（见 scripts/load-test.mjs）。
 *
 * <p>两个前提：
 * <ul>
 *   <li>被重试的方法必须是**幂等**的（下单靠 requestId 幂等键兜底）</li>
 *   <li>重试必须发生在**事务之外** —— 事务已经回滚了，在事务内重试没有意义</li>
 * </ul>
 */
@Slf4j
public final class DeadlockRetry {

    private static final int DEFAULT_ATTEMPTS = 5;

    private DeadlockRetry() {
    }

    public static <T> T run(Supplier<T> action) {
        return run(action, DEFAULT_ATTEMPTS);
    }

    public static <T> T run(Supplier<T> action, int maxAttempts) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isDeadlock(e) || attempt == maxAttempts) {
                    throw e;
                }
                last = e;
                // 退避带一点随机，避免几个事务又同时撞在一起
                long backoff = 15L * attempt + (long) (Math.random() * 15);
                log.warn("[tx] deadlock detected, retrying ({}/{}): {}", attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IllegalStateException("deadlock retry failed") : last;
    }

    /** 沿 cause 链判断是不是死锁（Spring 会包一层 DeadlockLoserDataAccessException） */
    public static boolean isDeadlock(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            if (cur instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (cur instanceof SQLException sql && sql.getErrorCode() == 1213) {
                return true;   // MySQL ER_LOCK_DEADLOCK
            }
            String msg = cur.getMessage();
            if (msg != null && msg.contains("Deadlock found")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
