package com.mall.core.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录态 Cookie 的配置（有效期不在这里，见 {@link JwtProperties} 的 access / refresh TTL）。
 *
 * <p>为什么从 localStorage 迁到 Cookie：localStorage 里的 token **任何 XSS 都能读走**，
 * 而且读走之后可以离线用满整个有效期。改成 {@code HttpOnly} Cookie 后 JS 根本拿不到它，
 * XSS 想"偷 token"这条路就断了。
 *
 * <p>名字都是**显式配置**而不是靠字符串拼接（比如 token 名加个后缀）——
 * 拼出来的名字不可预测，排查/写测试时很容易对不上。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.security.cookie")
public class AuthCookieProperties {

    /** 门户（H5）的 access Cookie 名。两个应用用不同的名字：localhost 下 5173/5174 的 Cookie 是共享的（Cookie 不区分端口） */
    private String portalName = "mall_portal_token";

    /** 门户的 refresh Cookie 名 */
    private String portalRefreshName = "mall_portal_refresh";

    /** 管理后台的 access Cookie 名 */
    private String adminName = "mall_admin_token";

    /** 管理后台的 refresh Cookie 名 */
    private String adminRefreshName = "mall_admin_refresh";

    /** 是否只在 HTTPS 下发送。dev 是 http，必须为 false；**prod 必须为 true**（启动自检会拦） */
    private boolean secure = false;

    /** SameSite：Lax 已经能挡掉绝大多数跨站 POST，配合 CSRF 双提交形成两层 */
    private String sameSite = "Lax";
}
