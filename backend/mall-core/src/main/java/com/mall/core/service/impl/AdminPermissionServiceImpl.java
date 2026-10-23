package com.mall.core.service.impl;

import com.mall.core.mapper.AdminRbacMapper;
import com.mall.core.service.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AdminPermissionServiceImpl implements AdminPermissionService {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final long TTL_MILLIS = 60_000L;

    private final AdminRbacMapper rbacMapper;

    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Set<String> codes, List<String> roles, long expireAt) {
        boolean alive() {
            return System.currentTimeMillis() < expireAt;
        }
    }

    @Override
    public Set<String> codesOf(Long userId) {
        return load(userId).codes();
    }

    @Override
    public List<String> roleCodesOf(Long userId) {
        return load(userId).roles();
    }

    @Override
    public void evictAll() {
        cache.clear();
    }

    private Cached load(Long userId) {
        if (userId == null) {
            return new Cached(Set.of(), List.of(), Long.MAX_VALUE);
        }
        Cached hit = cache.get(userId);
        if (hit != null && hit.alive()) {
            return hit;
        }
        List<String> roles = rbacMapper.roleCodesOf(userId);
        // 超级管理员短路：拥有全部权限码，新增权限点不用回来补关联
        Set<String> codes = roles.contains(SUPER_ADMIN)
                ? new HashSet<>(rbacMapper.allPermissionCodes())
                : new HashSet<>(rbacMapper.permissionCodesOf(userId));
        Cached fresh = new Cached(codes, roles, System.currentTimeMillis() + TTL_MILLIS);
        cache.put(userId, fresh);
        return fresh;
    }
}
