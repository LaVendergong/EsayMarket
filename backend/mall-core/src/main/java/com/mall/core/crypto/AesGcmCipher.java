package com.mall.core.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段的对称加密（AES-256-GCM）。
 *
 * <p><b>为什么用 GCM 而不是 ECB/CBC</b>：GCM 是带认证的加密（AEAD），除保密性外还能校验完整性 ——
 * 有人改了库里的密文，解密会直接失败，而不是悄悄解出一段错数据。ECB 还有个致命问题：
 * 相同明文得到相同密文，手机号这种低基数字段一眼就能被看出重复。
 *
 * <p><b>存储格式</b>：{@code enc:v1:<base64url(iv || ciphertext || tag)>}。
 * 带前缀是刻意的——存量数据是明文，读的时候靠前缀区分「已加密」和「历史明文」，
 * 这样上线不需要一次性停机迁移；新写入一律加密，历史行由 {@link PiiEncryptionMigrator} 补加密。
 *
 * <p><b>密钥</b>：{@code mall.crypto.secret} 用 SHA-256 派生 32 字节 AES 密钥（于是任意长度口令都能用，
 * 不要求恰好 32 字节）。⚠️ <b>密钥换了，旧密文就再也解不开</b>，轮换必须走「先用新密钥加密 + 兼容旧密钥解密」
 * 的双密钥流程，不能直接把 secret 改掉。
 */
@Slf4j
@Component
public class AesGcmCipher {

    /** 密文前缀 + 版本号：将来换算法/换密钥时靠它做兼容 */
    static final String PREFIX = "enc:v1:";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_SECRET_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** TypeHandler 由 MyBatis 实例化，拿不到 Spring 注入，所以在这里留一个静态引用 */
    private static volatile AesGcmCipher instance;

    private final SecretKey key;

    public AesGcmCipher(CryptoProperties properties) {
        String secret = properties.getSecret() == null ? "" : properties.getSecret().trim();
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "[crypto] mall.crypto.secret 至少 " + MIN_SECRET_LENGTH + " 个字符（建议 32+），当前长度 " + secret.length());
        }
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    @PostConstruct
    void register() {
        instance = this;
        log.info("[crypto] PII 字段加密已启用（AES-256-GCM，密文前缀 {}）", PREFIX);
    }

    static AesGcmCipher require() {
        AesGcmCipher cipher = instance;
        if (cipher == null) {
            throw new IllegalStateException("[crypto] 加密组件尚未初始化，无法读写加密字段");
        }
        return cipher;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密；传 null 返回 null，已经是密文的原样返回（幂等，避免迁移时二次加密）。
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        if (isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("[crypto] 字段加密失败", e);
        }
    }

    /**
     * 解密。没有前缀的按历史明文原样返回（兼容上线期未迁移的数据）。
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 最常见的原因是误改了 mall.crypto.secret。宁可报错也不要把脏数据当成正常值返回
            throw new IllegalStateException("[crypto] 字段解密失败：密钥可能已变更（见 docs §8 安全说明）", e);
        }
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
