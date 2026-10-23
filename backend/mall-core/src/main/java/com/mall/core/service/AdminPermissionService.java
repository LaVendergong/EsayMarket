package com.mall.core.service;

import java.util.List;
import java.util.Set;

/**
 * 当前登录管理员的权限查询（鉴权用）。
 *
 * <p>带 60 秒进程内缓存：每个后台请求都查一次三表 JOIN 太浪费，而后台请求量本来就低。
 * 角色/权限调整后调用 {@link #evictAll()}，最多 60 秒内生效。
 */
public interface AdminPermissionService {

    /** 权限码集合；超级管理员直接返回全部权限（不依赖关联表，以后加权限点不用回补） */
    Set<String> codesOf(Long userId);

    List<String> roleCodesOf(Long userId);

    void evictAll();
}
