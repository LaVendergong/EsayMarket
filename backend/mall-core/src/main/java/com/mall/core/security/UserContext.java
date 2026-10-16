package com.mall.core.security;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;

/**
 * 当前登录用户上下文（ThreadLocal，由 JwtAuthFilter 填充与清理）
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> ROLE = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId, Integer role) {
        USER_ID.set(userId);
        ROLE.set(role);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static Integer getRole() {
        return ROLE.get();
    }

    /** 取当前用户 ID，未登录直接抛 401 */
    public static Long requireUserId() {
        Long id = USER_ID.get();
        if (id == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return id;
    }

    public static boolean isAdmin() {
        Integer role = ROLE.get();
        return role != null && role == 1;
    }

    public static void clear() {
        USER_ID.remove();
        ROLE.remove();
    }
}
