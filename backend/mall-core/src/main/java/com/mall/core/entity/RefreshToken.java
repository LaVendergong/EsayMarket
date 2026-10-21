package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌。库里只存 {@code SHA-256(令牌)}，不存明文 —— 它等价于一个长期口令，
 * 明文入库等于把用户会话直接交出去。
 */
@Data
@TableName("t_refresh_token")
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** SHA-256 十六进制（64 字符） */
    private String tokenHash;

    private LocalDateTime expiresAt;

    /** 0-有效 1-已作废（登出，或已被轮换掉） */
    private Integer revoked;

    private LocalDateTime createTime;
}
