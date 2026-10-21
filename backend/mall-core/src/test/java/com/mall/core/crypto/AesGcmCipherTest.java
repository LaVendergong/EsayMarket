package com.mall.core.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字段加密的密钥轮换语义必须逐条守住 —— 这里错了不是「功能不对」，
 * 而是**库里已有的数据再也解不开**。
 */
class AesGcmCipherTest {

    private static final String OLD_SECRET = "unit-test-old-crypto-secret-0123456789";
    private static final String NEW_SECRET = "unit-test-new-crypto-secret-abcdefgh";

    private AesGcmCipher cipher(String secret, String previous) {
        CryptoProperties props = new CryptoProperties();
        props.setSecret(secret);
        props.setPreviousSecrets(previous);
        return new AesGcmCipher(props);
    }

    @Test
    void encrypt_prefixesKeyIdSoRotationCanFindTheData() {
        AesGcmCipher c = cipher(OLD_SECRET, null);
        String enc = c.encrypt("13800138000");

        assertThat(enc).startsWith(AesGcmCipher.PREFIX + c.currentKeyId() + ":");
        assertThat(c.isUnderCurrentKey(enc)).isTrue();
        assertThat(c.decrypt(enc)).isEqualTo("13800138000");
    }

    @Test
    void plaintextIsReturnedAsIs_soMigrationCanBeGradual() {
        AesGcmCipher c = cipher(OLD_SECRET, null);
        assertThat(c.decrypt("13800138000")).isEqualTo("13800138000");
        assertThat(c.isEncrypted("13800138000")).isFalse();
    }

    @Test
    void encryptIsIdempotent_soMigrationNeverDoubleWraps() {
        AesGcmCipher c = cipher(OLD_SECRET, null);
        String once = c.encrypt("abc");
        assertThat(c.encrypt(once)).isEqualTo(once);
    }

    @Test
    void rotatedCipher_canStillDecryptWithPreviousSecret() {
        String encryptedWithOld = cipher(OLD_SECRET, null).encrypt("收货人张三");
        AesGcmCipher rotated = cipher(NEW_SECRET, OLD_SECRET);

        assertThat(rotated.decrypt(encryptedWithOld)).isEqualTo("收货人张三");
        // 新写入必须用新密钥
        String encryptedWithNew = rotated.encrypt("收货人张三");
        assertThat(encryptedWithNew).startsWith(AesGcmCipher.PREFIX + rotated.currentKeyId() + ":");
        assertThat(rotated.isUnderCurrentKey(encryptedWithOld)).isFalse();
        assertThat(rotated.isUnderCurrentKey(encryptedWithNew)).isTrue();
    }

    @Test
    void droppingPreviousSecret_makesOldDataUnreadable_loudly() {
        String encryptedWithOld = cipher(OLD_SECRET, null).encrypt("13800138000");
        AesGcmCipher onlyNew = cipher(NEW_SECRET, null);

        // 必须抛错，而不是返回 null / 空串 / 脏数据 —— 静默失败会让上层把它当成「字段是空的」
        assertThatThrownBy(() -> onlyNew.decrypt(encryptedWithOld))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous-secrets");
    }

    @Test
    void legacyCiphertextWithoutKeyId_isStillReadableViaFallback() {
        // 模拟早期格式：enc:v1:<payload>（没有 kid）
        AesGcmCipher old = cipher(OLD_SECRET, null);
        String withKid = old.encrypt("13900000000");
        String legacy = AesGcmCipher.PREFIX + withKid.substring((AesGcmCipher.PREFIX + old.currentKeyId() + ":").length());

        assertThat(cipher(NEW_SECRET, OLD_SECRET).decrypt(legacy)).isEqualTo("13900000000");
        // 只配新密钥（不带历史）时，老格式解不开
        assertThatThrownBy(() -> cipher(NEW_SECRET, null).decrypt(legacy)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void samePlaintextProducesDifferentCiphertext_randomIvNotEcb() {
        AesGcmCipher c = cipher(OLD_SECRET, null);
        assertThat(c.encrypt("13800138000")).isNotEqualTo(c.encrypt("13800138000"));
    }

    @Test
    void tooShortSecretIsRejectedAtStartup() {
        CryptoProperties props = new CryptoProperties();
        props.setSecret("short");
        assertThatThrownBy(() -> new AesGcmCipher(props)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.crypto.secret");
    }

    @Test
    void tooShortPreviousSecretIsRejected_soRotationCannotSilentlyDoNothing() {
        CryptoProperties props = new CryptoProperties();
        props.setSecret(NEW_SECRET);
        props.setPreviousSecrets("tiny");
        assertThatThrownBy(() -> new AesGcmCipher(props)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous-secrets");
    }
}
