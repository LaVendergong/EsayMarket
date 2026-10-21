package com.mall.core.crypto;

import com.mall.core.mapper.PiiMigrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时把库里残留的**明文** PII 加密（幂等）。
 *
 * <p>为什么需要它：字段加密上线时库里已经有明文行（用户手机号、收货地址、历史订单快照）。
 * 如果只做「读时兼容明文」，这些老数据会一直明文躺着，等于没加密。
 *
 * <p>安全性：
 * <ul>
 *   <li>只处理没有 {@code enc:v1:} 前缀的值，重复执行无副作用</li>
 *   <li>每条更新都带 {@code AND <列> = 旧值} 乐观条件，两个应用同时启动也只会成功一次</li>
 *   <li>日志只打「处理了多少个值」，**不打任何 PII**</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiEncryptionMigrator implements ApplicationRunner {

    private final CryptoProperties properties;
    private final PiiMigrationMapper mapper;
    private final AesGcmCipher cipher;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isMigrateOnStartup()) {
            log.warn("[pii-migrate] 已配置关闭启动迁移（mall.crypto.migrate-on-startup=false），"
                    + "库里若还有明文 PII 将保持明文");
            return;
        }

        int users = 0;
        int addresses = 0;
        int orders = 0;

        for (Map<String, Object> row : mapper.plainUserPhones()) {
            String old = str(row.get("phone"));
            if (old != null) {
                users += mapper.updateUserPhone(num(row.get("id")), cipher.encrypt(old), old);
            }
        }

        for (Map<String, Object> row : mapper.plainAddresses()) {
            long id = num(row.get("id"));
            String receiver = str(row.get("receiver"));
            if (receiver != null) {
                addresses += mapper.updateAddressReceiver(id, cipher.encrypt(receiver), receiver);
            }
            String phone = str(row.get("phone"));
            if (phone != null) {
                addresses += mapper.updateAddressPhone(id, cipher.encrypt(phone), phone);
            }
            String detail = str(row.get("detail"));
            if (detail != null) {
                addresses += mapper.updateAddressDetail(id, cipher.encrypt(detail), detail);
            }
        }

        for (Map<String, Object> row : mapper.plainOrders()) {
            long id = num(row.get("id"));
            String receiver = str(row.get("receiver"));
            if (receiver != null) {
                orders += mapper.updateOrderReceiver(id, cipher.encrypt(receiver), receiver);
            }
            String phone = str(row.get("receiverPhone"));
            if (phone != null) {
                orders += mapper.updateOrderReceiverPhone(id, cipher.encrypt(phone), phone);
            }
            String addr = str(row.get("receiverAddr"));
            if (addr != null) {
                orders += mapper.updateOrderReceiverAddr(id, cipher.encrypt(addr), addr);
            }
        }

        int total = users + addresses + orders;
        if (total > 0) {
            // 只打数量：日志里绝不出现手机号/地址
            log.info("[pii-migrate] encrypted {} legacy plaintext value(s): user.phone={} address={} order={}",
                    total, users, addresses, orders);
        } else {
            log.debug("[pii-migrate] no legacy plaintext PII found");
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static long num(Object value) {
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
