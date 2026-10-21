package com.mall.core.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.RefreshToken;
import com.mall.core.mapper.RefreshTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 刷新令牌的签发 / 轮换 / 吊销。
 *
 * <p><b>为什么要 refresh token</b>：access token 如果有效期很长，一旦泄露就长期有效；
 * 如果很短，用户就得频繁重新登录。标准解法是「短命 access + 长寿但可吊销的 refresh」：
 * <ul>
 *   <li>access 只活 15 分钟 → 泄露窗口从 7 天压到 15 分钟</li>
 *   <li>refresh 存在库里（**只存哈希**）→ 可以主动吊销、异常时可全量失效</li>
 *   <li>refresh **用一次就作废并换新的**（轮换）→ 被偷走的旧 refresh 再用会失败</li>
 * </ul>
 *
 * <p>令牌本身是 32 字节随机数（base64url），不携带任何信息；与 JWT 无关，所以不怕被解出 claims。
 *
 * <p><b>关于更新写法</b>：这里用「patch 实体 + 条件 wrapper」，而不是
 * {@code lambdaUpdate().set(RefreshToken::getRevoked, 1)} —— 后者会在**构建 wrapper 时**
 * 立刻解析列名（需要 MyBatis-Plus 的 TableInfo 缓存），纯单元测试里没有缓存会直接抛
 * {@code can not find lambda cache for this entity}。{@code .eq()} 是延迟解析的，所以这种写法
 * 在生产与单测里表现一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtProperties jwtProperties;

    /** 给某个用户签发一个新的刷新令牌，返回**明文**（只在这一刻存在于内存/响应里） */
    public String issue(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken row = new RefreshToken();
        row.setUserId(userId);
        row.setTokenHash(sha256Hex(raw));
        row.setExpiresAt(LocalDateTime.now().plusSeconds(jwtProperties.getRefreshExpireMillis() / 1000));
        row.setRevoked(0);
        row.setCreateTime(LocalDateTime.now());
        refreshTokenMapper.insert(row);
        return raw;
    }

    /**
     * 校验并**消费**（轮换）：成功返回 userId，同时把这一条标记为已作废。
     *
     * <p>用「带 revoked=0 条件的 UPDATE + 判断影响行数」保证同一个 refresh 只能被用一次 ——
     * 两个并发请求同时拿同一个 refresh 来换，只有一个能成功（另一个 0 行 -> 401）。
     */
    public long consume(String raw) {
        Long userId = userIdOf(raw);
        int updated = refreshTokenMapper.update(revokedPatch(), Wrappers.<RefreshToken>lambdaUpdate()
                .eq(RefreshToken::getTokenHash, sha256Hex(raw))
                .eq(RefreshToken::getRevoked, 0)
                .gt(RefreshToken::getExpiresAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return userId;
    }

    /** 吊销一个刷新令牌（登出）。找不到/已作废都当成功处理，避免登出报错烦人 */
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        refreshTokenMapper.update(revokedPatch(), Wrappers.<RefreshToken>lambdaUpdate()
                .eq(RefreshToken::getTokenHash, sha256Hex(raw))
                .eq(RefreshToken::getRevoked, 0));
    }

    /** 清掉某个用户所有刷新令牌（改密码 / 发现异常时用） */
    public int revokeAll(long userId) {
        return refreshTokenMapper.update(revokedPatch(), Wrappers.<RefreshToken>lambdaUpdate()
                .eq(RefreshToken::getUserId, userId)
                .eq(RefreshToken::getRevoked, 0));
    }

    /** 物理清理过期行（保留一天便于排查） */
    public int purgeExpired() {
        return refreshTokenMapper.delete(Wrappers.<RefreshToken>lambdaQuery()
                .lt(RefreshToken::getExpiresAt, LocalDateTime.now().minusDays(1)));
    }

    private Long userIdOf(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        RefreshToken row = refreshTokenMapper.selectOne(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getTokenHash, sha256Hex(raw)));
        if (row == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return row.getUserId();
    }

    /** 只带 revoked=1 的 patch 实体：MyBatis-Plus 只更新非 null 字段 */
    private static RefreshToken revokedPatch() {
        RefreshToken patch = new RefreshToken();
        patch.setRevoked(1);
        return patch;
    }

    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
