package com.mall.core.crypto;

import com.mall.core.mapper.PiiMigrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时的两个数据加密维护动作（都幂等、双实例并发安全）：
 *
 * <ol>
 *   <li><b>补加密存量明文</b>（{@code mall.crypto.migrate-on-startup}，默认开）：
 *       字段加密上线时库里已经有明文行，只做「读时兼容明文」那些老数据会一直明文躺着，等于没加密。</li>
 *   <li><b>密钥轮换后的重加密</b>（{@code mall.crypto.re-encrypt-on-startup}，默认关）：
 *       把仍在历史密钥下的密文重新加密成当前密钥，之后就可以把 {@code previous-secrets} 清掉。
 *       它是**一次性运维动作**，平时不该每次启动全表扫。</li>
 * </ol>
 *
 * <p>安全性：每条更新都带 {@code AND <列> = 旧值} 乐观条件，两个应用同时启动也只会成功一次；
 * 日志只打「处理了多少个值」，<b>不打任何 PII</b>。
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
        if (properties.isMigrateOnStartup()) {
            migratePlaintext();
        } else {
            log.warn("[pii-migrate] 已配置关闭启动迁移（mall.crypto.migrate-on-startup=false），"
                    + "库里若还有明文 PII 将保持明文");
        }
        if (properties.isReEncryptOnStartup()) {
            reEncryptPreviousKeys();
        }
    }

    // ------------------------------------------------------------------ 1) 明文补加密

    private void migratePlaintext() {
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
            log.info("[pii-migrate] encrypted {} legacy plaintext value(s): user.phone={} address={} order={}",
                    total, users, addresses, orders);
        } else {
            log.debug("[pii-migrate] no legacy plaintext PII found");
        }
    }

    // ------------------------------------------------------------------ 2) 轮换重加密

    private void reEncryptPreviousKeys() {
        String kid = cipher.currentKeyId();
        int users = 0;
        int addresses = 0;
        int orders = 0;

        for (Map<String, Object> row : mapper.usersNotUnderKey(kid)) {
            String old = str(row.get("phone"));
            if (old != null) {
                users += mapper.updateUserPhone(num(row.get("id")), cipher.encrypt(cipher.decrypt(old)), old);
            }
        }
        for (Map<String, Object> row : mapper.addressesNotUnderKey(kid)) {
            long id = num(row.get("id"));
            String receiver = str(row.get("receiver"));
            if (receiver != null) {
                addresses += mapper.updateAddressReceiver(id, cipher.encrypt(cipher.decrypt(receiver)), receiver);
            }
            String phone = str(row.get("phone"));
            if (phone != null) {
                addresses += mapper.updateAddressPhone(id, cipher.encrypt(cipher.decrypt(phone)), phone);
            }
            String detail = str(row.get("detail"));
            if (detail != null) {
                addresses += mapper.updateAddressDetail(id, cipher.encrypt(cipher.decrypt(detail)), detail);
            }
        }
        for (Map<String, Object> row : mapper.ordersNotUnderKey(kid)) {
            long id = num(row.get("id"));
            String receiver = str(row.get("receiver"));
            if (receiver != null) {
                orders += mapper.updateOrderReceiver(id, cipher.encrypt(cipher.decrypt(receiver)), receiver);
            }
            String phone = str(row.get("receiverPhone"));
            if (phone != null) {
                orders += mapper.updateOrderReceiverPhone(id, cipher.encrypt(cipher.decrypt(phone)), phone);
            }
            String addr = str(row.get("receiverAddr"));
            if (addr != null) {
                orders += mapper.updateOrderReceiverAddr(id, cipher.encrypt(cipher.decrypt(addr)), addr);
            }
        }

        int total = users + addresses + orders;
        log.info("[pii-reencrypt] re-encrypted {} value(s) onto keyId={}: user.phone={} address={} order={} "
                        + "(现在可以清掉 mall.crypto.previous-secrets 并关闭 re-encrypt-on-startup)",
                total, kid, users, addresses, orders);
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
