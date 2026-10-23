package com.mall.core.task;

import com.mall.core.mapper.SearchLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 搜索日志清理：只保留最近 N 天。
 *
 * <p>这张表只增不删，是库里唯一会无界增长的表。热词只统计近 7 天，
 * 留 90 天足够回溯，再旧的数据只有存储成本。
 *
 * <p>最近一次执行结果记在进程内（给后台「数据维护」页展示）。重启后显示为空是正常的——
 * 为了这个再建一张表不值得。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.search-log.cleanup", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SearchLogCleanTask {

    private static final AtomicReference<LocalDateTime> LAST_RUN = new AtomicReference<>();
    private static final AtomicLong LAST_DELETED = new AtomicLong();

    private final SearchLogMapper searchLogMapper;

    @Value("${mall.search-log.retain-days:90}")
    private int retainDays;

    /** 每天凌晨 3:30，避开订单超时关单（每 10 秒）和报表高峰 */
    @Scheduled(cron = "${mall.search-log.cleanup.cron:0 30 3 * * ?}")
    public void clean() {
        try {
            LocalDateTime before = LocalDate.now().minusDays(retainDays).atStartOfDay();
            int deleted = searchLogMapper.deleteBefore(before);
            LAST_RUN.set(LocalDateTime.now());
            LAST_DELETED.set(deleted);
            if (deleted > 0) {
                log.info("[search-log] cleaned {} rows older than {} (retain {} days)", deleted, before, retainDays);
            }
        } catch (Exception e) {
            log.error("[search-log] cleanup failed", e);
        }
    }

    public static LocalDateTime lastRunAt() {
        return LAST_RUN.get();
    }

    public static long lastDeleted() {
        return LAST_DELETED.get();
    }
}
