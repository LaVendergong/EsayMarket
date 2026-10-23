package com.mall.core.tenant;

/**
 * 当前请求的租户（商户）上下文。
 *
 * <p>由 {@code JwtAuthFilter} 设置与清理：
 * <ul>
 *   <li>前台（H5）：取请求头 {@code X-Tenant-Id}，缺省 1 号「默认店铺」；</li>
 *   <li>后台：取 JWT 里的 {@code tenantId}（管理员绑定的商户），**覆盖**请求头 ——
 *       否则商户管理员改个 header 就能看别家的数据；</li>
 *   <li>平台方（tenantId=0）：租户插件对这类请求不做任何过滤，可以跨店看。</li>
 * </ul>
 */
public final class TenantContext {

    /** 默认店铺：升级前的所有数据都归它 */
    public static final long DEFAULT_TENANT = 1L;

    /** 平台方：跨租户，跳过租户过滤 */
    public static final long PLATFORM = 0L;

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        TENANT.set(tenantId);
    }

    public static Long get() {
        return TENANT.get();
    }

    /** 当前租户，未设置时按默认店铺处理（避免忘了设置就查出全库） */
    public static long current() {
        Long t = TENANT.get();
        return t == null ? DEFAULT_TENANT : t;
    }

    /** 平台方：跨租户视角 */
    public static boolean isPlatform() {
        return current() == PLATFORM;
    }

    public static void clear() {
        TENANT.remove();
    }
}
