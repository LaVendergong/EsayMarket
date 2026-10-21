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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感字段的对称加密（AES-256-GCM）。
 *
 * <p><b>为什么用 GCM 而不是 ECB/CBC</b>：GCM 是带认证的加密（AEAD），除保密性外还能校验完整性 ——
 * 有人改了库里的密文，解密会直接失败，而不是悄悄解出一段错数据。ECB 还有个致命问题：
 * 相同明文得到相同密文，手机号这种低基数字段一眼就能被看出重复。
 *
 * <h3>存储格式</h3>
 * <pre>
 *   enc:v1:&lt;keyId&gt;:&lt;base64url(iv || ciphertext || tag)&gt;   ← 现在写入的格式
 *   enc:v1:&lt;base64url(...)&gt;                                ← 早期没有 keyId 的格式，仍可读
 * </pre>
 * 带前缀是刻意的：读的时候靠前缀区分「已加密」和「历史明文」，所以上线不需要停机迁移。
 * 带 <b>keyId</b> 是为了能做密钥轮换：一眼就能知道某行是用哪把密钥加密的，
 * 于是「哪些数据需要重加密」可以用一条 SQL 精确筛出来，不必全表解密试探。
 *
 * <h3>密钥</h3>
 * {@code mall.crypto.secret} 用 SHA-256 派生 32 字节 AES 密钥（任意长度口令都能用）。
 * {@code mall.crypto.previous-secrets} 里的历史密钥**只用于解密**，新数据一律用当前密钥。
 * 轮换步骤见 {@link CryptoProperties}。
 */
@Slf4j
@Component
public class AesGcmCipher {

    /** 密文前缀 + 版本号：将来换算法时靠它做兼容 */
    static final String PREFIX = "enc:v1:";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_SECRET_LENGTH = 16;
    private static final int KEY_ID_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** TypeHandler 由 MyBatis 实例化，拿不到 Spring 注入，所以在这里留一个静态引用 */
    private static volatile AesGcmCipher instance;

    private final SecretKey key;
    private final String keyId;
    /** 历史密钥：kid -> key，用于解密带 kid 的旧密文 */
    private final Map<String, SecretKey> previousByKeyId;
    /** 历史密钥列表，用于解密**没有 kid** 的早期密文（只能逐个试） */
    private final List<SecretKey> previousFallback;

    public AesGcmCipher(CryptoProperties properties) {
        String secret = trim(properties.getSecret());
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "[crypto] mall.crypto.secret 至少 " + MIN_SECRET_LENGTH + " 个字符（建议 32+），当前长度 " + secret.length());
        }
        this.key = derive(secret);
        this.keyId = keyIdOf(secret);

        Map<String, SecretKey> byKid = new LinkedHashMap<>();
        List<SecretKey> fallback = new ArrayList<>();
        String raw = properties.getPreviousSecrets();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String prev = trim(part);
                if (prev.isEmpty()) {
                    continue;
                }
                if (prev.length() < MIN_SECRET_LENGTH) {
                    // 短密钥解不开任何东西，只会让轮换「看起来配了但没生效」，直接拒绝
                    throw new IllegalStateException(
                            "[crypto] mall.crypto.previous-secrets 里有一个密钥短于 " + MIN_SECRET_LENGTH + " 个字符");
                }
                String kid = keyIdOf(prev);
                if (kid.equals(this.keyId)) {
                    continue; // 与当前密钥相同，忽略
                }
                byKid.put(kid, derive(prev));
                fallback.add(derive(prev));
            }
        }
        this.previousByKeyId = byKid;
        this.previousFallback = fallback;
    }

    @PostConstruct
    void register() {
        instance = this;
        log.info("[crypto] PII 字段加密已启用（AES-256-GCM，当前 keyId={}，可用于解密的历史密钥 {} 个）",
                keyId, previousByKeyId.size());
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

    /** 当前密钥的标识（密文里会带上它） */
    public String currentKeyId() {
        return keyId;
    }

    /** 这段密文是不是用当前密钥加的（用于「重加密」时筛数据） */
    public boolean isUnderCurrentKey(String stored) {
        if (!isEncrypted(stored)) {
            return false;
        }
        String rest = stored.substring(PREFIX.length());
        int sep = rest.indexOf(':');
        return sep > 0 && rest.substring(0, sep).equals(keyId);
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
            return PREFIX + keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("[crypto] 字段加密失败", e);
        }
    }

    /**
     * 解密。没有前缀的按历史明文原样返回（兼容上线期未迁移的数据）。
     *
     * <p>找不到对应密钥时会**抛错**而不是返回空/脏数据 —— 密文解不开是严重问题，
     * 静默返回 null 会让上层把它当成"字段是空的"继续跑下去。
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!isEncrypted(stored)) {
            return stored;
        }
        String rest = stored.substring(PREFIX.length());
        int sep = rest.indexOf(':');
        // base64url 不含 ':'，所以「有冒号」一定是新格式（kid:payload）
        if (sep > 0) {
            String kid = rest.substring(0, sep);
            SecretKey target = kid.equals(keyId) ? key : previousByKeyId.get(kid);
            if (target == null) {
                throw new IllegalStateException("[crypto] 找不到 keyId=" + kid
                        + " 对应的密钥：请把它加到 mall.crypto.previous-secrets 再启动");
            }
            return decryptWith(target, rest.substring(sep + 1));
        }
        // 早期没有 kid 的格式：先试当前密钥，再逐个试历史密钥
        try {
            return decryptWith(key, rest);
        } catch (Exception ignored) {
            // 继续试历史密钥
        }
        for (SecretKey candidate : previousFallback) {
            try {
                return decryptWith(candidate, rest);
            } catch (Exception ignored) {
                // 继续试下一把
            }
        }
        throw new IllegalStateException("[crypto] 字段解密失败：密钥不匹配，且没有任何历史密钥能解开");
    }

    private String decryptWith(SecretKey candidate, String payload) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload);
            if (bytes.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(bytes, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, candidate, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("[crypto] 字段解密失败", e);
        }
    }

    private static SecretKey derive(String secret) {
        return new SecretKeySpec(sha256(secret), "AES");
    }

    /** 密钥标识 = SHA-256(secret) 的前 8 个 hex 字符（只作识别用，不泄露密钥） */
    private static String keyIdOf(String secret) {
        byte[] digest = sha256(secret);
        StringBuilder sb = new StringBuilder(KEY_ID_LENGTH);
        for (int i = 0; i < KEY_ID_LENGTH / 2; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
